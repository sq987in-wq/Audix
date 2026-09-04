package com.candela.camera

import android.graphics.Rect
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.nio.charset.StandardCharsets

data class DecodeHit(
    val bytes: ByteArray,
    val rect: Rect,
)

object ZxingRoiDecoder {
    private val reader = QRCodeReader()
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "ISO-8859-1",
    )

    @Synchronized
    fun decode(luma: ByteArray, width: Int, height: Int, roi: Rect?): DecodeHit? {
        val left = (roi?.left ?: (width * 0.1f).toInt()).coerceIn(0, width - 8)
        val top = (roi?.top ?: (height * 0.1f).toInt()).coerceIn(0, height - 8)
        val rw = (roi?.width() ?: (width * 0.8f).toInt()).coerceIn(8, width - left)
        val rh = (roi?.height() ?: (height * 0.8f).toInt()).coerceIn(8, height - top)
        val source = PlanarYUVLuminanceSource(luma, width, height, left, top, rw, rh, false)
        return try {
            val result = reader.decode(BinaryBitmap(HybridBinarizer(source)), hints)
            reader.reset()
            val bytes = result.getRawBytes()?.takeIf { it.isNotEmpty() }
                ?: result.text.toByteArray(StandardCharsets.ISO_8859_1)
            val pts = result.resultPoints
            val rect = if (pts != null && pts.size >= 3) {
                val xs = pts.map { it.x }
                val ys = pts.map { it.y }
                Rect(xs.min().toInt() + left, ys.min().toInt() + top, xs.max().toInt() + left, ys.max().toInt() + top)
            } else {
                Rect(left, top, left + rw, top + rh)
            }
            DecodeHit(bytes, rect)
        } catch (_: Exception) {
            reader.reset()
            null
        }
    }
}
