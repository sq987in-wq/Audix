package com.candela.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class FountainTest {
    @Test
    fun roundTripWithDrops() {
        val data = ByteArray(8192).also { SecureRandom().nextBytes(it) }
        val enc = FountainEncoder(data, 80)
        val dec = FountainDecoder(enc.k, 80, data.size.toLong())
        var i = 0
        val rng = SecureRandom()
        while (!dec.isComplete() && i < enc.recommendedSymbols() + enc.k) {
            if (rng.nextDouble() > 0.22) {
                dec.ingest(i, enc.encode(i))
            }
            i++
        }
        assertTrue(dec.isComplete())
        assertArrayEquals(data, dec.assemble())
        assertArrayEquals(Crypto.sha256(data), Crypto.sha256(dec.assemble()!!))
    }

    @Test
    fun framesSignAndVerify() {
        val keys = Crypto.generateKeyPair()
        val sid = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val hash = Crypto.sha256("hello".toByteArray())
        val header = Frames.encodeHeader(sid, "t.txt", 5, 1, 80, hash, keys.publicKey, "text/plain", keys.secretKey)
        val decoded = Frames.decode(header) as DecodedFrame.Header
        assertTrue(decoded.fileName == "t.txt")
        val payload = ByteArray(80)
        val data = Frames.encodeData(sid, 0, payload, keys.secretKey)
        val d = Frames.decode(data, keys.publicKey) as DecodedFrame.Data
        assertTrue(d.symbolId == 0)
    }
}
