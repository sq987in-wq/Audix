package com.candela.render

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrEncoder {
    fun encodeBitmap(payload: ByteArray, size: Int, ecc: Char): Bitmap {
        val hints = hashMapOf<EncodeHintType, Any>(
            EncodeHintType.ERROR_CORRECTION to when (ecc) {
                'L' -> ErrorCorrectionLevel.L
                'Q' -> ErrorCorrectionLevel.Q
                'H' -> ErrorCorrectionLevel.H
                else -> ErrorCorrectionLevel.M
            },
            EncodeHintType.MARGIN to 4,
            EncodeHintType.CHARACTER_SET to "ISO-8859-1",
        )
        val text = String(payload, Charsets.ISO_8859_1)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val off = y * w
            for (x in 0 until w) {
                pixels[off + x] = if (matrix[x, y]) Color.rgb(10, 9, 8) else Color.rgb(244, 239, 230)
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }
}
