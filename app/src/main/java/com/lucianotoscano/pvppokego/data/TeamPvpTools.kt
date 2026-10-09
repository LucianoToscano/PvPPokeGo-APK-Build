package com.lucianotoscano.pvppokego.data

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

data class IvLeagueResult(
    val rank: Int,
    val total: Int,
    val level: Double,
    val cp: Int,
    val statProduct: Double,
    val percentOfBest: Double,
    val maximumCpAtLevel50: Int
)

/** Deterministic ranking across 4096 IV combinations, using the app's own PvPoke CPM snapshot. */
object TeamPvpTools {
    private data class Best(val cp: Int, val level: Double, val product: Double)

    fun cpAt(base: PokemonBaseStats, attackIv: Int, defenseIv: Int, hpIv: Int, cpm: Double): Int {
        if (cpm <= 0 || base.atk <= 0 || base.def <= 0 || base.hp <= 0) return 0
        return floor(
            (base.atk + attackIv) * sqrt((base.def + defenseIv).toDouble()) *
                sqrt((base.hp + hpIv).toDouble()) * cpm * cpm / 10.0
        ).toInt().coerceAtLeast(10)
    }

    private fun best(base: PokemonBaseStats, atk: Int, def: Int, hp: Int,
        cpms: List<Double>, limit: Int): Best? {
        var selected: Best? = null
        cpms.forEachIndexed { index, cpm ->
            if (index > 100) return@forEachIndexed // up to level 51; no assumptions past this
            val cp = cpAt(base, atk, def, hp, cpm)
            if (cp <= limit && cp >= 10) {
                val level = 1 + index * .5
                val atkStat = (base.atk + atk) * cpm
                val defStat = (base.def + def) * cpm
                val hpStat = floor((base.hp + hp) * cpm).coerceAtLeast(10.0)
                val product = atkStat * defStat * hpStat
                if (selected == null || product > selected!!.product + 1e-7) {
                    selected = Best(cp, level, product)
                }
            }
        }
        return selected
    }

    fun rank(
        base: PokemonBaseStats,
        cpms: List<Double>,
        leagueCap: Int,
        attackIv: Int?,
        defenseIv: Int?,
        hpIv: Int?
    ): IvLeagueResult? {
        if (leagueCap !in 1500..10000 || cpms.size < 99) return null
        val a = attackIv?.takeIf { it in 0..15 } ?: return null
        val d = defenseIv?.takeIf { it in 0..15 } ?: return null
        val h = hpIv?.takeIf { it in 0..15 } ?: return null
        val mine = best(base, a, d, h, cpms, leagueCap) ?: return null
        var bestProduct = 0.0
        var higher = 0
        var valid = 0
        for (ia in 0..15) for (id in 0..15) for (ih in 0..15) {
            val other = best(base, ia, id, ih, cpms, leagueCap) ?: continue
            valid++
            if (other.product > bestProduct) bestProduct = other.product
            if (other.product > mine.product + 1e-7) higher++
        }
        val level50 = cpms.getOrNull(98)
        return IvLeagueResult(
            rank = higher + 1, total = valid, level = mine.level, cp = mine.cp,
            statProduct = mine.product,
            percentOfBest = if (bestProduct > 0) 100.0 * mine.product / bestProduct else 0.0,
            maximumCpAtLevel50 = if (level50 != null) cpAt(base, a, d, h, level50) else 0
        )
    }

    data class MoveSummary(
        val fastName: String, val energyPerTurn: Double, val damagePerTurn: Double,
        val chargedName: String, val chargedCost: Int, val damagePerEnergy: Double,
        val fastMovesNeeded: Int, val secondsNeeded: Double
    )
    /** Raw move efficiency only; no deceptive assertion about full-matchup ranking. */
    fun moveSummary(fast: MoveDef, charged: MoveDef): MoveSummary? {
        if (fast.energyGain <= 0 || charged.chargedCost <= 0) return null
        val turns = fast.turns.coerceAtLeast(1)
        val needed = ceil(charged.chargedCost.toDouble() / fast.energyGain).toInt()
        return MoveSummary(
            fast.name, fast.energyGain.toDouble() / turns,
            fast.power.toDouble() / turns,
            charged.name, charged.chargedCost,
            charged.power.toDouble() / charged.chargedCost,
            needed, needed * turns * .5
        )
    }
}
