package com.opencall.relay

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.*
import android.content.SharedPreferences

class RelayWebViewActivity : Activity() {

    companion object {
        const val TAG = "RelayWebView"
        var instance: RelayWebViewActivity? = null
    }

    private lateinit var webView: WebView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentCallId: String? = null
    private var callStateReceiver: CallStateReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this

        // Full screen WebView
        webView = WebView(this)
        setContentView(webView)

        val prefs = getSharedPreferences("opencall", MODE_PRIVATE)
        val serverUrl = prefs.getString("server_url",
            "wss://signal.opencall.space") ?: ""
        val country = detectCountry(
            prefs.getString("user_number", "") ?: "")
        val number = prefs.getString("user_number", "") ?: ""
        val mode = prefs.getString("relay_mode", "both") ?: "both"

        // Configure WebView
        webView.settings.apply {
            javaScriptEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            domStorageEnabled = true
            databaseEnabled = true
        }

        // Add Android bridge interface
        webView.addJavascriptInterface(
            AndroidRelayBridge(serverUrl, country, number, mode),
            "AndroidRelay"
        )

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                // Auto-grant microphone to WebView
                request.grant(request.resources)
                Log.d(TAG, "WebView permission granted: ${request.resources.toList()}")
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                Log.d(TAG, "Page loaded: $url")
                // Initialize relay with Android params
                webView.evaluateJavascript(
                    "initRelay('$serverUrl', '$country', '$number', '$mode')",
                    null
                )
            }
        }

        // Register call state receiver
        callStateReceiver = CallStateReceiver()
        val filter = android.content.IntentFilter().apply {
            addAction(android.telephony.TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        }
        registerReceiver(callStateReceiver, filter)

        CallStateReceiver.isMonitoring = false

        // Load relay page
        webView.loadUrl("https://opencall-server.vercel.app/relay.html")
        Log.d(TAG, "Loading relay page")
    }

    inner class AndroidRelayBridge(
        private val server: String,
        private val country: String,
        private val number: String,
        private val mode: String
    ) {
        @JavascriptInterface
        fun onLog(msg: String) {
            Log.d(TAG, "[JS] $msg")
        }

        @JavascriptInterface
        fun onRegistered() {
            Log.d(TAG, "✅ Relay registered with server")
            mainHandler.post {
                // Update MainActivity UI if visible
            }
        }

        @JavascriptInterface
        fun onRelayCall(dialNumber: String, callId: String, callerIdToShow: String) {
            Log.d(TAG, "📞 onRelayCall: $dialNumber callId: $callId")
            currentCallId = callId
            mainHandler.post {
                // Start watching call state
                CallStateReceiver.isMonitoring = true
                CallStateReceiver.onOffhook = {
                    Log.d(TAG, "✅ GSM OFFHOOK — C answered!")
                    webView.post {
                        webView.evaluateJavascript(
                            "onGSMConnected('$callId')", null)
                    }
                }
                CallStateReceiver.onIdle = {
                    Log.d(TAG, "📴 GSM IDLE — call ended")
                    CallStateReceiver.isMonitoring = false
                    webView.post {
                        webView.evaluateJavascript(
                            "onGSMEnded('$callId')", null)
                    }
                }
                CallStateReceiver.onRinging = {
                    Log.d(TAG, "🔔 GSM RINGING")
                }

                // Dial C
                try {
                    val intent = Intent(Intent.ACTION_CALL).apply {
                        data = Uri.parse("tel:$dialNumber")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                    Log.d(TAG, "✅ Dial intent sent for $dialNumber")
                } catch (e: Exception) {
                    Log.e(TAG, "Dial failed: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun onRelaySMS(targetNumber: String, joinURL: String, callId: String) {
            Log.d(TAG, "💬 onRelaySMS: $targetNumber")
            mainHandler.post {
                try {
                    val smsManager = android.telephony.SmsManager.getDefault()
                    val message = "Free call waiting on OpenCall. Answer: $joinURL"
                    smsManager.sendTextMessage(targetNumber, null, message, null, null)
                    Log.d(TAG, "✅ SMS sent")
                    webView.post {
                        webView.evaluateJavascript(
                            "onSMSSent('$callId', 'ok')", null)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "SMS failed: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun onWebRTCConnected() {
            Log.d(TAG, "✅ WebRTC connected — call is live!")
        }

        @JavascriptInterface
        fun onHangup() {
            Log.d(TAG, "📴 Hangup from server")
            mainHandler.post {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    try {
                        (getSystemService(TELECOM_SERVICE)
                            as android.telecom.TelecomManager).endCall()
                    } catch (e: Exception) {}
                }
            }
        }
    }

    private fun detectCountry(e164: String): String {
        return when {
            e164.startsWith("+1")   -> "US"
            e164.startsWith("+44")  -> "GB"
            e164.startsWith("+91")  -> "IN"
            e164.startsWith("+254") -> "KE"
            e164.startsWith("+234") -> "NG"
            else -> "XX"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        try { unregisterReceiver(callStateReceiver) } catch (e: Exception) {}
        CallStateReceiver.isMonitoring = false
    }
}
