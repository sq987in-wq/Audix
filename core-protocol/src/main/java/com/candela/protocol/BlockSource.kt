package com.candela.protocol

import java.io.InputStream
import java.security.MessageDigest

/**
 * Streaming block source: hashes while reading, caches only a sliding window of
 * recently used fountain source blocks. Never rasters the entire file into bitmaps.
 */
class BlockSource(
    private val open: () -> InputStream,
    val fileSize: Long,
    val blockSize: Int,
) {
    val k: Int = maxOf(1, ((fileSize + blockSize - 1) / blockSize).toInt())
    private val cache = object : LinkedHashMap<Int, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?): Boolean = size > 24
    }

    fun sha256(): ByteArray {
        return Crypto.sha256Streaming { md ->
            open().use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
        }
    }

    @Synchronized
    fun block(index: Int): ByteArray {
        cache[index]?.let { return it }
        val out = ByteArray(blockSize)
        open().use { input ->
            var remaining = index.toLong() * blockSize
            val skipBuf = ByteArray(16 * 1024)
            while (remaining > 0) {
                val n = input.read(skipBuf, 0, minOf(skipBuf.size.toLong(), remaining).toInt())
                if (n <= 0) break
                remaining -= n
            }
            var filled = 0
            while (filled < blockSize) {
                val n = input.read(out, filled, blockSize - filled)
                if (n <= 0) break
                filled += n
            }
        }
        cache[index] = out
        return out
    }

    fun encoder(): FountainEncoder = FountainEncoder(
        source = { i -> block(i) },
        k = k,
        blockSize = blockSize,
        fileSize = fileSize,
    )
}
