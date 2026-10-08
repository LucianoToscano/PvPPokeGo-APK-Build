package com.lucianotoscano.pvppokego.detect

/**
 * The sprite matcher is an optional independent signal, not a prerequisite
 * for recognizing a valid Pokémon name and CP from the team-selection OCR.
 */
internal object AllyOcrRecoveryPolicy {
    fun allowNameAndCp(name: String?, cp: Int?, confidence: Float): Boolean =
        !name.isNullOrBlank() && cp != null && cp in 10..10000 &&
            confidence >= 0.90f
}
