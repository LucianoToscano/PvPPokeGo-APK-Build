package com.lucianotoscano.pvppokego.engine

/**
 * Maps Pokemon GO's two native reserve cards to the user's stable team identities.
 *
 * Evidence priority:
 * 1) CP from the native card (authoritative when unique);
 * 2) visual National Dex fingerprint (only when unique among remaining team members);
 * 3) elimination when the other reserve is already confirmed;
 * 4) stable slot order as an UNCONFIRMED display fallback.
 *
 * This keeps a weak visual guess from ever overriding a unique CP match.
 */
internal object ReserveCardMatcher {
    data class Member(
        val slot: Int,
        val name: String,
        val cp: Int?,
        val dex: Int? = null,
        val speciesId: String? = null
    )

    data class Match(
        val member: Member,
        val cardCp: Int?,
        val confirmed: Boolean,
        val cardIndex: Int = -1,
        val cardDex: Int? = null,
        val cardSpeciesId: String? = null,
        val source: String = "fallback"
    )

    fun resolve(
        team: List<Member>,
        activeName: String?,
        activeCp: Int?,
        cardCps: List<Int?>,
        cardDexes: List<Int?> = emptyList(),
        cardSpeciesIds: List<String?> = emptyList(),
        activeSpeciesId: String? = null
    ): List<Match> {
        if (team.isEmpty()) return emptyList()

        // Exact stable form is stronger than a display name shared by regional variants.
        // Without it, preserve the existing name+CP fallback for older team snapshots.
        val active = activeSpeciesId?.takeIf(String::isNotBlank)?.let { exact ->
            team.singleOrNull {
                !it.speciesId.isNullOrBlank() && normalize(it.speciesId) == normalize(exact)
            }
        } ?: team.firstOrNull {
            it.name.equals(activeName, ignoreCase = true) &&
                (activeCp == null || it.cp == null || it.cp == activeCp)
        } ?: team.firstOrNull { it.name.equals(activeName, ignoreCase = true) }

        val reserves = team.filter { it.slot != active?.slot }
        if (reserves.isEmpty()) return emptyList()

        // Always keep both native card positions addressable. With only one known
        // reserve identity we still need to match it to card #2 if its CP/image says so.
        val result = arrayOfNulls<Match>(2)
        val used = mutableSetOf<Int>()

        // CP is the strongest identity signal because it comes from the same native card.
        result.indices.forEach { cardIndex ->
            val cp = cardCps.getOrNull(cardIndex) ?: return@forEach
            val matches = reserves.filter { it.cp == cp && it.slot !in used }
            if (matches.size == 1) {
                val member = matches.first()
                result[cardIndex] = Match(
                    member = member,
                    cardCp = cp,
                    cardDex = cardDexes.getOrNull(cardIndex),
                    cardSpeciesId = cardSpeciesIds.getOrNull(cardIndex),
                    confirmed = true,
                    cardIndex = cardIndex,
                    source = "cp"
                )
                used += member.slot
            }
        }

        // Form-aware visual speciesId is stronger than dex-only evidence and can
        // distinguish regional/forms that share the same National Dex.
        result.indices.forEach { cardIndex ->
            if (result[cardIndex] != null) return@forEach
            val speciesId = cardSpeciesIds.getOrNull(cardIndex)?.takeIf(String::isNotBlank)
                ?: return@forEach
            val matches = reserves.filter {
                it.slot !in used &&
                    !it.speciesId.isNullOrBlank() &&
                    normalize(it.speciesId) == normalize(speciesId)
            }
            if (matches.size == 1) {
                val member = matches.first()
                result[cardIndex] = Match(
                    member = member,
                    cardCp = cardCps.getOrNull(cardIndex),
                    cardDex = cardDexes.getOrNull(cardIndex),
                    cardSpeciesId = speciesId,
                    confirmed = true,
                    cardIndex = cardIndex,
                    source = "image-form"
                )
                used += member.slot
            }
        }

        // Visual evidence is independent of OCR. Only a unique dex among remaining
        // reserve members may confirm a card; ambiguous forms remain unconfirmed.
        result.indices.forEach { cardIndex ->
            if (result[cardIndex] != null) return@forEach
            val dex = cardDexes.getOrNull(cardIndex) ?: return@forEach
            val matches = reserves.filter {
                it.slot !in used && it.dex != null && it.dex == dex
            }
            if (matches.size == 1) {
                val member = matches.first()
                result[cardIndex] = Match(
                    member = member,
                    cardCp = cardCps.getOrNull(cardIndex),
                    cardDex = dex,
                    cardSpeciesId = cardSpeciesIds.getOrNull(cardIndex),
                    confirmed = true,
                    cardIndex = cardIndex,
                    source = "image"
                )
                used += member.slot
            }
        }

        // If one reserve is confirmed, the other card is fixed by elimination.
        if (reserves.size == 2 && used.size == 1) {
            val remainingMember = reserves.first { it.slot !in used }
            val emptyIndex = result.indexOfFirst { it == null }
            if (emptyIndex >= 0) {
                result[emptyIndex] = Match(
                    member = remainingMember,
                    cardCp = cardCps.getOrNull(emptyIndex),
                    cardDex = cardDexes.getOrNull(emptyIndex),
                    cardSpeciesId = cardSpeciesIds.getOrNull(emptyIndex),
                    confirmed = true,
                    cardIndex = emptyIndex,
                    source = "elimination"
                )
                used += remainingMember.slot
            }
        }

        // Stable original team order is only a visual placeholder and never trusted for
        // matchup/type recommendations until another signal confirms it.
        result.indices.forEach { index ->
            if (result[index] == null) {
                val remaining = reserves.firstOrNull { it.slot !in used } ?: return@forEach
                result[index] = Match(
                    member = remaining,
                    cardCp = cardCps.getOrNull(index),
                    cardDex = cardDexes.getOrNull(index),
                    cardSpeciesId = cardSpeciesIds.getOrNull(index),
                    confirmed = false,
                    cardIndex = index,
                    source = "fallback"
                )
                used += remaining.slot
            }
        }

        return result.filterNotNull()
    }

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]+"), "")
}
