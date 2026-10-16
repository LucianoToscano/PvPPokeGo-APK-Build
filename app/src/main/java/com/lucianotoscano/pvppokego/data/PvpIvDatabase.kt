package com.lucianotoscano.pvppokego.data

import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * External IV4U references. IV4U is a reading/validation reference, NOT a scraped
 * dataset or a runtime API. All calculations below use the app's local PvPoke stats.
 */
object PvpIvReferences {
    const val IV4U_HOME = "https://iv4u.lima-city.de/"
    const val IV4U_CLEFABLE = "https://iv4u.lima-city.de/?l=en&m=3&p=Clefable"

    fun iv4uUrl(speciesName: String?, leagueCap: Int): String {
        val league = when (leagueCap) {
            500 -> 4 // Mini
            1500 -> 1 // Great
            2500 -> 2 // Ultra
            10000 -> 3 // Master
            else -> return IV4U_HOME
        }
        val name = speciesName?.trim()?.takeIf { it.length in 2..80 } ?: return IV4U_HOME
        // Never interpolate raw OCR text into an external URL.
        if (!name.all { it.isLetterOrDigit() || it in " .'-" }) return IV4U_HOME
        val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        return IV4U_HOME + "?l=en&m=3&m2=" + league + "&p=" + encoded
    }
}

/**
 * An offline PvP-IV stat-product entry, not a simulated battle/meta rank.
 * Effective Attack is useful for comparing theoretical CMP at the listed levels.
 */
data class PvPIvRankEntry(
    val rank: Int,
    val attackIv: Int,
    val defenseIv: Int,
    val hpIv: Int,
    val cp: Int,
    val level: Double,
    val attack: Double,
    val defense: Double,
    val hp: Int,
    val statProduct: Double,
    val percentOfBest: Double
)

enum class PvPIvCmp {
    FAVORS_OURS, FAVORS_THEIRS, TIE, UNKNOWN
}

/**
 * Database-on-demand of up to 200 highest-stat-product spreads (among 4096).
 * Shares the exact offline CPM + CP formula used by TeamPvpTools; no network,
 * background scraping, battle injection, or cached third-party website HTML.
 */
object PvpIvDatabase {
    private data class Candidate(
        val attackIv: Int, val defenseIv: Int, val hpIv: Int,
        val cp: Int, val level: Double, val attack: Double,
        val defense: Double, val hp: Int, val product: Double
    )

    private fun bestUnderCap(
        base: PokemonBaseStats, cpms: List<Double>, cap: Int,
        attackIv: Int, defenseIv: Int, hpIv: Int
    ): Candidate? {
        var best: Candidate? = null
        cpms.forEachIndexed { index, cpm ->
            if (index > 100 || !cpm.isFinite() || cpm <= 0.0) return@forEachIndexed
            val cp = TeamPvpTools.cpAt(base, attackIv, defenseIv, hpIv, cpm)
            if (cp !in 10..cap) return@forEachIndexed
            val attack = (base.atk + attackIv) * cpm
            val defense = (base.def + defenseIv) * cpm
            val hp = floor((base.hp + hpIv) * cpm).toInt().coerceAtLeast(10)
            val product = attack * defense * hp
            if (best == null || product > best!!.product + 1e-7) {
                best = Candidate(attackIv, defenseIv, hpIv, cp,
                    1.0 + index * 0.5, attack, defense, hp, product)
            }
        }
        return best
    }

    fun topSpreads(
        base: PokemonBaseStats, cpms: List<Double>, leagueCap: Int, maxRows: Int = 20
    ): List<PvPIvRankEntry> {
        if (leagueCap !in setOf(500, 1500, 2500, 10000) ||
            maxRows !in 1..200 || cpms.size < 99 ||
            base.atk <= 0 || base.def <= 0 || base.hp <= 0
        ) return emptyList()

        val candidates = ArrayList<Candidate>(4096)
        for (a in 0..15) for (d in 0..15) for (h in 0..15) {
            bestUnderCap(base, cpms, leagueCap, a, d, h)?.let(candidates::add)
        }
        val sorted = candidates.sortedWith(
            compareByDescending<Candidate> { it.product }
                .thenByDescending { it.cp }
                .thenByDescending { it.hp }
                .thenBy { it.attackIv }
                .thenByDescending { it.defenseIv }
                .thenByDescending { it.hpIv }
        )
        val topProduct = sorted.firstOrNull()?.product ?: return emptyList()
        var currentRank = 1
        return sorted.take(maxRows).mapIndexed { index, item ->
            if (index > 0 && item.product < sorted[index - 1].product - 1e-7) {
                currentRank = index + 1
            }
            PvPIvRankEntry(
                currentRank, item.attackIv, item.defenseIv, item.hpIv,
                item.cp, item.level, item.attack, item.defense, item.hp,
                item.product, 100.0 * item.product / topProduct
            )
        }
    }

    /** Use only for known IVs/levels; never infer hidden opponent IVs from rank. */
    fun effectiveAttack(
        base: PokemonBaseStats, attackIv: Int?, level: Double?,
        cpms: List<Double>
    ): Double? {
        if (base.atk <= 0 || attackIv == null || attackIv !in 0..15 ||
            level == null || !level.isFinite()
        ) return null
        val idx = ((level - 1.0) * 2.0).roundToInt()
        if (idx !in cpms.indices || idx > 100 ||
            abs(1.0 + idx * .5 - level) > .001
        ) return null
        val cpm = cpms[idx].takeIf { it.isFinite() && it > 0.0 } ?: return null
        return (base.atk + attackIv) * cpm
    }

    /** Applies only when BOTH charged moves begin on the same turn. */
    fun cmpIfSimultaneous(oursAttack: Double?, theirsAttack: Double?): PvPIvCmp {
        if (oursAttack == null || theirsAttack == null ||
            !oursAttack.isFinite() || !theirsAttack.isFinite()
        ) return PvPIvCmp.UNKNOWN
        return when {
            oursAttack > theirsAttack + 1e-9 -> PvPIvCmp.FAVORS_OURS
            theirsAttack > oursAttack + 1e-9 -> PvPIvCmp.FAVORS_THEIRS
            else -> PvPIvCmp.TIE
        }
    }
}
