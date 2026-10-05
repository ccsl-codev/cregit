package cregit.blobexec

import org.eclipse.jgit.lib.Constants.OBJ_BLOB
import org.eclipse.jgit.lib.{ObjectId, ObjectInserter}

import java.io.{InputStream, OutputStream}
import java.nio.charset.StandardCharsets
import java.util.{Arrays => JavaArrays}
import scala.io.Source
import scala.sys.process.{Process, ProcessIO}

/** Runs a per-blob command: original bytes on stdin, replacement on stdout, env
  * `BFG_BLOB`, `BFG_PATH` and `BFG_FILENAME` (the basename, which `tokenBySha.pl`
  * keys its memo by). */
object BlobExec {

  /** Per-blob wall-clock budget for the external command, in seconds. */
  val DefaultTimeoutSeconds: Int = 600

  /** Reported for a killed child; negative, so no real exit status can equal it. */
  val TimeoutExitCode: Int = -1

  /** Must match `$PARSER_CRASH_EXIT` in `tokenize/tokenizeSrcMl.pl`. */
  val ParserCrashExitCode: Int = 33

  private val NotExitedSentinel: Int = Int.MinValue

  /** Seconds GNU `timeout` waits between its SIGTERM and its SIGKILL (`-k`). */
  private val KillGraceSeconds: Int = 5

  /** Backstop for the JVM latch, which fires only if `timeout` is missing or wedged. */
  private val BackstopSlackSeconds: Int = 25

  /** Longest one [[invoke]] can take: the budget, the kill grace and the backstop. */
  private[blobexec] def maxChildLifetimeSeconds(timeoutSeconds: Int): Int =
    math.min(math.max(1, timeoutSeconds).toLong + KillGraceSeconds + BackstopSlackSeconds, Int.MaxValue.toLong).toInt

  /** GNU `timeout`: 124 = budget expired, 137 = SIGKILL after `-k`. */
  private val TimeoutStatuses: Set[Int] = Set(124, 137)

  /** GNU `timeout` kills the child's whole process group; `Process.destroy()` would
    * kill only `tokenBySha.pl` and leave `srcml` holding our pipes open. */
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

  /** Thread-safe if `inserter` is confined to the calling thread. */
  def run(
      bytes: Array[Byte],
      origSha: String,
      filename: String,
      fullPath: String,
      command: String,
      abortOnError: Boolean,
      inserter: ObjectInserter,
      timeoutSeconds: Int = DefaultTimeoutSeconds,
      onTimeout: () => Unit = () => (),
      onParserCrash: () => Unit = () => (),
      workerPool: Option[TokenizerWorkerPool] = None
  ): Outcome = {
    val (exitCode, stdout, stderr) = workerPool match {
      case Some(pool) => pool.invoke(bytes, filename, timeoutSeconds)
      case None       => invoke(bytes, origSha, filename, fullPath, command, timeoutSeconds)
    }

    if (exitCode == TimeoutExitCode) {
      // Not routed through `abortOnError`: one wedged blob must not abort a long run.
      System.err.println(
        s"Warning: command [$command] timed out after ${timeoutSeconds}s on blob $origSha " +
          s"at path [$fullPath]: child killed, blob excluded"
      )
      onTimeout()
      Outcome.Skip
    } else if (exitCode == ParserCrashExitCode) {
      // Skip like a timeout, but counted apart: more time never fixes a crash.
      System.err.println(
        s"Warning: command [$command] reported a parser crash (exit $exitCode) on blob $origSha " +
          s"at path [$fullPath]: srcML died or produced no tokens, so this blob is excluded " +
          "rather than written as an empty tokenization"
      )
      if (stderr.nonEmpty) {
        System.err.println(s"--- stderr from $command on $origSha ($fullPath) ---")
        System.err.print(stderr)
        if (!stderr.endsWith("\n")) System.err.println()
        System.err.println("--- end stderr ---")
      }
      onParserCrash()
      Outcome.Skip
    } else if (exitCode != 0) {
      logError(command, origSha, fullPath, exitCode, stderr)
      if (abortOnError) Outcome.Abort(stderr, exitCode) else Outcome.Skip
    } else if (stdout.isEmpty && bytes.nonEmpty) {
      // Backstop for any tokenizer that exits 0 with no output: never insert it.
      System.err.println(
        s"Warning: command [$command] exited 0 but produced no output for the ${bytes.length}-byte " +
          s"blob $origSha at path [$fullPath]. Refusing to write an empty tokenization: counting " +
          "this as a parser crash and excluding the blob."
      )
      onParserCrash()
      Outcome.Skip
    } else if (JavaArrays.equals(bytes, stdout)) {
      Outcome.Skip
    } else {
      Outcome.Replace(inserter.insert(OBJ_BLOB, stdout))
    }
  }

  /** Returns (exit, stdout, stderr), or [[TimeoutExitCode]] and no output if killed. */
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

    // Daemon readers: on timeout they are abandoned while blocked and must not
    // keep the JVM alive.
    val io = new ProcessIO(writeStdin, readStdout, readStderr, daemonizeThreads = true)

    val secs = math.max(1, timeoutSeconds)
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

    // `exitValue()` joins the io threads, which a wedged child can block forever,
    // so it runs on a daemon thread and the caller waits on a latch.
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

    val latchBudget = maxChildLifetimeSeconds(secs).toLong

    // On the timeout paths the io threads are abandoned, so the builders are racy:
    // do not read them there.
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
          // Interrupted before any status: the result is unknown, so Skip.
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
