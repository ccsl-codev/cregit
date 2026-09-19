package cregit.blobexec

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** `--blob-timeout` is a binding part of the spec: default 600, and values that
  * are not a positive whole number of seconds must be rejected rather than
  * silently turned into "no timeout". The flag's value parser is tested here;
  * `main` itself cannot be, because it answers bad input with `sys.exit`. */
class MainOptionsSpec extends AnyFunSuite with Matchers {

  test("the default per-blob budget is 600 seconds") {
    BlobExec.DefaultTimeoutSeconds shouldEqual 600
  }

  test("--blob-timeout accepts positive whole seconds") {
    Main.parseBlobTimeout("600") shouldEqual Some(600)
    Main.parseBlobTimeout("1") shouldEqual Some(1)
    Main.parseBlobTimeout("86400") shouldEqual Some(86400)
  }

  test("--blob-timeout rejects zero and negatives: a blob must always be bounded") {
    Main.parseBlobTimeout("0") shouldEqual None
    Main.parseBlobTimeout("-1") shouldEqual None
    Main.parseBlobTimeout("-600") shouldEqual None
  }

  test("--blob-timeout rejects non-numeric and empty values") {
    Main.parseBlobTimeout("abc") shouldEqual None
    Main.parseBlobTimeout("") shouldEqual None
    Main.parseBlobTimeout("600s") shouldEqual None
    Main.parseBlobTimeout("10.5") shouldEqual None
  }

  test("a timed-out run has its own exit status, distinct from abort and usage") {
    Main.TimedOutExitStatus shouldEqual 4
    Set(0, 1, 2, 3) should not contain Main.TimedOutExitStatus
  }
}
