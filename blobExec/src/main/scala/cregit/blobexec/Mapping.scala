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

  /** The tokenizer identity recorded for `extension`, if any. */
  def storedTokenizerId(extension: String): Option[String] =
    getMeta(Mapping.TokenizerIdKeyPrefix + extension)

  def setTokenizerId(extension: String, value: String): Unit =
    setMeta(Mapping.TokenizerIdKeyPrefix + extension, value)

  /** Every extension whose recorded tokenizer identity differs from `identity`.
    *
    * An extension with nothing recorded is NOT a change: it is a map written
    * before identities were recorded at all, which describes every blob map on
    * this machine today. Treating that as a change would refuse all 188
    * projects at once and say nothing true. */
  def tokenizerIdentityChanges(identity: TokenizerIdentity): Vector[Mapping.IdentityChange] =
    identity.byExtension.toVector.sorted.flatMap { case (ext, requested) =>
      storedTokenizerId(ext) match {
        case Some(stored) if stored != requested =>
          Some(Mapping.IdentityChange(ext, stored, requested))
        case _ => None
      }
    }

  /** The subset of [[tokenizerIdentityChanges]] that can actually poison this run:
    * the extensions whose tokenizer moved AND which have cached tokenizations to
    * reuse.
    *
    * The filter is not an optimisation, it is what keeps the mechanism usable. The
    * pipeline reports one identity per extension it can parse, for every project,
    * because the identity is a property of the checkout and not of the project. A
    * Java-only project therefore carries a `c` identity it has no rows for — and
    * refusing that project because the C toolchain changed would be a refusal with
    * no remedy: `--retokenize=c` would then fail too, correctly, with "no tokenized
    * row carries that extension". A deadlock where the only way forward is to
    * delete the work.
    *
    * With no rows there is nothing to serve from cache, so the new identity is
    * simply recorded: whatever gets tokenized from now on is tokenized by the
    * tokenizer that is recorded. */
  def cachePoisoningIdentityChanges(identity: TokenizerIdentity): Vector[Mapping.IdentityChange] =
    tokenizerIdentityChanges(identity)
      .filter(c => countTokenizedRowsForExtensions(Set(c.extension)) > 0L)

  /** How many TOKENIZED rows sit on a path with one of these extensions. Zero is
    * what makes a `--retokenize` request a no-op, and a no-op has to fail. */
  def countTokenizedRowsForExtensions(extensions: Set[String]): Long = {
    val st = conn.createStatement()
    try {
      val rs = st.executeQuery(
        s"SELECT COUNT(*) FROM blob_map WHERE ${Mapping.TokenizedPredicate} " +
          s"AND ${Mapping.extensionPredicate(extensions)}")
      try { rs.next(); rs.getLong(1) } finally rs.close()
    } finally st.close()
  }

  /** The distinct ORIGINAL blob ids of the tokenized rows for these extensions.
    * Distinct because the memo is keyed on content, so the same blob reached
    * under two paths is one memo entry, and hashing it twice is wasted reads.
    *
    * Materialised rather than streamed: the caller hands the whole vector to the
    * memo purge, which must complete before anything is deleted from the map.
    * The corpus figure is 741,869 rows for a `.rs` invalidation, which is a
    * vector of 40-character strings — tens of megabytes, not gigabytes. */
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

  /** How many blob_map rows satisfy `predicate`. */
  private def countWhere(predicate: String): Long = {
    val st = conn.createStatement()
    try {
      val rs = st.executeQuery(s"SELECT COUNT(*) FROM blob_map WHERE $predicate")
      try { rs.next(); rs.getLong(1) } finally rs.close()
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
  def sampleTokenizedNewBlobs(size: Int, excludeExtensions: Set[String] = Set.empty): Vector[(String, String)] = {
    require(size > 0, "sample size must be positive")
    // The probe must ask about the rows that will be KEPT. Under --retokenize the
    // rows for the named extensions are about to be deleted, and their new_blob
    // ids may legitimately have been garbage-collected out of dst already; asking
    // about those would refuse a correct invalidation.
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

  /** Discard everything a changed tokenizer invalidates for `extensions`, and
    * record the new identity for them.
    *
    * Four deletions and the identity update, in ONE transaction, because a
    * partially invalidated map is worse than either a stale one or a clean one:
    * it is a state no flag describes and no later run can detect.
    *
    *  - `blob_map` TOKENIZED rows for these extensions. These are the poisoned
    *    entries: same bytes, same path, but produced by the tokenizer that has
    *    since been corrected.
    *  - `tree_map`. A tree names the blobs under it, so a tree containing a
    *    re-tokenized file has a different id; and a retained `tree_map` row
    *    short-circuits the re-walk of that whole subtree (buildTreePlan consults
    *    `getTree` before it looks at anything below), so keeping it would leave
    *    the tree pointing at the old token blob and invalidate nothing.
    *  - `commit_map`, because commits name trees, and `ref_map`, because refs
    *    name commits. ref_map would cascade from commit_map's FK, but only if the
    *    per-connection pragma is on, so it is deleted explicitly.
    *
    * What survives is every tokenized row for every OTHER extension. That is the
    * whole point: re-tokenizing a corpus costs 88% of total pipeline time, and a
    * `.rs`-only defect has no business touching the C work. Trees and commits are
    * rebuilt by a walk, which is the cheap part.
    *
    * Identity pass-through rows (orig == new) are kept. They record "this path was
    * not selected, its bytes pass through", the mask has not changed here, and so
    * the same paths are still not selected. (That is exactly the case
    * `--mask-widened` must handle differently, which is why the two flags refuse
    * to run together.) */
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
      // Every extension the run knows about gets its identity recorded, not just
      // the invalidated ones: an extension with nothing recorded is a hole the
      // next run cannot detect a change through.
      identity.byExtension.foreach { case (ext, value) =>
        if (extensions.contains(ext) || storedTokenizerId(ext).isEmpty) setTokenizerId(ext, value)
      }
      setMeta(Mapping.RetokenizedAtKey, atEpochSeconds.toString)
      setMeta(Mapping.RetokenizedExtensionsKey, extensions.toVector.sorted.mkString(","))
      setMeta(Mapping.RetokenizedFromKey, previousIds.mkString(","))
    }
    Mapping.RetokenizeResult(before, rowCounts, extensions, invalidated, memo)
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

  /** A recorded tokenizer identity does not match this run's, and the operator
    * did not ask for the affected entries to be invalidated. Reusing them would
    * reproduce the tokens of the tokenizer that has since been corrected —
    * silently, because nothing downstream can tell one token stream from
    * another. Same exit status as a meta mismatch (3): the cause is the same
    * class of thing, "this memo does not match this run". */
  final class TokenizerChangedException(message: String) extends RuntimeException(message)

  /** `--retokenize` was passed and would have invalidated nothing.
    *
    * This exception is the whole safety property of the flag. An invalidation
    * that quietly invalidates nothing and exits 0 is indistinguishable, in a log,
    * from one that worked — and the operator then publishes a dataset believing
    * the defect is out of it. Every path that reaches "nothing changed" raises
    * this instead of returning. */
  final class NothingInvalidatedException(message: String) extends RuntimeException(message)

  /** A recorded identity that differs from the one this run reports. */
  final case class IdentityChange(extension: String, stored: String, requested: String)

  /** What distinguishes a real tokenization from a pass-through. A tokenized row
    * exists only because the mask selected its path; an identity row exists
    * because it did not. Every decision in the mask-widening path turns on this
    * one predicate, so it is written once. */
  private[blobexec] val TokenizedPredicate = "orig_blob <> new_blob"

  /** meta keys recording that a widening happened, so the provenance of a reused
    * blob_map is in the database rather than only in a log. */
  private[blobexec] val MaskWidenedFromKey = "mask_widened_from"
  private[blobexec] val MaskWidenedAtKey   = "mask_widened_at"

  /** meta key prefix for the per-extension tokenizer identity: one row per
    * extension, `tokenizer_id.rs`, `tokenizer_id.c`. Per extension rather than
    * one combined value, because `--retokenize` has to be able to invalidate one
    * language without disturbing the recorded state of the others. */
  private[blobexec] val TokenizerIdKeyPrefix = "tokenizer_id."

  /** meta keys recording that an invalidation happened, and what it replaced, so
    * a reused blob map carries its own provenance. */
  private[blobexec] val RetokenizedAtKey         = "retokenized_at"
  private[blobexec] val RetokenizedExtensionsKey = "retokenized_extensions"
  private[blobexec] val RetokenizedFromKey       = "retokenized_from"

  /** "Does this path carry one of these extensions?", as SQL.
    *
    * `lower(path) LIKE '%.rs'` rather than a suffix computed in Scala, so the
    * whole selection stays one statement over a table that holds 2.7 M rows for
    * the largest project. Case-insensitive because the mask is (`(?i)` in
    * CregitLanguages::file_mask) and `.C` and `.H` are real files in this corpus.
    *
    * The dot is part of the pattern, which is what stops `.c` from selecting
    * `.cc` and `.cpp`. There is nothing to escape: [[TokenizerIdentity]] admits
    * only `[a-z0-9+]+`, an alphabet with no quote, no `%` and no `_`. The require
    * is there so that stays true if a future caller builds the set some other
    * way. */
  private[blobexec] def extensionPredicate(extensions: Set[String]): String = {
    require(extensions.nonEmpty, "extensionPredicate needs at least one extension")
    extensions.foreach(e => require(
      e.matches(TokenizerIdentity.ExtensionPattern),
      s"not a usable extension: [$e] (must match ${TokenizerIdentity.ExtensionPattern})"))
    extensions.toVector.sorted.map(e => s"lower(path) LIKE '%.$e'").mkString("(", " OR ", ")")
  }

  /** How many `new_blob` ids to probe in dst. Every retained row's id must exist
    * there, but reading 2.7 M of them is minutes of object lookup before the walk
    * starts; a spread sample catches the failure this guards against, which is a
    * dst that was wiped or never written, not one blob gone missing. */
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

  /** The opt-in behind `--retokenize`, and the four things it has to be given.
    *
    * `extensions` is what makes it surgical. Re-tokenizing a corpus is 88% of
    * total pipeline time, so a defect in one language's tokenizer must invalidate
    * one language's entries.
    *
    * `newBlobResolves` answers "does this object id exist in dst?", for the rows
    * that are KEPT. Not optional and with no default, for the same reason as in
    * [[MaskWidening]]: `blob_map`'s `new_blob` ids live in dst and nowhere else.
    *
    * `purgeMemo` is handed the original blob ids being invalidated and must delete
    * their entries from the content-addressed memo. It is not optional either, and
    * that is the point: the memo is keyed on `sha1(contents)` alone
    * (tokenizeByBlobId/tokenBySha.pl:76), with no tokenizer in the key, so
    * dropping a `blob_map` row on its own just moves the stale answer one layer
    * down. Two layers, one flag, no way to do one without the other.
    *
    * `report` receives the human-readable account of what was invalidated and
    * what was kept. Injected so the decision is testable without capturing
    * stdout. */
  final case class Retokenize(
      extensions: Set[String],
      newBlobResolves: String => Boolean,
      purgeMemo: Vector[String] => TokenizerMemo.PurgeReport,
      report: String => Unit,
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
           maskWidening: Option[MaskWidening] = None,
           tokenizerIdentity: TokenizerIdentity = TokenizerIdentity.empty,
           retokenize: Option[Retokenize] = None): Mapping = {
    // Refused rather than composed. Both invalidations are verified against the
    // rows before they touch anything, and running them together would mean
    // verifying each against a state the other was about to change: a widening
    // decides what to do with identity rows on the assumption the tokenizations
    // are valid, and an invalidation decides which tokenizations to drop on the
    // assumption the mask has not moved. One at a time, each with its own
    // verified report, and the second run is a resume.
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
    // Last, because it is the only check that can delete rows, and it must not do
    // so against a map whose command or mask this run has already refused.
    retokenize match {
      case Some(r) => retokenizeOrRefuse(m, tokenizerIdentity, r)
      case None    => if (tokenizerIdentity.nonEmpty) checkOrRecordTokenizerIdentity(m, tokenizerIdentity)
    }
    m
  }

  /** The default path: record what is not recorded yet, and refuse if anything
    * recorded disagrees.
    *
    * Refusing is not the same thing as invalidating, and the distinction is the
    * whole reason this is safe to make the default. Nothing is deleted here. The
    * 186 already-published projects are untouched — a project that is never run
    * again is never refused either — and a project that IS re-run with a changed
    * tokenizer stops instead of quietly reproducing the old tokens.
    *
    * An extension with nothing recorded is not a change. That is what every blob
    * map in this corpus looks like today, and calling it a change would refuse
    * every project at once on the strength of no evidence at all. */
  private def checkOrRecordTokenizerIdentity(m: Mapping, identity: TokenizerIdentity): Unit = {
    val changes = m.cachePoisoningIdentityChanges(identity)
    if (changes.nonEmpty) throw new TokenizerChangedException(tokenizerChangedMessage(changes))
    // Nothing recorded yet, or recorded but with no rows to poison: record the
    // current value either way. Leaving an extension unrecorded is the hole this
    // whole mechanism exists to close, and leaving a superseded value in place for
    // an extension with no rows would refuse the next run for no reason.
    identity.byExtension.foreach { case (ext, value) =>
      if (!m.storedTokenizerId(ext).contains(value)) m.setTokenizerId(ext, value)
    }
  }

  /** The `--retokenize` path. Five outcomes, in this order, and the order is the
    * safety property: nothing is deleted until every check has passed.
    *
    *  1. A changed tokenizer the flag did not name: refuse, naming it. Invalidating
    *     the named extensions first and then refusing would leave a map no flag
    *     describes — part redone under a new tokenizer, part stale, and the
    *     recorded identity a mixture of both.
    *  2. No tokenized row carries any of the named extensions: refuse. This is the
    *     no-op case, and a no-op that exits 0 is the failure this flag exists to
    *     prevent.
    *  3. A sampled `new_blob` of a RETAINED row does not resolve in dst: refuse.
    *     Those ids live only there, so the map is reusable only alongside it.
    *  4. The memo purge. It runs BEFORE the transaction because the two failure
    *     directions are not symmetric: a memo entry deleted for nothing costs one
    *     tokenizer invocation, a memo entry kept serves the stale tokens the whole
    *     exercise is meant to remove. If the purge throws, nothing has been
    *     invalidated and the run stops.
    *  5. The purge found no entries at all: refuse. With a non-empty blob_map the
    *     tokenized rows were produced by tokenBySha.pl, which memoizes every one
    *     of them, so "none of them are in this directory" means the directory is
    *     not the memo — and the real memo would answer the re-tokenization with
    *     the stale tokens.
    *
    * Only after all five does anything change, and then it changes in one
    * transaction. */
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
    if (memo.examined > 0L && memo.deleted == 0L)
      throw new NothingInvalidatedException(
        s"--retokenize refused: the memo held none of the ${memo.examined} affected blob(s) " +
          s"(${memo.render}). Every tokenized row in this map was written by " +
          "tokenizeByBlobId/tokenBySha.pl, which memoizes each one, so an intact memo cannot be " +
          "missing all of them — the --memo-dir is almost certainly not this project's. That " +
          "matters because the memo is keyed on sha1(contents) with no tokenizer in the key: " +
          "dropping the blob_map rows while the real memo keeps its entries just serves the same " +
          "stale tokens through the other door. Nothing has been changed in the blob map.")

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
