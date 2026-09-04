package com.candela.protocol

object Bytes {
    fun concat(vararg parts: ByteArray): ByteArray {
        val n = parts.sumOf { it.size }
        val out = ByteArray(n)
        var o = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, o, p.size)
            o += p.size
        }
        return out
    }

    fun u16be(n: Int): ByteArray = byteArrayOf(((n ushr 8) and 0xff).toByte(), (n and 0xff).toByte())

    fun u32be(n: Int): ByteArray = byteArrayOf(
        ((n ushr 24) and 0xff).toByte(),
        ((n ushr 16) and 0xff).toByte(),
        ((n ushr 8) and 0xff).toByte(),
        (n and 0xff).toByte(),
    )

    fun readU16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)

    fun readU32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xff) shl 24) or
            ((b[o + 1].toInt() and 0xff) shl 16) or
            ((b[o + 2].toInt() and 0xff) shl 8) or
            (b[o + 3].toInt() and 0xff)

    fun eq(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var d = 0
        for (i in a.indices) d = d or (a[i].toInt() xor b[i].toInt())
        return d == 0
    }

    fun toHex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append(String.format("%02x", x.toInt() and 0xff))
        return sb.toString()
    }

    fun formatBytes(n: Long): String {
        if (n < 1024) return "$n B"
        if (n < 1024 * 1024) return String.format("%.1f KB", n / 1024.0)
        return String.format("%.2f MB", n / (1024.0 * 1024.0))
    }

    fun xorInto(dst: ByteArray, src: ByteArray) {
        val n = minOf(dst.size, src.size)
        for (i in 0 until n) dst[i] = (dst[i].toInt() xor src[i].toInt()).toByte()
    }
}
