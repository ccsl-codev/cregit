package cregit.blobexec

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters._
import scala.util.Using

/** Blobs never handed to the tokenizer, keyed by git blob id. The list is a data
  * file ([[BlobDenylist.ResourcePath]]) so the dataset can cite it. */
final class BlobDenylist private (private val bySha: Map[String, BlobDenylist.Entry]) {

  def entryFor(sha: String): Option[BlobDenylist.Entry] =
    if (bySha.isEmpty) None else bySha.get(sha.toLowerCase)

  def size: Int = bySha.size
  def isEmpty: Boolean = bySha.isEmpty
  def shas: Set[String] = bySha.keySet
  def entries: Vector[BlobDenylist.Entry] = bySha.values.toVector.sortBy(_.sha)
}

object BlobDenylist {

  final case class Entry(sha: String, citation: String, reason: String) {
    def describe: String = s"$reason [$citation]"
  }

  val ResourcePath = "/cregit/blobexec/blob-denylist.tsv"

  val empty: BlobDenylist = new BlobDenylist(Map.empty)

  private val ShaPattern = "^[0-9a-f]{40}$".r

  /** Throws on malformed input: a dropped line would silently re-admit a blob. */
  def parse(lines: IterableOnce[String], where: String): BlobDenylist = {
    val acc = scala.collection.mutable.LinkedHashMap.empty[String, Entry]
    lines.iterator.zipWithIndex.foreach { case (raw, i) =>
      val line = raw.trim
      if (line.nonEmpty && !line.startsWith("#")) {
        // Trailing tabs are tolerated; an empty field in the middle is not.
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

  /** Parsed on first use; a parse failure is fatal by design. */
  lazy val shipped: BlobDenylist = fromResource(ResourcePath)
}
