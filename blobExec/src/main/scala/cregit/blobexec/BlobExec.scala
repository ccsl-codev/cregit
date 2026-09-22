package cregit.blobexec

import org.eclipse.jgit.lib.Constants.OBJ_BLOB
import org.eclipse.jgit.lib.{ObjectId, ObjectInserter}

import java.util.{Arrays => JavaArrays}

/**
 * Run the per-blob command and map its result onto a git object.
 *
 * The command reads the original blob on stdin and writes the replacement on
 * stdout. `BFG_FILENAME` is the basename rather than the path because cregit's
 * `tokenBySha.pl` keys its on-disk memo by it.
 *
 *   - env `BFG_BLOB` / `BFG_FILENAME` / `BFG_PATH`
 *   - exit != 0      → `Skip` (or `Abort` if `abortOnError`)
 *   - killed         → `Skip`, never `Abort`, never `Replace`
 *   - stdout == stdin → `Skip`
 *   - otherwise      → `Replace(newBlob)`
 */
object BlobExec {

  /** Per-blob wall-clock budget for the external command, in seconds. */
  val DefaultTimeoutSeconds: Int = 600

  /** Tokenizer status meaning "no usable tokenization". Any tokenizer may use it;
    * only tokenizeSrcMl.pl does today. tests/t/tokenizeSrcMl.t holds the two in step. */
  val ParserCrashExitCode: Int = 33

  sealed trait Outcome
  object Outcome {
    case object Skip                                      extends Outcome
    final case class Replace(newBlob: ObjectId)           extends Outcome
    final case class Abort(stderr: String, exitCode: Int) extends Outcome
  }

  /**
   * Run `command` against `bytes`. Pure aside from the child process and the
   * jgit inserter, which must be confined to the calling thread.
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
      onTimeout: () => Unit = () => (),
      onParserCrash: () => Unit = () => ()
  ): Outcome = {
    val env = Seq("BFG_BLOB" -> origSha, "BFG_FILENAME" -> filename, "BFG_PATH" -> fullPath)

    new ChildRunner(timeoutSeconds).run(command, bytes, env) match {
      case ChildRunner.Outcome.Killed(why) =>
        System.err.println(
          s"Warning: command [$command] on blob $origSha at path [$fullPath] gave no usable " +
            s"result ($why): blob left untokenized"
        )
        onTimeout()
        Outcome.Skip

      case ChildRunner.Outcome.Exited(ParserCrashExitCode, _, stderr) =>
        reportParserCrash(command, origSha, fullPath, s"reported a parser crash (exit $ParserCrashExitCode)", stderr)
        onParserCrash()
        Outcome.Skip

      case ChildRunner.Outcome.Exited(0, stdout, stderr) if stdout.isEmpty && bytes.nonEmpty =>
        reportParserCrash(command, origSha, fullPath,
          s"reported a parser crash: exited 0 with no output for a ${bytes.length}-byte blob", stderr)
        onParserCrash()
        Outcome.Skip

      case ChildRunner.Outcome.Exited(status, _, stderr) if status != 0 =>
        logError(command, origSha, fullPath, status, stderr)
        if (abortOnError) Outcome.Abort(stderr, status) else Outcome.Skip

      case ChildRunner.Outcome.Exited(_, stdout, _) if JavaArrays.equals(bytes, stdout) =>
        Outcome.Skip

      case ChildRunner.Outcome.Exited(_, stdout, _) =>
        Outcome.Replace(inserter.insert(OBJ_BLOB, stdout))
    }
  }

  private def reportParserCrash(
      command: String,
      origSha: String,
      fullPath: String,
      what: String,
      stderr: String
  ): Unit = {
    System.err.println(
      s"Warning: command [$command] $what on blob $origSha at path [$fullPath]: " +
        "blob left untokenized rather than written as an empty tokenization"
    )
    printStderr(command, origSha, fullPath, stderr)
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
    printStderr(command, origSha, fullPath, stderr)
  }

  private def printStderr(command: String, origSha: String, fullPath: String, stderr: String): Unit =
    if (stderr.nonEmpty) {
      System.err.println(s"--- stderr from $command on $origSha ($fullPath) ---")
      System.err.print(stderr)
      if (!stderr.endsWith("\n")) System.err.println()
      System.err.println("--- end stderr ---")
    }
}
