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
      onTimeout: () => Unit = () => ()
  ): Outcome = {
    val env = Seq("BFG_BLOB" -> origSha, "BFG_FILENAME" -> filename, "BFG_PATH" -> fullPath)

    new ChildRunner(timeoutSeconds).run(command, bytes, env) match {
      case ChildRunner.Outcome.Killed(why) =>
        // Skips one blob and bypasses `abortOnError`: one wedged tokenizer must
        // not end a run that has folded thousands of commits. `onTimeout` is the
        // only trace left, because the child's output is discarded.
        System.err.println(
          s"Warning: command [$command] on blob $origSha at path [$fullPath] gave no usable " +
            s"result ($why): blob left untokenized"
        )
        onTimeout()
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
