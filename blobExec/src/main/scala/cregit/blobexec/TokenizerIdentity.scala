package cregit.blobexec

/** Which tokenizer produced the tokens for each file extension.
  *
  * The problem this type exists for. `blob_map` reuse was decided on `command`
  * and `mask` alone (see `Mapping.open`), and `command` is the fixed path
  * `tokenizeByBlobId/tokenBySha.pl` — a constant for the life of the pipeline.
  * So when the Rust tokenizer's output format was corrected in 729643e, nothing
  * about the recorded metadata changed, every cached `.rs` row stayed a cache
  * hit, and a run with the corrected tokenizer reproduced the old, wrong tokens
  * exactly. The mask cannot stand in for this: the mask decides WHICH files are
  * tokenized and never HOW.
  *
  * The identity is per EXTENSION rather than per language, because the extension
  * is what the cache rows can be selected by: `blob_map` stores paths, not
  * languages. `CregitLanguages.pm` maps extension to language and language to
  * parser, so the pipeline collapses that chain into one value per extension
  * before handing it over (see `tokenize/tokenizerIdentity.pl`).
  *
  * The value is opaque here on purpose. This code never needs to know whether it
  * is a hash of a binary, a version string the tokenizer reports, or a digest of
  * a whole toolchain — only whether it is the same string as last time. */
final case class TokenizerIdentity(byExtension: Map[String, String]) {

  def isEmpty: Boolean = byExtension.isEmpty
  def nonEmpty: Boolean = byExtension.nonEmpty
  def extensions: Set[String] = byExtension.keySet
  def get(extension: String): Option[String] = byExtension.get(extension)

  /** Canonical, sorted rendering: the same map always prints the same string, so
    * it can go in a log or a refusal message and be compared by eye. */
  def render: String =
    byExtension.toVector.sorted.map { case (e, id) => s"$e=$id" }.mkString(",")
}

object TokenizerIdentity {

  val empty: TokenizerIdentity = TokenizerIdentity(Map.empty)

  /** Extensions as `CregitLanguages.pm` spells them: lowercase, no leading dot.
    * `c++` and `h++` are real entries in that table, hence the `+`. The pattern
    * is also a safety property: these strings are interpolated into a SQL LIKE
    * pattern in [[Mapping]], and this alphabet contains no quote, no `%` and no
    * `_`, so there is nothing to escape and nothing to inject. */
  private[blobexec] val ExtensionPattern = "[a-z0-9+]+"

  /** The identity value: hex, and long enough to be a digest rather than a
    * guess. Bounded above so a malformed argument cannot become a meta row of
    * arbitrary size. */
  private[blobexec] val ValuePattern = "[0-9a-f]{8,128}"

  private val EntryRe = s"($ExtensionPattern)=($ValuePattern)".r

  /** Parse `ext=value[,ext=value]...`.
    *
    * Left on anything malformed, naming the offending entry. Refusing a
    * malformed identity matters more than it looks: a value this code does not
    * understand would be recorded verbatim, compare unequal to the next run's,
    * and turn every subsequent run into a refusal nobody can explain. */
  def parse(spec: String): Either[String, TokenizerIdentity] = {
    val trimmed = spec.trim
    if (trimmed.isEmpty)
      return Left("a tokenizer identity must name at least one extension, as ext=value")
    val entries = trimmed.split(",", -1).toVector
    val parsed = entries.map {
      case EntryRe(ext, value) => Right((ext, value))
      case other =>
        Left(
          s"cannot read [$other] as a tokenizer identity entry. Wanted " +
            s"<extension>=<value>, extension matching $ExtensionPattern (lowercase, no " +
            s"leading dot, as in CregitLanguages.pm) and value matching $ValuePattern."
        )
    }
    parsed.collectFirst { case Left(why) => Left(why) }.getOrElse {
      val pairs = parsed.collect { case Right(p) => p }
      val duplicated = pairs.groupBy(_._1).collect { case (e, vs) if vs.size > 1 => e }
      if (duplicated.nonEmpty)
        Left(s"extension(s) named more than once in the tokenizer identity: " +
          duplicated.toVector.sorted.mkString(", "))
      else Right(TokenizerIdentity(pairs.toMap))
    }
  }

  /** Parse a `--retokenize=` extension list. Same alphabet as above, so the two
    * cannot disagree about what an extension looks like. */
  def parseExtensions(spec: String): Either[String, Set[String]] = {
    val trimmed = spec.trim
    if (trimmed.isEmpty)
      return Left("--retokenize must name at least one extension, as in --retokenize=rs")
    val parts = trimmed.split(",", -1).toVector.map(_.trim)
    val bad = parts.filterNot(_.matches(ExtensionPattern))
    if (bad.nonEmpty)
      Left(s"not an extension: ${bad.map(b => s"[$b]").mkString(", ")}. Wanted lowercase " +
        s"names without a leading dot, matching $ExtensionPattern (as in CregitLanguages.pm): " +
        "--retokenize=rs, --retokenize=c,h")
    else Right(parts.toSet)
  }
}
