package cregit.blobexec

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** What a published dataset does NOT contain is part of its contract, so the shipped
  * entries are asserted rather than trusted. */
class BlobDenylistSpec extends AnyFunSuite with Matchers {

  // TestNewCastArray.java (4) and CheckErrorsForSource7.java (4): srcML/srcML#2361.
  // srcML develop d38271916 parses them, so they must not be on the list.
  private val JavaAnnotationShas = Set(
    "2ee2673ad0a8ff2cef0254e7bfdc488cc1d61a65",
    "303c1a109f1fdc4b9d84e15111c56931202844ac",
    "504aaf9109fc01d6d62d99ac529ded99a8dfa8ca",
    "c91924d1e87cb82d372a73b24579ccc34609efaa",
    "56c8adf588e697a3e42b600b2e5ec54710592c61",
    "5e9c9138ed9c43c20591d1814cb72ad014d1a9e5",
    "6bf666550f6cba72f1602ce1b7a6b0c9025fc8c7",
    "afbd81e5789ba060147b0de3be4f01759963299d"
  )

  // StatTimes.h: the C parser on C++ in a `.h`. No upstream issue.
  private val CParserShas = Set(
    "5460d43f1884e3be230e02215151b9b963844a48",
    "720914b27e170b73b38ef662fb04239273dd0a34",
    "99c191e9a208547f5c9b1224c290f106a640de59",
    "dbb885e0bf046755c4fcfbddfc2454f90c550c62"
  )

  private val shipped = BlobDenylistEntries.NonTerminating

  test("the shipped list holds exactly the four known hangs, so an addition is deliberate") {
    // Also catches a sha written twice: a Map literal silently keeps the last.
    shipped.keySet shouldEqual CParserShas
    BlobDenylist.shipped.size shouldEqual 4
  }

  test("the Java blobs that srcML develop parses are not denylisted") {
    JavaAnnotationShas.foreach { sha =>
      withClue(s"$sha: ") { BlobDenylist.shipped.entryFor(sha) shouldEqual None }
    }
  }

  test("every key is a 40-character lowercase hex git blob id") {
    shipped.keys.foreach { sha =>
      withClue(s"$sha: ") { sha should fullyMatch regex "[0-9a-f]{40}" }
    }
  }

  test("every entry carries a one-line reason and the citation of its own defect") {
    shipped.foreach { case (sha, e) =>
      withClue(s"$sha: ") {
        e.citation should include("srcml-nontermination-analysis.md")
        e.reason should not include "\n"
        e.reason should include("1.1.0 and develop d38271916")
      }
    }
  }

  test("the C parser entries do not claim to be srcML#2361, and carry their reproducer") {
    // With no upstream issue, the reproducer in the reason is the evidence.
    CParserShas.foreach { sha =>
      val e = BlobDenylist.shipped.entryFor(sha).getOrElse(fail(s"$sha is not denylisted"))
      withClue(s"$sha: ") {
        e.citation should not include "2361"
        e.reason should include("namespace n{struct T&f(const struct S);}")
      }
    }
  }

  test("lookup is by git blob id, in either case, and misses everything else") {
    val sha = CParserShas.head
    BlobDenylist.shipped.entryFor(sha) should not be empty
    BlobDenylist.shipped.entryFor(sha.toUpperCase) should not be empty
    BlobDenylist.shipped.entryFor("0" * 40) shouldEqual None
    BlobDenylist.shipped.entryFor("") shouldEqual None
  }

  test("a fixture list built from uppercase keys still matches lowercase ids") {
    val entry = BlobDenylist.Entry("c", "r")
    BlobDenylist(Map("AB" * 20 -> entry)).entryFor("ab" * 20) shouldEqual Some(entry)
  }

  test("an empty list matches nothing, so a test fixture cannot exclude by accident") {
    BlobDenylist.empty.isEmpty shouldBe true
    BlobDenylist.empty.entryFor(CParserShas.head) shouldEqual None
  }
}
