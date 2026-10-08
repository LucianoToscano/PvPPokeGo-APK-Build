package com.lucianotoscano.pvppokego.capture

import android.graphics.Bitmap

/**
 * Standard distribution stub.
 *
 * The installable default APK intentionally contains no AccessibilityService.
 * External-recorder screenshot compatibility lives only in the recorderCompat flavor.
 */
object AccessibilityScreenCapture {
    val isConnected: Boolean
        get() = false

    fun requestScreenshot(nowMs: Long = System.currentTimeMillis()): Boolean = false

    fun acquireLatestBitmap(afterCapturedAtMs: Long): Pair<Bitmap, Long>? = null

    fun clearLatest() = Unit

    const val MIN_REQUEST_INTERVAL_MS = 360L
}
