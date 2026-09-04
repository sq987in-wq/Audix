package com.candela.camera

data class GateResult(
    val pass: Boolean,
    val blur: Float,
    val contrast: Float,
    val refuse: Boolean,
    val reason: String,
)

object FrameGates {
    const val BLUR_MIN = 4.5f
    const val CONTRAST_MIN = 0.18f
    const val CR_REFUSE = 0.08f

    fun evaluateLuma(luma: ByteArray, width: Int, height: Int): GateResult {
        val tw = 96
        val th = 96
        val g = ByteArray(tw * th)
        val xRatio = width.toFloat() / tw
        val yRatio = height.toFloat() / th
        for (y in 0 until th) {
            val sy = minOf(height - 1, (y * yRatio).toInt())
            for (x in 0 until tw) {
                val sx = minOf(width - 1, (x * xRatio).toInt())
                g[y * tw + x] = luma[sy * width + sx]
            }
        }
        val sorted = g.map { it.toInt() and 0xff }.sorted()
        val p1 = sorted[(0.01 * (sorted.size - 1)).toInt()]
        val p99 = sorted[(0.99 * (sorted.size - 1)).toInt()]
        val contrast = (p99 - p1) / 255f
        var blurAcc = 0.0
        var n = 0
        for (y in 1 until 95) {
            for (x in 1 until 95) {
                val i = y * tw + x
                val c = g[i].toInt() and 0xff
                val lap = -4 * c + (g[i - 1].toInt() and 0xff) + (g[i + 1].toInt() and 0xff) +
                    (g[i - tw].toInt() and 0xff) + (g[i + tw].toInt() and 0xff)
                blurAcc += lap * lap
                n++
            }
        }
        val blur = kotlin.math.sqrt(blurAcc / n).toFloat()
        val refuse = contrast < CR_REFUSE
        val pass = blur > BLUR_MIN && contrast > CONTRAST_MIN && !refuse
        val reason = when {
            refuse -> "contrast floor"
            contrast <= CONTRAST_MIN -> "low contrast"
            blur <= BLUR_MIN -> "motion / blur"
            else -> "locked"
        }
        return GateResult(pass, blur, contrast, refuse, reason)
    }
}

class MotionGate {
    var magnitude = 0f
        private set
    var stable = true
        private set
    private var last = Float.NaN

    fun push(ax: Float, ay: Float, az: Float) {
        val a = kotlin.math.sqrt(ax * ax + ay * ay + az * az)
        if (last.isNaN()) {
            last = a
            stable = true
            return
        }
        val jerk = kotlin.math.abs(a - last)
        last = a
        magnitude = 0.7f * magnitude + 0.3f * jerk
        stable = magnitude < 0.8f
    }
}
