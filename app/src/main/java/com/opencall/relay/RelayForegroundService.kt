package com.opencall.relay

import android.app.*
import android.content.Intent
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat

class RelayForegroundService : Service() {

    companion object {
        const val TAG = "RelayFGService"
        const val CHANNEL_ID = "ocp_relay_channel"
        const val NOTIF_ID = 1001
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        var instance: RelayForegroundService? = null
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var relayId: String = ""
    private var relayNumber: String = ""
    private var webSocket: okhttp3.WebSocket? = null
    private val wsClient = okhttp3.OkHttpClient.Builder()
        .pingInterval(2, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // Rate limiting: max 1 SMS/sec, max 60 SMS/min
    private var lastSmsSentMs = 0L
    private val smsMinuteTimestamps = ArrayDeque<Long>()

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIF_ID, buildNotification("Relay starting..."))
                startNativeRelay()
            }
            ACTION_STOP -> {
                webSocket?.close(1000, "Service stopped")
                stopForeground(true)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startNativeRelay() {
        val prefs = getSharedPreferences("opencall", MODE_PRIVATE)
        val serverUrl = prefs.getString("server_url",
            "wss://signal.opencall.space") ?: ""
        val number = prefs.getString("user_number", "") ?: ""
        val mode = prefs.getString("relay_mode", "both") ?: "both"
        val country = detectCountry(number)

        relayNumber = number
        relayId = "relay_${System.currentTimeMillis()}_$country"

        connectWebSocket(serverUrl, country, number, mode)

        updateNotification("Relay active 🟢")
        Log.d(TAG, "Native relay started")
    }

    private fun connectWebSocket(server: String, country: String,
            number: String, mode: String) {
        val request = okhttp3.Request.Builder().url(server).build()

        webSocket = wsClient.newWebSocket(request, object : okhttp3.WebSocketListener() {
            override fun onOpen(ws: okhttp3.WebSocket, response: okhttp3.Response) {
                Log.d(TAG, "✅ WebSocket connected")
                updateNotification("Relay connected 🟢")

                val msg = org.json.JSONObject().apply {
                    put("type", "register_relay")
                    put("country", country)
                    put("relay_mode", mode)
                    put("ocp_address", relayId)
                    put("number", number)
                    put("capacity", 3)
                    put("caps", org.json.JSONArray().apply { put("sms") })
                }
                ws.send(msg.toString())
            }

            override fun onMessage(ws: okhttp3.WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosed(ws: okhttp3.WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closed: $code $reason")
                mainHandler.postDelayed({ connectWebSocket(server, country, number, mode) }, 3000)
            }

            override fun onFailure(ws: okhttp3.WebSocket, t: Throwable,
                    response: okhttp3.Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                mainHandler.postDelayed({ connectWebSocket(server, country, number, mode) }, 3000)
            }
        })
    }

    private fun handleMessage(text: String) {
        try {
            val msg = org.json.JSONObject(text)
            val type = msg.optString("type")
            Log.d(TAG, "← $type")

            when (type) {
                "relay_registered" -> {
                    Log.d(TAG, "✅ Registered as relay")
                    updateNotification("Relay active 🟢 — ready for SMS")
                }

                "relay_sms" -> {
                    val threadId = msg.optString("threadId")
                    val to = msg.getString("to")
                    val body = msg.getString("text")

                    if (!checkRateLimit()) {
                        Log.w(TAG, "Rate limit — rejecting SMS to $to")
                        sendWsStatus(threadId, to, "failed", "rate_limited")
                        return
                    }

                    try {
                        @Suppress("DEPRECATION")
                        val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                            applicationContext.getSystemService(
                                android.telephony.SmsManager::class.java)
                        else
                            android.telephony.SmsManager.getDefault()

                        val parts = sms.divideMessage(body)
                        sms.sendMultipartTextMessage(to, null, parts, null, null)
                        Log.d(TAG, "✅ SMS sent to $to (${parts.size} part(s))")
                        sendWsStatus(threadId, to, "sent", null)
                        updateNotification("Relay active 🟢 — SMS sent")
                    } catch (e: Exception) {
                        Log.e(TAG, "SMS send failed: ${e.message}")
                        sendWsStatus(threadId, to, "failed", e.message)
                    }
                }

                "ping" -> webSocket?.send("""{"type":"pong"}""")
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleMessage error: ${e.message}")
        }
    }

    private fun sendWsStatus(threadId: String, to: String,
            status: String, error: String?) {
        val msg = org.json.JSONObject().apply {
            put("type", "relay_sms_status")
            put("threadId", threadId)
            put("to", to)
            put("status", status)
            if (error != null) put("error", error)
        }
        webSocket?.send(msg.toString())
    }

    fun forwardIncomingSms(from: String, text: String) {
        val msg = org.json.JSONObject().apply {
            put("type", "relay_sms_in")
            put("from", from)
            put("text", text)
            put("relayId", relayId)
        }
        webSocket?.send(msg.toString())
        Log.d(TAG, "→ relay_sms_in from $from")
    }

    // Returns true if within both rate limits and records the send timestamp.
    private fun checkRateLimit(): Boolean {
        val now = System.currentTimeMillis()

        if (now - lastSmsSentMs < 1_000) return false

        // Prune timestamps older than 60 seconds
        while (smsMinuteTimestamps.isNotEmpty() &&
               now - smsMinuteTimestamps.first() > 60_000) {
            smsMinuteTimestamps.removeFirst()
        }
        if (smsMinuteTimestamps.size >= 60) return false

        lastSmsSentMs = now
        smsMinuteTimestamps.addLast(now)
        return true
    }

    private fun detectCountry(e164: String): String {
        return when {
            e164.startsWith("+1")   -> "US"
            e164.startsWith("+44")  -> "GB"
            e164.startsWith("+91")  -> "IN"
            e164.startsWith("+254") -> "KE"
            e164.startsWith("+234") -> "NG"
            e164.startsWith("+61")  -> "AU"
            e164.startsWith("+49")  -> "DE"
            e164.startsWith("+33")  -> "FR"
            e164.startsWith("+27")  -> "ZA"
            e164.startsWith("+55")  -> "BR"
            else -> "XX"
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OCP Relay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "OpenCall relay node status"
                setShowBadge(false)
                setSound(null, null)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, RelayForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenCall Relay")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPending)
            .build()
    }

    fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        webSocket?.close(1000, "destroyed")
        Log.d(TAG, "Service destroyed")
    }
}
