package com.lucianotoscano.pvppokego.engine

import org.junit.Assert.*
import org.junit.Test

class OpponentTeamTrackerTest {
    @Test fun tracksThreeSlotsWithoutGuessingUnknownPokemon() {
        val tracker = OpponentTeamTracker()
        assertEquals(0, tracker.observe("Giratina", "giratina_altered", 2497))
        tracker.updateHp(.63f)
        assertEquals(1, tracker.observe("Swampert", "swampert", 2495))
        assertEquals(2, tracker.observe("Charizard", "charizard", 2480))
        val slots = tracker.slots()
        assertEquals(3, slots.size)
        assertEquals("Giratina", slots[0].name)
        assertEquals(2497, slots[0].cp)
        assertEquals(.63f, slots[0].hpRatio!!, .001f)
        assertTrue(slots[2].active)
        assertFalse(slots[0].active)
        assertNull(tracker.observe("Registeel", "registeel", 2496))
        assertEquals("Charizard", tracker.slots()[2].name)
    }

    @Test fun restoresKnownSlotAcrossSwitchAndTracksSameSpeciesWithDistinctCp() {
        val tracker = OpponentTeamTracker()
        assertEquals(0, tracker.observe("Swampert", "swampert", 1497))
        assertEquals(1, tracker.observe("Altaria", "altaria", 1495))
        assertEquals(0, tracker.observe("Swampert", "swampert", 1497))
        assertTrue(tracker.slots()[0].active)
        assertEquals(2, tracker.observe("Swampert", "swampert", 1499))
        assertEquals(1497, tracker.slots()[0].cp)
        assertEquals(1499, tracker.slots()[2].cp)
    }

    @Test fun neverAssignsSecondIdentityFromMissingCpOrWeakEvidence() {
        val tracker = OpponentTeamTracker()
        assertNull(tracker.observe("", null, null))
        assertEquals(0, tracker.observe("Umbreon", "umbreon", null))
        assertEquals(0, tracker.observe("Umbreon", "umbreon", 1500))
        assertEquals(0, tracker.observe("Umbreon", "umbreon", null))
        assertNull(tracker.slots()[1].name)
        tracker.updateHp(0f)
        assertTrue(tracker.slots()[0].fainted)
        tracker.clear()
        assertTrue(tracker.slots().all { it.name == null })
    }
}
