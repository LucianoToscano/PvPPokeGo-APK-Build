package com.lucianotoscano.pvppokego.engine

import com.lucianotoscano.pvppokego.data.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TacticalForecastEngineTest {
    @Test fun farmDownComparesKoClockToEnemyChargedClock() {
        val move=MoveDef("CM","Charged",power=80,energy=50)
        val threat=ChargedThreatForecast(
            move,50,30,30,2,2,2,4,4,4,2000,2000,0,false,false,false,
            confidence=.9f,evidenceClass=PredictionEvidenceClass.MATHEMATICALLY_POSSIBLE,eptMin=4f,eptMax=4f
        )
        val forecast=EnemyEnergyForecast(30,30,30,1,listOf("Fast"),listOf(threat),0,1,.9f)
        val safe=TacticalForecastEngine.farmDown(20f,.30f,1,forecast)!!
        assertTrue(safe.safe)
        val unsafe=TacticalForecastEngine.farmDown(10f,.50f,1,forecast)!!
        assertFalse(unsafe.safe)
    }

    @Test fun optimalThrowTargetsLastEnemyFastTurn() {
        val phase=FastMovePhaseSnapshot("F","Fast",3,1,3,1500,.9f,4)
        val out=TacticalForecastEngine.optimalThrow(phase,1,50,8,45)!!
        assertTrue(out.waitFastMoves in 0..4)
        assertTrue(out.confidence > .6f)
    }

    @Test fun sacSwapPrefersResistedReserve() {
        val move=MoveDef("ICE","Ice",type="ice",power=100,energy=50)
        val threat=ChargedThreatForecast(
            move,50,50,50,0,0,0,0,0,0,0,0,0,true,true,false,
            confidence=.9f,evidenceClass=PredictionEvidenceClass.MATHEMATICALLY_POSSIBLE,eptMin=4f,eptMax=4f,
            damageForecast=DamageForecast("Ice",70f,65f,75f,null,"test",DamageForecastConfidence.MODEL),likelyKo=true,threatScore=.9f
        )
        val forecast=EnemyEnergyForecast(50,50,50,1,listOf("Fast"),listOf(threat),0,1,.9f)
        val reserves=listOf(
            ReserveState("Water",cp=1500,types=listOf("water"),matchup=MatchupState.NEUTRAL,identityConfirmed=true,score=.2,teamSlot=1),
            ReserveState("Dragon",cp=1500,types=listOf("dragon"),matchup=MatchupState.NEUTRAL,identityConfirmed=true,score=.8,teamSlot=2)
        )
        val out=TacticalForecastEngine.sacSwap(forecast,listOf("ground"),reserves,null)!!
        assertEquals("Water",out.targetName)
    }
}
