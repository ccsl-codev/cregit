package cregit.blobexec

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters._
import scala.util.Using

/** Blobs that must never be handed to the tokenizer, keyed by git blob id.
  *
  * srcML 1.1.0 does not terminate on a few known blobs: it produces 0 bytes, pegs
  * one core and never finishes, at any budget. The per-blob timeout (see
  * [[BlobExec]]) contains that, at the price of exit 4 — "incomplete, do not
  * publish". Exit 4 is the right answer for an undiagnosed hang. A blob reaches
  * this list only with a minimal reproducer and an open upstream defect, so exit 4
  * would hold its project back on a third-party parser bug that is already known.
  *
  * A denylisted blob takes the exclusion path an oversized blob takes: excluded in
  * milliseconds, dropped from every rewritten tree, counted on its own, logged
  * with its sha, path and reason, and NOT gating the exit status. The timeout stays
  * exactly as it was.
  *
  * The entries live in a data file rather than in this code
  * ([[BlobDenylist.ResourcePath]]), because the list is a documented property of
  * the published dataset and a paper has to be able to cite it.
  */
final class BlobDenylist private (private val bySha: Map[String, BlobDenylist.Entry]) {

  /** The entry for `sha`, or None. Case-insensitive, because a git id is hex and
    * both cases name the same object. */
  def entryFor(sha: String): Option[BlobDenylist.Entry] =
    if (bySha.isEmpty) None else bySha.get(sha.toLowerCase)

  def size: Int = bySha.size
  def isEmpty: Boolean = bySha.isEmpty
  def shas: Set[String] = bySha.keySet
  def entries: Vector[BlobDenylist.Entry] = bySha.values.toVector.sortBy(_.sha)
}

object BlobDenylist {

  /** One excluded blob: the git blob id, an upstream citation, and one line of
    * reason. All three are required — an exclusion with no citation and no reason
    * is indistinguishable from data loss. */
  final case class Entry(sha: String, citation: String, reason: String) {
    /** The half of the log line that comes from the data file. */
    def describe: String = s"$reason [$citation]"
  }

  /** Classpath location of the shipped list. A resource, so it travels inside the
    * assembly jar and cannot go missing between the build and the run. */
  val ResourcePath = "/cregit/blobexec/blob-denylist.tsv"

  val empty: BlobDenylist = new BlobDenylist(Map.empty)

  private val ShaPattern = "^[0-9a-f]{40}$".r

  /** Parse the TSV. Malformed input throws: a denylist that silently drops a line
    * would silently re-introduce a blob that hangs the whole run for 600 s and then
    * blocks publication, which is precisely the failure this list removes.
    *
    * `where` names the source in any error message, because the same parser reads
    * the shipped resource and a test fixture.
    */
  def parse(lines: IterableOnce[String], where: String): BlobDenylist = {
    val acc = scala.collection.mutable.LinkedHashMap.empty[String, Entry]
    lines.iterator.zipWithIndex.foreach { case (raw, i) =>
      val line = raw.trim
      if (line.nonEmpty && !line.startsWith("#")) {
        // Trailing tabs are tolerated; an empty field in the middle is not. A
        // missing citation or a missing reason has to be an error, or the list
        // stops being something a paper can cite.
        val fields = line.split("\t", -1).map(_.trim).reverse.dropWhile(_.isEmpty).reverse
        if (fields.length != 3 || fields.exists(_.isEmpty))
          throw new IllegalArgumentException(
            s"$where:${i + 1}: expected 3 non-empty tab-separated fields " +
              s"(sha, citation, reason), got ${fields.length}: [$line]")
        val Array(sha, citation, reason) = fields
        val key = sha.toLowerCase
        if (ShaPattern.findFirstIn(key).isEmpty)
          throw new IllegalArgumentException(
            s"$where:${i + 1}: [$sha] is not a 40-character hex git blob sha. The key is the " +
              "git blob id, which is what blobExec logs, not the memo's content hash.")
        if (acc.contains(key))
          throw new IllegalArgumentException(
            s"$where:${i + 1}: [$key] is listed twice. Two reasons for one blob means one of " +
              "them is wrong, and the list is what the paper cites.")
        acc.update(key, Entry(key, citation, reason))
      }
    }
    new BlobDenylist(acc.toMap)
  }

  def fromFile(path: Path): BlobDenylist =
    parse(Files.readAllLines(path, UTF_8).asScala, path.toString)

  def fromResource(resource: String): BlobDenylist = {
    val stream = Option(getClass.getResourceAsStream(resource)).getOrElse(
      throw new IllegalStateException(
        s"blobExec resource [$resource] is missing from the classpath. The blob denylist " +
          "ships inside the jar; a jar built without it would hand four known " +
          "non-terminating blobs to srcml again."))
    Using.resource(scala.io.Source.fromInputStream(stream, UTF_8.name))(src =>
      parse(src.getLines().toVector, resource))
  }

  /** The shipped list. Parsed once, on first use, and a parse failure is fatal by
    * design: running with a list nobody could read is worse than not running. */
  lazy val shipped: BlobDenylist = fromResource(ResourcePath)
}
