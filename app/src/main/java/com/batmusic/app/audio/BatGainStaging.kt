package com.batmusic.app.audio

import kotlin.math.max

/**
 * Conservative estimate of positive gain accumulated before the output limiter.
 *
 * The estimate is used only to attenuate the processor input; it never raises gain.
 * The output true-peak limiter remains the final protection stage.
 */
internal data class BatGainStagingPlan(
    val estimatedPositiveStackDb: Float,
    val headroomDb: Float,
    val inputGainDb: Float
)

internal object BatGainStaging {
    fun plan(settings: BatAudioSettings): BatGainStagingPlan {
        if (!settings.enabled) return BatGainStagingPlan(0f, 0f, 0f)
        val preGainDb = settings.preGainDb.takeIf { it.isFinite() }?.coerceIn(0f, 24f) ?: 0f
        val maxEqDb = if (settings.equalizerEnabled) {
            settings.bandGainsDb.asSequence()
                .filter { it.isFinite() }
                .map { it.coerceIn(-12f, 12f) }
                .filter { it > 0f }
                .maxOrNull() ?: 0f
        } else 0f
        val bassDb = if (settings.enabled) settings.bassBoost.coerceIn(0, 100) * 0.12f else 0f
        val boostMakeupDb = if (settings.enabled) {
            (settings.batBoost.coerceIn(100, 200) - 100) / 100f * 6.0206f
        } else 0f
        val compressorMakeupDb = if (settings.enabled && settings.compressorEnabled) {
            settings.compressorMakeupDb.takeIf { it.isFinite() }?.coerceIn(-12f, 12f)?.coerceAtLeast(0f) ?: 0f
        } else 0f

        val stack = (preGainDb + maxEqDb + bassDb + boostMakeupDb + compressorMakeupDb)
            .coerceAtMost(60f)
        val boostAmount = (settings.batBoost.coerceIn(100, 200) - 100) / 100f
        val headroom = max(0f, stack - 7f) * (0.35f + boostAmount * 0.25f)
        return BatGainStagingPlan(
            estimatedPositiveStackDb = stack,
            headroomDb = headroom,
            inputGainDb = preGainDb - headroom
        )
    }
}
