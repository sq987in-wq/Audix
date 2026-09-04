package app.candela.app

import android.content.Context
import android.view.Surface
import app.candela.camera.Camera2Session
import app.candela.camera.DecodePipeline
import app.candela.camera.ExposurePlan
import app.candela.camera.LockPolicy
import app.candela.protocol.DecodeResult
import app.candela.protocol.Frames

/**
 * Binds :optical-camera to [ReceiveViewModel].
 *
 * THIS IS THE PIECE THAT WAS MISSING. MainActivity declared a `CameraController`
 * interface and never assigned it, so `camera?.attachPreview(...)` was always a
 * no-op on a null reference: no device was opened, no capture session existed,
 * and the SurfaceView stayed the colour of an unwritten surface — pitch black,
 * with no error, whatever the user did with permissions.
 *
 * Everything below is plumbing. The decisions (exposure strategy, lock policy,
 * gate thresholds, ROI) already live in verified pure code; this class only
 * moves bytes between them and marshals callbacks onto the main thread.
 */
class CameraBinding(
    context: Context,
    private val vm: ReceiveViewModel,
    private val postToMain: (() -> Unit) -> Unit,
) : CameraController {

    /** Frames arrive on the camera HandlerThread; the pipeline stays on it. */
    private var pipeline: DecodePipeline? = null
    private var frameCount = 0L
    private var lastFpsNs = 0L
    private var fps = 0.0

    private val session = Camera2Session(
        context,
        object : Camera2Session.Callbacks {
            override fun onLumaFrame(
                luma: ByteArray,
                width: Int,
                height: Int,
                rowStride: Int,
                pixelStride: Int,
                timestampNs: Long,
                decodable: Boolean,
            ): Boolean {
                val p = pipeline ?: DecodePipeline(
                    imageWidth = width,
                    imageHeight = height,
                    onSymbol = { payload -> onSymbol(payload) },
                    onMetrics = { m -> onMetrics(m) },
                ).also { pipeline = it }

                // Thermal budget tightens the gate as the device heats.
                p.thresholds = vm.effectiveThresholds()

                measureFps(timestampNs)
                val passed = p.onFrame(
                    luma = luma,
                    rowStride = rowStride,
                    pixelStride = pixelStride,
                    motionStable = true,
                    decodable = decodable,
                )
                sessionRef?.onGateResult(passed)
                return passed
            }

            override fun onLockStateChanged(
                phase: LockPolicy.Phase,
                plan: ExposurePlan.Plan?,
            ) {
                postToMain {
                    // LOCKED is the signal that calibration succeeded: exposure,
                    // focus and white balance are frozen, so the link is usable.
                    if (phase == LockPolicy.Phase.LOCKED) {
                        vm.onCalibrationResult(true, null)
                    }
                }
            }

            override fun onStreaming() {
                postToMain { vm.onCameraStreaming() }
            }

            override fun onError(message: String, cause: Throwable?) {
                postToMain { vm.onCameraError(message, cause?.message) }
            }
        },
    )

    private var sessionRef: Camera2Session? = session

    private fun measureFps(timestampNs: Long) {
        frameCount++
        if (lastFpsNs == 0L) lastFpsNs = timestampNs
        val dt = timestampNs - lastFpsNs
        if (dt > 1_000_000_000L) {
            fps = frameCount * 1e9 / dt
            frameCount = 0
            lastFpsNs = timestampNs
        }
    }

    private fun onSymbol(payload: ByteArray) {
        // Decode against the header's key once we have one; Frames.decode
        // rejects DATA with no key, which is the desired behaviour pre-header.
        val key = vm.headerPublicKey()
        val result: DecodeResult = try {
            if (key != null) Frames.decode(payload, key) else Frames.decode(payload)
        } catch (_: Exception) {
            return
        }
        postToMain { vm.onFrameDecoded(result) }
    }

    private fun onMetrics(m: DecodePipeline.Metrics) {
        postToMain {
            vm.onCoachMetrics(
                blur = m.blur,
                contrast = m.contrast,
                motion = 0.0,
                fps = fps,
                decodeMs = m.lastDecodeMs,
                gatePass = m.reason == "ok",
                reason = m.reason,
                motionStable = true,
            )
        }
    }

    /** Opens the camera. Caller must already hold CAMERA permission. */
    fun open() {
        session.open()
    }

    /**
     * Called from the SurfaceView callback. Camera2Session defers capture-session
     * configuration until both the device and this Surface exist, so the order
     * of open()/attachPreview() no longer decides whether a preview appears.
     */
    override fun attachPreview(surface: Surface) {
        session.attachPreview(surface)
    }

    /** Start the C1 exposure/focus freeze once the CAL code is framed. */
    fun beginCalibrationLock(imageW: Int, imageH: Int) {
        session.beginCalibrationLock(null, imageW, imageH)
    }

    override fun close() {
        sessionRef = null
        session.close()
        pipeline = null
    }
}
