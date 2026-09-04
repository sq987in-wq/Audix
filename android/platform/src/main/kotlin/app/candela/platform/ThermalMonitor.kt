package app.candela.platform

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import app.candela.render.ThermalGovernor
import app.candela.render.ThermalLevel
import app.candela.render.ThermalTracker
import java.util.concurrent.Executor

/**
 * `PowerManager` thermal listener (audit section 3).
 *
 * This class is deliberately almost empty. It registers a listener, converts an
 * `Int` to a [ThermalLevel], and forwards it to [ThermalTracker]. Every actual
 * decision — pacing, duty cycle, downsample, pause, abort — lives in pure Kotlin
 * in :optical-render and is unit-tested on a bare JVM. The rule is that nothing
 * here may branch on a thermal value, because nothing here can be tested here.
 *
 * API level: `addThermalStatusListener` is API 29+. The app's minSdk is 26, so
 * on 26-28 there is no thermal API at all and [isSupported] is false. That case
 * is NOT silently ignored — see [ThermalMonitor.start]'s contract below.
 */
class ThermalMonitor(
    context: Context,
    private val tracker: ThermalTracker = ThermalTracker(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    val isSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    private var listener: PowerManager.OnThermalStatusChangedListener? = null

    /** Latest budget. Safe to read from any thread; replaced wholesale, never mutated. */
    @Volatile
    var budget: ThermalGovernor.Budget = ThermalGovernor.budgetFor(ThermalLevel.NONE)
        private set

    /**
     * @param onChange invoked only when the EFFECTIVE level changes (i.e. after
     *   hysteresis), on [executor]. Callers re-plan pacing here; they must not
     *   poll [budget] on a per-frame path.
     *
     * On API < 29 this registers nothing and leaves the budget at NONE. That is a
     * real limitation, not a safe default: those devices run ungoverned. The
     * honest mitigation is the conservative static envelope the sender already
     * uses (8-12 fps, gated decode), plus the session-duration cap. Callers can
     * check [isSupported] to surface it.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun start(executor: Executor, onChange: (ThermalGovernor.Budget) -> Unit) {
        if (!isSupported || listener != null) return

        // Seed from the CURRENT status before subscribing. Listeners only fire on
        // change, so a session started on an already-warm device would otherwise
        // run at full power until the status happened to move.
        applyStatus(power.currentThermalStatus, onChange, force = true, executor = executor)

        val l = PowerManager.OnThermalStatusChangedListener { status ->
            applyStatus(status, onChange, force = false, executor = executor)
        }
        listener = l
        power.addThermalStatusListener(executor, l)
    }

    private fun applyStatus(
        status: Int,
        onChange: (ThermalGovernor.Budget) -> Unit,
        force: Boolean,
        executor: Executor,
    ) {
        val level = ThermalGovernor.fromPlatformStatus(status)
        val changed = tracker.observe(level, clock())
        if (changed || force) {
            budget = tracker.budget()
            executor.execute { onChange(budget) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun stop() {
        listener?.let { power.removeThermalStatusListener(it) }
        listener = null
    }

    /**
     * Re-read the status directly. The listener is the normal path; this exists
     * for the resume-after-pause check, where we need to know whether the device
     * has cooled without waiting for an edge that may never come (a device can
     * sit at SEVERE indefinitely).
     */
    fun poll(): ThermalLevel =
        if (!isSupported) ThermalLevel.NONE
        else ThermalGovernor.fromPlatformStatus(power.currentThermalStatus)
            .also { tracker.observe(it, clock()); budget = tracker.budget() }

    val level: ThermalLevel get() = tracker.level
    val peak: ThermalLevel get() = tracker.peak
}

/**
 * Session-scoped partial wakelock (audit section 3: "session-scoped wakelock with
 * timeout; no background scanning, ever").
 *
 * The timeout is not belt-and-braces, it is the primary safety mechanism: a
 * leaked partial wakelock on a device that is already thermally stressed is one
 * of the few ways an app can flatten a battery outright. It is bounded so that
 * even a crash between acquire and release cannot hold the CPU indefinitely.
 */
class SessionWakeLock(context: Context) {
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private var lock: PowerManager.WakeLock? = null

    fun acquire(timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        if (lock != null) return
        lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG).apply {
            setReferenceCounted(false)
            acquire(timeoutMs)
        }
    }

    fun release() {
        lock?.let { if (it.isHeld) it.release() }
        lock = null
    }

    companion object {
        private const val TAG = "candela:session"

        /**
         * 10 minutes. Longer than any plausible single transfer at the audit's
         * throughput, short enough that a leak is bounded. A transfer still
         * running at 10 minutes has other problems.
         */
        const val DEFAULT_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
