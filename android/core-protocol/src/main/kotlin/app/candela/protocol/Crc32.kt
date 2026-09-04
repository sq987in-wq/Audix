package app.candela.protocol

/**
 * IEEE CRC32. 1:1 with src/protocol/crc32.ts.
 *
 * Implemented directly rather than delegating to java.util.zip.CRC32 so that
 * core-protocol stays a pure-Kotlin module (no java.util.zip on every target)
 * and so the polynomial/table are visible and auditable. The golden vectors
 * assert equality with the TS table-driven implementation, and Crc32Test also
 * cross-checks the standard "123456789" -> 0xCBF43926 vector.
 */
object Crc32 {

    private val TABLE = IntArray(256).also { t ->
        for (i in 0 until 256) {
            var c = i
            repeat(8) {
                c = if (c and 1 != 0) (0xEDB88320.toInt() xor (c ushr 1)) else (c ushr 1)
            }
            t[i] = c
        }
    }

    /** Returns the CRC as an unsigned 32-bit value widened into a Long. */
    fun compute(data: ByteArray, from: Int = 0, to: Int = data.size): Long {
        var c = -1 // 0xFFFFFFFF
        for (i in from until to) {
            c = TABLE[(c xor data[i].toInt()) and 0xFF] xor (c ushr 8)
        }
        return (c xor -1).toLong() and 0xFFFFFFFFL
    }

    fun bytes(data: ByteArray, from: Int = 0, to: Int = data.size): ByteArray =
        Bytes.u32be(compute(data, from, to))

    fun read(b: ByteArray, o: Int): Long = Bytes.readU32(b, o)
}
