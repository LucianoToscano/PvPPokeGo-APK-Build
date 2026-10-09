package com.lucianotoscano.pvppokego.arena

import com.lucianotoscano.pvppokego.data.BattleHistoryEntry
import java.text.Normalizer

/** Offline activity only. These points are NEVER the competitive rating or prize entitlement. */
data class ArenaMedal(
    val name: String,
    val description: String,
    val current: Int,
    val target: Int
) {
    val earned: Boolean get() = current >= target
}

data class ArenaSnapshot(
    val recorded: Int,
    val victories: Int,
    val defeats: Int,
    val draws: Int,
    val unresolved: Int,
    val activityXp: Int,
    val bestStreak: Int,
    val distinctLeagues: Int,
    val medals: List<ArenaMedal>
) {
    val tier: String get() = when {
        activityXp >= 4000 -> "Mestre"
        activityXp >= 2500 -> "Diamante"
        activityXp >= 1500 -> "Platina"
        activityXp >= 750 -> "Ouro"
        activityXp >= 250 -> "Prata"
        else -> "Bronze"
    }
}

object ArenaProgress {
    private enum class Outcome { WIN, LOSS, DRAW, UNKNOWN }

    private fun outcome(raw: String?): Outcome {
        val normalized = Normalizer.normalize(raw.orEmpty(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .trim().lowercase()
        return when (normalized) {
            "vitoria", "victory", "win", "won" -> Outcome.WIN
            "derrota", "defeat", "loss", "lost" -> Outcome.LOSS
            "empate", "draw", "tie" -> Outcome.DRAW
            else -> Outcome.UNKNOWN
        }
    }

    /**
     * Only explicitly recorded outcomes count; no inference from KO, OCR confidence or
     * the sheer number of events. History is currently capped at 50 in BattleHistoryRepository,
     * so this is a snapshot of available records, NOT lifetime statistics.
     */
    fun fromHistory(entries: List<BattleHistoryEntry>): ArenaSnapshot {
        val distinct = entries.distinctBy { it.id }.sortedWith(
            compareBy<BattleHistoryEntry> { it.startedAtEpochMs }.thenBy { it.id }
        )
        val outcomes = distinct.map { outcome(it.result) }
        val victories = outcomes.count { it == Outcome.WIN }
        val defeats = outcomes.count { it == Outcome.LOSS }
        val draws = outcomes.count { it == Outcome.DRAW }
        var streak = 0
        var best = 0
        for (result in outcomes) {
            streak = if (result == Outcome.WIN) streak + 1 else 0
            best = maxOf(best, streak)
        }
        val leagues = distinct.filter { outcome(it.result) != Outcome.UNKNOWN }
            .map { it.leagueCp }.filter { it in setOf(1500, 2500, 10000) }.distinct().size
        return ArenaSnapshot(
            recorded = distinct.size,
            victories = victories,
            defeats = defeats,
            draws = draws,
            unresolved = outcomes.count { it == Outcome.UNKNOWN },
            activityXp = victories * 25 + defeats * 10 + draws * 15,
            bestStreak = best,
            distinctLeagues = leagues,
            medals = listOf(
                ArenaMedal("Primeira vitória", "1 vitória registrada", victories, 1),
                ArenaMedal("Veterano", "10 vitórias registradas", victories, 10),
                ArenaMedal("Conquistador", "25 vitórias registradas", victories, 25),
                ArenaMedal("Em sequência", "5 vitórias consecutivas", best, 5),
                ArenaMedal("Três ligas", "Resultados nas três ligas principais", leagues, 3)
            )
        )
    }
}
