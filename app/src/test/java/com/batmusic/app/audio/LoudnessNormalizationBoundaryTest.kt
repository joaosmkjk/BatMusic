package com.batmusic.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoudnessNormalizationBoundaryTest {
    @Test
    fun normalizationDoesNotExceedConfiguredPositiveGainCap() {
        val plan = LoudnessNormalizer.plan(
            integratedLufs = -45f,
            truePeakDbtp = -20f
        )

        assertNotNull(plan)
        assertEquals(3f, plan!!.appliedGainDb, 0.001f)
        assertTrue(plan.predictedTruePeakDbtp <= LoudnessNormalizer.TRUE_PEAK_CEILING_DBTP)
    }

    @Test
    fun normalizationNeverAppliesMoreThanConfiguredMaximumCut() {
        val plan = LoudnessNormalizer.plan(
            integratedLufs = -1f,
            truePeakDbtp = -1f
        )

        assertNotNull(plan)
        assertEquals(LoudnessNormalizer.MAX_CUT_DB, plan!!.appliedGainDb, 0.001f)
        assertTrue(plan.predictedTruePeakDbtp <= LoudnessNormalizer.TRUE_PEAK_CEILING_DBTP)
    }

    @Test
    fun normalizationRejectsTrackWhenPeakWouldRequireCutBeyondAllowedRange() {
        val plan = LoudnessNormalizer.plan(
            integratedLufs = -20f,
            truePeakDbtp = 11.1f
        )

        assertNull(plan)
    }

    @Test
    fun normalizationHonorsCustomTargetWithoutExceedingPeakBudget() {
        val plan = LoudnessNormalizer.plan(
            integratedLufs = -24f,
            truePeakDbtp = -2f,
            targetLufs = -18f
        )

        assertNotNull(plan)
        assertEquals(4f, plan!!.requestedGainDb, 0.001f)
        assertEquals(0.5f, plan.appliedGainDb, 0.001f)
        assertEquals(-1.5f, plan.predictedTruePeakDbtp, 0.001f)
    }

    @Test
    fun abMatcherRespectsTruePeakCeiling() {
        val gain = LoudnessABMatcher.matchGainDb(
            referenceLufs = -16f,
            comparisonLufs = -24f,
            comparisonTruePeakDbtp = -2f
        )

        assertNotNull(gain)
        assertEquals(0.5f, gain!!, 0.001f)
        assertTrue(-2f + gain <= LoudnessNormalizer.TRUE_PEAK_CEILING_DBTP)
    }

    @Test
    fun abMatcherRejectsSilenceAndNonFiniteMeasurements() {
        assertNull(LoudnessABMatcher.matchGainDb(-16f, -120f, -3f))
        assertNull(LoudnessABMatcher.matchGainDb(Float.NaN, -24f, -3f))
        assertNull(LoudnessABMatcher.matchGainDb(-16f, -24f, Float.POSITIVE_INFINITY))
    }
}
