package app.candela.protocol

/**
 * The session state machine — the domain model (audit section 6.7, PSR section 2.2).
 *
 * The audit is explicit that this is "the only state that matters", so transitions
 * are an explicit allow-list rather than free assignment. Illegal transitions
 * return null instead of throwing: outside the operating envelope the product must
 * degrade to pause/refuse, never crash and never corrupt (audit section 7).
 */
enum class SessionState {
    IDLE,
    CALIBRATING,
    PAIRING,
    SENDING,
    RECEIVING,
    VERIFYING,
    COMPLETE,
    ABORTED,
    PAUSED;

    val isTerminal: Boolean get() = this == COMPLETE || this == ABORTED

    val label: String
        get() = when (this) {
            IDLE -> "Idle"
            CALIBRATING -> "Calibrating"
            PAIRING -> "Compare SAS"
            SENDING -> "Transmitting"
            RECEIVING -> "Receiving"
            VERIFYING -> "Verifying"
            COMPLETE -> "Complete"
            ABORTED -> "Aborted"
            PAUSED -> "Paused"
        }
}

enum class SessionEvent {
    START_CALIBRATION,
    CALIBRATION_OK,
    CALIBRATION_REFUSED,
    SAS_CONFIRMED,
    BEGIN_SEND,
    ALL_SYMBOLS_IN,
    VERIFY_OK,
    VERIFY_FAILED,
    THERMAL_PAUSE,
    RESUME,
    ABORT,
}

object SessionMachine {

    /**
     * @return the next state, or null if the transition is not legal.
     *
     * Notable rules, each traceable to the audit:
     *  - PAIRING cannot be skipped: the SAS compare must block the data plane
     *    (audit section 5.2). The web POC displays SAS and continues; that is the
     *    one regression PSR section 2.7 flags and it is not reproduced here.
     *  - VERIFY_FAILED goes to ABORTED, never COMPLETE. No partial write.
     *  - THERMAL_PAUSE is legal from any active phase and is resumable
     *    (audit section 3 thermal governor).
     */
    fun next(current: SessionState, event: SessionEvent): SessionState? {
        if (event == SessionEvent.ABORT) {
            return if (current.isTerminal) null else SessionState.ABORTED
        }
        if (event == SessionEvent.THERMAL_PAUSE) {
            return when (current) {
                SessionState.CALIBRATING, SessionState.PAIRING, SessionState.SENDING,
                SessionState.RECEIVING, SessionState.VERIFYING -> SessionState.PAUSED
                else -> null
            }
        }
        return when (current) {
            SessionState.IDLE -> when (event) {
                SessionEvent.START_CALIBRATION -> SessionState.CALIBRATING
                else -> null
            }
            SessionState.CALIBRATING -> when (event) {
                SessionEvent.CALIBRATION_OK -> SessionState.PAIRING
                SessionEvent.CALIBRATION_REFUSED -> SessionState.ABORTED
                else -> null
            }
            SessionState.PAIRING -> when (event) {
                SessionEvent.SAS_CONFIRMED -> SessionState.RECEIVING
                SessionEvent.BEGIN_SEND -> SessionState.SENDING
                else -> null
            }
            SessionState.SENDING -> when (event) {
                SessionEvent.ALL_SYMBOLS_IN -> SessionState.VERIFYING
                else -> null
            }
            SessionState.RECEIVING -> when (event) {
                SessionEvent.ALL_SYMBOLS_IN -> SessionState.VERIFYING
                else -> null
            }
            SessionState.VERIFYING -> when (event) {
                SessionEvent.VERIFY_OK -> SessionState.COMPLETE
                SessionEvent.VERIFY_FAILED -> SessionState.ABORTED
                else -> null
            }
            SessionState.PAUSED -> when (event) {
                SessionEvent.RESUME -> SessionState.RECEIVING
                else -> null
            }
            SessionState.COMPLETE, SessionState.ABORTED -> null
        }
    }
}

/** Live metrics for the alignment coach HUD (audit section 4 — this is the product). */
data class CoachMetrics(
    val blur: Double = 0.0,
    val contrast: Double = 0.0,
    val motion: Double = 0.0,
    val fps: Double = 0.0,
    val decodeMs: Double = 0.0,
    val unique: Int = 0,
    val recovered: Int = 0,
    val k: Int = 0,
    val gatePass: Boolean = false,
    val reason: String = "awaiting",
) {
    val progress: Double get() = if (k == 0) 0.0 else recovered.toDouble() / k
}
