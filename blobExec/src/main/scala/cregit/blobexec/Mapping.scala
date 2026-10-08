package cregit.blobexec

import java.nio.file.Path
import java.sql.{Connection, DriverManager, PreparedStatement}
import scala.util.matching.Regex

/** SQLite-backed persistent mapping between original and rewritten object ids.
  * One JDBC connection confined to the walker thread (only the external command
  * runs in parallel). Each row is stamped `processed_at` (epoch seconds) so a run's
  * rows are queryable; non-matching blobs get identity rows (orig == new). */
final class Mapping private (conn: Connection, warm: Option[Connection]) extends AutoCloseable {

  // INSERT OR IGNORE so processed_at reflects first-write time, not last.
  // The data values are deterministic for a given (key, command, mask), so
  // silently keeping the original row on conflict is safe.
  private val selBlob   = conn.prepareStatement("SELECT new_blob FROM blob_map WHERE orig_blob = ? AND path = ?")
  private val insBlob   = conn.prepareStatement("INSERT OR IGNORE INTO blob_map(orig_blob, path, new_blob) VALUES (?, ?, ?)")
  private val selCommit = conn.prepareStatement("SELECT new_commit FROM commit_map WHERE orig_commit = ?")
  private val insCommit = conn.prepareStatement("INSERT OR IGNORE INTO commit_map(orig_commit, new_commit) VALUES (?, ?)")
  private val selTree   = conn.prepareStatement("SELECT new_tree FROM tree_map WHERE orig_tree = ?")
  private val insTree   = conn.prepareStatement("INSERT OR IGNORE INTO tree_map(orig_tree, new_tree) VALUES (?, ?)")

  // Read-only fallback on the optional warm DB (frozen prior memo): consulted only
  // for content-addressed blob_map/tree_map misses, never commit_map, never written.
  // A hit is the id a fresh tokenize would produce, so it only skips redone work.
  private val warmSelBlob = warm.map(_.prepareStatement("SELECT new_blob FROM blob_map WHERE orig_blob = ? AND path = ?"))
  private val warmSelTree = warm.map(_.prepareStatement("SELECT new_tree FROM tree_map WHERE orig_tree = ?"))
  // Refs: INSERT OR REPLACE because a ref at the same name can be retargeted
  // (branch moved, tag re-issued). The row should reflect the current dst
  // state, not the first-seen state. processed_at is updated accordingly.
  private val selRef    = conn.prepareStatement("SELECT kind, orig_target, new_target, orig_commit, new_commit FROM ref_map WHERE ref_name = ?")
  private val insRef    = conn.prepareStatement("INSERT OR REPLACE INTO ref_map(ref_name, kind, orig_target, new_target, orig_commit, new_commit, processed_at) VALUES (?, ?, ?, ?, ?, ?, CAST(strftime('%s','now') AS INTEGER))")
  private val delRef    = conn.prepareStatement("DELETE FROM ref_map WHERE ref_name = ?")
  private val selMeta   = conn.prepareStatement("SELECT value FROM meta WHERE key = ?")
  private val insMeta   = conn.prepareStatement("INSERT OR REPLACE INTO meta(key, value) VALUES (?, ?)")

  def getBlob(origBlob: String, path: String): Option[String] =
    Mapping.selectString(selBlob, origBlob, path)
      .orElse(warmSelBlob.flatMap(st => Mapping.selectString(st, origBlob, path)))

  def putBlob(origBlob: String, path: String, newBlob: String): Unit =
    Mapping.execute(insBlob, origBlob, path, newBlob)

  def getCommit(origCommit: String): Option[String] =
    Mapping.selectString(selCommit, origCommit)

  def putCommit(origCommit: String, newCommit: String): Unit =
    Mapping.execute(insCommit, origCommit, newCommit)

  def getTree(origTree: String): Option[String] =
    Mapping.selectString(selTree, origTree)
      .orElse(warmSelTree.flatMap(st => Mapping.selectString(st, origTree)))

  def putTree(origTree: String, newTree: String): Unit =
    Mapping.execute(insTree, origTree, newTree)

  def getRef(refName: String): Option[Mapping.RefRow] = {
    selRef.setString(1, refName)
    val rs = selRef.executeQuery()
    try if (rs.next()) Some(Mapping.RefRow(
        refName    = refName,
        kind       = rs.getString(1),
        origTarget = rs.getString(2),
        newTarget  = rs.getString(3),
        origCommit = rs.getString(4),
        newCommit  = rs.getString(5)
      )) else None
    finally rs.close()
  }

  def putRef(row: Mapping.RefRow): Unit = {
    insRef.setString(1, row.refName)
    insRef.setString(2, row.kind)
    insRef.setString(3, row.origTarget)
    insRef.setString(4, row.newTarget)
    insRef.setString(5, row.origCommit)
    insRef.setString(6, row.newCommit)
    insRef.executeUpdate()
    ()
  }

  def deleteRef(refName: String): Unit = {
    delRef.setString(1, refName)
    delRef.executeUpdate()
    ()
  }

  /** All ref_names currently in ref_map. */
  def allRefNames: Vector[String] = {
    val st = conn.createStatement()
    try {
      val rs = st.executeQuery("SELECT ref_name FROM ref_map")
      try Iterator
        .continually(if (rs.next()) Some(rs.getString(1)) else None)
        .takeWhile(_.isDefined)
        .flatten
        .toVector
      finally rs.close()
    } finally st.close()
  }

  def getMeta(key: String): Option[String] =
    Mapping.selectString(selMeta, key)

  def setMeta(key: String, value: String): Unit =
    Mapping.execute(insMeta, key, value)

  def storedTokenizerId(extension: String): Option[String] =
    getMeta(Mapping.TokenizerIdKeyPrefix + extension)

  def setTokenizerId(extension: String, value: String): Unit =
    setMeta(Mapping.TokenizerIdKeyPrefix + extension, value)

  /** Extensions whose recorded identity differs from `identity`. Nothing recorded
    * is not a change: older maps carry no identity at all. */
  def tokenizerIdentityChanges(identity: TokenizerIdentity): Vector[Mapping.IdentityChange] =
    identity.byExtension.toVector.sorted.flatMap { case (ext, requested) =>
      storedTokenizerId(ext) match {
        case Some(stored) if stored != requested =>
          Some(Mapping.IdentityChange(ext, stored, requested))
        case _ => None
      }
    }

  /** The changed extensions that also have cached tokenizations. Refusing one
    * with no rows would deadlock: `--retokenize` of it fails too, as a no-op. */
  def cachePoisoningIdentityChanges(identity: TokenizerIdentity): Vector[Mapping.IdentityChange] =
    tokenizerIdentityChanges(identity)
      .filter(c => countTokenizedRowsForExtensions(Set(c.extension)) > 0L)

  /** Zero makes a `--retokenize` request a no-op, and a no-op has to fail. */
  def countTokenizedRowsForExtensions(extensions: Set[String]): Long = {
    val st = conn.createStatement()
    try {
      val rs = st.executeQuery(
        s"SELECT COUNT(*) FROM blob_map WHERE ${Mapping.TokenizedPredicate} " +
          s"AND ${Mapping.extensionPredicate(extensions)}")
      try { rs.next(); rs.getLong(1) } finally rs.close()
    } finally st.close()
  }

  /** Distinct, because the memo is keyed on content: one blob under two paths is
    * one memo entry. */
  def tokenizedOrigBlobsForExtensions(extensions: Set[String]): Vector[String] = {
    val st = conn.createStatement()
    try {
      val rs = st.executeQuery(
        s"SELECT DISTINCT orig_blob FROM blob_map WHERE ${Mapping.TokenizedPredicate} " +
          s"AND ${Mapping.extensionPredicate(extensions)}")
      try Iterator
        .continually(if (rs.next()) Some(rs.getString(1)) else None)
        .takeWhile(_.isDefined)
        .flatten
        .toVector
      finally rs.close()
    } finally st.close()
  }

  /** All original-commit SHAs already recorded. Used to mark walk frontier. */
  def allCommitOrigShas: Vector[String] = {
    val st = conn.createStatement()
    try {
      val rs = st.executeQuery("SELECT orig_commit FROM commit_map")
      try Iterator
        .continually(if (rs.next()) Some(rs.getString(1)) else None)
        .takeWhile(_.isDefined)
        .flatten
        .toVector
      finally rs.close()
    } finally st.close()
  }

  private def countWhere(predicate: String): Long = {
    val st = conn.createStatement()
    try {
      val rs = st.executeQuery(s"SELECT COUNT(*) FROM blob_map WHERE $predicate")
      try { rs.next(); rs.getLong(1) } finally rs.close()
    } finally st.close()
  }

  def rowCounts: Mapping.RowCounts = {
    def one(sql: String): Long = {
      val st = conn.createStatement()
      try {
        val rs = st.executeQuery(sql)
        try { rs.next(); rs.getLong(1) } finally rs.close()
      } finally st.close()
    }
    Mapping.RowCounts(
      blobTokenized = one(s"SELECT COUNT(*) FROM blob_map WHERE ${Mapping.TokenizedPredicate}"),
      blobIdentity  = one(s"SELECT COUNT(*) FROM blob_map WHERE NOT (${Mapping.TokenizedPredicate})"),
      tree          = one("SELECT COUNT(*) FROM tree_map"),
      commit        = one("SELECT COUNT(*) FROM commit_map"),
      ref           = one("SELECT COUNT(*) FROM ref_map")
    )
  }

  /** The first tokenized-row path `newMask` no longer selects. Identity rows are
    * skipped: their paths match no mask. Matched on the basename, as Walker does. */
  def firstPathNarrowedBy(newMask: Regex): Option[String] = {
    val st = conn.createStatement()
    try {
      val rs = st.executeQuery(s"SELECT path FROM blob_map WHERE ${Mapping.TokenizedPredicate}")
      try {
        var offender: Option[String] = None
        while (offender.isEmpty && rs.next()) {
          val path = rs.getString(1)
          if (newMask.findFirstIn(Mapping.basename(path)).isEmpty) offender = Some(path)
        }
        offender
      } finally rs.close()
    } finally st.close()
  }

  /** Up to `size` tokenized (path, new_blob) pairs, spread across the table and
    * deterministic, so a refusal is reproducible. */
  def sampleTokenizedNewBlobs(size: Int, excludeExtensions: Set[String] = Set.empty): Vector[(String, String)] = {
    require(size > 0, "sample size must be positive")
    // Skip rows --retokenize is about to delete: their new_blob may already be
    // gone from dst.
    val keep =
      if (excludeExtensions.isEmpty) Mapping.TokenizedPredicate
      else s"${Mapping.TokenizedPredicate} AND NOT ${Mapping.extensionPredicate(excludeExtensions)}"
    val total = countWhere(keep)
    if (total == 0L) return Vector.empty
    val stride = math.max(1L, total / size)
    val st = conn.prepareStatement(
      s"""SELECT path, new_blob FROM (
         |  SELECT path, new_blob, ROW_NUMBER() OVER (ORDER BY orig_blob, path) AS rn
         |  FROM blob_map WHERE $keep
         |) WHERE (rn - 1) % ? = 0 LIMIT ?""".stripMargin)
    try {
      st.setLong(1, stride)
      st.setInt(2, size)
      val rs = st.executeQuery()
      try Iterator
        .continually(if (rs.next()) Some((rs.getString(1), rs.getString(2))) else None)
        .takeWhile(_.isDefined)
        .flatten
        .toVector
      finally rs.close()
    } finally st.close()
  }

  /** Drop commit, ref and tree maps and the identity blob rows, which a wider mask
    * would otherwise serve as cache hits of raw source, and record the new mask.
    * ref_map is deleted explicitly, not left to the per-connection FK pragma. */
  def applyMaskWidening(oldMask: String, newMask: String, atEpochSeconds: Long): Mapping.WideningResult = {
    val before = rowCounts
    inTx {
      val st = conn.createStatement()
      try {
        st.executeUpdate("DELETE FROM commit_map")
        st.executeUpdate("DELETE FROM ref_map")
        st.executeUpdate("DELETE FROM tree_map")
        st.executeUpdate(s"DELETE FROM blob_map WHERE NOT (${Mapping.TokenizedPredicate})")
      } finally st.close()
      setMeta("mask", newMask)
      setMeta(Mapping.MaskWidenedFromKey, oldMask)
      setMeta(Mapping.MaskWidenedAtKey, atEpochSeconds.toString)
    }
    Mapping.WideningResult(before, rowCounts)
  }

  /** Drop the tokenized blob rows for `extensions` plus tree, commit and ref maps,
    * and record the new identity, in one transaction: a partial invalidation is
    * undetectable. Identity rows stay, because the mask has not changed. */
  def applyRetokenize(
      extensions: Set[String],
      identity: TokenizerIdentity,
      memo: TokenizerMemo.PurgeReport,
      atEpochSeconds: Long
  ): Mapping.RetokenizeResult = {
    val before = rowCounts
    val invalidated = countTokenizedRowsForExtensions(extensions)
    val previousIds = extensions.toVector.sorted.flatMap(e => storedTokenizerId(e).map(v => s"$e=$v"))
    inTx {
      val st = conn.createStatement()
      try {
        st.executeUpdate("DELETE FROM commit_map")
        st.executeUpdate("DELETE FROM ref_map")
        st.executeUpdate("DELETE FROM tree_map")
        st.executeUpdate(
          s"DELETE FROM blob_map WHERE ${Mapping.TokenizedPredicate} " +
            s"AND ${Mapping.extensionPredicate(extensions)}")
      } finally st.close()
      // Record every extension, not only the invalidated ones: an unrecorded one
      // is a hole the next run cannot detect a change through.
      identity.byExtension.foreach { case (ext, value) =>
        if (extensions.contains(ext) || storedTokenizerId(ext).isEmpty) setTokenizerId(ext, value)
      }
      setMeta(Mapping.RetokenizedAtKey, atEpochSeconds.toString)
      setMeta(Mapping.RetokenizedExtensionsKey, extensions.toVector.sorted.mkString(","))
      setMeta(Mapping.RetokenizedFromKey, previousIds.mkString(","))
    }
    Mapping.RetokenizeResult(before, rowCounts, extensions, invalidated, memo)
  }

  /** Run `body` inside a transaction; commit on success, rollback on throw.
    * An open group (see [[inGroupedTx]]) is committed first. */
  def inTx[A](body: => A): A = {
    commitGroup()
    conn.setAutoCommit(false)
    try {
      val result = body
      conn.commit()
      result
    } catch {
      case t: Throwable =>
        try conn.rollback() catch { case _: Throwable => () }
        throw t
    } finally {
      conn.setAutoCommit(true)
    }
  }

  /** A PRAGMA's current value on this connection, for tests. */
  private[blobexec] def pragmaValue(name: String): Long = {
    val st = conn.createStatement()
    try { val rs = st.executeQuery(s"PRAGMA $name"); rs.next(); rs.getLong(1) } finally st.close()
  }

  // -- grouped transactions ---------------------------------------------------
  // A commit's rows are one body; up to `maxBodies` bodies share one transaction.
  // Each SQLite transaction rewrites the pages it touched into the WAL, and the
  // walk touches the same index pages for every commit, so per-commit
  // transactions cost more than the rows. A group is all-or-nothing, like the
  // one-commit transaction it replaces: a crash loses whole bodies, never part of
  // one, so a commit row is still never durable without its trees and blobs, and
  // the lost commits are re-walked on resume (their tokens come from the memo).

  private var groupOpen       = false
  private var groupBodies     = 0
  private var groupStartNanos = 0L

  /** As [[inTx]], but `body` joins the open group, which is committed once it
    * holds `maxBodies` bodies or is `maxNanos` old. A throw in `body` rolls back
    * the whole group. `maxBodies` <= 1 is plain [[inTx]]. Not thread-safe: the
    * caller serializes access, as it does for every other method here. */
  def inGroupedTx[A](maxBodies: Int, maxNanos: Long)(body: => A): A =
    if (maxBodies <= 1) inTx(body)
    else {
      if (!groupOpen) {
        conn.setAutoCommit(false)
        groupOpen = true
        groupBodies = 0
        groupStartNanos = System.nanoTime()
      }
      val result =
        try body
        catch { case t: Throwable => abandonGroup(); throw t }
      groupBodies += 1
      if (groupBodies >= maxBodies || System.nanoTime() - groupStartNanos >= maxNanos) commitGroup()
      result
    }

  /** Commit the open group, if any. The walk calls it when it stops, by abort too. */
  def commitGroup(): Unit =
    if (groupOpen) {
      try conn.commit()
      catch { case t: Throwable => abandonGroup(); throw t }
      groupOpen = false
      groupBodies = 0
      conn.setAutoCommit(true)
    }

  private def abandonGroup(): Unit =
    if (groupOpen) {
      groupOpen = false
      groupBodies = 0
      try conn.rollback() catch { case _: Throwable => () }
      try conn.setAutoCommit(true) catch { case _: Throwable => () }
    }

  override def close(): Unit = {
    // Every body in the group finished (a failed one rolled the group back).
    commitGroup()
    (List(selBlob, insBlob, selCommit, insCommit, selTree, insTree,
          selRef, insRef, delRef, selMeta, insMeta) ++ warmSelBlob.toList ++ warmSelTree.toList)
      .foreach(s => try s.close() catch { case _: Throwable => () })
    conn.close()
    warm.foreach(w => try w.close() catch { case _: Throwable => () })
  }
}

object Mapping {

  /** Mismatch between the recorded command/mask and the values passed in. */
  final class MetaMismatchException(message: String) extends RuntimeException(message)

  /** `--mask-widened` was passed but the new mask is not a widening. */
  final class MaskNarrowedException(message: String) extends RuntimeException(message)

  /** Reusing the map would dangle: its `new_blob` ids do not resolve in dst. */
  final class DanglingNewBlobException(message: String) extends RuntimeException(message)

  /** A recorded tokenizer identity changed and `--retokenize` did not name it. */
  final class TokenizerChangedException(message: String) extends RuntimeException(message)

  /** `--retokenize` would invalidate nothing; exiting 0 would look like success. */
  final class NothingInvalidatedException(message: String) extends RuntimeException(message)

  final case class IdentityChange(extension: String, stored: String, requested: String)

  private[blobexec] val TokenizedPredicate = "orig_blob <> new_blob"

  private[blobexec] val MaskWidenedFromKey = "mask_widened_from"
  private[blobexec] val MaskWidenedAtKey   = "mask_widened_at"

  /** One meta row per extension, so `--retokenize` can invalidate one language. */
  private[blobexec] val TokenizerIdKeyPrefix = "tokenizer_id."

  private[blobexec] val RetokenizedAtKey         = "retokenized_at"
  private[blobexec] val RetokenizedExtensionsKey = "retokenized_extensions"
  private[blobexec] val RetokenizedFromKey       = "retokenized_from"

  /** SQL for "path has one of these extensions", case-insensitive like the mask.
    * The dot stops `.c` from matching `.cc`; TokenizerIdentity's alphabet leaves
    * nothing to escape. */
  private[blobexec] def extensionPredicate(extensions: Set[String]): String = {
    require(extensions.nonEmpty, "extensionPredicate needs at least one extension")
    extensions.foreach(e => require(
      e.matches(TokenizerIdentity.ExtensionPattern),
      s"not a usable extension: [$e] (must match ${TokenizerIdentity.ExtensionPattern})"))
    extensions.toVector.sorted.map(e => s"lower(path) LIKE '%.$e'").mkString("(", " OR ", ")")
  }

  /** A sample, not all 2.7 M ids: it guards against a wiped dst, not one lost blob. */
  private[blobexec] val ReachabilitySampleSize = 256

  final case class RowCounts(blobTokenized: Long, blobIdentity: Long, tree: Long,
                             commit: Long, ref: Long)

  final case class WideningResult(before: RowCounts, after: RowCounts)

  final case class RetokenizeResult(
      before: RowCounts,
      after: RowCounts,
      extensions: Set[String],
      blobsInvalidated: Long,
      memo: TokenizerMemo.PurgeReport)

  /** Opt-in for `--retokenize`. `purgeMemo` is mandatory: the memo is keyed on
    * contents alone (tokenBySha.pl), so dropping only blob_map rows would serve
    * the stale tokens from one layer down. */
  final case class Retokenize(
      extensions: Set[String],
      newBlobResolves: String => Boolean,
      purgeMemo: Vector[String] => TokenizerMemo.PurgeReport,
      report: String => Unit,
      memoDirIsTokenizerMemo: Boolean = false,
      nowEpochSeconds: () => Long = () => System.currentTimeMillis() / 1000L,
      sampleSize: Int = ReachabilitySampleSize)

  /** The part of `path` Walker matches the mask against. */
  private[blobexec] def basename(path: String): String =
    path.substring(path.lastIndexOf('/') + 1)

  // processed_at stored INTEGER (epoch seconds) for numeric filters. FK
  // ref_map.orig_commit -> commit_map ON DELETE CASCADE cleans up refs with their
  // commit; blob_map/tree_map have no FK (a blob/tree spans many commits).
  private val Schema = Seq(
    """CREATE TABLE IF NOT EXISTS commit_map (
      |  orig_commit  TEXT PRIMARY KEY,
      |  new_commit   TEXT    NOT NULL,
      |  processed_at INTEGER NOT NULL DEFAULT (CAST(strftime('%s','now') AS INTEGER))
      |)""".stripMargin,
    """CREATE TABLE IF NOT EXISTS blob_map (
      |  orig_blob    TEXT    NOT NULL,
      |  path         TEXT    NOT NULL,
      |  new_blob     TEXT    NOT NULL,
      |  processed_at INTEGER NOT NULL DEFAULT (CAST(strftime('%s','now') AS INTEGER)),
      |  PRIMARY KEY (orig_blob, path)
      |)""".stripMargin,
    """CREATE TABLE IF NOT EXISTS tree_map (
      |  orig_tree    TEXT PRIMARY KEY,
      |  new_tree     TEXT    NOT NULL,
      |  processed_at INTEGER NOT NULL DEFAULT (CAST(strftime('%s','now') AS INTEGER))
      |)""".stripMargin,
    """CREATE TABLE IF NOT EXISTS ref_map (
      |  ref_name     TEXT PRIMARY KEY,
      |  kind         TEXT    NOT NULL CHECK (kind IN ('head','annotated_tag','lightweight_tag')),
      |  orig_target  TEXT    NOT NULL,
      |  new_target   TEXT    NOT NULL,
      |  orig_commit  TEXT    NOT NULL REFERENCES commit_map(orig_commit) ON DELETE CASCADE,
      |  new_commit   TEXT    NOT NULL,
      |  processed_at INTEGER NOT NULL DEFAULT (CAST(strftime('%s','now') AS INTEGER))
      |)""".stripMargin,
    """CREATE TABLE IF NOT EXISTS meta (
      |  key   TEXT PRIMARY KEY,
      |  value TEXT NOT NULL
      |)""".stripMargin
  )

  // A ref_map row. kind in {head, annotated_tag, lightweight_tag}: for annotated
  // tags the target fields hold the tag-object SHAs (commit fields peeled);
  // otherwise target == commit.
  final case class RefRow(
      refName: String,
      kind: String,
      origTarget: String,
      newTarget: String,
      origCommit: String,
      newCommit: String
  )

  /** Configure a fresh connection: FK enforcement (per-connection), plus WAL +
    * synchronous=NORMAL to drop the per-commit fsync floor (~1.46M txns) and
    * busy_timeout for concurrent readers. Crash-safe for content-addressed data --
    * a lost WAL tail is re-derived on resume from the memo. */
  private def enableForeignKeys(conn: Connection): Unit = {
    val st = conn.createStatement()
    try {
      st.execute("PRAGMA foreign_keys = ON")
      st.execute("PRAGMA journal_mode = WAL")
      st.execute("PRAGMA synchronous = NORMAL")
      st.execute("PRAGMA busy_timeout = 30000")
    } finally st.close()
  }

  /** Read-path sizing for the walk's DB. Both change speed only, never results.
    *   cacheMiB  SQLite's own page cache, private anonymous memory, filled only
    *             as pages are read: the DB's size at most (default 2 MB).
    *   mmapMiB   map up to this much of the file. Lookups then read the kernel's
    *             page cache in place instead of copying each page out with a
    *             syscall. Those are file pages: shared and reclaimable, and the
    *             same pages that reads cache anyway. 0 turns it off. */
  final case class Tuning(cacheMiB: Int = DefaultCacheMiB, mmapMiB: Int = DefaultMmapMiB) {
    require(cacheMiB >= 0 && mmapMiB >= 0, s"SQLite sizes must be >= 0 (cache=$cacheMiB MiB, mmap=$mmapMiB MiB)")
  }
  val DefaultCacheMiB = 256
  val DefaultMmapMiB  = 4096
  /** SQLite's defaults: what every run used before. */
  val UntunedSqlite: Tuning = Tuning(cacheMiB = 0, mmapMiB = 0)

  private def applyTuning(conn: Connection, t: Tuning): Unit = {
    val st = conn.createStatement()
    try {
      // Negative cache_size is KiB; 0 keeps the default.
      if (t.cacheMiB > 0) st.execute(s"PRAGMA cache_size = -${t.cacheMiB.toLong * 1024L}")
      if (t.mmapMiB > 0) st.execute(s"PRAGMA mmap_size = ${t.mmapMiB.toLong * 1024L * 1024L}")
    } finally st.close()
  }

  /** Opt-in for `--mask-widened`. `newBlobResolves` has no default: `new_blob` ids
    * live only in dst, and a wiped dst would dangle every reused row. */
  final case class MaskWidening(newBlobResolves: String => Boolean,
                                report: String => Unit,
                                nowEpochSeconds: () => Long = () => System.currentTimeMillis() / 1000L,
                                sampleSize: Int = ReachabilitySampleSize)

  /** Open/create the DB at `path` and check `command`/`mask` against `meta`.
    * `maskWidening` relaxes only the mask check, never the command check. */
  def open(path: Path, command: String, mask: String, warm: Option[Path] = None,
           maskWidening: Option[MaskWidening] = None,
           tokenizerIdentity: TokenizerIdentity = TokenizerIdentity.empty,
           retokenize: Option[Retokenize] = None,
           tuning: Tuning = Tuning()): Mapping = {
    // Refused, not composed: each invalidation is verified against a state the
    // other would change. Run one, then the other as a resume.
    require(!(maskWidening.isDefined && retokenize.isDefined),
      "--mask-widened and --retokenize cannot be used in the same run: each verifies its own " +
        "precondition against the rows, and together each would be verifying against a state the " +
        "other is about to change. Run the widening first, then resume with --retokenize.")
    retokenize.foreach { r =>
      require(tokenizerIdentity.nonEmpty,
        "--retokenize needs a tokenizer identity to record. Without one the entries would be " +
          "invalidated and nothing would be written to detect the next change with, so the very " +
          "next run would reuse the new tokenizations without ever knowing which tokenizer made " +
          "them.")
      val unknown = r.extensions -- tokenizerIdentity.extensions
      require(unknown.isEmpty,
        s"--retokenize names extension(s) the tokenizer identity does not cover: " +
          s"${unknown.toVector.sorted.mkString(", ")}. There is no identity to record for them, so " +
          "the invalidation could not be accounted for. Known: " +
          s"${tokenizerIdentity.extensions.toVector.sorted.mkString(", ")}.")
    }

    val url = s"jdbc:sqlite:${path.toAbsolutePath}"
    val conn = DriverManager.getConnection(url)
    enableForeignKeys(conn)
    applyTuning(conn, tuning)
    val st = conn.createStatement()
    try Schema.foreach(st.execute) finally st.close()

    val warmConn = warm.map(p => openWarm(p, command, mask))

    val m = new Mapping(conn, warmConn)
    // command first, and unconditionally: it gates the widening too.
    checkOrSetMeta(m, "command", command)
    maskWidening match {
      case Some(w) => widenOrCheckMask(m, mask, w)
      case None    => checkOrSetMeta(m, "mask", mask)
    }
    // Last: the only check that deletes rows must not run on a refused map.
    retokenize match {
      case Some(r) => retokenizeOrRefuse(m, tokenizerIdentity, r)
      case None    => if (tokenizerIdentity.nonEmpty) checkOrRecordTokenizerIdentity(m, tokenizerIdentity)
    }
    m
  }

  /** Record identities not yet recorded; refuse on a recorded mismatch. Deletes
    * nothing, so a re-run with a changed tokenizer stops instead of reusing. */
  private def checkOrRecordTokenizerIdentity(m: Mapping, identity: TokenizerIdentity): Unit = {
    val changes = m.cachePoisoningIdentityChanges(identity)
    if (changes.nonEmpty) throw new TokenizerChangedException(tokenizerChangedMessage(changes))
    // Also overwrite a superseded value with no rows behind it, or it would
    // refuse the next run for no reason.
    identity.byExtension.foreach { case (ext, value) =>
      if (!m.storedTokenizerId(ext).contains(value)) m.setTokenizerId(ext, value)
    }
  }

  /** Refuse an unnamed change, a no-op, a dangling dst sample, or a purge that
    * found nothing. The purge runs before the transaction: a needless purge costs
    * a re-tokenize, a skipped one serves stale tokens. */
  private def retokenizeOrRefuse(m: Mapping, identity: TokenizerIdentity, r: Retokenize): Unit = {
    val uncovered = m.cachePoisoningIdentityChanges(identity).filterNot(c => r.extensions.contains(c.extension))
    if (uncovered.nonEmpty)
      throw new TokenizerChangedException(
        "--retokenize refused: " + tokenizerChangedMessage(uncovered) +
          s"\n  --retokenize named only [${r.extensions.toVector.sorted.mkString(",")}], so those " +
          "entries would have been invalidated while the ones above stayed stale, and the recorded " +
          "identity would have become a mixture of two tokenizers. Nothing has been changed in the " +
          "blob map. Name every changed extension in one run.")

    val affected = m.countTokenizedRowsForExtensions(r.extensions)
    if (affected == 0L)
      throw new NothingInvalidatedException(
        s"--retokenize=${r.extensions.toVector.sorted.mkString(",")} refused: this blob map holds no " +
          "tokenized row on a path with any of those extensions, so the flag would have invalidated " +
          "NOTHING and the run would have exited 0 having reused every cached tokenization. Check " +
          "the extension spelling (lowercase, no dot, as in tokenize/CregitLanguages.pm) and that " +
          "this is the right project's blob map. Nothing has been changed.")

    val sample = m.sampleTokenizedNewBlobs(r.sampleSize, excludeExtensions = r.extensions)
    sample.find { case (_, newBlob) => !r.newBlobResolves(newBlob) }.foreach {
      case (path, newBlob) =>
        throw new DanglingNewBlobException(
          s"--retokenize refused: blob_map's new_blob $newBlob (for '$path') does not exist in the " +
            "destination repository. That row is one this invalidation KEEPS, and those ids live " +
            "only in dst, so the map is only reusable alongside it. This is a --from-step 2 resume " +
            "that keeps the work directory, never a fresh run (which deletes it first). Nothing has " +
            s"been changed in the blob map. Sampled ${sample.size} retained tokenized rows.")
    }

    val blobs = m.tokenizedOrigBlobsForExtensions(r.extensions)
    val memo = r.purgeMemo(blobs)
    if (memo.examined > 0L && memo.deleted == 0L) {
      if (!r.memoDirIsTokenizerMemo)
        throw new NothingInvalidatedException(
          s"--retokenize refused: the memo held none of the ${memo.examined} affected blob(s) " +
            s"(${memo.render}), and --memo-dir is not the directory in $$BFG_MEMO_DIR, the memo " +
            "tokenBySha.pl reads. The --memo-dir is almost certainly not this project's. That " +
            "matters because the memo is keyed on sha1(contents) with no tokenizer in the key: " +
            "dropping the blob_map rows while the real memo keeps its entries just serves the same " +
            "stale tokens through the other door. Nothing has been changed in the blob map.")
      r.report(
        s"--retokenize: the memo held none of the ${memo.examined} affected blob(s) " +
          s"(${memo.render}). Accepted, because --memo-dir is $$BFG_MEMO_DIR, the memo " +
          "tokenBySha.pl reads: no stale entry is left to serve, and every affected blob will run " +
          "its tokenizer again.")
    }

    val res = m.applyRetokenize(r.extensions, identity, memo, r.nowEpochSeconds())
    r.report(
      s"--retokenize: invalidating the entries of [${res.extensions.toVector.sorted.mkString(",")}] " +
        "because their tokenizer changed.\n" +
        s"  tokenizer identity now: ${identity.render}\n" +
        s"  DROPPED ${res.blobsInvalidated} tokenized blob_map rows on those extensions, and purged " +
        s"their memo entries (${memo.render}). Both layers, because the memo is keyed on " +
        "sha1(contents) alone and would otherwise answer the re-tokenization with the old tokens.\n" +
        s"  KEPT    ${res.after.blobTokenized} tokenized blob_map rows on every other extension, and " +
        s"${res.after.blobIdentity} identity rows. Re-tokenizing those is 88% of the pipeline and the " +
        "defect has nothing to do with them.\n" +
        s"  DROPPED ${res.before.tree} tree_map rows (a tree names its blobs, and a retained tree row " +
        s"short-circuits the re-walk), ${res.before.commit} commit_map rows (commits name trees) and " +
        s"${res.before.ref} ref_map rows (refs name commits).\n" +
        s"  CHECKED ${math.min(r.sampleSize.toLong, res.after.blobTokenized)} of the retained new_blob " +
        "ids resolve in dst.")
  }

  private def tokenizerChangedMessage(changes: Vector[IdentityChange]): String = {
    val exts = changes.map(_.extension).sorted
    val detail = changes.map(c =>
      s"\n    .${c.extension}: recorded ${c.stored}, this run reports ${c.requested}").mkString
    s"the tokenizer for ${exts.map("." + _).mkString(", ")} is not the one that produced the " +
      s"cached tokens in this blob map.$detail" +
      "\n  Reusing those rows would reproduce the OLD tokenizer's output for every cached file, and " +
      "nothing downstream can tell one token stream from another: that is how a 16-day-old " +
      "rustTokenizer binary put a `line:col<TAB>` prefix into 741,869 .rs entries across 45 " +
      "projects with no error anywhere.\n" +
      "  Nothing has been changed. Either restore the tokenizer this map was built with, or " +
      s"invalidate exactly those entries:\n    --retokenize=${exts.mkString(",")}\n" +
      "  That drops the affected blob_map rows and their memo entries, keeps every other " +
      "extension's tokenizations, and requires --memo-dir and a step-2 resume."
  }

  /** No stored mask or an equal one: no-op. Otherwise refuse a narrowing or a
    * dangling dst sample before writing anything. */
  private def widenOrCheckMask(m: Mapping, mask: String, w: MaskWidening): Unit =
    m.getMeta("mask") match {
      case None =>
        m.setMeta("mask", mask)
        w.report("--mask-widened: no mask recorded yet (fresh blob map), so there is nothing " +
          "to reuse and nothing to check. Proceeding as a normal first run.")
      case Some(stored) if stored == mask =>
        w.report(s"--mask-widened: the recorded mask already equals this run's [$mask], so this " +
          "is an ordinary resume and the flag changes nothing.")
      case Some(stored) =>
        val counts = m.rowCounts
        m.firstPathNarrowedBy(mask.r).foreach { offending =>
          throw new MaskNarrowedException(
            s"--mask-widened refused: [$mask] does not select '$offending', which was tokenized " +
              s"under the recorded mask [$stored]. That makes the change a NARROWING, not a " +
              "widening, so the rows this flag would reuse are not all valid. Nothing has been " +
              "changed in the blob map. Verified against the data, one regex test per tokenized " +
              s"row (${counts.blobTokenized} of them), not by comparing the two regexes."
          )
        }
        val sample = m.sampleTokenizedNewBlobs(w.sampleSize)
        sample.find { case (_, newBlob) => !w.newBlobResolves(newBlob) }.foreach {
          case (path, newBlob) =>
            throw new DanglingNewBlobException(
              s"--mask-widened refused: blob_map's new_blob $newBlob (for '$path') does not exist " +
                "in the destination repository. Those ids live only in dst, so the map is only " +
                "reusable alongside it. This is a --from-step 2 resume that KEEPS the work " +
                "directory, never a fresh run (which deletes it first). Nothing has been changed " +
                s"in the blob map. Sampled ${sample.size} of ${counts.blobTokenized} tokenized rows."
            )
        }
        val r = m.applyMaskWidening(stored, mask, w.nowEpochSeconds())
        w.report(
          s"--mask-widened: reusing the blob map across a mask widening.\n" +
            s"  old mask: $stored\n" +
            s"  new mask: $mask\n" +
            s"  KEPT    ${r.after.blobTokenized} tokenized blob_map rows (same blob, same path, so " +
            "same content and same extension, so the same tokens)\n" +
            s"  DROPPED ${r.before.blobIdentity} identity blob_map rows (orig == new). Under the old " +
            "mask these recorded 'not selected, bytes pass through'. Some of those paths ARE " +
            "selected now, and keeping them would serve raw source as a cache hit.\n" +
            s"  DROPPED ${r.before.tree} tree_map rows (trees gain entries) and " +
            s"${r.before.commit} commit_map rows (commits name trees), and " +
            s"${r.before.ref} ref_map rows (refs name commits).\n" +
            s"  CHECKED every one of the ${r.after.blobTokenized} retained paths still matches the new " +
            s"mask, and ${math.min(w.sampleSize.toLong, r.after.blobTokenized)} of their new_blob ids " +
            "resolve in dst."
        )
    }

  /** Open a frozen prior-run DB read-only (query_only + busy_timeout) as a lookup
    * fallback; never written by us, so concurrent shard readers are safe. Rejects a
    * warm DB whose command/mask differ from this run -- its ids would be foreign. */
  private def openWarm(path: Path, command: String, mask: String): Connection = {
    val url = s"jdbc:sqlite:${path.toAbsolutePath}"
    val c = DriverManager.getConnection(url)
    val st = c.createStatement()
    try {
      st.execute("PRAGMA query_only = ON")
      st.execute("PRAGMA busy_timeout = 30000")
    } finally st.close()
    checkWarmMeta(c, "command", command)
    checkWarmMeta(c, "mask",    mask)
    c
  }

  private def checkWarmMeta(c: Connection, key: String, value: String): Unit = {
    val ps = c.prepareStatement("SELECT value FROM meta WHERE key = ?")
    ps.setString(1, key)
    val rs = ps.executeQuery()
    try
      if (rs.next()) {
        val stored = rs.getString(1)
        if (stored != null && stored != value)
          throw new MetaMismatchException(
            s"warm DB meta mismatch on '$key': stored='$stored', requested='$value'. " +
              "Refusing warm fallback from a different command/mask."
          )
      }
    finally { rs.close(); ps.close() }
  }

  /** In-memory variant used by tests. */
  def openInMemory(command: String, mask: String): Mapping = {
    val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
    enableForeignKeys(conn)
    val st = conn.createStatement()
    try Schema.foreach(st.execute) finally st.close()
    val m = new Mapping(conn, None)
    checkOrSetMeta(m, "command", command)
    checkOrSetMeta(m, "mask",    mask)
    m
  }

  private def checkOrSetMeta(m: Mapping, key: String, value: String): Unit =
    m.getMeta(key) match {
      case Some(existing) if existing != value =>
        throw new MetaMismatchException(
          s"meta mismatch on '$key': stored='$existing', requested='$value'. " +
            "Refusing incremental run against a different command/mask."
        )
      case Some(_) => ()
      case None    => m.setMeta(key, value)
    }

  private def selectString(st: PreparedStatement, args: String*): Option[String] = {
    args.zipWithIndex.foreach { case (a, i) => st.setString(i + 1, a) }
    val rs = st.executeQuery()
    try if (rs.next()) Some(rs.getString(1)) else None
    finally rs.close()
  }

  private def execute(st: PreparedStatement, args: String*): Unit = {
    args.zipWithIndex.foreach { case (a, i) => st.setString(i + 1, a) }
    st.executeUpdate()
    ()
  }
}
