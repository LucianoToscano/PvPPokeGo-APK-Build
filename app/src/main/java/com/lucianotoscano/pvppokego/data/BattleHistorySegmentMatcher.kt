package com.lucianotoscano.pvppokego.data

/**
 * Rejoins history fragments that clearly belong to the same PvP match.
 *
 * False splits can happen when OCR disappears during Charged Move, switch or recorder
 * transitions. Merging is deliberately conservative: a confirmed result always closes
 * the match, league must agree, the gap must be short, and there must be continuity
 * evidence (same participant/team evidence) unless the new fragment explicitly started
 * mid-battle almost immediately after an inactivity split.
 */
internal object BattleHistorySegmentMatcher {
    private const val MAX_MERGE_GAP_MS = 20_000L
    private const val MAX_CONFIRMED_GAP_MS = 7_000L
    private const val STRONG_SHORT_GAP_MS = 3_500L

    fun shouldMerge(previous: BattleHistoryEntry, next: BattleHistoryEntry): Boolean {
        if (previous.result != null) return false
        if (previous.leagueCp != next.leagueCp) return false

        val gap = next.startedAtEpochMs - previous.endedAtEpochMs
        if (gap < -1_000L || gap > MAX_MERGE_GAP_MS) return false

        val previousLooksFragmented = previous.endReason
            ?.contains("evid", ignoreCase = true) == true ||
            previous.endReason?.contains("captura", ignoreCase = true) == true ||
            previous.endReason?.contains("tempor", ignoreCase = true) == true

        val ownOverlap = participantKeys(previous, "VOCÊ")
            .intersect(participantKeys(next, "VOCÊ"))
            .isNotEmpty()
        val enemyOverlap = participantKeys(previous, "INIMIGO")
            .intersect(participantKeys(next, "INIMIGO"))
            .isNotEmpty()

        val exactOwn = sameHeaderPokemon(
            previous.playerName, previous.playerCp,
            next.playerName, next.playerCp
        )
        val exactEnemy = sameHeaderPokemon(
            previous.opponentName, previous.opponentCp,
            next.opponentName, next.opponentCp
        )

        // Mere proximity cannot connect unknown sessions. Otherwise an empty
        // observation ending before the next match could silently merge two battles.
        val ownContinuity = ownOverlap || exactOwn
        val enemyContinuity = enemyOverlap || exactEnemy
        if (!ownContinuity && !enemyContinuity) return false

        // Interruption evidence must exist on both sides: a recorder gap and a
        // fragment explicitly observed mid-battle. Without both, a fast rematch
        // with the same lead would be irreversibly merged.
        if (!previousLooksFragmented || !next.startedMidBattle) return false

        // Short gaps with both participants verified are safer; one-sided
        // continuity is permitted only for an almost immediate resumed frame.
        return if (ownContinuity && enemyContinuity) {
            gap <= MAX_CONFIRMED_GAP_MS
        } else {
            gap <= STRONG_SHORT_GAP_MS
        }
    }

    fun merge(previous: BattleHistoryEntry, next: BattleHistoryEntry): BattleHistoryEntry {
        val offset = (next.startedAtEpochMs - previous.startedAtEpochMs).coerceAtLeast(0L)

        val previousEvents = previous.events.filterNot {
            it.actor == "PARTIDA" && it.category == "FIM"
        }
        val nextEvents = next.events.filterNot {
            it.actor == "PARTIDA" && it.category == "INÍCIO"
        }.map { event ->
            event.copy(elapsedMs = (event.elapsedMs + offset).coerceAtLeast(0L))
        }

        val boundary = BattleHistoryEvent(
            elapsedMs = offset,
            actor = "PARTIDA",
            category = "CONTINUIDADE",
            moveName = "Trecho reconectado à mesma partida",
            source = "session-matcher",
            confidence = "CONFIRMADO",
            reason = "Intervalo curto com identidade compatível; estado preservado como uma única batalha"
        )

        return BattleHistoryEntry(
            id = previous.id,
            startedAtEpochMs = previous.startedAtEpochMs,
            endedAtEpochMs = maxOf(previous.endedAtEpochMs, next.endedAtEpochMs),
            leagueCp = previous.leagueCp,
            playerName = previous.playerName ?: next.playerName,
            playerCp = previous.playerCp ?: next.playerCp,
            opponentName = previous.opponentName ?: next.opponentName,
            opponentCp = previous.opponentCp ?: next.opponentCp,
            appVersion = next.appVersion.takeUnless { it == "desconhecida" } ?: previous.appVersion,
            dataVersion = next.dataVersion ?: previous.dataVersion,
            startedMidBattle = previous.startedMidBattle,
            endReason = next.endReason,
            result = next.result,
            events = (previousEvents + boundary + nextEvents).sortedBy { it.elapsedMs }
        )
    }

    fun repair(entries: List<BattleHistoryEntry>): List<BattleHistoryEntry> {
        if (entries.size < 2) return entries
        val chronological = entries.sortedBy { it.startedAtEpochMs }
        val merged = mutableListOf<BattleHistoryEntry>()
        chronological.forEach { current ->
            val previous = merged.lastOrNull()
            if (previous != null && shouldMerge(previous, current)) {
                merged[merged.lastIndex] = merge(previous, current)
            } else {
                merged += current
            }
        }
        return merged.sortedByDescending { it.startedAtEpochMs }
    }

    private fun sameHeaderPokemon(
        firstName: String?,
        firstCp: Int?,
        secondName: String?,
        secondCp: Int?
    ): Boolean {
        if (firstName.isNullOrBlank() || secondName.isNullOrBlank()) return false
        if (!firstName.equals(secondName, ignoreCase = true)) return false
        return firstCp == null || secondCp == null || firstCp == secondCp
    }

    private fun participantKeys(entry: BattleHistoryEntry, actor: String): Set<String> {
        val keys = linkedSetOf<String>()
        fun add(name: String?, cp: Int?) {
            if (name.isNullOrBlank()) return
            keys += normalize(name) + ":" + (cp?.toString() ?: "?")
            keys += normalize(name) + ":*"
        }

        if (actor == "VOCÊ") add(entry.playerName, entry.playerCp)
        if (actor == "INIMIGO") add(entry.opponentName, entry.opponentCp)

        entry.events.asSequence()
            .filter { it.actor == actor }
            .forEach { event ->
                val cp = event.details["pc"]?.toIntOrNull()
                add(event.pokemon, cp)
                event.details["para"]?.let { add(it, cp) }
                event.details["de"]?.let { add(it, null) }
            }
        return keys
    }

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]+"), "")
}
