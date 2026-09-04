package app.candela.render

import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.View
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.EnumMap

/**
 * Display-side setup for the sender (audit section 2.2, section 6.3-6.5, PSR section 5.3).
 *
 * Everything here exists to make the panel a predictable light source:
 *
 *  - FIXED 60 Hz. On an LTPO panel the refresh rate drifts with content, and at
 *    120 Hz some panels dual-scan the display in two halves — a seam that is
 *    unfixable in software. Display.setFrameRate(60, FIXED) pins it.
 *  - MAX BRIGHTNESS. Defeats PWM dimming (which strobes the panel and interacts
 *    badly with a short camera exposure) and pre-empts the ambient-light sensor
 *    ramping brightness mid-transfer, which would invalidate the exposure the
 *    receiver locked at calibration (audit section 6.4).
 *  - KEEP_SCREEN_ON + immersive sticky. A screen timeout or a swiped-in system
 *    bar mid-transfer corrupts the symbol being held.
 */
object SenderDisplayController {

    /**
     * Pin the panel to a fixed 60 Hz.
     *
     * FRAME_RATE_COMPATIBILITY_FIXED_SOURCE tells the compositor we require this
     * exact cadence rather than merely preferring it, which is what stops LTPO
     * from dropping to 1-30 Hz on "static" content — and our content IS static
     * for 100 ms at a time, so LTPO would absolutely try.
     */
    fun pinRefreshRate(activity: Activity, hz: Float = 60f) {
        val window = activity.window ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.decorView.rootSurfaceControl // touch to ensure surface exists
            try {
                window.decorView.setFrameRate(
                    hz,
                    View.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                )
            } catch (_: Throwable) {
                legacyPreferredMode(activity, hz)
            }
        } else {
            legacyPreferredMode(activity, hz)
        }
    }

    /** Pre-R fallback: pick the display mode closest to the target rate. */
    private fun legacyPreferredMode(activity: Activity, hz: Float) {
        val window = activity.window ?: return
        @Suppress("DEPRECATION")
        val display: Display? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display
            else activity.windowManager.defaultDisplay
        val modes = display?.supportedModes ?: return
        val best = modes.minByOrNull { kotlin.math.abs(it.refreshRate - hz) } ?: return
        val lp = window.attributes
        lp.preferredDisplayModeId = best.modeId
        window.attributes = lp
    }

    /**
     * Maximum brightness for the duration of the session.
     *
     * Uses the WINDOW-level override, not Settings.System — no WRITE_SETTINGS
     * permission needed, it is automatically scoped to this window, and it is
     * restored the moment the session ends. A full-screen white QR at max
     * brightness also defeats most auto-brightness behaviour on its own.
     */
    fun setMaxBrightness(activity: Activity) {
        val window = activity.window ?: return
        val lp = window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        window.attributes = lp
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun restoreBrightness(activity: Activity) {
        val window = activity.window ?: return
        val lp = window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Immersive sticky so no system bar overlaps the quiet zone. */
    fun enterImmersive(activity: Activity) {
        val window = activity.window ?: return
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** True when the user has animations disabled, which can stall Choreographer. */
    fun animationsDisabled(activity: Activity): Boolean = try {
        Settings.Global.getFloat(
            activity.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    } catch (_: Exception) {
        false
    }
}

/**
 * Pre-rasterizes every symbol's QR exactly once (PSR section 5.3).
 *
 * Rasterizing on the frame path would allocate a Bitmap 8-12 times a second and
 * hand the GC a steady stream of multi-hundred-KB garbage — the audit's kill #5.
 * A QR Version 40 encode is well under a millisecond, so doing all of them up
 * front costs a fraction of a second at session start and nothing thereafter.
 *
 * Bitmaps are ALPHA_8 where possible: one byte per pixel instead of four, which
 * matters when holding ~190 symbols resident. The blit paint maps it to
 * black-on-white.
 *
 * The output is strictly 2-colour. No dithering, no palette, no GIF (audit
 * section 6.1 "GIF trap"): a dithered or palettised QR has destroyed module edges
 * before the light ever leaves the panel.
 */
class SymbolBitmapCache(
    private val moduleScale: Int = 3,
    private val ecc: ErrorCorrectionLevel = ErrorCorrectionLevel.L,
) {
    private val writer = QRCodeWriter()
    private val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
        put(EncodeHintType.ERROR_CORRECTION, ecc)
        put(EncodeHintType.MARGIN, 4) // the mandatory 4-module quiet zone
        put(EncodeHintType.CHARACTER_SET, "ISO-8859-1") // byte mode, no UTF-8 mangling
    }

    private val cache = HashMap<Int, Bitmap>()
    private var headerBitmap: Bitmap? = null
    private var calBitmap: Bitmap? = null

    val size: Int get() = cache.size

    fun prerender(symbols: List<ByteArray>, header: ByteArray, cal: ByteArray) {
        calBitmap = render(cal)
        headerBitmap = render(header)
        symbols.forEachIndexed { i, s -> cache[i] = render(s) }
    }

    fun bitmapFor(content: SymbolScheduler.Content): Bitmap? = when (content) {
        is SymbolScheduler.Content.Header -> headerBitmap
        is SymbolScheduler.Content.Data -> cache[content.symbolIndex]
    }

    fun calibrationBitmap(): Bitmap? = calBitmap

    private fun render(payload: ByteArray): Bitmap {
        // ISO-8859-1 is a byte-transparent mapping, so arbitrary binary survives
        // the String round-trip ZXing's API forces on us.
        val text = String(payload, Charsets.ISO_8859_1)
        val matrix = writer.encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val w = matrix.width
        val h = matrix.height
        val bmp = Bitmap.createBitmap(w * moduleScale, h * moduleScale, Bitmap.Config.ARGB_8888)
        val row = IntArray(w * moduleScale)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = if (matrix.get(x, y)) android.graphics.Color.BLACK
                else android.graphics.Color.WHITE
                val base = x * moduleScale
                for (i in 0 until moduleScale) row[base + i] = c
            }
            for (i in 0 until moduleScale) {
                bmp.setPixels(row, 0, w * moduleScale, 0, y * moduleScale + i, w * moduleScale, 1)
            }
        }
        return bmp
    }

    fun recycle() {
        cache.values.forEach { it.recycle() }
        cache.clear()
        headerBitmap?.recycle()
        calBitmap?.recycle()
        headerBitmap = null
        calBitmap = null
    }
}
