package cregit.blobexec

/** The denylisted blobs themselves, keyed by git blob id. Data only: [[BlobDenylist]]
  * holds the lookup, and Walker drops these paths from every rewritten tree.
  *
  * Keyed on content, not path, so one file's history across a move is several
  * entries sharing a reason. Each defect class is named once below, which is why the
  * shas are grouped rather than each carrying its own prose. */
private[blobexec] object BlobDenylistEntries {

  import BlobDenylist.Entry

  /** srcML 1.1.0 loops forever on `String @A [] [] s = new String @A [2] [2];`.
    * Open upstream against the latest release. */
  private val JavaAnnotatedArrays = Entry(
    "srcML/srcML#2361",
    "srcML 1.1.0 does not terminate on this file's annotated multi-dimensional " +
      "array declarations; OpenJDK langtools regression test @bug 8005681"
  )

  val NonTerminating: Map[String, Entry] = Map(
    "2ee2673ad0a8ff2cef0254e7bfdc488cc1d61a65" -> JavaAnnotatedArrays,
    "303c1a109f1fdc4b9d84e15111c56931202844ac" -> JavaAnnotatedArrays,
    "504aaf9109fc01d6d62d99ac529ded99a8dfa8ca" -> JavaAnnotatedArrays,
    "c91924d1e87cb82d372a73b24579ccc34609efaa" -> JavaAnnotatedArrays
  )
}
