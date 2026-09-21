package cregit.blobexec

import org.eclipse.jgit.lib.Constants.OBJ_BLOB
import org.eclipse.jgit.lib.{ObjectId, ObjectInserter}

import java.io.{InputStream, OutputStream}
import java.nio.charset.StandardCharsets
import java.util.{Arrays => JavaArrays}
import scala.io.Source
import scala.sys.process.{Process, ProcessIO}

/**
 * Run an external per-blob command and return the resulting `ObjectId`.
 *
 * Pure helper carved out of the old `BlobExecModifier`. Contract:
 *
 *   - env `BFG_BLOB`     = orig blob 40-hex
 *   - env `BFG_FILENAME` = blob basename (preserved for backward
 *                          compatibility with cregit's `tokenBySha.pl`,
 *                          which keys its on-disk memo by basename)
 *   - env `BFG_PATH`     = blob's full repo-root relative path
 *                          (e.g. `src/lib/foo.c`). Enables tokenizers to
 *                          make path-aware decisions.
 *   - stdin              = original blob bytes
 *   - stdout             = replacement blob bytes
 *   - exit != 0          → `Skip` (or `Abort` if `abortOnError`)
 *   - timeout            → child killed, `Skip` (never `Abort`, never `Replace`)
 *   - stdout == stdin    → `Skip` (no inserter activity)
 *   - otherwise          → `Replace(newBlob)`
 */
object BlobExec {

  /** Per-blob wall-clock budget for the external command, in seconds. */
  val DefaultTimeoutSeconds: Int = 600

  /** Synthetic exit code reported by [[invoke]] when the child was killed for
    * exceeding its budget. A real child can never produce it: on Unix a waited
    * status is 0..255 (128+signal when killed), so negative values are free. */
  val TimeoutExitCode: Int = -1

  /** "the waiter never observed an exit status" — distinct from
    * [[TimeoutExitCode]] so an interrupted wait can never be mistaken for a
    * completed one, nor a completed one for a timeout. */
  private val NotExitedSentinel: Int = Int.MinValue

  /** Seconds GNU `timeout` waits between its SIGTERM and its SIGKILL (`-k`). */
  private val KillGraceSeconds: Int = 5

  /** Slack on the JVM-side backstop latch beyond the child's own budget plus
    * the kill grace. The latch should only ever fire if `timeout` itself is
    * missing or wedged. */
  private val BackstopSlackSeconds: Int = 25

  /** Longest a single [[invoke]] can take: the child's own budget, the kill
    * grace, and the JVM-side backstop latch. A healthy run can therefore go
    * this long with no blob completing, which is what the stall watchdog's
    * window has to clear — see [[Walker.stallFloorFor]]. */
  private[blobexec] def maxChildLifetimeSeconds(timeoutSeconds: Int): Int =
    math.max(1, timeoutSeconds) + KillGraceSeconds + BackstopSlackSeconds

  /** GNU `timeout`'s own statuses: 124 = the budget expired, 137 = 128+SIGKILL,
    * i.e. the command ignored SIGTERM and needed the `-k` follow-up. Both mean
    * "we killed it", and both must reach the Skip branch rather than the
    * `exitCode != 0` branch, which `--abort-on-error` turns into a run abort. */
  private val TimeoutStatuses: Set[Int] = Set(124, 137)

  /** GNU `timeout`, if it is on PATH. It is the only cheap way to kill the
    * whole child *process group*: `Process.destroy()` signals the direct child's
    * pid alone, and the tokenizer chain is `tokenBySha.pl` -> `sh` -> `srcml`,
    * so the grandchildren survive, inherit the JVM's stderr pipe, and keep the
    * reader thread blocked — which is how one wedged blob wedged a whole run.
    * Resolved once; the warning is therefore printed at most once per process. */
  private lazy val gnuTimeout: Option[String] = {
    val found = sys.env
      .getOrElse("PATH", "")
      .split(java.io.File.pathSeparatorChar)
      .iterator
      .filter(_.nonEmpty)
      .map(dir => java.nio.file.Paths.get(dir, "timeout"))
      .find(java.nio.file.Files.isExecutable)
      .map(_.toString)
    if (found.isEmpty) {
      System.err.println(
        "blobExec: warning: GNU `timeout` was not found on PATH. Per-blob budgets " +
          "still apply, but a killed child's grandchildren can survive and hold the " +
          "pipe open. Install coreutils to get process-group kills."
      )
    }
    found
  }

  sealed trait Outcome
  object Outcome {
    case object Skip                          extends Outcome
    final case class Replace(newBlob: ObjectId) extends Outcome
    final case class Abort(stderr: String, exitCode: Int) extends Outcome
  }

  /**
   * Run `command` against `bytes`. Pure aside from the JVM process and the
   * (single-threaded-per-call) jgit inserter. Thread-safe so long as
   * `inserter` is confined to the calling thread.
   */
  def run(
      bytes: Array[Byte],
      origSha: String,
      filename: String,
      fullPath: String,
      command: String,
      abortOnError: Boolean,
      inserter: ObjectInserter,
      timeoutSeconds: Int = DefaultTimeoutSeconds,
      onTimeout: () => Unit = () => ()
  ): Outcome = {
    val (exitCode, stdout, stderr) = invoke(bytes, origSha, filename, fullPath, command, timeoutSeconds)

    if (exitCode == TimeoutExitCode) {
      // A timed-out child skips exactly one blob. Deliberately *not* routed
      // through `abortOnError`: one wedged tokenizer must never take down a
      // run that has already folded thousands of commits, and its (discarded,
      // possibly truncated) stdout must never be mistaken for a tokenization.
      // `onTimeout` is how the caller counts it: a Skip that leaves raw source
      // where tokens belong must not be invisible to the run's statistics.
      System.err.println(
        s"Warning: command [$command] timed out after ${timeoutSeconds}s on blob $origSha " +
          s"at path [$fullPath]: child killed, blob left untokenized"
      )
      onTimeout()
      Outcome.Skip
    } else if (exitCode != 0) {
      logError(command, origSha, fullPath, exitCode, stderr)
      if (abortOnError) Outcome.Abort(stderr, exitCode) else Outcome.Skip
    } else if (JavaArrays.equals(bytes, stdout)) {
      Outcome.Skip
    } else {
      Outcome.Replace(inserter.insert(OBJ_BLOB, stdout))
    }
  }

  /** Visible for testing. Runs the process and returns (exit, stdout, stderr).
    *
    * The child is bounded by `timeoutSeconds`; on expiry its whole process
    * group is killed (via GNU `timeout`) and [[TimeoutExitCode]] is returned
    * with empty stdout/stderr. */
  private[blobexec] def invoke(
      bytes: Array[Byte],
      origSha: String,
      filename: String,
      fullPath: String,
      command: String,
      timeoutSeconds: Int = DefaultTimeoutSeconds
  ): (Int, Array[Byte], String) = {
    val stdoutBuilder = new java.io.ByteArrayOutputStream(math.max(bytes.length, 1024))
    val stderrBuilder = new StringBuilder

    val readStdout: InputStream => Unit = in => {
      try transfer(in, stdoutBuilder) finally in.close()
    }
    val writeStdin: OutputStream => Unit = out => {
      try { out.write(bytes); out.flush() } finally out.close()
    }
    val readStderr: InputStream => Unit = err => {
      val src = Source.fromInputStream(err, StandardCharsets.UTF_8.name)
      try stderrBuilder.append(src.mkString) finally src.close()
    }

    // daemonizeThreads = true: the stdout/stderr readers must not keep the JVM
    // alive. `exitValue()` still joins them on the healthy path, so this only
    // matters at JVM exit — and on the timeout path they are deliberately
    // abandoned while blocked in `FileInputStream.readBytes`. Left non-daemon,
    // two such threads kept the process running after the walk had finished,
    // reintroducing the very stall this timeout exists to remove.
    val io = new ProcessIO(writeStdin, readStdout, readStderr, daemonizeThreads = true)

    val secs = math.max(1, timeoutSeconds)
    // Prefer the kernel over a pid-by-pid kill: GNU `timeout` runs the command
    // in its own process group and signals the *group* on expiry, so `sh`,
    // `tokenize.pl`, `srcml` and `srcml2token` all die together instead of
    // orphaning themselves onto the JVM's pipes.
    val argv = gnuTimeout match {
      case Some(bin) => Seq(bin, "-k", KillGraceSeconds.toString, secs.toString, command)
      case None      => Seq(command)
    }
    val groupKilled = gnuTimeout.isDefined
    val pb = Process(
      argv,
      None,
      "BFG_BLOB"     -> origSha,
      "BFG_FILENAME" -> filename,
      "BFG_PATH"     -> fullPath
    )
    val proc = pb.run(io)

    // A stuck srcml/ctags used to park the caller forever: the child stops
    // producing output but holds its stdout open, the reader thread blocks in
    // pipe_read, `exitValue()` (which joins the io threads) never returns, and
    // the whole project goes silent. Bound the child instead of trusting it to
    // exit. `exitValue()` is moved onto a daemon thread so the timeout can be
    // observed even when that join is the thing that is wedged.
    val finished   = new java.util.concurrent.CountDownLatch(1)
    val exitHolder = new java.util.concurrent.atomic.AtomicInteger(NotExitedSentinel)
    val waiter = new Thread(
      () => {
        try exitHolder.set(proc.exitValue())
        catch { case _: InterruptedException => Thread.currentThread().interrupt() }
        finally finished.countDown()
      },
      s"blobexec-wait-$origSha"
    )
    waiter.setDaemon(true)
    waiter.start()

    // `timeout` is the primary kill, so the latch is only a backstop: give it
    // the child's own budget, the -k grace, and slack. It fires only when
    // `timeout` is absent or itself wedged.
    val latchBudget = maxChildLifetimeSeconds(secs).toLong

    // The io threads are abandoned rather than joined on every timeout path: if
    // a surviving grandchild still holds a pipe, joining them is exactly the
    // hang we are escaping. That makes `stdoutBuilder`/`stderrBuilder` live,
    // racy state, so neither is read there — a timed-out blob's output is
    // discarded by the caller anyway.
    if (!finished.await(latchBudget, java.util.concurrent.TimeUnit.SECONDS)) {
      System.err.println(
        s"blobExec: no exit from [$command] ${latchBudget}s after start on blob $origSha " +
          s"($fullPath); destroying the direct child" +
          (if (groupKilled) "" else " (no GNU timeout: grandchildren may survive)")
      )
      proc.destroy()
      (TimeoutExitCode, Array.emptyByteArray, "")
    } else
      exitHolder.get() match {
        case NotExitedSentinel =>
          // The waiter was interrupted (e.g. pool.shutdownNow) before any status
          // was observed. The blob may well have succeeded, but we cannot claim
          // that, so it is reported as a timeout: Skip, never Replace.
          System.err.println(
            s"blobExec: wait for blob $origSha ($fullPath) was interrupted before the child " +
              "reported a status; treating it as a timeout and discarding its output"
          )
          (TimeoutExitCode, Array.emptyByteArray, "")
        case code if groupKilled && TimeoutStatuses.contains(code) =>
          System.err.println(
            s"blobExec: timeout after ${secs}s on blob $origSha ($fullPath); " +
              s"child process group killed by `timeout` (status $code)"
          )
          (TimeoutExitCode, Array.emptyByteArray, "")
        case code =>
          (code, stdoutBuilder.toByteArray, stderrBuilder.toString)
      }
  }

  private def transfer(in: InputStream, out: java.io.OutputStream): Unit = {
    val buf = new Array[Byte](8192)
    Iterator
      .continually(in.read(buf))
      .takeWhile(_ != -1)
      .foreach(n => out.write(buf, 0, n))
  }

  private def logError(
      command: String,
      origSha: String,
      fullPath: String,
      exitCode: Int,
      stderr: String
  ): Unit = {
    System.err.println(
      s"Warning: error executing command [$command] on blob $origSha at path [$fullPath]: exit code $exitCode"
    )
    if (stderr.nonEmpty) {
      System.err.println(s"--- stderr from $command on $origSha ($fullPath) ---")
      System.err.print(stderr)
      if (!stderr.endsWith("\n")) System.err.println()
      System.err.println("--- end stderr ---")
    }
  }
}
