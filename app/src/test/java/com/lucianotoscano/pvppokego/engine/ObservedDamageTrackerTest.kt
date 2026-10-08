package com.lucianotoscano.pvppokego.engine

import com.lucianotoscano.pvppokego.data.DamageConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservedDamageTrackerTest {
    @Test
    fun learnsNormalizedDamagePerHit() {
        val tracker = ObservedDamageTracker()
        assertTrue(
            tracker.record(
                attackerKey = "whiscash",
                defenderKey = "houndoom",
                moveKey = ObservedDamageTracker.FAST_MOVE_KEY,
                totalDamageFraction = 0.076f,
                hits = 2,
                confidence = 0.90f,
                charged = false
            )
        )

        val estimate = tracker.estimate(
            "whiscash",
            "houndoom",
            ObservedDamageTracker.FAST_MOVE_KEY,
            currentHpRatio = 0.80f
        )
        assertNotNull(estimate)
        assertEquals(3.8f, estimate!!.averagePercent, 0.15f)
        assertEquals(38, estimate.averagePoints)
        assertEquals(76.2f, estimate.projectedRemainingPercent!!, 0.2f)
        assertEquals(DamageConfidence.FIRST_SAMPLE, estimate.confidence)
    }

    @Test
    fun keepsDamageSeparateByMatchup() {
        val tracker = ObservedDamageTracker()
        tracker.record("whiscash", "houndoom", "SCALD", 0.38f, confidence = 1f, charged = true)
        tracker.record("whiscash", "altaria", "SCALD", 0.19f, confidence = 1f, charged = true)

        assertEquals(
            38f,
            tracker.estimate("whiscash", "houndoom", "SCALD", 1f)!!.averagePercent,
            0.2f
        )
        assertEquals(
            19f,
            tracker.estimate("whiscash", "altaria", "SCALD", 1f)!!.averagePercent,
            0.2f
        )
    }

    @Test
    fun rejectsWeakOrImpossibleSamples() {
        val tracker = ObservedDamageTracker()
        assertFalse(
            tracker.record(
                "whiscash",
                "houndoom",
                ObservedDamageTracker.FAST_MOVE_KEY,
                0.04f,
                confidence = 0.30f,
                charged = false
            )
        )
        assertFalse(
            tracker.record(
                "whiscash",
                "houndoom",
                ObservedDamageTracker.FAST_MOVE_KEY,
                0.60f,
                confidence = 1f,
                charged = false
            )
        )
    }

    @Test
    fun repeatedSamplesIncreaseConfidence() {
        val tracker = ObservedDamageTracker()
        repeat(5) {
            tracker.record("talonflame", "cradily", "FLY", 0.24f, confidence = 1f, charged = true)
        }
        val estimate = tracker.estimate("talonflame", "cradily", "FLY", 0.70f)!!
        assertEquals(5, estimate.samples)
        assertEquals(DamageConfidence.STABLE, estimate.confidence)
        assertEquals(46f, estimate.projectedRemainingPercent!!, 0.2f)
    }

    @Test
    fun resetClearsBattleLearning() {
        val tracker = ObservedDamageTracker()
        tracker.record("a", "b", "MOVE", 0.20f, confidence = 1f, charged = true)
        tracker.resetBattle()
        assertEquals(null, tracker.estimate("a", "b", "MOVE", 1f))
    }
}
