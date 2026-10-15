package com.lucianotoscano.pvppokego.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import com.lucianotoscano.pvppokego.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** Daily background updates, outside the battle hot path. */
object HybridSyncScheduler {
    private const val UNIQUE_WORK = "pvppokego-daily-hybrid-data-sync"
    private const val PREFS = "hybrid_data_sync"

    fun install(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("auto", true)) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
            return
        }
        val wifiOnly = prefs.getBoolean("wifi_only", true)
        val request = PeriodicWorkRequestBuilder<HybridSyncWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(
                        if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                    ).build()
            ).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request
        )
    }

    fun configure(context: Context, automatic: Boolean, wifiOnly: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("auto", automatic).putBoolean("wifi_only", wifiOnly).apply()
        install(context)
    }
}

class HybridSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    companion object {
        /** Fixed trusted repository. Never accept arbitrary remote URLs from the manifest. */
        const val TRUSTED_MANIFEST_URL =
            "https://raw.githubusercontent.com/LucianoToscano/PvPPokeGo-APK-Build/hybrid-feed/manifest.json"
        private val REQUIRED = setOf(
            "gamemaster.json", "pvpoke_cpms.json",
            "rankings-1500.json", "rankings-2500.json", "rankings-10000.json",
            "pvpoke_snapshot.json", "pvpoke_LICENSE.txt"
        )
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences("hybrid_data_sync", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("auto", true)) return@withContext Result.success()
        val endpoint = BuildConfig.HYBRID_MANIFEST_URL
        if (endpoint.isBlank()) return@withContext Result.success()
        if (prefs.getBoolean("wifi_only", true)) {
            val manager = applicationContext.getSystemService(ConnectivityManager::class.java)
            val caps = manager?.activeNetwork?.let { manager.getNetworkCapabilities(it) }
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
                return@withContext Result.retry()
            }
        }
        try {
            require(endpoint == TRUSTED_MANIFEST_URL)
            val manifest = Json.parseToJsonElement(download(endpoint, 512_000).decodeToString())
                .jsonObject
            require(manifest["schema"]?.jsonPrimitive?.content == "pvppokego-hybrid-v1")
            val version = manifest.getValue("version").jsonPrimitive.content
            require(version.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,100}")))
            val files = manifest.getValue("files").jsonArray
            require(files.size == REQUIRED.size)
            val entries = files.map { item ->
                val obj = item.jsonObject
                HybridSnapshotStore.Entry(
                    obj.getValue("name").jsonPrimitive.content,
                    obj.getValue("sha256").jsonPrimitive.content
                )
            }
            require(entries.map { it.name }.toSet() == REQUIRED)

            val store = HybridSnapshotStore(applicationContext.filesDir.resolve("hybrid-data"))
            if (store.activeVersion() == version) return@withContext Result.success()
            if (store.snapshot(version) != null) {
                prefs.edit().putString("pending_version", version).apply()
                return@withContext Result.success()
            }
            var total = 0L
            val downloaded = files.associate { item ->
                val obj = item.jsonObject
                val name = obj.getValue("name").jsonPrimitive.content
                require(name in REQUIRED)
                val size = obj.getValue("size").jsonPrimitive.content.toInt()
                require(size in 2..35_000_000)
                total += size
                require(total <= 100_000_000L)
                val target = endpoint.substringBeforeLast('/') + "/" + name
                val bytes = download(target, size)
                require(bytes.size == size)
                name to bytes
            }

            val gm = Json.parseToJsonElement(downloaded.getValue("gamemaster.json").decodeToString())
                .jsonObject
            require(gm.getValue("pokemon").jsonArray.size > 100 &&
                gm.getValue("moves").jsonArray.size > 100)
            for (cp in listOf(1500, 2500, 10000)) {
                require(Json.parseToJsonElement(
                    downloaded.getValue("rankings-" + cp + ".json").decodeToString()
                ).jsonArray.size > 100)
            }
            require(Json.parseToJsonElement(
                downloaded.getValue("pvpoke_cpms.json").decodeToString()
            ).jsonObject.getValue("cpms").jsonArray.size >= 100)
            require(downloaded.getValue("pvpoke_LICENSE.txt").size >= 50)
            require(Json.parseToJsonElement(
                downloaded.getValue("pvpoke_snapshot.json").decodeToString()
            ).jsonObject["pvpokeRef"] != null)

            require(store.stage(version, downloaded, entries))
            // Do not activate in this background worker. Only the next process starts it.
            prefs.edit().putString("pending_version", version)
                .putLong("last_success_ms", System.currentTimeMillis()).apply()
            Result.success()
        } catch (_: IllegalArgumentException) {
            Result.failure() // Reject invalid, unsupported or corrupted data.
        } catch (_: Exception) {
            Result.retry() // Temporary network, disk or server failure.
        }
    }

    private fun download(address: String, limit: Int): ByteArray {
        val url = URL(address)
        require(url.protocol == "https" && url.host == "raw.githubusercontent.com")
        require(url.toExternalForm().startsWith(
            "https://raw.githubusercontent.com/LucianoToscano/PvPPokeGo-APK-Build/hybrid-feed/"
        ))
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 12_000
            instanceFollowRedirects = false
        }
        try {
            if (connection.responseCode != 200) {
                throw IOException("Trusted feed unavailable: HTTP " + connection.responseCode)
            }
            val out = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(out.size() + count <= limit)
                    out.write(buffer, 0, count)
                }
            }
            return out.toByteArray()
        } finally {
            connection.disconnect()
        }
    }
}
