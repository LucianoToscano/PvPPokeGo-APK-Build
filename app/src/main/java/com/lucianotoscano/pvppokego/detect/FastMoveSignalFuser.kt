package com.lucianotoscano.pvppokego.detect

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pure decision helper used by BattleFrameEventDetector.
 *
 * HP change is the evidence. Cadence may estimate that more than one Fast Move occurred
 * between visible HP-pixel changes. Motion can improve confidence only when explicitly
 * enabled; callers no longer use motion as a standalone attack signal.
 */
object FastMoveSignalFuser {
    data class Decision(
        val confirm: Boolean,
        val count: Int,
        val source: String,
        val confidence: Float
    )

    fun decide(
        pendingDropPx: Int,
        hpThresholdPx: Int,
        elapsedMs: Long,
        minGapMs: Long,
        expectedDurationMs: Long,
        motion: Float,
        motionBaseline: Float,
        battleUiVisible: Boolean,
        allowMotionFallback: Boolean
    ): Decision {
        if (elapsedMs < minGapMs) return Decision(false, 0, "gap", 0f)

        val duration = expectedDurationMs.coerceAtLeast(250L)
        val cadenceCount = (elapsedMs.toDouble() / duration.toDouble())
            .roundToInt()
            .coerceIn(1, MAX_RECOVERED_FASTS)

        if (pendingDropPx >= hpThresholdPx.coerceAtLeast(1)) {
            return if (cadenceCount > 1 && elapsedMs >= (duration * 1.55f).toLong()) {
                Decision(true, cadenceCount, "hp-drop-cadence", .80f)
            } else {
                Decision(true, 1, "hp-drop", .98f)
            }
        }

        val cadenceReady = elapsedMs >=
            (duration * .72f).toLong().coerceAtLeast(minGapMs)

        if (pendingDropPx > 0 && cadenceReady) {
            return Decision(
                confirm = true,
                count = if (elapsedMs >= (duration * 1.55f).toLong()) cadenceCount else 1,
                source = if (elapsedMs >= (duration * 1.55f).toLong()) "microdrop-cadence" else "microdrop",
                confidence = if (elapsedMs >= (duration * 1.55f).toLong()) .72f else .91f
            )
        }

        if (allowMotionFallback && battleUiVisible && cadenceReady) {
            val baseline = motionBaseline.coerceAtLeast(1f)
            val strongMotion = motion >= max(16f, baseline * 1.72f)
            val longEnough = elapsedMs >=
                (duration * .88f).toLong().coerceAtLeast(minGapMs)
            if (strongMotion && longEnough) {
                return Decision(true, 1, "motion-cadence", .72f)
            }
        }

        return Decision(false, 0, "none", 0f)
    }

    private const val MAX_RECOVERED_FASTS = 3
}
