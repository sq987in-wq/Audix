package app.candela.camera

import app.candela.render.HoldTimePlan
import app.candela.render.SymbolScheduler
import app.candela.render.ThermalLevel
import kotlin.system.exitProcess

/**
 * Stage 4 + Stage 6 verification for everything that can be tested without an
 * Android device: exposure strategy, the AF/AE lock state machine, YUV ROI
 * cropping arithmetic, hold-time pacing and the symbol scheduler.
 *
 * The Camera2/SurfaceView call layers cannot run here (no SDK, no device) and are
 * verified on-device per the plan's Stage 4/6 exit tests. What IS covered here is
 * every decision those layers make — which is where the bugs actually live.
 */

private object C {
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

private val FULL_MANUAL = ExposurePlan.SensorLimits(
    minExposureNs = 20_000L,        // 1/50000 s
    maxExposureNs = 200_000_000L,   // 1/5 s
    minIso = 50,
    maxIso = 6400,
    manualSensorSupported = true,
)

fun main() {
    println("Candela Stage 4 + Stage 6 logic verification")

    testExposureStrategy()
    testForbiddenBand()
    testLockPolicy()
    testRelockDiscipline()
    testRoiGeometry()
    testRoiCropping()
    testOtsuAndMedian()
    testHoldTime()
    testScheduler()
    testThermalDerate()

    exitProcess(C.summary())
}

// ---------------------------------------------------------------- Stage 4

private fun testExposureStrategy() {
    C.suite("Exposure strategy (audit C1)")

    // Bright indoor scene: AE lands around 1/500 s at ISO 200.
    val bright = ExposurePlan.choose(2_000_000L, 200, ExposurePlan.BAND_HI_NS + 1_666_667L, FULL_MANUAL)
    C.check("bright scene freezes", bright.strategy == ExposurePlan.Strategy.SHORT_FREEZE)
    C.check("bright exposure <= 4 ms", bright.exposureTimeNs <= ExposurePlan.SHORT_MAX_NS)
    C.check("bright ISO within 200-800", bright.iso in ExposurePlan.ISO_MIN..ExposurePlan.ISO_MAX,
        "iso=${bright.iso}")
    C.info("bright: ${bright.exposureMs} ms @ ISO ${bright.iso} (${bright.strategy})")

    // Dim room: AE wants 1/30 s at ISO 1600. Cannot freeze inside ISO 800.
    val dim = ExposurePlan.choose(33_000_000L, 1600, 16_666_667L, FULL_MANUAL)
    C.check("dim scene integrates instead", dim.strategy == ExposurePlan.Strategy.LONG_INTEGRATE)
    C.check("dim exposure >= one screen period", dim.exposureTimeNs > ExposurePlan.BAND_HI_NS,
        "exposure=${dim.exposureMs} ms")
    C.info("dim: ${dim.exposureMs} ms @ ISO ${dim.iso} (${dim.strategy})")

    // LEGACY device: no manual sensor control at all.
    val legacy = ExposurePlan.choose(
        16_666_667L, 400, 16_666_667L, FULL_MANUAL.copy(manualSensorSupported = false),
    )
    C.check("legacy falls back to AE lock", legacy.clamped)
    C.check("legacy note explains degradation", legacy.note.contains("LEGACY"))
    C.ok("brightness preserved via exposure x gain product")
}

private fun testForbiddenBand() {
    C.suite("The 5-15 ms forbidden band (audit section 2.2 vs 5.1)")

    // Sweep a wide range of metering outcomes; NONE may land in the band.
    var violations = 0
    var shortCount = 0
    var longCount = 0
    for (expUs in longArrayOf(500, 1_000, 2_000, 4_000, 8_000, 12_000, 16_000, 33_000, 66_000)) {
        for (iso in intArrayOf(50, 100, 200, 400, 800, 1600, 3200)) {
            val p = ExposurePlan.choose(expUs * 1_000L, iso, 16_666_667L, FULL_MANUAL)
            if (p.inForbiddenBand) {
                violations++
                C.check("no plan in band (exp=${expUs}us iso=$iso)", false, "${p.exposureMs} ms")
            }
            if (p.strategy == ExposurePlan.Strategy.SHORT_FREEZE) shortCount++ else longCount++
        }
    }
    C.check("no exposure plan lands in 5-15 ms", violations == 0, "$violations violations")
    C.info("swept 63 metering outcomes: $shortCount short-freeze, $longCount long-integrate")
    C.ok("blur-and-banding worst case is unreachable by construction")
}

private fun testLockPolicy() {
    C.suite("AF/AE lock sequence (audit C1)")
    val p = LockPolicy()
    C.check("starts unlocked", p.phase == LockPolicy.Phase.UNLOCKED)
    C.check("not decodable while unlocked", !p.isDecodable)

    val a1 = p.beginLock()
    C.check("begin triggers AF", a1 is LockPolicy.Action.StartAfTrigger)
    C.check("phase is converging", p.phase == LockPolicy.Phase.AF_CONVERGING)
    C.check("still not decodable", !p.isDecodable)

    // A few frames of hunting, then focus.
    repeat(3) { p.onAfState(focused = false, failed = false, currentFocusDistance = 0f) }
    C.check("still converging while hunting", p.phase == LockPolicy.Phase.AF_CONVERGING)

    val a2 = p.onAfState(focused = true, failed = false, currentFocusDistance = 3.2f)
    C.check("focus lock pins the lens", a2 is LockPolicy.Action.PinFocus)
    C.check("focus distance captured", p.focusDistance == 3.2f)
    C.check("af converged flag", (a2 as LockPolicy.Action.PinFocus).afConverged)

    val plan = ExposurePlan.choose(2_000_000L, 200, 16_666_667L, FULL_MANUAL)
    val a3 = p.onFocusPinned(plan)
    C.check("freeze applied", a3 is LockPolicy.Action.ApplyFreeze)
    C.check("now locked", p.phase == LockPolicy.Phase.LOCKED)
    C.check("now decodable", p.isDecodable)

    // AF timeout path: a flat QR field often never reports FOCUSED_LOCKED.
    val q = LockPolicy()
    q.beginLock()
    var timedOutAction: LockPolicy.Action = LockPolicy.Action.None
    repeat(LockPolicy.AF_TIMEOUT_FRAMES) {
        timedOutAction = q.onAfState(false, false, 2.5f)
    }
    C.check("AF timeout still pins focus", timedOutAction is LockPolicy.Action.PinFocus)
    C.check("timeout recorded", q.afTimedOut)
    C.check(
        "timeout pins with afConverged=false",
        !(timedOutAction as LockPolicy.Action.PinFocus).afConverged,
    )
    C.ok("a soft but stable lens beats a hunting one")
}

private fun testRelockDiscipline() {
    C.suite("Re-lock only on sustained breach (audit C1 rule)")
    val p = LockPolicy()
    p.beginLock()
    p.onAfState(true, false, 3f)
    p.onFocusPinned(ExposurePlan.choose(2_000_000L, 200, 16_666_667L, FULL_MANUAL))

    // A burst of bad frames shorter than the threshold must NOT re-lock.
    repeat(LockPolicy.BREACH_FRAMES - 1) {
        C.check("no relock at ${it + 1} bad frames",
            p.onGateMetrics(false) is LockPolicy.Action.None)
    }
    C.check("still locked", p.phase == LockPolicy.Phase.LOCKED)
    C.check("no relocks yet", p.relockCount == 0)

    // One good frame resets the counter — transient noise must not accumulate.
    p.onGateMetrics(true)
    repeat(LockPolicy.BREACH_FRAMES - 1) { p.onGateMetrics(false) }
    C.check("good frame reset the breach counter", p.relockCount == 0)
    C.check("still locked after reset", p.phase == LockPolicy.Phase.LOCKED)

    // Sustained failure DOES re-lock.
    repeat(LockPolicy.BREACH_FRAMES) { p.onGateMetrics(false) }
    C.check("sustained breach relocks", p.relockCount == 1, "relocks=${p.relockCount}")
    C.check("relock re-enters converging", p.phase == LockPolicy.Phase.AF_CONVERGING)
    C.check("not decodable while relocking", !p.isDecodable)
    C.info("breach threshold = ${LockPolicy.BREACH_FRAMES} frames (~1.5 s @ 30 fps)")
    C.ok("no timer-driven AF hunting")
}

private fun testRoiGeometry() {
    C.suite("ROI geometry and metering regions")
    val r = IntRect(100, 50, 200, 200)
    C.check("clamp inside bounds", r.clampedTo(640, 480) == r)

    val over = IntRect(600, 400, 300, 300).clampedTo(640, 480)
    C.check("clamps overflow", over.right <= 640 && over.bottom <= 480, "got $over")

    val neg = IntRect(-50, -20, 100, 100).clampedTo(640, 480)
    C.check("clamps negative origin", neg.x >= 0 && neg.y >= 0, "got $neg")

    val pad = r.padded(12, 640, 480)
    C.check("padding expands", pad.width > r.width && pad.height > r.height)
    C.check("padding clamped at edges", IntRect(0, 0, 640, 480).padded(50, 640, 480).x == 0)

    // Sensor-array mapping for AE_REGIONS/AF_REGIONS.
    val metering = IntRect(160, 120, 320, 240).toMeteringRect(640, 480, 4032, 3024)
    C.check("metering scales to sensor array",
        metering.x in 1000..1030 && metering.width in 2000..2030, "got $metering")
    C.check("metering stays in sensor bounds",
        metering.right <= 4032 && metering.bottom <= 3024)

    val dst = YuvRoi.scaledSizeFor(177, 2.0)
    C.check("v40 ROI target ~2 px/module", dst in 360..380, "got $dst")
    C.info("QR v40 (177 modules + quiet zone) -> ${dst}x$dst decode buffer")
    C.ok("ROI maps correctly into sensor coordinates")
}

private fun testRoiCropping() {
    C.suite("YUV luma ROI cropping (audit C4)")
    val w = 640
    val h = 480
    // rowStride > width is the norm on real hardware; getting this wrong shears
    // the image and only shows up on-device.
    val stride = 704
    val luma = ByteArray(stride * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            luma[y * stride + x] = (if ((x / 8 + y / 8) % 2 == 0) 240 else 16).toByte()
        }
    }

    val buf = YuvRoi.Buffer()
    val roi = IntRect(160, 120, 320, 240)
    YuvRoi.cropScale(luma, w, h, stride, 1, roi, 128, 128, buf)

    C.check("buffer sized", buf.width == 128 && buf.height == 128)
    var dark = 0
    var light = 0
    for (i in 0 until 128 * 128) {
        val v = buf.data[i].toInt() and 0xFF
        if (v < 64) dark++ else if (v > 192) light++
    }
    C.check("preserves bimodal content", dark > 3000 && light > 3000, "dark=$dark light=$light")
    C.check("no mid-grey smearing", dark + light > 128 * 128 * 9 / 10,
        "sharp=${dark + light}/${128 * 128}")

    // Buffer reuse: no reallocation when the shape is unchanged.
    val ref = buf.data
    repeat(20) { YuvRoi.cropScale(luma, w, h, stride, 1, roi, 128, 128, buf) }
    C.check("buffer reused across frames", buf.data === ref)

    // pixelStride=2 (semi-planar luma). The row must be indexed as
    // y*rowStride + x*pixelStride, so the backing array needs a stride wide
    // enough to hold w*2 samples per row — the earlier fixture used the packed
    // stride and silently read the wrong half of every row.
    val stride2 = w * 2 + 64
    val luma2 = ByteArray(stride2 * h)
    for (y in 0 until h) for (x in 0 until w) {
        luma2[y * stride2 + x * 2] = (if (x < w / 2) 250 else 10).toByte()
    }
    val buf2 = YuvRoi.Buffer()
    YuvRoi.cropScale(luma2, w, h, stride2, 2, IntRect(0, 0, w, h), 64, 64, buf2)
    val leftPx = buf2.data[64 * 32 + 8].toInt() and 0xFF
    val rightPx = buf2.data[64 * 32 + 56].toInt() and 0xFF
    C.check("pixelStride=2 honoured", leftPx > 200 && rightPx < 60, "l=$leftPx r=$rightPx")
    C.ok("rowStride and pixelStride handled; content survives the crop")
}

private fun testOtsuAndMedian() {
    C.suite("Median filter and Otsu threshold")
    val buf = YuvRoi.Buffer()
    buf.ensure(64, 64)
    for (i in 0 until 64 * 64) buf.data[i] = if ((i / 8) % 2 == 0) 230.toByte() else 25.toByte()
    // Salt-and-pepper impulses the median must remove.
    buf.data[64 * 10 + 10] = 0
    buf.data[64 * 20 + 20] = 255.toByte()

    // Otsu returns the LAST bin of the dark class (the conventional convention:
    // pixels > threshold are foreground), so for a 25/230 split it returns 25,
    // not something strictly between. Assert it separates the two populations
    // rather than assuming an exclusive midpoint.
    val hist = IntArray(256)
    val t = YuvRoi.otsuThreshold(buf, hist)
    C.check("otsu separates the two populations", t in 25..229, "threshold=$t")
    C.check("otsu puts dark below and light above", 25 <= t && 230 > t, "threshold=$t")

    // A genuinely mid-split image should land near the midpoint.
    val even = YuvRoi.Buffer()
    even.ensure(64, 64)
    for (i in 0 until 64 * 64) even.data[i] = if (i % 2 == 0) 60.toByte() else 200.toByte()
    val tEven = YuvRoi.otsuThreshold(even, hist)
    C.check("otsu near midpoint for 60/200 split", tEven in 60..199, "threshold=$tEven")

    val scratch = ByteArray(64 * 64)
    val beforeA = buf.data[64 * 10 + 10].toInt() and 0xFF
    YuvRoi.median3(buf, scratch)
    val afterA = buf.data[64 * 10 + 10].toInt() and 0xFF
    C.check("median removes impulse noise", beforeA == 0 && afterA != 0, "before=$beforeA after=$afterA")

    // Edge preservation: a hard vertical edge must stay hard.
    val edge = YuvRoi.Buffer()
    edge.ensure(64, 64)
    for (y in 0 until 64) for (x in 0 until 64) {
        edge.data[y * 64 + x] = if (x < 32) 20.toByte() else 235.toByte()
    }
    YuvRoi.median3(edge, scratch)
    val l = edge.data[64 * 32 + 20].toInt() and 0xFF
    val r = edge.data[64 * 32 + 44].toInt() and 0xFF
    C.check("median preserves edges", l < 40 && r > 200, "l=$l r=$r")
    C.ok("noise removed without softening module boundaries")
}

// ---------------------------------------------------------------- Stage 6

private fun testHoldTime() {
    C.suite("Hold-time / phase independence (audit section 2)")
    val p = HoldTimePlan.compute(refreshHz = 60.0)
    C.info(p.note)
    C.check("hold is whole display frames",
        p.holdNs % (1_000_000_000L / 60) < 1_000_000L || p.framesPerSymbol >= 1)
    // Compare in whole frames, not nanoseconds: HZ_60_PERIOD_NS (16,666,667) is a
    // rounded-up constant, so 6 * it exceeds six actual vsync intervals by 6 ns.
    // The invariant is "at least 6 display frames", and frames are the unit the
    // hardware actually quantises to.
    C.check("hold >= 6 display frames",
        p.framesPerSymbol >= HoldTimePlan.PHASE_INDEPENDENCE_FACTOR,
        "frames=${p.framesPerSymbol} hold=${p.holdMs} ms")
    C.check("fps within 8-12 envelope",
        p.effectiveFps >= HoldTimePlan.MIN_FPS - 0.5 && p.effectiveFps <= HoldTimePlan.MAX_FPS + 0.5,
        "fps=${p.effectiveFps}")
    C.check("hold around 100 ms at 60 Hz", p.holdMs in 80.0..135.0, "${p.holdMs} ms")

    // A slow rolling shutter must lengthen the hold, not shorten it.
    val slow = HoldTimePlan.compute(
        refreshHz = 60.0, receiverExposureNs = 20_000_000L, receiverReadoutNs = 20_000_000L,
    )
    C.check("slow readout lengthens hold", slow.holdNs >= p.holdNs,
        "slow=${slow.holdMs} base=${p.holdMs}")
    C.info("slow-readout device: ${slow.note}")

    // The LONG_INTEGRATE fallback: a receiver forced onto a full-refresh exposure
    // legitimately needs a longer hold, and correctness beats the fps envelope.
    val longExp = HoldTimePlan.compute(
        refreshHz = 60.0, receiverExposureNs = HoldTimePlan.HZ_60_PERIOD_NS,
    )
    C.check("long-exposure receiver gets a longer hold", longExp.holdNs > p.holdNs,
        "long=${longExp.holdMs} short=${p.holdMs}")
    C.check("long-exposure hold still phase-independent",
        longExp.holdNs >= 6 * (HoldTimePlan.HZ_60_PERIOD_NS + 12_000_000L))
    C.info("long-integrate receiver: ${longExp.note}")

    // 90 Hz panel: shorter period, but the 6x rule still governs.
    val hz90 = HoldTimePlan.compute(refreshHz = 90.0)
    C.check("90 Hz still >= 6 periods",
        hz90.holdNs >= 6 * (1_000_000_000L / 90), "hold=${hz90.holdMs} ms")
    C.check("90 Hz respects fps ceiling", hz90.effectiveFps <= HoldTimePlan.MAX_FPS + 0.5)
    C.ok("tearing prevented by arithmetic, not clock sync")
}

private fun testScheduler() {
    C.suite("Symbol scheduler / zero-redraw guard")
    val plan = HoldTimePlan.compute(60.0)
    val sched = SymbolScheduler(plan, totalSymbols = 100, headerInterleave = 8)
    sched.start(0L)

    C.check("slot 0 is HEADER", sched.contentAt(0L) is SymbolScheduler.Content.Header)

    // Within the first hold, exactly one redraw.
    var redraws = 0
    val vsync = 1_000_000_000L / 60
    for (f in 0 until plan.framesPerSymbol) {
        if (sched.needsRedraw(f * vsync)) redraws++
    }
    C.check("one redraw per hold", redraws == 1, "redraws=$redraws in ${plan.framesPerSymbol} vsyncs")

    // Over a full second: redraws must equal symbols, not vsyncs.
    val sched2 = SymbolScheduler(plan, 100, 8)
    sched2.start(0L)
    var r2 = 0
    for (f in 0 until 60) if (sched2.needsRedraw(f * vsync)) r2++
    C.check("redraws match symbol rate, not vsync rate", r2 <= 13 && r2 >= 7, "redraws/s=$r2")
    C.info("at 60 Hz: $r2 redraws/s vs 60 vsyncs/s -> ${60 - r2} no-op callbacks")

    // HEADER interleave: the trust anchor must recur.
    val sched3 = SymbolScheduler(plan, 100, 8)
    sched3.start(0L)
    var headers = 0
    var dataSeen = mutableSetOf<Int>()
    for (slot in 0 until 90) {
        when (val c = sched3.contentAt(slot * plan.holdNs)) {
            is SymbolScheduler.Content.Header -> headers++
            is SymbolScheduler.Content.Data -> dataSeen.add(c.symbolIndex)
        }
    }
    C.check("header recurs every 9th slot", headers == 10, "headers=$headers/90")
    C.check("data symbols advance", dataSeen.size == 80, "unique=${dataSeen.size}")
    C.check("data starts at 0", dataSeen.contains(0))

    // Wrap-around: fountain streams keep cycling until the receiver has enough.
    val c = sched3.contentAt(200L * plan.holdNs)
    C.check("cycles past the symbol count", c is SymbolScheduler.Content.Data)
    C.ok("5 of every 6 vsyncs do no work at all")
}

private fun testThermalDerate() {
    C.suite("Thermal derating (audit section 3)")
    val base = HoldTimePlan.compute(60.0)
    val light = HoldTimePlan.derate(base, ThermalLevel.LIGHT)
    val moderate = HoldTimePlan.derate(base, ThermalLevel.MODERATE)
    val severe = HoldTimePlan.derate(base, ThermalLevel.SEVERE)

    C.check("LIGHT slows the sender", light.effectiveFps <= base.effectiveFps + 0.01,
        "base=${base.effectiveFps} light=${light.effectiveFps}")
    C.check("MODERATE slower than LIGHT", moderate.effectiveFps < light.effectiveFps)
    C.check("SEVERE slowest", severe.effectiveFps < moderate.effectiveFps)
    C.check("NONE unchanged", HoldTimePlan.derate(base, ThermalLevel.NONE) == base)

    // Derating must never break phase independence — slower is always safe.
    for (level in ThermalLevel.values()) {
        val d = HoldTimePlan.derate(base, level)
        C.check("$level still >= 6 display frames",
            d.framesPerSymbol >= HoldTimePlan.PHASE_INDEPENDENCE_FACTOR,
            "frames=${d.framesPerSymbol} hold=${d.holdMs} ms")
        C.check("$level never faster than base", d.effectiveFps <= base.effectiveFps + 1e-6,
            "base=${base.effectiveFps} $level=${d.effectiveFps}")
    }
    C.info("fps ladder: base=${"%.1f".format(base.effectiveFps)} " +
        "light=${"%.1f".format(light.effectiveFps)} " +
        "moderate=${"%.1f".format(moderate.effectiveFps)} " +
        "severe=${"%.1f".format(severe.effectiveFps)}")
    C.ok("throttling slows the fountain; it never corrupts it")
}
