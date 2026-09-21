package cregit.blobexec

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files

/** The denylist is a DATA file, because the list of blobs a published dataset
  * contents are part of the dataset's contract, so they are asserted, not only
  * parsed. */
class BlobDenylistSpec extends AnyFunSuite with Matchers {

  // Four historical versions of one file at two paths: the list is keyed on content.
  private val KnownShas = Set(
    "2ee2673ad0a8ff2cef0254e7bfdc488cc1d61a65",
    "303c1a109f1fdc4b9d84e15111c56931202844ac",
    "504aaf9109fc01d6d62d99ac529ded99a8dfa8ca",
    "c91924d1e87cb82d372a73b24579ccc34609efaa"
  )

  test("the shipped list is on the classpath and holds exactly the four known blobs") {
    // A jar built without the resource would hand these four back to srcml.
    BlobDenylist.shipped.shas shouldEqual KnownShas
    BlobDenylist.shipped.size shouldEqual 4
  }

  test("every entry carries a one-line reason and the upstream citation") {
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
    // The key is the git blob id, not the memo's content hash.
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
      list.entryFor(sha1).map(e => (e.reason, e.citation)) shouldEqual
        Some(("non-termination", "srcML/srcML#2361"))
    } finally Files.deleteIfExists(f)
  }

  test("a missing resource is fatal rather than an empty list") {
    intercept[IllegalStateException] {
      BlobDenylist.fromResource("/cregit/blobexec/no-such-denylist.tsv")
    }
  }
}
