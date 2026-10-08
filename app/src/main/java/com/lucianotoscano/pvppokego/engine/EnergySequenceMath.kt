package com.lucianotoscano.pvppokego.engine

import kotlin.math.ceil

/**
 * Residual-energy move-count math, equivalent to the useful core idea exposed by PokéMoves.
 * It deliberately never resets energy to zero after a Charged Move.
 */
internal object EnergySequenceMath {
    fun repeatedChargedCounts(
        currentEnergy: Int,
        fastEnergyGain: Int,
        chargedCost: Int,
        steps: Int = 4,
        energyCap: Int = 100
    ): List<Int> {
        if (fastEnergyGain <= 0 || chargedCost <= 0 || steps <= 0) return emptyList()
        var energy = currentEnergy.coerceIn(0, energyCap)
        return buildList {
            repeat(steps.coerceAtMost(12)) {
                val missing = (chargedCost - energy).coerceAtLeast(0)
                val fasts = if (missing == 0) 0 else ceil(missing.toDouble() / fastEnergyGain.toDouble()).toInt()
                add(fasts)
                energy = (energy + fasts * fastEnergyGain).coerceAtMost(energyCap)
                if (energy < chargedCost) return@repeat
                energy -= chargedCost
            }
        }
    }
}
