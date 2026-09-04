package app.candela.render

import kotlin.system.exitProcess

/**
 * Stage 7 verification: the thermal governor.
 *
 * The governor's job is to keep a transfer alive on a passively cooled device.
 * The tests are written around the two properties that make that true:
 *
 *   RATCHET   — no lever ever permits more work at a hotter level. Asserted
 *               exhaustively across every ordered pair of levels, not just
 *               adjacent ones, because a single edited branch is exactly how
 *               this breaks.
 *   NO LOSS   — every lever trades throughput, never correctness. Pausing and
 *               aborting must be resumable; nothing here may drop a symbol
 *               silently or write a partial file.
 */

private object T {
    var passed = 0
    var failed = 0
    val failures = mutableListOf<String>()
    var suite = ""

    fun suite(n: String) {
        suite = n
        println("\n\u2500\u2500 $n ${"\u2500".repeat(maxOf(2, 58 - n.length))}")
    }

    fun check(name: String, cond: Boolean, detail: String = "") {
        if (cond) passed++ else {
            failed++
            failures.add("[$suite] $name :: $detail")
            println("  FAIL  $name  ($detail)")
        }
    }

    fun info(m: String) = println("  \u2022 $m")
    fun ok(m: String) = println("  \u2713 $m")

    fun summary(): Int {
        println("\n" + "=".repeat(64))
        if (failed == 0) println("ALL TESTS PASSED   $passed assertions, 0 failures")
        else {
            println("FAILURES: $failed  (passed $passed)")
            failures.forEach { println("   - $it") }
        }
        println("=".repeat(64))
        return if (failed == 0) 0 else 1
    }
}

fun main() {
    println("Candela Stage 7 verification — thermal governor")

    testPlatformMapping()
    testLadderRatchet()
    testLeverOrdering()
    testDutyCycle()
    testHysteresis()
    testGateThresholdScaling()
    testPacingIntegration()

    exitProcess(T.summary())
}

private fun testPlatformMapping() {
    T.suite("PowerManager status mapping")
    val g = ThermalGovernor
    T.check("0 -> NONE", g.fromPlatformStatus(0) == ThermalLevel.NONE)
    T.check("1 -> LIGHT", g.fromPlatformStatus(1) == ThermalLevel.LIGHT)
    T.check("2 -> MODERATE", g.fromPlatformStatus(2) == ThermalLevel.MODERATE)
    T.check("3 -> SEVERE", g.fromPlatformStatus(3) == ThermalLevel.SEVERE)
    T.check("4 (CRITICAL) -> CRITICAL", g.fromPlatformStatus(4) == ThermalLevel.CRITICAL)

    // Fail safe, not open: worse-than-known must never read as "fine".
    T.check("5 (EMERGENCY) -> CRITICAL", g.fromPlatformStatus(5) == ThermalLevel.CRITICAL)
    T.check("6 (SHUTDOWN) -> CRITICAL", g.fromPlatformStatus(6) == ThermalLevel.CRITICAL)
    T.check("99 (unknown future) -> CRITICAL", g.fromPlatformStatus(99) == ThermalLevel.CRITICAL)
    T.check("-1 (garbage) -> CRITICAL", g.fromPlatformStatus(-1) == ThermalLevel.CRITICAL)
    T.ok("unknown status clamps to the safest interpretation")
}

private fun testLadderRatchet() {
    T.suite("Ratchet: hotter never permits more work")
    val levels = ThermalLevel.entries
    val budgets = levels.associateWith { ThermalGovernor.budgetFor(it) }

    // Every ordered pair, not just neighbours.
    for (i in levels.indices) {
        for (j in i + 1 until levels.size) {
            val cool = budgets[levels[i]]!!
            val hot = budgets[levels[j]]!!
            val a = levels[i].name
            val b = levels[j].name
            T.check("$b fps <= $a", hot.targetFps <= cool.targetFps,
                "${hot.targetFps} vs ${cool.targetFps}")
            T.check("$b downsample >= $a", hot.roiDownsample >= cool.roiDownsample,
                "${hot.roiDownsample} vs ${cool.roiDownsample}")
            T.check("$b thresholds >= $a",
                hot.gateThresholdScale >= cool.gateThresholdScale)
            T.check("$b duty <= $a", hot.dutyFraction <= cool.dutyFraction,
                "${hot.dutyFraction} vs ${cool.dutyFraction}")
            T.check("$b workIndex <= $a", hot.workIndex <= cool.workIndex,
                "${hot.workIndex} vs ${cool.workIndex}")
        }
    }

    val idx = levels.map { budgets[it]!!.workIndex }
    T.info("work index by level: " + levels.zip(idx).joinToString(", ") {
        "${it.first}=${"%.2f".format(it.second)}"
    })
    T.check("strictly decreases NONE->SEVERE",
        idx[0] > idx[1] && idx[1] > idx[2] && idx[2] > idx[3])
    T.ok("no lever regresses at any hotter level")
}

private fun testLeverOrdering() {
    T.suite("Levers engage cheapest-harm-first")
    val none = ThermalGovernor.budgetFor(ThermalLevel.NONE)
    val light = ThermalGovernor.budgetFor(ThermalLevel.LIGHT)
    val mod = ThermalGovernor.budgetFor(ThermalLevel.MODERATE)
    val sev = ThermalGovernor.budgetFor(ThermalLevel.SEVERE)
    val crit = ThermalGovernor.budgetFor(ThermalLevel.CRITICAL)

    T.check("NONE runs unrestricted", none.dutyCycle == null && !none.paused && !none.aborted)
    T.check("NONE at max fps", none.targetFps == HoldTimePlan.MAX_FPS)
    T.check("NONE full resolution", none.roiDownsample == 1)
    T.check("NONE says nothing", none.userMessage == null)

    // LIGHT must be invisible: no pause, no duty cycle, no nagging.
    T.check("LIGHT does not duty-cycle", light.dutyCycle == null)
    T.check("LIGHT does not pause", !light.paused)
    T.check("LIGHT is silent", light.userMessage == null)
    T.check("LIGHT still inside product envelope", light.targetFps >= HoldTimePlan.MIN_FPS)

    T.check("MODERATE duty-cycles", mod.dutyCycle != null)
    T.check("MODERATE does not pause", !mod.paused)
    T.check("MODERATE explains itself", !mod.userMessage.isNullOrBlank())

    T.check("SEVERE pauses", sev.paused)
    T.check("SEVERE does not abort", !sev.aborted)
    T.check("SEVERE promises resume", sev.userMessage!!.contains("resume", ignoreCase = true))
    T.check("SEVERE does no work", sev.dutyFraction == 0.0)

    T.check("CRITICAL aborts", crit.aborted)
    T.check("CRITICAL mentions saved progress",
        crit.userMessage!!.contains("saved", ignoreCase = true))
    T.check("CRITICAL does no work", crit.workIndex == 0.0)

    // A silent stall makes the user move the phone and lose the pose.
    for (l in listOf(ThermalLevel.MODERATE, ThermalLevel.SEVERE, ThermalLevel.CRITICAL)) {
        val b = ThermalGovernor.budgetFor(l)
        T.check("$l is user-visible", !b.userMessage.isNullOrBlank())
    }
    T.ok("slow -> decimate -> duty-cycle -> pause -> abort, each explained")
}

private fun testDutyCycle() {
    T.suite("Duty cycle arithmetic (8 s work / 2 s idle)")
    val mod = ThermalGovernor.budgetFor(ThermalLevel.MODERATE)
    T.check("80% duty", kotlin.math.abs(mod.dutyFraction - 0.8) < 1e-9,
        "${mod.dutyFraction}")

    T.check("works at t=0", ThermalGovernor.isWorkPhase(mod, 0))
    T.check("works at 7.9 s", ThermalGovernor.isWorkPhase(mod, 7_900))
    T.check("idle at 8.0 s", !ThermalGovernor.isWorkPhase(mod, 8_000))
    T.check("idle at 9.9 s", !ThermalGovernor.isWorkPhase(mod, 9_999))
    T.check("works again at 10.0 s", ThermalGovernor.isWorkPhase(mod, 10_000))
    T.check("periodic at 30 s", ThermalGovernor.isWorkPhase(mod, 30_000))

    // Measured duty over a full minute must match the declared fraction.
    var working = 0
    for (ms in 0 until 60_000 step 100) if (ThermalGovernor.isWorkPhase(mod, ms.toLong())) working++
    val measured = working / 600.0
    T.check("measured duty ~= 0.8", kotlin.math.abs(measured - 0.8) < 0.02, "$measured")
    T.info("measured %.1f%% work over 60 s".format(measured * 100))

    val none = ThermalGovernor.budgetFor(ThermalLevel.NONE)
    T.check("NONE always works", (0..50).all {
        ThermalGovernor.isWorkPhase(none, it * 1000L)
    })
    val sev = ThermalGovernor.budgetFor(ThermalLevel.SEVERE)
    T.check("paused never works", (0..50).none {
        ThermalGovernor.isWorkPhase(sev, it * 1000L)
    })
    T.ok("duty cycle is pure modular arithmetic — no timers, no wakeups")
}

private fun testHysteresis() {
    T.suite("Asymmetric hysteresis (fast up, slow down)")
    val t = ThermalTracker(dwellMs = 20_000)
    var now = 0L

    T.check("starts NONE", t.level == ThermalLevel.NONE)

    T.check("escalation reports change", t.observe(ThermalLevel.MODERATE, now))
    T.check("escalates instantly", t.level == ThermalLevel.MODERATE)

    // Cooling must NOT take effect immediately.
    now += 1_000
    T.check("cooler reading is not an immediate change", !t.observe(ThermalLevel.NONE, now))
    T.check("still MODERATE after 1 s", t.level == ThermalLevel.MODERATE)
    now += 10_000
    t.observe(ThermalLevel.NONE, now)
    T.check("still MODERATE after 11 s", t.level == ThermalLevel.MODERATE)

    now += 10_000 // 21 s of sustained cool
    T.check("de-escalates after dwell", t.observe(ThermalLevel.NONE, now))
    T.check("steps down ONE rung, not straight to NONE", t.level == ThermalLevel.LIGHT,
        "level=${t.level}")

    // Heating during a pending cooldown must cancel it and take effect at once.
    val t2 = ThermalTracker(dwellMs = 20_000)
    t2.observe(ThermalLevel.SEVERE, 0)
    t2.observe(ThermalLevel.LIGHT, 1_000)   // starts cooling
    t2.observe(ThermalLevel.CRITICAL, 2_000) // spike
    T.check("spike overrides pending cooldown", t2.level == ThermalLevel.CRITICAL)
    t2.observe(ThermalLevel.LIGHT, 3_000)
    t2.observe(ThermalLevel.LIGHT, 30_000)
    T.check("cooldown restarts after the spike", t2.level == ThermalLevel.SEVERE,
        "level=${t2.level}")

    // Flapping at a boundary must not oscillate the budget.
    val t3 = ThermalTracker(dwellMs = 20_000)
    var changes = 0
    var clock = 0L
    t3.observe(ThermalLevel.LIGHT, clock)
    for (i in 0 until 40) { // 40 flips over ~20 s, i.e. every 500 ms
        clock += 500
        val reported = if (i % 2 == 0) ThermalLevel.NONE else ThermalLevel.LIGHT
        if (t3.observe(reported, clock)) changes++
    }
    T.check("flapping input causes no budget churn", changes == 0, "changes=$changes")
    T.info("40 boundary flips over 20 s produced $changes re-plans")

    // Peak and latched abort are diagnostics that must survive cooling.
    val t4 = ThermalTracker(dwellMs = 1)
    t4.observe(ThermalLevel.CRITICAL, 0)
    T.check("abort latches", t4.latchedAbort)
    t4.observe(ThermalLevel.NONE, 10_000)
    t4.observe(ThermalLevel.NONE, 20_000)
    T.check("peak survives cooling", t4.peak == ThermalLevel.CRITICAL)
    T.check("latched abort survives cooling", t4.latchedAbort)
    t4.reset()
    T.check("reset clears latch", !t4.latchedAbort && t4.level == ThermalLevel.NONE)
    T.ok("heating is instant, cooling costs 20 s of sustained evidence")
}

private fun testGateThresholdScaling() {
    T.suite("Gate thresholds tighten, never loosen")
    // The learned values from the Stage 2 vision run.
    val blur = 74.518
    val contrast = 0.656

    for (l in ThermalLevel.entries) {
        val b = ThermalGovernor.budgetFor(l)
        val (sb, sc) = ThermalGovernor.scaledThresholds(blur, contrast, b)
        T.check("$l blur not loosened", sb >= blur, "$sb < $blur")
        T.check("$l contrast not loosened", sc >= contrast, "$sc < $contrast")
        T.check("$l contrast stays achievable", sc <= 0.95, "$sc")
    }

    // A high contrast floor must not be scaled into impossibility.
    val (_, clamped) = ThermalGovernor.scaledThresholds(
        10.0, 0.9, ThermalGovernor.budgetFor(ThermalLevel.SEVERE),
    )
    T.check("0.9 * 1.5 clamps to <= 0.95", clamped <= 0.95, "$clamped")
    T.check("clamp does not loosen", clamped >= 0.9)
    T.info("SEVERE scales blur 74.5 -> %.1f".format(
        ThermalGovernor.scaledThresholds(blur, contrast,
            ThermalGovernor.budgetFor(ThermalLevel.SEVERE)).first))
    T.ok("throttling never becomes refuse-everything-forever")
}

private fun testPacingIntegration() {
    T.suite("Governor drives real sender pacing")
    val base = HoldTimePlan.compute(refreshHz = 60.0)
    T.info("base: %.1f sym/s, %d frames/symbol".format(base.effectiveFps, base.framesPerSymbol))

    var lastFps = Double.MAX_VALUE
    for (l in ThermalLevel.entries) {
        val plan = HoldTimePlan.derate(base, l)
        T.check("$l pacing non-increasing", plan.effectiveFps <= lastFps + 1e-9,
            "${plan.effectiveFps} > $lastFps")
        T.check("$l hold is a whole number of frames",
            plan.holdNs == plan.framesPerSymbol * (1_000_000_000L / 60))
        T.check("$l never faster than base", plan.effectiveFps <= base.effectiveFps + 1e-9)
        lastFps = plan.effectiveFps
        T.info("$l -> %.1f sym/s (%d frames, %.0f ms hold)".format(
            plan.effectiveFps, plan.framesPerSymbol, plan.holdMs))
    }

    // Derating must agree with the budget: pacing and budget cannot disagree.
    for (l in ThermalLevel.entries) {
        val plan = HoldTimePlan.derate(base, l)
        val b = ThermalGovernor.budgetFor(l)
        T.check("$l plan respects budget ceiling", plan.effectiveFps <= b.targetFps + 0.5,
            "plan=${plan.effectiveFps} ceiling=${b.targetFps}")
    }

    // A slow-readout receiver already below the thermal target must not be sped up.
    val slow = HoldTimePlan.compute(refreshHz = 60.0, receiverReadoutNs = 30_000_000L)
    val slowDerated = HoldTimePlan.derate(slow, ThermalLevel.LIGHT)
    T.check("derate never speeds a slow plan up",
        slowDerated.effectiveFps <= slow.effectiveFps + 1e-9,
        "${slowDerated.effectiveFps} vs ${slow.effectiveFps}")
    T.info("slow-readout base %.1f sym/s stays %.1f under LIGHT".format(
        slow.effectiveFps, slowDerated.effectiveFps))
    T.ok("thermal ladder is a ceiling on work, never a floor on rate")
}
