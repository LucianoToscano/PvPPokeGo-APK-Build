package com.lucianotoscano.pvppokego.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeciesCpPlausibilityPolicyTest {
    private val cpms = List(100) { 0.1 + it / 150.0 } + 0.845300018787384

    @Test fun rejectsImpossiblePokemonNamesFromActual0533History() {
        val wingull = PokemonBaseStats(atk = 106, def = 61, hp = 120)
        val vanillite = PokemonBaseStats(atk = 118, def = 106, hp = 113)
        assertEquals(875, SpeciesCpPlausibilityPolicy.maximumCp(wingull, cpms))
        assertEquals(1182, SpeciesCpPlausibilityPolicy.maximumCp(vanillite, cpms))
        assertFalse(SpeciesCpPlausibilityPolicy.isPlausible(1485, wingull, cpms))
        assertFalse(SpeciesCpPlausibilityPolicy.isPlausible(1485, vanillite, cpms))
    }

    @Test fun preservesLegitimateCramorantAt1485() {
        val cramorant = PokemonBaseStats(atk = 173, def = 163, hp = 172)
        assertEquals(2450, SpeciesCpPlausibilityPolicy.maximumCp(cramorant, cpms))
        assertTrue(SpeciesCpPlausibilityPolicy.isPlausible(1485, cramorant, cpms))
        assertTrue(SpeciesCpPlausibilityPolicy.isPlausible(null, cramorant, cpms))
    }

    @Test fun doesNotRejectSpeciesWhenOfflineBaseStatsUnavailable() {
        assertTrue(SpeciesCpPlausibilityPolicy.isPlausible(
            1500, PokemonBaseStats(), emptyList()
        ))
        assertFalse(SpeciesCpPlausibilityPolicy.isPlausible(
            11000, PokemonBaseStats(), emptyList()
        ))
    }
}
