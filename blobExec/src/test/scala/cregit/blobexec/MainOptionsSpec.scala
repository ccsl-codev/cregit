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
  //
  // A pure-blob commit's only progress stamp is a blob finishing or being killed,
  // so the watchdog window must exceed the per-blob budget. The defaults (600 and
  // 1800) satisfy it; following the advice to raise --blob-timeout to 1800 without
  // touching the window would not, and a legitimately slow blob would then race
  // its own watchdog.

  test("a window larger than the budget is accepted unchanged") {
    Main.resolveStallTimeout(600, 1800, stallExplicit = false) shouldEqual Right(1800)
    Main.resolveStallTimeout(600, 1800, stallExplicit = true) shouldEqual Right(1800)
    Main.resolveStallTimeout(600, 601, stallExplicit = true) shouldEqual Right(601)
  }

  test("the defaults satisfy the relationship") {
    Main.resolveStallTimeout(
      BlobExec.DefaultTimeoutSeconds,
      Walker.DefaultStallTimeoutSeconds,
      stallExplicit = false
    ) shouldEqual Right(Walker.DefaultStallTimeoutSeconds)
  }

  test("a defaulted window is widened to fit a raised --blob-timeout") {
    // The exact collision the printed advice used to create: --blob-timeout=1800
    // against the 1800 default window.
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

  // -- which counters gate publication ----------------------------------------
  //
  // The denylist only earns its place if it does NOT gate the exit status: the
  // four blobs it holds hang srcml, the timeout path answers that with exit 4
  // ("incomplete, do not publish"), and a project cannot sit unpublishable
  // forever over a diagnosed third-party parser bug. A timeout is different — it
  // is a hang nobody has explained — and it must keep blocking.

  private def stats(
      aborted: Boolean = false,
      blobsTimedOut: Long = 0L,
      blobsOversized: Long = 0L,
      blobsDenylisted: Long = 0L
  ) = WalkStats(
    commitsProcessed = 1, commitsAlreadyMapped = 0, blobsRunThroughCommand = 1,
    blobsCacheHit = 0, refsProjected = 1, aborted = aborted,
    blobsTimedOut = blobsTimedOut, blobsOversized = blobsOversized,
    blobsDenylisted = blobsDenylisted, blobCommandExecutions = 1,
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
