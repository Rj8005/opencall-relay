package com.opencall.relay.offline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * FIX 3: exists purely to anchor process priority for the duration of an offline
 * Wi-Fi-Direct call. Camera/mic/codec/sockets stay owned exactly where they already
 * are (OfflineMediaTransport/OfflineCallManager/OfflineCallActivity) — this service
 * doesn't touch any of them. Without it, the whole call lived inside the Activity's
 * process lifetime, which OEM battery managers (vivo Funtouch, Moto, etc.) can and do
 * kill the moment it's backgrounded, with no crash ever logged. Mirrors the pattern
 * already used by RelayForegroundService elsewhere in this app.
 */
class OfflineCallService : Service() {

    companion object {
        private const val TAG = "OfflineCallService"
        private const val CHANNEL_ID = "offline_call_channel"
        private const val NOTIF_ID = 2001
        const val ACTION_START = "com.opencall.relay.offline.START"
        const val ACTION_STOP = "com.opencall.relay.offline.STOP"
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                releaseWakeLock()
                stopForeground(true)
                stopSelf()
            }
            else -> {
                startForeground(NOTIF_ID, buildNotification())
                acquireWakeLock()
            }
        }
        return START_NOT_STICKY
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenCall:OfflineCall")
            .apply { acquire(30 * 60 * 1000L) }
        Log.d(TAG, "wake lock acquired")
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Offline Call",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Offline Wi-Fi Direct call status"
                setShowBadge(false)
                setSound(null, null)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openPending = PendingIntent.getActivity(
            this, 0, Intent(this, OfflineCallActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Offline call in progress")
            .setContentText("Wi-Fi Direct call active")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setOngoing(true)
            .setContentIntent(openPending)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        Log.d(TAG, "service destroyed")
    }
}
