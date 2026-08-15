package com.opencall.relay.offline

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.graphics.Color
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.opencall.relay.CapabilityProbe

/**
 * Minimal, fully-programmatic UI (no layout resource) for discovering a
 * Wi-Fi-Direct peer/group and running an offline call over it entirely via
 * OfflineMediaTransport (camera/mic -> MediaCodec -> raw socket). Built
 * entirely in com.opencall.relay.offline so it never touches
 * RelayService/AudioBridge/SMS/GSM/relay code.
 *
 * PHASE 3 — GO AS SWITCHBOARD: tapping a nearby device no longer immediately
 * starts a call — it JOINS a WiFi Direct group (the tapper's device becomes/joins
 * the group; up to ~8 members). Once joined, [groupScreen] shows the live roster
 * (see OfflineMediaTransport.onRosterUpdated) instead of going straight to the call
 * screen. Tapping a roster member brings up the same 3-mode dialog as before, but
 * now addresses a specific member (mediaTransport.placeCall) — that member may be
 * the GO, or another client relayed through it; this screen never needs to know
 * which. Hanging up (mediaTransport.endCall) returns to the roster, not to
 * re-discovery — the underlying group stays joined until "Leave group".
 */
class OfflineCallActivity : AppCompatActivity() {

    companion object {
        private const val PERM_REQUEST = 5001
        private const val GROUP_FORMATION_TIMEOUT_MS = 30_000L
        // CASE E1: guards against re-chaining the handler on every Activity recreation
        // (rotation, etc.) — install exactly once per process.
        private var uncaughtHandlerInstalled = false
        // FIX 5: one-time-per-process, same pattern as uncaughtHandlerInstalled above —
        // avoids re-prompting for battery exemption on every group join.
        private var batteryPromptShown = false
    }

    private lateinit var wifiDirect: WifiDirectManager
    private var signaling: LocalSignaling? = null

    private lateinit var statusText: TextView
    private lateinit var peerListView: ListView
    private lateinit var searchButton: Button
    // PHASE 7A STEP 5: user-chosen display name — see OfflineIdentity.displayName.
    private lateinit var displayNameButton: Button
    private lateinit var hangupButton: Button
    private lateinit var searchScreen: LinearLayout
    private lateinit var callScreen: LinearLayout
    private lateinit var errorText: TextView

    // PHASE 3: roster screen — shown after a WiFi Direct group is joined, before any
    // call is placed. Lists every member (self included); tapping one opens the same
    // call-mode dialog the old peer-tap flow had, tapping "Group chat" opens the
    // chat overlay in broadcast mode.
    private lateinit var groupScreen: LinearLayout
    private lateinit var rosterListView: ListView
    private lateinit var leaveGroupButton: Button
    private var roster: List<RoutingTable.Member> = emptyList()

    // PHASE 5A: SOS / FIND-over-mesh — roster screen only, no new Activity/screen.
    private lateinit var sosButton: Button
    private lateinit var sosAlertsText: TextView
    private var sosActive = false
    private val sosEntries = mutableMapOf<Long, MeshSosManager.SosEntry>()
    private val findResponses = mutableMapOf<Long, MeshSosManager.SosEntry>()

    // PHASE 5BC: full-screen SOS alert (over roster AND over an active call) plus
    // a separate party-status screen — both are the SAME overlay container
    // repurposed, since only one is ever shown at a time and they share almost
    // all of their per-member rendering. Neither touches videoFrame/GroupTile/
    // the call surfaces — this is a sibling layer stacked ABOVE the existing
    // `root` LinearLayout inside a wrapping FrameLayout (see buildUi).
    private lateinit var sosOverlay: LinearLayout
    private lateinit var sosOverlayBody: LinearLayout
    private lateinit var partyStatusOverlay: LinearLayout
    private lateinit var partyStatusOverlayBody: LinearLayout
    private lateinit var partyStatusButton: Button
    private lateinit var sosAlarm: SosAlarm
    // PHASE 6 TRACK B: hands-free trigger settings entry point.
    private lateinit var sosSettingsButton: Button
    private val handledRelayPrompts = mutableSetOf<String>()
    // PHASE 6 TRACK D: last-resort SSID broadcast — same direct-singleton
    // pattern as sosAlarm above (doesn't need any OfflineMediaTransport wiring,
    // it operates at the WifiP2pManager level, independent of the mesh frames).
    private lateinit var sosSsidBroadcast: SosSsidBroadcast
    private lateinit var ssidBroadcastButton: Button

    // PHASE 6 TRACK E: self-healing GO re-election state — see handleGoLost/
    // handleElectionResult/becomeNewGoAfterElection/waitForInviteAfterElection.
    private var reconnectingAfterGoLoss = false
    private var groupCallModeBeforeGoLoss: OfflineMediaTransport.GroupCallMode? = null
    // Auto-invite dedupe during the post-election window — invitePeer() per
    // discovered device address at most once per election, not once per
    // onPeersChanged tick (which fires repeatedly while discovery runs).
    private val invitedDuringElection = mutableSetOf<String>()
    // FIX 6: true once this device is confirmed as this group's owner — gates the
    // "Add to group" invite rows (GO-only) on the roster screen, and the discovery
    // this device keeps running while there to keep finding invitable peers.
    private var isLocalGroupOwner = false
    // FIX 6b: nearby (Wi-Fi-Direct-level) peers not already part of this connection —
    // rendered as trailing "Add to group" rows on the roster screen, GO only. Kept
    // separate from `roster` (mesh-level, node-id-based) since a discovered
    // WifiP2pDevice has no node id until it actually joins and sends HELLO.
    private var invitableDevices: List<WifiP2pDevice> = emptyList()

    // PHASE 3C: group call screen — a responsive GRID of per-participant tiles
    // (live video, letterboxed, or an avatar+initials placeholder when that
    // participant's camera is off), replacing the old single-active-speaker view.
    // A separate screen from callScreen (1:1) entirely, so the existing 1:1/group-
    // chat flow is untouched (point 12) — the two share the transport's camera/
    // decoder pipeline (mutually exclusive, never both active) but not any UI.
    private lateinit var groupCallScreen: LinearLayout
    private lateinit var groupCallGrid: GridLayout
    private lateinit var groupCallStatusText: TextView
    private lateinit var groupCallCameraButton: Button
    private lateinit var groupCallMicButton: Button
    private lateinit var leaveGroupCallButton: Button
    private var groupCallParticipants: List<Long> = emptyList()
    private var groupCallActiveSpeaker: Long? = null
    private var groupCallPinned = false
    private var groupCallMode: OfflineMediaTransport.GroupCallMode? = null
    // Per-participant camera on/off, mirrored from the transport's TYPE_CAM state —
    // drives each tile's live-video-vs-avatar choice.
    private var groupCallCamStates: MutableMap<Long, Boolean> = mutableMapOf()
    private var groupCallLocalCameraOn = false
    private var groupCallLocalMicMuted = false
    // Non-null while one tile is shown fullscreen (tap-to-focus, point 9); tapping
    // that same tile again clears it and returns to the grid.
    private var groupCallFocusedTile: Long? = null

    /** One grid tile's views, built once per participant and reused across grid
     *  rebuilds (reparented, never recreated) so its SurfaceView/decoder binding
     *  survives a layout change — only [videoWidth]/[videoHeight] (the last known
     *  decoded size, for re-letterboxing) are mutable. */
    private data class GroupTile(
        val container: FrameLayout,
        val surfaceView: SurfaceView,
        val avatarText: TextView,
        val nameLabel: TextView,
        val speakingDot: TextView,
        val batteryText: TextView,
        var videoWidth: Int = 0,
        var videoHeight: Int = 0
    )
    private val groupTiles = LinkedHashMap<Long, GroupTile>()

    // Direct-MediaCodec transport — the offline call's entire media path
    // (camera/mic/encode/decode/socket), independent of WebRTC.
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

    // PHASE 3: callScreen is now shared between a real 1:1 call and the group-chat-only
    // screen (no camera/mic involved, no placeCall) — this flag distinguishes them for
    // onSendChatClicked/onHangupClicked.
    private var isGroupChatScreen = false
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

    // BUG 2 FIX: deviceAddress -> display name, from Wi-Fi Direct DNS-SD TXT
    // records (see WifiDirectManager.onServiceTxtRecordFound) — a PRE-CONNECT
    // hint only. UNVERIFIED: no signature exists before HELLO, so this must
    // never be treated as authenticated or cached as a verified name; once
    // connected, the signed HELLO/ROSTER name (via nameFor/nameForGroupParticipant)
    // takes over completely and this map is never consulted again for that peer.
    private val dnsSdNamesByAddress = mutableMapOf<String, String>()

    // CASE 2 fix: tracks whether *this* device actually issued connect(), so a
    // groupFormed=false broadcast can be told apart from stale/teardown noise.
    private var connectRequested = false
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var groupFormationTimeoutRunnable: Runnable? = null

    // IDLE-SESSION FIX: unconditionally kept in sync with the latest
    // WifiP2pInfo.groupFormed seen (even the "ignoring stale/teardown connInfo" early
    // return in onConnectionChangedInternal used to leave this unobservable) — backs
    // the isGroupFormed lambda handed to OfflineMediaTransport so its client-side
    // reconnect can tell "transient socket drop" apart from "the group itself is gone".
    private var currentGroupFormed = false
    // True until the media transport itself gives up (onLinkLost) — see onPeerGone's
    // use of this below: a signaling-channel keepalive timeout alone no longer tears
    // the group down if the media channel still looks healthy.
    private var mediaLinkAlive = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installUncaughtExceptionLogger()
        buildUi()
        sosAlarm = SosAlarm.get(applicationContext)
        // BUG 1 FIX 2: silencing the sound alone left the underlying SOS marked
        // active, so the sender's next ordinary rebroadcast re-triggered the
        // siren — this also marks it non-alarmable (both on the transport's
        // MeshSosManager and this Activity's own display mirror) so it actually
        // stays silenced, while still showing as historical (see renderSosAlerts).
        sosAlarm.onAutoStopTimeout = { senders ->
            runOnUiThread {
                mediaTransport?.suppressSosAlarmFor(senders)
                senders.forEach { id -> sosEntries[id]?.let { sosEntries[id] = it.copy(alarmable = false) } }
                renderSosAlerts()
                val activeSenders = mediaTransport?.activeSosSenderIds() ?: emptySet()
                renderSosOverlay(activeSenders)
                sosOverlay.visibility = if (activeSenders.isNotEmpty()) View.VISIBLE else View.GONE
            }
        }
        sosSsidBroadcast = SosSsidBroadcast.get(applicationContext)
        sosSsidBroadcast.onRejoinWindow = {
            // PHASE 6 TRACK D: deliberately NOT the full leaveGroup() — that
            // also shuts down mediaTransport (and with it MeshSosManager,
            // discarding the very SOS beacon/carry state this last-resort mode
            // exists to publicize). Just resume ordinary peer discovery; the
            // existing WifiDirectManager/OfflineMediaTransport reconnect logic
            // picks back up on its own if the same group re-forms.
            runOnUiThread { wifiDirect.startDiscovery { ok -> if (!ok) Log.w("OFFTRACE", "SSID: rejoin discovery failed") } }
        }
        sosSsidBroadcast.onStateChanged = { active, broadcasting, ssid ->
            runOnUiThread { updateSsidBroadcastButtonUi(active, broadcasting, ssid) }
        }
        maybeShowSosOnboarding()

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
        // BUG 2 FIX: pre-connect display-name hint via DNS-SD TXT record — see
        // WifiDirectManager.onServiceTxtRecordFound's doc. UNVERIFIED.
        wifiDirect.onServiceTxtRecordFound = { address, record ->
            val name = record["n"]
            if (name != null) {
                runOnUiThread {
                    dnsSdNamesByAddress[address] = name
                    refreshPeerListUi()
                }
            }
        }
        wifiDirect.init()
        registerDnsSdLocalService()
    }

    /** BUG 2 FIX: advertises this device's OfflineIdentity display name over
     *  Wi-Fi Direct DNS-SD so it's visible in a peer's discovery list BEFORE any
     *  connection (and therefore before HELLO) is possible — see
     *  WifiDirectManager.registerLocalService's doc for why setDeviceName isn't
     *  usable here. Called once at startup and again from [showDisplayNameDialog]
     *  whenever the name actually changes, so an edit takes effect without an
     *  app restart. */
    private fun registerDnsSdLocalService() {
        val nodeId = OfflineIdentity.nodeId(applicationContext)
        val shortNodeIdHex = OfflineIdentity.hex(nodeId).takeLast(6)
        val name = OfflineIdentity.displayName(applicationContext)
        wifiDirect.registerLocalService(name, shortNodeIdHex, MeshFrame.VERSION.toString())
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

        // PHASE 7A STEP 5: always visible/editable, not tucked behind a menu —
        // this is the one piece of identity every peer sees the moment this
        // device joins a group (travels signed inside HELLO/ROSTER).
        displayNameButton = Button(this).apply {
            setOnClickListener { showDisplayNameDialog() }
        }
        updateDisplayNameButtonUi()
        root.addView(displayNameButton)

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

        // PHASE 3: roster screen — built once here, shown between "joined the group"
        // and "placed/received a call".
        groupScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val groupHeader = TextView(this).apply {
            text = "Group members"
            textSize = 16f
            setPadding(0, 0, 0, 12)
        }
        // PHASE 5A: SOS toggle — visually latched (red + relabeled) while active.
        // Sticky local state lives in the transport (MeshSosManager); sosActive
        // here only mirrors it for the button's own label/color.
        sosButton = Button(this).apply {
            text = "SOS"
            setOnClickListener {
                if (sosActive) {
                    mediaTransport?.stopSos()
                } else {
                    mediaTransport?.startSos(null)
                }
                sosActive = !sosActive
                updateSosButtonUi()
            }
        }
        // PHASE 5A: incoming SOS/Find results — deliberately a SEPARATE element
        // from rosterListView, not a row folded into it, so an incoming SOS can't
        // be silently lost among ordinary peer rows. GONE (nothing shown) whenever
        // there's nothing to report.
        sosAlertsText = TextView(this).apply {
            textSize = 13f
            setPadding(16, 12, 16, 12)
            visibility = View.GONE
        }
        // PHASE 6 TRACK B: hands-free trigger toggles + emergency contacts.
        sosSettingsButton = Button(this).apply {
            text = "SOS settings"
            setOnClickListener { showSosSettingsDialog() }
        }
        // PHASE 6 TRACK D: last-resort SSID broadcast — explicit confirmation
        // required every time (see SosSsidBroadcast's class doc: this disconnects
        // the whole party from the mesh while active).
        ssidBroadcastButton = Button(this).apply {
            text = "Last resort: broadcast SSID"
            setOnClickListener { onSsidBroadcastButtonClicked() }
        }
        // PHASE 5BC: party-status screen — every known ledger member (not just
        // active SOS senders), including anyone currently out of contact.
        partyStatusButton = Button(this).apply {
            text = "Party status"
            setOnClickListener {
                renderPartyStatus()
                partyStatusOverlay.visibility = View.VISIBLE
            }
        }
        rosterListView = ListView(this)
        rosterListView.setOnItemClickListener { _, _, position, _ -> onRosterItemClicked(position) }
        // PHASE 5A: long-press a roster row to "Find" that peer — tap alone is
        // already taken (opens the call-mode dialog), so this needs its own
        // gesture rather than a conflicting second tap target.
        rosterListView.setOnItemLongClickListener { _, _, position, _ ->
            onRosterItemLongClicked(position)
        }
        leaveGroupButton = Button(this).apply {
            text = "Leave group"
            setOnClickListener { leaveGroup("user left group") }
        }
        groupScreen.addView(groupHeader)
        groupScreen.addView(sosButton)
        groupScreen.addView(sosSettingsButton)
        groupScreen.addView(ssidBroadcastButton)
        groupScreen.addView(partyStatusButton)
        groupScreen.addView(sosAlertsText)
        groupScreen.addView(rosterListView)
        groupScreen.addView(leaveGroupButton)
        root.addView(groupScreen)

        buildGroupCallScreen(root)

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
        // PHASE 3: also reused, full-screen (see applyUiForMode), for the group-chat-only
        // screen (no camera/mic involved there).
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

        // PHASE 5BC: wrap the existing `root` in a FrameLayout so the SOS alert /
        // party-status overlays can stack ABOVE it — over the roster screen AND
        // over an active call screen, per the spec, without touching anything
        // inside `root` (searchScreen/groupScreen/callScreen/videoFrame/tiles are
        // all untouched siblings underneath).
        val overlayRoot = FrameLayout(this)
        overlayRoot.addView(
            root,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        sosOverlay = buildFullScreenOverlay(
            titleText = "🆘 SOS ALERT",
            titleBg = Color.argb(255, 150, 0, 0),
            onCloseSilenceAll = { sosAlarm.silence() }
        ) { sosOverlayBody = it }
        partyStatusOverlay = buildFullScreenOverlay(
            titleText = "Party status",
            titleBg = Color.DKGRAY,
            onCloseSilenceAll = null
        ) { partyStatusOverlayBody = it }
        overlayRoot.addView(
            sosOverlay,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        overlayRoot.addView(
            partyStatusOverlay,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        setContentView(overlayRoot)
    }

    /** PHASE 5BC: builds one full-screen, scrollable overlay — used for both the
     *  SOS alert and the party-status screen (see their call sites). GONE by
     *  default. [onCloseSilenceAll] is non-null only for the SOS alert, where
     *  "Silence" stops sound on this device only (does not clear SOS state or
     *  this screen — see SosAlarm.silence's doc); the party-status screen's
     *  close button is a plain dismiss. */
    private fun buildFullScreenOverlay(
        titleText: String,
        titleBg: Int,
        onCloseSilenceAll: (() -> Unit)?,
        bodyOut: (LinearLayout) -> Unit
    ): LinearLayout {
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(245, 20, 20, 20))
            visibility = View.GONE
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(titleBg)
            setPadding(24, 48, 24, 24)
        }
        val titleLabel = TextView(this).apply {
            text = titleText
            textSize = 22f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleRow.addView(titleLabel)
        if (onCloseSilenceAll != null) {
            val silenceButton = Button(this).apply {
                text = "Silence"
                setOnClickListener { onCloseSilenceAll() }
            }
            titleRow.addView(silenceButton)
        }
        val closeButton = Button(this).apply {
            text = "Close"
            setOnClickListener { overlay.visibility = View.GONE }
        }
        titleRow.addView(closeButton)
        overlay.addView(titleRow)

        val scroll = android.widget.ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        scroll.addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        overlay.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        bodyOut(body)
        return overlay
    }

    /** PHASE 5BC: onboarding copy shown once per install, plainly — this
     *  supplements and does not replace a PLB or satellite messenger. */
    private fun maybeShowSosOnboarding() {
        val prefs = getSharedPreferences("opencall", MODE_PRIVATE)
        if (prefs.getBoolean("sos_onboarding_shown", false)) return
        AlertDialog.Builder(this)
            .setTitle("Before you rely on SOS")
            .setMessage(
                "This mesh SOS works over Wi-Fi Direct only — it has no satellite or " +
                    "cellular link. It can only reach party members within Wi-Fi Direct " +
                    "range (roughly 50-200m line of sight, much less through snow, rock, " +
                    "or bodies), or who later come back into range and pick up a cached " +
                    "alert.\n\nIt supplements, but does NOT replace, a personal locator " +
                    "beacon (PLB) or satellite messenger. Carry one on any serious trip."
            )
            .setPositiveButton("Understood", null)
            .setCancelable(false)
            .show()
        prefs.edit().putBoolean("sos_onboarding_shown", true).apply()
    }

    /** PHASE 3B: large active-speaker area (video, or a text placeholder in audio
     *  mode / when nobody's currently speaking) + a horizontally-scrolling
     *  participant strip below it. Built once here, shown only once a group call is
     *  actually running (see [onGroupCallStarted]). */
    private fun buildGroupCallScreen(root: LinearLayout) {
        val density = resources.displayMetrics.density
        groupCallScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        groupCallStatusText = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        groupCallScreen.addView(groupCallStatusText)

        // PHASE 3C: the grid itself — cell count/shape is recomputed on every
        // participants change (see gridDimensionsFor); tiles are built once per
        // participant and reparented into new cells on each rebuild rather than
        // recreated, so a live decoder's Surface binding survives a resize.
        groupCallGrid = GridLayout(this)
        groupCallScreen.addView(
            groupCallGrid,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        // PHASE 3C: local controls bar — camera toggle (sends TYPE_CAM, starts/
        // stops the local encoder once the GO confirms), mic hard-mute toggle,
        // leave call.
        groupCallCameraButton = Button(this).apply {
            text = "Camera: Off"
            setOnClickListener {
                // FIX G: decide what to request from the transport's REAL camera
                // state, not the groupCallLocalCameraOn mirror alone — that mirror
                // only ever advances on a confirmed onGroupCallCamState callback,
                // so if a prior request was ever dropped/denied and the mirror
                // stayed at its correct "false", this still asks for "true" again
                // (fine); but guarding against drift here means a future desync
                // between the mirror and reality can never turn this button into a
                // silent no-op either way.
                val actuallyOn = mediaTransport?.isLocalCameraOn() ?: groupCallLocalCameraOn
                val turningOn = !actuallyOn
                mediaTransport?.setGroupCallCameraOn(turningOn)
                // Turning OFF is always accepted, so it's safe to reflect right
                // away; turning ON waits for onGroupCallCamState (accepted) or
                // onGroupCallCamDenied (cap reached) before the button/tile change.
                if (!turningOn) {
                    groupCallLocalCameraOn = false
                    updateGroupCallControlsBar()
                }
            }
        }
        groupCallMicButton = Button(this).apply {
            text = "Mic: On"
            setOnClickListener {
                groupCallLocalMicMuted = !groupCallLocalMicMuted
                mediaTransport?.setGroupCallMicMuted(groupCallLocalMicMuted)
                updateGroupCallControlsBar()
            }
        }
        leaveGroupCallButton = Button(this).apply {
            text = "Leave call"
            setOnClickListener { mediaTransport?.leaveGroupCall() }
        }
        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, (8 * density).toInt(), 0, (16 * density).toInt())
            addView(groupCallCameraButton)
            addView(groupCallMicButton)
            addView(leaveGroupCallButton)
        }
        groupCallScreen.addView(controlsRow)

        // FIX: root.addView(groupCallScreen) with no explicit LayoutParams used to
        // fall back to LinearLayout's default WRAP_CONTENT/WRAP_CONTENT — the
        // classic Android weight-collapse trap: groupCallGrid's OWN
        // LayoutParams(MATCH_PARENT, 0, 1f) relative to groupCallScreen were
        // already correct, but a weighted child's "0 + weight" only resolves
        // against space its PARENT actually has to distribute, and a WRAP_CONTENT
        // groupCallScreen has none — so groupCallGrid (and every tile inside it)
        // measured to a real width but zero height. root itself IS bounded
        // (MATCH_PARENT via setContentView), so giving groupCallScreen a weight
        // here is enough to fix the whole chain without touching the per-tile
        // GridLayout.LayoutParams at all.
        root.addView(
            groupCallScreen,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f }
        )
    }

    /** Point 7: 1 = full, 2 = split, 3-4 = 2x2, 5-9 = 3x3. MAX_GROUP_PARTICIPANTS
     *  (8, see OfflineMediaTransport) never exceeds the 3x3 = 9-cell ceiling here. */
    private fun gridDimensionsFor(n: Int): Pair<Int, Int> = when {
        n <= 1 -> 1 to 1
        n == 2 -> 1 to 2
        n <= 4 -> 2 to 2
        else -> 3 to 3
    }

    private fun initialsFor(name: String): String {
        val letters = name.trim().split(Regex("\\s+")).mapNotNull { it.firstOrNull()?.uppercaseChar() }
        return if (letters.isEmpty()) "?" else letters.take(2).joinToString("")
    }

    /** Builds one tile's view tree — video Surface underneath, avatar/initials
     *  overlay above it (visibility toggled by camera state, never both hidden),
     *  name/speaking-indicator/battery overlays on top of both. Registers this
     *  tile's Surface with the transport as either the LOCAL camera preview target
     *  (this device's own id — see setLocalPreviewSurface) or a remote sender's
     *  decode target (setGroupTileSurface) the moment the SurfaceView is created,
     *  and unregisters it on destroy. */
    private fun createGroupTile(nodeId: Long): GroupTile {
        val isMe = nodeId == mediaTransport?.localNodeId
        val container = FrameLayout(this)
        val surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.d("OFFTRACE", "UI: surfaceCreated tile=${MeshFrame.hex(nodeId)} " +
                    "${holder.surfaceFrame.width()}x${holder.surfaceFrame.height()}")
                if (isMe) mediaTransport?.setLocalPreviewSurface(holder.surface)
                else mediaTransport?.setGroupTileSurface(nodeId, holder.surface)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (isMe) mediaTransport?.setLocalPreviewSurface(null)
                else mediaTransport?.setGroupTileSurface(nodeId, null)
            }
        })
        container.addView(surfaceView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val avatarText = TextView(this).apply {
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(255, 55, 55, 65))
        }
        container.addView(avatarText, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val nameLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            setPadding(12, 4, 12, 4)
        }
        container.addView(
            nameLabel,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.START
            }
        )

        val speakingDot = TextView(this).apply { text = "🔊"; textSize = 14f; visibility = View.GONE }
        container.addView(
            speakingDot,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.END
            }
        )

        val batteryText = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 10f
            visibility = View.GONE
        }
        container.addView(
            batteryText,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.END
            }
        )

        val tile = GroupTile(container, surfaceView, avatarText, nameLabel, speakingDot, batteryText)
        container.setOnClickListener { onTileTapped(nodeId) }
        // Point 7's "letterboxed via the existing aspect fix" — re-run on every
        // resize of THIS tile's own cell (grid rebuild, rotation, fullscreen
        // toggle), same pattern as the 1:1/old speaker view's frame listener.
        container.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if ((right - left) != (oldRight - oldLeft) || (bottom - top) != (oldBottom - oldTop)) {
                letterbox(tile.surfaceView, tile.container, tile.videoWidth, tile.videoHeight)
            }
        }
        return tile
    }

    // ── TASK 1: remote video letterboxing ───────────────────────────────────────

    /** Shared math behind [applyVideoAspectRatio] (1:1) and [applyTileAspectRatio]
     *  (PHASE 3C, per grid tile) — resizes [view] to the largest centered rect that
     *  fits [parent] while preserving the decoder's actual width:height ratio —
     *  letterbox, never crop or stretch. Safe to call with a size before layout has
     *  happened; the caller's own layout-change listener re-runs it once real
     *  dimensions are available. Returns the (width, height) actually applied, or
     *  null if it couldn't run yet (no valid video size, view, or parent layout) —
     *  callers that log a resize (see [applyTileAspectRatio]) key off this return
     *  value. */
    private fun letterbox(view: SurfaceView?, parent: FrameLayout, videoWidth: Int, videoHeight: Int): Pair<Int, Int>? {
        if (videoWidth <= 0 || videoHeight <= 0) return null
        val parentWidth = parent.width
        val parentHeight = parent.height
        if (parentWidth <= 0 || parentHeight <= 0) return null // not laid out yet

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

        val v = view ?: return null
        val params = v.layoutParams as? FrameLayout.LayoutParams
            ?: FrameLayout.LayoutParams(targetWidth, targetHeight)
        params.width = targetWidth
        params.height = targetHeight
        params.gravity = Gravity.CENTER
        v.layoutParams = params
        return targetWidth to targetHeight
    }

    private fun applyVideoAspectRatio(videoWidth: Int, videoHeight: Int) {
        remoteVideoWidth = videoWidth
        remoteVideoHeight = videoHeight
        letterbox(mediaRemoteView, videoFrame, videoWidth, videoHeight)
    }

    /** PHASE 3C: per-tile letterboxing — same shared [letterbox] math as the 1:1
     *  screen, targeting one grid tile's own SurfaceView/container instead of the
     *  old single active-speaker view. Re-run on every resize of that tile's own
     *  cell (see the addOnLayoutChangeListener in [createGroupTile]) and every time
     *  [OfflineMediaTransport.onGroupTileVideoSize] fires for that sender. */
    private fun applyTileAspectRatio(nodeId: Long, videoWidth: Int, videoHeight: Int) {
        val tile = groupTiles[nodeId] ?: return
        tile.videoWidth = videoWidth
        tile.videoHeight = videoHeight
        val rect = letterbox(tile.surfaceView, tile.container, videoWidth, videoHeight)
        if (rect != null) {
            Log.d("OFFTRACE", "VIDEO: tile ${shortId(nodeId)} sized ${videoWidth}x${videoHeight} -> rect ${rect.first}x${rect.second}")
        }
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
     *  to fill the whole screen instead of floating over video — this is also what
     *  the group-chat-only screen uses (PHASE 3). */
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

    /** Neutral baseline restored on return-to-roster/leave-group so a later call in a
     *  different mode doesn't inherit stale layout. */
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
        val sent = if (isGroupChatScreen) {
            mediaTransport?.sendGroupChat(text) ?: false
        } else {
            mediaTransport?.sendChat(text) ?: false
        }
        if (!sent) {
            Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show()
            return
        }
        appendChatMessage(text, fromMe = true)
        chatInput.setText("")
    }

    /** PHASE 3: routes an incoming chat frame to the overlay — [isGroup] messages are
     *  labeled so they're not mistaken for the current 1:1 call partner's message. */
    private fun onTransportChatMessage(fromName: String, text: String, isGroup: Boolean) {
        val label = if (isGroup) "[Group] $fromName" else fromName
        appendChatMessage("$label: $text", fromMe = false)
    }

    /** Called on return-to-roster/leave-group — chat is in-memory for the
     *  call/session's duration only. */
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
        // PHASE 6 TRACK C: BLE presence/beacon — runtime permissions only exist
        // from API 31; below that, plain manifest-level BLUETOOTH/BLUETOOTH_ADMIN
        // (maxSdkVersion=30) plus the already-requested ACCESS_FINE_LOCATION above
        // cover it, nothing further to request here.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
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
        // BUG 2 FIX: DNS-SD scan runs alongside ordinary peer discovery — same
        // one-shot-per-call semantics, see WifiDirectManager.discoverServices's doc.
        wifiDirect.discoverServices()
    }

    private fun onPeersChanged(list: WifiP2pDeviceList) {
        devices = list.deviceList.toList()
        refreshPeerListUi()
        statusText.text = if (devices.isEmpty()) "No devices found yet" else "${devices.size} device(s) found"
        // FIX 6b: refresh the roster's "Add to group" rows if we're the GO and
        // currently showing that screen — discovery keeps running in the background
        // (see onConnectionChangedInternal) specifically to keep this list current.
        if (isLocalGroupOwner && groupScreen.visibility == View.VISIBLE) {
            renderRosterList()
        }
        // PHASE 6 TRACK E: see autoInviteDuringElection's doc — no-op unless
        // this device just became GO via re-election and is waiting to reclaim
        // its old party.
        autoInviteDuringElection()
    }

    /** BUG 2 FIX: prefers the (unverified — see [dnsSdNamesByAddress]'s doc)
     *  DNS-SD name so a name set before pairing is visible here; falls back to
     *  the raw OS-level Wi-Fi Direct device name only when no TXT record has
     *  arrived yet for that address. Called both after an ordinary peers-changed
     *  broadcast and whenever a TXT record arrives on its own, so an
     *  already-visible row upgrades in place without waiting for the next scan. */
    private fun refreshPeerListUi() {
        val names = devices.map { "${dnsSdNamesByAddress[it.deviceAddress] ?: it.deviceName} (${it.deviceAddress})" }
        peerListView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
    }

    /** PHASE 3: tapping a nearby device now JOINS a group with them — it no longer
     *  picks a call mode up front. The mode dialog moved to the roster screen (see
     *  [onRosterItemClicked]), since a call now targets a specific member, chosen
     *  only once the group's membership is known. */
    private fun onPeerSelected(device: WifiP2pDevice) {
        if (connectRequested) {
            Log.d("OFFTRACE", "connect already in progress, ignoring tap")
            return
        }
        // FIX 6c: this device's own connect() is only reliable for the FIRST pairing
        // (forming a brand-new group). A device that's already the owner of an
        // existing group is unreliable to connect() into from here — the correct
        // path is the GO inviting this device in (see invitePeerToGroup /
        // WifiDirectManager.invitePeer), not blindly retrying a client-initiated
        // connect against an already-formed group.
        if (device.isGroupOwner) {
            Toast.makeText(this, "${device.deviceName} already has a group — ask them to add you", Toast.LENGTH_LONG).show()
            return
        }
        connectedPeerName = device.deviceName ?: ""
        connectToPeer(device)
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
                    if (device.isGroupOwner) {
                        Toast.makeText(this, "Ask ${device.deviceName} to add you to their group", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Connect failed", Toast.LENGTH_SHORT).show()
                    }
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

    // ── Connection → local signaling → mesh transport ───────────────────────────

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
        // IDLE-SESSION FIX: updated unconditionally, BEFORE the "ignoring stale/
        // teardown connInfo" early return below — that return used to make a real
        // mid-session group breakage indistinguishable from noise as far as any other
        // code was concerned. mediaTransport's reconnect logic reads this via the
        // isGroupFormed lambda passed to its constructor.
        currentGroupFormed = info.groupFormed
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
        if (signaling != null) return // already wired up for this group session

        isLocalGroupOwner = info.isGroupOwner
        statusText.text = "Connected — joining group..."
        searchScreen.visibility = View.GONE
        groupScreen.visibility = View.VISIBLE
        errorText.visibility = View.GONE

        if (isLocalGroupOwner) {
            // FIX 6d: log/stash the group's SSID — foundation for a legacy-join
            // fallback later, not consumed anywhere yet.
            // CAP PROBE: temporary read-only diagnostic riding the same callback —
            // see CapabilityProbe.kt.
            wifiDirect.requestGroupInfo { group -> CapabilityProbe.logGroupFormedCapabilities(group) }
            // FIX 6b: keep discovery running so this device (the GO) keeps seeing
            // nearby non-member peers to offer "Add to group" for on the roster
            // screen — see renderRosterList/onPeersChanged.
            wifiDirect.startDiscovery { ok ->
                if (!ok) Log.w("OFFTRACE", "MESH: GO could not resume discovery for invites")
            }
        }

        // FIX: LocalSignaling is now just the underlying-connection hangup/keepalive
        // channel (see LocalSignaling.kt) — losing it means the whole WiFi Direct group
        // connection is gone, not just one call (see leaveGroup()).
        val local = LocalSignaling(info.isGroupOwner, info.groupOwnerAddress)
        signaling = local
        local.onConnected = { statusText.text = "Signaling connected" }
        local.onError = { err -> statusText.text = "Signaling error: $err" }
        // IDLE-SESSION FIX: a signaling-channel keepalive timeout (now 45s, up from
        // 15s — see LocalSignaling) no longer tears the group down by itself if the
        // MEDIA channel still looks alive — that channel has its own reconnect logic
        // (see OfflineMediaTransport.attemptClientReconnect) and is the better signal
        // for whether the underlying WiFi Direct link is actually still usable. Only
        // once the media transport has ALSO given up (mediaLinkAlive == false, set by
        // onLinkLost below) does a signaling loss actually end the group.
        local.onPeerGone = { reason ->
            runOnUiThread {
                if (mediaLinkAlive) {
                    Log.w("OFFTRACE", "signaling lost ($reason) but media link still alive — not leaving group")
                } else {
                    leaveGroup("signaling: $reason")
                }
            }
        }
        local.onDegraded = { silentMs ->
            runOnUiThread {
                Log.w("OFFTRACE", "SIG: link degraded, ${silentMs / 1000}s silent")
                statusText.text = "Reconnecting…"
            }
        }
        local.onRecovered = {
            runOnUiThread {
                statusText.text = if (roster.isNotEmpty()) "Group: ${roster.size} member(s)" else "Signaling connected"
            }
        }
        local.start()

        mediaLinkAlive = true
        // FIX 3: must run before constructing a new transport — if the system
        // destroyed-and-recreated this Activity while backgrounded (see FIX 4's
        // isFinishing-gated onDestroy), an old transport instance may still be alive
        // and bound to port 8889; constructing a second one without stopping the
        // first would BindException the new one's accept loop into never starting,
        // silently orphaning new joins on a transport nobody's UI is listening to.
        OfflineMediaTransport.stopOrphanedInstance()
        val transport = OfflineMediaTransport(
            applicationContext,
            info.isGroupOwner,
            info.groupOwnerAddress,
            localDisplayName = OfflineIdentity.displayName(applicationContext),
            isGroupFormed = { currentGroupFormed },
            onError = { msg ->
                runOnUiThread {
                    // errorText lives inside callScreen only — PHASE 3B added a THIRD
                    // screen (groupCallScreen) that doesn't contain it, so a Toast is
                    // the one channel guaranteed visible regardless of which screen is
                    // currently showing.
                    errorText.text = msg
                    errorText.visibility = View.VISIBLE
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    Log.e("OfflineMediaTransport", msg)
                }
            }
        )
        // PHASE 3: onLinkLost now means the MESH session itself is gone (a client's
        // one uplink dying after exhausting its own reconnect attempts, or the GO's
        // own accept loop failing) — full leaveGroup(), same as the old link-lost
        // teardown used to be for the single 1:1 call.
        transport.onLinkLost = { uiMessage ->
            runOnUiThread {
                mediaLinkAlive = false
                leaveGroup("media link lost", uiMessage)
            }
        }
        // IDLE-SESSION FIX: fires per reconnect attempt (see attemptClientReconnect) —
        // not fatal by itself, onLinkLost still fires if every attempt fails.
        transport.onReconnecting = { attempt, max ->
            runOnUiThread { statusText.text = "Reconnecting to group… ($attempt/$max)" }
        }
        // PHASE 3C: onVideoSize is now 1:1-only — group video sizing arrives per
        // sender via onGroupTileVideoSize instead (multiple simultaneous streams,
        // not one).
        transport.onVideoSize = { w, h -> runOnUiThread { applyVideoAspectRatio(w, h) } }
        transport.onGroupTileVideoSize = { nodeId, w, h -> runOnUiThread { applyTileAspectRatio(nodeId, w, h) } }
        transport.onGroupCallCamState = { nodeId, on ->
            runOnUiThread {
                groupCallCamStates[nodeId] = on
                if (nodeId == mediaTransport?.localNodeId) {
                    groupCallLocalCameraOn = on
                    updateGroupCallControlsBar()
                }
                groupTiles[nodeId]?.let { updateTileContent(nodeId, it) }
            }
        }
        transport.onGroupCallCamDenied = {
            runOnUiThread {
                groupCallLocalCameraOn = false
                updateGroupCallControlsBar()
                Toast.makeText(
                    this,
                    "Too many cameras on (4 max) — ask someone to turn theirs off",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        transport.onChatMessage = { _, fromName, text, isGroup ->
            runOnUiThread { onTransportChatMessage(fromName, text, isGroup) }
        }
        // PHASE 3: fires for BOTH the initiator (right after placeCall) and the callee
        // (auto-answered) — see onCallStarted for how each is handled.
        transport.onModeResolved = { _, peerName, mode -> runOnUiThread { onCallStarted(peerName, mode) } }
        transport.onCallEnded = { reason -> runOnUiThread { onCallEndedRemotely(reason) } }
        transport.onCallBusy = { peerName ->
            runOnUiThread {
                Toast.makeText(this, "$peerName is busy", Toast.LENGTH_SHORT).show()
                returnToRosterScreen()
            }
        }
        transport.onRosterUpdated = { members -> runOnUiThread { updateRosterUi(members) } }
        // PHASE 3B: group call wiring.
        transport.onGroupCallInvite = { _, fromName, mode, callId ->
            runOnUiThread { showGroupCallInviteDialog(fromName, mode, callId) }
        }
        transport.onGroupCallStarted = { mode, _ -> runOnUiThread { enterGroupCallScreen(mode) } }
        transport.onGroupCallParticipants = { ids ->
            runOnUiThread {
                groupCallParticipants = ids
                syncGroupTiles()
                rebuildGroupCallGrid()
                updateGroupCallStatusText()
            }
        }
        // PHASE 3C: video is no longer gated to a single active speaker — this now
        // ONLY drives the highlight border/indicator on that participant's tile
        // (see updateTileContent), per point 1's "keep TYPE_SPEAKER/VAD ONLY for
        // highlight" requirement. No camera/decoder/letterbox side effects here.
        transport.onGroupCallSpeaker = { nodeId, pinned ->
            runOnUiThread {
                groupCallActiveSpeaker = nodeId
                groupCallPinned = pinned
                groupTiles.forEach { (id, tile) -> updateTileContent(id, tile) }
            }
        }
        // FIX 1c: "no one answered" is the ringing timeout — worth a distinct toast
        // rather than just quietly returning to the roster like a normal call end.
        transport.onGroupCallEnded = { reason ->
            runOnUiThread {
                if (reason == "no one answered") {
                    Toast.makeText(this, "No one answered", Toast.LENGTH_SHORT).show()
                }
                exitGroupCallScreen(reason)
            }
        }
        transport.onGroupCallRejected = { reason -> runOnUiThread { Toast.makeText(this, reason, Toast.LENGTH_LONG).show() } }
        // PHASE 5A: SOS/FIND — independent of call state, so wired unconditionally
        // alongside the roster callback above rather than anywhere call-specific.
        transport.onSosEntry = { entry ->
            runOnUiThread {
                sosEntries[entry.srcId] = entry
                renderSosAlerts()
                val activeSenders = transport.activeSosSenderIds()
                sosAlarm.onActiveSendersChanged(activeSenders)
                renderSosOverlay(activeSenders)
                sosOverlay.visibility = if (activeSenders.isNotEmpty()) View.VISIBLE else View.GONE
                if (activeSenders.isNotEmpty()) {
                    Log.d(
                        "OFFTRACE",
                        "SOS: alert shown from=${MeshFrame.hex(entry.srcId)} tier=${lastKnownTier(entry.srcId)} " +
                            "dist=${lastKnownDistanceM(entry.srcId)} vert=${lastKnownVerticalM(entry.srcId)}"
                    )
                }
            }
        }
        transport.onFindResponse = { entry ->
            runOnUiThread {
                findResponses[entry.srcId] = entry
                renderSosAlerts()
            }
        }
        // PHASE 5BC: our own SOS's "SEEN BY k/N" progress.
        transport.onSosAckProgress = { seenBy, total ->
            runOnUiThread { updateSosButtonUi(seenBy, total) }
        }
        // PHASE 5BC: any peer's ledger track updated — refresh whichever overlay
        // is currently visible.
        transport.onPositionUpdated = {
            runOnUiThread {
                if (sosOverlay.visibility == View.VISIBLE) renderSosOverlay(transport.activeSosSenderIds())
                if (partyStatusOverlay.visibility == View.VISIBLE) renderPartyStatus()
            }
        }
        // PHASE 6 TRACK C: a BLE-only sighting changed — refresh party status if
        // it's the screen currently showing (same "only redraw if visible"
        // pattern as onPositionUpdated above).
        transport.onBlePresenceUpdated = {
            runOnUiThread { if (partyStatusOverlay.visibility == View.VISIBLE) renderPartyStatus() }
        }
        // PHASE 6 TRACK E: self-healing GO re-election.
        transport.onGoLost = { runOnUiThread { handleGoLost() } }
        transport.onElectionResult = { winnerId, isSelf -> runOnUiThread { handleElectionResult(winnerId, isSelf) } }
        transport.onSplitBrainDetected = { runOnUiThread { handleSplitBrainStandDown() } }
        // PHASE 6 TRACK B3: cellular relay — "TAP TO SEND" confirmation.
        transport.onRelayPromptReady = { prompt -> runOnUiThread { showRelayPrompt(transport, prompt) } }
        // PHASE 6 TRACK B2: hands-free trigger countdown — the notification is
        // the reliable always-available surface (see SosTriggers' class doc);
        // this is just an in-app echo while the Activity happens to be visible.
        transport.onTriggerCountdownTick = { secondsRemaining, name ->
            // Only announce once, at the start — the notification (with its own
            // live countdown + Cancel action) is the reliable surface for the
            // rest of the window; a Toast every tick for 30s would just spam.
            if (secondsRemaining >= 29) {
                runOnUiThread {
                    Toast.makeText(this, "SOS trigger ($name) — 30s to cancel via the notification", Toast.LENGTH_LONG).show()
                }
            }
        }
        mediaTransport = transport
        // Both roles show the remote peer's camera on mediaRemoteView. Surface may
        // already be ready if the SurfaceView layout completed synchronously; if not,
        // the surfaceCreated callback above will call setDisplaySurface once it is.
        mediaSurface?.let { transport.setDisplaySurface(it) }
        transport.start()

        // PHASE 6 TRACK E: this fresh transport is the result of a completed
        // re-election (either we just called createGroup() as the winner, or
        // we were just invited by the new GO as a follower) — resume anything
        // that only lived on the OLD, now-discarded transport instance. See
        // handleGoLost's doc for exactly what does and doesn't survive.
        if (reconnectingAfterGoLoss) {
            reconnectingAfterGoLoss = false
            invitedDuringElection.clear()
            transport.meshElection.resetWatchdog()
            statusText.text = "Reconnected — new group owner ${if (isLocalGroupOwner) "(this device)" else ""}"
            if (sosActive) {
                // Our own SOS lived on the OLD MeshSosManager instance, which is
                // gone with the old transport — MeshCarrier (a process-wide
                // singleton, unaffected by this transport swap) still holds the
                // queued message and keeps offering it to reconnecting peers,
                // but the LIVE 30s-repeat beacon needs re-arming on the new
                // instance, or it silently stops.
                transport.startSos(null)
            }
            groupCallModeBeforeGoLoss?.let { mode ->
                groupCallModeBeforeGoLoss = null
                // Cold rebuild, not live continuity — see this track's design
                // decision doc: a fresh callId, existing/tested startGroupCall
                // path, every reconnected member re-announces (participants,
                // camera slots, VAD) rather than any state being transferred.
                Toast.makeText(this, "Restarting group call after reconnect…", Toast.LENGTH_SHORT).show()
                transport.startGroupCall(mode)
            }
        }

        // FIX 3: anchor process priority for the duration of the group session so OEM
        // battery managers don't kill us the moment the Activity is backgrounded.
        startOfflineCallService()

        // FIX 5: the foreground service alone doesn't stop aggressive OEM battery
        // managers from throttling background networking/CPU — ask, once per process,
        // for the standard exemption.
        maybePromptBatteryExemption()
    }

    /** FIX 5: shown at most once per process (see batteryPromptShown), only if the
     *  system doesn't already consider this app exempt. Declining is not re-prompted
     *  this session — the user can still grant it later from system battery settings. */
    private fun maybePromptBatteryExemption() {
        if (batteryPromptShown) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        batteryPromptShown = true
        AlertDialog.Builder(this)
            .setTitle("Keep this group connected")
            .setMessage(
                "Exempting OpenCall from battery optimization keeps this offline group " +
                    "(and any calls in it) alive when your screen is off."
            )
            .setPositiveButton("Continue") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                } catch (e: Exception) {
                    Log.w("OFFTRACE", "battery exemption prompt failed: ${e.message}")
                }
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    // ── PHASE 3: roster screen ───────────────────────────────────────────────────

    private fun updateRosterUi(members: List<RoutingTable.Member>) {
        roster = members
        renderRosterList()
        statusText.text = "Group: ${members.size} member(s)"
    }

    /** FIX 6b: rebuilds the roster ListView from [roster] plus — GO only — a
     *  trailing "Add to group" row per nearby discovered peer not already part of
     *  this Wi-Fi Direct connection (status != CONNECTED). Newcomers are added BY
     *  the group (GO-initiated invite, see invitePeerToGroup) rather than
     *  self-joining, since a device trying to connect() into an already-formed
     *  group on its own is unreliable — see FIX 6c on onPeerSelected. */
    private fun renderRosterList() {
        val localId = mediaTransport?.localNodeId
        val labels = mutableListOf("Group chat  (${roster.size} member(s))", "Start group call")
        roster.forEach { m ->
            val youTag = if (m.nodeId == localId) " (you)" else ""
            labels.add("${m.name}$youTag  ${shortId(m.nodeId)}")
        }
        invitableDevices = if (isLocalGroupOwner) {
            devices.filter { it.status != WifiP2pDevice.CONNECTED }
        } else {
            emptyList()
        }
        invitableDevices.forEach { d -> labels.add("Add to group: ${d.deviceName}") }
        rosterListView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
    }

    private fun shortId(id: Long): String = String.format("%016x", id).takeLast(6)

    /** Position 0/1 are the synthetic "Group chat"/"Start group call" rows; the next
     *  [roster].size positions map 1:1 onto [roster]; anything after that maps onto
     *  [invitableDevices] (FIX 6b, GO only). */
    private fun onRosterItemClicked(position: Int) {
        when (position) {
            0 -> { openGroupChat(); return }
            1 -> { showStartGroupCallDialog(); return }
        }
        val idx = position - 2
        if (idx < roster.size) {
            val member = roster[idx]
            if (member.nodeId == mediaTransport?.localNodeId) {
                Toast.makeText(this, "That's you", Toast.LENGTH_SHORT).show()
                return
            }
            showCallModeDialog(member)
            return
        }
        val device = invitableDevices.getOrNull(idx - roster.size) ?: return
        invitePeerToGroup(device)
    }

    /** PHASE 5A: long-press companion to [onRosterItemClicked] — same position
     *  math (0/1 are synthetic rows, only an actual [roster] row can be found),
     *  but sends a FIND_REQ instead of opening the call-mode dialog. Returns
     *  whether the long-press was consumed, per ListView's listener contract. */
    private fun onRosterItemLongClicked(position: Int): Boolean {
        val idx = position - 2
        if (idx < 0 || idx >= roster.size) return false
        val member = roster[idx]
        if (member.nodeId == mediaTransport?.localNodeId) {
            Toast.makeText(this, "That's you", Toast.LENGTH_SHORT).show()
            return true
        }
        mediaTransport?.sendFindRequest(member.nodeId)
        Toast.makeText(this, "Finding ${member.name}…", Toast.LENGTH_SHORT).show()
        return true
    }

    /** PHASE 5BC: [seenBy]/[total] show "SEEN BY k/N" (or "NOT YET SEEN BY
     *  ANYONE") while our own SOS is active — a climber needs to know whether
     *  anyone got it, since it changes what they do next. */
    private fun updateSosButtonUi(seenBy: Int = -1, total: Int = -1) {
        if (sosActive) {
            val progress = mediaTransport?.sosAckProgress()
            val k = if (seenBy >= 0) seenBy else progress?.first ?: 0
            val n = if (total >= 0) total else progress?.second ?: 0
            sosButton.text = if (k > 0) "SOS ACTIVE — SEEN BY $k/$n (tap to cancel)"
                else "SOS ACTIVE — NOT YET SEEN BY ANYONE (tap to cancel)"
            sosButton.setBackgroundColor(Color.RED)
        } else {
            sosButton.text = "SOS"
            sosButton.setBackgroundColor(Color.DKGRAY)
        }
        sosButton.setTextColor(Color.WHITE)
    }

    /** PHASE 5A: renders every currently-active incoming SOS plus every FIND_RESP
     *  received so far into [sosAlertsText] — a separate element from
     *  rosterListView (see buildUi's doc comment on it) so an incoming SOS can
     *  never be silently folded into the ordinary peer list. Hidden entirely
     *  when there's nothing to show. */
    private fun renderSosAlerts() {
        val activeSos = sosEntries.values.filter { it.active }
        val lines = mutableListOf<String>()
        activeSos.forEach { e ->
            // BUG 1 FIX 3: a historical (non-alarmable — replayed >30min old, or
            // locally auto-stopped after 5min, see MeshSosManager.SosEntry) entry
            // is still shown, but never with the siren emoji — a day-old
            // replayed record and a live emergency must never look the same.
            val marker = if (e.alarmable) "🆘" else "🕐 HISTORICAL"
            lines.add("$marker ${nameForGroupParticipant(e.srcId)}: ${formatLocationLine(e)}")
        }
        findResponses.values.forEach { e -> lines.add("📍 ${nameForGroupParticipant(e.srcId)}: ${formatLocationLine(e)}") }
        if (lines.isEmpty()) {
            sosAlertsText.visibility = View.GONE
            return
        }
        sosAlertsText.visibility = View.VISIBLE
        sosAlertsText.text = lines.joinToString("\n")
        sosAlertsText.setTextColor(Color.WHITE)
        // Visually distinct: a live incoming SOS gets a strong red background,
        // never mistaken for an ordinary status line.
        sosAlertsText.setBackgroundColor(
            if (activeSos.isNotEmpty()) Color.argb(230, 180, 0, 0) else Color.argb(200, 40, 40, 40)
        )
    }

    /** "lat, lon (±Nm), Xm ago" when [MeshSosManager.SosEntry.hasFix], else "no
     *  GPS fix" — full coordinate precision is fine here (UI only); log lines
     *  elsewhere truncate to 3 decimal places instead (see MeshSosManager). */
    private fun formatLocationLine(entry: MeshSosManager.SosEntry): String {
        if (!entry.hasFix) return "no GPS fix"
        // FIX 4: receivedAtMs is our own local clock, but still clamp — a manual
        // clock change/NTP correction mid-session is rare but not impossible.
        val ageMin = ((System.currentTimeMillis() - entry.receivedAtMs) / 60_000).coerceAtLeast(0L)
        val acc = entry.accuracyMeters?.let { "±${it}m" } ?: "±?m"
        return String.format("%.5f, %.5f (%s), %dm ago", entry.latitude, entry.longitude, acc, ageMin)
    }

    // ── PHASE 5BC: SOS alert / party-status overlays ────────────────────────────

    /** Full-screen SOS alert body — one card per active sender, most recent
     *  first. Rebuilt from scratch on every call (cheap — bounded by party size,
     *  never more than ~8 rows in this mesh — see the survey behind this phase). */
    private fun renderSosOverlay(activeSenderIds: Set<Long>) {
        sosOverlayBody.removeAllViews()
        val ordered = activeSenderIds
            .mapNotNull { id -> sosEntries[id]?.let { id to it } }
            .sortedByDescending { it.second.receivedAtMs }
        if (ordered.isEmpty()) return
        ordered.forEach { (id, entry) -> sosOverlayBody.addView(buildMemberCard(id, isActiveSos = true)) }
        // entry is only used to sort; formatting reads fresh from the ledger.
    }

    /** Every known ledger member, including anyone currently out of contact —
     *  the "where is everyone" screen. Self is skipped (nothing useful to show
     *  about your own position relative to yourself). */
    private fun renderPartyStatus() {
        partyStatusOverlayBody.removeAllViews()
        val transport = mediaTransport ?: return
        val ids = transport.ledger.knownNodeIds().filter { it != transport.localNodeId }
        if (ids.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No position data yet."
                setTextColor(Color.LTGRAY)
            }
            partyStatusOverlayBody.addView(empty)
            return
        }
        ids.forEach { id -> partyStatusOverlayBody.addView(buildMemberCard(id, isActiveSos = false)) }
    }

    /** One member's detail card — shared by the SOS alert and the party-status
     *  screen (see class doc). Short lines, large type, no jargon: this screen
     *  matters most to someone cold, exhausted, and frightened. */
    private fun buildMemberCard(nodeId: Long, isActiveSos: Boolean): LinearLayout {
        val transport = mediaTransport
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(if (isActiveSos) Color.argb(230, 120, 0, 0) else Color.argb(180, 40, 40, 40))
        }
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 16) }

        fun addLine(text: String, sizeSp: Float = 18f, bold: Boolean = false) {
            card.addView(
                TextView(this).apply {
                    this.text = text
                    textSize = sizeSp
                    setTextColor(Color.WHITE)
                    if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
            )
        }

        val name = nameForGroupParticipant(nodeId)
        addLine("${if (isActiveSos) "🆘 " else ""}$name  ${shortId(nodeId)}", sizeSp = 20f, bold = true)

        val ledger = transport?.ledger
        val latest = ledger?.latestEntry(nodeId)
        val lost = ledger?.lostContactFor(nodeId)
        val myEntry = transport?.let { ledger?.latestEntry(it.localNodeId) }

        if (latest == null) {
            // PHASE 6 TRACK C: BLE-only sighting — a genuinely new state: this
            // member isn't reachable over Wi-Fi Direct right now, but their
            // beacon is audible, so walking toward them will re-form the mesh.
            // Never presented as a fix — no distance number, only a trend.
            val presence = ledger?.blePresenceFor(nodeId)
            if (presence != null && System.currentTimeMillis() - presence.seenAtMs < 30_000L) {
                val trendWord = when (presence.trend) {
                    MeshLedger.Trend.CLOSER -> "getting closer"
                    MeshLedger.Trend.FARTHER -> "moving away"
                    MeshLedger.Trend.STEADY -> "steady distance"
                    MeshLedger.Trend.UNKNOWN -> "trend not yet known"
                }
                addLine("NEARBY, NOT CONNECTED — $trendWord (BLE)", bold = true)
            } else {
                addLine("No location — signal strength only: unavailable")
            }
        } else {
            // FIX 4: local clock, but clamp for the same reason as formatLocationLine.
            val ageSec = ((System.currentTimeMillis() - latest.receivedAtMs) / 1000).coerceAtLeast(0L)
            if (latest.tier == MeshLocation.LOC_TIER_NONE || (latest.latE7 == 0 && latest.lonE7 == 0)) {
                addLine("No location — no fix (${ageSec}s ago)")
            } else {
                val lat = latest.latitude
                val lon = latest.longitude
                addLine(String.format("%.5f, %.5f", lat, lon))
                addLine(GeoUtils.toMgrs(lat, lon))
                if (myEntry != null && !(myEntry.latE7 == 0 && myEntry.lonE7 == 0)) {
                    val dist = GeoUtils.haversineMeters(myEntry.latitude, myEntry.longitude, lat, lon)
                    val bearing = GeoUtils.bearingDeg(myEntry.latitude, myEntry.longitude, lat, lon)
                    addLine("${dist.toInt()}m away, ${bearing.toInt()}° ${GeoUtils.compassPoint(bearing)}")
                }
                val vert = latest.pressureHpaX10?.let { transport?.barometer?.relativeAltitudeTo(it / 10.0) }
                if (vert != null) {
                    val dir = if (vert >= 0) "ABOVE" else "BELOW"
                    addLine("${kotlin.math.abs(vert)}m $dir you", bold = true)
                }
                val tierWords = when (latest.tier) {
                    MeshLocation.LOC_TIER_GPS_LIVE -> "GPS location, ±${latest.accuracyMeters ?: "?"}m"
                    MeshLocation.LOC_TIER_GPS_STALE -> "Last GPS fix, ${ageSec}s ago — may have moved"
                    MeshLocation.LOC_TIER_PASSIVE -> "Approximate location, ${ageSec}s ago"
                    else -> "No location"
                }
                addLine(tierWords, sizeSp = 14f)
            }
        }

        if (lost != null) {
            // FIX 4: local clock, but clamp for the same reason as above.
            val lostAgeMin = ((System.currentTimeMillis() - lost.lastEntry.receivedAtMs) / 60_000).coerceAtLeast(0L)
            val hdgWord = lost.computedHeadingDeg?.let { GeoUtils.compassPoint(it.toDouble()) }
            val spdMs = lost.computedSpeedCms?.let { it / 100.0 }
            val trendWord = when (lost.trend) {
                MeshLedger.Trend.CLOSER -> "was getting closer"
                MeshLedger.Trend.FARTHER -> "was moving away"
                MeshLedger.Trend.STEADY -> "distance was steady"
                MeshLedger.Trend.UNKNOWN -> null
            }
            val parts = mutableListOf("LAST SEEN ${lostAgeMin}min ago")
            if (hdgWord != null) {
                parts.add(if (spdMs != null) "heading $hdgWord at ${"%.1f".format(spdMs)} m/s" else "heading $hdgWord")
            }
            if (trendWord != null) parts.add(trendWord)
            addLine(parts.joinToString(", "), bold = true)
            val cone = ledger?.searchCone(nodeId)
            if (cone != null) {
                addLine("Search area: ${cone.minRangeM.toInt()}-${cone.maxRangeM.toInt()}m from last known position")
            }
        }

        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val copyButton = Button(this).apply {
            text = "Copy coordinates"
            setOnClickListener {
                val text = if (latest != null && !(latest.latE7 == 0 && latest.lonE7 == 0)) {
                    "${latest.latitude}, ${latest.longitude} (${GeoUtils.toMgrs(latest.latitude, latest.longitude)})"
                } else "No coordinates available"
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("coordinates", text))
                Toast.makeText(this@OfflineCallActivity, "Copied", Toast.LENGTH_SHORT).show()
            }
        }
        actionRow.addView(copyButton)
        if (isActiveSos) {
            val silenceButton = Button(this).apply {
                text = "Silence"
                setOnClickListener { sosAlarm.silence() }
            }
            actionRow.addView(silenceButton)
        }
        card.addView(actionRow)

        return card
    }

    private fun lastKnownTier(nodeId: Long): Int =
        mediaTransport?.ledger?.latestEntry(nodeId)?.tier ?: MeshLocation.LOC_TIER_NONE

    private fun lastKnownDistanceM(nodeId: Long): Int {
        val transport = mediaTransport ?: return -1
        val latest = transport.ledger.latestEntry(nodeId) ?: return -1
        val mine = transport.ledger.latestEntry(transport.localNodeId) ?: return -1
        if (latest.latE7 == 0 && latest.lonE7 == 0) return -1
        return GeoUtils.haversineMeters(mine.latitude, mine.longitude, latest.latitude, latest.longitude).toInt()
    }

    // ── PHASE 6 TRACK D: last-resort SSID broadcast ─────────────────────────────

    private fun onSsidBroadcastButtonClicked() {
        if (sosSsidBroadcast.active) {
            sosSsidBroadcast.deactivate()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Broadcast SOS via Wi-Fi name?")
            .setMessage(
                "This DISCONNECTS you from the rest of the party's mesh while active. " +
                    "Your phone will alternate 60s broadcasting a Wi-Fi name containing your " +
                    "position (visible to ANY nearby phone, no app needed) with 30s attempting " +
                    "to reconnect to the group. Only use this as a last resort — e.g. you are " +
                    "alone and out of mesh range and need any nearby person to see you."
            )
            .setPositiveButton("Broadcast") { _, _ -> startSsidBroadcast() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startSsidBroadcast() {
        val transport = mediaTransport
        val name = connectedPeerName.ifEmpty { "Party member" }
        val myFix = transport?.ledger?.latestEntry(transport.localNodeId)
        if (myFix == null || (myFix.latE7 == 0 && myFix.lonE7 == 0)) {
            Toast.makeText(this, "No location fix yet — cannot compose an SOS SSID", Toast.LENGTH_LONG).show()
            return
        }
        sosSsidBroadcast.activate(name, myFix.latitude, myFix.longitude)
    }

    private fun updateSsidBroadcastButtonUi(active: Boolean, broadcasting: Boolean, ssid: String?) {
        ssidBroadcastButton.text = when {
            !active -> "Last resort: broadcast SSID"
            broadcasting -> "Broadcasting \"$ssid\" — tap to stop"
            else -> "Attempting to reconnect… — tap to stop"
        }
        ssidBroadcastButton.setBackgroundColor(if (active) Color.RED else Color.DKGRAY)
        ssidBroadcastButton.setTextColor(Color.WHITE)
    }

    // ── PHASE 6 TRACK E: self-healing GO re-election ────────────────────────────
    //
    // DESIGN NOTE — read before touching this section: the codebase's own FIX 6c
    // (see onPeerSelected's doc, this file) already discovered and documented
    // that a client calling connect() against an ALREADY-FORMED group's GO is
    // unreliable — the proven, reliable direction is the GO inviting a client in
    // (invitePeer). This track's own instructions describe "losers connect to
    // [the winner]" — that phrasing was written before checking against this
    // codebase's own prior finding. Rather than build on a path this app's own
    // history already flags as unreliable, the winner instead auto-INVITES every
    // nearby discovered peer (reusing invitePeerToGroup's exact, already-tested
    // mechanism) and followers simply stay discoverable and wait. The "rank * 2s"
    // stagger from the spec is applied to how long each FOLLOWER waits before
    // starting its own discovery-visibility phase, reducing thundering-herd
    // discovery/invite traffic, rather than to a connect() attempt that this
    // codebase's own history says doesn't work well.

    /** Fired once, when this CLIENT hasn't heard a GO heartbeat in 30s (never
     *  fires on the device that IS the GO — see MeshElection's doc). Per the
     *  HARD RULE this track was built against: an active call is never left
     *  half-alive — it's about to be cold-rebuilt (see the post-reconnect hook
     *  in the transport-setup function), never silently frozen. */
    private fun handleGoLost() {
        if (reconnectingAfterGoLoss) return
        reconnectingAfterGoLoss = true
        groupCallModeBeforeGoLoss = groupCallMode
        statusText.text = "GROUP OWNER LOST — RECONNECTING"
        errorText.text = "Group owner lost — electing a new one…"
        errorText.visibility = View.VISIBLE
        Log.w("OFFTRACE", "ELECT: GO lost — running election")
        mediaTransport?.meshElection?.runElection()
    }

    /** The deterministic election result — every surviving device computes the
     *  same [winnerId] independently (see MeshElection's class doc), so this
     *  fires on every device, not just the winner's. */
    private fun handleElectionResult(winnerId: Long, isSelf: Boolean) {
        val rank = mediaTransport?.meshElection?.myRank()?.coerceAtLeast(0) ?: 0
        Log.d("OFFTRACE", "ELECT: new GO up, reconnecting in ${if (isSelf) 0 else rank * 2000}ms (rank $rank)")
        if (isSelf) {
            becomeNewGoAfterElection()
        } else {
            Handler(Looper.getMainLooper()).postDelayed({ waitForInviteAfterElection() }, rank * 2_000L)
        }
    }

    /** Split-brain guard: this device is GO and just heard another device that
     *  ALSO believes itself GO — MeshElection already restricted this callback
     *  to firing only on the higher-nodeId side (the lower one stands down
     *  symmetrically on its own end when it hears OUR heartbeat), so reaching
     *  here always means THIS device should yield. Reuses the exact same
     *  reconnect path as an ordinary GO loss — from this device's perspective
     *  its own "GO" is now effectively gone (there can only be one). */
    private fun handleSplitBrainStandDown() {
        Log.w("OFFTRACE", "ELECT: split-brain — this device standing down")
        handleGoLost()
    }

    /** Winner path: tear down the old, now-headless transport (the OLD GO is
     *  gone; this instance's isGroupOwner=false is stale either way) and
     *  unilaterally form a fresh group. MeshLedger/MeshCarrier are process-wide
     *  singletons unaffected by this instance swap — position history and any
     *  queued SOS/chat survive by construction, per this track's design. */
    private fun becomeNewGoAfterElection() {
        statusText.text = "Becoming new group owner…"
        mediaTransport?.stop()
        mediaTransport = null
        signaling?.stop()
        signaling = null
        wifiDirect.createGroup { ok ->
            if (!ok) {
                runOnUiThread {
                    Toast.makeText(this, "Could not become group owner — returning to search", Toast.LENGTH_LONG).show()
                    leaveGroup("election: createGroup failed")
                }
            }
            // onConnectionChangedInternal picks up groupFormed=true from here,
            // the SAME bootstrap path a normal initial connection already uses
            // — no duplicated setup logic.
        }
    }

    /** Follower path: stay discoverable and wait to be invited by the new GO —
     *  see this section's design note on why this device does NOT call
     *  connect() itself. */
    private fun waitForInviteAfterElection() {
        statusText.text = "Waiting for new group owner to reconnect us…"
        mediaTransport?.stop()
        mediaTransport = null
        signaling?.stop()
        signaling = null
        wifiDirect.startDiscovery { ok ->
            if (!ok) Log.w("OFFTRACE", "ELECT: rediscovery failed while waiting for invite")
        }
    }

    /** Called from onPeersChanged while this device is the just-elected GO
     *  waiting to reconnect its old party — auto-invites every discovered
     *  device instead of requiring a manual "Add to group" tap (see this
     *  section's design note). Safe/idempotent per device address. */
    private fun autoInviteDuringElection() {
        if (!reconnectingAfterGoLoss || !isLocalGroupOwner) return
        devices.filter { it.status != WifiP2pDevice.CONNECTED && invitedDuringElection.add(it.deviceAddress) }
            .forEach { device ->
                Log.d("OFFTRACE", "ELECT: auto-inviting ${device.deviceName} back into the re-formed group")
                wifiDirect.invitePeer(device)
            }
    }

    // ── PHASE 7A STEP 5: signed display name ────────────────────────────────

    private fun updateDisplayNameButtonUi() {
        displayNameButton.text = "My name: ${OfflineIdentity.displayName(applicationContext)}"
    }

    /** Editable any time, not just before joining — a change here only takes
     *  effect on the NEXT group join (HELLO is sent once, at connect; this
     *  deliberately does not attempt to re-announce a name change to peers
     *  already in an active session). */
    private fun showDisplayNameDialog() {
        val input = EditText(this).apply {
            hint = "Shown to other party members"
            setText(OfflineIdentity.displayName(applicationContext))
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Display name")
            .setMessage("Sent to other devices signed with your mesh identity — max ${OfflineIdentity.MAX_DISPLAY_NAME_BYTES} bytes.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val saved = OfflineIdentity.setDisplayName(applicationContext, input.text.toString())
                if (saved == null) {
                    Toast.makeText(this, "Name can't be empty", Toast.LENGTH_SHORT).show()
                } else {
                    updateDisplayNameButtonUi()
                    // BUG 2 FIX: re-advertise immediately so a name change is
                    // visible to a peer still browsing the discovery list, without
                    // needing an app restart.
                    registerDnsSdLocalService()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── PHASE 6 TRACK B2: hands-free trigger settings ───────────────────────────

    private fun showSosSettingsDialog() {
        val transport = mediaTransport ?: return
        val triggers = transport.sosTriggers
        val labels = arrayOf(
            "Volume-down x5 (screen off OK)",
            "Hardware button long-press (not available on most phones)",
            "Freefall + impact",
            "No motion 20min while separated from party"
        )
        val keys = arrayOf(
            SosTriggers.PREF_VOLUME_DOWN, SosTriggers.PREF_BUTTON_LONGPRESS,
            SosTriggers.PREF_FREEFALL, SosTriggers.PREF_NO_MOTION
        )
        val checked = keys.map { triggers.isEnabled(it) }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle("Hands-free SOS triggers")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                triggers.setEnabled(keys[which], isChecked)
            }
            .setNeutralButton("Emergency contacts") { _, _ -> showEmergencyContactsDialog() }
            // BUG 1 FIX 4: plain, discoverable escape hatch for stale test-session
            // SOS state — see MeshSosManager.clearStoredAlerts's doc for exactly
            // what this does and does not touch.
            .setNegativeButton("Clear stored alerts") { _, _ -> confirmClearStoredAlerts() }
            .setPositiveButton("Done", null)
            .show()
    }

    private fun confirmClearStoredAlerts() {
        AlertDialog.Builder(this)
            .setTitle("Clear stored alerts?")
            .setMessage(
                "Removes every cached/historical SOS record on this device, including " +
                    "ones being carried for other members. Does not cancel your own SOS " +
                    "if you currently have one active."
            )
            .setPositiveButton("Clear") { _, _ ->
                val transport = mediaTransport ?: return@setPositiveButton
                val n = transport.clearStoredSosAlerts()
                sosEntries.clear()
                renderSosAlerts()
                val activeSenders = transport.activeSosSenderIds()
                sosAlarm.onActiveSendersChanged(activeSenders)
                renderSosOverlay(activeSenders)
                sosOverlay.visibility = if (activeSenders.isNotEmpty()) View.VISIBLE else View.GONE
                Toast.makeText(this, "Cleared $n stored alert(s)", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEmergencyContactsDialog() {
        val transport = mediaTransport ?: return
        val input = EditText(this).apply {
            hint = "comma-separated phone numbers"
            setText(transport.sosRelay.emergencyContacts().joinToString(", "))
        }
        AlertDialog.Builder(this)
            .setTitle("Emergency contacts")
            .setMessage("Used only for the cellular-relay \"tap to send\" prompt — never sent automatically.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val numbers = input.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }
                transport.sosRelay.setEmergencyContacts(numbers)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** PHASE 6 TRACK B3: one tap, no permission — a human still has to confirm
     *  before an emergency SMS actually goes out (see SosRelay's class doc). */
    private fun showRelayPrompt(transport: OfflineMediaTransport, prompt: SosRelay.RelayPrompt) {
        if (!handledRelayPrompts.add(prompt.msgId)) return // already prompted this session
        val toNumber = prompt.contacts.firstOrNull() ?: return
        AlertDialog.Builder(this)
            .setTitle("TAP TO SEND — RELAYING SOS FOR ${prompt.senderName}")
            .setMessage(prompt.smsBody)
            .setCancelable(false)
            .setPositiveButton("SEND") { _, _ ->
                try {
                    startActivity(transport.sosRelay.buildSendIntent(prompt, toNumber))
                } catch (e: Exception) {
                    Toast.makeText(this, "No SMS app available: ${e.message}", Toast.LENGTH_LONG).show()
                }
                transport.sosRelay.markRelayed(prompt.msgId)
            }
            .setNegativeButton("Not now") { _, _ -> handledRelayPrompts.remove(prompt.msgId) }
            .show()
    }

    /** PHASE 6 TRACK B2: foreground-reliable companion to SosTriggers' MediaSession
     *  path (screen-off case) — Android routes a volume press to whichever of the
     *  two actually has priority at the moment, never reliably both, so double
     *  counting isn't a practical concern (see SosTriggers.onVolumeDownPress's doc). */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN && event.action == android.view.KeyEvent.ACTION_DOWN) {
            mediaTransport?.sosTriggers?.onVolumeDownPress()
        }
        return super.dispatchKeyEvent(event)
    }

    private fun lastKnownVerticalM(nodeId: Long): Int {
        val transport = mediaTransport ?: return 0
        val latest = transport.ledger.latestEntry(nodeId) ?: return 0
        val p = latest.pressureHpaX10 ?: return 0
        return transport.barometer.relativeAltitudeTo(p / 10.0) ?: 0
    }

    /** FIX 6b: GO-only — invites a nearby non-member peer into this already-formed
     *  group (see WifiDirectManager.invitePeer's doc for why this is the reliable
     *  direction, vs. that peer trying to connect() in on its own). */
    private fun invitePeerToGroup(device: WifiP2pDevice) {
        statusText.text = "Inviting ${device.deviceName}..."
        wifiDirect.invitePeer(device) { ok ->
            runOnUiThread {
                if (ok) {
                    Toast.makeText(this, "Invited ${device.deviceName}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Invite failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** PHASE 3B: initiator picks audio or video, then broadcasts the invite —
     *  [OfflineMediaTransport.startGroupCall] joins this device to it immediately. */
    private fun showStartGroupCallDialog() {
        val labels = arrayOf("Audio", "Video")
        AlertDialog.Builder(this)
            .setTitle("Start group call")
            .setItems(labels) { _, which ->
                val mode = if (which == 0) {
                    OfflineMediaTransport.GroupCallMode.AUDIO
                } else {
                    OfflineMediaTransport.GroupCallMode.VIDEO
                }
                mediaTransport?.startGroupCall(mode)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** PHASE 3B: shown on every OTHER member when someone starts a group call.
     *  FIX 4: the "Join" button disables itself the instant it's tapped, before
     *  dismissing — a plain setPositiveButton listener's dismiss-after-return isn't
     *  synchronous with touch input, so a fast double-tap could otherwise fire
     *  acceptGroupCall() twice for the same callId. */
    private fun showGroupCallInviteDialog(fromName: String, mode: OfflineMediaTransport.GroupCallMode, callId: Long) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Group call")
            .setMessage("$fromName started a ${mode.name.lowercase()} group call.")
            .setPositiveButton("Join", null)
            .setNegativeButton("Ignore", null)
            .create()
        dialog.setOnShowListener {
            val joinButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            joinButton.setOnClickListener {
                joinButton.isEnabled = false
                mediaTransport?.acceptGroupCall(callId)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /** Tap a member → the 3-mode dialog → call/message THAT member specifically.
     *  Transparent whether they're directly connected or relayed through the GO. */
    private fun showCallModeDialog(member: RoutingTable.Member) {
        val labels = arrayOf("Video call", "Audio call", "Message")
        AlertDialog.Builder(this)
            .setTitle("Call ${member.name}")
            .setItems(labels) { _, which ->
                val mode = when (which) {
                    0 -> OfflineMediaTransport.CallMode.VIDEO
                    1 -> OfflineMediaTransport.CallMode.AUDIO
                    else -> OfflineMediaTransport.CallMode.CHAT
                }
                startDirectCall(member, mode)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startDirectCall(member: RoutingTable.Member, mode: OfflineMediaTransport.CallMode) {
        isGroupChatScreen = false
        connectedPeerName = member.name
        groupScreen.visibility = View.GONE
        callScreen.visibility = View.VISIBLE
        errorText.visibility = View.GONE
        applyUiForMode(mode)
        startCallTimer()
        mediaTransport?.placeCall(member.nodeId, member.name, mode)
    }

    /** "Group chat" row — not a media call (no placeCall), just switches to the same
     *  chat overlay used by a 1:1 CHAT-mode call, in broadcast send mode. */
    private fun openGroupChat() {
        isGroupChatScreen = true
        connectedPeerName = "Group chat"
        groupScreen.visibility = View.GONE
        callScreen.visibility = View.VISIBLE
        errorText.visibility = View.GONE
        applyUiForMode(OfflineMediaTransport.CallMode.CHAT)
    }

    /** Fires for both the initiator (redundantly, right after startDirectCall already
     *  switched screens — a no-op re-apply) and the callee (the ONLY trigger that
     *  switches it from the roster screen to the call screen — auto-answered, same as
     *  every mode resolution in this app always has been). */
    private fun onCallStarted(peerName: String, mode: OfflineMediaTransport.CallMode) {
        isGroupChatScreen = false
        connectedPeerName = peerName
        if (groupScreen.visibility == View.VISIBLE) {
            groupScreen.visibility = View.GONE
            callScreen.visibility = View.VISIBLE
            errorText.visibility = View.GONE
            startCallTimer()
        }
        applyUiForMode(mode)
    }

    /** The call partner hung up, or their link died — return to the roster without
     *  disturbing the group itself. */
    private fun onCallEndedRemotely(reason: String) {
        Log.d("OFFTRACE", "call ended: $reason")
        returnToRosterScreen()
    }

    private fun returnToRosterScreen() {
        stopCallTimer()
        resetVideoAspectRatio()
        resetChat()
        resetCallModeUi()
        isGroupChatScreen = false
        connectedPeerName = ""
        errorText.visibility = View.GONE
        callScreen.visibility = View.GONE
        groupScreen.visibility = View.VISIBLE
    }

    // ── PHASE 3B: group call screen ──────────────────────────────────────────────

    /** Fires once this device is a confirmed participant — for the initiator right
     *  after [showStartGroupCallDialog], for everyone else once accepted (see
     *  [showGroupCallInviteDialog]). */
    /** Deliberately does NOT reset groupCallLocalCameraOn/groupCallLocalMicMuted —
     *  when THIS device is the GO, [OfflineMediaTransport.startGroupCall]/
     *  [acceptGroupCall] call setGroupCallCameraOn(true) SYNCHRONOUSLY before
     *  posting onGroupCallStarted (which is what calls this), so its
     *  onGroupCallCamState confirmation is already posted to mainHandler ahead of
     *  this one and would otherwise get clobbered by a blind reset here. Both flags
     *  instead default false from [exitGroupCallScreen]'s cleanup after the
     *  previous call and are only ever set true by the real onGroupCallCamState
     *  callback. */
    private fun enterGroupCallScreen(mode: OfflineMediaTransport.GroupCallMode) {
        groupCallMode = mode
        groupCallActiveSpeaker = null
        groupCallPinned = false
        groupCallFocusedTile = null
        searchScreen.visibility = View.GONE
        groupScreen.visibility = View.GONE
        callScreen.visibility = View.GONE
        groupCallScreen.visibility = View.VISIBLE
        errorText.visibility = View.GONE
        updateGroupCallStatusText()
        updateGroupCallControlsBar()
    }

    /** FIX 1b: reflects RINGING (fewer than 2 participants — a valid, expected state
     *  for a founding call, not an error) vs. a normal in-progress call. */
    private fun updateGroupCallStatusText() {
        val mode = groupCallMode ?: return
        groupCallStatusText.text = if (groupCallParticipants.size < 2) {
            "Group call — ${mode.name.lowercase()} — ringing…"
        } else {
            "Group call — ${mode.name.lowercase()} — ${groupCallParticipants.size} participants"
        }
    }

    private fun updateGroupCallControlsBar() {
        groupCallCameraButton.isEnabled = groupCallMode == OfflineMediaTransport.GroupCallMode.VIDEO
        groupCallCameraButton.text = if (groupCallLocalCameraOn) "Camera: On" else "Camera: Off"
        groupCallMicButton.text = if (groupCallLocalMicMuted) "Mic: Muted" else "Mic: On"
    }

    /** Fires when THIS device's own group call ends — local leave, the call falling
     *  below 2 participants, or a join rejection (see
     *  [OfflineMediaTransport.onGroupCallRejected] for the latter's extra toast).
     *  Returns to the roster without disturbing the group itself, same spirit as
     *  [onCallEndedRemotely]. */
    private fun exitGroupCallScreen(reason: String) {
        Log.d("OFFTRACE", "group call ended: $reason")
        groupTiles.values.forEach { (it.container.parent as? ViewGroup)?.removeView(it.container) }
        groupTiles.clear()
        groupCallGrid.removeAllViews()
        groupCallParticipants = emptyList()
        groupCallCamStates.clear()
        groupCallActiveSpeaker = null
        groupCallPinned = false
        groupCallFocusedTile = null
        groupCallMode = null
        groupCallLocalCameraOn = false
        groupCallLocalMicMuted = false
        errorText.visibility = View.GONE
        groupCallScreen.visibility = View.GONE
        groupScreen.visibility = View.VISIBLE
    }

    private fun nameForGroupParticipant(id: Long): String =
        roster.firstOrNull { it.nodeId == id }?.name ?: shortId(id)

    /** Creates a [GroupTile] for any newly-joined participant and tears down (incl.
     *  unregistering its Surface with the transport) any tile whose participant is
     *  no longer in [groupCallParticipants] — e.g. they left or disconnected. Call
     *  before [rebuildGroupCallGrid] whenever the participant list changes. */
    private fun syncGroupTiles() {
        val ids = groupCallParticipants.toSet()
        val gone = groupTiles.keys - ids
        gone.forEach { id ->
            groupTiles.remove(id)?.let { tile -> (tile.container.parent as? ViewGroup)?.removeView(tile.container) }
            groupCallCamStates.remove(id)
            if (id == mediaTransport?.localNodeId) mediaTransport?.setLocalPreviewSurface(null)
            else mediaTransport?.setGroupTileSurface(id, null)
            if (groupCallFocusedTile == id) groupCallFocusedTile = null
        }
        groupCallParticipants.forEach { id ->
            if (id !in groupTiles) groupTiles[id] = createGroupTile(id)
        }
    }

    /** Point 7: reparents each surviving tile into the grid at its computed cell —
     *  or, with a tile focused (point 9), shows just that one at full size. Tiles
     *  are never recreated here, only moved, so a live decoder binding is
     *  undisturbed by a grid reshape. Cheap enough at up to 8 tiles (point 10's
     *  hard cap) to just refresh every tile's content each call rather than diff. */
    private fun rebuildGroupCallGrid() {
        groupCallGrid.removeAllViews()
        val focused = groupCallFocusedTile
        if (focused != null && groupTiles.containsKey(focused)) {
            groupCallGrid.rowCount = 1
            groupCallGrid.columnCount = 1
            addTileToGrid(focused, 0, 0)
        } else {
            val ids = groupCallParticipants
            val (rows, cols) = gridDimensionsFor(ids.size)
            groupCallGrid.rowCount = rows
            groupCallGrid.columnCount = cols
            ids.forEachIndexed { i, id -> addTileToGrid(id, i / cols, i % cols) }
        }
        groupTiles.forEach { (id, tile) -> updateTileContent(id, tile) }
        groupCallGrid.post {
            Log.d("OFFTRACE", "UI: grid size=${groupCallGrid.width}x${groupCallGrid.height}")
            groupTiles.forEach { (id, t) ->
                Log.d("OFFTRACE", "UI: tile ${MeshFrame.hex(id)} size=" +
                    "${t.container.width}x${t.container.height} sv=" +
                    "${t.surfaceView.width}x${t.surfaceView.height}")
            }
        }
    }

    private fun addTileToGrid(nodeId: Long, row: Int, col: Int) {
        val tile = groupTiles[nodeId] ?: return
        (tile.container.parent as? ViewGroup)?.removeView(tile.container)
        // FIX: GridLayout.spec(index, span, weight) alone leaves cell alignment at
        // its non-FILL default — combined with width/height=0 that measures the
        // tile to 0x0 (no BufferQueue ever gets allocated for a 0x0 SurfaceView, so
        // surfaceCreated never fires and setGroupTileSurface/setLocalPreviewSurface
        // are never called). All three of GridLayout.FILL on both specs,
        // width/height=0, and setGravity(Gravity.FILL) are required together for
        // the weighted cell to actually stretch to fill its allotted space.
        val params = GridLayout.LayoutParams(
            GridLayout.spec(row, 1, GridLayout.FILL, 1f),
            GridLayout.spec(col, 1, GridLayout.FILL, 1f)
        ).apply {
            width = 0
            height = 0
            setGravity(Gravity.FILL)
        }
        groupCallGrid.addView(tile.container, params)
    }

    /** Refreshes one tile's video-vs-avatar visibility, name, speaking highlight,
     *  and (self only) battery — called after any camera-state, speaker, or grid
     *  change. Battery is local-only, same reasoning as [readBatteryPercent]'s doc:
     *  no wire frame carries other participants' battery level in this phase. */
    private fun updateTileContent(nodeId: Long, tile: GroupTile) {
        val isMe = nodeId == mediaTransport?.localNodeId
        val camOn = isMe && groupCallLocalCameraOn || !isMe && groupCallCamStates[nodeId] == true
        // FIX: a GONE SurfaceView never produces a Surface — surfaceCreated would
        // never fire and setGroupTileSurface/setLocalPreviewSurface would never be
        // called, so the decoder/camera could never bind to it once camOn actually
        // went true. Always keep it VISIBLE; the avatar overlay (added as a later
        // sibling in the FrameLayout, see createGroupTile) draws on top of it and
        // hides it when the camera is off instead.
        tile.surfaceView.visibility = View.VISIBLE
        tile.avatarText.visibility = if (camOn) View.GONE else View.VISIBLE
        Log.d("OFFTRACE", "UI: tile ${MeshFrame.hex(nodeId)} camOn=$camOn surfaceValid=${tile.surfaceView.holder.surface?.isValid}")
        val name = nameForGroupParticipant(nodeId)
        tile.avatarText.text = initialsFor(name)
        tile.nameLabel.text = name + if (isMe) " (you)" else ""
        tile.speakingDot.visibility = if (nodeId == groupCallActiveSpeaker) View.VISIBLE else View.GONE
        if (isMe) {
            val pct = readBatteryPercent()
            tile.batteryText.visibility = if (pct != null) View.VISIBLE else View.GONE
            if (pct != null) tile.batteryText.text = "🔋 $pct%"
        } else {
            tile.batteryText.visibility = View.GONE
        }
    }

    /** Point 9: tapping a tile shows it fullscreen; tapping the same one again
     *  returns to the grid. */
    private fun onTileTapped(nodeId: Long) {
        groupCallFocusedTile = if (groupCallFocusedTile == nodeId) null else nodeId
        rebuildGroupCallGrid()
    }

    /** Local battery only — there's no wire frame carrying OTHER participants'
     *  battery level in this phase's protocol (see the group call class doc), so
     *  every OTHER tile simply omits it rather than showing fabricated data. */
    private fun readBatteryPercent(): Int? {
        val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return level * 100 / scale
    }

    private fun onHangupClicked() {
        // A group-chat-only screen never placed a call, so there's nothing to end —
        // just go back. A real call sends TYPE_HANGUP to the partner (best-effort;
        // endCall() no-ops harmlessly if the call already ended some other way).
        if (!isGroupChatScreen) {
            mediaTransport?.endCall()
        }
        returnToRosterScreen()
    }

    // ── Leaving the group entirely ───────────────────────────────────────────────

    /** PHASE 3: full teardown of the WiFi Direct group + mesh session — this used to
     *  be what a single call's end/link-loss did (Phase 2 had no group to leave, just
     *  the one call). Now triggered by: the "Leave group" button, the underlying
     *  signaling connection going away, or the mesh session itself dying.
     *  [persistentErrorMessage] is set only for a protocol version mismatch — kept
     *  visible via a Toast rather than the transient errorText, same as before. */
    private fun leaveGroup(reason: String, persistentErrorMessage: String? = null) {
        Log.e("OFFTRACE", "leaving group ($reason)")
        statusText.text = "Disconnected"
        currentGroupFormed = false
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
        isGroupChatScreen = false
        connectedPeerName = ""
        roster = emptyList()
        isLocalGroupOwner = false
        invitableDevices = emptyList()
        groupCallParticipants = emptyList()
        groupCallActiveSpeaker = null
        groupCallPinned = false
        groupCallFocusedTile = null
        groupCallCamStates.clear()
        groupTiles.values.forEach { (it.container.parent as? ViewGroup)?.removeView(it.container) }
        groupTiles.clear()
        groupCallGrid.removeAllViews()
        groupCallMode = null
        groupCallLocalCameraOn = false
        groupCallLocalMicMuted = false
        // PHASE 5A: mediaTransport?.stop() above already shuts down MeshSosManager/
        // OfflineLocationProvider on the transport side — this just clears this
        // Activity's own local mirror so a stale SOS/Find alert doesn't survive
        // into the next session.
        sosActive = false
        sosEntries.clear()
        findResponses.clear()
        updateSosButtonUi()
        renderSosAlerts()
        // PHASE 5BC: same "stale alert must not survive into the next session"
        // reasoning as the PHASE 5A clear above — drive the siren back to silent
        // through its normal empty-set transition rather than an ad hoc stop, and
        // hide both overlays.
        sosAlarm.onActiveSendersChanged(emptySet())
        sosOverlay.visibility = View.GONE
        partyStatusOverlay.visibility = View.GONE
        errorText.visibility = View.GONE
        callScreen.visibility = View.GONE
        groupScreen.visibility = View.GONE
        groupCallScreen.visibility = View.GONE
        searchScreen.visibility = View.VISIBLE
        if (persistentErrorMessage != null) {
            Toast.makeText(this, persistentErrorMessage, Toast.LENGTH_LONG).show()
        }
        proceedToDiscovery()
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
        // in leaveGroup()/onDestroy(), i.e. real end-of-group/end-of-activity.
        Log.d("OFFTRACE", "onStop — keeping session alive")
        wifiDirect.stopPeerDiscovery()
        // PHASE 5BC: an immediate ledger flush, not a stop — the mesh session (and
        // the ambient position feed) keeps running with the screen off, see the
        // comment above; this just makes sure onStop itself isn't a data-loss
        // window, per the persistence decision this phase was built against.
        mediaTransport?.ledger?.flushNow()
    }

    override fun onDestroy() {
        super.onDestroy()
        // FIX 4 (idle-session): onDestroy() fires both when the user genuinely finishes
        // this screen (back press -> finish(), isFinishing == true) AND when the system
        // destroys just this Activity object to reclaim memory from a backgrounded/
        // locked screen while keeping the hosting process — and its foreground service
        // — alive (isFinishing == false then). This used to tear the whole group down
        // unconditionally, so Activity death alone (nothing the user asked for) could
        // kill a perfectly healthy session. Only tear down on a genuine finish.
        // wifiDirect itself was constructed with applicationContext (see onCreate), so
        // its broadcast receiver keeps working regardless of this Activity's lifecycle
        // — a system-reclaimed (non-finishing) destroy here leaves mediaTransport and
        // signaling running, anchored by the still-live foreground service, exactly as
        // intended. Known limitation of this minimal fix (vs. moving transport
        // ownership into the service): a freshly re-created Activity after such a
        // reclaim has no way to rediscover that still-running session — it starts with
        // null mediaTransport/signaling fields, same as a first launch.
        if (isFinishing) {
            signaling?.stop()
            mediaTransport?.stop()
            stopOfflineCallService() // safety net in case leaveGroup didn't run
            wifiDirect.stopPeerDiscovery()
            wifiDirect.disconnect()
            wifiDirect.teardown()
        }
        resetConnectionAttemptState()
        stopCallTimer()
    }
}
