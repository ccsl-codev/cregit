package cregit.blobexec

import java.io.{InputStream, OutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.jdk.CollectionConverters._

/** One run of an external command under a wall-clock budget.
  *
  * Owns the child's environment, its pipes, its budget and its kill. Nothing
  * here knows what the command is for.
  */
final class ChildRunner(budgetSeconds: Int) {

  import ChildRunner._

  private val budget: Int = math.max(1, budgetSeconds)

  def run(command: String, stdin: Array[Byte], env: Seq[(String, String)]): Outcome = {
    val builder = new ProcessBuilder(command)
    env.foreach { case (name, value) => builder.environment.put(name, value) }
    val child = builder.start()

    val stdout  = new java.io.ByteArrayOutputStream(math.max(stdin.length, StdoutBufferBytes))
    val stderr  = new StringBuilder
    val readers = new CountDownLatch(2)

    pump("stdin", () => writeThenClose(child.getOutputStream, stdin))
    pump("stdout", () => try copyBytes(child.getInputStream, stdout) finally readers.countDown())
    pump("stderr", () => try copyText(child.getErrorStream, stderr) finally readers.countDown())

    val exited =
      try child.waitFor(budget.toLong, TimeUnit.SECONDS)
      catch {
        case _: InterruptedException =>
          Thread.currentThread().interrupt()
          false
      }

    if (!exited) kill(child, s"no exit within ${budget}s")
    else if (!readers.await(DrainGraceSeconds.toLong, TimeUnit.SECONDS))
      // The child is gone but something it left behind still holds a pipe, so
      // its output is incomplete. Waiting for that writer is the hang this
      // class exists to prevent, and `stdout` is not safe to read until the
      // reader thread has stopped touching it.
      kill(child, s"output still open ${DrainGraceSeconds}s after exit")
    else Outcome.Exited(child.exitValue, stdout.toByteArray, stderr.toString)
  }

  private def kill(child: Process, why: String): Outcome = {
    val killed = killTree(child)
    Outcome.Killed(if (killed > 0) s"$why; killed $killed process(es)" else why)
  }
}

object ChildRunner {

  sealed trait Outcome
  object Outcome {
    final case class Exited(status: Int, stdout: Array[Byte], stderr: String) extends Outcome

    /** No result that can be trusted. Never carries output. */
    final case class Killed(why: String) extends Outcome
  }

  /** Seconds an open pipe is waited for after the child itself has exited. */
  private val DrainGraceSeconds: Int = 5

  /** Seconds a forced kill needs to take effect. */
  private val KillSettleSeconds: Int = 5

  private val StdoutBufferBytes: Int = 1024

  /** Longest one [[ChildRunner.run]] can take. This is the quiet period a
    * healthy but slow blob creates, which the stall watchdog's window has to
    * clear — see [[Walker.stallFloorFor]]. */
  private[blobexec] def maxLifetimeSeconds(budgetSeconds: Int): Int =
    math.max(1, budgetSeconds) + DrainGraceSeconds + KillSettleSeconds

  /** Kill the child and every process beneath it, and report how many were
    * signalled. The tokenizer chain is `tokenBySha.pl` -> `sh` -> `srcml`, so
    * signalling the direct child alone leaves grandchildren holding the pipes. */
  private[blobexec] def killTree(child: Process): Int = {
    val handle = child.toHandle
    // Snapshot before killing anything: a dead parent's children are reparented
    // to init and leave the tree, but a handle taken now stays valid.
    val tree   = handle.descendants().iterator().asScala.toVector
    val parent = if (handle.destroyForcibly()) 1 else 0
    val known  = tree.count(_.destroyForcibly())
    // A process forked between the snapshot and the kill is reachable only from
    // its own parent.
    val late = tree.flatMap(_.descendants().iterator().asScala).count(_.destroyForcibly())
    parent + known + late
  }

  private def pump(name: String, body: () => Unit): Unit = {
    val t = new Thread(() => body(), s"blobexec-$name")
    // Daemon: an abandoned reader must never keep the JVM alive.
    t.setDaemon(true)
    t.start()
  }

  private def writeThenClose(out: OutputStream, bytes: Array[Byte]): Unit =
    // A command that ignores its input, or dies early, breaks this pipe. That is
    // the child's business, not a failure of the run.
    try { out.write(bytes); out.flush() }
    catch { case _: java.io.IOException => () }
    finally closeQuietly(out)

  private def copyBytes(in: InputStream, out: java.io.ByteArrayOutputStream): Unit =
    try {
      val buf = new Array[Byte](8192)
      Iterator.continually(in.read(buf)).takeWhile(_ != -1).foreach(n => out.write(buf, 0, n))
    } catch { case _: java.io.IOException => () } finally closeQuietly(in)

  private def copyText(in: InputStream, out: StringBuilder): Unit =
    try {
      val reader = new java.io.InputStreamReader(in, UTF_8)
      val buf    = new Array[Char](4096)
      Iterator.continually(reader.read(buf)).takeWhile(_ != -1).foreach(n => out.appendAll(buf, 0, n))
    } catch { case _: java.io.IOException => () } finally closeQuietly(in)

  private def closeQuietly(c: java.io.Closeable): Unit =
    try c.close()
    catch { case _: java.io.IOException => () }
}
