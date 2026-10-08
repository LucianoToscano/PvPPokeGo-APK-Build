package com.lucianotoscano.pvppokego.engine

/** Conservative gate for inferring a Protect Shield from a settled Charged-Move outcome. */
internal object ShieldObservationPolicy {
    fun isShieldLikely(
        damageFraction: Float,
        observations: Int,
        elapsedMs: Long,
        shieldsRemaining: Int
    ): Boolean =
        shieldsRemaining > 0 &&
            observations >= MIN_OBSERVATIONS &&
            elapsedMs >= SETTLE_MS &&
            damageFraction >= 0f && damageFraction < MAX_SHIELDED_DAMAGE_FRACTION

    const val SETTLE_MS = 2_800L
    const val MIN_OBSERVATIONS = 4
    const val MAX_SHIELDED_DAMAGE_FRACTION = 0.010f
}
