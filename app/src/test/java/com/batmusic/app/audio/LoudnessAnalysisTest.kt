package com.batmusic.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class LoudnessAnalysisTest {
    @Test
    fun silenceRemainsAtFloor() {
        val meter = LoudnessMeter(48_000, 2)
        meter.process(FloatArray(48_000 * 2))
        assertEquals(LoudnessMeter.SILENCE_LUFS, meter.momentaryLufs, 0.01f)
        assertEquals(LoudnessMeter.SILENCE_LUFS, meter.integratedLufs, 0.01f)
    }

    @Test
    fun steadyOneKilohertzToneProducesStableLoudness() {
        val rate = 48_000
        val meter = LoudnessMeter(rate, 2)
        val block = FloatArray(4_800 * 2)
        var phase = 0
        repeat(20) {
            for (frame in 0 until 4_800) {
                val sample = (0.1 * sin(2.0 * PI * 1_000.0 * (phase + frame) / rate)).toFloat()
                block[frame * 2] = sample
                block[frame * 2 + 1] = sample
            }
            meter.process(block)
            phase += 4_800
        }
        assertTrue("momentary LUFS should be finite", meter.momentaryLufs.isFinite())
        assertTrue("integrated LUFS should be plausible: ${meter.integratedLufs}",
            meter.integratedLufs in -27f..-20f)
        assertTrue(meter.measuredBlocks > 50)
    }

    @Test
    fun resetClearsTrackHistoryAndFilters() {
        val meter = LoudnessMeter(48_000, 1)
        val tone = FloatArray(48_000) { index ->
            (0.5 * sin(2.0 * PI * 440.0 * index / 48_000)).toFloat()
        }
        meter.process(tone)
        assertTrue(meter.integratedLufs > -70f)
        meter.reset()
        assertEquals(LoudnessMeter.SILENCE_LUFS, meter.integratedLufs, 0.01f)
        assertEquals(0, meter.measuredBlocks)
    }

    @Test
    fun spectrumFindsDominantToneBin() {
        val size = 1024
        val analyzer = BatSpectrumAnalyzer(size)
        val input = FloatArray(size) { index ->
            sin(2.0 * PI * 8.0 * index / size).toFloat()
        }
        val output = FloatArray(size / 2)
        analyzer.analyze(input, output)
        val peakBin = output.indices.maxByOrNull { output[it] }
        assertEquals(8, peakBin)
    }
}
