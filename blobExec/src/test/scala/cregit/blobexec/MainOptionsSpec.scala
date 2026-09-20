package cregit.blobexec

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** `--blob-timeout` and `--stall-timeout` are binding parts of the spec: 600 and
  * 1800 by default, and values that are not a positive whole number of seconds
  * must be rejected rather than silently read as "no limit". The shared value
  * parser is tested here; `main` itself cannot be, because it answers bad input
  * with `sys.exit`. The stall watchdog's decision is tested here too, because
  * the watchdog itself halts the JVM and so cannot be exercised in-process. */
class MainOptionsSpec extends AnyFunSuite with Matchers {

  test("the default per-blob budget is 600 seconds") {
    BlobExec.DefaultTimeoutSeconds shouldEqual 600
  }

  test("the default stall window is 1800 seconds") {
    Walker.DefaultStallTimeoutSeconds shouldEqual 1800
  }

  test("a timeout budget accepts positive whole seconds") {
    Main.parsePositiveSeconds("600") shouldEqual Some(600)
    Main.parsePositiveSeconds("1") shouldEqual Some(1)
    Main.parsePositiveSeconds("1800") shouldEqual Some(1800)
    Main.parsePositiveSeconds("86400") shouldEqual Some(86400)
  }

  test("a timeout budget rejects zero and negatives: work must always be bounded") {
    Main.parsePositiveSeconds("0") shouldEqual None
    Main.parsePositiveSeconds("-1") shouldEqual None
    Main.parsePositiveSeconds("-600") shouldEqual None
  }

  test("a timeout budget rejects non-numeric and empty values") {
    Main.parsePositiveSeconds("abc") shouldEqual None
    Main.parsePositiveSeconds("") shouldEqual None
    Main.parsePositiveSeconds("600s") shouldEqual None
    Main.parsePositiveSeconds("10.5") shouldEqual None
  }

  test("timeout and stall have distinct exit statuses, and neither collides") {
    Main.TimedOutExitStatus shouldEqual 4
    Walker.StalledExitStatus shouldEqual 5
    Main.TimedOutExitStatus should not equal Walker.StalledExitStatus
    Set(0, 1, 2, 3) should not contain Main.TimedOutExitStatus
    Set(0, 1, 2, 3) should not contain Walker.StalledExitStatus
  }

  // -- the two timeouts are coupled -------------------------------------------

  test("a window at or above the floor is accepted unchanged") {
    Walker.resolveStallTimeout(600, 1800, stallExplicit = false) shouldEqual Right(1800)
    Walker.resolveStallTimeout(600, 1800, stallExplicit = true) shouldEqual Right(1800)
    Walker.resolveStallTimeout(600, Walker.stallFloorFor(600), stallExplicit = true) shouldEqual
      Right(Walker.stallFloorFor(600))
  }

  test("the floor is the child's whole lifetime, not just the budget") {
    Walker.stallFloorFor(600) should be > ChildRunner.maxLifetimeSeconds(600)
    Walker.resolveStallTimeout(600, 601, stallExplicit = true).isLeft shouldBe true
    Walker.resolveStallTimeout(600, 630, stallExplicit = true).isLeft shouldBe true
  }

  test("a refusal names the floor it wants, and the suggestion clears it") {
    val why = Walker.resolveStallTimeout(600, 601, stallExplicit = true).swap.getOrElse("")
    why should include(Walker.stallFloorFor(600).toString)
    val suggested = Walker.stallTimeoutFor(600)
    Walker.resolveStallTimeout(600, suggested, stallExplicit = true) shouldEqual Right(suggested)
  }

  test("a small budget is widened past the fixed kill overhead, not just tripled") {
    Walker.resolveStallTimeout(5, 3, stallExplicit = false)
      .getOrElse(0) should be >= Walker.stallFloorFor(5)
    Walker.resolveStallTimeout(1, 1, stallExplicit = false)
      .getOrElse(0) should be >= Walker.stallFloorFor(1)
  }

  test("the floor cannot overflow into a guard that always passes") {
    Walker.stallFloorFor(Int.MaxValue) should be > 0
    ChildRunner.maxLifetimeSeconds(Int.MaxValue) should be > 0
  }

  test("the defaults satisfy the relationship") {
    Walker.resolveStallTimeout(
      BlobExec.DefaultTimeoutSeconds,
      Walker.DefaultStallTimeoutSeconds,
      stallExplicit = false
    ) shouldEqual Right(Walker.DefaultStallTimeoutSeconds)
  }

  test("a defaulted window is widened to fit a raised --blob-timeout") {
    Walker.resolveStallTimeout(1800, 1800, stallExplicit = false) shouldEqual Right(5400)
    Walker.resolveStallTimeout(3600, 1800, stallExplicit = false) shouldEqual Right(10800)
  }

  test("an explicit window that is too small is refused, naming both values") {
    val bad = Walker.resolveStallTimeout(1800, 1800, stallExplicit = true)
    bad.isLeft shouldBe true
    val why = bad.swap.getOrElse("")
    why should include("--stall-timeout=1800")
    why should include("--blob-timeout=1800")
    Walker.resolveStallTimeout(600, 60, stallExplicit = true).isLeft shouldBe true
  }

  test("widening cannot overflow Int") {
    Walker.resolveStallTimeout(Int.MaxValue, 1800, stallExplicit = false) shouldEqual Right(Int.MaxValue)
  }

  // -- the stall window's floor -----------------------------------------------

  test("the floor clears one blob's whole lifetime, kill path included") {
    Walker.stallFloorFor(600) should be > ChildRunner.maxLifetimeSeconds(600)
    Walker.stallFloorFor(1) should be > ChildRunner.maxLifetimeSeconds(1)
  }

  test("the default window is derived from the blob budget, not a second literal") {
    Walker.DefaultStallTimeoutSeconds shouldEqual Walker.stallTimeoutFor(BlobExec.DefaultTimeoutSeconds)
  }

  test("a defaulted window follows a raised budget past the floor") {
    Walker.stallTimeoutFor(1800) shouldEqual 5400
    Walker.stallTimeoutFor(5) should be >= Walker.stallFloorFor(5)
    Walker.stallTimeoutFor(1) should be >= Walker.stallFloorFor(1)
  }

  test("a blob budget past the default window raises the floor above it") {
    Walker.stallFloorFor(3600) should be > Walker.DefaultStallTimeoutSeconds
  }

  test("the floor grows with the blob budget, and never underflows") {
    Walker.stallFloorFor(60) should be < Walker.stallFloorFor(600)
    Walker.stallFloorFor(0) should be > 0
    Walker.stallFloorFor(-5) should be > 0
  }

  test("a run at exactly the floor is not stalled by its own slowest blob") {
    val blobBudget = 3600
    val floor      = Walker.stallFloorFor(blobBudget)
    val quiet      = ChildRunner.maxLifetimeSeconds(blobBudget).toLong * 1000000000L
    Walker.isStalled(nowNanos = quiet, lastProgressNanos = 0L, floor) shouldBe false
  }

  test("the accepted range of a timeout keeps every derived window inside Int") {
    Main.parsePositiveSeconds((Main.MaxTimeoutSeconds + 1).toString) shouldEqual None
    Main.parsePositiveSeconds(Int.MaxValue.toString) shouldEqual None
    Walker.stallTimeoutFor(Main.MaxTimeoutSeconds) should be > 0
    ChildRunner.maxLifetimeSeconds(Main.MaxTimeoutSeconds) should be > 0
  }

  // -- which counters gate publication ----------------------------------------

  private def stats(
      aborted: Boolean = false,
      blobsTimedOut: Long = 0L,
      blobsOversized: Long = 0L,
      blobsDenylisted: Long = 0L,
      blobsParserCrashed: Long = 0L
  ) = WalkStats(
    commitsProcessed = 1, commitsAlreadyMapped = 0, blobsRunThroughCommand = 1,
    blobsCacheHit = 0, refsProjected = 1, aborted = aborted,
    blobsTimedOut = blobsTimedOut, blobsOversized = blobsOversized,
    blobsDenylisted = blobsDenylisted, blobsParserCrashed = blobsParserCrashed,
    blobCommandExecutions = 1,
    originalBlobCopyRequests = 0, originalBlobCopies = 0,
    originalBlobAlreadyPresent = 0, originalBlobCacheHits = 0,
    originalBlobDestinationLookups = 0, originalBlobBytesCopied = 0,
    originalBlobBytesAvoided = 0)

  test("a clean walk exits 0") {
    Main.exitStatus(stats()) shouldEqual 0
  }

  test("denylisted blobs do not change the exit status, at any count") {
    Main.exitStatus(stats(blobsDenylisted = 1)) shouldEqual 0
    Main.exitStatus(stats(blobsDenylisted = 4)) shouldEqual 0
    Main.exitStatus(stats(blobsDenylisted = 1000)) shouldEqual 0
  }

  test("oversized blobs do not change it either, unchanged from before") {
    Main.exitStatus(stats(blobsOversized = 3)) shouldEqual 0
    Main.exitStatus(stats(blobsOversized = 3, blobsDenylisted = 4)) shouldEqual 0
  }

  test("a timeout still blocks publication, even alongside a denylisted blob") {
    Main.exitStatus(stats(blobsTimedOut = 1)) shouldEqual Main.TimedOutExitStatus
    Main.exitStatus(stats(blobsTimedOut = 1, blobsDenylisted = 4)) shouldEqual
      Main.TimedOutExitStatus
  }

  test("an abort still wins over everything") {
    Main.exitStatus(stats(aborted = true)) shouldEqual 2
    Main.exitStatus(stats(aborted = true, blobsTimedOut = 1, blobsDenylisted = 4)) shouldEqual 2
    Main.exitStatus(stats(aborted = true, blobsParserCrashed = 1)) shouldEqual 2
  }

  // A parser crash is a defect nobody has explained — srcML 1.1.0 dying on a
  // signal — so it gates publication the way a timeout does, and unlike an
  // oversized or denylisted blob. It gets its OWN status because the remedies
  // differ: --blob-timeout does nothing for a segfault.

  test("a parser crash blocks publication, with its own status") {
    Main.exitStatus(stats(blobsParserCrashed = 1)) shouldEqual Main.ParserCrashedExitStatus
    Main.exitStatus(stats(blobsParserCrashed = 36)) shouldEqual Main.ParserCrashedExitStatus
  }

  test("a parser crash still blocks alongside explained exclusions") {
    Main.exitStatus(stats(blobsParserCrashed = 1, blobsDenylisted = 4, blobsOversized = 3)) shouldEqual
      Main.ParserCrashedExitStatus
  }

  // Regression: this status was first written as 5, which is already
  // Walker.StalledExitStatus — so a parser crash would have been reported to
  // run_pipeline_process.sh as a stall, sending the operator to --blob-timeout for
  // a segfault and writing the wrong marker file. Every status blobExec can exit
  // with must be distinct, so assert the whole set rather than just one pair.
  test("the parser-crash status collides with no other blobExec exit status") {
    val others = Map(
      "clean"       -> 0,
      "usage"       -> 1,
      "aborted"     -> 2,
      "maskChanged" -> 3,
      "timedOut"    -> Main.TimedOutExitStatus,
      "stalled"     -> Walker.StalledExitStatus
    )
    others.foreach { case (name, status) =>
      withClue(s"parser-crash status must differ from $name ($status): ") {
        Main.ParserCrashedExitStatus should not equal status
      }
    }
  }

  // -- the watchdog's decision ------------------------------------------------

  private val second = 1000000000L

  test("no stall while progress is inside the window") {
    Walker.isStalled(nowNanos = 100 * second, lastProgressNanos = 100 * second, 30) shouldBe false
    Walker.isStalled(nowNanos = 129 * second, lastProgressNanos = 100 * second, 30) shouldBe false
  }

  test("the boundary itself is not a stall; one nanosecond past it is") {
    Walker.isStalled(nowNanos = 130 * second, lastProgressNanos = 100 * second, 30) shouldBe false
    Walker.isStalled(nowNanos = 130 * second + 1, lastProgressNanos = 100 * second, 30) shouldBe true
  }

  test("a long stall is a stall, and the default window is what fires on 51 hours") {
    Walker.isStalled(nowNanos = 3600 * second, lastProgressNanos = 0L, 1800) shouldBe true
    val hours51 = 51L * 3600L * second
    Walker.isStalled(hours51, 0L, Walker.DefaultStallTimeoutSeconds) shouldBe true
  }

  test("a degenerate window is floored at one second, never zero") {
    Walker.isStalled(nowNanos = second / 2, lastProgressNanos = 0L, 0) shouldBe false
    Walker.isStalled(nowNanos = 2 * second, lastProgressNanos = 0L, 0) shouldBe true
    Walker.isStalled(nowNanos = 2 * second, lastProgressNanos = 0L, -5) shouldBe true
  }

  test("nanoTime is monotonic but not epoch-based, so negative elapsed is never a stall") {
    Walker.isStalled(nowNanos = -5 * second, lastProgressNanos = -3 * second, 30) shouldBe false
  }
}
