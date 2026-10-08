package com.lucianotoscano.pvppokego.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class ScreenCapture(
    private val context: Context,
    private val projection: MediaProjection
) {
    private val latest = AtomicReference<Bitmap?>(null)
    private val latestTimestampMs = AtomicLong(0L)
    /** Guards copy/replacement so the capture thread never recycles a bitmap mid-copy. */
    private val bitmapLock = Any()
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    var width: Int = 0
        private set
    var height: Int = 0
        private set

    fun start() {
        if (display != null) return
        openCapture()
    }

    /**
     * If the device rotated or the screen metrics changed, rebuild the virtual display
     * so OCR ROIs stay aligned with the live Pokemon GO frame.
     */
    fun ensureMatchesCurrentDisplay(): Boolean {
        val (w, h) = currentScreenSize()
        if (w <= 0 || h <= 0) return false
        if (display != null && kotlin.math.abs(w - width) <= 2 && kotlin.math.abs(h - height) <= 2) return false
        closeCaptureSurfaces()
        width = w
        height = h
        openCaptureSurfacesOnly()
        return true
    }

    private fun openCapture() {
        val (w, h) = currentScreenSize()
        width = w
        height = h
        if (thread == null) {
            thread = HandlerThread("pvppokego-capture").also { it.start() }
            handler = Handler(thread!!.looper)
        }
        openCaptureSurfacesOnly()
    }

    private fun openCaptureSurfacesOnly() {
        val density = context.resources.displayMetrics.densityDpi
        val h = handler ?: return
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader!!.setOnImageAvailableListener({ r ->
            val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes.firstOrNull() ?: return@setOnImageAvailableListener
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val paddedWidth = width + rowPadding / pixelStride
                val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(buffer)
                val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
                padded.recycle()

                val old = synchronized(bitmapLock) {
                    latestTimestampMs.set(System.currentTimeMillis())
                    latest.getAndSet(cropped)
                }
                old?.recycle()
            } finally {
                image.close()
            }
        }, h)

        display = projection.createVirtualDisplay(
            "PVPPokeGoCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface,
            null,
            h
        )
    }

    private fun closeCaptureSurfaces() {
        runCatching { display?.release() }
        display = null
        runCatching { reader?.close() }
        reader = null
        val last = synchronized(bitmapLock) {
            latestTimestampMs.set(0L)
            latest.getAndSet(null)
        }
        last?.recycle()
    }

    private fun currentScreenSize(): Pair<Int, Int> {
        val wm = context.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
    }

    /** Timestamp of the actual ImageReader frame, not the caller's last copy. */
    fun latestFrameTimestampMs(): Long = latestTimestampMs.get()

    /** Caller owns and must recycle the returned copy. */
    fun acquireLatestBitmap(): Bitmap? = synchronized(bitmapLock) {
        val src = latest.get() ?: return@synchronized null
        if (src.isRecycled) null else src.copy(Bitmap.Config.ARGB_8888, false)
    }

    fun stop() {
        closeCaptureSurfaces()
        thread?.quitSafely()
        thread = null
        handler = null
        width = 0
        height = 0
    }

}
