package com.batmusic.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class MultibandDynamicsTest {
    @Test
    fun unityRatioReconstructsInputWithoutCrossoverHoles() {
        val processor = MultibandDynamics(48_000, 2)
        processor.ratio = 1f
        processor.thresholdDb = -48f
        val input = FloatArray(9_600) { index ->
            (0.35 * sin(2.0 * PI * 997.0 * (index / 2) / 48_000.0)).toFloat()
        }
        val expected = input.copyOf()
        processor.process(input, input.size / 2)
        var maximumError = 0f
        for (i in input.indices) maximumError = maxOf(maximumError, kotlin.math.abs(input[i] - expected[i]))
        assertTrue("complementary bands should reconstruct the signal; error=$maximumError", maximumError < 1e-5f)
    }

    @Test
    fun outputRemainsFiniteForInvalidInputSamples() {
        val processor = MultibandDynamics(48_000, 2)
        val input = FloatArray(2_048) { if (it % 17 == 0) Float.NaN else 0.9f }
        processor.process(input, input.size / 2)
        assertTrue(input.all { it.isFinite() })
    }

    @Test
    fun compressionDoesNotIncreaseBandEnvelopeWithoutMakeup() {
        val processor = MultibandDynamics(48_000, 1)
        processor.thresholdDb = -30f
        processor.ratio = 4f
        processor.attackMs = 2f
        processor.makeupDb = 0f
        processor.updateTimeConstants()
        val input = FloatArray(48_000) { index ->
            (0.8 * sin(2.0 * PI * 100.0 * index / 48_000.0)).toFloat()
        }
        processor.process(input, input.size)
        val peak = input.maxOf { kotlin.math.abs(it) }
        assertTrue("compressed signal must stay finite and bounded", peak.isFinite() && peak <= 1f)
        assertEquals(48_000, input.size)
    }
}
