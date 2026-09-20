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

  /** Row counts of the four maps, for the mask-widening report. */
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

  /** The first `blob_map` path of a TOKENIZED row (orig_blob <> new_blob) that
    * `newMask` no longer selects, or None when every one still matches.
    *
    * Restricted to tokenized rows on purpose, and that restriction is the whole
    * correctness argument. `blob_map` also holds IDENTITY rows (orig == new) for
    * every blob the walker merely passed through — `README.md`, `CMakeLists.txt`,
    * a `.pdf` — written by Walker's `unchangedBlobs`. Those paths match no mask
    * that ever existed, so a check over all rows refuses every project on this
    * machine (measured: 198 of 198). A tokenized row, by contrast, exists only
    * because the old mask selected its path, so "does the new mask still select
    * it?" is exactly the superset question, asked of the data rather than of two
    * regexes.
    *
    * Matched against the BASENAME, because that is what Walker matches
    * (`fileMask.findFirstIn(tw.getNameString)`). Anchoring makes the two
    * equivalent for every mask this pipeline has used, but replicating the
    * walker is the point: the check must answer what the walker will do, not
    * what a reader assumes it does.
    *
    * Streams the result set rather than materialising it: torvalds__linux holds
    * 2.7 M tokenized rows. */
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

  /** Up to `size` (path, new_blob) pairs from the TOKENIZED rows, spread across
    * the table rather than taken from its head, so the sample is not confined to
    * whatever the first commit happened to touch. Deterministic: the same DB
    * gives the same sample, so a refusal is reproducible. */
  def sampleTokenizedNewBlobs(size: Int): Vector[(String, String)] = {
    require(size > 0, "sample size must be positive")
    val total = rowCounts.blobTokenized
    if (total == 0L) return Vector.empty
    val stride = math.max(1L, total / size)
    val st = conn.prepareStatement(
      s"""SELECT path, new_blob FROM (
         |  SELECT path, new_blob, ROW_NUMBER() OVER (ORDER BY orig_blob, path) AS rn
         |  FROM blob_map WHERE ${Mapping.TokenizedPredicate}
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

  /** Discard everything a wider mask invalidates and record the new mask.
    *
    * Four deletions, in one transaction, each for its own reason:
    *
    *  - `commit_map`: a commit names a tree, and every tree gains entries, so
    *    every rewritten commit id changes.
    *  - `ref_map`: its rows name commits. The FK cascades from `commit_map`, but
    *    deleting it explicitly means this does not depend on a per-connection
    *    pragma being on.
    *  - `tree_map`: trees gain entries.
    *  - `blob_map` IDENTITY rows only. This is the load-bearing one. An identity
    *    row says "this path was not selected, so its bytes pass through". Under a
    *    wider mask some of those paths ARE selected now, and `getBlob` would
    *    serve the identity row as a cache hit — putting RAW SOURCE into the
    *    tokenized repository, invisibly, for exactly the files this widening
    *    exists to tokenize. Measured on this corpus: 456,363 such rows across 88
    *    of 198 projects. Identity rows on still-unselected paths are never read
    *    (`getBlob` is only called inside the mask branch), so dropping all of
    *    them is free and re-inserting them is a walk, not a tokenize.
    *
    * What survives is every row where orig_blob <> new_blob: a real
    * tokenization, keyed by (blob, path), so same content and same extension and
    * therefore same language and same tokens. That is 94.4% of the work.
    *
    * Returns the counts before and after, for the caller to report. */
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

  /** Run `body` inside a transaction; commit on success, rollback on throw. */
  def inTx[A](body: => A): A = {
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

  override def close(): Unit = {
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

  /** `--mask-widened` was passed but the new mask does not select something the
    * old one did, so it is not a widening. Distinct from MetaMismatchException
    * because the cause is different — the flag was given and the data refused it,
    * rather than the flag being absent — but Main maps both to exit 3, which
    * already means "this memo does not match this run". */
  final class MaskNarrowedException(message: String) extends RuntimeException(message)

  /** `--mask-widened` was passed and the map is reusable, but the `new_blob` ids
    * it holds do not resolve in dst, so reusing it would dangle every reference. */
  final class DanglingNewBlobException(message: String) extends RuntimeException(message)

  /** What distinguishes a real tokenization from a pass-through. A tokenized row
    * exists only because the mask selected its path; an identity row exists
    * because it did not. Every decision in the mask-widening path turns on this
    * one predicate, so it is written once. */
  private[blobexec] val TokenizedPredicate = "orig_blob <> new_blob"

  /** meta keys recording that a widening happened, so the provenance of a reused
    * blob_map is in the database rather than only in a log. */
  private[blobexec] val MaskWidenedFromKey = "mask_widened_from"
  private[blobexec] val MaskWidenedAtKey   = "mask_widened_at"

  /** How many `new_blob` ids to probe in dst. Every retained row's id must exist
    * there, but reading 2.7 M of them is minutes of object lookup before the walk
    * starts; a spread sample catches the failure this guards against, which is a
    * dst that was wiped or never written, not one blob gone missing. */
  private[blobexec] val ReachabilitySampleSize = 256

  final case class RowCounts(blobTokenized: Long, blobIdentity: Long, tree: Long,
                             commit: Long, ref: Long)

  final case class WideningResult(before: RowCounts, after: RowCounts)

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

  /** The opt-in behind `--mask-widened`, and the two things it has to be given.
    *
    * `newBlobResolves` answers "does this object id exist in dst?". It is not
    * optional and has no default, because `blob_map`'s `new_blob` ids live in
    * dst and nowhere else: reusing the map against a dst that was wiped, or was
    * never written, leaves every retained reference dangling and the failure
    * surfaces as a corrupt repository several steps later. A default would let a
    * caller skip the check by forgetting about it.
    *
    * `report` receives the human-readable account of what was kept and what was
    * dropped. Injected so the decision is testable without capturing stdout. */
  final case class MaskWidening(newBlobResolves: String => Boolean,
                                report: String => Unit,
                                nowEpochSeconds: () => Long = () => System.currentTimeMillis() / 1000L,
                                sampleSize: Int = ReachabilitySampleSize)

  /** Open/create the SQLite DB at `path` (schema created if absent) and validate
    * `command`/`mask` against `meta`: mismatch throws MetaMismatchException,
    * absence records them.
    *
    * `maskWidening` is the ONLY way past the mask refusal, and it is deliberately
    * narrow. `command` is still checked exactly as before: a different tokenizer
    * means different tokens for the same bytes, and no amount of mask reasoning
    * covers that. */
  def open(path: Path, command: String, mask: String, warm: Option[Path] = None,
           maskWidening: Option[MaskWidening] = None): Mapping = {
    val url = s"jdbc:sqlite:${path.toAbsolutePath}"
    val conn = DriverManager.getConnection(url)
    enableForeignKeys(conn)
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
    m
  }

  /** The `--mask-widened` path. Four outcomes, in this order, and the order is
    * the safety property: nothing is written until both checks have passed.
    *
    *  1. No stored mask (a fresh DB): record it. Nothing to reuse, nothing to
    *     check — the flag is a no-op and says so.
    *  2. Stored mask equals the new one: an ordinary resume. The flag is a no-op.
    *  3. A tokenized row's path is no longer selected: this is a NARROWING, not a
    *     widening. Refuse, naming the path, having written nothing.
    *  4. A sampled `new_blob` does not resolve in dst: refuse, naming it, having
    *     written nothing.
    *
    * Only after 3 and 4 both pass does anything change. */
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
