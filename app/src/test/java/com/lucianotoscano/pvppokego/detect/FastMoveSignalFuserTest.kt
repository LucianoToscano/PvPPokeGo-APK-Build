package com.lucianotoscano.pvppokego.detect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class FastMoveSignalFuserTest {
    @Test
    fun strongHpDropConfirmsImmediatelyAfterGap() {
        val d = FastMoveSignalFuser.decide(
            pendingDropPx = 3,
            hpThresholdPx = 2,
            elapsedMs = 600,
            minGapMs = 300,
            expectedDurationMs = 1000,
            motion = 2f,
            motionBaseline = 2f,
            battleUiVisible = true,
            allowMotionFallback = true
        )
        assertTrue(d.confirm)
        assertEquals("hp-drop", d.source)
    }

    @Test
    fun singlePixelMicrodropConfirmsOnCadence() {
        val d = FastMoveSignalFuser.decide(
            pendingDropPx = 1,
            hpThresholdPx = 2,
            elapsedMs = 800,
            minGapMs = 300,
            expectedDurationMs = 1000,
            motion = 2f,
            motionBaseline = 2f,
            battleUiVisible = true,
            allowMotionFallback = true
        )
        assertTrue(d.confirm)
        assertEquals("microdrop", d.source)
    }

    @Test
    fun motionFallbackNeedsBattleAndStrongSpike() {
        val ok = FastMoveSignalFuser.decide(
            pendingDropPx = 0,
            hpThresholdPx = 2,
            elapsedMs = 950,
            minGapMs = 300,
            expectedDurationMs = 1000,
            motion = 30f,
            motionBaseline = 10f,
            battleUiVisible = true,
            allowMotionFallback = true
        )
        assertTrue(ok.confirm)
        assertEquals("motion-cadence", ok.source)

        val noBattle = FastMoveSignalFuser.decide(
            pendingDropPx = 0,
            hpThresholdPx = 2,
            elapsedMs = 950,
            minGapMs = 300,
            expectedDurationMs = 1000,
            motion = 30f,
            motionBaseline = 10f,
            battleUiVisible = false,
            allowMotionFallback = true
        )
        assertFalse(noBattle.confirm)
    }

    @Test
    fun gapPreventsDoubleCounting() {
        val d = FastMoveSignalFuser.decide(
            pendingDropPx = 5,
            hpThresholdPx = 1,
            elapsedMs = 100,
            minGapMs = 300,
            expectedDurationMs = 500,
            motion = 40f,
            motionBaseline = 5f,
            battleUiVisible = true,
            allowMotionFallback = true
        )
        assertFalse(d.confirm)
    }
    @Test
    fun delayedMicrodropCanRecoverTwoMissedFastMoves() {
        val d = FastMoveSignalFuser.decide(
            pendingDropPx = 1,
            hpThresholdPx = 2,
            elapsedMs = 2_050,
            minGapMs = 620,
            expectedDurationMs = 1_000,
            motion = 40f,
            motionBaseline = 5f,
            battleUiVisible = true,
            allowMotionFallback = false
        )
        assertTrue(d.confirm)
        assertEquals(2, d.count)
        assertEquals("microdrop-cadence", d.source)
    }

    @Test
    fun productionModeNeverCountsMotionWithoutHpEvidence() {
        val d = FastMoveSignalFuser.decide(
            pendingDropPx = 0,
            hpThresholdPx = 2,
            elapsedMs = 2_000,
            minGapMs = 620,
            expectedDurationMs = 1_000,
            motion = 60f,
            motionBaseline = 5f,
            battleUiVisible = true,
            allowMotionFallback = false
        )
        assertFalse(d.confirm)
        assertEquals(0, d.count)
    }

}
