package cregit.blobexec

import java.nio.file.{Files, Path}
import java.security.MessageDigest

/** The tokenBySha.pl memo, keyed by `sha1_hex(contents)` alone (tokenBySha.pl:76,83-84).
  * A dropped `blob_map` row would be refilled from it with stale tokens, so
  * `--retokenize` purges both, the memo first: a lost entry only costs a rerun. */
object TokenizerMemo {

  /** `missing` is normal (excluded blobs never had an entry); [[Mapping]] refuses
    * on `deleted == 0` with `examined > 0`. */
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

  /** Must match tokenBySha.pl:83-84 byte for byte; pinned by `TokenizerMemoSpec`. */
  def relativePathFor(sha1Hex: String): String = {
    require(sha1Hex.length >= 4, s"not a sha1: [$sha1Hex]")
    s"${sha1Hex.substring(0, 2)}/${sha1Hex.substring(2, 4)}/$sha1Hex"
  }

  def entryFor(root: Path, sha1Hex: String): Path =
    root.resolve(relativePathFor(sha1Hex))

  def isTokenizerMemo(memoDir: Path, bfgMemoDir: Option[String]): Boolean =
    bfgMemoDir.filter(_.nonEmpty).exists { env =>
      try Files.isSameFile(memoDir, java.nio.file.Paths.get(env))
      catch { case _: java.io.IOException | _: java.nio.file.InvalidPathException => false }
    }

  /** Delete the memo entries of `origBlobs`. `readBytes` is None for a blob that
    * cannot be materialised. One blob at a time: an invalidation can be ~10^6 rows. */
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
