package com.lucianotoscano.pvppokego

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class PvPPokeGoApp : Application() {
    override fun onCreate() {
        super.onCreate()
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
