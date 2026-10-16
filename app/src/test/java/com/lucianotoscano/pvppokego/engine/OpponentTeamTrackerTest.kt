package com.lucianotoscano.pvppokego.engine

import org.junit.Assert.*
import org.junit.Test

class OpponentTeamTrackerTest {
    @Test fun `switching restores stable slots and HP`() {
        val team = OpponentTeamTracker()
        assertEquals(0, team.observe("Charizard", "charizard", 1500, nowMs = 1000L))
        team.updateHp(.56f, 1010L)
        assertEquals(1, team.observe("Gengar", "gengar", 1499, nowMs = 1100L))
        team.updateHp(.32f, 1110L)
        assertEquals(0, team.observe("Charizard", "charizard", 1500, nowMs = 1200L))
        assertEquals(.56f, team.slots()[0].hpRatio!!, 0.00001f)
        assertEquals(.32f, team.slots()[1].hpRatio!!, 0.00001f)
        assertTrue(team.slots()[0].active)
        assertFalse(team.slots()[1].active)
    }

    @Test fun `same species separate CP never overwrites known slot`() {
        val team = OpponentTeamTracker()
        assertEquals(0, team.observe("Gengar", "gengar", 1500))
        assertEquals(1, team.observe("Gengar", "gengar", 1498))
        assertEquals(0, team.observe("Gengar", "gengar", 1500))
        assertEquals(null, team.observe("Gengar", "gengar", null))
        assertEquals(2, team.slots().count { it.name != null })
    }

    @Test fun `reset wipes opponent histories`() {
        val team = OpponentTeamTracker()
        team.observe("Mewtwo", "mewtwo", 1500)
        team.updateHp(.01f)
        team.clear()
        assertNull(team.activeSlot())
        assertTrue(team.slots().all { it.name == null && it.hpRatio == null && it.lastSeenAtMs == 0L })
    }

    @Test fun `untrusted frames cannot overwrite team`() {
        val team = OpponentTeamTracker()
        team.observe("Mewtwo", "mewtwo", 1500, confidence = .9f)
        assertNull(team.observe("Noise", "noise", 1520, confidence = .49f))
        assertEquals("Mewtwo", team.slots()[0].name)
        assertTrue(team.slots()[0].active)
    }

    @Test fun `fourth slot refused without discarding other members`() {
        val team = OpponentTeamTracker()
        listOf("one", "two", "three").forEachIndexed { i, n ->
            assertEquals(i, team.observe(n, n, 1000 + i))
        }
        assertNull(team.observe("four", "four", 1004))
        assertEquals(listOf("one", "two", "three"), team.slots().map { it.name })
    }

    @Test fun `fourth rejected identity invalidates active HP target and recovers`() {
        val team = OpponentTeamTracker()
        listOf("one", "two", "three").forEachIndexed { i, name ->
            team.observe(name, name, 1450 + i, nowMs = 100L + i)
        }
        team.updateHp(.71f, 120L)
        val before = team.slots()
        assertNull(team.observe("four", "four", 1499, nowMs = 130L))
        assertNull(team.activeSlot())
        team.updateHp(.25f, 140L)
        assertEquals(before.map { it.hpRatio }, team.slots().map { it.hpRatio })
        assertEquals(before.map { it.lastSeenAtMs }, team.slots().map { it.lastSeenAtMs })
        assertTrue(team.slots().none { it.active })
        assertEquals(2, team.observe("three", "three", 1452, nowMs = 150L))
        team.updateHp(.35f, 160L)
        assertEquals(.35f, team.slots()[2].hpRatio!!, .00001f)
    }

    @Test fun `two duplicate species without CP must not receive other slot HP`() {
        val team = OpponentTeamTracker()
        team.observe("Gengar", "gengar", 1490)
        team.observe("Gengar", "gengar", 1499)
        team.updateHp(.91f)
        val before = team.slots()
        assertNull(team.observe("Gengar", "gengar", null))
        assertNull(team.activeSlot())
        team.updateHp(.20f)
        assertEquals(before.map { it.hpRatio }, team.slots().map { it.hpRatio })
        assertEquals(0, team.observe("Gengar", "gengar", 1490))
    }

    @Test fun `exact name and CP can upgrade OCR species to regional form`() {
        val team = OpponentTeamTracker()
        assertEquals(0, team.observe("Ninetales", null, 1480))
        assertEquals(0, team.observe("Ninetales", "ninetales_alolan", 1480))
        assertEquals("ninetales_alolan", team.slots()[0].speciesId)
        assertEquals(1, team.slots().count { it.name != null })
    }

}
