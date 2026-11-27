package com.batmusic.app.audio

import kotlin.math.max

/**
 * Exact dB budget for the known linear gain stages ahead of the safety limiter.
 * EQ/bass response is measured from the processor's real filter coefficients.
 * The 7 dB allowance is retained from the previous tuning; excess is fully
 * compensated rather than partially/boost-dependently compensated.
 */
internal data class BatGainStagingPlan(
    val estimatedPositiveStackDb: Float,
    val headroomDb: Float,
    val inputGainDb: Float
)

internal object BatGainStaging {
    fun plan(settings: BatAudioSettings, peakFilterGainDb: Float = 0f): BatGainStagingPlan {
        if (!settings.enabled) return BatGainStagingPlan(0f, 0f, 0f)

        val preGainDb = settings.preGainDb.takeIf { it.isFinite() }?.coerceIn(0f, 24f) ?: 0f
        val filterGainDb = peakFilterGainDb.takeIf { it.isFinite() }?.coerceIn(0f, 60f) ?: 0f
        val boostMakeupDb = (settings.batBoost.coerceIn(100, 200) - 100) / 100f * 6.0206f
        val compressorMakeupDb = if (settings.compressorEnabled) {
            settings.compressorMakeupDb.takeIf { it.isFinite() }?.coerceIn(-12f, 12f)?.coerceAtLeast(0f) ?: 0f
        } else 0f

        val stack = (preGainDb + filterGainDb + boostMakeupDb + compressorMakeupDb)
            .coerceIn(0f, 96f)
        val headroom = max(0f, stack - HEADROOM_ALLOWANCE_DB)
        return BatGainStagingPlan(
            estimatedPositiveStackDb = stack,
            headroomDb = headroom,
            inputGainDb = preGainDb - headroom
        )
    }

    private const val HEADROOM_ALLOWANCE_DB = 7f
}
