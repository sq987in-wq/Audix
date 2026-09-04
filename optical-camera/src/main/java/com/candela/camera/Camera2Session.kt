package com.candela.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.util.concurrent.Executor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class Camera2Session(
    private val context: Context,
    private val textureView: TextureView,
) {
    interface Listener {
        fun onFrame(luma: ByteArray, width: Int, height: Int, gate: GateResult)
        fun onError(message: String)
        fun onLocked()
    }

    var listener: Listener? = null

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val openLock = Semaphore(1)
    private val started = AtomicBoolean(false)
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var decodeThread: HandlerThread? = null
    private var decodeHandler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var cameraId: String? = null
    private var previewSize: Size = Size(1280, 720)
    private var locked = false
    private var lastRoi: Rect? = null
    private val motion = MotionGate()
    private var accelListener: android.hardware.SensorEventListener? = null
    @Volatile private var decodeBusy = false

    fun updateRoi(rect: Rect?) { lastRoi = rect }
    fun motion(): MotionGate = motion

    fun start() {
        if (!started.compareAndSet(false, true)) return
        cameraThread = HandlerThread("candela-cam").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
        decodeThread = HandlerThread("candela-decode").also { it.start() }
        decodeHandler = Handler(decodeThread!!.looper)
        attachMotion()
        if (textureView.isAvailable) openCamera() else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { openCamera() }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { closeCamera(); return true }
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
    }

    fun stop() {
        started.set(false)
        closeCamera()
        detachMotion()
        cameraThread?.quitSafely(); cameraThread = null; cameraHandler = null
        decodeThread?.quitSafely(); decodeThread = null; decodeHandler = null
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        try {
            if (!openLock.tryAcquire(2, TimeUnit.SECONDS)) return
            cameraId = pickBackCamera()
            val id = cameraId ?: run { openLock.release(); listener?.onError("No camera"); return }
            val chars = manager.getCameraCharacteristics(id)
            previewSize = chooseSize(chars)
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    openLock.release()
                    startSession()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close(); device = null; openLock.release()
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close(); device = null; openLock.release()
                    listener?.onError("Camera error $error")
                }
            }, cameraHandler)
        } catch (e: Exception) {
            openLock.release()
            listener?.onError(e.message ?: "openCamera failed")
        }
    }

    private fun startSession() {
        val cam = device ?: return
        val st = textureView.surfaceTexture ?: return
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        previewSurface?.release()
        previewSurface = Surface(st)
        reader?.close()
        reader = ImageReader.newInstance(previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 3).also { ir ->
            ir.setOnImageAvailableListener({ handleImage(it) }, cameraHandler)
        }
        val surfaces = listOf(previewSurface!!, reader!!.surface)
        val cb = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                startRepeating()
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                listener?.onError("Capture session failed")
            }
        }
        if (Build.VERSION.SDK_INT >= 28) {
            val configs = surfaces.map { OutputConfiguration(it) }
            val exec = Executor { r -> cameraHandler?.post(r) }
            cam.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, configs, exec, cb))
        } else {
            @Suppress("DEPRECATION")
            cam.createCaptureSession(surfaces, cb, cameraHandler)
        }
    }

    private fun startRepeating() {
        val cam = device ?: return
        val sess = session ?: return
        val builder = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        builder.addTarget(previewSurface!!)
        builder.addTarget(reader!!.surface)
        applyFreeze(builder, lock = false)
        sess.setRepeatingRequest(builder.build(), null, cameraHandler)
        cameraHandler?.postDelayed({ lockAeAf() }, 900)
    }

    private fun lockAeAf() {
        val cam = device ?: return
        val sess = session ?: return
        try {
            val builder = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(previewSurface!!)
            builder.addTarget(reader!!.surface)
            applyFreeze(builder, lock = true)
            sess.setRepeatingRequest(builder.build(), null, cameraHandler)
            locked = true
            listener?.onLocked()
        } catch (_: Exception) {}
    }

    private fun applyFreeze(builder: CaptureRequest.Builder, lock: Boolean) {
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
        builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
        builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        if (lock) {
            builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, true)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            lastRoi?.let { r ->
                val mr = MeteringRectangle(r, MeteringRectangle.METERING_WEIGHT_MAX)
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(mr))
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(mr))
            }
        }
    }

    private fun handleImage(reader: ImageReader) {
        val image = try { reader.acquireLatestImage() } catch (_: Exception) { null } ?: return
        try {
            if (decodeBusy) return
            val (luma, w, h) = YuvLuma.extract(image)
            val gate = FrameGates.evaluateLuma(luma, w, h)
            if (!gate.pass || !motion.stable) {
                listener?.onFrame(luma, w, h, gate)
                return
            }
            decodeBusy = true
            decodeHandler?.post {
                try { listener?.onFrame(luma, w, h, gate) } finally { decodeBusy = false }
            }
        } finally {
            image.close()
        }
    }

    private fun pickBackCamera(): String? {
        for (id in manager.cameraIdList) {
            val facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) return id
        }
        return manager.cameraIdList.firstOrNull()
    }

    private fun chooseSize(chars: CameraCharacteristics): Size {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return Size(1280, 720)
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888) ?: return Size(1280, 720)
        return sizes.filter { it.width * it.height <= 1280 * 720 }
            .minByOrNull { kotlin.math.abs(it.width * it.height - 1280 * 720) }
            ?: sizes.minBy { it.width * it.height }
    }

    private fun closeCamera() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { device?.close() } catch (_: Exception) {}
        device = null
        reader?.close(); reader = null
        previewSurface?.release(); previewSurface = null
        if (openLock.availablePermits() == 0) openLock.release()
    }

    private fun attachMotion() {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val sensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        accelListener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(event: android.hardware.SensorEvent) {
                motion.push(event.values[0], event.values[1], event.values[2])
            }
            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
        }
        sensor?.let { sm.registerListener(accelListener, it, android.hardware.SensorManager.SENSOR_DELAY_GAME) }
    }

    private fun detachMotion() {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        accelListener?.let { sm.unregisterListener(it) }
        accelListener = null
    }
}
