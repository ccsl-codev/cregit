package cregit.blobexec

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files

/** The denylist is a DATA file, because the list of blobs a published dataset
  * omits is a property of the dataset and a paper has to cite it. That makes the
  * file's contents part of the contract, so they are asserted here and not only
  * parsed. */
class BlobDenylistSpec extends AnyFunSuite with Matchers {

  // CLASS 1 — the Java parser on type-annotated array types, upstream
  // srcML/srcML#2361. Eight blobs, all in tencent__tencentkona-21's bare clone,
  // all OpenJDK langtools regression tests. They are the historical versions of
  // TWO files, one of which lives at two paths after a repository reorganisation —
  // which is why the list is keyed on content, not on path. The second file
  // carries a distinct sufficient construct within the same defect.
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

  // CLASS 2 — the C parser on C++ inside a `.h`, because CregitLanguages maps that
  // extension to language C. A DIFFERENT defect in a DIFFERENT language, with NO
  // upstream issue, so it cites the analysis document instead. Asserted separately
  // because the one thing a reader must not do is read this list as one defect.
  // Four historical versions of eden/fs/utils/StatTimes.h in facebook__sapling;
  // the file's other four versions return by value and parse in milliseconds.
  private val CParserShas = Set(
    "5460d43f1884e3be230e02215151b9b963844a48",
    "720914b27e170b73b38ef662fb04239273dd0a34",
    "99c191e9a208547f5c9b1224c290f106a640de59",
    "dbb885e0bf046755c4fcfbddfc2454f90c550c62"
  )

  // CLASS 3 — the C and C++ parsers CRASHING ON A SIGNAL (SIGSEGV or SIGABRT)
  // under `--position`. A THIRD defect, and the only one that is not a hang: srcml
  // is dead in milliseconds, so no timeout budget is ever involved. 197 blobs, the
  // whole history of 36 files across 18 projects, found by running the tokenizer's
  // real chain over all 904 historical versions of those paths (707 of the 904
  // parse cleanly — this is content-triggered, not a property of the path).
  //
  // Asserted by SIZE and by spot-checked members rather than by listing 197
  // literals: a 197-line literal set duplicated here would be maintained by
  // copy-paste and would stop being a check. What must not drift is the count, the
  // citation, and the fact that class 3 never claims to be #2361.
  private val ParserCrashCount = 197

  // Spot checks, one per distinguishing property, so the assertions below are
  // anchored on real entries rather than on whatever happens to be first.
  //   - the sweep's own control file, 1,263 B, which rules out a size limit
  //   - a SIGABRT entry, where the signal is NOT a segfault
  //   - a blob vendored byte-identically into TWO projects, which is why the list
  //     is keyed on content and not on path
  private val CrashPositionOnlySha = "4a62830c807a5a2aff73f474891c38ed913d39c4"
  private val CrashAbortSha        = "37cf5d5b1985a7510ad9c952897ef253231e80f7"
  private val CrashSharedBlobSha   = "012ea8148e8d21cf515a9ff47d4af719d049b276"

  private val KnownHangShas = JavaAnnotationShas ++ CParserShas

  test("the shipped list is on the classpath and holds the twelve hangs plus 197 crashes") {
    // A jar built without the resource would hand all of these back to srcml. The
    // twelve would hang for 600s each and then exit 4; the 197 would segfault or
    // abort, and blobExec would exit 6 on `blobsParserCrashed`. Either way the
    // project cannot publish, so "the resource is present" is itself a requirement.
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
    // The two groups are separated by citation, which is the only thing in the data
    // file that distinguishes them, so this also pins that the citations did not
    // get pasted across classes.
    val crashes = BlobDenylist.shipped.entries.filter(_.citation.contains("silent-empty-sweep.md"))
    crashes.size shouldEqual ParserCrashCount
    crashes.map(_.sha).toSet.size shouldEqual ParserCrashCount   // no duplicates
    crashes.map(_.sha).toSet intersect KnownHangShas shouldBe empty
  }

  test("a class 3 entry says CRASH, names its signal, and disclaims #2361") {
    // "we could not parse it" is indefensible in a paper; so is calling a segfault
    // a hang. Every class 3 reason has to carry the defect, the signal and the
    // srcML version, and has to say which invocation produced it.
    val crashes = BlobDenylist.shipped.entries.filter(_.citation.contains("silent-empty-sweep.md"))
    crashes.foreach { e =>
      withClue(s"${e.sha}: ") {
        e.citation should not include "2361"
        e.reason should include("srcML 1.1.0")
        e.reason should include("--position")
        e.reason.toUpperCase should include("CRASH")
        e.reason should (include("SIGSEGV (shell status 139)") or include("SIGABRT (shell status 134)"))
        // the flag is not optional, and the reason has to say why
        e.reason should include("token format carries line:col positions")
        // a crash is not the hang, and must not be mistaken for it
        e.reason should include("not the non-termination")
      }
    }
  }

  test("each class 3 entry records whether --position is the trigger, measured per blob") {
    // silent-empty-sweep.md claims --position is the trigger for all of them. It is
    // not: measured per blob, 132 are position-only, 49 die either way, and 16 exit
    // 1 without the flag. Inheriting the sweep's single claim would have put a
    // false statement on 65 entries, so each one states its own measurement and
    // this holds the three groups to their counts.
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
    // crc32_small.c, 1,263 B — the sweep's control, and the file the committed
    // reproducer tokenize/t/fixtures/srcml-position-crash.c is derived from.
    val posOnly = BlobDenylist.shipped.entryFor(CrashPositionOnlySha).getOrElse(
      fail(s"$CrashPositionOnlySha (crc32_small.c) is missing from the shipped denylist"))
    posOnly.reason should include("--position IS the trigger")
    posOnly.reason should include("SIGSEGV (shell status 139)")

    // An abort, not a segfault. The defect is broader than "segfault", and an
    // abort evades a timeout-shaped denylist just as thoroughly.
    val abort = BlobDenylist.shipped.entryFor(CrashAbortSha).getOrElse(
      fail(s"$CrashAbortSha (util-linux swaplabel.c) is missing from the shipped denylist"))
    abort.reason should include("SIGABRT (shell status 134)")

    // One blob, two projects: zlib's deflate.c is byte-identical in ossec__ossec-hids
    // and azerothcore__azerothcore-wotlk. A path-keyed list would need two entries
    // and would be wrong the moment a third project vendored the same bytes.
    BlobDenylist.shipped.entryFor(CrashSharedBlobSha) should not be empty
  }

  test("every entry carries a one-line reason and a citation for its own defect") {
    // Without both, an exclusion is indistinguishable from data loss. The partner
    // rejected "we could not parse it" as indefensible in a paper, and this is the
    // assertion that keeps the answer in the artefact.
    //
    // The citation is per-CLASS, not global. Citing #2361 for the C parser hang
    // would be a false attribution in a published artefact: it is a different
    // language and a different construct, and no upstream issue exists for it.
    // The same applies to class 3, which is not even a hang.
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
    // A pasted or empty citation would leave an omission in a published dataset with
    // nothing to look up. Partitioning the whole list proves there is no fourth,
    // accidental citation string hiding in the file.
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
    // The evidence for these is a minimised reproducer in the artefact rather than
    // an upstream issue number, so the reproducer is part of the contract.
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
        s"$sha2\tsrcML/srcML#2361\tnon-termination\t"   // a trailing tab is tolerated
      ),
      "fixture")
    list.size shouldEqual 2
    list.entryFor(sha1).map(_.reason) shouldEqual Some("non-termination")
  }

  test("a line that is not three fields is refused, naming the line") {
    // Silently skipping a malformed line would re-admit a blob that hangs the run
    // for 600s and then blocks publication — the exact failure this list removes.
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
    // The memo's key is sha1 of the file contents with no git header, so a
    // plausible-looking wrong hash is an easy mistake to make. A 39-character or
    // non-hex key would simply never match, silently.
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
