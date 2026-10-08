package com.lucianotoscano.pvppokego.engine

import com.lucianotoscano.pvppokego.data.ChargedThreatForecast
import com.lucianotoscano.pvppokego.data.DamageForecast
import com.lucianotoscano.pvppokego.data.EnemyEnergyForecast
import com.lucianotoscano.pvppokego.data.FastMovePhaseSnapshot
import com.lucianotoscano.pvppokego.data.GameDataRepository
import com.lucianotoscano.pvppokego.data.MoveDef
import com.lucianotoscano.pvppokego.data.ObservedDamageEstimate
import com.lucianotoscano.pvppokego.data.PredictionEvidenceClass
import com.lucianotoscano.pvppokego.data.StatStageRange
import com.lucianotoscano.pvppokego.data.WeightedMove
import kotlin.math.ceil

/**
 * Pure predictive layer: energy hypotheses -> per-Charged threat windows.
 * It never mutates battle state and never sends input to Pokémon GO.
 */
internal object ChargedMovePredictor {
    data class EnergyWindow(
        val fastMovesMin: Int,
        val fastMovesLikely: Int,
        val fastMovesMax: Int,
        val turnsMin: Int,
        val turnsLikely: Int,
        val turnsMax: Int,
        val readyPossible: Boolean,
        val readyCertain: Boolean,
        val doubleReadyPossible: Boolean
    )

    /**
     * Pure turn/energy math used by the live predictor and deterministic JVM tests.
     * The input hypotheses already encode uncertainty about the enemy Fast Move.
     */
    internal fun energyWindow(
        hypotheses: List<EnemyEnergyTracker.Hypothesis>,
        energyCost: Int
    ): EnergyWindow? {
        if (hypotheses.isEmpty() || energyCost <= 0) return null
        val perHypothesis = hypotheses.map { h ->
            val missing = (energyCost - h.energy).coerceAtLeast(0)
            val gain = h.fastMove.energyGain.coerceAtLeast(1)
            val fastNeeded = if (missing == 0) 0 else {
                ceil(missing.toDouble() / gain.toDouble()).toInt()
            }
            val turns = fastNeeded * h.fastMove.turns.coerceAtLeast(1)
            Triple(h, fastNeeded, turns)
        }
        val fastValues = perHypothesis.map { it.second to it.first.weight }
        val turnValues = perHypothesis.map { it.third to it.first.weight }
        return EnergyWindow(
            fastMovesMin = fastValues.minOf { it.first },
            fastMovesLikely = weightedMedian(fastValues),
            fastMovesMax = fastValues.maxOf { it.first },
            turnsMin = turnValues.minOf { it.first },
            turnsLikely = weightedMedian(turnValues),
            turnsMax = turnValues.maxOf { it.first },
            readyPossible = hypotheses.any { it.energy >= energyCost },
            readyCertain = hypotheses.all { it.energy >= energyCost },
            doubleReadyPossible = energyCost * 2 <= 100 &&
                hypotheses.any { it.energy >= energyCost * 2 }
        )
    }

    fun forecast(
        repo: GameDataRepository,
        energyTracker: EnemyEnergyTracker,
        chargedMoves: List<WeightedMove>,
        attackerId: String?,
        attackerCp: Int?,
        defenderId: String?,
        defenderCp: Int?,
        currentHpRatio: Float?,
        reaction: ReactionWindowEstimator,
        observedDamage: (MoveDef) -> ObservedDamageEstimate?,
        attackerAttackStages: StatStageRange = StatStageRange(),
        defenderDefenseStages: StatStageRange = StatStageRange(),
        /** Time since the player HP bar was last readable. Projection is non-mutating. */
        unobservedFastWindowMs: Long = 0L,
        fastPhase: FastMovePhaseSnapshot? = null
    ): EnemyEnergyForecast? {
        val hypotheses = energyTracker.projectedHypotheses(unobservedFastWindowMs)
        if (hypotheses.isEmpty() || chargedMoves.isEmpty()) return null

        val projectedEnergyMin = hypotheses.minOf { it.energy }
        val projectedEnergyMax = hypotheses.maxOf { it.energy }
        val projectedEnergyLikely = weightedMedian(hypotheses.map { it.energy to it.weight })
        val projectionPenalty = when {
            unobservedFastWindowMs <= 0L -> 1f
            unobservedFastWindowMs < 1_500L -> .88f
            unobservedFastWindowMs < 3_000L -> .78f
            else -> .68f
        }
        val consistencyScore = energyTracker.consistencyScore()
        val mathematicalConfidence = (energyTracker.confidence() * projectionPenalty).coerceIn(0f, 1f)
        val likelyHypothesis = hypotheses.maxByOrNull { it.weight }

        val reactionTurns = reaction.reactionTurns()
        val leadTimeMs = reaction.leadTimeMs()
        val attacker = repo.pokemon(attackerId)
        val defender = repo.pokemon(defenderId)
        val attackerStats = attacker?.let { repo.estimatedBattleStats(it.speciesId, attackerCp) }
        val defenderStats = defender?.let { repo.estimatedBattleStats(it.speciesId, defenderCp) }

        val candidates = chargedMoves.mapNotNull candidate@ { weightedMove ->
            val move = weightedMove.move
            val cost = move.chargedCost.takeIf { it > 0 } ?: return@candidate null
            val window = energyWindow(hypotheses, cost) ?: return@candidate null
            val fastMin = window.fastMovesMin
            val fastMax = window.fastMovesMax
            val fastLikely = window.fastMovesLikely
            val turnsMin = window.turnsMin
            val turnsMax = window.turnsMax
            val turnsLikely = window.turnsLikely
            val readyPossible = window.readyPossible
            val readyCertain = window.readyCertain
            val doubleReadyPossible = window.doubleReadyPossible

            val damage = DamageForecastEngine.forecast(
                repo = repo,
                attackerId = attackerId,
                attackerCp = attackerCp,
                defenderId = defenderId,
                defenderCp = defenderCp,
                move = move,
                observed = observedDamage(move),
                currentHpRatio = currentHpRatio,
                attackerAttackStages = attackerAttackStages,
                defenderDefenseStages = defenderDefenseStages
            )

            val fastPressure = hypotheses.mapNotNull pressure@ { h ->
                if (attacker == null || defender == null || attackerStats == null || defenderStats == null) {
                    return@pressure null
                }
                val pct = DamageForecastEngine.modelDamagePercent(
                    attacker = attacker,
                    attackerStats = attackerStats,
                    defender = defender,
                    defenderStats = defenderStats,
                    move = h.fastMove,
                    attackerAttackStage = attackerAttackStages,
                    defenderDefenseStage = defenderDefenseStages
                ) ?: return@pressure null
                val moveTurns = h.fastMove.turns.coerceAtLeast(1)
                val dpt = pct / moveTurns.toFloat()
                val missing = (cost - h.energy).coerceAtLeast(0)
                val fastNeeded = if (missing == 0) 0 else {
                    ceil(missing.toDouble() / h.fastMove.energyGain.coerceAtLeast(1).toDouble()).toInt()
                }
                val turnsUntilReady = fastNeeded * moveTurns
                Pair(dpt, dpt * turnsUntilReady.toFloat())
            }
            val fastDptValues = fastPressure.map { it.first }
            val fastDamageUntilReady = fastPressure.map { it.second }
            val eptValues = hypotheses.map {
                it.fastMove.energyGain.toFloat() / it.fastMove.turns.coerceAtLeast(1).toFloat()
            }

            val currentHpPercent = currentHpRatio?.times(100f)
            val likelyKo = damage != null &&
                currentHpPercent != null &&
                damage.averagePercent >= currentHpPercent - 1f
            val mayKoBeforeCharged = currentHpPercent != null &&
                (fastDamageUntilReady.maxOrNull() ?: 0f) >= currentHpPercent
            val strongerAlternatives = chargedMoves.filter { other ->
                val otherCost = other.move.chargedCost
                other.move.moveId != move.moveId &&
                    otherCost >= cost + 5 &&
                    other.move.power >= move.power * 1.18
            }
            val baitPotential = strongerAlternatives.any { other ->
                projectedEnergyMax >= other.move.chargedCost
            }
            val affordableWeight = chargedMoves
                .filter { it.move.chargedCost in 1..projectedEnergyMax }
                .sumOf { it.weight.toDouble() }
                .toFloat()
                .takeIf { it > 0f } ?: 1f
            val baitProbability = if (baitPotential) {
                (weightedMove.weight / affordableWeight).coerceIn(0f, .95f)
            } else 0f
            val fastCountSequence = likelyHypothesis?.let { h ->
                EnergySequenceMath.repeatedChargedCounts(
                    currentEnergy = h.energy,
                    fastEnergyGain = h.fastMove.energyGain,
                    chargedCost = cost,
                    steps = 4
                )
            }.orEmpty()

            val prior = weightedMove.weight.coerceIn(.01f, 1f)
            val urgency = when {
                readyCertain -> 1f
                readyPossible -> .92f
                turnsMin <= reactionTurns -> .82f
                turnsLikely <= reactionTurns + 2 -> .68f
                else -> .45f
            }
            val damagePressure = damage?.let {
                (it.averagePercent / (currentHpPercent ?: 100f).coerceAtLeast(10f)).coerceIn(0f, 1.4f)
            } ?: .35f
            val threat = (
                urgency * .42f +
                    damagePressure.coerceIn(0f, 1f) * .32f +
                    mathematicalConfidence * .18f +
                    prior * .08f
                ).coerceIn(0f, 1f)
            val spamBonus = ((55 - cost).coerceAtLeast(0) / 30f).coerceIn(0f, .35f)
            val shieldPressure = (
                threat * .72f +
                    spamBonus * .18f +
                    (if (baitPotential) .10f else 0f)
                ).coerceIn(0f, 1f)

            val evidenceClass = when {
                hypotheses.size == 1 && readyCertain -> PredictionEvidenceClass.MATHEMATICALLY_POSSIBLE
                prior >= .60f && mathematicalConfidence >= .70f -> PredictionEvidenceClass.ESTIMATE
                else -> PredictionEvidenceClass.MATHEMATICALLY_POSSIBLE
            }

            ChargedThreatForecast(
                move = move,
                energyCost = cost,
                currentEnergyMin = projectedEnergyMin,
                currentEnergyMax = projectedEnergyMax,
                fastMovesRemainingMin = fastMin,
                fastMovesRemainingLikely = fastLikely,
                fastMovesRemainingMax = fastMax,
                turnsRemainingMin = turnsMin,
                turnsRemainingLikely = turnsLikely,
                turnsRemainingMax = turnsMax,
                earliestTimeMs = turnsMin * EnemyEnergyTracker.TURN_MS.toLong(),
                likelyTimeMs = turnsLikely * EnemyEnergyTracker.TURN_MS.toLong(),
                prepareInTurns = (turnsMin - reactionTurns).coerceAtLeast(0),
                readyPossible = readyPossible,
                readyCertain = readyCertain,
                doubleReadyPossible = doubleReadyPossible,
                fastCountSequenceLikely = fastCountSequence,
                baitProbability = baitProbability,
                confidence = (mathematicalConfidence * .78f + prior * .22f).coerceIn(0f, 1f),
                evidenceClass = evidenceClass,
                eptMin = eptValues.minOrNull() ?: 0f,
                eptMax = eptValues.maxOrNull() ?: 0f,
                dptMin = fastDptValues.minOrNull(),
                dptMax = fastDptValues.maxOrNull(),
                dpeMin = damage?.minPercent?.div(cost.toFloat()),
                dpeMax = damage?.maxPercent?.div(cost.toFloat()),
                fastDamageUntilReadyMin = fastDamageUntilReady.minOrNull(),
                fastDamageUntilReadyMax = fastDamageUntilReady.maxOrNull(),
                mayKoBeforeCharged = mayKoBeforeCharged,
                baitPotential = baitPotential,
                shieldPressureScore = shieldPressure,
                damageForecast = damage,
                likelyKo = likelyKo,
                threatScore = threat
            )
        }.sortedWith(
            compareByDescending<ChargedThreatForecast> { it.readyCertain }
                .thenByDescending { it.readyPossible }
                .thenBy { it.turnsRemainingMin }
                .thenByDescending { it.threatScore }
        )

        if (candidates.isEmpty()) return null
        return EnemyEnergyForecast(
            energyMin = projectedEnergyMin,
            energyLikely = projectedEnergyLikely,
            energyMax = projectedEnergyMax,
            hypothesisCount = hypotheses.size,
            fastMoveNames = hypotheses
                .sortedByDescending { it.weight }
                .map { repo.localizedMoveName(it.fastMove) }
                .distinct()
                .take(3),
            candidates = candidates,
            leadTimeMs = leadTimeMs,
            reactionTurns = reactionTurns,
            confidence = mathematicalConfidence,
            consistencyScore = consistencyScore,
            anomalyCount = energyTracker.anomalyCount(),
            fastPhase = fastPhase
        )
    }

    private fun weightedMedian(values: List<Pair<Int, Float>>): Int {
        if (values.isEmpty()) return 0
        val total = values.sumOf { it.second.toDouble() }.toFloat().takeIf { it > 0f } ?: 1f
        var running = 0f
        values.sortedBy { it.first }.forEach { (value, weight) ->
            running += weight / total
            if (running >= .5f) return value
        }
        return values.maxByOrNull { it.second }?.first ?: 0
    }
}
