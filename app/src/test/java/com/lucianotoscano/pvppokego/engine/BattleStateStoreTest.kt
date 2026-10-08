package com.lucianotoscano.pvppokego.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test

class BattleStateStoreTest {
    @Test
    fun exactFormIsRetainedWhenStateIsReused() {
        val store = BattleStateStore()
        val state = store.getOrCreate(
            key = "slot0:ninetalesalolan:1490",
            speciesName = "Ninetales (Alolan)",
            speciesId = "ninetales_alolan"
        )
        state.estimatedEnergy = 47
        state.fastMoveCount = 5

        val same = store.getOrCreate(
            key = "slot0:ninetalesalolan:1490",
            speciesName = "Ninetales (Alolan)",
            speciesId = "ninetales_alolan"
        )

        assertEquals("ninetales_alolan", same.speciesId)
        assertEquals(47, same.estimatedEnergy)
        assertEquals(5, same.fastMoveCount)
    }

    @Test
    fun differentFormsCanKeepIndependentEnergyState() {
        val store = BattleStateStore()
        val normal = store.getOrCreate("enemy:ninetales", "Ninetales", "ninetales")
        val alolan = store.getOrCreate(
            "enemy:ninetalesalolan",
            "Ninetales (Alolan)",
            "ninetales_alolan"
        )
        normal.estimatedEnergy = 30
        alolan.estimatedEnergy = 65

        assertNotSame(normal, alolan)
        assertEquals(30, store.find("enemy:ninetales")?.estimatedEnergy)
        assertEquals(65, store.find("enemy:ninetalesalolan")?.estimatedEnergy)
        assertEquals("ninetales", normal.speciesId)
        assertEquals("ninetales_alolan", alolan.speciesId)
    }

    @Test
    fun laterExactFormEvidenceEnrichesStateWithoutClearingIt() {
        val store = BattleStateStore()
        val initial = store.getOrCreate("slot1:stunfiskgalarian:1495", "Stunfisk (Galarian)")
        initial.minEnergy = 31
        initial.maxEnergy = 47
        initial.fastMoveCount = 4

        val enriched = store.getOrCreate(
            "slot1:stunfiskgalarian:1495",
            "Stunfisk (Galarian)",
            "stunfisk_galarian"
        )

        assertEquals("stunfisk_galarian", enriched.speciesId)
        assertEquals(31, enriched.minEnergy)
        assertEquals(47, enriched.maxEnergy)
        assertEquals(4, enriched.fastMoveCount)
    }

    @Test
    fun predictiveSnapshotSurvivesSwitchStateReuseButNewBattleClearsIt() {
        val store = BattleStateStore()
        val first = store.getOrCreate("enemy:lanturn", "Lanturn", "lanturn")
        first.predictiveEnergySnapshot = com.lucianotoscano.pvppokego.data.EnemyEnergySnapshot(
            hypotheses = listOf(
                com.lucianotoscano.pvppokego.data.EnemyEnergyHypothesisSnapshot(
                    fastMoveId = "SPARK", energy = 36, completedFastMoves = 4, elapsedTurns = 8, weight = 1f
                )
            )
        )
        val returned = store.getOrCreate("enemy:lanturn", "Lanturn", "lanturn")
        assertEquals(36, returned.predictiveEnergySnapshot?.hypotheses?.single()?.energy)

        store.clearBattle()
        assertNull(store.find("enemy:lanturn"))
        val nextBattle = store.getOrCreate("enemy:lanturn", "Lanturn", "lanturn")
        assertNull(nextBattle.predictiveEnergySnapshot)
        assertEquals(0, nextBattle.estimatedEnergy)
    }
}
