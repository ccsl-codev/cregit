package cregit.blobexec

import org.eclipse.jgit.lib.Constants.{OBJ_BLOB, OBJ_COMMIT}
import org.eclipse.jgit.lib._
import org.eclipse.jgit.revwalk.{RevCommit, RevSort, RevTag, RevWalk}
import org.eclipse.jgit.treewalk.{CanonicalTreeParser, TreeWalk}

import java.util.concurrent.{ArrayBlockingQueue, BlockingQueue, ConcurrentHashMap, Executors, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference, AtomicReferenceArray, LongAdder}
import scala.collection.immutable.{Map => IMap}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.matching.Regex

final case class WalkStats(
    commitsProcessed: Int,
    commitsAlreadyMapped: Int,
    blobsRunThroughCommand: Int,
    blobsCacheHit: Int,
    refsProjected: Int,
    aborted: Boolean,
    /** Blobs whose command was killed for exceeding its budget. Each one is a
      * file left holding raw source instead of tokens, so a non-zero count must
      * reach the caller rather than living only in stderr. */
    blobsTimedOut: Long,
    /** Distinct mask-matched blobs excluded because JGit will not materialise
      * them AND their own header identifies them as machine-generated (see
      * [[Walker.generatedEvidence]]). The provenance is the reason, which is why
      * this is no longer called `blobsOversized`: the size only triggers the
      * check, it does not justify the exclusion. Explained and deterministic, so
      * like [[blobsDenylisted]] and unlike [[blobsTimedOut]] it is reported but
      * does not block publication. */
    blobsGeneratedExcluded: Long,
    /** Distinct mask-matched blobs excluded because they are on the shipped blob
      * denylist ([[BlobDenylist]]): srcML 1.1.0 does not terminate on them, the
      * defect is diagnosed and cited, and excluding them is deterministic. Like
      * [[blobsGeneratedExcluded]] and unlike [[blobsTimedOut]] this is reported
      * but does not block publication. */
    blobsDenylisted: Long,
    /** Blobs whose tokenizer reported [[BlobExec.ParserCrashExitCode]]: srcML died
      * on a signal, or the token stream came back empty. Like [[blobsTimedOut]]
      * and unlike [[blobsGeneratedExcluded]]/[[blobsDenylisted]] this DOES block
      * publication, because an unexplained parser death is exactly the defect that
      * used to be written out as a silent 0-byte tokenization. It is a separate
      * counter rather than more timeouts because the two need different fixes: a
      * timeout wants --blob-timeout, a crash wants the blob denylisting or srcML
      * fixing. Defaulted so that adding it did not have to touch callers that
      * construct [[WalkStats]] for other reasons. */
    blobsParserCrashed: Long = 0L,
    /** Distinct mask-matched blobs JGit will not materialise and that nothing
      * identifies as generated: on the evidence available, large HAND-WRITTEN
      * source files. Kept apart from [[blobsGeneratedExcluded]] precisely so that
      * the defensible exclusion and the indefensible one can never be read as one
      * number. Like [[blobsTimedOut]] this DOES block publication: an exclusion
      * whose reason cannot be stated is not a finding, it is a hole. Defaulted so
      * adding it did not have to touch callers that construct [[WalkStats]] for
      * other reasons. */
    blobsUntokenizable: Long = 0L,
    blobCommandExecutions: Long,
    originalBlobCopyRequests: Long,
    originalBlobCopies: Long,
    originalBlobAlreadyPresent: Long,
    originalBlobCacheHits: Long,
    originalBlobDestinationLookups: Long,
    originalBlobBytesCopied: Long,
    originalBlobBytesAvoided: Long
)

/** Rebuild `src` history into `dst`, persisting mappings to `mapping`.
  * Writes are confined to the jgit inserter/refs, `Mapping`, and the blob pool. */
final class Walker(
    src: Repository,
    dst: Repository,
    mapping: Mapping,
    fileMask: Regex,
    command: String,
    abortOnError: Boolean,
    parallelism: Int,
    pipeline: Boolean = false,
    pipelineTrees: Boolean = false,
    shard: Option[(Int, Int)] = None,
    deduplicateOriginalBlobs: Boolean = true,
    destinationMayContainObjects: Boolean = true,
    blobTimeoutSeconds: Int = BlobExec.DefaultTimeoutSeconds,
    stallTimeoutSeconds: Int = Walker.DefaultStallTimeoutSeconds,
    // The shipped list by default, so a caller cannot forget it and hand a known
    // non-terminating blob to srcml. A parameter only so a test can supply its
    // own fixture; nothing at run time chooses a different list.
    denylist: BlobDenylist = BlobDenylist.shipped
) {
  import Walker._

  // Serializes `mapping` access (one non-thread-safe JDBC connection): the
  // pipelined producer reads while the consumer writes. Tokenizer workers never
  // touch it, so the CPU-heavy path stays lock-free.
  private val dbLock = new AnyRef

  private val blobCommandExecutions          = new LongAdder
  private val blobsTimedOut                  = new LongAdder
  private val blobsGeneratedExcluded         = new LongAdder
  private val blobsUntokenizable             = new LongAdder
  private val blobsDenylisted                = new LongAdder
  private val blobsParserCrashed             = new LongAdder
  private val originalBlobCopyRequests       = new LongAdder
  private val originalBlobCopies             = new LongAdder
  private val originalBlobAlreadyPresent     = new LongAdder
  private val originalBlobCacheHits          = new LongAdder
  private val originalBlobDestinationLookups = new LongAdder
  private val originalBlobBytesCopied        = new LongAdder
  private val originalBlobBytesAvoided       = new LongAdder

  // -- stall watchdog ------------------------------------------------------
  //
  // The consumer's `Await.result` calls are unbounded again, and deliberately.
  // A duration budget cannot tell a wedged run from an honestly large one: the
  // predecessor of this watchdog computed 3,806,400s for tencentkona-21's
  // first-import commit and clamped to 30 days, so on the very run that
  // motivated this work it would never have fired. Instead of predicting how
  // long honest work takes, assert that work is *happening*: every blob, tree,
  // commit and original-blob copy stamps `lastProgressNanos`, and if nothing at
  // all completes within `stallTimeoutSeconds` the watchdog kills the process.
  // Safe because GNU `timeout` hard-bounds every child at `blobTimeoutSeconds`,
  // so a healthy run always completes *something* well inside the window.

  private val lastProgressNanos = new AtomicLong(System.nanoTime())
  private val lastProgressWhat  = new AtomicReference[String]("startup")

  /** Stamp forward progress. Called on every unit of work that can complete, so
    * "no progress" means the run is genuinely stuck rather than merely busy. */
  private def progress(what: => String): Unit = {
    lastProgressNanos.lazySet(System.nanoTime())
    lastProgressWhat.lazySet(what)
  }

  /** Blob tokenizations currently in flight, for the watchdog's diagnosis: this
    * is the list that names the wedged blob when a stall is reported. */
  private val inFlightBlobs = new ConcurrentHashMap[String, java.lang.Long]()

  private def stalled(): Option[Long] = {
    val last = lastProgressNanos.get()
    val now  = System.nanoTime()
    if (Walker.isStalled(now, last, stallTimeoutSeconds)) Some(now - last) else None
  }

  private def watchdogReport(stalledNanos: Long): String = {
    val inFlight = inFlightBlobs.asScala.toVector
      .sortBy(_._2.longValue())
      .map { case (what, since) =>
        s"      $what (running ${(System.nanoTime() - since) / 1000000000L}s)"
      }
    (Vector(
      s"blobExec: STALLED: no progress for ${stalledNanos / 1000000000L}s " +
        s"(limit ${stallTimeoutSeconds}s). Last completed work: ${lastProgressWhat.get()}.",
      s"    blobs in flight: ${inFlight.size}"
    ) ++ inFlight.take(16) ++ Vector(
      s"    Killing the process with status ${Walker.StalledExitStatus} rather than waiting: the " +
        "memo is durable, so a resuming run picks up from here (from the pipeline, resume at " +
        "step 2 — step 1 deletes the work directory)."
    )).mkString("\n")
  }

  /** Force-kill every process this JVM still has beneath it, and return how many
    * were signalled. `ProcessHandle` sees the whole descendant tree, so this
    * reaches the grandchildren that `Process.destroy()` cannot. */
  private def killDescendants(): Int = {
    var killed = 0
    ProcessHandle.current().descendants().iterator().asScala.foreach { h =>
      if (h.destroyForcibly()) killed += 1
    }
    killed
  }

  /** Run `body` under the stall watchdog. The watchdog is a daemon so it can
    * never keep the JVM alive, and it halts rather than exiting: a stalled run
    * may well have a shutdown hook that would block on the same wedged thread,
    * and an exit path that can hang is not a fix for a hang. */
  private def withStallWatchdog[A](body: => A): A = {
    val done = new AtomicBoolean(false)
    val tick = math.max(1L, math.min(30L, math.max(1, stallTimeoutSeconds).toLong / 4L))
    val watchdog = new Thread(
      () => {
        while (!done.get()) {
          try Thread.sleep(tick * 1000L)
          catch { case _: InterruptedException => Thread.currentThread().interrupt() }
          if (!done.get()) stalled().foreach { stalledNanos =>
            System.err.println(watchdogReport(stalledNanos))
            // Halting leaves nothing behind to reap the children, and an
            // orphaned srcml tree is what survived its parent by 51 hours in
            // the incident this work comes from. Kill the whole descendant
            // tree first — `timeout`, the shell, the tokenizer, all of it.
            val killed = killDescendants()
            System.err.println(s"blobExec: killed $killed leftover child process(es) before exiting")
            System.err.flush()
            System.out.flush()
            Runtime.getRuntime.halt(Walker.StalledExitStatus)
          }
        }
      },
      "blobexec-stall-watchdog"
    )
    watchdog.setDaemon(true)
    watchdog.start()
    try body
    finally {
      done.set(true)
      watchdog.interrupt()
    }
  }

  private val originalBlobCache = new AtomicReferenceArray[OriginalBlobCacheEntry](OriginalBlobCacheSize)
  private val originalBlobCopyLocks = Array.fill[AnyRef](OriginalBlobCopyLockCount)(new AnyRef)

  // -- entry point ---------------------------------------------------------

  def run(): WalkStats = withStallWatchdog {
    shard match {
      case Some((k, n)) => runShard(k, n)
      case None         => runFull()
    }
  }

  /** Full history rewrite: walk every not-yet-mapped commit, fold the commit
    * graph, and project refs. This is the canonical single-process path (also
    * used, unchanged, as the merge re-fold over a warmed tree_map). */
  private def runFull(): WalkStats = {
    val revWalk = new RevWalk(src)
    revWalk.sort(RevSort.TOPO, true)
    revWalk.sort(RevSort.REVERSE, true)

    try {
      markUninteresting(revWalk)
      markStartRefs(revWalk)

      val (commitsProcessed, blobsRun, blobsHit, aborted) =
        if (pipelineTrees) walkCommitsTrees(revWalk)
        else if (pipeline) walkCommitsPipelined(revWalk)
        else walkCommits(revWalk)
      val refsCount =
        if (aborted) 0
        else projectRefs(revWalk)

      WalkStats(
        commitsProcessed       = commitsProcessed,
        commitsAlreadyMapped   = 0,  // we don't double-count: only walked ones are reported
        blobsRunThroughCommand = blobsRun,
        blobsCacheHit          = blobsHit,
        refsProjected          = refsCount,
        aborted                = aborted,
        blobsTimedOut          = blobsTimedOut.sum(),
        blobsGeneratedExcluded = blobsGeneratedExcluded.sum(),
        blobsUntokenizable     = blobsUntokenizable.sum(),
        blobsDenylisted        = blobsDenylisted.sum(),
        blobsParserCrashed     = blobsParserCrashed.sum(),
        blobCommandExecutions       = blobCommandExecutions.sum(),
        originalBlobCopyRequests    = originalBlobCopyRequests.sum(),
        originalBlobCopies          = originalBlobCopies.sum(),
        originalBlobAlreadyPresent  = originalBlobAlreadyPresent.sum(),
        originalBlobCacheHits       = originalBlobCacheHits.sum(),
        originalBlobDestinationLookups = originalBlobDestinationLookups.sum(),
        originalBlobBytesCopied     = originalBlobBytesCopied.sum(),
        originalBlobBytesAvoided    = originalBlobBytesAvoided.sum()
      )
    } finally revWalk.close()
  }

  /** Tree-only history shard (Tier 3): for shard `k` of `n` contiguous
    * TOPO+REVERSE ranges, tokenize + persist each commit's tree (blob_map/tree_map
    * incl. subtree ids) but resolve no parents/commit/refs, so shards are independent.
    * A later serial [[runFull]] re-fold unions the shards and rebuilds commit_map
    * byte-identically -- content-addressed ids mean the shard boundary can't affect output. */
  private def runShard(k: Int, n: Int): WalkStats = {
    val ordered = enumerateCommits()
    val m  = ordered.size
    // Contiguous, exhaustive, non-overlapping integer partition:
    //   shard k owns [floor(k*m/n), floor((k+1)*m/n)).
    val lo = ((k.toLong * m) / n).toInt
    val hi = (((k + 1).toLong * m) / n).toInt
    val slice = ordered.slice(lo, hi)
    println(s"blobExec shard $k/$n: totalCommits=$m range=[$lo,$hi) sliceSize=${slice.size}")

    val (commits, blobsRun, blobsHit, aborted) = walkShard(slice)
    WalkStats(
      commitsProcessed       = commits,
      commitsAlreadyMapped   = 0,
      blobsRunThroughCommand = blobsRun,
      blobsCacheHit          = blobsHit,
      refsProjected          = 0,     // shards deliberately never project refs
      aborted                = aborted,
      blobsTimedOut          = blobsTimedOut.sum(),
      blobsGeneratedExcluded = blobsGeneratedExcluded.sum(),
      blobsUntokenizable     = blobsUntokenizable.sum(),
      blobsDenylisted        = blobsDenylisted.sum(),
      blobsParserCrashed     = blobsParserCrashed.sum(),
      blobCommandExecutions       = blobCommandExecutions.sum(),
      originalBlobCopyRequests    = originalBlobCopyRequests.sum(),
      originalBlobCopies          = originalBlobCopies.sum(),
      originalBlobAlreadyPresent  = originalBlobAlreadyPresent.sum(),
      originalBlobCacheHits       = originalBlobCacheHits.sum(),
      originalBlobDestinationLookups = originalBlobDestinationLookups.sum(),
      originalBlobBytesCopied     = originalBlobBytesCopied.sum(),
      originalBlobBytesAvoided    = originalBlobBytesAvoided.sum()
    )
  }

  /** The canonical ordered commit enumeration used to partition shards. Same
    * config as the full walk's RevWalk (TOPO+REVERSE, all ref tips), minus the
    * commit_map frontier (a tree-only shard never writes commit_map, and the
    * partition must cover the whole history). Bodies are disposed as we go so
    * only the id vector is retained. */
  private def enumerateCommits(): Vector[ObjectId] = {
    val rw = new RevWalk(src)
    rw.sort(RevSort.TOPO, true)
    rw.sort(RevSort.REVERSE, true)
    try {
      markStartRefs(rw)
      val b  = Vector.newBuilder[ObjectId]
      val it = rw.iterator()
      while (it.hasNext) {
        val rc = it.next()
        b += rc.getId
        rc.disposeBody()
        progress(s"enumerate ${rc.getId.name}")
      }
      b.result()
    } finally rw.close()
  }

  /** Process one shard's slice of commits, tree-only. Modeled on
    * [[walkCommits]] with the commit fold removed: no parent resolution, no
    * buildCommit, no putCommit, no ref projection. Returns
    * (commitsProcessed, blobsRun, blobsHit, aborted). */
  private def walkShard(sliceIds: Vector[ObjectId]): (Int, Int, Int, Boolean) = {
    val pool = Executors.newFixedThreadPool(parallelism)
    implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(pool)
    val treeInserter = dst.newObjectInserter()
    val procWalk     = new RevWalk(src)

    var aborted  = false
    var timedOut = false
    var commits  = 0
    var blobsRun = 0
    var blobsHit = 0

    try {
      val it = sliceIds.iterator
      while (it.hasNext && !aborted && !timedOut) {
        val rc = procWalk.parseCommit(it.next())
        val origTreeId = rc.getTree.getId

        // Pure plan for this commit's tree (short-circuits on a tree_map hit,
        // incl. the optional --warm fallback).
        val (plan, artifacts) = buildTreePlan(origTreeId, pathPrefix = "")
        val misses = resolveMisses(artifacts.misses, pool)
        val resolved = misses.ids

        if (misses.abort) {
          aborted = true
        } else if (misses.timedOutKeys.nonEmpty) {
          // Same rule as the full walk: no tree rows for a commit that carries
          // raw source, so the next shard run retries the blob.
          timedOut = true
          persistRetryableBlobs(misses, rc.getId.name)
        } else {
          val subtreeMap = scala.collection.mutable.Map.empty[String, String]
          val newTreeId  = assemble(plan, resolved, treeInserter, subtreeMap)

          mapping.inTx {
            resolved.foreach { case ((origSha, path), newId) =>
              mapping.putBlob(origSha, path, newId.name)
            }
            artifacts.unchangedBlobs.foreach { case (bid, path) =>
              mapping.putBlob(bid.name, path, bid.name)
            }
            mapping.putTree(origTreeId.name, newTreeId.name)
            subtreeMap.foreach { case (origId, newId) => mapping.putTree(origId, newId) }
            // No putCommit: the shard omits the parent-dependent hash-chain fold.
          }

          commits  += 1
          blobsRun += artifacts.misses.size
          blobsHit += artifacts.hitCount
          progress(s"shard commit ${rc.getId.name}")
        }
        rc.disposeBody()
      }

      treeInserter.flush()
    } finally {
      treeInserter.close()
      procWalk.close()
      pool.shutdown()
      val _ = pool.awaitTermination(1, TimeUnit.MINUTES)
    }

    (commits, blobsRun, blobsHit, aborted)
  }

  /** Persist only what a commit containing a timed-out blob may leave behind.
    *
    * Three durable writes each independently hide a timeout from the next run:
    * the blob's own `blob_map` row (a `getBlob` hit), any `tree_map` row on the
    * path above it (a `getTree` hit short-circuits the *entire* subtree, so the
    * re-run never reaches the blob), and the commit's `commit_map` row (which
    * marks the commit done and is what `markUninteresting` walks). All three are
    * suppressed. The blobs that tokenized correctly are content-addressed and
    * already in dst, so they are kept — otherwise every retry would re-tokenize
    * a whole commit to get at one blob. Identity rows for unmasked blobs are
    * *not* kept: their bytes are copied during tree assembly, which we skip
    * here, and a row without the bytes would leave dst inconsistent.
    */
  private def persistRetryableBlobs(misses: MissResolution, origCommitSha: String): Unit = {
    val keep = misses.persistable
    dbLock.synchronized {
      mapping.inTx {
        keep.foreach { case ((origSha, path), newId) => mapping.putBlob(origSha, path, newId.name) }
      }
    }
    System.err.println(
      s"blobExec: commit $origCommitSha contains ${misses.timedOutKeys.size} timed-out blob(s): " +
        s"kept ${keep.size} good blob row(s), recorded no tree and no commit for it. " +
        "Another run over this memo retries just those blobs (from the pipeline: resume at " +
        "step 2, never step 1); nothing needs clearing by hand."
    )
    misses.timedOutKeys.toVector.sorted.foreach { case (sha, path) =>
      System.err.println(s"blobExec:   will retry on the next run: $sha ($path)")
    }
  }

  // -- ref preparation -----------------------------------------------------

  /** For every commit sha already in `commit_map` that still exists in src,
    * mark it uninteresting so the walk skips it and its ancestors. */
  private def markUninteresting(revWalk: RevWalk): Unit = {
    mapping.allCommitOrigShas.foreach { sha =>
      val id = ObjectId.fromString(sha)
      // Scanning a large memo's frontier happens before any blob runs, and on a
      // long history it is not fast; it must not look like a stall.
      progress(s"frontier $sha")
      try {
        val rc = revWalk.parseCommit(id)
        revWalk.markUninteresting(rc)
      } catch {
        case _: org.eclipse.jgit.errors.MissingObjectException => ()
        case _: org.eclipse.jgit.errors.IncorrectObjectTypeException => ()
      }
    }
  }

  /** Add every commit reachable from a ref tip as a walk start point. */
  private def markStartRefs(revWalk: RevWalk): Unit = {
    src.getRefDatabase.getRefs.asScala.foreach { ref =>
      val id = Option(ref.getObjectId)
      id.foreach { oid =>
        try {
          val obj = revWalk.parseAny(oid)
          val commit = obj match {
            case t: RevTag    => peelToCommit(t, revWalk).orNull
            case c: RevCommit => c
            case _            => null
          }
          if (commit ne null) revWalk.markStart(commit)
        } catch {
          case _: org.eclipse.jgit.errors.MissingObjectException => ()
          case _: org.eclipse.jgit.errors.IncorrectObjectTypeException => ()
        }
      }
    }
  }

  // -- commit loop ---------------------------------------------------------

  /** Returns (commitsProcessed, blobsRun, blobsHit, aborted). */
  private def walkCommits(revWalk: RevWalk): (Int, Int, Int, Boolean) = {
    val pool = Executors.newFixedThreadPool(parallelism)
    implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(pool)
    val treeInserter   = dst.newObjectInserter()
    val commitInserter = dst.newObjectInserter()

    var aborted     = false       // local mutation across the walk loop is unavoidable
    var timedOut    = false       // a blob in this commit was killed on its budget
    var commits     = 0
    var blobsRun    = 0
    var blobsHit    = 0

    try {
      val iter = revWalk.iterator()
      while (iter.hasNext && !aborted && !timedOut) {
        val rc = iter.next()
        val origCommitSha = rc.getId.name

        // Build a pure plan for this commit's tree.
        val (plan, artifacts) = buildTreePlan(rc.getTree.getId, pathPrefix = "")

        // Run all matching-blob misses in parallel.
        val misses = resolveMisses(artifacts.misses, pool)
        val resolved = misses.ids

        if (misses.abort) {
          aborted = true
        } else if (misses.timedOutKeys.nonEmpty) {
          // Retryable failure: keep the tokenizations that succeeded (they are
          // content-addressed and already in dst), but persist no tree and no
          // commit for this one. Recording any of those three would make the
          // next run skip straight past the raw-source substitution. Stop here
          // so no descendant commit is folded onto a parent we did not record.
          timedOut = true
          persistRetryableBlobs(misses, origCommitSha)
        } else {
          // Assemble the new tree id (bottom-up). `subtreeMap` collects every
          // freshly built (sub)tree's orig->new id so the transaction below
          // can persist them into tree_map (keystone: lets an unchanged
          // subtree short-circuit on a later commit instead of re-walking).
          val subtreeMap = scala.collection.mutable.Map.empty[String, String]
          val newTreeId = assemble(plan, resolved, treeInserter, subtreeMap)

          // Record the resolved blobs and the top-level tree mapping.
          val parents = rc.getParents.toVector.map { p =>
            val pn = p.getId.name
            mapping.getCommit(pn).getOrElse(
              throw new IllegalStateException(s"parent commit $pn missing from commit_map while processing $origCommitSha")
            )
          }.map(ObjectId.fromString)

          val newCommit = buildCommit(rc, parents, newTreeId, commitInserter)

          mapping.inTx {
            // Matching blobs the cmd resolved (Replace or Skip outcomes).
            resolved.foreach { case ((origSha, path), newId) =>
              mapping.putBlob(origSha, path, newId.name)
            }
            // Identity rows for non-matching blobs we walked through.
            // INSERT OR IGNORE keeps the original processed_at if a prior
            // commit already recorded the same (orig_blob, path).
            artifacts.unchangedBlobs.foreach { case (id, path) =>
              mapping.putBlob(id.name, path, id.name)
            }
            mapping.putTree(rc.getTree.getId.name, newTreeId.name)
            subtreeMap.foreach { case (origId, newId) => mapping.putTree(origId, newId) }
            mapping.putCommit(origCommitSha, newCommit.name)
          }

          commits  += 1
          blobsRun += artifacts.misses.size
          blobsHit += artifacts.hitCount
          progress(s"commit $origCommitSha")
        }
      }

      treeInserter.flush()
      commitInserter.flush()
    } finally {
      treeInserter.close()
      commitInserter.close()
      pool.shutdown()
      val _ = pool.awaitTermination(1, TimeUnit.MINUTES)
    }

    (commits, blobsRun, blobsHit, aborted)
  }

  // -- pipelined commit loop ----------------------------------------------

  /** Look-ahead pipelined [[walkCommits]]: a producer runs ahead up to a bounded
    * window, submitting every unique blob miss to the pool to keep it saturated,
    * while the single consumer applies results in strict RevWalk order. Content-
    * addressed trees + a deterministic tokenizer keep commit_map byte-identical to
    * the serial walker. Returns (commitsProcessed, blobsRun, blobsHit, aborted). */
  private def walkCommitsPipelined(revWalk: RevWalk): (Int, Int, Int, Boolean) = {
    val pool = Executors.newFixedThreadPool(parallelism)
    implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(pool)
    val treeInserter   = dst.newObjectInserter()
    val commitInserter = dst.newObjectInserter()

    // Bounded look-ahead: the queue caps how far the producer runs ahead of
    // the consumer, which in turn bounds the in-flight future map so memory
    // stays flat regardless of history length.
    val queue: BlockingQueue[QueueItem] = new ArrayBlockingQueue[QueueItem](PipelineWindow)
    // Dedup: tokenize each (origSha, path) at most once while it is in flight,
    // so a blob shared by several in-window commits reuses one future.
    val inFlight = new ConcurrentHashMap[(String, String), Future[BlobResult]]()

    val aborted       = new AtomicBoolean(false)
    // Set when a commit contained a timed-out blob: like `aborted` it stops the
    // producer and makes the consumer drain, but it is not an abort — the run
    // keeps everything it earned and simply stops folding here.
    val timedOutStop  = new AtomicBoolean(false)
    val producerError = new AtomicReference[Throwable](null)

    // -- producer: iterate the walk in order, plan trees, submit blob work --
    val producer = new Thread(new Runnable {
      def run(): Unit = {
        try {
          val iter = revWalk.iterator()
          while (iter.hasNext && !aborted.get() && !timedOutStop.get()) {
            val rc   = iter.next()
            val data = snapshotCommit(rc)  // decouple consumer from the shared RevWalk
            val (plan, artifacts) = buildTreePlan(rc.getTree.getId, pathPrefix = "")
            rc.disposeBody()  // Tier 1: snapshot + plan already copied all needed fields; free the
                              // raw RevCommit body so ~1.46M bodies don't accumulate over the walk.
            // De-dup within the commit, then submit/reuse across the window.
            val uniqueMisses = artifacts.misses.iterator
              .map(m => (m.origId.name, m.fullPath) -> m).toMap
            val missFutures: Map[(String, String), Future[BlobResult]] =
              uniqueMisses.map { case (key, task) =>
                key -> inFlight.computeIfAbsent(key, (_: (String, String)) => Future(executeBlobTask(task)))
              }
            queue.put(CommitItem(data, plan, artifacts, missFutures))  // backpressure when full
          }
        } catch {
          case t: Throwable =>
            producerError.set(t)
            aborted.set(true)
        } finally {
          // The consumer always drains to EndOfWalk, so this cannot deadlock
          // on a full queue even after an early stop.
          try queue.put(EndOfWalk) catch { case _: InterruptedException => Thread.currentThread().interrupt() }
        }
      }
    }, "blobexec-producer")
    producer.setDaemon(true)
    producer.start()

    // -- consumer: this thread, strict order, sole DB writer ----------------
    var commits  = 0
    var blobsRun = 0
    var blobsHit = 0
    var stopped  = false

    try {
      while (!stopped) {
        queue.take() match {
          case EndOfWalk => stopped = true
          case CommitItem(data, plan, artifacts, missFutures) =>
            if (aborted.get() || timedOutStop.get()) {
              // Draining after abort / producer-error / a timed-out commit:
              // discard until EndOfWalk. Folding a later commit here would
              // reference a parent this run deliberately did not record.
            } else {
              // Await only this commit's futures (typically already complete).
              // Unbounded on purpose: the stall watchdog, not a guessed
              // duration, is what stops a wedged run (see withStallWatchdog).
              val results = missFutures.map { case (k, f) => k -> Await.result(f, Duration.Inf) }
              val timedOutIds: IMap[(String, String), ObjectId] =
                results.iterator.collect { case (k, BlobResult.TimedOut(id)) => k -> id }.toMap
              results.values.collectFirst { case a: BlobResult.Aborted => a } match {
                case Some(_) =>
                  aborted.set(true)  // stop the producer; drain the remainder
                case None if timedOutIds.nonEmpty =>
                  // Retryable: keep the good blob rows, write no tree and no
                  // commit, and stop the walk so nothing is folded onto a parent
                  // this run did not record. See persistRetryableBlobs.
                  val good: IMap[(String, String), ObjectId] =
                    results.iterator.collect { case (k, BlobResult.Resolved(id)) => k -> id }.toMap
                  persistRetryableBlobs(
                    MissResolution(good ++ timedOutIds, abort = false, timedOutKeys = timedOutIds.keySet),
                    data.origCommitSha
                  )
                  timedOutStop.set(true)
                case None =>
                  val resolved: IMap[(String, String), ObjectId] =
                    results.iterator.collect { case (k, BlobResult.Resolved(id)) => k -> id }.toMap
                  val subtreeMap = scala.collection.mutable.Map.empty[String, String]
                  val newTreeId = assemble(plan, resolved, treeInserter, subtreeMap)
                  val parents = data.parentOrigShas.map { pn =>
                    dbLock.synchronized(mapping.getCommit(pn)).getOrElse(
                      throw new IllegalStateException(
                        s"parent commit $pn missing from commit_map while processing ${data.origCommitSha}")
                    )
                  }.map(ObjectId.fromString)
                  val newCommit = buildCommitFromData(data, parents, newTreeId, commitInserter)
                  dbLock.synchronized {
                    mapping.inTx {
                      resolved.foreach { case ((origSha, path), newId) =>
                        mapping.putBlob(origSha, path, newId.name)
                      }
                      artifacts.unchangedBlobs.foreach { case (id, path) =>
                        mapping.putBlob(id.name, path, id.name)
                      }
                      mapping.putTree(data.origTreeId.name, newTreeId.name)
                      subtreeMap.foreach { case (origId, newId) => mapping.putTree(origId, newId) }
                      mapping.putCommit(data.origCommitSha, newCommit.name)
                    }
                  }
                  commits  += 1
                  blobsRun += artifacts.misses.size
                  blobsHit += artifacts.hitCount
                  progress(s"commit ${data.origCommitSha}")
              }
              // Flat memory: this commit's blobs are now cached in blob_map, so
              // future producer look-ups hit the DB instead of the dedup map.
              // In-window commits that already referenced these keys captured
              // their future handles, so removal is safe.
              missFutures.keysIterator.foreach(k => inFlight.remove(k))
            }
        }
      }

      treeInserter.flush()
      commitInserter.flush()
    } finally {
      treeInserter.close()
      commitInserter.close()
      pool.shutdown()
      if (!pool.awaitTermination(1, TimeUnit.MINUTES)) { pool.shutdownNow(); () }
      try producer.join(TimeUnit.MINUTES.toMillis(1)) catch { case _: InterruptedException => Thread.currentThread().interrupt() }
    }

    // Surface an unexpected producer failure (distinct from a clean abort).
    Option(producerError.get()).foreach(t => throw t)

    (commits, blobsRun, blobsHit, aborted.get())
  }

  // -- tree-parallel pipelined commit loop (Design B) ----------------------

  /** Design B: [[walkCommitsPipelined]] plus tree ASSEMBLY moved onto the pool.
    * The serial consumer rebuilt each commit's tree bottom-up (only top trees are
    * memoized), capping cores at ~7/16; here each commit's `assemble` is chained off
    * its blob tasks with a per-worker `ObjectInserter`, so many assemble concurrently.
    * The consumer keeps only the ordered persist step (one DB writer => just producer
    * + consumer contend for `dbLock`), so commit_map stays byte-identical to serial.
    * Returns (commitsProcessed, blobsRun, blobsHit, aborted). */
  private def walkCommitsTrees(revWalk: RevWalk): (Int, Int, Int, Boolean) = {
    val pool = Executors.newFixedThreadPool(parallelism)
    implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(pool)
    // Only the consumer writes commit objects; each worker uses its own
    // inserter (created inside `assembleTreeOnly`) for the trees/blobs it
    // assembles, mirroring the per-worker inserter in `executeBlobTask`.
    val commitInserter = dst.newObjectInserter()

    val queue: BlockingQueue[TreeQueueItem] = new ArrayBlockingQueue[TreeQueueItem](PipelineWindow)
    val inFlight = new ConcurrentHashMap[(String, String), Future[BlobResult]]()

    val aborted       = new AtomicBoolean(false)
    val timedOutStop  = new AtomicBoolean(false)   // see walkCommitsPipelined
    val producerError = new AtomicReference[Throwable](null)

    // -- producer: plan trees, submit blob work, chain tree assembly --------
    val producer = new Thread(new Runnable {
      def run(): Unit = {
        try {
          val iter = revWalk.iterator()
          while (iter.hasNext && !aborted.get() && !timedOutStop.get()) {
            val rc   = iter.next()
            val data = snapshotCommit(rc)
            val (plan, artifacts) = buildTreePlan(rc.getTree.getId, pathPrefix = "")
            rc.disposeBody()  // snapshot + plan copied all needed fields; free the body so they don't accumulate
            // De-dup within the commit, then submit/reuse across the window.
            val uniqueMisses = artifacts.misses.iterator
              .map(m => (m.origId.name, m.fullPath) -> m).toMap
            val missFutures: Map[(String, String), Future[BlobResult]] =
              uniqueMisses.map { case (key, task) =>
                key -> inFlight.computeIfAbsent(key, (_: (String, String)) => Future(executeBlobTask(task)))
              }
            // Chain tree assembly onto this commit's blob tokenizations. The
            // `.map` continuation runs on a pool thread (non-blocking Future
            // composition, no thread parked), so multiple commits' assemblies
            // proceed concurrently. Keys are carried through so the consumer
            // can record blob_map without re-awaiting.
            val keyed = missFutures.toVector
            val treeFuture: Future[TreeResult] =
              Future.sequence(keyed.map { case (k, f) => f.map(r => (k, r)) })
                .map(results => assembleTreeOnly(plan, results))
            queue.put(CommitTreeItem(data, artifacts, missFutures.keySet, treeFuture))  // backpressure when full
          }
        } catch {
          case t: Throwable =>
            producerError.set(t)
            aborted.set(true)
        } finally {
          try queue.put(EndOfTreeWalk) catch { case _: InterruptedException => Thread.currentThread().interrupt() }
        }
      }
    }, "blobexec-tree-producer")
    producer.setDaemon(true)
    producer.start()

    // -- consumer: strict order, parent resolution + all DB writes ----------
    var commits  = 0
    var blobsRun = 0
    var blobsHit = 0
    var stopped  = false

    try {
      while (!stopped) {
        queue.take() match {
          case EndOfTreeWalk => stopped = true
          case CommitTreeItem(data, artifacts, blobKeys, treeFuture) =>
            if (aborted.get() || timedOutStop.get()) {
              // Draining after abort / producer-error / a timed-out commit:
              // discard until EndOfTreeWalk.
            } else {
              // The assembly (and any abort it observed) is already done off-thread.
              // Unbounded: the stall watchdog is the backstop, not a budget.
              Await.result(treeFuture, Duration.Inf) match {
                case TreeResult.Aborted(_, _) =>
                  aborted.set(true)  // stop the producer; drain the remainder
                case TreeResult.Built(_, resolved, _, tok) if tok.nonEmpty =>
                  // Retryable: the tree was assembled off-thread and is usable,
                  // but recording it (or the commit) would hide the timeout from
                  // the next run. Keep the good blob rows only, and stop here.
                  persistRetryableBlobs(
                    MissResolution(resolved, abort = false, timedOutKeys = tok),
                    data.origCommitSha
                  )
                  timedOutStop.set(true)
                case TreeResult.Built(newTreeId, resolved, subtrees, _) =>
                  val parents = data.parentOrigShas.map { pn =>
                    dbLock.synchronized(mapping.getCommit(pn)).getOrElse(
                      throw new IllegalStateException(
                        s"parent commit $pn missing from commit_map while processing ${data.origCommitSha}")
                    )
                  }.map(ObjectId.fromString)
                  val newCommit = buildCommitFromData(data, parents, newTreeId, commitInserter)
                  dbLock.synchronized {
                    mapping.inTx {
                      resolved.foreach { case ((origSha, path), newId) =>
                        mapping.putBlob(origSha, path, newId.name)
                      }
                      artifacts.unchangedBlobs.foreach { case (id, path) =>
                        mapping.putBlob(id.name, path, id.name)
                      }
                      mapping.putTree(data.origTreeId.name, newTreeId.name)
                      subtrees.foreach { case (origId, newId) => mapping.putTree(origId, newId) }
                      mapping.putCommit(data.origCommitSha, newCommit.name)
                    }
                  }
                  commits  += 1
                  blobsRun += artifacts.misses.size
                  blobsHit += artifacts.hitCount
                  progress(s"commit ${data.origCommitSha}")
              }
              // Flat memory: this commit's blobs are now cached in blob_map, so
              // future producer look-ups hit the DB instead of the dedup map.
              blobKeys.foreach(k => inFlight.remove(k))
            }
        }
      }

      commitInserter.flush()
    } finally {
      commitInserter.close()
      pool.shutdown()
      if (!pool.awaitTermination(1, TimeUnit.MINUTES)) { pool.shutdownNow(); () }
      try producer.join(TimeUnit.MINUTES.toMillis(1)) catch { case _: InterruptedException => Thread.currentThread().interrupt() }
    }

    // Surface an unexpected producer failure (distinct from a clean abort).
    Option(producerError.get()).foreach(t => throw t)

    (commits, blobsRun, blobsHit, aborted.get())
  }

  /** Worker-side (pool) assembly of one commit's tree with a fresh per-worker
    * `ObjectInserter`, returning the top-tree id + resolved blob map. Touches no
    * shared state (`mapping`/`commit_map`), so it needs no `dbLock`; a blob
    * abort-on-error propagates instead of building a tree. */
  private def assembleTreeOnly(
      plan: TreePlan,
      blobResults: Vector[((String, String), BlobResult)]
  ): TreeResult = {
    blobResults.iterator.map(_._2).collectFirst { case a: BlobResult.Aborted => a } match {
      case Some(a) => TreeResult.Aborted(a.stderr, a.exitCode)
      case None =>
        // `resolved` is what the consumer persists, so a timed-out blob is kept
        // out of it; `forTree` is what the tree references, so it is kept in.
        val resolved: IMap[(String, String), ObjectId] =
          blobResults.iterator.collect { case (k, BlobResult.Resolved(id)) => k -> id }.toMap
        val timedOut: IMap[(String, String), ObjectId] =
          blobResults.iterator.collect { case (k, BlobResult.TimedOut(id)) => k -> id }.toMap
        val inserter = dst.newObjectInserter()
        try {
          val subtreeMap = scala.collection.mutable.Map.empty[String, String]
          val newTreeId = assemble(plan, resolved ++ timedOut, inserter, subtreeMap)
          inserter.flush()
          TreeResult.Built(newTreeId, resolved, subtreeMap.toMap, timedOut.keySet)
        } finally inserter.close()
    }
  }

  /** Worker body run on the pool: read the blob, run the external command,
    * and return the id the rewritten tree should reference. Mirrors the
    * per-task logic in [[resolveMisses]] (a Skip re-inserts the original bytes
    * into dst). Never touches `mapping`, keeping the hot path lock-free. */
  private def executeBlobTask(task: BlobMissTask): BlobResult =
    readBlob(task) match {
      // None means "excluded from the rewrite": machine-generated, untokenizable
      // and unexplained, or on the blob denylist. All three take the same
      // downstream path — no id, no tree entry, no blob_map row — which is why one
      // result covers them. readBlob has already counted and reported which it was,
      // and the exclusions differ in what they do to the exit status, not here.
      case None        => BlobResult.Excluded(task.origId)
      case Some(bytes) => executeBlobTask(task, bytes)
    }

  private def executeBlobTask(task: BlobMissTask, bytes: Array[Byte]): BlobResult = {
    val workerInserter = dst.newObjectInserter()
    val label = s"${task.origId.name} (${task.fullPath})"
    inFlightBlobs.put(label, System.nanoTime())
    try {
      blobCommandExecutions.increment()
      // Set by either callback below: both mean "this blob's tokenization is
      // unusable, so nothing about it may be persisted".
      val unusable = new AtomicBoolean(false)
      val outcome = BlobExec.run(
        bytes        = bytes,
        origSha      = task.origId.name,
        filename     = task.filename,
        fullPath     = task.fullPath,
        command      = command,
        abortOnError = abortOnError,
        inserter     = workerInserter,
        timeoutSeconds = blobTimeoutSeconds,
        onTimeout      = () => { blobsTimedOut.increment(); unusable.set(true) },
        onParserCrash  = () => { blobsParserCrashed.increment(); unusable.set(true) }
      )
      val res = outcome match {
        case BlobExec.Outcome.Skip if unusable.get() =>
          // The tree must still reference something, so keep the original bytes
          // available — but report it as TimedOut so nothing gets persisted. A
          // parser crash takes this same branch: the name is now narrower than the
          // meaning, which is "unusable, persist nothing".
          ensureOriginalBlobAvailable(task.origId, insertHeldBytes(bytes), workerInserter)
          BlobResult.TimedOut(task.origId)
        case BlobExec.Outcome.Skip =>
          ensureOriginalBlobAvailable(task.origId, insertHeldBytes(bytes), workerInserter)
          BlobResult.Resolved(task.origId)
        case BlobExec.Outcome.Replace(newId) =>
          BlobResult.Resolved(newId)
        case BlobExec.Outcome.Abort(stderr, code) =>
          BlobResult.Aborted(stderr, code)
      }
      workerInserter.flush()
      progress(s"blob $label")
      res
    } finally {
      inFlightBlobs.remove(label)
      workerInserter.close()
    }
  }

  /** Freeze everything the consumer needs from a RevCommit so it never touches
    * the shared, single-threaded RevWalk owned by the producer. */
  private def snapshotCommit(rc: RevCommit): CommitData = {
    val enc = try rc.getEncoding catch { case _: Throwable => java.nio.charset.StandardCharsets.UTF_8 }
    CommitData(
      origCommitSha  = rc.getId.name,
      parentOrigShas = rc.getParents.toVector.map(_.getId.name),
      origTreeId     = rc.getTree.getId,
      author         = rc.getAuthorIdent,
      committer      = rc.getCommitterIdent,
      encoding       = enc,
      fullMessage    = rc.getFullMessage
    )
  }

  /** Identical to [[buildCommit]] but sourced from an immutable snapshot.
    * Produces the same commit bytes (hence the same SHA) as the serial path. */
  private def buildCommitFromData(
      data: CommitData,
      parents: Vector[ObjectId],
      newTree: ObjectId,
      inserter: ObjectInserter
  ): ObjectId = {
    val cb = new CommitBuilder
    cb.setAuthor(data.author)
    cb.setCommitter(data.committer)
    cb.setEncoding(data.encoding)
    cb.setMessage(FormerCommitFooter.append(data.fullMessage, data.origCommitSha))
    cb.setTreeId(newTree)
    cb.setParentIds(parents: _*)
    inserter.insert(cb)
  }

  // -- tree planning -------------------------------------------------------

  /** Walk `origTreeId` under `pathPrefix`, returning a `TreePlan`, the matching-blob
    * misses to tokenize, the hit count, and the non-matching (orig_id, full_path)
    * pairs for identity rows. Caveat: a `tree_map` short-circuit skips the re-walk,
    * so blob_map can miss (blob, alt_path) rows for the same subtree under a new path. */
  private def buildTreePlan(origTreeId: ObjectId, pathPrefix: String): (TreePlan, PlanArtifacts) = {
    dbLock.synchronized(mapping.getTree(origTreeId.name)) match {
      case Some(newName) =>
        (TreeExisting(ObjectId.fromString(newName)), PlanArtifacts.empty)
      case None =>
        val reader = src.newObjectReader()
        try {
          val tw = new TreeWalk(reader)
          tw.addTree(new CanonicalTreeParser(null, reader, origTreeId))
          tw.setRecursive(false)

          val builder        = Vector.newBuilder[EntryPlan]
          val missesBuilder  = Vector.newBuilder[BlobMissTask]
          val unchangedBldr  = Vector.newBuilder[(ObjectId, String)]
          var hits = 0
          while (tw.next()) {
            val mode = tw.getFileMode(0)
            val name = tw.getNameString
            val id   = tw.getObjectId(0)
            val fullPath = pathPrefix + name

            if (mode == FileMode.TREE) {
              val (sub, subArtifacts) = buildTreePlan(id, fullPath + "/")
              builder += EntrySubtree(name, mode, sub)
              missesBuilder ++= subArtifacts.misses
              unchangedBldr ++= subArtifacts.unchangedBlobs
              hits += subArtifacts.hitCount
            } else if (mode == FileMode.GITLINK) {
              // Gitlinks reference commits in other repos; not stored in dst,
              // not recorded in blob_map (they aren't blobs).
              builder += EntryUnchanged(name, mode, id, copyBytes = false)
            } else {
              // Blob (regular file, executable, or symlink).
              if (fileMask.findFirstIn(name).isDefined) {
                dbLock.synchronized(mapping.getBlob(id.name, fullPath)) match {
                  case Some(newSha) =>
                    builder += EntryBlobHit(name, mode, ObjectId.fromString(newSha))
                    hits += 1
                  case None =>
                    builder += EntryBlobMiss(name, mode, id, fullPath)
                    missesBuilder += BlobMissTask(id, name, fullPath)
                }
              } else {
                builder += EntryUnchanged(name, mode, id, copyBytes = true)
                unchangedBldr += ((id, fullPath))
              }
            }
          }
          val artifacts = PlanArtifacts(missesBuilder.result(), hits, unchangedBldr.result())
          (TreeBuild(origTreeId, builder.result()), artifacts)
        } finally reader.close()
    }
  }

  // -- parallel blob resolution -------------------------------------------

  /** Run each miss through the external command on the pool. Returns the ids the
    * tree should reference, an aborted flag, and the keys whose command was
    * killed on its budget — those are in the id map (the tree needs them) but
    * must be kept out of `blob_map`, so the caller can retry them. */
  private def resolveMisses(
      misses: Vector[BlobMissTask],
      pool: java.util.concurrent.ExecutorService
  )(implicit ec: ExecutionContext): MissResolution = {
    if (misses.isEmpty) return MissResolution(IMap.empty, abort = false, timedOutKeys = Set.empty)

    // De-dup so we don't run the command twice for the same key within a
    // single commit (e.g. the same (blob, path) reached via two subtrees).
    val unique = misses.map(m => (m.origId.name, m.fullPath) -> m).toMap.values.toVector

    // An excluded blob yields None: it contributes no id, so no tree entry, no
    // blob_map row and no dataset row. See readBlob and resolveEntry.
    val futures = unique.map { task =>
      Future {
        readBlob(task).map { bytes =>
          val workerInserter = dst.newObjectInserter()
          val label = s"${task.origId.name} (${task.fullPath})"
          inFlightBlobs.put(label, System.nanoTime())
          try {
            blobCommandExecutions.increment()
            // Set by either callback below: both mean "this blob's tokenization is
      // unusable, so nothing about it may be persisted".
      val unusable = new AtomicBoolean(false)
            val outcome = BlobExec.run(
              bytes        = bytes,
              origSha      = task.origId.name,
              filename     = task.filename,
              fullPath     = task.fullPath,
              command      = command,
              abortOnError = abortOnError,
              inserter     = workerInserter,
              timeoutSeconds = blobTimeoutSeconds,
              onTimeout      = () => { blobsTimedOut.increment(); unusable.set(true) },
              onParserCrash  = () => { blobsParserCrashed.increment(); unusable.set(true) }
            )
            // For Skip outcomes (identical output, a non-zero exit with
            // abortOnError=false, or a timeout) we keep the original blob id, so
            // the dst tree will reference it — meaning the bytes must exist in
            // dst. For Replace outcomes the worker has already inserted the new
            // blob. For Abort we do nothing (caller short-circuits).
            outcome match {
              case BlobExec.Outcome.Skip => ensureOriginalBlobAvailable(task.origId, insertHeldBytes(bytes), workerInserter)
              case _                     => ()
            }
            workerInserter.flush()
            progress(s"blob $label")
            (task, outcome, unusable.get())
          } finally {
            inFlightBlobs.remove(label)
            workerInserter.close()
          }
        }
      }
    }

    // Unbounded: the stall watchdog is the backstop, not a budget. `flatten`
    // drops the excluded blobs: they are excluded, not resolved.
    val results = Await.result(Future.sequence(futures), Duration.Inf).flatten

    val abort = results.exists { case (_, o, _) => o.isInstanceOf[BlobExec.Outcome.Abort] }
    if (abort) MissResolution(IMap.empty, abort = true, timedOutKeys = Set.empty)
    else {
      val ids = results.iterator.collect {
        case (task, BlobExec.Outcome.Replace(newId), _) =>
          (task.origId.name, task.fullPath) -> newId
        case (task, BlobExec.Outcome.Skip, _) =>
          // identical, non-zero-exit (with abortOnError=false), or timed out
          (task.origId.name, task.fullPath) -> task.origId
      }.toMap
      val timedOutKeys = results.iterator.collect {
        case (task, _, true) => (task.origId.name, task.fullPath)
      }.toSet
      MissResolution(ids, abort = false, timedOutKeys = timedOutKeys)
    }
  }

  /** Read one mask-matched blob, or `None` when it has been excluded from the
    * rewrite. Two things exclude it: the blob denylist, and a provenance
    * classification made when JGit refuses to materialise the object.
    *
    * `qualcomm/qcom-embedded-power-measurement` ended with rc 1 here:
    *
    *   org.eclipse.jgit.errors.LargeObjectException: 205b0f65... exceeds size limit
    *     at cregit.blobexec.Walker.readBlob(Walker.scala:1047)
    *
    * on `src/libraries/libexcel/excel.cpp`, 102,897,757 bytes and 2,398,232
    * lines. The mask *did* select it (`.cpp`), so unlike the pass-through path
    * this is not a binary that slipped through. It is machine-generated: its own
    * header says it is a `dumpcpp` dump of Microsoft Excel's COM type library.
    * It carries no contributor-behaviour signal, so it is excluded — and the size
    * alone is not the reason worth writing down, the provenance is.
    *
    * That last sentence used to sit above a size test, and the mismatch was the
    * defect: a size test also drops a genuinely large HAND-WRITTEN source file,
    * for a reason that does not apply to it. So size is the TRIGGER here and
    * provenance is the VERDICT:
    *
    *   1. The trigger. JGit will not materialise an object at or above its
    *      stream-file threshold, so such a blob cannot be handed to the tokenizer
    *      as bytes. That physical limit fires in two places, and both are only
    *      triggers: the [[Walker.isOversized]] fast path, and the
    *      `LargeObjectException` catch, which is what covers a JGit configured
    *      LOWER than its own default ([[Walker.MaxBlobBytes]] can only read the
    *      default — the installed value is not exposed by public API). Classifying
    *      from both sites is deliberate: the two must never disagree.
    *   2. The verdict, when a banner is found. The blob is classified from a
    *      bounded prefix — [[Walker.ProvenancePrefixBytes]] read through
    *      `openStream`, which works at any size, so no 98 MB object has to be
    *      materialised to read its header. A generator banner
    *      ([[Walker.generatedEvidence]]) makes the exclusion defensible, and the
    *      matched line is logged as the evidence rather than asserted: see
    *      [[noteGeneratedExclusion]].
    *   3. The verdict, when no banner is found. This is the case a size test got
    *      wrong: a large file that nothing shows to be generated. It still cannot
    *      be tokenized here, but it is NOT an explained exclusion, so it gets its
    *      own counter, its own report line and its own exit status — see
    *      [[noteUntokenizable]]. Being dropped silently is the one outcome ruled
    *      out.
    *
    * Tokenizing that third case instead was assessed and rejected, with numbers.
    * `openStream` can supply the bytes JGit refuses to materialise, so reading is
    * not the obstacle; [[BlobExec.invoke]] is. It buffers the whole token stream
    * in the heap (a `ByteArrayOutputStream` that doubles as it grows) and then
    * compares it byte-for-byte with the input, so an N-byte blob costs N plus
    * several times the size of its token stream, and the tokenizer's own srcML
    * footprint on top. `parallelism` is `availableProcessors` (16 on this host)
    * and the jar runs under the JVM's default ceiling (7.66 GiB here, 25% of 30
    * GiB), with up to three projects running at once. Bounding that needs
    * BlobExec's stdio spooled through temp files instead of buffered, which is a
    * different change from this one — so it is reported, not guessed at. The 98 MB
    * case does not need it: it is generated, and step 2 excludes it for the right
    * reason.
    */
  private def readBlob(task: BlobMissTask): Option[Array[Byte]] = {
    // Before anything is read or opened: a denylisted blob is one srcML cannot be
    // trusted with, so the cheapest possible check is the right one. This is a map
    // lookup on a 4-entry map, and it is what turns a 600 s timeout plus exit 4
    // into a microsecond and a logged exclusion.
    val denied = denylist.entryFor(task.origId.name)
    if (denied.isDefined) { noteDenylisted(task, denied.get); return None }

    val r = src.newObjectReader()
    try {
      val loader = r.open(task.origId, OBJ_BLOB)
      val size   = loader.getSize
      // Size only says "the bytes cannot be produced here". Which exclusion this
      // is, and whether it is defensible at all, is classifyUnmaterialisable's.
      if (Walker.isOversized(size)) { classifyUnmaterialisable(task, loader, size); None }
      else
        try Some(loader.getBytes)
        catch {
          case _: org.eclipse.jgit.errors.LargeObjectException =>
            classifyUnmaterialisable(task, loader, size)
            None
        }
    } finally r.close()
  }

  /** Decide WHY a blob JGit will not materialise is being left out, and record it
    * under that reason. Called from both trigger sites in [[readBlob]] so the size
    * fast path and the `LargeObjectException` band can never classify a blob
    * differently. `loader`'s reader must still be open: the header is read through
    * the loader's own stream. */
  private def classifyUnmaterialisable(
      task: BlobMissTask,
      loader: ObjectLoader,
      sizeBytes: Long
  ): Unit =
    Walker.generatedEvidence(provenancePrefix(task, loader)) match {
      case Some(evidence) => noteGeneratedExclusion(task, sizeBytes, evidence)
      case None           => noteUntokenizable(task, sizeBytes)
    }

  /** The first [[Walker.ProvenancePrefixBytes]] of a blob, read through
    * `openStream` because `getBytes` is precisely what refused. Streaming a header
    * is bounded work at any blob size, which is what makes classifying a 98 MB
    * object affordable: this reads 8 KiB whatever the blob weighs.
    *
    * A read failure yields an empty prefix, which classifies as NOT generated.
    * That direction is deliberate: an unreadable header is not evidence of
    * provenance, and the unclassified path is the loud one. */
  private def provenancePrefix(task: BlobMissTask, loader: ObjectLoader): Array[Byte] =
    try {
      val in = loader.openStream()
      try in.readNBytes(Walker.ProvenancePrefixBytes)
      finally in.close()
    } catch {
      case scala.util.control.NonFatal(e) =>
        System.err.println(
          s"blobExec: could not read the header of blob ${task.origId.name} " +
            s"(${task.fullPath}) in order to classify it: ${e.getClass.getName}: " +
            s"${e.getMessage}. Treating it as unclassified, which is the loud path."
        )
        Array.emptyByteArray
    }

  /** `(origSha, fullPath)` of every blob excluded because its header says a
    * machine wrote it. Read by [[resolveEntry]], which drops those entries from
    * the rewritten tree: the key's absence from `resolved` is what omits the file,
    * and this set is what distinguishes a deliberate omission from a missing-key
    * bug. */
  private val generatedKeys = ConcurrentHashMap.newKeySet[(String, String)]()

  /** `(origSha, fullPath)` of every blob JGit would not materialise and nothing
    * identified as generated. Read by [[resolveEntry]] for the same reason as
    * [[generatedKeys]], and kept separate from it so that the explained exclusion
    * and the unexplained one cannot be confused in the counts — which is the whole
    * point of splitting them. */
  private val untokenizableKeys = ConcurrentHashMap.newKeySet[(String, String)]()

  /** `(origSha, fullPath)` of every blob excluded by the denylist. Read by
    * [[resolveEntry]] for the same reason as [[generatedKeys]]: the key's absence
    * from `resolved` is what omits the path, and this set is what separates a
    * deliberate omission from a missing-key bug. Kept separate from the other two
    * so the exclusions can never be confused in the counts. */
  private val denylistedKeys = ConcurrentHashMap.newKeySet[(String, String)]()

  /** Count and explain one denylisted blob, once per `(sha, path)`. The sha, the
    * path, the reason and the citation are all in the line, because this line is
    * the only per-blob record of what the dataset does not contain, and "we could
    * not parse it" is not a defensible sentence in a paper. */
  private def noteDenylisted(task: BlobMissTask, entry: BlobDenylist.Entry): Unit = {
    val key = (task.origId.name, task.fullPath)
    // Counted and logged once per (sha, path), exactly like the other exclusions,
    // so every exclusion counter means the same thing. One blob reached through two paths is
    // therefore two, which is the honest figure for "paths the dataset is missing"
    // — but note the four shipped entries are ONE file's history, so a count of 4
    // is not four distinct files.
    if (denylistedKeys.add(key)) {
      blobsDenylisted.increment()
      System.err.println(
        s"blobExec: EXCLUDED denylisted blob: sha=${task.origId.name} " +
          s"path=${task.fullPath} reason=${entry.reason} citation=${entry.citation}. " +
          "The blob is left out of the rewritten tree, so it produces no blame and no dataset " +
          "row, and the file is absent from the tokenized repository rather than present as raw " +
          "source. The exclusion is deterministic and cited, so unlike a tokenizer timeout it " +
          "does NOT block publication: the walk carries on and the run still exits 0. See " +
          s"${BlobDenylist.ResourcePath} in the blobExec jar for the list itself."
      )
    }
  }

  /** Count and explain one provenance-based exclusion, once per `(sha, path)`.
    * The path and the size are in the line on purpose: a bare sha cannot be cited
    * in a paper, and this line is the only durable record of what the dataset is
    * missing. The matched marker and the header line it came from are in it for a
    * stronger reason — they ARE the reason. "It was too big" is not a defensible
    * sentence in a paper; "its own header says dumpcpp generated it" is, and a
    * reader can check it. */
  private def noteGeneratedExclusion(
      task: BlobMissTask,
      sizeBytes: Long,
      evidence: Walker.GeneratedEvidence
  ): Unit = {
    val key = (task.origId.name, task.fullPath)
    if (generatedKeys.add(key)) {
      blobsGeneratedExcluded.increment()
      System.err.println(
        s"blobExec: EXCLUDED generated blob: sha=${task.origId.name} " +
          s"path=${task.fullPath} size=${sizeBytes}B jgitDefaultLimit=${Walker.MaxBlobBytes}B " +
          s"marker=[${evidence.marker}] header=[${evidence.line}]. " +
          "JGit will not materialise an object this large, so its bytes cannot be handed to " +
          "the tokenizer here — but the reason it is EXCLUDED is the header above: the file " +
          "is machine-generated, so it carries no contributor-behaviour signal. The blob is " +
          "left out of the rewritten tree, so it produces no blame and no dataset row, and " +
          "the file is absent from the tokenized repository rather than present as raw " +
          "source. Explained and deterministic, so unlike a tokenizer timeout this does NOT " +
          "block publication: the walk carries on and the run still exits 0."
      )
    }
  }

  /** Count and report one blob JGit will not materialise and nothing identifies as
    * generated, once per `(sha, path)`.
    *
    * This is the case the old size test got wrong. On the evidence available it is
    * a large hand-written source file, so excluding it is NOT defensible, and it
    * must not be counted alongside the generated ones or the two become one
    * indistinguishable number again. It still cannot be tokenized in this run (see
    * [[readBlob]] on why buying that with memory is not free), so the path is
    * dropped — but the run is reported incomplete, exactly as a timeout or a
    * parser crash is, because an exclusion whose reason cannot be stated is a hole
    * in the dataset rather than a finding about it. */
  private def noteUntokenizable(task: BlobMissTask, sizeBytes: Long): Unit = {
    val key = (task.origId.name, task.fullPath)
    if (untokenizableKeys.add(key)) {
      blobsUntokenizable.increment()
      System.err.println(
        s"blobExec: UNTOKENIZABLE blob, exclusion NOT explained: sha=${task.origId.name} " +
          s"path=${task.fullPath} size=${sizeBytes}B jgitDefaultLimit=${Walker.MaxBlobBytes}B. " +
          "JGit will not materialise an object this large, so the tokenizer cannot be handed " +
          s"its bytes — but nothing in its first ${Walker.ProvenancePrefixBytes} bytes says a " +
          "machine wrote it, so on the evidence this is a large HAND-WRITTEN source file and " +
          "dropping it is not defensible. It is left out of the rewritten tree for this run " +
          "(no blame, no dataset row, absent rather than raw source) and the run is reported " +
          "INCOMPLETE. Remedies, in order: look at the file. If it is generated after all, the " +
          "honest fix is a header marker this check can see, or the blob denylist with a reason " +
          "and a citation (see " + BlobDenylist.ResourcePath + "). If it really is authored, " +
          "tokenizing it needs BlobExec's stdio spooled to disk instead of buffered in the " +
          "heap: a deliberate memory decision, not a flag to flip."
      )
    }
  }

  /** Copy one original blob from src into dst by streaming it. Returns (id, size).
    *
    * Replaces readBlob on the pass-through path. `ObjectLoader.getBytes` throws
    * LargeObjectException above JGit's stream threshold, and a blob that does not
    * match the mask is copied verbatim, so its size is whatever the project
    * committed rather than the size of a source file. redis/redis failed this way
    * after 1,684s of work:
    *
    *   org.eclipse.jgit.errors.LargeObjectException: 12e1ac54... exceeds size limit
    *     at cregit.blobexec.Walker.ensureOriginalBlobAvailable(Walker.scala:912)
    *
    * Streaming also removes the memory spike. Raising the threshold instead would
    * still hold the whole blob, and three concurrent projects on a 30 GiB box
    * cannot each afford a large fixture.
    *
    * The reader stays open for the whole copy: closing it before the stream is
    * consumed would invalidate the stream.
    */
  private def streamBlobInto(id: ObjectId, inserter: ObjectInserter): (ObjectId, Long) = {
    val r = src.newObjectReader()
    try {
      val loader = r.open(id, OBJ_BLOB)
      val size   = loader.getSize
      val in     = loader.openStream()
      try (inserter.insert(OBJ_BLOB, size, in), size)
      finally in.close()
    } finally r.close()
  }

  // -- assembly ------------------------------------------------------------

  /** Build the plan bottom-up, writing trees into dst and returning the top id.
    * Each subtree's `origId -> newId` goes into `subtreeAcc`; the caller persists it
    * to `tree_map` so a later unchanged subtree short-circuits [[buildTreePlan]].
    * Safe because rewritten ids are content-addressed (path-independent). */
  private def assemble(
      plan: TreePlan,
      resolved: IMap[(String, String), ObjectId],
      inserter: ObjectInserter,
      subtreeAcc: scala.collection.mutable.Map[String, String]
  ): ObjectId = plan match {
    case TreeExisting(id) => id
    case TreeBuild(origId, entries) =>
      val tf = new org.eclipse.jgit.lib.TreeFormatter
      // flatMap, not map: an excluded blob resolves to no entry at all, so the
      // rewritten tree simply does not contain that path.
      val resolvedEntries = entries.flatMap(resolveEntry(_, resolved, inserter, subtreeAcc))
      // jgit requires sorted entries (git tree order). The TreeWalk visited
      // them in tree order already, so we keep that order.
      resolvedEntries.foreach { e =>
        tf.append(e.name, e.mode, e.id)
        if (e.copyBytes) copyBlobIfMissing(e.id, inserter)
      }
      val newId = inserter.insert(tf)
      subtreeAcc.update(origId.name, newId.name)
      // Assembling a huge first-import tree can run for a long time without any
      // blob completing (it is mostly byte copies), so it counts as progress.
      progress(s"tree ${origId.name}")
      newId
  }

  private def resolveEntry(
      entry: EntryPlan,
      resolved: IMap[(String, String), ObjectId],
      inserter: ObjectInserter,
      subtreeAcc: scala.collection.mutable.Map[String, String]
  ): Option[ResolvedEntry] = entry match {
    case EntryUnchanged(name, mode, id, copyBytes) =>
      Some(ResolvedEntry(name, mode, id, copyBytes))
    case EntrySubtree(name, mode, sub) =>
      val subId = assemble(sub, resolved, inserter, subtreeAcc)
      Some(ResolvedEntry(name, mode, subId, copyBytes = false))
    case EntryBlobHit(name, mode, newId) =>
      // The new blob was inserted in a prior run; verify-or-copy is unneeded
      // because dst is the only consumer and we always insert before mapping.
      Some(ResolvedEntry(name, mode, newId, copyBytes = false))
    case EntryBlobMiss(name, mode, origId, fullPath) =>
      val key = (origId.name, fullPath)
      resolved.get(key) match {
        case Some(newId) => Some(ResolvedEntry(name, mode, newId, copyBytes = false))
        // Deliberately excluded (see readBlob): machine-generated, untokenizable
        // and unexplained, or denylisted. Drop the entry, so the file is absent
        // from the rewritten tree rather than present as raw source.
        case None
            if generatedKeys.contains(key) || untokenizableKeys.contains(key) ||
              denylistedKeys.contains(key) =>
          None
        // Anything else missing is a bug, and used to surface as a bare
        // NoSuchElementException. Keep it fatal and say which blob it was.
        case None =>
          throw new IllegalStateException(
            s"blob ${origId.name} ($fullPath) is a mask-matched miss with no resolution " +
              "and was excluded neither as generated, nor as untokenizable, nor by the " +
              "blob denylist")
      }
  }

  /** Make sure dst holds the original blob, inserting it at most once.
    *
    * `insertBlob` performs the insertion and returns (inserted id, size). It is
    * a function rather than an `Array[Byte]` so each caller chooses how to supply
    * the bytes, and it is only invoked when the blob really must be written:
    *
    *   tokenizer paths  already hold the bytes, because they just read them to
    *                    tokenize a masked source file. They insert from memory.
    *   pass-through     an unmasked blob of unknown size. It streams, because
    *                    getBytes throws LargeObjectException above JGit's
    *                    threshold. See streamBlobInto.
    *
    * All the deduplication, striped locking and accounting stays here, so the two
    * kinds of caller cannot drift apart.
    */
  private def ensureOriginalBlobAvailable(
      id: ObjectId,
      insertBlob: ObjectInserter => (ObjectId, Long),
      inserter: ObjectInserter
  ): Unit = {
    originalBlobCopyRequests.increment()
    // Copying a 435 GB repository's unmasked blobs is work, not a stall.
    progress(s"original blob ${id.name}")

    if (!deduplicateOriginalBlobs) {
      val (_, size) = insertBlob(inserter)
      originalBlobCopies.increment()
      originalBlobBytesCopied.add(size)
      return
    }

    val cacheSlot = id.hashCode() & (originalBlobCache.length() - 1)
    val cached = cachedOriginalBlob(cacheSlot, id)
    if (cached ne null) {
      originalBlobCacheHits.increment()
      recordOriginalBlobAlreadyPresent(cached.size)
      return
    }

    val stripe = originalBlobCopyLocks(cacheSlot & (originalBlobCopyLocks.length - 1))
    stripe.synchronized {
      val rechecked = cachedOriginalBlob(cacheSlot, id)
      if (rechecked ne null) {
        originalBlobCacheHits.increment()
        recordOriginalBlobAlreadyPresent(rechecked.size)
      } else {
        // A fresh destination cannot contain the object. Cache collisions and
        // incremental runs still require an existence check before insertion.
        val occupiedSlot = originalBlobCache.get(cacheSlot) ne null
        val existingSize =
          if (destinationMayContainObjects || occupiedSlot) {
            originalBlobDestinationLookups.increment()
            originalBlobSizeInDestination(id)
          } else None
        val size = existingSize match {
          case Some(value) =>
            recordOriginalBlobAlreadyPresent(value)
            value
          case None =>
            val (inserted, copied) = insertBlob(inserter)
            if (inserted != id)
              throw new IllegalStateException(
                s"original blob ${id.name} produced unexpected id ${inserted.name} while copying to dst")
            originalBlobCopies.increment()
            originalBlobBytesCopied.add(copied)
            copied
        }
        originalBlobCache.set(cacheSlot, OriginalBlobCacheEntry(id.copy(), size))
      }
    }
  }

  private def cachedOriginalBlob(slot: Int, id: ObjectId): OriginalBlobCacheEntry = {
    val entry = originalBlobCache.get(slot)
    if ((entry ne null) && entry.id == id) entry else null
  }

  private def originalBlobSizeInDestination(id: ObjectId): Option[Long] = {
    val reader = dst.newObjectReader()
    try {
      if (reader.has(id, OBJ_BLOB)) Some(reader.getObjectSize(id, OBJ_BLOB))
      else None
    } finally reader.close()
  }

  private def recordOriginalBlobAlreadyPresent(size: Long): Unit = {
    originalBlobAlreadyPresent.increment()
    originalBlobBytesAvoided.add(size)
  }

  private def copyBlobIfMissing(id: ObjectId, inserter: ObjectInserter): Unit = {
    // Streams: this blob did not match the mask, so its size is unbounded.
    ensureOriginalBlobAvailable(id, ins => streamBlobInto(id, ins), inserter)
  }

  /** Insert bytes a caller already holds. Used by the tokenizer paths, which read
    * the blob to tokenize it and so pay no second read. */
  private def insertHeldBytes(content: Array[Byte])(
      inserter: ObjectInserter
  ): (ObjectId, Long) =
    (inserter.insert(OBJ_BLOB, content), content.length.toLong)

  // -- commit construction -------------------------------------------------

  private def buildCommit(
      orig: RevCommit,
      parents: Vector[ObjectId],
      newTree: ObjectId,
      inserter: ObjectInserter
  ): ObjectId = {
    val cb = new CommitBuilder
    cb.setAuthor(orig.getAuthorIdent)
    cb.setCommitter(orig.getCommitterIdent)
    val enc = try orig.getEncoding catch { case _: Throwable => java.nio.charset.StandardCharsets.UTF_8 }
    cb.setEncoding(enc)
    cb.setMessage(FormerCommitFooter.append(orig.getFullMessage, orig.getId.name))
    cb.setTreeId(newTree)
    cb.setParentIds(parents: _*)
    inserter.insert(cb)
  }

  // -- ref projection ------------------------------------------------------

  // Project every src ref into dst. Returns the number of refs written.
  // After projection, prunes any dst refs under refs/heads/ or refs/tags/
  // that no longer exist in src (mirror semantics), and removes corresponding
  // tag_map rows. Out-of-band refs (refs/notes/ etc) are left alone.
  private def projectRefs(revWalk: RevWalk): Int = {
    val tagInserter = dst.newObjectInserter()
    try {
      val refs = src.getRefDatabase.getRefs.asScala.toVector
      // One DB transaction covers every tag_map put/delete this projection
      // produces. Individual jgit ref updates are atomic at the git level
      // but not collectively; a re-run reconciles any partial ref state.
      mapping.inTx {
        val written = refs.foldLeft(0) { (acc, ref) =>
          progress(s"ref ${ref.getName}")
          if (projectOneRef(ref, revWalk, tagInserter)) acc + 1 else acc
        }
        tagInserter.flush()
        copyHeadSymref()
        pruneStaleRefs(refs.map(_.getName).toSet)
        written
      }
    } finally tagInserter.close()
  }

  // True if the ref was projected.
  // Records every non-symbolic ref under refs/heads/, refs/tags/, refs/remotes/
  // into ref_map; symbolic refs are linked but not recorded.
  private def projectOneRef(ref: Ref, revWalk: RevWalk, tagInserter: ObjectInserter): Boolean = {
    val name = ref.getName
    if (name == Constants.HEAD) return false  // handled separately
    if (ref.isSymbolic) {
      // Mirror symbolic refs as-is (target is another ref name).
      val target = ref.getTarget.getName
      val ru = dst.getRefDatabase.newUpdate(name, false)
      ru.link(target)
      return true
    }

    val obj = Option(ref.getObjectId).map(id => safelyParse(revWalk, id))
    obj match {
      case Some(Some(tag: RevTag)) =>
        val peeled = peelToCommit(tag, revWalk)
        peeled.flatMap(c => mapping.getCommit(c.getId.name).map(nc => (c.getId.name, nc))) match {
          case Some((origCommitSha, newCommitSha)) =>
            val newTagId = rewriteAnnotatedTag(tag, ObjectId.fromString(newCommitSha), tagInserter)
            writeRef(name, newTagId)
            if (isTrackedRef(name)) {
              mapping.putRef(Mapping.RefRow(
                refName    = name,
                kind       = "annotated_tag",
                origTarget = tag.getId.name,
                newTarget  = newTagId.name,
                origCommit = origCommitSha,
                newCommit  = newCommitSha
              ))
            }
            true
          case None => false
        }
      case Some(Some(commit: RevCommit)) =>
        mapping.getCommit(commit.getId.name) match {
          case Some(newSha) =>
            writeRef(name, ObjectId.fromString(newSha))
            if (isTrackedRef(name)) {
              val rowKind = if (isTagName(name)) "lightweight_tag" else "head"
              mapping.putRef(Mapping.RefRow(
                refName    = name,
                kind       = rowKind,
                origTarget = commit.getId.name,
                newTarget  = newSha,
                origCommit = commit.getId.name,
                newCommit  = newSha
              ))
            }
            true
          case None => false
        }
      case _ => false
    }
  }

  private def isTagName(refName: String): Boolean    = refName.startsWith("refs/tags/")
  private def isHeadName(refName: String): Boolean   = refName.startsWith("refs/heads/")
  private def isRemoteName(refName: String): Boolean = refName.startsWith("refs/remotes/")
  // Namespaces we both record in ref_map and prune for mirror semantics.
  private def isTrackedRef(refName: String): Boolean =
    isHeadName(refName) || isTagName(refName) || isRemoteName(refName)

  // Delete dst refs in tracked namespaces that aren't in `seen`, and remove
  // their ref_map rows. Out-of-band refs (e.g. refs/notes/*) are left alone.
  private def pruneStaleRefs(seenSrcRefNames: Set[String]): Unit = {
    val dstRefs = dst.getRefDatabase.getRefs.asScala.toVector
    val stale = dstRefs.filter { r =>
      val n = r.getName
      isTrackedRef(n) && !seenSrcRefNames.contains(n)
    }
    stale.foreach { r =>
      val n = r.getName
      val ru = dst.getRefDatabase.newUpdate(n, true)
      ru.setForceUpdate(true)
      ru.delete()
      mapping.deleteRef(n)
    }
  }

  private def safelyParse(revWalk: RevWalk, id: ObjectId): Option[org.eclipse.jgit.revwalk.RevObject] =
    try Some(revWalk.parseAny(id))
    catch {
      case _: org.eclipse.jgit.errors.MissingObjectException => None
      case _: Throwable => None
    }

  private def peelToCommit(tag: RevTag, revWalk: RevWalk): Option[RevCommit] = {
    var current: org.eclipse.jgit.revwalk.RevObject = tag
    while (current.isInstanceOf[RevTag]) {
      val inner = current.asInstanceOf[RevTag].getObject
      current = safelyParse(revWalk, inner).orNull
      if (current eq null) return None
    }
    current match {
      case c: RevCommit => Some(c)
      case _            => None
    }
  }

  private def rewriteAnnotatedTag(orig: RevTag, mappedCommit: ObjectId, inserter: ObjectInserter): ObjectId = {
    val tb = new TagBuilder
    tb.setTag(orig.getTagName)
    // null/malformed tagger on some ancient kernel tags -> TagBuilder NPEs; sentinel keeps projection deterministic
    tb.setTagger(Option(orig.getTaggerIdent).getOrElse(new org.eclipse.jgit.lib.PersonIdent("unknown", "unknown", 0L, 0)))
    tb.setMessage(orig.getFullMessage)
    tb.setObjectId(mappedCommit, OBJ_COMMIT)
    inserter.insert(tb)
  }

  private def writeRef(name: String, newId: ObjectId): Unit = {
    val ru = dst.getRefDatabase.newUpdate(name, true)
    ru.setNewObjectId(newId)
    ru.setForceUpdate(true)
    ru.update()
    ()
  }

  private def copyHeadSymref(): Unit = {
    val srcHead = src.exactRef(Constants.HEAD)
    if ((srcHead ne null) && srcHead.isSymbolic) {
      val target = srcHead.getTarget.getName
      val ru = dst.getRefDatabase.newUpdate(Constants.HEAD, false)
      ru.link(target)
      ()
    }
  }
}

object Walker {

  /** Size at or above which JGit will not materialise a blob, so its bytes cannot
    * be handed to the tokenizer.
    *
    * This is a TRIGGER, not a reason to exclude anything: reaching it makes
    * [[Walker#readBlob]] classify the blob's provenance, and the classification is
    * what decides between a defensible exclusion and a reported hole. A file is not
    * generated because it is large.
    *
    * Deliberately not a number of our own choosing. It is read from JGit, which
    * refuses to materialise any object at or above its stream-file threshold
    * (`core.streamFileThreshold`, default 50 MiB) — `ObjectLoader.getBytes`
    * throws `LargeObjectException` instead. Picking a larger constant, as an
    * earlier draft of this fix did with 64 MiB, leaves a band (50-64 MiB) in
    * which the size check passes and `getBytes` then throws anyway; picking a
    * smaller one would silently drop files JGit could have handled.
    *
    * Reading a *fresh* `WindowCacheConfig` gives JGit's default rather than
    * whatever is currently installed, which cannot be read back through public
    * API. That is why this is only a fast path and [[Walker#readBlob]] also
    * catches `LargeObjectException`: with the threshold lowered below the
    * default, the catch is what handles the band.
    *
    * `>=`, not `>`, because that is JGit's own comparison: `Pack.load` returns a
    * streaming (non-materialisable) loader once `size >= streamFileThreshold`.
    */
  private[blobexec] val MaxBlobBytes: Long =
    new org.eclipse.jgit.storage.file.WindowCacheConfig().getStreamFileThreshold.toLong

  /** Pure form of the TRIGGER, so it can be checked without a repo. Deliberately
    * not the exclusion decision: it answers "can JGit produce these bytes?" and
    * nothing else. [[Walker#readBlob]] decides what to do about that, from the
    * blob's provenance. */
  private[blobexec] def isOversized(sizeBytes: Long): Boolean = sizeBytes >= MaxBlobBytes

  /** Bytes of a blob's head read to classify it when JGit will not materialise the
    * whole object.
    *
    * 8 KiB, because a generator banner is a header: MSVC's `#import`/dumpcpp writes
    * "compiler-generated file ... DO NOT EDIT!" at the top, and every convention in
    * [[GeneratedMarkers]] is a header convention. Bounded on purpose — the point of
    * classifying from a prefix is that it costs the same on a 98 MB object as on a
    * 1 KB one, so provenance can be established without the memory the whole blob
    * would take. */
  private[blobexec] val ProvenancePrefixBytes: Int = 8 * 1024

  /** Lowercase substrings that identify a file as machine-generated.
    *
    * Header conventions rather than cleverness, because this list is the thing a
    * paper has to defend: `DO NOT EDIT` and "generated by" are what generators
    * actually write, `@generated` is the codemod convention, and `dumpcpp` names
    * the tool in the one case the corpus really hit.
    *
    * The two error directions are deliberately asymmetric, which is what lets the
    * list stay short and literal. A false positive costs a label on a blob that
    * could not have been tokenized here anyway. A false negative costs nothing at
    * all: the unclassified path is reported loudly, counted separately and gates
    * publication, so a missed marker becomes a question asked, not data lost. */
  private[blobexec] val GeneratedMarkers: Vector[String] = Vector(
    "do not edit",
    "do not modify",
    "@generated",
    "autogenerated",
    "auto-generated",
    "automatically generated",
    "generated automatically",
    "generated by",
    "machine generated",
    "machine-generated",
    "dumpcpp"
  )

  /** Which marker matched, and the header line it matched in. The line travels
    * with the marker so a log line can quote its evidence instead of asserting a
    * verdict — that quotation is what makes the exclusion checkable by a reader. */
  final case class GeneratedEvidence(marker: String, line: String)

  /** Longest header line quoted as evidence: long enough for a real banner, short
    * enough that a single-line generated file cannot print 8 KiB into a log. */
  private val MaxEvidenceLineChars = 200

  /** Pure classifier, so provenance is testable without a repository: does this
    * header prefix identify the blob as machine-generated, and on what evidence?
    *
    * ISO-8859-1, not UTF-8: the prefix is arbitrary bytes cut at a fixed offset, so
    * a decoder that can fail, or that swallows a split multi-byte character, would
    * be deciding provenance by accident. Every marker is ASCII, so the byte-per-char
    * decode finds all of them and can never throw.
    *
    * Line by line, and the first match wins, so the reported evidence is the line a
    * human would have looked at.
    *
    * The [[ProvenancePrefixBytes]] bound is enforced here as well as by the caller
    * that reads the blob, so "provenance is decided from a header" is a property of
    * this function rather than of one call site. A marker further down a file than
    * that is not a header and does not classify it. */
  private[blobexec] def generatedEvidence(prefix: Array[Byte]): Option[GeneratedEvidence] =
    new String(
      prefix, 0, math.min(prefix.length, ProvenancePrefixBytes),
      java.nio.charset.StandardCharsets.ISO_8859_1
    ).linesIterator
      .flatMap { line =>
        val lowered = line.toLowerCase(java.util.Locale.ROOT)
        GeneratedMarkers.iterator
          .filter(lowered.contains)
          .map(marker => GeneratedEvidence(marker, line.trim.take(MaxEvidenceLineChars)))
      }
      .nextOption()

  private val OriginalBlobCacheSize = 1 << 16
  private val OriginalBlobCopyLockCount = 256

  private final case class OriginalBlobCacheEntry(id: ObjectId, size: Long)

  // Pure data describing how to assemble a rewritten tree. Hoisted out of
  // `Walker` so case-class pattern matches don't need to check an outer
  // reference (scalac warning 'outer reference in this type test...').

  sealed trait TreePlan
  final case class TreeExisting(newId: ObjectId) extends TreePlan
  final case class TreeBuild(origId: ObjectId, entries: Vector[EntryPlan]) extends TreePlan

  sealed trait EntryPlan { def name: String; def mode: FileMode }
  /** Verbatim pass-through: non-matching blob, gitlink, etc. The bytes are
    * copied into dst when `copyBytes` is true (regular blobs) and skipped
    * otherwise (gitlinks reference commits in other repos). */
  final case class EntryUnchanged(name: String, mode: FileMode, id: ObjectId, copyBytes: Boolean) extends EntryPlan
  final case class EntrySubtree(name: String, mode: FileMode, plan: TreePlan) extends EntryPlan
  final case class EntryBlobHit(name: String, mode: FileMode, newId: ObjectId) extends EntryPlan
  /** Matching blob whose `(origSha, fullPath)` is not yet in `blob_map`. */
  final case class EntryBlobMiss(name: String, mode: FileMode, origId: ObjectId, fullPath: String) extends EntryPlan

  /** A matching blob that needs to go through the external command. We
    * carry both the basename (`filename`, exposed as `BFG_FILENAME` for
    * backward compatibility with `tokenBySha.pl`) and the full repo-root
    * relative path (`fullPath`, exposed as `BFG_PATH` and used as the
    * `blob_map` cache key). */
  final case class BlobMissTask(origId: ObjectId, filename: String, fullPath: String)

  final case class ResolvedEntry(name: String, mode: FileMode, id: ObjectId, copyBytes: Boolean)

  /** What a `buildTreePlan` call discovered alongside the plan itself. */
  final case class PlanArtifacts(
      misses: Vector[BlobMissTask],
      hitCount: Int,
      unchangedBlobs: Vector[(ObjectId, String)]  // (orig_id, full_path)
  )

  object PlanArtifacts {
    val empty: PlanArtifacts = PlanArtifacts(Vector.empty, 0, Vector.empty)
  }

  // -- pipelined-walk support ------------------------------------------------

  /** Bounded look-ahead: the maximum number of commits the producer may run
    * ahead of the consumer. Also caps the in-flight tokenization map, keeping
    * memory flat regardless of history length. Sized well above `parallelism`
    * so the pool never idles waiting for the producer, but small enough that
    * memory stays modest. */
  private val PipelineWindow = 32

  /** Window with no completed work of any kind after which the run is declared
    * stalled. Generous because it is a watchdog, not a schedule: every child is
    * separately hard-bounded at `--blob-timeout`. */
  private[blobexec] val DefaultStallTimeoutSeconds = 1800

  /** Exit status when the watchdog kills a stalled run. Distinct from 4 (a blob
    * timed out but the walk finished) so the runner can tell them apart. */
  private[blobexec] val StalledExitStatus = 5

  /** Pure form of the watchdog's decision, so it can be tested without halting
    * a JVM: has more than `stallTimeoutSeconds` passed with no progress? */
  private[blobexec] def isStalled(
      nowNanos: Long,
      lastProgressNanos: Long,
      stallTimeoutSeconds: Int
  ): Boolean =
    (nowNanos - lastProgressNanos) > math.max(1, stallTimeoutSeconds).toLong * 1000000000L

  /** Immutable snapshot of a RevCommit, so the consumer never reaches back
    * into the single-threaded RevWalk that the producer is iterating. */
  final case class CommitData(
      origCommitSha: String,
      parentOrigShas: Vector[String],
      origTreeId: ObjectId,
      author: PersonIdent,
      committer: PersonIdent,
      encoding: java.nio.charset.Charset,
      fullMessage: String
  )

  /** What [[Walker.resolveMisses]] hands back: `ids` is what the tree
    * references, `timedOutKeys` is the subset that must not be persisted. */
  final case class MissResolution(
      ids: IMap[(String, String), ObjectId],
      abort: Boolean,
      timedOutKeys: Set[(String, String)]
  ) {
    /** The rows that are genuinely correct and worth keeping: content-addressed
      * tokenizations of blobs that did not time out. */
    def persistable: IMap[(String, String), ObjectId] = ids -- timedOutKeys
  }

  /** Per-blob worker result handed from a pool thread back to the consumer. */
  sealed trait BlobResult
  object BlobResult {
    /** Replace or Skip: the id the rewritten tree should reference. */
    final case class Resolved(newId: ObjectId) extends BlobResult
    /** abort-on-error tripped by a non-zero command exit. */
    final case class Aborted(stderr: String, exitCode: Int) extends BlobResult
    /** The command was killed on its budget. The tree still references the
      * original blob (dst stays self-consistent), but this is deliberately not
      * a `Resolved`: nothing about this blob, the trees containing it, or its
      * commit may be persisted, or a re-run would treat raw source as done. */
    final case class TimedOut(origId: ObjectId) extends BlobResult
    /** Deliberately excluded from the rewrite: machine-generated, untokenizable
      * and unexplained, or denylisted (see [[Walker#readBlob]]). It is
      * deliberately neither `Resolved` nor `TimedOut`: it contributes no id, so
      * every `collect` that builds a tree/blob_map map drops it and `resolveEntry`
      * omits the path. Whether the RUN is still publishable is not decided here
      * but by the counter the exclusion was recorded under — an explained
      * exclusion does not gate, an unexplained one does. */
    final case class Excluded(origId: ObjectId) extends BlobResult
  }

  /** Items flowing producer -> consumer across the bounded queue. */
  sealed trait QueueItem
  final case class CommitItem(
      data: CommitData,
      plan: TreePlan,
      artifacts: PlanArtifacts,
      missFutures: Map[(String, String), Future[BlobResult]]
  ) extends QueueItem
  case object EndOfWalk extends QueueItem

  // -- tree-parallel-walk support (Design B) ---------------------------------

  /** Result of the off-thread tree assembly for one commit. `Built` carries
    * the new top-tree id plus the resolved matching-blob map (so the consumer
    * records `blob_map` without re-awaiting); `Aborted` propagates an
    * abort-on-error that a blob tokenization observed. */
  sealed trait TreeResult
  object TreeResult {
    final case class Built(
        newTreeId: ObjectId,
        resolved: IMap[(String, String), ObjectId],
        subtrees: IMap[String, String],
        /** Blobs in this commit whose command was killed on its budget. The tree
          * is usable for this run's output, but if this is non-empty neither it
          * nor the commit may be recorded, or the next run skips the retry. */
        timedOutKeys: Set[(String, String)]
    ) extends TreeResult
    final case class Aborted(stderr: String, exitCode: Int) extends TreeResult
  }

  /** Items flowing producer -> consumer in the tree-parallel pipeline. The
    * assembled tree arrives as a future the consumer awaits in order. */
  sealed trait TreeQueueItem
  final case class CommitTreeItem(
      data: CommitData,
      artifacts: PlanArtifacts,
      blobKeys: Set[(String, String)],
      treeFuture: Future[TreeResult]
  ) extends TreeQueueItem
  case object EndOfTreeWalk extends TreeQueueItem
}
