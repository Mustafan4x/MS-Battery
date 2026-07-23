package com.mustafanazeer.baselinems.dsp.voice

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Quality bands and engagement gate for the Voice Reading Test.
 *
 * Three heuristics per ADR 0006 Section 5:
 *
 * 1. Sustained voice activity (heuristic 3 in the ADR). At least 15 of the 30
 *    second window must contain voice activity above the speech threshold. The
 *    floor was reduced from 20 seconds to 15 seconds per accessibility
 *    requirement PA4, to keep MS users with moderate bulbar dysarthria from
 *    being excluded outright; the per feature flags below carry the remaining
 *    discrimination load.
 *
 * 2. No clipping (heuristic 2 in the ADR). More than 1 percent of samples at
 *    or near the int16 extremes is treated as a clipped recording; clipping
 *    destroys the per cycle amplitude information that shimmer and HNR
 *    depend on so the headline quality score drops to 0.0.
 *
 * 3. Adequate signal to noise ratio (heuristic 1 in the ADR). Implemented in
 *    `VoiceQualityScore.snrAdequate` as voicedSeconds at or above the 15
 *    second floor; the calibration window driven RMS comparison is part of
 *    the Phase 8 implementation work in the `signals/` layer and surfaces to
 *    the quality scorer through `VoiceFeatureSet.voicedSeconds`.
 *
 * Per feature flags. Each of the six features (`jitter`, `shimmer`, `hnr`,
 * `f0_mean`, `f0_sd`, `speaking_rate`, `pause_fraction`) gets one
 * `FeatureQualityFlag`. The flag captures the specific reason a feature is
 * unreliable for the recording:
 *
 *   - `INSUFFICIENT_PERIODS` when fewer than 50 voiced cycles were stitched
 *     (jitter, shimmer, F0 SD).
 *   - `INSUFFICIENT_VOICING` when voicedSeconds is below the 15 second floor
 *     (HNR, speaking rate).
 *   - `CLIPPED` when more than 1 percent of samples were at the int16 rail.
 *   - `NULL` when the feature scalar itself is null on `VoiceFeatureSet`.
 *   - `OK` otherwise.
 *
 * Pure Kotlin. No Android imports. No mutable global state.
 */
enum class FeatureQualityFlag {
    OK,
    INSUFFICIENT_PERIODS,
    INSUFFICIENT_VOICING,
    CLIPPED,
    NULL,
}

data class VoiceQualityScore(
    val qualityScore: Double,
    val perFeatureFlags: Map<String, FeatureQualityFlag>,
    val engagementOk: Boolean,
    val clippingDetected: Boolean,
    val snrAdequate: Boolean,
)

object VoiceQuality {

    const val VOICED_SECONDS_FLOOR: Double = 15.0
    const val CLIPPING_THRESHOLD_FRACTION: Double = 0.01
    const val CLIPPING_ABS_LEVEL: Int = 32_700
    const val MIN_VOICED_PERIODS_FOR_PERTURBATION: Int = 50
    private const val PER_NULL_PENALTY: Double = 0.1

    // Frame width tolerance on the engagement floor. The autocorrelation analyzer at 40 ms
    // windows with 10 ms hops loses up to one window of voiced classification at each end of a
    // voiced segment (the 40 ms window must lie fully inside the voiced region to register).
    // A 0.1 second tolerance corresponds to one hop and keeps the inclusivity margin for
    // recordings that hit the analytic 15 s floor but realize 14.9 s after framing.
    private const val ENGAGEMENT_FRAME_TOLERANCE_SEC: Double = 0.1

    const val FEATURE_KEY_JITTER: String = "jitter_local"
    const val FEATURE_KEY_SHIMMER: String = "shimmer_local"
    const val FEATURE_KEY_HNR: String = "hnr_db"
    const val FEATURE_KEY_F0_MEAN: String = "f0_mean_hz"
    const val FEATURE_KEY_F0_SD: String = "f0_sd_hz"
    const val FEATURE_KEY_SPEAKING_RATE: String = "speaking_rate_wpm"
    const val FEATURE_KEY_PAUSE_FRACTION: String = "pause_fraction"

    fun score(
        features: VoiceFeatureSet,
        samples: ShortArray,
        sampleRateHz: Int,
    ): VoiceQualityScore {
        require(sampleRateHz > 0) { "sampleRateHz must be positive" }

        val clippingDetected = detectClipping(samples)
        val engagementOk =
            features.voicedSeconds >= VOICED_SECONDS_FLOOR - ENGAGEMENT_FRAME_TOLERANCE_SEC
        val snrAdequate = engagementOk

        val flags = mutableMapOf<String, FeatureQualityFlag>()
        flags[FEATURE_KEY_JITTER] = flagForPerturbationFeature(
            value = features.jitterLocal,
            periodCount = features.periodCount,
            engagementOk = engagementOk,
            clippingDetected = clippingDetected,
        )
        flags[FEATURE_KEY_SHIMMER] = flagForPerturbationFeature(
            value = features.shimmerLocal,
            periodCount = features.periodCount,
            engagementOk = engagementOk,
            clippingDetected = clippingDetected,
        )
        flags[FEATURE_KEY_HNR] = flagForVoicingDependentFeature(
            value = features.hnrDb,
            engagementOk = engagementOk,
            clippingDetected = clippingDetected,
        )
        flags[FEATURE_KEY_F0_MEAN] = flagForVoicingDependentFeature(
            value = features.f0MeanHz,
            engagementOk = engagementOk,
            clippingDetected = clippingDetected,
        )
        flags[FEATURE_KEY_F0_SD] = flagForPerturbationFeature(
            value = features.f0SdHz,
            periodCount = features.periodCount,
            engagementOk = engagementOk,
            clippingDetected = clippingDetected,
        )
        flags[FEATURE_KEY_SPEAKING_RATE] = flagForVoicingDependentFeature(
            value = features.speakingRateWpm,
            engagementOk = engagementOk,
            clippingDetected = clippingDetected,
        )
        flags[FEATURE_KEY_PAUSE_FRACTION] = if (clippingDetected) {
            FeatureQualityFlag.CLIPPED
        } else {
            FeatureQualityFlag.OK
        }

        val rawScore = when {
            clippingDetected -> 0.0
            !engagementOk -> 0.0
            else -> {
                val nullCount = flags.values.count { it == FeatureQualityFlag.NULL }
                val penalty = nullCount * PER_NULL_PENALTY
                max(0.0, min(1.0, 1.0 - penalty))
            }
        }

        return VoiceQualityScore(
            qualityScore = rawScore,
            perFeatureFlags = flags.toMap(),
            engagementOk = engagementOk,
            clippingDetected = clippingDetected,
            snrAdequate = snrAdequate,
        )
    }

    private fun detectClipping(samples: ShortArray): Boolean {
        if (samples.isEmpty()) return false
        var clippedCount = 0L
        for (s in samples) {
            if (abs(s.toInt()) >= CLIPPING_ABS_LEVEL) clippedCount++
        }
        val fraction = clippedCount.toDouble() / samples.size
        return fraction > CLIPPING_THRESHOLD_FRACTION
    }

    private fun flagForPerturbationFeature(
        value: Double?,
        periodCount: Int,
        engagementOk: Boolean,
        clippingDetected: Boolean,
    ): FeatureQualityFlag {
        if (clippingDetected) return FeatureQualityFlag.CLIPPED
        if (!engagementOk) return FeatureQualityFlag.INSUFFICIENT_VOICING
        if (periodCount < MIN_VOICED_PERIODS_FOR_PERTURBATION) {
            return FeatureQualityFlag.INSUFFICIENT_PERIODS
        }
        if (value == null) return FeatureQualityFlag.NULL
        return FeatureQualityFlag.OK
    }

    private fun flagForVoicingDependentFeature(
        value: Double?,
        engagementOk: Boolean,
        clippingDetected: Boolean,
    ): FeatureQualityFlag {
        if (clippingDetected) return FeatureQualityFlag.CLIPPED
        if (!engagementOk) return FeatureQualityFlag.INSUFFICIENT_VOICING
        if (value == null) return FeatureQualityFlag.NULL
        return FeatureQualityFlag.OK
    }
}
