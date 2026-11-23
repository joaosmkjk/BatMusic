package com.batmusic.app.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow

/**
 * Three-band feed-forward dynamics stage with complementary one-pole splits.
 * Bands are low (<250 Hz), mid (250 Hz..4 kHz), and high (>4 kHz).
 * Detectors are peak-linked across channels. This stage is not a limiter.
 *
 * State is allocated once; process() does not allocate on the audio thread.
 */
internal class MultibandDynamics(
    private val sampleRate: Int,
    private val channels: Int,
    lowCrossoverHz: Float = 250f,
    highCrossoverHz: Float = 4_000f
) {
    init {
        require(sampleRate >= 8_000)
        require(channels in 1..8)
        require(lowCrossoverHz > 20f && highCrossoverHz > lowCrossoverHz)
        require(highCrossoverHz < sampleRate * 0.45f)
    }

    @Volatile var thresholdDb: Float = -18f
    @Volatile var ratio: Float = 2f
    @Volatile var attackMs: Float = 12f
    @Volatile var releaseMs: Float = 160f
    @Volatile var makeupDb: Float = 0f

    private val lowCoefficient = (1.0 - exp(-2.0 * Math.PI * safeLowCrossoverHz / sampleRate)).toFloat()
    private val highCoefficient = (1.0 - exp(-2.0 * Math.PI * safeHighCrossoverHz / sampleRate)).toFloat()
    private val lowState = FloatArray(channels)
    private val midState = FloatArray(channels)
    private val envelopes = FloatArray(3)
    private val attack = FloatArray(3)
    private val release = FloatArray(3)
    private val lowBand = FloatArray(channels)
    private val midBand = FloatArray(channels)
    private val highBand = FloatArray(channels)

    init { updateTimeConstants() }

    fun updateTimeConstants() {
        val safeAttackMs = attackMs.takeIf { it.isFinite() }?.coerceIn(1f, 100f) ?: 12f
        val safeReleaseMs = releaseMs.takeIf { it.isFinite() }?.coerceIn(10f, 1_000f) ?: 160f
        val attackValue = exp(-1.0 / (sampleRate * safeAttackMs / 1000.0)).toFloat()
        val releaseValue = exp(-1.0 / (sampleRate * safeReleaseMs / 1000.0)).toFloat()
        for (band in 0..2) {
            attack[band] = attackValue
            release[band] = releaseValue
        }
    }

    fun reset() {
        lowState.fill(0f)
        midState.fill(0f)
        envelopes.fill(0f)
    }

    fun process(interleaved: FloatArray, frameCount: Int) {
        require(frameCount >= 0 && frameCount * channels <= interleaved.size)
        val threshold = thresholdDb.takeIf { it.isFinite() }?.coerceIn(-48f, 0f) ?: -18f
        val compressionRatio = ratio.takeIf { it.isFinite() }?.coerceIn(1f, 12f) ?: 2f
        val safeMakeupDb = makeupDb.takeIf { it.isFinite() }?.coerceIn(-12f, 6f) ?: 0f
        val makeup = 10f.pow(safeMakeupDb / 20f)

        for (frame in 0 until frameCount) {
            val offset = frame * channels
            var peakLow = 0f
            var peakMid = 0f
            var peakHigh = 0f

            for (channel in 0 until channels) {
                val input = interleaved[offset + channel].let { if (it.isFinite()) it else 0f }
                val low = lowState[channel] + lowCoefficient * (input - lowState[channel])
                lowState[channel] = low
                val remainder = input - low
                val mid = midState[channel] + highCoefficient * (remainder - midState[channel])
                midState[channel] = mid
                val high = remainder - mid
                lowBand[channel] = low
                midBand[channel] = mid
                highBand[channel] = high
                peakLow = max(peakLow, abs(low))
                peakMid = max(peakMid, abs(mid))
                peakHigh = max(peakHigh, abs(high))
            }

            val gainLow = gainForBand(0, peakLow, threshold, compressionRatio, makeup)
            val gainMid = gainForBand(1, peakMid, threshold, compressionRatio, makeup)
            val gainHigh = gainForBand(2, peakHigh, threshold, compressionRatio, makeup)
            for (channel in 0 until channels) {
                val output = lowBand[channel] * gainLow +
                    midBand[channel] * gainMid + highBand[channel] * gainHigh
                interleaved[offset + channel] = if (output.isFinite()) output else 0f
            }
        }
    }

    private fun gainForBand(
        band: Int,
        peak: Float,
        thresholdDb: Float,
        ratio: Float,
        makeup: Float
    ): Float {
        val coefficient = if (peak > envelopes[band]) attack[band] else release[band]
        envelopes[band] = coefficient * envelopes[band] + (1f - coefficient) * peak
        val levelDb = 20f * kotlin.math.log10(envelopes[band].coerceAtLeast(1e-6f))
        val overDb = levelDb - thresholdDb
        val reductionDb = if (overDb > 0f) overDb * (1f - 1f / ratio) else 0f
        return 10f.pow(-reductionDb / 20f) * makeup
    }
}
