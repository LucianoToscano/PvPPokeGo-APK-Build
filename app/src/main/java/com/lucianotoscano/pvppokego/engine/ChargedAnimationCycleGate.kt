package com.lucianotoscano.pvppokego.engine

/**
 * Deduplicates OCR observations from one Charged Move animation cycle.
 *
 * The same "X used Y" text can remain visible for several OCR frames. A new event is
 * emitted only after the previous cycle has visibly cleared, so legitimate consecutive
 * Charged Moves are still allowed without relying on a long fixed cooldown.
 */
internal class ChargedAnimationCycleGate {
    data class Signature(val actorKey: String, val moveId: String)

    private var latched: Signature? = null
    private var lastSeenAtMs: Long = 0L
    private var clearFrames: Int = 0
    private var firstClearAtMs: Long = 0L

    fun observe(signature: Signature?, nowMs: Long): Boolean {
        if (signature == null) {
            if (latched != null) {
                if (clearFrames == 0) firstClearAtMs = nowMs
                clearFrames++
                val clearLongEnough = nowMs - firstClearAtMs >= MIN_CLEAR_MS
                if (clearFrames >= REQUIRED_CLEAR_FRAMES && clearLongEnough) {
                    latched = null
                    clearFrames = 0
                    firstClearAtMs = 0L
                }
            }
            return false
        }

        val current = latched
        if (current == null) {
            latched = signature
            lastSeenAtMs = nowMs
            clearFrames = 0
            firstClearAtMs = 0L
            return true
        }

        if (current == signature) {
            lastSeenAtMs = nowMs
            clearFrames = 0
            firstClearAtMs = 0L
            return false
        }

        // A different move/actor cannot replace a still-visible animation immediately.
        // It may start a new cycle only after the old text had enough clean time.
        val hadClearWindow = clearFrames >= REQUIRED_CLEAR_FRAMES &&
            firstClearAtMs > 0L &&
            nowMs - firstClearAtMs >= MIN_CLEAR_MS
        if (hadClearWindow || nowMs - lastSeenAtMs >= MAX_STALE_MS) {
            latched = signature
            lastSeenAtMs = nowMs
            clearFrames = 0
            firstClearAtMs = 0L
            return true
        }

        return false
    }

    fun reset() {
        latched = null
        lastSeenAtMs = 0L
        clearFrames = 0
        firstClearAtMs = 0L
    }

    companion object {
        private const val REQUIRED_CLEAR_FRAMES = 2
        private const val MIN_CLEAR_MS = 420L
        private const val MAX_STALE_MS = 2_600L
    }
}
