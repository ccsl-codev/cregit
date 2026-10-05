package cregit.blobexec

/** Blobs on which srcML 1.1.0 does not terminate, keyed by git blob id and grouped
  * by defect. A tokenizer crash needs no entry: the walk already excludes that blob. */
private[blobexec] object BlobDenylistEntries {

  import BlobDenylist.Entry

  private val JavaAnnotatedArrays = Entry(
    "srcML/srcML#2361",
    "srcML 1.1.0 does not terminate on annotated array dimensions, minimal reproducer " +
      "`class C { int @A [] a @A []; }`; OpenJDK langtools regression tests " +
      "TestNewCastArray (@bug 8005681) and CheckErrorsForSource7"
  )

  private val CppInCHeader = Entry(
    "srcml-nontermination-analysis.md#class-2-the-c-parser-on-c-inside-a-h",
    "srcML 1.1.0's C parser does not terminate on C++ in a .h file (no upstream issue), " +
      "minimal reproducer `namespace n{struct T&f(const struct S);}`; eden/fs/utils/StatTimes.h"
  )

  val NonTerminating: Map[String, Entry] = Map(
    "2ee2673ad0a8ff2cef0254e7bfdc488cc1d61a65" -> JavaAnnotatedArrays,
    "303c1a109f1fdc4b9d84e15111c56931202844ac" -> JavaAnnotatedArrays,
    "504aaf9109fc01d6d62d99ac529ded99a8dfa8ca" -> JavaAnnotatedArrays,
    "c91924d1e87cb82d372a73b24579ccc34609efaa" -> JavaAnnotatedArrays,
    "56c8adf588e697a3e42b600b2e5ec54710592c61" -> JavaAnnotatedArrays,
    "5e9c9138ed9c43c20591d1814cb72ad014d1a9e5" -> JavaAnnotatedArrays,
    "6bf666550f6cba72f1602ce1b7a6b0c9025fc8c7" -> JavaAnnotatedArrays,
    "afbd81e5789ba060147b0de3be4f01759963299d" -> JavaAnnotatedArrays,
    "5460d43f1884e3be230e02215151b9b963844a48" -> CppInCHeader,
    "720914b27e170b73b38ef662fb04239273dd0a34" -> CppInCHeader,
    "99c191e9a208547f5c9b1224c290f106a640de59" -> CppInCHeader,
    "dbb885e0bf046755c4fcfbddfc2454f90c550c62" -> CppInCHeader
  )
}
