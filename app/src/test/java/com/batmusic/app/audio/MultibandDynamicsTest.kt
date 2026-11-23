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
}
