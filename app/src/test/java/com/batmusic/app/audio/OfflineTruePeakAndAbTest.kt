package com.batmusic.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.pow

class OfflineTruePeakAndAbTest {
    @Test
    fun peakMonitorIsChunkBoundaryInvariant() {
        val samples = FloatArray(4096 * 2) { index ->
            val frame = index / 2
            (0.92 * sin(2.0 * PI * 0.37 * frame + 0.31)).toFloat()
        }
        val onePass = OversampledTruePeakMeter(2).apply { process(samples) }.finishDbtp()
        val chunked = OversampledTruePeakMeter(2).apply {
            var offset = 0
            while (offset < samples.size) {
                val count = minOf(514, samples.size - offset)
                process(samples.copyOfRange(offset, offset + count), count / 2)
                offset += count
            }
        }.finishDbtp()
        assertEquals(onePass, chunked, 0.0001f)
    }

    @Test
    fun firPeakMonitorDetectsInterSamplePeakAboveSamplePeak() {
        val samples = FloatArray(4096) { index ->
            (0.9 * sin(2.0 * PI * 0.37 * index + 0.31)).toFloat()
        }
        val samplePeak = samples.maxOf { kotlin.math.abs(it) }
        val measured = OversampledTruePeakMeter(1).apply { process(samples) }.finishDbtp()
        val measuredLinear = 10.0.pow(measured / 20.0)
        assertTrue("FIR oversampling should reveal inter-sample peaks", measuredLinear > samplePeak + 0.005)
        assertTrue("The estimate must remain finite", measured.isFinite())
    }

    @Test
    fun peakMonitorNeverReportsBelowSamplePeak() {
        val samples = FloatArray(1024) { index ->
            (0.8 * sin(2.0 * PI * 0.41 * index + 0.2)).toFloat()
        }
        val samplePeak = samples.maxOf { kotlin.math.abs(it) }
        val meter = OversampledTruePeakMeter(1)
        meter.process(samples)
        assertTrue(meter.finishDbtp() >= 20.0 * kotlin.math.log10(samplePeak.toDouble()) - 0.001)
    }

    @Test
    fun abMatcherKeepsComparisonBelowTruePeakCeiling() {
        val gain = LoudnessABMatcher.matchGainDb(-16f, -22f, -4f)
        assertNotNull(gain)
        assertEquals(2.5f, gain!!, 0.001f)
        assertTrue(-4f + gain <= LoudnessNormalizer.TRUE_PEAK_CEILING_DBTP)
    }

    @Test
    fun unsafePeakCorrectionOutsideBoundedRangeIsRejected() {
        assertNull(LoudnessNormalizer.plan(-18f, 20f))
        assertNull(LoudnessABMatcher.matchGainDb(-18f, -18f, 20f))
    }

    @Test
    fun abMatcherRejectsSilenceAndInvalidMeasurements() {
        assertNull(LoudnessABMatcher.matchGainDb(-120f, -18f, -4f))
        assertNull(LoudnessABMatcher.matchGainDb(-16f, Float.NaN, -4f))
    }

    @Test
    fun normalizerNeverRequestsUnsafePositiveGain() {
        val plan = LoudnessNormalizer.plan(-32f, -2f)
        assertNotNull(plan)
        assertTrue(plan!!.appliedGainDb <= LoudnessNormalizer.MAX_POSITIVE_GAIN_DB)
        assertTrue(plan.predictedTruePeakDbtp <= LoudnessNormalizer.TRUE_PEAK_CEILING_DBTP)
    }
    @Test
    fun itu48TapMeterApproachesKnownBandlimitedSinePeak() {
        val rate = 48_000
        val amplitude = 0.91
        val frequency = 0.473
        val phase = 0.29
        val pcm = FloatArray(48_000) { index ->
            (amplitude * sin(2.0 * PI * frequency * index + phase)).toFloat()
        }
        val samplePeak = pcm.maxOf { kotlin.math.abs(it) }
        val measuredDbtp = OversampledTruePeakMeter(1, rate).apply { process(pcm) }.finishDbtp()
        val measured = 10.0.pow(measuredDbtp / 20.0)

        assertTrue("true peak must not under-read the sample peak", measured + 0.001 >= samplePeak)
        assertEquals("48-tap interpolation should recover the sine's continuous peak",
            amplitude, measured, 0.015)
    }

    @Test
    fun itu48TapMeterPreservesDcAndFlushesTheFinalSamples() {
        val pcm = FloatArray(512) { 0.75f }
        val meter = OversampledTruePeakMeter(1, 48_000)
        meter.process(pcm.copyOfRange(0, 251), 251)
        meter.process(pcm.copyOfRange(251, pcm.size), pcm.size - 251)
        val measuredDbtp = meter.finishDbtp()
        assertEquals(20.0 * kotlin.math.log10(0.75), measuredDbtp.toDouble(), 0.015)
    }

}
