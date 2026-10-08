package com.lucianotoscano.pvppokego.engine

import com.lucianotoscano.pvppokego.data.MoveDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargedMovePredictorTest {
    private fun fast(id: String, gain: Int, turns: Int) = MoveDef(
        moveId = id,
        name = id,
        energyGain = gain,
        cooldown = turns * 500
    )

    private fun h(fast: MoveDef, energy: Int, weight: Float) =
        EnemyEnergyTracker.Hypothesis(
            fastMove = fast,
            energy = energy,
            completedFastMoves = 0,
            elapsedTurns = 0,
            weight = weight
        )

    @Test
    fun exactFastCalculatesFastAndTurnWindow() {
        val move = fast("MUD_SHOT", 9, 2)
        val window = ChargedMovePredictor.energyWindow(listOf(h(move, 27, 1f)), 45)!!
        assertEquals(2, window.fastMovesLikely)
        assertEquals(4, window.turnsLikely)
        assertFalse(window.readyPossible)
    }

    @Test
    fun unknownFastMaintainsRangeInsteadOfChoosingOne() {
        val quick = fast("QUICK", 9, 2)
        val slow = fast("SLOW", 7, 4)
        val window = ChargedMovePredictor.energyWindow(
            listOf(h(quick, 27, .65f), h(slow, 24, .35f)),
            45
        )!!
        assertEquals(2, window.fastMovesMin)
        assertEquals(2, window.fastMovesLikely)
        assertEquals(3, window.fastMovesMax)
        assertEquals(4, window.turnsMin)
        assertEquals(4, window.turnsLikely)
        assertEquals(12, window.turnsMax)
    }

    @Test
    fun readyPossibleAndCertainStayDistinct() {
        val a = fast("A", 8, 2)
        val b = fast("B", 10, 3)
        val possible = ChargedMovePredictor.energyWindow(
            listOf(h(a, 48, .5f), h(b, 32, .5f)),
            45
        )!!
        assertTrue(possible.readyPossible)
        assertFalse(possible.readyCertain)

        val certain = ChargedMovePredictor.energyWindow(
            listOf(h(a, 48, .5f), h(b, 55, .5f)),
            45
        )!!
        assertTrue(certain.readyPossible)
        assertTrue(certain.readyCertain)
    }

    @Test
    fun doubleChargedRequiresEnoughStoredEnergy() {
        val move = fast("FAST", 10, 2)
        assertTrue(ChargedMovePredictor.energyWindow(listOf(h(move, 90, 1f)), 45)!!.doubleReadyPossible)
        assertFalse(ChargedMovePredictor.energyWindow(listOf(h(move, 80, 1f)), 45)!!.doubleReadyPossible)
    }

    @Test
    fun expensiveChargedNeedsMoreTimeThanCheapCharged() {
        val move = fast("FAST", 8, 2)
        val hypotheses = listOf(h(move, 24, 1f))
        val cheap = ChargedMovePredictor.energyWindow(hypotheses, 35)!!
        val expensive = ChargedMovePredictor.energyWindow(hypotheses, 65)!!
        assertTrue(cheap.fastMovesLikely < expensive.fastMovesLikely)
        assertTrue(cheap.turnsLikely < expensive.turnsLikely)
    }

    @Test
    fun currentPvpokeMudShotToHydroCannonFixtureIsFiveFastTenTurns() {
        // PvPoke snapshot f627e89: Mud Shot = 9E/2 turns, Hydro Cannon = 40E.
        val mudShot = fast("MUD_SHOT", 9, 2)
        val window = ChargedMovePredictor.energyWindow(listOf(h(mudShot, 0, 1f)), 40)!!
        assertEquals(5, window.fastMovesLikely)
        assertEquals(10, window.turnsLikely)
    }

    @Test
    fun currentPvpokeIncinerateToFlameChargeFixtureIsThreeFastFifteenTurns() {
        // PvPoke snapshot f627e89: Incinerate = 20E/5 turns, Flame Charge = 50E.
        val incinerate = fast("INCINERATE", 20, 5)
        val window = ChargedMovePredictor.energyWindow(listOf(h(incinerate, 0, 1f)), 50)!!
        assertEquals(3, window.fastMovesLikely)
        assertEquals(15, window.turnsLikely)
    }
    @Test
    fun tenEnergyOneSecondFastReachesFiftyEnergyChargedInFiveSeconds() {
        val quick = fast("EXAMPLE_FAST", 10, 2) // 2 turns = 1.0 s
        val window = ChargedMovePredictor.energyWindow(listOf(h(quick, 0, 1f)), 50)!!
        assertEquals(5, window.fastMovesLikely)
        assertEquals(10, window.turnsLikely)
        assertEquals(5_000L, window.turnsLikely * EnemyEnergyTracker.TURN_MS.toLong())
    }

}
