package com.batmusic.app.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class MultibandDynamicsTest {
    @Test
    fun nonFiniteControlsCannotPoisonAudioOutput() {
        val dynamics = MultibandDynamics(48_000, 2).apply {
            thresholdDb = Float.NaN
            ratio = Float.POSITIVE_INFINITY
            attackMs = Float.NaN
            releaseMs = Float.NEGATIVE_INFINITY
            makeupDb = Float.NaN
            updateTimeConstants()
        }
        val pcm = FloatArray(4_800 * 2) { index ->
            (0.25 * sin(2.0 * PI * 997.0 * (index / 2) / 48_000)).toFloat()
        }
        dynamics.process(pcm, 4_800)
        assertTrue(pcm.all { it.isFinite() })
    }

    @Test
    fun lowSampleRateUsesValidCrossoverFrequencies() {
        val dynamics = MultibandDynamics(8_000, 1)
        val pcm = FloatArray(8_000) { index ->
            (0.2 * sin(2.0 * PI * 1_000.0 * index / 8_000)).toFloat()
        }
        dynamics.process(pcm, pcm.size)
        assertTrue(pcm.all { it.isFinite() })
    }

    @Test
    fun extremeFiniteControlsRemainBoundedAndFinite() {
        val dynamics = MultibandDynamics(48_000, 2).apply {
            thresholdDb = -10_000f
            ratio = 10_000f
            attackMs = 0.0001f
            releaseMs = 10_000f
            makeupDb = 100f
            updateTimeConstants()
        }
        val pcm = FloatArray(4_800 * 2) { index ->
            (0.4 * sin(2.0 * PI * 500.0 * (index / 2) / 48_000)).toFloat()
        }
        dynamics.process(pcm, 4_800)
        assertTrue(pcm.all { it.isFinite() })
    }

    @Test
    fun stereoChannelsShareLinkedGainReduction() {
        val sampleRate = 48_000
        val frames = sampleRate
        val input = FloatArray(frames * 2)
        for (frame in 0 until frames) {
            val sample = sin(2.0 * PI * 100.0 * frame / sampleRate).toFloat()
            input[frame * 2] = sample * 0.6f
            input[frame * 2 + 1] = sample * 0.15f
        }
        val original = input.copyOf()
        val dynamics = MultibandDynamics(sampleRate, 2).apply {
            thresholdDb = -30f
            ratio = 4f
            attackMs = 1f
            releaseMs = 80f
            makeupDb = 0f
            updateTimeConstants()
        }

        dynamics.process(input, frames)

        fun rms(channel: Int, samples: FloatArray): Double {
            var sum = 0.0
            var count = 0
            for (frame in 4_800 until frames) {
                val value = samples[frame * 2 + channel].toDouble()
                sum += value * value
                count++
            }
            return kotlin.math.sqrt(sum / count)
        }

        val leftGain = rms(0, input) / rms(0, original)
        val rightGain = rms(1, input) / rms(1, original)
        assertTrue("The louder channel should trigger meaningful compression", leftGain < 0.95)
        assertTrue(
            "Stereo-linked detection should apply nearly identical gain to both channels",
            kotlin.math.abs(leftGain - rightGain) < 0.01
        )
    }

}
