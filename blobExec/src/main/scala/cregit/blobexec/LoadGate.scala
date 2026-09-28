package cregit.blobexec

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}

/** Wait while the machine is loaded, before the second attempt at a timed-out
  * blob.
  *
  * A timeout is often the load of the machine, not the blob. If the second
  * attempt starts at once, under the same load, it can time out for the same
  * reason. So the walker calls [[await]] first. It reads the 1-minute load average
  * from `/proc/loadavg`, and it waits while that value is more than `limit`. It
  * waits `maxWaitSeconds` at most, and then it continues: the second attempt also
  * has a longer budget, and a wait with no end would stop the run.
  *
  * If the file cannot be read (not Linux), there is no wait.
  *
  * `limit <= 0` or `maxWaitSeconds <= 0` disables the gate. */
final case class LoadGate(
    limit: Double,
    maxWaitSeconds: Int,
    pollSeconds: Int = LoadGate.DefaultPollSeconds,
    loadavg: Path = LoadGate.ProcLoadavg
) {

  def enabled: Boolean = limit > 0 && maxWaitSeconds > 0

  /** The 1-minute load average, or None if it cannot be read. */
  def current: Option[Double] =
    try {
      val text = new String(Files.readAllBytes(loadavg), UTF_8).trim
      text.split("\\s+").headOption.flatMap(_.toDoubleOption)
    } catch { case _: Exception => None }

  /** Wait while the load is above the limit. `onPoll` is called at each poll with
    * the load, so the caller can stamp progress (the stall watchdog must not count
    * a deliberate wait as a stall). Returns the seconds waited. */
  def await(onPoll: Double => Unit): Long = {
    if (!enabled) return 0L
    val start = System.nanoTime()
    val deadline = start + maxWaitSeconds.toLong * 1000000000L
    var waiting = true
    while (waiting) {
      current match {
        case Some(load) if load > limit && System.nanoTime() < deadline =>
          onPoll(load)
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
  /** 600 s. The 1-minute load average decays by a factor of e each minute once a
    * burst ends, so a burst above the limit is gone from it within about five
    * minutes. A wait longer than ten minutes helps only under a load that does
    * not end, and there a longer wait only makes the worst case longer: with 3
    * retries each wait is paid up to three times per blob. */
  val DefaultMaxWaitSeconds: Int = 600

  /** The gate a test or a library caller gets: no wait at all. */
  val disabled: LoadGate = LoadGate(0.0, 0)
}
