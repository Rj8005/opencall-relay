package com.opencall.relay.offline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * FIX 3: exists purely to anchor process priority for the duration of an offline
 * Wi-Fi-Direct group session. Camera/mic/codec/sockets stay owned exactly where they
 * already are (OfflineMediaTransport/OfflineCallActivity) — this service
 * doesn't touch any of them. Without it, the whole session lived inside the
 * Activity's process lifetime, which OEM battery managers (vivo Funtouch, Moto, etc.)
 * can and do kill the moment it's backgrounded, with no crash ever logged. Mirrors the
 * pattern already used by RelayForegroundService elsewhere in this app.
 *
 * IDLE-SESSION FIX: this used to only guard CPU wakefulness (PARTIAL_WAKE_LOCK), which
 * does nothing for the WiFi radio itself — on screen-off, WiFi power-save can still
 * suspend the WiFi Direct group's link even with the CPU awake, which is what killed
 * an idle-on-roster (not-in-a-call) session. Now also holds a WifiLock
 * (FULL_LOW_LATENCY / FULL_HIGH_PERF) for the same lifetime as the wake lock. The wake
 * lock itself is also no longer time-bounded — a 30-minute timed acquire was a latent
 * bug: it silently expired mid-session for any idle period longer than that, with
 * nothing re-acquiring it.
 */
class OfflineCallService : Service() {

    companion object {
        private const val TAG = "OfflineCallService"
        private const val CHANNEL_ID = "offline_call_channel"
        private const val NOTIF_ID = 2001
        const val ACTION_START = "com.opencall.relay.offline.START"
        const val ACTION_STOP = "com.opencall.relay.offline.STOP"

        // PHASE 6 TRACK B: same "process-wide instance pointer" pattern
        // OfflineMediaTransport already uses (see its activeInstance/
        // stopOrphanedInstance) — SosBeaconMode needs to duty-cycle THIS
        // service's own WifiLock (a second, independent lock would do nothing,
        // since this service's lock alone already keeps the radio out of
        // power-save regardless of what anything else holds).
        @Volatile private var activeInstance: OfflineCallService? = null

        /** No-op if the service isn't running (session already ended). */
        fun releaseWifiLockForBeacon() = activeInstance?.releaseWifiLock()

        /** No-op if the service isn't running. */
        fun reacquireWifiLockForBeacon() = activeInstance?.acquireWifiLock()
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                releaseWakeLock()
                releaseWifiLock()
                stopForeground(true)
                stopSelf()
            }
            else -> {
                startForeground(NOTIF_ID, buildNotification())
                acquireWakeLock()
                acquireWifiLock()
            }
        }
        return START_NOT_STICKY
    }

    /** FIX 2: untimed — released explicitly in the stop path (ACTION_STOP) and in
     *  onDestroy as a safety net, never by its own timeout. A timed acquire here was a
     *  latent bug: it silently expired mid-session (30 min) with nothing re-acquiring
     *  it, long before any explicit stop. */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenCall:OfflineCall")
            .apply { acquire() }
        Log.d(TAG, "wake lock acquired")
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    /** FIX 1 (primary cause): PARTIAL_WAKE_LOCK alone does nothing for the WiFi radio —
     *  on screen-off, WiFi power-save can still suspend a WiFi Direct group's link
     *  (worse for the GO, serving multiple STAs) even with the CPU held awake. Held for
     *  the same lifetime as the wake lock (whole group session, not just an active
     *  call — see class doc). FULL_LOW_LATENCY (API 29+) keeps the radio in active
     *  mode with the lowest latency; FULL_HIGH_PERF is the pre-Q equivalent. */
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val (mode, modeLabel) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY to "FULL_LOW_LATENCY"
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF to "FULL_HIGH_PERF"
        }
        wifiLock = wifiManager.createWifiLock(mode, "OpenCall:Mesh").apply { acquire() }
        Log.d("OFFTRACE", "LOCK: wifi lock acquired mode=$modeLabel")
    }

    private fun releaseWifiLock() {
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
                Log.d("OFFTRACE", "LOCK: wifi lock released")
            }
        } catch (_: Exception) {}
        wifiLock = null
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
        if (activeInstance === this) activeInstance = null
        releaseWakeLock()
        releaseWifiLock()
        Log.d(TAG, "service destroyed")
    }
}
