package com.lucianotoscano.pvppokego.data

import org.junit.Assert.*
import org.junit.Test

class TeamPvpToolsTest {
    private val base = PokemonBaseStats(atk = 120, def = 150, hp = 130)
    private val multipliers = (0..100).map { 0.10 + it * 0.007 }

    @Test fun cpIsMonotoneWithIvsAndMultiplier() {
        val a = TeamPvpTools.cpAt(base, 0, 0, 0, 0.7)
        val b = TeamPvpTools.cpAt(base, 15, 15, 15, 0.7)
        assertTrue(b > a)
        assertTrue(TeamPvpTools.cpAt(base, 15, 15, 15, 0.8) > b)
    }

    @Test fun masterPerfectIvsAreRankOneWhenAllCombosFit() {
        val rank = TeamPvpTools.rank(base, multipliers, 10000, 15, 15, 15)
        assertNotNull(rank)
        assertEquals(1, rank!!.rank)
        assertEquals(4096, rank.total)
    }

    @Test fun declinesUnknownIvs() {
        assertNull(TeamPvpTools.rank(base, multipliers, 1500, null, 14, 15))
    }

    @Test fun moveEnergyMathIsCorrect() {
        val f = MoveDef("peck", "Bicada", power = 4, energyGain = 6, cooldown = 500)
        val c = MoveDef("dive", "Mergulho", power = 75, energy = 50)
        val report = TeamPvpTools.moveSummary(f, c)!!
        assertEquals(9, report.fastMovesNeeded)
        assertEquals(4.5, report.secondsNeeded, .0001)
        assertEquals(1.5, report.damagePerEnergy, .0001)
    }
}
