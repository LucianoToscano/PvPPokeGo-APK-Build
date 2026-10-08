package com.lucianotoscano.pvppokego.engine

/**
 * Decides whether the native reserve cards still need OCR/visual scanning.
 *
 * Mid-battle starts are valid: one stable active Pokémon is enough to scan the two
 * reserve cards and reconstruct the rest of the team from card CP + visual evidence.
 */
internal object ReserveScanPolicy {
    fun shouldScan(
        hasActivePlayer: Boolean,
        knownTeamSize: Int,
        cardCps: List<Int?>,
        cardDexes: List<Int?>,
        cardSpeciesIds: List<String?>,
        cardMappingConfirmed: List<Boolean>
    ): Boolean {
        if (!hasActivePlayer || knownTeamSize <= 0) return false
        val count = maxOf(cardCps.size, cardDexes.size, cardSpeciesIds.size, 2)
        return (0 until count.coerceAtMost(2)).any { index ->
            val cpMissing = cardCps.getOrNull(index) == null
            val visualMissing =
                cardSpeciesIds.getOrNull(index).isNullOrBlank() &&
                    cardDexes.getOrNull(index) == null

            // Reading a CP does not prove which team member occupies this card:
            // duplicate/unmatched CPs still need visual evidence. Only the matcher
            // may confirm the binding (including form-aware evidence/elimination).
            val mappingMissing = cardMappingConfirmed.getOrNull(index) != true
            cpMissing || mappingMissing || (knownTeamSize < 3 && visualMissing)
        }
    }
}

