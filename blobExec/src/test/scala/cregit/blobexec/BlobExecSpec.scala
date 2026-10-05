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
    val cmd = shellScript(s"echo 'partial tokens'; exit ${BlobExec.ParserCrashExitCode}")
    BlobExec.run("int main(){}".getBytes(UTF_8), sampleSha, "x.c", "src/x.c", cmd,
                 abortOnError = false, inserter,
                 onParserCrash = () => { crashes.incrementAndGet(); () }
    ) shouldBe BlobExec.Outcome.Skip
    crashes.get shouldEqual 1
  }

  test("a parser crash skips one blob even with abortOnError, like a timeout") {
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

    run(shellScript("tr a-z A-Z"))
    run(shellScript("exit 7"))
    crashes.get  shouldEqual 1
    timeouts.get shouldEqual 1
  }

  test("a child that never exits is killed at the timeout, not awaited forever") {
    // `sleep 30` is the wedged srcml chain: it reads nothing, writes nothing, and
    // holds its stdout open so the reader parks in pipe_read.
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

  test("an orphan grandchild holding the pipes cannot outlive the timeout") {
    // The real process shape, tokenBySha.pl -> sh -> srcml: killing only the
    // direct child leaves the background `sleep` holding this JVM's pipes.
    val cmd     = shellScript("sleep 30 & sleep 30")
    val started = System.currentTimeMillis()
    val (exit, _, _) = BlobExec.invoke(
      "irrelevant".getBytes(UTF_8), "0" * 40, "input.java", "input.java", cmd, timeoutSeconds = 1)
    val elapsed = System.currentTimeMillis() - started
    assert(elapsed < 10000, s"invoke took ${elapsed}ms; the process group was not killed")
    exit shouldEqual BlobExec.TimeoutExitCode
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

  test("the crash status blobExec recognises is the one tokenizeSrcMl.pl exits with") {
    val perl = new String(Files.readAllBytes(Paths.get("../tokenize/tokenizeSrcMl.pl")), UTF_8)
    val declared = """\$PARSER_CRASH_EXIT\s*=\s*(\d+)""".r.findFirstMatchIn(perl)
      .getOrElse(fail("no $PARSER_CRASH_EXIT in tokenize/tokenizeSrcMl.pl"))
      .group(1).toInt
    declared shouldEqual BlobExec.ParserCrashExitCode
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
