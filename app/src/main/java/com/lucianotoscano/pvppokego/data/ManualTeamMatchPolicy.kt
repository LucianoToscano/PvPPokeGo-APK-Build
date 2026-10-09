package com.lucianotoscano.pvppokego.data

/**
 * Matches a CURRENT in-battle observation to the player's explicitly selected
 * roster. The roster is known, but which member is active is not assumed.
 * A strong visual ID contradicting a CP-pinned DIFFERENT slot must be rejected.
 */
object ManualTeamMatchPolicy {
    fun memberIndex(
        members: List<Pair<String, Int>>,
        visualSpeciesId: String?,
        cp: Int?,
        ocrSpeciesId: String?
    ): Int? {
        fun same(a: String, b: String) = a.equals(b, ignoreCase = true)
        val cpIndex = cp?.let { value ->
            members.indices.filter { members[it].second == value }.singleOrNull()
        }
        val visual = visualSpeciesId?.takeIf(String::isNotBlank)
        if (visual != null) {
            val visualIndices = members.indices.filter { same(members[it].first, visual) }
            if (cpIndex != null && cpIndex !in visualIndices) return null
            return if (cpIndex != null) cpIndex else visualIndices.singleOrNull()
        }
        if (cpIndex != null) return cpIndex
        val ocr = ocrSpeciesId?.takeIf(String::isNotBlank) ?: return null
        return members.indices.filter { same(members[it].first, ocr) &&
            (cp == null || members[it].second == cp) }.singleOrNull()
    }
}
