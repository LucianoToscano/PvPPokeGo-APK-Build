package com.lucianotoscano.pvppokego.engine

/**
 * Decides whether a reserve can safely receive a matchup classification.
 *
 * Native-card mapping and Pokémon identity are different questions. We may know with
 * high confidence that Cradily is one of our two reserves before knowing whether it is
 * currently the upper or lower native card. That uncertainty must not erase its types
 * or turn a known matchup into UNKNOWN.
 */
internal object ReserveMatchupPolicy {
    const val MIN_STABLE_TEAM_IDENTITY_CONFIDENCE = 0.76f

    fun classifyScore(score: Double): com.lucianotoscano.pvppokego.data.MatchupState = when {
        score >= 0.16 -> com.lucianotoscano.pvppokego.data.MatchupState.FAVORABLE
        score <= -0.16 -> com.lucianotoscano.pvppokego.data.MatchupState.UNFAVORABLE
        else -> com.lucianotoscano.pvppokego.data.MatchupState.NEUTRAL
    }

    fun canEvaluate(
        speciesId: String?,
        identityConfidence: Float,
        types: List<String>,
        enemyTypes: List<String>,
        cardMappingConfirmed: Boolean
    ): Boolean {
        if (types.isEmpty() || enemyTypes.isEmpty()) return false
        if (cardMappingConfirmed) return true
        return !speciesId.isNullOrBlank() &&
            identityConfidence >= MIN_STABLE_TEAM_IDENTITY_CONFIDENCE
    }
}
