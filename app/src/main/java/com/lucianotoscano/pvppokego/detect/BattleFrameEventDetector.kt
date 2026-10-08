package com.lucianotoscano.pvppokego.detect

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max

/**
 * Lightweight visual detector.
 *
 * Enemy Fast Moves are inferred only from discrete losses on the PLAYER HP bar.
 * Player Fast Moves are inferred independently from discrete losses on the OPPONENT HP bar.
 * Motion is diagnostic only, so attack VFX cannot increase either energy counter by themselves.
 */
class BattleFrameEventDetector {
    data class Event(
        val fastMoveCount: Int = 0,
        val ownFastMoveCount: Int = 0,
        val hpRatio: Float? = null,
        val opponentHpRatio: Float? = null,
        val playerHpBarYFraction: Float? = null,
        val reserve1HpRatio: Float? = null,
        val reserve2HpRatio: Float? = null,
        /** Normalized HP lost by the player/opponent in the confirmed Fast-Move event. */
        val playerDamageFraction: Float? = null,
        val opponentDamageFraction: Float? = null,
        val motion: Float = 0f,
        val confidence: Float = 0f,
        val source: String = "none",
        val reserve1Visible: Boolean? = null,
        val reserve2Visible: Boolean? = null,
        val battleUiVisible: Boolean = false,
        /** Screen-pixel Y of the top edge of GO's white trainer/Pokemon cards when found. */
        val nativeTopCardY: Int? = null,
        /** Aggregate visual confidence that a live PvP battle HUD is present. */
        val battleConfidence: Float = 0f
    )

    private data class HpFill(val run: Int, val y: Int)

    private var previousHpRun: Int? = null
    private var maximumHpRun: Int = 0
    private var previousOpponentHpRun: Int? = null
    private var maximumOpponentHpRun: Int = 0
    private var previousMotionSamples: IntArray? = null
    private var motionEma = 0f
    private var lastFastAtMs = 0L
    private var lastOwnFastAtMs = 0L
    private var pendingEnemyHpDropPx = 0
    private var pendingOwnHpDropPx = 0
    private var frozenUntilMs = 0L

    private val largeDamageFraction = 0.16f
    private val largeDamageGuardMs = 1_500L

    private val reserveVisibility = arrayOfNulls<Boolean>(2)
    private val reserveHitStreak = IntArray(2)
    private val reserveMissStreak = IntArray(2)
    private val reserveMaxHpRun = IntArray(2)

    fun reset() {
        previousHpRun = null
        maximumHpRun = 0
        previousOpponentHpRun = null
        maximumOpponentHpRun = 0
        previousMotionSamples = null
        motionEma = 0f
        lastFastAtMs = 0L
        lastOwnFastAtMs = 0L
        pendingEnemyHpDropPx = 0
        pendingOwnHpDropPx = 0
        frozenUntilMs = 0L
        reserveVisibility.fill(null)
        reserveHitStreak.fill(0)
        reserveMissStreak.fill(0)
        reserveMaxHpRun.fill(0)
    }

    fun freezeForCharged(nowMs: Long = System.currentTimeMillis(), durationMs: Long = 6_200L) {
        frozenUntilMs = max(frozenUntilMs, nowMs + durationMs)
        previousHpRun = null
        previousOpponentHpRun = null
        pendingEnemyHpDropPx = 0
        pendingOwnHpDropPx = 0
        previousMotionSamples = null
    }

    fun analyze(
        frame: Bitmap,
        expectedEnemyFastDurationMs: Long,
        expectedOwnFastDurationMs: Long = expectedEnemyFastDurationMs,
        nowMs: Long = System.currentTimeMillis()
    ): Event {
        if (frame.isRecycled) return Event()

        val reserve1Fill = findNativeReserveHpFill(frame, 0)
        val reserve2Fill = findNativeReserveHpFill(frame, 1)
        val reserve1 = updateReserveVisibility(0, reserve1Fill != null)
        val reserve2 = updateReserveVisibility(1, reserve2Fill != null)
        val reserve1HpRatio = reserve1Fill?.let { fill ->
            reserveMaxHpRun[0] = max(reserveMaxHpRun[0], fill.run)
            if (reserveMaxHpRun[0] > 0) fill.run.toFloat() / reserveMaxHpRun[0].toFloat() else null
        }?.coerceIn(0f, 1f)
        val reserve2HpRatio = reserve2Fill?.let { fill ->
            reserveMaxHpRun[1] = max(reserveMaxHpRun[1], fill.run)
            if (reserveMaxHpRun[1] > 0) fill.run.toFloat() / reserveMaxHpRun[1].toFloat() else null
        }?.coerceIn(0f, 1f)

        val playerHp = findPlayerHpFill(frame)
        val opponentHp = findOpponentHpFill(frame)
        val nativeTopCardY = findNativeTopCardTop(frame)
        val hpRun = playerHp?.run
        val opponentHpRun = opponentHp?.run
        val bothHp = hpRun != null && opponentHpRun != null
        val battleConfidence = (
            (if (bothHp) .68f else if (hpRun != null || opponentHpRun != null) .30f else 0f) +
            (if (nativeTopCardY != null) .24f else 0f) +
            (if (reserve1 == true || reserve2 == true) .08f else 0f)
        ).coerceIn(0f, 1f)
        val battleUiVisible = battleConfidence >= .62f

        val hpRatio = if (hpRun != null) {
            maximumHpRun = max(maximumHpRun, hpRun)
            if (maximumHpRun > 0) hpRun.toFloat() / maximumHpRun.toFloat() else null
        } else null
        val opponentHpRatio = if (opponentHpRun != null) {
            maximumOpponentHpRun = max(maximumOpponentHpRun, opponentHpRun)
            if (maximumOpponentHpRun > 0) opponentHpRun.toFloat() / maximumOpponentHpRun.toFloat() else null
        } else null
        val motion = motionScore(frame)

        if (nowMs < frozenUntilMs) {
            if (hpRun != null) previousHpRun = hpRun
            if (opponentHpRun != null) previousOpponentHpRun = opponentHpRun
            return Event(
                hpRatio = hpRatio,
                opponentHpRatio = opponentHpRatio,
                playerHpBarYFraction = playerHp?.y?.toFloat()?.div(frame.height.toFloat()),
                reserve1HpRatio = reserve1HpRatio,
                reserve2HpRatio = reserve2HpRatio,
                motion = motion,
                source = "frozen",
                reserve1Visible = reserve1,
                reserve2Visible = reserve2,
                battleUiVisible = battleUiVisible,
                nativeTopCardY = nativeTopCardY,
                battleConfidence = battleConfidence
            )
        }

        val enemyMinGap = (expectedEnemyFastDurationMs * 0.62f).toLong().coerceIn(280L, 1_650L)
        val ownMinGap = (expectedOwnFastDurationMs * 0.62f).toLong().coerceIn(280L, 1_650L)
        var enemyFast = 0
        var ownFast = 0
        var source = if (battleUiVisible) "battle-hud" else "no-battle-hud"
        var confidence = if (battleUiVisible) .78f else .18f
        var playerDamageFraction: Float? = null
        var opponentDamageFraction: Float? = null

        if (hpRun != null) {
            val old = previousHpRun
            previousHpRun = hpRun
            if (old != null) {
                if (hpRun > old + max(7, maximumHpRun / 30)) {
                    maximumHpRun = max(maximumHpRun, hpRun)
                    pendingEnemyHpDropPx = 0
                    source = "player-hp-reset"
                } else {
                    val drop = old - hpRun
                    val chargedLikeDrop = drop > 0 &&
                        maximumHpRun > 0 &&
                        drop.toFloat() / maximumHpRun.toFloat() >= largeDamageFraction
                    when {
                        chargedLikeDrop -> {
                            pendingEnemyHpDropPx = 0
                            frozenUntilMs = max(frozenUntilMs, nowMs + largeDamageGuardMs)
                            source = "player-hp-large-drop"
                        }
                        drop > 0 -> pendingEnemyHpDropPx =
                            (pendingEnemyHpDropPx + drop).coerceAtMost(maximumHpRun.coerceAtLeast(12))
                        drop < -1 -> pendingEnemyHpDropPx = 0
                    }

                    // HP is primary evidence. Pixel granularity can hide one or more hits,
                    // cadence + strong motion can recover a missed Fast Move without blindly
                    // counting every animation frame.
                    val threshold = max(1, maximumHpRun / 145)
                    val elapsed = nowMs - lastFastAtMs
                    val decision = FastMoveSignalFuser.decide(
                        pendingDropPx = pendingEnemyHpDropPx,
                        hpThresholdPx = threshold,
                        elapsedMs = elapsed,
                        minGapMs = enemyMinGap,
                        expectedDurationMs = expectedEnemyFastDurationMs,
                        motion = motion,
                        motionBaseline = motionEma,
                        battleUiVisible = battleUiVisible,
                        allowMotionFallback = false
                    )
                    if (decision.confirm && !chargedLikeDrop) {
                        lastFastAtMs = nowMs
                        enemyFast = decision.count
                        playerDamageFraction = if (maximumHpRun > 0) {
                            pendingEnemyHpDropPx.toFloat() / maximumHpRun.toFloat()
                        } else null
                        source = when (decision.source) {
                            "hp-drop" -> "player-hp-drop"
                            "hp-drop-cadence" -> "player-hp-cadence"
                            "microdrop" -> "player-hp-microdrop"
                            "microdrop-cadence" -> "player-hp-microdrop-cadence"
                            else -> "player-hp-estimated"
                        }
                        confidence = decision.confidence
                        pendingEnemyHpDropPx = 0
                    }
                }
            }
        } else {
            // Do not invent Fast Moves while the HP bar is temporarily hidden by VFX.
            previousHpRun = null
            pendingEnemyHpDropPx = 0
        }

        if (opponentHpRun != null) {
            val old = previousOpponentHpRun
            previousOpponentHpRun = opponentHpRun
            if (old != null) {
                if (opponentHpRun > old + max(7, maximumOpponentHpRun / 30)) {
                    maximumOpponentHpRun = max(maximumOpponentHpRun, opponentHpRun)
                    pendingOwnHpDropPx = 0
                    if (enemyFast == 0) source = "opponent-hp-reset"
                } else {
                    val drop = old - opponentHpRun
                    val chargedLikeDrop = drop > 0 &&
                        maximumOpponentHpRun > 0 &&
                        drop.toFloat() / maximumOpponentHpRun.toFloat() >= largeDamageFraction
                    when {
                        chargedLikeDrop -> {
                            pendingOwnHpDropPx = 0
                            frozenUntilMs = max(frozenUntilMs, nowMs + largeDamageGuardMs)
                            if (enemyFast == 0) source = "opponent-hp-large-drop"
                        }
                        drop > 0 -> pendingOwnHpDropPx =
                            (pendingOwnHpDropPx + drop).coerceAtMost(maximumOpponentHpRun.coerceAtLeast(12))
                        drop < -1 -> pendingOwnHpDropPx = 0
                    }
                    val threshold = max(1, maximumOpponentHpRun / 145)
                    val elapsed = nowMs - lastOwnFastAtMs
                    val decision = FastMoveSignalFuser.decide(
                        pendingDropPx = pendingOwnHpDropPx,
                        hpThresholdPx = threshold,
                        elapsedMs = elapsed,
                        minGapMs = ownMinGap,
                        expectedDurationMs = expectedOwnFastDurationMs,
                        motion = motion,
                        motionBaseline = motionEma,
                        battleUiVisible = battleUiVisible,
                        allowMotionFallback = false
                    )
                    if (decision.confirm && !chargedLikeDrop) {
                        lastOwnFastAtMs = nowMs
                        ownFast = decision.count
                        opponentDamageFraction = if (maximumOpponentHpRun > 0) {
                            pendingOwnHpDropPx.toFloat() / maximumOpponentHpRun.toFloat()
                        } else null
                        source = when {
                            enemyFast > 0 -> "both-hp-drop"
                            decision.source == "hp-drop" -> "opponent-hp-drop"
                            decision.source == "hp-drop-cadence" -> "opponent-hp-cadence"
                            decision.source == "microdrop-cadence" -> "opponent-hp-microdrop-cadence"
                            else -> "opponent-hp-microdrop"
                        }
                        confidence = decision.confidence
                        pendingOwnHpDropPx = 0
                    }
                }
            }
        } else {
            previousOpponentHpRun = null
            pendingOwnHpDropPx = 0
        }

        return Event(
            fastMoveCount = enemyFast,
            ownFastMoveCount = ownFast,
            hpRatio = hpRatio,
            opponentHpRatio = opponentHpRatio,
            playerHpBarYFraction = playerHp?.y?.toFloat()?.div(frame.height.toFloat()),
            reserve1HpRatio = reserve1HpRatio,
            reserve2HpRatio = reserve2HpRatio,
            playerDamageFraction = playerDamageFraction,
            opponentDamageFraction = opponentDamageFraction,
            motion = motion,
            confidence = confidence,
            source = source,
            reserve1Visible = reserve1,
            reserve2Visible = reserve2,
            battleUiVisible = battleUiVisible,
            nativeTopCardY = nativeTopCardY,
            battleConfidence = battleConfidence
        )
    }

    private fun updateReserveVisibility(index: Int, detected: Boolean): Boolean? {
        if (detected) {
            reserveHitStreak[index]++
            reserveMissStreak[index] = 0
            if (reserveHitStreak[index] >= 2) reserveVisibility[index] = true
        } else {
            reserveMissStreak[index]++
            reserveHitStreak[index] = 0
            if (reserveMissStreak[index] >= 5) reserveVisibility[index] = false
        }
        return reserveVisibility[index]
    }

    /** Detect only the thin native HP line under each reserve card. */
    private fun findNativeReserveHpFill(frame: Bitmap, slot: Int): HpFill? {
        val left = (frame.width * .82f).toInt().coerceAtLeast(0)
        val right = (frame.width * .94f).toInt().coerceAtMost(frame.width)
        val topFrac = if (slot == 0) .604f else .700f
        val bottomFrac = if (slot == 0) .617f else .713f
        val top = (frame.height * topFrac).toInt().coerceAtLeast(0)
        val bottom = (frame.height * bottomFrac).toInt().coerceAtMost(frame.height)
        if (right <= left || bottom <= top) return null

        val roiW = right - left
        val row = IntArray(roiW)
        var best = 0
        var y = top
        while (y < bottom) {
            frame.getPixels(row, 0, roiW, left, y, roiW, 1)
            var run = 0
            for (p in row) {
                if (isHpColor(p)) {
                    run++
                    if (run > best) best = run
                } else run = 0
            }
            y++
        }
        val minimum = (frame.width * .034f).toInt().coerceAtLeast(24)
        return if (best >= minimum) HpFill(best, top) else null
    }

    /**
     * Finds GO's two light top info cards. Their top edge is a far more stable anchor than a hard
     * coded Y because status-bar/notch insets differ by device.
     */
    private fun findNativeTopCardTop(frame: Bitmap): Int? {
        val minY = (frame.height * .045f).toInt().coerceAtLeast(0)
        val maxY = (frame.height * .16f).toInt().coerceAtMost(frame.height - 1)
        val leftA = (frame.width * .015f).toInt()
        val rightA = (frame.width * .42f).toInt()
        val leftB = (frame.width * .58f).toInt()
        val rightB = (frame.width * .985f).toInt().coerceAtMost(frame.width)
        val required = (frame.width * .15f).toInt().coerceAtLeast(70)
        var y = minY
        while (y <= maxY) {
            val leftRun = longestBrightRun(frame, y, leftA, rightA)
            val rightRun = longestBrightRun(frame, y, leftB, rightB)
            if (leftRun >= required && rightRun >= required) return y
            y += 2
        }
        return null
    }

    private fun longestBrightRun(frame: Bitmap, y: Int, left: Int, right: Int): Int {
        if (right <= left) return 0
        var best = 0
        var run = 0
        var x = left.coerceAtLeast(0)
        val end = right.coerceAtMost(frame.width)
        while (x < end) {
            val p = frame.getPixel(x, y)
            val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
            val brightCard = r >= 175 && g >= 185 && b >= 190 && abs(r - g) < 55 && abs(g - b) < 55
            if (brightCard) {
                run++
                if (run > best) best = run
            } else run = 0
            x += 2
        }
        return best * 2
    }

    private fun findPlayerHpFill(frame: Bitmap): HpFill? = findHpFill(
        frame = frame,
        leftFrac = .035f,
        rightFrac = .405f,
        topFrac = .345f,
        bottomFrac = .445f
    )

    private fun findOpponentHpFill(frame: Bitmap): HpFill? = findHpFill(
        frame = frame,
        leftFrac = .49f,
        rightFrac = .91f,
        topFrac = .345f,
        bottomFrac = .445f
    )

    private fun findHpFill(
        frame: Bitmap,
        leftFrac: Float,
        rightFrac: Float,
        topFrac: Float,
        bottomFrac: Float
    ): HpFill? {
        val left = (frame.width * leftFrac).toInt().coerceAtLeast(0)
        val right = (frame.width * rightFrac).toInt().coerceAtMost(frame.width)
        val top = (frame.height * topFrac).toInt().coerceAtLeast(0)
        val bottom = (frame.height * bottomFrac).toInt().coerceAtMost(frame.height)
        if (right <= left || bottom <= top) return null

        val roiW = right - left
        val row = IntArray(roiW)
        var best = 0
        var bestY = top
        var y = top
        while (y < bottom) {
            frame.getPixels(row, 0, roiW, left, y, roiW, 1)
            var run = 0
            for (p in row) {
                if (isHpColor(p)) {
                    run++
                    if (run > best) { best = run; bestY = y }
                } else run = 0
            }
            y += 2
        }
        val minimum = (frame.width * .055f).toInt().coerceAtLeast(28)
        return if (best >= minimum) HpFill(best, bestY) else null
    }

    private fun isHpColor(p: Int): Boolean {
        val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
        val cyanGreen = g >= 135 && g >= r + 24 && b >= 70 && b <= 245
        val yellowOrange = r >= 155 && g >= 75 && b <= 135 && r >= b + 45
        val red = r >= 155 && g <= 125 && b <= 135 && r >= g + 35
        return cyanGreen || yellowOrange || red
    }

    private fun motionScore(frame: Bitmap): Float {
        val left = (frame.width * .50f).toInt()
        val right = (frame.width * .82f).toInt().coerceAtMost(frame.width)
        val top = (frame.height * .27f).toInt()
        val bottom = (frame.height * .58f).toInt().coerceAtMost(frame.height)
        val step = max(6, frame.width / 120)
        val cols = max(1, (right - left) / step)
        val rows = max(1, (bottom - top) / step)
        val samples = IntArray(cols * rows)
        var index = 0
        var y = top
        while (y < bottom && index < samples.size) {
            var x = left
            while (x < right && index < samples.size) {
                val p = frame.getPixel(x, y)
                samples[index++] = (Color.red(p) * 3 + Color.green(p) * 6 + Color.blue(p)) / 10
                x += step
            }
            y += step
        }
        val previous = previousMotionSamples
        previousMotionSamples = samples
        if (previous == null || previous.size != samples.size) return 0f
        var sum = 0L
        for (i in samples.indices) sum += abs(samples[i] - previous[i])
        val score = if (samples.isNotEmpty()) sum.toFloat() / samples.size.toFloat() else 0f
        motionEma = if (motionEma == 0f) score else motionEma * .88f + score * .12f
        return score
    }
}
