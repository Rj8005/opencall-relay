package com.opencall.relay.offline

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
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

    var onPeersChanged: ((WifiP2pDeviceList) -> Unit)? = null
    var onConnectionChanged: ((WifiP2pInfo) -> Unit)? = null
    var onP2pStateChanged: ((Boolean) -> Unit)? = null

    /** Fired when the P2P channel is lost and cannot be recovered after MAX_CHANNEL_REINITS. */
    var onFatalError: ((String) -> Unit)? = null

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
        receiver = null
        channel = null
        channelReinitCount = 0
    }
}
