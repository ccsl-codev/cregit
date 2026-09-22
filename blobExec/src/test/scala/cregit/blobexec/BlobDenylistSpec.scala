package cregit.blobexec

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** What a published dataset does NOT contain is part of its contract, so the shipped
  * entries are asserted rather than trusted. */
class BlobDenylistSpec extends AnyFunSuite with Matchers {

  // Four historical versions of one file at two paths: the list is keyed on content.
  private val KnownShas = Set(
    "2ee2673ad0a8ff2cef0254e7bfdc488cc1d61a65",
    "303c1a109f1fdc4b9d84e15111c56931202844ac",
    "504aaf9109fc01d6d62d99ac529ded99a8dfa8ca",
    "c91924d1e87cb82d372a73b24579ccc34609efaa"
  )

  test("the shipped list holds exactly the known blobs, so an addition is deliberate") {
    // Also catches a sha written twice: a Map literal silently keeps the last.
    BlobDenylistEntries.NonTerminating.keySet shouldEqual KnownShas
  }

  test("every key is a 40-character lowercase hex git blob id") {
    // Not the memo's content hash, and not a path.
    BlobDenylistEntries.NonTerminating.keys.foreach { sha =>
      withClue(s"$sha: ") { sha should fullyMatch regex "[0-9a-f]{40}" }
    }
  }

  test("every entry cites a defect and says what it is") {
    BlobDenylistEntries.NonTerminating.foreach { case (sha, e) =>
      withClue(s"$sha: ") {
        e.citation should not be empty
        e.reason should not include "\n"
        e.reason.toLowerCase should include("srcml")
      }
    }
  }

  test("lookup is by git blob id, in either case, and misses everything else") {
    val sha = KnownShas.head
    BlobDenylist.shipped.entryFor(sha) should not be empty
    BlobDenylist.shipped.entryFor(sha.toUpperCase) should not be empty
    BlobDenylist.shipped.entryFor("0" * 40) shouldEqual None
    BlobDenylist.shipped.entryFor("") shouldEqual None
  }

  test("an empty list matches nothing, so a test fixture cannot exclude by accident") {
    BlobDenylist.empty.isEmpty shouldBe true
    BlobDenylist.empty.entryFor(KnownShas.head) shouldEqual None
  }
}
