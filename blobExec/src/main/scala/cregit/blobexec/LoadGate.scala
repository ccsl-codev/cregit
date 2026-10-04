package cregit.blobexec

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}

/** A timeout is often the load of the machine, not the blob, so a retry first
  * waits while the 1-minute load average is above `limit`, for `maxWaitSeconds`
  * at most. No wait if /proc/loadavg cannot be read (not Linux). */
final case class LoadGate(
    limit: Double,
    maxWaitSeconds: Int,
    pollSeconds: Int = LoadGate.DefaultPollSeconds,
    loadavg: Path = LoadGate.ProcLoadavg
) {

  def enabled: Boolean = limit > 0 && maxWaitSeconds > 0

  def current: Option[Double] =
    try {
      val text = new String(Files.readAllBytes(loadavg), UTF_8).trim
      text.split("\\s+").headOption.flatMap(_.toDoubleOption)
    } catch { case _: Exception => None }

  /** Returns the seconds waited. No progress is stamped: Main makes the stall
    * window larger than the longest wait. */
  def await(): Long = {
    if (!enabled) return 0L
    val start = System.nanoTime()
    val deadline = start + maxWaitSeconds.toLong * 1000000000L
    var waiting = true
    while (waiting) {
      current match {
        case Some(load) if load > limit && System.nanoTime() < deadline =>
          val left = (deadline - System.nanoTime()) / 1000000L
          try Thread.sleep(math.max(1L, math.min(pollSeconds.toLong * 1000L, left)))
          catch { case _: InterruptedException => Thread.currentThread().interrupt(); waiting = false }
        case _ => waiting = false
      }
    }
    (System.nanoTime() - start) / 1000000000L
  }
}

object LoadGate {
  val ProcLoadavg: Path = Paths.get("/proc/loadavg")
  val DefaultPollSeconds: Int = 15
  /** The 1-minute load average decays by e each minute, so a burst is gone from it
    * within about five minutes. A longer wait helps only under a load that does
    * not end, and there it only makes the worst case longer. */
  val DefaultMaxWaitSeconds: Int = 600

  val disabled: LoadGate = LoadGate(0.0, 0)
}
