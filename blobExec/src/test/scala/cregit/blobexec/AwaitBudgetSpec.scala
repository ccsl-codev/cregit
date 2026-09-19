package cregit.blobexec

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** The per-commit await budget replaced `Duration.Inf`. Its shape is load
  * bearing in both directions: too small and a healthy multi-thousand-blob
  * commit is cut off mid-flight, too large and the backstop is decorative. The
  * formula is `(2 * ceil(blobCount / parallelism) + 10) * blobTimeoutSeconds`,
  * clamped, and these tests pin each part of that. */
class AwaitBudgetSpec extends AnyFunSuite with Matchers {

  private def budget(blobs: Int, p: Int = 16, t: Int = 600): Long =
    Walker.awaitBudgetSeconds(blobs, p, t)

  test("a single blob still gets the fixed floor, not one blob's timeout") {
    // 1 wave + 10 => 12 * 600s. The floor covers blob reads and inserter flushes
    // that the per-blob budget does not.
    budget(1) shouldEqual 12L * 600L
  }

  test("an empty commit gets the floor alone") {
    budget(0) shouldEqual 10L * 600L
  }

  test("the wave count rounds up, so the last partial wave is not cut off") {
    // 16 blobs on 16 threads is one wave; 17 needs a second.
    budget(16) shouldEqual 12L * 600L
    budget(17) shouldEqual 14L * 600L
  }

  test("the budget never decreases as a commit gets bigger") {
    val counts  = Seq(0, 1, 15, 16, 17, 100, 1000, 50663)
    val budgets = counts.map(budget(_))
    budgets shouldEqual budgets.sorted
  }

  test("more parallelism means fewer waves, never more") {
    budget(1000, p = 1) should be >= budget(1000, p = 16)
    budget(1000, p = 16) shouldEqual (2L * 63L + 10L) * 600L
  }

  test("a bigger per-blob timeout scales the budget with it") {
    budget(100, t = 60) * 10 shouldEqual budget(100, t = 600)
  }

  test("degenerate parallelism and timeout are floored at 1, never zero") {
    budget(10, p = 0) shouldEqual budget(10, p = 1)
    budget(10, p = -4) shouldEqual budget(10, p = 1)
    budget(10, t = 0) shouldEqual budget(10, t = 1)
    budget(10, t = -1) shouldEqual budget(10, t = 1)
  }

  test("the budget is clamped, so the arithmetic cannot overflow Duration") {
    budget(Int.MaxValue, p = 1, t = Int.MaxValue) shouldEqual Walker.MaxAwaitBudgetSeconds
    budget(Int.MaxValue) shouldEqual Walker.MaxAwaitBudgetSeconds
    budget(1) should be < Walker.MaxAwaitBudgetSeconds
  }
}
