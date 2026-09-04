package app.candela.render

import kotlin.math.ceil
import kotlin.math.max

/**
 * Symbol hold-time pacing — the rolling-shutter countermeasure (audit section 2).
 *
 * The core insight, and the reason this is arithmetic rather than clock sync:
 * the sender panel's scan-out clock and the receiver's row-readout clock are
 * independent and cannot be synchronised over an optical channel. Trying is
 * hopeless. Instead you make the phase RELATIONSHIP IRRELEVANT by holding each
 * symbol on screen for long enough that any camera frame landing anywhere in the
 * cycle sees static content:
 *
 *     HOLD >= 6 x max(screenPeriod, exposure + readout)
 *
 * Tearing (two different symbols in one camera image) becomes impossible when the
 * content does not change during a sensor readout. At 60 Hz that lands at ~100 ms,
 * which is where the audit's 8-12 fps sender rate comes from.
 *
 * Pure Kotlin: no Choreographer, no Display, no android.* imports. The render
 * layer asks this class what to do and when.
 */
object HoldTimePlan {

    /** Audit section 2.2: hold must exceed the worst-case period by this factor. */
    const val PHASE_INDEPENDENCE_FACTOR = 6

    /** Product envelope: sender runs 8-12 fps (audit section 7). */
    const val MIN_FPS = 8.0
    const val MAX_FPS = 12.0

    const val HZ_60_PERIOD_NS = 16_666_667L

    /** ExposurePlan.SHORT_MAX_NS — the receiver's preferred 1/250 s freeze. */
    const val SHORT_EXPOSURE_NS = 4_000_000L

    data class Plan(
        val holdNs: Long,
        val framesPerSymbol: Int,
        val effectiveFps: Double,
        val refreshHz: Double,
        val note: String,
    ) {
        val holdMs: Double get() = holdNs / 1_000_000.0
    }

    /**
     * @param refreshHz the panel rate we will pin via Display.setFrameRate
     * @param receiverExposureNs receiver exposure. Defaults to the SHORT_FREEZE
     *   ceiling (4 ms), because that is the strategy ExposurePlan prefers and the
     *   one the receiver will be running in any adequately lit room. Defaulting to
     *   a full 16.7 ms LONG_INTEGRATE exposure would inflate the hold to ~183 ms
     *   and drop the sender to 5.5 symbols/s — outside the audit's 8-12 fps
     *   envelope, i.e. paying a 45% throughput tax for a case that is the
     *   documented fallback, not the norm. A receiver that genuinely needs the
     *   long exposure passes it explicitly and the hold lengthens accordingly.
     * @param receiverReadoutNs rolling-shutter readout for 1080p, 6-12 ms typical
     *
     * Hold is quantised UP to a whole number of display frames: a symbol swap
     * mid-scanout is exactly the tear this is preventing.
     */
    fun compute(
        refreshHz: Double = 60.0,
        receiverExposureNs: Long = SHORT_EXPOSURE_NS,
        receiverReadoutNs: Long = 12_000_000L,
    ): Plan {
        val periodNs = (1_000_000_000.0 / refreshHz).toLong()
        val worstCaseNs = max(periodNs, receiverExposureNs + receiverReadoutNs)
        val requiredNs = worstCaseNs * PHASE_INDEPENDENCE_FACTOR

        // Round up to whole display frames.
        var frames = ceil(requiredNs.toDouble() / periodNs).toInt().coerceAtLeast(1)
        var holdNs = frames.toLong() * periodNs
        var fps = 1_000_000_000.0 / holdNs

        // Clamp into the product envelope. Slower is always safe (fountain codes
        // absorb it); faster than 12 fps starts risking tears on slow readouts.
        if (fps > MAX_FPS) {
            frames = ceil(1_000_000_000.0 / MAX_FPS / periodNs).toInt()
            holdNs = frames.toLong() * periodNs
            fps = 1_000_000_000.0 / holdNs
        }

        val note = buildString {
            append("hold ${"%.0f".format(holdNs / 1e6)} ms = $frames frames @ ")
            append("${"%.0f".format(refreshHz)} Hz -> ${"%.1f".format(fps)} symbols/s. ")
            append("Phase-independent: ${PHASE_INDEPENDENCE_FACTOR}x worst-case ")
            append("${"%.1f".format(worstCaseNs / 1e6)} ms (exposure+readout vs refresh).")
        }
        return Plan(holdNs, frames, fps, refreshHz, note)
    }

    /**
     * Thermal derating (audit section 3 / PSR section 5.4). Slowing the sender is
     * always safe for correctness — the fountain simply takes longer — so this is
     * the first lever the governor pulls.
     */
    fun derate(base: Plan, thermal: ThermalLevel): Plan = when (thermal) {
        ThermalLevel.NONE -> base
        ThermalLevel.LIGHT -> scaleToFps(base, MIN_FPS)
        ThermalLevel.MODERATE -> scaleToFps(base, MIN_FPS * 0.75)
        ThermalLevel.SEVERE, ThermalLevel.CRITICAL -> scaleToFps(base, MIN_FPS * 0.5)
    }

    /**
     * Derating must only ever SLOW the sender. If the base plan is already below
     * the target (e.g. a slow-readout receiver forced a long hold), keep the base
     * hold rather than speeding up into a tearing risk to satisfy a thermal
     * target — the thermal ladder is a ceiling on work, not a floor on rate.
     */
    private fun scaleToFps(base: Plan, targetFps: Double): Plan {
        val periodNs = (1_000_000_000.0 / base.refreshHz).toLong()
        val frames = ceil(1_000_000_000.0 / targetFps / periodNs).toInt()
            .coerceAtLeast(base.framesPerSymbol)
        val holdNs = frames.toLong() * periodNs
        if (frames == base.framesPerSymbol) return base
        return base.copy(
            holdNs = holdNs,
            framesPerSymbol = frames,
            effectiveFps = 1_000_000_000.0 / holdNs,
            note = "thermally derated to ${"%.1f".format(1_000_000_000.0 / holdNs)} symbols/s",
        )
    }
}

enum class ThermalLevel { NONE, LIGHT, MODERATE, SEVERE, CRITICAL }

/**
 * Drives which symbol is on screen, given a monotonic clock.
 *
 * Deliberately a pure function of time: the Choreographer callback asks
 * [symbolIndexAt] and only redraws when the answer changes. No timers, no
 * coroutine delays, no drift accumulation, and — critically — no allocation and
 * no redraw on the ~5 of 6 vsyncs where the content is unchanged (audit kill #5).
 */
class SymbolScheduler(
    private var plan: HoldTimePlan.Plan,
    private val totalSymbols: Int,
    /** HEADER re-sent every N data symbols; it is the trust anchor. */
    private val headerInterleave: Int = 8,
) {
    private var startNs: Long = -1
    private var lastEmitted: Int = -1

    fun start(nowNs: Long) {
        startNs = nowNs
        lastEmitted = -1
    }

    fun updatePlan(p: HoldTimePlan.Plan) {
        plan = p
    }

    /** Slot index since start; each slot is one held symbol. */
    fun slotAt(nowNs: Long): Int {
        if (startNs < 0) return 0
        return ((nowNs - startNs) / plan.holdNs).toInt()
    }

    /**
     * What to display in the current slot.
     * Every (headerInterleave+1)-th slot is a HEADER so a late-joining or
     * resuming receiver re-acquires the manifest without restarting the session.
     */
    fun contentAt(nowNs: Long): Content {
        val slot = slotAt(nowNs)
        val cycle = headerInterleave + 1
        return if (slot % cycle == 0) {
            Content.Header
        } else {
            val dataIndex = slot - (slot / cycle) - 1
            Content.Data(if (totalSymbols == 0) 0 else dataIndex % totalSymbols)
        }
    }

    /**
     * True only when the content differs from what was last drawn.
     * This is the zero-allocation guard: at 60 Hz with a 6-frame hold, 5 of every
     * 6 vsync callbacks return false and cost nothing.
     */
    fun needsRedraw(nowNs: Long): Boolean {
        val slot = slotAt(nowNs)
        if (slot == lastEmitted) return false
        lastEmitted = slot
        return true
    }

    sealed interface Content {
        object Header : Content
        data class Data(val symbolIndex: Int) : Content
    }
}
