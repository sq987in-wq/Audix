package com.candela.protocol

object Protocol {
    val MAGIC = byteArrayOf(0x43, 0x4C)
    const val VERSION: Byte = 1
    const val KIND_CAL: Byte = 0
    const val KIND_HEADER: Byte = 1
    const val KIND_DATA: Byte = 2
    const val SESSION_ID_LEN = 8
    const val PUBKEY_LEN = 32
    const val SIG_LEN = 64
    const val HASH_LEN = 32
    const val CRC_LEN = 4
    const val SAS_DIGITS = 8
    const val HEADER_INTERLEAVE = 8
    const val CALIBRATION_MS = 2800L
    const val MAX_FILE_BYTES = 1_048_576L
    const val FOUNTAIN_C = 0.12
    const val FOUNTAIN_DELTA = 0.05
    const val FOUNTAIN_OVERHEAD = 1.55
    const val FRAME_STALE_MS = 160L
}

enum class Density(
    val payloadBytes: Int,
    val holdMs: Long,
    val qrEcc: Char,
) {
    ROBUST(80, 220L, 'M'),
    STANDARD(160, 160L, 'M'),
    FAST(280, 120L, 'L'),
}

enum class SessionState {
    IDLE,
    CALIBRATING,
    PAIRING,
    SENDING,
    RECEIVING,
    VERIFYING,
    COMPLETE,
    ABORTED,
    PAUSED,
}
