package com.lucianotoscano.pvppokego.data

import org.junit.Assert.*
import org.junit.Test

/** Source-only tests: user requested NO BUILD / NO TEST EXECUTION in this phase. */
class TrainerToolsTest {
    @Test fun trainerLevelIsOptionalAndBounded() {
        assertTrue(TrainerTools.validLevel(null))
        assertTrue(TrainerTools.validLevel(54))
        assertFalse(TrainerTools.validLevel(0))
        assertFalse(TrainerTools.validLevel(81))
    }

    @Test fun nameGeneratorNeverGuessesUnknownIv() {
        val pokemon = ManualTeamPokemon(speciesId = "ALTARIA", speciesName = "Altaria", cp = 1497)
        assertEquals("Altaria · PC 1497",
            TrainerTools.generatedName(pokemon, PokemonNameStyle.PVP))
        assertNull(TrainerTools.ivSummary(pokemon))
    }

    @Test fun recordedIvsAreFormattedNotRanked() {
        val pokemon = ManualTeamPokemon(
            speciesName = "Altaria", cp = 1497, atkIv = 0, defIv = 14, hpIv = 15
        )
        assertEquals("0/14/15", TrainerTools.ivSummary(pokemon))
        assertEquals("Altaria · 0/14/15 · PC 1497",
            TrainerTools.generatedName(pokemon, PokemonNameStyle.PVP))
    }

    @Test fun emptySpeciesCannotProduceName() {
        assertNull(TrainerTools.generatedName(
            ManualTeamPokemon(cp = 1497), PokemonNameStyle.ORIGINAL))
    }

    @Test fun captureDateFormatsFollowConfiguredPattern() {
        // 2024-01-02 00:00 UTC; use TZ-independent assertions for ISO layout.
        assertEquals(10, TrainerTools.formatDate(1704153600000L, CatchDateDisplay.ISO).length)
        assertEquals(10, TrainerTools.formatDate(1704153600000L, CatchDateDisplay.BRAZIL).length)
        assertEquals(8, TrainerTools.formatDate(1704153600000L, CatchDateDisplay.SHORT).length)
    }

    @Test fun factionStartsUnconfiguredRatherThanUsingScreenshotAsTruth() {
        assertEquals("Não informada", TrainerFaction.UNSET.label)
    }
}
