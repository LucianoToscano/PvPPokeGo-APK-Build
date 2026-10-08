package com.lucianotoscano.pvppokego.engine

import java.util.ArrayDeque
import kotlin.math.ceil

/**
 * Converts measured app latency plus a documented human-reaction allowance into PvP turns.
 *
 * Pipeline latency is measured on-device from fresh frame timestamp -> analyzed/redrawn state.
 * Until enough samples exist, the bootstrap value intentionally assumes a slower device.
 */
internal class ReactionWindowEstimator(
    private val humanReactionMs: Long = DEFAULT_HUMAN_REACTION_MS,
    private val overlayBudgetMs: Long = DEFAULT_OVERLAY_BUDGET_MS,
    private val safetyMarginMs: Long = DEFAULT_SAFETY_MARGIN_MS,
    private val bootstrapPipelineMs: Long = DEFAULT_BOOTSTRAP_PIPELINE_MS,
    private val maxSamples: Int = 96
) {
    private val pipelineSamples = ArrayDeque<Long>()

    fun recordPipelineLatency(latencyMs: Long) {
        if (latencyMs !in 0L..10_000L) return
        pipelineSamples.add(latencyMs)
        while (pipelineSamples.size > maxSamples) pipelineSamples.removeFirst()
    }

    fun pipelineP95Ms(): Long {
        if (pipelineSamples.size < MIN_MEASURED_SAMPLES) return bootstrapPipelineMs
        val sorted = pipelineSamples.sorted()
        val index = ceil(sorted.size * .95).toInt().coerceIn(1, sorted.size) - 1
        return sorted[index].coerceAtLeast(1L)
    }

    fun leadTimeMs(): Long =
        pipelineP95Ms() + overlayBudgetMs + humanReactionMs + safetyMarginMs

    fun reactionTurns(): Int =
        ceil(leadTimeMs().toDouble() / EnemyEnergyTracker.TURN_MS.toDouble())
            .toInt()
            .coerceAtLeast(1)

    fun sampleCount(): Int = pipelineSamples.size

    companion object {
        /**
         * 450 ms is an explicit user-reaction allowance, not a claim about any specific
         * individual. It can later be calibrated from drills/history without changing math.
         */
        const val DEFAULT_HUMAN_REACTION_MS = 450L
        /** Render/notification allowance kept separate from measured capture/detection latency. */
        const val DEFAULT_OVERLAY_BUDGET_MS = 80L
        /** Half-turn guard against scheduling jitter near a 500 ms turn boundary. */
        const val DEFAULT_SAFETY_MARGIN_MS = 250L
        /** Conservative startup estimate until at least 12 real pipeline samples exist. */
        const val DEFAULT_BOOTSTRAP_PIPELINE_MS = 650L
        const val MIN_MEASURED_SAMPLES = 12
    }
}
