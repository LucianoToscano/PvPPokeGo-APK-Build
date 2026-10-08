package com.lucianotoscano.pvppokego.engine

import com.lucianotoscano.pvppokego.data.DamageConfidence
import com.lucianotoscano.pvppokego.data.ObservedDamageEstimate
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Learns observed HP loss inside the current battle only.
 *
 * Values are normalized to the visible HP bar: 1.0 = 100% = 1000 normalized points.
 * This is deliberately not presented as the Pokémon's real HP stat because real HP
 * depends on species, level and IVs.
 */
internal class ObservedDamageTracker {
    private data class Key(
        val attacker: String,
        val defender: String,
        val moveKey: String
    )

    private val samples = linkedMapOf<Key, ArrayDeque<Float>>()

    fun resetBattle() {
        samples.clear()
    }

    fun record(
        attackerKey: String?,
        defenderKey: String?,
        moveKey: String,
        totalDamageFraction: Float?,
        hits: Int = 1,
        confidence: Float = 1f,
        charged: Boolean = false
    ): Boolean {
        val attacker = attackerKey?.takeIf { it.isNotBlank() } ?: return false
        val defender = defenderKey?.takeIf { it.isNotBlank() } ?: return false
        val damage = totalDamageFraction ?: return false
        if (hits <= 0 || confidence < MIN_OBSERVATION_CONFIDENCE) return false

        val perHit = (damage / hits.toFloat()).coerceAtLeast(0f)
        val maxAllowed = if (charged) MAX_CHARGED_FRACTION else MAX_FAST_FRACTION
        val minAllowed = if (charged) MIN_CHARGED_FRACTION else MIN_FAST_FRACTION
        if (perHit !in minAllowed..maxAllowed) return false

        val key = Key(attacker, defender, moveKey)
        val queue = samples.getOrPut(key) { ArrayDeque() }

        // Once we have a baseline, reject a single wildly different bar reading instead
        // of letting HP animation/cropping noise ruin the learned average.
        if (queue.size >= 2) {
            val median = queue.sorted()[queue.size / 2]
            val tolerance = maxOf(MIN_ABSOLUTE_OUTLIER_TOLERANCE, median * RELATIVE_OUTLIER_TOLERANCE)
            if (abs(perHit - median) > tolerance) return false
        }

        queue.add(perHit)
        while (queue.size > MAX_SAMPLES_PER_KEY) queue.removeFirst()
        return true
    }

    fun estimate(
        attackerKey: String?,
        defenderKey: String?,
        moveKey: String,
        currentHpRatio: Float?
    ): ObservedDamageEstimate? {
        val attacker = attackerKey?.takeIf { it.isNotBlank() } ?: return null
        val defender = defenderKey?.takeIf { it.isNotBlank() } ?: return null
        val values = samples[Key(attacker, defender, moveKey)]?.toList().orEmpty()
        if (values.isEmpty()) return null

        val sorted = values.sorted()
        val robust = if (sorted.size >= 5) sorted.drop(1).dropLast(1) else sorted
        val average = robust.average().toFloat().coerceIn(0f, 1f)
        val minimum = sorted.first().coerceIn(0f, 1f)
        val maximum = sorted.last().coerceIn(0f, 1f)
        val projected = currentHpRatio?.let { (it - average).coerceIn(0f, 1f) }

        val confidence = when {
            values.size >= 5 -> DamageConfidence.STABLE
            values.size >= 2 -> DamageConfidence.ESTIMATED
            else -> DamageConfidence.FIRST_SAMPLE
        }

        return ObservedDamageEstimate(
            averagePercent = average * 100f,
            minPercent = minimum * 100f,
            maxPercent = maximum * 100f,
            averagePoints = (average * 1000f).roundToInt(),
            samples = values.size,
            projectedRemainingPercent = projected?.times(100f),
            confidence = confidence
        )
    }

    companion object {
        const val FAST_MOVE_KEY = "__FAST_OBSERVED__"

        private const val MIN_OBSERVATION_CONFIDENCE = 0.68f
        private const val MIN_FAST_FRACTION = 0.002f
        private const val MAX_FAST_FRACTION = 0.18f
        private const val MIN_CHARGED_FRACTION = 0.010f
        private const val MAX_CHARGED_FRACTION = 0.95f
        private const val MIN_ABSOLUTE_OUTLIER_TOLERANCE = 0.025f
        private const val RELATIVE_OUTLIER_TOLERANCE = 0.60f
        private const val MAX_SAMPLES_PER_KEY = 12
    }
}
