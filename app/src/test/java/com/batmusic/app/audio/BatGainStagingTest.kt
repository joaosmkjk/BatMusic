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
    fun budgetIncludesBoostAndPositiveCompressorMakeup() {
        val base = BatGainStaging.plan(BatAudioSettings(enabled = true))
        val boost = BatGainStaging.plan(BatAudioSettings(enabled = true, preGainDb = 2f, batBoost = 200))
        val compressor = BatGainStaging.plan(
            BatAudioSettings(enabled = true, compressorEnabled = true, compressorMakeupDb = 12f)
        )
        assertTrue(boost.estimatedPositiveStackDb > base.estimatedPositiveStackDb)
        assertTrue(compressor.estimatedPositiveStackDb > base.estimatedPositiveStackDb)
        assertTrue(boost.headroomDb > base.headroomDb)
        assertTrue(compressor.headroomDb > base.headroomDb)
    }

    @Test
    fun budgetAccountsForOverlappingPositiveEqBands() {
        val single = BatGainStaging.plan(
            BatAudioSettings(enabled = true, equalizerEnabled = true,
                bandGainsDb = listOf(6f) + List(9) { 0f })
        )
        val stacked = BatGainStaging.plan(
            BatAudioSettings(enabled = true, equalizerEnabled = true,
                bandGainsDb = List(10) { 6f })
        )
        assertTrue(stacked.estimatedPositiveStackDb > single.estimatedPositiveStackDb)
        assertTrue(stacked.estimatedPositiveStackDb <= 24f)
        assertTrue(stacked.headroomDb > single.headroomDb)
    }

    @Test
    fun disabledToneEngineDoesNotBudgetInactiveEqOrMakeup() {
        val plan = BatGainStaging.plan(
            BatAudioSettings(
                enabled = false,
                equalizerEnabled = true,
                bandGainsDb = List(10) { 12f },
                preGainDb = 12f,
                compressorEnabled = true,
                compressorMakeupDb = 12f,
                batBoost = 200
            )
        )
        assertEquals(0f, plan.estimatedPositiveStackDb, 0.0001f)
        assertEquals(0f, plan.inputGainDb, 0.0001f)
    }

    @Test
    fun maximumControlsAreIncludedInTheExpandedHeadroomBudget() {
        val plan = BatGainStaging.plan(
            BatAudioSettings(
                enabled = true,
                preGainDb = 24f,
                equalizerEnabled = true,
                bandGainsDb = List(10) { 12f },
                bassBoost = 100,
                batBoost = 300,
                compressorEnabled = true,
                compressorMakeupDb = 24f
            )
        )
        assertEquals(96.0412f, plan.estimatedPositiveStackDb, 0.01f)
        assertTrue(plan.headroomDb.isFinite())
        assertTrue(plan.headroomDb > 0f)
        assertTrue(plan.inputGainDb.isFinite())
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
            )
        )
        assertTrue(plan.estimatedPositiveStackDb.isFinite())
        assertTrue(plan.headroomDb.isFinite())
        assertTrue(plan.inputGainDb.isFinite())
        assertTrue(plan.headroomDb >= 0f)
        assertTrue(plan.inputGainDb <= 0f)
    }
}
