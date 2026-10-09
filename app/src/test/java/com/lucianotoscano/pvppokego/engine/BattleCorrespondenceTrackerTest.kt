package com.lucianotoscano.pvppokego.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class BattleCorrespondenceTrackerTest {
    private fun id(name: String, cp: Int? = null, speciesId: String? = null, confidence: Float = .95f) =
        BattleCorrespondenceTracker.Identity(name, speciesId, cp, confidence)

    private val team = listOf(
        id("Houndoom", 1499, "houndoom"),
        id("Cramorant", 1485, "cramorant"),
        id("Rillaboom", 1500, "rillaboom")
    )

    @Test
    fun truncatedCpDoesNotSplitSameBattle() {
        val tracker = BattleCorrespondenceTracker()
        val decision = tracker.observe(
            BattleCorrespondenceTracker.Context(
                activePlayer = id("Cramorant", 1485, "cramorant"),
                activeOpponent = id("Magcargo", 1486, "magcargo"),
                ownTeam = team,
                knownOpponents = listOf(id("Magcargo", 1486, "magcargo")),
                inactiveForMs = 300,
                sessionAgeMs = 40_000
            ),
            BattleCorrespondenceTracker.Observation(
                player = id("Cramorant", 148),
                opponent = id("Magcargo", 143),
                pairedCards = true
            ),
            10_000
        )
        assertEquals(BattleCorrespondenceTracker.Decision.CONTINUE, decision)
    }

    @Test
    fun ownKnownPokemonAndNewEnemyAreSwitches() {
        val tracker = BattleCorrespondenceTracker()
        val decision = tracker.observe(
            BattleCorrespondenceTracker.Context(
                activePlayer = team[0],
                activeOpponent = id("Sneasel", 1465, "sneasel"),
                ownTeam = team,
                knownOpponents = listOf(id("Sneasel", 1465, "sneasel")),
                inactiveForMs = 400,
                sessionAgeMs = 30_000
            ),
            BattleCorrespondenceTracker.Observation(
                player = team[2],
                opponent = id("Magcargo", 1486, "magcargo"),
                pairedCards = true
            ),
            20_000
        )
        assertEquals(BattleCorrespondenceTracker.Decision.SWITCH_WITHIN_BATTLE, decision)
    }

    @Test
    fun oneBadFrameCannotCreateNewBattle() {
        val tracker = BattleCorrespondenceTracker()
        val context = BattleCorrespondenceTracker.Context(
            activePlayer = team[0],
            activeOpponent = id("Sneasel", 1465, "sneasel"),
            ownTeam = team,
            knownOpponents = listOf(id("Sneasel", 1465, "sneasel")),
            inactiveForMs = 6_000,
            sessionAgeMs = 45_000
        )
        val first = tracker.observe(
            context,
            BattleCorrespondenceTracker.Observation(
                player = id("Azumarill", 1498, "azumarill"),
                opponent = id("Lanturn", 1497, "lanturn"),
                pairedCards = true
            ),
            30_000
        )
        assertEquals(BattleCorrespondenceTracker.Decision.INSUFFICIENT, first)
    }

    @Test
    fun repeatedIncompatiblePairStartsNewBattle() {
        val tracker = BattleCorrespondenceTracker()
        val context = BattleCorrespondenceTracker.Context(
            activePlayer = team[0],
            activeOpponent = id("Sneasel", 1465, "sneasel"),
            ownTeam = team,
            knownOpponents = listOf(id("Sneasel", 1465, "sneasel")),
            inactiveForMs = 6_000,
            sessionAgeMs = 45_000
        )
        val obs = BattleCorrespondenceTracker.Observation(
            player = id("Azumarill", 1498, "azumarill"),
            opponent = id("Lanturn", 1497, "lanturn"),
            pairedCards = true
        )
        assertEquals(
            BattleCorrespondenceTracker.Decision.INSUFFICIENT,
            tracker.observe(context, obs, 30_000)
        )
        assertEquals(
            BattleCorrespondenceTracker.Decision.NEW_BATTLE,
            tracker.observe(context, obs, 30_700)
        )
    }

    @Test
    fun formAwareIdentityDistinguishesRegionalFormWhenBothAreKnown() {
        val normal = id("Ninetales", 1490, "ninetales")
        val alolan = id("Ninetales (Alolan)", 1490, "ninetales_alolan")
        org.junit.Assert.assertEquals(
            false,
            BattleCorrespondenceTracker.sameIdentity(normal, alolan)
        )
    }

    @Test
    fun lowConfidenceVisualOrOcrEvidenceCannotResetSession() {
        val tracker = BattleCorrespondenceTracker()
        val decision = tracker.observe(
            BattleCorrespondenceTracker.Context(
                activePlayer = team[0],
                activeOpponent = id("Sneasel", 1465, "sneasel"),
                ownTeam = team,
                knownOpponents = listOf(id("Sneasel", 1465, "sneasel")),
                inactiveForMs = 9_000,
                sessionAgeMs = 50_000
            ),
            BattleCorrespondenceTracker.Observation(
                player = id("Azumarill", 1498, "azumarill", .55f),
                opponent = id("Lanturn", 1497, "lanturn", .60f),
                pairedCards = true
            ),
            40_000
        )
        assertEquals(BattleCorrespondenceTracker.Decision.INSUFFICIENT, decision)
    }
    @Test
    fun unknownOwnPokemonIsHeldInsteadOfReplacingTrustedTeam() {
        val tracker = BattleCorrespondenceTracker()
        val decision = tracker.observe(
            BattleCorrespondenceTracker.Context(
                activePlayer = team[0],
                activeOpponent = id("Sneasel", 1465, "sneasel"),
                ownTeam = team,
                knownOpponents = listOf(id("Sneasel", 1465, "sneasel")),
                inactiveForMs = 200,
                sessionAgeMs = 25_000
            ),
            BattleCorrespondenceTracker.Observation(
                player = id("Magcargo", 149, "magcargo"),
                opponent = id("Sneasel", 1465, "sneasel"),
                pairedCards = true
            ),
            50_000
        )
        assertEquals(BattleCorrespondenceTracker.Decision.HOLD_CURRENT, decision)
    }

    @Test
    fun brandNewEnemyNeedsTwoFramesButKnownEnemyReturnsImmediately() {
        val tracker = BattleCorrespondenceTracker()
        val context = BattleCorrespondenceTracker.Context(
            activePlayer = team[0],
            activeOpponent = id("Sneasel", 1465, "sneasel"),
            ownTeam = team,
            knownOpponents = listOf(
                id("Sneasel", 1465, "sneasel"),
                id("Cradily", 1470, "cradily")
            ),
            inactiveForMs = 200,
            sessionAgeMs = 25_000
        )
        val newEnemy = BattleCorrespondenceTracker.Observation(
            player = team[0],
            opponent = id("Magcargo", 1486, "magcargo"),
            pairedCards = true
        )
        assertEquals(
            BattleCorrespondenceTracker.Decision.HOLD_CURRENT,
            tracker.observe(context, newEnemy, 60_000)
        )
        assertEquals(
            BattleCorrespondenceTracker.Decision.SWITCH_WITHIN_BATTLE,
            tracker.observe(context, newEnemy, 60_600)
        )

        val knownEnemy = BattleCorrespondenceTracker.Observation(
            player = team[0],
            opponent = id("Cradily", 1470, "cradily"),
            pairedCards = true
        )
        assertEquals(
            BattleCorrespondenceTracker.Decision.SWITCH_WITHIN_BATTLE,
            tracker.observe(context, knownEnemy, 61_500)
        )
    }

    @Test
    fun sameDisplayNameDifferentFormsRemainDistinctIdentities() {
        val normal = id("Ninetales", 1490, "ninetales")
        val alolan = id("Ninetales", 1488, "ninetales_alolan")
        assertEquals(false, BattleCorrespondenceTracker.sameIdentity(normal, alolan))
    }

    @Test
    fun knownRegionalFormSwitchStaysInsideSameBattle() {
        val tracker = BattleCorrespondenceTracker()
        val formTeam = listOf(
            id("Ninetales", 1490, "ninetales"),
            id("Ninetales (Alolan)", 1488, "ninetales_alolan"),
            id("Azumarill", 1498, "azumarill")
        )
        val context = BattleCorrespondenceTracker.Context(
            activePlayer = formTeam[2],
            activeOpponent = id("Sneasel", 1465, "sneasel"),
            ownTeam = formTeam,
            knownOpponents = listOf(id("Sneasel", 1465, "sneasel")),
            inactiveForMs = 250,
            sessionAgeMs = 35_000
        )
        val observation = BattleCorrespondenceTracker.Observation(
            player = formTeam[1],
            opponent = id("Sneasel", 1465, "sneasel"),
            pairedCards = true
        )

        assertEquals(
            BattleCorrespondenceTracker.Decision.SWITCH_WITHIN_BATTLE,
            tracker.observe(context, observation, 70_000)
        )
    }


    @Test
    fun repeatedIncompatiblePairsDuringContinuousCombatDoNotSplitBattle() {
        val tracker = BattleCorrespondenceTracker()
        val context = BattleCorrespondenceTracker.Context(
            activePlayer = team[0],
            activeOpponent = id("Sneasel", 1465, "sneasel"),
            ownTeam = team,
            knownOpponents = listOf(id("Sneasel", 1465, "sneasel")),
            inactiveForMs = 450,
            sessionAgeMs = 150_000
        )
        val falseNew = BattleCorrespondenceTracker.Observation(
            player = id("Wingull", 875, "wingull"),
            opponent = id("Vanillite", 1182, "vanillite"),
            pairedCards = true
        )
        repeat(4) { frame ->
            assertEquals(BattleCorrespondenceTracker.Decision.HOLD_CURRENT,
                tracker.observe(context, falseNew, 90_000L + frame * 450L))
        }
        assertEquals(false, tracker.hasPendingNewBattleCandidate)
    }

    @Test
    fun opponentOnlyUnknownNeedsTwoFramesButReturningEnemyIsImmediate() {
        val tracker = BattleCorrespondenceTracker()
        val context = BattleCorrespondenceTracker.Context(
            activePlayer = team[0], activeOpponent = id("Sneasel", 1465, "sneasel"),
            ownTeam = team, knownOpponents = listOf(id("Sneasel", 1465, "sneasel")),
            inactiveForMs = 200, sessionAgeMs = 30_000
        )
        val first = BattleCorrespondenceTracker.Observation(
            player = null, opponent = id("Lanturn", 1498, "lanturn"), pairedCards = false
        )
        assertEquals(BattleCorrespondenceTracker.Decision.HOLD_CURRENT,
            tracker.observe(context, first, 100_000))
        assertEquals(BattleCorrespondenceTracker.Decision.SWITCH_WITHIN_BATTLE,
            tracker.observe(context, first, 100_700))
    }

}
