package com.opencall.relay.offline

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Thin wrapper around WifiP2pManager: discovery, connect, and the
 * broadcast receiver plumbing. Caller is responsible for holding
 * ACCESS_FINE_LOCATION (+ NEARBY_WIFI_DEVICES on API 33+) before calling
 * startDiscovery()/connect() — the underlying WifiP2pManager calls throw
 * SecurityException otherwise.
 */
class WifiDirectManager(private val context: Context) {

    companion object {
        private const val TAG = "WifiDirectManager"
        private const val MAX_DISCOVERY_RETRIES = 3
        private const val DISCOVERY_RETRY_DELAY_MS = 2000L
        private const val MAX_CHANNEL_REINITS = 3
        // BUG 2 FIX: DNS-SD local service — see class doc's "pre-connect display
        // name" section. Instance name is arbitrary/unused for matching (peers are
        // matched by TXT record, not this string); the service TYPE is what a
        // discoverer filters on.
        private const val DNSSD_INSTANCE_NAME = "_opencall"
        private const val DNSSD_SERVICE_TYPE = "_opencall._tcp"
    }

    private val manager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var registered = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var channelReinitCount = 0

    private var p2pEnabled = false
    private var isDiscovering = false
    private var discoveryRetryCount = 0
    // FIX 6d: set by requestGroupInfo(), read via groupSsid().
    private var currentGroupSsid: String? = null

    var onPeersChanged: ((WifiP2pDeviceList) -> Unit)? = null
    var onConnectionChanged: ((WifiP2pInfo) -> Unit)? = null
    var onP2pStateChanged: ((Boolean) -> Unit)? = null

    /** Fired when the P2P channel is lost and cannot be recovered after MAX_CHANNEL_REINITS. */
    var onFatalError: ((String) -> Unit)? = null

    // BUG 2 FIX: DNS-SD — a pre-connect display-name hint. UNVERIFIED: no
    // signature exists before HELLO, this is whatever the OTHER device's TXT
    // record claims. Never use this for any permission decision, never cache
    // it as a verified name — see [onServiceTxtRecordFound]'s own doc.
    private var dnsSdListenersRegistered = false
    private var dnsSdServiceRequest: WifiP2pDnsSdServiceRequest? = null

    /** [deviceAddress]->TXT record map (see [DNSSD_SERVICE_TYPE]'s n/id/v keys).
     *  UNVERIFIED — same caveat as the class doc above. */
    var onServiceTxtRecordFound: ((deviceAddress: String, record: Map<String, String>) -> Unit)? = null

    private val channelListener = WifiP2pManager.ChannelListener {
        Log.e("OFFTRACE", "p2p channel DISCONNECTED — reinitializing")
        channel = null
        if (channelReinitCount < MAX_CHANNEL_REINITS) {
            channelReinitCount++
            Log.w(TAG, "reinitializing p2p channel (attempt $channelReinitCount/$MAX_CHANNEL_REINITS)")
            initializeChannel()
        } else {
            Log.e("OFFTRACE", "p2p channel disconnected — max re-inits reached, giving up")
            onFatalError?.invoke("Wi-Fi Direct connection lost and could not be recovered")
        }
    }

    private fun initializeChannel() {
        channel = manager?.initialize(context, Looper.getMainLooper(), channelListener)
    }

    fun init() {
        if (channel != null) return
        initializeChannel()

        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        p2pEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        onP2pStateChanged?.invoke(p2pEnabled)
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        Log.d("OFFTRACE", "P2P_CONNECTION_CHANGED fired")
                        requestConnectionInfo()
                    }
                    WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1)
                        if (state == WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED) {
                            isDiscovering = false
                        }
                    }
                }
            }
        }
        receiver = r

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
        }
        context.registerReceiver(r, filter)
        registered = true

        registerDnsSdListeners()
    }

    /** BUG 2 FIX: registered once, independent of any particular discovery
     *  call — these are just callback plumbing, not a scan trigger (that's
     *  [discoverServices] below). Safe to call more than once. */
    @SuppressLint("MissingPermission")
    private fun registerDnsSdListeners() {
        if (dnsSdListenersRegistered) return
        val ch = channel ?: return
        // Confirmed via the compileSdk android.jar (javap): the platform API is
        // ONE combined setter, not separate setDnsSdResponseListener/
        // setDnsSdTxtRecordListener calls.
        manager?.setDnsSdResponseListeners(
            ch,
            WifiP2pManager.DnsSdServiceResponseListener { _, _, _ ->
                // Service-instance events carry no TXT data of their own on most
                // OEMs — the TXT listener below is the reliable source for the
                // fields this app actually needs (n/id/v).
            },
            WifiP2pManager.DnsSdTxtRecordListener { _, record, device ->
                val name = record["n"]
                val idHex = record["id"]
                // BUG 2: UNVERIFIED — no signature exists before HELLO. This is
                // purely a discovery-list hint; the caller must never treat it
                // as authenticated (see [onServiceTxtRecordFound]'s doc).
                Log.d("OFFTRACE", "DNSSD: peer ${device.deviceAddress} name=\"$name\" id=$idHex (UNVERIFIED)")
                if (name != null) {
                    onServiceTxtRecordFound?.invoke(device.deviceAddress, record)
                }
            }
        )
        dnsSdListenersRegistered = true
    }

    /** BUG 2 FIX: advertises this device's display name/nodeId/protocol version
     *  over Wi-Fi Direct service discovery so a peer's "nearby devices" list can
     *  show a real name before any connection (and therefore before HELLO) is
     *  possible — see WifiP2pManager.setDeviceName's rejection (reason=0 on
     *  target hardware, plus it tears down the P2P group) for why this exists
     *  instead. Clears any previously-registered local service first so
     *  re-registering after a display-name change never leaves two stale TXT
     *  records advertised at once. */
    @SuppressLint("MissingPermission")
    fun registerLocalService(displayName: String, shortNodeIdHex: String, protocolVersion: String, onResult: ((Boolean) -> Unit)? = null) {
        val ch = channel
        if (ch == null) {
            onResult?.invoke(false)
            return
        }
        val record = mapOf("n" to displayName, "id" to shortNodeIdHex, "v" to protocolVersion)
        val info = WifiP2pDnsSdServiceInfo.newInstance(DNSSD_INSTANCE_NAME, DNSSD_SERVICE_TYPE, record)
        manager?.clearLocalServices(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = addLocalServiceInternal(ch, info, displayName, onResult)
            override fun onFailure(reason: Int) = addLocalServiceInternal(ch, info, displayName, onResult)
        })
    }

    @SuppressLint("MissingPermission")
    private fun addLocalServiceInternal(
        ch: WifiP2pManager.Channel,
        info: WifiP2pDnsSdServiceInfo,
        displayName: String,
        onResult: ((Boolean) -> Unit)?
    ) {
        manager?.addLocalService(ch, info, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d("OFFTRACE", "DNSSD: local service registered name=\"$displayName\"")
                onResult?.invoke(true)
            }
            override fun onFailure(reason: Int) {
                Log.w("OFFTRACE", "DNSSD: local service registration failed reason=$reason")
                onResult?.invoke(false)
            }
        })
    }

    /** BUG 2 FIX: triggers a service-discovery scan — call alongside
     *  [startDiscovery] (same one-shot-per-call semantics; the OS keeps
     *  listening for TXT responses while a discovery window is open, same as
     *  ordinary peer discovery). */
    @SuppressLint("MissingPermission")
    fun discoverServices(onResult: ((Boolean) -> Unit)? = null) {
        val ch = channel
        if (ch == null) {
            onResult?.invoke(false)
            return
        }
        registerDnsSdListeners()
        val request = WifiP2pDnsSdServiceRequest.newInstance()
        dnsSdServiceRequest = request
        manager?.addServiceRequest(ch, request, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                manager.discoverServices(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.d(TAG, "discoverServices: success")
                        onResult?.invoke(true)
                    }
                    override fun onFailure(reason: Int) {
                        Log.w("OFFTRACE", "DNSSD: discoverServices failed reason=$reason")
                        onResult?.invoke(false)
                    }
                })
            }
            override fun onFailure(reason: Int) {
                Log.w("OFFTRACE", "DNSSD: addServiceRequest failed reason=$reason")
                onResult?.invoke(false)
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery(onResult: ((Boolean) -> Unit)? = null) {
        if (channel == null) {
            Log.w(TAG, "startDiscovery: channel is null, ignoring")
            onResult?.invoke(false)
            return
        }
        if (!p2pEnabled) {
            Log.w(TAG, "startDiscovery: P2P not enabled yet, ignoring")
            onResult?.invoke(false)
            return
        }
        if (isDiscovering) {
            Log.d(TAG, "startDiscovery: a discovery is already pending, stopping it first")
            stopPeerDiscovery { clearStaleGroupThenDiscover(onResult) }
            return
        }
        clearStaleGroupThenDiscover(onResult)
    }

    @SuppressLint("MissingPermission")
    fun stopPeerDiscovery(onResult: ((Boolean) -> Unit)? = null) {
        if (channel == null) {
            Log.w(TAG, "stopPeerDiscovery: channel is null, ignoring")
            isDiscovering = false
            onResult?.invoke(false)
            return
        }
        manager?.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "stopPeerDiscovery: success")
                isDiscovering = false
                onResult?.invoke(true)
            }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "stopPeerDiscovery failed: $reason")
                isDiscovering = false
                onResult?.invoke(false)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun clearStaleGroupThenDiscover(onResult: ((Boolean) -> Unit)?) {
        if (channel == null) {
            Log.w(TAG, "clearStaleGroupThenDiscover: channel is null, ignoring")
            onResult?.invoke(false)
            return
        }
        discoveryRetryCount = 0
        // Clears a group left behind by a prior crashed/killed session before discovering —
        // a stale group is what causes discoverPeers() to come back BUSY (reason 2).
        manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "removeGroup (pre-discovery): success")
                discoverPeersInternal(onResult)
            }
            override fun onFailure(reason: Int) {
                Log.d(TAG, "removeGroup (pre-discovery) failed: $reason (no group is expected here)")
                discoverPeersInternal(onResult)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun discoverPeersInternal(onResult: ((Boolean) -> Unit)?) {
        if (channel == null) {
            Log.w(TAG, "discoverPeersInternal: channel is null, ignoring")
            onResult?.invoke(false)
            return
        }
        manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "discoverPeers: success")
                isDiscovering = true
                discoveryRetryCount = 0
                onResult?.invoke(true)
            }
            override fun onFailure(reason: Int) {
                Log.e(TAG, "discoverPeers failed: $reason")
                if (reason == WifiP2pManager.BUSY && discoveryRetryCount < MAX_DISCOVERY_RETRIES) {
                    discoveryRetryCount++
                    Log.w(TAG, "discoverPeers retry $discoveryRetryCount after BUSY")
                    mainHandler.postDelayed(
                        { discoverPeersInternal(onResult) },
                        DISCOVERY_RETRY_DELAY_MS
                    )
                } else {
                    Log.e("OFFTRACE", "discoverPeers failed (final): reason=$reason")
                    isDiscovering = false
                    onResult?.invoke(false)
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun requestPeers() {
        if (channel == null) {
            Log.w(TAG, "requestPeers: channel is null, ignoring")
            return
        }
        manager?.requestPeers(channel) { peers -> onPeersChanged?.invoke(peers) }
    }

    @SuppressLint("MissingPermission")
    private fun requestConnectionInfo() {
        if (channel == null) {
            Log.w(TAG, "requestConnectionInfo: channel is null, ignoring")
            return
        }
        manager?.requestConnectionInfo(channel) { info -> onConnectionChanged?.invoke(info) }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: WifiP2pDevice, onResult: ((Boolean) -> Unit)? = null) {
        if (channel == null) {
            Log.w(TAG, "connect: channel is null, ignoring")
            onResult?.invoke(false)
            return
        }
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            groupOwnerIntent = 15
        }
        manager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "connect: requested")
                Log.d("OFFTRACE", "connect onSuccess")
                onResult?.invoke(true)
            }
            override fun onFailure(reason: Int) {
                Log.e(TAG, "connect failed: $reason")
                Log.e("OFFTRACE", "connect onFailure reason=$reason")
                onResult?.invoke(false)
            }
        })
    }

    /** FIX 6a: GO-initiated invite — the GO calls this (not [connect]) to add a
     *  newcomer to a group that already exists. Under the hood it's the exact same
     *  WifiP2pManager.connect() call [connect] makes (same groupOwnerIntent=15
     *  config) — the framework distinguishes the two cases itself: since a group is
     *  already formed here, it treats this connect() as an invitation for
     *  [device] to join it, rather than a fresh negotiation. Newcomers are ADDED BY
     *  the group this way; they never reliably self-join an already-formed group by
     *  calling [connect] on their own end (see FIX 6c in OfflineCallActivity). */
    @SuppressLint("MissingPermission")
    fun invitePeer(device: WifiP2pDevice, onResult: ((Boolean) -> Unit)? = null) {
        if (channel == null) {
            Log.w(TAG, "invitePeer: channel is null, ignoring")
            onResult?.invoke(false)
            return
        }
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            groupOwnerIntent = 15
        }
        Log.d("OFFTRACE", "MESH: inviting ${device.deviceName} (${device.deviceAddress}) to existing group")
        manager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "invitePeer: requested")
                Log.d("OFFTRACE", "invitePeer onSuccess")
                onResult?.invoke(true)
            }
            override fun onFailure(reason: Int) {
                Log.e(TAG, "invitePeer failed: $reason")
                Log.e("OFFTRACE", "invitePeer onFailure reason=$reason")
                onResult?.invoke(false)
            }
        })
    }

    /** PHASE 6 TRACK E: unilaterally becomes GO of a brand-new group, bypassing
     *  the normal negotiation entirely — this is the standard, public way to
     *  force a SPECIFIC device to be GO (see WifiP2pManager docs), used by
     *  [MeshElection]'s winner to re-form the mesh after the old GO is lost.
     *  Deliberately does NOT call removeGroup() first (unlike
     *  clearStaleGroupThenDiscover) — by the time this is called the old group
     *  is already gone (that's WHY an election ran), so there's nothing stale
     *  to clear, and an unconditional removeGroup() here could race a
     *  just-started createGroup() on a slower device. */
    @SuppressLint("MissingPermission")
    fun createGroup(onResult: ((Boolean) -> Unit)? = null) {
        if (channel == null) {
            Log.w(TAG, "createGroup: channel is null, ignoring")
            onResult?.invoke(false)
            return
        }
        manager?.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d("OFFTRACE", "ELECT: createGroup onSuccess")
                onResult?.invoke(true)
            }
            override fun onFailure(reason: Int) {
                Log.e("OFFTRACE", "ELECT: createGroup onFailure reason=$reason")
                onResult?.invoke(false)
            }
        })
    }

    /** FIX 6d: queried by the GO right after group formation — logs and stashes the
     *  group's SSID/client count. Not consumed anywhere yet; this is the foundation
     *  for a legacy (manual Wi-Fi-join) fallback path later. */
    @SuppressLint("MissingPermission")
    fun requestGroupInfo(onResult: ((WifiP2pGroup?) -> Unit)? = null) {
        if (channel == null) {
            Log.w(TAG, "requestGroupInfo: channel is null, ignoring")
            onResult?.invoke(null)
            return
        }
        manager?.requestGroupInfo(channel) { group ->
            if (group != null) {
                currentGroupSsid = group.networkName
                Log.d("OFFTRACE", "MESH: group ssid=${group.networkName} clients=${group.clientList.size}")
            }
            onResult?.invoke(group)
        }
    }

    /** The most recently observed group SSID (see [requestGroupInfo]), null until
     *  queried at least once. */
    fun groupSsid(): String? = currentGroupSsid

    @SuppressLint("MissingPermission")
    fun disconnect() {
        if (channel == null) {
            Log.w(TAG, "disconnect: channel is null, ignoring")
            return
        }
        manager?.cancelConnect(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "cancelConnect: success") }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "cancelConnect failed: $reason")
                Log.w("OFFTRACE", "cancelConnect failed: $reason")
            }
        })
        manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "removeGroup: success") }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "removeGroup failed: $reason")
                Log.w("OFFTRACE", "removeGroup failed: $reason")
            }
        })
    }

    fun teardown() {
        mainHandler.removeCallbacksAndMessages(null)
        if (registered) {
            try { context.unregisterReceiver(receiver) } catch (e: Exception) { /* not registered */ }
            registered = false
        }
        channel?.let { ch ->
            try { manager?.clearLocalServices(ch, null) } catch (_: Exception) {}
            try { manager?.clearServiceRequests(ch, null) } catch (_: Exception) {}
        }
        receiver = null
        channel = null
        channelReinitCount = 0
        currentGroupSsid = null
        dnsSdListenersRegistered = false
        dnsSdServiceRequest = null
    }
}
