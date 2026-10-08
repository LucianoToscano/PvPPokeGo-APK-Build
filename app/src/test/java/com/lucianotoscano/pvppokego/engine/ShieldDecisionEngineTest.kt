package com.lucianotoscano.pvppokego.engine

import com.lucianotoscano.pvppokego.data.*
import org.junit.Assert.assertEquals
import org.junit.Test

class ShieldDecisionEngineTest {
    private fun forecast(damage: Float, lethal: Boolean=false, bait: Float=0f): EnemyEnergyForecast {
        val move = MoveDef("NUKE", "Nuke", power=100, energy=50)
        val c = ChargedThreatForecast(
            move=move, energyCost=50, currentEnergyMin=50, currentEnergyMax=60,
            fastMovesRemainingMin=0, fastMovesRemainingLikely=0, fastMovesRemainingMax=0,
            turnsRemainingMin=0, turnsRemainingLikely=0, turnsRemainingMax=0,
            earliestTimeMs=0, likelyTimeMs=0, prepareInTurns=0,
            readyPossible=true, readyCertain=true, doubleReadyPossible=false,
            baitProbability=bait, confidence=.9f, evidenceClass=PredictionEvidenceClass.MATHEMATICALLY_POSSIBLE,
            eptMin=4f, eptMax=4f, damageForecast=DamageForecast("Nuke",damage,damage-2,damage+2,null,"test",DamageForecastConfidence.MODEL),
            likelyKo=lethal, threatScore=.9f
        )
        return EnemyEnergyForecast(50,55,60,1,listOf("Fast"),listOf(c),0,1,.9f,.95f,0,null)
    }

    @Test fun lethalReadyMoveUsesShield() {
        val d = ShieldDecisionEngine.evaluate(forecast(60f,true), .5f, true, 1)!!
        assertEquals(ShieldAction.SHIELD, d.action)
    }

    @Test fun noShieldStateNeverPretendsShieldExists() {
        val d = ShieldDecisionEngine.evaluate(forecast(60f,true), .5f, true, 0)!!
        assertEquals(ShieldAction.HOLD, d.action)
    }
}
