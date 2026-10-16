package com.lucianotoscano.pvppokego

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.lucianotoscano.pvppokego.data.PokemonImageSyncWorker
import com.lucianotoscano.pvppokego.data.sync.HybridSyncScheduler
import com.lucianotoscano.pvppokego.data.sync.HybridSnapshotStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel

class PvPPokeGoApp : Application() {
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingHybridActivation: Deferred<Unit>? = null

    /** Data loads await this before reading assets; disk hashing never blocks main. */
    suspend fun awaitPendingHybridActivation() {
        runCatching { pendingHybridActivation?.await() }
    }

    override fun onCreate() {
        super.onCreate()
        runCatching { PokemonImageSyncWorker.schedule(this) }
        // A process starts with no active battle. Pending hybrid data may be safely
        // activated now, never during an ongoing battle or capture frame.
        val syncPrefs = runCatching { getSharedPreferences("hybrid_data_sync", MODE_PRIVATE) }.getOrNull()
        val pending = runCatching { syncPrefs?.getString("pending_version", null) }.getOrNull()
        if (!pending.isNullOrBlank() && syncPrefs != null) {
            pendingHybridActivation = startupScope.async {
                // Catch inside async so awaiters (GameDataRepository) cannot fail startup.
                val activated = runCatching {
                    HybridSnapshotStore(File(filesDir, "hybrid-data"))
                        .activateBetweenBattles(pending, battleActive = false) != null
                }.getOrDefault(false)
                runCatching {
                    val edit = syncPrefs.edit().remove("pending_version")
                    if (activated) edit.remove("last_error")
                    else edit.putString("last_error", "Snapshot pendente inválido; dados internos mantidos")
                    edit.apply()
                }
            }
        } else if (syncPrefs != null &&
            runCatching { syncPrefs.contains("pending_version") }.getOrDefault(false)) {
            // Wrongly typed/stale pending marker must not poison every launch.
            runCatching { syncPrefs.edit().remove("pending_version").apply() }
        }
        runCatching { HybridSyncScheduler.install(this) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PvPPokeGo Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Captura visual e overlay do PvPPokeGo"
                setShowBadge(false)
            }
            // Invalid system state must not prevent opening the main Activity.
            runCatching {
                getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
            }
        }
    }

    override fun onTerminate() {
        startupScope.cancel()
        super.onTerminate()
    }

    companion object {
        const val CHANNEL_ID = "pvppokego_overlay"
    }
}
