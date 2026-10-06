package cregit.blobexec

/** Blobs on which srcML 1.1.0 and develop d38271916 do not terminate, keyed by git
  * blob id and grouped by defect. A tokenizer crash needs no entry: the walk already
  * excludes that blob. */
private[blobexec] object BlobDenylistEntries {

  import BlobDenylist.Entry

  private val CppInCHeader = Entry(
    "srcml-nontermination-analysis.md#class-2-the-c-parser-on-c-inside-a-h",
    "srcML's C parser (1.1.0 and develop d38271916) does not terminate on C++ in a .h file " +
      "(no upstream issue), minimal reproducer `namespace n{struct T&f(const struct S);}`; " +
      "eden/fs/utils/StatTimes.h"
  )

  val NonTerminating: Map[String, Entry] = Map(
    "5460d43f1884e3be230e02215151b9b963844a48" -> CppInCHeader,
    "720914b27e170b73b38ef662fb04239273dd0a34" -> CppInCHeader,
    "99c191e9a208547f5c9b1224c290f106a640de59" -> CppInCHeader,
    "dbb885e0bf046755c4fcfbddfc2454f90c550c62" -> CppInCHeader
  )
}
