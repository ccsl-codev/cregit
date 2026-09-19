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

  // `raw` (not `s`): the mask example below contains a regex backslash, which a
  // processed-escape interpolator rejects. `$$` therefore renders a literal `$`.
  private val Usage =
    raw"""Usage: blobExec [--abort-on-error] [--pipeline | --pipeline-trees | --shard=K/N] [--warm=<db>] [--blob-timeout=<seconds>] <src.git> <dst.git> <db.sqlite> <command> <fileMaskRegex>
      |
      |  --abort-on-error  exit immediately (status 2) on the first non-zero
      |                    exit from <command>, instead of skipping that blob
      |  --blob-timeout=<seconds>
      |                    wall-clock budget for one <command> invocation
      |                    (default ${BlobExec.DefaultTimeoutSeconds}). A child that exceeds it is
      |                    killed and that single blob is left untokenized;
      |                    the run continues, and --abort-on-error does not
      |                    turn a timeout into a whole-run abort.
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
        spec.toIntOption match {
          case Some(secs) if secs > 0 => blobTimeoutSeconds = secs
          case _ =>
            System.err.println(s"Error: --blob-timeout must be a positive whole number of seconds [$spec]")
            sys.exit(1)
        }
      case other =>
        System.err.println(s"Error: unknown flag [$other]")
        System.err.println(Usage)
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

    val incremental = Files.isDirectory(dstPath)
    val shardStr = shard.map { case (k, n) => s"$k/$n" }.getOrElse("none")
    val warmStr  = warmPath.map(_.toString).getOrElse("none")
    println(
      s"blobExec: src=$srcPath dst=$dstPath db=$dbPath command=$command mask=$mask " +
        s"abortOnError=$abortOnError pipeline=$pipeline pipelineTrees=$pipelineTrees " +
        s"shard=$shardStr warm=$warmStr blobTimeout=${blobTimeoutSeconds}s incremental=$incremental"
    )

    val src: FileRepository = openSrc(srcPath)
    val dst: FileRepository = openOrInitDst(dstPath)
    val mapping = try Mapping.open(dbPath, command, mask, warmPath) catch {
      case m: Mapping.MetaMismatchException =>
        System.err.println(s"Error: ${m.getMessage}")
        src.close(); dst.close()
        sys.exit(3)
    }

    val stats = try {
      val parallelism = math.max(1, Runtime.getRuntime.availableProcessors)
      val walker = new Walker(
        src, dst, mapping, mask.r, command, abortOnError, parallelism,
        pipeline, pipelineTrees, shard,
        destinationMayContainObjects = incremental,
        blobTimeoutSeconds = blobTimeoutSeconds
      )
      walker.run()
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
        s"aborted=${stats.aborted}"
    )

    if (stats.aborted) sys.exit(2)
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
