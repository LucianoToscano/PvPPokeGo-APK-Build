package com.lucianotoscano.pvppokego.engine

/**
 * Multi-frame CP stabilizer.
 *
 * A single OCR result is never allowed to replace a trusted CP. New identities need two
 * matching frames; replacement of an existing CP needs three matching frames.
 * Missing OCR simply keeps the last reliable value.
 */
internal class StableCpTracker {
    private var subjectKey: String? = null
    private var accepted: Int? = null
    private var candidate: Int? = null
    private var streak: Int = 0

    fun observe(subject: String, cp: Int?): Int? {
        if (subjectKey != subject) {
            subjectKey = subject
            accepted = null
            candidate = null
            streak = 0
        }

        if (cp == null || cp !in 10..10000) return accepted
        if (cp == accepted) {
            candidate = null
            streak = 0
            return accepted
        }

        if (candidate == cp) streak++ else {
            candidate = cp
            streak = 1
        }

        val required = if (accepted == null) INITIAL_CONFIRM_FRAMES else REPLACEMENT_CONFIRM_FRAMES
        if (streak >= required) {
            accepted = cp
            candidate = null
            streak = 0
        }
        return accepted
    }

    fun reset() {
        subjectKey = null
        accepted = null
        candidate = null
        streak = 0
    }

    companion object {
        private const val INITIAL_CONFIRM_FRAMES = 2
        private const val REPLACEMENT_CONFIRM_FRAMES = 3
    }
}
