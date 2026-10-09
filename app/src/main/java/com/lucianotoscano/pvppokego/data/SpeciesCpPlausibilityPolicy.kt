package com.lucianotoscano.pvppokego.data

import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Sanity gate for species/CP pairs before an OCR or visual identity is trusted.
 * CP may be absent; this guard never fabricates it. Uses bundled Game Master and
 * CP multipliers (up to level 51 including Best Buddy), not a network lookup.
 */
internal object SpeciesCpPlausibilityPolicy {
    fun maximumCp(base: PokemonBaseStats, multipliers: List<Double>): Int? {
        if (base.atk <= 0 || base.def <= 0 || base.hp <= 0) return null
        val cpm = multipliers.take(101).lastOrNull()?.takeIf { it > 0.0 } ?: return null
        val raw = (base.atk + 15.0) * sqrt(base.def + 15.0) *
            sqrt(base.hp + 15.0) * cpm * cpm / 10.0
        return floor(raw).toInt().coerceAtLeast(10)
    }

    fun isPlausible(cp: Int?, base: PokemonBaseStats, multipliers: List<Double>): Boolean {
        if (cp == null) return true // Name-only OCR is not the same as a disproven CP.
        if (cp !in 10..10_000) return false
        val maxCp = maximumCp(base, multipliers) ?: return true // Missing data: don't discard identity.
        return cp <= maxCp
    }
}
