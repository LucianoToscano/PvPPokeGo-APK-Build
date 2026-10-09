package com.lucianotoscano.pvppokego.data

import org.junit.Assert.*
import org.junit.Test

class ManualTeamPolicyTest {
    @Test fun emptyRosterAlwaysHasThreeSlotsAndIsNotReady() {
        val roster = ManualTeamPolicy.sanitize(ManualTeamRoster(slots = emptyList()))
        assertEquals(3, roster.slots.size)
        assertEquals(0, ManualTeamPolicy.completeCount(roster, 1500))
        assertFalse(ManualTeamPolicy.canPrepare(roster, 1500))
    }

    @Test fun cpLimitDependsOnSelectedLeague() {
        val roster = ManualTeamRoster(slots = listOf(
            ManualTeamPokemon(speciesId = "ALTARIA", cp = 1497),
            ManualTeamPokemon(speciesId = "HOUNDOOM", cp = 1499),
            ManualTeamPokemon(speciesId = "RILLABOOM", cp = 1500)
        ))
        assertTrue(ManualTeamPolicy.canPrepare(roster, 1500))
        assertTrue(ManualTeamPolicy.canPrepare(roster, 2500))
        assertEquals(2, ManualTeamPolicy.completeCount(roster, 1499))
    }

    @Test fun invalidStatsDoNotBecomeFakeValidValues() {
        val result = ManualTeamPolicy.sanitize(ManualTeamRoster(slots = listOf(
            ManualTeamPokemon(speciesId = " ALTARIA ", cp = 99999, atkIv = 19,
                defIv = -1, hpIv = 15, maxHp = -10, gender = "INVALID")
        ))).slots.first()
        assertEquals("ALTARIA", result.speciesId)
        assertNull(result.cp)
        assertNull(result.atkIv)
        assertNull(result.defIv)
        assertEquals(15, result.hpIv)
        assertNull(result.maxHp)
        assertEquals("UNKNOWN", result.gender)
    }

    @Test fun missingSpeciesCannotBeMarkedReady() {
        val roster = ManualTeamRoster(slots = listOf(
            ManualTeamPokemon(cp = 1490),
            ManualTeamPokemon(speciesId = "ALTARIA", cp = 1495),
            ManualTeamPokemon(speciesId = "RILLABOOM", cp = 1500)
        ))
        assertEquals(2, ManualTeamPolicy.completeCount(roster, 1500))
        assertFalse(ManualTeamPolicy.canPrepare(roster, 1500))
    }

    @Test fun optionalFieldsDoNotBlockTeamPreparation() {
        val roster = ManualTeamRoster(slots = List(3) { i ->
            ManualTeamPokemon(speciesId = "species-$i", cp = 1200 + i)
        })
        assertTrue(ManualTeamPolicy.canPrepare(roster, 1500))
    }
}
