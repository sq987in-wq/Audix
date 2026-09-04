package app.candela.camera

import kotlin.math.roundToLong

/**
 * Exposure/ISO selection for the C1 freeze (audit section 1.2 C1, section 2.2).
 *
 * Pure Kotlin: no Camera2 imports. The Android layer reads CameraCharacteristics,
 * hands the numbers here, and applies whatever this returns. That keeps the one
 * genuinely subtle decision in the receiver testable without a device.
 *
 * THE FORBIDDEN BAND. The audit gives two constraints that look contradictory
 * until you read section 2.2 carefully:
 *
 *   "Target exposure: 1/125-1/250 s with ISO clamped to 200-800."   (section 1.2)
 *   "Do not sit exposure in 5-15 ms ... Either >= one screen period
 *    (~17 ms @ 60 Hz) paired with motion gates, or <= ~1/250 s and let
 *    HybridBinarizer absorb banding."                               (section 5.1)
 *
 * 1/125 s is 8 ms, which is inside the band section 5.1 forbids. The resolution
 * is that 5-15 ms is the worst of both worlds: long enough for hand tremor to
 * smear a module, short enough that the rolling shutter only catches part of a
 * refresh cycle, so you get blur AND banding. The two escapes are:
 *
 *   SHORT (<= ~4 ms): freezes motion outright. A camera row integrates a sliver
 *     of the refresh, so banding is maximal — but banding is a low-frequency
 *     illumination gradient, which HybridBinarizer's per-block thresholding is
 *     specifically designed to absorb. Blur, by contrast, destroys information
 *     no binarizer can recover. Prefer this whenever light allows.
 *
 *   LONG (>= one full screen period): integrates at least one complete refresh,
 *     so illumination is uniform and banding vanishes. Costs motion tolerance,
 *     which is why it is only safe behind the accelerometer motion gate (C2).
 *
 * So: prefer SHORT, fall back to LONG when the scene is too dim to hold ISO
 * within 200-800, and never emit a value inside the band.
 */
object ExposurePlan {

    const val ISO_MIN = 200
    const val ISO_MAX = 800

    /** 1/250 s. Upper bound of the SHORT strategy. */
    const val SHORT_MAX_NS = 4_000_000L

    /** Audit's forbidden band, in nanoseconds. */
    const val BAND_LO_NS = 5_000_000L
    const val BAND_HI_NS = 15_000_000L

    enum class Strategy {
        /** <= 4 ms. Motion-proof; banding absorbed by HybridBinarizer. */
        SHORT_FREEZE,

        /** >= one screen period. Banding-proof; requires the motion gate. */
        LONG_INTEGRATE,
    }

    data class Plan(
        val exposureTimeNs: Long,
        val iso: Int,
        val strategy: Strategy,
        /** True when the sensor could not hit the request and we clamped. */
        val clamped: Boolean,
        val note: String,
    ) {
        val exposureMs: Double get() = exposureTimeNs / 1_000_000.0
        val inForbiddenBand: Boolean
            get() = exposureTimeNs in BAND_LO_NS..BAND_HI_NS
    }

    /** What the sensor actually supports, read from CameraCharacteristics. */
    data class SensorLimits(
        val minExposureNs: Long,
        val maxExposureNs: Long,
        val minIso: Int,
        val maxIso: Int,
        /** ANDROID_INFO_SUPPORTED_HARDWARE_LEVEL allows manual sensor control. */
        val manualSensorSupported: Boolean,
    )

    /**
     * @param meteredExposureNs exposure AE converged on at the calibration pose
     * @param meteredIso ISO AE converged on at the calibration pose
     * @param screenPeriodNs one refresh period of the SENDER panel (16.67 ms @ 60 Hz)
     *
     * Brightness is held constant by preserving the exposure x gain product, so a
     * shorter exposure is paid for with proportionally more gain.
     */
    fun choose(
        meteredExposureNs: Long,
        meteredIso: Int,
        screenPeriodNs: Long,
        limits: SensorLimits,
    ): Plan {
        if (!limits.manualSensorSupported) {
            return Plan(
                meteredExposureNs, meteredIso, Strategy.SHORT_FREEZE, clamped = true,
                note = "LEGACY/LIMITED device: no manual sensor control. Falling back to " +
                    "AE_LOCK at the metered value; expect banding or blur outside the envelope.",
            )
        }

        // Exposure-gain product. Preserving this preserves image brightness.
        val ev = meteredExposureNs.toDouble() * meteredIso.toDouble()

        // --- Attempt SHORT: freeze motion, accept banding.
        val tShort = SHORT_MAX_NS.coerceIn(limits.minExposureNs, limits.maxExposureNs)
        val isoShort = (ev / tShort).roundToLong()
        if (isoShort <= ISO_MAX && tShort <= SHORT_MAX_NS) {
            val iso = isoShort.coerceIn(ISO_MIN.toLong(), ISO_MAX.toLong()).toInt()
            return Plan(
                tShort, iso, Strategy.SHORT_FREEZE,
                clamped = isoShort < ISO_MIN,
                note = "Short exposure ${fmtMs(tShort)} ms at ISO $iso. Motion frozen; " +
                    "rolling-shutter banding left to HybridBinarizer.",
            )
        }

        // --- Too dim to freeze. Integrate a whole refresh instead.
        val tLong = screenPeriodNs.coerceIn(
            maxOf(limits.minExposureNs, BAND_HI_NS + 1),
            limits.maxExposureNs,
        )
        val isoLong = (ev / tLong).roundToLong().coerceIn(ISO_MIN.toLong(), ISO_MAX.toLong()).toInt()
        return Plan(
            tLong, isoLong, Strategy.LONG_INTEGRATE,
            clamped = tLong != screenPeriodNs,
            note = "Scene too dim to freeze at ISO <= $ISO_MAX. Integrating one full " +
                "refresh (${fmtMs(tLong)} ms) at ISO $isoLong; banding eliminated, " +
                "motion gate now mandatory.",
        )
    }

    private fun fmtMs(ns: Long): String = "%.1f".format(ns / 1_000_000.0)
}
