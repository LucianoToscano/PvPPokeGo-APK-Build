package com.lucianotoscano.pvppokego.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Optional capture fallback for users who want to run Samsung/third-party screen recorders.
 *
 * Android may revoke the MediaProjection owned by PvPPokeGo when another recorder starts.
 * If this AccessibilityService has been enabled by the user, PvPPokeGo can keep taking
 * low-frequency screenshots for OCR/matchup analysis instead of shutting the overlay down.
 *
 * It intentionally does not inspect accessibility nodes or perform input.
 */
object AccessibilityScreenCapture {
    private val serviceRef = AtomicReference<WeakReference<PvPPokeGoAccessibilityCaptureService>?>(null)
    private val latest = AtomicReference<Bitmap?>(null)
    private val inFlight = AtomicBoolean(false)
    private val bitmapLock = Any()

    @Volatile
    private var lastRequestAtMs: Long = 0L

    @Volatile
    private var latestCapturedAtMs: Long = 0L

    val isConnected: Boolean
        get() = serviceRef.get()?.get() != null

    internal fun attach(service: PvPPokeGoAccessibilityCaptureService) {
        serviceRef.set(WeakReference(service))
    }

    internal fun detach(service: PvPPokeGoAccessibilityCaptureService) {
        val current = serviceRef.get()?.get()
        if (current === service) serviceRef.set(null)
        inFlight.set(false)
        val old = synchronized(bitmapLock) {
            latestCapturedAtMs = 0L
            latest.getAndSet(null)
        }
        old?.recycle()
    }

    fun requestScreenshot(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val service = serviceRef.get()?.get() ?: return false
        if (inFlight.get()) return false
        if (nowMs - lastRequestAtMs < MIN_REQUEST_INTERVAL_MS) return false
        if (!inFlight.compareAndSet(false, true)) return false
        lastRequestAtMs = nowMs

        return runCatching {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        try {
                            val hardware = screenshot.hardwareBuffer
                            val wrapped = Bitmap.wrapHardwareBuffer(hardware, screenshot.colorSpace)
                            val copy = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                            hardware.close()
                            if (copy != null) {
                                val old = synchronized(bitmapLock) {
                                    latestCapturedAtMs = System.currentTimeMillis()
                                    latest.getAndSet(copy)
                                }
                                old?.recycle()
                            }
                        } finally {
                            inFlight.set(false)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        inFlight.set(false)
                    }
                }
            )
            true
        }.getOrElse {
            inFlight.set(false)
            false
        }
    }

    /** Caller owns and must recycle the returned copy. Returns only a newer frame. */
    fun acquireLatestBitmap(afterCapturedAtMs: Long): Pair<Bitmap, Long>? = synchronized(bitmapLock) {
        val capturedAt = latestCapturedAtMs
        if (capturedAt <= afterCapturedAtMs) return@synchronized null
        val src = latest.get() ?: return@synchronized null
        if (src.isRecycled) null else src.copy(Bitmap.Config.ARGB_8888, false) to capturedAt
    }

    fun clearLatest() {
        val old = synchronized(bitmapLock) { latest.getAndSet(null) }
        old?.recycle()
    }

    const val MIN_REQUEST_INTERVAL_MS = 360L
}

class PvPPokeGoAccessibilityCaptureService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityScreenCapture.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Deliberately unused. This service exists only for takeScreenshot().
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        AccessibilityScreenCapture.detach(this)
        super.onDestroy()
    }
}
