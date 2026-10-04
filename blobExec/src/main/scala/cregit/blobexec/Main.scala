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

  /** Exit status when `--retokenize` was asked for and would have invalidated
    * nothing.
    *
    * Its own status, and not 3, because it is a different kind of answer. 3 means
    * "this memo does not match this run, so the run did not start". This means
    * "the run could have started, and the invalidation you asked for was a no-op".
    * An operator scripting a corpus-wide re-tokenization needs to tell those
    * apart: the first is a refusal to proceed, the second is a request that did
    * nothing, and treating the second as success is how a poisoned cache gets
    * published. 7 is the next free status after 1, 2, 3, 4, 5, 6;
    * `retokenizeStatusIsUnique` in MainOptionsSpec pins that. */
  private[blobexec] val RetokenizeIneffectiveExitStatus = 7

  /** The process exit status for a finished walk: 2 on an abort, else 0.
    * Excluded blobs (oversized, denylisted, or whose tokenizer failed) are
    * named on EXCLUDED lines and do not change it. */
  private[blobexec] def exitStatus(stats: WalkStats): Int =
    if (stats.aborted) 2 else 0

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
    raw"""Usage: blobExec [--abort-on-error] [--pipeline | --pipeline-trees | --shard=K/N] [--warm=<db>] [--mask-widened] [--tokenizer-identity=<ext>=<value>,...] [--retokenize=<ext>,...] [--memo-dir=<dir>] [--blob-timeout=<seconds>] [--stall-timeout=<seconds>] <src.git> <dst.git> <db.sqlite> <command> <fileMaskRegex>
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
      |  --tokenizer-identity=<ext>=<value>[,<ext>=<value>...]
      |                    which tokenizer produced the tokens for each file
      |                    extension, as an opaque value per extension (a digest
      |                    of the parser toolchain; tokenize/tokenizerIdentity.pl
      |                    computes it). Recorded in the blob map's meta table on
      |                    first sight and COMPARED on every later run.
      |
      |                    This closes a hole that <command> could not. <command>
      |                    is the constant path tokenizeByBlobId/tokenBySha.pl, so
      |                    when a tokenizer behind it is corrected, nothing the
      |                    reuse decision looks at changes: every cached row for
      |                    that language stays a cache hit and the run reproduces
      |                    the OLD tokenizer's output with no error anywhere. That
      |                    happened — a rustTokenizer binary 16 days older than
      |                    its source kept emitting a `line:col<TAB>` prefix, and
      |                    741,869 .rs entries in 45 projects carried it.
      |
      |                    A mismatch REFUSES the run (status 3) and names the
      |                    flag to fix it. It never invalidates anything by
      |                    itself, which is why comparing is safe as a default:
      |                    the 186 published projects are not touched. An
      |                    extension with nothing recorded is not a mismatch —
      |                    that is what every blob map built before this flag
      |                    looks like — so it is simply recorded.
      |  --retokenize=<ext>[,<ext>...]
      |                    the opt-in past that refusal: invalidate the cached
      |                    tokenizations of these extensions and nothing else.
      |                    Requires --tokenizer-identity and --memo-dir, and a
      |                    step-2 resume (the ids live in <dst.git>).
      |
      |                    SELECTIVE on purpose. Re-tokenizing is 88% of total
      |                    pipeline time, so a defect in one language's tokenizer
      |                    must cost one language's entries. Deliberately NOT
      |                    --drop-memo, which destroys 2.6 M memoized
      |                    tokenizations wholesale, and deliberately not
      |                    --mask-widened, which answers a different question.
      |
      |                    BOTH cache layers go, together, and neither can be
      |                    done without the other:
      |                      - blob_map's tokenized rows on those extensions, and
      |                      - their entries in the memo under --memo-dir. The
      |                        memo is keyed on sha1(contents) ALONE
      |                        (tokenBySha.pl:76) with no tokenizer in the key, so
      |                        dropping only the blob_map row just moves the stale
      |                        answer one layer down.
      |                    tree_map, commit_map and ref_map go too: a tree names
      |                    its blobs, and a retained tree_map row short-circuits
      |                    the re-walk of the subtree holding the file.
      |
      |                    IT CANNOT QUIETLY DO NOTHING. It refuses, before
      |                    changing anything, when: another extension's tokenizer
      |                    also changed and was not named; no tokenized row
      |                    carries any named extension (status ${RetokenizeIneffectiveExitStatus}); the memo held
      |                    none of the affected blobs and --memo-dir is not
      |                    $$BFG_MEMO_DIR, the memo tokenBySha.pl reads (status ${RetokenizeIneffectiveExitStatus});
      |                    or a RETAINED new_blob id does not resolve in <dst.git>.
      |  --memo-dir=<dir>  the memo directory ($$BFG_MEMO_DIR) whose entries
      |                    --retokenize must purge. Only read with --retokenize;
      |                    passing it alone is an error rather than a no-op.
      |  --blob-timeout=<seconds>
      |                    wall-clock budget for one <command> invocation
      |                    (default ${BlobExec.DefaultTimeoutSeconds}). A child that exceeds it is
      |                    killed (whole process group) and that blob is
      |                    excluded as failed (see below); the run continues.
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
      |  not terminate on the listed blobs, and the defect is diagnosed and cited
      |  upstream, so the list saves the --blob-timeout each run would spend.
      |
      |  A blob whose tokenizer times out or reports a parser crash is excluded
      |  the same way: dropped from the rewritten trees, counted as blobsTimedOut
      |  or blobsParserCrashed, named on an 'EXCLUDED failed blob' line, and tried
      |  only once per run. It does NOT change the exit status.
      |
      |  Exit status: 0 = clean, 1 = usage, 2 = aborted on a command error,
      |               3 = memo meta mismatch (including a changed tokenizer with
      |               no --retokenize), ${Walker.StalledExitStatus} = killed by the stall watchdog,
      |               ${RetokenizeIneffectiveExitStatus} = --retokenize would have invalidated nothing.
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
    var tokenizerIdentity = TokenizerIdentity.empty
    var retokenizeExtensions: Set[String] = Set.empty
    var memoDir: Option[java.nio.file.Path] = None
    flags.foreach {
      case "--abort-on-error" => abortOnError = true
      case "--pipeline"       => pipeline = true
      case "--pipeline-trees" => pipelineTrees = true
      case "--mask-widened"   => maskWidened = true
      case t if t.startsWith("--tokenizer-identity=") =>
        TokenizerIdentity.parse(t.stripPrefix("--tokenizer-identity=")) match {
          case Right(id) => tokenizerIdentity = id
          case Left(why) =>
            System.err.println(s"Error: --tokenizer-identity: $why")
            sys.exit(1)
        }
      case t if t.startsWith("--retokenize=") =>
        TokenizerIdentity.parseExtensions(t.stripPrefix("--retokenize=")) match {
          case Right(exts) => retokenizeExtensions = exts
          case Left(why) =>
            System.err.println(s"Error: --retokenize: $why")
            sys.exit(1)
        }
      case t if t.startsWith("--memo-dir=") =>
        val p = Paths.get(t.stripPrefix("--memo-dir="))
        if (!Files.isDirectory(p)) {
          System.err.println(
            s"Error: --memo-dir [$p] is not a directory. It must be the SAME memo this project's " +
              "tokenizations were written into, or the invalidation would leave the real memo's " +
              "stale entries in place.")
          sys.exit(1)
        }
        memoDir = Some(p)
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

    // Every way --retokenize could end up doing nothing, refused before the walk.
    // A flag whose whole purpose is to force work must never be able to run and
    // force none.
    if (retokenizeExtensions.nonEmpty) {
      if (tokenizerIdentity.isEmpty) {
        System.err.println(
          "Error: --retokenize needs --tokenizer-identity. Invalidating the entries without " +
            "recording which tokenizer replaces them leaves nothing for the next run to detect a " +
            "change against, so the next tokenizer defect would be just as silent as this one.")
        System.err.println(Usage)
        sys.exit(1)
      }
      if (memoDir.isEmpty) {
        System.err.println(
          "Error: --retokenize needs --memo-dir. There are two caches, not one: dropping a blob_map " +
            "row makes the walker re-run <command>, and <command> is tokenizeByBlobId/tokenBySha.pl, " +
            "which answers from $BFG_MEMO_DIR keyed on sha1(contents) with no tokenizer in the key. " +
            "Invalidating one layer without the other invalidates nothing at all.")
        System.err.println(Usage)
        sys.exit(1)
      }
      val unknown = retokenizeExtensions -- tokenizerIdentity.extensions
      if (unknown.nonEmpty) {
        System.err.println(
          s"Error: --retokenize names extension(s) --tokenizer-identity does not cover: " +
            s"${unknown.toVector.sorted.mkString(", ")}. Known: " +
            s"${tokenizerIdentity.extensions.toVector.sorted.mkString(", ")}.")
        sys.exit(1)
      }
      if (maskWidened) {
        System.err.println(
          "Error: --mask-widened and --retokenize cannot be combined. Each verifies its own " +
            "precondition against the rows, and together each would verify against a state the " +
            "other is about to change. Widen first, then resume with --retokenize.")
        sys.exit(1)
      }
      if (shard.isDefined) {
        System.err.println(
          "Error: --retokenize has nothing to do under --shard: each shard builds a fresh dst.git " +
            "and blobmap.db, so there are no cached tokenizations to invalidate. Retokenize the " +
            "merged result, or drop the shards' --warm=<db>.")
        sys.exit(1)
      }
    } else if (memoDir.isDefined) {
      System.err.println(
        "Error: --memo-dir has no effect without --retokenize. Nothing else in blobExec reads the " +
          "memo — tokenizeByBlobId/tokenBySha.pl takes it from $BFG_MEMO_DIR in the environment.")
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
        s"maskWidened=$maskWidened denylistEntries=${denylist.size} " +
        s"tokenizerIdentity=${if (tokenizerIdentity.isEmpty) "none" else tokenizerIdentity.render} " +
        s"retokenize=${if (retokenizeExtensions.isEmpty) "none" else retokenizeExtensions.toVector.sorted.mkString(",")} " +
        s"memoDir=${memoDir.map(_.toString).getOrElse("none")}"
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

    // The invalidation, wired to the two things only this layer can supply: the
    // dst reachability probe, and a memo purge that reads the original blobs out
    // of src to recompute their content sha1 — the memo's only key
    // (tokenizeByBlobId/tokenBySha.pl:76).
    val retokenize =
      if (retokenizeExtensions.isEmpty) None
      else Some(Mapping.Retokenize(
        extensions = retokenizeExtensions,
        newBlobResolves = id => {
          val reader = dst.newObjectReader()
          try reader.has(org.eclipse.jgit.lib.ObjectId.fromString(id))
          catch { case _: IllegalArgumentException => false }
          finally reader.close()
        },
        purgeMemo = blobs => TokenizerMemo.purge(memoDir.get, blobs, sha => {
          val reader = src.newObjectReader()
          try Some(reader.open(org.eclipse.jgit.lib.ObjectId.fromString(sha),
            org.eclipse.jgit.lib.Constants.OBJ_BLOB).getBytes)
          catch { case _: Exception => None }
          finally reader.close()
        }),
        report = msg => println(s"blobExec: $msg"),
        memoDirIsTokenizerMemo =
          memoDir.exists(d => TokenizerMemo.isTokenizerMemo(d, sys.env.get("BFG_MEMO_DIR")))
      ))

    val mapping = try Mapping.open(dbPath, command, mask, warmPath, widening,
                                   tokenizerIdentity, retokenize) catch {
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
      case t: Mapping.TokenizerChangedException =>
        System.err.println(s"Error: ${t.getMessage}")
        src.close(); dst.close()
        sys.exit(3)
      case n: Mapping.NothingInvalidatedException =>
        // Deliberately NOT 0. The walk could have run; the invalidation asked for
        // could not. Exiting 0 here would let a corpus script publish a project
        // whose poisoned entries were never touched.
        System.err.println(s"Error: ${n.getMessage}")
        src.close(); dst.close()
        sys.exit(RetokenizeIneffectiveExitStatus)
      case i: IllegalArgumentException =>
        // Mapping.open's own preconditions on the flag combinations, re-checked
        // there so a library caller cannot bypass what main() validates.
        System.err.println(s"Error: ${i.getMessage}")
        src.close(); dst.close()
        sys.exit(1)
    }

    val stats = try {
      val parallelism = math.max(1, Runtime.getRuntime.availableProcessors)
      val walker = new Walker(
        src, dst, mapping, mask.r, command, abortOnError, parallelism,
        pipeline, pipelineTrees, shard,
        destinationMayContainObjects = incremental,
        blobTimeoutSeconds = blobTimeoutSeconds,
        stallTimeoutSeconds = stallTimeoutSeconds,
        denylist = denylist
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
        s"blobsTimedOut=${stats.blobsTimedOut} " +
        s"blobsOversized=${stats.blobsOversized} " +
        s"blobsDenylisted=${stats.blobsDenylisted} " +
        s"blobsParserCrashed=${stats.blobsParserCrashed} " +
        s"aborted=${stats.aborted}"
    )

    if (stats.blobsDenylisted > 0) {
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

    if (stats.blobsOversized > 0) {
      // Reported, never fatal. An oversized blob is deterministic and fully
      // explained by the EXCLUDED lines above, so gating publication on it would
      // only mean this project could never publish while telling us nothing new.
      System.err.println(
        s"blobExec: ${stats.blobsOversized} blob(s) were excluded as oversized (>= " +
          s"${Walker.MaxBlobBytes} bytes, JGit's stream-file threshold). Each one is named with " +
          "its sha, path and size on an 'EXCLUDED oversized blob' line above; those lines are the " +
          "record of what this project's dataset does not contain. The files are absent from the " +
          "tokenized repository, not present as raw source, so they produce no blame and no " +
          "dataset row. This is not a failure and does not affect the exit status."
      )
    }

    if (stats.blobsTimedOut + stats.blobsParserCrashed > 0) {
      System.err.println(
        s"blobExec: ${stats.blobsTimedOut + stats.blobsParserCrashed} blob(s) were excluded because " +
          s"their tokenizer failed (${stats.blobsTimedOut} timed out after ${blobTimeoutSeconds}s, " +
          s"${stats.blobsParserCrashed} reported a parser crash). Each one is named on an " +
          "'EXCLUDED failed blob' line above; those lines are the record of what this project's " +
          "dataset does not contain. This does not affect the exit status."
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
