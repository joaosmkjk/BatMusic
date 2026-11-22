package com.batmusic.app.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow

/**
 * Three-band feed-forward dynamics stage.
 *
 * The crossover is a complementary one-pole split: low < 250 Hz, mid
 * 250 Hz..4 kHz, and high > 4 kHz. The three bands sum to the input before
 * gain reduction, avoiding crossover holes. Detectors are peak-linked across
 * channels per band. This is an optional effect stage, not a limiter.
 *
 * The class allocates its state once. process() performs no per-block or
 * per-frame allocations and must be called from the PCM audio thread.
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

    private val lowCoefficient = (1.0 - exp(-2.0 * Math.PI * lowCrossoverHz / sampleRate)).toFloat()
    private val highCoefficient = (1.0 - exp(-2.0 * Math.PI * highCrossoverHz / sampleRate)).toFloat()
    private val lowState = FloatArray(channels)
    private val midState = FloatArray(channels)
    private val envelopes = FloatArray(3)
    private val attack = FloatArray(3)
    private val release = FloatArray(3)

    init { updateTimeConstants() }

    fun updateTimeConstants() {
        val attackValue = exp(-1.0 / (sampleRate * attackMs.coerceIn(1f, 100f) / 1000.0)).toFloat()
        val releaseValue = exp(-1.0 / (sampleRate * releaseMs.coerceIn(10f, 1_000f) / 1000.0)).toFloat()
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
        val threshold = thresholdDb.coerceIn(-48f, 0f)
        val compressionRatio = ratio.coerceIn(1f, 12f)
        val makeup = 10f.pow(makeupDb.coerceIn(-12f, 6f) / 20f)
        val thresholdLinear = 10f.pow(threshold / 20f)

        for (frame in 0 until frameCount) {
            val offset = frame * channels
            var peaksLow = 0f
            var peaksMid = 0f
            var peaksHigh = 0f

            for (channel in 0 until channels) {
                val input = interleaved[offset + channel].let { if (it.isFinite()) it else 0f }
                val low = lowState[channel] + lowCoefficient * (input - lowState[channel])
                lowState[channel] = low
                val remainder = input - low
                val mid = midState[channel] + highCoefficient * (remainder - midState[channel])
                midState[channel] = mid
                val high = remainder - mid
                peaksLow = max(peaksLow, abs(low))
                peaksMid = max(peaksMid, abs(mid))
                peaksHigh = max(peaksHigh, abs(high))
            }

            val peaks = floatArrayOf(peaksLow, peaksMid, peaksHigh)
            val gains = FloatArray(3)
            for (band in 0..2) {
                val coefficient = if (peaks[band] > envelopes[band]) attack[band] else release[band]
                envelopes[band] = coefficient * envelopes[band] + (1f - coefficient) * peaks[band]
                val levelDb = 20f * kotlin.math.log10(envelopes[band].coerceAtLeast(1e-6f))
                val overDb = levelDb - threshold
                val reductionDb = if (overDb > 0f) overDb * (1f - 1f / compressionRatio) else 0f
                gains[band] = 10f.pow(-reductionDb / 20f) * makeup
            }

            for (channel in 0 until channels) {
                val input = interleaved[offset + channel].let { if (it.isFinite()) it else 0f }
                val low = lowState[channel]
                val remainder = input - low
                val mid = midState[channel]
                val high = remainder - mid
                val output = low * gains[0] + mid * gains[1] + high * gains[2]
                interleaved[offset + channel] = if (output.isFinite()) output else 0f
            }
        }
    }
}
