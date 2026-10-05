package cregit.blobexec

/** Opaque tokenizer identity per file extension, so `blob_map` reuse notices a
  * changed tokenizer. Per extension because `blob_map` stores paths, not
  * languages (see `tokenize/tokenizerIdentity.pl`). */
final case class TokenizerIdentity(byExtension: Map[String, String]) {

  def isEmpty: Boolean = byExtension.isEmpty
  def nonEmpty: Boolean = byExtension.nonEmpty
  def extensions: Set[String] = byExtension.keySet
  def get(extension: String): Option[String] = byExtension.get(extension)

  /** Sorted, so the same map always renders the same string. */
  def render: String =
    byExtension.toVector.sorted.map { case (e, id) => s"$e=$id" }.mkString(",")
}

object TokenizerIdentity {

  val empty: TokenizerIdentity = TokenizerIdentity(Map.empty)

  /** As `CregitLanguages.pm` spells them (`c++` is real). Interpolated into a SQL
    * LIKE in [[Mapping]]: no quote, `%` or `_` allowed, so nothing to escape. */
  private[blobexec] val ExtensionPattern = "[a-z0-9+]+"

  private[blobexec] val ValuePattern = "[0-9a-f]{8,128}"

  private val EntryRe = s"($ExtensionPattern)=($ValuePattern)".r

  /** Parse `ext=value[,ext=value]...`; Left names the malformed entry. */
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

  /** Parse a `--retokenize=` extension list. */
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
