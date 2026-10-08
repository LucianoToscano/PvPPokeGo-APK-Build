package com.lucianotoscano.pvppokego.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.lucianotoscano.pvppokego.data.BattleUiState
import com.lucianotoscano.pvppokego.data.ChargedPrediction
import com.lucianotoscano.pvppokego.data.GameDataRepository
import com.lucianotoscano.pvppokego.data.EnergyConfidence
import com.lucianotoscano.pvppokego.data.MatchupState
import com.lucianotoscano.pvppokego.data.MoveDef
import com.lucianotoscano.pvppokego.data.MoveKnowledgeConfidence
import com.lucianotoscano.pvppokego.data.SettingsRepository
import com.lucianotoscano.pvppokego.engine.AdviceStabilityGate
import com.lucianotoscano.pvppokego.engine.BattleAssistAdvisor
import com.lucianotoscano.pvppokego.engine.TypeChart
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.max

/** Canonical PvPPokeGo HUD anchored around Pokemon GO's native battle layout. */
class BattleOverlayView(
    context: Context,
    private val settings: SettingsRepository,
    private val gameRepo: GameDataRepository,
    private val callbacks: Callbacks
) : View(context) {

    interface Callbacks {
        fun onFastMoveTapped()
        fun onCharged1Tapped()
        fun onCharged2Tapped()
        fun onResetTapped()
        fun onManualFastDelta(delta: Int)
        fun onMoveSelected(kind: MoveKind, move: MoveDef)
    }

    enum class MoveKind { FAST, CHARGED_1, CHARGED_2 }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.WHITE
    }

    private var state = BattleUiState()
    private var scaleFactor = settings.scale
    private var opacityFactor = settings.opacity
    private var hudAlpha = 255
    private var collapsed = false
    private var editTarget: String? = null
    private var switchChoicePromptVisible = false
    private var nativeTopCardYpx: Int? = null
    private var blockAlphaMultiplier = 1f
    private val reserveUsableCache = booleanArrayOf(true, true)
    private val pokemonIconAtlas = PokemonIconAtlas(context.applicationContext)
    /** Current native reserve-card crops, indexed by top/bottom card. */
    private val reservePortraits = arrayOfNulls<Bitmap>(2)
    /** Stable team-selection/card portraits, indexed by original team slot 0..2. */
    private val ownTeamPortraits = arrayOfNulls<Bitmap>(3)
    private val adviceStabilityGate = AdviceStabilityGate()
    private var stableAdvice: BattleAssistAdvisor.Advice? = null

    /**
     * Live native-card crops are a visual fallback only. They never confer identity confidence.
     * The offline atlas remains primary once species/form is known.
     */
    fun setReservePortraits(first: Bitmap?, second: Bitmap?) {
        replaceReservePortrait(0, first)
        replaceReservePortrait(1, second)
        promoteMappedReservePortraits()
        postInvalidate()
    }

    /**
     * Team portraits survive native-card reordering and therefore remain usable after a
     * switch/faint and inside the switch chooser. Null entries do not erase a good cache.
     */
    fun setOwnTeamPortraits(portraits: List<Bitmap?>) {
        portraits.take(3).forEachIndexed { index, portrait ->
            if (portrait != null && !portrait.isRecycled) replaceOwnTeamPortrait(index, portrait)
        }
        promoteMappedReservePortraits()
        postInvalidate()
    }

    fun clearReservePortraits() {
        replaceReservePortrait(0, null)
        replaceReservePortrait(1, null)
        postInvalidate()
    }

    fun clearPokemonPortraits() {
        replaceReservePortrait(0, null)
        replaceReservePortrait(1, null)
        ownTeamPortraits.indices.forEach { replaceOwnTeamPortrait(it, null) }
        postInvalidate()
    }

    fun release() {
        clearPokemonPortraits()
    }

    private fun replaceReservePortrait(index: Int, next: Bitmap?) {
        if (index !in 0..1) {
            next?.let { if (!it.isRecycled) it.recycle() }
            return
        }
        val old = reservePortraits[index]
        if (old !== next) old?.let { if (!it.isRecycled) it.recycle() }
        reservePortraits[index] = next?.takeUnless { it.isRecycled }
    }

    private fun replaceOwnTeamPortrait(index: Int, next: Bitmap?) {
        if (index !in ownTeamPortraits.indices) {
            next?.let { if (!it.isRecycled) it.recycle() }
            return
        }
        val old = ownTeamPortraits[index]
        if (old !== next) old?.let { if (!it.isRecycled) it.recycle() }
        ownTeamPortraits[index] = next?.takeUnless { it.isRecycled }
    }

    /**
     * Once CP/species evidence maps a native reserve card to a stable team slot, copy the
     * live card portrait into that slot. This makes the icon independent from atlas
     * availability and keeps it after the native card disappears during a switch prompt.
     */
    private fun promoteMappedReservePortraits() {
        state.reserves.take(2).forEachIndexed { cardIndex, reserve ->
            val teamSlot = reserve.teamSlot ?: return@forEachIndexed
            if (!reserve.cardMappingConfirmed || teamSlot !in ownTeamPortraits.indices) return@forEachIndexed
            val source = reservePortraits.getOrNull(cardIndex)
                ?.takeUnless { it.isRecycled }
                ?: return@forEachIndexed
            val existing = ownTeamPortraits[teamSlot]
            if (existing == null || existing.isRecycled) {
                replaceOwnTeamPortrait(
                    teamSlot,
                    source.copy(Bitmap.Config.ARGB_8888, false)
                )
            }
        }
    }

    fun render(newState: BattleUiState) {
        if (newState.playerName.isNullOrBlank() && newState.opponentName.isNullOrBlank()) {
            adviceStabilityGate.reset()
            stableAdvice = null
        }
        state = newState
        promoteMappedReservePortraits()
        scaleFactor = settings.scale
        opacityFactor = settings.opacity
        if (!switchChoicePromptVisible) {
            newState.reserves.take(2).forEachIndexed { index, reserve ->
                reserveUsableCache[index] = reserve.visible
            }
        }
        invalidate()
    }

    fun setCollapsed(value: Boolean) { collapsed = value; invalidate() }
    fun isCollapsed(): Boolean = collapsed
    fun setSwitchChoicePromptVisible(value: Boolean) {
        if (switchChoicePromptVisible != value) {
            switchChoicePromptVisible = value
            invalidate()
        }
    }
    fun setNativeTopCardY(y: Int?) { if (y != null) nativeTopCardYpx = y; invalidate() }
    fun selectedEditTarget(): String? = editTarget
    fun blockPosition(block: String): Pair<Float, Float>? = when (block) {
        BLOCK_PLAYER_MATCHUP -> topPos(block, PLAYER_MATCHUP_X)
        BLOCK_STRONG_TYPES -> topPos(block, STRONG_TYPES_RIGHT_X)
        BLOCK_RESERVE_1 -> reservePos(0)
        BLOCK_RESERVE_2 -> reservePos(1)
        BLOCK_ENEMY_MOVES -> pos(block, ENEMY_MOVE_CENTER_X, ENEMY_MOVE_FAST_Y)
        BLOCK_BATTLE_ASSIST -> pos(block, BATTLE_ASSIST_X, BATTLE_ASSIST_Y)
        BLOCK_HUD_TOGGLE -> pos(block, HUD_TOGGLE_CENTER_X, HUD_TOGGLE_CENTER_Y)
        BLOCK_HUD_LOCK -> pos(block, HUD_LOCK_CENTER_X, HUD_LOCK_CENTER_Y)
        BLOCK_ENEMY_HISTORY -> pos(block, ENEMY_HISTORY_X, ENEMY_HISTORY_Y)
        else -> null
    }
    fun showMoveMenu(kind: MoveKind, options: List<MoveDef>) = Unit
    fun dismissMenu() = Unit

    private fun sx(): Float = width / 864f
    private fun sy(): Float = height / 1536f
    private fun u(): Float = min(sx(), sy()) * (scaleFactor / .8f).coerceIn(.62f, 1.5f)
    private fun rx(v: Float): Float = v * sx()
    private fun ry(v: Float): Float = v * sy()
    private fun refX(px: Float): Float = if (sx() > 0f) px / sx() else px
    private fun refY(px: Float): Float = if (sy() > 0f) px / sy() else px

    private fun pos(block: String, defaultX: Float, defaultY: Float): Pair<Float, Float> =
        settings.getPosition(block, defaultX, defaultY)


    /**
     * Default top-row Y. No status-bar/card safe floor: the user may place icons
     * anywhere including the very top edge.
     */
    private fun dynamicTopIconRefY(): Float {
        // Tracking nativeTopCardY every frame caused the top icons to jump/flicker.
        return TOP_ICON_Y
    }

    private fun topPos(block: String, defaultX: Float): Pair<Float, Float> =
        if (settings.hasPosition(block)) settings.getPosition(block, defaultX, TOP_ICON_Y)
        else defaultX to dynamicTopIconRefY()

    private inline fun withBlockAlpha(block: String, draw: () -> Unit) {
        if (settings.isBlockHidden(block)) return
        val old = blockAlphaMultiplier
        blockAlphaMultiplier = settings.blockOpacity(block)
        try { draw() } finally { blockAlphaMultiplier = old }
    }

    /** Backward-compatible defaults: an old combined reserve position becomes the base for both slots. */
    private fun reservePos(index: Int): Pair<Float, Float> {
        val legacy = pos(BLOCK_RESERVES_LEGACY, RESERVE_INDICATOR_X, RESERVE_FIRST_Y)
        val block = if (index == 0) BLOCK_RESERVE_1 else BLOCK_RESERVE_2
        return pos(block, legacy.first, legacy.second + index * RESERVE_SPACING_Y)
    }

    /** Returns the draggable HUD block under a screen coordinate while edit mode is active. */
    fun dragTargetAt(screenX: Float, screenY: Float): String? {
        if ((settings.layoutLocked && !settings.editMode) || collapsed || width <= 0 || height <= 0) return null
        val x = refX(screenX)
        val y = refY(screenY)

        fun near(block: String, dx: Float, dy: Float, halfW: Float, halfH: Float): Boolean {
            // When the global padlock is open, ALL HUD blocks must be movable.
            // Per-block locks only apply inside the legacy fine-edit mode while the
            // global layout itself is locked.
            if ((settings.isBlockLocked(block) && settings.layoutLocked) || settings.isBlockHidden(block)) return false
            val (cx, cy) = if (block == BLOCK_PLAYER_MATCHUP || block == BLOCK_STRONG_TYPES) topPos(block, dx) else pos(block, dx, dy)
            return x in (cx-halfW)..(cx+halfW) && y in (cy-halfH)..(cy+halfH)
        }

        if (near(BLOCK_HUD_TOGGLE, HUD_TOGGLE_CENTER_X, HUD_TOGGLE_CENTER_Y, 32f, 32f)) return BLOCK_HUD_TOGGLE
        if (near(BLOCK_HUD_LOCK, HUD_LOCK_CENTER_X, HUD_LOCK_CENTER_Y, 32f, 32f)) return BLOCK_HUD_LOCK
        if (near(BLOCK_PLAYER_MATCHUP, PLAYER_MATCHUP_X, TOP_ICON_Y, 34f, 30f)) return BLOCK_PLAYER_MATCHUP
        if (near(BLOCK_BATTLE_ASSIST, BATTLE_ASSIST_X, BATTLE_ASSIST_Y, 190f, 45f)) return BLOCK_BATTLE_ASSIST

        val strong = state.strongTypesAgainstOpponent.take(4)
        if (strong.isNotEmpty() && !settings.isBlockHidden(BLOCK_STRONG_TYPES) &&
            !(settings.isBlockLocked(BLOCK_STRONG_TYPES) && settings.layoutLocked)) {
            val (right, cy) = topPos(BLOCK_STRONG_TYPES, STRONG_TYPES_RIGHT_X)
            val left = right - 35f * (strong.size - 1).coerceAtLeast(0) - 28f
            if (x in left..(right + 48f) && y in (cy-30f)..(cy+30f)) return BLOCK_STRONG_TYPES
        }

        val (mx, my) = pos(BLOCK_ENEMY_MOVES, ENEMY_MOVE_CENTER_X, ENEMY_MOVE_FAST_Y)
        if (!settings.isBlockHidden(BLOCK_ENEMY_MOVES) &&
            !(settings.isBlockLocked(BLOCK_ENEMY_MOVES) && settings.layoutLocked) &&
            x in (mx-150f)..(mx+150f) && y in (my-55f)..(my+205f)) return BLOCK_ENEMY_MOVES

        val (r1x, r1y) = reservePos(0)
        if (!settings.isBlockHidden(BLOCK_RESERVE_1) &&
            !(settings.isBlockLocked(BLOCK_RESERVE_1) && settings.layoutLocked) &&
            x in (r1x-65f)..(r1x+175f) && y in (r1y-55f)..(r1y+125f)) return BLOCK_RESERVE_1
        val (r2x, r2y) = reservePos(1)
        if (!settings.isBlockHidden(BLOCK_RESERVE_2) &&
            !(settings.isBlockLocked(BLOCK_RESERVE_2) && settings.layoutLocked) &&
            x in (r2x-65f)..(r2x+175f) && y in (r2y-55f)..(r2y+125f)) return BLOCK_RESERVE_2
        if (near(BLOCK_ENEMY_HISTORY, ENEMY_HISTORY_X, ENEMY_HISTORY_Y, 180f, 80f)) return BLOCK_ENEMY_HISTORY
        return null
    }

    fun setEditTarget(block: String?) {
        editTarget = block
        invalidate()
    }

    /** Persists exactly where the user drags the block. No positional restrictions are applied. */
    fun moveDragTarget(block: String, screenX: Float, screenY: Float) {
        if ((settings.layoutLocked && !settings.editMode) || width <= 0 || height <= 0) return
        if (settings.isBlockLocked(block) && settings.layoutLocked) return
        settings.setPosition(block, refX(screenX), refY(screenY))
        editTarget = block
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        hudAlpha = (255f * opacityFactor).toInt().coerceIn(76, 255)
        paint.alpha = 255
        stroke.alpha = 255
        stroke.color = hudColor(Color.WHITE)

        if (collapsed) {
            drawVisibilityToggle(canvas, true)
            return
        }

        if (settings.showCurrentIndicator) withBlockAlpha(BLOCK_PLAYER_MATCHUP) {
            val (x, y) = topPos(BLOCK_PLAYER_MATCHUP, PLAYER_MATCHUP_X)
            val sx = rx(x)
            val sy = ry(y)
            val bs = settings.blockScale(BLOCK_PLAYER_MATCHUP)
            drawMatchupIndicator(canvas, sx, sy, state.playerMatchup, 14f*u()*bs)
            if (settings.showHudText && settings.showHpAssist) {
                state.playerHpRatio?.let { hp ->
                    paint.color = hudColor(Color.WHITE, .92f)
                    paint.textAlign = Paint.Align.CENTER
                    paint.typeface = Typeface.DEFAULT_BOLD
                    paint.textSize = 9.2f*u()*bs
                    paint.setShadowLayer(3f, 0f, 1f, Color.BLACK)
                    canvas.drawText("HP " + (hp.coerceIn(0f,1f)*100f).toInt() + "%", sx, sy + 29f*u()*bs, paint)
                    paint.clearShadowLayer()
                }
            }
        }
        drawIncomingDamagePreview(canvas)
        if (settings.showStrongTypes) withBlockAlpha(BLOCK_STRONG_TYPES) { drawStrongTypes(canvas) }
        if (settings.showEnemyMoves) withBlockAlpha(BLOCK_ENEMY_MOVES) { drawMoveTriangle(canvas) }
        if (state.chargedIncoming && settings.showEnemyMoves && !settings.battleAssistEnabled) drawShieldAdvice(canvas)
        if (settings.analyzeReserves) {
            if (switchChoicePromptVisible) drawSwitchChoiceIndicators(canvas) else drawReserves(canvas)
        }
        if (settings.battleAssistEnabled) withBlockAlpha(BLOCK_BATTLE_ASSIST) { drawBattleAssist(canvas) }
        if (settings.showEnemyHistory) withBlockAlpha(BLOCK_ENEMY_HISTORY) { drawEnemyHistory(canvas) }
        if (settings.showSwitchTimer) drawTimers(canvas)
        if (settings.editMode) {
            drawResetAndCorrection(canvas)
            drawEditModeGuide(canvas)
        }
        if (settings.debugMode) drawDebug(canvas)
        drawLayoutLock(canvas)
        drawVisibilityToggle(canvas, false)
    }

    private fun drawLayoutLock(canvas: Canvas) {
        val (refCx, refCy) = pos(BLOCK_HUD_LOCK, HUD_LOCK_CENTER_X, HUD_LOCK_CENTER_Y)
        val cx = rx(refCx); val cy = ry(refCy); val radius = 16f*u()
        paint.style = Paint.Style.FILL
        paint.color = hudColor(if (settings.layoutLocked) Color.rgb(72,76,82) else Color.rgb(45,112,82), .88f)
        canvas.drawCircle(cx, cy, radius, paint)
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = 1.5f*u(); stroke.color = hudColor(Color.WHITE, .72f)
        canvas.drawCircle(cx, cy, radius, stroke)
        val body = RectF(cx-7f*u(), cy-1.5f*u(), cx+7f*u(), cy+9f*u())
        stroke.strokeWidth = 1.8f*u(); stroke.color = hudColor(Color.WHITE)
        canvas.drawRoundRect(body, 2.2f*u(), 2.2f*u(), stroke)
        val shackle = RectF(cx-5.2f*u(), cy-8f*u(), cx+5.2f*u(), cy+3.2f*u())
        canvas.drawArc(shackle, 190f, 160f, false, stroke)
        if (!settings.layoutLocked) {
            // Open shackle: short break/offset on the right makes unlocked state obvious.
            paint.style = Paint.Style.FILL; paint.color = hudColor(if (settings.layoutLocked) Color.rgb(72,76,82) else Color.rgb(45,112,82))
            canvas.drawRect(cx+3.8f*u(), cy-7.5f*u(), cx+8.2f*u(), cy-1f*u(), paint)
            stroke.color = hudColor(Color.WHITE); stroke.strokeWidth = 1.8f*u()
            canvas.drawLine(cx+4.7f*u(), cy-7.0f*u(), cx+7.4f*u(), cy-9.0f*u(), stroke)
        }
        drawEditSelection(canvas, BLOCK_HUD_LOCK, cx, cy, 25f*u(), 25f*u())
    }

    private fun drawVisibilityToggle(canvas: Canvas, hidden: Boolean) {
        val (refCx, refCy) = pos(BLOCK_HUD_TOGGLE, HUD_TOGGLE_CENTER_X, HUD_TOGGLE_CENTER_Y)
        val cx = rx(refCx); val cy = ry(refCy); val radius = 16f*u()
        paint.style = Paint.Style.FILL
        paint.color = hudColor(Color.rgb(72,76,82), .88f)
        canvas.drawCircle(cx, cy, radius, paint)
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = 1.5f*u()
        stroke.color = hudColor(Color.WHITE, .68f)
        canvas.drawCircle(cx, cy, radius, stroke)
        val eye = RectF(cx-8.2f*u(), cy-4.7f*u(), cx+8.2f*u(), cy+4.7f*u())
        stroke.color = hudColor(Color.WHITE); stroke.strokeWidth = 1.8f*u()
        canvas.drawOval(eye, stroke)
        paint.color = hudColor(Color.WHITE); canvas.drawCircle(cx,cy,2.6f*u(),paint)
        if (!hidden) {
            stroke.strokeWidth = 2.1f*u()
            canvas.drawLine(cx-9f*u(),cy+7.7f*u(),cx+9f*u(),cy-7.7f*u(),stroke)
        }
        drawEditSelection(canvas, BLOCK_HUD_TOGGLE, cx, cy, 25f*u(), 25f*u())
    }

    private fun drawMatchupIndicator(canvas: Canvas, cx: Float, cy: Float, matchup: MatchupState, radius: Float = 17f*u()) {
        paint.style = Paint.Style.FILL
        paint.color = when (matchup) {
            MatchupState.FAVORABLE -> hudColor(Color.rgb(20,190,80))
            MatchupState.UNFAVORABLE -> hudColor(Color.rgb(225,45,55))
            MatchupState.NEUTRAL -> hudColor(Color.rgb(145,145,145))
            MatchupState.UNKNOWN -> hudColor(Color.rgb(105,105,105))
        }
        canvas.drawCircle(cx,cy,radius,paint)
        stroke.color = hudColor(Color.WHITE); stroke.strokeWidth = 2f*u(); canvas.drawCircle(cx,cy,radius,stroke)
        paint.color = hudColor(Color.WHITE); paint.textAlign = Paint.Align.CENTER; paint.typeface = Typeface.DEFAULT_BOLD
        when (matchup) {
            MatchupState.FAVORABLE -> { paint.textSize=19f*u(); canvas.drawText("✓",cx,cy+6f*u(),paint) }
            MatchupState.UNFAVORABLE -> { paint.textSize=20f*u(); canvas.drawText("×",cx,cy+6.3f*u(),paint) }
            MatchupState.NEUTRAL -> canvas.drawCircle(cx,cy,5.6f*u(),paint)
            MatchupState.UNKNOWN -> { paint.textSize=13f*u(); canvas.drawText("?",cx,cy+4.5f*u(),paint) }
        }
        if (settings.editMode) {
            val (mx, my) = topPos(BLOCK_PLAYER_MATCHUP, PLAYER_MATCHUP_X)
            if (kotlin.math.abs(cx-rx(mx)) < 2f && kotlin.math.abs(cy-ry(my)) < 2f) {
                drawEditSelection(canvas, BLOCK_PLAYER_MATCHUP, cx, cy, 24f*u(), 24f*u())
            }
        }
    }

    private fun drawIncomingDamagePreview(canvas: Canvas) {
        if (!settings.showHpAssist) return
        val forecast = state.incomingDamagePreview ?: return
        val hp = state.playerHpRatio?.coerceIn(0f, 1f) ?: return
        val yFraction = state.playerHpBarYFraction?.takeIf { it in 0f..1f } ?: return
        if (
            state.captureHealth == com.lucianotoscano.pvppokego.data.CaptureHealth.STALE ||
            state.captureHealth == com.lucianotoscano.pvppokego.data.CaptureHealth.PAUSED
        ) return

        // Same player HP ROI used by BattleFrameEventDetector. Draw above the native
        // bar so the preview never hides Pokemon GO current HP.
        val left = width * .035f
        val right = width * .405f
        val fullWidth = (right - left).coerceAtLeast(1f)
        val y = height * yFraction - 8f * u()
        val barHeight = 5.2f * u()
        val currentX = left + fullWidth * hp
        val minDamage = (forecast.minPercent / 100f).coerceIn(0f, 1f)
        val maxDamage = (forecast.maxPercent / 100f).coerceIn(minDamage, 1f)
        val bestRemaining = (hp - minDamage).coerceIn(0f, hp)
        val worstRemaining = (hp - maxDamage).coerceIn(0f, hp)
        val bestX = left + fullWidth * bestRemaining
        val worstX = left + fullWidth * worstRemaining

        val predictiveOnly = !state.chargedIncoming
        paint.style = Paint.Style.FILL
        paint.color = hudColor(Color.rgb(238, 70, 64), if (predictiveOnly) .22f else .34f)
        canvas.drawRoundRect(RectF(worstX, y, currentX, y + barHeight), 2.5f*u(), 2.5f*u(), paint)
        paint.color = hudColor(Color.rgb(255, 184, 58), .55f)
        canvas.drawRect(bestX - 1.2f*u(), y - 1f*u(), bestX + 1.2f*u(), y + barHeight + 1f*u(), paint)
        paint.color = hudColor(Color.rgb(238, 70, 64), .78f)
        canvas.drawRect(worstX - 1.2f*u(), y - 1f*u(), worstX + 1.2f*u(), y + barHeight + 1f*u(), paint)

        if (settings.showHudText) {
            val remainMin = (hp * 100f - forecast.maxPercent).coerceIn(0f, 100f)
            val remainMax = (hp * 100f - forecast.minPercent).coerceIn(0f, 100f)
            val source = when (forecast.confidence) {
                com.lucianotoscano.pvppokego.data.DamageForecastConfidence.OBSERVED_STABLE -> "OBS"
                com.lucianotoscano.pvppokego.data.DamageForecastConfidence.OBSERVED_ESTIMATED -> "OBS~"
                com.lucianotoscano.pvppokego.data.DamageForecastConfidence.MODEL -> "MODELO"
            }
            val phase = if (predictiveOnly) "PREVIEW" else "IMPACTO"
            val noShield = "SEM ESC -" + "%.0f".format(forecast.minPercent) + "-" +
                "%.0f".format(forecast.maxPercent) + "% →" + "%.0f".format(remainMin) + "-" +
                "%.0f".format(remainMax) + "%"
            val withShield = when {
                !state.ownShieldsKnown -> "COM ESC se disponível"
                state.ownShieldsRemaining > 0 -> "COM ESC protegido"
                else -> "COM ESC indisponível"
            }
            val ko = when {
                remainMax <= 0.5f -> " • KO PROVÁVEL"
                remainMin <= 0.5f -> " • KO POSSÍVEL"
                else -> ""
            }
            val label = phase + " • " + noShield + " • " + withShield + ko + " • " + source
            paint.color = hudColor(Color.WHITE, .96f)
            paint.textAlign = Paint.Align.LEFT
            paint.textSize = 8.8f*u()
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.setShadowLayer(3f, 0f, 1.2f, Color.BLACK)
            canvas.drawText(label, left, y - 4f*u(), paint)
            paint.clearShadowLayer()
        }
    }

    private fun drawStrongTypes(canvas: Canvas) {
        val strong = state.strongTypesAgainstOpponent.take(4)
        if (strong.isEmpty()) return
        val bs = settings.blockScale(BLOCK_STRONG_TYPES)
        val radius = 14f*u()*bs
        val (rightRef, yRef) = topPos(BLOCK_STRONG_TYPES, STRONG_TYPES_RIGHT_X)
        val y = ry(yRef)
        val spacing = rx(35f) * bs
        val strongRight = rx(rightRef)
        val arrowX = strongRight + rx(36f)
        val firstStrong = strongRight - spacing*(strong.size-1).coerceAtLeast(0)
        strong.forEachIndexed { i,type -> drawTypeChip(canvas,firstStrong+spacing*i,y,type,radius) }
        paint.color=hudColor(Color.WHITE); paint.textAlign=Paint.Align.CENTER; paint.textSize=18f*u(); paint.typeface=Typeface.DEFAULT_BOLD
        paint.setShadowLayer(2.5f,0f,1f,Color.BLACK); canvas.drawText("›",arrowX,y+5.8f*u(),paint); paint.clearShadowLayer()
        val centerX = (firstStrong + strongRight) / 2f
        val halfW = (strongRight-firstStrong)/2f + 27f*u()
        drawEditSelection(canvas, BLOCK_STRONG_TYPES, centerX, y, halfW, 24f*u())
    }

    private fun drawMoveTriangle(canvas: Canvas) {
        val (centerRefX, fastRefY) = pos(BLOCK_ENEMY_MOVES, ENEMY_MOVE_CENTER_X, ENEMY_MOVE_FAST_Y)
        val bs = settings.blockScale(BLOCK_ENEMY_MOVES)
        val centerX=rx(centerRefX); val fastY=ry(fastRefY); val chargedY=fastY+ry(112f)*bs
        val spread=rx(74f)*bs; val fastRadius=31f*u()*bs; val chargedRadius=41f*u()*bs
        state.fastMove?.let { m ->
            drawMoveCircle(canvas,centerX,fastY,fastRadius,m)
            drawObservedDamageBadge(canvas, centerX, fastY-fastRadius-10f*u()*bs, state.incomingFastDamage)
            drawMoveLabel(
                canvas,
                centerX,
                fastY+fastRadius+20f*u(),
                shortMoveName(m.name)+" "+knowledgeMark(state.fastMoveKnowledge)+" ▼"
            )
        }
        state.charged1?.let { p ->
            val cx=centerX-spread
            drawCharged(canvas,cx,chargedY,chargedRadius,p)
            drawObservedDamageBadge(canvas, cx, chargedY-chargedRadius-10f*u()*bs, state.incomingCharged1Damage)
            val labelY=chargedY+chargedRadius+22f*u()
            drawMoveLabel(canvas,cx,labelY,shortMoveName(p.move.name)+" "+knowledgeMark(state.charged1Knowledge)+" ▼")
            drawEnemySuperEffectiveText(canvas,cx,labelY+17f*u(),p.move)
        }
        state.charged2?.let { p ->
            val cx=centerX+spread
            drawCharged(canvas,cx,chargedY,chargedRadius,p)
            drawObservedDamageBadge(canvas, cx, chargedY-chargedRadius-10f*u()*bs, state.incomingCharged2Damage)
            val labelY=chargedY+chargedRadius+22f*u()
            drawMoveLabel(canvas,cx,labelY,shortMoveName(p.move.name)+" "+knowledgeMark(state.charged2Knowledge)+" ▼")
            drawEnemySuperEffectiveText(canvas,cx,labelY+17f*u(),p.move)
        }
        if (settings.showHudText) {
            val predictive = state.enemyEnergyForecast
            if (predictive != null) {
                val energyText = if (predictive.energyMin == predictive.energyMax) {
                    "⚡ " + predictive.energyLikely + " E"
                } else {
                    "⚡ " + predictive.energyMin + "-" + predictive.energyMax + " E"
                }
                val fastHypothesis = if (predictive.fastMoveNames.size > 1) {
                    " • " + predictive.fastMoveNames.size + " FAST possíveis"
                } else ""
                val phaseText = predictive.fastPhase?.takeIf { it.confidence >= .45f }?.let {
                    " • Fase " + it.phaseTurn + "/" + it.moveTurns
                }.orEmpty()
                val qualityText = " • C" + (predictive.consistencyScore * 100f).toInt().coerceIn(0, 100) + "%"
                val cmpText = when (state.cmpForecast?.outcome) {
                    com.lucianotoscano.pvppokego.data.CmpOutcome.WIN -> " • CMP+"
                    com.lucianotoscano.pvppokego.data.CmpOutcome.LOSE -> " • CMP-"
                    com.lucianotoscano.pvppokego.data.CmpOutcome.UNCERTAIN -> " • CMP?"
                    else -> ""
                }
                paint.color=hudColor(Color.WHITE,.84f)
                paint.textAlign=Paint.Align.CENTER
                paint.textSize=9.4f*u()*bs
                paint.typeface=Typeface.DEFAULT_BOLD
                paint.setShadowLayer(2f,0f,1f,Color.BLACK)
                canvas.drawText(
                    energyText + fastHypothesis + phaseText + qualityText + cmpText,
                    centerX,
                    chargedY+chargedRadius+53f*u()*bs,
                    paint
                )
                paint.clearShadowLayer()
                drawPredictiveThreatRows(
                    canvas,
                    centerX,
                    chargedY+chargedRadius+69f*u()*bs,
                    bs,
                    predictive
                )
            } else {
                val p = state.charged1 ?: state.charged2
                if (p != null) {
                    val energyText = when (p.confidence) {
                        EnergyConfidence.CONFIRMED -> "ENERGIA " + p.currentEnergy + " • CONFIRMADA"
                        EnergyConfidence.ESTIMATED -> "ENERGIA ~" + p.currentEnergy + " • ESTIMADA"
                        EnergyConfidence.RANGE -> "ENERGIA " + p.minEnergy + "-" + p.maxEnergy + " • FAIXA"
                    }
                    paint.color=hudColor(Color.WHITE,.82f)
                    paint.textAlign=Paint.Align.CENTER
                    paint.textSize=9.5f*u()*bs
                    paint.typeface=Typeface.DEFAULT_BOLD
                    paint.setShadowLayer(2f,0f,1f,Color.BLACK)
                    canvas.drawText(energyText,centerX,chargedY+chargedRadius+54f*u()*bs,paint)
                    paint.clearShadowLayer()
                }
            }
        }
        drawEditSelection(
            canvas,
            BLOCK_ENEMY_MOVES,
            centerX,
            (fastY+chargedY)/2f + 11f*u()*bs,
            142f*u()*bs,
            148f*u()*bs
        )
    }

    private fun drawPredictiveThreatRows(
        canvas: Canvas,
        centerX: Float,
        firstY: Float,
        bs: Float,
        forecast: com.lucianotoscano.pvppokego.data.EnemyEnergyForecast
    ) {
        val candidates = forecast.candidates
            .sortedWith(
                compareByDescending<com.lucianotoscano.pvppokego.data.ChargedThreatForecast> { it.readyCertain }
                    .thenByDescending { it.readyPossible }
                    .thenBy { it.turnsRemainingMin }
                    .thenByDescending { it.threatScore }
            )
            .take(2)
        candidates.forEachIndexed { index, candidate ->
            val status = when {
                candidate.readyCertain && candidate.likelyKo -> "DANGER"
                candidate.readyCertain -> "READY"
                candidate.readyPossible -> "READY?"
                candidate.turnsRemainingMin <= forecast.reactionTurns -> "PREPARE"
                else -> "+" + candidate.fastMovesRemainingLikely + " FAST"
            }
            val time = when {
                candidate.readyPossible -> "agora"
                candidate.likelyTimeMs <= 0L -> "agora"
                else -> "~" + "%.1f".format(candidate.likelyTimeMs / 1000f) + "s"
            }
            val confidence = (candidate.confidence * 100f).toInt().coerceIn(0, 100)
            val sequence = candidate.fastCountSequenceLikely
                .takeIf { it.isNotEmpty() }
                ?.let { " • " + it.joinToString("-") }
                .orEmpty()
            val bait = candidate.baitProbability
                .takeIf { it >= .20f }
                ?.let { " • bait " + "%.0f".format(it * 100f) + "%" }
                .orEmpty()
            val line = shortMoveName(candidate.move.name) + "  " + status + " • " + time +
                sequence + bait + " • " + confidence + "%"
            paint.color = hudColor(
                when {
                    candidate.readyCertain && candidate.likelyKo -> Color.rgb(244, 72, 68)
                    candidate.readyPossible -> Color.rgb(244, 112, 64)
                    candidate.turnsRemainingMin <= forecast.reactionTurns -> Color.rgb(245, 175, 52)
                    else -> Color.WHITE
                },
                .94f
            )
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 9.2f*u()*bs
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.setShadowLayer(3f,0f,1.1f,Color.BLACK)
            canvas.drawText(line, centerX, firstY + index * 15f*u()*bs, paint)
            paint.clearShadowLayer()
        }
    }

    private fun drawObservedDamageBadge(
        canvas: Canvas,
        x: Float,
        y: Float,
        estimate: com.lucianotoscano.pvppokego.data.ObservedDamageEstimate?
    ) {
        if (!settings.showHudText || estimate == null) return
        paint.color = hudColor(Color.WHITE, .90f)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 9.0f*u()
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.setShadowLayer(3f, 0f, 1f, Color.BLACK)
        val sampleSuffix = if (estimate.samples > 1) " • " + estimate.samples + "x" else ""
        canvas.drawText(
            "~" + "%.0f".format(estimate.averagePercent) + "% HP" + sampleSuffix,
            x,
            y,
            paint
        )
        paint.clearShadowLayer()
    }

    private fun drawEnemySuperEffectiveText(canvas: Canvas, x: Float, y: Float, move: MoveDef) {
        val playerTypes = currentPlayerTypes()
        if (playerTypes.isEmpty()) return
        if (TypeChart.multiplier(move.type, playerTypes) <= 1.01) return
        paint.color = hudColor(Color.rgb(255, 153, 45))
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 10.8f*u()
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.setShadowLayer(3.4f,0f,1.3f,Color.BLACK)
        canvas.drawText("SUPEREFICAZ!",x,y,paint)
        paint.clearShadowLayer()
    }

    private fun drawShieldAdvice(canvas: Canvas) {
        val formal = state.shieldDecision
        val advice = if (formal != null) {
            val title = when (formal.action) {
                com.lucianotoscano.pvppokego.data.ShieldAction.SHIELD -> "USAR ESCUDO"
                com.lucianotoscano.pvppokego.data.ShieldAction.HOLD ->
                    if (state.ownShieldsKnown && state.ownShieldsRemaining <= 0) "SEM ESCUDOS" else "PODE NÃO USAR"
                com.lucianotoscano.pvppokego.data.ShieldAction.OPTIONAL -> "ESCUDO OPCIONAL"
                com.lucianotoscano.pvppokego.data.ShieldAction.UNKNOWN -> "ANALISANDO"
            }
            val color = when (formal.action) {
                com.lucianotoscano.pvppokego.data.ShieldAction.SHIELD -> Color.rgb(232,61,72)
                com.lucianotoscano.pvppokego.data.ShieldAction.HOLD -> Color.rgb(46,188,105)
                com.lucianotoscano.pvppokego.data.ShieldAction.OPTIONAL -> Color.rgb(245,175,52)
                com.lucianotoscano.pvppokego.data.ShieldAction.UNKNOWN -> Color.rgb(128,136,146)
            }
            val bait = formal.baitProbability.takeIf { it >= .20f }
                ?.let { " • bait ~" + "%.0f".format(it * 100f) + "%" }
                .orEmpty()
            ShieldAdvice(
                title,
                listOfNotNull(formal.moveName?.let(::shortMoveName), formal.reason.takeIf { it.isNotBlank() })
                    .joinToString(" • ") + bait,
                color
            )
        } else {
            val playerTypes = currentPlayerTypes()
            if (playerTypes.isEmpty()) return
            val all = listOfNotNull(state.charged1, state.charged2)
            if (all.isEmpty()) return
            val plausible = all.filter { it.ready || it.progress >= .72f }.ifEmpty { all }
            val threat = plausible.maxByOrNull { p ->
                p.move.power.toDouble() * TypeChart.multiplier(p.move.type, playerTypes)
            } ?: return
            val eff = TypeChart.multiplier(threat.move.type, playerTypes)
            val threatScore = threat.move.power.toDouble() * eff
            when {
                eff >= 1.5 || threatScore >= 100.0 -> ShieldAdvice(
                    "USAR ESCUDO", shortMoveName(threat.move.name) + " • " +
                        (if (eff > 1.01) "SUPEREFICAZ • " else "") + "DANO ALTO", Color.rgb(232,61,72)
                )
                eff <= .65 && threatScore < 70.0 -> ShieldAdvice(
                    "PODE NÃO USAR", shortMoveName(threat.move.name) + " • POUCO EFICAZ • DANO BAIXO", Color.rgb(46,188,105)
                )
                threatScore >= 70.0 -> ShieldAdvice(
                    "ESCUDO OPCIONAL", shortMoveName(threat.move.name) + " • DANO MÉDIO", Color.rgb(245,175,52)
                )
                else -> ShieldAdvice(
                    "PODE NÃO USAR", shortMoveName(threat.move.name) + " • DANO BAIXO", Color.rgb(46,188,105)
                )
            }
        }
        val (centerRefX, fastRefY) = pos(BLOCK_ENEMY_MOVES, ENEMY_MOVE_CENTER_X, ENEMY_MOVE_FAST_Y)
        val centerX=rx(centerRefX); val centerY=ry(fastRefY+237f); val halfW=142f*u(); val halfH=31f*u()
        val box=RectF(centerX-halfW,centerY-halfH,centerX+halfW,centerY+halfH)
        paint.style=Paint.Style.FILL; paint.color=hudColor(Color.rgb(22,27,34),.90f); canvas.drawRoundRect(box,16f*u(),16f*u(),paint)
        stroke.style=Paint.Style.STROKE; stroke.strokeWidth=2.4f*u(); stroke.color=hudColor(advice.color); canvas.drawRoundRect(box,16f*u(),16f*u(),stroke)
        paint.textAlign=Paint.Align.CENTER; paint.typeface=Typeface.DEFAULT_BOLD; paint.color=hudColor(advice.color); paint.textSize=16f*u(); paint.setShadowLayer(2f,0f,1f,Color.BLACK)
        canvas.drawText(advice.title,centerX,centerY-4f*u(),paint)
        paint.color=hudColor(Color.WHITE); paint.textSize=10.5f*u(); canvas.drawText(advice.detail,centerX,centerY+16f*u(),paint); paint.clearShadowLayer()
    }
    private fun drawCharged(canvas: Canvas,cx: Float,cy: Float,radius: Float,prediction: ChargedPrediction) {
        val circle=Path().apply { addCircle(cx,cy,radius,Path.Direction.CW) }
        canvas.save(); canvas.clipPath(circle)
        paint.style=Paint.Style.FILL
        paint.color=hudColor(Color.rgb(22,26,34),.84f)
        canvas.drawRect(cx-radius,cy-radius,cx+radius,cy+radius,paint)
        if (settings.showEnergyProgress) {
            val progress=prediction.progress.coerceIn(0f,1f)
            val primaryColor=typeColor(prediction.move.type)
            val fillTop=cy+radius-2f*radius*progress
            paint.color=hudColor(primaryColor,.92f)
            canvas.drawRect(cx-radius,fillTop,cx+radius,cy+radius,paint)
            if (progress>.02f && progress<.98f) {
                paint.color=hudColor(Color.WHITE,.62f)
                canvas.drawRect(cx-radius,fillTop-1.1f*u(),cx+radius,fillTop+1.1f*u(),paint)
            }

            // One full Charged stays visible; a darker second layer shows overfarm
            // toward another copy, up to Pokemon GO's 100-energy cap.
            if (prediction.ready && prediction.overflowProgress > .01f) {
                val overflow=prediction.overflowProgress.coerceIn(0f,1f)
                val overflowTop=cy+radius-2f*radius*overflow
                val dark=Color.rgb(
                    (Color.red(primaryColor)*.48f).toInt().coerceIn(0,255),
                    (Color.green(primaryColor)*.48f).toInt().coerceIn(0,255),
                    (Color.blue(primaryColor)*.48f).toInt().coerceIn(0,255)
                )
                paint.color=hudColor(dark,.96f)
                canvas.drawRect(cx-radius,overflowTop,cx+radius,cy+radius,paint)
                paint.color=hudColor(Color.WHITE,.72f)
                canvas.drawRect(cx-radius,overflowTop-1.1f*u(),cx+radius,overflowTop+1.1f*u(),paint)
            }
        } else {
            paint.color=hudColor(typeColor(prediction.move.type),.90f)
            canvas.drawRect(cx-radius,cy-radius,cx+radius,cy+radius,paint)
        }
        canvas.restore()
        stroke.style=Paint.Style.STROKE
        stroke.color=hudColor(Color.rgb(10,13,18),.82f)
        stroke.strokeWidth=5.2f*u()
        canvas.drawCircle(cx,cy,radius,stroke)
        stroke.color=hudColor(Color.WHITE,.68f)
        stroke.strokeWidth=1.5f*u()
        canvas.drawCircle(cx,cy,radius-1.2f*u(),stroke)
        drawTypeGlyph(canvas,cx,cy,prediction.move.type,23f*u())
        if (settings.showChargedCounter) drawChargeProgressDots(canvas,cx,cy,radius,prediction)
    }

    private fun drawChargeProgressDots(canvas: Canvas,cx: Float,cy: Float,radius: Float,prediction: ChargedPrediction) {
        val total=5
        val progress=if(prediction.ready) prediction.overflowProgress.coerceIn(0f,1f) else prediction.progress.coerceIn(0f,1f)
        val filled=if(prediction.ready){ if(progress>=.999f) total else floor(progress*total).toInt().coerceIn(0,total) } else { if(progress<=0f) 0 else ceil(progress*total).toInt().coerceIn(0,total) }
        val dotRadius=2.5f*u(); val gap=7.5f*u(); val startX=cx-gap*(total-1)/2f; val y=cy+radius*.72f
        repeat(total){i-> paint.style=Paint.Style.FILL; paint.color=if(i<filled) hudColor(Color.WHITE) else hudColor(Color.rgb(34,37,44),.92f); canvas.drawCircle(startX+i*gap,y,dotRadius,paint)}
    }

    private fun drawMoveCircle(canvas: Canvas,cx: Float,cy: Float,radius: Float,move: MoveDef) {
        val circle=Path().apply { addCircle(cx,cy,radius,Path.Direction.CW) }
        canvas.save(); canvas.clipPath(circle)
        paint.style=Paint.Style.FILL
        paint.color=hudColor(Color.rgb(22,26,34),.84f)
        canvas.drawRect(cx-radius,cy-radius,cx+radius,cy+radius,paint)
        paint.color=hudColor(typeColor(move.type),.88f)
        val fillTop=cy+radius*.05f
        canvas.drawRect(cx-radius,fillTop,cx+radius,cy+radius,paint)
        paint.color=hudColor(Color.WHITE,.48f)
        canvas.drawRect(cx-radius,fillTop-1f*u(),cx+radius,fillTop+1f*u(),paint)
        canvas.restore()
        stroke.style=Paint.Style.STROKE
        stroke.color=hudColor(Color.rgb(10,13,18),.82f)
        stroke.strokeWidth=4.7f*u()
        canvas.drawCircle(cx,cy,radius,stroke)
        stroke.color=hudColor(Color.WHITE,.66f)
        stroke.strokeWidth=1.4f*u()
        canvas.drawCircle(cx,cy,radius-1.1f*u(),stroke)
        drawTypeGlyph(canvas,cx,cy,move.type,20f*u())
    }

    private fun drawMoveLabel(canvas: Canvas,x: Float,y: Float,text: String) {
        paint.color=hudColor(Color.WHITE,.88f)
        paint.textAlign=Paint.Align.CENTER
        paint.textSize=12.5f*u()
        paint.typeface=Typeface.DEFAULT_BOLD
        paint.setShadowLayer(3f,0f,1f,Color.BLACK)
        canvas.drawText(text,x,y,paint)
        paint.clearShadowLayer()
    }

    private fun drawReserves(canvas: Canvas) {
        state.reserves.take(2).forEachIndexed { index, reserve ->
            val reserveName = reserve.name?.takeIf { it.isNotBlank() } ?: "POKÉMON ${index + 2}"
            val block = if (index == 0) BLOCK_RESERVE_1 else BLOCK_RESERVE_2
            if (settings.isBlockHidden(block)) return@forEachIndexed

            val bs = settings.blockScale(block)
            val oldAlpha = blockAlphaMultiplier
            blockAlphaMultiplier = settings.blockOpacity(block)

            val (indicatorRefX, cardRefY) = reservePos(index)
            val indicatorX = rx(indicatorRefX)
            val cardCenterY = ry(cardRefY)
            val identityReady = reserve.identityConfirmed
            val matchup = if (identityReady) reserve.matchup else MatchupState.UNKNOWN

            // Matchup badge belongs to the stable team identity, not to the still-unknown
            // upper/lower native card mapping.
            drawMatchupIndicator(
                canvas,
                indicatorX,
                cardCenterY,
                matchup,
                13.5f * u() * bs
            )

            val iconCenterX = indicatorX + rx(43f) * bs
            val iconRadius = 23f * u() * bs
            drawReservePokemonIcon(
                canvas = canvas,
                index = index,
                reserve = reserve,
                fallbackName = reserveName,
                centerX = iconCenterX,
                centerY = cardCenterY,
                radius = iconRadius
            )

            if (settings.showHudText) {
                val textX = indicatorX + rx(105f) * bs
                val title = buildString {
                    append(reserveName)
                    reserve.cp?.let { append(" PC ").append(it) }
                }
                paint.textAlign = Paint.Align.CENTER
                paint.typeface = Typeface.DEFAULT_BOLD
                paint.textSize = 10.0f * u() * bs
                paint.color = hudColor(Color.WHITE, .95f)
                paint.setShadowLayer(2.8f, 0f, 1f, Color.BLACK)
                canvas.drawText(shortReserveTitle(title), textX, cardCenterY - 8f * u() * bs, paint)

                val matchupText = when {
                    !identityReady -> "ANALISANDO"
                    matchup == MatchupState.UNKNOWN -> "ANALISANDO"
                    else -> reserveMatchupLabel(matchup)
                }
                val recommendation = reserve.recommendation
                    ?.takeIf { identityReady && (it == "MELHOR" || it == "EVITAR") }
                val hpLabel = if (settings.showHpAssist) {
                    reserve.hpRatio?.let { "HP " + (it.coerceIn(0f, 1f) * 100f).toInt() + "%" }
                } else null
                val lifeLabel = if (
                    reserve.status == com.lucianotoscano.pvppokego.data.TeamPokemonStatus.FAINTED
                ) "DESMAIADO" else null
                val status = listOfNotNull(lifeLabel, matchupText, recommendation, hpLabel)
                    .distinct()
                    .joinToString(" • ")

                paint.textSize = 9.1f * u() * bs
                paint.color = hudColor(
                    if (identityReady) reserveMatchupColor(matchup) else Color.rgb(190, 194, 201),
                    .96f
                )
                canvas.drawText(status, textX, cardCenterY + 10f * u() * bs, paint)
                paint.clearShadowLayer()
            }

            if (settings.showReserveTypes && reserve.types.isNotEmpty() && identityReady) {
                val typeRadius = 10.5f * u() * bs
                val spacing = rx(27f) * bs
                val centerX = indicatorX + rx(105f) * bs
                var x = centerX - if (reserve.types.size > 1) spacing / 2f else 0f
                reserve.types.take(2).forEach { type ->
                    drawTypeChip(canvas, x, cardCenterY + 31f * u() * bs, type, typeRadius)
                    x += spacing
                }
            }

            drawEditSelection(
                canvas,
                block,
                indicatorX + rx(72f) * bs,
                cardCenterY,
                132f * u() * bs,
                52f * u() * bs
            )
            blockAlphaMultiplier = oldAlpha
        }
    }

    private fun drawReservePokemonIcon(
        canvas: Canvas,
        index: Int,
        reserve: com.lucianotoscano.pvppokego.data.ReserveState,
        fallbackName: String,
        centerX: Float,
        centerY: Float,
        radius: Float
    ) {
        paint.style = Paint.Style.FILL
        paint.color = hudColor(Color.rgb(20, 24, 31), .72f)
        canvas.drawCircle(centerX, centerY, radius + 2.0f * u(), paint)

        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = 1.7f * u()
        stroke.color = hudColor(
            if (reserve.identityConfirmed) reserveMatchupColor(reserve.matchup) else Color.WHITE,
            .92f
        )
        canvas.drawCircle(centerX, centerY, radius + 1.0f * u(), stroke)

        val def = gameRepo.pokemon(reserve.speciesId ?: fallbackName)
        val destination = RectF(
            centerX - radius,
            centerY - radius,
            centerX + radius,
            centerY + radius
        )
        val oldPaintAlpha = paint.alpha
        paint.alpha = (hudAlpha * blockAlphaMultiplier).toInt().coerceIn(0, 255)
        val drawn = pokemonIconAtlas.draw(
            canvas = canvas,
            speciesId = reserve.speciesId ?: def?.speciesId,
            dex = def?.dex?.takeIf { it > 0 },
            destination = destination,
            paint = paint
        )
        paint.alpha = oldPaintAlpha

        if (!drawn) {
            val stableTeamPortrait = reserve.teamSlot
                // This portrait belongs to the stable team slot, just like the atlas
                // identity above; it does not require a confirmed native-card position.
                ?.takeIf { it in ownTeamPortraits.indices }
                ?.let { ownTeamPortraits[it] }
                ?.takeUnless { it.isRecycled }
            // A native crop belongs to a card position, not automatically to the
            // reserve's species/team slot. Switches may reorder native cards.
            // Never show another Pokémon's portrait while matching is ambiguous.
            val mappedCardPortrait = if (reserve.cardMappingConfirmed) {
                reservePortraits.getOrNull(index)?.takeUnless { it.isRecycled }
            } else null
            val portrait = stableTeamPortrait ?: mappedCardPortrait
            if (portrait != null) {
                val clip = Path().apply { addCircle(centerX, centerY, radius, Path.Direction.CW) }
                canvas.save()
                canvas.clipPath(clip)
                val previousAlpha = paint.alpha
                paint.alpha = (hudAlpha * blockAlphaMultiplier).toInt().coerceIn(0, 255)
                canvas.drawBitmap(portrait, null, destination, paint)
                paint.alpha = previousAlpha
                canvas.restore()
            } else {
                paint.color = hudColor(Color.WHITE, .95f)
                paint.textAlign = Paint.Align.CENTER
                paint.typeface = Typeface.DEFAULT_BOLD
                paint.textSize = radius * .72f
                val initials = fallbackName
                    .split(Regex("\\s+"))
                    .filter { it.isNotBlank() }
                    .take(2)
                    .joinToString("") { it.take(1).uppercase() }
                    .ifBlank { "?" }
                canvas.drawText(initials, centerX, centerY + radius * .25f, paint)
            }
        }
    }

    private fun reserveMatchupLabel(matchup: MatchupState): String = when (matchup) {
        MatchupState.FAVORABLE -> "FORTE"
        MatchupState.UNFAVORABLE -> "FRACO"
        MatchupState.NEUTRAL -> "NEUTRO"
        MatchupState.UNKNOWN -> "ANALISANDO"
    }

    private fun reserveMatchupColor(matchup: MatchupState): Int = when (matchup) {
        MatchupState.FAVORABLE -> Color.rgb(46, 188, 105)
        MatchupState.UNFAVORABLE -> Color.rgb(232, 61, 72)
        MatchupState.NEUTRAL -> Color.rgb(188, 191, 196)
        MatchupState.UNKNOWN -> Color.rgb(155, 160, 169)
    }

    private fun shortReserveTitle(value: String): String =
        if (value.length <= 23) value else value.take(22) + "…"

    /**
     * During Pokémon GO's switch chooser, keep the two confirmed card positions visible
     * even while matchup confidence is still UNKNOWN. The icon should never disappear
     * merely because opponent typing/recommendation is still being resolved.
     */
    private fun drawSwitchChoiceIndicators(canvas: Canvas) {
        val candidates = state.reserves.take(2).mapIndexedNotNull { index, reserve ->
            if (
                reserveUsableCache.getOrElse(index) { true } &&
                reserve.cardMappingConfirmed
            ) index to reserve else null
        }
        if (candidates.isEmpty()) return

        val indicatorY = ry(SWITCH_CHOICE_INDICATOR_Y)
        val iconY = ry(SWITCH_CHOICE_ICON_Y)
        candidates.forEach { (slotIndex, reserve) ->
            val x = if (slotIndex == 0) rx(SWITCH_CHOICE_LEFT_X) else rx(SWITCH_CHOICE_RIGHT_X)
            drawReservePokemonIcon(
                canvas = canvas,
                index = slotIndex,
                reserve = reserve,
                fallbackName = reserve.name ?: "POKÉMON ${slotIndex + 2}",
                centerX = x,
                centerY = iconY,
                radius = 30f * u()
            )
            drawMatchupIndicator(canvas, x, indicatorY, reserve.matchup, 16f*u())
            if (settings.showHudText && !reserve.recommendation.isNullOrBlank()) {
                paint.color=hudColor(when(reserve.recommendation){"MELHOR"->Color.rgb(46,188,105);"EVITAR"->Color.rgb(232,61,72);else->Color.WHITE})
                paint.textAlign=Paint.Align.CENTER; paint.textSize=10f*u(); paint.typeface=Typeface.DEFAULT_BOLD
                paint.setShadowLayer(2f,0f,1f,Color.BLACK); canvas.drawText(reserve.recommendation!!,x,indicatorY+30f*u(),paint); paint.clearShadowLayer()
            }
        }
    }

    private fun stableBattleAdvice(nowMs: Long = System.currentTimeMillis()): BattleAssistAdvisor.Advice? {
        val candidate = BattleAssistAdvisor.advise(state, gameRepo)
        val key = candidate?.let {
            AdviceStabilityGate.Key(it.title, it.detail, it.priority)
        }
        val accepted = adviceStabilityGate.update(key, nowMs)
        if (accepted == null) {
            stableAdvice = null
        } else if (candidate != null && accepted == key) {
            stableAdvice = candidate
        }
        return stableAdvice
    }

    private fun drawBattleAssist(canvas: Canvas) {
        if (switchChoicePromptVisible) return
        val advice = stableBattleAdvice() ?: return
        val (refCx, refCy) = pos(BLOCK_BATTLE_ASSIST, BATTLE_ASSIST_X, BATTLE_ASSIST_Y)
        val cx = rx(refCx)
        val cy = ry(refCy)
        val bs = settings.blockScale(BLOCK_BATTLE_ASSIST)
        val halfW = 176f*u()*bs
        val halfH = 30f*u()*bs
        val box = RectF(cx-halfW, cy-halfH, cx+halfW, cy+halfH)

        paint.style = Paint.Style.FILL
        paint.color = hudColor(Color.rgb(18,22,28), .78f)
        canvas.drawRoundRect(box, 15f*u(), 15f*u(), paint)
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = 2.2f*u()
        stroke.color = hudColor(advice.color, .98f)
        canvas.drawRoundRect(box, 15f*u(), 15f*u(), stroke)

        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.color = hudColor(advice.color)
        paint.textSize = 15.5f*u()
        paint.setShadowLayer(2.5f,0f,1f,Color.BLACK)
        canvas.drawText(advice.title, cx, cy-4f*u(), paint)
        paint.color = hudColor(Color.WHITE)
        paint.textSize = 10.5f*u()
        canvas.drawText(advice.detail, cx, cy+15f*u(), paint)
        paint.clearShadowLayer()

        drawEditSelection(canvas, BLOCK_BATTLE_ASSIST, cx, cy, halfW+5f*u(), halfH+5f*u())
    }

    private fun drawEnemyHistory(canvas: Canvas) {
        if (state.enemyHistory.isEmpty()) return
        val (refX, refY) = pos(BLOCK_ENEMY_HISTORY, ENEMY_HISTORY_X, ENEMY_HISTORY_Y)
        val bs = settings.blockScale(BLOCK_ENEMY_HISTORY)
        val x = rx(refX); val y = ry(refY)
        paint.textAlign=Paint.Align.RIGHT; paint.typeface=Typeface.DEFAULT_BOLD; paint.textSize=9.4f*u()*bs
        state.enemyHistory.take(3).forEachIndexed { i, line ->
            paint.color=hudColor(Color.WHITE,.86f); paint.setShadowLayer(2f,0f,1f,Color.BLACK)
            canvas.drawText(line,x,y+i*14f*u()*bs,paint)
        }
        paint.clearShadowLayer()
        drawEditSelection(canvas,BLOCK_ENEMY_HISTORY,x-rx(85f)*bs,y+12f*u()*bs,rx(95f)*bs,28f*u()*bs)
    }

    private fun drawTimers(canvas: Canvas) {
        paint.textAlign=Paint.Align.LEFT; paint.textSize=12f*u(); paint.typeface=Typeface.DEFAULT_BOLD; paint.color=hudColor(Color.WHITE)
        state.ownSwitchSeconds?.let{canvas.drawText("↻ $it",10f*u(),height*.47f,paint)}
        state.opponentSwitchSeconds?.let{canvas.drawText("↻ $it",width-58f*u(),height*.47f,paint)}
    }

    private fun drawResetAndCorrection(canvas: Canvas) {
        val y=ry(274f); val radius=15f*u(); val resetX=rx(22f)
        paint.color=hudColor(Color.rgb(45,45,45)); canvas.drawCircle(resetX,y,radius,paint); stroke.color=hudColor(Color.WHITE); canvas.drawCircle(resetX,y,radius,stroke)
        paint.color=hudColor(Color.WHITE); paint.textAlign=Paint.Align.CENTER; paint.textSize=16f*u(); canvas.drawText("↻",resetX,y+5f*u(),paint)
        val correctionY=y+40f*u(); listOf(resetX to "−",(resetX+38f*u()) to "+").forEach{(x,g)->paint.color=hudColor(Color.rgb(45,45,45));canvas.drawCircle(x,correctionY,radius,paint);canvas.drawCircle(x,correctionY,radius,stroke);paint.color=hudColor(Color.WHITE);canvas.drawText(g,x,correctionY+5f*u(),paint)}
    }

    private fun drawEditModeGuide(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = hudColor(Color.rgb(18,22,28), .72f)
        val cx = rx(432f); val cy = ry(302f); val box = RectF(cx-rx(175f),cy-ry(18f),cx+rx(175f),cy+ry(18f))
        canvas.drawRoundRect(box,12f*u(),12f*u(),paint)
        paint.color = hudColor(Color.WHITE)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 11.5f*u()
        paint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText("MODO EDIÇÃO • ARRASTE LIVRE • RESERVAS SEPARADAS",cx,cy+4f*u(),paint)
    }

    private fun drawEditSelection(canvas: Canvas, block: String, cx: Float, cy: Float, halfW: Float, halfH: Float) {
        if (!settings.editMode || editTarget != block) return
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = 1.7f*u()
        stroke.color = hudColor(Color.rgb(255,205,60), .95f)
        canvas.drawRoundRect(RectF(cx-halfW,cy-halfH,cx+halfW,cy+halfH),8f*u(),8f*u(),stroke)
    }

    private fun drawDebug(canvas: Canvas) {
        val text=state.debugText?:return; paint.color=hudColor(Color.BLACK,.78f); val box=RectF(8f,height-166f*u(),width*.82f,height-8f); canvas.drawRoundRect(box,8f,8f,paint)
        paint.color=hudColor(Color.WHITE); paint.textAlign=Paint.Align.LEFT; paint.textSize=10f*u(); text.lines().take(9).forEachIndexed{i,line->canvas.drawText(line,box.left+8f,box.top+(i+1)*14f*u(),paint)}
    }

    private fun drawTypeChip(canvas: Canvas,cx: Float,cy: Float,type: String,radius: Float) {
        paint.style=Paint.Style.FILL; paint.color=hudColor(Color.rgb(29,34,45),.62f); canvas.drawCircle(cx,cy+1.5f*u(),radius+2.2f*u(),paint)
        paint.color=hudColor(typeColor(type)); canvas.drawCircle(cx,cy,radius,paint); stroke.color=hudColor(Color.WHITE,.92f); stroke.strokeWidth=1.35f*u(); canvas.drawCircle(cx,cy,radius,stroke); drawTypeGlyph(canvas,cx,cy,type,radius*.95f); stroke.color=hudColor(Color.WHITE)
    }

    private fun drawTypeGlyph(canvas: Canvas,cx: Float,cy: Float,type: String,size: Float) {
        paint.style=Paint.Style.FILL; paint.color=hudColor(Color.WHITE); val path=TypeGlyphPaths.build(type,cx,cy,size*1.18f)
        if(path!=null){canvas.drawPath(path,paint);return}; paint.textAlign=Paint.Align.CENTER; paint.textSize=size*1.05f; paint.typeface=Typeface.DEFAULT_BOLD; canvas.drawText(type.take(1).uppercase().ifBlank{"?"},cx,cy+size*.35f,paint)
    }

    private fun knowledgeMark(value: MoveKnowledgeConfidence): String = when (value) {
        MoveKnowledgeConfidence.CONFIRMED -> "✓"
        MoveKnowledgeConfidence.MANUAL -> "M"
        MoveKnowledgeConfidence.RANKED -> "~"
        MoveKnowledgeConfidence.UNKNOWN -> "?"
    }

    private fun currentPlayerTypes(): List<String> = gameRepo.pokemon(state.playerName)?.types.orEmpty()
    private data class ShieldAdvice(val title:String,val detail:String,val color:Int)
    private fun shortMoveName(name:String)=if(name.length<=19) name else name.take(18)+"…"
    private fun hudColor(color:Int,multiplier:Float=1f):Int{val a=(hudAlpha*multiplier*blockAlphaMultiplier).toInt().coerceIn(0,255);return Color.argb(a,Color.red(color),Color.green(color),Color.blue(color))}
    private fun typeColor(type:String):Int=when(type.lowercase()){
        "fairy"->Color.rgb(239,153,230);"psychic"->Color.rgb(250,137,131);"fighting"->Color.rgb(219,66,86);"rock"->Color.rgb(206,193,141);"fire"->Color.rgb(251,165,75);"steel"->Color.rgb(85,151,164);"water"->Color.rgb(91,166,224);"flying"->Color.rgb(155,180,229);"ghost"->Color.rgb(99,111,191);"grass"->Color.rgb(93,190,101);"bug"->Color.rgb(161,194,49);"ground"->Color.rgb(214,133,85);"ice"->Color.rgb(125,212,200);"dark"->Color.rgb(113,121,140);"dragon"->Color.rgb(7,115,200);"normal"->Color.rgb(153,156,161);"electric"->Color.rgb(243,219,85);"poison"->Color.rgb(178,98,204);else->Color.rgb(153,156,161)
    }

    companion object {
        const val BLOCK_PLAYER_MATCHUP = "hud_player_matchup"
        const val BLOCK_STRONG_TYPES = "hud_strong_types"
        const val BLOCK_ENEMY_MOVES = "hud_enemy_moves"
        const val BLOCK_RESERVE_1 = "hud_reserve_1"
        const val BLOCK_RESERVE_2 = "hud_reserve_2"
        const val BLOCK_RESERVES_LEGACY = "hud_reserves"
        const val BLOCK_HUD_TOGGLE = "hud_toggle"
        const val BLOCK_HUD_LOCK = "hud_lock"
        const val BLOCK_BATTLE_ASSIST = "hud_battle_assist"
        const val BLOCK_ENEMY_HISTORY = "hud_enemy_history"

        const val TOP_ICON_Y=66f
        const val PLAYER_MATCHUP_X=136f
        const val STRONG_TYPES_RIGHT_X=688f
        const val HUD_TOGGLE_CENTER_X=432f
        const val HUD_TOGGLE_CENTER_Y=66f
        const val HUD_LOCK_CENTER_X=382f
        const val HUD_LOCK_CENTER_Y=66f
        const val ENEMY_MOVE_CENTER_X=558f
        const val ENEMY_MOVE_FAST_Y=355f
        const val RESERVE_INDICATOR_X=692f
        const val RESERVE_FIRST_Y=918f
        const val RESERVE_SPACING_Y=140f
        const val SWITCH_CHOICE_LEFT_X=282f
        const val SWITCH_CHOICE_RIGHT_X=582f
        /** Center of the Pokémon portrait over each native switch-choice card. */
        const val SWITCH_CHOICE_ICON_Y=1322f
        const val SWITCH_CHOICE_INDICATOR_Y=1444f
        const val BATTLE_ASSIST_X=432f
        const val BATTLE_ASSIST_Y=770f
        const val ENEMY_HISTORY_X=846f
        const val ENEMY_HISTORY_Y=650f
    }
}

