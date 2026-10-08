package com.lucianotoscano.pvppokego.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AttackRangeEstimatorTest {
    @Test
    fun exactCpShellProducesBoundedAttackRange() {
        val out = AttackRangeEstimator.estimate(
            base = PokemonBaseStats(atk = 100, def = 100, hp = 100),
            cpMultipliers = listOf(.5),
            targetCp = 250,
            leagueCp = 1500,
            likelyAttack = 50.0,
            maxCpError = 0
        )
        assertNotNull(out)
        out!!
        assertEquals(0, out.cpError)
        assertEquals(50.0, out.minAttack, .0001)
        assertTrueWithin(out.likelyAttack, out.minAttack, out.maxAttack)
    }

    @Test
    fun implausibleCpIsRejectedWhenErrorGateIsStrict() {
        val out = AttackRangeEstimator.estimate(
            base = PokemonBaseStats(atk = 100, def = 100, hp = 100),
            cpMultipliers = listOf(.5),
            targetCp = 1499,
            leagueCp = 1500,
            maxCpError = 0
        )
        assertNull(out)
    }

    private fun assertTrueWithin(value: Double, min: Double, max: Double) {
        if (value < min || value > max) {
            throw AssertionError("$value not in [$min,$max]")
        }
    }
}
