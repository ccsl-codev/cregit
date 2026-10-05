package cregit.blobexec

private[blobexec] object Saturating {

  def sum(a: Int, b: Int): Int = clampToInt(a.toLong + b.toLong)

  def product(a: Int, b: Int): Int = clampToInt(a.toLong * b.toLong)

  private def clampToInt(value: Long): Int =
    math.min(math.max(value, 0L), Int.MaxValue.toLong).toInt
}
