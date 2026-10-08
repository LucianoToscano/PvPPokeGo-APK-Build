package com.lucianotoscano.pvppokego.detect

/**
 * Fuses weak/strong battle evidence over time so the overlay does not flash on the map and does
 * not disappear during Charged Move/switch animations. No single OCR string can activate it.
 */
class BattlePresenceTracker {
    var confidence: Float = 0f
        private set
    var active: Boolean = false
        private set
    private var lastUpdateMs = 0L

    fun reset() {
        confidence = 0f
        active = false
        lastUpdateMs = 0L
    }

    fun update(
        visualConfidence: Float? = null,
        pairedPokemonCards: Boolean = false,
        battleTextEvidence: Boolean = false,
        switchPrompt: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (lastUpdateMs != 0L) {
            val elapsed = (nowMs - lastUpdateMs).coerceAtLeast(0L)
            val decay = (elapsed / 5_000f).coerceIn(0f, .18f)
            confidence = (confidence - decay).coerceAtLeast(0f)
        }
        lastUpdateMs = nowMs

        visualConfidence?.let { v ->
            val clamped = v.coerceIn(0f, 1f)
            confidence = maxOf(confidence * .72f + clamped * .28f, clamped * .84f)
        }
        if (pairedPokemonCards) confidence = maxOf(confidence, .88f)
        if (battleTextEvidence) confidence = maxOf(confidence, .76f)
        if (switchPrompt) confidence = maxOf(confidence, .92f)

        active = if (active) confidence >= .22f else confidence >= .62f
        return active
    }

    fun tick(nowMs: Long = System.currentTimeMillis()): Boolean = update(nowMs = nowMs)
}
