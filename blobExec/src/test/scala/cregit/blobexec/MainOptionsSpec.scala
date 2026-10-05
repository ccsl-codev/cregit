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

  test("the stall status collides with no other exit status") {
    Walker.StalledExitStatus shouldEqual 5
    Set(0, 1, 2, 3) should not contain Walker.StalledExitStatus
  }

  // -- the two timeouts are coupled -------------------------------------------

  test("a window at or above the floor is accepted unchanged") {
    Main.resolveStallTimeout(600, 1800, stallExplicit = false) shouldEqual Right(1800)
    Main.resolveStallTimeout(600, 1800, stallExplicit = true) shouldEqual Right(1800)
    Main.resolveStallTimeout(600, Walker.stallFloorFor(600), stallExplicit = true) shouldEqual
      Right(Walker.stallFloorFor(600))
  }

  test("the floor is the child's whole lifetime, not just the budget") {
    Walker.stallFloorFor(600) should be > BlobExec.maxChildLifetimeSeconds(600)
    Main.resolveStallTimeout(600, 601, stallExplicit = true).isLeft shouldBe true
    Main.resolveStallTimeout(600, 630, stallExplicit = true).isLeft shouldBe true
  }

  test("a refusal names the floor it wants, and the suggestion clears it") {
    val why = Main.resolveStallTimeout(600, 601, stallExplicit = true).swap.getOrElse("")
    why should include(Walker.stallFloorFor(600).toString)
    val suggested = Main.widenedStall(600)
    Main.resolveStallTimeout(600, suggested, stallExplicit = true) shouldEqual Right(suggested)
  }

  test("a small --blob-timeout no longer lets the watchdog stop a healthy run") {
    val oneBlobLifetimeNanos = BlobExec.maxChildLifetimeSeconds(5).toLong * 1000000000L
    Main.resolveStallTimeout(5, 15, stallExplicit = true).isLeft shouldBe true
    val window = Main.resolveStallTimeout(5, 3, stallExplicit = false).getOrElse(0)
    Walker.isStalled(oneBlobLifetimeNanos, 0L, window) shouldBe false
    Main.resolveStallTimeout(1, 1, stallExplicit = false).getOrElse(0) should be >= Walker.stallFloorFor(1)
  }

  test("the floor cannot overflow into a guard that always passes") {
    Walker.stallFloorFor(Int.MaxValue) should be > 0
    BlobExec.maxChildLifetimeSeconds(Int.MaxValue) should be > 0
  }

  test("the defaults satisfy the relationship") {
    Main.resolveStallTimeout(
      BlobExec.DefaultTimeoutSeconds,
      Walker.DefaultStallTimeoutSeconds,
      stallExplicit = false
    ) shouldEqual Right(Walker.DefaultStallTimeoutSeconds)
  }

  test("a defaulted window is widened to fit a raised --blob-timeout") {
    Main.resolveStallTimeout(1800, 1800, stallExplicit = false) shouldEqual Right(5400)
    Main.resolveStallTimeout(3600, 1800, stallExplicit = false) shouldEqual Right(10800)
  }

  test("an explicit window that is too small is refused, naming both values") {
    val bad = Main.resolveStallTimeout(1800, 1800, stallExplicit = true)
    bad.isLeft shouldBe true
    val why = bad.swap.getOrElse("")
    why should include("--stall-timeout=1800")
    why should include("--blob-timeout=1800")
    Main.resolveStallTimeout(600, 60, stallExplicit = true).isLeft shouldBe true
  }

  test("widening cannot overflow Int") {
    Main.resolveStallTimeout(Int.MaxValue, 1800, stallExplicit = false) shouldEqual Right(Int.MaxValue)
  }

  // -- which counters change the exit status ----------------------------------

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

  test("a failed blob does not change the exit status: it is excluded") {
    Main.exitStatus(stats(blobsTimedOut = 1)) shouldEqual 0
    Main.exitStatus(stats(blobsParserCrashed = 36)) shouldEqual 0
    Main.exitStatus(stats(blobsTimedOut = 1, blobsParserCrashed = 1, blobsDenylisted = 4,
      blobsOversized = 3)) shouldEqual 0
  }

  test("an abort still wins over everything") {
    Main.exitStatus(stats(aborted = true)) shouldEqual 2
    Main.exitStatus(stats(aborted = true, blobsTimedOut = 1, blobsDenylisted = 4)) shouldEqual 2
    Main.exitStatus(stats(aborted = true, blobsParserCrashed = 1)) shouldEqual 2
  }

  // run_pipeline_process.sh maps each status to its own remedy and marker file.
  test("the ineffective-retokenize status collides with no other blobExec exit status") {
    val others = Map(
      "clean"        -> 0,
      "usage"        -> 1,
      "aborted"      -> 2,
      "maskChanged"  -> 3,
      "stalled"      -> Walker.StalledExitStatus
    )
    others.foreach { case (name, status) =>
      withClue(s"ineffective-retokenize status must differ from $name ($status): ") {
        Main.RetokenizeIneffectiveExitStatus should not equal status
      }
    }
  }

  test("an ineffective --retokenize is not reported as a clean walk") {
    Main.RetokenizeIneffectiveExitStatus should not equal 0
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
