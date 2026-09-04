package app.candela.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Gate-first vision pipeline (audit section 1.2, countermeasures C2/C3).
 *
 * Design rules baked into these signatures:
 *  - Operates on a raw luma ByteArray + stride, NEVER a Bitmap. On Android this
 *    is plane 0 of an ImageReader YUV_420_888 image with zero conversion and zero
 *    allocation; off-device it is testable as plain data.
 *  - Reuses scratch buffers ([GateWorkspace]) so the steady-state hot path
 *    allocates nothing. Audit kill #5 is per-frame allocation.
 *  - Cheap first: motion gate (free, sensor) -> blur/contrast (~1-3 ms on 128x128)
 *    -> only then ROI decode (5-15 ms). The whole point is that ~90-95% of frames
 *    never reach ZXing, cutting idle scanning from 2-4 W to ~0.1-0.2 W.
 */

/** Integer rectangle in source-image pixel coordinates. */
data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
    val area: Int get() = width * height

    fun padded(pad: Int, maxW: Int, maxH: Int): Rect {
        val nx = max(0, x - pad)
        val ny = max(0, y - pad)
        val nr = min(maxW, right + pad)
        val nb = min(maxH, bottom + pad)
        return Rect(nx, ny, max(1, nr - nx), max(1, nb - ny))
    }

    companion object {
        /** Fallback ROI when no QR has been tracked yet: centre fraction of frame. */
        fun centerFraction(w: Int, h: Int, fraction: Double): Rect {
            val m = (1.0 - fraction) / 2.0
            val x0 = (w * m).toInt()
            val y0 = (h * m).toInt()
            return Rect(x0, y0, max(1, w - 2 * x0), max(1, h - 2 * y0))
        }
    }
}

enum class GateVerdict { PASS, BLOCK_MOTION, BLOCK_BLUR, BLOCK_CONTRAST, REFUSE_CONTRAST_FLOOR }

data class GateResult(
    val verdict: GateVerdict,
    val blur: Double,
    val contrast: Double,
    val lumaMean: Double,
    val lumaVar: Double,
    val elapsedNanos: Long = 0,
) {
    val pass: Boolean get() = verdict == GateVerdict.PASS

    /** Hard physical floor — coach must tell the user to fix the scene, not retry. */
    val refuse: Boolean get() = verdict == GateVerdict.REFUSE_CONTRAST_FLOOR

    val reason: String
        get() = when (verdict) {
            GateVerdict.PASS -> "locked"
            GateVerdict.BLOCK_MOTION -> "hold still"
            GateVerdict.BLOCK_BLUR -> "motion / blur"
            GateVerdict.BLOCK_CONTRAST -> "low contrast"
            GateVerdict.REFUSE_CONTRAST_FLOOR -> "contrast floor"
        }
}

/**
 * Thresholds are LEARNED at the calibration pose, never hard-coded.
 *
 * The web POC's relaxed demo values (BLUR_MIN 1.2 / CONTRAST_MIN 0.08) are
 * deliberately not carried over — PSR section 2.8 flags them as demo-only.
 * [CONTRAST_REFUSE_RATIO] encodes the audit's hard CR ~5:1 floor: a 5:1 contrast
 * ratio corresponds to a normalised (p99-p1)/255 of about 0.667.
 */
data class GateThresholds(
    val blurMin: Double,
    val contrastMin: Double,
    val contrastRefuse: Double = CONTRAST_REFUSE_NORM,
) {
    companion object {
        /** CR 5:1 -> (5-1)/(5+1) = 0.667 normalised dynamic range. */
        const val CONTRAST_REFUSE_NORM = 0.667

        /** Conservative bootstrap used only before calibration completes. */
        val BOOTSTRAP = GateThresholds(blurMin = 6.0, contrastMin = 0.35)

        /**
         * Derive from calibration-pose samples: accept frames meaningfully worse
         * than the calibration pose (the user will drift) but not garbage.
         */
        fun learn(blurSamples: DoubleArray, contrastSamples: DoubleArray): GateThresholds {
            val b = median(blurSamples)
            val c = median(contrastSamples)
            return GateThresholds(
                blurMin = max(1.0, b * 0.55),
                contrastMin = max(0.12, c * 0.70),
            )
        }

        private fun median(v: DoubleArray): Double {
            if (v.isEmpty()) return 0.0
            val s = v.sortedArray()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
        }
    }
}

/** Reusable scratch. One per camera pipeline; never allocate per frame. */
class GateWorkspace(val size: Int = 128) {
    val gray = ByteArray(size * size)
    val histogram = IntArray(256)
}

object Gates {

    /**
     * Integer-stride downsample of a ROI to size x size grayscale.
     * Nearest-neighbour by design: it is ~0.5 ms and preserves the high-frequency
     * edge energy the blur metric depends on. Area-averaging would smooth exactly
     * the signal being measured.
     */
    fun downsampleLuma(
        luma: ByteArray,
        imageWidth: Int,
        imageHeight: Int,
        rowStride: Int,
        roi: Rect,
        ws: GateWorkspace,
    ) {
        val n = ws.size
        val rx = max(1, roi.width)
        val ry = max(1, roi.height)
        for (y in 0 until n) {
            val sy = min(imageHeight - 1, roi.y + (y.toLong() * ry / n).toInt())
            val rowOff = sy * rowStride
            val dstOff = y * n
            for (x in 0 until n) {
                val sx = min(imageWidth - 1, roi.x + (x.toLong() * rx / n).toInt())
                ws.gray[dstOff + x] = luma[rowOff + sx]
            }
        }
    }

    /**
     * Laplacian energy — the sharpness proxy from audit C3.
     * Higher is sharper. A blurred QR loses module-edge energy immediately, which
     * is why this is a reliable pre-decode gate at ~0.5 ms.
     */
    fun blurScore(ws: GateWorkspace): Double {
        val n = ws.size
        val g = ws.gray
        var acc = 0.0
        for (y in 1 until n - 1) {
            val row = y * n
            for (x in 1 until n - 1) {
                val i = row + x
                val lap = -4 * (g[i].toInt() and 0xFF) +
                    (g[i - 1].toInt() and 0xFF) +
                    (g[i + 1].toInt() and 0xFF) +
                    (g[i - n].toInt() and 0xFF) +
                    (g[i + n].toInt() and 0xFF)
                acc += (lap * lap).toDouble()
            }
        }
        val count = ((n - 2) * (n - 2)).toDouble()
        return sqrt(acc / count)
    }

    /** (p99 - p1) / 255 via a 256-bin histogram — allocation-free, O(n). */
    fun contrastRatio(ws: GateWorkspace): Double {
        val hist = ws.histogram
        java.util.Arrays.fill(hist, 0)
        val g = ws.gray
        for (b in g) hist[b.toInt() and 0xFF]++
        val total = g.size
        val loTarget = (total * 0.01).toInt()
        val hiTarget = (total * 0.99).toInt()
        var cum = 0
        var p1 = 0
        var p99 = 255
        for (v in 0 until 256) {
            cum += hist[v]
            if (cum > loTarget) { p1 = v; break }
        }
        cum = 0
        for (v in 0 until 256) {
            cum += hist[v]
            if (cum >= hiTarget) { p99 = v; break }
        }
        return (p99 - p1).toDouble() / 255.0
    }

    fun meanVar(ws: GateWorkspace): Pair<Double, Double> {
        val g = ws.gray
        var sum = 0.0
        var sum2 = 0.0
        for (b in g) {
            val v = (b.toInt() and 0xFF).toDouble()
            sum += v
            sum2 += v * v
        }
        val n = g.size.toDouble()
        val mean = sum / n
        return Pair(mean, sum2 / n - mean * mean)
    }

    /**
     * Full gate. Order matters: motion is free, so it short-circuits before any
     * pixel work; contrast floor refuses before blur is even considered because a
     * privacy film or direct sun is not something the user can fix by holding
     * steadier (audit section 4).
     */
    fun evaluate(
        luma: ByteArray,
        imageWidth: Int,
        imageHeight: Int,
        rowStride: Int,
        roi: Rect,
        thresholds: GateThresholds,
        ws: GateWorkspace,
        motionStable: Boolean = true,
    ): GateResult {
        val t0 = System.nanoTime()
        if (!motionStable) {
            return GateResult(GateVerdict.BLOCK_MOTION, 0.0, 0.0, 0.0, 0.0, System.nanoTime() - t0)
        }
        downsampleLuma(luma, imageWidth, imageHeight, rowStride, roi, ws)
        val contrast = contrastRatio(ws)
        val blur = blurScore(ws)
        val (mean, variance) = meanVar(ws)
        val dt = System.nanoTime() - t0

        val verdict = when {
            contrast < thresholds.contrastRefuse * REFUSE_FRACTION ->
                GateVerdict.REFUSE_CONTRAST_FLOOR
            contrast < thresholds.contrastMin -> GateVerdict.BLOCK_CONTRAST
            blur < thresholds.blurMin -> GateVerdict.BLOCK_BLUR
            else -> GateVerdict.PASS
        }
        return GateResult(verdict, blur, contrast, mean, variance, dt)
    }

    /**
     * Below this fraction of the CR floor we stop blaming the user's aim and
     * declare a hard physical floor. Kept separate from [GateThresholds.contrastRefuse]
     * so the calibration screen can use the strict 5:1 test while the live gate
     * tolerates transient dips without aborting a running session.
     */
    private const val REFUSE_FRACTION = 0.30
}

/**
 * Motion gate over TYPE_LINEAR_ACCELERATION magnitude (audit C2).
 *
 * Hand tremor is low-frequency, so an EWMA over |a| separates "planted on a desk"
 * from "handheld drift" without any image work. Frames captured while moving are
 * worthless; dropping them is strictly cheaper than deblurring them.
 */
class MotionGate(
    private val thresholdMs2: Double = DEFAULT_THRESHOLD_MS2,
    private val alpha: Double = 0.30,
) {
    var magnitude: Double = 0.0
        private set
    var stable: Boolean = true
        private set

    private var initialised = false

    fun push(ax: Double, ay: Double, az: Double): Boolean {
        val a = sqrt(ax * ax + ay * ay + az * az)
        if (!initialised) {
            initialised = true
            magnitude = a
        } else {
            magnitude = (1 - alpha) * magnitude + alpha * a
        }
        stable = magnitude < thresholdMs2
        return stable
    }

    fun reset() {
        initialised = false
        magnitude = 0.0
        stable = true
    }

    companion object {
        /** Audit C2 suggests ~0.3 m/s^2 for linear acceleration. */
        const val DEFAULT_THRESHOLD_MS2 = 0.30
    }
}

/** Calibration outcome — the refuse path is a product feature, not an error case. */
sealed interface CalibrationVerdict {
    data class Ok(val thresholds: GateThresholds, val contrastRatio: Double) : CalibrationVerdict
    data class Warn(val thresholds: GateThresholds, val message: String) : CalibrationVerdict
    data class Refuse(val message: String) : CalibrationVerdict
}

/**
 * Calibration pose analysis (audit section 4 + section 7 envelope contract).
 *
 * Converts normalised dynamic range back to a true contrast ratio and applies the
 * audit's hard floor: below CR ~5:1 we refuse the session up front rather than
 * letting the user burn two minutes on a transfer that cannot physically work.
 */
object Calibration {

    fun contrastRatioFromNormalised(norm: Double): Double {
        val n = norm.coerceIn(0.0, 0.999)
        return (1.0 + n) / (1.0 - n)
    }

    fun analyse(blurSamples: DoubleArray, contrastSamples: DoubleArray): CalibrationVerdict {
        if (blurSamples.isEmpty() || contrastSamples.isEmpty()) {
            return CalibrationVerdict.Refuse("No calibration frames captured. Point at the sender screen.")
        }
        val learned = GateThresholds.learn(blurSamples, contrastSamples)
        val medianContrast = contrastSamples.sortedArray()[contrastSamples.size / 2]
        val cr = contrastRatioFromNormalised(medianContrast)

        return when {
            cr < 5.0 -> CalibrationVerdict.Refuse(
                "Contrast ${fmt(cr)}:1 is below the 5:1 floor. Move out of direct sun, " +
                    "remove any privacy screen protector, and face the sender head-on.",
            )
            cr < 8.0 -> CalibrationVerdict.Warn(
                learned,
                "Contrast ${fmt(cr)}:1 is workable but marginal. Expect dropped frames.",
            )
            median(blurSamples) < 3.0 -> CalibrationVerdict.Warn(
                learned,
                "Image looks soft. Steady both phones and check the lens is clean.",
            )
            else -> CalibrationVerdict.Ok(learned, cr)
        }
    }

    private fun median(v: DoubleArray): Double {
        val s = v.sortedArray()
        return if (s.isEmpty()) 0.0
        else if (s.size % 2 == 1) s[s.size / 2]
        else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    private fun fmt(d: Double): String {
        val r = Math.round(d * 10.0) / 10.0
        return if (abs(r - Math.round(r).toDouble()) < 1e-9) Math.round(r).toString() else r.toString()
    }
}

/**
 * Tracks the last known QR rect so the gate and decoder both work on a small ROI
 * instead of a full 1080p frame (audit C4: 5-15 ms vs 40-120 ms).
 */
class RoiTracker(
    private val imageWidth: Int,
    private val imageHeight: Int,
    private val fallbackFraction: Double = 0.40,
) {
    private var last: Rect? = null
    private var missCount = 0

    fun current(): Rect =
        last?.padded(PAD, imageWidth, imageHeight)
            ?: Rect.centerFraction(imageWidth, imageHeight, fallbackFraction)

    fun onDecodeSuccess(rect: Rect) {
        last = rect
        missCount = 0
    }

    /** After repeated misses, widen back out — the user has moved the phone. */
    fun onDecodeMiss() {
        missCount++
        if (missCount >= MAX_MISSES) {
            last = null
            missCount = 0
        }
    }

    companion object {
        const val PAD = 12
        const val MAX_MISSES = 12
    }
}
