package com.lucianotoscano.pvppokego.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargedAnimationCycleGateTest {
    private val fly = ChargedAnimationCycleGate.Signature("voce", "FLY")

    @Test
    fun repeatedFramesFromSameAnimationEmitOnce() {
        val gate = ChargedAnimationCycleGate()
        assertTrue(gate.observe(fly, 17_034L))
        assertFalse(gate.observe(fly, 17_895L))
        assertFalse(gate.observe(fly, 18_842L))
    }

    @Test
    fun legitimateConsecutiveChargedAfterClearEmitsAgain() {
        val gate = ChargedAnimationCycleGate()
        assertTrue(gate.observe(fly, 10_000L))
        assertFalse(gate.observe(null, 10_550L))
        assertFalse(gate.observe(null, 11_050L))
        assertTrue(gate.observe(fly, 11_650L))
    }

    @Test
    fun oneOcrMissDoesNotRearmCycle() {
        val gate = ChargedAnimationCycleGate()
        assertTrue(gate.observe(fly, 1_000L))
        assertFalse(gate.observe(null, 1_520L))
        assertFalse(gate.observe(fly, 2_040L))
    }

    @Test
    fun distinctMoveStillNeedsPreviousAnimationToClear() {
        val gate = ChargedAnimationCycleGate()
        val rock = ChargedAnimationCycleGate.Signature("inimigo", "ROCK_SLIDE")
        assertTrue(gate.observe(fly, 5_000L))
        assertFalse(gate.observe(rock, 5_500L))
        assertFalse(gate.observe(null, 6_000L))
        assertFalse(gate.observe(null, 6_500L))
        assertTrue(gate.observe(rock, 7_000L))
    }
}
