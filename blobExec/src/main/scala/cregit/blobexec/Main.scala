package cregit.blobexec

import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.lib.{Constants, ObjectId}
import org.eclipse.jgit.storage.file.FileRepositoryBuilder

import java.nio.file.{Files, Path, Paths}
import scala.util.Try

/** Step 2 of the cregit pipeline: rewrites <src.git> into <dst.git> through the
  * per-blob <command>, and records (orig -> new) ids in SQLite so a rerun resumes. */
object Main {

  /** Not 3 (memo mismatch): a no-op request must be told apart from a refusal. */
  private[blobexec] val RetokenizeIneffectiveExitStatus = 7

  /** zlib level 1 for dst's loose objects: they are temporary, because the pack
    * after step 2 re-deflates every one at git's pack.compression. Measured -11%
    * consumer CPU on psi-probe against JGit's default level. */
  private[blobexec] val DefaultLooseCompression = 1

  private val UsageExitStatus   = 1
  private val RefusedExitStatus = 3

  /** 2 on an abort, else 0: excluded blobs are named on EXCLUDED lines, not failed. */
  private[blobexec] def exitStatus(stats: WalkStats): Int =
    if (stats.aborted) 2 else 0

  /** Zero and negatives are refused rather than read as "no limit". */
  private[blobexec] def parsePositiveSeconds(spec: String): Option[Int] =
    spec.toIntOption.filter(_ > 0)

  /** Ratio of the two defaults (600 and 1800). */
  private[blobexec] val StallTimeoutMultiple = 3

  /** An explicit window below the floor is refused; a defaulted one is widened to
    * [[widenedStall]]. Overriding a number the operator typed is worse than stopping. */
  private[blobexec] def resolveStallTimeout(
      blobTimeoutSeconds: Int,
      stallTimeoutSeconds: Int,
      stallExplicit: Boolean
  ): Either[String, Int] = {
    val floor = Walker.stallFloorFor(blobTimeoutSeconds)
    if (stallTimeoutSeconds >= floor) Right(stallTimeoutSeconds)
    else if (stallExplicit)
      Left(
        s"--stall-timeout=$stallTimeoutSeconds must be at least ${floor}s to go with " +
          s"--blob-timeout=$blobTimeoutSeconds. A blob completing or being killed is the only " +
          "progress a pure-blob commit makes, and killing one takes the budget plus the kill " +
          "grace, so a smaller stall window kills runs whose blobs are merely slow. Try " +
          s"--stall-timeout=${widenedStall(blobTimeoutSeconds)} with --blob-timeout=$blobTimeoutSeconds."
      )
    else Right(widenedStall(blobTimeoutSeconds))
  }

  private[blobexec] def widenedStall(blobTimeoutSeconds: Int): Int =
    math.max(
      Saturating.product(math.max(1, blobTimeoutSeconds), StallTimeoutMultiple),
      Walker.stallFloorFor(blobTimeoutSeconds)
    )

  // `raw`: the mask example holds a regex backslash; `$$` renders a literal `$`.
  private[blobexec] val Usage =
    raw"""Usage: blobExec [options] <src.git> <dst.git> <db.sqlite> <command> <fileMaskRegex>
      |
      |  --abort-on-error           exit 2 on the first error from <command> instead of excluding the blob
      |  --pipeline                 look-ahead pipelined walker; same output as the serial walker
      |  --pipeline-trees           as --pipeline, and also assemble trees on the worker pool
      |  --shard=K/N                tree-only run of shard K (0-based) of N commit ranges, no commits or
      |                             refs (merge with shard_merge.py); not with --pipeline(-trees)
      |  --warm=<db>                read-only fallback mapping DB, consulted on a blob_map/tree_map miss
      |  --mask-widened             resume under a wider <fileMaskRegex>, keeping blob_map's tokens;
      |                             refused (status 3) unless every tokenized path still matches the
      |                             new mask and <dst.git> holds the kept ids
      |  --tokenizer-identity=<ext>=<value>[,...]
      |                             tokenizer digest per extension (tokenize/tokenizerIdentity.pl); a
      |                             change since the cache was built refuses the run (status 3)
      |  --retokenize=<ext>[,...]   drop these extensions' cached tokens (blob_map and memo); needs
      |                             --tokenizer-identity and --memo-dir; a no-op is refused (status ${RetokenizeIneffectiveExitStatus})
      |  --memo-dir=<dir>           the tokenizer memo ($$BFG_MEMO_DIR) that --retokenize purges
      |  --tokenizer-worker=<path>  tokenize through a pool of persistent workers; needs --pipeline(-trees)
      |  --alternates               write <dst.git>/objects/info/alternates naming <src.git>'s objects
      |                             and copy no original blob; the caller must then repack <dst.git>
      |                             without -l and delete that file. Not with --shard
      |  --commits-per-transaction=<n>
      |                             commits whose mapping rows share one SQLite transaction
      |                             (default ${Walker.DefaultCommitsPerTransaction}; 1 = one per commit)
      |  --sqlite-cache-mb=<n>      SQLite page cache for the mapping DB (default ${Mapping.DefaultCacheMiB}; 0 = SQLite's 2 MB)
      |  --sqlite-mmap-mb=<n>       memory-map up to this much of the mapping DB (default ${Mapping.DefaultMmapMiB}; 0 = off)
      |  --loose-compression=<n>    zlib level of the loose objects written to <dst.git>, in memory only
      |                             (default ${DefaultLooseCompression}; -1 = JGit's default, 6). git repacks them anyway
      |  --blob-timeout=<seconds>   budget for one <command> run (default ${BlobExec.DefaultTimeoutSeconds}); then its process
      |                             tree is killed and the blob excluded
      |  --stall-timeout=<seconds>  exit ${Walker.StalledExitStatus} after this long with no progress (default ${Walker.DefaultStallTimeoutSeconds});
      |                             must cover one blob's lifetime: below it is refused, a default raised
      |  <src.git>                  bare source repository (read-only)
      |  <dst.git>                  bare destination repository (created if missing, reused on resume)
      |  <db.sqlite>                SQLite mapping file (created if missing, reused on resume)
      |  <command>                  absolute path of the per-blob command
      |  <fileMaskRegex>            regex matched against each blob's filename (e.g. '\.[ch]$$')
      |
      |  Denylisted (${BlobDenylist.EntriesSource}) and oversized blobs, and blobs whose tokenizer
      |  times out, crashes or exits non-zero, are left out of the trees and named on an EXCLUDED
      |  line; they do not change the exit status.
      |
      |  Exit status: 0 clean, 1 usage, 2 aborted on a command error, 3 cache mismatch,
      |  ${Walker.StalledExitStatus} stalled, ${RetokenizeIneffectiveExitStatus} --retokenize would invalidate nothing.
      |""".stripMargin

  private[blobexec] final case class Options(
      abortOnError: Boolean = false,
      pipeline: Boolean = false,
      pipelineTrees: Boolean = false,
      shard: Option[(Int, Int)] = None,
      warm: Option[Path] = None,
      maskWidened: Boolean = false,
      tokenizerIdentity: TokenizerIdentity = TokenizerIdentity.empty,
      retokenize: Set[String] = Set.empty,
      memoDir: Option[Path] = None,
      tokenizerWorker: Option[Path] = None,
      alternates: Boolean = false,
      commitsPerTransaction: Int = Walker.DefaultCommitsPerTransaction,
      sqlite: Mapping.Tuning = Mapping.Tuning(),
      looseCompression: Int = DefaultLooseCompression,
      blobTimeoutSeconds: Int = BlobExec.DefaultTimeoutSeconds,
      stallTimeoutSeconds: Int = Walker.DefaultStallTimeoutSeconds,
      stallExplicit: Boolean = false,
      positional: Vector[String] = Vector.empty
  ) {
    def src: Path       = Paths.get(positional(0))
    def dst: Path       = Paths.get(positional(1))
    def db: Path        = Paths.get(positional(2))
    def command: String = positional(3)
    def mask: String    = positional(4)

    def stallWindow: Either[String, Int] =
      resolveStallTimeout(blobTimeoutSeconds, stallTimeoutSeconds, stallExplicit)

    def anyPipeline: Boolean = pipeline || pipelineTrees
  }

  private object Messages {
    def withUsage(error: String): String = s"$error\n$Usage"

    def unknownFlag(flag: String): String = withUsage(s"Error: unknown flag [$flag]")
    def badFlagValue(flag: String, why: String): String = s"Error: $flag: $why"

    def secondsWanted(spec: String): String = s"must be a positive whole number of seconds [$spec]"
    def shardRange(spec: String): String    = s"must be K/N with 0 <= K < N and N >= 1 [$spec]"
    def shardForm(spec: String): String     = s"must be of the form K/N [$spec]"

    val NotAFile = "is not a file"
    val NotTheMemo =
      "is not a directory. It must be the SAME memo this project's tokenizations were written " +
        "into, or the invalidation would leave the real memo's stale entries in place."

    val PipelinesExclusive = withUsage("Error: --pipeline and --pipeline-trees are mutually exclusive")
    val AlternatesUnderShard = withUsage(
      "Error: --alternates cannot be used with --shard: shard_merge.py copies each shard's own objects")
    val ShardWithPipeline = withUsage(
      "Error: --shard uses the serial tree-only walker and cannot be combined with --pipeline / --pipeline-trees")
    val MaskWidenedUnderShard = withUsage(
      "Error: --mask-widened has nothing to do under --shard: each shard builds a fresh dst.git and " +
        "blobmap.db, so no mask is recorded to widen. Reuse a prior run's tokenizations with --warm=<db> instead.")

    val RetokenizeNeedsIdentity = withUsage(
      "Error: --retokenize needs --tokenizer-identity. Invalidating the entries without recording which " +
        "tokenizer replaces them leaves nothing for the next run to detect a change against, so the next " +
        "tokenizer defect would be just as silent as this one.")
    val RetokenizeNeedsMemoDir = withUsage(
      "Error: --retokenize needs --memo-dir. There are two caches, not one: dropping a blob_map row makes " +
        "the walker re-run <command>, and <command> is tokenizeByBlobId/tokenBySha.pl, which answers from " +
        "$BFG_MEMO_DIR keyed on sha1(contents) with no tokenizer in the key. Invalidating one layer " +
        "without the other invalidates nothing at all.")
    def retokenizeUncovered(unknown: Set[String], known: Set[String]): String =
      s"Error: --retokenize names extension(s) --tokenizer-identity does not cover: " +
        s"${unknown.toVector.sorted.mkString(", ")}. Known: ${known.toVector.sorted.mkString(", ")}."
    val RetokenizeWithMaskWidened =
      "Error: --mask-widened and --retokenize cannot be combined. Each verifies its own precondition " +
        "against the rows, and together each would verify against a state the other is about to " +
        "change. Widen first, then resume with --retokenize."
    val RetokenizeUnderShard =
      "Error: --retokenize has nothing to do under --shard: each shard builds a fresh dst.git and " +
        "blobmap.db, so there are no cached tokenizations to invalidate. Retokenize the merged result, " +
        "or drop the shards' --warm=<db>."
    val MemoDirWithoutRetokenize =
      "Error: --memo-dir has no effect without --retokenize. Nothing else in blobExec reads the memo " +
        "— tokenizeByBlobId/tokenBySha.pl takes it from $BFG_MEMO_DIR in the environment."

    def workerNotExecutable(path: Path): String = s"Error: --tokenizer-worker [$path] is not an executable file"
    val WorkerNeedsPipeline = "Error: --tokenizer-worker needs --pipeline or --pipeline-trees"

    def srcNotADirectory(path: Path): String = s"Error: src repo [$path] is not a directory"
    def commandMissing(command: String): String = s"Error: command [$command] does not exist"
    val EmptyMask = "Error: fileMaskRegex must be non-empty"

    def stallRaised(from: Int, to: Int, blobTimeoutSeconds: Int): String =
      s"blobExec: raising the stall window from ${from}s to ${to}s, because " +
        s"--blob-timeout=${blobTimeoutSeconds}s needs a window that clears one blob's whole lifetime. " +
        "Pass --stall-timeout explicitly to choose your own."

    def error(e: Throwable): String = s"Error: ${e.getMessage}"
    val MaskWidenedHint =
      "Hint: if the new mask is a strict superset of the recorded one, --mask-widened reuses the " +
        "tokenizations already in blob_map instead of redoing them. It verifies that against the rows " +
        "themselves and refuses if it is not true. It requires the work directory to be intact (resume " +
        "at step 2), because blob_map's ids live in dst."

    def denylisted(count: Long, entries: Int): String =
      s"blobExec: $count blob(s) were excluded by the blob denylist " +
        s"(${BlobDenylist.EntriesSource}, $entries entr${if (entries == 1) "y" else "ies"}). Each one is " +
        "named with its sha, path, reason and upstream citation on an 'EXCLUDED denylisted blob' line " +
        "above; those lines and that list are the record of what this project's dataset does not " +
        "contain. The files are absent from the tokenized repository, not present as raw source, so " +
        "they produce no blame and no dataset row. This is not a failure and does not affect the exit status."

    def oversized(count: Long): String =
      s"blobExec: $count blob(s) were excluded as oversized (>= ${Walker.MaxBlobBytes} bytes, JGit's " +
        "stream-file threshold). Each one is named with its sha, path and size on an 'EXCLUDED " +
        "oversized blob' line above; those lines are the record of what this project's dataset does " +
        "not contain. The files are absent from the tokenized repository, not present as raw source, " +
        "so they produce no blame and no dataset row. This is not a failure and does not affect the exit status."

    def failed(timedOut: Long, crashed: Long, errored: Long, blobTimeoutSeconds: Int): String =
      s"blobExec: ${timedOut + crashed + errored} blob(s) were excluded because their tokenizer failed " +
        s"($timedOut timed out after ${blobTimeoutSeconds}s, $crashed reported a parser crash, " +
        s"$errored exited non-zero). Each one is named on an 'EXCLUDED failed blob' line above; " +
        "those lines are the record of what this project's dataset does not contain. This does " +
        "not affect the exit status."
  }

  /** `--name=<value>` as a pattern: `case Flag(value) =>`. */
  private final class ValuedFlag(val name: String) {
    private val prefix = name + "="
    def unapply(arg: String): Option[String] = Option.when(arg.startsWith(prefix))(arg.substring(prefix.length))
  }

  private val TokenizerIdentityFlag = new ValuedFlag("--tokenizer-identity")
  private val RetokenizeFlag        = new ValuedFlag("--retokenize")
  private val MemoDirFlag           = new ValuedFlag("--memo-dir")
  private val ShardFlag             = new ValuedFlag("--shard")
  private val WarmFlag              = new ValuedFlag("--warm")
  private val TokenizerWorkerFlag   = new ValuedFlag("--tokenizer-worker")
  private val BlobTimeoutFlag       = new ValuedFlag("--blob-timeout")
  private val StallTimeoutFlag      = new ValuedFlag("--stall-timeout")
  private val CommitsPerTxFlag      = new ValuedFlag("--commits-per-transaction")
  private val SqliteCacheFlag       = new ValuedFlag("--sqlite-cache-mb")
  private val SqliteMmapFlag        = new ValuedFlag("--sqlite-mmap-mb")
  private val LooseCompressionFlag  = new ValuedFlag("--loose-compression")

  private def flagValue[A](flag: ValuedFlag, spec: String)(parse: String => Either[String, A]): Either[String, A] =
    parse(spec).left.map(Messages.badFlagValue(flag.name, _))

  private def positive(spec: String): Either[String, Int] =
    spec.toIntOption.filter(_ > 0).toRight(s"must be a positive whole number [$spec]")

  private def mebibytes(spec: String): Either[String, Int] =
    spec.toIntOption.filter(_ >= 0).toRight(s"must be a whole number of MiB, 0 or more [$spec]")

  private def level(spec: String): Either[String, Int] =
    spec.toIntOption.filter(n => n >= -1 && n <= 9).toRight(s"must be a zlib level, -1 to 9 [$spec]")

  private def seconds(spec: String): Either[String, Int] =
    parsePositiveSeconds(spec).toRight(Messages.secondsWanted(spec))

  private def shardSpec(spec: String): Either[String, (Int, Int)] =
    spec.split("/", -1) match {
      case Array(k, n) =>
        (k.toIntOption, n.toIntOption) match {
          case (Some(k), Some(n)) if n >= 1 && k >= 0 && k < n => Right((k, n))
          case _                                               => Left(Messages.shardRange(spec))
        }
      case _ => Left(Messages.shardForm(spec))
    }

  private def existingPath(isValid: Path => Boolean, otherwise: String)(spec: String): Either[String, Path] = {
    val path = Paths.get(spec)
    if (isValid(path)) Right(path) else Left(s"[$path] $otherwise")
  }

  private def parseFlag(o: Options, flag: String): Either[String, Options] = flag match {
    case "--abort-on-error" => Right(o.copy(abortOnError = true))
    case "--pipeline"       => Right(o.copy(pipeline = true))
    case "--pipeline-trees" => Right(o.copy(pipelineTrees = true))
    case "--mask-widened"   => Right(o.copy(maskWidened = true))
    case "--alternates"     => Right(o.copy(alternates = true))
    case TokenizerIdentityFlag(spec) =>
      flagValue(TokenizerIdentityFlag, spec)(TokenizerIdentity.parse).map(id => o.copy(tokenizerIdentity = id))
    case RetokenizeFlag(spec) =>
      flagValue(RetokenizeFlag, spec)(TokenizerIdentity.parseExtensions).map(exts => o.copy(retokenize = exts))
    case MemoDirFlag(spec) =>
      flagValue(MemoDirFlag, spec)(existingPath(Files.isDirectory(_), Messages.NotTheMemo))
        .map(dir => o.copy(memoDir = Some(dir)))
    case ShardFlag(spec) =>
      flagValue(ShardFlag, spec)(shardSpec).map(kn => o.copy(shard = Some(kn)))
    case WarmFlag(spec) =>
      flagValue(WarmFlag, spec)(existingPath(Files.isRegularFile(_), Messages.NotAFile))
        .map(db => o.copy(warm = Some(db)))
    case TokenizerWorkerFlag(spec) =>
      Right(o.copy(tokenizerWorker = Some(Paths.get(spec))))
    case BlobTimeoutFlag(spec) =>
      flagValue(BlobTimeoutFlag, spec)(seconds).map(secs => o.copy(blobTimeoutSeconds = secs))
    case CommitsPerTxFlag(spec) =>
      flagValue(CommitsPerTxFlag, spec)(positive).map(n => o.copy(commitsPerTransaction = n))
    case SqliteCacheFlag(spec) =>
      flagValue(SqliteCacheFlag, spec)(mebibytes).map(n => o.copy(sqlite = o.sqlite.copy(cacheMiB = n)))
    case SqliteMmapFlag(spec) =>
      flagValue(SqliteMmapFlag, spec)(mebibytes).map(n => o.copy(sqlite = o.sqlite.copy(mmapMiB = n)))
    case LooseCompressionFlag(spec) =>
      flagValue(LooseCompressionFlag, spec)(level).map(n => o.copy(looseCompression = n))
    case StallTimeoutFlag(spec) =>
      flagValue(StallTimeoutFlag, spec)(seconds).map(secs => o.copy(stallTimeoutSeconds = secs, stallExplicit = true))
    case other => Left(Messages.unknownFlag(other))
  }

  /** The first rule a combination of flags breaks, in the order they are checked. */
  private def combinationError(o: Options): Option[String] = {
    val retokenizing = o.retokenize.nonEmpty
    val uncovered    = o.retokenize -- o.tokenizerIdentity.extensions
    val rules: Seq[(Boolean, () => String)] = Seq(
      (o.pipeline && o.pipelineTrees)               -> (() => Messages.PipelinesExclusive),
      (o.shard.isDefined && o.maskWidened)          -> (() => Messages.MaskWidenedUnderShard),
      (retokenizing && o.tokenizerIdentity.isEmpty) -> (() => Messages.RetokenizeNeedsIdentity),
      (retokenizing && o.memoDir.isEmpty)           -> (() => Messages.RetokenizeNeedsMemoDir),
      (retokenizing && uncovered.nonEmpty)          ->
        (() => Messages.retokenizeUncovered(uncovered, o.tokenizerIdentity.extensions)),
      (retokenizing && o.maskWidened)               -> (() => Messages.RetokenizeWithMaskWidened),
      (retokenizing && o.shard.isDefined)           -> (() => Messages.RetokenizeUnderShard),
      (!retokenizing && o.memoDir.isDefined)        -> (() => Messages.MemoDirWithoutRetokenize),
      (o.shard.isDefined && o.anyPipeline)          -> (() => Messages.ShardWithPipeline),
      (o.shard.isDefined && o.alternates)           -> (() => Messages.AlternatesUnderShard),
      o.tokenizerWorker.exists(p => !Files.isRegularFile(p) || !Files.isExecutable(p)) ->
        (() => Messages.workerNotExecutable(o.tokenizerWorker.get)),
      (o.tokenizerWorker.isDefined && !o.anyPipeline) -> (() => Messages.WorkerNeedsPipeline)
    )
    rules.collectFirst { case (true, message) => message() }
  }

  private def positionalError(o: Options): Option[String] =
    if (!Files.isDirectory(o.src)) Some(Messages.srcNotADirectory(o.src))
    else if (!Files.exists(Paths.get(o.command))) Some(Messages.commandMissing(o.command))
    else if (o.mask.isEmpty) Some(Messages.EmptyMask)
    else None

  /** Left is the whole text to print before exiting with the usage status. */
  private[blobexec] def parse(args: Seq[String]): Either[String, Options] = {
    val (flags, positional) = args.partition(_.startsWith("-"))
    for {
      parsed <- flags.foldLeft[Either[String, Options]](Right(Options()))((acc, f) => acc.flatMap(parseFlag(_, f)))
      _      <- parsed.stallWindow.left.map(why => s"Error: $why")
      _      <- combinationError(parsed).toLeft(())
      _      <- Either.cond(positional.length == 5, (), Usage)
      full    = parsed.copy(positional = positional.toVector)
      _      <- positionalError(full).toLeft(())
    } yield full
  }

  def main(args: Array[String]): Unit = {
    val options = parse(args.toVector) match {
      case Right(o)  => o
      case Left(why) => exit(why, UsageExitStatus)
    }
    val stats = run(options, stallSeconds(options))
    // Exit explicitly: a timed-out blob's daemon reader threads can keep the JVM alive.
    sys.exit(exitStatus(stats))
  }

  private def exit(message: String, status: Int): Nothing = {
    System.err.println(message)
    sys.exit(status)
  }

  private def stallSeconds(o: Options): Int = {
    val secs = o.stallWindow.getOrElse(o.stallTimeoutSeconds)
    if (secs != o.stallTimeoutSeconds)
      System.err.println(Messages.stallRaised(o.stallTimeoutSeconds, secs, o.blobTimeoutSeconds))
    secs
  }

  private def run(o: Options, stallSeconds: Int): WalkStats = {
    createParentDirectory(o.db)
    val incremental = Files.isDirectory(o.dst)
    println(startLine(o, stallSeconds, incremental))

    val src = openSrc(o.src)
    val dst = openOrInitDst(o.dst, borrowFrom = Option.when(o.alternates)(src.getObjectsDirectory.toPath))
    setLooseCompression(dst, o.looseCompression)
    val mapping = openMapping(o, src, dst) match {
      case Right(m) => m
      case Left(refusal) =>
        src.close(); dst.close()
        exit(refusal.message, refusal.status)
    }

    val stats =
      try walk(o, stallSeconds, src, dst, mapping, incremental)
      finally { mapping.close(); dst.close(); src.close() }
    println(doneLine(stats))
    reportExclusions(stats, o.blobTimeoutSeconds)
    stats
  }

  // Not Files.createDirectories alone: it throws on macOS when the target is a symlink (/tmp).
  private def createParentDirectory(path: Path): Unit =
    Option(path.getParent).filterNot(Files.isDirectory(_)).foreach(Files.createDirectories(_))

  private def startLine(o: Options, stallSeconds: Int, incremental: Boolean): String = {
    def orNone(value: Option[Any]): String = value.map(_.toString).getOrElse("none")
    s"blobExec: src=${o.src} dst=${o.dst} db=${o.db} command=${o.command} mask=${o.mask} " +
      s"abortOnError=${o.abortOnError} pipeline=${o.pipeline} pipelineTrees=${o.pipelineTrees} " +
      s"shard=${orNone(o.shard.map { case (k, n) => s"$k/$n" })} warm=${orNone(o.warm)} " +
      s"blobTimeout=${o.blobTimeoutSeconds}s stallTimeout=${stallSeconds}s incremental=$incremental " +
      s"maskWidened=${o.maskWidened} denylistEntries=${BlobDenylist.shipped.size} " +
      s"tokenizerIdentity=${if (o.tokenizerIdentity.isEmpty) "none" else o.tokenizerIdentity.render} " +
      s"retokenize=${if (o.retokenize.isEmpty) "none" else o.retokenize.toVector.sorted.mkString(",")} " +
      s"memoDir=${orNone(o.memoDir)} tokenizerWorker=${orNone(o.tokenizerWorker)} " +
      s"alternates=${o.alternates} commitsPerTransaction=${o.commitsPerTransaction} " +
      s"sqliteCacheMiB=${o.sqlite.cacheMiB} sqliteMmapMiB=${o.sqlite.mmapMiB} " +
      s"looseCompression=${o.looseCompression}"
  }

  private final case class Refusal(status: Int, message: String)

  private def openMapping(o: Options, src: FileRepository, dst: FileRepository): Either[Refusal, Mapping] =
    Try(Mapping.open(o.db, o.command, o.mask, o.warm, widening(o, dst), o.tokenizerIdentity, retokenize(o, src, dst),
                     o.sqlite))
      .toEither
      .left.map(e => refusalFor(e, o).getOrElse(throw e))

  private def refusalFor(e: Throwable, o: Options): Option[Refusal] = e match {
    case _: Mapping.MetaMismatchException if !o.maskWidened && e.getMessage.contains("mask") =>
      Some(Refusal(RefusedExitStatus, s"${Messages.error(e)}\n${Messages.MaskWidenedHint}"))
    case _: Mapping.MetaMismatchException | _: Mapping.MaskNarrowedException |
        _: Mapping.DanglingNewBlobException | _: Mapping.TokenizerChangedException =>
      Some(Refusal(RefusedExitStatus, Messages.error(e)))
    // Not 0: a corpus script would publish entries that were never invalidated.
    case _: Mapping.NothingInvalidatedException => Some(Refusal(RetokenizeIneffectiveExitStatus, Messages.error(e)))
    // Mapping.open re-checks the flag rules above, for library callers.
    case _: IllegalArgumentException => Some(Refusal(UsageExitStatus, Messages.error(e)))
    case _                           => None
  }

  private val report: String => Unit = msg => println(s"blobExec: $msg")

  private def widening(o: Options, dst: FileRepository): Option[Mapping.MaskWidening] =
    Option.when(o.maskWidened)(Mapping.MaskWidening(newBlobResolves = hasObject(dst), report = report))

  // The memo is keyed on content sha1 alone (tokenizeByBlobId/tokenBySha.pl:76),
  // so the purge re-reads the original blobs from src.
  private def retokenize(o: Options, src: FileRepository, dst: FileRepository): Option[Mapping.Retokenize] =
    Option.when(o.retokenize.nonEmpty)(Mapping.Retokenize(
      extensions = o.retokenize,
      newBlobResolves = hasObject(dst),
      purgeMemo = blobs => TokenizerMemo.purge(o.memoDir.get, blobs, blobBytes(src)),
      report = report,
      memoDirIsTokenizerMemo = o.memoDir.exists(d => TokenizerMemo.isTokenizerMemo(d, sys.env.get("BFG_MEMO_DIR")))
    ))

  private def hasObject(repo: FileRepository)(id: String): Boolean = {
    val reader = repo.newObjectReader()
    try reader.has(ObjectId.fromString(id))
    catch { case _: IllegalArgumentException => false }
    finally reader.close()
  }

  private def blobBytes(repo: FileRepository)(sha: String): Option[Array[Byte]] = {
    val reader = repo.newObjectReader()
    try Some(reader.open(ObjectId.fromString(sha), Constants.OBJ_BLOB).getBytes)
    catch { case _: Exception => None }
    finally reader.close()
  }

  private def walk(
      o: Options,
      stallSeconds: Int,
      src: FileRepository,
      dst: FileRepository,
      mapping: Mapping,
      incremental: Boolean
  ): WalkStats = {
    val parallelism = math.max(1, Runtime.getRuntime.availableProcessors)
    val workerPool  = o.tokenizerWorker.map(path => new TokenizerWorkerPool(path.toString, parallelism))
    try
      new Walker(
        src, dst, mapping, o.mask.r, o.command, o.abortOnError, parallelism,
        o.pipeline, o.pipelineTrees, o.shard,
        destinationMayContainObjects = incremental,
        borrowOriginalObjects = o.alternates,
        blobTimeoutSeconds = o.blobTimeoutSeconds,
        stallTimeoutSeconds = stallSeconds,
        workerPool = workerPool,
        commitsPerTransaction = o.commitsPerTransaction,
        denylist = BlobDenylist.shipped
      ).run()
    finally workerPool.foreach(_.close())
  }

  private def doneLine(stats: WalkStats): String =
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
      s"originalBlobsBorrowed=${stats.originalBlobsBorrowed} " +
      s"refsProjected=${stats.refsProjected} " +
      s"blobsTimedOut=${stats.blobsTimedOut} " +
      s"blobsOversized=${stats.blobsOversized} " +
      s"blobsDenylisted=${stats.blobsDenylisted} " +
      s"blobsParserCrashed=${stats.blobsParserCrashed} " +
      s"blobsTokenizerFailed=${stats.blobsTokenizerFailed} " +
      s"aborted=${stats.aborted}"

  private def reportExclusions(stats: WalkStats, blobTimeoutSeconds: Int): Unit = {
    if (stats.blobsDenylisted > 0)
      System.err.println(Messages.denylisted(stats.blobsDenylisted, BlobDenylist.shipped.size))
    if (stats.blobsOversized > 0)
      System.err.println(Messages.oversized(stats.blobsOversized))
    val failed = stats.blobsTimedOut + stats.blobsParserCrashed + stats.blobsTokenizerFailed
    if (failed > 0)
      System.err.println(
        Messages.failed(stats.blobsTimedOut, stats.blobsParserCrashed, stats.blobsTokenizerFailed, blobTimeoutSeconds))
  }

  private def openSrc(path: Path): FileRepository = {
    val gitDir = if (Files.isDirectory(path.resolve(".git"))) path.resolve(".git").toFile else path.toFile
    FileRepositoryBuilder.create(gitDir).asInstanceOf[FileRepository]
  }

  /** `borrowFrom` names src's objects directory: it is written to dst's
    * objects/info/alternates BEFORE dst is opened for the walk, because JGit reads
    * that file once, and a walk that skipped a copy must find the object there. */
  private[blobexec] def openOrInitDst(path: Path, borrowFrom: Option[Path] = None): FileRepository = {
    val exists = Files.isDirectory(path)
    val repo = FileRepositoryBuilder.create(path.toFile).asInstanceOf[FileRepository]
    if (!exists) repo.create(true)
    borrowFrom match {
      case None => repo
      case Some(objects) =>
        val info = repo.getObjectsDirectory.toPath.resolve("info")
        repo.close()
        Files.createDirectories(info)
        writeAlternates(info.resolve("alternates"), objects)
        FileRepositoryBuilder.create(path.toFile).asInstanceOf[FileRepository]
    }
  }

  /** In memory only: dst's config file is not touched, so nothing that reads dst
    * later (git repack, steps 3+) can see it. Object ids do not depend on it. JGit
    * reads it when an inserter is created; a reload of the file would only bring
    * back the default level, a speed loss, never a different object. */
  private[blobexec] def setLooseCompression(dst: FileRepository, level: Int): Unit = {
    val config = dst.getConfig  // loads the file first, if it changed
    config.setInt("core", null, "compression", level)
  }

  /** One absolute line, replacing whatever was there: a resume writes the same
    * file again. Written to a temporary file and renamed, so git never reads half. */
  private[blobexec] def writeAlternates(file: Path, objects: Path): Unit = {
    val line = objects.toAbsolutePath.normalize.toString + "\n"
    val tmp  = file.resolveSibling(file.getFileName.toString + ".tmp")
    Files.write(tmp, line.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
      java.nio.file.StandardCopyOption.ATOMIC_MOVE)
  }
}
