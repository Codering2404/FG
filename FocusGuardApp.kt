package com.focusguard.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.google.android.gms.ads.MobileAds
import com.google.firebase.FirebaseApp

class FocusGuardApp : Application() {

    companion object {
        const val CHANNEL_MONITOR  = "fg_monitor"   // persistent service notification
        const val CHANNEL_ALERT    = "fg_alert"     // block/partner alerts
        const val CHANNEL_PARTNER  = "fg_partner"   // incoming partner messages
    }

    override fun onCreate() {
        super.onCreate()
        
        try {
            FirebaseApp.initializeApp(this)
        } catch (e: Exception) {
            // Log error or handle missing google-services.json
            e.printStackTrace()
        }

        try {
            MobileAds.initialize(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        listOf(
            NotificationChannel(CHANNEL_MONITOR, "FocusGuard Active",
                NotificationManager.IMPORTANCE_LOW).apply { description = "Persistent monitoring indicator" },
            NotificationChannel(CHANNEL_ALERT, "Block Alerts",
                NotificationManager.IMPORTANCE_HIGH).apply { description = "Notifications when content is blocked" },
            NotificationChannel(CHANNEL_PARTNER, "Partner Updates",
                NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Messages from accountability partner" }
        ).forEach { nm.createNotificationChannel(it) }
    }
}
