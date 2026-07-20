package com.opencall.relay.offline

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Minimal, fully-programmatic UI (no layout resource) for discovering a
 * Wi-Fi-Direct peer and running an offline call over it entirely via
 * OfflineMediaTransport (camera/mic -> MediaCodec -> raw socket). Built
 * entirely in com.opencall.relay.offline so it never touches
 * RelayService/AudioBridge/SMS/GSM/relay code.
 * FIX: WebRTC (OfflineCallManager) removed from this path — it carried no
 * media (that's owned by OfflineMediaTransport) and its JavaAudioDeviceModule
 * was a second, unnecessary consumer contending for the microphone.
 * LocalSignaling stays, repurposed as a lightweight hangup/keepalive control
 * channel (see LocalSignaling.kt) instead of an sdp/ice carrier.
 */
class OfflineCallActivity : AppCompatActivity() {

    companion object {
        private const val PERM_REQUEST = 5001
        private const val GROUP_FORMATION_TIMEOUT_MS = 30_000L
        // CASE E1: guards against re-chaining the handler on every Activity recreation
        // (rotation, etc.) — install exactly once per process.
        private var uncaughtHandlerInstalled = false
    }

    private lateinit var wifiDirect: WifiDirectManager
    private var signaling: LocalSignaling? = null

    private lateinit var statusText: TextView
    private lateinit var peerListView: ListView
    private lateinit var searchButton: Button
    private lateinit var hangupButton: Button
    private lateinit var searchScreen: LinearLayout
    private lateinit var callScreen: LinearLayout
    private lateinit var errorText: TextView

    // New direct-MediaCodec transport (runs in parallel with WebRTC for step-1 verification)
    private lateinit var videoFrame: FrameLayout
    private var mediaRemoteView: SurfaceView? = null
    private var mediaTransport: OfflineMediaTransport? = null
    private var mediaSurface: Surface? = null

    // TASK 1: the decoder's actual reported size (post-crop), used to letterbox
    // mediaRemoteView instead of letting it stretch to fill videoFrame.
    private var remoteVideoWidth = 0
    private var remoteVideoHeight = 0

    // TASK 2: minimal in-memory chat overlay — no persistence, cleared on hangup/link-lost.
    private data class ChatEntry(val text: String, val fromMe: Boolean)
    private val chatMessages = mutableListOf<ChatEntry>()
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var chatListView: ListView
    private lateinit var chatInput: EditText
    private lateinit var bottomPanel: LinearLayout

    // CALL MODES: chosen via the pre-connect dialog on peer tap, kept for the session.
    // Only the tapping device knows the mode upfront; the callee learns it from the
    // wire via mediaTransport.onModeResolved.
    private var isCallInitiator = false
    private var selectedCallMode: OfflineMediaTransport.CallMode? = null
    private var connectedPeerName = ""
    private lateinit var modeInfoBar: LinearLayout
    private lateinit var peerNameText: TextView
    private lateinit var callTimerText: TextView
    private val timerHandler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null
    private var callStartElapsedMs = 0L

    private var devices: List<WifiP2pDevice> = emptyList()
    private var p2pEnabled = false
    private var searchPending = false

    // CASE 2 fix: tracks whether *this* device actually issued connect(), so a
    // groupFormed=false broadcast can be told apart from stale/teardown noise.
    private var connectRequested = false
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var groupFormationTimeoutRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installUncaughtExceptionLogger()
        buildUi()

        wifiDirect = WifiDirectManager(applicationContext)
        wifiDirect.onPeersChanged = { list -> onPeersChanged(list) }
        wifiDirect.onConnectionChanged = { info -> onConnectionChanged(info) }
        wifiDirect.onP2pStateChanged = { enabled -> onP2pStateChanged(enabled) }
        wifiDirect.onFatalError = { msg ->
            runOnUiThread {
                errorText.text = msg
                errorText.visibility = View.VISIBLE
                statusText.text = "Wi-Fi Direct error"
                Log.e("OFFTRACE", "fatal: $msg")
            }
        }
        wifiDirect.init()
    }

    /**
     * CASE E1: no Thread.setDefaultUncaughtExceptionHandler existed anywhere in the
     * app, so a genuine uncaught crash and an OS-initiated background kill produced
     * the identical symptom (pid change, nothing in logcat). This closes that gap for
     * real crashes; it chains to whatever handler was already installed (if any) and
     * rethrows into it so process-death/crash-reporting semantics are unchanged —
     * this only adds a log line before that happens.
     */
    private fun installUncaughtExceptionLogger() {
        if (uncaughtHandlerInstalled) return
        uncaughtHandlerInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val stackLines = throwable.stackTrace.take(5).joinToString("\n") { "    at $it" }
            Log.e(
                "OFFTRACE",
                "FATAL UNCAUGHT on ${thread.name}: ${throwable.javaClass.simpleName}: ${throwable.message}\n$stackLines"
            )
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
        }

        statusText = TextView(this).apply {
            text = "Offline Call"
            textSize = 18f
            setPadding(0, 0, 0, 24)
        }
        root.addView(statusText)

        searchScreen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        searchButton = Button(this).apply {
            text = "Search nearby"
            setOnClickListener { onSearchClicked() }
        }
        peerListView = ListView(this)
        peerListView.setOnItemClickListener { _, _, position, _ ->
            val device = devices.getOrNull(position)
            Log.d("OFFTRACE", "tap peer=${device?.deviceName} addr=${device?.deviceAddress}")
            try {
                device?.let { onPeerSelected(it) }
            } catch (t: Throwable) {
                Log.e("OFFTRACE", "tap handler threw", t)
                errorText.text = "tap handler threw: ${t.message ?: t.toString()}"
                errorText.visibility = View.VISIBLE
            }
        }
        searchScreen.addView(searchButton)
        searchScreen.addView(peerListView)
        root.addView(searchScreen)

        callScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        videoFrame = FrameLayout(this)

        // Plain SurfaceView for the MediaCodec decoder output. holder.surface is passed
        // to the transport once surfaceCreated fires (always before the first encoded
        // frame arrives).
        val mediaView = SurfaceView(this)
        mediaRemoteView = mediaView
        mediaView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                mediaSurface = holder.surface
                mediaTransport?.setDisplaySurface(holder.surface)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { mediaSurface = null }
        })
        videoFrame.addView(
            mediaView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        val density = resources.displayMetrics.density

        // CALL MODES: shown instead of the video views in AUDIO/CHAT mode (no camera
        // feed to display) — peer name + a running call timer.
        modeInfoBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        peerNameText = TextView(this).apply {
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        callTimerText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            text = "00:00"
        }
        modeInfoBar.addView(peerNameText)
        modeInfoBar.addView(callTimerText)
        videoFrame.addView(
            modeInfoBar,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
        )

        errorText = TextView(this).apply {
            setTextColor(Color.RED)
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
            visibility = View.GONE
        }
        videoFrame.addView(
            errorText,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
        )

        // TASK 2: chat overlay — semi-transparent message list + input row, stacked with
        // the hangup button into one bottom-anchored panel over the video (never pushes
        // the video area around; just floats on top of it, hence semi-transparent).
        chatListView = ListView(this).apply {
            setBackgroundColor(Color.argb(120, 0, 0, 0))
            divider = null
            dividerHeight = 0
            isFocusable = false
            transcriptMode = ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL
        }
        chatAdapter = ChatAdapter()
        chatListView.adapter = chatAdapter

        chatInput = EditText(this).apply {
            hint = "Message"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setBackgroundColor(Color.argb(160, 255, 255, 255))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { onSendChatClicked(); true } else false
            }
        }
        val sendButton = Button(this).apply {
            text = "Send"
            setOnClickListener { onSendChatClicked() }
        }
        val chatInputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            addView(chatInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(sendButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        hangupButton = Button(this).apply {
            text = "Hang Up"
            setOnClickListener { onHangupClicked() }
        }
        val hangupRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, (8 * density).toInt(), 0, (16 * density).toInt())
            addView(hangupButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(chatListView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (200 * density).toInt()))
            addView(chatInputRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(hangupRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        videoFrame.addView(
            bottomPanel,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
            }
        )

        // TASK 1: re-letterbox whenever videoFrame's own size changes (rotation, etc.).
        videoFrame.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val widthChanged = (right - left) != (oldRight - oldLeft)
            val heightChanged = (bottom - top) != (oldBottom - oldTop)
            if (widthChanged || heightChanged) {
                applyVideoAspectRatio(remoteVideoWidth, remoteVideoHeight)
            }
        }

        callScreen.addView(
            videoFrame,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        )
        root.addView(callScreen)

        setContentView(root)
    }

    // ── TASK 1: remote video letterboxing ───────────────────────────────────────

    /** Resize mediaRemoteView to the largest centered rect that fits videoFrame while
     *  preserving the decoder's actual width:height ratio — letterbox, never crop or
     *  stretch. Safe to call with a size before layout has happened; it's re-run by
     *  the layout-change listener once real dimensions are available. */
    private fun applyVideoAspectRatio(videoWidth: Int, videoHeight: Int) {
        if (videoWidth <= 0 || videoHeight <= 0) return
        remoteVideoWidth = videoWidth
        remoteVideoHeight = videoHeight

        val parentWidth = videoFrame.width
        val parentHeight = videoFrame.height
        if (parentWidth <= 0 || parentHeight <= 0) return // not laid out yet

        val parentRatio = parentWidth.toFloat() / parentHeight.toFloat()
        val videoRatio = videoWidth.toFloat() / videoHeight.toFloat()
        val targetWidth: Int
        val targetHeight: Int
        if (videoRatio > parentRatio) {
            // Video is relatively wider than the parent — fit width, letterbox top/bottom.
            targetWidth = parentWidth
            targetHeight = (parentWidth / videoRatio).toInt()
        } else {
            // Video is relatively taller/narrower — fit height, letterbox left/right.
            targetWidth = (parentHeight * videoRatio).toInt()
            targetHeight = parentHeight
        }

        val view = mediaRemoteView ?: return
        val params = view.layoutParams as? FrameLayout.LayoutParams
            ?: FrameLayout.LayoutParams(targetWidth, targetHeight)
        params.width = targetWidth
        params.height = targetHeight
        params.gravity = Gravity.CENTER
        view.layoutParams = params
    }

    /** Called on hangup/link-lost so a stale letterboxed size doesn't bleed into the
     *  next call before its first video frame arrives. */
    private fun resetVideoAspectRatio() {
        remoteVideoWidth = 0
        remoteVideoHeight = 0
        mediaRemoteView?.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        )
    }

    // ── CALL MODES: per-mode UI ──────────────────────────────────────────────────

    /** VIDEO = current call screen (camera views + chat overlay). AUDIO = no remote/
     *  local camera views, peer name + timer shown instead, chat overlay unchanged.
     *  CHAT = same as AUDIO minus the camera views, plus the chat overlay is expanded
     *  to fill the whole screen instead of floating over video. */
    private fun applyUiForMode(mode: OfflineMediaTransport.CallMode) {
        val density = resources.displayMetrics.density
        val showVideo = mode == OfflineMediaTransport.CallMode.VIDEO
        val fullScreenChat = mode == OfflineMediaTransport.CallMode.CHAT

        mediaRemoteView?.visibility = if (showVideo) View.VISIBLE else View.GONE
        modeInfoBar.visibility = if (showVideo) View.GONE else View.VISIBLE
        peerNameText.text = connectedPeerName.ifEmpty { "Peer" }

        bottomPanel.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            if (fullScreenChat) FrameLayout.LayoutParams.MATCH_PARENT else FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM }

        chatListView.layoutParams = if (fullScreenChat) {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        } else {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (200 * density).toInt())
        }
    }

    /** Neutral baseline applied while the callee hasn't yet learned the mode from the
     *  wire (mediaTransport.onModeResolved), and restored on hangup/link-lost so a
     *  redial in a different mode doesn't inherit stale layout. */
    private fun resetCallModeUi() {
        val density = resources.displayMetrics.density
        mediaRemoteView?.visibility = View.VISIBLE
        modeInfoBar.visibility = View.GONE
        bottomPanel.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM }
        chatListView.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (200 * density).toInt())
    }

    private fun startCallTimer() {
        callStartElapsedMs = SystemClock.elapsedRealtime()
        val r = object : Runnable {
            override fun run() {
                val secs = (SystemClock.elapsedRealtime() - callStartElapsedMs) / 1000
                callTimerText.text = String.format("%02d:%02d", secs / 60, secs % 60)
                timerHandler.postDelayed(this, 1000)
            }
        }
        timerRunnable = r
        timerHandler.post(r)
    }

    private fun stopCallTimer() {
        timerRunnable?.let { timerHandler.removeCallbacks(it) }
        timerRunnable = null
        callTimerText.text = "00:00"
    }

    // ── TASK 2: chat overlay ─────────────────────────────────────────────────────

    private inner class ChatAdapter : BaseAdapter() {
        override fun getCount() = chatMessages.size
        override fun getItem(position: Int): ChatEntry = chatMessages[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val entry = chatMessages[position]
            val density = resources.displayMetrics.density
            val bubble = TextView(this@OfflineCallActivity).apply {
                text = entry.text
                setTextColor(Color.WHITE)
                setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
                setBackgroundColor(
                    if (entry.fromMe) Color.argb(220, 30, 100, 220) else Color.argb(220, 70, 70, 70)
                )
            }
            val row = FrameLayout(this@OfflineCallActivity).apply {
                setPadding((6 * density).toInt(), (2 * density).toInt(), (6 * density).toInt(), (2 * density).toInt())
            }
            row.addView(
                bubble,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = if (entry.fromMe) Gravity.END else Gravity.START
                }
            )
            return row
        }
    }

    private fun appendChatMessage(text: String, fromMe: Boolean) {
        chatMessages.add(ChatEntry(text, fromMe))
        chatAdapter.notifyDataSetChanged()
        chatListView.post { if (chatAdapter.count > 0) chatListView.setSelection(chatAdapter.count - 1) }
    }

    private fun onSendChatClicked() {
        val text = chatInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        val sent = mediaTransport?.sendChat(text) ?: false
        if (!sent) {
            Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show()
            return
        }
        appendChatMessage(text, fromMe = true)
        chatInput.setText("")
    }

    /** Called on hangup/link-lost — chat is in-memory for the call's duration only. */
    private fun resetChat() {
        chatMessages.clear()
        chatAdapter.notifyDataSetChanged()
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun requiredPermissions(): List<String> {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return perms
    }

    private fun onSearchClicked() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERM_REQUEST)
            return
        }
        proceedToDiscovery()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                proceedToDiscovery()
            } else {
                Toast.makeText(
                    this,
                    "Location, Nearby Devices, Microphone, and Camera permissions are required for offline calls",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** CASE 6 fix: permissions being granted isn't enough — the system Location toggle
     *  must also be on, or Wi-Fi Direct discovery/connection silently never completes. */
    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            val mode = Settings.Secure.getInt(contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF)
            mode != Settings.Secure.LOCATION_MODE_OFF
        }
    }

    private fun proceedToDiscovery() {
        if (!isLocationEnabled()) {
            Log.e("OFFTRACE", "location services OFF — blocking discovery")
            Toast.makeText(this, "Turn on Location — required for Wi-Fi Direct", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        requestDiscoveryWhenReady()
    }

    // ── Discovery ─────────────────────────────────────────────────────────────

    private fun onP2pStateChanged(enabled: Boolean) {
        p2pEnabled = enabled
        if (enabled && searchPending) {
            searchPending = false
            startDiscovery()
        }
    }

    /** Permissions are granted at this point; only proceed once P2P itself is reported enabled. */
    private fun requestDiscoveryWhenReady() {
        if (p2pEnabled) {
            startDiscovery()
        } else {
            searchPending = true
            statusText.text = "Waiting for Wi-Fi Direct to turn on..."
        }
    }

    private fun startDiscovery() {
        statusText.text = "Searching for nearby devices..."
        wifiDirect.startDiscovery { ok ->
            if (!ok) runOnUiThread {
                Toast.makeText(this, "Discovery failed to start", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun onPeersChanged(list: WifiP2pDeviceList) {
        devices = list.deviceList.toList()
        val names = devices.map { "${it.deviceName} (${it.deviceAddress})" }
        peerListView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        statusText.text = if (devices.isEmpty()) "No devices found yet" else "${devices.size} device(s) found"
    }

    private fun onPeerSelected(device: WifiP2pDevice) {
        if (connectRequested) {
            Log.d("OFFTRACE", "connect already in progress, ignoring tap")
            return
        }
        showCallModeDialog(device)
    }

    /** CALL MODES: the tapping device is always the call initiator — it picks the mode
     *  here, before the Wi-Fi Direct connect even starts, and announces it to the peer
     *  over the media socket once connected (see OfflineMediaTransport). */
    private fun showCallModeDialog(device: WifiP2pDevice) {
        val labels = arrayOf("Video call", "Audio call", "Message")
        AlertDialog.Builder(this)
            .setTitle("Call ${device.deviceName}")
            .setItems(labels) { _, which ->
                val mode = when (which) {
                    0 -> OfflineMediaTransport.CallMode.VIDEO
                    1 -> OfflineMediaTransport.CallMode.AUDIO
                    else -> OfflineMediaTransport.CallMode.CHAT
                }
                isCallInitiator = true
                selectedCallMode = mode
                connectedPeerName = device.deviceName ?: ""
                connectToPeer(device)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun connectToPeer(device: WifiP2pDevice) {
        statusText.text = "Connecting to ${device.deviceName}..."
        connectRequested = true
        peerListView.isEnabled = false
        wifiDirect.connect(device) { ok ->
            if (!ok) {
                connectRequested = false
                runOnUiThread {
                    peerListView.isEnabled = true
                    Toast.makeText(this, "Connect failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun scheduleGroupFormationTimeout() {
        if (groupFormationTimeoutRunnable != null) return // already waiting on one
        val runnable = Runnable {
            groupFormationTimeoutRunnable = null
            connectRequested = false
            peerListView.isEnabled = true
            Log.e("OFFTRACE", "group formation TIMEOUT after 30s")
            statusText.text = "Connection timed out"
            errorText.text = "Could not form Wi-Fi Direct group (timed out)"
            errorText.visibility = View.VISIBLE
        }
        groupFormationTimeoutRunnable = runnable
        timeoutHandler.postDelayed(runnable, GROUP_FORMATION_TIMEOUT_MS)
    }

    private fun cancelGroupFormationTimeout() {
        groupFormationTimeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }
        groupFormationTimeoutRunnable = null
    }

    /** CASE 2 fix: resets connect-attempt tracking; call on every disconnect/teardown path. */
    private fun resetConnectionAttemptState() {
        connectRequested = false
        peerListView.isEnabled = true
        cancelGroupFormationTimeout()
    }

    // ── Connection → local signaling → WebRTC ───────────────────────────────────

    private fun onConnectionChanged(info: WifiP2pInfo) {
        // FIX 4: this runs directly as a WifiP2pManager callback on the main thread —
        // any uncaught exception here kills the whole process. Never let one escape.
        try {
            onConnectionChangedInternal(info)
        } catch (e: Exception) {
            Log.e("OFFTRACE", "FATAL onConnectionChanged: ${e.javaClass.simpleName}: ${e.message}", e)
            errorText.text = "Internal error: ${e.javaClass.simpleName}: ${e.message}"
            errorText.visibility = View.VISIBLE
        }
    }

    private fun onConnectionChangedInternal(info: WifiP2pInfo) {
        Log.d("OFFTRACE", "connInfo groupFormed=${info.groupFormed} isGO=${info.isGroupOwner} goAddr=${info.groupOwnerAddress}")
        if (!info.groupFormed) {
            if (!connectRequested) {
                Log.d("OFFTRACE", "ignoring stale/teardown connInfo")
                return
            }
            Log.d("OFFTRACE", "waiting for group formation...")
            scheduleGroupFormationTimeout()
            return
        }
        cancelGroupFormationTimeout()
        // FIX 5: clear the connect-attempt flag now that formation succeeded, so a later
        // groupFormed=false flap (e.g. a transient blip) can't re-arm the 30s timeout mid-call.
        connectRequested = false
        peerListView.isEnabled = true
        if (signaling != null) return // already wired up for this call

        statusText.text = "Connected — setting up call..."
        searchScreen.visibility = View.GONE
        callScreen.visibility = View.VISIBLE
        errorText.visibility = View.GONE

        // CALL MODES: the initiator already knows the mode (picked in the pre-connect
        // dialog) and can build the right screen immediately; the callee doesn't know
        // it yet and gets a neutral baseline until mediaTransport.onModeResolved fires.
        val initiatorMode = if (isCallInitiator) selectedCallMode else null
        if (initiatorMode != null) {
            applyUiForMode(initiatorMode)
        } else {
            resetCallModeUi()
        }
        startCallTimer()

        // FIX: LocalSignaling is now just the hangup/keepalive control channel — the
        // media transport below is the entire call, so there's no sdp/ice handshake
        // to gate anything on here.
        val local = LocalSignaling(info.isGroupOwner, info.groupOwnerAddress)
        signaling = local
        local.onConnected = { statusText.text = "Signaling connected" }
        local.onError = { err -> statusText.text = "Signaling error: $err" }
        local.onPeerGone = { reason -> runOnUiThread { onCallEnded("signaling: $reason") } }
        local.start()

        // FIX: OfflineMediaTransport is the entire media path now — WebRTC
        // (OfflineCallManager) has been removed from this call path (see class doc).
        val transport = OfflineMediaTransport(
            applicationContext,
            info.isGroupOwner,
            info.groupOwnerAddress,
            initiatorMode,
            onError = { msg ->
                runOnUiThread {
                    errorText.text = msg
                    errorText.visibility = View.VISIBLE
                    Log.e("OfflineMediaTransport", msg)
                }
            }
        )
        // FIX 2: the transport has already torn itself down by the time this fires —
        // we just need to end our own session and get back to a state where a new
        // call is possible without relaunching the app.
        transport.onLinkLost = { runOnUiThread { onCallEnded("media link lost") } }
        transport.onVideoSize = { w, h -> runOnUiThread { applyVideoAspectRatio(w, h) } }
        transport.onChatMessage = { text -> runOnUiThread { appendChatMessage(text, fromMe = false) } }
        // CALL MODES: fires immediately for the initiator (mode already known) and,
        // for the callee, once learned from the wire (type-5 frame or back-compat
        // fallback) — either way this is what actually finishes building the screen.
        transport.onModeResolved = { mode -> runOnUiThread { applyUiForMode(mode) } }
        mediaTransport = transport
        // Both roles show the remote peer's camera on mediaRemoteView. Surface may
        // already be ready if the SurfaceView layout completed synchronously; if not,
        // the surfaceCreated callback above will call setDisplaySurface once it is.
        mediaSurface?.let { transport.setDisplaySurface(it) }
        transport.start()

        // FIX 3: anchor process priority for the duration of the call so OEM battery
        // managers don't kill us the moment the Activity is backgrounded.
        startOfflineCallService()
    }

    /** FIX: fired when either OfflineMediaTransport detects an unrecoverable link
     *  failure, or LocalSignaling's peer hangs up cleanly / goes silent past its
     *  keepalive timeout. Either way the call is over — mediaTransport.stop() is
     *  idempotent whether or not the transport already tore itself down (link-death
     *  case), so it's safe to call unconditionally here. */
    private fun onCallEnded(reason: String) {
        Log.e("OFFTRACE", "call ended ($reason) — restarting discovery")
        statusText.text = "Call ended — connection lost"
        signaling?.stop()
        signaling = null
        mediaTransport?.stop()
        mediaTransport = null
        stopOfflineCallService()
        wifiDirect.stopPeerDiscovery()
        wifiDirect.disconnect()
        resetConnectionAttemptState()
        resetVideoAspectRatio()
        resetChat()
        resetCallModeUi()
        stopCallTimer()
        isCallInitiator = false
        selectedCallMode = null
        connectedPeerName = ""
        errorText.visibility = View.GONE
        callScreen.visibility = View.GONE
        searchScreen.visibility = View.VISIBLE
        proceedToDiscovery()
    }

    private fun onHangupClicked() {
        // FIX: tell the peer we're hanging up (best-effort — the socket is about to
        // close either way) so its LocalSignaling can end the call cleanly instead of
        // waiting on the media link to die.
        signaling?.sendHangup()
        signaling?.stop()
        signaling = null
        mediaTransport?.stop()
        mediaTransport = null
        stopOfflineCallService()
        wifiDirect.stopPeerDiscovery()
        wifiDirect.disconnect()
        resetConnectionAttemptState()
        resetVideoAspectRatio()
        resetChat()
        resetCallModeUi()
        stopCallTimer()
        isCallInitiator = false
        selectedCallMode = null
        connectedPeerName = ""
        errorText.visibility = View.GONE
        callScreen.visibility = View.GONE
        searchScreen.visibility = View.VISIBLE
        statusText.text = "Call ended"
    }

    // ── FIX 3: foreground service anchor ────────────────────────────────────────

    private fun startOfflineCallService() {
        val intent = Intent(this, OfflineCallService::class.java).apply {
            action = OfflineCallService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopOfflineCallService() {
        val intent = Intent(this, OfflineCallService::class.java).apply {
            action = OfflineCallService.ACTION_STOP
        }
        startService(intent)
    }

    override fun onStop() {
        super.onStop()
        // FIX 3: onStop fires for reasons unrelated to the user ending the call — screen
        // off, the system's own Wi-Fi Direct connect dialog, a notification pull-down —
        // any of which could happen mid-negotiation or mid-call. Tearing down the P2P
        // group here (as this used to) was the actual cause of groupFormed flapping
        // true->false during setup. Only pause discovery; disconnect() now only happens
        // in onHangupClicked()/onDestroy(), i.e. real end-of-call/end-of-activity.
        Log.d("OFFTRACE", "onStop — keeping session alive")
        wifiDirect.stopPeerDiscovery()
    }

    override fun onDestroy() {
        super.onDestroy()
        signaling?.stop()
        mediaTransport?.stop()
        stopOfflineCallService() // safety net in case hangup/link-lost didn't run
        wifiDirect.stopPeerDiscovery()
        wifiDirect.disconnect()
        wifiDirect.teardown()
        resetConnectionAttemptState()
        stopCallTimer()
    }
}
