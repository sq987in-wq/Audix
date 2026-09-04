package app.candela.protocol

/**
 * Sender-side orchestration — the mirror of [ReceiveSession].
 *
 * Pure Kotlin: no Android imports, no I/O, no clock. The caller supplies the
 * file bytes and drives the state machine; this class decides what may be
 * transmitted and when. That keeps the security-relevant part — "no DATA frame
 * is emitted before both humans confirm the SAS" — unit-testable on a bare JVM,
 * exactly as the receiver's gate is.
 *
 * THE SYMMETRY THAT MATTERS. The receiver refuses to *ingest* data before the
 * SAS is confirmed. If the sender happily *transmits* before confirmation, an
 * attacker who controls only the receiver display still sees the whole payload
 * on the wire. So the sender enforces the same gate in [nextFrame], not in the
 * UI: an unconfirmed session emits CAL frames and nothing else.
 */
class SendSession(
    /** Injected for tests; production passes a real keypair. */
    private val keyPair: Crypto.KeyPair = Crypto.generateKeyPair(),
    private val sessionIdOverride: ByteArray? = null,
) {

    var state: SessionState = SessionState.IDLE
        private set

    var sasGate: SasGate? = null
        private set

    /** Set once [prepare] succeeds. */
    var payload: Payload? = null
        private set

    var lastError: String? = null
        private set

    /** Diagnostics: DATA frames withheld because the SAS was unconfirmed. */
    var framesBlockedBySas: Int = 0
        private set

    var symbolsEmitted: Long = 0L
        private set

    private var encoder: Fountain.Encoder? = null
    private var headerFrame: ByteArray? = null
    private var calFrame: ByteArray? = null
    private var nextSymbolId = 0

    val sessionId: ByteArray = sessionIdOverride ?: Crypto.randomBytes(Constants.SESSION_ID_LEN)

    data class Payload(
        val fileName: String,
        val mime: String,
        val sizeBytes: Int,
        val k: Int,
        val blockSize: Int,
        val sha256Hex: String,
        /** Symbols to send for a comfortable margin over the erasure channel. */
        val recommendedSymbols: Int,
    )

    sealed interface Prepared {
        data class Ready(val payload: Payload, val sas: String) : Prepared
        data class Refused(val reason: String, val detail: String) : Prepared
    }

    /**
     * Ingest the chosen file and build the fountain encoder.
     *
     * Refuses rather than truncates. An oversized file on a ~10 symbol/s optical
     * link is not a slow transfer, it is an hours-long one that will thermally
     * abort long before it finishes, so it is rejected up front with the actual
     * numbers rather than started optimistically.
     */
    fun prepare(
        bytes: ByteArray,
        fileName: String,
        mime: String,
        blockSize: Int = Density.STANDARD.payloadBytes,
    ): Prepared {
        if (bytes.isEmpty()) {
            return refuse("Empty file", "There is nothing to send.")
        }
        if (bytes.size > Constants.MAX_FILE_BYTES) {
            return refuse(
                "File too large",
                "This build carries up to ${Constants.MAX_FILE_BYTES / 1024} KB. " +
                    "That file is ${bytes.size / 1024} KB. Optical transfer runs at " +
                    "roughly 10 symbols per second, so larger files would take hours " +
                    "and overheat before finishing.",
            )
        }

        val enc = Fountain.Encoder(bytes, blockSize)
        val hash = Crypto.fileHash(bytes)
        val safeName = ExportGate.sanitiseFileName(fileName)

        val header = HeaderPayload(
            sessionId = sessionId,
            fileName = safeName,
            fileSize = bytes.size.toLong(),
            k = enc.k,
            blockSize = blockSize,
            fileHash = hash,
            publicKey = keyPair.publicKey,
            mime = mime.ifEmpty { "application/octet-stream" },
        )

        encoder = enc
        headerFrame = Frames.encodeHeader(header, keyPair.secretKey)
        calFrame = Frames.encodeCal(sessionId)
        nextSymbolId = 0
        symbolsEmitted = 0
        framesBlockedBySas = 0

        val p = Payload(
            fileName = safeName,
            mime = header.mime,
            sizeBytes = bytes.size,
            k = enc.k,
            blockSize = blockSize,
            sha256Hex = Bytes.toHex(hash),
            recommendedSymbols = enc.recommendedSymbols(),
        )
        payload = p

        val sas = Crypto.sasFromPublicKey(keyPair.publicKey)
        sasGate = SasGate(sas)
        lastError = null
        return Prepared.Ready(p, sas)
    }

    private fun refuse(reason: String, detail: String): Prepared {
        lastError = "$reason — $detail"
        return Prepared.Refused(reason, detail)
    }

    // ------------------------------------------------------------- state machine

    fun startCalibration(): Boolean = apply(SessionEvent.START_CALIBRATION)

    /**
     * Calibration is the CAL QR being held while the receiver locks exposure.
     * Moving to PAIRING presents the SAS; it cannot be skipped.
     */
    fun onCalibrationComplete(): Boolean {
        if (payload == null) return false
        val ok = apply(SessionEvent.CALIBRATION_OK)
        if (ok) sasGate?.present()
        return ok
    }

    fun confirmSasLocal(comparedAgainst: String? = null): SasGate.State? =
        sasGate?.confirmLocal(comparedAgainst)?.also { afterSasChange(it) }

    fun confirmSasRemote(remoteSas: String? = null): SasGate.State? =
        sasGate?.confirmRemote(remoteSas)?.also { afterSasChange(it) }

    fun reportSasMismatch(): SasGate.State? =
        sasGate?.reportMismatch()?.also { afterSasChange(it) }

    /**
     * SessionMachine maps PAIRING + SAS_CONFIRMED to RECEIVING, because it was
     * written from the receiver's point of view. The sender must land in
     * SENDING instead, so it emits BEGIN_SEND on confirmation rather than
     * SAS_CONFIRMED. Deliberately handled here rather than by adding a role
     * flag to the shared machine: the transition table is covered by the
     * protocol golden tests and is not worth destabilising for a caller-side
     * distinction.
     */
    private fun afterSasChange(s: SasGate.State) {
        when (s) {
            SasGate.State.UNLOCKED -> apply(SessionEvent.BEGIN_SEND)
            SasGate.State.REJECTED -> apply(SessionEvent.ABORT)
            else -> Unit
        }
    }

    /** No-op once confirmation already moved the session to SENDING. */
    fun beginSending(): Boolean =
        state == SessionState.SENDING || apply(SessionEvent.BEGIN_SEND)
    fun pauseThermal(): Boolean = apply(SessionEvent.THERMAL_PAUSE)
    fun resume(): Boolean = apply(SessionEvent.RESUME)
    fun abort(): Boolean = apply(SessionEvent.ABORT)

    /** The sender cannot know the receiver finished; the human ends the session. */
    fun markComplete(): Boolean =
        apply(SessionEvent.ALL_SYMBOLS_IN) && apply(SessionEvent.VERIFY_OK)

    private fun apply(event: SessionEvent): Boolean {
        val next = SessionMachine.next(state, event) ?: return false
        state = next
        return true
    }

    // ----------------------------------------------------------------- the gate

    /**
     * The frame to display for [content].
     *
     * THE INVARIANT: a DATA frame is returned only when the SAS gate is
     * UNLOCKED. Before that the sender shows CAL — which carries no payload —
     * so an unconfirmed session leaks nothing even if the UI is wrong. Withheld
     * frames are counted rather than silently swapped, so the behaviour is
     * observable in a test.
     */
    fun frameFor(content: SymbolContent): ByteArray? {
        val cal = calFrame ?: return null
        val unlocked = sasGate?.isDataPlaneUnlocked == true

        if (!unlocked) {
            if (content !is SymbolContent.Cal) framesBlockedBySas++
            return cal
        }
        return when (content) {
            is SymbolContent.Cal -> cal
            is SymbolContent.Header -> headerFrame
            is SymbolContent.Data -> dataFrame(content.symbolIndex)
        }
    }

    private fun dataFrame(index: Int): ByteArray? {
        val enc = encoder ?: return null
        val sym = enc.encode(index)
        symbolsEmitted++
        return Frames.encodeData(sessionId, index, sym.payload, keyPair.secretKey)
    }

    /**
     * Pre-render the full symbol set for the bitmap cache.
     *
     * Returns an empty list while the SAS is unconfirmed: pre-rendering DATA
     * frames into a cache the UI could blit would defeat the gate. Rendering is
     * therefore deferred until after confirmation.
     */
    fun allDataFrames(): List<ByteArray> {
        if (sasGate?.isDataPlaneUnlocked != true) return emptyList()
        val enc = encoder ?: return emptyList()
        return (0 until enc.recommendedSymbols()).map { id ->
            Frames.encodeData(sessionId, id, enc.encode(id).payload, keyPair.secretKey)
        }
    }

    fun headerFrameBytes(): ByteArray? =
        if (sasGate?.isDataPlaneUnlocked == true) headerFrame else null

    fun calFrameBytes(): ByteArray? = calFrame

    fun sas(): String? = sasGate?.localSas

    /** Content the scheduler can ask for; mirrors SymbolScheduler.Content. */
    sealed interface SymbolContent {
        object Cal : SymbolContent
        object Header : SymbolContent
        data class Data(val symbolIndex: Int) : SymbolContent
    }
}
