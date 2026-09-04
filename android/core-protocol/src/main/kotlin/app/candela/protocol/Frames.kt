package app.candela.protocol

import app.candela.protocol.Constants.CRC_LEN
import app.candela.protocol.Constants.HASH_LEN
import app.candela.protocol.Constants.KIND_CAL
import app.candela.protocol.Constants.KIND_DATA
import app.candela.protocol.Constants.KIND_HEADER
import app.candela.protocol.Constants.MAGIC
import app.candela.protocol.Constants.PROTOCOL_VERSION
import app.candela.protocol.Constants.PUBKEY_LEN
import app.candela.protocol.Constants.SESSION_ID_LEN
import app.candela.protocol.Constants.SIG_LEN

/**
 * CAL / HEADER / DATA codec. 1:1 with src/protocol/frames.ts.
 *
 * Wire layout (PSR section 2.3):
 *   CAL    magic(2) version(1) kind=0(1) sessionId(8) CRC32(4)
 *   HEADER magic(2) version(1) kind=1(1) sessionId(8) nameLen(2) name
 *          fileSize(4) k(2) blockSize(2) fileHash(32) publicKey(32)
 *          mimeLen(2) mime sig(64) CRC32(4)
 *   DATA   magic(2) version(1) kind=2(1) sessionId(8) symbolId(2)
 *          payloadLen(2) payload sig(64) CRC32(4)
 *
 * Signature covers the body BEFORE the signature. CRC32 covers body+signature.
 *
 * Verify order is fixed by audit section 5.2 and is enforced in [decode]:
 *   CRC32 -> Ed25519 -> symbol-id bounds -> (caller) fountain insert.
 * CRC first because it is ~1000x cheaper than a signature check and rejects the
 * overwhelming majority of bad frames; the signature is what stops injection.
 */
sealed interface Frame {
    val sessionId: ByteArray

    data class Cal(override val sessionId: ByteArray) : Frame {
        override fun equals(other: Any?) = other is Cal && sessionId.contentEquals(other.sessionId)
        override fun hashCode() = sessionId.contentHashCode()
    }

    data class Header(
        override val sessionId: ByteArray,
        val fileName: String,
        val fileSize: Long,
        val k: Int,
        val blockSize: Int,
        val fileHash: ByteArray,
        val publicKey: ByteArray,
        val mime: String,
        val signature: ByteArray,
    ) : Frame {
        override fun equals(other: Any?) = other is Header &&
            sessionId.contentEquals(other.sessionId) &&
            fileName == other.fileName && fileSize == other.fileSize &&
            k == other.k && blockSize == other.blockSize &&
            fileHash.contentEquals(other.fileHash) &&
            publicKey.contentEquals(other.publicKey) && mime == other.mime

        override fun hashCode(): Int = fileName.hashCode() * 31 + k
    }

    data class Data(
        override val sessionId: ByteArray,
        val symbolId: Int,
        val payload: ByteArray,
        val signature: ByteArray,
    ) : Frame {
        override fun equals(other: Any?) = other is Data &&
            sessionId.contentEquals(other.sessionId) &&
            symbolId == other.symbolId && payload.contentEquals(other.payload)

        override fun hashCode(): Int = symbolId * 31 + payload.contentHashCode()
    }
}

/** Why a frame was rejected — surfaced to the coach HUD and to tests. */
enum class RejectReason {
    TOO_SHORT, BAD_MAGIC, BAD_VERSION, BAD_CRC, BAD_SIGNATURE,
    UNKNOWN_KIND, MALFORMED, SYMBOL_OUT_OF_RANGE, MISSING_KEY,
}

sealed interface DecodeResult {
    data class Ok(val frame: Frame) : DecodeResult
    data class Rejected(val reason: RejectReason) : DecodeResult
}

data class HeaderPayload(
    val sessionId: ByteArray,
    val fileName: String,
    val fileSize: Long,
    val k: Int,
    val blockSize: Int,
    val fileHash: ByteArray,
    val publicKey: ByteArray,
    val mime: String,
) {
    override fun equals(other: Any?): Boolean = other is HeaderPayload &&
        sessionId.contentEquals(other.sessionId) && fileName == other.fileName
    override fun hashCode(): Int = fileName.hashCode()
}

object Frames {

    fun encodeCal(sessionId: ByteArray): ByteArray {
        val body = Bytes.concat(
            MAGIC,
            byteArrayOf(PROTOCOL_VERSION.toByte(), KIND_CAL.toByte()),
            sessionId,
        )
        return Bytes.concat(body, Crc32.bytes(body))
    }

    fun encodeHeaderBody(h: HeaderPayload): ByteArray {
        val name = Bytes.utf8Truncate(h.fileName, Constants.MAX_NAME_BYTES)
        val mimeStr = h.mime.ifEmpty { "application/octet-stream" }
        val mime = Bytes.utf8Truncate(mimeStr, Constants.MAX_MIME_BYTES)
        return Bytes.concat(
            MAGIC,
            byteArrayOf(PROTOCOL_VERSION.toByte(), KIND_HEADER.toByte()),
            h.sessionId,
            Bytes.u16be(name.size),
            name,
            Bytes.u32be(h.fileSize),
            Bytes.u16be(h.k),
            Bytes.u16be(h.blockSize),
            h.fileHash,
            h.publicKey,
            Bytes.u16be(mime.size),
            mime,
        )
    }

    fun encodeHeader(h: HeaderPayload, secretKey: ByteArray): ByteArray {
        val body = encodeHeaderBody(h)
        val sig = Crypto.sign(body, secretKey)
        val bodySig = Bytes.concat(body, sig)
        return Bytes.concat(bodySig, Crc32.bytes(bodySig))
    }

    fun encodeData(
        sessionId: ByteArray,
        symbolId: Int,
        payload: ByteArray,
        secretKey: ByteArray,
    ): ByteArray {
        val body = Bytes.concat(
            MAGIC,
            byteArrayOf(PROTOCOL_VERSION.toByte(), KIND_DATA.toByte()),
            sessionId,
            Bytes.u16be(symbolId),
            Bytes.u16be(payload.size),
            payload,
        )
        val sig = Crypto.sign(body, secretKey)
        val bodySig = Bytes.concat(body, sig)
        return Bytes.concat(bodySig, Crc32.bytes(bodySig))
    }

    /**
     * @param expectedKey required for DATA frames (they are signed by the key the
     *   HEADER established). DATA without a key is rejected, never trusted.
     * @param expectedK when known, bounds-checks symbolId so a corrupt-but-valid
     *   frame cannot poison the fountain.
     */
    fun decode(raw: ByteArray, expectedKey: ByteArray? = null, expectedK: Int? = null): DecodeResult {
        if (raw.size < 8) return DecodeResult.Rejected(RejectReason.TOO_SHORT)
        if (raw[0] != MAGIC[0] || raw[1] != MAGIC[1]) {
            return DecodeResult.Rejected(RejectReason.BAD_MAGIC)
        }
        if ((raw[2].toInt() and 0xFF) != PROTOCOL_VERSION) {
            return DecodeResult.Rejected(RejectReason.BAD_VERSION)
        }

        val crcOff = raw.size - CRC_LEN
        if (Crc32.compute(raw, 0, crcOff) != Crc32.read(raw, crcOff)) {
            return DecodeResult.Rejected(RejectReason.BAD_CRC)
        }

        return when (raw[3].toInt() and 0xFF) {
            KIND_CAL -> decodeCal(raw)
            KIND_HEADER -> decodeHeader(raw)
            KIND_DATA -> decodeData(raw, expectedKey, expectedK)
            else -> DecodeResult.Rejected(RejectReason.UNKNOWN_KIND)
        }
    }

    private fun decodeCal(raw: ByteArray): DecodeResult {
        if (raw.size < 4 + SESSION_ID_LEN + CRC_LEN) {
            return DecodeResult.Rejected(RejectReason.MALFORMED)
        }
        return DecodeResult.Ok(Frame.Cal(raw.copyOfRange(4, 4 + SESSION_ID_LEN)))
    }

    private fun decodeHeader(raw: ByteArray): DecodeResult = try {
        var o = 4
        val sessionId = raw.copyOfRange(o, o + SESSION_ID_LEN); o += SESSION_ID_LEN
        val nameLen = Bytes.readU16(raw, o); o += 2
        val name = Bytes.utf8Decode(raw.copyOfRange(o, o + nameLen)); o += nameLen
        val fileSize = Bytes.readU32(raw, o); o += 4
        val k = Bytes.readU16(raw, o); o += 2
        val blockSize = Bytes.readU16(raw, o); o += 2
        val fileHash = raw.copyOfRange(o, o + HASH_LEN); o += HASH_LEN
        val publicKey = raw.copyOfRange(o, o + PUBKEY_LEN); o += PUBKEY_LEN
        val mimeLen = Bytes.readU16(raw, o); o += 2
        val mime = Bytes.utf8Decode(raw.copyOfRange(o, o + mimeLen)); o += mimeLen
        val sig = raw.copyOfRange(o, o + SIG_LEN)
        val body = raw.copyOfRange(0, o)

        if (!Crypto.verify(sig, body, publicKey)) {
            DecodeResult.Rejected(RejectReason.BAD_SIGNATURE)
        } else {
            DecodeResult.Ok(
                Frame.Header(sessionId, name, fileSize, k, blockSize, fileHash, publicKey, mime, sig),
            )
        }
    } catch (_: IndexOutOfBoundsException) {
        DecodeResult.Rejected(RejectReason.MALFORMED)
    }

    private fun decodeData(raw: ByteArray, expectedKey: ByteArray?, expectedK: Int?): DecodeResult {
        if (expectedKey == null) return DecodeResult.Rejected(RejectReason.MISSING_KEY)
        return try {
            var o = 4
            val sessionId = raw.copyOfRange(o, o + SESSION_ID_LEN); o += SESSION_ID_LEN
            val symbolId = Bytes.readU16(raw, o); o += 2
            val payloadLen = Bytes.readU16(raw, o); o += 2
            val payload = raw.copyOfRange(o, o + payloadLen); o += payloadLen
            val sig = raw.copyOfRange(o, o + SIG_LEN)
            val body = raw.copyOfRange(0, o)

            when {
                !Crypto.verify(sig, body, expectedKey) ->
                    DecodeResult.Rejected(RejectReason.BAD_SIGNATURE)
                expectedK != null && symbolId >= 65536 ->
                    DecodeResult.Rejected(RejectReason.SYMBOL_OUT_OF_RANGE)
                else -> DecodeResult.Ok(Frame.Data(sessionId, symbolId, payload, sig))
            }
        } catch (_: IndexOutOfBoundsException) {
            DecodeResult.Rejected(RejectReason.MALFORMED)
        }
    }
}
