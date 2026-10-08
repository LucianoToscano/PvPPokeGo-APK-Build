package com.lucianotoscano.pvppokego.engine

import com.lucianotoscano.pvppokego.data.AttackRangeEstimate
import com.lucianotoscano.pvppokego.data.CmpOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

class CmpForecastEngineTest {
    @Test fun guaranteesWinOnlyWhenRangesDoNotOverlap() {
        val own = AttackRangeEstimate(150.0, 153.0, 156.0, 20, 0)
        val opp = AttackRangeEstimate(140.0, 144.0, 149.5, 30, 0)
        assertEquals(CmpOutcome.WIN, CmpForecastEngine.evaluate(own, opp).outcome)
    }

    @Test fun overlappingHiddenIvRangesStayUncertain() {
        val own = AttackRangeEstimate(145.0, 151.0, 158.0, 20, 0)
        val opp = AttackRangeEstimate(150.0, 152.0, 160.0, 30, 0)
        assertEquals(CmpOutcome.UNCERTAIN, CmpForecastEngine.evaluate(own, opp).outcome)
    }
}
