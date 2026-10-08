package com.lucianotoscano.pvppokego.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameMasterModelTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun currentPvpokeMoveFieldsDecodeIncludingBuffChance() {
        val move = json.decodeFromString<MoveDef>(
            """{
              "moveId":"ACID_SPRAY",
              "name":"Acid Spray",
              "type":"poison",
              "power":20,
              "energy":45,
              "energyGain":0,
              "cooldown":500,
              "buffs":[0,-2],
              "buffTarget":"opponent",
              "buffApplyChance":"1",
              "archetype":"Debuff",
              "turns":1
            }"""
        )
        assertEquals(45, move.chargedCost)
        assertEquals(listOf(0,-2), move.buffs)
        assertEquals("opponent", move.buffTarget)
        assertEquals(1f, move.buffChance, .0001f)
        assertEquals("Debuff", move.archetype)
    }

    @Test
    fun pokemonLegalMovepoolsDecodeFromGameMaster() {
        val pokemon = json.decodeFromString<PokemonDef>(
            """{
              "speciesId":"example",
              "speciesName":"Example",
              "types":["water"],
              "fastMoves":["WATER_GUN","MUD_SHOT"],
              "chargedMoves":["AQUA_TAIL","EARTHQUAKE"]
            }"""
        )
        assertEquals(listOf("WATER_GUN","MUD_SHOT"), pokemon.fastMoves)
        assertEquals(listOf("AQUA_TAIL","EARTHQUAKE"), pokemon.chargedMoves)
        assertTrue(pokemon.types.contains("water"))
    }

    @Test
    fun dualTargetBuffFieldsDecodeForObstructShape() {
        val move = json.decodeFromString<MoveDef>(
            """{
              "moveId":"OBSTRUCT",
              "energy":40,
              "buffs":[0,1],
              "buffsSelf":[0,1],
              "buffsOpponent":[0,-1],
              "buffTarget":"both",
              "buffApplyChance":"1"
            }"""
        )
        assertEquals(listOf(0,1), move.buffsSelf)
        assertEquals(listOf(0,-1), move.buffsOpponent)
        assertEquals("both", move.buffTarget)
    }
}
