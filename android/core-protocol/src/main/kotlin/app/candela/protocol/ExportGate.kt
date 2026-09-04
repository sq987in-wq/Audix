package app.candela.protocol

/**
 * The final integrity gate before a received file becomes visible to the user
 * (audit section 5.2, section 7).
 *
 * The audit's contract is that outside the operating envelope the system
 * "degrades *gracefully* to a pause/refuse, never to corruption — that property,
 * not throughput, is what makes it commercial-grade." Concretely:
 *
 *   NO BYTES REACH USER-VISIBLE STORAGE UNTIL THE WHOLE-FILE SHA-256 MATCHES
 *   THE HASH THE SIGNED HEADER COMMITTED TO.
 *
 * That ordering matters more than it looks. Per-symbol CRC32 + Ed25519 already
 * rejected forged and corrupted frames, but they cannot catch a *fountain
 * reassembly* error: a decoder bug, a block written at the wrong offset, or a
 * truncated final block all produce a file made entirely of individually-valid
 * symbols. Only the whole-file hash catches that class, which is why the audit
 * calls it "the final gate — mandatory".
 *
 * The write protocol enforced by [Decision]:
 *   1. Reassemble in memory (payloads are <= 1 MB by envelope).
 *   2. Hash. Compare against the HEADER's committed hash.
 *   3. On match  -> stage to a temp location, then publish atomically.
 *   4. On mismatch -> write NOTHING. Not a partial file, not a .part file, not a
 *      zero-byte placeholder. A half-written file in the user's Downloads that
 *      looks like a successful transfer is worse than no file at all.
 *
 * Pure Kotlin. The MediaStore plumbing lives in :platform; the *decision* lives
 * here so the no-partial-write invariant is unit-testable.
 */
object ExportGate {

    sealed interface Decision {
        /** Hash matched. Safe to stage and publish. */
        data class Publish(
            val fileName: String,
            val mimeType: String,
            val sizeBytes: Int,
            val sha256Hex: String,
        ) : Decision

        /** Hash mismatch, size mismatch, or incomplete. Write nothing. */
        data class Refuse(val reason: String, val detail: String) : Decision
    }

    /**
     * @param assembled the reassembled bytes, or null if the fountain never completed
     * @param header the signed HEADER frame that committed to name/size/hash
     */
    fun evaluate(assembled: ByteArray?, header: Frame.Header): Decision {
        if (assembled == null) {
            return Decision.Refuse(
                "Transfer incomplete",
                "The fountain decoder did not recover every block. Nothing was written. " +
                    "Resume the session to continue from where it stopped.",
            )
        }

        // Size is committed in the signed header, so a mismatch means the
        // reassembly is wrong even if every symbol verified.
        if (assembled.size.toLong() != header.fileSize) {
            return Decision.Refuse(
                "Size mismatch",
                "Reassembled ${assembled.size} bytes but the signed header committed to " +
                    "${header.fileSize}. This indicates a reassembly fault, not a bad frame. " +
                    "Nothing was written.",
            )
        }

        val actual = Crypto.sha256(assembled)
        if (!Bytes.eq(actual, header.fileHash)) {
            return Decision.Refuse(
                "Integrity check failed",
                "SHA-256 of the reassembled file does not match the signed header. " +
                    "Expected ${Bytes.toHex(header.fileHash).take(16)}…, " +
                    "got ${Bytes.toHex(actual).take(16)}…. Nothing was written.",
            )
        }

        return Decision.Publish(
            fileName = sanitiseFileName(header.fileName),
            mimeType = header.mime.ifBlank { "application/octet-stream" },
            sizeBytes = assembled.size,
            sha256Hex = Bytes.toHex(actual),
        )
    }

    /**
     * The file name arrives from the optical channel, so it is attacker-influenced
     * even when the signature is valid (a malicious *sender* is inside the threat
     * model; the audit is explicit that crypto cannot stop a compromised intended
     * sender). Strip anything that could escape the target directory or confuse
     * the shell.
     */
    fun sanitiseFileName(raw: String): String {
        val base = raw
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("""[\x00-\x1f\x7f]"""), "")
            .replace(Regex("""[<>:"|?*]"""), "_")
            .trim()
            .trimStart('.')

        val safe = base.ifBlank { "candela-transfer.bin" }
        return if (safe.length <= MAX_NAME) safe else {
            val dot = safe.lastIndexOf('.')
            if (dot > 0 && safe.length - dot <= 12) {
                safe.take(MAX_NAME - (safe.length - dot)) + safe.substring(dot)
            } else {
                safe.take(MAX_NAME)
            }
        }
    }

    private const val MAX_NAME = 120
}
