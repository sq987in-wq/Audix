package app.candela.protocol

import java.math.BigInteger
import java.security.MessageDigest

/**
 * RFC 8032 Ed25519 (pure Kotlin, BigInteger-backed).
 *
 * Why not java.security: JDK 15+ ships EdDSA, but on Android `java.security`
 * Ed25519 is only guaranteed from API 33. minSdk for this product is 26, and the
 * audit forbids pulling in Play Services. A self-contained implementation keeps
 * core-protocol dependency-free, runs identically on desktop JVM and every
 * supported Android level, and stays fully testable in CI without an SDK.
 *
 * Signing is deterministic (RFC 8032 derives the nonce from the key and message),
 * so signatures are byte-identical to @noble/ed25519 in the TypeScript reference.
 * The golden vectors assert exactly that.
 *
 * Performance note: BigInteger point arithmetic costs roughly 1-3 ms per verify
 * on a midrange phone versus the audit's 40-80 us budget for a native
 * implementation. At 8-12 symbols/s that is under 4% of one core, comfortably
 * inside the thermal budget. If profiling on-device shows otherwise, swap in a
 * field-element implementation behind [SignatureProvider] without touching the
 * protocol layer.
 */
object Ed25519 : SignatureProvider {

    private val P: BigInteger = BigInteger.TWO.pow(255) - BigInteger.valueOf(19)
    private val L: BigInteger =
        BigInteger.TWO.pow(252) + BigInteger("27742317777372353535851937790883648493")
    private val D: BigInteger = BigInteger.valueOf(-121665)
        .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
    private val I: BigInteger = BigInteger.TWO.modPow(P - BigInteger.ONE shr 2, P)

    private val ZERO: BigInteger = BigInteger.ZERO
    private val ONE: BigInteger = BigInteger.ONE

    /** Extended homogeneous coordinates (X : Y : Z : T). */
    private class Pt(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    private val BY: BigInteger = BigInteger.valueOf(4)
        .multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)
    private val BX: BigInteger = recoverX(BY, false)
    private val B: Pt = Pt(BX, BY, ONE, BX.multiply(BY).mod(P))
    private val IDENTITY: Pt = Pt(ZERO, ONE, ONE, ZERO)

    private fun recoverX(y: BigInteger, xIsOdd: Boolean): BigInteger {
        val y2 = y.multiply(y).mod(P)
        val u = y2.subtract(ONE).mod(P)
        val v = D.multiply(y2).add(ONE).mod(P)
        val uv3 = u.multiply(v.modPow(BigInteger.valueOf(3), P)).mod(P)
        val uv7 = u.multiply(v.modPow(BigInteger.valueOf(7), P)).mod(P)
        var x = uv3.multiply(uv7.modPow((P - BigInteger.valueOf(5)) shr 3, P)).mod(P)
        val vx2 = v.multiply(x).multiply(x).mod(P)
        if (vx2 != u.mod(P)) {
            if (vx2 == u.negate().mod(P)) {
                x = x.multiply(I).mod(P)
            } else {
                throw IllegalArgumentException("point is not on curve")
            }
        }
        if (x.testBit(0) != xIsOdd) x = P.subtract(x).mod(P)
        return x
    }

    private fun add(a: Pt, b: Pt): Pt {
        val aa = a.y.subtract(a.x).multiply(b.y.subtract(b.x)).mod(P)
        val bb = a.y.add(a.x).multiply(b.y.add(b.x)).mod(P)
        val cc = a.t.multiply(BigInteger.TWO).multiply(D).multiply(b.t).mod(P)
        val dd = a.z.multiply(BigInteger.TWO).multiply(b.z).mod(P)
        val e = bb.subtract(aa)
        val f = dd.subtract(cc)
        val g = dd.add(cc)
        val h = bb.add(aa)
        return Pt(
            e.multiply(f).mod(P),
            g.multiply(h).mod(P),
            f.multiply(g).mod(P),
            e.multiply(h).mod(P),
        )
    }

    private fun mul(pIn: Pt, sIn: BigInteger): Pt {
        var q = IDENTITY
        var p = pIn
        var s = sIn
        while (s > ZERO) {
            if (s.testBit(0)) q = add(q, p)
            p = add(p, p)
            s = s shr 1
        }
        return q
    }

    private fun encodePoint(p: Pt): ByteArray {
        val zi = p.z.modInverse(P)
        val x = p.x.multiply(zi).mod(P)
        val y = p.y.multiply(zi).mod(P)
        val out = le32(y)
        if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    private fun decodePoint(enc: ByteArray): Pt? {
        if (enc.size != 32) return null
        val tmp = enc.copyOf()
        val xOdd = (tmp[31].toInt() and 0x80) != 0
        tmp[31] = (tmp[31].toInt() and 0x7F).toByte()
        val y = leInt(tmp)
        if (y >= P) return null
        val x = try {
            recoverX(y, xOdd)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return Pt(x, y, ONE, x.multiply(y).mod(P))
    }

    private fun le32(v: BigInteger): ByteArray {
        val out = ByteArray(32)
        var t = v.mod(P)
        for (i in 0 until 32) {
            out[i] = t.and(BigInteger.valueOf(0xFF)).toInt().toByte()
            t = t shr 8
        }
        return out
    }

    private fun leInt(b: ByteArray): BigInteger {
        var r = ZERO
        for (i in b.indices.reversed()) {
            r = r.shl(8).add(BigInteger.valueOf((b[i].toInt() and 0xFF).toLong()))
        }
        return r
    }

    private fun sha512(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        for (p in parts) md.update(p)
        return md.digest()
    }

    /** Clamped scalar + prefix, per RFC 8032 section 5.1.5. */
    private fun expand(secretKey: ByteArray): Pair<BigInteger, ByteArray> {
        require(secretKey.size == 32) { "Ed25519 secret key must be 32 bytes" }
        val h = sha512(secretKey)
        val a = h.copyOfRange(0, 32)
        a[0] = (a[0].toInt() and 248).toByte()
        a[31] = ((a[31].toInt() and 127) or 64).toByte()
        return Pair(leInt(a), h.copyOfRange(32, 64))
    }

    override fun publicKey(secretKey: ByteArray): ByteArray {
        val (a, _) = expand(secretKey)
        return encodePoint(mul(B, a))
    }

    override fun sign(message: ByteArray, secretKey: ByteArray): ByteArray {
        val (a, prefix) = expand(secretKey)
        val pub = encodePoint(mul(B, a))
        val r = leInt(sha512(prefix, message)).mod(L)
        val rEnc = encodePoint(mul(B, r))
        val k = leInt(sha512(rEnc, pub, message)).mod(L)
        val s = r.add(k.multiply(a)).mod(L)
        return Bytes.concat(rEnc, le32Scalar(s))
    }

    private fun le32Scalar(v: BigInteger): ByteArray {
        val out = ByteArray(32)
        var t = v
        for (i in 0 until 32) {
            out[i] = t.and(BigInteger.valueOf(0xFF)).toInt().toByte()
            t = t shr 8
        }
        return out
    }

    override fun verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean {
        if (signature.size != 64 || publicKey.size != 32) return false
        return try {
            val rEnc = signature.copyOfRange(0, 32)
            val s = leInt(signature.copyOfRange(32, 64))
            if (s >= L) return false
            val aPoint = decodePoint(publicKey) ?: return false
            val rPoint = decodePoint(rEnc) ?: return false
            val k = leInt(sha512(rEnc, publicKey, message)).mod(L)
            val lhs = mul(B, s)
            val rhs = add(rPoint, mul(aPoint, k))
            Bytes.eq(encodePoint(lhs), encodePoint(rhs))
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * Signing/verification seam.
 *
 * Keeps the protocol layer independent of the crypto backend so a faster
 * implementation can be dropped in later without touching frame parsing.
 */
interface SignatureProvider {
    fun publicKey(secretKey: ByteArray): ByteArray
    fun sign(message: ByteArray, secretKey: ByteArray): ByteArray
    fun verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean
}
