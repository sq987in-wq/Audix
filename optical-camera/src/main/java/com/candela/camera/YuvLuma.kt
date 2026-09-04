package com.candela.camera

import android.media.Image

object YuvLuma {
    fun extract(image: Image): Triple<ByteArray, Int, Int> {
        val plane = image.planes[0]
        val w = image.width
        val h = image.height
        val rowStride = plane.rowStride
        val buf = plane.buffer
        val out = ByteArray(w * h)
        if (rowStride == w) {
            buf.get(out, 0, w * h)
        } else {
            val row = ByteArray(rowStride)
            var dst = 0
            for (y in 0 until h) {
                buf.position(y * rowStride)
                buf.get(row, 0, minOf(rowStride, buf.remaining()))
                System.arraycopy(row, 0, out, dst, w)
                dst += w
            }
        }
        return Triple(out, w, h)
    }
}
