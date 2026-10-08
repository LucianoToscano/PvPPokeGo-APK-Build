package com.lucianotoscano.pvppokego.engine

import com.lucianotoscano.pvppokego.data.MoveDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FastMovePhaseTrackerTest {
    private val fast = MoveDef(moveId="TEST_FAST", name="Teste", energyGain=9, cooldown=1500)

    @Test fun registrationOccursOnLastTurn() {
        val tracker = FastMovePhaseTracker()
        tracker.observeCompletion(fast, 1, nowMs=10_000, detectorConfidence=.95f)
        val t1 = tracker.snapshot(10_000)!!
        assertEquals(1, t1.phaseTurn)
        assertEquals(3, t1.turnsUntilRegistration)
        assertEquals(1500L, t1.nextRegistrationInMs)

        val t2 = tracker.snapshot(10_500)!!
        assertEquals(2, t2.phaseTurn)
        assertEquals(2, t2.turnsUntilRegistration)

        val t3 = tracker.snapshot(11_000)!!
        assertEquals(3, t3.phaseTurn)
        assertEquals(1, t3.turnsUntilRegistration)
        assertEquals(500L, t3.nextRegistrationInMs)
    }

    @Test fun confidenceDecaysAcrossUnseenCycles() {
        val tracker = FastMovePhaseTracker()
        tracker.observeCompletion(fast, 1, 1_000, .9f)
        val early = tracker.snapshot(1_500)!!.confidence
        val late = tracker.snapshot(8_000)!!.confidence
        assertTrue(late < early)
    }
}
