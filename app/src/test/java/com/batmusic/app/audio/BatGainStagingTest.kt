package com.batmusic.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatGainStagingTest {
    @Test
    fun flatSettingsDoNotAddHeadroomAttenuation() {
        val plan = BatGainStaging.plan(BatAudioSettings())
        assertEquals(0f, plan.estimatedPositiveStackDb, 0.0001f)
        assertEquals(0f, plan.headroomDb, 0.0001f)
        assertEquals(0f, plan.inputGainDb, 0.0001f)
    }

    @Test
    fun exactBudgetFullyCompensatesOnlyTheExcessAboveSevenDb() {
        val underBudget = BatGainStaging.plan(
            BatAudioSettings(enabled = true, preGainDb = 2f, batBoost = 150),
            peakFilterGainDb = 1f
        )
        assertEquals(6.0103f, underBudget.estimatedPositiveStackDb, 0.002f)
        assertEquals(0f, underBudget.headroomDb, 0.0001f)
        assertEquals(2f, underBudget.inputGainDb, 0.0001f)

        val overBudget = BatGainStaging.plan(
            BatAudioSettings(
                enabled = true,
                preGainDb = 2f,
                batBoost = 200,
                compressorEnabled = true,
                compressorMakeupDb = 3f
            ),
            peakFilterGainDb = 4f
        )
        // 2 pre + 4 measured filter + 6.0206 BATGAIN + 3 makeup = 15.0206 dB.
        assertEquals(15.0206f, overBudget.estimatedPositiveStackDb, 0.002f)
        assertEquals(8.0206f, overBudget.headroomDb, 0.002f)
        assertEquals(-6.0206f, overBudget.inputGainDb, 0.002f)
        assertEquals(7f, overBudget.inputGainDb + overBudget.estimatedPositiveStackDb - 2f, 0.002f)
    }

    @Test
    fun filterBudgetUsesMeasuredCascadeResponseInsteadOfAddingBandValues() {
        val plan = BatGainStaging.plan(
            BatAudioSettings(
                enabled = true,
                equalizerEnabled = true,
                bandGainsDb = List(10) { 12f },
                bassBoost = 100,
                batBoost = 100
            ),
            peakFilterGainDb = 13.4f
        )
        // Peak filter gain is supplied by BatAudioProcessor's coefficient sweep;
        // nominal individual band values are deliberately not summed here.
        assertEquals(13.4f, plan.estimatedPositiveStackDb, 0.001f)
        assertEquals(6.4f, plan.headroomDb, 0.001f)
        assertEquals(-6.4f, plan.inputGainDb, 0.001f)
    }

    @Test
    fun disabledToneEngineDoesNotBudgetInactiveControls() {
        val plan = BatGainStaging.plan(
            BatAudioSettings(
                enabled = false,
                equalizerEnabled = true,
                bandGainsDb = List(10) { 12f },
                preGainDb = 12f,
                compressorEnabled = true,
                compressorMakeupDb = 12f,
                batBoost = 200
            ),
            peakFilterGainDb = 24f
        )
        assertEquals(0f, plan.estimatedPositiveStackDb, 0.0001f)
        assertEquals(0f, plan.inputGainDb, 0.0001f)
    }

    @Test
    fun nonFiniteAndOutOfRangeControlsCannotPoisonBudget() {
        val plan = BatGainStaging.plan(
            BatAudioSettings(
                enabled = true,
                equalizerEnabled = true,
                bandGainsDb = listOf(Float.NaN) + List(9) { 100f },
                preGainDb = Float.POSITIVE_INFINITY,
                compressorEnabled = true,
                compressorMakeupDb = Float.NaN,
                batBoost = 999
            ),
            peakFilterGainDb = Float.NaN
        )
        assertTrue(plan.estimatedPositiveStackDb.isFinite())
        assertTrue(plan.headroomDb.isFinite())
        assertTrue(plan.inputGainDb.isFinite())
        assertTrue(plan.headroomDb >= 0f)
        assertTrue(plan.inputGainDb <= 0f)
    }
}
