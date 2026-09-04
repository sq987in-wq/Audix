package app.candela.camera

import app.candela.vision.GateThresholds
import app.candela.vision.GateWorkspace
import app.candela.vision.Gates
import app.candela.vision.Rect
import app.candela.vision.RoiTracker
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.EnumMap

/**
 * camera -> motion gate -> blur/contrast gate -> ROI crop -> ZXing -> protocol.
 *
 * The economics this exists to enforce (audit sections 1.2, 3):
 *   ungated full-frame ZXing at 30 fps = 1.5-9 cores = 2-4 W = throttle in 5-10 min
 *   gated ROI decode                   = ~0.1-0.2 W idle, ~8-12 decodes/s
 *
 * So the decoder must NEVER see a frame that cannot decode. Every rejection here
 * is a rejection that costs ~0.15 ms instead of 40-120 ms.
 *
 * ZXing-core only. No ML Kit, no Play Services (audit C4 / section 6.2): the
 * "completely offline" claim is a product-definition promise, not an
 * implementation detail, and the target audience runs AOSP/air-gapped devices
 * where GMS may not exist at all.
 *
 * Threading: called on the camera HandlerThread. Holds no locks; all state is
 * confined to that thread. Decoding is bounded to 2 workers upstream
 * (Dispatchers.Default.limitedParallelism(2)) — never more, per the thermal budget.
 */
class DecodePipeline(
    private val imageWidth: Int,
    private val imageHeight: Int,
    private val onSymbol: (ByteArray) -> Unit,
    private val onMetrics: (Metrics) -> Unit = {},
) {

    data class Metrics(
        val framesSeen: Long,
        val framesGated: Long,
        val decodeAttempts: Long,
        val decodeSuccesses: Long,
        val lastGateMs: Double,
        val lastDecodeMs: Double,
        val blur: Double,
        val contrast: Double,
        val reason: String,
    ) {
        /** The audit predicts the gate rejects ~90-95% of frames. */
        val gateRejectRate: Double
            get() = if (framesSeen == 0L) 0.0 else 1.0 - (framesGated.toDouble() / framesSeen)

        val decodeYield: Double
            get() = if (decodeAttempts == 0L) 0.0 else decodeSuccesses.toDouble() / decodeAttempts
    }

    private val tracker = RoiTracker(imageWidth, imageHeight)
    private val workspace = GateWorkspace(128)
    private val roiBuffer = YuvRoi.Buffer()
    private var medianScratch = ByteArray(0)
    private val histogram = IntArray(256)

    private val reader = QRCodeReader()
    private val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
        // Byte mode: the protocol is binary, not text. TRY_HARDER is deliberately
        // NOT set — it multiplies decode cost for marginal gain on a frame that
        // already passed a sharpness gate.
        put(DecodeHintType.POSSIBLE_FORMATS, listOf(com.google.zxing.BarcodeFormat.QR_CODE))
    }

    var thresholds: GateThresholds = GateThresholds.BOOTSTRAP

    private var framesSeen = 0L
    private var framesGated = 0L
    private var decodeAttempts = 0L
    private var decodeSuccesses = 0L

    /**
     * @return true if the frame passed the gate (so the caller can drive re-lock)
     */
    fun onFrame(
        luma: ByteArray,
        rowStride: Int,
        pixelStride: Int,
        motionStable: Boolean,
        decodable: Boolean,
    ): Boolean {
        framesSeen++

        // Not locked yet -> never decode. Calibration frames are for metrics only.
        if (!decodable) {
            onMetrics(snapshot(0.0, 0.0, 0.0, 0.0, "calibrating"))
            return false
        }

        val roi = tracker.current()
        val gate = Gates.evaluate(
            luma, imageWidth, imageHeight, rowStride,
            Rect(roi.x, roi.y, roi.width, roi.height),
            thresholds, workspace, motionStable,
        )
        val gateMs = gate.elapsedNanos / 1e6

        if (!gate.pass) {
            onMetrics(snapshot(gateMs, 0.0, gate.blur, gate.contrast, gate.reason))
            return false
        }
        framesGated++

        // ---- ROI decode. This is the only place ZXing is invoked.
        val t0 = System.nanoTime()
        val dst = YuvRoi.scaledSizeFor(moduleCount = 177, pxPerModule = 2.0)
        YuvRoi.cropScale(
            luma, imageWidth, imageHeight, rowStride, pixelStride,
            IntRect(roi.x, roi.y, roi.width, roi.height),
            dst, dst, roiBuffer,
        )
        if (medianScratch.size < dst * dst) medianScratch = ByteArray(dst * dst)
        YuvRoi.median3(roiBuffer, medianScratch)

        decodeAttempts++
        val payload = decodeRoi(dst)
        val decodeMs = (System.nanoTime() - t0) / 1e6

        if (payload != null) {
            decodeSuccesses++
            tracker.onDecodeSuccess(Rect(roi.x, roi.y, roi.width, roi.height))
            onSymbol(payload)
        } else {
            tracker.onDecodeMiss()
        }
        onMetrics(snapshot(gateMs, decodeMs, gate.blur, gate.contrast, gate.reason))
        return true
    }

    private fun decodeRoi(size: Int): ByteArray? = try {
        val source = PlanarYUVLuminanceSource(
            roiBuffer.data, size, size, 0, 0, size, size, false,
        )
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        val result = reader.decode(bitmap, hints)
        // Byte-mode segments carry the raw protocol frame. Text would corrupt it.
        val segments = result.resultMetadata
            ?.get(com.google.zxing.ResultMetadataType.BYTE_SEGMENTS) as? List<*>
        val bytes = segments?.firstOrNull() as? ByteArray
        bytes ?: result.text?.toByteArray(Charsets.ISO_8859_1)
    } catch (_: NotFoundException) {
        null
    } catch (_: Exception) {
        null
    } finally {
        reader.reset()
    }

    private fun snapshot(
        gateMs: Double,
        decodeMs: Double,
        blur: Double,
        contrast: Double,
        reason: String,
    ) = Metrics(
        framesSeen, framesGated, decodeAttempts, decodeSuccesses,
        gateMs, decodeMs, blur, contrast, reason,
    )
}
