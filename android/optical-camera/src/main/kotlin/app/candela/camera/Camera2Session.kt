package app.candela.camera

import android.Manifest
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresPermission
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Camera2 receiver implementing the C1 freeze (audit section 1.2 / PSR section 5.1).
 *
 * This is THE highest-ROI component in the product. The audit is unambiguous:
 * freezing camera state converts "a 30 fps stream of garbage" into "a 30 fps
 * stream of sharp frames", and no amount of downstream cleverness substitutes.
 *
 * WHY CAMERA2 AND NOT CAMERAX. CameraX cannot express what C1 requires: it owns
 * the 3A state machine, re-runs AE on its own schedule, and offers no way to pin
 * LENS_FOCUS_DISTANCE or to set CONTROL_MODE=OFF with explicit
 * SENSOR_EXPOSURE_TIME/SENSITIVITY. Those are the whole countermeasure.
 *
 * WHY NOT ML KIT (decoder side). "Completely offline" dies the moment Play
 * Services is a dependency, and the target audience is air-gapped/AOSP/enterprise
 * devices where GMS may be absent. ZXing-core only (audit C4, section 6.2).
 *
 * Frames flow: ImageReader -> gate -> ROI -> decoder, on a dedicated
 * HandlerThread. Nothing in the hot path touches the main thread, and nothing
 * allocates per frame.
 */
class Camera2Session(
    private val context: Context,
    private val callbacks: Callbacks,
) {

    interface Callbacks {
        /**
         * Called on the camera HandlerThread for every frame that ARRIVES.
         * Implementations must apply the gate and return quickly; the Image is
         * recycled the instant this returns.
         *
         * @return true if the frame was accepted for decoding (diagnostics only)
         */
        fun onLumaFrame(
            luma: ByteArray,
            width: Int,
            height: Int,
            rowStride: Int,
            pixelStride: Int,
            timestampNs: Long,
            decodable: Boolean,
        ): Boolean

        fun onLockStateChanged(phase: LockPolicy.Phase, plan: ExposurePlan.Plan?)
        fun onError(message: String, cause: Throwable?)

        /**
         * The repeating request is live and the sensor is delivering.
         *
         * Reported explicitly because "preview is black" is otherwise
         * indistinguishable from "session never configured", "request never
         * looped" and "frames arriving but the scene is dark". The UI shows a
         * real diagnostic instead of a black rectangle.
         */
        fun onStreaming() {}
    }

    private val cameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null

    private var characteristics: CameraCharacteristics? = null
    private var cameraId: String? = null

    val lockPolicy = LockPolicy()
    private val closing = AtomicBoolean(false)

    /** Set on the first completed capture; drives Callbacks.onStreaming. */
    @Volatile
    private var streaming = false

    /**
     * Reused luma scratch. ImageReader hands back a direct ByteBuffer whose
     * lifetime ends with the Image, so we copy once into a buffer we own — that
     * copy is the only per-frame memory traffic, and it is reused.
     */
    private var lumaScratch = ByteArray(0)

    private var sensorArrayW = 0
    private var sensorArrayH = 0
    private var meteringRect: IntRect? = null

    // ---------------------------------------------------------------- lifecycle

    @RequiresPermission(Manifest.permission.CAMERA)
    fun open(targetSize: Size = Size(1920, 1080)) {
        closing.set(false)
        val t = HandlerThread("candela-camera").also { it.start() }
        thread = t
        handler = Handler(t.looper)

        val id = selectBackCamera() ?: run {
            callbacks.onError("No back-facing camera available", null)
            return
        }
        cameraId = id
        val chars = cameraManager.getCameraCharacteristics(id)
        characteristics = chars

        chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let {
            sensorArrayW = it.width()
            sensorArrayH = it.height()
        }

        val size = chooseSize(chars, targetSize)
        // maxImages=3 gives C5 burst-of-3 for free: if the blur gate is marginal
        // we can keep the sharpest of the buffered frames at zero extra cost.
        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
        r.setOnImageAvailableListener({ ir -> drainReader(ir) }, handler)
        reader = r

        try {
            cameraManager.openCamera(id, deviceCallback, handler)
        } catch (e: SecurityException) {
            callbacks.onError("CAMERA permission not granted", e)
        } catch (e: Exception) {
            callbacks.onError("Failed to open camera", e)
        }
    }

    fun close() {
        if (!closing.compareAndSet(false, true)) return
        try {
            session?.close()
            device?.close()
            reader?.close()
        } catch (_: Exception) {
        } finally {
            session = null
            device = null
            reader = null
            thread?.quitSafely()
            thread = null
            handler = null
            previewSurface = null
            streaming = false
            lockPolicy.reset()
        }
    }

    private fun selectBackCamera(): String? =
        cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cameraManager.cameraIdList.firstOrNull()

    private fun chooseSize(chars: CameraCharacteristics, target: Size): Size {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return target
        val options = map.getOutputSizes(ImageFormat.YUV_420_888) ?: return target
        // Prefer the requested size; otherwise the largest that stays <= 1080p.
        // Beyond 1080p costs bandwidth and heat for module detail the optics of a
        // 15-40 cm handheld shot cannot deliver anyway.
        return options.firstOrNull { it.width == target.width && it.height == target.height }
            ?: options.filter { it.width <= 1920 && it.height <= 1080 }
                .maxByOrNull { it.width.toLong() * it.height }
            ?: options.first()
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            device = camera
            createSession(camera)
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            device = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            device = null
            callbacks.onError("Camera device error $error", null)
        }
    }

    /**
     * Configure the capture session.
     *
     * THE ORDERING BUG THIS GUARDS AGAINST. A capture session's output set is
     * fixed at configuration time; a Surface added later is simply not a target,
     * and the preview stays black forever with no error anywhere. But the device
     * opens asynchronously and the SurfaceView's Surface is created
     * asynchronously, so whichever loses the race used to decide whether the
     * user ever saw a preview.
     *
     * So configuration is deferred until BOTH are present: onOpened calls this,
     * and so does attachPreview. Whichever arrives second does the work.
     */
    private fun createSession(camera: CameraDevice) {
        if (closing.get()) return
        val preview = previewSurface
        if (preview == null) {
            // Device is ready, Surface is not. attachPreview will re-enter.
            return
        }
        if (!preview.isValid) {
            callbacks.onError("Preview surface is not valid", null)
            return
        }
        if (session != null) return // already configured for this surface

        val surfaces = listOfNotNull(reader?.surface, preview)
        try {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (closing.get()) {
                        s.close()
                        return
                    }
                    session = s
                    startPreview()
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    callbacks.onError("Capture session configuration failed", null)
                }
            }, handler)
        } catch (e: Exception) {
            callbacks.onError("Failed to create capture session", e)
        }
    }

    /**
     * Hand the preview Surface to the session.
     *
     * Safe to call before or after [open]; it triggers configuration if the
     * device is already open, and is a no-op if the surface is unchanged.
     */
    fun attachPreview(surface: Surface) {
        if (previewSurface == surface && session != null) return
        previewSurface = surface
        val cam = device ?: return // open() will configure once onOpened fires
        // The surface changed after a session existed (e.g. the view was
        // recreated): tear the old session down so the new target takes effect.
        session?.let {
            runCatching { it.stopRepeating() }
            runCatching { it.close() }
            session = null
        }
        val h = handler
        if (h != null) h.post { createSession(cam) } else createSession(cam)
    }

    // ------------------------------------------------------------- request build

    private fun baseRequest(): CaptureRequest.Builder? {
        val cam = device ?: return null
        val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        reader?.surface?.let { b.addTarget(it) }
        previewSurface?.let { b.addTarget(it) }
        applyInvariants(b)
        return b
    }

    /**
     * Settings that are wrong for a QR stream regardless of lock phase
     * (audit section 5.1 steps 5-7).
     *
     * EIS is the important one: it warps the module grid geometrically and adds
     * pipeline latency. OIS is left ON because it stabilises without resampling.
     */
    private fun applyInvariants(b: CaptureRequest.Builder) {
        b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
        b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
        b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_FAST)
        b.set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
        )
        b.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_DISABLED)
        b.set(CaptureRequest.CONTROL_EFFECT_MODE, CameraMetadata.CONTROL_EFFECT_MODE_OFF)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // ZSL re-runs AE behind our back, undoing the freeze.
            b.set(CaptureRequest.CONTROL_ENABLE_ZSL, false)
        }
        // Cap the frame rate at 30: nothing downstream can use more, and the
        // unused frames are pure heat.
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(15, 30))
    }

    private fun startPreview() {
        val b = baseRequest() ?: run {
            callbacks.onError("Could not build preview request", null)
            return
        }
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        b.set(CaptureRequest.CONTROL_AE_LOCK, false)
        b.set(CaptureRequest.CONTROL_AWB_LOCK, false)
        try {
            session?.setRepeatingRequest(b.build(), captureCallback, handler)
        } catch (e: Exception) {
            // Without this the loop silently never starts and the preview is
            // black with nothing in logcat pointing at the cause.
            callbacks.onError("Failed to start preview stream", e)
        }
    }

    // ------------------------------------------------------------ the C1 freeze

    /**
     * Begin the lock sequence. Call once the calibration CAL QR is stably framed.
     * @param roi the tracked QR rect, used for AE/AF metering regions
     */
    fun beginCalibrationLock(roi: IntRect?, imageW: Int, imageH: Int) {
        if (roi != null && sensorArrayW > 0) {
            meteringRect = roi.toMeteringRect(imageW, imageH, sensorArrayW, sensorArrayH)
        }
        when (lockPolicy.beginLock()) {
            is LockPolicy.Action.StartAfTrigger -> triggerAf()
            else -> Unit
        }
        callbacks.onLockStateChanged(lockPolicy.phase, null)
    }

    private fun triggerAf() {
        val b = baseRequest() ?: return
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        applyMeteringRegions(b)
        session?.setRepeatingRequest(b.build(), captureCallback, handler)

        b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
        session?.capture(b.build(), captureCallback, handler)
    }

    /** Meter on the QR, not the whole scene (audit E1 / section 5.1 step 8). */
    private fun applyMeteringRegions(b: CaptureRequest.Builder) {
        val m = meteringRect ?: return
        val chars = characteristics ?: return
        val rect = MeteringRectangle(
            m.x, m.y, m.width, m.height, MeteringRectangle.METERING_WEIGHT_MAX - 1,
        )
        if ((chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0) {
            b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(rect))
        }
        if ((chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0) {
            b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(rect))
        }
    }

    private fun pinFocus(distance: Float) {
        val b = baseRequest() ?: return
        b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
        session?.capture(b.build(), null, handler)

        b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
        b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        b.set(CaptureRequest.LENS_FOCUS_DISTANCE, distance)
        session?.setRepeatingRequest(b.build(), captureCallback, handler)
    }

    /**
     * Apply the exposure freeze. Full manual when the device supports it,
     * AE_LOCK otherwise — a LEGACY device still benefits from a locked AE even
     * though we cannot pick the exposure ourselves.
     */
    private fun applyFreeze(plan: ExposurePlan.Plan) {
        val b = baseRequest() ?: return
        val chars = characteristics
        val manual = chars?.let { supportsManualSensor(it) } ?: false

        if (manual && plan.strategy != null) {
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, plan.exposureTimeNs)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, plan.iso)
            chars?.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)?.let {
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, minOf(33_333_333L, it))
            }
        } else {
            b.set(CaptureRequest.CONTROL_AE_LOCK, true)
        }
        b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
        b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        b.set(CaptureRequest.LENS_FOCUS_DISTANCE, lockPolicy.focusDistance)
        session?.setRepeatingRequest(b.build(), captureCallback, handler)
        callbacks.onLockStateChanged(lockPolicy.phase, plan)
    }

    private fun supportsManualSensor(chars: CameraCharacteristics): Boolean {
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return false
        return caps.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR,
        )
    }

    fun sensorLimits(): ExposurePlan.SensorLimits {
        val chars = characteristics
        val expRange = chars?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val isoRange = chars?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        return ExposurePlan.SensorLimits(
            minExposureNs = expRange?.lower ?: 100_000L,
            maxExposureNs = expRange?.upper ?: 100_000_000L,
            minIso = isoRange?.lower ?: 100,
            maxIso = isoRange?.upper ?: 3200,
            manualSensorSupported = chars?.let { supportsManualSensor(it) } ?: false,
        )
    }

    /**
     * Feed gate results back so the policy can decide on a re-lock.
     * Re-lock happens ONLY on sustained degradation, never on a timer — a timer
     * restarts AF mid-symbol and, because fountain codes hide the failure, the
     * lens hunts forever with no corrective signal.
     */
    fun onGateResult(passed: Boolean) {
        when (lockPolicy.onGateMetrics(passed)) {
            is LockPolicy.Action.StartAfTrigger -> {
                triggerAf()
                callbacks.onLockStateChanged(lockPolicy.phase, null)
            }
            else -> Unit
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            s: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            if (closing.get()) return
            if (!streaming) {
                streaming = true
                callbacks.onStreaming()
            }
            val afState = result.get(CaptureResult.CONTROL_AF_STATE)
            val lensDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f

            when (lockPolicy.phase) {
                LockPolicy.Phase.AF_CONVERGING -> {
                    val focused = afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                    val failed = afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                    when (val a = lockPolicy.onAfState(focused, failed, lensDistance)) {
                        is LockPolicy.Action.PinFocus -> {
                            pinFocus(a.focusDistance)
                            val plan = ExposurePlan.choose(
                                meteredExposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                                    ?: 16_666_667L,
                                meteredIso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 400,
                                screenPeriodNs = HZ60_PERIOD_NS,
                                limits = sensorLimits(),
                            )
                            when (val f = lockPolicy.onFocusPinned(plan)) {
                                is LockPolicy.Action.ApplyFreeze -> applyFreeze(f.plan)
                                else -> Unit
                            }
                        }
                        else -> Unit
                    }
                }
                else -> Unit
            }
        }
    }

    // ------------------------------------------------------------- frame plumbing

    /**
     * Drain the reader, keeping only the newest image.
     *
     * Frames older than ~150 ms are worthless (the symbol on screen has already
     * changed), so buffering them just adds latency and heat. This is the
     * DROP_OLDEST policy from PSR section 5.2, applied at the source.
     */
    private fun drainReader(ir: ImageReader) {
        // acquireLatestImage() is a platform type (Image!) and genuinely returns
        // null when no new frame is queued, so the nullable annotation is load
        // bearing. IllegalStateException means the caller has not closed enough
        // images -- treated as "no frame" rather than crashing the camera thread.
        val image: Image? = try {
            ir.acquireLatestImage()
        } catch (_: IllegalStateException) {
            null
        }
        if (image == null) return

        try {
            val plane = image.planes[0]
            val buf = plane.buffer
            val needed = buf.remaining()
            if (lumaScratch.size < needed) lumaScratch = ByteArray(needed)
            buf.get(lumaScratch, 0, needed)

            callbacks.onLumaFrame(
                luma = lumaScratch,
                width = image.width,
                height = image.height,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
                timestampNs = image.timestamp,
                decodable = lockPolicy.isDecodable,
            )
        } catch (e: Exception) {
            callbacks.onError("Frame processing failed", e)
        } finally {
            // Closing is mandatory, not hygiene: the ImageReader has a fixed
            // buffer count and leaking even one image permanently starves the
            // pipeline. The local itself is dead here, so it is not reassigned.
            image.close()
        }
    }

    companion object {
        const val HZ60_PERIOD_NS = 16_666_667L
    }
}
