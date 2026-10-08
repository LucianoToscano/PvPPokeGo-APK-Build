package com.lucianotoscano.pvppokego.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BattleHistorySegmentMatcherTest {
    private fun battle(
        id: Long,
        start: Long,
        end: Long,
        player: String = "Cramorant",
        playerCp: Int = 1485,
        enemy: String = "Golisopod",
        enemyCp: Int = 1499,
        startedMid: Boolean = false,
        endReason: String? = "Sem evidência de batalha por 12s",
        result: String? = null,
        events: List<BattleHistoryEvent> = emptyList()
    ) = BattleHistoryEntry(
        id=id, startedAtEpochMs=start, endedAtEpochMs=end, leagueCp=1500,
        playerName=player, playerCp=playerCp, opponentName=enemy, opponentCp=enemyCp,
        startedMidBattle=startedMid, endReason=endReason, result=result, events=events
    )

    @Test
    fun immediateMidBattleFragmentWithSameOpponentMerges() {
        val a = battle(1, 1_000, 50_000)
        val b = battle(2, 52_000, 80_000, startedMid=true)
        assertTrue(BattleHistorySegmentMatcher.shouldMerge(a,b))
    }

    @Test
    fun confirmedResultNeverMergesIntoNextMatch() {
        val a = battle(1, 1_000, 50_000, result="Vitória", endReason="Resultado identificado na tela")
        val b = battle(2, 52_000, 80_000, startedMid=true)
        assertFalse(BattleHistorySegmentMatcher.shouldMerge(a,b))
    }

    @Test
    fun quickNormalRematchWithoutMidBattleEvidenceStaysSeparate() {
        val a = battle(1, 1_000, 50_000, endReason="Partida encerrada")
        val b = battle(2, 55_000, 90_000, startedMid=false)
        assertFalse(BattleHistorySegmentMatcher.shouldMerge(a,b))
    }

    @Test
    fun mergedTimelineKeepsAbsoluteElapsedOrder() {
        val a = battle(
            1, 1_000, 50_000,
            events=listOf(BattleHistoryEvent(10_000,"VOCÊ","RÁPIDO","Bicada"))
        )
        val b = battle(
            2, 52_000, 80_000, startedMid=true,
            events=listOf(BattleHistoryEvent(0,"INIMIGO","CARREGADO","Aqua Jato"))
        )
        val merged = BattleHistorySegmentMatcher.merge(a,b)
        val charged = merged.events.first { it.category == "CARREGADO" }
        assertEquals(51_000L, charged.elapsedMs)
        assertEquals(1L, merged.id)
    }
}
