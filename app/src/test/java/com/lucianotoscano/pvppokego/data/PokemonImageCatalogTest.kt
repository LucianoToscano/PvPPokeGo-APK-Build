package com.lucianotoscano.pvppokego.data

import org.junit.Assert.*
import org.junit.Test

class PokemonImageCatalogTest {
    @Test fun completeNationalDexAndNoDuplicateKeys() {
        val images = PokemonImageCatalog.allImages().toList()
        assertEquals(1071, images.size)
        assertEquals(1025, images.count { it.form == "base" })
        assertEquals(46, images.count { it.form.startsWith("mega") })
        assertEquals(images.size, images.map { it.key }.toSet().size)
        assertEquals((1..1025).toList(), images.filter { it.form == "base" }.map { it.dex })
    }

    @Test fun megaHoundoomIsNotBaseHoundoom() {
        assertEquals("0229_mega", PokemonImageCatalog.lookup(229, "houndoom-mega")?.key)
        assertEquals("0229_base", PokemonImageCatalog.lookup(229, "houndoom")?.key)
        assertEquals(10048, PokemonImageCatalog.lookup(229, "houndoom-mega")?.sourceId)
    }

    @Test fun megaXAndYRemainDistinct() {
        assertEquals("0006_mega_x", PokemonImageCatalog.lookup(6, "charizard-mega-x")?.key)
        assertEquals("0006_mega_y", PokemonImageCatalog.lookup(6, "charizard-mega-y")?.key)
        assertEquals("0150_mega_x", PokemonImageCatalog.lookup(150, "mewtwo-mega-x")?.key)
        assertEquals("0150_mega_y", PokemonImageCatalog.lookup(150, "mewtwo-mega-y")?.key)
        assertNull(PokemonImageCatalog.lookup(150, "mewtwo-mega"))
    }

    @Test fun unknownAlternateFormsNeverUseBaseArtwork() {
        assertNull(PokemonImageCatalog.lookup(19, "rattata-alola"))
        assertNull(PokemonImageCatalog.lookup(150, "mewtwo-mega"))
        assertNull(PokemonImageCatalog.lookup(6, null))
        assertNull(PokemonImageCatalog.lookup(1026, "new_pokemon"))
    }

    @Test fun sourceUrlsAreDeterministicAndSecure() {
        val mega = PokemonImageCatalog.lookup(6, "charizard-mega-y")!!
        assertEquals("https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/home/10035.png", mega.url)
        assertTrue(PokemonImageCatalog.allImages().all {
            it.url.startsWith("https://raw.githubusercontent.com/PokeAPI/sprites/")
        })
    }
}
