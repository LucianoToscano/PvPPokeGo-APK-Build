package com.lucianotoscano.pvppokego.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PokemonTypeSanitizerTest {
    @Test
    fun rillaboomKeepsOnlyGrass() {
        assertEquals(listOf("grass"), sanitizePokemonTypes(listOf("grass", "none")))
    }

    @Test
    fun cramorantKeepsFlyingAndWater() {
        assertEquals(listOf("flying", "water"), sanitizePokemonTypes(listOf("flying", "water")))
    }

    @Test
    fun blanksDuplicatesAndNoneAreRemoved() {
        assertEquals(listOf("dark", "fire"), sanitizePokemonTypes(listOf("Dark", "", "none", "FIRE", "dark")))
    }
}
