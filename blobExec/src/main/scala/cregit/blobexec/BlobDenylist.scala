package cregit.blobexec

/** Blobs never handed to the tokenizer, keyed by git blob id: srcML 1.1.0 does not
  * terminate on them. Excluded like an oversized blob, and deliberately not gating
  * the exit status. The entries live in [[BlobDenylistEntries]]. */
final class BlobDenylist private (private val bySha: Map[String, BlobDenylist.Entry]) {

  def entryFor(sha: String): Option[BlobDenylist.Entry] = bySha.get(sha.toLowerCase)

  def size: Int = bySha.size
  def isEmpty: Boolean = bySha.isEmpty
}

object BlobDenylist {

  /** Why one blob is excluded. Both fields are required: an exclusion nobody can
    * trace back to a defect is indistinguishable from data loss. */
  final case class Entry(citation: String, reason: String)

  /** Where a reader finds the list, quoted in the excluded-blob report. */
  val EntriesSource = "BlobDenylistEntries.scala"

  val empty: BlobDenylist = new BlobDenylist(Map.empty)

  def apply(bySha: Map[String, Entry]): BlobDenylist =
    new BlobDenylist(bySha.map { case (sha, e) => sha.toLowerCase -> e })

  val shipped: BlobDenylist = apply(BlobDenylistEntries.NonTerminating)
}
