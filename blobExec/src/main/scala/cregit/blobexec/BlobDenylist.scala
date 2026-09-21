package cregit.blobexec

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters._
import scala.util.Using

/** Blobs never handed to the tokenizer, keyed by git blob id: srcML 1.1.0 does
  * not terminate on them. Excluded like an oversized blob, and deliberately not
  * gating the exit status. The entries live in a citable data file, not here. */
final class BlobDenylist private (private val bySha: Map[String, BlobDenylist.Entry]) {

  def entryFor(sha: String): Option[BlobDenylist.Entry] = bySha.get(sha.toLowerCase)

  def size: Int = bySha.size
  def isEmpty: Boolean = bySha.isEmpty
  def shas: Set[String] = bySha.keySet
  def entries: Vector[BlobDenylist.Entry] = bySha.values.toVector.sortBy(_.sha)
}

object BlobDenylist {

  /** One excluded blob. Citation and reason are both required. */
  final case class Entry(sha: String, citation: String, reason: String)

  /** Classpath location of the shipped list, inside the assembly jar. */
  val ResourcePath = "/cregit/blobexec/blob-denylist.tsv"

  val empty: BlobDenylist = new BlobDenylist(Map.empty)

  private val ShaPattern = "^[0-9a-f]{40}$".r

  /** Parse the TSV; malformed input throws. `where` names the source in errors. */
  def parse(lines: IterableOnce[String], where: String): BlobDenylist = {
    val acc = scala.collection.mutable.LinkedHashMap.empty[String, Entry]
    lines.iterator.zipWithIndex.foreach { case (raw, i) =>
      val line = raw.trim
      if (line.nonEmpty && !line.startsWith("#")) {
        // Trailing tabs are tolerated; an empty field is not.
        val fields = line.split("\t", -1).map(_.trim).reverse.dropWhile(_.isEmpty).reverse
        if (fields.length != 3 || fields.exists(_.isEmpty))
          throw new IllegalArgumentException(
            s"$where:${i + 1}: expected 3 non-empty tab-separated fields " +
              s"(sha, citation, reason), got ${fields.length}: [$line]")
        val Array(sha, citation, reason) = fields
        val key = sha.toLowerCase
        if (!ShaPattern.matches(key))
          throw new IllegalArgumentException(
            s"$where:${i + 1}: [$sha] is not a 40-character hex git blob sha")
        if (acc.contains(key))
          throw new IllegalArgumentException(s"$where:${i + 1}: [$key] is listed twice")
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
