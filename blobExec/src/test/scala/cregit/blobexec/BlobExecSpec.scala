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

  test("zero exit, empty stdout against non-empty input → Skip, counted, no blob") {
    val crashes = new java.util.concurrent.atomic.AtomicInteger(0)
    val cmd = shellScript("cat > /dev/null; true")
    BlobExec.run("x".getBytes(UTF_8), sampleSha, "x.c", "src/x.c", cmd,
                 abortOnError = false, inserter,
                 onParserCrash = () => { crashes.incrementAndGet(); () }
    ) shouldBe BlobExec.Outcome.Skip
    crashes.get shouldEqual 1
  }

  test("zero exit, empty stdout against empty input → Skip, not counted a crash") {
    val crashes = new java.util.concurrent.atomic.AtomicInteger(0)
    val cmd = shellScript("cat > /dev/null; true")
    BlobExec.run(Array.emptyByteArray, sampleSha, "x.c", "src/x.c", cmd,
                 abortOnError = false, inserter,
                 onParserCrash = () => { crashes.incrementAndGet(); () }
    ) shouldBe BlobExec.Outcome.Skip
    crashes.get shouldEqual 0
  }

  test("the parser-crash exit status is Skip, counted, and never a Replace") {
    val crashes = new java.util.concurrent.atomic.AtomicInteger(0)
    // Output first, so the status is what rejects it, not the emptiness check.
    val cmd = shellScript(s"echo 'partial tokens'; exit ${BlobExec.ParserCrashExitCode}")
    BlobExec.run("int main(){}".getBytes(UTF_8), sampleSha, "x.c", "src/x.c", cmd,
                 abortOnError = false, inserter,
                 onParserCrash = () => { crashes.incrementAndGet(); () }
    ) shouldBe BlobExec.Outcome.Skip
    crashes.get shouldEqual 1
  }

  test("a parser crash skips one blob even with abortOnError, like a timeout") {
    // One crashing blob must not end the run. It gates publication through the exit
    // status instead (Main.exitStatus), which is where a timeout gates it too.
    val cmd = shellScript(s"exit ${BlobExec.ParserCrashExitCode}")
    BlobExec.run("int main(){}".getBytes(UTF_8), sampleSha, "x.c", "src/x.c", cmd,
                 abortOnError = true, inserter) shouldBe BlobExec.Outcome.Skip
  }

  test("a parser crash is counted separately from a timeout, and only when it happens") {
    val crashes  = new java.util.concurrent.atomic.AtomicInteger(0)
    val timeouts = new java.util.concurrent.atomic.AtomicInteger(0)
    def run(cmd: String, secs: Int = 30): BlobExec.Outcome =
      BlobExec.run("hello".getBytes(UTF_8), sampleSha, "x.c", "src/x.c", cmd,
                   abortOnError = false, inserter, timeoutSeconds = secs,
                   onTimeout     = () => { timeouts.incrementAndGet(); () },
                   onParserCrash = () => { crashes.incrementAndGet(); () })

    run(shellScript(s"exit ${BlobExec.ParserCrashExitCode}"))
    crashes.get  shouldEqual 1
    timeouts.get shouldEqual 0   // a crash is not a timeout: different remedy

    run(shellScript("sleep 30"), secs = 1)
    timeouts.get shouldEqual 1
    crashes.get  shouldEqual 1   // a timeout is not a crash

    // A healthy blob and an ordinary failure must leave both counts alone, or a
    // project would be held back from publication for nothing.
    run(shellScript("tr a-z A-Z"))
    run(shellScript("exit 7"))
    crashes.get  shouldEqual 1
    timeouts.get shouldEqual 1
  }

  test("a child that never exits is killed at its budget, not awaited forever") {
    // `sleep 30` is the wedged srcml chain: it reads nothing, writes nothing, and
    // holds its stdout open so the reader parks in pipe_read.
    val cmd     = shellScript("sleep 30")
    val started = System.currentTimeMillis()
    val outcome = new ChildRunner(1).run(cmd, "irrelevant".getBytes(UTF_8), Nil)
    val elapsed = System.currentTimeMillis() - started
    assert(elapsed < 10000, s"run took ${elapsed}ms; the budget did not fire")
    outcome shouldBe a[ChildRunner.Outcome.Killed]
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

  test("an orphan grandchild holding the pipes cannot outlive the budget") {
    // The real process shape, tokenBySha.pl -> sh -> srcml: killing only the
    // direct child leaves the background `sleep` holding this JVM's pipes.
    val cmd     = shellScript("sleep 30 & sleep 30")
    val started = System.currentTimeMillis()
    val outcome = new ChildRunner(1).run(cmd, "irrelevant".getBytes(UTF_8), Nil)
    val elapsed = System.currentTimeMillis() - started
    assert(elapsed < 10000, s"run took ${elapsed}ms; the descendant tree was not killed")
    outcome shouldBe a[ChildRunner.Outcome.Killed]
  }

  test("a descendant holding the pipe cannot park the call, nor forge output") {
    // perl's fork() guarantees the child inherits fd 1. A shell's `&` does not
    // reliably, which is why this does not use one.
    val cmd = shellScript(
      """exec perl -e 'if (fork() == 0) { sleep 10; exit 0 } print "partial"; exit 0'""")
    val started = System.currentTimeMillis()
    val outcome = new ChildRunner(30, drainGraceSeconds = 1).run(cmd, Array.emptyByteArray, Nil)
    val elapsed = System.currentTimeMillis() - started

    // The holder lives 10s. Either branch below is safe, and neither may wait for it.
    assert(elapsed < 5000, s"run took ${elapsed}ms: it waited for the descendant")
    outcome match {
      // EOF was reached, so the buffer holds everything the child wrote.
      case ChildRunner.Outcome.Exited(status, stdout, _) =>
        status shouldEqual 0
        new String(stdout, UTF_8) shouldEqual "partial"
      // The reader was still blocked, so no output is offered at all.
      case ChildRunner.Outcome.Killed(why) =>
        why should include("still open")
    }
  }

  test("a blob whose command leaves a descendant on the pipe is never an abort") {
    val cmd = shellScript(
      """exec perl -e 'if (fork() == 0) { sleep 10; exit 0 } print "partial"; exit 0'""")
    val started = System.currentTimeMillis()
    val outcome = BlobExec.run("original".getBytes(UTF_8), sampleSha, "x.c", "src/x.c", cmd,
                               abortOnError = true, inserter, timeoutSeconds = 30)
    assert(System.currentTimeMillis() - started < 20000, "the walk waited for the descendant")
    outcome should not be a[BlobExec.Outcome.Abort]
  }

  test("the environment reaches the child, and the exit status comes back") {
    val cmd = shellScript("""printf '%s' "$BFG_PATH"; exit 3""")
    inside(new ChildRunner(30).run(cmd, Array.emptyByteArray, Seq("BFG_PATH" -> "src/x.c"))) {
      case ChildRunner.Outcome.Exited(status, stdout, _) =>
        status shouldEqual 3
        new String(stdout, UTF_8) shouldEqual "src/x.c"
      case other => fail(s"expected Exited, got $other")
    }
  }

  test("a timeout is reported to the caller exactly once, and only on a timeout") {
    val timeouts = new java.util.concurrent.atomic.AtomicInteger(0)

    val hanging = shellScript("sleep 30")
    BlobExec.run("anything".getBytes(UTF_8), sampleSha, "x.c", "src/x.c", hanging,
                 abortOnError = false, inserter, timeoutSeconds = 1,
                 onTimeout = () => { timeouts.incrementAndGet(); () }) shouldBe BlobExec.Outcome.Skip
    timeouts.get shouldEqual 1

    // A healthy blob and an honestly failing one must leave the count alone,
    // or a project would be held back from publication for nothing.
    BlobExec.run("hello".getBytes(UTF_8), sampleSha, "x.c", "src/x.c", shellScript("tr a-z A-Z"),
                 abortOnError = false, inserter, timeoutSeconds = 30,
                 onTimeout = () => { timeouts.incrementAndGet(); () })
    BlobExec.run("hello".getBytes(UTF_8), sampleSha, "x.c", "src/x.c", shellScript("exit 7"),
                 abortOnError = false, inserter, timeoutSeconds = 30,
                 onTimeout = () => { timeouts.incrementAndGet(); () })
    timeouts.get shouldEqual 1
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
