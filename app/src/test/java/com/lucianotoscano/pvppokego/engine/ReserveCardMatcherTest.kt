package com.lucianotoscano.pvppokego.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReserveCardMatcherTest {
    private val team = listOf(
        ReserveCardMatcher.Member(0, "Houndoom", 1499, 229, "houndoom"),
        ReserveCardMatcher.Member(1, "Cramorant", 1485, 845, "cramorant"),
        ReserveCardMatcher.Member(2, "Rillaboom", 1500, 812, "rillaboom")
    )

    @Test
    fun cpPinsCramorantAndRillaboomToCorrectCards() {
        val r = ReserveCardMatcher.resolve(team, "Houndoom", 1499, listOf(1485, 1500))
        assertEquals(listOf("Cramorant", "Rillaboom"), r.map { it.member.name })
        assertTrue(r.all { it.confirmed })
    }

    @Test
    fun afterSwitchCpStillPinsCardsInsteadOfCircularGuess() {
        val r = ReserveCardMatcher.resolve(team, "Cramorant", 1485, listOf(1499, 1500))
        assertEquals(listOf("Houndoom", "Rillaboom"), r.map { it.member.name })
        assertTrue(r.all { it.confirmed })
    }

    @Test
    fun oneCpConfirmsSecondCardByElimination() {
        val r = ReserveCardMatcher.resolve(team, "Houndoom", 1499, listOf(1485, null))
        assertEquals(listOf("Cramorant", "Rillaboom"), r.map { it.member.name })
        assertTrue(r.all { it.confirmed })
    }

    @Test
    fun imageCanRecoverReserveIdentityWhenCpIsUnreadable() {
        val r = ReserveCardMatcher.resolve(
            team = team,
            activeName = "Houndoom",
            activeCp = 1499,
            cardCps = listOf(null, null),
            cardDexes = listOf(845, 812)
        )
        assertEquals(listOf("Cramorant", "Rillaboom"), r.map { it.member.name })
        assertEquals(listOf("image", "image"), r.map { it.source })
        assertTrue(r.all { it.confirmed })
    }

    @Test
    fun cpWinsEvenWhenVisualEvidenceConflicts() {
        val r = ReserveCardMatcher.resolve(
            team = team,
            activeName = "Houndoom",
            activeCp = 1499,
            cardCps = listOf(1485, 1500),
            cardDexes = listOf(812, 845)
        )
        assertEquals(listOf("Cramorant", "Rillaboom"), r.map { it.member.name })
        assertEquals(listOf("cp", "cp"), r.map { it.source })
        assertTrue(r.all { it.confirmed })
    }

    @Test
    fun missingCpDoesNotPretendIdentityIsConfirmed() {
        val r = ReserveCardMatcher.resolve(team, "Houndoom", 1499, listOf(null, null))
        assertEquals(listOf("Cramorant", "Rillaboom"), r.map { it.member.name })
        assertFalse(r[0].confirmed)
        assertFalse(r[1].confirmed)
    }
    @Test
    fun exactVisualFormBeatsAmbiguousSharedDex() {
        val formTeam = listOf(
            ReserveCardMatcher.Member(0, "Stunfisk", 1490, 618, "stunfisk"),
            ReserveCardMatcher.Member(1, "Stunfisk (Galarian)", 1495, 618, "stunfisk_galarian"),
            ReserveCardMatcher.Member(2, "Cramorant", 1485, 845, "cramorant")
        )
        val r = ReserveCardMatcher.resolve(
            team = formTeam,
            activeName = "Cramorant",
            activeCp = 1485,
            cardCps = listOf(null, null),
            cardDexes = listOf(618, 618),
            cardSpeciesIds = listOf("stunfisk_galarian", "stunfisk")
        )
        assertEquals(listOf("Stunfisk (Galarian)", "Stunfisk"), r.map { it.member.name })
        assertEquals(listOf("image-form", "image-form"), r.map { it.source })
        assertTrue(r.all { it.confirmed })
    }

    @Test
    fun dexOnlySharedFormStaysUnconfirmedUntilAnotherSignalExists() {
        val formTeam = listOf(
            ReserveCardMatcher.Member(0, "Stunfisk", 1490, 618, "stunfisk"),
            ReserveCardMatcher.Member(1, "Stunfisk (Galarian)", 1495, 618, "stunfisk_galarian"),
            ReserveCardMatcher.Member(2, "Cramorant", 1485, 845, "cramorant")
        )
        val r = ReserveCardMatcher.resolve(
            team = formTeam,
            activeName = "Cramorant",
            activeCp = 1485,
            cardCps = listOf(null, null),
            cardDexes = listOf(618, 618)
        )
        assertFalse(r[0].confirmed)
        assertFalse(r[1].confirmed)
    }

    @Test
    fun formAwareVisualEvidenceDistinguishesSameDexForms() {
        val forms = listOf(
            ReserveCardMatcher.Member(0, "Ninetales", 1490, 38, "ninetales"),
            ReserveCardMatcher.Member(1, "Ninetales (Alolan)", 1488, 38, "ninetales_alolan"),
            ReserveCardMatcher.Member(2, "Azumarill", 1498, 184, "azumarill")
        )
        val result = ReserveCardMatcher.resolve(
            team = forms,
            activeName = "Azumarill",
            activeCp = 1498,
            cardCps = listOf(null, null),
            cardDexes = listOf(38, 38),
            cardSpeciesIds = listOf("ninetales_alolan", "ninetales")
        )
        assertEquals(listOf("Ninetales (Alolan)", "Ninetales"), result.map { it.member.name })
        assertTrue(result.all { it.confirmed })
        assertEquals(listOf("image-form", "image-form"), result.map { it.source })
    }

    @Test
    fun oneKnownReserveCanStillMatchSecondNativeCard() {
        val partialTeam = listOf(
            ReserveCardMatcher.Member(0, "Houndoom", 1499, 229, "houndoom"),
            ReserveCardMatcher.Member(1, "Cradily", 1494, 346, "cradily")
        )

        val result = ReserveCardMatcher.resolve(
            team = partialTeam,
            activeName = "Houndoom",
            activeCp = 1499,
            cardCps = listOf(1485, 1494),
            cardDexes = listOf(null, 346),
            cardSpeciesIds = listOf(null, "cradily")
        )

        assertEquals(1, result.size)
        assertEquals("Cradily", result.single().member.name)
        assertEquals(1, result.single().cardIndex)
        assertTrue(result.single().confirmed)
    }


}
