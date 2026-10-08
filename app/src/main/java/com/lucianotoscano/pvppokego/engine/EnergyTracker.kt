package com.lucianotoscano.pvppokego.engine

import com.lucianotoscano.pvppokego.data.ChargedPrediction
import com.lucianotoscano.pvppokego.data.EnergyConfidence
import com.lucianotoscano.pvppokego.data.MoveDef
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * PvP energy math with an uncertainty range.
 *
 * When every observed event is known, minEnergy == maxEnergy and the value is exact. When OCR
 * confirms that a Charged Move happened but cannot identify which one, the tracker keeps every
 * still-plausible residual energy instead of inventing the cheapest move.
 */
class EnergyTracker(
    initialEnergy: Int = 0,
    private val maxEnergyCap: Int = 100
) {
    var minEnergy: Int = initialEnergy.coerceIn(0, maxEnergyCap)
        private set
    var maxEnergy: Int = initialEnergy.coerceIn(0, maxEnergyCap)
        private set
    var fastMoveCount: Int = 0
        private set

    /** Concise single-value estimate for legacy UI/debug; the full range remains authoritative. */
    val energy: Int get() = ((minEnergy + maxEnergy) / 2).coerceIn(0, maxEnergyCap)
    val isRange: Boolean get() = minEnergy != maxEnergy

    fun reset() {
        minEnergy = 0
        maxEnergy = 0
        fastMoveCount = 0
    }

    fun restore(energy: Int, count: Int) = restoreRange(energy, energy, count)

    fun restoreRange(minimum: Int, maximum: Int, count: Int) {
        minEnergy = min(minimum, maximum).coerceIn(0, maxEnergyCap)
        maxEnergy = max(minimum, maximum).coerceIn(0, maxEnergyCap)
        fastMoveCount = count.coerceAtLeast(0)
    }

    fun onFastMove(fast: MoveDef, count: Int = 1) {
        if (count <= 0) return
        val gain = fast.energyGain.coerceAtLeast(0) * count
        minEnergy = (minEnergy + gain).coerceAtMost(maxEnergyCap)
        maxEnergy = (maxEnergy + gain).coerceAtMost(maxEnergyCap)
        fastMoveCount += count
    }

    fun undoFastMove(fast: MoveDef, count: Int = 1) {
        if (count <= 0) return
        val gain = fast.energyGain.coerceAtLeast(0) * count
        minEnergy = (minEnergy - gain).coerceAtLeast(0)
        maxEnergy = (maxEnergy - gain).coerceAtLeast(0)
        fastMoveCount = (fastMoveCount - count).coerceAtLeast(0)
    }

    /** Manual confirmation. If the upper bound cannot afford the move, reject it. */
    fun onChargedMove(charged: MoveDef): Boolean {
        val cost = charged.chargedCost.coerceAtLeast(0)
        if (maxEnergy < cost) return false
        minEnergy = (minEnergy - cost).coerceAtLeast(0)
        maxEnergy = (maxEnergy - cost).coerceAtLeast(0)
        normalizeRange()
        return true
    }

    /** Exact visual/OCR move identification. */
    fun onObservedChargedMove(charged: MoveDef): Boolean {
        val cost = charged.chargedCost.coerceAtLeast(0)
        when {
            maxEnergy < cost -> {
                // A real move happened, so our Fast counter missed energy. Keep a conservative 0.
                minEnergy = 0
                maxEnergy = 0
            }
            minEnergy >= cost -> {
                minEnergy -= cost
                maxEnergy -= cost
            }
            else -> {
                // Part of the range could afford it and part could not. Keep all plausible residuals.
                minEnergy = 0
                maxEnergy = (maxEnergy - cost).coerceAtLeast(0)
            }
        }
        normalizeRange()
        return true
    }

    /**
     * OCR saw a Charged Move but not its name. Subtract the set of possible costs as a range.
     * This intentionally avoids the old cheapest-move guess that could corrupt the rest of battle.
     */
    fun onObservedUnknownCharged(candidates: List<MoveDef>): Boolean {
        val costs = candidates.map { it.chargedCost }.filter { it > 0 }.distinct()
        if (costs.isEmpty()) return false
        val minCost = costs.minOrNull() ?: return false
        val maxCost = costs.maxOrNull() ?: return false

        if (maxEnergy < minCost) {
            minEnergy = 0
            maxEnergy = 0
        } else {
            val oldMin = minEnergy
            val oldMax = maxEnergy
            minEnergy = (oldMin - maxCost).coerceAtLeast(0)
            maxEnergy = (oldMax - minCost).coerceAtLeast(0)
        }
        normalizeRange()
        return true
    }

    fun predict(
        charged: MoveDef,
        fast: MoveDef,
        confidenceHint: EnergyConfidence = EnergyConfidence.ESTIMATED
    ): ChargedPrediction {
        val cost = charged.chargedCost.coerceAtLeast(1)
        val gain = fast.energyGain.coerceAtLeast(1)
        val estimate = energy
        val missing = (cost - maxEnergy).coerceAtLeast(0)
        val remaining = if (missing == 0) 0 else ceil(missing.toDouble() / gain).toInt()
        val progress = (estimate.toFloat() / cost.toFloat()).coerceIn(0f, 1f)
        val overflow = if (estimate >= cost) {
            ((estimate - cost).toFloat() / cost.toFloat()).coerceIn(0f, 1f)
        } else 0f
        val confidence = when {
            isRange -> EnergyConfidence.RANGE
            else -> confidenceHint
        }
        return ChargedPrediction(
            move = charged,
            fastMovesRemaining = remaining,
            progress = progress,
            ready = maxEnergy >= cost,
            definitelyReady = minEnergy >= cost,
            currentEnergy = estimate,
            energyCost = cost,
            minEnergy = minEnergy,
            maxEnergy = maxEnergy,
            confidence = confidence,
            overflowProgress = overflow
        )
    }

    private fun normalizeRange() {
        minEnergy = minEnergy.coerceIn(0, maxEnergyCap)
        maxEnergy = maxEnergy.coerceIn(0, maxEnergyCap)
        if (minEnergy > maxEnergy) minEnergy = maxEnergy
    }
}
