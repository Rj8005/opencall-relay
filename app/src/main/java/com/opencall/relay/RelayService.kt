package com.opencall.relay

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class RelayService : Service() {

    companion object {
        var instance: RelayService? = null
        private const val TAG = "RelayService"
        const val ACTION_START     = "com.opencall.relay.START"
        const val ACTION_STOP      = "com.opencall.relay.STOP"
        const val ACTION_HANGUP    = "com.opencall.relay.HANGUP"
        const val ACTION_RELAY_SMS = "com.opencall.relay.RELAY_SMS"
        const val ACTION_SMS_SENT  = "com.opencall.relay.SMS_SENT"
        const val ACTION_STOPPED   = "com.opencall.relay.STOPPED"
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_AREA_CODE  = "area_code"
        const val EXTRA_COUNTRY    = "country"
        const val EXTRA_RELAY_MODE = "relay_mode"
        const val EXTRA_CALL_ID    = "call_id"
        const val DEFAULT_SERVER   = "wss://signal.opencall.space"
        var isRunning  = false
        var serverUrl  = ""
        var statusText = "Stopped"
    }

    // General-purpose HTTP client (DHT, etc.)
    private val okClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0,  TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // Dedicated WebSocket client — never shared with coroutines so a scope
    // cancellation cannot tear down the signalling channel.
    private val wsClient = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0,  TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket:    WebSocket? = null
    private var reconnectJob: Job?       = null
    private var keepaliveJob: Job?       = null
    internal val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection:        PeerConnection?        = null
    private var localAudioTrack:       AudioTrack?            = null
    private var eglBase:               EglBase?               = null
    private var audioBridge:           AudioBridge?           = null
    private var jadm:                  JavaAudioDeviceModule? = null
    private val webrtcPlaybackQueue = LinkedBlockingQueue<ByteArray>(50)
    private val gsmPcmQueue         = LinkedBlockingQueue<ByteArray>(50)
    @Volatile private var captureFrames = 0
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private var currentCallId:      String? = null
    private var currentDialedNumber = ""
    private var relayReadySent:     Boolean = false
    private var callStateReceiver:  CallStateReceiver? = null
    private var wakeLock:           PowerManager.WakeLock? = null
    private var isRegistered = false

    private var areaCode  = "+91"
    private var country   = "IN"
    private var relayMode = "both"

    private val handledMessages = mutableSetOf<String>()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenCall:Relay")
            .apply { acquire(10 * 60 * 1000L) }
        initWebRTC()
        registerCallReceiver()
        Log.d(TAG, "✅ Relay service started with call receiver")
    }

    private fun registerCallReceiver() {
        callStateReceiver = CallStateReceiver()
        val filter = android.content.IntentFilter().apply {
            addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            addAction("android.intent.action.NEW_OUTGOING_CALL")
        }
        registerReceiver(callStateReceiver, filter)
        Log.d(TAG, "✅ CallStateReceiver registered")
    }

    private fun unregisterCallReceiver() {
        try {
            callStateReceiver?.let { unregisterReceiver(it) }
            callStateReceiver = null
            Log.d(TAG, "✅ CallStateReceiver unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "unregister receiver error: ${e.message}")
        }
    }

    private fun initWebRTC() {
        try {
            if (eglBase == null) eglBase = EglBase.create()
            if (peerConnectionFactory == null) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions
                        .builder(applicationContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
                val adm = JavaAudioDeviceModule.builder(applicationContext)
                    .setAudioSource(android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setSampleRate(8000)
                    .setUseHardwareAcousticEchoCanceler(false)
                    .setUseHardwareNoiseSuppressor(false)
                    .setSamplesReadyCallback { samples ->
                        val n = captureFrames++
                        if (n % 100 == 0) {
                            val buf = samples.data
                            var max = 0
                            var i = 0
                            while (i + 1 < buf.size) {
                                val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
                                val a = if (s < 0) -s else s
                                if (a > max) max = a
                                i += 2
                            }
                            if (max > 300) Log.d(TAG, "GSM capture active frame=$n maxLevel=$max")
                        }
                    }
                    .setPlaybackSamplesReadyCallback { samples ->
                        val copy = samples.data.copyOf()
                        if (!webrtcPlaybackQueue.offer(copy)) {
                            webrtcPlaybackQueue.poll()
                            webrtcPlaybackQueue.offer(copy)
                        }
                    }
                    .createAudioDeviceModule()
                jadm = adm
                peerConnectionFactory = PeerConnectionFactory.builder()
                    .setOptions(PeerConnectionFactory.Options())
                    .setAudioDeviceModule(adm)
                    .createPeerConnectionFactory()
                adm.release()
                Log.d(TAG, "✅ PeerConnectionFactory initialized with JADM")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ initWebRTC failed: ${e.message}")
            e.printStackTrace()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_SERVER_URL) ?: DEFAULT_SERVER
                areaCode  = intent.getStringExtra(EXTRA_AREA_CODE)  ?: "+91"
                country   = intent.getStringExtra(EXTRA_COUNTRY)    ?: "IN"
                relayMode = intent.getStringExtra(EXTRA_RELAY_MODE)
                    ?: getSharedPreferences("opencall", Context.MODE_PRIVATE)
                        .getString("relay_mode", "both") ?: "both"
                serverUrl = url
                isRunning = true
                statusText = "Connecting..."
                startForeground(OpenCallApp.NOTIF_RELAY_ID, buildNotif("Connecting…"))
                connectWS(url)
                return START_STICKY
            }
            ACTION_STOP -> {
                stopRelay()
                return START_NOT_STICKY
            }
            ACTION_HANGUP -> endCurrentCall()
            ACTION_RELAY_SMS -> {
                val targetNumber = intent.getStringExtra("targetNumber") ?: return START_NOT_STICKY
                val joinURL      = intent.getStringExtra("joinURL")      ?: ""
                val callId       = intent.getStringExtra("callId")       ?: ""
                sendSMSInvite(targetNumber, joinURL, callId)
            }
            ACTION_SMS_SENT -> {
                val callId = intent.getStringExtra("callId") ?: ""
                val status = intent.getStringExtra("status") ?: "ok"
                webSocket?.send(JSONObject().apply {
                    put("type",   "sms_sent")
                    put("callId", callId)
                    put("status", status)
                }.toString())
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        unregisterCallReceiver()
        CallStateReceiver.isMonitoring = false
        cleanupWebRTC()
        webSocket?.close(1000, "Service destroyed")
        Log.d(TAG, "RelayService destroyed")
        stopRelay()
        wakeLock?.let { if (it.isHeld) it.release() }
        instance = null
        scope.cancel()
    }

    // ── WebSocket connection ──────────────────────────────────────────────────

    private fun connectWS(url: String) {
        updateNotif("Connecting…")
        try {
            webSocket = wsClient.newWebSocket(       // wsClient — never cancelled by scope
                Request.Builder().url(url).build(),
                object : WebSocketListener() {

                    override fun onOpen(ws: WebSocket, response: Response) {
                        Log.i(TAG, "WS open — registering relay")
                        isRunning = true
                        sendRegister(ws)
                        startKeepalive(ws)
                        updateNotif("Connected — waiting for calls")
                        statusText = "Connected"
                    }

                    override fun onMessage(ws: WebSocket, text: String) {
                        try { handleMsg(JSONObject(text)) }
                        catch (e: Exception) { Log.e(TAG, "JSON parse: ${e.message}") }
                    }

                    override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                        Log.e(TAG, "❌ WebSocket failure: ${t.message}")
                        isRunning = false
                        scope.launch {
                            delay(3_000)
                            Log.d(TAG, "🔄 Reconnecting after failure...")
                            connectWS(serverUrl)
                        }
                    }

                    override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                        Log.w(TAG, "⚠️ WebSocket closed: $reason")
                        isRunning = false
                        scope.launch {
                            delay(3_000)
                            if (currentCallId == null) {
                                Log.d(TAG, "🔄 Reconnecting WebSocket...")
                                connectWS(serverUrl)
                            }
                        }
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "connectWS exception: ${e.message}")
            showError("Connection error: ${e.message}")
            scheduleReconnect(url)
        }
    }

    private fun sendRegister(ws: WebSocket) {
        val prefs      = getSharedPreferences("opencall", Context.MODE_PRIVATE)
        val userName   = prefs.getString("user_name",   "Relay") ?: "Relay"
        val userNumber = normalizeNumber(prefs.getString("user_number", "") ?: "")
        val relayMode  = prefs.getString("relay_mode",  "both")  ?: "both"
        val country    = detectCountry(userNumber)
        val ocpAddress = getRelayId()

        Log.d(TAG, "📡 Registering — country: $country  mode: $relayMode  ocp: $ocpAddress")

        ws.send(JSONObject().apply {
            put("type",        "register_relay")
            put("name",        userName)
            put("number",      userNumber)
            put("country",     country)
            put("relay_mode",  relayMode)
            put("ocp_address", ocpAddress)
            put("capacity",    3)
        }.toString())
    }

    private fun startKeepalive(ws: WebSocket) {
        keepaliveJob?.cancel()
        keepaliveJob = scope.launch {
            while (isActive) {
                delay(30_000)
                try {
                    ws.send(JSONObject().apply { put("type", "ping") }.toString())
                } catch (e: Exception) {
                    Log.w(TAG, "keepalive send failed: ${e.message}")
                    break
                }
            }
        }
    }

    private fun scheduleReconnect(url: String) {
        updateNotif("Disconnected — retrying in 5 s")
        statusText = "Reconnecting…"
        if (!isRunning) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(5_000)
            if (isRunning) connectWS(url)
        }
    }

    // ── Message dispatch ──────────────────────────────────────────────────────

    private fun handleMsg(msg: JSONObject) {
        val msgId = msg.optString("id", "")
        if (msgId.isNotEmpty()) {
            if (handledMessages.contains(msgId)) {
                Log.d(TAG, "Duplicate message ignored: $msgId")
                return
            }
            handledMessages.add(msgId)
            if (handledMessages.size > 1000) handledMessages.clear()
        }

        when (msg.optString("type")) {

            "relay_registered" -> {
                Log.d(TAG, "✅ RELAY REGISTERED with server successfully")
                Log.d(TAG, "Server response: $msg")
                isRegistered = true
                updateNotif("Relay active — ready to bridge calls")
                statusText = "Active"
                registerInDHT(areaCode, country)
                sendBroadcast(Intent("com.opencall.relay.REGISTERED"))
            }

            "error" -> {
                val reason = msg.optString("reason", "unknown")
                Log.e(TAG, "❌ SERVER ERROR: $reason")
                updateNotif("Error: $reason")
            }

            "relay_call" -> {
                try {
                    val callId     = msg.getString("callId")
                    val dialNumber = msg.getString("dialNumber")

                    Log.d(TAG, "📞 relay_call received: $dialNumber callId: $callId")
                    currentCallId = callId

                    if (checkSelfPermission(android.Manifest.permission.CALL_PHONE)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        Log.e(TAG, "❌ No CALL_PHONE permission")
                        webSocket?.send(JSONObject().apply {
                            put("type",   "relay_error")
                            put("callId", callId)
                            put("reason", "no_call_permission")
                        }.toString())
                        return
                    }

                    Log.d(TAG, "📱 Dialing $dialNumber")
                    startActivity(Intent(Intent.ACTION_CALL).apply {
                        data  = android.net.Uri.parse("tel:$dialNumber")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                    Log.d(TAG, "✅ Dial intent fired")

                    CallStateReceiver.isMonitoring = true
                    CallStateReceiver.lastState = TelephonyManager.CALL_STATE_IDLE

                    CallStateReceiver.onRinging = {
                        Log.d(TAG, "🔔 C's phone is ringing! callId: $callId")
                        webSocket?.send(JSONObject().apply {
                            put("type",   "relay_ringing")
                            put("callId", callId)
                        }.toString())
                        Log.d(TAG, "📤 relay_ringing sent")
                    }

                    CallStateReceiver.onOffhook = {
                        Log.d(TAG, "✅ OFFHOOK — C answered")
                        webSocket?.send(JSONObject().apply {
                            put("type",   "relay_ready")
                            put("callId", callId)
                            put("status", "connected")
                        }.toString())
                        Log.d(TAG, "📤 relay_ready SENT")

                        startAudioBridge()

                        android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed({
                                try {
                                    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                                    am.mode = AudioManager.MODE_IN_COMMUNICATION
                                    Log.d(TAG, "Audio mode → MODE_IN_COMMUNICATION")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Audio mode error: ${e.message}")
                                }
                            }, 2000)
                    }

                    CallStateReceiver.onIdle = {
                        Log.d(TAG, "📴 IDLE — call ended. callId: $callId")
                        audioBridge?.stop()
                        audioBridge = null
                        webrtcPlaybackQueue.clear()
                        gsmPcmQueue.clear()
                        try {
                            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                            am.mode = AudioManager.MODE_NORMAL
                            Log.d(TAG, "Audio mode → MODE_NORMAL")
                        } catch (e: Exception) {
                            Log.e(TAG, "Audio restore error: ${e.message}")
                        }
                        CallStateReceiver.isMonitoring = false
                        CallStateReceiver.onOffhook = null
                        CallStateReceiver.onIdle    = null
                        CallStateReceiver.onRinging = null
                        webSocket?.send(JSONObject().apply {
                            put("type",   "relay_call_ended")
                            put("callId", callId)
                        }.toString())
                        Log.d(TAG, "📤 relay_call_ended sent")
                        currentCallId = null
                        cleanupWebRTC()
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "❌ relay_call crash: ${e.message}")
                    e.printStackTrace()
                    try {
                        webSocket?.send(JSONObject().apply {
                            put("type",   "relay_error")
                            put("callId", msg.optString("callId", "unknown"))
                            put("reason", "crash: ${e.message}")
                        }.toString())
                    } catch (e2: Exception) {
                        Log.e(TAG, "Could not send error to server: ${e2.message}")
                    }
                }
            }

            "start_webrtc" -> {
                val callId = msg.getString("callId")
                Log.d(TAG, "start_webrtc — setting up WebRTC as answerer")
                setupWebRTCAnswerer(callId)
            }

            "sdp_offer" -> {
                val callId = msg.optString("callId", currentCallId ?: "")
                Log.d(TAG, "sdp_offer received callId=$callId")

                mainHandler.post {
                    try {
                        if (peerConnection == null) {
                            Log.e(TAG, "peerConnection null — setting up now")
                            setupWebRTCAnswerer(callId)
                            mainHandler.postDelayed({
                                handleSdpOffer(msg, callId)
                            }, 500)
                            return@post
                        }

                        handleSdpOffer(msg, callId)

                    } catch (e: Exception) {
                        Log.e(TAG, "sdp_offer handler crash: ${e.message}")
                        e.printStackTrace()
                    }
                }
            }

            "ice" -> {
                mainHandler.post {
                    try {
                        val c = msg.optJSONObject("candidate") ?: return@post
                        peerConnection?.addIceCandidate(IceCandidate(
                            c.getString("sdpMid"),
                            c.getInt("sdpMLineIndex"),
                            c.getString("candidate")
                        ))
                        Log.d(TAG, "ICE candidate added")
                    } catch (e: Exception) {
                        Log.e(TAG, "ICE add failed: ${e.message}")
                    }
                }
            }

            "relay_hangup" -> {
                Log.d(TAG, "🔴 Hangup received from server")
                endCurrentCall()
            }

            "pong" -> Log.d(TAG, "pong")

            else -> Log.d(TAG, "unhandled: ${msg.optString("type")}")
        }
    }

    // ── WebRTC answerer ───────────────────────────────────────────────────────

    private fun setupWebRTCAnswerer(callId: String) {
        mainHandler.post {
            try {
                Log.d(TAG, "setupWebRTCAnswerer START")

                if (peerConnectionFactory == null) initWebRTC()

                val iceServers = listOf(
                    PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                        .createIceServer(),
                    PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302")
                        .createIceServer(),
                    PeerConnection.IceServer.builder("turn:node.opencall.space:3478")
                        .setUsername("ocp")
                        .setPassword("opencall2026")
                        .createIceServer()
                )
                val config = PeerConnection.RTCConfiguration(iceServers)

                peerConnection = peerConnectionFactory!!.createPeerConnection(
                    config,
                    object : PeerConnection.Observer {
                        override fun onIceCandidate(c: IceCandidate) {
                            mainHandler.post {
                                try {
                                    webSocket?.send(JSONObject().apply {
                                        put("type", "ice")
                                        put("callId", callId)
                                        put("candidate", JSONObject().apply {
                                            put("sdpMid", c.sdpMid)
                                            put("sdpMLineIndex", c.sdpMLineIndex)
                                            put("candidate", c.sdp)
                                        })
                                    }.toString())
                                } catch (e: Exception) { Log.e(TAG, "ICE send: ${e.message}") }
                            }
                        }
                        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                            Log.d(TAG, "ICE: $s")
                        }
                        override fun onConnectionChange(s: PeerConnection.PeerConnectionState?) {
                            Log.d(TAG, "PC: $s")
                        }
                        override fun onIceConnectionReceivingChange(b: Boolean) {}
                        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
                        override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
                        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
                        override fun onAddStream(s: MediaStream?) {}
                        override fun onRemoveStream(s: MediaStream?) {}
                        override fun onDataChannel(d: DataChannel?) {}
                        override fun onRenegotiationNeeded() {}
                    }
                )!!

                Log.d(TAG, "PeerConnection created")

            } catch (e: Exception) {
                Log.e(TAG, "setupWebRTCAnswerer CRASH: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun handleSdpOffer(json: JSONObject, callId: String) {
        try {
            val sdpObj = json.getJSONObject("sdp")
            val sdp = SessionDescription(
                SessionDescription.Type.fromCanonicalForm(sdpObj.getString("type")),
                sdpObj.getString("sdp")
            )

            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    Log.d(TAG, "Remote SDP set")
                    mainHandler.post { addTrackAndAnswer(callId) }
                }
                override fun onSetFailure(e: String?) {
                    Log.e(TAG, "setRemote failed: $e")
                }
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(p0: String?) {}
            }, sdp)

        } catch (e: Exception) {
            Log.e(TAG, "handleSdpOffer crash: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun addTrackAndAnswer(callId: String) {
        try {
            Log.d(TAG, "addTrackAndAnswer START")

            if (localAudioTrack == null) {
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
                    mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "false"))
                    mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl",  "false"))
                }
                val source = peerConnectionFactory?.createAudioSource(constraints)
                localAudioTrack = peerConnectionFactory?.createAudioTrack("audio0", source)
                localAudioTrack?.setEnabled(true)
                peerConnection?.addTrack(localAudioTrack, listOf("s0"))
                Log.d(TAG, "Audio track added")
            }

            peerConnection?.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(answer: SessionDescription) {
                    Log.d(TAG, "Answer created")
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            Log.d(TAG, "Local SDP set — sending answer")
                            try {
                                val msg = JSONObject().apply {
                                    put("type", "sdp_answer")
                                    put("callId", callId)
                                    put("sdp", JSONObject().apply {
                                        put("type", answer.type.canonicalForm())
                                        put("sdp", answer.description)
                                    })
                                }
                                webSocket?.send(msg.toString())
                                Log.d(TAG, "SDP answer sent")

                            } catch (e: Exception) {
                                Log.e(TAG, "Send answer crash: ${e.message}")
                                e.printStackTrace()
                            }
                        }
                        override fun onSetFailure(e: String?) {
                            Log.e(TAG, "setLocal failed: $e")
                        }
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, answer)
                }
                override fun onCreateFailure(e: String?) {
                    Log.e(TAG, "createAnswer failed: $e")
                }
                override fun onSetSuccess() {}
                override fun onSetFailure(p0: String?) {}
            }, MediaConstraints())

        } catch (e: Exception) {
            Log.e(TAG, "addTrackAndAnswer CRASH: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun startAudioBridge() {
        audioBridge?.stop()
        val bridge = AudioBridge(applicationContext)
        bridge.onGSMAudio    = { pcm -> gsmPcmQueue.offer(pcm) }
        bridge.getWebRTCAudio = { webrtcPlaybackQueue.poll() }
        bridge.start()
        audioBridge = bridge
        Log.d(TAG, "AudioBridge started")
    }

    // ── WebRTC cleanup ────────────────────────────────────────────────────────

    private fun cleanupWebRTC() {
        audioBridge?.stop()
        audioBridge = null
        gsmPcmQueue.clear()
        try { localAudioTrack?.setEnabled(false) } catch (e: Exception) {}
        try { localAudioTrack?.dispose()         } catch (e: Exception) {}
        localAudioTrack = null
        try { peerConnection?.close()            } catch (e: Exception) {}
        peerConnection = null
        Log.d(TAG, "WebRTC cleaned up")
    }

    // ── Call teardown ─────────────────────────────────────────────────────────

    private fun endCurrentCall() {
        cleanupWebRTC()
        currentCallId = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            try {
                (getSystemService(TELECOM_SERVICE) as android.telecom.TelecomManager).endCall()
            } catch (e: Exception) {
                Log.e(TAG, "endCall error: ${e.message}")
            }
        }
        try {
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {}
        Log.d(TAG, "✅ Call ended cleanly")
    }

    // ── DHT registration ──────────────────────────────────────────────────────

    private fun registerInDHT(areaCode: String, country: String) {
        val bootstrapNodes = listOf(
            "wss://node.opencall.space/ws"
        )
        val relayInfo = JSONObject().apply {
            put("ocp_address", getRelayId())
            put("country",     country)
            put("area_code",   areaCode)
            put("capacity",    5)
            put("timestamp",   System.currentTimeMillis() / 1000)
        }.toString()
        val storeMsg = JSONObject().apply {
            put("type",  "STORE")
            put("key",   "bridge:$country")
            put("value", relayInfo)
            put("ttl",   3600)
        }.toString()

        scope.launch(Dispatchers.IO) {
            for (nodeUrl in bootstrapNodes) {
                try {
                    okClient.newWebSocket(
                        Request.Builder().url(nodeUrl).build(),
                        object : WebSocketListener() {
                            override fun onOpen(ws: WebSocket, response: Response) {
                                ws.send(storeMsg)
                                Log.i(TAG, "DHT registered at $nodeUrl")
                                scope.launch { delay(2_000); ws.close(1000, "stored") }
                            }
                            override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) {
                                Log.w(TAG, "DHT $nodeUrl failed: ${t.message}")
                            }
                        }
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "DHT error at $nodeUrl: ${e.message}")
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun showError(msg: String) {
        Log.e(TAG, msg)
        updateNotif("Error: ${msg.take(50)}")
        statusText = "Error"
        scope.launch(Dispatchers.Main) {
            Toast.makeText(this@RelayService, "Relay: $msg", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildNotif(status: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, RelayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, OpenCallApp.CHANNEL_RELAY)
            .setContentTitle("OpenCall Relay")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setOngoing(true)
            .setSilent(true)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPi)
            .build()
    }

    private fun updateNotif(s: String) {
        statusText = s
        getSystemService(NotificationManager::class.java)
            .notify(OpenCallApp.NOTIF_RELAY_ID, buildNotif(s))
    }

    private fun sendSMSInvite(targetNumber: String, joinURL: String, callId: String) {
        try {
            val message = "Hi! You have a free call waiting on OpenCall.\n" +
                          "Tap to answer: $joinURL\n" +
                          "(No app needed - works in any browser)"
            @Suppress("DEPRECATION")
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                getSystemService(android.telephony.SmsManager::class.java)
            else
                android.telephony.SmsManager.getDefault()
            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(targetNumber, null, parts, null, null)
            Log.d(TAG, "SMS sent to $targetNumber")
            webSocket?.send(JSONObject().apply {
                put("type",   "sms_sent")
                put("callId", callId)
                put("status", "ok")
            }.toString())
        } catch (e: Exception) {
            Log.e(TAG, "SMS send failed: ${e.message}")
            webSocket?.send(JSONObject().apply {
                put("type",   "sms_sent")
                put("callId", callId)
                put("status", "error")
                put("reason", e.message)
            }.toString())
        }
    }

    fun normalizeNumber(num: String): String {
        var n = num.trim().replace(Regex("[\\s\\-\\(\\)]"), "")
        if (n.isNotEmpty() && !n.startsWith("+")) n = "+$n"
        return n
    }

    fun detectCountry(e164: String): String {
        return when {
            e164.startsWith("+254") -> "KE"
            e164.startsWith("+234") -> "NG"
            e164.startsWith("+233") -> "GH"
            e164.startsWith("+380") -> "UA"
            e164.startsWith("+44")  -> "GB"
            e164.startsWith("+49")  -> "DE"
            e164.startsWith("+33")  -> "FR"
            e164.startsWith("+27")  -> "ZA"
            e164.startsWith("+55")  -> "BR"
            e164.startsWith("+52")  -> "MX"
            e164.startsWith("+61")  -> "AU"
            e164.startsWith("+81")  -> "JP"
            e164.startsWith("+82")  -> "KR"
            e164.startsWith("+86")  -> "CN"
            e164.startsWith("+91")  -> "IN"
            e164.startsWith("+1")   -> "US"
            e164.startsWith("+7")   -> "RU"
            else                    -> "XX"
        }
    }

    private fun getRelayId(): String {
        val p = getSharedPreferences("opencall", Context.MODE_PRIVATE)
        return p.getString("relay_id", null)
            ?: "relay_${System.currentTimeMillis()}".also {
                p.edit().putString("relay_id", it).apply()
            }
    }

    // ── Stop relay ────────────────────────────────────────────────────────────

    private fun stopRelay() {
        val wasRunning = isRunning
        isRunning  = false
        statusText = "Stopped"

        keepaliveJob?.cancel()
        keepaliveJob = null
        reconnectJob?.cancel()
        reconnectJob = null

        webSocket?.close(1000, "user_stopped")
        webSocket = null

        endCurrentCall()

        val factory = peerConnectionFactory
        peerConnectionFactory = null
        factory?.dispose()

        val adm = jadm
        jadm = null
        adm?.release()

        val base = eglBase
        eglBase = null
        base?.release()

        stopForeground(STOP_FOREGROUND_REMOVE)

        if (wasRunning) sendBroadcast(Intent(ACTION_STOPPED))

        stopSelf()
    }
}
