package com.lucianotoscano.pvppokego.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StableCpTrackerTest {
    @Test
    fun oneTruncatedReadingNeverBecomesTrusted() {
        val tracker = StableCpTracker()
        assertNull(tracker.observe("magcargo", 143))
        assertNull(tracker.observe("magcargo", 1486))
        assertEquals(1486, tracker.observe("magcargo", 1486))
    }

    @Test
    fun oneBadFrameDoesNotReplaceReliableCp() {
        val tracker = StableCpTracker()
        tracker.observe("raichu", 1498)
        assertEquals(1498, tracker.observe("raichu", 1498))
        assertEquals(1498, tracker.observe("raichu", 149))
        assertEquals(1498, tracker.observe("raichu", null))
        assertEquals(1498, tracker.observe("raichu", 149))
    }

    @Test
    fun subjectChangeClearsOldCpInsteadOfContaminatingNewPokemon() {
        val tracker = StableCpTracker()
        tracker.observe("cramorant", 1485)
        assertEquals(1485, tracker.observe("cramorant", 1485))
        assertNull(tracker.observe("rillaboom", null))
        assertNull(tracker.observe("rillaboom", 1500))
        assertEquals(1500, tracker.observe("rillaboom", 1500))
    }
}
