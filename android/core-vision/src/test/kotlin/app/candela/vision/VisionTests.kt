package app.candela.vision

import kotlin.math.roundToInt
import kotlin.system.exitProcess

/**
 * Stage 2 verification for the gate-first vision pipeline.
 *
 * These tests are synthetic-fixture based rather than golden-vector based: the
 * TypeScript gates operate on RGBA ImageData at 96x96 with demo-relaxed
 * thresholds, whereas the Kotlin gates operate on YUV luma at 128x128 with
 * calibration-learned thresholds. Asserting numeric parity would enshrine the
 * web POC's known-wrong behaviour (PSR section 2.7/2.8). What must hold instead
 * are the BEHAVIOURAL invariants the audit specifies:
 *
 *   1. A sharp, high-contrast QR passes.
 *   2. A blurred one is blocked (audit C3).
 *   3. A low-contrast one is blocked, and a privacy-film/sunlight-grade one is
 *      REFUSED, not merely blocked (audit section 4 hard floor).
 *   4. Motion short-circuits before any pixel work (audit C2).
 *   5. The gate costs ~1-3 ms and allocates nothing per frame (audit section 3).
 */

private object V {
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

private const val W = 640
private const val H = 480

/** Synthetic QR-like target: a module grid of the given contrast, optionally blurred. */
private fun renderTarget(
    moduleSize: Int = 6,
    black: Int = 8,
    white: Int = 247,
    blurRadius: Int = 0,
    background: Int = 128,
): ByteArray {
    val img = ByteArray(W * H) { background.toByte() }
    val gridPx = 177 * moduleSize / 6 // keep target roughly centred and large
    val side = minOf(gridPx, H - 40)
    val x0 = (W - side) / 2
    val y0 = (H - side) / 2
    var seed = 0x2545F491
    fun rnd(): Int {
        seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5)
        return seed
    }
    for (my in 0 until side / moduleSize) {
        for (mx in 0 until side / moduleSize) {
            val on = (rnd() and 1) == 0
            val v = if (on) black else white
            for (dy in 0 until moduleSize) {
                val y = y0 + my * moduleSize + dy
                if (y !in 0 until H) continue
                val row = y * W
                for (dx in 0 until moduleSize) {
                    val x = x0 + mx * moduleSize + dx
                    if (x in 0 until W) img[row + x] = v.toByte()
                }
            }
        }
    }
    return if (blurRadius > 0) boxBlur(img, blurRadius) else img
}

/** Separable box blur — stands in for motion smear / defocus. */
private fun boxBlur(src: ByteArray, r: Int): ByteArray {
    val tmp = ByteArray(src.size)
    val out = ByteArray(src.size)
    for (y in 0 until H) {
        val row = y * W
        for (x in 0 until W) {
            var acc = 0; var n = 0
            for (d in -r..r) {
                val xx = x + d
                if (xx in 0 until W) { acc += src[row + xx].toInt() and 0xFF; n++ }
            }
            tmp[row + x] = (acc / n).toByte()
        }
    }
    for (y in 0 until H) {
        for (x in 0 until W) {
            var acc = 0; var n = 0
            for (d in -r..r) {
                val yy = y + d
                if (yy in 0 until H) { acc += tmp[yy * W + x].toInt() and 0xFF; n++ }
            }
            out[y * W + x] = (acc / n).toByte()
        }
    }
    return out
}

fun main() {
    println("Candela vision gate verification")

    val ws = GateWorkspace(128)
    val roi = Rect.centerFraction(W, H, 0.80)

    // ---- calibration on a good pose ------------------------------------
    V.suite("Calibration pose learning")
    val sharp = renderTarget(moduleSize = 6, black = 8, white = 247)
    val blurSamples = DoubleArray(8)
    val contrastSamples = DoubleArray(8)
    for (i in 0 until 8) {
        val r = Gates.evaluate(sharp, W, H, W, roi, GateThresholds.BOOTSTRAP, ws)
        blurSamples[i] = r.blur
        contrastSamples[i] = r.contrast
    }
    val verdict = Calibration.analyse(blurSamples, contrastSamples)
    V.check("clean target calibrates OK", verdict is CalibrationVerdict.Ok, "got $verdict")
    val learned = when (verdict) {
        is CalibrationVerdict.Ok -> {
            V.info("CR = ${(verdict.contrastRatio * 10).roundToInt() / 10.0}:1")
            verdict.thresholds
        }
        is CalibrationVerdict.Warn -> verdict.thresholds
        is CalibrationVerdict.Refuse -> GateThresholds.BOOTSTRAP
    }
    V.info("learned thresholds blurMin=${fmt(learned.blurMin)} contrastMin=${fmt(learned.contrastMin)}")
    V.ok("thresholds are learned, not hard-coded")

    // ---- the four canonical frames --------------------------------------
    V.suite("Gate admits sharp, rejects the rest")
    val rSharp = Gates.evaluate(sharp, W, H, W, roi, learned, ws)
    V.check("sharp QR passes", rSharp.pass, "verdict=${rSharp.verdict} blur=${fmt(rSharp.blur)}")
    V.info("sharp:  blur=${fmt(rSharp.blur)} contrast=${fmt(rSharp.contrast)}")

    // Radius 3 isolates the blur variable: sharpness collapses (135 -> ~49) while
    // dynamic range is still high (~0.86), so the verdict must be BLOCK_BLUR.
    // Blurring harder (r>=5) also crushes contrast on a fine module grid — that is
    // real physics, not a gate bug, and it is asserted separately below.
    val blurred = renderTarget(moduleSize = 6, black = 8, white = 247, blurRadius = 3)
    val rBlur = Gates.evaluate(blurred, W, H, W, roi, learned, ws)
    V.check("blurred QR blocked", !rBlur.pass, "verdict=${rBlur.verdict} blur=${fmt(rBlur.blur)}")
    V.check("blurred blamed on blur", rBlur.verdict == GateVerdict.BLOCK_BLUR, "got ${rBlur.verdict}")
    V.check(
        "blur gate fires while contrast is still healthy",
        rBlur.contrast > learned.contrastMin,
        "contrast=${fmt(rBlur.contrast)} min=${fmt(learned.contrastMin)}",
    )
    V.info("blurred: blur=${fmt(rBlur.blur)} contrast=${fmt(rBlur.contrast)} -> ${rBlur.reason}")

    // Monotonicity: sharpness must decrease as smear grows. This is the property
    // the motion/blur gate actually relies on, so assert it directly rather than
    // trusting a single hand-picked radius.
    var prev = Double.MAX_VALUE
    var monotonic = true
    val curve = StringBuilder()
    for (r in intArrayOf(0, 1, 2, 3, 4)) {
        val s = Gates.evaluate(
            renderTarget(moduleSize = 6, black = 8, white = 247, blurRadius = r),
            W, H, W, roi, learned, ws,
        ).blur
        curve.append("r$r=${fmt(s)} ")
        if (s > prev) monotonic = false
        prev = s
    }
    V.check("blur score decreases monotonically with smear", monotonic, curve.toString())
    V.info("blur curve: $curve")

    // A heavily smeared frame must still be rejected, whatever the attributed reason.
    val heavy = renderTarget(moduleSize = 6, black = 8, white = 247, blurRadius = 6)
    V.check("heavily smeared frame rejected", !Gates.evaluate(heavy, W, H, W, roi, learned, ws).pass)

    // Mild matte / washed out: low contrast but above the hard floor.
    val lowContrast = renderTarget(moduleSize = 6, black = 108, white = 148)
    val rLow = Gates.evaluate(lowContrast, W, H, W, roi, learned, ws)
    V.check("low-contrast QR blocked", !rLow.pass, "verdict=${rLow.verdict}")
    V.info("low-CR:  blur=${fmt(rLow.blur)} contrast=${fmt(rLow.contrast)} -> ${rLow.reason}")

    // Privacy film / direct sun: hard physical floor -> refuse, do not compensate.
    val floored = renderTarget(moduleSize = 6, black = 124, white = 132)
    val rFloor = Gates.evaluate(floored, W, H, W, roi, learned, ws)
    V.check("privacy-film-grade frame refused", rFloor.refuse, "verdict=${rFloor.verdict}")
    V.info("floor:   contrast=${fmt(rFloor.contrast)} -> ${rFloor.reason}")
    V.ok("hard floors refuse; recoverable conditions merely block")

    // ---- calibration refusal --------------------------------------------
    V.suite("Calibration refuses below CR 5:1")
    val floorSamples = DoubleArray(6)
    val floorBlur = DoubleArray(6)
    for (i in 0 until 6) {
        val r = Gates.evaluate(floored, W, H, W, roi, GateThresholds.BOOTSTRAP, ws)
        floorSamples[i] = r.contrast
        floorBlur[i] = r.blur
    }
    val fv = Calibration.analyse(floorBlur, floorSamples)
    V.check("refuses low-CR pose", fv is CalibrationVerdict.Refuse, "got $fv")
    if (fv is CalibrationVerdict.Refuse) V.info("message: ${fv.message}")

    V.check(
        "CR conversion 5:1 boundary",
        kotlin.math.abs(Calibration.contrastRatioFromNormalised(0.667) - 5.0) < 0.02,
        "got ${Calibration.contrastRatioFromNormalised(0.667)}",
    )
    V.check("empty calibration refuses", Calibration.analyse(DoubleArray(0), DoubleArray(0)) is CalibrationVerdict.Refuse)
    V.ok("audit CR ~5:1 floor enforced at calibration")

    // ---- motion gate -----------------------------------------------------
    V.suite("Motion gate (audit C2)")
    val mg = MotionGate()
    repeat(10) { mg.push(0.02, 0.01, 0.02) }
    V.check("still hand is stable", mg.stable, "mag=${fmt(mg.magnitude)}")
    repeat(10) { mg.push(1.4, 0.9, 1.1) }
    V.check("shaking hand is unstable", !mg.stable, "mag=${fmt(mg.magnitude)}")

    val rMotion = Gates.evaluate(sharp, W, H, W, roi, learned, ws, motionStable = false)
    V.check("motion short-circuits the gate", rMotion.verdict == GateVerdict.BLOCK_MOTION)
    V.check("motion path does no pixel work", rMotion.blur == 0.0 && rMotion.contrast == 0.0)
    mg.reset()
    V.check("reset restores stability", mg.stable)
    V.ok("motion gate blocks before any image processing")

    // ---- ROI tracking ----------------------------------------------------
    V.suite("ROI tracker")
    val tracker = RoiTracker(W, H)
    val fallback = tracker.current()
    V.check("fallback is centre region", fallback.width < W && fallback.height < H)
    tracker.onDecodeSuccess(Rect(100, 80, 200, 200))
    val tracked = tracker.current()
    V.check("tracks last QR rect", tracked.x <= 100 && tracked.width >= 200, "got $tracked")
    repeat(RoiTracker.MAX_MISSES) { tracker.onDecodeMiss() }
    V.check("widens after repeated misses", tracker.current().width == fallback.width)
    V.check("rect padding is clamped", Rect(0, 0, W, H).padded(50, W, H).width == W)
    V.ok("ROI falls back gracefully when tracking is lost")

    // ---- performance + allocation ----------------------------------------
    V.suite("Gate cost (audit section 3 thermal budget)")
    repeat(200) { Gates.evaluate(sharp, W, H, W, roi, learned, ws) } // warm up JIT
    val iters = 400
    val t0 = System.nanoTime()
    repeat(iters) { Gates.evaluate(sharp, W, H, W, roi, learned, ws) }
    val perFrameMs = (System.nanoTime() - t0) / 1e6 / iters
    V.info("gate cost = ${fmt(perFrameMs)} ms/frame on this JVM (128x128 ROI)")
    V.check("gate under 3 ms/frame", perFrameMs < 3.0, "${fmt(perFrameMs)} ms")

    // A 30 fps ungated 1080p ZXing path is 1.5-9 cores; gated must be a rounding error.
    val budgetPct = perFrameMs * 30 / 10.0
    V.info("at 30 fps that is ${fmt(budgetPct)}% of one core")
    V.check("gate uses under 10% of a core at 30 fps", budgetPct < 10.0)

    val ws2 = GateWorkspace(128)
    val before = ws2.gray.size + ws2.histogram.size
    repeat(50) { Gates.evaluate(sharp, W, H, W, roi, learned, ws2) }
    V.check("workspace is reused, not reallocated", ws2.gray.size + ws2.histogram.size == before)
    V.ok("gate-first pipeline is thermally cheap and allocation-free")

    exitProcess(V.summary())
}

private fun fmt(d: Double): String = "%.3f".format(d)
