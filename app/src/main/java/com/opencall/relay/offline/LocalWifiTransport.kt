package com.opencall.relay.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PART 2: the second [MeshTransport] — devices already on the same ordinary
 * Wi-Fi network (a home router, a hotspot, a venue AP) have IP reachability
 * with no Wi-Fi Direct at all. Discovery is mDNS/NSD (2.1); there is no
 * group-owner concept (2.2) — every peer is equal, reached by dialing its
 * own advertised IP:port directly (2.3); the socket layer above (PeerLink,
 * MeshFrame, the read loops, the writer threads) is COMPLETELY UNCHANGED
 * (2.4) — this transport's only contact with that layer is
 * [OfflineMediaTransport.adoptPeerSocket], a thin new wrapper over the
 * existing, untouched handleNewConnection.
 *
 * ACTIVITY WIRING — REPORTED GAP, NOT DONE IN THIS PASS: OfflineCallActivity
 * constructs OfflineMediaTransport (and wires its ~30 callback vars) from
 * ONE call site, onConnectionChanged (OfflineCallActivity.kt:4588+), keyed
 * to Wi-Fi Direct's WifiP2pInfo.isGroupOwner/groupOwnerAddress. Generalizing
 * (or duplicating) that ~150-line construction block so it ALSO fires from
 * this transport's [onSocketEstablished] is real Activity-level work this
 * pass does not attempt — see PART 2 OUTPUT for why. [onSocketEstablished]
 * is the seam that work would hook into; every already-connected socket
 * this transport produces (both [invite]'s outbound dial and
 * [acceptLoop]'s inbound accepts) is handed there, untouched.
 */
class LocalWifiTransport(private val context: Context) : MeshTransport {

    // Plain (non-inner) nested types — Kotlin nested classes are already
    // static-like by default (no implicit outer-instance reference), and
    // unlike a class/enum declared INSIDE `companion object { }` (which is
    // only reachable as LocalWifiTransport.Companion.X), these ARE directly
    // reachable as LocalWifiTransport.X from outside — see
    // LocalWifiTransportTest.

    /** PART 2.2/2.7: the pure decision behind [reportNetworkState] —
     *  extracted so "reports unavailable when not on Wi-Fi" is directly
     *  unit-testable without a real ConnectivityManager/NetworkCapabilities
     *  (this project has no Robolectric/mocking framework — see
     *  WifiDirectManager.capabilities's identical reasoning for why
     *  instance-level Android APIs stay untested here, only their pure
     *  decision cores are). */
    enum class NetworkAvailability(val reason: String) {
        AVAILABLE("n/a"),
        NO_ACTIVE_NETWORK("no-active-network"),
        CELLULAR_ONLY("cellular-only"),
        NO_WIFI_TRANSPORT("no-wifi-transport")
    }

    /** PART 2.1: pure TXT-record shape, extracted for direct unit testing
     *  (see LocalWifiTransportTest) — no NsdManager/Context involved.
     *  [nodeId] is the OCP short node id text exactly as advertised, no
     *  further decoding. */
    data class TxtRecord(val nodeId: String?, val name: String?, val version: String?)

    companion object {
        private const val TAG = "LocalWifiTransport"
        const val TRANSPORT_ID = "local-wifi"
        const val SERVICE_TYPE = "_opencall._tcp"

        // TXT record keys — same short names as WifiDirectManager's DNS-SD
        // scheme (WifiDirectManager.kt's "n"/"id"/"v") for one-glance
        // consistency between the two transports' logs; this is a SEPARATE
        // NsdManager-based service record, not the same wire object.
        private const val TXT_NODE_ID = "id"
        private const val TXT_NAME = "n"
        private const val TXT_VERSION = "v"

        val LOCAL_WIFI_CAPS = TransportCaps(
            canHost = false,
            canRelay = true,
            supportsMedia = true,
            maxPeers = 32,
            requiresSharedNetwork = true
        )

        private const val CONNECT_TIMEOUT_MS = 5_000

        fun evaluate(hasActiveNetwork: Boolean, hasWifiTransport: Boolean, hasCellularTransport: Boolean): NetworkAvailability = when {
            !hasActiveNetwork -> NetworkAvailability.NO_ACTIVE_NETWORK
            hasWifiTransport -> NetworkAvailability.AVAILABLE
            hasCellularTransport -> NetworkAvailability.CELLULAR_ONLY
            else -> NetworkAvailability.NO_WIFI_TRANSPORT
        }

        /** PART 2.6: [DiscoveredPeer.transportPeerId] encoding for this
         *  transport — "host:port", the resolved mDNS address. Pure/
         *  Context-free, directly unit-testable. */
        fun encodePeerId(host: String, port: Int): String = "$host:$port"

        /** Splits on the LAST colon so an IPv6 literal host (which itself
         *  contains colons) still parses correctly. Returns null for
         *  anything that isn't "host:port" with a valid port number. */
        fun decodePeerId(id: String): Pair<String, Int>? {
            val idx = id.lastIndexOf(':')
            if (idx <= 0 || idx == id.length - 1) return null
            val host = id.substring(0, idx)
            val port = id.substring(idx + 1).toIntOrNull() ?: return null
            return host to port
        }

        fun encodeTxtRecord(nodeId: String, name: String, version: String): Map<String, String> =
            mapOf(TXT_NODE_ID to nodeId, TXT_NAME to name, TXT_VERSION to version)

        fun decodeTxtRecord(attributes: Map<String, ByteArray?>): TxtRecord = TxtRecord(
            nodeId = attributes[TXT_NODE_ID]?.let { String(it, Charsets.UTF_8) },
            name = attributes[TXT_NAME]?.let { String(it, Charsets.UTF_8) },
            version = attributes[TXT_VERSION]?.let { String(it, Charsets.UTF_8) }
        )

        /** PART 2.1: a resolved NsdServiceInfo -> DiscoveredPeer — pure given
         *  already-extracted fields (host/port/txt), so this is directly
         *  testable without a real NsdServiceInfo (which, like WifiP2pDevice,
         *  needs a real resolve callback to ever carry a host/port in
         *  practice). Returns null if [host] is null (not yet resolved) —
         *  callers only see this after NsdManager's own ResolveListener
         *  fires, so that should never actually happen, but the mapping
         *  itself doesn't assume it. */
        fun mapResolved(host: InetAddress?, port: Int, txt: TxtRecord, serviceName: String): DiscoveredPeer? {
            if (host == null) return null
            return DiscoveredPeer(
                transportPeerId = encodePeerId(host.hostAddress ?: return null, port),
                displayName = txt.name?.takeIf { it.isNotBlank() } ?: serviceName,
                resolvedShortNodeId = txt.nodeId,
                signalQuality = null, // NSD exposes no signal-strength concept
                transportId = TRANSPORT_ID
            )
        }
    }

    override val id: String get() = TRANSPORT_ID
    override val capabilities: TransportCaps get() = LOCAL_WIFI_CAPS

    private val mainHandler = Handler(Looper.getMainLooper())
    private val nsdManager: NsdManager? = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val running = AtomicBoolean(false)
    private var callbacks: MeshTransport.Callbacks? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    @Volatile private var registered = false
    @Volatile private var discovering = false
    @Volatile private var state: TransportState = TransportState.IDLE
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var boundPort: Int = 0
    @Volatile private var localAddress: InetAddress? = null

    private val knownPeers = ConcurrentHashMap<String, DiscoveredPeer>() // keyed by transportPeerId

    /** PART 2.4 — see class doc's "ACTIVITY WIRING" note: the seam a live
     *  call-session integration hooks into. [outbound]=true for [invite]'s
     *  dial, false for [acceptLoop]'s accepts — same contract
     *  [OfflineMediaTransport.handleNewConnection]'s own [outbound] param
     *  already has. */
    var onSocketEstablished: ((Socket, outbound: Boolean) -> Unit)? = null

    private fun transition(to: TransportState) {
        if (state == to) return
        state = to
        Log.d("OFFTRACE", "XPORT: id=$id state=$state peers=${knownPeers.size} caps=$capabilities")
    }

    override fun start(callbacks: MeshTransport.Callbacks) {
        if (running.getAndSet(true)) return
        this.callbacks = callbacks
        transition(TransportState.DISCOVERING)
        startAcceptLoop()
        registerService()
        startDiscovery()
        reportNetworkState()
    }

    override fun stop() {
        if (!running.getAndSet(false)) return
        unregisterService()
        stopDiscoveryInternal()
        serverSocket?.let { try { it.close() } catch (_: Exception) {} }
        serverSocket = null
        knownPeers.clear()
        callbacks = null
        transition(TransportState.IDLE)
    }

    /** PART 2: local-wifi is always resolving whenever [start] has run — see
     *  MeshTransport.startDiscovery's own doc for why a no-op here (rather
     *  than an error) is the correct contract for a transport with no
     *  distinct discovery phase. */
    override fun startDiscovery() {
        if (!running.get() || discovering) return
        val manager = nsdManager ?: return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d("OFFTRACE", "XPORT[$id]: discovery started type=$serviceType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType.trimEnd('.') != SERVICE_TYPE.trimEnd('.')) return
                if (service.serviceName == advertisedServiceName) return // our own advertisement
                resolveService(service)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                val removed = knownPeers.entries.removeAll { it.value.displayName == service.serviceName }
                if (removed) notifyPeersChanged()
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d("OFFTRACE", "XPORT[$id]: discovery stopped type=$serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w("OFFTRACE", "XPORT[$id]: discovery start failed err=$errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w("OFFTRACE", "XPORT[$id]: discovery stop failed err=$errorCode")
            }
        }
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            discoveryListener = listener
            discovering = true
        } catch (e: Exception) {
            Log.w("OFFTRACE", "XPORT[$id]: discoverServices threw: ${e.message}")
        }
    }

    override fun stopDiscovery() {
        stopDiscoveryInternal()
    }

    private fun stopDiscoveryInternal() {
        val listener = discoveryListener ?: return
        discoveryListener = null
        discovering = false
        try {
            nsdManager?.stopServiceDiscovery(listener)
        } catch (e: Exception) {
            Log.w("OFFTRACE", "XPORT[$id]: stopServiceDiscovery threw: ${e.message}")
        }
    }

    private fun resolveService(service: NsdServiceInfo) {
        val manager = nsdManager ?: return
        // PART 2.1: NsdManager.resolveService throws if a resolve for the
        // SAME NsdServiceInfo is already outstanding on some OS versions —
        // a fresh listener object per call (this function's own local
        // object) is what NsdManager requires; a shared/reused listener
        // across resolves is a documented cause of "already in progress"
        // failures.
        manager.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w("OFFTRACE", "XPORT[$id]: resolve failed name=${serviceInfo.serviceName} err=$errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val txt = decodeTxtRecord(serviceInfo.attributes ?: emptyMap())
                val peer = mapResolved(serviceInfo.host, serviceInfo.port, txt, serviceInfo.serviceName) ?: return
                knownPeers[peer.transportPeerId] = peer
                notifyPeersChanged()
            }
        })
    }

    private fun notifyPeersChanged() {
        val snapshot = knownPeers.values.toList()
        mainHandler.post { callbacks?.onPeersChanged(snapshot) }
    }

    @Volatile private var advertisedServiceName: String? = null

    /** PART 2.1: registers this device's own service — OCP short node id,
     *  display name, protocol version, and the mesh listen port (the ACTUAL
     *  bound port of [serverSocket], set to 0/ephemeral and read back after
     *  binding — never a hardcoded port, so this transport can run
     *  alongside anything else already using a fixed one). Unregisters on
     *  [stop] — see that function and [unregisterService]; never left
     *  registered across sessions (the DNS-SD service-request leak this
     *  class doc's sibling, WifiDirectManager, was bitten by once already —
     *  see that file's BUG (REVERT SERVICE-REQUEST CLEAR) FIX). */
    private fun registerService() {
        val manager = nsdManager ?: return
        val port = boundPort
        if (port == 0) return // accept loop hasn't bound yet — start() calls startAcceptLoop() first, so this shouldn't happen
        // Same "short id" truncation convention as WifiDirectManager's own
        // DNS-SD TXT record (see OfflineCallActivity.registerDnsSdLocalService).
        val shortId = OfflineIdentity.hex(OfflineIdentity.nodeId(context)).takeLast(6)
        val name = OfflineIdentity.displayName(context)
        val info = NsdServiceInfo().apply {
            serviceName = "opencall-$shortId"
            serviceType = SERVICE_TYPE
            setPort(port)
            encodeTxtRecord(shortId, name, MeshFrame.VERSION.toString()).forEach { (k, v) -> setAttribute(k, v) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w("OFFTRACE", "XPORT[$id]: register FAILED err=$errorCode")
            }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w("OFFTRACE", "XPORT[$id]: unregister failed err=$errorCode")
            }
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                advertisedServiceName = serviceInfo.serviceName
                Log.d("OFFTRACE", "XPORT[$id]: registered name=${serviceInfo.serviceName} port=$port")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.d("OFFTRACE", "XPORT[$id]: unregistered name=${serviceInfo.serviceName}")
            }
        }
        try {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            registrationListener = listener
            registered = true
        } catch (e: Exception) {
            Log.w("OFFTRACE", "XPORT[$id]: registerService threw: ${e.message}")
        }
    }

    /** PART 2.1: register on start, unregister on stop — NEVER left
     *  registered across sessions. Idempotent (safe to call even if
     *  registration never actually succeeded). */
    private fun unregisterService() {
        val listener = registrationListener ?: return
        registrationListener = null
        registered = false
        advertisedServiceName = null
        try {
            nsdManager?.unregisterService(listener)
        } catch (e: Exception) {
            Log.w("OFFTRACE", "XPORT[$id]: unregisterService threw: ${e.message}")
        }
    }

    /** PART 2.2: fires [Callbacks.onNetworkReady] immediately once this
     *  device has a usable, non-cellular network — isOwner is always false
     *  (no group-owner concept on this transport — see this class doc).
     *  Reports unavailability with the exact log line 2.2 specifies when
     *  Wi-Fi is off or the active network is cellular — [evaluate]'s pure
     *  decision (see its own doc) drives which reason string is logged. */
    private fun reportNetworkState() {
        val cm = connectivityManager ?: run {
            Log.d("OFFTRACE", "XPORT: local-wifi unavailable reason=no-connectivity-manager")
            return
        }
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val availability = evaluate(
            hasActiveNetwork = network != null,
            hasWifiTransport = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
            hasCellularTransport = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        )
        if (availability != NetworkAvailability.AVAILABLE) {
            Log.d("OFFTRACE", "XPORT: local-wifi unavailable reason=${availability.reason}")
            return
        }
        val addr = resolveLocalAddress(cm, network!!)
        localAddress = addr
        if (addr == null) {
            Log.d("OFFTRACE", "XPORT: local-wifi unavailable reason=no-local-address")
            return
        }
        val cb = callbacks
        mainHandler.post { cb?.onNetworkReady(false, addr) }
    }

    private fun resolveLocalAddress(cm: ConnectivityManager, network: Network): InetAddress? {
        val linkProperties = try {
            cm.getLinkProperties(network)
        } catch (e: Exception) {
            null
        } ?: return null
        return linkProperties.linkAddresses
            .map { it.address }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
    }

    override fun localAddress(): InetAddress? = localAddress

    /** PART 2.3: the actual connect path. Resolves [peer]'s advertised
     *  host:port (see [decodePeerId]), opens a plain TCP socket to it —
     *  no provision discovery, no group formation, no passphrase, exactly
     *  2.3's spec — and hands the connected socket to
     *  [onSocketEstablished](outbound=true), the same seam
     *  [acceptLoop]'s inbound side uses. [onOutcome] fires true the moment
     *  the socket is actually connected (this transport has no separate
     *  "negotiation" phase to wait on — a connected TCP socket on
     *  local-wifi IS the link, unlike Wi-Fi Direct's connect() which only
     *  means "request accepted," see WifiDirectManager.connect's own doc). */
    override fun invite(peer: DiscoveredPeer, onOutcome: (Boolean) -> Unit) {
        val (host, port) = decodePeerId(peer.transportPeerId) ?: run {
            Log.w("OFFTRACE", "XPORT[$id]: invite — malformed transportPeerId=${peer.transportPeerId}")
            onOutcome(false)
            return
        }
        Thread({
            try {
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.connect(java.net.InetSocketAddress(InetAddress.getByName(host), port), CONNECT_TIMEOUT_MS)
                Log.d("OFFTRACE", "XPORT[$id]: invite connected host=$host port=$port")
                onSocketEstablished?.invoke(socket, true)
                onOutcome(true)
            } catch (e: Exception) {
                Log.w("OFFTRACE", "XPORT[$id]: invite connect failed host=$host port=$port: ${e.message}")
                onOutcome(false)
            }
        }, "LocalWifiInvite").start()
    }

    /** PART 2.2/2.6: every peer on this transport is equal — there is no
     *  single owner to dial into, so THIS device must also accept inbound
     *  connections from anyone who invites IT. Binds an ephemeral port
     *  (0 — see [registerService]'s doc for why never a fixed one)
     *  SYNCHRONOUSLY, on the calling ([start]'s) thread — [registerService]
     *  runs right after and needs [boundPort] to already be the real bound
     *  value, not 0, so the bind itself cannot be deferred onto the accept
     *  thread below the way OfflineMediaTransport.startAsServer's identical-
     *  looking bind is (that one never needs to read its own port back).
     *  Only the blocking accept() loop itself runs on a background thread. */
    private fun startAcceptLoop() {
        val srv = try {
            ServerSocket(0)
        } catch (e: Exception) {
            Log.w("OFFTRACE", "XPORT[$id]: accept loop bind failed: ${e.message}")
            return
        }
        serverSocket = srv
        boundPort = srv.localPort
        Log.d("OFFTRACE", "XPORT[$id]: listening on port $boundPort")
        Thread({
            while (running.get()) {
                val client = try {
                    srv.accept()
                } catch (e: Exception) {
                    if (running.get()) Log.w("OFFTRACE", "XPORT[$id]: accept failed: ${e.message}")
                    break
                }
                if (!running.get()) { client.close(); break }
                try {
                    client.tcpNoDelay = true
                    client.keepAlive = true
                } catch (_: Exception) {}
                onSocketEstablished?.invoke(client, false)
            }
        }, "LocalWifiAccept").start()
    }

    override fun currentState(): TransportState = state
}
