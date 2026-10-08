package com.lucianotoscano.pvppokego.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class EnergySequenceMathTest {
    @Test fun residualEnergyProducesAlternatingCounts() {
        assertEquals(listOf(5, 4, 5, 4), EnergySequenceMath.repeatedChargedCounts(0, 10, 45, 4))
    }

    @Test fun storedEnergyAllowsImmediateBackToBackCopy() {
        assertEquals(listOf(0, 0, 5), EnergySequenceMath.repeatedChargedCounts(100, 10, 50, 3))
    }

    @Test fun invalidGainDoesNotInventCounts() {
        assertEquals(emptyList<Int>(), EnergySequenceMath.repeatedChargedCounts(30, 0, 50))
    }
}
