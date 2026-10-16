package com.lucianotoscano.pvppokego.engine

/**
 * Deterministic guardrails for tactical heuristics. No player behaviour is certain.
 * All damage and energy inputs must come from the established trackers, not screen taps.
 */
internal object EnemyMovePredictor {
    /** Bait means a cheap non-lethal move used to induce a shield. */
    fun baitHeuristic(
        baseProbability: Float,
        cheapMovePreviouslyObserved: Int,
        expensiveMoveAffordable: Boolean,
        cheapMoveLethal: Boolean,
        likelyEnemyEnergy: Int,
        enemyHpRatio: Float?
    ): Float {
        if (!expensiveMoveAffordable || cheapMoveLethal) return 0f
        val historyAdjustment = when {
            cheapMovePreviouslyObserved >= 3 -> .24f
            cheapMovePreviouslyObserved == 2 -> .20f
            cheapMovePreviouslyObserved == 1 -> .12f
            else -> 0f
        }
        val highEnergyPenalty = if (likelyEnemyEnergy >= 85) .15f else 0f
        val criticalHpPenalty = if (enemyHpRatio != null && enemyHpRatio <= .15f) .15f else 0f
        // Bounded heuristic, NEVER display as 100% certainty.
        return (baseProbability.coerceIn(0f, 1f) + historyAdjustment - highEnergyPenalty - criticalHpPenalty)
            .coerceIn(0f, .85f)
    }

    /** Impossible to infer an exact throw/catch window without both animation phases. */
    fun possibleOpponentThrowBoundary(enemyFastTurns: Int, turnsSinceEnemyCompletion: Int): Boolean =
        enemyFastTurns in 1..5 && turnsSinceEnemyCompletion >= 0 &&
            turnsSinceEnemyCompletion % enemyFastTurns == 0
}
