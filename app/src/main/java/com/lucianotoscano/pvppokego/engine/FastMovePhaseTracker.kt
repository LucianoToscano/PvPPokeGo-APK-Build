package com.lucianotoscano.pvppokego.engine

import com.lucianotoscano.pvppokego.data.FastMovePhaseSnapshot
import com.lucianotoscano.pvppokego.data.MoveDef
import kotlin.math.abs

/**
 * Tracks enemy Fast-Move phase between visual confirmations.
 * Damage + energy register on the final turn. No completed attack is fabricated.
 */
internal class FastMovePhaseTracker {
    private var move: MoveDef? = null
    private var lastCompletionAtMs: Long = 0L
    private var confidence: Float = 0f
    private var observedCompletions: Int = 0

    fun reset(candidate: MoveDef? = null) {
        move = candidate
        lastCompletionAtMs = 0L
        confidence = 0f
        observedCompletions = 0
    }

    fun confirmMove(candidate: MoveDef) {
        if (move?.moveId != candidate.moveId) {
            move = candidate
            lastCompletionAtMs = 0L
            confidence = 0f
            observedCompletions = 0
        }
    }

    fun observeCompletion(
        candidate: MoveDef,
        count: Int,
        nowMs: Long,
        detectorConfidence: Float,
        observedIntervalMs: Long? = null
    ) {
        if (count <= 0) return
        confirmMove(candidate)
        val durationMs = candidate.turns.coerceAtLeast(1) * EnemyEnergyTracker.TURN_MS.toLong()
        val cadence = observedIntervalMs?.takeIf { it > 0L }?.let { observed ->
            val expected = durationMs * count
            val relative = abs(observed - expected).toFloat() / expected.coerceAtLeast(1L).toFloat()
            (1f - relative).coerceIn(.05f, 1f)
        } ?: 1f
        val evidence = (detectorConfidence.coerceIn(.05f, 1f) * .72f + cadence * .28f)
            .coerceIn(.05f, 1f)
        confidence = if (observedCompletions == 0) evidence
        else (confidence * .62f + evidence * .38f).coerceIn(0f, 1f)
        observedCompletions += count
        lastCompletionAtMs = nowMs
    }

    fun snapshot(nowMs: Long = System.currentTimeMillis()): FastMovePhaseSnapshot? {
        val current = move ?: return null
        if (lastCompletionAtMs <= 0L) return null
        val turns = current.turns.coerceAtLeast(1)
        val durationMs = turns * EnemyEnergyTracker.TURN_MS.toLong()
        val elapsed = (nowMs - lastCompletionAtMs).coerceAtLeast(0L)
        val cycleElapsed = elapsed % durationMs
        val completedTurnsInsideCycle = (cycleElapsed / EnemyEnergyTracker.TURN_MS)
            .toInt().coerceIn(0, turns - 1)
        val phaseTurn = completedTurnsInsideCycle + 1
        val turnsUntilRegistration = (turns - completedTurnsInsideCycle).coerceAtLeast(1)
        val nextRegistration = (durationMs - cycleElapsed).coerceIn(1L, durationMs)
        val cyclesUnseen = (elapsed / durationMs).toInt()
        val decay = when {
            cyclesUnseen <= 1 -> 1f
            cyclesUnseen == 2 -> .88f
            cyclesUnseen == 3 -> .74f
            cyclesUnseen == 4 -> .60f
            else -> .48f
        }
        return FastMovePhaseSnapshot(
            moveId = current.moveId,
            moveName = current.name,
            moveTurns = turns,
            phaseTurn = phaseTurn,
            turnsUntilRegistration = turnsUntilRegistration,
            nextRegistrationInMs = nextRegistration,
            confidence = (confidence * decay).coerceIn(0f, 1f),
            observedCompletions = observedCompletions
        )
    }
}
