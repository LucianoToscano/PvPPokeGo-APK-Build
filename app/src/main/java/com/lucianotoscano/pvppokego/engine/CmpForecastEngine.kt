package com.lucianotoscano.pvppokego.engine

import com.lucianotoscano.pvppokego.data.AttackRangeEstimate
import com.lucianotoscano.pvppokego.data.CmpForecast
import com.lucianotoscano.pvppokego.data.CmpOutcome

/** Range-based CMP: hidden-IV overlap remains explicitly uncertain. */
internal object CmpForecastEngine {
    fun evaluate(own: AttackRangeEstimate?, opponent: AttackRangeEstimate?): CmpForecast {
        if (own == null || opponent == null) return CmpForecast(CmpOutcome.UNKNOWN, own, opponent)
        val marginMin = own.minAttack - opponent.maxAttack
        val marginMax = own.maxAttack - opponent.minAttack
        val outcome = when {
            marginMin > 0.0 -> CmpOutcome.WIN
            marginMax < 0.0 -> CmpOutcome.LOSE
            else -> CmpOutcome.UNCERTAIN
        }
        val ownWidth = (own.maxAttack - own.minAttack).coerceAtLeast(.001)
        val oppWidth = (opponent.maxAttack - opponent.minAttack).coerceAtLeast(.001)
        val overlap = (minOf(own.maxAttack, opponent.maxAttack) -
            maxOf(own.minAttack, opponent.minAttack)).coerceAtLeast(0.0)
        val confidence = when (outcome) {
            CmpOutcome.WIN, CmpOutcome.LOSE -> {
                val gap = kotlin.math.abs(if (outcome == CmpOutcome.WIN) marginMin else marginMax)
                (0.82 + (gap / 8.0).coerceAtMost(.17)).toFloat()
            }
            CmpOutcome.UNCERTAIN ->
                (1.0 - overlap / maxOf(ownWidth, oppWidth)).toFloat().coerceIn(.20f, .72f)
            CmpOutcome.UNKNOWN -> 0f
        }
        return CmpForecast(
            outcome = outcome,
            own = own,
            opponent = opponent,
            marginMin = marginMin,
            marginMax = marginMax,
            confidence = confidence.coerceIn(0f, .99f)
        )
    }
}
