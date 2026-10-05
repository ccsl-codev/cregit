package cregit.blobexec

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}

/** Tokenizer-identity recording, refusal and `--retokenize`, at the blob-map level. */
class RetokenizeSpec extends AnyFunSuite with Matchers {

  private val cmd  = "/bin/cat"
  private val mask = """(?i)\.(c|h|rs)$"""

  private val rustV1 = "1111111111111111"
  private val rustV2 = "2222222222222222"
  private val cV1    = "aaaaaaaaaaaaaaaa"
  private val cV2    = "bbbbbbbbbbbbbbbb"

  private def id(pairs: (String, String)*) = TokenizerIdentity(pairs.toMap)

  private def withDb[A](body: Path => A): A = {
    val d = Files.createTempDirectory("retok-")
    try body(d.resolve("map.sqlite"))
    finally {
      def rm(p: Path): Unit = {
        if (Files.isDirectory(p)) { val s = Files.list(p); try s.forEach(rm) finally s.close() }
        Files.deleteIfExists(p)
        ()
      }
      rm(d)
    }
  }

  /** A finished run: two `.rs` rows, two `.c` rows and one identity row. */
  private def seed(db: Path, identity: TokenizerIdentity): Unit = {
    val m = Mapping.open(db, cmd, mask, tokenizerIdentity = identity)
    try {
      m.putBlob("rsblob1", "src/one.rs",   "newrs1")
      m.putBlob("rsblob2", "src/two.rs",   "newrs2")
      m.putBlob("cblob1",  "src/one.c",    "newc1")
      m.putBlob("cblob2",  "lib/two.h",    "newc2")
      m.putBlob("mdblob",  "README.md",    "mdblob")   // identity row: not selected
      m.putTree("tree1", "newtree1")
      m.putCommit("commit1", "newcommit1")
      m.putRef(Mapping.RefRow("refs/heads/master", "head", "commit1", "newcommit1", "commit1", "newcommit1"))
    } finally m.close()
  }

  private def resolvesEverything: String => Boolean = _ => true

  private def retokenize(
      exts: Set[String],
      purge: Vector[String] => TokenizerMemo.PurgeReport =
        blobs => TokenizerMemo.PurgeReport(blobs.size.toLong, blobs.size.toLong, 0L, 0L),
      resolves: String => Boolean = resolvesEverything,
      report: String => Unit = _ => (),
      memoDirIsTokenizerMemo: Boolean = false
  ): Mapping.Retokenize =
    Mapping.Retokenize(
      extensions = exts,
      newBlobResolves = resolves,
      purgeMemo = purge,
      report = report,
      memoDirIsTokenizerMemo = memoDirIsTokenizerMemo,
      nowEpochSeconds = () => 1700000000L)

  private val emptyMemo: Vector[String] => TokenizerMemo.PurgeReport =
    blobs => TokenizerMemo.PurgeReport(blobs.size.toLong, 0L, blobs.size.toLong, 0L)

  test("an identity absent from meta is recorded, so the first run establishes it") {
    withDb { db =>
      seed(db, id("rs" -> rustV1, "c" -> cV1))
      val m = Mapping.open(db, cmd, mask, tokenizerIdentity = id("rs" -> rustV1, "c" -> cV1))
      try {
        m.storedTokenizerId("rs") shouldBe Some(rustV1)
        m.storedTokenizerId("c")  shouldBe Some(cV1)
      } finally m.close()
    }
  }

  test("an unchanged identity is an ordinary resume and changes nothing") {
    withDb { db =>
      seed(db, id("rs" -> rustV1))
      val m = Mapping.open(db, cmd, mask, tokenizerIdentity = id("rs" -> rustV1))
      try m.rowCounts.blobTokenized shouldEqual 4L
      finally m.close()
    }
  }

  test("a CHANGED identity refuses the run; this is the bug, and the refusal is the fix") {
    withDb { db =>
      seed(db, id("rs" -> rustV1, "c" -> cV1))
      val ex = intercept[Mapping.TokenizerChangedException] {
        Mapping.open(db, cmd, mask, tokenizerIdentity = id("rs" -> rustV2, "c" -> cV1))
      }
      ex.getMessage should include("rs")
      ex.getMessage should include(rustV1)
      ex.getMessage should include(rustV2)
      ex.getMessage should include("--retokenize=rs")
      // Naming the unchanged extension would tell the operator to discard valid work.
      ex.getMessage should not include "--retokenize=c,rs"
    }
  }

  test("a refusal writes nothing: the map is exactly as it was") {
    withDb { db =>
      seed(db, id("rs" -> rustV1))
      intercept[Mapping.TokenizerChangedException] {
        Mapping.open(db, cmd, mask, tokenizerIdentity = id("rs" -> rustV2))
      }
      val m = Mapping.open(db, cmd, mask, tokenizerIdentity = id("rs" -> rustV1))
      try {
        m.rowCounts.blobTokenized shouldEqual 4L
        m.storedTokenizerId("rs") shouldBe Some(rustV1)
      } finally m.close()
    }
  }

  test("no identity passed at all leaves behaviour exactly as it was") {
    // Already-published projects must not start refusing.
    withDb { db =>
      seed(db, TokenizerIdentity.empty)
      val m = Mapping.open(db, cmd, mask)
      try {
        m.rowCounts.blobTokenized shouldEqual 4L
        m.storedTokenizerId("rs") shouldBe None
      } finally m.close()
    }
  }

  test("a changed identity for an extension with no cached rows is recorded, not refused") {
    // Every project gets an identity for every extension the checkout parses, so a
    // refusal here would have no remedy: --retokenize would have nothing to drop.
    withDb { db =>
      seed(db, id("rs" -> rustV1, "java" -> "9999999999999999"))
      val m = Mapping.open(db, cmd, mask,
        tokenizerIdentity = id("rs" -> rustV1, "java" -> "8888888888888888"))
      try {
        m.storedTokenizerId("java") shouldBe Some("8888888888888888")
        m.rowCounts.blobTokenized shouldEqual 4L
      } finally m.close()
    }
  }

  test("a changed identity IS refused as soon as one cached row carries the extension") {
    withDb { db =>
      seed(db, id("rs" -> rustV1, "java" -> "9999999999999999"))
      val m0 = Mapping.open(db, cmd, mask, tokenizerIdentity = id("rs" -> rustV1))
      try m0.putBlob("javablob", "src/One.java", "newjava") finally m0.close()
      intercept[Mapping.TokenizerChangedException] {
        Mapping.open(db, cmd, mask,
          tokenizerIdentity = id("rs" -> rustV1, "java" -> "8888888888888888"))
      }
    }
  }

  test("--retokenize drops only the named extension's tokenized rows") {
    withDb { db =>
      seed(db, id("rs" -> rustV1, "c" -> cV1))
      var reported = ""
      val m = Mapping.open(db, cmd, mask,
        tokenizerIdentity = id("rs" -> rustV2, "c" -> cV1),
        retokenize = Some(retokenize(Set("rs"), report = s => reported = s)))
      try {
        m.getBlob("rsblob1", "src/one.rs") shouldBe None
        m.getBlob("rsblob2", "src/two.rs") shouldBe None
        // The C rows survive: what makes this cheaper than --drop-memo.
        m.getBlob("cblob1", "src/one.c") shouldBe Some("newc1")
        m.getBlob("cblob2", "lib/two.h") shouldBe Some("newc2")
        m.storedTokenizerId("rs") shouldBe Some(rustV2)
        m.storedTokenizerId("c")  shouldBe Some(cV1)
        reported should include("rs")
      } finally m.close()
    }
  }

  test("--retokenize drops tree_map, commit_map and ref_map, because trees name blobs") {
    withDb { db =>
      seed(db, id("rs" -> rustV1))
      val m = Mapping.open(db, cmd, mask,
        tokenizerIdentity = id("rs" -> rustV2),
        retokenize = Some(retokenize(Set("rs"))))
      try {
        // A retained tree_map row would skip the re-walk and keep the old token blob.
        m.getTree("tree1") shouldBe None
        m.getCommit("commit1") shouldBe None
        m.getRef("refs/heads/master") shouldBe None
      } finally m.close()
    }
  }

  test("--retokenize keeps the identity pass-through rows, which are not tokenizations") {
    withDb { db =>
      seed(db, id("rs" -> rustV1))
      val m = Mapping.open(db, cmd, mask,
        tokenizerIdentity = id("rs" -> rustV2),
        retokenize = Some(retokenize(Set("rs"))))
      try m.getBlob("mdblob", "README.md") shouldBe Some("mdblob")
      finally m.close()
    }
  }

  test("--retokenize purges the memo for exactly the invalidated blobs, before touching the map") {
    withDb { db =>
      seed(db, id("rs" -> rustV1))
      var purged: Vector[String] = Vector.empty
      val m = Mapping.open(db, cmd, mask,
        tokenizerIdentity = id("rs" -> rustV2),
        retokenize = Some(retokenize(Set("rs"), purge = { blobs =>
          purged = blobs
          TokenizerMemo.PurgeReport(blobs.size.toLong, blobs.size.toLong, 0L, 0L)
        })))
      try {
        purged.sorted shouldEqual Vector("rsblob1", "rsblob2")
        // The memo key has no tokenizer in it (tokenBySha.pl:76), so it would
        // serve the same stale tokens.
        purged should not contain "cblob1"
      } finally m.close()
    }
  }

  test("a memo purge that fails aborts before the map is changed") {
    withDb { db =>
      seed(db, id("rs" -> rustV1))
      intercept[RuntimeException] {
        Mapping.open(db, cmd, mask,
          tokenizerIdentity = id("rs" -> rustV2),
          retokenize = Some(retokenize(Set("rs"),
            purge = _ => throw new RuntimeException("memo is read-only"))))
      }
      val m = Mapping.open(db, cmd, mask, tokenizerIdentity = id("rs" -> rustV1))
      try {
        m.getBlob("rsblob1", "src/one.rs") shouldBe Some("newrs1")
        m.storedTokenizerId("rs") shouldBe Some(rustV1)
      } finally m.close()
    }
  }

  test("a memo that held none of the affected blobs refuses: the --memo-dir is wrong") {
    withDb { db =>
      seed(db, id("rs" -> rustV1))
      val ex = intercept[Mapping.NothingInvalidatedException] {
        Mapping.open(db, cmd, mask,
          tokenizerIdentity = id("rs" -> rustV2),
          retokenize = Some(retokenize(Set("rs"),
            purge = blobs => TokenizerMemo.PurgeReport(blobs.size.toLong, 0L, blobs.size.toLong, 0L))))
      }
      ex.getMessage should include("--memo-dir")
      ex.getMessage should include("$BFG_MEMO_DIR")
      val m = Mapping.open(db, cmd, mask, tokenizerIdentity = id("rs" -> rustV1))
      try m.getBlob("rsblob1", "src/one.rs") shouldBe Some("newrs1")
      finally m.close()
    }
  }

  test("an empty memo is accepted when --memo-dir is the memo the tokenizer reads") {
    withDb { db =>
      seed(db, id("rs" -> rustV1, "c" -> cV1))
      val reports = Vector.newBuilder[String]
      val m = Mapping.open(db, cmd, mask,
        tokenizerIdentity = id("rs" -> rustV2, "c" -> cV1),
        retokenize = Some(retokenize(Set("rs"), purge = emptyMemo, report = reports += _,
          memoDirIsTokenizerMemo = true)))
      try {
        m.getBlob("rsblob1", "src/one.rs") shouldBe None
        m.getBlob("rsblob2", "src/two.rs") shouldBe None
        m.getBlob("cblob1", "src/one.c") shouldBe Some("newc1")
        m.storedTokenizerId("rs") shouldBe Some(rustV2)
        val log = reports.result().mkString("\n")
        log should include("the memo held none of the 2 affected blob(s)")
        log should include("Accepted, because --memo-dir is $BFG_MEMO_DIR")
        log should include("DROPPED 2 tokenized blob_map rows")
      } finally m.close()
    }
  }

  test("a memo that held entries is purged without the empty-memo notice") {
    withDb { db =>
      seed(db, id("rs" -> rustV1))
      val reports = Vector.newBuilder[String]
      val m = Mapping.open(db, cmd, mask,
        tokenizerIdentity = id("rs" -> rustV2),
        retokenize = Some(retokenize(Set("rs"), report = reports += _, memoDirIsTokenizerMemo = true)))
      try {
        m.getBlob("rsblob1", "src/one.rs") shouldBe None
        reports.result().mkString("\n") should not include "Accepted, because"
      } finally m.close()
    }
  }

  test("--retokenize on an extension with no tokenized rows refuses, it does not exit quietly") {
    withDb { db =>
      seed(db, id("rs" -> rustV1, "java" -> "cccccccccccccccc"))
      val ex = intercept[Mapping.NothingInvalidatedException] {
        Mapping.open(db, cmd, mask,
          tokenizerIdentity = id("rs" -> rustV1, "java" -> "dddddddddddddddd"),
          retokenize = Some(retokenize(Set("java"))))
      }
      ex.getMessage should include("java")
      val m = Mapping.open(db, cmd, mask, tokenizerIdentity = id("rs" -> rustV1))
      try {
        m.rowCounts.blobTokenized shouldEqual 4L
        m.getTree("tree1") shouldBe Some("newtree1")
      } finally m.close()
    }
  }

  test("--retokenize that does not cover every changed tokenizer refuses first, changing nothing") {
    withDb { db =>
      seed(db, id("rs" -> rustV1, "c" -> cV1))
      val ex = intercept[Mapping.TokenizerChangedException] {
        Mapping.open(db, cmd, mask,
          tokenizerIdentity = id("rs" -> rustV2, "c" -> cV2),
          retokenize = Some(retokenize(Set("rs"))))
      }
      ex.getMessage should include("c")
      // Invalidating .rs and then refusing would leave a half-invalidated map.
      val m = Mapping.open(db, cmd, mask, tokenizerIdentity = id("rs" -> rustV1, "c" -> cV1))
      try {
        m.getBlob("rsblob1", "src/one.rs") shouldBe Some("newrs1")
        m.storedTokenizerId("rs") shouldBe Some(rustV1)
      } finally m.close()
    }
  }

  test("naming every changed tokenizer at once is accepted") {
    withDb { db =>
      seed(db, id("rs" -> rustV1, "c" -> cV1, "h" -> cV1))
      val m = Mapping.open(db, cmd, mask,
        tokenizerIdentity = id("rs" -> rustV2, "c" -> cV2, "h" -> cV2),
        retokenize = Some(retokenize(Set("rs", "c", "h"))))
      try {
        m.rowCounts.blobTokenized shouldEqual 0L
        m.storedTokenizerId("rs") shouldBe Some(rustV2)
        m.storedTokenizerId("c")  shouldBe Some(cV2)
      } finally m.close()
    }
  }

  test("--retokenize refuses when a retained new_blob does not resolve in dst") {
    withDb { db =>
      seed(db, id("rs" -> rustV1, "c" -> cV1))
      // blob_map's new_blob ids live in dst and nowhere else. Reusing the .c rows
      // beside a dst that was wiped leaves every retained reference dangling.
      intercept[Mapping.DanglingNewBlobException] {
        Mapping.open(db, cmd, mask,
          tokenizerIdentity = id("rs" -> rustV2, "c" -> cV1),
          retokenize = Some(retokenize(Set("rs"), resolves = _ => false)))
      }
      val m = Mapping.open(db, cmd, mask, tokenizerIdentity = id("rs" -> rustV1))
      try m.getBlob("rsblob1", "src/one.rs") shouldBe Some("newrs1")
      finally m.close()
    }
  }

  test("the reachability probe asks about the rows that are KEPT, never the ones dropped") {
    withDb { db =>
      seed(db, id("rs" -> rustV1, "c" -> cV1))
      var asked = Vector.empty[String]
      val m = Mapping.open(db, cmd, mask,
        tokenizerIdentity = id("rs" -> rustV2, "c" -> cV1),
        retokenize = Some(retokenize(Set("rs"), resolves = { b => asked :+= b; true })))
      try {
        asked should contain allOf ("newc1", "newc2")
        // Probing a dropped row would refuse a correct invalidation whenever the
        // old token blobs had already been garbage-collected out of dst.
        asked should not contain "newrs1"
      } finally m.close()
    }
  }

  test("extensions are matched case-insensitively, as the mask is") {
    withDb { db =>
      val m0 = Mapping.open(db, cmd, mask, tokenizerIdentity = id("rs" -> rustV1))
      try m0.putBlob("upper", "src/Shouty.RS", "newupper") finally m0.close()
      val m = Mapping.open(db, cmd, mask,
        tokenizerIdentity = id("rs" -> rustV2),
        retokenize = Some(retokenize(Set("rs"))))
      try m.getBlob("upper", "src/Shouty.RS") shouldBe None
      finally m.close()
    }
  }

  test("a longer extension is not matched by a shorter one's suffix") {
    withDb { db =>
      val m0 = Mapping.open(db, cmd, mask, tokenizerIdentity = id("c" -> cV1))
      try {
        m0.putBlob("ccblob",  "src/x.cc",  "newcc")
        m0.putBlob("cppblob", "src/x.cpp", "newcpp")
        m0.putBlob("cblob",   "src/x.c",   "newc")
      } finally m0.close()
      val m = Mapping.open(db, cmd, mask,
        tokenizerIdentity = id("c" -> cV2),
        retokenize = Some(retokenize(Set("c"))))
      try {
        m.getBlob("cblob", "src/x.c") shouldBe None
        // `.cc` and `.cpp` end in neither `.c`; a naive LIKE '%c' would take both.
        m.getBlob("ccblob", "src/x.cc")   shouldBe Some("newcc")
        m.getBlob("cppblob", "src/x.cpp") shouldBe Some("newcpp")
      } finally m.close()
    }
  }

  test("the invalidation is recorded in meta, so a reused map says where it came from") {
    withDb { db =>
      seed(db, id("rs" -> rustV1))
      val m = Mapping.open(db, cmd, mask,
        tokenizerIdentity = id("rs" -> rustV2),
        retokenize = Some(retokenize(Set("rs"))))
      try {
        m.getMeta(Mapping.RetokenizedAtKey) shouldBe Some("1700000000")
        m.getMeta(Mapping.RetokenizedExtensionsKey) shouldBe Some("rs")
        m.getMeta(Mapping.RetokenizedFromKey) shouldBe Some(s"rs=$rustV1")
      } finally m.close()
    }
  }

  test("--mask-widened and --retokenize together are refused, not composed") {
    withDb { db =>
      seed(db, id("rs" -> rustV1))
      intercept[IllegalArgumentException] {
        Mapping.open(db, cmd, """(?i)\.(c|h|rs|java)$""",
          maskWidening = Some(Mapping.MaskWidening(newBlobResolves = _ => true, report = _ => ())),
          tokenizerIdentity = id("rs" -> rustV2),
          retokenize = Some(retokenize(Set("rs"))))
      }
    }
  }

  test("--retokenize without an identity to record is refused") {
    withDb { db =>
      seed(db, TokenizerIdentity.empty)
      intercept[IllegalArgumentException] {
        Mapping.open(db, cmd, mask, retokenize = Some(retokenize(Set("rs"))))
      }
    }
  }

  test("--retokenize naming an extension the identity does not cover is refused") {
    withDb { db =>
      seed(db, id("rs" -> rustV1))
      intercept[IllegalArgumentException] {
        Mapping.open(db, cmd, mask,
          tokenizerIdentity = id("rs" -> rustV2),
          retokenize = Some(retokenize(Set("rs", "java"))))
      }
    }
  }
}
