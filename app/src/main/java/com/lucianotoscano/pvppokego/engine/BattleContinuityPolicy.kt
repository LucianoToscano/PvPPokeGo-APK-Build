package com.lucianotoscano.pvppokego.engine

import com.lucianotoscano.pvppokego.data.DetectedPokemon

/** Pure boundary rules used to separate a finished match from temporary battle UI gaps. */
internal object BattleContinuityPolicy {
    data class ActiveIdentity(
        val playerName: String?,
        val playerCp: Int?,
        val opponentName: String?,
        val opponentCp: Int?
    )

    data class DetectionIdentity(
        val playerName: String?,
        val playerCp: Int?,
        val opponentName: String?,
        val opponentCp: Int?
    )

    /**
     * Fallback boundary when the team-selection screen was missed.
     *
     * A one-sided identity change is always treated as a normal switch. Only both active
     * identities changing after a meaningful no-battle gap can start a fresh match.
     */
    fun confirmedNewBattleFromPairedCards(
        current: ActiveIdentity,
        detected: DetectionIdentity,
        battleActive: Boolean,
        switchPrompt: Boolean,
        msSinceBattleEvidence: Long
    ): Boolean {
        if (battleActive || switchPrompt || msSinceBattleEvidence < MIN_PAIRED_CARD_BOUNDARY_MS) return false

        val playerChanged = definitelyDifferent(
            current.playerName, current.playerCp,
            detected.playerName, detected.playerCp
        )
        val opponentChanged = definitelyDifferent(
            current.opponentName, current.opponentCp,
            detected.opponentName, detected.opponentCp
        )
        return playerChanged && opponentChanged
    }

    private fun definitelyDifferent(
        oldName: String?,
        oldCp: Int?,
        newName: String?,
        newCp: Int?
    ): Boolean {
        if (oldName.isNullOrBlank() || newName.isNullOrBlank()) return false
        // CP is deliberately ignored as a battle-boundary signal. A cropped frame can
        // turn 1486 into 143/149 and must never split a session by itself.
        return !oldName.equals(newName, ignoreCase = true)
    }

    fun confirmedTeamSelectionBoundary(
        team: List<DetectedPokemon>,
        battleActive: Boolean,
        msSinceBattleEvidence: Long
    ): Boolean {
        if (battleActive || msSinceBattleEvidence < MIN_OUT_OF_BATTLE_MS) return false
        val three = team.take(3)
        return three.size == 3 &&
            three.all { it.cp != null && it.confidence >= MIN_TEAM_CONFIDENCE } &&
            three.map { pokemon ->
                // Form-aware visual identity prevents normal/regional forms with the
                // same display name from collapsing into one team member.
                normalize(pokemon.visualSpeciesId?.takeIf(String::isNotBlank) ?: pokemon.name)
            }.distinct().size == 3
    }

    private fun normalize(v: String): String =
        v.lowercase().replace(Regex("[^a-z0-9]+"), "")

    const val MIN_OUT_OF_BATTLE_MS = 4_500L
    const val MIN_PAIRED_CARD_BOUNDARY_MS = 6_000L
    const val MIN_TEAM_CONFIDENCE = 0.90f
}
