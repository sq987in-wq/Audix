package app.candela.protocol

/**
 * Frozen protocol constants. 1:1 with src/protocol/constants.ts.
 *
 * Any change here is a wire-format break and must be mirrored in the TypeScript
 * reference and re-validated against the golden vectors.
 */
object Constants {
    /** "CL" */
    val MAGIC = byteArrayOf(0x43, 0x4C)
    const val PROTOCOL_VERSION = 1

    const val KIND_CAL = 0
    const val KIND_HEADER = 1
    const val KIND_DATA = 2

    const val SESSION_ID_LEN = 8
    const val PUBKEY_LEN = 32
    const val SIG_LEN = 64
    const val HASH_LEN = 32
    const val CRC_LEN = 4
    const val SAS_DIGITS = 8

    const val HEADER_INTERLEAVE = 8

    const val CALIBRATION_MS = 2800L
    const val MAX_FILE_BYTES = 1_048_576
    const val RECOMMENDED_FILE_BYTES = 512_000

    const val FOUNTAIN_C = 0.12
    const val FOUNTAIN_DELTA = 0.05
    const val FOUNTAIN_OVERHEAD = 1.55

    /** Field limits enforced by the header codec. */
    const val MAX_NAME_BYTES = 180
    const val MAX_MIME_BYTES = 80
}

/**
 * Symbol density profile.
 *
 * NOTE (audit §1.1 / PSR §2.5): these payload sizes are the WEB POC ceiling.
 * On Android, after the C1 Camera2 freeze is proven on-device, payload should be
 * raised toward QR Version 40-L (~2.7 KB useful). Do not treat 32-64 B as the
 * product target.
 */
enum class Density(val payloadBytes: Int, val holdMs: Long, val ecc: String) {
    ROBUST(32, 220, "M"),
    STANDARD(48, 160, "M"),
    FAST(64, 120, "L");

    companion object {
        fun fromName(n: String): Density = valueOf(n.uppercase())
    }
}
