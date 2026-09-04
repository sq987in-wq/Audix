package app.candela.protocol

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Hashing, key handling and SAS. 1:1 with src/protocol/crypto.ts.
 *
 * SAS is the entire PKI (audit section 5.2): two humans compare 8 digits derived
 * from the sender's ephemeral public key. There is no server and no prior key
 * distribution.
 */
object Crypto {

    data class KeyPair(val secretKey: ByteArray, val publicKey: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is KeyPair &&
                secretKey.contentEquals(other.secretKey) &&
                publicKey.contentEquals(other.publicKey)

        override fun hashCode(): Int = secretKey.contentHashCode() * 31 + publicKey.contentHashCode()
    }

    private val rng = SecureRandom()
    var signer: SignatureProvider = Ed25519

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    fun generateKeyPair(): KeyPair {
        val sk = randomBytes(32)
        return KeyPair(sk, signer.publicKey(sk))
    }

    fun keyPairFromSecret(secretKey: ByteArray): KeyPair =
        KeyPair(secretKey, signer.publicKey(secretKey))

    fun sign(message: ByteArray, secretKey: ByteArray): ByteArray = signer.sign(message, secretKey)

    fun verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean =
        signer.verify(signature, message, publicKey)

    fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    fun fileHash(data: ByteArray): ByteArray = sha256(data)

    /**
     * Short Authentication String. sha256("SAS" || publicKey), first 4 bytes as a
     * big-endian unsigned int, mod 10^8, zero-padded to 8 digits.
     */
    fun sasFromPublicKey(publicKey: ByteArray): String {
        val h = sha256(Bytes.concat(byteArrayOf(0x53, 0x41, 0x53), publicKey))
        var n = 0L
        for (i in 0 until 4) n = (n * 256 + (h[i].toInt() and 0xFF)) and 0xFFFFFFFFL
        var mod = 1L
        repeat(Constants.SAS_DIGITS) { mod *= 10 }
        return (n % mod).toString().padStart(Constants.SAS_DIGITS, '0')
    }

    fun sasPretty(sas: String): String = "${sas.substring(0, 4)} ${sas.substring(4)}"

    fun sessionFingerprint(sessionId: ByteArray, publicKey: ByteArray): String =
        Bytes.toHex(sha256(Bytes.concat(sessionId, publicKey)).copyOfRange(0, 6)).uppercase()
}
