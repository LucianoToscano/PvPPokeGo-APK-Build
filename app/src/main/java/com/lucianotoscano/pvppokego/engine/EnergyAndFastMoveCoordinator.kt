package com.lucianotoscano.pvppokego.engine

import com.lucianotoscano.pvppokego.data.MoveDef

/** Stable duration math; the facade still owns mutable frame/turn bookkeeping. */
internal object EnergyAndFastMoveCoordinator {
    fun ownFastDurationMs(fast: MoveDef?): Long =
        fast?.cooldown?.toLong()?.coerceIn(500L, 3_500L) ?: 1_000L

    fun fastDurationMs(
        manualOrOnlyHypothesis: Boolean,
        currentFast: MoveDef?,
        likelyFastTurns: Int,
        turnMs: Int
    ): Long = if (manualOrOnlyHypothesis) ownFastDurationMs(currentFast) else
        (likelyFastTurns * turnMs).toLong().coerceIn(500L, 3_500L)
}
