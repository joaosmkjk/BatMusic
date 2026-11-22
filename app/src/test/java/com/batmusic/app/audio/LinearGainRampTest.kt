package com.batmusic.app.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class LinearGainRampTest {
    @Test
    fun reachesTargetExactlyAfterRequestedFrames() {
        val ramp = LinearGainRamp()
        ramp.setTarget(2f, 4)
        assertEquals(1.25f, ramp.next(), 0.0001f)
        assertEquals(1.5f, ramp.next(), 0.0001f)
        assertEquals(1.75f, ramp.next(), 0.0001f)
        assertEquals(2f, ramp.next(), 0.0001f)
        assertEquals(2f, ramp.next(), 0.0001f)
    }

    @Test
    fun retargetsFromCurrentGainWithoutDiscontinuity() {
        val ramp = LinearGainRamp()
        ramp.setTarget(2f, 4)
        assertEquals(1.25f, ramp.next(), 0.0001f)
        ramp.setTarget(1f, 2)
        assertEquals(1.125f, ramp.next(), 0.0001f)
        assertEquals(1f, ramp.next(), 0.0001f)
    }

    @Test
    fun invalidTargetFallsBackToUnity() {
        val ramp = LinearGainRamp()
        ramp.setTarget(Float.NaN, 3)
        assertEquals(1f, ramp.next(), 0.0001f)
    }

    @Test
    fun resetReturnsToUnityImmediately() {
        val ramp = LinearGainRamp()
        ramp.setTarget(3f, 2)
        ramp.reset()
        assertEquals(1f, ramp.next(), 0.0001f)
    }
}
