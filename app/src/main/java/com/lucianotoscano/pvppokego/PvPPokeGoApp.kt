package com.lucianotoscano.pvppokego

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.lucianotoscano.pvppokego.data.sync.HybridSyncScheduler
import com.lucianotoscano.pvppokego.data.sync.HybridSnapshotStore
import java.io.File

class PvPPokeGoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // A new process starts without a running battle engine. Activate staged data
        // only here; never swap datasets while a session is running.
        val syncPrefs = getSharedPreferences("hybrid_data_sync", MODE_PRIVATE)
        val pending = syncPrefs.getString("pending_version", null)
        if (pending != null) {
            val activated = runCatching {
                HybridSnapshotStore(File(filesDir, "hybrid-data"))
                    .activateBetweenBattles(pending, battleActive = false)
            }.getOrNull()
            if (activated != null) syncPrefs.edit().remove("pending_version").apply()
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

    companion object {
        const val CHANNEL_ID = "pvppokego_overlay"
    }
}
