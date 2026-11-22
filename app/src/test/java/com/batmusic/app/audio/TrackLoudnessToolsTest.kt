package com.batmusic.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class TrackLoudnessToolsTest {
    @Test
    fun normalizationNeverExceedsPositiveGainOrTruePeakCeiling() {
        val plan = LoudnessNormalizer.plan(
            integratedLufs = -30f,
            truePeakDbtp = -0.5f
        )
        assertNotNull(plan)
        assertEquals(-1f, plan!!.appliedGainDb, 0.001f)
        assertTrue(plan.predictedTruePeakDbtp <= LoudnessNormalizer.TRUE_PEAK_CEILING_DBTP)
    }

    @Test
    fun quietTrackGetsOnlyBoundedPositiveGain() {
        val plan = LoudnessNormalizer.plan(
            integratedLufs = -35f,
            truePeakDbtp = -12f
        )
        assertNotNull(plan)
        assertEquals(LoudnessNormalizer.MAX_POSITIVE_GAIN_DB, plan!!.appliedGainDb, 0.001f)
        assertEquals(-32f, plan.predictedIntegratedLufs, 0.001f)
    }

    @Test
    fun silenceDoesNotReceiveNormalizationPlan() {
        assertNull(LoudnessNormalizer.plan(-120f, -120f))
    }

    @Test
    fun nonFiniteMeasurementsAreRejected() {
        assertNull(LoudnessNormalizer.plan(Float.NaN, -3f))
        assertNull(LoudnessNormalizer.plan(-20f, Float.POSITIVE_INFINITY))
    }

    @Test
    fun offlineAccumulatorProcessesBlocksWithoutRetainingPcm() {
        val rate = 48_000
        val accumulator = OfflineLoudnessAccumulator(
            mediaKey = "content://media/audio/123",
            fingerprint = "size=1234;mtime=42",
            durationMs = 2_000,
            sampleRate = rate,
            channels = 2
        )
        val block = FloatArray(4_800 * 2)
        repeat(20) { blockIndex ->
            for (frame in 0 until 4_800) {
                val sample = (0.1 * sin(2.0 * PI * 1_000.0 * (blockIndex * 4_800 + frame) / rate)).toFloat()
                block[frame * 2] = sample
                block[frame * 2 + 1] = sample
            }
            accumulator.feed(block)
        }
        val record = accumulator.finish(-2.0f)
        assertEquals("content://media/audio/123", record.mediaKey)
        assertEquals("size=1234;mtime=42", record.fingerprint)
        assertEquals(TrackLoudnessRecord.CURRENT_VERSION, record.analysisVersion)
        assertEquals(TrackLoudnessRecord.CURRENT_VERSION, 2)
        assertTrue(record.integratedLufs > -70f)
        assertEquals(-2f, record.truePeakDbtp, 0.001f)
    }

    @Test(expected = IllegalStateException::class)
    fun accumulatorCannotBeReusedAfterFinish() {
        val accumulator = OfflineLoudnessAccumulator("id", "fingerprint", 1_000, 48_000, 1)
        accumulator.finish(-3f)
        accumulator.feed(FloatArray(480))
    }
}
