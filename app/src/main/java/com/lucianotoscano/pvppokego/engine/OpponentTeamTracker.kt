package com.lucianotoscano.pvppokego.engine

/**
 * Only evidence-based identities receive a numbered opponent slot.
 * Unknown identities never become a guessed species; battle data remains read-only.
 */
class OpponentTeamTracker {
    data class Slot(
        val index: Int,
        val name: String?,
        val speciesId: String?,
        val cp: Int?,
        val hpRatio: Float?,
        val active: Boolean,
        val fainted: Boolean
    )

    private data class Observed(
        var identity: String,
        var name: String,
        var speciesId: String?,
        var cp: Int?,
        var hpRatio: Float? = null,
        var fainted: Boolean = false
    )

    private val observed = mutableListOf<Observed>()
    private var activeIndex = -1

    fun clear() {
        observed.clear()
        activeIndex = -1
    }

    fun observe(name: String, speciesId: String?, cp: Int?): Int? {
        if (name.isBlank()) return null
        val identity = (speciesId?.takeIf(String::isNotBlank) ?: name)
            .lowercase().trim()
        if (identity.isEmpty()) return null
        val reliableCp = cp?.takeIf { it in 10..10000 }
        var index = observed.indexOfFirst {
            it.identity == identity && reliableCp != null && it.cp == reliableCp
        }
        if (index < 0) {
            val candidates = observed.withIndex().filter { it.value.identity == identity }
            // A prior incomplete identity can be upgraded without inventing a new slot.
            index = candidates.firstOrNull { it.value.cp == null }?.index ?: -1
            if (index < 0 && candidates.size == 1 && reliableCp == null) {
                index = candidates.single().index
            }
            // Two observed copies of one species + unreadable CP are ambiguous.
            // Do not invent a third slot or switch identities.
            if (index < 0 && candidates.size > 1 && reliableCp == null) return null
        }
        if (index < 0 && reliableCp != null) {
            // Upgrade a formerly OCR-only species identity to an exact form ID
            // only when both its name and confirmed CP identify one slot.
            val sameCard = observed.withIndex().filter {
                it.value.name.equals(name, ignoreCase = true) && it.value.cp == reliableCp
            }
            if (sameCard.size == 1) index = sameCard.single().index
        }
        if (index < 0) {
            if (observed.size == 3) {
                activeIndex = -1
                return null // Never discard or relabel a previously trusted team slot.
            }
            observed += Observed(identity, name, speciesId, reliableCp)
            index = observed.lastIndex
        }
        val entry = observed[index]
        entry.identity = identity
        entry.name = name
        if (!speciesId.isNullOrBlank()) entry.speciesId = speciesId
        if (reliableCp != null) entry.cp = reliableCp
        activeIndex = index
        return index
    }

    fun updateHp(hpRatio: Float) {
        if (activeIndex !in observed.indices) return
        observed[activeIndex].apply {
            this.hpRatio = hpRatio.coerceIn(0f, 1f)
            if (hpRatio <= 0.01f) fainted = true
        }
    }

    fun slots(): List<Slot> = (0..2).map { index ->
        val seen = observed.getOrNull(index)
        Slot(
            index = index,
            name = seen?.name,
            speciesId = seen?.speciesId,
            cp = seen?.cp,
            hpRatio = seen?.hpRatio,
            active = index == activeIndex && seen != null,
            fainted = seen?.fainted == true
        )
    }
}
