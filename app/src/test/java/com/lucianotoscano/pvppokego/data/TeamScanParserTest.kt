package com.lucianotoscano.pvppokego.data

import org.junit.Assert.*
import org.junit.Test

class TeamScanParserTest {
    private val species = listOf(
        PokemonDef("cramorant", "Cramorant", fastMoves = listOf("peck"),
            chargedMoves = listOf("dive", "fly")),
        PokemonDef("altaria", "Altaria", fastMoves = listOf("dragon_breath"),
            chargedMoves = listOf("sky_attack"))
    )
    private val moves = listOf(
        MoveDef("peck", "Peck", energyGain = 6),
        MoveDef("dive", "Dive", energy = 50),
        MoveDef("fly", "Fly", energy = 80)
    )
    private val pt = mapOf("peck" to "Bicada", "dive" to "Mergulho", "fly" to "Voar")

    @Test fun readsCramorantDetailWithoutInventingNicknameIvs() {
        val x = TeamScanParser.parse(
            "PC1485\nCramorant 6/9/14\n126 / 126 PS\nBicada 6\nMergulho 50\nVoar 80",
            species, moves, pt
        )
        assertEquals("cramorant", x.speciesId)
        assertEquals(1485, x.cp)
        assertEquals(126, x.maxHp)
        assertEquals("peck", x.fastMoveId)
        assertEquals("dive", x.chargedMove1Id)
        assertEquals("fly", x.chargedMove2Id)
        assertNull(x.gender)
    }

    @Test fun ignoresNicknameNumbersAsHitpointsAndStardustAsCp() {
        val x = TeamScanParser.parse("Cramorant 6/9/14\n740.249 poeira\n78 doces", species, moves, pt)
        assertEquals("cramorant", x.speciesId)
        assertNull(x.cp)
        assertNull(x.maxHp)
    }

    @Test fun doesNotAcceptMoveOutsideSelectedSpecies() {
        val x = TeamScanParser.parse("PC 1499\nAltaria\nMergulho\nVoar", species, moves, pt)
        assertEquals("altaria", x.speciesId)
        assertNull(x.fastMoveId)
        assertNull(x.chargedMove1Id)
    }
}
