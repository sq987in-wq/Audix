package com.candela.protocol

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom

data class KeyPair(val secretKey: ByteArray, val publicKey: ByteArray)

object Crypto {
    fun generateKeyPair(): KeyPair {
        val priv = Ed25519PrivateKeyParameters(SecureRandom())
        val pub = priv.generatePublicKey()
        return KeyPair(priv.encoded, pub.encoded)
    }

    fun sign(message: ByteArray, secretKey: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(secretKey, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean {
        return try {
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signature)
        } catch (_: Exception) {
            false
        }
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun sha256Streaming(update: (MessageDigest) -> Unit): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        update(md)
        return md.digest()
    }

    fun sasFromPublicKey(publicKey: ByteArray): String {
        val h = sha256(Bytes.concat(byteArrayOf(0x53, 0x41, 0x53), publicKey))
        var n = 0L
        for (i in 0 until 4) n = (n * 256 + (h[i].toInt() and 0xff)) and 0xffffffffL
        return (n % 100_000_000L).toString().padStart(Protocol.SAS_DIGITS, '0')
    }

    fun sasPretty(sas: String): String = "${sas.take(4)} ${sas.drop(4)}"
}
