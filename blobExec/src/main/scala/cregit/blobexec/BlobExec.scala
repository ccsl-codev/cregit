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
      timeoutSeconds: Int = DefaultTimeoutSeconds
  ): Outcome = {
    val (exitCode, stdout, stderr) = invoke(bytes, origSha, filename, fullPath, command, timeoutSeconds)

    if (exitCode == TimeoutExitCode) {
      // A timed-out child skips exactly one blob. Deliberately *not* routed
      // through `abortOnError`: one wedged tokenizer must never take down a
      // run that has already folded thousands of commits, and its (discarded,
      // possibly truncated) stdout must never be mistaken for a tokenization.
      System.err.println(
        s"Warning: command [$command] timed out after ${timeoutSeconds}s on blob $origSha " +
          s"at path [$fullPath]: child killed, blob left untokenized"
      )
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
    * The child is bounded by `timeoutSeconds`; on expiry it is killed and
    * [[TimeoutExitCode]] is returned with empty stdout/stderr. */
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

    val io = new ProcessIO(writeStdin, readStdout, readStderr, false)
    val pb = Process(
      Seq(command),
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
    val exitHolder = new java.util.concurrent.atomic.AtomicInteger(TimeoutExitCode)
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

    val budget = math.max(1, timeoutSeconds).toLong
    if (finished.await(budget, java.util.concurrent.TimeUnit.SECONDS)) {
      (exitHolder.get(), stdoutBuilder.toByteArray, stderrBuilder.toString)
    } else {
      System.err.println(
        s"blobExec: timeout after ${timeoutSeconds}s on blob $origSha ($fullPath); killing child"
      )
      proc.destroy()
      // The io threads are abandoned rather than joined: if a surviving
      // grandchild still holds the pipe, joining them is exactly the hang we
      // are escaping. That makes `stdoutBuilder`/`stderrBuilder` live, racy
      // state, so neither is read here — the caller discards a timed-out
      // blob's output anyway.
      (TimeoutExitCode, Array.emptyByteArray, "")
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
