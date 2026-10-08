package com.lucianotoscano.pvppokego.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.lucianotoscano.pvppokego.data.BattleLeagueMode
import com.lucianotoscano.pvppokego.data.SettingsRepository
import kotlin.math.abs
import kotlin.math.min

/** Floating battle menu: icon-only launcher, PGSharp-like icon+text panel, independent league badge. */
class QuickOverlayMenu(
    private val context: Context,
    private val windowManager: WindowManager,
    private val settings: SettingsRepository,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onSettingsChanged(rebuildTouchZones: Boolean = false)
        fun onToggleHud()
        fun isHudCollapsed(): Boolean
        fun isLayoutLocked(): Boolean
        fun onResetHudPositions()
        fun currentBattleModeLabel(): String
        fun isVisionPaused(): Boolean
        fun onToggleVision()
        fun isOverlaySuppressed(): Boolean
        fun onToggleOverlaySuppressed()
        fun isRecording(): Boolean
        fun onToggleRecording()
        fun onStopService()
    }

    private var gearView: View? = null
    private var gearParams: WindowManager.LayoutParams? = null
    private var leagueView: LeagueBadgeView? = null
    private var leagueParams: WindowManager.LayoutParams? = null
    private var panelView: ScrollView? = null
    private var externallyVisible = false
    private var battleActiveForLeague = false
    private var panelScrollY = 0

    private val metrics get() = context.resources.displayMetrics
    private val density get() = metrics.density
    private fun dp(v: Int): Int = (v * density).toInt()

    fun attach() {
        if (gearView != null) return
        settings.ensureQuickMenuBottomRightV2()
        settings.ensureQuickMenuStyleV3()

        val gear = ImageView(context).apply {
            // Use Android's standard settings/preferences symbol so it is immediately
            // recognizable as configuration instead of resembling a sun.
            setImageResource(android.R.drawable.ic_menu_preferences)
            setColorFilter(Color.argb(238, 245, 247, 250))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = "Configurações PvPPokeGo"
            background = null
            visibility = View.GONE
        }
        val gp = gearLayoutParams()
        gearParams = gp
        installGearDragAndTap(gear, gp)
        gearView = gear
        windowManager.addView(gear, gp)

        val league = LeagueBadgeView(context).apply {
            contentDescription = "Liga atual"
            visibility = View.GONE
        }
        val lp = leagueLayoutParams()
        leagueParams = lp
        installLeagueDrag(league, lp)
        leagueView = league
        windowManager.addView(league, lp)
        refreshStatusBadge()
    }

    fun setVisible(visible: Boolean) {
        externallyVisible = visible
        gearView?.visibility = if (visible) View.VISIBLE else View.GONE
        syncLeagueVisibility()
        if (!visible) removePanel(false)
        if (visible) refreshStatusBadge()
    }

    fun setBattleActive(active: Boolean) {
        battleActiveForLeague = active
        syncLeagueVisibility()
    }

    private fun syncLeagueVisibility() {
        leagueView?.visibility = if (
            externallyVisible && (battleActiveForLeague || settings.editMode)
        ) View.VISIBLE else View.GONE
    }

    /** Shows the active league as a standalone icon, not attached to the gear. */
    fun refreshStatusBadge() {
        val label = callbacks.currentBattleModeLabel()
        val kind = when {
            label.contains("ULTRA") || label.contains("AUTO-U") -> LeagueKind.ULTRA
            label.contains("MESTRA") || label.contains("AUTO-M") -> LeagueKind.MASTER
            label.contains("GRANDE") || label.contains("AUTO-G") -> LeagueKind.GREAT
            else -> when (settings.leagueMode) {
                BattleLeagueMode.ULTRA -> LeagueKind.ULTRA
                BattleLeagueMode.MASTER -> LeagueKind.MASTER
                BattleLeagueMode.GREAT -> LeagueKind.GREAT
                BattleLeagueMode.AUTO -> LeagueKind.AUTO
            }
        }
        leagueView?.setKind(kind)
    }

    fun bringToFront() {
        gearView?.let { v -> gearParams?.let { p -> runCatching { windowManager.removeView(v) }; runCatching { windowManager.addView(v, p) } } }
        leagueView?.let { v -> leagueParams?.let { p -> runCatching { windowManager.removeView(v) }; runCatching { windowManager.addView(v, p) } } }
    }

    fun dismissPanel() {
        panelScrollY = 0
        removePanel(false)
    }

    fun destroy() {
        removePanel(false)
        leagueView?.let { runCatching { windowManager.removeView(it) } }
        gearView?.let { runCatching { windowManager.removeView(it) } }
        leagueView = null
        leagueParams = null
        gearView = null
        gearParams = null
    }

    private fun togglePanel() {
        if (!externallyVisible) return
        if (panelView != null) dismissPanel() else showPanel()
    }

    private fun removePanel(preserveScroll: Boolean) {
        val view = panelView ?: return
        if (preserveScroll) panelScrollY = view.scrollY
        runCatching { windowManager.removeView(view) }
        panelView = null
    }

    private fun showPanel() {
        if (!externallyVisible) return
        val restoreY = panelScrollY
        removePanel(true)

        val labels = settings.quickMenuShowLabels
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // PGSharp-style floating menu: no card/panel behind the controls.
            // Only the icon tiles and high-contrast white labels are drawn.
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setBackgroundColor(Color.TRANSPARENT)
        }
        if (labels) buildLabeledMenu(content) else buildIconMenu(content)

        val scroll = ScrollView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isFillViewport = false
            clipToPadding = false
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val gear = gearParams ?: return
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val width = if (labels) min((screenW * .72f).toInt(), dp(282)) else min((screenW * .50f).toInt(), dp(190))
        val desiredHeight = if (labels) dp(540) else dp(285)
        val height = min((screenH * if (labels) .70f else .40f).toInt(), desiredHeight)
        val margin = dp(5)
        val rightX = gear.x + gear.width + margin
        val x = if (rightX + width <= screenW) rightX else (gear.x - width - margin).coerceAtLeast(0)
        val y = (gear.y - height + gear.height).coerceIn(0, (screenH - height).coerceAtLeast(0))

        val params = WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        panelView = scroll
        windowManager.addView(scroll, params)
        scroll.post { scroll.scrollTo(0, restoreY.coerceAtLeast(0)) }
    }

    private fun buildIconMenu(parent: LinearLayout) {
        fun row(vararg items: IconItem) {
            val line = LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.START }
            items.forEach { addIconCell(line,it) }
            while(line.childCount<4) line.addView(View(context),LinearLayout.LayoutParams(0,dp(43),1f))
            parent.addView(line,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(47)))
        }
        if (!settings.quickMenuAdvanced) {
            row(
                IconItem("◉","HUD",!callbacks.isHudCollapsed()){callbacks.onToggleHud();refreshPanel()},
                IconItem("★","Assistido",settings.battleAssistEnabled){settings.battleAssistEnabled=!settings.battleAssistEnabled;changed()},
                IconItem("△","Liga",null){cycleLeague()},
                IconItem("↔","Editar HUD",settings.editMode){settings.editMode=!settings.editMode;callbacks.onSettingsChanged(true);if(settings.editMode)dismissPanel()else refreshPanel()}
            )
            row(
                IconItem(if(callbacks.isVisionPaused())"▶" else "Ⅱ","Visão",!callbacks.isVisionPaused()){callbacks.onToggleVision();refreshPanel()},
                IconItem("…","Avançado",null){settings.quickMenuAdvanced=true;refreshPanel()},
                IconItem("≡","Mostrar textos",null){settings.quickMenuShowLabels=true;refreshPanel()},
                IconItem("×","Fechar",null){dismissPanel()}
            )
            row(
                IconItem(if(callbacks.isRecording())"■" else "●","Gravar",callbacks.isRecording()){callbacks.onToggleRecording();refreshPanel()},
                IconItem("□","Overlay",!callbacks.isOverlaySuppressed()){callbacks.onToggleOverlaySuppressed();dismissPanel()},
                IconItem("■","Parar",null){callbacks.onStopService()}
            )
        } else {
            row(
                IconItem("A","Reconhecimento",settings.autoRecognition){settings.autoRecognition=!settings.autoRecognition;changed()},
                IconItem("◇","Fraquezas",settings.showStrongTypes){settings.showStrongTypes=!settings.showStrongTypes;changed()},
                IconItem("✓","Matchup",settings.showCurrentIndicator){settings.showCurrentIndicator=!settings.showCurrentIndicator;changed()},
                IconItem("2","Reservas",settings.analyzeReserves){settings.analyzeReserves=!settings.analyzeReserves;changed()}
            )
            row(
                IconItem("T","Tipos reservas",settings.showReserveTypes){settings.showReserveTypes=!settings.showReserveTypes;changed()},
                IconItem("⚔","Ataques",settings.showEnemyMoves){settings.showEnemyMoves=!settings.showEnemyMoves;changed()},
                IconItem("••","Contagem",settings.showChargedCounter){settings.showChargedCounter=!settings.showChargedCounter;changed()},
                IconItem("⚡","Energia",settings.showEnergyProgress){settings.showEnergyProgress=!settings.showEnergyProgress;changed()}
            )
            row(
                IconItem("HP","HP / dano",settings.showHpAssist){settings.showHpAssist=!settings.showHpAssist;changed()},
                IconItem("↻","Timer",settings.showSwitchTimer){settings.showSwitchTimer=!settings.showSwitchTimer;changed()},
                IconItem("H","Histórico",settings.showEnemyHistory){settings.showEnemyHistory=!settings.showEnemyHistory;changed()},
                IconItem("D","Debug",settings.debugMode){settings.debugMode=!settings.debugMode;changed()}
            )
            row(
                IconItem("‹","Básico",null){settings.quickMenuAdvanced=false;refreshPanel()},
                IconItem("↺","Restaurar",null){resetPositions()},
                IconItem("▦","Textos",null){settings.quickMenuShowLabels=true;refreshPanel()},
                IconItem("×","Fechar",null){dismissPanel()}
            )
            row(
                IconItem(if(callbacks.isRecording())"■" else "●","Gravar",callbacks.isRecording()){callbacks.onToggleRecording();refreshPanel()},
                IconItem(if(callbacks.isVisionPaused())"▶" else "Ⅱ","Visão",!callbacks.isVisionPaused()){callbacks.onToggleVision();refreshPanel()},
                IconItem("□","Overlay",!callbacks.isOverlaySuppressed()){callbacks.onToggleOverlaySuppressed();dismissPanel()},
                IconItem("■","Parar",null){callbacks.onStopService()}
            )
        }
    }

    /** Organized quick menu with clear sections and destructive actions separated. */
    private fun buildLabeledMenu(parent: LinearLayout) {
        addSectionHeader(parent, "BATALHA")
        addRow(parent, "◉", if (callbacks.isHudCollapsed()) "Mostrar HUD" else "Ocultar HUD", !callbacks.isHudCollapsed()) {
            callbacks.onToggleHud()
            refreshPanel()
        }
        addRow(parent, "★", "Modo batalha assistido", settings.battleAssistEnabled) {
            settings.battleAssistEnabled = !settings.battleAssistEnabled
            changed()
        }
        addRow(parent, "△", "Liga: ${leagueShort(settings.leagueMode)}", null) { cycleLeague() }
        addRow(parent, "↔", "Editar posições do HUD", settings.editMode) {
            settings.editMode = !settings.editMode
            callbacks.onSettingsChanged(true)
            if (settings.editMode) dismissPanel() else refreshPanel()
        }

        addSectionHeader(parent, "HUD / VISUAL")
        addRow(parent, "T", "Textos do HUD", settings.showHudText) {
            settings.showHudText = !settings.showHudText
            changed()
        }
        addRow(parent, "S", "Escala ${(settings.scale * 100).toInt()}%", null) {
            settings.scale = if (settings.scale >= 1.19f) .5f else settings.scale + .1f
            callbacks.onSettingsChanged(true)
            refreshPanel()
        }
        addRow(parent, "◐", "Opacidade ${(settings.opacity * 100).toInt()}%", null) {
            settings.opacity = if (settings.opacity >= .99f) .3f else settings.opacity + .1f
            callbacks.onSettingsChanged(false)
            refreshPanel()
        }
        addRow(parent, "↺", "Restaurar posições/layout", null) {
            settings.resetHudCustomization()
            resetPositions()
        }

        if (settings.quickMenuAdvanced) {
            addSectionHeader(parent, "ANÁLISE")
            addRow(parent, "A", "Reconhecimento automático", settings.autoRecognition) {
                settings.autoRecognition = !settings.autoRecognition
                changed()
            }
            addRow(parent, "◇", "Fraquezas do inimigo", settings.showStrongTypes) {
                settings.showStrongTypes = !settings.showStrongTypes
                changed()
            }
            addRow(parent, "✓", "Indicador Pokémon atual", settings.showCurrentIndicator) {
                settings.showCurrentIndicator = !settings.showCurrentIndicator
                changed()
            }
            addRow(parent, "2", "Analisar reservas", settings.analyzeReserves) {
                settings.analyzeReserves = !settings.analyzeReserves
                changed()
            }
            addRow(parent, "T", "Tipos das reservas", settings.showReserveTypes) {
                settings.showReserveTypes = !settings.showReserveTypes
                changed()
            }
            addRow(parent, "⚔", "Ataques do inimigo", settings.showEnemyMoves) {
                settings.showEnemyMoves = !settings.showEnemyMoves
                changed()
            }
            addRow(parent, "••", "Contagem de carregados", settings.showChargedCounter) {
                settings.showChargedCounter = !settings.showChargedCounter
                changed()
            }
            addRow(parent, "⚡", "Progresso de energia", settings.showEnergyProgress) {
                settings.showEnergyProgress = !settings.showEnergyProgress
                changed()
            }
            addRow(parent, "HP", "HP e previsão de dano", settings.showHpAssist) {
                settings.showHpAssist = !settings.showHpAssist
                changed()
            }
            addRow(parent, "↻", "Timer de troca", settings.showSwitchTimer) {
                settings.showSwitchTimer = !settings.showSwitchTimer
                changed()
            }
            addRow(parent, "H", "Histórico do inimigo", settings.showEnemyHistory) {
                settings.showEnemyHistory = !settings.showEnemyHistory
                changed()
            }
            addRow(parent, "D", "Diagnóstico / DEBUG", settings.debugMode) {
                settings.debugMode = !settings.debugMode
                changed()
            }
            addRow(parent, "‹", "Ocultar opções avançadas", null) {
                settings.quickMenuAdvanced = false
                refreshPanel()
            }
        } else {
            addRow(parent, "…", "Mostrar opções avançadas", null) {
                settings.quickMenuAdvanced = true
                refreshPanel()
            }
        }

        addSectionHeader(parent, "SISTEMA")
        addRow(
            parent,
            if (callbacks.isRecording()) "■" else "●",
            if (callbacks.isRecording()) "Parar e salvar gravação" else "Gravar tela com PvPPokeGo",
            callbacks.isRecording()
        ) {
            callbacks.onToggleRecording()
            refreshPanel()
        }
        addRow(
            parent,
            if (callbacks.isVisionPaused()) "▶" else "Ⅱ",
            if (callbacks.isVisionPaused()) "Retomar visão/análise" else "Pausar visão/análise",
            !callbacks.isVisionPaused()
        ) {
            callbacks.onToggleVision()
            refreshPanel()
        }
        addRow(
            parent,
            if (callbacks.isOverlaySuppressed()) "□" else "×",
            if (callbacks.isOverlaySuppressed()) "Mostrar sobreposição" else "Remover sobreposição",
            null,
            danger = !callbacks.isOverlaySuppressed()
        ) {
            callbacks.onToggleOverlaySuppressed()
            dismissPanel()
        }
        addRow(parent, "■", "Parar serviço PvPPokeGo", null, danger = true) {
            callbacks.onStopService()
        }
        addRow(parent, "▦", "Modo somente ícones", null) {
            settings.quickMenuShowLabels = false
            refreshPanel()
        }
        addRow(parent, "×", "Fechar menu", null) { dismissPanel() }
    }

    private fun addIconCell(parent: LinearLayout, item: IconItem) {
        val button = TextView(context).apply {
            text = item.icon
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = if (item.icon.length > 1) 12f else 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setShadowLayer(1.6f, 0f, 1f, Color.BLACK)
            background = squareIconBackground(item.enabled)
            contentDescription = item.label
            isClickable = true
            setOnClickListener { item.onTap() }
        }
        parent.addView(button, LinearLayout.LayoutParams(0, dp(39), 1f).apply {
            marginStart = dp(3); marginEnd = dp(3); topMargin = dp(2); bottomMargin = dp(2)
        })
    }

    private fun addSectionHeader(parent: LinearLayout, title: String) {
        parent.addView(TextView(context).apply {
            text = title
            setTextColor(Color.argb(225, 255, 255, 255))
            textSize = 10f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setShadowLayer(3.2f, 0f, 1.2f, Color.BLACK)
            setPadding(dp(4), dp(8), dp(4), dp(4))
            letterSpacing = .08f
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun addRow(
        parent: LinearLayout,
        icon: String,
        label: String,
        enabled: Boolean?,
        danger: Boolean = false,
        onTap: () -> Unit
    ) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(6), dp(2))
            isClickable = true
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { onTap() }
        }
        row.addView(TextView(context).apply {
            text = icon
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = if (icon.length > 1) 12f else 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = squareIconBackground(enabled, danger)
        }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(9) })
        row.addView(TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 13.5f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setShadowLayer(4f, 0f, 1.5f, Color.BLACK)
            maxLines = 1
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        parent.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(41)).apply {
            bottomMargin = dp(1)
        })
    }

    private fun changed(rebuildTouches: Boolean = false) {
        callbacks.onSettingsChanged(rebuildTouches)
        refreshStatusBadge()
        refreshPanel()
    }

    /** Refreshes an already-open panel after a service action such as recording. */
    fun refreshPanelFromService() {
        if (panelView != null) refreshPanel()
    }

    /** Rebuild without jumping to the top after every selection. */
    private fun refreshPanel() {
        val current = panelView ?: return
        panelScrollY = current.scrollY
        showPanel()
    }

    private fun cycleLeague() {
        settings.leagueMode = when (settings.leagueMode) {
            BattleLeagueMode.AUTO -> BattleLeagueMode.GREAT
            BattleLeagueMode.GREAT -> BattleLeagueMode.ULTRA
            BattleLeagueMode.ULTRA -> BattleLeagueMode.MASTER
            BattleLeagueMode.MASTER -> BattleLeagueMode.AUTO
        }
        callbacks.onSettingsChanged(false)
        refreshStatusBadge()
        refreshPanel()
    }

    private fun leagueShort(mode: BattleLeagueMode): String = when (mode) {
        BattleLeagueMode.AUTO -> "Automático"
        BattleLeagueMode.GREAT -> "Grande"
        BattleLeagueMode.ULTRA -> "Ultra"
        BattleLeagueMode.MASTER -> "Mestra"
    }

    private fun resetPositions() {
        settings.resetQuickMenuPosition()
        callbacks.onResetHudPositions()
        moveGearToStoredPosition()
        moveLeagueToStoredPosition()
        callbacks.onSettingsChanged(true)
        refreshPanel()
    }

    private fun gearLayoutParams(): WindowManager.LayoutParams {
        // Touch target remains comfortable while the visible vector stays as small as eye/lock.
        val size = min(dp(32), (metrics.widthPixels * .074f).toInt()).coerceAtLeast(dp(30))
        return floatingParams(size, size).apply {
            val maxX = (metrics.widthPixels - size).coerceAtLeast(0)
            val maxY = (metrics.heightPixels - size).coerceAtLeast(0)
            val margin = dp(6).coerceAtMost(maxX / 2).coerceAtLeast(0)
            val bottomMargin = dp(8).coerceAtMost(maxY / 2).coerceAtLeast(0)
            x = (settings.quickMenuXFraction * maxX).toInt().coerceIn(margin, (maxX - margin).coerceAtLeast(margin))
            y = (settings.quickMenuYFraction * maxY).toInt().coerceIn(bottomMargin, (maxY - bottomMargin).coerceAtLeast(bottomMargin))
        }
    }

    private fun leagueLayoutParams(): WindowManager.LayoutParams {
        val size = dp(42).coerceAtLeast(44)
        return floatingParams(size, size).apply {
            val maxX = (metrics.widthPixels - size).coerceAtLeast(0)
            val maxY = (metrics.heightPixels - size).coerceAtLeast(0)
            x = (settings.leagueBadgeXFraction * maxX).toInt().coerceIn(0, maxX)
            y = (settings.leagueBadgeYFraction * maxY).toInt().coerceIn(0, maxY)
        }
    }

    private fun floatingParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun moveGearToStoredPosition() {
        val view = gearView ?: return
        val p = gearParams ?: return
        val maxX = (metrics.widthPixels - p.width).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - p.height).coerceAtLeast(0)
        val margin = dp(6).coerceAtMost(maxX / 2).coerceAtLeast(0)
        val bottomMargin = dp(8).coerceAtMost(maxY / 2).coerceAtLeast(0)
        p.x = (settings.quickMenuXFraction * maxX).toInt().coerceIn(margin, (maxX - margin).coerceAtLeast(margin))
        p.y = (settings.quickMenuYFraction * maxY).toInt().coerceIn(bottomMargin, (maxY - bottomMargin).coerceAtLeast(bottomMargin))
        runCatching { windowManager.updateViewLayout(view, p) }
    }

    private fun moveLeagueToStoredPosition() {
        val view = leagueView ?: return
        val p = leagueParams ?: return
        val maxX = (metrics.widthPixels - p.width).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - p.height).coerceAtLeast(0)
        p.x = (settings.leagueBadgeXFraction * maxX).toInt().coerceIn(0, maxX)
        p.y = (settings.leagueBadgeYFraction * maxY).toInt().coerceIn(0, maxY)
        runCatching { windowManager.updateViewLayout(view, p) }
    }

    private fun installGearDragAndTap(view: View, params: WindowManager.LayoutParams) {
        installDrag(view, params, canDrag = { !callbacks.isLayoutLocked() }, onMoved = { xFrac, yFrac ->
            settings.quickMenuXFraction = xFrac
            settings.quickMenuYFraction = yFrac
        }, onTap = { togglePanel() })
    }

    private fun installLeagueDrag(view: View, params: WindowManager.LayoutParams) {
        installDrag(view, params, canDrag = { !callbacks.isLayoutLocked() }, onMoved = { xFrac, yFrac ->
            settings.leagueBadgeXFraction = xFrac
            settings.leagueBadgeYFraction = yFrac
        }, onTap = { /* display only; league changes from the menu */ })
    }

    private fun installDrag(
        view: View,
        params: WindowManager.LayoutParams,
        canDrag: () -> Boolean,
        onMoved: (Float, Float) -> Unit,
        onTap: () -> Unit
    ) {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat().coerceAtLeast(16f)
        var downRawX = 0f; var downRawY = 0f; var startX = 0; var startY = 0; var dragging = false
        view.isClickable = true
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX; downRawY = event.rawY; startX = params.x; startY = params.y; dragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX; val dy = event.rawY - downRawY
                    // Require movement past system touch-slop so taps still open the menu.
                    if (canDrag() && (abs(dx) >= touchSlop || abs(dy) >= touchSlop)) dragging = true
                    if (dragging) {
                        removePanel(false)
                        val maxX = (metrics.widthPixels - params.width).coerceAtLeast(0)
                        val maxY = (metrics.heightPixels - params.height).coerceAtLeast(0)
                        params.x = (startX + dx).toInt().coerceIn(0, maxX)
                        params.y = (startY + dy).toInt().coerceIn(0, maxY)
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        val maxX = (metrics.widthPixels - params.width).coerceAtLeast(1)
                        val maxY = (metrics.heightPixels - params.height).coerceAtLeast(1)
                        onMoved(params.x.toFloat() / maxX, params.y.toFloat() / maxY)
                        callbacks.onSettingsChanged(false)
                    } else onTap()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    true
                }
                else -> true
            }
        }
    }

    private fun roundLauncherBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.argb(150, 30, 35, 44))
        setStroke(dp(1), Color.argb(225, 238, 241, 246))
    }

    private fun squareIconBackground(enabled: Boolean?, danger: Boolean = false): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(1).toFloat()
        setColor(
            when {
                danger -> Color.argb(235, 118, 92, 92)
                enabled == true -> Color.argb(238, 112, 118, 113)
                enabled == false -> Color.argb(218, 132, 136, 133)
                else -> Color.argb(230, 122, 126, 123)
            }
        )
        setStroke(dp(1), Color.argb(125, 245, 245, 245))
    }

    private fun leagueBackground(kind: LeagueKind): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(when (kind) {
            LeagueKind.GREAT -> Color.argb(215, 52, 111, 190)
            LeagueKind.ULTRA -> Color.argb(215, 194, 145, 43)
            LeagueKind.MASTER -> Color.argb(215, 111, 76, 169)
            LeagueKind.AUTO -> Color.argb(195, 73, 80, 91)
        })
        setStroke(dp(1), Color.argb(235, 245, 247, 250))
    }


    /** Vector recreation of the league emblems supplied as the visual reference. */
    private inner class LeagueBadgeView(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var kind: LeagueKind = LeagueKind.AUTO
        fun setKind(value: LeagueKind) { kind=value; invalidate() }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w=width.toFloat(); val h=height.toFloat(); val pad=2.5f*density
            val tri=Path().apply { moveTo(pad,pad); lineTo(w-pad,pad); lineTo(w/2f,h-pad); close() }
            canvas.save(); canvas.clipPath(tri)
            p.style=Paint.Style.FILL
            p.color=when(kind){
                LeagueKind.GREAT->Color.rgb(18,55,142)
                LeagueKind.ULTRA->Color.rgb(18,23,35)
                LeagueKind.MASTER->Color.rgb(105,55,151)
                LeagueKind.AUTO->Color.rgb(235,45,49)
            }
            canvas.drawRect(0f,0f,w,h,p)
            p.style=Paint.Style.STROKE; p.strokeCap=Paint.Cap.SQUARE
            when(kind){
                LeagueKind.GREAT->{
                    p.color=Color.rgb(235,53,58); p.strokeWidth=5.2f*density
                    canvas.drawLine(w*.30f,h*.50f,w*.90f,h*.18f,p)
                }
                LeagueKind.ULTRA->{
                    p.color=Color.rgb(250,207,26); p.strokeWidth=4.6f*density
                    canvas.drawLine(w*.22f,h*.48f,w*.92f,h*.12f,p)
                    canvas.drawLine(w*.32f,h*.66f,w*.92f,h*.34f,p)
                }
                LeagueKind.MASTER->{
                    p.color=Color.rgb(232,64,178); p.strokeWidth=4.1f*density
                    canvas.drawLine(w*.20f,h*.44f,w*.90f,h*.10f,p)
                    canvas.drawLine(w*.27f,h*.59f,w*.91f,h*.27f,p)
                    canvas.drawLine(w*.35f,h*.73f,w*.82f,h*.49f,p)
                }
                LeagueKind.AUTO->{
                    // Fourth supplied emblem: red field with pale sweeping lower section.
                    p.style=Paint.Style.FILL; p.color=Color.rgb(224,239,249)
                    val sweep=Path().apply { moveTo(w*.40f,h*.25f); cubicTo(w*.58f,h*.18f,w*.78f,h*.17f,w,h*.18f); lineTo(w,h); lineTo(w*.5f,h); close() }
                    canvas.drawPath(sweep,p)
                }
            }
            canvas.restore()
            // Gray outer and white inner triangle borders.
            p.style=Paint.Style.STROKE; p.strokeJoin=Paint.Join.ROUND
            p.strokeWidth=4.2f*density; p.color=Color.rgb(96,96,96); canvas.drawPath(tri,p)
            val innerPad=5.2f*density
            val inner=Path().apply { moveTo(innerPad,innerPad); lineTo(w-innerPad,innerPad); lineTo(w/2f,h-innerPad); close() }
            p.strokeWidth=1.8f*density; p.color=Color.WHITE; canvas.drawPath(inner,p)
            // White Pokeball mark in the upper-left, matching the supplied emblems.
            val cx=w*.31f; val cy=h*.25f; val r=w*.105f
            p.style=Paint.Style.FILL; p.color=Color.WHITE; canvas.drawCircle(cx,cy,r,p)
            p.style=Paint.Style.STROKE; p.strokeWidth=1.6f*density; p.color=when(kind){LeagueKind.GREAT->Color.rgb(18,55,142);LeagueKind.ULTRA->Color.rgb(18,23,35);LeagueKind.MASTER->Color.rgb(105,55,151);LeagueKind.AUTO->Color.rgb(210,45,49)}
            canvas.drawLine(cx-r,cy,cx+r,cy,p); canvas.drawCircle(cx,cy,r*.30f,p)
        }
    }

    private enum class LeagueKind { AUTO, GREAT, ULTRA, MASTER }
    private data class IconItem(val icon: String, val label: String, val enabled: Boolean?, val onTap: () -> Unit)
}
