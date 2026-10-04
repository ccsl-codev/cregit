package cregit.blobexec

import java.io.{BufferedWriter, IOException}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import scala.jdk.CollectionConverters._

/** Every blob that the tokenized repository does not contain, one row per (sha,
  * path, reason). A row is flushed when the skip occurs: a resume does not visit
  * the durable commits again, so it could not write their rows. Thread-safe. */
final class SkipLog private (val path: Option[Path], initial: Vector[SkipLog.Row]) {
  import SkipLog._

  private val lock = new AnyRef
  // Insertion order is kept so that a rewrite keeps the order of the file.
  private val rows = scala.collection.mutable.LinkedHashMap.empty[(String, String, String), Row]
  initial.foreach(r => rows.update(r.key, r))
  private var writer: Option[BufferedWriter] = None
  private var addedThisRun = 0L

  def enabled: Boolean = path.isDefined

  /** Rows that this run added (not the rows that were in the file before). */
  def added: Long = lock.synchronized(addedThisRun)

  /** All rows, in file order. */
  def all: Vector[Row] = lock.synchronized(rows.values.toVector)

  /** Add a row, unless a row with the same sha, path and reason is there. Returns
    * true if the row is new. */
  def record(row: Row): Boolean = lock.synchronized {
    val r = row.cleaned
    if (rows.contains(r.key)) false
    else {
      rows.update(r.key, r)
      addedThisRun += 1
      path.foreach { _ =>
        val w = openWriter()
        w.write(r.render)
        w.write('\n')
        w.flush()
      }
      true
    }
  }

  /** Remove the rows of this blob that have one of `reasons`. Returns how many
    * rows were removed. */
  def forget(sha: String, blobPath: String, reasons: Set[String]): Int = lock.synchronized {
    val keys = reasons.toVector.map(r => (sha, blobPath, r)).filter(rows.contains)
    if (keys.nonEmpty) {
      keys.foreach(rows.remove)
      path.foreach(rewrite)
    }
    keys.size
  }

  def close(): Unit = lock.synchronized {
    writer.foreach(w => try w.close() catch { case _: IOException => () })
    writer = None
  }

  private def openWriter(): BufferedWriter = writer.getOrElse {
    val p = path.get
    val parent = p.toAbsolutePath.getParent
    if (parent != null && !Files.isDirectory(parent)) Files.createDirectories(parent)
    val fresh = !Files.exists(p) || Files.size(p) == 0L
    val w = Files.newBufferedWriter(p, UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    if (fresh) { w.write(Header); w.write('\n'); w.flush() }
    writer = Some(w)
    w
  }

  private def rewrite(p: Path): Unit = {
    writer.foreach(w => try w.close() catch { case _: IOException => () })
    writer = None
    val tmp = p.resolveSibling(p.getFileName.toString + ".tmp")
    val body = (Header +: rows.values.toVector.map(_.render)).mkString("", "\n", "\n")
    Files.write(tmp, body.getBytes(UTF_8))
    Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    ()
  }
}

object SkipLog {

  val Header: String = "sha\tpath\treason\tdetail\ttokenizer"

  val Denylisted: String = "denylisted"
  val Oversized: String  = "oversized"

  /** Every reason a row can carry. The three tokenizer failures come from
    * [[BlobExec.Failure]]. */
  val Reasons: Set[String] =
    Set(Denylisted, Oversized, BlobExec.Failure.ParserCrash, BlobExec.Failure.EmptyOutput,
      BlobExec.Failure.Timeout)

  /** The reasons that a later tokenization of the same blob makes wrong. A
    * denylisted or oversized blob never gets to the tokenizer, so those rows stay. */
  val TokenizerFailures: Set[String] =
    Set(BlobExec.Failure.ParserCrash, BlobExec.Failure.EmptyOutput, BlobExec.Failure.Timeout)

  final case class Row(sha: String, path: String, reason: String, detail: String, tokenizer: String) {
    def key: (String, String, String) = (sha, path, reason)
    def render: String = Vector(sha, path, reason, detail, tokenizer).mkString("\t")
    private[blobexec] def cleaned: Row =
      Row(sha, BlobExec.Failure.clean(path), reason, BlobExec.Failure.clean(detail),
        BlobExec.Failure.clean(tokenizer))
  }

  /** A log that keeps its rows in memory only. For tests, and for a run that
    * was not given `--skipped-tsv`. */
  def disabled: SkipLog = new SkipLog(None, Vector.empty)

  /** Open `path`, and read the rows that are already in it. A missing or empty
    * file is a new log. A file with a different header, or a row that is not
    * five fields, is refused: the run must not append to a file it cannot read. */
  def open(path: Path): SkipLog =
    if (!Files.exists(path) || Files.size(path) == 0L) new SkipLog(Some(path), Vector.empty)
    else {
      val lines = Files.readAllLines(path, UTF_8).asScala.toVector
      if (lines.head != Header)
        throw new IllegalArgumentException(
          s"the skip file [$path] does not start with the header [${Header.replace("\t", "<TAB>")}]. " +
            "Move it away, or delete it, and run again.")
      val rows = lines.tail.zipWithIndex.filter(_._1.nonEmpty).map { case (line, i) =>
        line.split("\t", -1) match {
          case Array(sha, p, reason, detail, tok) => Row(sha, p, reason, detail, tok)
          case _ =>
            throw new IllegalArgumentException(
              s"line ${i + 2} of the skip file [$path] does not have five tab-separated fields")
        }
      }
      new SkipLog(Some(path), rows)
    }

  /** The `tokenizer` column for a path: `<ext>=<identity>`, or `unknown`. */
  def tokenizerFor(identity: TokenizerIdentity, blobPath: String): String = {
    val name = Mapping.basename(blobPath)
    val dot  = name.lastIndexOf('.')
    val ext  = if (dot >= 0) name.substring(dot + 1).toLowerCase else ""
    identity.get(ext).map(v => s"$ext=$v").getOrElse("unknown")
  }
}
