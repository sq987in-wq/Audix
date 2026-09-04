package com.candela.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.concurrent.atomic.AtomicReference

class QrSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private val current = AtomicReference<Bitmap?>(null)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    @Volatile private var surfaceReady = false

    init {
        holder.addCallback(this)
        setZOrderOnTop(false)
        setBackgroundColor(Color.rgb(244, 239, 230))
    }

    fun show(bitmap: Bitmap) {
        current.set(bitmap)
        drawNow()
    }

    private fun drawNow() {
        if (!surfaceReady) return
        val canvas: Canvas = try {
            holder.lockCanvas() ?: return
        } catch (_: Exception) {
            return
        }
        try {
            canvas.drawColor(Color.rgb(244, 239, 230))
            val bmp = current.get() ?: return
            val side = minOf(canvas.width, canvas.height)
            val left = (canvas.width - side) / 2
            val top = (canvas.height - side) / 2
            canvas.drawBitmap(bmp, null, Rect(left, top, left + side, top + side), paint)
        } finally {
            try { holder.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        drawNow()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceReady = true
        drawNow()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
    }
}
