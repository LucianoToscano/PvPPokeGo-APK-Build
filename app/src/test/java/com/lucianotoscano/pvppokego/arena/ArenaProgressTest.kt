package com.lucianotoscano.pvppokego.arena

import com.lucianotoscano.pvppokego.data.BattleHistoryEntry
import org.junit.Assert.*
import org.junit.Test

class ArenaProgressTest {
    private fun entry(id: Long, result: String?, league: Int = 1500) =
        BattleHistoryEntry(
            id = id, startedAtEpochMs = id * 10, endedAtEpochMs = id * 10 + 5,
            leagueCp = league, result = result
        )

    @Test fun onlyExplicitResultsAccrueLocalXp() {
        val snapshot = ArenaProgress.fromHistory(listOf(
            entry(1, "Vitória"), entry(2, "Derrota"), entry(3, "Empate"),
            entry(4, null), entry(5, "Possível vitória")
        ))
        assertEquals(1, snapshot.victories)
        assertEquals(1, snapshot.defeats)
        assertEquals(1, snapshot.draws)
        assertEquals(2, snapshot.unresolved)
        assertEquals(50, snapshot.activityXp)
    }

    @Test fun duplicateHistoryIdsNeverEarnTwice() {
        val snapshot = ArenaProgress.fromHistory(listOf(entry(1, "Vitória"), entry(1, "Vitória")))
        assertEquals(25, snapshot.activityXp)
        assertEquals(1, snapshot.recorded)
    }

    @Test fun unresolvedBreaksStreakAndLeaguesMustHaveResult() {
        val snapshot = ArenaProgress.fromHistory(listOf(
            entry(1, "Vitória"), entry(2, "Vitória"), entry(3, null),
            entry(4, "Vitória", 2500), entry(5, "Vitória", 10000)
        ))
        assertEquals(2, snapshot.bestStreak)
        assertEquals(3, snapshot.distinctLeagues)
        assertTrue(snapshot.medals.first().earned)
        assertFalse(snapshot.medals[1].earned)
    }
}
