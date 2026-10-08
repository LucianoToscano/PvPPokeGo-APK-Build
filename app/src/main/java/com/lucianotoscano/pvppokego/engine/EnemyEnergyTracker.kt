package com.lucianotoscano.pvppokego.engine

import com.lucianotoscano.pvppokego.data.EnemyEnergyHypothesisSnapshot
import com.lucianotoscano.pvppokego.data.EnemyEnergySnapshot
import com.lucianotoscano.pvppokego.data.MoveDef
import com.lucianotoscano.pvppokego.data.WeightedMove
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * Discrete enemy-energy hypothesis tracker.
 *
 * Each hypothesis owns a legal Fast Move and exact residual energy. Unknown Fast/Charged
 * choices branch instead of collapsing to a guessed number. The state is small (normally
 * 1-4 Fast Moves) and is pruned/merged after every event.
 */
internal class EnemyEnergyTracker(
    private val maxEnergy: Int = 100,
    private val maxHypotheses: Int = 32
) {
    data class Hypothesis(
        val fastMove: MoveDef,
        val energy: Int,
        val completedFastMoves: Int,
        val elapsedTurns: Int,
        val weight: Float
    )

    data class FastMoveInference(
        val move: MoveDef,
        /** Posterior probability from ranking prior + cadence + observed HP damage. */
        val probability: Float,
        /** Separation from the second most likely Fast Move. */
        val margin: Float,
        val observedEvents: Int
    )

    private var hypotheses: List<Hypothesis> = emptyList()
    private var observedFastEvents: Int = 0
    private var lastObservedTurn: Int = 0
    private var lastFastEventId: Long? = null
    private var consistencyScore: Float = 1f
    private var anomalyCount: Int = 0

    val isInitialized: Boolean get() = hypotheses.isNotEmpty()

    fun reset(candidates: List<WeightedMove>, snapshot: EnemyEnergySnapshot? = null) {
        observedFastEvents = snapshot?.observedFastEvents ?: 0
        lastObservedTurn = snapshot?.lastObservedTurn ?: 0
        lastFastEventId = null
        consistencyScore = snapshot?.consistencyScore?.coerceIn(.05f, 1f) ?: 1f
        anomalyCount = snapshot?.anomalyCount?.coerceAtLeast(0) ?: 0
        hypotheses = restoreCandidates(candidates, snapshot)
        normalizeAndPrune()
    }

    fun clear() {
        hypotheses = emptyList()
        observedFastEvents = 0
        lastObservedTurn = 0
        lastFastEventId = null
        consistencyScore = 1f
        anomalyCount = 0
    }

    fun confirmFastMove(move: MoveDef) {
        val matching = hypotheses.filter { it.fastMove.moveId == move.moveId }
        hypotheses = if (matching.isNotEmpty()) {
            matching
        } else {
            val likelyEnergy = weightedMedianEnergy(hypotheses)
            listOf(
                Hypothesis(
                    fastMove = move,
                    energy = likelyEnergy,
                    completedFastMoves = hypotheses.maxOfOrNull { it.completedFastMoves } ?: 0,
                    elapsedTurns = hypotheses.maxOfOrNull { it.elapsedTurns } ?: 0,
                    weight = 1f
                )
            )
        }
        normalizeAndPrune()
    }

    /**
     * Register completed Fast Moves, never raw taps. eventId makes duplicate delivery
     * idempotent even if a detector/service callback is repeated.
     */
    fun observeFastCompletion(
        count: Int,
        eventId: Long,
        confidence: Float,
        observedIntervalMs: Long? = null
    ): Boolean {
        if (count <= 0 || hypotheses.isEmpty()) return false
        if (lastFastEventId == eventId) return false
        lastFastEventId = eventId

        val boundedConfidence = confidence.coerceIn(0.05f, 1f)
        observedIntervalMs?.takeIf { it in 150L..12_000L }?.let { observed ->
            val bestCadence = hypotheses.maxOfOrNull { h ->
                val expectedMs = (h.fastMove.turns * TURN_MS * count).toLong().coerceAtLeast(1L)
                val relativeError = abs(observed - expectedMs).toDouble() / expectedMs.toDouble()
                exp(-2.4 * relativeError).toFloat().coerceIn(0.02f, 1f)
            } ?: 1f
            registerEvidence(bestCadence)
        }
        hypotheses = hypotheses.map { h ->
            val gain = h.fastMove.energyGain.coerceAtLeast(0) * count
            val expectedMs = (h.fastMove.turns * TURN_MS * count).toLong()
            val cadenceLikelihood = observedIntervalMs
                ?.takeIf { it in 150L..12_000L && expectedMs > 0L }
                ?.let { observed ->
                    val relativeError = abs(observed - expectedMs).toDouble() / expectedMs.toDouble()
                    exp(-2.4 * relativeError).toFloat().coerceIn(0.04f, 1f)
                }
                ?: 1f
            val evidenceWeight = (
                (1f - boundedConfidence) + boundedConfidence * cadenceLikelihood
            ).coerceIn(0.02f, 1f)
            h.copy(
                energy = (h.energy + gain).coerceAtMost(maxEnergy),
                completedFastMoves = h.completedFastMoves + count,
                elapsedTurns = h.elapsedTurns + h.fastMove.turns * count,
                weight = h.weight * evidenceWeight
            )
        }
        observedFastEvents++
        lastObservedTurn = hypotheses.maxOfOrNull { it.elapsedTurns } ?: lastObservedTurn
        normalizeAndPrune()
        return true
    }

    /**
     * Reweight legal Fast-Move hypotheses using damage from an already-confirmed
     * HP-drop event. This never creates a new attack; it only helps identify the move.
     */
    fun reweightByDamageEvidence(
        observedDamageFraction: Float?,
        hits: Int,
        expectedDamagePercent: (MoveDef) -> Float?
    ) {
        val observedPercentPerHit = observedDamageFraction
            ?.takeIf { it > 0f && it < 0.50f }
            ?.times(100f)
            ?.div(hits.coerceAtLeast(1).toFloat())
            ?: return
        if (hypotheses.isEmpty()) return

        hypotheses = hypotheses.map { h ->
            val expected = expectedDamagePercent(h.fastMove)
                ?.takeIf { it > 0f }
                ?: return@map h
            val relativeError = abs(observedPercentPerHit - expected) / expected.coerceAtLeast(1.0f)
            val likelihood = exp(-1.75 * relativeError.toDouble())
                .toFloat()
                .coerceIn(DAMAGE_MIN_LIKELIHOOD, 1f)
            h.copy(weight = h.weight * likelihood)
        }
        normalizeAndPrune()
    }

    /**
     * Best current Fast-Move identity without collapsing the uncertainty set.
     */
    fun fastMoveInference(): FastMoveInference? {
        if (hypotheses.isEmpty()) return null
        val grouped = hypotheses
            .groupBy { it.fastMove.moveId }
            .map { (_, group) ->
                group.first().fastMove to group.sumOf { it.weight.toDouble() }.toFloat()
            }
            .sortedByDescending { it.second }
        val top = grouped.firstOrNull() ?: return null
        val second = grouped.getOrNull(1)?.second ?: 0f
        val total = grouped.sumOf { it.second.toDouble() }.toFloat().takeIf { it > 0f } ?: 1f
        val probability = (top.second / total).coerceIn(0f, 1f)
        val secondProbability = (second / total).coerceIn(0f, 1f)
        return FastMoveInference(
            move = top.first,
            probability = probability,
            margin = (probability - secondProbability).coerceIn(0f, 1f),
            observedEvents = observedFastEvents
        )
    }

    /**
     * Non-mutating projection for an interval where the player's HP bar cannot be read.
     * Perfect tapping gets the largest weight, but 0..N completions remain possible.
     */
    fun projectedHypotheses(
        unobservedMs: Long,
        maxExtraMoves: Int = MAX_GAP_RECOVERY_MOVES
    ): List<Hypothesis> {
        if (hypotheses.isEmpty() || unobservedMs < MIN_PROJECTION_GAP_MS) return hypotheses
        val cappedExtra = maxExtraMoves.coerceIn(0, MAX_GAP_RECOVERY_MOVES)
        val projected = hypotheses.flatMap { h ->
            val durationMs = h.fastMove.turns.coerceAtLeast(1) * TURN_MS.toLong()
            val maximumCompleted = (unobservedMs / durationMs).toInt().coerceIn(0, cappedExtra)
            if (maximumCompleted <= 0) {
                listOf(h)
            } else {
                (0..maximumCompleted).map { extra ->
                    val gain = h.fastMove.energyGain.coerceAtLeast(0) * extra
                    val distanceFromPerfectTapping = maximumCompleted - extra
                    val timingWeight = PROJECTION_WEIGHT_DECAY.pow(distanceFromPerfectTapping)
                    h.copy(
                        energy = (h.energy + gain).coerceAtMost(maxEnergy),
                        completedFastMoves = h.completedFastMoves + extra,
                        elapsedTurns = h.elapsedTurns + h.fastMove.turns * extra,
                        weight = h.weight * timingWeight
                    )
                }
            }
        }
        return normalizedProjection(projected)
    }

    /**
     * Widen the state after a capture gap or Charged sequence where a Fast Move may have
     * registered without a trustworthy HP event. Zero-extra remains possible; no event is
     * asserted as fact. Higher missed counts receive progressively lower prior weight.
     */
    fun widenForPossibleMissedFast(maxExtraMoves: Int) {
        val extraMax = maxExtraMoves.coerceIn(0, MAX_GAP_RECOVERY_MOVES)
        if (extraMax <= 0 || hypotheses.isEmpty()) return
        consistencyScore = (consistencyScore * .97f).coerceAtLeast(.05f)
        hypotheses = hypotheses.flatMap { h ->
            (0..extraMax).map { extra ->
                val gain = h.fastMove.energyGain.coerceAtLeast(0) * extra
                h.copy(
                    energy = (h.energy + gain).coerceAtMost(maxEnergy),
                    completedFastMoves = h.completedFastMoves + extra,
                    elapsedTurns = h.elapsedTurns + h.fastMove.turns * extra,
                    weight = h.weight * GAP_WEIGHT_DECAY.pow(extra)
                )
            }
        }
        normalizeAndPrune()
    }

    fun manualFastDelta(delta: Int, confirmedMove: MoveDef? = null) {
        if (delta == 0 || hypotheses.isEmpty()) return
        confirmedMove?.let(::confirmFastMove)
        hypotheses = hypotheses.map { h ->
            val gain = h.fastMove.energyGain.coerceAtLeast(0) * kotlin.math.abs(delta)
            val turns = h.fastMove.turns * kotlin.math.abs(delta)
            if (delta > 0) {
                h.copy(
                    energy = (h.energy + gain).coerceAtMost(maxEnergy),
                    completedFastMoves = h.completedFastMoves + delta,
                    elapsedTurns = h.elapsedTurns + turns
                )
            } else {
                h.copy(
                    energy = (h.energy - gain).coerceAtLeast(0),
                    completedFastMoves = (h.completedFastMoves + delta).coerceAtLeast(0),
                    elapsedTurns = (h.elapsedTurns - turns).coerceAtLeast(0)
                )
            }
        }
        normalizeAndPrune()
    }

    /**
     * Exact Charged identification removes impossible states and subtracts the cost only
     * from states that could legally pay it. If all hypotheses fail, preserve a degraded
     * recovery state at zero energy instead of fabricating pre-move energy.
     */
    fun observeCharged(move: MoveDef): Boolean {
        val cost = move.chargedCost.coerceAtLeast(1)
        val valid = hypotheses
            .filter { it.energy >= cost }
            .map { it.copy(energy = it.energy - cost) }
        hypotheses = if (valid.isNotEmpty()) {
            consistencyScore = (consistencyScore + .012f).coerceAtMost(1f)
            valid
        } else {
            registerAnomaly(.72f)
            // A real Charged happened, so at least one Fast completion was missed.
            // Reconstruct the minimum missing events needed to make the observation legal,
            // and also keep one-extra-Fast overfarm state so recovery does not assume 0 energy.
            hypotheses.flatMap { h ->
                recoverAfterMissedFast(h, cost)
            }
        }
        normalizeAndPrune()
        return hypotheses.isNotEmpty()
    }

    /**
     * Unknown Charged identification branches across every legal affordable cost.
     * Duplicate residual states are merged immediately to cap state growth.
     */
    fun observeUnknownCharged(candidates: List<MoveDef>): Boolean {
        val costs = candidates
            .map { it.chargedCost }
            .filter { it > 0 }
            .distinct()
        if (costs.isEmpty() || hypotheses.isEmpty()) return false

        val anyAffordable = hypotheses.any { h -> costs.any { it <= h.energy } }
        if (!anyAffordable) registerAnomaly(.78f)
        else consistencyScore = (consistencyScore + .008f).coerceAtMost(1f)

        val branched = buildList {
            hypotheses.forEach { h ->
                val affordable = costs.filter { it <= h.energy }
                if (affordable.isEmpty()) {
                    costs.forEach { cost ->
                        addAll(recoverAfterMissedFast(h, cost))
                    }
                } else {
                    val branchWeight = h.weight / affordable.size.toFloat()
                    affordable.forEach { cost ->
                        add(h.copy(energy = h.energy - cost, weight = branchWeight))
                    }
                }
            }
        }
        hypotheses = branched
        normalizeAndPrune()
        return hypotheses.isNotEmpty()
    }

    fun snapshot(): EnemyEnergySnapshot = EnemyEnergySnapshot(
        hypotheses = hypotheses.map {
            EnemyEnergyHypothesisSnapshot(
                fastMoveId = it.fastMove.moveId,
                energy = it.energy,
                completedFastMoves = it.completedFastMoves,
                elapsedTurns = it.elapsedTurns,
                weight = it.weight
            )
        },
        observedFastEvents = observedFastEvents,
        lastObservedTurn = lastObservedTurn,
        consistencyScore = consistencyScore,
        anomalyCount = anomalyCount
    )

    fun hypotheses(): List<Hypothesis> = hypotheses

    fun energyMin(): Int = hypotheses.minOfOrNull { it.energy } ?: 0
    fun energyMax(): Int = hypotheses.maxOfOrNull { it.energy } ?: 0
    fun energyLikely(): Int = weightedMedianEnergy(hypotheses)
    fun hypothesisCount(): Int = hypotheses.size
    fun consistencyScore(): Float = consistencyScore
    fun anomalyCount(): Int = anomalyCount

    fun likelyFastTurns(): Int {
        if (hypotheses.isEmpty()) return 2
        val values = hypotheses.map { it.fastMove.turns.coerceAtLeast(1) to it.weight }
        val total = values.sumOf { it.second.toDouble() }.toFloat().takeIf { it > 0f } ?: 1f
        var running = 0f
        values.sortedBy { it.first }.forEach { (turns, weight) ->
            running += weight / total
            if (running >= .5f) return turns
        }
        return values.maxByOrNull { it.second }?.first ?: 2
    }

    fun fastTurnsRange(): IntRange {
        val min = hypotheses.minOfOrNull { it.fastMove.turns.coerceAtLeast(1) } ?: 2
        val max = hypotheses.maxOfOrNull { it.fastMove.turns.coerceAtLeast(1) } ?: min
        return min..max
    }

    fun confidence(): Float {
        if (hypotheses.isEmpty()) return 0f
        val top = hypotheses.maxOf { it.weight }
        val spreadPenalty = (1f / hypotheses.size.toFloat().coerceAtLeast(1f)).coerceIn(.18f, 1f)
        val evidence = (observedFastEvents / 5f).coerceIn(.35f, 1f)
        val base = (top * .65f + spreadPenalty * .15f + evidence * .20f).coerceIn(0f, 1f)
        return (base * (.55f + .45f * consistencyScore)).coerceIn(0f, 1f)
    }

    private fun registerEvidence(score: Float) {
        val bounded = score.coerceIn(0f, 1f)
        if (bounded < .22f) {
            registerAnomaly(.84f)
        } else {
            consistencyScore = (consistencyScore * .94f + bounded * .06f).coerceIn(.05f, 1f)
        }
    }

    private fun registerAnomaly(multiplier: Float) {
        anomalyCount++
        consistencyScore = (consistencyScore * multiplier.coerceIn(.30f, .98f)).coerceIn(.05f, 1f)
    }

    private fun restoreCandidates(
        candidates: List<WeightedMove>,
        snapshot: EnemyEnergySnapshot?
    ): List<Hypothesis> {
        val legal = candidates.filter { it.move.energyGain > 0 }.distinctBy { it.move.moveId }
        if (legal.isEmpty()) return emptyList()
        val byId = legal.associateBy { it.move.moveId }
        val restored = snapshot?.hypotheses.orEmpty().mapNotNull { saved ->
            val candidate = byId[saved.fastMoveId] ?: return@mapNotNull null
            Hypothesis(
                fastMove = candidate.move,
                energy = saved.energy.coerceIn(0, maxEnergy),
                completedFastMoves = saved.completedFastMoves.coerceAtLeast(0),
                elapsedTurns = saved.elapsedTurns.coerceAtLeast(0),
                weight = saved.weight.coerceAtLeast(MIN_WEIGHT)
            )
        }
        if (restored.isNotEmpty()) return restored
        return legal.map { candidate ->
            Hypothesis(
                fastMove = candidate.move,
                energy = 0,
                completedFastMoves = 0,
                elapsedTurns = 0,
                weight = candidate.weight.coerceAtLeast(MIN_WEIGHT)
            )
        }
    }

    private fun recoverAfterMissedFast(h: Hypothesis, cost: Int): List<Hypothesis> {
        val gain = h.fastMove.energyGain.coerceAtLeast(1)
        val missing = (cost - h.energy).coerceAtLeast(0)
        val minimumMissed = if (missing == 0) 0 else kotlin.math.ceil(
            missing.toDouble() / gain.toDouble()
        ).toInt()
        fun recovered(extra: Int): Hypothesis {
            val count = minimumMissed + extra
            val before = (h.energy + gain * count).coerceAtMost(maxEnergy)
            return h.copy(
                energy = (before - cost).coerceAtLeast(0),
                completedFastMoves = h.completedFastMoves + count,
                elapsedTurns = h.elapsedTurns + h.fastMove.turns * count,
                weight = h.weight * RECOVERY_WEIGHT / 2f
            )
        }
        val minimum = recovered(0)
        val overfarm = recovered(1)
        return if (
            overfarm.energy == minimum.energy &&
            overfarm.completedFastMoves == minimum.completedFastMoves
        ) listOf(minimum) else listOf(minimum, overfarm)
    }

    private fun normalizeAndPrune() {
        if (hypotheses.isEmpty()) return
        val merged = hypotheses
            .groupBy { listOf(it.fastMove.moveId, it.energy.toString(), it.completedFastMoves.toString(), it.elapsedTurns.toString()).joinToString("|") }
            .map { (_, group) ->
                val first = group.first()
                first.copy(weight = group.sumOf { it.weight.toDouble() }.toFloat())
            }
            .sortedByDescending { it.weight }
            .take(maxHypotheses)
        val total = merged.sumOf { it.weight.toDouble() }.toFloat()
        hypotheses = if (total > 0f) {
            merged.map { it.copy(weight = (it.weight / total).coerceAtLeast(MIN_WEIGHT)) }
                .let { renormalize(it) }
        } else {
            val uniform = 1f / merged.size.toFloat()
            merged.map { it.copy(weight = uniform) }
        }
    }

    private fun renormalize(values: List<Hypothesis>): List<Hypothesis> {
        val total = values.sumOf { it.weight.toDouble() }.toFloat().takeIf { it > 0f } ?: 1f
        return values.map { it.copy(weight = it.weight / total) }
    }

    private fun normalizedProjection(values: List<Hypothesis>): List<Hypothesis> {
        if (values.isEmpty()) return emptyList()
        val merged = values
            .groupBy {
                listOf(
                    it.fastMove.moveId,
                    it.energy.toString(),
                    it.completedFastMoves.toString(),
                    it.elapsedTurns.toString()
                ).joinToString("|")
            }
            .map { (_, group) ->
                val first = group.first()
                first.copy(weight = group.sumOf { it.weight.toDouble() }.toFloat())
            }
            .sortedByDescending { it.weight }
            .take(maxHypotheses)
        return renormalize(merged)
    }

    private fun weightedMedianEnergy(values: List<Hypothesis>): Int {
        if (values.isEmpty()) return 0
        var running = 0f
        values.sortedBy { it.energy }.forEach { h ->
            running += h.weight
            if (running >= .5f) return h.energy
        }
        return values.maxByOrNull { it.weight }?.energy ?: 0
    }

    companion object {
        const val TURN_MS = 500
        private const val MIN_WEIGHT = 0.0001f
        private const val RECOVERY_WEIGHT = 0.35f
        private const val GAP_WEIGHT_DECAY = 0.58f
        private const val PROJECTION_WEIGHT_DECAY = 0.58f
        private const val DAMAGE_MIN_LIKELIHOOD = 0.05f
        private const val MIN_PROJECTION_GAP_MS = 600L
        private const val MAX_GAP_RECOVERY_MOVES = 5
    }
}
