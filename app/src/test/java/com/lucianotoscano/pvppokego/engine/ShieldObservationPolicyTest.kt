package com.lucianotoscano.pvppokego.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShieldObservationPolicyTest {
    @Test
    fun stableNearZeroDamageAfterChargedCanCountOneShield() {
        assertTrue(
            ShieldObservationPolicy.isShieldLikely(
                damageFraction = 0.003f,
                observations = 5,
                elapsedMs = 3_100L,
                shieldsRemaining = 2
            )
        )
    }

    @Test
    fun delayedDamageOrInsufficientFramesDoesNotInventShield() {
        assertFalse(
            ShieldObservationPolicy.isShieldLikely(
                damageFraction = 0.0f,
                observations = 2,
                elapsedMs = 3_500L,
                shieldsRemaining = 2
            )
        )
        assertFalse(
            ShieldObservationPolicy.isShieldLikely(
                damageFraction = 0.20f,
                observations = 8,
                elapsedMs = 4_000L,
                shieldsRemaining = 2
            )
        )
    }

    @Test
    fun cannotConsumeMoreThanTwoShields() {
        assertFalse(
            ShieldObservationPolicy.isShieldLikely(
                damageFraction = 0.0f,
                observations = 8,
                elapsedMs = 4_000L,
                shieldsRemaining = 0
            )
        )
    }
}
