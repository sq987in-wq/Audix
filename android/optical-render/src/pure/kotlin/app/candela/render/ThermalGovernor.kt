package app.candela.render

import kotlin.math.max

/**
 * The thermal governor (audit section 3, PSR section 5.4).
 *
 * The audit is explicit that this is a PRODUCT FEATURE, not an optimisation: an
 * optical link runs the camera, the ISP, ZXing and a full-brightness panel
 * simultaneously, which is a sustained 2-4 W draw on a passively cooled device.
 * Ungoverned, the phone throttles in 5-10 minutes and the transfer dies partway
 * through. A transfer that gracefully takes twice as long is a success; one that
 * dies at 80% is a failure. So every lever here trades throughput for survival,
 * and none of them can lose data — the fountain simply needs more symbols.
 *
 * WHY THIS IS PURE. The whole governor is a deterministic function of
 * (thermal status, elapsed time). `PowerManager` supplies one integer; every
 * decision derived from it — pacing, duty cycle, ROI downsample, gate
 * thresholds, pause, abort — is arithmetic and is unit-tested on a bare JVM.
 * The Android layer in :platform does nothing but forward the integer and a
 * clock reading.
 *
 * THE TWO NON-OBVIOUS RULES, both learned from how thermal mitigation actually
 * behaves on real hardware:
 *
 *  1. ASYMMETRIC HYSTERESIS. Escalate instantly, de-escalate only after a dwell
 *     period. `PowerManager` reports status transitions the moment a sensor
 *     crosses a threshold, and those thresholds flap — a device sitting right at
 *     the boundary can oscillate LIGHT/NONE every few seconds. Reacting
 *     symmetrically means immediately restoring full rate into a chassis that is
 *     still hot, which re-triggers mitigation harder. Cooling is far slower than
 *     heating, so the governor must be too.
 *
 *  2. THE LADDER IS A RATCHET WITHIN A SESSION-LOCAL WINDOW. Work is
 *     monotonically non-increasing as level rises: at no point may a hotter state
 *     permit more work than a cooler one, for any lever. This is asserted
 *     exhaustively in the tests, because it is exactly the kind of invariant that
 *     a plausible-looking edit to one branch quietly breaks.
 */
object ThermalGovernor {

    /** Escalation is immediate. De-escalation waits this long at the cooler level. */
    const val COOLDOWN_DWELL_MS = 20_000L

    /** Audit section 3: MODERATE duty-cycles 8 s of work to 2 s of idle. */
    const val DUTY_WORK_MS = 8_000L
    const val DUTY_IDLE_MS = 2_000L

    /**
     * What the rest of the app is allowed to do right now.
     *
     * A single immutable snapshot rather than a bag of getters: the sender's
     * pacing, the receiver's ROI decimation and the session's pause state must
     * all derive from the SAME observation. Sampling them separately across a
     * status change is how you end up transmitting at full rate while the
     * receiver believes it is paused.
     */
    data class Budget(
        val level: ThermalLevel,
        /** Sender symbol rate ceiling. */
        val targetFps: Double,
        /** Receiver ROI downsample factor: 1 = full res, 2 = half in each axis. */
        val roiDownsample: Int,
        /** Multiplier applied to learned blur/contrast gate thresholds. */
        val gateThresholdScale: Double,
        /** Null = run continuously. Otherwise (workMs, idleMs). */
        val dutyCycle: Pair<Long, Long>?,
        /** Transfer suspended; resumable. */
        val paused: Boolean,
        /** Transfer must stop and persist resume state. */
        val aborted: Boolean,
        /** Shown verbatim to the user. Silence during a thermal pause reads as a hang. */
        val userMessage: String?,
    ) {
        /**
         * Fraction of wall-clock time the pipeline is permitted to work.
         * The single scalar the ratchet test compares across levels.
         */
        val dutyFraction: Double
            get() {
                if (aborted || paused) return 0.0
                val d = dutyCycle ?: return 1.0
                return d.first.toDouble() / (d.first + d.second)
            }

        /**
         * Rough relative compute demand. Rate x pixels-per-frame x duty.
         * Used to assert the ladder never increases work as the device heats.
         */
        val workIndex: Double
            get() = targetFps * (1.0 / (roiDownsample * roiDownsample)) * dutyFraction
    }

    /**
     * The ladder. Steps are ordered cheapest-first in terms of user-visible harm:
     * slow down, then decimate, then duty-cycle, then pause, then abort.
     */
    fun budgetFor(level: ThermalLevel): Budget = when (level) {
        ThermalLevel.NONE -> Budget(
            level = level,
            targetFps = HoldTimePlan.MAX_FPS,
            roiDownsample = 1,
            gateThresholdScale = 1.0,
            dutyCycle = null,
            paused = false,
            aborted = false,
            userMessage = null,
        )

        // First lever: slow the sender to the bottom of the product envelope and
        // halve the receiver's decode area. Both are invisible to correctness.
        ThermalLevel.LIGHT -> Budget(
            level = level,
            targetFps = HoldTimePlan.MIN_FPS,
            roiDownsample = 2,
            gateThresholdScale = 1.0,
            dutyCycle = null,
            paused = false,
            aborted = false,
            userMessage = null,
        )

        // Second lever: duty-cycle, and raise the gate thresholds so marginal
        // frames are rejected for ~0.15 ms instead of costing a 40-120 ms decode
        // that was unlikely to succeed anyway.
        ThermalLevel.MODERATE -> Budget(
            level = level,
            targetFps = HoldTimePlan.MIN_FPS * 0.75,
            roiDownsample = 2,
            gateThresholdScale = 1.25,
            dutyCycle = DUTY_WORK_MS to DUTY_IDLE_MS,
            paused = false,
            aborted = false,
            userMessage = "Device is warm — running slower to keep the transfer alive.",
        )

        // Third lever: stop, but stay resumable and SAY SO. An unexplained stall
        // makes the user move the phone, which loses the calibration pose.
        ThermalLevel.SEVERE -> Budget(
            level = level,
            targetFps = HoldTimePlan.MIN_FPS * 0.5,
            roiDownsample = 4,
            gateThresholdScale = 1.5,
            dutyCycle = DUTY_WORK_MS to DUTY_IDLE_MS,
            paused = true,
            aborted = false,
            userMessage = "Paused: device too hot. Hold position — this resumes " +
                "automatically once it cools.",
        )

        // The device is protecting itself and will start killing processes.
        // Abort deliberately, persisting resume state, rather than being killed
        // mid-write.
        ThermalLevel.CRITICAL -> Budget(
            level = level,
            targetFps = HoldTimePlan.MIN_FPS * 0.5,
            roiDownsample = 4,
            gateThresholdScale = 1.5,
            dutyCycle = DUTY_WORK_MS to DUTY_IDLE_MS,
            paused = true,
            aborted = true,
            userMessage = "Stopped: device overheated. Progress is saved — resume " +
                "when it has cooled.",
        )
    }

    /**
     * Maps `PowerManager.THERMAL_STATUS_*` to the ladder.
     *
     * Kept here, in pure code, rather than in :platform so the mapping is
     * testable. THERMAL_STATUS_NONE=0, LIGHT=1, MODERATE=2, SEVERE=3,
     * CRITICAL=4, EMERGENCY=5, SHUTDOWN=6.
     *
     * EMERGENCY and SHUTDOWN map to CRITICAL: they are strictly worse, and an
     * unknown//future higher value must never be treated as "fine". Anything
     * unrecognised clamps to CRITICAL for the same reason — failing safe here
     * costs a resumable abort, failing open costs a dead battery or a
     * process kill mid-transfer.
     */
    fun fromPlatformStatus(status: Int): ThermalLevel = when (status) {
        0 -> ThermalLevel.NONE
        1 -> ThermalLevel.LIGHT
        2 -> ThermalLevel.MODERATE
        3 -> ThermalLevel.SEVERE
        else -> ThermalLevel.CRITICAL
    }

    /** Gate thresholds tighten as the device heats; never loosen. */
    fun scaledThresholds(blurMin: Double, contrastMin: Double, b: Budget): Pair<Double, Double> =
        blurMin * b.gateThresholdScale to
            // Contrast is a normalised 0..1 dynamic range, so scaling must not
            // push it past 1.0 (which no real scene could ever satisfy) — that
            // would turn "throttle" into "refuse everything forever".
            (contrastMin * b.gateThresholdScale).coerceAtMost(0.95)

    /**
     * Whether the pipeline should be working at [elapsedInPhaseMs] into the
     * current duty window. Pure modular arithmetic — no timers, no wakeups.
     */
    fun isWorkPhase(b: Budget, elapsedInPhaseMs: Long): Boolean {
        if (b.paused || b.aborted) return false
        val (work, idle) = b.dutyCycle ?: return true
        val period = work + idle
        return (elapsedInPhaseMs % period) < work
    }
}

/**
 * Stateful wrapper applying the asymmetric hysteresis of rule 1.
 *
 * Not merged into [ThermalGovernor] because the governor's value is that it is a
 * pure function; this class holds the only mutable state (the current level and
 * when a cooler reading was first seen) and is driven by an explicit clock, so
 * it is still fully testable without a device or a scheduler.
 */
class ThermalTracker(
    private val dwellMs: Long = ThermalGovernor.COOLDOWN_DWELL_MS,
) {
    var level: ThermalLevel = ThermalLevel.NONE
        private set

    /** Worst level seen this session — surfaced in diagnostics, never a lever. */
    var peak: ThermalLevel = ThermalLevel.NONE
        private set

    private var pendingCooler: ThermalLevel? = null
    private var pendingSinceMs: Long = 0

    /** True when a session was thermally aborted; only [reset] clears it. */
    var latchedAbort: Boolean = false
        private set

    /**
     * @return true if the effective level changed (caller should re-plan).
     */
    fun observe(reported: ThermalLevel, nowMs: Long): Boolean {
        if (reported.ordinal >= ThermalLevel.CRITICAL.ordinal) latchedAbort = true
        if (reported.ordinal > peak.ordinal) peak = reported

        // Heating: react immediately. Any delay here is thermal debt.
        if (reported.ordinal > level.ordinal) {
            level = reported
            pendingCooler = null
            return true
        }

        if (reported.ordinal == level.ordinal) {
            // Back to where we are; cancel any in-flight cooldown.
            pendingCooler = null
            return false
        }

        // Cooling: require a sustained dwell before giving throughput back.
        if (pendingCooler == null || reported != pendingCooler) {
            pendingCooler = reported
            pendingSinceMs = nowMs
            return false
        }
        if (nowMs - pendingSinceMs >= dwellMs) {
            // Step down ONE rung at a time even if the reading jumped several.
            // A device that reads NONE straight after SEVERE is almost certainly
            // flapping, and restoring full power to it immediately is how you
            // get a sawtooth instead of a steady transfer.
            level = ThermalLevel.entries[max(reported.ordinal, level.ordinal - 1)]
            pendingCooler = null
            pendingSinceMs = nowMs
            return true
        }
        return false
    }

    fun budget(): ThermalGovernor.Budget = ThermalGovernor.budgetFor(level)

    fun reset() {
        level = ThermalLevel.NONE
        peak = ThermalLevel.NONE
        pendingCooler = null
        pendingSinceMs = 0
        latchedAbort = false
    }
}
