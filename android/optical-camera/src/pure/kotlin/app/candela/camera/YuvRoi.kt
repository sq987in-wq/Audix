package app.candela.camera

import kotlin.math.max
import kotlin.math.min

/**
 * YUV_420_888 luma ROI extraction (audit C4).
 *
 * The economics: a full-frame 1080p ZXing decode is 40-120 ms; a ROI decode at
 * 1.5-2 px per module is 5-15 ms. That 4-10x cut sits on top of the ~90-95% of
 * frames the gate already rejected, and together they are what keep the receiver
 * inside the 2-4 W thermal budget instead of throttling in 5-10 minutes.
 *
 * Implementation rules, all of which exist to avoid allocation and copying:
 *  - Reads plane 0 (luma) ONLY. Chroma is never touched; a QR is monochrome.
 *  - Honours rowStride, which is NOT width on most devices (commonly padded to a
 *    16- or 64-byte boundary). Ignoring it produces a sheared image that decodes
 *    on an emulator and fails on real hardware.
 *  - Honours pixelStride: some devices deliver luma with a stride of 2.
 *  - Writes into a caller-owned destination buffer that is reused every frame.
 */
object YuvRoi {

    /**
     * Reusable destination for a cropped-and-scaled ROI.
     * One instance per pipeline; [ensure] only reallocates when the shape changes.
     */
    class Buffer {
        var data: ByteArray = ByteArray(0)
            private set
        var width: Int = 0
            private set
        var height: Int = 0
            private set

        fun ensure(w: Int, h: Int) {
            if (width == w && height == h && data.size >= w * h) return
            width = w
            height = h
            data = ByteArray(w * h)
        }
    }

    /**
     * Crop [roi] from a luma plane and nearest-neighbour scale it to
     * [dstWidth] x [dstHeight] into [out].
     *
     * Nearest-neighbour, not area-averaging: QR decoding wants crisp module
     * transitions. Averaging softens exactly the edges the binarizer keys on, and
     * costs more. The 3x3 median in [median3] is the correct place to kill sensor
     * noise, because it is edge-preserving.
     */
    fun cropScale(
        luma: ByteArray,
        imageWidth: Int,
        imageHeight: Int,
        rowStride: Int,
        pixelStride: Int,
        roi: IntRect,
        dstWidth: Int,
        dstHeight: Int,
        out: Buffer,
    ) {
        val r = roi.clampedTo(imageWidth, imageHeight)
        out.ensure(dstWidth, dstHeight)
        val dst = out.data
        val rw = max(1, r.width)
        val rh = max(1, r.height)

        for (y in 0 until dstHeight) {
            val sy = min(imageHeight - 1, r.y + (y.toLong() * rh / dstHeight).toInt())
            val srcRow = sy * rowStride
            val dstRow = y * dstWidth
            for (x in 0 until dstWidth) {
                val sx = min(imageWidth - 1, r.x + (x.toLong() * rw / dstWidth).toInt())
                dst[dstRow + x] = luma[srcRow + sx * pixelStride]
            }
        }
    }

    /**
     * Target ROI dimensions for ~[pxPerModule] pixels per QR module.
     *
     * Audit C4 calls for 1.5-2 px/module. Below ~1.5 the binarizer cannot resolve
     * modules; above ~3 you are paying for pixels that carry no extra information.
     */
    fun scaledSizeFor(moduleCount: Int, pxPerModule: Double = 2.0): Int {
        val quietZone = 4 * 2 // 4-module quiet zone each side
        return ((moduleCount + quietZone) * pxPerModule).toInt().coerceIn(64, 1024)
    }

    /**
     * Edge-preserving 3x3 median (audit C4: "kills sensor noise without touching
     * edges"). In-place over a scratch copy; allocation-free given a reused [scratch].
     */
    fun median3(buf: Buffer, scratch: ByteArray) {
        val w = buf.width
        val h = buf.height
        if (w < 3 || h < 3) return
        val src = buf.data
        System.arraycopy(src, 0, scratch, 0, w * h)
        val v = IntArray(9)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                var i = 0
                for (dy in -1..1) {
                    val row = (y + dy) * w
                    for (dx in -1..1) {
                        v[i++] = scratch[row + x + dx].toInt() and 0xFF
                    }
                }
                // Partial selection sort to the median — cheaper than a full sort.
                for (a in 0..4) {
                    var mIdx = a
                    for (b in a + 1 until 9) if (v[b] < v[mIdx]) mIdx = b
                    val t = v[a]; v[a] = v[mIdx]; v[mIdx] = t
                }
                src[y * w + x] = v[4].toByte()
            }
        }
    }

    /** Otsu threshold, used as the fallback when HybridBinarizer struggles. */
    fun otsuThreshold(buf: Buffer, histogram: IntArray): Int {
        java.util.Arrays.fill(histogram, 0)
        val n = buf.width * buf.height
        for (i in 0 until n) histogram[buf.data[i].toInt() and 0xFF]++

        var sum = 0.0
        for (t in 0 until 256) sum += t * histogram[t].toDouble()
        var sumB = 0.0
        var wB = 0
        var best = 0.0
        var threshold = 127
        for (t in 0 until 256) {
            wB += histogram[t]
            if (wB == 0) continue
            val wF = n - wB
            if (wF == 0) break
            sumB += t.toDouble() * histogram[t]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                threshold = t
            }
        }
        return threshold
    }
}

/** Integer rect independent of android.graphics.Rect so this stays pure. */
data class IntRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height

    fun clampedTo(maxW: Int, maxH: Int): IntRect {
        val nx = x.coerceIn(0, max(0, maxW - 1))
        val ny = y.coerceIn(0, max(0, maxH - 1))
        val nw = width.coerceIn(1, maxW - nx)
        val nh = height.coerceIn(1, maxH - ny)
        return IntRect(nx, ny, nw, nh)
    }

    /** Expand by [pad] on all sides, clamped to the image. */
    fun padded(pad: Int, maxW: Int, maxH: Int): IntRect {
        val nx = max(0, x - pad)
        val ny = max(0, y - pad)
        val nr = min(maxW, right + pad)
        val nb = min(maxH, bottom + pad)
        return IntRect(nx, ny, max(1, nr - nx), max(1, nb - ny))
    }

    /**
     * Convert to normalised sensor-array coordinates for AE_REGIONS / AF_REGIONS
     * (audit section 5.1 step 8) — metering on the QR, not the whole scene.
     */
    fun toMeteringRect(imageW: Int, imageH: Int, sensorW: Int, sensorH: Int): IntRect {
        val sx = sensorW.toDouble() / imageW
        val sy = sensorH.toDouble() / imageH
        return IntRect(
            (x * sx).toInt(), (y * sy).toInt(),
            max(1, (width * sx).toInt()), max(1, (height * sy).toInt()),
        ).clampedTo(sensorW, sensorH)
    }
}
