package cregit.blobexec

import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder

import java.nio.file.{Files, Paths}

/**
 * From-scratch rewriter for the cregit pipeline (step 2). Replaces the
 * previous in-place BFG-based rewrite with a jgit walk that builds a new
 * destination repo and persists `(orig → new)` mappings to SQLite, so a
 * subsequent invocation can resume incrementally.
 *
 *   blobExec [--abort-on-error] [--pipeline | --pipeline-trees | --shard=K/N] [--warm=<db>] <src.git> <dst.git> <db.sqlite> <command> <fileMaskRegex>
 *
 *   --abort-on-error  exit immediately (status 2) on the first non-zero
 *                     exit from <command>, instead of skipping that blob
 *   <src.git>         path to the bare source repo (read-only)
 *   <dst.git>         path to the bare destination repo (created if missing)
 *   <db.sqlite>       path to the SQLite mapping file (created if missing)
 *   <command>         absolute path to the per-blob script to run
 *   <fileMaskRegex>   regex matched against each blob's filename
 *
 * For each blob whose filename matches `fileMaskRegex`, `command` is invoked
 * with the blob bytes on stdin and env vars `BFG_BLOB` (orig sha) +
 * `BFG_FILENAME`. Its stdout becomes the new blob. Non-zero exit or
 * identical-output leaves the blob unchanged. The new commit message gets
 * `Former-commit-id: <orig-sha>` appended (BFG-compatible).
 */
object Main {

  /** Memo `meta` key holding the cumulative number of blobs whose command was
    * killed for exceeding its budget. Durable because the skip is durable. */
  private[blobexec] val BlobsTimedOutMetaKey = "blobs_timed_out"

  /** Exit status when any blob in this memo has ever timed out: the walk itself
    * succeeded, but the output is incomplete and must not be validated. */
  private[blobexec] val TimedOutExitStatus = 4

  /** Exit status when any blob's tokenizer reported a parser crash: srcML died on
    * a signal, or produced no tokens. Distinct from [[TimedOutExitStatus]] on
    * purpose — both block publication, but they need different remedies, and an
    * operator who cannot tell them apart will reach for --blob-timeout, which does
    * nothing whatsoever for a segfault.
    *
    * 6, not 5: 5 is already [[Walker.StalledExitStatus]]. The statuses in use are
    * 1 (usage), 2 (abort), 3 (mask changed), 4 (timed out), 5 (stalled), so 6 is
    * the next free one. `parserCrashStatusIsUnique` in MainOptionsSpec pins that. */
  private[blobexec] val ParserCrashedExitStatus = 6

  /** Exit status when a blob JGit cannot materialise could not be classified as
    * machine-generated: on the evidence, a large hand-written source file that this
    * run excluded without being able to say why.
    *
    * Its own status, distinct from [[TimedOutExitStatus]] and
    * [[ParserCrashedExitStatus]], because the remedy is again different: not more
    * time, not a srcML fix, but a decision about one file — confirm it is generated
    * (then a header marker or the denylist covers it), or accept that tokenizing it
    * needs BlobExec's stdio spooled to disk. Telling an operator "timed out" here
    * would send them to --blob-timeout for a file that never ran.
    *
    * 7, the next free status: 1 usage, 2 abort, 3 mask changed, 4 timed out, 5
    * stalled, 6 parser crash. "the untokenizable status collides with no other
    * blobExec exit status", in MainOptionsSpec, pins that. */
  private[blobexec] val UntokenizableExitStatus = 7

  /** The process exit status for a finished walk.
    *
    * A function, and taking the whole [[WalkStats]], so that "which counters gate
    * publication" is a property something can be asserted about rather than a
    * conditional buried in `main`. The dividing line is not severity and not
    * determinism, it is whether the dataset's gap can be EXPLAINED. Deliberately
    * NOT gating:
    *
    *   - `blobsGeneratedExcluded` — jgit will not materialise the object and its own
    *     header names a generator; the provenance is quoted per blob, and a
    *     generated file carries no contributor behaviour to lose.
    *   - `blobsDenylisted` — srcML 1.1.0 does not terminate on it; diagnosed, with
    *     an upstream citation, in a data file a paper can cite. Gating on this
    *     would mean tencent__tencentkona-21 could never publish, while telling us
    *     nothing we do not already know.
    *
    * The distinction is the whole point of the denylist: a timeout is a hang
    * nobody has explained yet, and that must keep blocking publication.
    *
    * `blobsParserCrashed` joins the gating set for exactly that reason. A srcML
    * signal death is, today, a defect nobody has explained — so it belongs with a
    * timeout, not with the denylist. Once a specific crashing blob is diagnosed and
    * cited it can be moved onto the denylist, which is the documented way for a
    * known third-party parser bug to stop blocking publication. Until then, failing
    * closed is the point: the alternative is the 0-byte tokenization that shipped.
    *
    * `blobsUntokenizable` joins the gating set for the same reason, and it is the
    * counter this function used to be wrong about. Under the old size-only gate that
    * blob was counted as `blobsOversized` and published around: a file nobody had
    * shown to be generated, dropped from the dataset because of its size, while the
    * comment beside the code claimed provenance was the reason. An exclusion whose
    * reason cannot be stated is a hole, so it blocks publication until someone
    * states the reason — by confirming the file is generated, denylisting it with a
    * citation, or making it tokenizable.
    *
    * A timeout is checked first only because it is the older and broader signal;
    * when several fire, any of those statuses correctly means "do not publish".
    */
  private[blobexec] def exitStatus(stats: WalkStats): Int =
    if (stats.aborted) 2
    else if (stats.blobsTimedOut > 0) TimedOutExitStatus
    else if (stats.blobsParserCrashed > 0) ParserCrashedExitStatus
    else if (stats.blobsUntokenizable > 0) UntokenizableExitStatus
    else 0

  /** Value parser for the `--blob-timeout=` / `--stall-timeout=` seconds: a
    * positive whole number, else None (which the caller reports and exits 1 on).
    * Zero and negatives are rejected rather than read as "no limit" — an
    * unbounded blob is the defect this whole change exists to remove. */
  private[blobexec] def parsePositiveSeconds(spec: String): Option[Int] =
    spec.toIntOption.filter(_ > 0)

  /** Multiple of `--blob-timeout` used when the stall window has to be widened
    * for it, matching the ratio of the two defaults (600 and 1800). */
  private[blobexec] val StallTimeoutMultiple = 3

  /** Reconcile the two timeouts, which are not independent: during a commit that
    * is pure blob work, a blob finishing or being killed is the only thing that
    * stamps progress, so the watchdog window has to be strictly larger than the
    * per-blob budget or a legitimately slow blob races its own watchdog.
    *
    *  - window already larger: accept it.
    *  - window too small but never asked for (still the default): widen it, and
    *    let the caller say so. Raising only `--blob-timeout` is the common case
    *    and it should not need a second flag to be correct.
    *  - window too small and explicitly requested: refuse, naming both values.
    *    Silently overriding a number the operator typed is worse than stopping.
    */
  private[blobexec] def resolveStallTimeout(
      blobTimeoutSeconds: Int,
      stallTimeoutSeconds: Int,
      stallExplicit: Boolean
  ): Either[String, Int] =
    if (stallTimeoutSeconds > blobTimeoutSeconds) Right(stallTimeoutSeconds)
    else if (stallExplicit)
      Left(
        s"--stall-timeout=$stallTimeoutSeconds must be greater than --blob-timeout=$blobTimeoutSeconds. " +
          "A blob completing or being killed is the only progress a pure-blob commit makes, so an " +
          "equal or smaller stall window kills runs whose blobs are merely slow. Try " +
          s"--stall-timeout=${widenedStall(blobTimeoutSeconds)} with --blob-timeout=$blobTimeoutSeconds."
      )
    else Right(widenedStall(blobTimeoutSeconds))

  private def widenedStall(blobTimeoutSeconds: Int): Int =
    math.min(blobTimeoutSeconds.toLong * StallTimeoutMultiple, Int.MaxValue.toLong).toInt

  // `raw` (not `s`): the mask example below contains a regex backslash, which a
  // processed-escape interpolator rejects. `$$` therefore renders a literal `$`.
  private val Usage =
    raw"""Usage: blobExec [--abort-on-error] [--pipeline | --pipeline-trees | --shard=K/N] [--warm=<db>] [--mask-widened] [--blob-timeout=<seconds>] [--stall-timeout=<seconds>] <src.git> <dst.git> <db.sqlite> <command> <fileMaskRegex>
      |
      |  --abort-on-error  exit immediately (status 2) on the first non-zero
      |                    exit from <command>, instead of skipping that blob
      |  --mask-widened    resume against a blob map recorded under a DIFFERENT
      |                    <fileMaskRegex>, keeping the tokenizations already in
      |                    blob_map. Without it a mask change is refused (status
      |                    3), which is the right default and stays the default.
      |                    Valid only because the mask decides WHICH blobs are
      |                    tokenized, never HOW: the language comes from the
      |                    file's extension, per file, so the same (blob, path)
      |                    yields the same tokens under any mask that selects it.
      |
      |                    It is not taken on trust. Two checks run first, and
      |                    NOTHING is written until both pass:
      |                      1. every path of a tokenized row (orig <> new) must
      |                         still match the new mask. One regex test per row,
      |                         against the data, so a narrowing mislabelled as a
      |                         widening is caught and named. Regexes are never
      |                         compared to each other.
      |                      2. a spread sample of the retained new_blob ids must
      |                         resolve in <dst.git>. Those ids exist only there,
      |                         so the map is reusable only alongside it.
      |
      |                    Then: tree_map, commit_map and ref_map are emptied
      |                    (trees gain entries, commits name trees, refs name
      |                    commits), and so are blob_map's IDENTITY rows
      |                    (orig == new). The identity rows are the subtle part:
      |                    they record "this path was not selected, its bytes pass
      |                    through", and under a wider mask some of those paths
      |                    ARE selected. Keeping them would serve RAW SOURCE as a
      |                    cache hit for precisely the files the widening exists
      |                    to tokenize.
      |
      |                    This is a step-2 resume that KEEPS the work directory,
      |                    never a fresh run — a fresh run deletes dst.git, and
      |                    check 2 then refuses.
      |  --blob-timeout=<seconds>
      |                    wall-clock budget for one <command> invocation
      |                    (default ${BlobExec.DefaultTimeoutSeconds}). A child that exceeds it is
      |                    killed (whole process group) and that single blob is
      |                    left untokenized; the run continues, and
      |                    --abort-on-error does not turn a timeout into a
      |                    whole-run abort. The count is reported on the done
      |                    line and the process then exits ${TimedOutExitStatus},
      |                    so the caller cannot publish a project whose tokens are
      |                    incomplete. Nothing durable is recorded for the blob,
      |                    the trees above it or its commit, so another run over
      |                    the same memo retries just that blob (from the
      |                    pipeline: resume at step 2, never step 1).
      |  --stall-timeout=<seconds>
      |                    watchdog window (default ${Walker.DefaultStallTimeoutSeconds}); must be larger than
      |                    --blob-timeout, since a pure-blob commit's only
      |                    progress is a blob finishing or being killed. If it is
      |                    not, an explicit value is refused and a defaulted one
      |                    is raised to ${StallTimeoutMultiple}x --blob-timeout. If no blob, tree,
      |                    commit or blob copy completes anywhere in this window,
      |                    the run is stuck in a way the per-blob kill did not
      |                    cover: it is reported with the work in flight and the
      |                    process is killed with status ${Walker.StalledExitStatus}. The memo is
      |                    durable, so re-running resumes.
      |
      |  Blobs on the shipped denylist (${BlobDenylist.ResourcePath} inside this
      |  jar) are never handed to <command>: they are dropped from the rewritten
      |  trees, counted as blobsDenylisted, named with their reason and citation on
      |  an EXCLUDED line, and they do NOT change the exit status. srcML 1.1.0 does
      |  not terminate on the four listed blobs, the defect is diagnosed and cited
      |  upstream, and a diagnosed exclusion must not block publication the way an
      |  unexplained timeout does.
      |
      |  Exit status: 0 = clean, 1 = usage, 2 = aborted on a command error,
      |               3 = memo meta mismatch, ${TimedOutExitStatus} = completed
      |               but some blob timed out (output incomplete, re-run to
      |               retry), ${Walker.StalledExitStatus} = killed by the stall watchdog.
      |  --pipeline        use the look-ahead pipelined walker (producer runs
      |                    ahead so the blob-command pool stays saturated);
      |                    output is identical to the default serial walker
      |  --pipeline-trees  as --pipeline, but also assembles rewritten trees on
      |                    the worker pool (Design B: only commit construction
      |                    and the ordered DB write stay on the consumer);
      |                    output is identical to the default serial walker
      |  --shard=K/N       TREE-ONLY history shard: partition the commits (in
      |                    canonical TOPO+REVERSE order) into N contiguous
      |                    ranges and process only shard K (0-based). Tokenizes
      |                    blobs and builds+persists trees (blob_map + tree_map
      |                    incl. subtree ids) for the slice, but does NOT fold
      |                    commits (no commit_map, no refs). Run N shards to
      |                    their own dst.git + db.sqlite, then merge + serial
      |                    re-fold (shard_merge.py) for byte-identical output.
      |                    Uses the flat-memory serial walker; mutually
      |                    exclusive with --pipeline / --pipeline-trees.
      |  --warm=<db>       optional read-only fallback DB (a frozen prior memo,
      |                    e.g. the paused whole-kernel run). On a blob_map /
      |                    tree_map miss the lookup falls through to this DB, so
      |                    a shard skips re-tokenizing content already done.
      |                    Never written; commit_map is never consulted.
      |  <src.git>         bare source repo (read-only)
      |  <dst.git>         bare destination repo (created on first run, reused on incremental)
      |  <db.sqlite>       SQLite mapping file (created on first run, reused on incremental)
      |  <command>         absolute path to the per-blob script to run
      |  <fileMaskRegex>   regex matched against each blob's filename (e.g. '\.[ch]$$')
      |""".stripMargin

  def main(args: Array[String]): Unit = {
    val (flags, positional) = args.partition(_.startsWith("-"))

    var abortOnError = false
    var pipeline     = false
    var pipelineTrees = false
    var shard: Option[(Int, Int)] = None
    var warmPath: Option[java.nio.file.Path] = None
    var blobTimeoutSeconds = BlobExec.DefaultTimeoutSeconds
    var stallTimeoutSeconds = Walker.DefaultStallTimeoutSeconds
    var stallExplicit = false
    var maskWidened = false
    flags.foreach {
      case "--abort-on-error" => abortOnError = true
      case "--pipeline"       => pipeline = true
      case "--pipeline-trees" => pipelineTrees = true
      case "--mask-widened"   => maskWidened = true
      case s if s.startsWith("--shard=") =>
        val spec = s.stripPrefix("--shard=")
        spec.split("/", -1) match {
          case Array(kStr, nStr) =>
            val k = kStr.toIntOption.getOrElse(-1)
            val n = nStr.toIntOption.getOrElse(-1)
            if (n < 1 || k < 0 || k >= n) {
              System.err.println(s"Error: --shard must be K/N with 0 <= K < N and N >= 1 [$spec]")
              sys.exit(1)
            }
            shard = Some((k, n))
          case _ =>
            System.err.println(s"Error: --shard must be of the form K/N [$spec]")
            sys.exit(1)
        }
      case w if w.startsWith("--warm=") =>
        val p = Paths.get(w.stripPrefix("--warm="))
        if (!Files.isRegularFile(p)) {
          System.err.println(s"Error: --warm db [$p] is not a file")
          sys.exit(1)
        }
        warmPath = Some(p)
      case t if t.startsWith("--blob-timeout=") =>
        val spec = t.stripPrefix("--blob-timeout=")
        parsePositiveSeconds(spec) match {
          case Some(secs) => blobTimeoutSeconds = secs
          case None =>
            System.err.println(s"Error: --blob-timeout must be a positive whole number of seconds [$spec]")
            sys.exit(1)
        }
      case t if t.startsWith("--stall-timeout=") =>
        val spec = t.stripPrefix("--stall-timeout=")
        parsePositiveSeconds(spec) match {
          case Some(secs) => stallTimeoutSeconds = secs; stallExplicit = true
          case None =>
            System.err.println(s"Error: --stall-timeout must be a positive whole number of seconds [$spec]")
            sys.exit(1)
        }
      case other =>
        System.err.println(s"Error: unknown flag [$other]")
        System.err.println(Usage)
        sys.exit(1)
    }

    resolveStallTimeout(blobTimeoutSeconds, stallTimeoutSeconds, stallExplicit) match {
      case Right(secs) =>
        if (secs != stallTimeoutSeconds) {
          System.err.println(
            s"blobExec: raising the stall window from ${stallTimeoutSeconds}s to ${secs}s, because " +
              s"--blob-timeout=${blobTimeoutSeconds}s needs a watchdog window larger than itself. " +
              "Pass --stall-timeout explicitly to choose your own."
          )
          stallTimeoutSeconds = secs
        }
      case Left(why) =>
        System.err.println(s"Error: $why")
        sys.exit(1)
    }

    if (pipeline && pipelineTrees) {
      System.err.println("Error: --pipeline and --pipeline-trees are mutually exclusive")
      System.err.println(Usage)
      sys.exit(1)
    }

    // A shard writes its own fresh dst.git and blobmap.db every run, so there is
    // never a recorded mask for --mask-widened to widen. Refusing says so instead
    // of letting the flag be a silent no-op; --warm is the sharded equivalent.
    if (shard.isDefined && maskWidened) {
      System.err.println("Error: --mask-widened has nothing to do under --shard: each shard builds a " +
        "fresh dst.git and blobmap.db, so no mask is recorded to widen. Reuse a prior run's " +
        "tokenizations with --warm=<db> instead.")
      System.err.println(Usage)
      sys.exit(1)
    }

    if (shard.isDefined && (pipeline || pipelineTrees)) {
      System.err.println("Error: --shard uses the serial tree-only walker and cannot be combined with --pipeline / --pipeline-trees")
      System.err.println(Usage)
      sys.exit(1)
    }

    if (positional.length != 5) {
      System.err.println(Usage)
      sys.exit(1)
    }
    val srcPath = Paths.get(positional(0))
    val dstPath = Paths.get(positional(1))
    val dbPath  = Paths.get(positional(2))
    val command = positional(3)
    val mask    = positional(4)

    if (!Files.isDirectory(srcPath)) {
      System.err.println(s"Error: src repo [$srcPath] is not a directory")
      sys.exit(1)
    }
    if (!Files.exists(Paths.get(command))) {
      System.err.println(s"Error: command [$command] does not exist")
      sys.exit(1)
    }
    if (mask.isEmpty) {
      System.err.println("Error: fileMaskRegex must be non-empty")
      sys.exit(1)
    }
    // Files.createDirectories throws FileAlreadyExistsException on macOS
    // when the target is a symlink (e.g. /tmp -> /private/tmp). Guard
    // against that with an explicit isDirectory check.
    val dbParent = dbPath.getParent
    if (dbParent != null && !Files.isDirectory(dbParent)) Files.createDirectories(dbParent)

    // Loaded here rather than on first use: a jar built without the resource, or a
    // malformed line in it, must stop the run now and say so, not silently hand a
    // known non-terminating blob to srcml an hour into the walk.
    val denylist =
      try BlobDenylist.shipped
      catch {
        case e: Exception =>
          System.err.println(s"Error: cannot read the blob denylist: ${e.getMessage}")
          sys.exit(1)
      }

    val incremental = Files.isDirectory(dstPath)
    val shardStr = shard.map { case (k, n) => s"$k/$n" }.getOrElse("none")
    val warmStr  = warmPath.map(_.toString).getOrElse("none")
    println(
      s"blobExec: src=$srcPath dst=$dstPath db=$dbPath command=$command mask=$mask " +
        s"abortOnError=$abortOnError pipeline=$pipeline pipelineTrees=$pipelineTrees " +
        s"shard=$shardStr warm=$warmStr blobTimeout=${blobTimeoutSeconds}s " +
        s"stallTimeout=${stallTimeoutSeconds}s incremental=$incremental " +
        s"maskWidened=$maskWidened denylistEntries=${denylist.size}"
    )

    val src: FileRepository = openSrc(srcPath)
    val dst: FileRepository = openOrInitDst(dstPath)

    // The reachability probe the widening needs. It reads dst, which is why the
    // whole decision lives here and not inside Mapping.open's signature alone:
    // `blob_map` is only meaningful next to the repository its new_blob ids are in.
    val widening =
      if (!maskWidened) None
      else Some(Mapping.MaskWidening(
        newBlobResolves = id => {
          val reader = dst.newObjectReader()
          try reader.has(org.eclipse.jgit.lib.ObjectId.fromString(id))
          catch { case _: IllegalArgumentException => false }
          finally reader.close()
        },
        report = msg => println(s"blobExec: $msg")
      ))

    val mapping = try Mapping.open(dbPath, command, mask, warmPath, widening) catch {
      case m: Mapping.MetaMismatchException =>
        System.err.println(s"Error: ${m.getMessage}")
        if (!maskWidened && m.getMessage.contains("mask"))
          System.err.println(
            "Hint: if the new mask is a strict superset of the recorded one, --mask-widened reuses " +
              "the tokenizations already in blob_map instead of redoing them. It verifies that " +
              "against the rows themselves and refuses if it is not true. It requires the work " +
              "directory to be intact (resume at step 2), because blob_map's ids live in dst."
          )
        src.close(); dst.close()
        sys.exit(3)
      case n: Mapping.MaskNarrowedException =>
        System.err.println(s"Error: ${n.getMessage}")
        src.close(); dst.close()
        sys.exit(3)
      case d: Mapping.DanglingNewBlobException =>
        System.err.println(s"Error: ${d.getMessage}")
        src.close(); dst.close()
        sys.exit(3)
    }

    // (stats, timeouts this memo has ever seen). The cumulative figure is kept
    // for forensics only — it must NOT gate the exit status, or a blob that
    // times out once could never be retried to a clean run.
    val (stats, timedOutEver) = try {
      val parallelism = math.max(1, Runtime.getRuntime.availableProcessors)
      val walker = new Walker(
        src, dst, mapping, mask.r, command, abortOnError, parallelism,
        pipeline, pipelineTrees, shard,
        destinationMayContainObjects = incremental,
        blobTimeoutSeconds = blobTimeoutSeconds,
        stallTimeoutSeconds = stallTimeoutSeconds,
        denylist = denylist
      )
      val s = walker.run()
      val prior = mapping.getMeta(BlobsTimedOutMetaKey).flatMap(_.toLongOption).getOrElse(0L)
      val total = prior + s.blobsTimedOut
      if (s.blobsTimedOut > 0) mapping.setMeta(BlobsTimedOutMetaKey, total.toString)
      (s, total)
    } finally {
      mapping.close()
      dst.close()
      src.close()
    }

    println(
      s"blobExec done: commitsProcessed=${stats.commitsProcessed} " +
        s"blobsRunThroughCommand=${stats.blobsRunThroughCommand} " +
        s"blobCommandExecutions=${stats.blobCommandExecutions} " +
        s"blobsCacheHit=${stats.blobsCacheHit} " +
        s"originalBlobCopyRequests=${stats.originalBlobCopyRequests} " +
        s"originalBlobCopies=${stats.originalBlobCopies} " +
        s"originalBlobAlreadyPresent=${stats.originalBlobAlreadyPresent} " +
        s"originalBlobCacheHits=${stats.originalBlobCacheHits} " +
        s"originalBlobDestinationLookups=${stats.originalBlobDestinationLookups} " +
        s"originalBlobBytesCopied=${stats.originalBlobBytesCopied} " +
        s"originalBlobBytesAvoided=${stats.originalBlobBytesAvoided} " +
        s"refsProjected=${stats.refsProjected} " +
        s"blobsTimedOut=${stats.blobsTimedOut} " +
        s"blobsTimedOutEver=$timedOutEver " +
        s"blobsGeneratedExcluded=${stats.blobsGeneratedExcluded} " +
        s"blobsUntokenizable=${stats.blobsUntokenizable} " +
        s"blobsDenylisted=${stats.blobsDenylisted} " +
        s"blobsParserCrashed=${stats.blobsParserCrashed} " +
        s"aborted=${stats.aborted}"
    )

    if (stats.blobsDenylisted > 0) {
      // Reported, never fatal — and that is the entire purpose of the denylist.
      // The same blob on the timeout path costs 600s and then exit 4, which stops
      // the project from ever publishing over a third-party parser bug that is
      // already diagnosed and cited.
      System.err.println(
        s"blobExec: ${stats.blobsDenylisted} blob(s) were excluded by the blob denylist " +
          s"(${BlobDenylist.ResourcePath} in this jar, ${denylist.size} entr" +
          s"${if (denylist.size == 1) "y" else "ies"}). Each one is named with its sha, path, " +
          "reason and upstream citation on an 'EXCLUDED denylisted blob' line above; those " +
          "lines and that file are the record of what this project's dataset does not contain. " +
          "The files are absent from the tokenized repository, not present as raw source, so " +
          "they produce no blame and no dataset row. This is not a failure and does not affect " +
          "the exit status."
      )
    }

    if (stats.blobsGeneratedExcluded > 0) {
      // Reported, never fatal. The exclusion is explained by the provenance quoted
      // on the EXCLUDED lines above, so gating publication on it would only mean
      // this project could never publish while telling us nothing new.
      System.err.println(
        s"blobExec: ${stats.blobsGeneratedExcluded} blob(s) were excluded as machine-generated. " +
          "Each is a blob JGit will not materialise (>= " + Walker.MaxBlobBytes + " bytes, " +
          "JGit's default stream-file threshold; an installed one can be lower) whose own " +
          "header identifies a generator, and each is " +
          "named with its sha, path, size and the matching header line on an 'EXCLUDED generated " +
          "blob' line above; those lines are the record of what this project's dataset does not " +
          "contain, and the header is the reason — not the size. The files are absent from the " +
          "tokenized repository, not present as raw source, so they produce no blame and no " +
          "dataset row. This is not a failure and does not affect the exit status."
      )
    }

    if (stats.blobsUntokenizable > 0) {
      System.err.println(
        s"blobExec: INCOMPLETE, DO NOT PUBLISH: ${stats.blobsUntokenizable} blob(s) could not be " +
          "tokenized and their exclusion is NOT explained. Each is named on an 'UNTOKENIZABLE " +
          "blob' line above: JGit will not materialise it (>= " + Walker.MaxBlobBytes + " bytes, " +
          "JGit's default stream-file threshold; an installed one can be lower), and nothing in " +
          "its header says a machine wrote it, so on " +
          "the evidence it is a large hand-written source file. That is the one case this gate " +
          "used to get wrong — it dropped such a file as merely 'oversized', silently, under a " +
          "comment that claimed provenance was the reason. Its path is absent from the rewritten " +
          s"tree for this run, so exiting $UntokenizableExitStatus rather than publishing a hole. " +
          "Re-running changes nothing by itself: this is deterministic, and --blob-timeout is " +
          "irrelevant. Decide what the file is. If it is generated, give it a header marker this " +
          s"check can see, or denylist it with a reason and citation (${BlobDenylist.ResourcePath}). " +
          "If it is genuinely authored, tokenizing it means spooling BlobExec's stdio to disk " +
          "instead of buffering the whole token stream in the heap — a deliberate memory change, " +
          "not a flag."
      )
    }

    if (stats.blobsTimedOut > 0) {
      System.err.println(
        s"blobExec: INCOMPLETE, DO NOT PUBLISH: ${stats.blobsTimedOut} blob(s) timed out this " +
          s"run ($timedOutEver ever for this memo, see meta['$BlobsTimedOutMetaKey'] in $dbPath). " +
          "Their files would carry raw source instead of tokens, so the walk stopped at that " +
          s"commit and recorded nothing for it: no blob row, no tree row, no commit row. " +
          s"Exiting $TimedOutExitStatus. Recovery is another blobExec run over this same memo: it " +
          "retries exactly those blobs and needs no changes to the database. Driven from " +
          "run_pipeline_process.sh, that means resuming at step 2 (trailing '2', or ctp.py " +
          "--from-step 2) — a step-1 run deletes the work directory first, memo included. " +
          "If the same blobs keep failing, check which kind of failure it is: a child killed on " +
          s"its budget is slowness, and the knob is --blob-timeout (currently ${blobTimeoutSeconds}s; " +
          "run_pipeline_process.sh --blob-timeout N, or CREGIT_BLOB_TIMEOUT=N in the environment). " +
          "A child reporting status 137 was SIGKILLed, which on a memory-tight host usually means " +
          "the kernel's OOM killer took it — more time will not help; give the run more memory or " +
          "exclude that blob via the mask."
      )
    }

    if (stats.blobsParserCrashed > 0) {
      System.err.println(
        s"blobExec: INCOMPLETE, DO NOT PUBLISH: ${stats.blobsParserCrashed} blob(s) had their " +
          "tokenizer report a parser crash this run. Each one is named on a 'reported a parser " +
          "crash' line above, with the failing stage and signal. This is srcML dying on a signal " +
          "(SIGSEGV or SIGABRT) on a C/C++ input, or returning no tokens at all; before this was " +
          "detected such a blob became a silent 0-byte tokenization and the file simply vanished " +
          "from the dataset with nothing counting it. Nothing was recorded for the containing " +
          s"commit: no blob row, no tree row, no commit row. Exiting $ParserCrashedExitStatus. " +
          "Unlike a timeout this is deterministic, so re-running alone will NOT clear it and " +
          "--blob-timeout is irrelevant — more time does not help a segfault. The two real " +
          "remedies are: fix or upgrade srcML (1.1.0 faults in its C/C++ position tracking, and " +
          "--position cannot be dropped because the token format depends on it), or, once a " +
          "specific blob is diagnosed, add it to the blob denylist with its reason and citation " +
          s"(${BlobDenylist.ResourcePath}) so it is excluded deterministically and reported " +
          "without blocking publication."
      )
    }

    // Always exit explicitly. A timed-out blob abandons its (daemon) reader
    // threads while they are blocked on a pipe, and on the pre-fix build the
    // JVM outlived the finished walk on exactly those threads — a silent stall
    // behind a done-line that read `aborted=false`.
    sys.exit(exitStatus(stats))
  }

  private def openSrc(path: java.nio.file.Path): FileRepository = {
    val gitDir = if (Files.isDirectory(path.resolve(".git"))) path.resolve(".git").toFile else path.toFile
    FileRepositoryBuilder.create(gitDir).asInstanceOf[FileRepository]
  }

  private def openOrInitDst(path: java.nio.file.Path): FileRepository = {
    val exists = Files.isDirectory(path)
    val repo = FileRepositoryBuilder.create(path.toFile).asInstanceOf[FileRepository]
    if (!exists) repo.create(true)  // bare init
    repo
  }
}
