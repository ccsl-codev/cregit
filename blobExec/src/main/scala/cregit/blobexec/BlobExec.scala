package cregit.blobexec

import org.eclipse.jgit.lib.Constants.OBJ_BLOB
import org.eclipse.jgit.lib.{ObjectId, ObjectInserter}

import java.util.{Arrays => JavaArrays}

/** Runs a per-blob command: original bytes on stdin, replacement on stdout, env
  * `BFG_BLOB`, `BFG_PATH` and `BFG_FILENAME` (the basename, which `tokenBySha.pl`
  * keys its memo by). */
object BlobExec {

  /** Per-blob wall-clock budget for the external command, in seconds. */
  val DefaultTimeoutSeconds: Int = 600

  /** Must match `$PARSER_CRASH_EXIT` in `tokenize/tokenizeSrcMl.pl`. */
  val ParserCrashExitCode: Int = 33

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
    val env = Seq("BFG_BLOB" -> origSha, "BFG_FILENAME" -> filename, "BFG_PATH" -> fullPath)
    val ran = workerPool match {
      case Some(pool) => pool.invoke(bytes, filename, timeoutSeconds)
      case None       => new ChildRunner(timeoutSeconds).run(command, bytes, env)
    }

    ran match {
      case ChildRunner.Outcome.Killed(why) =>
        // Not routed through `abortOnError`: one wedged blob must not abort a long run.
        System.err.println(
          s"Warning: command [$command] timed out on blob $origSha at path [$fullPath] ($why): " +
            "blob excluded"
        )
        onTimeout()
        Outcome.Skip

      case ChildRunner.Outcome.Exited(ParserCrashExitCode, _, stderr) =>
        // Skip like a timeout, but counted apart: more time never fixes a crash.
        System.err.println(
          s"Warning: command [$command] reported a parser crash (exit $ParserCrashExitCode) on blob " +
            s"$origSha at path [$fullPath]: srcML died or produced no tokens, so this blob is excluded " +
            "rather than written as an empty tokenization"
        )
        printStderr(command, origSha, fullPath, stderr)
        onParserCrash()
        Outcome.Skip

      case ChildRunner.Outcome.Exited(status, _, stderr) if status != 0 =>
        logError(command, origSha, fullPath, status, stderr)
        if (abortOnError) Outcome.Abort(stderr, status) else Outcome.Skip

      case ChildRunner.Outcome.Exited(_, stdout, _) if stdout.isEmpty && bytes.nonEmpty =>
        System.err.println(
          s"Warning: command [$command] exited 0 but produced no output for the ${bytes.length}-byte " +
            s"blob $origSha at path [$fullPath]. Refusing to write an empty tokenization: counting " +
            "this as a parser crash and excluding the blob."
        )
        onParserCrash()
        Outcome.Skip

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
