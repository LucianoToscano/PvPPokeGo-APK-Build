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

    @Test fun alternateFormsKeepTheirExistingExactAtlasArt() {
        assertNull(PokemonImageCatalog.lookup(386, "deoxys_attack"))
        assertNull(PokemonImageCatalog.lookup(386, "deoxys_defense"))
        assertNull(PokemonImageCatalog.lookup(487, "giratina_origin"))
        assertNull(PokemonImageCatalog.lookup(351, "castform_rainy"))
        assertNull(PokemonImageCatalog.lookup(585, "deerling_summer"))
        assertTrue(PokemonImageCatalog.isDistinctForm("deoxys_attack"))
        assertTrue(PokemonImageCatalog.isDistinctForm("charizard_mega_x"))
    }

    @Test fun meganiumIsNotAMegaEvolution() {
        assertFalse(PokemonImageCatalog.isDistinctForm("meganium"))
        assertEquals("0154_base", PokemonImageCatalog.lookup(154, "meganium")?.key)
        assertFalse(PokemonImageCatalog.isDistinctForm("gengar"))
    }

    @Test fun offlineAtlasBaseSelectionPreservesSpecialForms() {
        assertEquals(
            "mewtwo",
            PokemonImageCatalog.inferBaseSpeciesKey(
                listOf("mewtwo", "mewtwo_armored", "mewtwo_mega_x", "mewtwo_mega_y")
            )
        )
        assertEquals(
            "mr_mime",
            PokemonImageCatalog.inferBaseSpeciesKey(listOf("mr_mime", "mr_mime_galarian"))
        )
        assertEquals(
            "pikachu",
            PokemonImageCatalog.inferBaseSpeciesKey(listOf("pikachu", "pikachu_libre", "pikachu_flying"))
        )
        assertNull(PokemonImageCatalog.inferBaseSpeciesKey(
            listOf("giratina_altered", "giratina_origin")
        ))
        assertNull(PokemonImageCatalog.inferBaseSpeciesKey(
            listOf("darmanitan_standard", "darmanitan_zen")
        ))
    }

    @Test fun sourceUrlsAreDeterministicAndSecure() {
        val mega = PokemonImageCatalog.lookup(6, "charizard-mega-y")!!
        assertEquals("https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/home/10035.png", mega.url)
        assertTrue(PokemonImageCatalog.allImages().all {
            it.url.startsWith("https://raw.githubusercontent.com/PokeAPI/sprites/")
        })
    }
}
