package app.candela.camera

/**
 * The AF/AE/AWB lock state machine (audit C1) — the single highest-ROI change in
 * the receiver, expressed as pure logic so it can be tested without a camera.
 *
 * The audit's sequence is:
 *   CONTINUOUS_PICTURE -> AF_TRIGGER_START -> await FOCUSED_LOCKED
 *   -> AF_TRIGGER_CANCEL -> AF_MODE_OFF + fixed LENS_FOCUS_DISTANCE
 *   -> AE_LOCK / manual SENSOR_EXPOSURE_TIME+SENSITIVITY -> AWB_LOCK
 *
 * Two rules that are easy to get wrong and are enforced here:
 *
 *  1. RE-LOCK ONLY ON A QUALITY BREACH, NEVER ON A TIMER. A timer re-triggers AF
 *     while the sender is mid-symbol, and because fountain codes tolerate drops
 *     the receiver never reports failure — the lens hunts forever and throughput
 *     silently dies. [onGateMetrics] therefore requires SUSTAINED degradation
 *     ([BREACH_FRAMES] consecutive bad frames) before it will re-lock.
 *
 *  2. AF FAILURE IS NOT FATAL. If AF never reports FOCUSED_LOCKED (common on
 *     LEGACY devices and on flat, low-texture QR fields), we pin the lens at the
 *     hyperfocal-ish calibration distance and continue rather than aborting. A
 *     slightly soft but stable image decodes; a hunting lens does not.
 */
class LockPolicy(
    private val breachFrames: Int = BREACH_FRAMES,
    private val afTimeoutFrames: Int = AF_TIMEOUT_FRAMES,
) {

    enum class Phase {
        /** Nothing locked; preview running with continuous AF/AE. */
        UNLOCKED,

        /** AF trigger sent, waiting for FOCUSED_LOCKED or timeout. */
        AF_CONVERGING,

        /** AF settled (or timed out). Applying manual/locked 3A. */
        APPLYING_LOCKS,

        /** Fully frozen. This is where decoding happens. */
        LOCKED,

        /** A sustained quality breach was detected; re-running the sequence. */
        RELOCKING,
    }

    /** Actions the Android layer must perform. Pure data — no Camera2 types. */
    sealed interface Action {
        /** CONTROL_AF_MODE=CONTINUOUS_PICTURE + CONTROL_AF_TRIGGER_START */
        object StartAfTrigger : Action

        /** CONTROL_AF_TRIGGER_CANCEL, then AF_MODE_OFF + LENS_FOCUS_DISTANCE */
        data class PinFocus(val focusDistance: Float, val afConverged: Boolean) : Action

        /** AE/AWB lock or full manual sensor, plus the noise/edge/EIS/ZSL disables. */
        data class ApplyFreeze(val plan: ExposurePlan.Plan) : Action

        /** Nothing to do this frame. */
        object None : Action
    }

    var phase: Phase = Phase.UNLOCKED
        private set

    var lockedPlan: ExposurePlan.Plan? = null
        private set

    var focusDistance: Float = 0f
        private set

    /** Diagnostics for the coach HUD and for post-session triage. */
    var relockCount: Int = 0
        private set
    var afTimedOut: Boolean = false
        private set

    private var afFrames = 0
    private var consecutiveBad = 0

    /** Begin the C1 sequence. Called once calibration has a stable CAL QR. */
    fun beginLock(): Action {
        phase = Phase.AF_CONVERGING
        afFrames = 0
        return Action.StartAfTrigger
    }

    /**
     * Feed each CaptureResult's AF state while converging.
     * @param focused true when AF_STATE is FOCUSED_LOCKED
     * @param failed true when AF_STATE is NOT_FOCUSED_LOCKED
     * @param currentFocusDistance LENS_FOCUS_DISTANCE from the result
     */
    fun onAfState(focused: Boolean, failed: Boolean, currentFocusDistance: Float): Action {
        if (phase != Phase.AF_CONVERGING) return Action.None
        afFrames++

        if (focused) {
            focusDistance = currentFocusDistance
            phase = Phase.APPLYING_LOCKS
            return Action.PinFocus(currentFocusDistance, afConverged = true)
        }
        // Rule 2: a soft but stable lens beats a hunting one.
        if (failed || afFrames >= afTimeoutFrames) {
            afTimedOut = true
            focusDistance = currentFocusDistance
            phase = Phase.APPLYING_LOCKS
            return Action.PinFocus(currentFocusDistance, afConverged = false)
        }
        return Action.None
    }

    /** Apply the exposure freeze once focus is pinned. */
    fun onFocusPinned(plan: ExposurePlan.Plan): Action {
        if (phase != Phase.APPLYING_LOCKS) return Action.None
        lockedPlan = plan
        phase = Phase.LOCKED
        consecutiveBad = 0
        return Action.ApplyFreeze(plan)
    }

    /**
     * Rule 1: re-lock only on SUSTAINED degradation.
     *
     * @param gatePassed whether the blur/contrast gate admitted this frame
     * @return an action if a re-lock is warranted, otherwise [Action.None]
     */
    fun onGateMetrics(gatePassed: Boolean): Action {
        if (phase != Phase.LOCKED) return Action.None
        if (gatePassed) {
            consecutiveBad = 0
            return Action.None
        }
        consecutiveBad++
        if (consecutiveBad < breachFrames) return Action.None

        relockCount++
        consecutiveBad = 0
        afFrames = 0
        phase = Phase.AF_CONVERGING
        return Action.StartAfTrigger
    }

    /** True while frames may be handed to the decoder. */
    val isDecodable: Boolean get() = phase == Phase.LOCKED

    fun reset() {
        phase = Phase.UNLOCKED
        lockedPlan = null
        consecutiveBad = 0
        afFrames = 0
        relockCount = 0
        afTimedOut = false
    }

    companion object {
        /**
         * ~1.5 s of sustained failure at 30 fps before touching the lens. Long
         * enough that a hand adjustment or a single bad symbol cannot trigger a
         * re-lock storm; short enough that a genuine drift is corrected quickly.
         */
        const val BREACH_FRAMES = 45

        /** ~1 s. Beyond this, AF is hunting on a low-texture target; pin and move on. */
        const val AF_TIMEOUT_FRAMES = 30
    }
}
