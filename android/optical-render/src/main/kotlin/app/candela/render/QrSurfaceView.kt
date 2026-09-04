package app.candela.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * The QR transmit plane (audit section 8 kill #5, PSR section 5.3).
 *
 * WHY A SURFACEVIEW AND NOT COMPOSE. The audit lists "per-frame Compose
 * recomposition / bitmap allocation" as one of the five things that kill this
 * product. Compose is a UI shell; a composable that swaps a QR image 8-12 times a
 * second drags the whole recomposition machinery (and often a Bitmap allocation
 * plus a GC pause) onto the frame path. Jank on the sender is not cosmetic here:
 * a dropped frame during a symbol hold is a torn symbol on the receiver.
 *
 * So the QR plane is a dedicated SurfaceView on its own composited layer, drawing
 * pre-rasterized bitmaps with a single drawBitmap per changed symbol. Compose may
 * own everything around it; it must never own this.
 *
 * ZERO-ALLOCATION CONTRACT, enforced by construction:
 *  - All symbol bitmaps are rasterized ONCE up front by [SymbolBitmapCache].
 *  - The draw path allocates nothing: [srcRect]/[dstRect]/[paint] are fields.
 *  - Choreographer callbacks that fall inside a hold return without locking the
 *    canvas at all. At 60 Hz with a 6-frame hold that is 5 of every 6 vsyncs
 *    doing literally nothing.
 *  - drawBitmap uses integer-aligned destination rects with filtering OFF, so
 *    module edges stay hard. Bilinear filtering would smear exactly the
 *    transitions the receiver's binarizer depends on.
 */
class QrSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback, Choreographer.FrameCallback {

    /** Supplies the bitmap for a scheduled slot. Must not allocate. */
    interface Source {
        fun bitmapFor(content: SymbolScheduler.Content): Bitmap?
        fun onSlotAdvanced(content: SymbolScheduler.Content, slot: Int) {}
    }

    private val paint = Paint().apply {
        isFilterBitmap = false // hard module edges — see class docs
        isAntiAlias = false
        isDither = false
    }
    private val srcRect = Rect()
    private val dstRect = Rect()

    private var scheduler: SymbolScheduler? = null
    private var source: Source? = null
    private var running = false
    private var surfaceReady = false

    /**
     * Quiet-zone-safe bounds. Notches, rounded corners and the gesture pill crop
     * the 4-module quiet zone a QR needs (audit section 6.3), so the caller passes
     * WindowInsets-derived padding and the QR is laid out strictly inside it.
     */
    private var safeInsetLeft = 0
    private var safeInsetTop = 0
    private var safeInsetRight = 0
    private var safeInsetBottom = 0

    var framesDrawn: Long = 0L
        private set
    var vsyncsSkipped: Long = 0L
        private set

    init {
        holder.addCallback(this)
        setWillNotDraw(true) // we never use onDraw; all drawing is via the holder
    }

    fun configure(scheduler: SymbolScheduler, source: Source) {
        this.scheduler = scheduler
        this.source = source
    }

    fun setSafeInsets(left: Int, top: Int, right: Int, bottom: Int) {
        safeInsetLeft = left
        safeInsetTop = top
        safeInsetRight = right
        safeInsetBottom = bottom
    }

    fun start(nowNs: Long = System.nanoTime()) {
        if (running) return
        running = true
        scheduler?.start(nowNs)
        framesDrawn = 0
        vsyncsSkipped = 0
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    // ------------------------------------------------------------- vsync loop

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        // Re-post FIRST so a slow draw cannot stall the loop.
        Choreographer.getInstance().postFrameCallback(this)

        val sched = scheduler ?: return
        if (!surfaceReady) return

        // The zero-work path: inside a hold, there is nothing to do.
        if (!sched.needsRedraw(frameTimeNanos)) {
            vsyncsSkipped++
            return
        }
        val content = sched.contentAt(frameTimeNanos)
        val bitmap = source?.bitmapFor(content) ?: return
        blit(bitmap)
        source?.onSlotAdvanced(content, sched.slotAt(frameTimeNanos))
        framesDrawn++
    }

    private fun blit(bitmap: Bitmap) {
        val canvas = holder.lockHardwareCanvasCompat() ?: return
        try {
            canvas.drawColor(Color.WHITE) // quiet zone must be white, not black
            layoutInto(canvas.width, canvas.height, bitmap)
            canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    /**
     * Integer-multiple scaling inside the safe area.
     *
     * A non-integer scale resamples module boundaries into grey pixels, which
     * costs contrast at exactly the frequency the receiver is trying to resolve.
     * Snapping to a whole-pixel multiple keeps every module identical.
     */
    private fun layoutInto(viewW: Int, viewH: Int, bitmap: Bitmap) {
        srcRect.set(0, 0, bitmap.width, bitmap.height)

        val availW = viewW - safeInsetLeft - safeInsetRight
        val availH = viewH - safeInsetTop - safeInsetBottom
        val side = minOf(availW, availH)
        if (side <= 0 || bitmap.width == 0) {
            dstRect.set(0, 0, viewW, viewH)
            return
        }
        val scale = maxOf(1, side / bitmap.width)
        val drawn = bitmap.width * scale
        val left = safeInsetLeft + (availW - drawn) / 2
        val top = safeInsetTop + (availH - drawn) / 2
        dstRect.set(left, top, left + drawn, top + drawn)
    }

    // --------------------------------------------------------- surface callbacks

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceReady = true
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
    }

    private fun SurfaceHolder.lockHardwareCanvasCompat(): Canvas? = try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            lockHardwareCanvas()
        } else {
            lockCanvas()
        }
    } catch (_: IllegalStateException) {
        null
    }
}
