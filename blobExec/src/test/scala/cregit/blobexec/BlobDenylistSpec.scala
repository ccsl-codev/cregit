package cregit.blobexec

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files

/** The denylist is a DATA file, because the list of blobs a published dataset
  * omits is a property of the dataset and a paper has to cite it. That makes the
  * file's contents part of the contract, so they are asserted here and not only
  * parsed. */
class BlobDenylistSpec extends AnyFunSuite with Matchers {

  // The four blobs, and the sizes `git cat-file --batch-check` reports for them.
  // All four are historical versions of one OpenJDK langtools regression test, at
  // two paths after a repository reorganisation — which is why the list is keyed
  // on content, not on path.
  private val KnownShas = Set(
    "2ee2673ad0a8ff2cef0254e7bfdc488cc1d61a65",
    "303c1a109f1fdc4b9d84e15111c56931202844ac",
    "504aaf9109fc01d6d62d99ac529ded99a8dfa8ca",
    "c91924d1e87cb82d372a73b24579ccc34609efaa"
  )

  test("the shipped list is on the classpath and holds exactly the four known blobs") {
    // A jar built without the resource would hand these four back to srcml, which
    // does not terminate on any of them: 600s each, then exit 4, then the project
    // cannot publish. So "the resource is present" is itself a requirement.
    BlobDenylist.shipped.shas shouldEqual KnownShas
    BlobDenylist.shipped.size shouldEqual 4
  }

  test("every entry carries a one-line reason and the upstream citation") {
    // Without both, an exclusion is indistinguishable from data loss. The partner
    // rejected "we could not parse it" as indefensible in a paper, and this is the
    // assertion that keeps the answer in the artefact.
    BlobDenylist.shipped.entries.foreach { e =>
      withClue(s"${e.sha}: ") {
        e.citation shouldEqual "srcML/srcML#2361"
        e.reason should not be empty
        e.reason should not include "\n"
        e.reason.toLowerCase should include("srcml")
      }
    }
  }

  test("lookup is by git blob id, in either case, and misses everything else") {
    val sha = KnownShas.head
    BlobDenylist.shipped.entryFor(sha).map(_.sha) shouldEqual Some(sha)
    BlobDenylist.shipped.entryFor(sha.toUpperCase).map(_.sha) shouldEqual Some(sha)
    BlobDenylist.shipped.entryFor("0" * 40) shouldEqual None
    BlobDenylist.shipped.entryFor("") shouldEqual None
  }

  test("an empty list matches nothing, so a test fixture cannot exclude by accident") {
    BlobDenylist.empty.isEmpty shouldBe true
    BlobDenylist.empty.entryFor(KnownShas.head) shouldEqual None
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
