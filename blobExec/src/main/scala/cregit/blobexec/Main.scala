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

  /** srcML died on a signal, or produced no tokens. Distinct from
    * [[TimedOutExitStatus]] because --blob-timeout does nothing for a segfault. */
  private[blobexec] val ParserCrashedExitStatus = 6

  /** Upper bound on either timeout flag. A week is already far past any real
    * budget, and bounding the input here is what keeps every window derived from
    * it inside `Int`. */
  private[blobexec] val MaxTimeoutSeconds: Int = 7 * 86400

  /** Seconds for `--blob-timeout=` / `--stall-timeout=`: a positive whole number
    * within [[MaxTimeoutSeconds]], else None. Zero is rejected rather than read
    * as "no limit". */

  /** The process exit status for a finished walk.
    * Only an abort, a killed tokenizer and a parser crash gate publication;
    * blobsOversized and blobsDenylisted do not. */
  private[blobexec] def exitStatus(stats: WalkStats): Int =
    if (stats.aborted) 2
    else if (stats.blobsTimedOut > 0) TimedOutExitStatus
    else if (stats.blobsParserCrashed > 0) ParserCrashedExitStatus
    else 0

  /** Value parser for the `--blob-timeout=` / `--stall-timeout=` seconds: a
    * positive whole number, else None (which the caller reports and exits 1 on).
    * Zero and negatives are rejected rather than read as "no limit" — an
    * unbounded blob is the defect this whole change exists to remove. */
  private[blobexec] def parsePositiveSeconds(spec: String): Option[Int] =
    spec.toIntOption.filter(s => s > 0 && s <= MaxTimeoutSeconds)

  // `raw` (not `s`): the mask example below contains a regex backslash, which a
  // processed-escape interpolator rejects. `$$` therefore renders a literal `$`.
  private val Usage =
    raw"""Usage: blobExec [--abort-on-error] [--pipeline | --pipeline-trees | --shard=K/N] [--warm=<db>] [--blob-timeout=<seconds>] [--stall-timeout=<seconds>] <src.git> <dst.git> <db.sqlite> <command> <fileMaskRegex>
      |
      |  --abort-on-error  exit immediately (status 2) on the first non-zero
      |                    exit from <command>, instead of skipping that blob
      |  --blob-timeout=<seconds>
      |                    budget for one <command> invocation (default ${BlobExec.DefaultTimeoutSeconds}).
      |                    A child that exceeds it is killed with everything
      |                    beneath it; that blob is left untokenized, the walk
      |                    records nothing for its commit, and the run exits
      |                    ${TimedOutExitStatus} so an incomplete project cannot be published.
      |                    Re-running retries exactly those blobs.
      |  --stall-timeout=<seconds>
      |                    watchdog window (default ${Walker.DefaultStallTimeoutSeconds}, derived from
      |                    --blob-timeout). If nothing completes anywhere in this
      |                    window the run exits ${Walker.StalledExitStatus}. Neither flag normally
      |                    needs setting: the window follows the budget, and a
      |                    window that one blob's lifetime could trip is refused.
      |
      |  Blobs on the shipped denylist (${BlobDenylist.EntriesSource}) are never
      |  handed to <command>: they are dropped from the rewritten trees, counted as
      |  blobsDenylisted, named with their reason and citation on an EXCLUDED line,
      |  and they do NOT change the exit status. srcML 1.1.0 does not terminate on
      |  the listed blobs, the defect is diagnosed and cited upstream, and a
      |  diagnosed exclusion must not block publication the way an unexplained
      |  timeout does.
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
    flags.foreach {
      case "--abort-on-error" => abortOnError = true
      case "--pipeline"       => pipeline = true
      case "--pipeline-trees" => pipelineTrees = true
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

    Walker.resolveStallTimeout(blobTimeoutSeconds, stallTimeoutSeconds, stallExplicit) match {
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

    val denylist = BlobDenylist.shipped

    val incremental = Files.isDirectory(dstPath)
    val shardStr = shard.map { case (k, n) => s"$k/$n" }.getOrElse("none")
    val warmStr  = warmPath.map(_.toString).getOrElse("none")
    println(
      s"blobExec: src=$srcPath dst=$dstPath db=$dbPath command=$command mask=$mask " +
        s"abortOnError=$abortOnError pipeline=$pipeline pipelineTrees=$pipelineTrees " +
        s"shard=$shardStr warm=$warmStr blobTimeout=${blobTimeoutSeconds}s " +
        s"stallTimeout=${stallTimeoutSeconds}s incremental=$incremental " +
        s"denylistEntries=${denylist.size}"
    )

    val src: FileRepository = openSrc(srcPath)
    val dst: FileRepository = openOrInitDst(dstPath)
    val mapping = try Mapping.open(dbPath, command, mask, warmPath) catch {
      case m: Mapping.MetaMismatchException =>
        System.err.println(s"Error: ${m.getMessage}")
        src.close(); dst.close()
        sys.exit(3)
    }

    // The cumulative figure is forensic only: gating on it would make one past
    // timeout permanent.
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
        s"blobsOversized=${stats.blobsOversized} " +
        s"blobsDenylisted=${stats.blobsDenylisted} " +
        s"blobsParserCrashed=${stats.blobsParserCrashed} " +
        s"aborted=${stats.aborted}"
    )

    if (stats.blobsDenylisted > 0) {
      // Reported, never fatal.
      System.err.println(
        s"blobExec: ${stats.blobsDenylisted} blob(s) were excluded by the blob denylist " +
          s"(${BlobDenylist.EntriesSource}, ${denylist.size} entr" +
          s"${if (denylist.size == 1) "y" else "ies"}). Each one is named with its sha, path, " +
          "reason and upstream citation on an 'EXCLUDED denylisted blob' line above; those " +
          "lines and that file are the record of what this project's dataset does not contain. " +
          "The files are absent from the tokenized repository, not present as raw source, so " +
          "they produce no blame and no dataset row. This is not a failure and does not affect " +
          "the exit status."
      )
    }

    if (stats.blobsOversized > 0) {
      System.err.println(
        s"blobExec: ${stats.blobsOversized} blob(s) were excluded as oversized. Each is named " +
          "with its sha, path and size on an 'EXCLUDED oversized blob' line above. Reported, " +
          "never fatal."
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
          "commit: no blob row, no tree row, no commit row. " +
          "Unlike a timeout this is deterministic, so re-running alone will NOT clear it and " +
          "--blob-timeout is irrelevant — more time does not help a segfault. The two real " +
          "remedies are: fix or upgrade srcML (1.1.0 faults in its C/C++ position tracking, and " +
          "tokenizeSrcMl.pl cannot drop --position: it parses srcml2token's line:col prefix), or, once a " +
          "specific blob is diagnosed, add it to the blob denylist with its reason and citation " +
          s"(${BlobDenylist.ResourcePath}) so it is excluded deterministically and reported " +
          "without blocking publication."
      )
    }

    val status = exitStatus(stats)
    if (status != 0) System.err.println(s"blobExec: exiting $status")
    // Exit explicitly: abandoned daemon readers must not decide JVM exit.
    sys.exit(status)
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
