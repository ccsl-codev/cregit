package cregit.blobexec

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.lib.Constants.OBJ_BLOB
import org.eclipse.jgit.lib.{ObjectId, ObjectInserter, Repository}
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.TreeWalk
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters._

/**
 * The default behaviour for a blob whose tokenizer fails: the blob is dropped
 * from the rewritten trees, exactly as a denylisted blob is, the walk continues,
 * and the blob is recorded in the skip file.
 *
 * The fixture has two commits. The first adds `a.c` and `deep/b.c`. The second
 * changes `a.c` only. `b.c` is the blob that fails. "The run continues" is then
 * checkable: the second commit is folded, and `a.c` is tokenized in both.
 *
 * The tokenizer is a shell script. For `b.c` it does what the test asks for
 * (exit 33 with the stderr of tokenizeSrcMl.pl, exit 0 with no output, or sleep
 * past the budget) while a marker file exists. Otherwise it upper-cases stdin.
 * Every call is logged, so the test can count the calls for `b.c`.
 *
 * TimeoutRetrySpec pins the strict behaviour (`strictTokenize = true`).
 */
class SkipFailedBlobSpec extends AnyFunSuite with Matchers with BeforeAndAfterAll {

  private val workRoot: Path = Files.createTempDirectory("skip-failed-")

  override def afterAll(): Unit = deleteRecursive(workRoot)

  private val Modes = Seq("serial", "pipeline", "pipeline-trees")

  /** What the tokenizer does for b.c while the marker exists. */
  private def failBody(kind: String): String = kind match {
    case "crash" =>
      """cat > /dev/null
        |echo "cregit: tokenization of [input.c] FAILED: srcml was killed by signal 11 (shell status 139)." >&2
        |exit 33""".stripMargin
    case "empty"   => "cat > /dev/null\nexit 0"
    case "timeout" => "sleep 300 & sleep 300"
    // "timeout-N": hangs on the first N calls, then tokenizes. The load that made
    // it slow is gone by call N+1.
    case k if k.startsWith("timeout-") =>
      val n = k.stripPrefix("timeout-").toInt
      s"""c=$$(cat "$$0.count" 2>/dev/null || echo 0); c=$$((c + 1)); echo $$c > "$$0.count"
         |if [ $$c -gt $n ]; then tr a-z A-Z; else sleep 300 & sleep 300; fi""".stripMargin
  }

  private final case class Fixture(
      dir: Path,
      src: Repository,
      firstCommit: ObjectId,
      secondCommit: ObjectId,
      bSha: String,
      marker: Path,
      calls: Path,
      command: String,
      tsv: Path
  ) {
    def dstPath: Path = dir.resolve("dst.git")
    def dbPath: Path  = dir.resolve("blobmap.db")
    def callsForB: Int =
      if (!Files.exists(calls)) 0
      else Files.readAllLines(calls).asScala.count(_ == "deep/b.c")
  }

  private def fixture(kind: String): Fixture = {
    val dir = Files.createTempDirectory(workRoot, s"$kind-")
    val srcDir = dir.resolve("src")
    Files.createDirectories(srcDir.resolve("deep"))
    val git = Git.init().setDirectory(srcDir.toFile).setBare(false).call()
    Files.writeString(srcDir.resolve("a.c"), "alpha\n")
    Files.writeString(srcDir.resolve("deep/b.c"), "beta\n")
    git.add().addFilepattern("a.c").call()
    git.add().addFilepattern("deep/b.c").call()
    val c1 = commit(git, "add a.c and deep/b.c")
    Files.writeString(srcDir.resolve("a.c"), "alpha two\n")
    git.add().addFilepattern("a.c").call()
    val c2 = commit(git, "change a.c only")

    val marker = dir.resolve("FAIL")
    Files.writeString(marker, "fail b.c\n")
    val calls = dir.resolve("calls.txt")
    val f = dir.resolve("tok.sh")
    Files.writeString(
      f,
      s"""|#!/bin/sh
          |printf '%s\\n' "$$BFG_PATH" >> "${calls.toAbsolutePath}"
          |if [ "$$BFG_FILENAME" = "b.c" ] && [ -e "${marker.toAbsolutePath}" ]; then
          |${failBody(kind)}
          |else
          |  tr a-z A-Z
          |fi
          |""".stripMargin
    )
    f.toFile.setExecutable(true)
    val src = git.getRepository
    val bSha = blobIdAt(src, c1, "deep/b.c").get.name
    Fixture(dir, src, c1, c2, bSha, marker, calls, f.toAbsolutePath.toString, dir.resolve("skipped.tsv"))
  }

  private def commit(git: Git, msg: String): ObjectId =
    git.commit().setMessage(msg)
      .setAuthor("Tester", "t@example.org").setCommitter("Tester", "t@example.org")
      .call().getId

  private def run(
      fx: Fixture,
      mode: String,
      strict: Boolean = false,
      denylist: BlobDenylist = BlobDenylist.empty,
      maxRetries: Int = 0,
      retryFactor: Int = 2,
      retryPass: Boolean = true,
      onRefold: () => Unit = () => ()
  ): WalkStats = {
    val dstExisted = Files.isDirectory(fx.dstPath)
    val dst = FileRepositoryBuilder.create(fx.dstPath.toFile).asInstanceOf[FileRepository]
    if (!dstExisted) dst.create(true)
    val mapping = Mapping.open(fx.dbPath, fx.command, """\.c$""")
    val log = SkipLog.open(fx.tsv)
    try
      new Walker(
        fx.src, dst, mapping, """\.c$""".r, fx.command,
        abortOnError = false,
        parallelism = 2,
        pipeline = mode == "pipeline",
        pipelineTrees = mode == "pipeline-trees",
        destinationMayContainObjects = dstExisted,
        blobTimeoutSeconds = 1,
        denylist = denylist,
        strictTokenize = strict,
        skipLog = log,
        tokenizerIdentity = TokenizerIdentity(Map("c" -> "0123456789abcdef")),
        maxRetries = maxRetries,
        timeoutRetryFactor = retryFactor,
        retryTimedOutPass = retryPass,
        onRefold = onRefold
      ).run()
    finally {
      log.close()
      mapping.close()
      dst.close()
    }
  }

  private def withMapping[A](fx: Fixture)(body: Mapping => A): A = {
    val m = Mapping.open(fx.dbPath, fx.command, """\.c$""")
    try body(m) finally m.close()
  }

  private def tsvLines(fx: Fixture): Vector[String] =
    Files.readAllLines(fx.tsv).asScala.toVector

  /** The rewritten commit's content at `path`, or None if the tree omits it. */
  private def dstContent(fx: Fixture, origCommit: ObjectId, path: String): Option[String] = {
    val newCommit = withMapping(fx)(_.getCommit(origCommit.name)).getOrElse(
      fail(s"commit ${origCommit.name} is not in commit_map"))
    val dst = FileRepositoryBuilder.create(fx.dstPath.toFile).asInstanceOf[FileRepository]
    try blobIdAt(dst, ObjectId.fromString(newCommit), path).map { id =>
      val r = dst.newObjectReader()
      try new String(r.open(id).getBytes, UTF_8) finally r.close()
    } finally dst.close()
  }

  /** The common assertions for a dropped b.c after run 1. */
  private def assertDropped(fx: Fixture, stats: WalkStats, reason: String, detail: String): Unit = {
    stats.aborted shouldBe false
    stats.commitsProcessed shouldEqual 2        // the run continued past the failure
    stats.blobsSkipped shouldEqual 1L
    Main.exitStatus(stats, strictTokenize = false) shouldEqual 0

    // Dropped, as a denylisted blob is: not raw source, not an empty blob.
    dstContent(fx, fx.firstCommit, "deep/b.c") shouldEqual None
    dstContent(fx, fx.secondCommit, "deep/b.c") shouldEqual None
    dstContent(fx, fx.firstCommit, "a.c") shouldEqual Some("ALPHA\n")
    dstContent(fx, fx.secondCommit, "a.c") shouldEqual Some("ALPHA TWO\n")
    withMapping(fx)(_.getBlob(fx.bSha, "deep/b.c")) shouldEqual None

    // The tokenizer is not asked a second time for the same blob in this run,
    // although the second commit reaches b.c again only through a tree_map hit.
    fx.callsForB shouldEqual 1

    tsvLines(fx) shouldEqual Vector(
      SkipLog.Header,
      s"${fx.bSha}\tdeep/b.c\t$reason\t$detail\tc=0123456789abcdef"
    )
  }

  for (mode <- Modes) {
    test(s"[$mode] a parser crash: dropped, recorded, the run continues, exit 0") {
      val fx = fixture("crash")
      val stats = run(fx, mode)
      stats.blobsParserCrashed shouldEqual 1L
      assertDropped(fx, stats, BlobExec.Failure.ParserCrash, "exit=33 signal=11")
      withMapping(fx)(_.retryBlobs) shouldBe empty   // a crash is not retried
    }

    test(s"[$mode] empty output for a non-empty input: dropped, recorded, exit 0") {
      val fx = fixture("empty")
      val stats = run(fx, mode)
      stats.blobsParserCrashed shouldEqual 1L
      assertDropped(fx, stats, BlobExec.Failure.EmptyOutput, "exit=0 input=5B output=0B")
      withMapping(fx)(_.retryBlobs) shouldBe empty
    }

    test(s"[$mode] a timeout: dropped and recorded, and NOT memoized as a permanent skip") {
      val fx = fixture("timeout")
      val first = run(fx, mode)
      first.blobsTimedOut shouldEqual 1L
      assertDropped(fx, first, BlobExec.Failure.Timeout, "timeout=1s")
      withMapping(fx)(_.retryBlobs) shouldEqual Vector((fx.bSha, "deep/b.c"))

      // Run 2 while b.c still hangs: tried again, dropped again, nothing folded
      // again, and no second row in the skip file.
      val second = run(fx, mode)
      second.blobsTimedOut shouldEqual 1L
      second.blobsRecovered shouldEqual 0L
      second.refolded shouldBe false
      second.commitsProcessed shouldEqual 0
      fx.callsForB shouldEqual 2
      tsvLines(fx).size shouldEqual 2
      withMapping(fx)(_.retryBlobs) shouldEqual Vector((fx.bSha, "deep/b.c"))

      // Run 3 with the load gone: b.c tokenizes, all of history is folded again
      // with b.c in it, and the record of the timeout goes.
      Files.delete(fx.marker)
      val third = run(fx, mode)
      third.blobsRecovered shouldEqual 1L
      third.refolded shouldBe true
      third.commitsProcessed shouldEqual 2
      third.blobsTimedOut shouldEqual 0L
      dstContent(fx, fx.firstCommit, "deep/b.c") shouldEqual Some("BETA\n")
      dstContent(fx, fx.secondCommit, "deep/b.c") shouldEqual Some("BETA\n")
      dstContent(fx, fx.secondCommit, "a.c") shouldEqual Some("ALPHA TWO\n")
      withMapping(fx)(_.retryBlobs) shouldBe empty
      tsvLines(fx) shouldEqual Vector(SkipLog.Header)
    }

    test(s"[$mode] strict mode: a parser crash stops the walk and exits 6, as before") {
      val fx = fixture("crash")
      val stats = run(fx, mode, strict = true)
      stats.blobsParserCrashed shouldEqual 1L
      stats.blobsSkipped shouldEqual 0L
      stats.commitsProcessed shouldEqual 0
      Main.exitStatus(stats, strictTokenize = true) shouldEqual Main.ParserCrashedExitStatus
      withMapping(fx)(_.getCommit(fx.firstCommit.name)) shouldEqual None
      // Strict mode records only exclusions (denylisted, oversized), and there
      // are none here, so the file is not created.
      Files.exists(fx.tsv) shouldBe false
    }
  }

  test("strict mode: a timeout stops the walk and exits 4, as before") {
    val fx = fixture("timeout")
    val stats = run(fx, "serial", strict = true)
    stats.commitsProcessed shouldEqual 0
    Main.exitStatus(stats, strictTokenize = true) shouldEqual Main.TimedOutExitStatus
    withMapping(fx)(_.retryBlobs) shouldBe empty
  }

  test("a crash is recorded once across a resumed run") {
    val fx = fixture("crash")
    run(fx, "serial")
    // A third commit puts the same b.c under a new path, next to a new file, so
    // the resume reaches a new tree and has to decide on the blob again. (Without
    // the new file, other/ would be the same tree as deep/, and a tree_map hit
    // would reuse the rewritten deep/ with no decision at all. The skip file then
    // names only the first path. The sha is the key that is always complete.)
    val git = Git.open(fx.src.getDirectory.getParentFile)
    try {
      val srcDir = fx.src.getDirectory.getParentFile.toPath
      Files.createDirectories(srcDir.resolve("other"))
      Files.writeString(srcDir.resolve("other/b.c"), "beta\n")
      Files.writeString(srcDir.resolve("other/c.c"), "gamma\n")
      git.add().addFilepattern("other/b.c").call()
      git.add().addFilepattern("other/c.c").call()
      commit(git, "copy b.c")
    } finally git.close()
    val second = run(fx, "serial")
    second.commitsProcessed shouldEqual 1
    val rows = tsvLines(fx)
    rows.head shouldEqual SkipLog.Header
    rows.tail.map(_.split("\t").take(3).mkString(" ")) shouldEqual Vector(
      s"${fx.bSha} deep/b.c parser-crash",
      s"${fx.bSha} other/b.c parser-crash"
    )
    // A third run over the same memo adds nothing.
    run(fx, "serial").commitsProcessed shouldEqual 0
    tsvLines(fx) shouldEqual rows
  }

  test("a denylisted blob is recorded in the skip file, with its reason and citation") {
    val fx = fixture("crash")
    Files.delete(fx.marker)
    val denylist = BlobDenylist.parse(
      Vector(s"${fx.bSha}\tsrcML/srcML#2361\tsrcML 1.1.0 does not terminate on it"), "fixture")
    val stats = run(fx, "serial", denylist = denylist)
    stats.blobsDenylisted shouldEqual 1L
    stats.blobsSkipped shouldEqual 0L
    fx.callsForB shouldEqual 0
    tsvLines(fx) shouldEqual Vector(
      SkipLog.Header,
      s"${fx.bSha}\tdeep/b.c\tdenylisted\treason=srcML 1.1.0 does not terminate on it; " +
        "citation=srcML/srcML#2361\tc=0123456789abcdef"
    )
    // Copy one real row to the build output, so the report can show it.
    Files.writeString(java.nio.file.Paths.get("target", "skipfail-example.tsv"),
      tsvLines(fx).mkString("", "\n", "\n"))
  }

  test("a skip file with a different header is refused") {
    val p = Files.createTempFile(workRoot, "bad-", ".tsv")
    Files.writeString(p, "sha\tpath\n")
    an[IllegalArgumentException] should be thrownBy SkipLog.open(p)
  }

  test("the crash detail falls back to the FAILED line, then to the exit code") {
    BlobExec.Failure.crashDetail(33, "cregit: tokenization of [x.c] FAILED: srcml exited 2.\n") shouldEqual
      "exit=33 srcml exited 2."
    BlobExec.Failure.crashDetail(33, "") shouldEqual "exit=33"
  }

  // -- retries in the same run ---------------------------------------------------
  //
  // --blob-timeout is 1s here and the factor is 2, so each retry has 2s.

  for (mode <- Modes; (hangs, retry) <- Seq((1, 1), (3, 3))) {
    test(s"[$mode] a timeout recovered on retry $retry of 3 is not dropped and not recorded") {
      val fx = fixture(s"timeout-$hangs")
      val stats = run(fx, mode, maxRetries = 3)
      stats.blobsTimeoutRetriedInRun shouldEqual retry.toLong   // retry attempts
      stats.blobsTimeoutRecoveredInRun shouldEqual 1L            // blobs recovered
      stats.blobsTimedOut shouldEqual 0L
      stats.blobsSkipped shouldEqual 0L
      stats.commitsProcessed shouldEqual 2
      fx.callsForB shouldEqual 1 + retry                         // it stopped at the success
      dstContent(fx, fx.firstCommit, "deep/b.c") shouldEqual Some("BETA\n")
      dstContent(fx, fx.secondCommit, "deep/b.c") shouldEqual Some("BETA\n")
      withMapping(fx)(_.retryBlobs) shouldBe empty
      Files.exists(fx.tsv) shouldBe false   // nothing to record
      // A recovered blob does not make the strict exit status 4.
      Main.exitStatus(stats, strictTokenize = true) shouldEqual 0
    }
  }

  for (mode <- Modes)
  test(s"[$mode] a timeout on all 4 attempts is dropped, and the record lists the attempts") {
    val fx = fixture("timeout-4")
    val stats = run(fx, mode, maxRetries = 3)
    stats.blobsTimeoutRetriedInRun shouldEqual 3L
    stats.blobsTimeoutRecoveredInRun shouldEqual 0L
    stats.blobsTimedOut shouldEqual 1L
    fx.callsForB shouldEqual 4
    withMapping(fx)(_.retryBlobs) shouldEqual Vector((fx.bSha, "deep/b.c"))
    dstContent(fx, fx.firstCommit, "deep/b.c") shouldEqual None
    tsvLines(fx) shouldEqual Vector(
      SkipLog.Header,
      s"${fx.bSha}\tdeep/b.c\ttimeout\ttimeout=1s retries=3x2s\tc=0123456789abcdef")
    Main.exitStatus(stats, strictTokenize = false) shouldEqual 0
  }

  test("strict mode: a timeout on all attempts stops the walk and exits 4") {
    val fx = fixture("timeout-4")
    val stats = run(fx, "serial", strict = true, maxRetries = 3)
    fx.callsForB shouldEqual 4
    stats.commitsProcessed shouldEqual 0
    stats.blobsTimedOut shouldEqual 1L
    Main.exitStatus(stats, strictTokenize = true) shouldEqual Main.TimedOutExitStatus
  }

  test("strict mode: a timeout recovered on a retry does not stop the walk") {
    val fx = fixture("timeout-2")
    val stats = run(fx, "serial", strict = true, maxRetries = 3)
    stats.commitsProcessed shouldEqual 2
    Main.exitStatus(stats, strictTokenize = true) shouldEqual 0
  }

  test("--max-retries=0 gives no retry") {
    val fx = fixture("timeout-1")
    val stats = run(fx, "serial", maxRetries = 0)
    stats.blobsTimeoutRetriedInRun shouldEqual 0L
    stats.blobsSkipped shouldEqual 1L
    fx.callsForB shouldEqual 1
    tsvLines(fx).last.split("\t")(3) shouldEqual "timeout=1s"
  }

  test("the default is no retry, and the stall window then stays at 3 x --blob-timeout") {
    Main.DefaultMaxRetries shouldEqual 0
    Main.Usage should include("default 0: a timeout skips and records the blob")
    // With the default, a defaulted window stays at 1800s (30 min) for 600s blobs.
    Main.resolveStallWindow(600, Main.DefaultMaxRetries, Main.DefaultTimeoutRetryFactor,
      600, 1800, stallExplicit = false) shouldEqual Right(1800)
    // With an explicit --max-retries=3 it grows to 8420s (about 2 h 20 min).
    Main.resolveStallWindow(600, 3, Main.DefaultTimeoutRetryFactor,
      600, 1800, stallExplicit = false) shouldEqual Right(8420)
  }

  test("with the default, a timeout skips and records the blob at once") {
    val fx = fixture("timeout-1")
    val stats = run(fx, "serial", maxRetries = Main.DefaultMaxRetries)
    stats.blobsTimeoutRetriedInRun shouldEqual 0L
    stats.blobsSkipped shouldEqual 1L
    fx.callsForB shouldEqual 1
    tsvLines(fx).last.split("\t")(3) shouldEqual "timeout=1s"
  }

  test("an explicit --max-retries=3 still retries") {
    val fx = fixture("timeout-1")
    val stats = run(fx, "serial", maxRetries = 3)
    stats.blobsTimeoutRetriedInRun shouldEqual 1L
    stats.blobsTimeoutRecoveredInRun shouldEqual 1L
    stats.blobsSkipped shouldEqual 0L
    fx.callsForB shouldEqual 2
  }

  test("a factor of 0 or 1 gives each retry the first budget") {
    val fx = fixture("timeout-4")
    run(fx, "serial", maxRetries = 2, retryFactor = 1)
    fx.callsForB shouldEqual 3
    tsvLines(fx).last.split("\t")(3) shouldEqual "timeout=1s retries=2x1s"
  }

  test("a parser crash gets no retry, whatever --max-retries says") {
    val fx = fixture("crash")
    val stats = run(fx, "serial", maxRetries = 3)
    stats.blobsTimeoutRetriedInRun shouldEqual 0L
    stats.blobsParserCrashed shouldEqual 1L
    fx.callsForB shouldEqual 1
  }

  // -- the retry pass across runs ----------------------------------------------

  test("with the retry pass off, a timed-out blob stays dropped and keeps its rows") {
    val fx = fixture("timeout")
    run(fx, "serial")
    Files.delete(fx.marker)          // it would tokenize now
    val before = tsvLines(fx)

    val held = run(fx, "serial", retryPass = false)
    held.blobsRecovered shouldEqual 0L
    held.refolded shouldBe false
    held.commitsProcessed shouldEqual 0
    fx.callsForB shouldEqual 1       // not given to the tokenizer again
    withMapping(fx)(_.retryBlobs) shouldEqual Vector((fx.bSha, "deep/b.c"))
    tsvLines(fx) shouldEqual before
    dstContent(fx, fx.secondCommit, "deep/b.c") shouldEqual None

    // A later run with the pass on retries it, as before.
    val retried = run(fx, "serial")
    retried.blobsRecovered shouldEqual 1L
    retried.refolded shouldBe true
    dstContent(fx, fx.secondCommit, "deep/b.c") shouldEqual Some("BETA\n")
    withMapping(fx)(_.retryBlobs) shouldBe empty
  }

  test("a run that stops after the first recovery still re-folds next time, and has the marker") {
    val fx = fixture("timeout")
    run(fx, "serial")
    Files.delete(fx.marker)
    // A row that sorts after b.c's and cannot be read: the retry pass throws there.
    val bad = "z" * 40
    withMapping(fx)(_.putRetry(bad, "deep/x.c"))
    val refoldMarker = fx.dir.resolve("REFOLDED")
    an[IllegalArgumentException] should be thrownBy
      run(fx, "serial", onRefold = () => Files.writeString(refoldMarker, "x"))
    Files.exists(refoldMarker) shouldBe true
    withMapping(fx)(_.deleteRetry(bad, "deep/x.c"))

    val next = run(fx, "serial")
    next.commitsProcessed shouldEqual 2
    dstContent(fx, fx.secondCommit, "deep/b.c") shouldEqual Some("BETA\n")
    withMapping(fx)(_.retryBlobs) shouldBe empty
  }

  test("with the retry pass off, a new commit that holds the held blob drops it too") {
    val fx = fixture("timeout")
    run(fx, "serial")
    Files.delete(fx.marker)
    val git = Git.open(fx.src.getDirectory.getParentFile)
    try {
      val srcDir = fx.src.getDirectory.getParentFile.toPath
      Files.writeString(srcDir.resolve("deep/d.c"), "delta\n")
      git.add().addFilepattern("deep/d.c").call()
      commit(git, "a new file next to b.c")
    } finally git.close()
    val held = run(fx, "serial", retryPass = false)
    held.commitsProcessed shouldEqual 1
    fx.callsForB shouldEqual 1
    withMapping(fx)(_.retryBlobs) shouldEqual Vector((fx.bSha, "deep/b.c"))
  }

  // -- the load gate -------------------------------------------------------------

  test("the load gate waits while the load is above the limit, and no longer than its cap") {
    val f = Files.createTempFile(workRoot, "loadavg-", "")
    Files.writeString(f, "99.00 50.00 20.00 3/900 12345\n")
    var polls = 0
    val gate = LoadGate(limit = 4.0, maxWaitSeconds = 2, pollSeconds = 1, loadavg = f)
    val t0 = System.nanoTime()
    gate.await(_ => polls += 1)
    val secs = (System.nanoTime() - t0) / 1e9
    secs should be >= 1.9
    secs should be < 10.0
    polls should be >= 2

    Files.writeString(f, "0.50 0.40 0.30 1/900 12345\n")
    gate.await(_ => fail("no wait at a low load")) shouldEqual 0L
    LoadGate(4.0, 2, 1, workRoot.resolve("no-such-file")).await(_ => fail("no file, no wait")) shouldEqual 0L
    LoadGate.disabled.await(_ => fail("disabled")) shouldEqual 0L
  }

  test("the longest time for one blob includes every retry and its load wait") {
    // Defaults: 600s, then 3 x (600s wait + 1800s retry), plus 5s grace x 4 attempts.
    Walker.longestBlobSeconds(600, 3, 3, 600) shouldEqual 7820L
    Walker.longestBlobSeconds(600, 0, 3, 600) shouldEqual 605L
    Walker.retryBudget(600, 3) shouldEqual 1800
    Walker.retryBudget(600, 0) shouldEqual 600
  }

  test("the stall window with retries: a defaulted one is raised, an explicit one too small is refused") {
    // Defaulted: raised to the longest time plus one --blob-timeout.
    Main.resolveStallWindow(600, 3, 3, 600, 1800, stallExplicit = false) shouldEqual Right(8420)
    // Explicit and large enough: kept.
    Main.resolveStallWindow(600, 3, 3, 600, 9000, stallExplicit = true) shouldEqual Right(9000)
    // Explicit and too small: refused, naming the settings that make it too small.
    val bad = Main.resolveStallWindow(600, 3, 3, 600, 7000, stallExplicit = true)
    bad.isLeft shouldBe true
    Seq("--max-retries 3", "--timeout-retry-factor 3", "--load-wait-max", "7820s").foreach { part =>
      bad.left.getOrElse("") should include(part)
    }
    // Without retries the old rule holds, unchanged.
    Main.resolveStallWindow(600, 0, 3, 600, 1800, stallExplicit = false) shouldEqual Right(1800)
    Main.resolveStallWindow(600, 0, 3, 600, 601, stallExplicit = true) shouldEqual Right(601)
  }

  test("the default load limit is twice the processor count") {
    Main.defaultLoadLimit(16) shouldEqual 32.0
    LoadGate.DefaultMaxWaitSeconds shouldEqual 600
  }

  // -- helpers ---------------------------------------------------------------

  private def blobIdAt(repo: Repository, commitId: ObjectId, path: String): Option[ObjectId] = {
    val rw = new RevWalk(repo)
    try {
      val tree = rw.parseCommit(commitId).getTree
      Option(TreeWalk.forPath(repo, path, tree)).map { tw =>
        try tw.getObjectId(0) finally tw.close()
      }
    } finally rw.close()
  }

  private def deleteRecursive(p: Path): Unit = {
    if (Files.isDirectory(p)) {
      val s = Files.list(p)
      try s.forEach(deleteRecursive) finally s.close()
    }
    Files.deleteIfExists(p)
    ()
  }
}
