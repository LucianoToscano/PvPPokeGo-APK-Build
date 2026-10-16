package com.lucianotoscano.pvppokego.engine

import org.junit.Assert.*
import org.junit.Test

class EnemyChargedHistoryTest {
    @Test fun remembersMovesPerSlotAndDoesNotConfuseIdenticalSpecies() {
        val h = EnemyChargedHistory(5)
        h.record(0, "A", "BAIT", 1000L)
        h.record(1, "A", "NUKE", 1500L)
        h.record(0, "A", "BAIT", 1501L)
        assertEquals(listOf("BAIT"), h.observedMoves(0, "A"))
        assertEquals(listOf("NUKE"), h.observedMoves(1, "A"))
        h.clear()
        assertTrue(h.observedMoves(0, "A").isEmpty())
    }
}
