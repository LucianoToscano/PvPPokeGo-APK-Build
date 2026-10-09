package com.lucianotoscano.pvppokego.arena

import kotlin.math.pow
import kotlin.math.roundToInt

/** Never accept a battle detected only by local OCR as a rated Arena match. */
enum class ArenaOutcome { WIN, LOSS, DRAW }

/** Opponent and player reports must match for a match to be provisionally validated. */
data class ArenaMatchReport(
    val matchId: String,
    val seasonId: String,
    val leagueCp: Int,
    val playerId: String,
    val opponentId: String,
    val outcome: ArenaOutcome,
    val recordedAtMs: Long
)

data class ArenaApprovedMatch(
    val matchId: String,
    val seasonId: String,
    val leagueCp: Int,
    val firstPlayer: String,
    val secondPlayer: String,
    val firstPlayerResult: ArenaOutcome,
    val recordedAtMs: Long
)

data class ArenaStanding(
    val playerId: String,
    val rating: Int,
    val matches: Int,
    val wins: Int,
    val losses: Int,
    val draws: Int
)

/**
 * Pure offline rules, reusable by a future authoritative server.
 * Dual agreement is a minimum prerequisite, not protection against collusion:
 * moderators and a real backend must approve prize-bearing results.
 */
object ArenaCompetitionEngine {
    fun approvedMatches(reports: List<ArenaMatchReport>): List<ArenaApprovedMatch> {
        return reports.groupBy { it.matchId }.mapNotNull { (id, pair) ->
            if (id.isBlank() || pair.size != 2) return@mapNotNull null
            val a = pair[0]
            val b = pair[1]
            if (a.playerId.isBlank() || b.playerId.isBlank() ||
                a.playerId == b.playerId || a.playerId != b.opponentId ||
                b.playerId != a.opponentId || a.seasonId.isBlank() ||
                a.seasonId != b.seasonId || a.leagueCp != b.leagueCp ||
                a.leagueCp !in setOf(1500, 2500, 10000)
            ) return@mapNotNull null
            val reconciled = when (a.outcome) {
                ArenaOutcome.WIN -> b.outcome == ArenaOutcome.LOSS
                ArenaOutcome.LOSS -> b.outcome == ArenaOutcome.WIN
                ArenaOutcome.DRAW -> b.outcome == ArenaOutcome.DRAW
            }
            if (!reconciled) return@mapNotNull null
            ArenaApprovedMatch(
                matchId = id,
                seasonId = a.seasonId,
                leagueCp = a.leagueCp,
                firstPlayer = a.playerId,
                secondPlayer = b.playerId,
                firstPlayerResult = a.outcome,
                recordedAtMs = maxOf(a.recordedAtMs, b.recordedAtMs)
            )
        }.sortedWith(compareBy<ArenaApprovedMatch> { it.recordedAtMs }.thenBy { it.matchId })
    }

    fun standings(
        matches: List<ArenaApprovedMatch>,
        seasonId: String,
        leagueCp: Int
    ): List<ArenaStanding> {
        val table = mutableMapOf<String, ArenaStanding>()
        for (match in matches.filter { it.seasonId == seasonId && it.leagueCp == leagueCp }
            .distinctBy { it.matchId }.sortedWith(
                compareBy<ArenaApprovedMatch> { it.recordedAtMs }.thenBy { it.matchId }
            )
        ) {
            if (match.firstPlayer == match.secondPlayer ||
                match.firstPlayer.isBlank() || match.secondPlayer.isBlank()
            ) continue
            val a = table[match.firstPlayer] ?: ArenaStanding(match.firstPlayer, 1000, 0, 0, 0, 0)
            val b = table[match.secondPlayer] ?: ArenaStanding(match.secondPlayer, 1000, 0, 0, 0, 0)
            val scoreA = when (match.firstPlayerResult) {
                ArenaOutcome.WIN -> 1.0
                ArenaOutcome.LOSS -> 0.0
                ArenaOutcome.DRAW -> 0.5
            }
            val expectedA = 1.0 / (1.0 + 10.0.pow((b.rating - a.rating) / 400.0))
            val delta = (32.0 * (scoreA - expectedA)).roundToInt()
            table[a.playerId] = a.copy(
                rating = (a.rating + delta).coerceAtLeast(100),
                matches = a.matches + 1,
                wins = a.wins + if (scoreA == 1.0) 1 else 0,
                losses = a.losses + if (scoreA == 0.0) 1 else 0,
                draws = a.draws + if (scoreA == 0.5) 1 else 0
            )
            table[b.playerId] = b.copy(
                rating = (b.rating - delta).coerceAtLeast(100),
                matches = b.matches + 1,
                wins = b.wins + if (scoreA == 0.0) 1 else 0,
                losses = b.losses + if (scoreA == 1.0) 1 else 0,
                draws = b.draws + if (scoreA == 0.5) 1 else 0
            )
        }
        return table.values.sortedWith(compareByDescending<ArenaStanding> { it.rating }
            .thenByDescending { it.wins }.thenBy { it.playerId })
    }

    /** Seeded first round. Requires power-of-two entrants until byes are implemented. */
    fun firstRound(entrantIds: List<String>): List<Pair<String, String>> {
        require(entrantIds.size in 2..64 && entrantIds.size.countOneBits() == 1) {
            "O torneio exige 2, 4, 8, 16, 32 ou 64 participantes"
        }
        require(entrantIds.all { it.isNotBlank() } && entrantIds.distinct().size == entrantIds.size) {
            "Participantes inválidos ou duplicados"
        }
        return (0 until entrantIds.size / 2).map { index ->
            entrantIds[index] to entrantIds[entrantIds.lastIndex - index]
        }
    }
}
