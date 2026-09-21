package cregit.blobexec

import java.nio.file.{Files, Path}
import java.security.MessageDigest

/** The content-addressed memo `tokenizeByBlobId/tokenBySha.pl` keeps, described
  * from this side so it can be invalidated.
  *
  * That script is the `<command>` blobExec runs per blob. Before invoking a
  * tokenizer it hashes the bytes on its stdin and looks for
  * `$BFG_MEMO_DIR/xx/yy/<sha1>`; on a hit it prints that file and no tokenizer
  * runs at all. The key is `sha1_hex($contents)` and NOTHING else —
  * tokenBySha.pl:76,83-84. No tokenizer version, no extension, no language.
  *
  * So dropping a `blob_map` row is not enough to force a re-tokenization. The
  * walker re-runs the command for that blob, the command finds the memo entry,
  * and the same stale tokens come back. Both layers have to go together, which
  * is why `--retokenize` will not run without `--memo-dir`.
  *
  * Deleting a memo entry is always safe in the direction that matters: a miss
  * costs one tokenizer invocation and produces correct tokens, while a kept
  * entry produces wrong ones. That asymmetry is why the purge runs BEFORE the
  * database transaction — if the purge fails, nothing has been invalidated and
  * the run refuses; if the transaction fails afterwards, some cache entries were
  * thrown away and nothing is wrong. */
object TokenizerMemo {

  /** What the purge did. `missing` is not an error on its own: a blob excluded as
    * oversized or denylisted never reached the tokenizer, so it never had an
    * entry. `deleted == 0` with `examined > 0` IS, and [[Mapping]] refuses on it
    * — see there. */
  final case class PurgeReport(examined: Long, deleted: Long, missing: Long, unreadable: Long) {
    def render: String =
      s"examined=$examined deleted=$deleted missing=$missing unreadable=$unreadable"
  }

  object PurgeReport {
    val empty: PurgeReport = PurgeReport(0L, 0L, 0L, 0L)
  }

  /** `sha1_hex` of the bytes, matching Perl's `Digest::SHA::sha1_hex`. */
  def sha1Hex(bytes: Array[Byte]): String = {
    val md = MessageDigest.getInstance("SHA-1")
    val digest = md.digest(bytes)
    val sb = new StringBuilder(digest.length * 2)
    digest.foreach(b => sb.append("%02x".format(b)))
    sb.toString
  }

  /** The two-level fan-out tokenBySha.pl builds: `substr(0,2)/substr(2,2)/sha1`
    * (tokenBySha.pl:83-84). Replicated rather than abstracted over, because the
    * two have to agree byte for byte and a shared helper across a Perl/Scala
    * boundary is not available. `TokenizerMemoSpec` pins it against a literal
    * sha1 and, when perl is on PATH, against Digest::SHA itself. */
  def relativePathFor(sha1Hex: String): String = {
    require(sha1Hex.length >= 4, s"not a sha1: [$sha1Hex]")
    s"${sha1Hex.substring(0, 2)}/${sha1Hex.substring(2, 4)}/$sha1Hex"
  }

  def entryFor(root: Path, sha1Hex: String): Path =
    root.resolve(relativePathFor(sha1Hex))

  /** Delete the memo entries of `origBlobs`.
    *
    * `readBytes` turns an original blob sha into its bytes, or None when the
    * object cannot be materialised (oversized). Injected so this is testable
    * without a repository, and so the caller decides how the bytes are read.
    *
    * One blob at a time and nothing retained: the corpus figure for a `.rs`
    * invalidation is 741,869 rows, and holding their contents is not an option. */
  def purge(root: Path, origBlobs: Vector[String], readBytes: String => Option[Array[Byte]]): PurgeReport = {
    var deleted = 0L
    var missing = 0L
    var unreadable = 0L
    origBlobs.foreach { sha =>
      readBytes(sha) match {
        case None => unreadable += 1L
        case Some(bytes) =>
          val entry = entryFor(root, sha1Hex(bytes))
          if (Files.deleteIfExists(entry)) deleted += 1L else missing += 1L
      }
    }
    PurgeReport(origBlobs.size.toLong, deleted, missing, unreadable)
  }
}
