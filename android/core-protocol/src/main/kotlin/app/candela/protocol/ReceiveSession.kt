package app.candela.protocol

/**
 * Receiver session orchestration — the domain object the Compose UI observes.
 *
 * Deliberately free of Android and coroutine types so the entire
 * calibrate -> pair -> receive -> verify -> export flow is unit-testable. The
 * ViewModel in :app owns threading and exposes this as StateFlow; it adds no
 * decisions of its own.
 *
 * The two invariants this class exists to make unbreakable:
 *
 *  1. [ingestFrame] silently refuses every DATA frame until the SAS gate is
 *     unlocked. Not "the UI hides the button" — the ingest path itself drops
 *     them. A UI bug therefore cannot leak data past an unconfirmed SAS.
 *  2. The assembled file is never handed out until [ExportGate] approves it.
 */
class ReceiveSession {

    var state: SessionState = SessionState.IDLE
        private set

    var sasGate: SasGate? = null
        private set

    var header: Frame.Header? = null
        private set

    var decoder: Fountain.Decoder? = null
        private set

    var coach: CoachMetrics = CoachMetrics()
        private set

    var lastRejection: RejectReason? = null
        private set

    /** Frames dropped specifically because the SAS gate was not yet unlocked. */
    var framesBlockedBySas: Int = 0
        private set

    var exportDecision: ExportGate.Decision? = null
        private set

    private var assembled: ByteArray? = null

    // ------------------------------------------------------------- transitions

    fun startCalibration(): Boolean = apply(SessionEvent.START_CALIBRATION)

    /**
     * Calibration finished. A refusal is a hard physical floor (direct sun,
     * privacy film) and aborts rather than retrying — see audit section 4.
     */
    fun onCalibrationResult(accepted: Boolean): Boolean =
        apply(if (accepted) SessionEvent.CALIBRATION_OK else SessionEvent.CALIBRATION_REFUSED)

    /**
     * A HEADER arrived and verified. This establishes the sender's ephemeral key,
     * which is what the SAS is derived from, so this is where pairing begins.
     */
    fun onHeader(h: Frame.Header) {
        if (state != SessionState.PAIRING && state != SessionState.RECEIVING) return
        if (header != null) return // headers repeat by design; take the first

        header = h
        decoder = Fountain.Decoder(h.k, h.blockSize, h.fileSize.toInt())
        sasGate = SasGate(Crypto.sasFromPublicKey(h.publicKey)).also { it.present() }
        coach = coach.copy(k = h.k)
    }

    /** This device's human tapped "the digits match". */
    fun confirmSasLocal(comparedAgainst: String? = null): SasGate.State? =
        sasGate?.confirmLocal(comparedAgainst)?.also { afterSasChange(it) }

    /** The other device's human confirmed. */
    fun confirmSasRemote(remoteSas: String? = null): SasGate.State? =
        sasGate?.confirmRemote(remoteSas)?.also { afterSasChange(it) }

    fun reportSasMismatch(): SasGate.State? =
        sasGate?.reportMismatch()?.also { afterSasChange(it) }

    private fun afterSasChange(s: SasGate.State) {
        when (s) {
            SasGate.State.UNLOCKED -> if (state == SessionState.PAIRING) {
                apply(SessionEvent.SAS_CONFIRMED)
            }
            SasGate.State.REJECTED -> apply(SessionEvent.ABORT)
            else -> Unit
        }
    }

    /**
     * Ingest a decoded optical frame.
     *
     * @return true if the frame contributed a new symbol to the fountain
     */
    fun ingestFrame(result: DecodeResult): Boolean {
        if (result is DecodeResult.Rejected) {
            lastRejection = result.reason
            return false
        }
        val frame = (result as DecodeResult.Ok).frame

        if (frame is Frame.Header) {
            onHeader(frame)
            return false
        }
        if (frame !is Frame.Data) return false

        // ---- INVARIANT 1. The data plane is closed until both humans confirm.
        val gate = sasGate
        if (gate == null || !gate.isDataPlaneUnlocked) {
            framesBlockedBySas++
            return false
        }
        if (state != SessionState.RECEIVING) return false

        val dec = decoder ?: return false
        val h = header ?: return false

        // Bind the frame to THIS session. Replayed frames from an old session
        // carry a different session id and are dropped here (their signature
        // would also fail, since the key is ephemeral).
        if (!Bytes.eq(frame.sessionId, h.sessionId)) return false

        val accepted = dec.ingest(frame.symbolId, frame.payload)
        coach = coach.copy(unique = dec.uniqueCount, recovered = dec.doneCount, k = dec.k)

        if (dec.isComplete()) {
            apply(SessionEvent.ALL_SYMBOLS_IN)
            runVerification()
        }
        return accepted
    }

    /** VERIFYING -> COMPLETE or ABORTED. Never anything in between. */
    private fun runVerification() {
        val h = header ?: return
        val out = decoder?.assemble()
        val decision = ExportGate.evaluate(out, h)
        exportDecision = decision
        when (decision) {
            is ExportGate.Decision.Publish -> {
                assembled = out
                apply(SessionEvent.VERIFY_OK)
            }
            is ExportGate.Decision.Refuse -> {
                assembled = null // INVARIANT 2: nothing survives a failed verify
                apply(SessionEvent.VERIFY_FAILED)
            }
        }
    }

    /**
     * The verified bytes, or null. Only ever non-null in COMPLETE, and only after
     * [ExportGate] approved them.
     */
    fun verifiedBytes(): ByteArray? =
        if (state == SessionState.COMPLETE) assembled else null

    fun updateCoach(
        blur: Double,
        contrast: Double,
        motion: Double,
        fps: Double,
        decodeMs: Double,
        gatePass: Boolean,
        reason: String,
    ) {
        coach = coach.copy(
            blur = blur, contrast = contrast, motion = motion, fps = fps,
            decodeMs = decodeMs, gatePass = gatePass, reason = reason,
        )
    }

    fun pauseThermal(): Boolean = apply(SessionEvent.THERMAL_PAUSE)
    fun resume(): Boolean = apply(SessionEvent.RESUME)
    fun abort(): Boolean = apply(SessionEvent.ABORT)

    private fun apply(event: SessionEvent): Boolean {
        val next = SessionMachine.next(state, event) ?: return false
        state = next
        return true
    }
}
