package com.lucianotoscano.pvppokego.data

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Pure hidden-IV/level Attack scanner used by CMP.
 *
 * It does not guess an IV spread. It keeps the whole closest legal CP shell and returns
 * the minimum/maximum Attack across that shell. The caller may supply one likely Attack
 * only as a display point inside the mathematical interval.
 */
internal object AttackRangeEstimator {
    fun estimate(
        base: PokemonBaseStats,
        cpMultipliers: List<Double>,
        targetCp: Int,
        leagueCp: Int,
        likelyAttack: Double? = null,
        maxCpError: Int = 25
    ): AttackRangeEstimate? {
        if (
            base.atk <= 0 || base.def <= 0 || base.hp <= 0 ||
            cpMultipliers.isEmpty() || targetCp < 10
        ) return null

        val cpCap = if (leagueCp >= 10_000) Int.MAX_VALUE else leagueCp
        var bestError = Int.MAX_VALUE
        var minAttack = Double.POSITIVE_INFINITY
        var maxAttack = Double.NEGATIVE_INFINITY
        var samples = 0

        cpMultipliers.forEach { cpm ->
            if (!cpm.isFinite() || cpm <= 0.0) return@forEach
            for (atkIv in 0..15) {
                val attack = (base.atk + atkIv) * cpm
                for (defIv in 0..15) {
                    val partial = (base.atk + atkIv).toDouble() *
                        sqrt((base.def + defIv).toDouble()) *
                        cpm * cpm
                    for (hpIv in 0..15) {
                        val cp = floor(
                            partial * sqrt((base.hp + hpIv).toDouble()) / 10.0
                        ).toInt().coerceAtLeast(10)
                        if (cp > cpCap) continue
                        val error = abs(cp - targetCp)
                        when {
                            error < bestError -> {
                                bestError = error
                                minAttack = attack
                                maxAttack = attack
                                samples = 1
                            }
                            error == bestError -> {
                                minAttack = minOf(minAttack, attack)
                                maxAttack = maxOf(maxAttack, attack)
                                samples++
                            }
                        }
                    }
                }
            }
        }

        if (
            samples <= 0 || !minAttack.isFinite() || !maxAttack.isFinite() ||
            bestError > maxCpError
        ) return null

        val likely = likelyAttack
            ?.coerceIn(minAttack, maxAttack)
            ?: ((minAttack + maxAttack) / 2.0)

        return AttackRangeEstimate(
            minAttack = minAttack,
            likelyAttack = likely,
            maxAttack = maxAttack,
            sampleCount = samples,
            cpError = bestError
        )
    }
}
