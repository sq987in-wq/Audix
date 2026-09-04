package app.candela.protocol

/**
 * Big-endian byte helpers. 1:1 with src/protocol/bytes.ts.
 *
 * All multi-byte protocol fields are big-endian. Kotlin's Byte is signed, so
 * every read masks with 0xFF before widening — the single most common source of
 * JS/JVM divergence.
 */
object Bytes {

    fun concat(vararg parts: ByteArray): ByteArray {
        var n = 0
        for (p in parts) n += p.size
        val out = ByteArray(n)
        var o = 0
        for (p in parts) {
            p.copyInto(out, o)
            o += p.size
        }
        return out
    }

    fun u16be(n: Int): ByteArray =
        byteArrayOf(((n ushr 8) and 0xFF).toByte(), (n and 0xFF).toByte())

    fun u32be(n: Int): ByteArray = byteArrayOf(
        ((n ushr 24) and 0xFF).toByte(),
        ((n ushr 16) and 0xFF).toByte(),
        ((n ushr 8) and 0xFF).toByte(),
        (n and 0xFF).toByte(),
    )

    /** Long overload so 32-bit values above Int.MAX_VALUE stay unsigned. */
    fun u32be(n: Long): ByteArray = u32be((n and 0xFFFFFFFFL).toInt())

    fun readU16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    /** Returns an unsigned 32-bit value widened into a Long. */
    fun readU32(b: ByteArray, o: Int): Long =
        (((b[o].toInt() and 0xFF).toLong() shl 24) or
            ((b[o + 1].toInt() and 0xFF).toLong() shl 16) or
            ((b[o + 2].toInt() and 0xFF).toLong() shl 8) or
            (b[o + 3].toInt() and 0xFF).toLong())

    private val HEX = "0123456789abcdef".toCharArray()

    fun toHex(b: ByteArray): String {
        val out = CharArray(b.size * 2)
        for (i in b.indices) {
            val v = b[i].toInt() and 0xFF
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }

    fun fromHex(s: String): ByteArray {
        val clean = StringBuilder(s.length)
        for (ch in s) {
            if (ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F') clean.append(ch)
        }
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = ((hexVal(clean[i * 2]) shl 4) or hexVal(clean[i * 2 + 1])).toByte()
        }
        return out
    }

    private fun hexVal(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> 0
    }

    fun utf8(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

    fun utf8Decode(b: ByteArray): String = String(b, Charsets.UTF_8)

    /** Constant-time comparison — used on signature and hash paths. */
    fun eq(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var d = 0
        for (i in a.indices) d = d or (a[i].toInt() xor b[i].toInt())
        return d == 0
    }

    fun slice(b: ByteArray, from: Int, to: Int): ByteArray = b.copyOfRange(from, to)

    /**
     * UTF-8 truncation that never splits a multi-byte sequence.
     *
     * The TS reference does `fileName.slice(0, 180)` on UTF-16 code units, which
     * is a different unit. For ASCII names (the tested and expected case) the two
     * agree exactly; for non-ASCII this is the safer behaviour because it cannot
     * emit invalid UTF-8 onto the wire.
     */
    fun utf8Truncate(s: String, maxBytes: Int): ByteArray {
        val raw = utf8(s)
        if (raw.size <= maxBytes) return raw
        var end = maxBytes
        while (end > 0 && (raw[end].toInt() and 0xC0) == 0x80) end--
        return raw.copyOfRange(0, end)
    }
}
