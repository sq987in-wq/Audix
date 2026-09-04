package com.candela.render

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import com.candela.protocol.BlockSource
import com.candela.protocol.Density
import com.candela.protocol.Frames
import com.candela.protocol.Protocol
import com.candela.protocol.SessionState

class SenderDisplayController(
    private val activity: Activity,
    private val view: QrSurfaceView,
    private val density: Density,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    var onState: (SessionState) -> Unit = {}
    var onProgress: (index: Int, total: Int) -> Unit = { _, _ -> }
    var sasConfirmed = false

    fun lockDisplay() {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lp = activity.window.attributes
        lp.screenBrightness = 1f
        activity.window.attributes = lp
        if (Build.VERSION.SDK_INT >= 30) {
            activity.display?.mode?.let { mode ->
                try {
                    activity.window.attributes = activity.window.attributes.apply {
                        preferredDisplayModeId = mode.modeId
                    }
                    view.holder.surface?.let { }
                    activity.window.attributes.preferredRefreshRate = 60f
                } catch (_: Exception) {}
            }
        }
        activity.window.decorView.systemUiVisibility =
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    fun unlockDisplay() {
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun start(
        source: BlockSource,
        sessionId: ByteArray,
        fileName: String,
        mime: String,
        fileHash: ByteArray,
        publicKey: ByteArray,
        secretKey: ByteArray,
        waitForSas: () -> Boolean,
    ) {
        stop()
        running = true
        lockDisplay()
        Thread({
            try {
                val encoder = source.encoder()
                val cal = Frames.encodeCal(sessionId)
                val header = Frames.encodeHeader(
                    sessionId, fileName, source.fileSize, encoder.k, source.blockSize,
                    fileHash, publicKey, mime, secretKey,
                )
                val size = 720
                onState(SessionState.CALIBRATING)
                hold(cal, density.qrEcc, size, Protocol.CALIBRATION_MS)
                if (!running) return@Thread
                onState(SessionState.PAIRING)
                hold(header, density.qrEcc, size, 2200)
                while (running && !waitForSas()) {
                    hold(header, density.qrEcc, size, density.holdMs)
                }
                if (!running) return@Thread
                onState(SessionState.SENDING)
                val total = encoder.recommendedSymbols()
                var i = 0
                while (running && i < total) {
                    if (i > 0 && i % Protocol.HEADER_INTERLEAVE == 0) {
                        hold(header, density.qrEcc, size, density.holdMs)
                        if (!running) return@Thread
                    }
                    val payload = encoder.encode(i)
                    val frame = Frames.encodeData(sessionId, i, payload, secretKey)
                    hold(frame, density.qrEcc, size, density.holdMs)
                    onProgress(i + 1, total)
                    i++
                }
                if (running) {
                    hold(header, density.qrEcc, size, density.holdMs * 2)
                    onState(SessionState.COMPLETE)
                }
            } catch (_: Exception) {
                onState(SessionState.ABORTED)
            }
        }, "candela-sender").start()
    }

    private fun hold(payload: ByteArray, ecc: Char, size: Int, ms: Long) {
        val bmp = QrEncoder.encodeBitmap(payload, size, ecc)
        handler.post { view.show(bmp) }
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        unlockDisplay()
        onState(SessionState.ABORTED)
    }
}
