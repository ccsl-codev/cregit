package cregit.blobexec

import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.lib.{ObjectId, ObjectInserter}
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}

class BlobExecSpec extends AnyFunSuite with Matchers with BeforeAndAfterAll {

  private val tmpDir: Path = Files.createTempDirectory("blobexec-spec-")
  private var repo: FileRepository = _
  private var inserter: ObjectInserter = _

  override def beforeAll(): Unit = {
    val gitDir = tmpDir.resolve("repo.git")
    repo = FileRepositoryBuilder
      .create(gitDir.toFile)
      .asInstanceOf[FileRepository]
    repo.create(true)
    inserter = repo.newObjectInserter()
  }

  override def afterAll(): Unit = {
    if (inserter ne null) inserter.close()
    if (repo ne null) repo.close()
    deleteRecursive(tmpDir)
  }

  /** Write `script` to a file, mark executable, return the absolute path. */
  private def shellScript(script: String): String = {
    val f = Files.createTempFile(tmpDir, "cmd-", ".sh")
    Files.writeString(f, "#!/bin/sh\n" + script)
    f.toFile.setExecutable(true)
    f.toAbsolutePath.toString
  }

  private val sampleSha = "a" * 40

  test("identical output → Skip, no inserter activity") {
    val cmd = shellScript("cat")
    BlobExec.run("hello".getBytes(UTF_8), sampleSha, "x.c", "src/x.c", cmd,
                 abortOnError = false, inserter) shouldBe BlobExec.Outcome.Skip
  }

  test("different output → Replace with new blob id") {
    val cmd = shellScript("tr a-z A-Z")
    val out = BlobExec.run("hello".getBytes(UTF_8), sampleSha, "x.c", "src/x.c", cmd,
                           abortOnError = false, inserter)
    inside(out) {
      case BlobExec.Outcome.Replace(newId) =>
        // The new blob must actually be present in the repo's ODB.
        val r = repo.newObjectReader()
        try {
          val l = r.open(newId)
          new String(l.getBytes, UTF_8) shouldEqual "HELLO"
        } finally r.close()
      case other =>
        fail(s"expected Replace, got $other")
    }
  }

  test("non-zero exit, abortOnError=false → Skip") {
    val cmd = shellScript("exit 7")
    BlobExec.run("anything".getBytes(UTF_8), sampleSha, "x.c", "src/x.c", cmd,
                 abortOnError = false, inserter) shouldBe BlobExec.Outcome.Skip
  }

  test("non-zero exit, abortOnError=true → Abort with exit code + stderr") {
    val cmd = shellScript("echo bad >&2; exit 9")
    val out = BlobExec.run("anything".getBytes(UTF_8), sampleSha, "x.c", "src/x.c", cmd,
                           abortOnError = true, inserter)
    inside(out) {
      case BlobExec.Outcome.Abort(stderr, code) =>
        code shouldEqual 9
        stderr should include("bad")
      case other =>
        fail(s"expected Abort, got $other")
    }
  }

  test("env vars BFG_BLOB, BFG_FILENAME, BFG_PATH are passed through") {
    // Script writes the env values to stdout joined by '|'; the blob will
    // therefore differ from input, giving us a Replace whose bytes we read.
    val cmd = shellScript("""printf '%s|%s|%s' "$BFG_BLOB" "$BFG_FILENAME" "$BFG_PATH"""")
    val origSha = "0" * 40
    val outcome = BlobExec.run("ignored".getBytes(UTF_8), origSha, "main.c", "src/lib/main.c",
                               cmd, abortOnError = false, inserter)
    inside(outcome) {
      case BlobExec.Outcome.Replace(newId) =>
        val r = repo.newObjectReader()
        try {
          new String(r.open(newId).getBytes, UTF_8) shouldEqual s"$origSha|main.c|src/lib/main.c"
        } finally r.close()
      case other =>
        fail(s"expected Replace, got $other")
    }
  }

  test("zero exit, empty stdout against non-empty input → Replace with empty blob") {
    val cmd = shellScript("cat > /dev/null; true")
    val outcome = BlobExec.run("x".getBytes(UTF_8), sampleSha, "x.c", "src/x.c", cmd,
                               abortOnError = false, inserter)
    inside(outcome) {
      case BlobExec.Outcome.Replace(newId) =>
        val r = repo.newObjectReader()
        try {
          r.open(newId).getBytes.length shouldEqual 0
        } finally r.close()
      case other =>
        fail(s"expected Replace, got $other")
    }
  }

  test("a child that never exits is killed at the timeout, not awaited forever") {
    // `sleep 30` stands in for the wedged srcml chain: it reads nothing and
    // writes nothing, and it holds its stdout open so the reader thread parks
    // in pipe_read. With a 1 second budget the call must return quickly.
    val cmd     = shellScript("sleep 30")
    val started = System.currentTimeMillis()
    val (exit, _, _) = BlobExec.invoke(
      "irrelevant".getBytes(UTF_8),
      "0" * 40,
      "input.java",
      "input.java",
      cmd,
      timeoutSeconds = 1
    )
    val elapsed = System.currentTimeMillis() - started
    assert(elapsed < 10000, s"invoke took ${elapsed}ms; the timeout did not fire")
    exit shouldEqual BlobExec.TimeoutExitCode
  }

  test("a timed-out blob is skipped, never tokenized and never an abort") {
    // abortOnError=true is the hostile case: a timeout must still skip exactly
    // one blob rather than taking the whole run down, and it must never be
    // mistaken for a successful tokenization (Replace).
    val cmd = shellScript("sleep 30")
    val out = BlobExec.run("anything".getBytes(UTF_8), sampleSha, "x.c", "src/x.c", cmd,
                           abortOnError = true, inserter, timeoutSeconds = 1)
    out shouldBe BlobExec.Outcome.Skip
  }

  // tiny `inside` helper to keep the test bodies readable
  private def inside[T](v: T)(pf: PartialFunction[T, Unit]): Unit =
    if (pf.isDefinedAt(v)) pf(v) else fail(s"value did not match: $v")

  private def deleteRecursive(p: Path): Unit = {
    if (Files.isDirectory(p)) {
      val s = Files.list(p)
      try s.forEach(deleteRecursive) finally s.close()
    }
    Files.deleteIfExists(p)
    ()
  }
}
