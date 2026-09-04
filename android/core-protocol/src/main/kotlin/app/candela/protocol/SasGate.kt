package app.candela.protocol

/**
 * The blocking SAS confirmation gate (audit section 5.2).
 *
 * This is the single most security-critical piece of UI logic in the product, and
 * it is the one thing the web POC knowingly got wrong: PSR section 2.7 records
 * that the web sender "displays SAS and continues". Displaying a code nobody has
 * to act on is security theatre — it produces the *appearance* of authentication
 * while an attacker who has spliced their own screen into the optical path sails
 * straight through.
 *
 * The rule this class enforces, mechanically rather than by convention:
 *
 *   NO DATA FRAME IS EMITTED OR ACCEPTED UNTIL **BOTH** HUMANS HAVE CONFIRMED
 *   THAT THE 8 DIGITS ON THE TWO SCREENS MATCH.
 *
 * Why both, and why it cannot be one-sided. The SAS is a hash of the sender's
 * ephemeral public key. The receiver derives it from the key it actually
 * received; the sender derives it from the key it actually sent. If a
 * man-in-the-middle substituted its own key, the two strings differ and the
 * humans see it. If only ONE side confirmed, an attacker who controls the other
 * device's display simply never shows a mismatch — the whole ZRTP-style
 * comparison collapses to a single trusted endpoint, which is precisely the
 * assumption we cannot make over an unauthenticated optical channel.
 *
 * The local side confirms by tapping; the remote side's confirmation arrives
 * out-of-band (the humans are standing next to each other — that IS the channel,
 * exactly as the audit intends: "two humans standing next to each other can read
 * 8 digits aloud. That's the whole PKI.").
 *
 * Pure Kotlin, no Android imports, so the gate's invariants are provable in CI
 * rather than asserted in a code review.
 */
class SasGate(
    /** The SAS derived locally, from the key this device holds. */
    val localSas: String,
) {

    enum class State {
        /** No SAS shown yet; nothing to confirm. */
        IDLE,

        /** Digits are on screen. Waiting for the humans. */
        AWAITING_BOTH,

        /** This device's human has tapped "they match". */
        LOCAL_CONFIRMED,

        /** The other device reported its human confirmed. */
        REMOTE_CONFIRMED,

        /** Both confirmed. This is the ONLY state where data may flow. */
        UNLOCKED,

        /** A human reported a mismatch, or the strings differ. Terminal. */
        REJECTED,
    }

    var state: State = State.IDLE
        private set

    /** Populated only on rejection, for the abort screen. */
    var rejectionReason: String? = null
        private set

    private var localConfirmed = false
    private var remoteConfirmed = false

    /**
     * The one question the rest of the app is allowed to ask before sending or
     * ingesting a DATA frame. Deliberately not a `Boolean` field that could drift
     * out of sync with [state].
     */
    val isDataPlaneUnlocked: Boolean get() = state == State.UNLOCKED

    val isRejected: Boolean get() = state == State.REJECTED

    /** Present the digits. Called when the HEADER frame establishes the key. */
    fun present(): State {
        if (state == State.REJECTED) return state
        state = State.AWAITING_BOTH
        return state
    }

    /**
     * This device's human tapped "the digits match".
     *
     * @param comparedAgainst optionally, the digits the human read off the OTHER
     *   screen. When supplied we verify rather than trust the tap — a human who
     *   taps confirm on a visibly different number is caught by the machine.
     */
    fun confirmLocal(comparedAgainst: String? = null): State {
        if (state == State.REJECTED || state == State.IDLE) return state

        if (comparedAgainst != null && !constantTimeEquals(comparedAgainst, localSas)) {
            return reject(
                "SAS mismatch: this device shows ${Crypto.sasPretty(localSas)}, " +
                    "the other shows ${Crypto.sasPretty(comparedAgainst)}. " +
                    "A third device may be in the optical path. Session aborted.",
            )
        }
        localConfirmed = true
        return recompute()
    }

    /**
     * The other device's human confirmed.
     *
     * @param remoteSas the digits the remote device is displaying, when known.
     *   Verified even though a human already compared them — defence in depth
     *   costs nothing here.
     */
    fun confirmRemote(remoteSas: String? = null): State {
        if (state == State.REJECTED || state == State.IDLE) return state

        if (remoteSas != null && !constantTimeEquals(remoteSas, localSas)) {
            return reject(
                "SAS mismatch reported by the other device " +
                    "(${Crypto.sasPretty(remoteSas)} vs ${Crypto.sasPretty(localSas)}). " +
                    "Session aborted.",
            )
        }
        remoteConfirmed = true
        return recompute()
    }

    /** Either human reported the digits differ. There is no recovery path. */
    fun reportMismatch(): State = reject(
        "A mismatch was reported. The stream may be spliced or replayed from " +
            "another session. Start over; do not retry this session.",
    )

    private fun reject(reason: String): State {
        rejectionReason = reason
        state = State.REJECTED
        localConfirmed = false
        remoteConfirmed = false
        return state
    }

    private fun recompute(): State {
        state = when {
            localConfirmed && remoteConfirmed -> State.UNLOCKED
            localConfirmed -> State.LOCAL_CONFIRMED
            remoteConfirmed -> State.REMOTE_CONFIRMED
            else -> State.AWAITING_BOTH
        }
        return state
    }

    fun reset() {
        state = State.IDLE
        localConfirmed = false
        remoteConfirmed = false
        rejectionReason = null
    }

    /** Human-facing prompt for the pairing screen. */
    fun prompt(): String = when (state) {
        State.IDLE -> "Waiting for the sender's key…"
        State.AWAITING_BOTH ->
            "Read these digits aloud. Both of you must confirm they match."
        State.LOCAL_CONFIRMED -> "You confirmed. Waiting for the other device…"
        State.REMOTE_CONFIRMED -> "Other device confirmed. Now confirm on this one."
        State.UNLOCKED -> "Verified. Transfer authorised."
        State.REJECTED -> rejectionReason ?: "SAS rejected."
    }

    /**
     * Length-independent constant-time compare. SAS strings are 8 ASCII digits;
     * timing leakage is not a realistic attack here, but a comparison this cheap
     * has no excuse to be variable-time.
     */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}
