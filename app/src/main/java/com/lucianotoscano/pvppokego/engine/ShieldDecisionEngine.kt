package com.lucianotoscano.pvppokego.engine

import com.lucianotoscano.pvppokego.data.EnemyEnergyForecast
import com.lucianotoscano.pvppokego.data.ShieldAction
import com.lucianotoscano.pvppokego.data.ShieldDecisionForecast

/**
 * Converts the same mathematical threat ranges shown in the HUD into a conservative shield call.
 * Advisory only: it never sends input to Pokemon GO.
 */
internal object ShieldDecisionEngine {
    fun evaluate(
        forecast: EnemyEnergyForecast?,
        hpRatio: Float?,
        shieldsKnown: Boolean,
        shieldsRemaining: Int
    ): ShieldDecisionForecast? {
        val f = forecast ?: return null
        val candidates = f.candidates.filter {
            it.readyPossible || it.turnsRemainingMin <= f.reactionTurns + 1
        }.ifEmpty { f.candidates.take(1) }
        if (candidates.isEmpty()) return null

        val threat = candidates.maxByOrNull { c ->
            val damage = c.damageForecast?.maxPercent ?: 0f
            c.threatScore * 100f + damage
        } ?: return null
        val hpPercent = hpRatio?.times(100f)
        val damage = threat.damageForecast
        val lethalPossible = hpPercent != null && damage != null &&
            damage.maxPercent >= hpPercent - 1f
        val lethalLikely = threat.likelyKo || (hpPercent != null && damage != null &&
            damage.averagePercent >= hpPercent - 1f)
        val baitProbability = candidates.maxOfOrNull { it.baitProbability } ?: 0f

        if (shieldsKnown && shieldsRemaining <= 0) {
            return ShieldDecisionForecast(
                action = ShieldAction.HOLD,
                moveName = threat.move.name,
                confidence = .99f,
                reason = "sem escudos disponíveis",
                baitProbability = baitProbability
            )
        }
        if (lethalLikely && threat.readyPossible) {
            return ShieldDecisionForecast(
                ShieldAction.SHIELD,
                threat.move.name,
                confidence = (.84f + threat.confidence * .15f).coerceAtMost(.99f),
                reason = "dano provável alcança o HP atual",
                baitProbability = baitProbability
            )
        }
        if (lethalPossible && threat.readyPossible) {
            return ShieldDecisionForecast(
                ShieldAction.SHIELD,
                threat.move.name,
                confidence = (.68f + threat.confidence * .20f).coerceAtMost(.92f),
                reason = "faixa de dano contém KO",
                baitProbability = baitProbability
            )
        }
        if (baitProbability >= .48f && !threat.readyCertain) {
            return ShieldDecisionForecast(
                ShieldAction.OPTIONAL,
                threat.move.name,
                confidence = (.55f + f.consistencyScore * .20f).coerceAtMost(.82f),
                reason = "bait matematicamente plausível",
                baitProbability = baitProbability
            )
        }
        val maxDamage = damage?.maxPercent ?: 0f
        if (maxDamage in 0.1f..18f && !threat.likelyKo) {
            return ShieldDecisionForecast(
                ShieldAction.HOLD,
                threat.move.name,
                confidence = (.64f + threat.confidence * .18f).coerceAtMost(.88f),
                reason = "dano previsto baixo",
                baitProbability = baitProbability
            )
        }
        if (threat.threatScore >= .76f && threat.readyPossible) {
            return ShieldDecisionForecast(
                ShieldAction.SHIELD,
                threat.move.name,
                confidence = (.62f + threat.confidence * .24f).coerceAtMost(.91f),
                reason = "pressão combinada de energia e dano alta",
                baitProbability = baitProbability
            )
        }
        return ShieldDecisionForecast(
            ShieldAction.OPTIONAL,
            threat.move.name,
            confidence = (.48f + threat.confidence * .22f).coerceAtMost(.78f),
            reason = "ameaça moderada ou ainda incerta",
            baitProbability = baitProbability
        )
    }
}
