package com.lucianotoscano.pvppokego.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/**
 * Wi-Fi-only, rate-limited cache population. Downloading does not run during
 * battle frames; the existing baked sprite atlas always works without a network.
 */
internal class PokemonImageSyncWorker(
    context: Context, params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences("pvppokego_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("pokemon_images_auto_update", true)) return@withContext Result.success()
        val wifiOnly = prefs.getBoolean("pokemon_images_wifi_only", true)
        if (wifiOnly && !onWifi()) return@withContext Result.retry()

        val folder = File(applicationContext.filesDir, "pokemon_images")
        if (!folder.exists() && !folder.mkdirs()) return@withContext Result.retry()
        val now = System.currentTimeMillis()
        // Missing first, then oldest. Limit transfer volume/time on every run.
        val work = PokemonImageCatalog.allImages().map { image ->
            image to File(folder, image.fileName)
        }.sortedWith(compareBy({ (_, file) -> file.isFile }, { (_, file) ->
            if (file.isFile) file.lastModified() else 0L
        })).take(MAX_PER_RUN).toList()

        var failed = 0
        for ((image, target) in work) {
            if (isStopped) return@withContext Result.success()
            if (wifiOnly && !onWifi()) return@withContext Result.retry()
            if (target.isFile && target.length() > 250 && now - target.lastModified() < REFRESH_AFTER_MS) continue
            if (!downloadAndInstall(image.url, target)) failed++
        }
        // Avoid retry storms when upstream is unavailable. Existing images remain usable.
        if (failed == work.size && failed > 0) Result.retry() else Result.success()
    }

    private fun onWifi(): Boolean {
        val manager = applicationContext.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun downloadAndInstall(url: String, target: File): Boolean {
        var connection: HttpURLConnection? = null
        val tmp = File(target.parentFile, target.name + ".tmp")
        return try {
            // The catalog only constructs HTTPS URLs to this known fixed host.
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 9_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "PvPPokeGo-ImageDB/2.0")
            }
            connection = conn
            if (conn.responseCode != 200) return false
            val buffer = java.io.ByteArrayOutputStream()
            conn.inputStream.use { source ->
                val bytes = ByteArray(8192)
                var length: Int
                while (source.read(bytes).also { length = it } != -1) {
                    if (buffer.size() + length > MAX_DOWNLOAD_BYTES) return false
                    buffer.write(bytes, 0, length)
                }
            }
            val raw = buffer.toByteArray()
            if (raw.size < 100 || !raw.copyOfRange(0, 8).contentEquals(PNG_MAGIC)) return false
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, options)
            if (options.outWidth !in 32..2048 || options.outHeight !in 32..2048) return false
            val image = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return false
            val longest = maxOf(image.width, image.height)
            val output = if (longest > 256) {
                Bitmap.createScaledBitmap(
                    image, (image.width * 256f / longest).toInt().coerceAtLeast(1),
                    (image.height * 256f / longest).toInt().coerceAtLeast(1), true
                )
            } else image
            try {
                tmp.outputStream().buffered().use {
                    val format = if (Build.VERSION.SDK_INT >= 30) {
                        Bitmap.CompressFormat.WEBP_LOSSY
                    } else Bitmap.CompressFormat.WEBP
                    if (!output.compress(format, 88, it)) return false
                }
            } finally {
                if (output !== image) output.recycle()
                image.recycle()
            }
            if (tmp.length() < 200) return false
            // Replace only after the complete download, decode and WebP verification.
            if (BitmapFactory.decodeFile(tmp.path) == null) return false
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (_: Exception) {
            false
        } finally {
            tmp.delete()
            connection?.disconnect()
        }
    }

    companion object {
        private val PNG_MAGIC = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        private const val MAX_PER_RUN = 48
        private const val MAX_DOWNLOAD_BYTES = 6 * 1024 * 1024
        private const val REFRESH_AFTER_MS = 7L * 24 * 60 * 60 * 1000
        private const val PERIODIC_NAME = "pvppokego-pokemon-images-daily"

        fun schedule(context: Context) {
            val manager = WorkManager.getInstance(context)
            val prefs = context.getSharedPreferences("pvppokego_prefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("pokemon_images_auto_update", true)) {
                manager.cancelUniqueWork(PERIODIC_NAME)
                return
            }
            val network = if (prefs.getBoolean("pokemon_images_wifi_only", true)) {
                NetworkType.UNMETERED
            } else NetworkType.CONNECTED
            val constraints = Constraints.Builder().setRequiredNetworkType(network).build()
            manager.enqueueUniquePeriodicWork(
                PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<PokemonImageSyncWorker>(24, TimeUnit.HOURS)
                    .setConstraints(constraints).build()
            )
        }
    }
}
