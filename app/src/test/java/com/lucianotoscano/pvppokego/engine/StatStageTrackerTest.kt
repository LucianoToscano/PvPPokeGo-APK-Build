package com.lucianotoscano.pvppokego.engine

import com.lucianotoscano.pvppokego.data.MoveDef
import com.lucianotoscano.pvppokego.data.StatStageRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatStageTrackerTest {
    private fun move(
        id: String,
        buffs: List<Int>,
        target: String,
        chance: String
    ) = MoveDef(
        moveId = id,
        name = id,
        energy = 45,
        buffs = buffs,
        buffTarget = target,
        buffApplyChance = chance
    )

    @Test
    fun guaranteedSelfBuffAppliesExactlyAndCapsAtFour() {
        var self = StatStageTracker.Stages(
            attack = StatStageRange(3,3),
            defense = StatStageRange()
        )
        val result = StatStageTracker.applyMove(
            move("BUFF", listOf(2,0), "self", "1"),
            self,
            StatStageTracker.Stages()
        )
        self = result.self
        assertEquals(4, self.attack.min)
        assertEquals(4, self.attack.max)
        assertTrue(result.deterministic)
    }

    @Test
    fun probabilisticBuffWidensRangeInsteadOfPretendingProc() {
        val result = StatStageTracker.applyMove(
            move("PROC", listOf(1,0), "self", ".5"),
            StatStageTracker.Stages(),
            StatStageTracker.Stages()
        )
        assertEquals(0, result.self.attack.min)
        assertEquals(1, result.self.attack.max)
        assertEquals(.5f, result.chance, .0001f)
    }

    @Test
    fun guaranteedOpponentDebuffTargetsOpponentDefense() {
        val result = StatStageTracker.applyMove(
            move("DEBUFF", listOf(0,-2), "opponent", "1"),
            StatStageTracker.Stages(),
            StatStageTracker.Stages()
        )
        assertEquals(-2, result.opponent.defense.min)
        assertEquals(-2, result.opponent.defense.max)
    }

    @Test
    fun switchResetReturnsNeutralStages() {
        val reset = StatStageTracker.reset()
        assertEquals(0, reset.attack.min)
        assertEquals(0, reset.attack.max)
        assertEquals(0, reset.defense.min)
        assertEquals(0, reset.defense.max)
    }

    @Test
    fun cmpUsesRawAttackAndIsIndependentOfStages() {
        assertEquals(1, StatStageTracker.cmp(123.5, 122.9))
        assertEquals(-1, StatStageTracker.cmp(100.0, 101.0))
        assertEquals(0, StatStageTracker.cmp(100.0, 100.0))
    }

    @Test
    fun pvpokeStageMultiplierMatchesBuffDivisorFour() {
        assertEquals(1.25, DamageForecastEngine.statStageMultiplier(1), .00001)
        assertEquals(.8, DamageForecastEngine.statStageMultiplier(-1), .00001)
        assertEquals(2.0, DamageForecastEngine.statStageMultiplier(4), .00001)
        assertEquals(.5, DamageForecastEngine.statStageMultiplier(-4), .00001)
    }

    @Test
    fun dualTargetMoveAppliesDifferentSelfAndOpponentStages() {
        val obstruct = MoveDef(
            moveId = "OBSTRUCT",
            name = "Obstruct",
            energy = 40,
            buffs = listOf(0,1),
            buffsSelf = listOf(0,1),
            buffsOpponent = listOf(0,-1),
            buffTarget = "both",
            buffApplyChance = "1"
        )
        val result = StatStageTracker.applyMove(
            obstruct, StatStageTracker.Stages(), StatStageTracker.Stages()
        )
        assertEquals(1, result.self.defense.min)
        assertEquals(-1, result.opponent.defense.min)
    }
}
