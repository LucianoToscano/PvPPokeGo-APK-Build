package com.lucianotoscano.pvppokego.engine

import org.junit.Assert.*
import org.junit.Test

class EnemyMovePredictorTest {
    @Test fun observedCheapMovesIncreaseHeuristicWithoutClaimingCertainty() {
        val base = EnemyMovePredictor.baitHeuristic(.30f, 0, true, false, 60, .6f)
        val observed = EnemyMovePredictor.baitHeuristic(.30f, 2, true, false, 60, .6f)
        assertTrue(observed > base)
        assertTrue(observed < 1f)
    }
    @Test fun lethalCheapMoveIsNotADeceptiveBait() {
        assertEquals(0f, EnemyMovePredictor.baitHeuristic(.8f, 3, true, true, 55, .5f), 0f)
    }
    @Test fun noExpensiveAffordableMeansNoBaitInference() {
        assertEquals(0f, EnemyMovePredictor.baitHeuristic(.5f, 2, false, false, 40, .9f), 0f)
    }
    @Test fun criticalHpAndNearCapReduceBaitHeuristic() {
        val initial = EnemyMovePredictor.baitHeuristic(.50f, 2, true, false, 65, .8f)
        val critical = EnemyMovePredictor.baitHeuristic(.50f, 2, true, false, 90, .10f)
        assertTrue(initial > critical)
    }
}
