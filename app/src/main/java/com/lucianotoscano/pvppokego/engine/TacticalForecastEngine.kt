package com.lucianotoscano.pvppokego.engine

import com.lucianotoscano.pvppokego.data.EnemyEnergyForecast
import com.lucianotoscano.pvppokego.data.FastMovePhaseSnapshot
import com.lucianotoscano.pvppokego.data.FarmDownForecast
import com.lucianotoscano.pvppokego.data.OptimalThrowForecast
import com.lucianotoscano.pvppokego.data.ReserveState
import com.lucianotoscano.pvppokego.data.SacSwapForecast
import com.lucianotoscano.pvppokego.data.TeamPokemonStatus
import kotlin.math.ceil

internal object TacticalForecastEngine {
    fun farmDown(
        ownFastDamagePercent: Float?,
        enemyHpRatio: Float?,
        ownFastTurns: Int,
        enemyForecast: EnemyEnergyForecast?
    ): FarmDownForecast? {
        val damage = ownFastDamagePercent?.takeIf { it > 0f } ?: return null
        val hpPercent = enemyHpRatio?.times(100f)?.takeIf { it > 0f } ?: return null
        val hits = ceil(hpPercent / damage).toInt().coerceAtLeast(1)
        val turns = hits * ownFastTurns.coerceAtLeast(1)
        val fastKoRisk = enemyForecast?.candidates?.any { it.mayKoBeforeCharged } == true
        val threatTurns = enemyForecast?.candidates
            ?.minOfOrNull { if (it.readyPossible) 0 else it.turnsRemainingMin }
        val safe = !fastKoRisk && (threatTurns == null || turns < threatTurns)
        val confidence = (
            .45f +
                (enemyForecast?.consistencyScore ?: .55f) * .25f +
                (enemyForecast?.confidence ?: .45f) * .20f +
                (if (safe) .08f else 0f)
            ).coerceIn(0f, .95f)
        return FarmDownForecast(safe, hits, turns, threatTurns, confidence)
    }

    /**
     * Conservative optimal-throw timing. Only used when enemy Fast phase is well observed.
     * Target = launch Charged with one enemy Fast turn left before registration.
     */
    fun optimalThrow(
        phase: FastMovePhaseSnapshot?,
        ownFastTurns: Int,
        ownEnergy: Int,
        ownFastEnergyGain: Int,
        chargedCost: Int
    ): OptimalThrowForecast? {
        val p = phase ?: return null
        if (p.confidence < .62f || chargedCost <= 0 || ownEnergy < chargedCost) return null
        val enemyTurns = p.moveTurns.coerceAtLeast(1)
        val ownTurns = ownFastTurns.coerceAtLeast(1)
        var bestK = 0
        var bestPenalty = Int.MAX_VALUE
        for (k in 0..4) {
            val elapsedTurns = k * ownTurns
            val currentOffset = enemyTurns - p.turnsUntilRegistration
            val offsetAfter = (currentOffset + elapsedTurns) % enemyTurns
            val untilRegistration = enemyTurns - offsetAfter
            val timingPenalty = kotlin.math.abs(untilRegistration - 1) * 10 + k
            val projectedEnergy = (ownEnergy + k * ownFastEnergyGain).coerceAtMost(100)
            val overflowPenalty = if (ownEnergy + k * ownFastEnergyGain > 100) 12 else 0
            val affordabilityPenalty = if (projectedEnergy < chargedCost) 100 else 0
            val total = timingPenalty + overflowPenalty + affordabilityPenalty
            if (total < bestPenalty) {
                bestPenalty = total
                bestK = k
            }
        }
        return OptimalThrowForecast(
            waitFastMoves = bestK,
            confidence = (p.confidence * if (bestK <= 2) .92f else .80f).coerceIn(0f, .92f),
            reason = if (bestK == 0) "janela de timing favorável agora"
            else "alinhar Charged ao último turno do Fast inimigo"
        )
    }

    fun sacSwap(
        forecast: EnemyEnergyForecast?,
        currentTypes: List<String>,
        reserves: List<ReserveState>,
        switchSeconds: Int?
    ): SacSwapForecast? {
        if (switchSeconds != null) return null
        val f = forecast ?: return null
        val threat = f.candidates.firstOrNull {
            it.readyPossible || it.turnsRemainingMin <= f.reactionTurns + 1
        } ?: return null
        if (!threat.likelyKo && (threat.damageForecast?.maxPercent ?: 0f) < 45f) return null
        val currentEff = TypeChart.multiplier(threat.move.type, currentTypes).coerceAtLeast(.01)
        val candidates = reserves.filter {
            it.visible && it.identityConfirmed && it.status != TeamPokemonStatus.FAINTED &&
                (it.hpRatio == null || it.hpRatio > .10f) && it.types.isNotEmpty()
        }.map { reserve ->
            reserve to TypeChart.multiplier(threat.move.type, reserve.types)
        }.filter { (_, eff) -> eff <= currentEff * .82 }
        if (candidates.isEmpty()) return null
        val chosen = candidates
            .sortedWith(
                compareBy<Pair<ReserveState, Double>> { it.second }
                    .thenByDescending { it.first.score }
            )
            .first()
        return SacSwapForecast(
            targetName = chosen.first.name ?: return null,
            targetTeamSlot = chosen.first.teamSlot,
            confidence = (threat.confidence * .65f + f.consistencyScore * .25f + .08f)
                .coerceIn(0f, .94f),
            reason = "absorve " + threat.move.name + " melhor que o Pokémon ativo"
        )
    }
}
