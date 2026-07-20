package com.opencall.relay

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class OpenCallApp : Application() {
    companion object {
        const val CHANNEL_RELAY  = "opencall_relay"
        const val CHANNEL_CALL   = "opencall_call"
        const val NOTIF_RELAY_ID = 1001
        const val NOTIF_CALL_ID  = 1002
    }
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_RELAY, "Relay Service", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_CALL, "Incoming Calls", NotificationManager.IMPORTANCE_HIGH).apply { setShowBadge(true) })
    }
}