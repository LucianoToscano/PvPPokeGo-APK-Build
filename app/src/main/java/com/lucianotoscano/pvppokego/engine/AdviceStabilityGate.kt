package com.lucianotoscano.pvppokego.engine

/**
 * Hysteresis for assistant recommendations. It prevents normal low-urgency advice from
 * flickering when OCR/visual confidence oscillates for one or two frames, while urgent
 * safety changes and ANALISANDO states still surface immediately.
 */
internal class AdviceStabilityGate(
    private val settleMs: Long = 550L,
    private val clearAfterMs: Long = 900L,
    private val urgentPriorityDelta: Int = 12
) {
    data class Key(val title: String, val detail: String, val priority: Int)

    private var current: Key? = null
    private var pending: Key? = null
    private var pendingSinceMs: Long = 0L
    private var nullSinceMs: Long? = null

    fun update(candidate: Key?, nowMs: Long): Key? {
        val active = current
        if (candidate == null) {
            pending = null
            if (active == null) return null
            val since = nullSinceMs ?: nowMs.also { nullSinceMs = it }
            if (nowMs - since >= clearAfterMs) {
                current = null
                nullSinceMs = null
            }
            return current
        }

        nullSinceMs = null
        if (active == null) {
            current = candidate
            pending = null
            return candidate
        }
        if (candidate.title == active.title && candidate.detail == active.detail) {
            current = candidate
            pending = null
            return candidate
        }

        val immediate =
            candidate.title == "ANALISANDO" ||
                candidate.priority >= active.priority + urgentPriorityDelta
        if (immediate) {
            current = candidate
            pending = null
            return candidate
        }

        if (pending != candidate) {
            pending = candidate
            pendingSinceMs = nowMs
            return active
        }
        if (nowMs - pendingSinceMs >= settleMs) {
            current = candidate
            pending = null
        }
        return current
    }

    fun reset() {
        current = null
        pending = null
        pendingSinceMs = 0L
        nullSinceMs = null
    }
}
