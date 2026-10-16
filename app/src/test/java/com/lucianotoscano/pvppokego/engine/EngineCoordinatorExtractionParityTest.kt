package com.lucianotoscano.pvppokego.engine

import com.lucianotoscano.pvppokego.data.MoveDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineCoordinatorExtractionParityTest {
    @Test fun `500 millisecond turn duration uses exact legacy formula`() {
        listOf(1, 2, 3, 4, 5, 6, 7).forEach { turns ->
            assertEquals((turns * 500).toLong().coerceIn(500L, 3500L),
                EnergyAndFastMoveCoordinator.fastDurationMs(false, null, turns, 500))
        }
        assertEquals(1000L, EnergyAndFastMoveCoordinator.ownFastDurationMs(null))
        assertEquals(1000L,
            EnergyAndFastMoveCoordinator.fastDurationMs(true, null, 8, 500))
    }

    @Test fun `charged matcher requires unambiguous normalized substring`() {
        val moves = listOf(
            MoveDef("SHADOW_BALL", "Shadow Ball"),
            MoveDef("THUNDER", "Thunder")
        )
        val labels: (MoveDef) -> Set<String> = { setOf(it.name.lowercase().replace(" ", "")) }
        assertEquals(moves.first(),
            ChargedMoveCoordinator.findMoveMention("usoushadowball", moves, labels))
        assertNull(ChargedMoveCoordinator.findMoveMention("usou???", moves, labels))
    }

    @Test fun `scan policy never overwrites manual team`() {
        val members = listOf(
            OwnTeamSlot(0, "Mewtwo", 1500, "mewtwo", 150, .99f),
            OwnTeamSlot(1, "Charizard", 1499, "charizard", 6, .98f),
            OwnTeamSlot(2, "Gengar", 1498, "gengar", 94, .92f)
        )
        assertEquals(false, TeamAndReserveManager.needsOwnTeamScan(true, members, .90f))
        assertEquals(false, TeamAndReserveManager.needsOwnTeamScan(false, members, .90f))
        assertEquals(true, TeamAndReserveManager.needsOwnTeamScan(false,
            members.map { it.copy(identityConfidence = .70f) }, .90f))
    }
}
