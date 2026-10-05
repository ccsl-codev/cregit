package cregit.blobexec

import java.io.{BufferedOutputStream, DataInputStream, EOFException, IOException}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.concurrent.{ArrayBlockingQueue, ConcurrentHashMap, CountDownLatch, TimeUnit}
import scala.jdk.CollectionConverters._

/** Persistent tokenizer processes that speak tokenizeByBlobId/WORKER_PROTOCOL.md,
  * one request in flight per worker. */
final class TokenizerWorkerPool(workerPath: String, size: Int) extends AutoCloseable {
  import TokenizerWorkerPool._

  private final class Worker(val index: Int, val process: Process) {
    val stdin  = new BufferedOutputStream(process.getOutputStream)
    val stdout = new DataInputStream(process.getInputStream)
  }

  private val idle    = new ArrayBlockingQueue[Worker](size)
  private val workers = ConcurrentHashMap.newKeySet[Worker]()
  private val closed  = new AtomicBoolean(false)

  try (0 until size).foreach(addWorker)
  catch { case failure: Throwable => close(); throw failure }

  def invoke(bytes: Array[Byte], filename: String, timeoutSeconds: Int): ChildRunner.Outcome = {
    val worker   = idle.take()
    val backstop = ChildRunner.maxLifetimeSeconds(timeoutSeconds)
    within(backstop.toLong)(request(worker, bytes, filename, timeoutSeconds)) match {
      case None =>
        replace(worker)
        ChildRunner.Outcome.Killed(s"tokenizer worker ${worker.index} gave no response within ${backstop}s; replaced it")
      case Some(Left(failure)) =>
        replace(worker)
        ChildRunner.Outcome.Killed(s"tokenizer worker ${worker.index} died ($failure); replaced it")
      case Some(Right(response)) =>
        if (worker.process.isAlive) idle.put(worker) else replace(worker)
        response
    }
  }

  override def close(): Unit =
    if (closed.compareAndSet(false, true)) {
      val all = workers.asScala.toVector
      all.foreach(w => try w.stdin.close() catch { case _: IOException => () })
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CloseSeconds)
      all.foreach { w =>
        if (!w.process.waitFor(math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) kill(w.process)
      }
    }

  private def addWorker(index: Int): Unit = {
    val process = new ProcessBuilder(workerPath).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    val worker = new Worker(index, process)
    within(ReadySeconds)(readLine(worker.stdout)) match {
      case Some(Right("READY")) =>
        workers.add(worker)
        idle.put(worker)
      case notReady =>
        kill(process)
        throw new IllegalStateException(s"tokenizer worker [$workerPath] did not start: $notReady")
    }
  }

  private def replace(worker: Worker): Unit = {
    workers.remove(worker)
    kill(worker.process)
    if (!closed.get()) addWorker(worker.index)
  }

  private def request(worker: Worker, bytes: Array[Byte], filename: String, timeoutSeconds: Int) = {
    val name = filename.getBytes(UTF_8)
    worker.stdin.write(s"REQ ${bytes.length} ${name.length} $timeoutSeconds\n".getBytes(UTF_8))
    worker.stdin.write(name)
    worker.stdin.write(bytes)
    worker.stdin.flush()
    readLine(worker.stdout).split(' ') match {
      case Array("RES", exit, outLength, errLength) =>
        val out = readBytes(worker.stdout, outLength.toInt)
        val err = new String(readBytes(worker.stdout, errLength.toInt), UTF_8)
        if (exit.toInt == WorkerTimeoutExitCode) ChildRunner.Outcome.Killed(s"no exit within ${timeoutSeconds}s")
        else ChildRunner.Outcome.Exited(exit.toInt, out, err)
      case header => throw new IOException(s"malformed response header [${header.mkString(" ")}]")
    }
  }
}

object TokenizerWorkerPool {
  /** What a worker answers when it killed the tokenizer for running out of time. */
  val WorkerTimeoutExitCode = 124
  private val ReadySeconds = 30L
  private val CloseSeconds = 2L
  private val MaxHeaderBytes = 8192

  private def kill(process: Process): Unit = { ChildRunner.killTree(process); () }

  /** Runs `read` on a daemon thread, so that a worker which never answers cannot block the caller. */
  private def within[A](seconds: Long)(read: => A): Option[Either[Throwable, A]] = {
    val result = new AtomicReference[Either[Throwable, A]]()
    val done = new CountDownLatch(1)
    val reader = new Thread(() => {
      try result.set(Right(read))
      catch { case failure: Throwable => result.set(Left(failure)) }
      finally done.countDown()
    }, "tokenizer-worker-read")
    reader.setDaemon(true)
    reader.start()
    if (done.await(seconds, TimeUnit.SECONDS)) Some(result.get()) else None
  }

  private def readLine(in: DataInputStream): String = {
    val line = new java.io.ByteArrayOutputStream
    var next = in.read()
    while (next != '\n') {
      if (next == -1) throw new EOFException("tokenizer worker closed its stdout")
      if (line.size() >= MaxHeaderBytes) throw new IOException(s"header longer than $MaxHeaderBytes bytes")
      line.write(next)
      next = in.read()
    }
    line.toString(UTF_8)
  }

  private def readBytes(in: DataInputStream, length: Int): Array[Byte] = {
    val bytes = new Array[Byte](length)
    in.readFully(bytes)
    bytes
  }
}
