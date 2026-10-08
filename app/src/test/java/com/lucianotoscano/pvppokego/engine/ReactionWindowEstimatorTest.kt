package com.lucianotoscano.pvppokego.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactionWindowEstimatorTest {
    @Test
    fun bootstrapLeadTimeHasDocumentedComponentsAndConvertsToTurns() {
        val estimator = ReactionWindowEstimator(
            humanReactionMs = 450,
            overlayBudgetMs = 80,
            safetyMarginMs = 250,
            bootstrapPipelineMs = 650
        )
        assertEquals(1430L, estimator.leadTimeMs())
        assertEquals(3, estimator.reactionTurns())
    }

    @Test
    fun measuredP95ReplacesBootstrapAfterEnoughSamples() {
        val estimator = ReactionWindowEstimator(
            humanReactionMs = 0,
            overlayBudgetMs = 0,
            safetyMarginMs = 0,
            bootstrapPipelineMs = 999
        )
        (1L..20L).forEach { estimator.recordPipelineLatency(it * 10L) }
        // nearest-rank p95 of 20 ordered samples is the 19th value.
        assertEquals(190L, estimator.pipelineP95Ms())
        assertEquals(1, estimator.reactionTurns())
    }

    @Test
    fun latencyWindowNeverDropsBelowOneTurn() {
        val estimator = ReactionWindowEstimator(
            humanReactionMs = 1,
            overlayBudgetMs = 1,
            safetyMarginMs = 1,
            bootstrapPipelineMs = 1
        )
        assertTrue(estimator.reactionTurns() >= 1)
    }
}
