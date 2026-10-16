package com.lucianotoscano.pvppokego.data

import org.junit.Assert.*
import org.junit.Test

class PvpIvDatabaseTest {
    private val stats = PokemonBaseStats(atk = 120, def = 150, hp = 130)
    private val cpms = (0..100).map { 0.10 + it * 0.007 }

    @Test fun websiteLinksUseConfirmedLeagueParametersAndEncodeNames() {
        assertEquals("https://iv4u.lima-city.de/?l=en&m=3&m2=1&p=Clefable",
            PvpIvReferences.iv4uUrl("Clefable", 1500))
        assertEquals("https://iv4u.lima-city.de/?l=en&m=3&m2=2&p=Mr.%20Mime",
            PvpIvReferences.iv4uUrl("Mr. Mime", 2500))
        assertEquals("https://iv4u.lima-city.de/?l=en&m=3&m2=3&p=Clefable",
            PvpIvReferences.iv4uUrl("Clefable", 10000))
        assertEquals("https://iv4u.lima-city.de/?l=en&m=3&m2=4&p=Clefable",
            PvpIvReferences.iv4uUrl("Clefable", 500))
        assertEquals(PvpIvReferences.IV4U_HOME,
            PvpIvReferences.iv4uUrl("x&bad", 1500))
        assertEquals(PvpIvReferences.IV4U_HOME,
            PvpIvReferences.iv4uUrl(null, 1500))
    }

    @Test fun offlineMasterRanksAndPaginationAreDeterministic() {
        val first = PvpIvDatabase.topSpreads(stats, cpms, 10000, 20)
        val again = PvpIvDatabase.topSpreads(stats, cpms, 10000, 20)
        assertEquals(first, again)
        assertEquals(20, first.size)
        assertEquals(1, first.first().rank)
        assertEquals(15, first.first().attackIv)
        assertEquals(15, first.first().defenseIv)
        assertEquals(15, first.first().hpIv)
        assertEquals(100.0, first.first().percentOfBest, .000001)
        assertTrue(first.zipWithNext().all { (a, b) -> a.statProduct + 1e-7 >= b.statProduct })
        assertEquals(first, PvpIvDatabase.topSpreads(stats, cpms, 10000, 200).take(20))
    }

    @Test fun matchesExistingRankEngineForTopSpread() {
        val first = PvpIvDatabase.topSpreads(stats, cpms, 1500, 1).single()
        val legacy = TeamPvpTools.rank(stats, cpms, 1500,
            first.attackIv, first.defenseIv, first.hpIv)!!
        assertEquals(1, legacy.rank)
        assertEquals(legacy.cp, first.cp)
        assertEquals(legacy.level, first.level, 0.000001)
        assertEquals(legacy.statProduct, first.statProduct, .001)
    }

    @Test fun invalidOrMissingDatabaseDoesNotInventRank() {
        assertTrue(PvpIvDatabase.topSpreads(stats, emptyList(), 1500).isEmpty())
        assertTrue(PvpIvDatabase.topSpreads(stats, cpms, 750).isEmpty())
        assertTrue(PvpIvDatabase.topSpreads(stats, cpms, 1500, 201).isEmpty())
    }

    @Test fun sameSpeciesHigherAttackWinsOnlyConditionalCmp() {
        val ours = PvpIvDatabase.effectiveAttack(stats, 15, 40.0, cpms)
        val theirs = PvpIvDatabase.effectiveAttack(stats, 0, 40.0, cpms)
        assertEquals(PvPIvCmp.FAVORS_OURS,
            PvpIvDatabase.cmpIfSimultaneous(ours, theirs))
        assertEquals(PvPIvCmp.FAVORS_THEIRS,
            PvpIvDatabase.cmpIfSimultaneous(theirs, ours))
        assertEquals(PvPIvCmp.TIE,
            PvpIvDatabase.cmpIfSimultaneous(ours, ours))
        assertEquals(PvPIvCmp.UNKNOWN,
            PvpIvDatabase.cmpIfSimultaneous(null, theirs))
    }
}
