package cregit.blobexec

import org.eclipse.jgit.lib.Constants.{OBJ_BLOB, OBJ_COMMIT}
import org.eclipse.jgit.lib._
import org.eclipse.jgit.revwalk.{RevCommit, RevSort, RevTag, RevWalk}
import org.eclipse.jgit.treewalk.{CanonicalTreeParser, TreeWalk}

import java.util.concurrent.{ArrayBlockingQueue, BlockingQueue, ConcurrentHashMap, Executors, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference, AtomicReferenceArray, LongAdder}
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
    blobTimeoutSeconds: Int = BlobExec.DefaultTimeoutSeconds
) {
  import Walker._

  // Serializes `mapping` access (one non-thread-safe JDBC connection): the
  // pipelined producer reads while the consumer writes. Tokenizer workers never
  // touch it, so the CPU-heavy path stays lock-free.
  private val dbLock = new AnyRef

  private val blobCommandExecutions          = new LongAdder
  private val blobsTimedOut                  = new LongAdder
  private val originalBlobCopyRequests       = new LongAdder
  private val originalBlobCopies             = new LongAdder
  private val originalBlobAlreadyPresent     = new LongAdder
  private val originalBlobCacheHits          = new LongAdder
  private val originalBlobDestinationLookups = new LongAdder
  private val originalBlobBytesCopied        = new LongAdder
  private val originalBlobBytesAvoided       = new LongAdder

  /** Finite backstop for the consumer's `Await.result` calls.
    *
    * `Duration.Inf` was the second half of the tencentkona-21 stall: with the
    * child now bounded, a worker that still never completes (killed thread,
    * abandoned io thread) would park the consumer forever and the run would go
    * silent again. The budget must never cut off healthy work, so it is derived
    * from the worst case the per-blob timeout permits: `blobCount` blobs run
    * `parallelism` at a time, each bounded by `blobTimeoutSeconds`; doubled,
    * because futures are shared across the pipeline window and the pool may be
    * serving neighbouring commits, plus a fixed floor for read/insert time.
    * Overrunning it means something is wedged beyond the per-blob kill, and a
    * loud TimeoutException beats a silent multi-day stall. */
  private def awaitBudget(blobCount: Int): Duration =
    Duration(Walker.awaitBudgetSeconds(blobCount, parallelism, blobTimeoutSeconds), SECONDS)

  private val originalBlobCache = new AtomicReferenceArray[OriginalBlobCacheEntry](OriginalBlobCacheSize)
  private val originalBlobCopyLocks = Array.fill[AnyRef](OriginalBlobCopyLockCount)(new AnyRef)

  // -- entry point ---------------------------------------------------------

  def run(): WalkStats = shard match {
    case Some((k, n)) => runShard(k, n)
    case None         => runFull()
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
    var commits  = 0
    var blobsRun = 0
    var blobsHit = 0

    try {
      val it = sliceIds.iterator
      while (it.hasNext && !aborted) {
        val rc = procWalk.parseCommit(it.next())
        val origTreeId = rc.getTree.getId

        // Pure plan for this commit's tree (short-circuits on a tree_map hit,
        // incl. the optional --warm fallback).
        val (plan, artifacts) = buildTreePlan(origTreeId, pathPrefix = "")
        val (resolved, abortFromBlob) = resolveMisses(artifacts.misses, pool)

        if (abortFromBlob) {
          aborted = true
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

  // -- ref preparation -----------------------------------------------------

  /** For every commit sha already in `commit_map` that still exists in src,
    * mark it uninteresting so the walk skips it and its ancestors. */
  private def markUninteresting(revWalk: RevWalk): Unit = {
    mapping.allCommitOrigShas.foreach { sha =>
      val id = ObjectId.fromString(sha)
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
    var commits     = 0
    var blobsRun    = 0
    var blobsHit    = 0

    try {
      val iter = revWalk.iterator()
      while (iter.hasNext && !aborted) {
        val rc = iter.next()
        val origCommitSha = rc.getId.name

        // Build a pure plan for this commit's tree.
        val (plan, artifacts) = buildTreePlan(rc.getTree.getId, pathPrefix = "")

        // Run all matching-blob misses in parallel.
        val (resolved, abortFromBlob) = resolveMisses(artifacts.misses, pool)

        if (abortFromBlob) {
          aborted = true
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
    val producerError = new AtomicReference[Throwable](null)

    // -- producer: iterate the walk in order, plan trees, submit blob work --
    val producer = new Thread(new Runnable {
      def run(): Unit = {
        try {
          val iter = revWalk.iterator()
          while (iter.hasNext && !aborted.get()) {
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
            if (aborted.get()) {
              // Draining after abort/producer-error: discard until EndOfWalk.
            } else {
              // Await only this commit's futures (typically already complete),
              // under a finite backstop rather than Duration.Inf.
              val budget  = awaitBudget(missFutures.size)
              val results = missFutures.map { case (k, f) => k -> Await.result(f, budget) }
              results.values.collectFirst { case a: BlobResult.Aborted => a } match {
                case Some(_) =>
                  aborted.set(true)  // stop the producer; drain the remainder
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
    val producerError = new AtomicReference[Throwable](null)

    // -- producer: plan trees, submit blob work, chain tree assembly --------
    val producer = new Thread(new Runnable {
      def run(): Unit = {
        try {
          val iter = revWalk.iterator()
          while (iter.hasNext && !aborted.get()) {
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
            if (aborted.get()) {
              // Draining after abort/producer-error: discard until EndOfTreeWalk.
            } else {
              // The assembly (and any abort it observed) is already done off-thread.
              Await.result(treeFuture, awaitBudget(blobKeys.size)) match {
                case TreeResult.Aborted(_, _) =>
                  aborted.set(true)  // stop the producer; drain the remainder
                case TreeResult.Built(newTreeId, resolved, subtrees) =>
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
        val resolved: IMap[(String, String), ObjectId] =
          blobResults.iterator.collect { case (k, BlobResult.Resolved(id)) => k -> id }.toMap
        val inserter = dst.newObjectInserter()
        try {
          val subtreeMap = scala.collection.mutable.Map.empty[String, String]
          val newTreeId = assemble(plan, resolved, inserter, subtreeMap)
          inserter.flush()
          TreeResult.Built(newTreeId, resolved, subtreeMap.toMap)
        } finally inserter.close()
    }
  }

  /** Worker body run on the pool: read the blob, run the external command,
    * and return the id the rewritten tree should reference. Mirrors the
    * per-task logic in [[resolveMisses]] (a Skip re-inserts the original bytes
    * into dst). Never touches `mapping`, keeping the hot path lock-free. */
  private def executeBlobTask(task: BlobMissTask): BlobResult = {
    val bytes = readBlob(task.origId)
    val workerInserter = dst.newObjectInserter()
    try {
      blobCommandExecutions.increment()
      val outcome = BlobExec.run(
        bytes        = bytes,
        origSha      = task.origId.name,
        filename     = task.filename,
        fullPath     = task.fullPath,
        command      = command,
        abortOnError = abortOnError,
        inserter     = workerInserter,
        timeoutSeconds = blobTimeoutSeconds,
        onTimeout      = () => blobsTimedOut.increment()
      )
      val res = outcome match {
        case BlobExec.Outcome.Skip =>
          ensureOriginalBlobAvailable(task.origId, insertHeldBytes(bytes), workerInserter)
          BlobResult.Resolved(task.origId)
        case BlobExec.Outcome.Replace(newId) =>
          BlobResult.Resolved(newId)
        case BlobExec.Outcome.Abort(stderr, code) =>
          BlobResult.Aborted(stderr, code)
      }
      workerInserter.flush()
      res
    } finally workerInserter.close()
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

  /** Run each miss through the external command on the pool. Returns the
    * resolved map and an aborted flag. */
  private def resolveMisses(
      misses: Vector[BlobMissTask],
      pool: java.util.concurrent.ExecutorService
  )(implicit ec: ExecutionContext): (IMap[(String, String), ObjectId], Boolean) = {
    if (misses.isEmpty) return (IMap.empty, false)

    // De-dup so we don't run the command twice for the same key within a
    // single commit (e.g. the same (blob, path) reached via two subtrees).
    val unique = misses.map(m => (m.origId.name, m.fullPath) -> m).toMap.values.toVector

    val futures = unique.map { task =>
      Future {
        val bytes = readBlob(task.origId)
        val workerInserter = dst.newObjectInserter()
        try {
          blobCommandExecutions.increment()
          val outcome = BlobExec.run(
            bytes        = bytes,
            origSha      = task.origId.name,
            filename     = task.filename,
            fullPath     = task.fullPath,
            command      = command,
            abortOnError = abortOnError,
            inserter     = workerInserter,
            timeoutSeconds = blobTimeoutSeconds,
            onTimeout      = () => blobsTimedOut.increment()
          )
          // For Skip outcomes (identical output OR non-zero exit with
          // abortOnError=false) we keep the original blob id, so the dst
          // tree will reference it — meaning the bytes must exist in dst.
          // For Replace outcomes the worker has already inserted the new
          // blob. For Abort we do nothing (caller short-circuits).
          outcome match {
            case BlobExec.Outcome.Skip => ensureOriginalBlobAvailable(task.origId, insertHeldBytes(bytes), workerInserter)
            case _                     => ()
          }
          workerInserter.flush()
          (task, outcome)
        } finally workerInserter.close()
      }
    }

    val results = Await.result(Future.sequence(futures), awaitBudget(futures.size))

    val abort = results.exists { case (_, o) => o.isInstanceOf[BlobExec.Outcome.Abort] }
    if (abort) (IMap.empty, true)
    else {
      val resolved = results.iterator.collect {
        case (task, BlobExec.Outcome.Replace(newId)) =>
          (task.origId.name, task.fullPath) -> newId
        case (task, BlobExec.Outcome.Skip) =>
          // identical or non-zero-exit (with abortOnError=false): keep orig
          (task.origId.name, task.fullPath) -> task.origId
      }.toMap
      (resolved, false)
    }
  }

  private def readBlob(id: ObjectId): Array[Byte] = {
    val r = src.newObjectReader()
    try r.open(id, OBJ_BLOB).getBytes
    finally r.close()
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
      val resolvedEntries = entries.map(resolveEntry(_, resolved, inserter, subtreeAcc))
      // jgit requires sorted entries (git tree order). The TreeWalk visited
      // them in tree order already, so we keep that order.
      resolvedEntries.foreach { e =>
        tf.append(e.name, e.mode, e.id)
        if (e.copyBytes) copyBlobIfMissing(e.id, inserter)
      }
      val newId = inserter.insert(tf)
      subtreeAcc.update(origId.name, newId.name)
      newId
  }

  private def resolveEntry(
      entry: EntryPlan,
      resolved: IMap[(String, String), ObjectId],
      inserter: ObjectInserter,
      subtreeAcc: scala.collection.mutable.Map[String, String]
  ): ResolvedEntry = entry match {
    case EntryUnchanged(name, mode, id, copyBytes) =>
      ResolvedEntry(name, mode, id, copyBytes)
    case EntrySubtree(name, mode, sub) =>
      val subId = assemble(sub, resolved, inserter, subtreeAcc)
      ResolvedEntry(name, mode, subId, copyBytes = false)
    case EntryBlobHit(name, mode, newId) =>
      // The new blob was inserted in a prior run; verify-or-copy is unneeded
      // because dst is the only consumer and we always insert before mapping.
      ResolvedEntry(name, mode, newId, copyBytes = false)
    case EntryBlobMiss(name, mode, origId, fullPath) =>
      val newId = resolved((origId.name, fullPath))
      ResolvedEntry(name, mode, newId, copyBytes = false)
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

  /** Hard ceiling on [[awaitBudgetSeconds]], so the arithmetic stays inside
    * `Duration`'s nanosecond range on pathologically large commits. */
  private[blobexec] val MaxAwaitBudgetSeconds = 30L * 24L * 3600L  // 30 days

  /** Pure form of the per-commit await budget, in seconds; see the instance
    * method `Walker.awaitBudget` for why it is shaped this way. Extracted so
    * the formula can be tested without standing up a repository. */
  private[blobexec] def awaitBudgetSeconds(
      blobCount: Int,
      parallelism: Int,
      blobTimeoutSeconds: Int
  ): Long = {
    val p       = math.max(1, parallelism)
    val waves   = (math.max(0, blobCount).toLong + p - 1) / p
    val perBlob = math.max(1, blobTimeoutSeconds).toLong
    val factor  = 2L * waves + 10L
    // Saturating multiply: `factor * perBlob` overflows Long on absurd inputs
    // (a huge commit plus a huge --blob-timeout), and a negative budget would
    // make `Await.result` throw immediately instead of waiting. Same result as
    // the plain product for every input that does not overflow.
    if (factor > MaxAwaitBudgetSeconds / perBlob) MaxAwaitBudgetSeconds
    else math.min(factor * perBlob, MaxAwaitBudgetSeconds)
  }

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

  /** Per-blob worker result handed from a pool thread back to the consumer. */
  sealed trait BlobResult
  object BlobResult {
    /** Replace or Skip: the id the rewritten tree should reference. */
    final case class Resolved(newId: ObjectId) extends BlobResult
    /** abort-on-error tripped by a non-zero command exit. */
    final case class Aborted(stderr: String, exitCode: Int) extends BlobResult
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
        subtrees: IMap[String, String]
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
