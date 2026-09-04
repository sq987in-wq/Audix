package com.candela.protocol

object Crc32 {
    private val TABLE = IntArray(256) { i ->
        var c = i
        repeat(8) { c = if (c and 1 != 0) -0x12477ce0 xor (c ushr 1) else c ushr 1 }
        c
    }

    fun of(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        var c = -1
        for (i in offset until offset + length) {
            c = TABLE[(c xor (data[i].toInt() and 0xff)) and 0xff] xor (c ushr 8)
        }
        return c.inv()
    }

    fun bytes(data: ByteArray): ByteArray {
        val v = of(data)
        return byteArrayOf(
            ((v ushr 24) and 0xff).toByte(),
            ((v ushr 16) and 0xff).toByte(),
            ((v ushr 8) and 0xff).toByte(),
            (v and 0xff).toByte(),
        )
    }

    fun read(b: ByteArray, o: Int): Int {
        return ((b[o].toInt() and 0xff) shl 24) or
            ((b[o + 1].toInt() and 0xff) shl 16) or
            ((b[o + 2].toInt() and 0xff) shl 8) or
            (b[o + 3].toInt() and 0xff)
    }
}
