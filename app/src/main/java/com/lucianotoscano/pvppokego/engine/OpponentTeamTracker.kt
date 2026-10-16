package com.lucianotoscano.pvppokego.engine

/**
 * Tracks at most three opponents by an evidence-backed stable identity + CP.
 * A switch does not discard HP, energy or revealed moves: BattleStateStore is keyed
 * by this tracker's slot index. No OCR guess is promoted into a new slot when
 * the three trusted places have already been occupied.
 */
class OpponentTeamTracker {
    data class Slot(
        val index: Int,
        val name: String?,
        val speciesId: String?,
        val cp: Int?,
        val hpRatio: Float?,
        val active: Boolean,
        val fainted: Boolean,
        val confidence: Float,
        val lastSeenAtMs: Long
    )

    private data class Member(
        var name: String,
        var speciesId: String?,
        var cp: Int?,
        var hpRatio: Float? = null,
        var fainted: Boolean = false,
        var confidence: Float = 0f,
        var lastSeenAtMs: Long = 0L
    )

    private val team = mutableListOf<Member>()
    private var current = -1

    fun clear() {
        team.clear()
        current = -1
    }

    fun activeSlot(): Int? = current.takeIf { it in team.indices }

    fun observe(
        name: String,
        speciesId: String?,
        cp: Int?,
        confidence: Float = 1f,
        nowMs: Long = System.currentTimeMillis()
    ): Int? {
        if (name.isBlank() || !confidence.isFinite() || confidence < 0.5f) return null
        val reliableCp = cp?.takeIf { it in 10..10000 }
        val normalizedId = (speciesId?.takeIf(String::isNotBlank) ?: name).trim().lowercase()
        if (normalizedId.isEmpty()) return null
        fun matches(member: Member): Boolean =
            (member.speciesId?.takeIf(String::isNotBlank) ?: member.name).trim().lowercase() == normalizedId

        // The CP differentiates two members of the same species or form.
        var found = if (reliableCp != null) team.indexOfFirst { matches(it) && it.cp == reliableCp } else -1
        if (found < 0) {
            val candidates = team.withIndex().filter { matches(it.value) }
            found = candidates.firstOrNull { it.value.cp == null }?.index ?: -1
            if (found < 0 && reliableCp == null && candidates.size == 1) found = candidates[0].index
            if (found < 0 && reliableCp == null && candidates.size > 1) {
                // A hidden CP cannot identify which duplicate is on screen. Invalidate
                // only the active pointer; preserve all trusted HP and move histories.
                current = -1
                return null
            }
        }
        if (found < 0 && reliableCp != null) {
            // OCR species label -> precise visual form upgrade, only for an exact card CP.
            val upgrades = team.withIndex().filter {
                it.value.cp == reliableCp && it.value.name.equals(name, ignoreCase = true)
            }
            if (upgrades.size == 1) found = upgrades.single().index
        }
        if (found < 0) {
            if (team.size >= 3) {
                // A spurious fourth OCR identity must not attach its subsequent HP
                // observations to the last trusted opponent (historical regression R02).
                current = -1
                return null
            }
            team += Member(name = name, speciesId = speciesId, cp = reliableCp)
            found = team.lastIndex
        }
        team[found].apply {
            this.name = name
            if (!speciesId.isNullOrBlank()) this.speciesId = speciesId
            if (reliableCp != null) this.cp = reliableCp
            this.confidence = maxOf(this.confidence, confidence.coerceIn(0f, 1f))
            this.lastSeenAtMs = nowMs
        }
        current = found
        return found
    }

    fun updateHp(ratio: Float, nowMs: Long = System.currentTimeMillis()) {
        if (current !in team.indices || !ratio.isFinite() || ratio !in 0f..1f) return
        team[current].apply {
            hpRatio = ratio
            lastSeenAtMs = nowMs
            if (ratio <= .01f) fainted = true
        }
    }

    fun slots(): List<Slot> = (0..2).map { index ->
        val m = team.getOrNull(index)
        Slot(
            index = index,
            name = m?.name,
            speciesId = m?.speciesId,
            cp = m?.cp,
            hpRatio = m?.hpRatio,
            active = m != null && index == current,
            fainted = m?.fainted == true,
            confidence = m?.confidence ?: 0f,
            lastSeenAtMs = m?.lastSeenAtMs ?: 0L
        )
    }
}
