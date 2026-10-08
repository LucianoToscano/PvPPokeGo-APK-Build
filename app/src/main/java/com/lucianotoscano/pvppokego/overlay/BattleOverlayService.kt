package com.lucianotoscano.pvppokego.overlay

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.lucianotoscano.pvppokego.BuildConfig
import com.lucianotoscano.pvppokego.MainActivity
import com.lucianotoscano.pvppokego.PvPPokeGoApp
import com.lucianotoscano.pvppokego.R
import com.lucianotoscano.pvppokego.capture.AccessibilityScreenCapture
import com.lucianotoscano.pvppokego.capture.ScreenCapture
import com.lucianotoscano.pvppokego.capture.ScreenFrameRecorder
import com.lucianotoscano.pvppokego.data.BattleLeagueMode
import com.lucianotoscano.pvppokego.data.BattleHistoryRecorder
import com.lucianotoscano.pvppokego.data.BattleUiState
import com.lucianotoscano.pvppokego.data.CaptureHealth
import com.lucianotoscano.pvppokego.data.MoveKnowledgeConfidence
import com.lucianotoscano.pvppokego.data.BattleHistoryRepository
import com.lucianotoscano.pvppokego.data.BattleDetection
import com.lucianotoscano.pvppokego.data.GameDataRepository
import com.lucianotoscano.pvppokego.data.EnergyConfidence
import com.lucianotoscano.pvppokego.data.MoveDef
import com.lucianotoscano.pvppokego.data.SettingsRepository
import com.lucianotoscano.pvppokego.detect.BattleFrameEventDetector
import com.lucianotoscano.pvppokego.detect.BattleOcrDetector
import com.lucianotoscano.pvppokego.detect.BattlePresenceTracker
import com.lucianotoscano.pvppokego.engine.AdviceStabilityGate
import com.lucianotoscano.pvppokego.engine.BattleAssistAdvisor
import com.lucianotoscano.pvppokego.engine.BattleContinuityPolicy
import com.lucianotoscano.pvppokego.engine.BattleCorrespondenceTracker
import com.lucianotoscano.pvppokego.engine.BattleEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.min

class BattleOverlayService : Service(), BattleOverlayView.Callbacks {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var gameRepo: GameDataRepository
    private lateinit var historyRepository: BattleHistoryRepository
    private lateinit var historyRecorder: BattleHistoryRecorder
    private var lastStrategyOpponentKey: String? = null
    private var engine: BattleEngine? = null
    private var detector: BattleOcrDetector? = null
    private var frameDetector: BattleFrameEventDetector? = null
    private val presenceTracker = BattlePresenceTracker()
    private val battleCorrespondence = BattleCorrespondenceTracker()
    private var lastNativeTopCardY: Int? = null
    private var lastEditSnapshot: Triple<String, Float, Float>? = null
    private var projection: MediaProjection? = null
    private var capture: ScreenCapture? = null
    @Volatile private var projectionRevokedBySystem = false
    @Volatile private var usingAccessibilityFallback = false
    @Volatile private var destroyingService = false
    private var lastAccessibilityFrameAtMs = 0L
    private var lastMediaProjectionFrameAtMs = 0L
    private var lastAnalysisFrameSourceTimestampMs = 0L
    private var lastFreshAnalysisFrameAtMs = 0L
    private var captureStaleNotified = false
    private var screenRecorder: ScreenFrameRecorder? = null
    private var recordingAwaitingEncoder = false
    private var recordingConfirmedNotified = false
    private var windowManager: WindowManager? = null
    private var overlayView: BattleOverlayView? = null
    private var scanJob: Job? = null
    private var currentLeagueCp: Int = 1500
    private var lastRecordedAdviceKey: String? = null
    private val historyAdviceGate = AdviceStabilityGate()
    private var stableHistoryAdvice: BattleAssistAdvisor.Advice? = null
    private var lastReserveDiagnosticKey: String? = null

    private var fastTouch: View? = null
    private var charged1Touch: View? = null
    private var charged2Touch: View? = null
    private var hudToggleTouch: View? = null
    private var hudLockTouch: View? = null
    private var editTouchLayer: View? = null
    private var editToolbarView: View? = null
    /** Small per-block touch windows used when the padlock is open (does not cover the whole screen). */
    private val freeDragZones = mutableListOf<View>()
    private var moveMenuView: View? = null
    private var quickMenu: QuickOverlayMenu? = null
    private var hudCollapsed = false
    private var appUiVisible = false
    private var visionPaused = false
    private var overlaySuppressed = false

    private var battleActive = false
    private var lastBattleEvidenceAtMs = 0L
    /** Short visual lifetime for the league badge; independent from battle continuity. */
    private var lastBattleUiVisibleAtMs = 0L
    private var switchChoicePromptUntilMs = 0L
    private var lastHistoryStateAtMs = 0L

    override fun onCreate() {
        super.onCreate()
        settingsRepo = SettingsRepository(this)
        settingsRepo.ensureTopHudSafeV4()
        settingsRepo.ensureApprovedLayoutV13()
        settingsRepo.ensureApprovedLayoutV15()
        // A previously-running flag at process/service creation means the prior session
        // did not shut down cleanly. Only in that case clear legacy edit mode so an
        // invisible fullscreen drag layer cannot revive unexpectedly. On a clean start,
        // preserve an edit-mode choice the user explicitly made in the settings screen.
        val recoveringStaleSession = settingsRepo.overlayServiceRunning
        if (recoveringStaleSession) settingsRepo.editMode = false
        // Free-drag via the global padlock is always re-armed safely on service creation.
        settingsRepo.layoutLocked = true
        settingsRepo.overlayServiceRunning = true
        gameRepo = GameDataRepository(this)
        historyRepository = BattleHistoryRepository(this)
        historyRecorder = BattleHistoryRecorder(historyRepository)
        screenRecorder = ScreenFrameRecorder(this)
        setForegroundNotification(notification("Aguardando captura de tela"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { historyRecorder.finish(); stopSelf(); return START_NOT_STICKY }
            ACTION_START_ACCESSIBILITY_CAPTURE -> {
                if (BuildConfig.RECORDER_COMPAT) {
                    startAccessibilityCaptureMode()
                } else {
                    setForegroundNotification(
                        notification("APK padrão: modo gravador externo por Acessibilidade não incluído")
                    )
                }
                return START_STICKY
            }
            ACTION_TOGGLE_VISION -> {
                visionPaused = !visionPaused
                refreshForegroundNotification()
                redraw()
                return START_STICKY
            }
            ACTION_TOGGLE_OVERLAY -> {
                overlaySuppressed = !overlaySuppressed
                dismissMoveMenu()
                refreshForegroundNotification()
                redraw()
                return START_STICKY
            }
            ACTION_TOGGLE_RECORDING -> {
                toggleRecording()
                return START_STICKY
            }
            ACTION_RESET -> {
                historyRecorder.finish()
                lastRecordedAdviceKey = null
                lastReserveDiagnosticKey = null
                engine?.resetBattle()
                frameDetector?.reset()
                battleActive = false
                presenceTracker.reset()
                lastNativeTopCardY = null
                lastBattleEvidenceAtMs = 0L
                switchChoicePromptUntilMs = 0L
                detector?.clearCachedPortraits()
                overlayView?.clearPokemonPortraits()
                dismissMoveMenu()
                redraw()
                return START_STICKY
            }
            ACTION_APP_FOREGROUND -> {
                appUiVisible = true
                dismissMoveMenu()
                redraw()
                return START_STICKY
            }
            ACTION_APP_BACKGROUND -> {
                appUiVisible = false
                redraw()
                return START_STICKY
            }
        }

        if (intent?.hasExtra(EXTRA_APP_VISIBLE) == true) {
            appUiVisible = intent.getBooleanExtra(EXTRA_APP_VISIBLE, false)
        }

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (code != Int.MIN_VALUE && data != null && projection == null) {
            startProjection(code, data)
        } else if (projection == null && overlayView == null && intent?.action == null) {
            setForegroundNotification(notification("Toque em Iniciar overlay para reativar a captura"))
        }
        return START_STICKY
    }

    private fun startAccessibilityCaptureMode() {
        if (!AccessibilityScreenCapture.isConnected) {
            setForegroundNotification(
                notification("Ative PvPPokeGo em Acessibilidade para usar com gravador externo")
            )
            return
        }

        scope.launch {
            try {
                currentLeagueCp = settingsRepo.configuredLeagueCp()
                    ?: settingsRepo.lastDetectedLeagueCp.takeIf { it in SUPPORTED_LEAGUE_CPS }
                    ?: 1500

                withContext(Dispatchers.IO) { gameRepo.load(currentLeagueCp) }
                historyRecorder.setMetadata(BuildConfig.VERSION_NAME, gameRepo.offlineSnapshotVersion())
                if (engine == null) engine = BattleEngine(gameRepo)
                detector?.close()
                detector = BattleOcrDetector(this@BattleOverlayService, gameRepo)
                if (frameDetector == null) frameDetector = BattleFrameEventDetector()
                if (!battleActive && !historyRecorder.hasActiveSession()) {
                    presenceTracker.reset()
                    battleCorrespondence.reset()
                }

                projectionRevokedBySystem = true
                usingAccessibilityFallback = true
                lastAccessibilityFrameAtMs = 0L
                AccessibilityScreenCapture.clearLatest()

                withContext(Dispatchers.Main) {
                    attachOverlay()
                    setForegroundNotification(
                        notification("${BuildConfig.VERSION_NAME} • ${leagueLabel(currentLeagueCp)} • modo gravador externo")
                    )
                    redraw()
                }
                startScanLoop()
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    setForegroundNotification(
                        notification("Erro no modo gravador externo: ${t.javaClass.simpleName}")
                    )
                }
            }
        }
    }

    private fun startProjection(resultCode: Int, resultData: Intent) {
        scope.launch {
            try {
                currentLeagueCp = settingsRepo.configuredLeagueCp()
                    ?: settingsRepo.lastDetectedLeagueCp.takeIf { it in SUPPORTED_LEAGUE_CPS }
                    ?: 1500

                withContext(Dispatchers.IO) { gameRepo.load(currentLeagueCp) }
                historyRecorder.setMetadata(BuildConfig.VERSION_NAME, gameRepo.offlineSnapshotVersion())
                engine = BattleEngine(gameRepo)
                detector = BattleOcrDetector(this@BattleOverlayService, gameRepo)
                frameDetector = BattleFrameEventDetector()
                presenceTracker.reset()
                battleCorrespondence.reset()

                val pm = getSystemService(MediaProjectionManager::class.java)
                val mediaProjection = pm.getMediaProjection(resultCode, resultData)
                    ?: error("MediaProjection indisponível")
                mediaProjection.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        if (!destroyingService) {
                            scope.launch { handleProjectionRevoked() }
                        }
                    }
                }, null)
                projection = mediaProjection
                projectionRevokedBySystem = false
                usingAccessibilityFallback = false
                lastAccessibilityFrameAtMs = 0L
                AccessibilityScreenCapture.clearLatest()

                capture = ScreenCapture(this@BattleOverlayService, mediaProjection).also { it.start() }
                withContext(Dispatchers.Main) { attachOverlay() }
                startScanLoop()
                setForegroundNotification(notification("${BuildConfig.VERSION_NAME} • ${leagueLabel(currentLeagueCp)} • HUD PvP ativo"))
            } catch (t: Throwable) {
                setForegroundNotification(notification("Erro: ${t.javaClass.simpleName}"))
                stopSelf()
            }
        }
    }

    private fun attachOverlay() {
        if (overlayView != null) return
        windowManager = getSystemService(WindowManager::class.java)

        overlayView = BattleOverlayView(this, settingsRepo, gameRepo, this).also { view ->
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            windowManager?.addView(view, params)
        }

        attachMoveTouchZones()
        attachHudToggleTouch()
        attachHudLockTouch()
        val wm = windowManager ?: return
        quickMenu = QuickOverlayMenu(
            this,
            wm,
            settingsRepo,
            object : QuickOverlayMenu.Callbacks {
                override fun onSettingsChanged(rebuildTouchZones: Boolean) {
                    if (settingsRepo.editMode) removeEditToolbar()
                    if (rebuildTouchZones) rebuildTouchableZones()
                    redraw()
                }

                override fun onToggleHud() {
                    toggleHudCollapsed()
                }

                override fun isHudCollapsed(): Boolean = hudCollapsed
                override fun isLayoutLocked(): Boolean = settingsRepo.layoutLocked

                override fun onResetHudPositions() {
                    settingsRepo.clearHudPositions()
                    rebuildTouchableZones()
                    redraw()
                }

                override fun currentBattleModeLabel(): String = currentBattleModeLabelText()
                override fun isVisionPaused(): Boolean = visionPaused
                override fun onToggleVision() {
                    visionPaused = !visionPaused
                    refreshForegroundNotification()
                    redraw()
                }
                override fun isOverlaySuppressed(): Boolean = overlaySuppressed
                override fun onToggleOverlaySuppressed() {
                    overlaySuppressed = !overlaySuppressed
                    dismissMoveMenu()
                    refreshForegroundNotification()
                    redraw()
                }
                override fun isRecording(): Boolean = screenRecorder?.isRecording == true
                override fun onToggleRecording() {
                    toggleRecording()
                }
                override fun onStopService() {
                    historyRecorder.finish()
                    stopSelf()
                }
            }
        ).also { it.attach() }
        redraw()
    }

    private fun enemyMoveAnchor(): Pair<Float, Float> = settingsRepo.getPosition(
        BattleOverlayView.BLOCK_ENEMY_MOVES,
        BattleOverlayView.ENEMY_MOVE_CENTER_X,
        BattleOverlayView.ENEMY_MOVE_FAST_Y
    )

    private fun hudToggleAnchor(): Pair<Float, Float> = settingsRepo.getPosition(
        BattleOverlayView.BLOCK_HUD_TOGGLE,
        BattleOverlayView.HUD_TOGGLE_CENTER_X,
        BattleOverlayView.HUD_TOGGLE_CENTER_Y
    )

    private fun hudLockAnchor(): Pair<Float, Float> = settingsRepo.getPosition(
        BattleOverlayView.BLOCK_HUD_LOCK,
        BattleOverlayView.HUD_LOCK_CENTER_X,
        BattleOverlayView.HUD_LOCK_CENTER_Y
    )

    private fun attachMoveTouchZones() {
        val wm = windowManager ?: return
        if (fastTouch != null) return

        val metrics = resources.displayMetrics
        val sx = metrics.widthPixels / 864f
        val sy = metrics.heightPixels / 1536f
        val unit = min(sx, sy) * (settingsRepo.scale / .8f).coerceIn(.62f, 1.5f)

        fun zone(centerRefX: Float, centerRefY: Float, widthRef: Float, heightRef: Float, onTap: (View) -> Unit): View {
            val view = View(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                isClickable = true
                setOnClickListener { onTap(this) }
            }
            val w = (widthRef * unit).toInt().coerceAtLeast(44)
            val h = (heightRef * unit).toInt().coerceAtLeast(44)
            val cx = centerRefX * sx
            val cy = centerRefY * sy
            val params = smallTouchableParams(w, h).apply {
                x = (cx - w / 2f).toInt()
                y = (cy - h / 2f).toInt()
            }
            wm.addView(view, params)
            return view
        }

        val (centerX, fastY) = enemyMoveAnchor()
        fastTouch = zone(centerX, fastY + 15f, 106f, 116f) { showMoveMenu(MoveKind.FAST) }
        charged1Touch = zone(centerX - 72f, fastY + 129f, 120f, 130f) { showMoveMenu(MoveKind.CHARGED_1) }
        charged2Touch = zone(centerX + 72f, fastY + 129f, 120f, 130f) { showMoveMenu(MoveKind.CHARGED_2) }
    }

    private fun attachHudToggleTouch() {
        val wm = windowManager ?: return
        if (hudToggleTouch != null) return
        val params = hudControlParams(hudToggleAnchor())
        // Transparent hit-target only. The delicate eye is drawn on the HUD canvas.
        hudToggleTouch = TextView(this).apply {
            gravity = Gravity.CENTER
            text = ""
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "Ocultar ou mostrar HUD"
            isClickable = true
            isFocusable = false
            installHudControlDrag(this, params, BattleOverlayView.BLOCK_HUD_TOGGLE) { toggleHudCollapsed() }
        }.also { wm.addView(it, params) }
    }

    private fun attachHudLockTouch() {
        val wm = windowManager ?: return
        if (hudLockTouch != null) return
        val params = hudControlParams(hudLockAnchor())
        // Transparent hit-target only. The delicate padlock is drawn on the HUD canvas.
        hudLockTouch = TextView(this).apply {
            gravity = Gravity.CENTER
            text = ""
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "Travar ou destravar HUD"
            isClickable = true
            isFocusable = false
            installHudControlDrag(this, params, BattleOverlayView.BLOCK_HUD_LOCK) {
                settingsRepo.layoutLocked = !settingsRepo.layoutLocked
                if (settingsRepo.layoutLocked) {
                    overlayView?.setEditTarget(null)
                    removeEditTouchLayer()
                } else {
                    dismissMoveMenu()
                }
                redraw()
            }
        }.also { wm.addView(it, params) }
    }

    private fun hudControlParams(anchor: Pair<Float, Float>): WindowManager.LayoutParams {
        val metrics = resources.displayMetrics
        val sx = metrics.widthPixels / 864f
        val sy = metrics.heightPixels / 1536f
        val unit = min(sx, sy) * (settingsRepo.scale / .8f).coerceIn(.62f, 1.5f)
        val size = (56f * unit).toInt().coerceAtLeast(52)
        val centerX = anchor.first * sx
        val centerY = anchor.second * sy
        return smallTouchableParams(size, size).apply {
            x = (centerX - size / 2f).toInt()
            y = (centerY - size / 2f).toInt()
        }
    }

    private fun installHudControlDrag(view: View, params: WindowManager.LayoutParams, block: String, onTap: () -> Unit) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat().coerceAtLeast(16f)
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        view.isClickable = true
        view.isFocusable = false
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; dragging = false
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (!settingsRepo.layoutLocked && (abs(dx) >= touchSlop || abs(dy) >= touchSlop)) dragging = true
                    if (dragging) {
                        val maxX = (resources.displayMetrics.widthPixels - params.width).coerceAtLeast(0)
                        val maxY = (resources.displayMetrics.heightPixels - params.height).coerceAtLeast(0)
                        params.x = (startX + dx).toInt().coerceIn(0, maxX)
                        params.y = (startY + dy).toInt().coerceIn(-params.height / 2, maxY)
                        runCatching { windowManager?.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        val sx = resources.displayMetrics.widthPixels / 864f
                        val sy = resources.displayMetrics.heightPixels / 1536f
                        settingsRepo.setPosition(block, (params.x + params.width / 2f) / sx, (params.y + params.height / 2f) / sy)
                        redraw()
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

    private fun toggleHudCollapsed() {
        hudCollapsed = !hudCollapsed
        dismissMoveMenu()
        overlayView?.setCollapsed(hudCollapsed)
        redraw()
    }

    private fun controlButtonBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(225, 18, 22, 29))
            setStroke(2, Color.argb(235, 255, 255, 255))
        }

    private fun smallTouchableParams(width: Int, height: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

    /** Full-screen touch layer is temporary while editing/free-drag is active. Lock again before battling. */
    private fun attachEditTouchLayer() {
        val wm = windowManager ?: return
        if (editTouchLayer != null) return

        var candidate: String? = null
        var downX = 0f
        var downY = 0f
        var dragging = false

        val layer = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        candidate = overlayView?.dragTargetAt(event.x, event.y)
                        overlayView?.setEditTarget(candidate)
                        candidate?.let { block ->
                            overlayView?.blockPosition(block)?.let { pos ->
                                lastEditSnapshot = Triple(block, pos.first, pos.second)
                            }
                        }
                        downX = event.x
                        downY = event.y
                        dragging = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val block = candidate
                        if (block != null) {
                            val moved = abs(event.x - downX) + abs(event.y - downY) >= 6f
                            if (moved) dragging = true
                            if (dragging) overlayView?.moveDragTarget(block, event.x, event.y)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val block = candidate
                        if (block != null) {
                            if (dragging) {
                                overlayView?.moveDragTarget(block, event.x, event.y)
                                rebuildTouchableZones()
                            } else {
                                overlayView?.setEditTarget(block)
                            }
                        }
                        candidate = null
                        dragging = false
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        candidate = null
                        dragging = false
                        overlayView?.setEditTarget(null)
                        true
                    }
                    else -> true
                }
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        editTouchLayer = layer
        wm.addView(layer, params)
        if (settingsRepo.editMode) attachEditToolbar() else removeEditToolbar()
        bringHudControlsToFront()
        quickMenu?.bringToFront()
    }

    /** Compact edit controls. Select a HUD block, then resize/fade/lock/hide/undo or save. */
    private fun attachEditToolbar() {
        val wm = windowManager ?: return
        if (editToolbarView != null) return
        val d = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * d).toInt()

        fun button(textValue: String, action: () -> Unit) = TextView(this).apply {
            text = textValue
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setShadowLayer(2f, 0f, 1f, Color.BLACK)
            background = roundedBackground(Color.argb(210, 24, 29, 37), Color.argb(210, 238, 241, 246), dp(8).toFloat(), dp(1))
            isClickable = true
            setOnClickListener { action() }
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = roundedBackground(Color.argb(170, 15, 18, 24), Color.argb(150, 255, 255, 255), dp(10).toFloat(), dp(1))
        }

        fun selected(): String? = overlayView?.selectedEditTarget()
        fun refresh() { redraw() }

        val actions = listOf<Pair<String, () -> Unit>>(
            "−" to { selected()?.let { settingsRepo.setBlockScale(it, settingsRepo.blockScale(it) - .10f); refresh() } },
            "+" to { selected()?.let { settingsRepo.setBlockScale(it, settingsRepo.blockScale(it) + .10f); refresh() } },
            "◐" to { selected()?.let { val o=settingsRepo.blockOpacity(it); settingsRepo.setBlockOpacity(it, if (o <= .36f) 1f else o - .25f); refresh() } },
            "L" to { selected()?.let { settingsRepo.setBlockLocked(it, !settingsRepo.isBlockLocked(it)); refresh() } },
            "H" to { selected()?.let { settingsRepo.setBlockHidden(it, !settingsRepo.isBlockHidden(it)); refresh() } },
            "↶" to { lastEditSnapshot?.let { (block,x,y) -> settingsRepo.setPosition(block,x,y); overlayView?.setEditTarget(block); rebuildTouchableZones(); refresh() } },
            "✓" to {
                settingsRepo.editMode = false
                overlayView?.setEditTarget(null)
                removeEditToolbar()
                if (settingsRepo.layoutLocked) removeEditTouchLayer()
                rebuildTouchableZones()
                redraw()
            },
            "↺" to {
                settingsRepo.resetHudCustomization()
                overlayView?.setEditTarget(null)
                removeEditTouchLayer()
                rebuildTouchableZones()
                redraw()
            }
        )
        actions.forEach { (label, action) ->
            bar.addView(button(label, action), LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginStart=dp(2); marginEnd=dp(2) })
        }

        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val width = min((screenW * .92f).toInt(), dp(390))
        val height = dp(42)
        val params = smallTouchableParams(width, height).apply {
            x = (screenW - width) / 2
            y = (screenH - height - dp(62)).coerceAtLeast(0)
        }
        editToolbarView = bar
        wm.addView(bar, params)
    }

    private fun removeEditToolbar() {
        val bar = editToolbarView ?: return
        runCatching { windowManager?.removeView(bar) }
        editToolbarView = null
    }

    private fun removeEditTouchLayer() {
        val layer = editTouchLayer
        if (layer != null) runCatching { windowManager?.removeView(layer) }
        editTouchLayer = null
        removeEditToolbar()
        overlayView?.setEditTarget(null)
    }

    private var lastDragMode: String = "none"

    private fun syncEditTouchLayer(enabled: Boolean) {
        val inEdit = enabled && !hudCollapsed && settingsRepo.editMode
        val freeDrag = enabled && !hudCollapsed && !settingsRepo.layoutLocked && !settingsRepo.editMode
        val mode = when {
            inEdit -> "edit"
            freeDrag -> "free"
            else -> "none"
        }
        val modeChanged = mode != lastDragMode
        lastDragMode = mode

        when {
            inEdit -> {
                removeFreeDragZones()
                attachEditTouchLayer()
                if (editToolbarView == null) attachEditToolbar()
                if (modeChanged) {
                    bringEditToolbarToFront()
                    bringHudControlsToFront()
                    quickMenu?.bringToFront()
                }
            }
            freeDrag -> {
                removeEditTouchLayer()
                if (freeDragZones.isEmpty()) attachFreeDragZones()
                if (modeChanged) {
                    bringHudControlsToFront()
                    quickMenu?.bringToFront()
                }
            }
            else -> {
                removeEditTouchLayer()
                removeFreeDragZones()
            }
        }
    }

    private fun bringEditToolbarToFront() {
        val bar = editToolbarView ?: return
        val params = bar.layoutParams as? WindowManager.LayoutParams ?: return
        runCatching { windowManager?.removeView(bar) }
        runCatching { windowManager?.addView(bar, params) }
    }

    private fun attachFreeDragZones() {
        val wm = windowManager ?: return
        val view = overlayView ?: return
        removeFreeDragZones()
        val metrics = resources.displayMetrics
        val sx = metrics.widthPixels / 864f
        val sy = metrics.heightPixels / 1536f
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat().coerceAtLeast(16f)
        val blocks = listOf(
            BattleOverlayView.BLOCK_PLAYER_MATCHUP,
            BattleOverlayView.BLOCK_STRONG_TYPES,
            BattleOverlayView.BLOCK_ENEMY_MOVES,
            BattleOverlayView.BLOCK_RESERVE_1,
            BattleOverlayView.BLOCK_RESERVE_2,
            BattleOverlayView.BLOCK_BATTLE_ASSIST,
            BattleOverlayView.BLOCK_ENEMY_HISTORY
        )
        for (block in blocks) {
            if (settingsRepo.isBlockHidden(block)) continue
            val pos = view.blockPosition(block) ?: continue
            val half = when (block) {
                BattleOverlayView.BLOCK_ENEMY_MOVES -> 90f
                BattleOverlayView.BLOCK_STRONG_TYPES -> 70f
                BattleOverlayView.BLOCK_BATTLE_ASSIST -> 80f
                else -> 48f
            }
            val w = (half * 2f * sx).toInt().coerceAtLeast(48)
            val h = (half * 2f * sy).toInt().coerceAtLeast(48)
            val params = smallTouchableParams(w, h).apply {
                x = (pos.first * sx - w / 2f).toInt()
                y = (pos.second * sy - h / 2f).toInt()
            }
            var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
            val zone = View(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                isClickable = true
                contentDescription = "Arrastar $block"
                setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = event.rawX; downY = event.rawY
                            startX = params.x; startY = params.y
                            dragging = false
                            view.setEditTarget(block)
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.rawX - downX; val dy = event.rawY - downY
                            if (abs(dx) >= touchSlop || abs(dy) >= touchSlop) dragging = true
                            if (dragging) {
                                val maxX = (metrics.widthPixels - params.width).coerceAtLeast(0)
                                val maxY = (metrics.heightPixels - params.height).coerceAtLeast(0)
                                params.x = (startX + dx).toInt().coerceIn(0, maxX)
                                params.y = (startY + dy).toInt().coerceIn(-params.height / 2, maxY)
                                runCatching { wm.updateViewLayout(v, params) }
                                val cx = (params.x + params.width / 2f) / sx
                                val cy = (params.y + params.height / 2f) / sy
                                settingsRepo.setPosition(block, cx, cy)
                                view.invalidate()
                            }
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            if (dragging) {
                                val cx = (params.x + params.width / 2f) / sx
                                val cy = (params.y + params.height / 2f) / sy
                                settingsRepo.setPosition(block, cx, cy)
                                rebuildTouchableZones()
                                redraw()
                            }
                            view.setEditTarget(null)
                            dragging = false
                            true
                        }
                        else -> true
                    }
                }
            }
            runCatching { wm.addView(zone, params) }
            freeDragZones += zone
        }
    }

    private fun removeFreeDragZones() {
        val wm = windowManager
        freeDragZones.forEach { z -> runCatching { wm?.removeView(z) } }
        freeDragZones.clear()
    }

    private fun bringHudControlsToFront() {
        val wm = windowManager ?: return
        fun raise(view: View?) {
            if (view == null || view.windowToken == null) return
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return
            runCatching { wm.removeView(view) }
            runCatching { wm.addView(view, params) }
        }
        raise(hudToggleTouch)
        if (!hudCollapsed) raise(hudLockTouch)
    }

    private fun rebuildTouchableZones() {
        runCatching { fastTouch?.let { windowManager?.removeView(it) } }
        runCatching { charged1Touch?.let { windowManager?.removeView(it) } }
        runCatching { charged2Touch?.let { windowManager?.removeView(it) } }
        runCatching { hudToggleTouch?.let { windowManager?.removeView(it) } }
        runCatching { hudLockTouch?.let { windowManager?.removeView(it) } }
        fastTouch = null
        charged1Touch = null
        charged2Touch = null
        hudToggleTouch = null
        hudLockTouch = null
        attachMoveTouchZones()
        attachHudToggleTouch()
        attachHudLockTouch()
    }

    /** Compact type-colored submenu. Individual rows carry the color; there is no black panel behind them. */
    private fun showMoveMenu(kind: MoveKind) {
        // In manual HUD mode (automatic recognition disabled), battleActive has no
        // visual/OCR evidence by design; move selection must still remain usable.
        if (hudCollapsed || appUiVisible || (settingsRepo.autoRecognition && !battleActive) || settingsRepo.editMode) return
        val wm = windowManager ?: return
        val e = engine ?: return
        val options = when (kind) {
            MoveKind.FAST -> e.possibleFastMoves()
            MoveKind.CHARGED_1, MoveKind.CHARGED_2 -> e.possibleChargedMoves()
        }
        if (options.isEmpty()) return

        dismissMoveMenu()

        val metrics = resources.displayMetrics
        val density = metrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val ui = e.uiState(false)
        val selectedId = when (kind) {
            MoveKind.FAST -> ui.fastMove?.moveId
            MoveKind.CHARGED_1 -> ui.charged1?.move?.moveId
            MoveKind.CHARGED_2 -> ui.charged2?.move?.moveId
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setBackgroundColor(Color.TRANSPARENT)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = if (kind == MoveKind.FAST) "ATAQUE RÁPIDO" else "ATAQUE CARREGADO"
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
            textSize = 12.5f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = TextView(this).apply {
            text = "✕"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
            textSize = 16f
            setPadding(dp(8), 0, dp(2), 0)
            isClickable = true
            setOnClickListener { dismissMoveMenu() }
        }
        header.addView(title)
        header.addView(close)
        panel.addView(header)

        panel.addView(TextView(this).apply {
            text = "PvP • toque para trocar"
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
            textSize = 8.5f
            setPadding(0, 0, 0, dp(4))
        })

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        addMoveMenuRow(
            parent = list,
            title = "AUTO • PvPoke/OCR",
            detail = "moveset provável + aprendizado OCR",
            baseColor = Color.rgb(74, 82, 96),
            selected = false
        ) {
            when (kind) {
                MoveKind.FAST -> e.clearFastManualOverride()
                MoveKind.CHARGED_1 -> e.clearChargedManualOverride(0)
                MoveKind.CHARGED_2 -> e.clearChargedManualOverride(1)
            }
            frameDetector?.reset()
            dismissMoveMenu()
            redraw()
        }

        options.forEachIndexed { index, move ->
            val detail = if (kind == MoveKind.FAST) {
                "${move.type.uppercase()} • ${move.power} DMG • +${move.energyGain}E • ${move.turns}T"
            } else {
                "${move.type.uppercase()} • ${move.power} DMG • ${move.chargedCost}E"
            }
            addMoveMenuRow(
                parent = list,
                title = "#${index + 1} ${move.name}",
                detail = detail,
                baseColor = moveTypeColor(move.type),
                selected = move.moveId == selectedId
            ) {
                when (kind) {
                    MoveKind.FAST -> e.selectFast(move)
                    MoveKind.CHARGED_1 -> e.selectCharged1(move)
                    MoveKind.CHARGED_2 -> e.selectCharged2(move)
                }
                frameDetector?.reset()
                dismissMoveMenu()
                redraw()
            }
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.TRANSPARENT)
            addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val width = min((metrics.widthPixels * .70f).toInt(), dp(325))
        val estimatedRows = (options.size + 1).coerceAtMost(7)
        val desiredHeight = dp(48 + estimatedRows * 51)
        val height = min(desiredHeight, (metrics.heightPixels * .52f).toInt())
        val params = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (metrics.widthPixels - width) / 2
            y = (metrics.heightPixels * .26f).toInt()
        }

        moveMenuView = panel
        wm.addView(panel, params)
    }

    private fun addMoveMenuRow(
        parent: LinearLayout,
        title: String,
        detail: String,
        baseColor: Int,
        selected: Boolean,
        onTap: () -> Unit
    ) {
        val d = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * d).toInt()
        val rgb = darken(baseColor, .82f)
        val rowColor = withAlpha(rgb, 198)
        val border = if (selected) Color.argb(245, 255, 255, 255) else withAlpha(baseColor, 220)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = roundedBackground(rowColor, border, 9f * d, if (selected) dp(2) else dp(1))
            isClickable = true
            setOnClickListener { onTap() }
        }
        row.addView(TextView(this).apply {
            text = (if (selected) "✓ " else "") + title
            setTextColor(Color.WHITE)
            textSize = 11.5f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1
        })
        row.addView(TextView(this).apply {
            text = detail
            setTextColor(Color.argb(245, 248, 249, 252))
            textSize = 9f
            setPadding(0, dp(1), 0, 0)
            maxLines = 1
        })
        parent.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(4)
        })
    }

    private fun roundedBackground(color: Int, strokeColor: Int, radius: Float, strokeWidth: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius
            setStroke(strokeWidth, strokeColor)
        }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color)
    )

    private fun darken(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * factor).toInt().coerceIn(0, 255),
        (Color.green(color) * factor).toInt().coerceIn(0, 255),
        (Color.blue(color) * factor).toInt().coerceIn(0, 255)
    )

    private fun moveTypeColor(type: String): Int = when(type.lowercase()) {
        "fairy" -> Color.rgb(239,153,230); "psychic" -> Color.rgb(250,137,131)
        "fighting" -> Color.rgb(219,66,86); "rock" -> Color.rgb(206,193,141)
        "fire" -> Color.rgb(251,165,75); "steel" -> Color.rgb(85,151,164)
        "water" -> Color.rgb(91,166,224); "flying" -> Color.rgb(155,180,229)
        "ghost" -> Color.rgb(99,111,191); "grass" -> Color.rgb(93,190,101)
        "bug" -> Color.rgb(161,194,49); "ground" -> Color.rgb(214,133,85)
        "ice" -> Color.rgb(125,212,200); "dark" -> Color.rgb(113,121,140)
        "dragon" -> Color.rgb(7,115,200); "normal" -> Color.rgb(153,156,161)
        "electric" -> Color.rgb(243,219,85); "poison" -> Color.rgb(178,98,204)
        else -> Color.rgb(130,136,146)
    }

    private fun dismissMoveMenu() {
        val view = moveMenuView ?: return
        runCatching { windowManager?.removeView(view) }
        moveMenuView = null
    }

    private fun markBattleEvidence(now: Long) {
        lastBattleEvidenceAtMs = now
        battleActive = true
    }

    private fun normalizedBattleText(raw: String): String =
        Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")

    private fun battleTextLooksActive(raw: String): Boolean {
        if (raw.isBlank()) return false
        val normalized = normalizedBattleText(raw)
        return BATTLE_TEXT_MARKERS.any(normalized::contains)
    }

    private fun switchChoicePromptDetected(raw: String): Boolean {
        if (raw.isBlank()) return false
        return normalizedBattleText(raw).contains("quer trocar por um pokemon")
    }

    private fun battleResultFromText(raw: String): String? {
        if (raw.isBlank()) return null
        val compact = normalizedBattleText(raw).replace(Regex("[^a-z0-9]+"), "")
        return when {
            listOf("vocevenceu", "youwin", "victory").any(compact::contains) -> "Vitória"
            listOf("bomesforco", "voceperdeu", "youlost", "defeat").any(compact::contains) -> "Derrota"
            else -> null
        }
    }

    private fun updateHistorySession(
        isActive: Boolean,
        nowMs: Long,
        startedMidBattleEvidence: Boolean = false
    ) {
        val hadSession = historyRecorder.hasActiveSession()
        historyRecorder.setBattleActive(isActive, currentLeagueCp, nowMs)
        val hasSession = historyRecorder.hasActiveSession()

        if (!hadSession && hasSession && startedMidBattleEvidence) {
            historyRecorder.markStartedMidBattle()
            engine?.onStartedMidBattle()
        }

        if (hadSession && !hasSession) {
            clearBattleContextAfterConfirmedEnd()
        }
    }

    private fun clearBattleContextAfterConfirmedEnd() {
        lastRecordedAdviceKey = null
        historyAdviceGate.reset()
        stableHistoryAdvice = null
        lastReserveDiagnosticKey = null
        lastHistoryStateAtMs = 0L
        engine?.resetBattle()
        frameDetector?.reset()
        presenceTracker.reset()
        battleCorrespondence.reset()
        lastStrategyOpponentKey = null
        battleActive = false
        lastNativeTopCardY = null
        lastBattleEvidenceAtMs = 0L
        switchChoicePromptUntilMs = 0L
        detector?.clearCachedPortraits()
        overlayView?.clearPokemonPortraits()
        dismissMoveMenu()
    }

    private fun recordHistoryState(ui: BattleUiState, nowMs: Long, force: Boolean = false) {
        if (!historyRecorder.hasActiveSession()) return
        if (!force && nowMs - lastHistoryStateAtMs < HISTORY_STATE_INTERVAL_MS) return
        lastHistoryStateAtMs = nowMs

        val ownEnergy = ui.ownCharged1 ?: ui.ownCharged2
        val enemyEnergy = ui.charged1 ?: ui.charged2

        historyRecorder.recordState(
            actor = "VOCÊ",
            pokemon = ui.playerName,
            hpRatio = engine?.latestPlayerHpRatio(),
            energyMin = ownEnergy?.minEnergy,
            energyMax = ownEnergy?.maxEnergy,
            shields = ui.ownShieldsRemaining.takeIf { ui.ownShieldsKnown },
            confidence = ownEnergy?.confidence?.let(::energyConfidenceLabel),
            nowMs = nowMs
        )
        val predictive = ui.enemyEnergyForecast
        val topThreat = predictive?.candidates?.firstOrNull()
        historyRecorder.recordState(
            actor = "INIMIGO",
            pokemon = ui.opponentName,
            hpRatio = engine?.latestOpponentHpRatio(),
            energyMin = predictive?.energyMin ?: enemyEnergy?.minEnergy,
            energyMax = predictive?.energyMax ?: enemyEnergy?.maxEnergy,
            shields = ui.opponentShieldsRemaining.takeIf { ui.opponentShieldsKnown },
            confidence = if (predictive != null) {
                "PREVISÃO " + "%.0f".format(predictive.confidence * 100f) + "%"
            } else {
                enemyEnergy?.confidence?.let(::energyConfidenceLabel)
            },
            nowMs = nowMs,
            extraDetails = buildMap {
                predictive?.let {
                    put("hypotheses", it.hypothesisCount.toString())
                    put("reactionTurns", it.reactionTurns.toString())
                    put("leadTimeMs", it.leadTimeMs.toString())
                }
                topThreat?.let {
                    put("nextCharged", gameRepo.localizedMoveName(it.move))
                    put("turnsMin", it.turnsRemainingMin.toString())
                    put("turnsLikely", it.turnsRemainingLikely.toString())
                    put("turnsMax", it.turnsRemainingMax.toString())
                    put("fastMin", it.fastMovesRemainingMin.toString())
                    put("fastLikely", it.fastMovesRemainingLikely.toString())
                    put("fastMax", it.fastMovesRemainingMax.toString())
                    put("threat", "%.3f".format(it.threatScore))
                    put("confidence", "%.3f".format(it.confidence))
                    put("readyPossible", it.readyPossible.toString())
                    put("readyCertain", it.readyCertain.toString())
                    put("baitPotential", it.baitPotential.toString())
                    put("shieldPressure", "%.3f".format(it.shieldPressureScore))
                    put("mayKoBeforeCharged", it.mayKoBeforeCharged.toString())
                }
            }
        )
    }

    private fun energyConfidenceLabel(value: EnergyConfidence): String = when (value) {
        EnergyConfidence.CONFIRMED -> "CONFIRMADO"
        EnergyConfidence.ESTIMATED -> "ESTIMADO"
        EnergyConfidence.RANGE -> "FAIXA"
    }

    private fun startScanLoop() {
        scanJob?.cancel()
        scanJob = scope.launch {
            var lastOcrAt = 0L
            var lastTeamOcrAt = 0L
            var lastReserveOcrAt = 0L
            var lastLeagueOcrAt = 0L
            var consecutiveErrors = 0

            while (isActive) {
                if (visionPaused && screenRecorder?.isRecording != true) {
                    delay(250L)
                    continue
                }
                runCatching { capture?.ensureMatchesCurrentDisplay() }
                val nowForFrame = System.currentTimeMillis()
                val frame = acquireAnalysisFrame(nowForFrame)
                if (
                    frame == null &&
                    lastFreshAnalysisFrameAtMs > 0L &&
                    nowForFrame - lastFreshAnalysisFrameAtMs >= CAPTURE_STALE_AFTER_MS
                ) {
                    if (!captureStaleNotified) {
                        captureStaleNotified = true
                        engine?.onCaptureHealth(
                            CaptureHealth.STALE,
                            "Captura pausada • aguardando imagem atual"
                        )
                        withContext(Dispatchers.Main) {
                            refreshForegroundNotification("Captura pausada • aguardando imagem")
                            redraw()
                        }
                    }
                }
                if (frame != null) {
                    try {
                        screenRecorder?.let { recorder ->
                            recorder.recordFrame(frame)
                            if (recordingAwaitingEncoder && recorder.hasEncodedSample) {
                                recordingAwaitingEncoder = false
                                if (!recordingConfirmedNotified) {
                                    recordingConfirmedNotified = true
                                    historyRecorder.recordDiagnostic(
                                        "Gravação interna",
                                        "Encoder confirmado • perfil " + recorder.activeProfileLabel + " • " + recorder.submittedFrames + " frames enviados",
                                        System.currentTimeMillis()
                                    )
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            this@BattleOverlayService,
                                            "Gravação confirmada • perfil " + recorder.activeProfileLabel,
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        refreshForegroundNotification()
                                        quickMenu?.refreshPanelFromService()
                                    }
                                }
                            } else if (recordingAwaitingEncoder && recorder.encoderStalled) {
                                recordingAwaitingEncoder = false
                                runCatching { recorder.stop() }
                                val message = "Encoder não produziu vídeo nem no perfil compatível"
                                historyRecorder.recordDiagnostic("Gravação interna", message, System.currentTimeMillis())
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@BattleOverlayService, message, Toast.LENGTH_LONG).show()
                                    refreshForegroundNotification()
                                    quickMenu?.refreshPanelFromService()
                                }
                            }
                        }
                        if (visionPaused) {
                            consecutiveErrors = 0
                            delay(FRAME_INTERVAL_MS)
                            continue
                        }
                        val now = System.currentTimeMillis()
                        val manualCp = settingsRepo.configuredLeagueCp()
                        if (manualCp != null && manualCp != currentLeagueCp && engine?.activeEnemyName() == null) reloadLeague(manualCp)

                        if (
                            settingsRepo.leagueMode == BattleLeagueMode.AUTO &&
                            engine?.activeEnemyName() == null &&
                            now - lastLeagueOcrAt >= LEAGUE_OCR_INTERVAL_MS
                        ) {
                            detector?.detectLeagueCp(frame)
                                ?.takeIf { it in SUPPORTED_LEAGUE_CPS }
                                ?.let { detectedCp ->
                                    settingsRepo.lastDetectedLeagueCp = detectedCp
                                    if (detectedCp != currentLeagueCp) reloadLeague(detectedCp)
                                }
                            lastLeagueOcrAt = now
                        }

                        var e = engine
                        if (settingsRepo.autoRecognition && e != null) {
                            val event = frameDetector?.analyze(
                                frame,
                                e.currentFastDurationMs(),
                                e.currentOwnFastDurationMs(),
                                now
                            )
                            if (event != null) {
                                lastAnalysisFrameSourceTimestampMs
                                    .takeIf { it > 0L }
                                    ?.let { sourceTs ->
                                        e.onPipelineLatencySample(
                                            (System.currentTimeMillis() - sourceTs).coerceAtLeast(0L)
                                        )
                                    }
                                e.onPlayerHpBarYFraction(event.playerHpBarYFraction)
                                e.onReserveHpRatios(event.reserve1HpRatio, event.reserve2HpRatio)
                                val damageObservations = e.onHpObservation(
                                    event.hpRatio,
                                    event.opponentHpRatio,
                                    now
                                )
                                if (damageObservations.isNotEmpty()) {
                                    val snapshot = e.uiState()
                                    damageObservations.forEach { observation ->
                                        val localizedMove = gameRepo.move(observation.moveId)
                                            ?.let(gameRepo::localizedMoveName)
                                            ?: observation.moveName
                                        val attackerPokemon = if (observation.actor == "INIMIGO") {
                                            snapshot.opponentName
                                        } else {
                                            snapshot.playerName
                                        }
                                        val targetPokemon = if (observation.actor == "INIMIGO") {
                                            snapshot.playerName
                                        } else {
                                            snapshot.opponentName
                                        }
                                        if (observation.shielded) {
                                            val defenderActor = if (observation.actor == "INIMIGO") "VOCÊ" else "INIMIGO"
                                            historyRecorder.recordShield(
                                                actor = defenderActor,
                                                againstMove = localizedMove,
                                                remaining = observation.shieldsRemaining,
                                                nowMs = now,
                                                pokemon = targetPokemon,
                                                confidence = "INFERIDO_ALTO",
                                                source = "barra de HP estável pós-Charged"
                                            )
                                        } else {
                                            historyRecorder.recordDamageObservation(
                                                actor = observation.actor,
                                                moveName = localizedMove,
                                                damagePercent = observation.damagePercent,
                                                damagePoints = observation.damagePoints,
                                                learnedSamples = observation.learnedSamples,
                                                nowMs = now,
                                                attackerPokemon = attackerPokemon,
                                                targetPokemon = targetPokemon,
                                                source = "barra de HP"
                                            )
                                        }
                                    }
                                }
                                lastNativeTopCardY = event.nativeTopCardY ?: lastNativeTopCardY
                                battleActive = presenceTracker.update(
                                    visualConfidence = event.battleConfidence,
                                    nowMs = now
                                )
                                updateHistorySession(battleActive, now)
                                e.onBattleConfidence(presenceTracker.confidence)
                                if (event.battleUiVisible) {
                                    lastBattleUiVisibleAtMs = now
                                    markBattleEvidence(now)
                                }
                                if (battleActive || event.battleUiVisible) {
                                    e.onReserveSlotsDetected(event.reserve1Visible, event.reserve2Visible)
                                }
                                if (event.fastMoveCount > 0 && (battleActive || event.battleUiVisible)) {
                                    e.onFastDetected(
                                        event.fastMoveCount,
                                        event.confidence,
                                        event.source,
                                        event.hpRatio,
                                        event.motion,
                                        damageFraction = event.playerDamageFraction,
                                        nowMs = now
                                    )
                                    val snapshot = e.uiState()
                                    snapshot.fastMove?.let { move ->
                                        historyRecorder.recordFast(
                                            "INIMIGO",
                                            gameRepo.localizedMoveName(move),
                                            event.fastMoveCount,
                                            now,
                                            pokemon = snapshot.opponentName,
                                            confidence = knowledgeLabel(snapshot.fastMoveKnowledge),
                                            source = event.source,
                                            damageFraction = event.playerDamageFraction
                                        )
                                    }
                                }
                                if (event.ownFastMoveCount > 0 && (battleActive || event.battleUiVisible)) {
                                    e.onOwnFastDetected(event.ownFastMoveCount, event.confidence, event.source, event.opponentHpRatio)
                                    val snapshot = e.uiState()
                                    snapshot.ownFastMove?.let { move ->
                                        historyRecorder.recordFast(
                                            "VOCÊ",
                                            gameRepo.localizedMoveName(move),
                                            event.ownFastMoveCount,
                                            now,
                                            pokemon = snapshot.playerName,
                                            confidence = knowledgeLabel(snapshot.ownFastMoveKnowledge),
                                            source = event.source
                                        )
                                    }
                                }
                            }
                        }

                        e = engine
                        if (
                            settingsRepo.autoRecognition &&
                            e != null &&
                            e.needsReserveCardScan() &&
                            (battleActive || now - lastBattleEvidenceAtMs <= BATTLE_VISIBILITY_GRACE_MS) &&
                            now - lastReserveOcrAt >= RESERVE_OCR_INTERVAL_MS
                        ) {
                            detector?.detectReserveEvidence(frame)?.let { (topCard, bottomCard) ->
                                e.onReserveCardEvidence(topCard, bottomCard)
                                val portraits = detector?.copyReservePortraits()
                                if (portraits != null) {
                                    withContext(Dispatchers.Main) {
                                        val view = overlayView
                                        if (view != null) {
                                            view.setReservePortraits(portraits.first, portraits.second)
                                        } else {
                                            portraits.first?.let { if (!it.isRecycled) it.recycle() }
                                            portraits.second?.let { if (!it.isRecycled) it.recycle() }
                                        }
                                    }
                                }
                            }
                            lastReserveOcrAt = now
                        }

                        if (settingsRepo.autoRecognition && now - lastOcrAt >= OCR_INTERVAL_MS) {
                            val d = detector?.detect(frame)
                            e = engine
                            if (d != null && e != null) {
                                if (settingsRepo.leagueMode == BattleLeagueMode.AUTO) {
                                    inferLeagueCpFromCards(d)?.let { inferredCp ->
                                        if (inferredCp != currentLeagueCp) {
                                            settingsRepo.lastDetectedLeagueCp = inferredCp
                                            reloadLeague(inferredCp)
                                            e = engine
                                        }
                                    }
                                }
                                val pairedCards = d.player != null && d.opponent != null
                                val textEvidence = battleTextLooksActive(d.battleText)
                                val switchPrompt = switchChoicePromptDetected(d.battleText)
                                val hasBattleEvidence = pairedCards || textEvidence || switchPrompt
                                val previousEvidenceGap = if (lastBattleEvidenceAtMs > 0L) {
                                    (now - lastBattleEvidenceAtMs).coerceAtLeast(0L)
                                } else {
                                    Long.MAX_VALUE
                                }

                                val correspondenceEngine = e
                                val correspondenceDecision = if (
                                    historyRecorder.hasActiveSession() && correspondenceEngine != null
                                ) {
                                    battleCorrespondence.observe(
                                        context = correspondenceEngine.battleCorrespondenceContext(
                                            inactiveForMs = previousEvidenceGap,
                                            sessionAgeMs = historyRecorder.activeElapsedMs(now) ?: 0L
                                        ),
                                        observation = correspondenceEngine.battleCorrespondenceObservation(
                                            detection = d,
                                            pairedCards = pairedCards,
                                            battleTextEvidence = textEvidence,
                                            switchPrompt = switchPrompt
                                        ),
                                        nowMs = now
                                    )
                                } else {
                                    battleCorrespondence.reset()
                                    BattleCorrespondenceTracker.Decision.CONTINUE
                                }

                                if (correspondenceDecision == BattleCorrespondenceTracker.Decision.NEW_BATTLE) {
                                    // The old session survived a visibility gap, but a repeated,
                                    // incompatible player+opponent pair proves a new match.
                                    // Finish it before letting the new identities mutate energy/team state.
                                    historyRecorder.finish(
                                        nowMs = now,
                                        reason = "Nova partida confirmada por identidade visual/OCR"
                                    )
                                    clearBattleContextAfterConfirmedEnd()
                                    e = engine
                                }

                                if (hasBattleEvidence) {
                                    battleActive = presenceTracker.update(
                                        pairedPokemonCards = pairedCards,
                                        battleTextEvidence = textEvidence,
                                        switchPrompt = switchPrompt,
                                        nowMs = now
                                    )
                                    updateHistorySession(
                                        battleActive,
                                        now,
                                        startedMidBattleEvidence =
                                            correspondenceDecision == BattleCorrespondenceTracker.Decision.NEW_BATTLE &&
                                                textEvidence
                                    )
                                    e?.onBattleConfidence(presenceTracker.confidence)
                                    markBattleEvidence(now)
                                }
                                if (switchPrompt) {
                                    switchChoicePromptUntilMs = now + SWITCH_CHOICE_PROMPT_GRACE_MS
                                }

                                // While a possible new-match pair is waiting for its second
                                // confirming frame, preserve the old battle state untouched.
                                val holdIdentityMutation =
                                    correspondenceDecision == BattleCorrespondenceTracker.Decision.HOLD_CURRENT ||
                                        (
                                            correspondenceDecision == BattleCorrespondenceTracker.Decision.INSUFFICIENT &&
                                                battleCorrespondence.hasPendingNewBattleCandidate
                                            )

                                val before = e?.uiState()
                                val changes = if (!holdIdentityMutation) e?.onDetection(d) else null
                                val after = e?.uiState()
                                after?.opponentName?.takeIf { it.isNotBlank() }?.let { opponent ->
                                    val key = normalizedBattleText(opponent).replace(Regex("[^a-z0-9]+"), "")
                                    if (key != lastStrategyOpponentKey) {
                                        lastStrategyOpponentKey = key
                                        e?.onLocalStrategyProfile(historyRepository.strategyProfile(opponent))
                                    }
                                }
                                if (changes?.playerChanged == true) lastReserveOcrAt = 0L

                                if (battleActive && after != null) {
                                    historyRecorder.observeStableState(
                                        playerName = after.playerName,
                                        playerCp = after.playerCp,
                                        opponentName = after.opponentName,
                                        opponentCp = after.opponentCp,
                                        leagueCp = currentLeagueCp,
                                        nowMs = now
                                    )
                                }

                                if (changes?.playerChanged == true && after != null) {
                                    historyRecorder.recordSwitch(
                                        actor = "VOCÊ",
                                        fromPokemon = before?.playerName,
                                        toPokemon = after.playerName,
                                        cp = after.playerCp,
                                        nowMs = now,
                                        confidence = "CONFIRMADO"
                                    )
                                    recordHistoryState(after, now, force = true)
                                }
                                if (changes?.enemyChanged == true && after != null) {
                                    historyRecorder.recordSwitch(
                                        actor = "INIMIGO",
                                        fromPokemon = before?.opponentName,
                                        toPokemon = after.opponentName,
                                        cp = after.opponentCp,
                                        nowMs = now,
                                        confidence = "CONFIRMADO"
                                    )
                                    recordHistoryState(after, now, force = true)
                                }

                                if (changes?.playerChanged == true || changes?.enemyChanged == true) frameDetector?.reset()
                                if (changes?.playerChanged == true) switchChoicePromptUntilMs = 0L

                                val textEngine = e
                                val textEvent = if (!holdIdentityMutation) textEngine?.onBattleText(d.battleText, now) else null
                                if (textEvent != null && textEngine != null) {
                                    val chargedAtSessionStart =
                                        historyRecorder.activeElapsedMs(now)?.let { it <= 3_500L } == true &&
                                            (
                                                textEvent.newChargedPrompt ||
                                                    textEvent.chargedMoveConfirmed ||
                                                    textEvent.ownChargedMoveConfirmed
                                                )
                                    if (chargedAtSessionStart) historyRecorder.markStartedMidBattle()

                                    textEvent.enemyChargedMove?.let { move ->
                                        val snapshot = textEngine.uiState()
                                        historyRecorder.recordCharged(
                                            "INIMIGO",
                                            gameRepo.localizedMoveName(move),
                                            now,
                                            pokemon = snapshot.opponentName,
                                            confidence = chargedKnowledgeLabel(snapshot, move.moveId, own = false),
                                            source = "OCR"
                                        )
                                        recordHistoryState(snapshot, now, force = true)
                                    }
                                    textEvent.ownChargedMove?.let { move ->
                                        val snapshot = e.uiState()
                                        historyRecorder.recordCharged(
                                            "VOCÊ",
                                            gameRepo.localizedMoveName(move),
                                            now,
                                            pokemon = snapshot.playerName,
                                            confidence = chargedKnowledgeLabel(snapshot, move.moveId, own = true),
                                            source = "OCR"
                                        )
                                        recordHistoryState(snapshot, now, force = true)
                                    }
                                    when {
                                        textEvent.newChargedPrompt -> frameDetector?.freezeForCharged(now)
                                        textEvent.chargedMoveConfirmed || textEvent.ownChargedMoveConfirmed ->
                                            frameDetector?.freezeForCharged(now, 2_200L)
                                    }
                                }

                                battleResultFromText(d.battleText)?.let { result ->
                                    if (historyRecorder.hasActiveSession()) {
                                        historyRecorder.finish(
                                            nowMs = now,
                                            reason = "Resultado identificado na tela",
                                            result = result
                                        )
                                        clearBattleContextAfterConfirmedEnd()
                                    }
                                }
                            }
                            lastOcrAt = now
                        }

                        e = engine
                        val mayBeBackOnTeamScreen =
                            !battleActive &&
                                historyRecorder.hasActiveSession() &&
                                now - lastBattleEvidenceAtMs >= BattleContinuityPolicy.MIN_OUT_OF_BATTLE_MS
                        if (
                            settingsRepo.autoRecognition &&
                            e != null &&
                            !battleActive &&
                            (e.needsOwnTeamScan() || mayBeBackOnTeamScreen) &&
                            now - lastTeamOcrAt >= TEAM_OCR_INTERVAL_MS
                        ) {
                            val team = detector?.detectOwnTeam(frame).orEmpty()
                            // Copy before clearBattleContextAfterConfirmedEnd(): that method clears
                            // detector caches from the previous battle, but these copies belong to
                            // the newly detected team-selection screen and must survive it.
                            val detectedTeamPortraits = if (team.isNotEmpty()) {
                                detector?.copyOwnTeamPortraits().orEmpty()
                            } else emptyList()
                            if (
                                historyRecorder.hasActiveSession() &&
                                BattleContinuityPolicy.confirmedTeamSelectionBoundary(
                                    team = team,
                                    battleActive = battleActive,
                                    msSinceBattleEvidence = (now - lastBattleEvidenceAtMs).coerceAtLeast(0L)
                                )
                            ) {
                                historyRecorder.finish(
                                    nowMs = now,
                                    reason = "Tela de seleção da equipe confirmada"
                                )
                                clearBattleContextAfterConfirmedEnd()
                                engine?.onOwnTeamDetected(team)
                            } else if (e.needsOwnTeamScan() && team.isNotEmpty()) {
                                e.onOwnTeamDetected(team)
                            }
                            if (detectedTeamPortraits.any { it != null }) {
                                withContext(Dispatchers.Main) {
                                    val view = overlayView
                                    if (view != null) {
                                        view.setOwnTeamPortraits(detectedTeamPortraits)
                                    } else {
                                        detectedTeamPortraits.forEach { portrait ->
                                            portrait?.let { if (!it.isRecycled) it.recycle() }
                                        }
                                    }
                                }
                            }
                            lastTeamOcrAt = now
                        }

                        if (engine?.maybeConfirmPendingCharged(now) == true) {
                            historyRecorder.recordCharged(
                                "INIMIGO",
                                "Ataque carregado (não identificado)",
                                now,
                                pokemon = engine?.activeEnemyName(),
                                confidence = "DESCONHECIDO",
                                source = "energia/faixa"
                            )
                            markBattleEvidence(now)
                            frameDetector?.freezeForCharged(now, 1_800L)
                        }

                        battleActive = presenceTracker.tick(now)
                        updateHistorySession(battleActive, now)
                        engine?.onBattleConfidence(presenceTracker.confidence)
                        engine?.uiState()?.let { recordHistoryState(it, now) }
                        if (!battleActive) switchChoicePromptUntilMs = 0L

                        consecutiveErrors = 0
                        withContext(Dispatchers.Main) { redraw() }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                        consecutiveErrors++
                    } finally {
                        frame.recycle()
                    }
                }
                delay(if (consecutiveErrors == 0) FRAME_INTERVAL_MS else 650L)
            }
        }
    }

    /** Fallback when lobby OCR misses the league: live card CPs identify the cap reliably in normal GBL. */
    private fun inferLeagueCpFromCards(d: BattleDetection): Int? {
        val cps = listOfNotNull(d.player?.cp, d.opponent?.cp).filter { it > 0 }
        if (cps.isEmpty()) return null
        val maxCp = cps.maxOrNull() ?: return null
        return when {
            maxCp > 2500 -> 10000
            maxCp > 1500 -> 2500
            cps.size >= 2 -> 1500
            else -> null
        }
    }

    private fun knowledgeLabel(value: MoveKnowledgeConfidence): String = when (value) {
        MoveKnowledgeConfidence.CONFIRMED -> "CONFIRMADO"
        MoveKnowledgeConfidence.MANUAL -> "MANUAL"
        MoveKnowledgeConfidence.RANKED -> "ESTIMADO"
        MoveKnowledgeConfidence.UNKNOWN -> "DESCONHECIDO"
    }

    private fun chargedKnowledgeLabel(ui: BattleUiState, moveId: String, own: Boolean): String {
        return if (own) {
            when (moveId) {
                ui.ownCharged1?.move?.moveId -> knowledgeLabel(ui.ownCharged1Knowledge)
                ui.ownCharged2?.move?.moveId -> knowledgeLabel(ui.ownCharged2Knowledge)
                else -> "DESCONHECIDO"
            }
        } else {
            when (moveId) {
                ui.charged1?.move?.moveId -> knowledgeLabel(ui.charged1Knowledge)
                ui.charged2?.move?.moveId -> knowledgeLabel(ui.charged2Knowledge)
                else -> "DESCONHECIDO"
            }
        }
    }

    private fun recordUiDiagnostics(ui: BattleUiState, nowMs: Long = System.currentTimeMillis()) {
        if (!battleActive) {
            lastRecordedAdviceKey = null
            historyAdviceGate.reset()
            stableHistoryAdvice = null
            lastReserveDiagnosticKey = null
            return
        }

        val candidateAdvice = BattleAssistAdvisor.advise(ui, gameRepo)
        val candidateKey = candidateAdvice?.let {
            AdviceStabilityGate.Key(it.title, it.detail, it.priority)
        }
        val acceptedKey = historyAdviceGate.update(candidateKey, nowMs)
        if (acceptedKey == null) {
            stableHistoryAdvice = null
        } else if (candidateAdvice != null && acceptedKey == candidateKey) {
            stableHistoryAdvice = candidateAdvice
        }
        stableHistoryAdvice?.let { advice ->
            val key = "${advice.title}|${advice.detail}"
            if (key != lastRecordedAdviceKey) {
                val previous = lastRecordedAdviceKey?.substringBefore('|')
                val reason = if (previous == null) {
                    advice.detail
                } else {
                    "Mudou de $previous para ${advice.title}: ${advice.detail}"
                }
                historyRecorder.recordDecision(advice.title, reason, nowMs)
                lastRecordedAdviceKey = key
            }
        }

        val reserveKey = ui.reserves.joinToString(" | ") { reserve ->
            val c1 = reserve.chargedMove1?.let { gameRepo.localizedMoveName(it) } ?: "?"
            val c2 = reserve.chargedMove2?.let { gameRepo.localizedMoveName(it) } ?: "?"
            "#${(reserve.teamSlot ?: -1) + 1} ${reserve.name ?: "?"}/PC${reserve.cp ?: "?"}: " +
                "$c1[${knowledgeLabel(reserve.chargedMove1Knowledge)}] + " +
                "$c2[${knowledgeLabel(reserve.chargedMove2Knowledge)}]"
        }
        if (reserveKey.isNotBlank() && reserveKey != lastReserveDiagnosticKey) {
            historyRecorder.recordDiagnostic("Moveset das reservas", reserveKey, nowMs)
            lastReserveDiagnosticKey = reserveKey
        }
    }

    private suspend fun reloadLeague(cp: Int) {
        if (cp == currentLeagueCp) return
        withContext(Dispatchers.IO) { gameRepo.load(cp) }
        historyRecorder.setMetadata(BuildConfig.VERSION_NAME, gameRepo.offlineSnapshotVersion())
        currentLeagueCp = cp
        engine = BattleEngine(gameRepo)
        frameDetector?.reset()
        presenceTracker.reset()
        lastNativeTopCardY = null
        switchChoicePromptUntilMs = 0L
        withContext(Dispatchers.Main) {
            dismissMoveMenu()
            setForegroundNotification(notification("${BuildConfig.VERSION_NAME} • ${leagueLabel(cp)} • HUD PvP ativo"))
            redraw()
        }
    }

    private fun redraw() {
        val view = overlayView ?: return
        // With automatic recognition disabled, keep the HUD available as a manual
        // overlay instead of making every control disappear because battleActive can no
        // longer receive visual/OCR evidence.
        val enabled = !overlaySuppressed && settingsRepo.overlayEnabled && !appUiVisible && (
            battleActive || !settingsRepo.autoRecognition || settingsRepo.editMode
        )
        view.visibility = if (enabled) View.VISIBLE else View.GONE
        view.setCollapsed(hudCollapsed)
        view.setNativeTopCardY(lastNativeTopCardY)
        view.setSwitchChoicePromptVisible(enabled && System.currentTimeMillis() <= switchChoicePromptUntilMs)

        val ui = engine?.uiState(settingsRepo.debugMode)
        ui?.let {
            view.render(it)
            recordUiDiagnostics(it)
        }

        val dragHostEnabled = !overlaySuppressed && settingsRepo.overlayEnabled && !appUiVisible && (
            enabled || !settingsRepo.layoutLocked || settingsRepo.editMode
        )
        syncEditTouchLayer(dragHostEnabled)
        val selectorsEnabled = enabled && !hudCollapsed && settingsRepo.layoutLocked && !settingsRepo.editMode
        fastTouch?.visibility = if (selectorsEnabled && ui?.fastMove != null) View.VISIBLE else View.GONE
        charged1Touch?.visibility = if (selectorsEnabled && ui?.charged1 != null) View.VISIBLE else View.GONE
        charged2Touch?.visibility = if (selectorsEnabled && ui?.charged2 != null) View.VISIBLE else View.GONE
        val chromeEnabled = !overlaySuppressed && settingsRepo.overlayEnabled && !appUiVisible
        hudToggleTouch?.visibility = if (chromeEnabled) View.VISIBLE else View.GONE
        hudLockTouch?.visibility = if (chromeEnabled && !hudCollapsed) View.VISIBLE else View.GONE
        // Original lock/eye icons are drawn on the HUD canvas; touch views stay transparent.
        quickMenu?.setVisible(chromeEnabled && !hudCollapsed)
        val leagueVisuallyActive =
            System.currentTimeMillis() - lastBattleUiVisibleAtMs <= LEAGUE_BADGE_VISIBILITY_MS
        quickMenu?.setBattleActive(leagueVisuallyActive)
        if (!hudCollapsed) quickMenu?.refreshStatusBadge()
        if (!selectorsEnabled) dismissMoveMenu()
    }

    override fun onFastMoveTapped() { showMoveMenu(MoveKind.FAST) }
    override fun onCharged1Tapped() { showMoveMenu(MoveKind.CHARGED_1) }
    override fun onCharged2Tapped() { showMoveMenu(MoveKind.CHARGED_2) }
    override fun onResetTapped() {
        historyRecorder.finish()
        lastRecordedAdviceKey = null
        historyAdviceGate.reset()
        stableHistoryAdvice = null
        lastReserveDiagnosticKey = null
        engine?.resetBattle()
        frameDetector?.reset()
        presenceTracker.reset()
        lastNativeTopCardY = null
        battleActive = false
        lastBattleEvidenceAtMs = 0L
        switchChoicePromptUntilMs = 0L
        detector?.clearCachedPortraits()
        overlayView?.clearPokemonPortraits()
        dismissMoveMenu()
        redraw()
    }
    override fun onManualFastDelta(delta: Int) {
        engine?.manualFastDelta(delta)
        redraw()
    }
    override fun onMoveSelected(kind: BattleOverlayView.MoveKind, move: MoveDef) {
        when (kind) {
            BattleOverlayView.MoveKind.FAST -> engine?.selectFast(move)
            BattleOverlayView.MoveKind.CHARGED_1 -> engine?.selectCharged1(move)
            BattleOverlayView.MoveKind.CHARGED_2 -> engine?.selectCharged2(move)
        }
        frameDetector?.reset()
        dismissMoveMenu()
        redraw()
    }

    private fun toggleRecording() {
        val recorder = screenRecorder ?: return
        if (recorder.isRecording) {
            recordingAwaitingEncoder = false
            recordingConfirmedNotified = false
            val uri = recorder.stop()
            Toast.makeText(
                this,
                if (uri != null) {
                    "Gravação salva em Movies/PvPPokeGo"
                } else {
                    "Gravação não foi salva: " + (recorder.lastErrorMessage ?: "erro desconhecido")
                },
                Toast.LENGTH_LONG
            ).show()
        } else {
            val c = capture
            val ok = c != null && c.width > 0 && c.height > 0 && recorder.start(c.width, c.height)
            Toast.makeText(
                this,
                if (ok) {
                    recordingAwaitingEncoder = true
                    recordingConfirmedNotified = false
                    "Preparando gravação • aguardando o primeiro quadro codificado"
                } else {
                    recorder.lastErrorMessage ?: "Inicie a captura do overlay antes de gravar"
                },
                Toast.LENGTH_LONG
            ).show()
        }
        refreshForegroundNotification()
        quickMenu?.refreshPanelFromService()
    }

    /**
     * Returns the next frame from MediaProjection, or from the screenshot-only
     * Accessibility fallback after another recorder revokes MediaProjection.
     */
    private fun acquireAnalysisFrame(nowMs: Long): android.graphics.Bitmap? {
        val mediaCapture = capture
        if (mediaCapture != null) {
            val frameTimestamp = mediaCapture.latestFrameTimestampMs()
            if (frameTimestamp > 0L && frameTimestamp > lastMediaProjectionFrameAtMs) {
                val bitmap = mediaCapture.acquireLatestBitmap()
                if (bitmap != null) {
                    lastMediaProjectionFrameAtMs = frameTimestamp
                    lastAnalysisFrameSourceTimestampMs = frameTimestamp
                    lastFreshAnalysisFrameAtMs = nowMs
                    captureStaleNotified = false
                    usingAccessibilityFallback = false
                    engine?.onCaptureHealth(CaptureHealth.FRESH)
                    return bitmap
                }
            }
        }

        if (!projectionRevokedBySystem || !AccessibilityScreenCapture.isConnected) return null
        usingAccessibilityFallback = true
        AccessibilityScreenCapture.requestScreenshot(nowMs)
        val fresh = AccessibilityScreenCapture.acquireLatestBitmap(lastAccessibilityFrameAtMs) ?: return null
        lastAccessibilityFrameAtMs = fresh.second
        lastAnalysisFrameSourceTimestampMs = fresh.second
        lastFreshAnalysisFrameAtMs = nowMs
        captureStaleNotified = false
        engine?.onCaptureHealth(
            CaptureHealth.COMPATIBILITY,
            "Gravador externo ativo • análise por screenshots"
        )
        return fresh.first
    }

    private suspend fun handleProjectionRevoked() {
        projectionRevokedBySystem = true
        projection = null
        runCatching { capture?.stop() }
        capture = null

        if (screenRecorder?.isRecording == true) {
            runCatching { screenRecorder?.stop() }
        }

        // Standard builds intentionally have no Accessibility/specialUse fallback.
        // Preserve the in-memory battle context and history checkpoint instead of
        // silently ending the battle when another app revokes MediaProjection.
        if (!BuildConfig.RECORDER_COMPAT) {
            usingAccessibilityFallback = false
            engine?.onCaptureHealth(
                CaptureHealth.PAUSED,
                "Captura interrompida • reabra o PvPPokeGo para autorizar a tela novamente"
            )
            withContext(Dispatchers.Main) {
                refreshForegroundNotification(
                    "Captura pausada • contexto preservado • reautorize a captura"
                )
                redraw()
            }
            return
        }

        usingAccessibilityFallback =
            AccessibilityScreenCapture.isConnected
        if (usingAccessibilityFallback) {
            AccessibilityScreenCapture.clearLatest()
            lastAccessibilityFrameAtMs = 0L
        }
        withContext(Dispatchers.Main) {
            if (usingAccessibilityFallback) {
                refreshForegroundNotification(
                    "Gravador externo ativo • compatibilidade por screenshot • análise reduzida"
                )
            } else {
                refreshForegroundNotification(
                    "Captura tomada por outro gravador • ative Compatibilidade com gravador externo"
                )
            }
            redraw()
        }
    }

    private fun currentBattleModeLabelText(): String {
        val league = when (settingsRepo.leagueMode) {
            BattleLeagueMode.AUTO -> when (currentLeagueCp) {
                2500 -> "AUTO-U"
                10000 -> "AUTO-M"
                else -> "AUTO-G"
            }
            BattleLeagueMode.GREAT -> "GRANDE"
            BattleLeagueMode.ULTRA -> "ULTRA"
            BattleLeagueMode.MASTER -> "MESTRA"
        }
        return if (settingsRepo.battleAssistEnabled) "$league • ASSIST" else league
    }

    private fun serviceAction(requestCode: Int, action: String): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, BattleOverlayService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun refreshForegroundNotification(baseText: String? = null) {
        val state = when {
            usingAccessibilityFallback -> "Gravador externo • captura compatível (análise reduzida)"
            projectionRevokedBySystem && !AccessibilityScreenCapture.isConnected ->
                "Captura pausada pelo gravador externo • ative compatibilidade"
            screenRecorder?.isRecording == true && screenRecorder?.hasEncodedSample == true ->
                "● GRAVANDO • ${leagueLabel(currentLeagueCp)} • HUD ativo"
            screenRecorder?.isRecording == true ->
                "Preparando gravação • ${screenRecorder?.activeProfileLabel ?: "encoder"}"
            overlaySuppressed -> "Sobreposição oculta"
            visionPaused -> "Visão pausada"
            else -> baseText ?: "${leagueLabel(currentLeagueCp)} • HUD PvP ativo"
        }
        setForegroundNotification(notification(state))
    }

    private fun setForegroundNotification(value: Notification) {
        // The standard APK intentionally follows the proven 0.5.19 foreground-service
        // path: MediaProjection only, with no SPECIAL_USE startup type. Some Samsung/
        // Play Protect combinations rejected or killed the 0.5.21 standard overlay
        // because SPECIAL_USE was requested before the capture projection existed.
        if (!BuildConfig.RECORDER_COMPAT || Build.VERSION.SDK_INT < 34) {
            startForeground(NOTIFICATION_ID, value)
            return
        }

        val type = if (projection != null) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, value, type)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val vision = serviceAction(1, ACTION_TOGGLE_VISION)
        val overlay = serviceAction(2, ACTION_TOGGLE_OVERLAY)
        val stop = serviceAction(3, ACTION_STOP)
        val statusLabel = when {
            overlaySuppressed -> "OCULTO"
            visionPaused -> "PAUSADO"
            else -> "ON"
        }
        val largeIcon = runCatching {
            BitmapFactory.decodeResource(resources, R.drawable.pvppokego_launcher)
        }.getOrNull()
        return NotificationCompat.Builder(this, PvPPokeGoApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pvppokego)
            .setLargeIcon(largeIcon)
            .setContentTitle("PvPPokeGo • $statusLabel")
            .setContentText(text)
            .setSubText("Assistente PvP • toque para abrir")
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(
                android.R.drawable.ic_media_pause,
                if (visionPaused) "Retomar visão" else "Pausar visão",
                vision
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                if (overlaySuppressed) "Mostrar sobreposição" else "Remover sobreposição",
                overlay
            )
            .addAction(android.R.drawable.ic_menu_delete, "Parar serviço", stop)
            .build()
    }

    override fun onDestroy() {
        destroyingService = true
        if (::historyRecorder.isInitialized) historyRecorder.finish()
        scanJob?.cancel()
        dismissMoveMenu()
        quickMenu?.destroy()
        quickMenu = null
        removeEditTouchLayer()
        removeFreeDragZones()
        overlayView?.release()
        runCatching { overlayView?.let { windowManager?.removeView(it) } }
        runCatching { fastTouch?.let { windowManager?.removeView(it) } }
        runCatching { charged1Touch?.let { windowManager?.removeView(it) } }
        runCatching { charged2Touch?.let { windowManager?.removeView(it) } }
        runCatching { hudToggleTouch?.let { windowManager?.removeView(it) } }
        runCatching { hudLockTouch?.let { windowManager?.removeView(it) } }
        overlayView = null
        fastTouch = null
        charged1Touch = null
        charged2Touch = null
        hudToggleTouch = null
        hudLockTouch = null
        detector?.close()
        detector = null
        frameDetector = null
        screenRecorder?.release()
        screenRecorder = null
        capture?.stop()
        capture = null
        projection?.stop()
        projection = null
        AccessibilityScreenCapture.clearLatest()
        settingsRepo.overlayServiceRunning = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun leagueLabel(cp: Int): String = when (cp) {
        2500 -> "Liga Ultra"
        10000 -> "Liga Mestra"
        else -> "Liga Grande"
    }

    private enum class MoveKind { FAST, CHARGED_1, CHARGED_2 }

    companion object {
        const val EXTRA_RESULT_CODE = "media_projection_result_code"
        const val EXTRA_RESULT_DATA = "media_projection_result_data"
        const val EXTRA_APP_VISIBLE = "pvppokego_app_visible"
        const val ACTION_STOP = "com.lucianotoscano.pvppokego.STOP"
        const val ACTION_RESET = "com.lucianotoscano.pvppokego.RESET"
        const val ACTION_TOGGLE_VISION = "com.lucianotoscano.pvppokego.TOGGLE_VISION"
        const val ACTION_TOGGLE_OVERLAY = "com.lucianotoscano.pvppokego.TOGGLE_OVERLAY"
        const val ACTION_TOGGLE_RECORDING = "com.lucianotoscano.pvppokego.TOGGLE_RECORDING"
        const val ACTION_START_ACCESSIBILITY_CAPTURE = "com.lucianotoscano.pvppokego.START_ACCESSIBILITY_CAPTURE"
        const val ACTION_APP_FOREGROUND = "com.lucianotoscano.pvppokego.APP_FOREGROUND"
        const val ACTION_APP_BACKGROUND = "com.lucianotoscano.pvppokego.APP_BACKGROUND"
        private const val NOTIFICATION_ID = 1107
        private const val CAPTURE_STALE_AFTER_MS = 2_500L
        private const val LEAGUE_BADGE_VISIBILITY_MS = 2_800L
        private const val FRAME_INTERVAL_MS = 105L
        private const val OCR_INTERVAL_MS = 520L
        private const val RESERVE_OCR_INTERVAL_MS = 280L
        private const val TEAM_OCR_INTERVAL_MS = 1_550L
        private const val LEAGUE_OCR_INTERVAL_MS = 1_900L
        private const val BATTLE_VISIBILITY_GRACE_MS = 3_800L
        private const val SWITCH_CHOICE_PROMPT_GRACE_MS = 2_400L
        private const val HISTORY_STATE_INTERVAL_MS = 5_000L
        private const val EDIT_LONG_PRESS_MS = 180L
        private val SUPPORTED_LEAGUE_CPS = setOf(1500, 2500, 10000)
        private val BATTLE_TEXT_MARKERS = listOf(
            "usou ",
            "quer usar um escudo",
            "escudo protetor",
            "bloqueado",
            "supereficaz",
            "nao e muito eficaz",
            "quer trocar por um pokemon",
            "agora nao"
        )
    }
}
