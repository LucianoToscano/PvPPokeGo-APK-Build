package com.lucianotoscano.pvppokego.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import com.lucianotoscano.pvppokego.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * Safe default: check daily on unmetered connectivity, without modifying battle data.
 * Remote downloads must not be enabled until a trusted signed/versioned manifest exists.
 */
object HybridSyncScheduler {
    private const val UNIQUE_WORK = "pvppokego-daily-hybrid-data-sync"
    private const val PREFS = "hybrid_data_sync"
    private const val AUTO = "auto"
    private const val WIFI = "wifi_only"

    fun install(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(AUTO, true)
        val wifiOnly = prefs.getBoolean(WIFI, true)
        if (!enabled) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<HybridSyncWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK, ExistingPeriodicWorkPolicy.KEEP, request
        )
    }

    fun configure(context: Context, automatic: Boolean, wifiOnly: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(AUTO, automatic).putBoolean(WIFI, wifiOnly).apply()
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
        install(context)
    }
}

class HybridSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences("hybrid_data_sync", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("auto", true)) return@withContext Result.success()
        val endpoint = BuildConfig.HYBRID_MANIFEST_URL
        if (endpoint.isBlank()) return@withContext Result.success()
        try {
            val manifest = Json.parseToJsonElement(download(endpoint, 512_000).decodeToString()).jsonObject
            require(manifest["schema"]?.jsonPrimitive?.content == "pvppokego-hybrid-v1")
            val version = manifest.getValue("version").jsonPrimitive.content
            val files = manifest.getValue("files").jsonArray
            require(files.size in 5..12)
            val entries = files.map { item ->
                val obj = item.jsonObject
                HybridSnapshotStore.Entry(
                    obj.getValue("name").jsonPrimitive.content,
                    obj.getValue("sha256").jsonPrimitive.content
                )
            }
            require(entries.map { it.name }.toSet().containsAll(
                setOf("gamemaster.json", "pvpoke_cpms.json", "rankings-1500.json",
                    "rankings-2500.json", "rankings-10000.json")
            ))
            val store = HybridSnapshotStore(applicationContext.filesDir.resolve("hybrid-data"))
            if (store.activeVersion() == version || store.snapshot(version) != null) {
                return@withContext Result.success()
            }
            val base = URL(endpoint)
            val downloaded = files.associate { item ->
                val obj = item.jsonObject
                val name = obj.getValue("name").jsonPrimitive.content
                require(name.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,100}")))
                val size = obj.getValue("size").jsonPrimitive.content.toInt()
                require(size in 2..12_000_000)
                val fileUrl = URL(base, name)
                require(fileUrl.protocol == "https" && fileUrl.host == base.host &&
                    fileUrl.port == base.port && fileUrl.path.substringAfterLast('/') == name)
                val bytes = download(fileUrl.toString(), size)
                require(bytes.size == size)
                name to bytes
            }
            val gm = Json.parseToJsonElement(downloaded.getValue("gamemaster.json").decodeToString()).jsonObject
            require(gm.getValue("pokemon").jsonArray.size > 100 &&
                gm.getValue("moves").jsonArray.size > 100)
            for (cp in listOf(1500, 2500, 10000)) {
                require(Json.parseToJsonElement(
                    downloaded.getValue("rankings-$cp.json").decodeToString()
                ).jsonArray.size > 100)
            }
            require(Json.parseToJsonElement(
                downloaded.getValue("pvpoke_cpms.json").decodeToString()
            ).jsonObject.getValue("cpms").jsonArray.size >= 100)
            require(store.stage(version, downloaded, entries))
            prefs.edit().putString("pending_version", version)
                .putLong("last_success_ms", System.currentTimeMillis()).apply()
            Result.success()
        } catch (_: IllegalArgumentException) {
            Result.failure()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun download(address: String, limit: Int): ByteArray {
        val url = URL(address)
        require(url.protocol == "https")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 12_000
            instanceFollowRedirects = false
        }
        try {
            require(connection.responseCode == 200)
            val out = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(out.size() + n <= limit)
                    out.write(buffer, 0, n)
                }
            }
            return out.toByteArray()
        } finally {
            connection.disconnect()
        }
    }
}
