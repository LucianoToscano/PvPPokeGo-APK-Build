package com.lucianotoscano.pvppokego.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReserveScanPolicyTest {
    @Test
    fun activePokemonAloneIsEnoughToStartReserveDiscovery() {
        assertTrue(
            ReserveScanPolicy.shouldScan(
                hasActivePlayer = true,
                knownTeamSize = 1,
                cardCps = listOf(null, null),
                cardDexes = listOf(null, null),
                cardSpeciesIds = listOf(null, null),
                cardMappingConfirmed = listOf(false, false)
            )
        )
    }

    @Test
    fun noActivePokemonDoesNotScanRandomScreenRegions() {
        assertFalse(
            ReserveScanPolicy.shouldScan(
                hasActivePlayer = false,
                knownTeamSize = 1,
                cardCps = listOf(null, null),
                cardDexes = listOf(null, null),
                cardSpeciesIds = listOf(null, null),
                cardMappingConfirmed = listOf(false, false)
            )
        )
    }

    @Test
    fun bothCardsWithCpAndVisualIdentityStopScanning() {
        assertFalse(
            ReserveScanPolicy.shouldScan(
                hasActivePlayer = true,
                knownTeamSize = 3,
                cardCps = listOf(1485, 1494),
                cardDexes = listOf(845, 346),
                cardSpeciesIds = listOf("cramorant", "cradily"),
                cardMappingConfirmed = listOf(true, true)
            )
        )
    }

    @Test
    fun cpWithoutIdentityKeepsVisualScanAlive() {
        assertTrue(
            ReserveScanPolicy.shouldScan(
                hasActivePlayer = true,
                knownTeamSize = 1,
                cardCps = listOf(1485, 1494),
                cardDexes = listOf(null, null),
                cardSpeciesIds = listOf(null, null),
                cardMappingConfirmed = listOf(false, false)
            )
        )
    }
    @Test
    fun fullKnownTeamWithConfirmedNativeCpsDoesNotNeedVisualScan() {
        assertFalse(
            ReserveScanPolicy.shouldScan(
                hasActivePlayer = true,
                knownTeamSize = 3,
                cardCps = listOf(1485, 1494),
                cardDexes = listOf(null, null),
                cardSpeciesIds = listOf(null, null),
                cardMappingConfirmed = listOf(true, true)
            )
        )
    }


    @Test
    fun duplicateReserveCpsKeepScanningUntilVisualEvidenceBindsCards() {
        val team = listOf(
            ReserveCardMatcher.Member(0, "Azumarill", 1498, 184, "azumarill"),
            ReserveCardMatcher.Member(1, "Ninetales", 1490, 38, "ninetales"),
            ReserveCardMatcher.Member(2, "Ninetales (Alolan)", 1490, 38, "ninetales_alolan")
        )
        val cps = listOf(1490, 1490)
        val ambiguous = ReserveCardMatcher.resolve(team, "Azumarill", 1498, cps, listOf(38, 38))
        assertTrue(shouldScanMatches(cps, ambiguous))

        val identified = ReserveCardMatcher.resolve(
            team, "Azumarill", 1498, cps, listOf(38, 38),
            listOf("ninetales_alolan", "ninetales")
        )
        assertFalse(shouldScanMatches(cps, identified))
    }

    @Test
    fun unmatchedNativeCpsDoNotStopDiscoveryForACompleteTeam() {
        val team = listOf(
            ReserveCardMatcher.Member(0, "Azumarill", 1498),
            ReserveCardMatcher.Member(1, "Cradily", 1494),
            ReserveCardMatcher.Member(2, "Cramorant", 1485)
        )
        val cps = listOf(1400, 1401)
        val matches = ReserveCardMatcher.resolve(team, "Azumarill", 1498, cps)
        assertTrue(shouldScanMatches(cps, matches))
    }

    @Test
    fun missingSecondMappingRemainsUnresolved() {
        assertTrue(
            ReserveScanPolicy.shouldScan(
                hasActivePlayer = true,
                knownTeamSize = 3,
                cardCps = listOf(1494, 1485),
                cardDexes = listOf(346, 845),
                cardSpeciesIds = listOf("cradily", "cramorant"),
                cardMappingConfirmed = listOf(true)
            )
        )
    }

    private fun shouldScanMatches(cps: List<Int?>, matches: List<ReserveCardMatcher.Match>): Boolean =
        ReserveScanPolicy.shouldScan(
            hasActivePlayer = true,
            knownTeamSize = 3,
            cardCps = cps,
            cardDexes = listOf(38, 38),
            cardSpeciesIds = listOf(null, null),
            cardMappingConfirmed = (0..1).map { index ->
                matches.any { it.cardIndex == index && it.confirmed }
            }
        )
}

