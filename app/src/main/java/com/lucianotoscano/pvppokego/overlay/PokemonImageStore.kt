package com.lucianotoscano.pvppokego.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import com.lucianotoscano.pvppokego.data.PokemonImageCatalog
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Display-only image cache: no network calls, file reads, or bitmap decoding on
 * the battle UI draw path. The original packaged sprite atlas remains fallback.
 */
internal class PokemonImageStore(context: Context, private val invalidate: () -> Unit) {
    private val root = File(context.applicationContext.filesDir, "pokemon_images")
    private val main = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val retryAfter = ConcurrentHashMap<String, Long>()
    private val decoded = object : LruCache<String, Bitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    fun find(dex: Int?, speciesId: String?): Bitmap? {
        val image = PokemonImageCatalog.lookup(dex, speciesId) ?: return null
        synchronized(decoded) { decoded.get(image.key) }?.let { bitmap ->
            if (!bitmap.isRecycled) return bitmap
        }
        val now = SystemClock.elapsedRealtime()
        if (now < (retryAfter[image.key] ?: 0L)) return null
        if (pending.add(image.key)) {
            executor.execute {
                try {
                    val file = File(root, image.fileName)
                    val bitmap = if (file.isFile) BitmapFactory.decodeFile(file.path) else null
                    if (bitmap != null && bitmap.width >= 16 && bitmap.height >= 16) {
                        synchronized(decoded) { decoded.put(image.key, bitmap) }
                        retryAfter.remove(image.key)
                        main.post { invalidate() }
                    } else {
                        bitmap?.recycle()
                        retryAfter[image.key] = SystemClock.elapsedRealtime() + 30_000L
                    }
                } catch (_: Exception) {
                    retryAfter[image.key] = SystemClock.elapsedRealtime() + 30_000L
                } finally {
                    pending.remove(image.key)
                }
            }
        }
        return null
    }

    companion object {
        private val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "pvppokego-image-decode").apply { isDaemon = true }
        }
    }
}
