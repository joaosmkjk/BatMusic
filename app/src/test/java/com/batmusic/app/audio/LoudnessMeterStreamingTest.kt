package com.batmusic.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class LoudnessMeterStreamingTest {
    @Test
    fun loudnessIsIndependentOfInputChunkBoundaries() {
        val rate = 48_000
        val frames = rate * 2
        val pcm = FloatArray(frames * 2) { index ->
            val frame = index / 2
            (0.12 * sin(2.0 * PI * 997.0 * frame / rate)).toFloat()
        }

        val contiguous = LoudnessMeter(rate, 2)
        contiguous.process(pcm)

        val streamed = LoudnessMeter(rate, 2)
        var frameOffset = 0
        val chunkPattern = intArrayOf(1, 137, 997, 4_801, 73, 2_003)
        var patternIndex = 0
        while (frameOffset < frames) {
            val count = minOf(chunkPattern[patternIndex % chunkPattern.size], frames - frameOffset)
            val chunk = pcm.copyOfRange(frameOffset * 2, (frameOffset + count) * 2)
            streamed.process(chunk, count)
            frameOffset += count
            patternIndex++
        }

        assertEquals(contiguous.momentaryLufs, streamed.momentaryLufs, 0.0001f)
        assertEquals(contiguous.integratedLufs, streamed.integratedLufs, 0.0001f)
        assertEquals(contiguous.measuredBlocks, streamed.measuredBlocks)
    }

    @Test
    fun nonFiniteSamplesAreHandledWithoutPoisoningMeterState() {
        val meter = LoudnessMeter(48_000, 2)
        val pcm = FloatArray(48_000 * 2) { index ->
            when (index % 17) {
                0 -> Float.NaN
                1 -> Float.POSITIVE_INFINITY
                2 -> Float.NEGATIVE_INFINITY
                else -> 0.05f
            }
        }

        meter.process(pcm)

        assertTrue(meter.momentaryLufs.isFinite())
        assertTrue(meter.integratedLufs.isFinite())
        assertTrue(meter.integratedLufs > LoudnessMeter.SILENCE_LUFS)
    }

    @Test
    fun resetMakesReusedMeterMatchFreshMeter() {
        val rate = 48_000
        val tone = FloatArray(rate) { index ->
            (0.2 * sin(2.0 * PI * 440.0 * index / rate)).toFloat()
        }
        val reused = LoudnessMeter(rate, 1)
        reused.process(tone)
        reused.reset()
        reused.process(tone)

        val fresh = LoudnessMeter(rate, 1)
        fresh.process(tone)

        assertEquals(fresh.integratedLufs, reused.integratedLufs, 0.0001f)
        assertEquals(fresh.momentaryLufs, reused.momentaryLufs, 0.0001f)
        assertEquals(fresh.measuredBlocks, reused.measuredBlocks)
    }
}
