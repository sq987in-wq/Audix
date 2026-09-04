package com.candela.protocol

import java.nio.charset.StandardCharsets

sealed class DecodedFrame {
    data class Cal(val sessionId: ByteArray) : DecodedFrame()
    data class Header(
        val sessionId: ByteArray,
        val fileName: String,
        val fileSize: Long,
        val k: Int,
        val blockSize: Int,
        val fileHash: ByteArray,
        val publicKey: ByteArray,
        val mime: String,
        val signature: ByteArray,
    ) : DecodedFrame()
    data class Data(
        val sessionId: ByteArray,
        val symbolId: Int,
        val payload: ByteArray,
        val signature: ByteArray,
    ) : DecodedFrame()
}

object Frames {
    fun encodeCal(sessionId: ByteArray): ByteArray {
        val body = Bytes.concat(Protocol.MAGIC, byteArrayOf(Protocol.VERSION, Protocol.KIND_CAL), sessionId)
        return Bytes.concat(body, Crc32.bytes(body))
    }

    fun encodeHeaderBody(
        sessionId: ByteArray,
        fileName: String,
        fileSize: Long,
        k: Int,
        blockSize: Int,
        fileHash: ByteArray,
        publicKey: ByteArray,
        mime: String,
    ): ByteArray {
        val name = fileName.take(180).toByteArray(StandardCharsets.UTF_8)
        val mimeB = (mime.ifBlank { "application/octet-stream" }).take(80).toByteArray(StandardCharsets.UTF_8)
        return Bytes.concat(
            Protocol.MAGIC,
            byteArrayOf(Protocol.VERSION, Protocol.KIND_HEADER),
            sessionId,
            Bytes.u16be(name.size),
            name,
            Bytes.u32be(fileSize.toInt()),
            Bytes.u16be(k),
            Bytes.u16be(blockSize),
            fileHash,
            publicKey,
            Bytes.u16be(mimeB.size),
            mimeB,
        )
    }

    fun encodeHeader(
        sessionId: ByteArray,
        fileName: String,
        fileSize: Long,
        k: Int,
        blockSize: Int,
        fileHash: ByteArray,
        publicKey: ByteArray,
        mime: String,
        secretKey: ByteArray,
    ): ByteArray {
        val body = encodeHeaderBody(sessionId, fileName, fileSize, k, blockSize, fileHash, publicKey, mime)
        val signature = Crypto.sign(body, secretKey)
        return Bytes.concat(body, signature, Crc32.bytes(Bytes.concat(body, signature)))
    }

    fun encodeData(sessionId: ByteArray, symbolId: Int, payload: ByteArray, secretKey: ByteArray): ByteArray {
        val body = Bytes.concat(
            Protocol.MAGIC,
            byteArrayOf(Protocol.VERSION, Protocol.KIND_DATA),
            sessionId,
            Bytes.u16be(symbolId),
            Bytes.u16be(payload.size),
            payload,
        )
        val signature = Crypto.sign(body, secretKey)
        return Bytes.concat(body, signature, Crc32.bytes(Bytes.concat(body, signature)))
    }

    fun decode(raw: ByteArray, expectedKey: ByteArray? = null): DecodedFrame? {
        if (raw.size < 8) return null
        if (raw[0] != Protocol.MAGIC[0] || raw[1] != Protocol.MAGIC[1]) return null
        if (raw[2] != Protocol.VERSION) return null
        val crcOff = raw.size - 4
        val bodyWithSig = raw.copyOfRange(0, crcOff)
        if (Crc32.of(bodyWithSig) != Crc32.read(raw, crcOff)) return null
        return when (raw[3]) {
            Protocol.KIND_CAL -> {
                if (raw.size < 4 + Protocol.SESSION_ID_LEN + 4) return null
                DecodedFrame.Cal(raw.copyOfRange(4, 4 + Protocol.SESSION_ID_LEN))
            }
            Protocol.KIND_HEADER -> decodeHeader(raw)
            Protocol.KIND_DATA -> {
                if (expectedKey == null) return null
                decodeData(raw, expectedKey)
            }
            else -> null
        }
    }

    private fun decodeHeader(raw: ByteArray): DecodedFrame.Header? {
        var o = 4
        val sessionId = raw.copyOfRange(o, o + Protocol.SESSION_ID_LEN)
        o += Protocol.SESSION_ID_LEN
        val nameLen = Bytes.readU16(raw, o); o += 2
        if (o + nameLen > raw.size) return null
        val fileName = String(raw, o, nameLen, StandardCharsets.UTF_8); o += nameLen
        val fileSize = Bytes.readU32(raw, o).toLong() and 0xffffffffL; o += 4
        val k = Bytes.readU16(raw, o); o += 2
        val blockSize = Bytes.readU16(raw, o); o += 2
        val fileHash = raw.copyOfRange(o, o + Protocol.HASH_LEN); o += Protocol.HASH_LEN
        val publicKey = raw.copyOfRange(o, o + Protocol.PUBKEY_LEN); o += Protocol.PUBKEY_LEN
        val mimeLen = Bytes.readU16(raw, o); o += 2
        if (o + mimeLen + Protocol.SIG_LEN > raw.size) return null
        val mime = String(raw, o, mimeLen, StandardCharsets.UTF_8); o += mimeLen
        val signature = raw.copyOfRange(o, o + Protocol.SIG_LEN)
        val body = raw.copyOfRange(0, o)
        if (!Crypto.verify(signature, body, publicKey)) return null
        return DecodedFrame.Header(sessionId, fileName, fileSize, k, blockSize, fileHash, publicKey, mime, signature)
    }

    private fun decodeData(raw: ByteArray, expectedKey: ByteArray): DecodedFrame.Data? {
        var o = 4
        val sessionId = raw.copyOfRange(o, o + Protocol.SESSION_ID_LEN); o += Protocol.SESSION_ID_LEN
        val symbolId = Bytes.readU16(raw, o); o += 2
        val payloadLen = Bytes.readU16(raw, o); o += 2
        if (o + payloadLen + Protocol.SIG_LEN > raw.size) return null
        val payload = raw.copyOfRange(o, o + payloadLen); o += payloadLen
        val signature = raw.copyOfRange(o, o + Protocol.SIG_LEN)
        val body = raw.copyOfRange(0, o)
        if (!Crypto.verify(signature, body, expectedKey)) return null
        return DecodedFrame.Data(sessionId, symbolId, payload, signature)
    }
}
