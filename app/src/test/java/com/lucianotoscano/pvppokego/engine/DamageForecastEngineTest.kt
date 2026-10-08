package com.lucianotoscano.pvppokego.engine

import com.lucianotoscano.pvppokego.data.EstimatedBattleStats
import com.lucianotoscano.pvppokego.data.MoveDef
import com.lucianotoscano.pvppokego.data.PokemonBaseStats
import com.lucianotoscano.pvppokego.data.PokemonDef
import com.lucianotoscano.pvppokego.data.StatStageRange
import org.junit.Assert.assertTrue
import org.junit.Test

class DamageForecastEngineTest {
    private val fireAttacker = PokemonDef(
        speciesId = "test_fire",
        speciesName = "Test Fire",
        types = listOf("fire"),
        baseStats = PokemonBaseStats(200, 150, 150)
    )
    private val grassDefender = PokemonDef(
        speciesId = "test_grass",
        speciesName = "Test Grass",
        types = listOf("grass"),
        baseStats = PokemonBaseStats(150, 180, 180)
    )
    private val neutralDefender = grassDefender.copy(
        speciesId = "test_water",
        speciesName = "Test Water",
        types = listOf("water")
    )
    private val statsA = EstimatedBattleStats(140.0, 120.0, 130, 20.0, 0, 0, 0, 1490)
    private val statsD = EstimatedBattleStats(120.0, 135.0, 150, 20.0, 0, 0, 0, 1490)
    private val fireMove = MoveDef(
        moveId = "TEST_FIRE",
        name = "Test Fire",
        type = "fire",
        power = 90,
        energyGain = 0,
        energy = -50,
        cooldown = 500
    )

    @Test
    fun superEffectiveStabForecastIsHigherThanResistedForecast() {
        val superEffective = DamageForecastEngine.modelDamagePercent(
            fireAttacker, statsA, grassDefender, statsD, fireMove
        )!!
        val resisted = DamageForecastEngine.modelDamagePercent(
            fireAttacker, statsA, neutralDefender, statsD, fireMove
        )!!
        assertTrue(superEffective > resisted)
        assertTrue(superEffective > 0f)
    }

    @Test
    fun positiveAttackStageRaisesDamageAndDefenseStageLowersIt() {
        val neutral = DamageForecastEngine.modelDamagePercent(
            fireAttacker, statsA, grassDefender, statsD, fireMove
        )!!
        val boosted = DamageForecastEngine.modelDamagePercent(
            fireAttacker, statsA, grassDefender, statsD, fireMove,
            attackerAttackStage = StatStageRange(2,2)
        )!!
        val defended = DamageForecastEngine.modelDamagePercent(
            fireAttacker, statsA, grassDefender, statsD, fireMove,
            defenderDefenseStage = StatStageRange(2,2)
        )!!
        assertTrue(boosted > neutral)
        assertTrue(defended < neutral)
    }

    @Test
    fun shadowAttackerRaisesDamageAndShadowDefenderTakesMoreDamage() {
        val neutral = DamageForecastEngine.modelDamagePercent(
            fireAttacker, statsA, grassDefender, statsD, fireMove
        )!!
        val shadowAttacker = DamageForecastEngine.modelDamagePercent(
            fireAttacker.copy(tags = listOf("shadow")), statsA, grassDefender, statsD, fireMove
        )!!
        val shadowDefender = DamageForecastEngine.modelDamagePercent(
            fireAttacker, statsA, grassDefender.copy(tags = listOf("shadow")), statsD, fireMove
        )!!
        assertTrue(shadowAttacker > neutral)
        assertTrue(shadowDefender > neutral)
    }

    @Test
    fun percentMaxHpDamageMethodMatchesPvpokeRule() {
        val move = fireMove.copy(power = 50, damageMethod = "percentMaxHP")
        val pct = DamageForecastEngine.modelDamagePercent(
            fireAttacker, statsA, grassDefender, statsD, move
        )!!
        // floor(50% of 150 HP)+1 = 76 => 50.666...%
        assertTrue(pct > 50f)
        assertTrue(pct < 51f)
    }
}
