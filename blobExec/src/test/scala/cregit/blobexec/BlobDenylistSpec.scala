package cregit.blobexec

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files

/** The shipped file's contents are part of the contract, so they are asserted, not only parsed. */
class BlobDenylistSpec extends AnyFunSuite with Matchers {

  // Class 1: the Java parser hang, srcML/srcML#2361.
  private val JavaAnnotationShas = Set(
    // TestNewCastArray.java
    "2ee2673ad0a8ff2cef0254e7bfdc488cc1d61a65",
    "303c1a109f1fdc4b9d84e15111c56931202844ac",
    "504aaf9109fc01d6d62d99ac529ded99a8dfa8ca",
    "c91924d1e87cb82d372a73b24579ccc34609efaa",
    // CheckErrorsForSource7.java
    "56c8adf588e697a3e42b600b2e5ec54710592c61",
    "5e9c9138ed9c43c20591d1814cb72ad014d1a9e5",
    "6bf666550f6cba72f1602ce1b7a6b0c9025fc8c7",
    "afbd81e5789ba060147b0de3be4f01759963299d"
  )

  // Class 2: the C parser hang on C++ in a `.h` (StatTimes.h). No upstream issue.
  private val CParserShas = Set(
    "5460d43f1884e3be230e02215151b9b963844a48",
    "720914b27e170b73b38ef662fb04239273dd0a34",
    "99c191e9a208547f5c9b1224c290f106a640de59",
    "dbb885e0bf046755c4fcfbddfc2454f90c550c62"
  )

  // Class 3: C/C++ parser crashes under `--position`. Checked by count and spot
  // checks, not by duplicating 197 literals.
  private val ParserCrashCount = 197

  // crc32_small.c (position-only), a SIGABRT, and one blob vendored into two projects.
  private val CrashPositionOnlySha = "4a62830c807a5a2aff73f474891c38ed913d39c4"
  private val CrashAbortSha        = "37cf5d5b1985a7510ad9c952897ef253231e80f7"
  private val CrashSharedBlobSha   = "012ea8148e8d21cf515a9ff47d4af719d049b276"

  private val KnownHangShas = JavaAnnotationShas ++ CParserShas

  test("the shipped list is on the classpath and holds the twelve hangs plus 197 crashes") {
    BlobDenylist.shipped.size shouldEqual 12 + ParserCrashCount
    JavaAnnotationShas.size shouldEqual 8
    CParserShas.size shouldEqual 4
    KnownHangShas.foreach { sha =>
      withClue(s"$sha is missing from the shipped denylist: ") {
        BlobDenylist.shipped.entryFor(sha) should not be empty
      }
    }
  }

  test("class 3 holds exactly the 197 signal-death blobs, and no hang is counted among them") {
    val crashes = BlobDenylist.shipped.entries.filter(_.citation.contains("silent-empty-sweep.md"))
    crashes.size shouldEqual ParserCrashCount
    crashes.map(_.sha).toSet.size shouldEqual ParserCrashCount   // no duplicates
    crashes.map(_.sha).toSet intersect KnownHangShas shouldBe empty
  }

  test("a class 3 entry says CRASH, names its signal, and disclaims #2361") {
    val crashes = BlobDenylist.shipped.entries.filter(_.citation.contains("silent-empty-sweep.md"))
    crashes.foreach { e =>
      withClue(s"${e.sha}: ") {
        e.citation should not include "2361"
        e.reason should include("srcML 1.1.0")
        e.reason should include("--position")
        e.reason.toUpperCase should include("CRASH")
        e.reason should (include("SIGSEGV (shell status 139)") or include("SIGABRT (shell status 134)"))
        e.reason should include("token format carries line:col positions")
        e.reason should include("not the non-termination")
      }
    }
  }

  test("each class 3 entry records whether --position is the trigger, measured per blob") {
    // silent-empty-sweep.md says --position is the trigger for all; it is not for 65.
    val crashes = BlobDenylist.shipped.entries.filter(_.citation.contains("silent-empty-sweep.md"))
    val positionOnly = crashes.count(_.reason.contains("--position IS the trigger"))
    val eitherWay    = crashes.count(_.reason.contains("--position is NOT the trigger"))
    val noHelp       = crashes.count(_.reason.contains("dropping --position would not recover"))

    positionOnly shouldEqual 132
    eitherWay shouldEqual 49
    noHelp shouldEqual 16
    positionOnly + eitherWay + noHelp shouldEqual ParserCrashCount
  }

  test("the spot-checked class 3 blobs are present, with the property each was chosen for") {
    // Source of the reproducer tests/t/fixtures/srcml-position-crash.c.
    val posOnly = BlobDenylist.shipped.entryFor(CrashPositionOnlySha).getOrElse(
      fail(s"$CrashPositionOnlySha (crc32_small.c) is missing from the shipped denylist"))
    posOnly.reason should include("--position IS the trigger")
    posOnly.reason should include("SIGSEGV (shell status 139)")

    val abort = BlobDenylist.shipped.entryFor(CrashAbortSha).getOrElse(
      fail(s"$CrashAbortSha (util-linux swaplabel.c) is missing from the shipped denylist"))
    abort.reason should include("SIGABRT (shell status 134)")

    BlobDenylist.shipped.entryFor(CrashSharedBlobSha) should not be empty
  }

  test("every entry carries a one-line reason and a citation for its own defect") {
    // Per class: citing #2361 for class 2 or 3 would be a false attribution.
    BlobDenylist.shipped.entries.foreach { e =>
      withClue(s"${e.sha}: ") {
        if (JavaAnnotationShas.contains(e.sha)) e.citation shouldEqual "srcML/srcML#2361"
        else if (CParserShas.contains(e.sha)) e.citation should include("srcml-nontermination-analysis.md")
        else e.citation should include("silent-empty-sweep.md")
        e.reason should not be empty
        e.reason should not include "\n"
        e.reason.toLowerCase should include("srcml")
      }
    }
  }

  test("every citation belongs to exactly one of the three classes, so none is unattributed") {
    val byClass = BlobDenylist.shipped.entries.groupBy { e =>
      if (e.citation == "srcML/srcML#2361") "class1"
      else if (e.citation.contains("srcml-nontermination-analysis.md")) "class2"
      else if (e.citation.contains("silent-empty-sweep.md")) "class3"
      else "UNATTRIBUTED: " + e.citation
    }
    byClass.keys.filter(_.startsWith("UNATTRIBUTED")) shouldBe empty
    byClass("class1").size shouldEqual 8
    byClass("class2").size shouldEqual 4
    byClass("class3").size shouldEqual ParserCrashCount
  }

  test("the C parser entries do not claim to be srcML#2361, and carry their reproducer") {
    // With no upstream issue, the reproducer in the reason is the evidence.
    CParserShas.foreach { sha =>
      val e = BlobDenylist.shipped.entryFor(sha).getOrElse(
        fail(s"the C parser blob $sha is missing from the shipped denylist"))
      withClue(s"$sha: ") {
        e.citation should not include "2361"
        e.reason should include("namespace n{struct T&f(const struct S);}")
      }
    }
  }

  test("lookup is by git blob id, in either case, and misses everything else") {
    val sha = KnownHangShas.head
    BlobDenylist.shipped.entryFor(sha).map(_.sha) shouldEqual Some(sha)
    BlobDenylist.shipped.entryFor(sha.toUpperCase).map(_.sha) shouldEqual Some(sha)
    BlobDenylist.shipped.entryFor("0" * 40) shouldEqual None
    BlobDenylist.shipped.entryFor("") shouldEqual None
  }

  test("an empty list matches nothing, so a test fixture cannot exclude by accident") {
    BlobDenylist.empty.isEmpty shouldBe true
    BlobDenylist.empty.entryFor(KnownHangShas.head) shouldEqual None
  }

  // -- the parser --------------------------------------------------------------

  private val sha1 = "a" * 40
  private val sha2 = "b" * 40

  test("comments, blank lines and surrounding whitespace are ignored") {
    val list = BlobDenylist.parse(
      Vector(
        "# a comment",
        "",
        "   ",
        s"  $sha1\tsrcML/srcML#2361\tnon-termination  ",
        s"$sha2\tsrcML/srcML#2361\tnon-termination\t"
      ),
      "fixture")
    list.size shouldEqual 2
    list.entryFor(sha1).map(_.reason) shouldEqual Some("non-termination")
  }

  test("a line that is not three fields is refused, naming the line") {
    val bad = intercept[IllegalArgumentException] {
      BlobDenylist.parse(Vector(s"$sha1\tsrcML/srcML#2361"), "fixture")
    }
    bad.getMessage should include("fixture:1")
    intercept[IllegalArgumentException] {
      BlobDenylist.parse(Vector(s"$sha1\t\treason"), "fixture")   // empty citation
    }
    intercept[IllegalArgumentException] {
      BlobDenylist.parse(Vector(s"$sha1\tcitation\t"), "fixture") // empty reason
    }
  }

  test("a key that is not a 40-hex git blob sha is refused") {
    intercept[IllegalArgumentException] {
      BlobDenylist.parse(Vector(s"${"a" * 39}\tc\tr"), "fixture")
    }
    intercept[IllegalArgumentException] {
      BlobDenylist.parse(Vector(s"${"z" * 40}\tc\tr"), "fixture")
    }
    intercept[IllegalArgumentException] {
      BlobDenylist.parse(Vector("TestNewCastArray.java\tc\tr"), "fixture")
    }
  }

  test("a duplicated sha is refused: two reasons for one blob means one is wrong") {
    val bad = intercept[IllegalArgumentException] {
      BlobDenylist.parse(
        Vector(s"$sha1\tc\tfirst reason", s"${sha1.toUpperCase}\tc\tsecond reason"),
        "fixture")
    }
    bad.getMessage should include("twice")
  }

  test("the same parser reads a file, which is how a fixture list is built") {
    val f = Files.createTempFile("denylist-", ".tsv")
    try {
      Files.writeString(f, s"# header\n$sha1\tsrcML/srcML#2361\tnon-termination\n")
      val list = BlobDenylist.fromFile(f)
      list.shas shouldEqual Set(sha1)
      list.entryFor(sha1).map(_.describe) shouldEqual
        Some("non-termination [srcML/srcML#2361]")
    } finally Files.deleteIfExists(f)
  }

  test("a missing resource is fatal rather than an empty list") {
    intercept[IllegalStateException] {
      BlobDenylist.fromResource("/cregit/blobexec/no-such-denylist.tsv")
    }
  }
}
