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
        pendingHybridActivation?.await()
    }

    override fun onCreate() {
        super.onCreate()
        PokemonImageSyncWorker.schedule(this)
        // A process starts with no active battle. Pending hybrid data may be safely
        // activated now, never during an ongoing battle or capture frame.
        val syncPrefs = getSharedPreferences("hybrid_data_sync", MODE_PRIVATE)
        val pending = syncPrefs.getString("pending_version", null)
        if (pending != null) {
            pendingHybridActivation = startupScope.async {
                val switched = runCatching {
                    HybridSnapshotStore(File(filesDir, "hybrid-data"))
                        .activateBetweenBattles(pending, battleActive = false)
                }.getOrNull()
                if (switched != null) {
                    syncPrefs.edit().remove("pending_version").remove("last_error").apply()
                } else {
                    syncPrefs.edit().putString("last_error", "Snapshot pendente inválido").apply()
                }
            }
        }
        HybridSyncScheduler.install(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PvPPokeGo Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Captura visual e overlay do PvPPokeGo"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
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
