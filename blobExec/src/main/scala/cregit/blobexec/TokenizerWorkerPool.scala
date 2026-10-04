package cregit.blobexec

import java.io.{BufferedInputStream, BufferedOutputStream, BufferedReader, EOFException, IOException, InputStream, InputStreamReader}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.concurrent.{ArrayBlockingQueue, ConcurrentHashMap, CountDownLatch, TimeUnit}
import scala.jdk.CollectionConverters._

final class TokenizerWorkerPool(
    workerCommand: Seq[String],
    env: Map[String, String],
    size: Int,
    timeoutSeconds: Int
) extends AutoCloseable {

  require(workerCommand.nonEmpty, "workerCommand must not be empty")
  require(size > 0, "worker pool size must be positive")
  require(timeoutSeconds > 0, "worker timeout must be positive")

  private val WorkerTimeoutExitCode = 124

  private final class Response(val exitCode: Int, val stdout: Array[Byte], val stderr: String)

  private final class Worker(
      val index: Int,
      val process: Process,
      val stdin: BufferedOutputStream,
      val stdout: BufferedInputStream
  ) {
    private val diagnostics = new StringBuilder

    def appendDiagnostic(line: String): Unit = diagnostics.synchronized {
      diagnostics.append(line).append('\n')
    }

    def takeDiagnostics(): String = diagnostics.synchronized {
      try diagnostics.toString finally diagnostics.clear()
    }
  }

  private val idle = new ArrayBlockingQueue[Worker](size)
  private val workers = ConcurrentHashMap.newKeySet[Worker]()
  private val closed = new AtomicBoolean(false)

  try {
    (0 until size).foreach { index =>
      val worker = startWorker(index)
      workers.add(worker)
      idle.put(worker)
    }
  } catch {
    case failure: Throwable =>
      close()
      throw failure
  }

  def invoke(
      bytes: Array[Byte],
      origSha: String,
      filename: String,
      fullPath: String
  ): ChildRunner.Outcome = {
    if (closed.get()) throw new IllegalStateException("tokenizer worker pool is closed")

    val worker = idle.take()
    worker.takeDiagnostics()
    try {
      writeRequest(worker, bytes, origSha, filename, fullPath)
    } catch {
      case failure: IOException =>
        return failedWorker(worker, failure)
    }

    val finished = new CountDownLatch(1)
    val response = new AtomicReference[Either[Throwable, Response]]()
    val reader = new Thread(
      () => {
        try response.set(Right(readResponse(worker.stdout)))
        catch { case failure: Throwable => response.set(Left(failure)) }
        finally finished.countDown()
      },
      s"token-worker-response-${worker.index}"
    )
    reader.setDaemon(true)
    reader.start()

    val backstopSeconds = math.max(1, timeoutSeconds).toLong + 5L + 5L
    if (!finished.await(backstopSeconds, TimeUnit.SECONDS)) {
      replaceWorker(worker, force = true)
      ChildRunner.Outcome.Killed(s"no response within ${backstopSeconds}s; worker replaced")
    } else {
      response.get() match {
        case Right(result) =>
          returnWorker(worker)
          if (result.exitCode == WorkerTimeoutExitCode)
            ChildRunner.Outcome.Killed(s"no exit within ${timeoutSeconds}s")
          else ChildRunner.Outcome.Exited(result.exitCode, result.stdout, result.stderr)
        case Left(failure) =>
          failedWorker(worker, failure)
      }
    }
  }

  private[blobexec] def currentWorkerPids: Seq[Long] =
    workers.asScala.toVector.map(_.process.pid())

  override def close(): Unit = {
    if (closed.compareAndSet(false, true)) {
      val snapshot = workers.asScala.toVector
      snapshot.foreach(worker => try worker.stdin.close() catch { case _: IOException => () })
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
      snapshot.foreach { worker =>
        val remaining = deadline - System.nanoTime()
        if (remaining > 0) worker.process.waitFor(remaining, TimeUnit.NANOSECONDS)
      }
      snapshot.filter(_.process.isAlive).foreach(_.process.destroyForcibly())
      snapshot.foreach(_.process.waitFor(2, TimeUnit.SECONDS))
      workers.clear()
      idle.clear()
    }
  }

  private def startWorker(index: Int): Worker = {
    val builder = new ProcessBuilder(workerCommand: _*)
    builder.environment().putAll(env.asJava)
    val process = builder.start()
    val worker = new Worker(
      index,
      process,
      new BufferedOutputStream(process.getOutputStream),
      new BufferedInputStream(process.getInputStream)
    )
    startStderrForwarder(worker)

    val ready = new AtomicReference[Either[Throwable, String]]()
    val readyLatch = new CountDownLatch(1)
    val readyReader = new Thread(
      () => {
        try ready.set(Right(readHeader(worker.stdout)))
        catch { case failure: Throwable => ready.set(Left(failure)) }
        finally readyLatch.countDown()
      },
      s"token-worker-ready-$index"
    )
    readyReader.setDaemon(true)
    readyReader.start()

    if (!readyLatch.await(30, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      throw new IllegalStateException(s"tokenizer worker $index did not print READY within 30 seconds")
    }
    ready.get() match {
      case Right("READY") => worker
      case Right(line) =>
        process.destroyForcibly()
        throw new IllegalStateException(s"tokenizer worker $index printed [$line] instead of READY")
      case Left(failure) =>
        process.destroyForcibly()
        throw new IllegalStateException(s"tokenizer worker $index failed before READY", failure)
    }
  }

  private def startStderrForwarder(worker: Worker): Unit = {
    val thread = new Thread(
      () => {
        val reader = new BufferedReader(new InputStreamReader(worker.process.getErrorStream, UTF_8))
        try {
          Iterator.continually(reader.readLine()).takeWhile(_ != null).foreach { line =>
            worker.appendDiagnostic(line)
            System.err.println(s"tokenWorker[${worker.index}]: $line")
          }
        } catch {
          case failure: IOException if worker.process.isAlive =>
            val line = s"stderr forwarding failed: ${failure.getMessage}"
            worker.appendDiagnostic(line)
            System.err.println(s"tokenWorker[${worker.index}]: $line")
          case _: IOException => ()
        } finally try reader.close() catch { case _: IOException => () }
      },
      s"token-worker-stderr-${worker.index}"
    )
    thread.setDaemon(true)
    thread.start()
  }

  private def writeRequest(
      worker: Worker,
      bytes: Array[Byte],
      origSha: String,
      filename: String,
      fullPath: String
  ): Unit = {
    val filenameBytes = filename.getBytes(UTF_8)
    val pathBytes = fullPath.getBytes(UTF_8)
    val header = s"REQ $origSha ${bytes.length} ${filenameBytes.length} ${pathBytes.length} $timeoutSeconds\n"
      .getBytes(UTF_8)
    worker.stdin.write(header)
    worker.stdin.write(filenameBytes)
    worker.stdin.write(pathBytes)
    worker.stdin.write(bytes)
    worker.stdin.flush()
  }

  private def readResponse(input: InputStream): Response = {
    val fields = readHeader(input).split(" ", -1)
    if (fields.length != 4 || fields(0) != "RES")
      throw new IOException(s"malformed tokenizer worker response header [${fields.mkString(" ")}]")

    val exitCode = parseInt(fields(1), "exit")
    val stdoutLength = parseLength(fields(2), "outLen")
    val stderrLength = parseLength(fields(3), "errLen")
    val stdout = readExactly(input, stdoutLength)
    val stderr = new String(readExactly(input, stderrLength), UTF_8)
    new Response(exitCode, stdout, stderr)
  }

  private def readHeader(input: InputStream): String = {
    val bytes = new java.io.ByteArrayOutputStream
    var next = input.read()
    while (next != -1 && next != '\n') {
      if (bytes.size() >= 8192) throw new IOException("tokenizer worker response header exceeds 8192 bytes")
      bytes.write(next)
      next = input.read()
    }
    if (next == -1) throw new EOFException("tokenizer worker closed stdout")
    new String(bytes.toByteArray, UTF_8)
  }

  private def readExactly(input: InputStream, length: Int): Array[Byte] = {
    val bytes = new Array[Byte](length)
    var offset = 0
    while (offset < length) {
      val read = input.read(bytes, offset, length - offset)
      if (read == -1) throw new EOFException(s"tokenizer worker closed stdout after $offset of $length bytes")
      offset += read
    }
    bytes
  }

  private def parseInt(value: String, name: String): Int =
    value.toIntOption.getOrElse(throw new IOException(s"invalid $name [$value] in tokenizer response"))

  private def parseLength(value: String, name: String): Int = {
    val length = parseInt(value, name)
    if (length < 0) throw new IOException(s"negative $name [$value] in tokenizer response")
    length
  }

  private def failedWorker(worker: Worker, failure: Throwable): ChildRunner.Outcome = {
    val workerExit = if (worker.process.waitFor(100, TimeUnit.MILLISECONDS)) Some(worker.process.exitValue()) else None
    val diagnostics = worker.takeDiagnostics()
    replaceWorker(worker, force = true)
    if (workerExit.contains(WorkerTimeoutExitCode))
      ChildRunner.Outcome.Killed(s"worker exited $WorkerTimeoutExitCode (timeout)")
    else {
      val stderr = if (diagnostics.nonEmpty) diagnostics else Option(failure.getMessage).getOrElse(failure.toString)
      ChildRunner.Outcome.Exited(workerExit.filter(_ != 0).getOrElse(1), Array.emptyByteArray, stderr)
    }
  }

  private def returnWorker(worker: Worker): Unit = {
    if (worker.process.isAlive && !closed.get()) idle.put(worker)
    else replaceWorker(worker, force = true)
  }

  private def replaceWorker(worker: Worker, force: Boolean): Unit = {
    workers.remove(worker)
    try worker.stdin.close() catch { case _: IOException => () }
    if (force && worker.process.isAlive) worker.process.destroyForcibly()
    if (!closed.get()) {
      val replacement = startWorker(worker.index)
      workers.add(replacement)
      idle.put(replacement)
    }
  }
}
