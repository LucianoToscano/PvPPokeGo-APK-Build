package com.lucianotoscano.pvppokego.engine

/**
 * Decides whether fresh screen identity evidence still belongs to the current battle.
 *
 * Conservative by design:
 * - a known member of the current 3-Pokémon team is a switch, never a new battle;
 * - an opponent change alone is a switch;
 * - CP differences never create a new battle because OCR may truncate digits;
 * - a completely incompatible player+opponent pair must repeat on multiple frames.
 *
 * speciesId is preferred over display name so regional/forms can stay distinct when
 * form-aware visual recognition provides that evidence.
 */
internal class BattleCorrespondenceTracker {
    enum class Decision {
        CONTINUE,
        SWITCH_WITHIN_BATTLE,
        /** Keep current engine identity until another frame confirms the change. */
        HOLD_CURRENT,
        NEW_BATTLE,
        INSUFFICIENT
    }

    data class Identity(
        val name: String?,
        val speciesId: String? = null,
        val cp: Int? = null,
        val confidence: Float = 1f
    ) {
        val usable: Boolean
            get() = confidence >= MIN_IDENTITY_CONFIDENCE &&
                (!speciesId.isNullOrBlank() || !name.isNullOrBlank())

        fun stableKey(): String? = when {
            !speciesId.isNullOrBlank() -> normalize(speciesId)
            !name.isNullOrBlank() -> normalize(name)
            else -> null
        }
    }

    data class Context(
        val activePlayer: Identity?,
        val activeOpponent: Identity?,
        val ownTeam: List<Identity>,
        val knownOpponents: List<Identity>,
        val inactiveForMs: Long,
        val sessionAgeMs: Long
    )

    data class Observation(
        val player: Identity?,
        val opponent: Identity?,
        val pairedCards: Boolean,
        val battleTextEvidence: Boolean = false,
        val switchPrompt: Boolean = false
    )

    private var pendingNewSignature: String? = null
    private var pendingNewCount: Int = 0
    private var pendingNewFirstAtMs: Long = 0L
    private var pendingSwitchSignature: String? = null
    private var pendingSwitchCount: Int = 0
    private var pendingSwitchFirstAtMs: Long = 0L

    val hasPendingNewBattleCandidate: Boolean
        get() = pendingNewSignature != null && pendingNewCount > 0

    fun reset() {
        pendingNewSignature = null
        pendingNewCount = 0
        pendingNewFirstAtMs = 0L
        pendingSwitchSignature = null
        pendingSwitchCount = 0
        pendingSwitchFirstAtMs = 0L
    }

    fun observe(
        context: Context,
        observation: Observation,
        nowMs: Long = System.currentTimeMillis()
    ): Decision {
        val player = observation.player?.takeIf { it.usable }
        val opponent = observation.opponent?.takeIf { it.usable }

        if (player == null && opponent == null) {
            clearPendingIfExpired(nowMs)
            return Decision.INSUFFICIENT
        }

        val playerSame = player != null && sameIdentity(player, context.activePlayer)
        val enemySame = opponent != null && sameIdentity(opponent, context.activeOpponent)
        val playerKnownTeam = player != null && context.ownTeam.any { sameIdentity(player, it) }
        val enemyPreviouslySeen = opponent != null && context.knownOpponents.any { sameIdentity(opponent, it) }

        // A player identity outside an already-established own team is almost always
        // transient OCR. Never let one such frame replace the trusted active Pokémon.
        if (
            player != null &&
            context.ownTeam.size >= 2 &&
            !playerSame &&
            !playerKnownTeam &&
            (opponent == null || enemySame)
        ) {
            clearPendingSwitch()
            return Decision.HOLD_CURRENT
        }

        // A brand-new opponent is a valid switch, but require two matching frames unless
        // the game is explicitly showing its switch prompt. Switching back to a previously
        // seen opponent is immediate because its identity/energy state already exists.
        if (
            playerSame &&
            opponent != null &&
            !enemySame &&
            !enemyPreviouslySeen
        ) {
            if (observation.switchPrompt) {
                clearPendingSwitch()
                resetNewCandidateOnly()
                return Decision.SWITCH_WITHIN_BATTLE
            }
            val signature = "enemy:" + opponent.stableKey().orEmpty()
            if (!confirmSwitchCandidate(signature, nowMs)) {
                return Decision.HOLD_CURRENT
            }
            resetNewCandidateOnly()
            return Decision.SWITCH_WITHIN_BATTLE
        }

        // Exact/same identities or a normal one-sided switch are strong continuity.
        if (playerSame && (opponent == null || enemySame || enemyPreviouslySeen)) {
            reset()
            return if (opponent != null && !enemySame) Decision.SWITCH_WITHIN_BATTLE else Decision.CONTINUE
        }
        if (enemySame && (player == null || playerSame || playerKnownTeam)) {
            reset()
            return if (player != null && !playerSame) Decision.SWITCH_WITHIN_BATTLE else Decision.CONTINUE
        }

        // The player's known team is the strongest continuity anchor. Both sides are
        // allowed to change together (simultaneous/faint switch) without splitting history.
        if (playerKnownTeam) {
            reset()
            return if (!playerSame || (opponent != null && !enemySame)) {
                Decision.SWITCH_WITHIN_BATTLE
            } else {
                Decision.CONTINUE
            }
        }

        // Opponent-only identity change is common and must never start a new session.
        if (player == null && opponent != null) {
            reset()
            return Decision.SWITCH_WITHIN_BATTLE
        }

        // A single strange OCR/visual frame is never enough to discard energy/team state.
        if (!observation.pairedCards || player == null || opponent == null) {
            clearPendingIfExpired(nowMs)
            return Decision.INSUFFICIENT
        }

        val bothDifferent =
            !sameIdentity(player, context.activePlayer) &&
                !sameIdentity(opponent, context.activeOpponent)
        if (!bothDifferent) {
            reset()
            return Decision.CONTINUE
        }

        val ownTeamEstablished = context.ownTeam.size >= 2
        val playerIncompatible = ownTeamEstablished &&
            context.ownTeam.none { sameIdentity(player, it) }
        val opponentNew = context.knownOpponents.none { sameIdentity(opponent, it) }

        // Candidate new match:
        // 1) player is incompatible with the already-known team and opponent is new, or
        // 2) after a meaningful visibility gap, both sides are new.
        val newCandidate =
            (playerIncompatible && opponentNew) ||
                (context.inactiveForMs >= LONG_GAP_NEW_MATCH_MS && opponentNew)

        if (!newCandidate) {
            reset()
            return Decision.SWITCH_WITHIN_BATTLE
        }

        val signature = listOf(
            player.stableKey().orEmpty(),
            opponent.stableKey().orEmpty()
        ).joinToString("|")

        if (signature != pendingNewSignature || nowMs - pendingNewFirstAtMs > CANDIDATE_WINDOW_MS) {
            pendingNewSignature = signature
            pendingNewCount = 1
            pendingNewFirstAtMs = nowMs
            return Decision.INSUFFICIENT
        }

        pendingNewCount++
        val requiredFrames = if (
            context.inactiveForMs >= LONG_GAP_NEW_MATCH_MS ||
            context.sessionAgeMs >= MATURE_SESSION_MS
        ) 2 else 3

        if (pendingNewCount >= requiredFrames) {
            reset()
            return Decision.NEW_BATTLE
        }
        return Decision.INSUFFICIENT
    }

    private fun confirmSwitchCandidate(signature: String, nowMs: Long): Boolean {
        if (
            pendingSwitchSignature != signature ||
            pendingSwitchFirstAtMs == 0L ||
            nowMs - pendingSwitchFirstAtMs > SWITCH_CANDIDATE_WINDOW_MS
        ) {
            pendingSwitchSignature = signature
            pendingSwitchCount = 1
            pendingSwitchFirstAtMs = nowMs
            return false
        }
        pendingSwitchCount++
        if (pendingSwitchCount >= SWITCH_CONFIRM_FRAMES) {
            clearPendingSwitch()
            return true
        }
        return false
    }

    private fun clearPendingSwitch() {
        pendingSwitchSignature = null
        pendingSwitchCount = 0
        pendingSwitchFirstAtMs = 0L
    }

    private fun resetNewCandidateOnly() {
        pendingNewSignature = null
        pendingNewCount = 0
        pendingNewFirstAtMs = 0L
    }

    private fun clearPendingIfExpired(nowMs: Long) {
        if (pendingNewFirstAtMs != 0L && nowMs - pendingNewFirstAtMs > CANDIDATE_WINDOW_MS) {
            reset()
        }
    }

    companion object {
        private const val MIN_IDENTITY_CONFIDENCE = 0.78f
        private const val LONG_GAP_NEW_MATCH_MS = 5_500L
        private const val MATURE_SESSION_MS = 20_000L
        private const val CANDIDATE_WINDOW_MS = 3_500L
        private const val SWITCH_CANDIDATE_WINDOW_MS = 2_200L
        private const val SWITCH_CONFIRM_FRAMES = 2

        fun sameIdentity(a: Identity?, b: Identity?): Boolean {
            if (a == null || b == null) return false
            val aSpecies = a.speciesId?.takeIf(String::isNotBlank)
            val bSpecies = b.speciesId?.takeIf(String::isNotBlank)
            if (aSpecies != null && bSpecies != null) {
                return normalize(aSpecies) == normalize(bSpecies)
            }
            val aName = a.name?.takeIf(String::isNotBlank) ?: return false
            val bName = b.name?.takeIf(String::isNotBlank) ?: return false
            return normalize(aName) == normalize(bName)
        }

        private fun normalize(value: String): String =
            value.lowercase().replace(Regex("[^a-z0-9]+"), "")
    }
}
