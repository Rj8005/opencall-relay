package com.opencall.relay.offline

import java.net.InetAddress

/**
 * PART 1 EXTRACTION: the boundary between "how two devices find each other
 * and get IP reachability" and "what they say to each other once they have
 * it." Everything above the IP layer — MeshFrame, PeerLink, RoutingTable,
 * MeshSigner, MeshCarrier, MeshSosManager, MeshElection, the group-call
 * protocol, all of it living in OfflineMediaTransport.kt and friends — is
 * already transport-agnostic; it just reads/writes bytes on a
 * java.net.Socket. [WifiDirectManager] is the first (and, until Part 2, only)
 * implementation. This interface covers ONLY link establishment and peer
 * reachability: no frame ever crosses it, no socket is ever visible through
 * it, and no protocol type/version constant is defined here — see
 * RoutingTable.kt's VERSION and OfflineMediaTransport's TYPE_* constants for
 * those, both untouched by this extraction.
 */
interface MeshTransport {
    /** Stable, lowercase-hyphenated identity for this transport —
     *  "wifi-direct", "local-wifi" — used as a log prefix (see
     *  WifiDirectManager's "XPORT:"/"P2P:" lines) and as the dedupe/
     *  provenance key in [MeshTransportRegistry]. Never shown to the user
     *  directly; it's a diagnostic/internal tag, not a display name. */
    val id: String

    /** What this transport can do — read once at registration time by
     *  [MeshTransportRegistry] and (in a later phase, NOT wired yet — see
     *  this field's own doc on the interface below) by MeshElection to
     *  score a device out of GO eligibility on a platform that cannot host.
     *  Carried now, scored later. */
    val capabilities: TransportCaps

    /** Begins this transport's lifecycle — registers whatever OS-level
     *  receivers/services it needs and starts delivering [callbacks].
     *  Idempotent: calling this while already started is a no-op (mirrors
     *  [WifiDirectManager.init]'s existing "if (channel != null) return"
     *  contract). */
    fun start(callbacks: Callbacks)

    /** Full shutdown — unregisters everything [start] registered. Mirrors
     *  [WifiDirectManager.teardown]. */
    fun stop()

    /** Begins actively looking for peers. A no-op (not an error) if this
     *  transport has no distinct "discovery" phase — see the local-Wi-Fi
     *  transport (Part 2), which is always resolving via mDNS whenever
     *  started. */
    fun startDiscovery()

    fun stopDiscovery()

    /** Establishes a link to [peer] — for Wi-Fi Direct this is
     *  connect()/invite() (provision discovery + GO negotiation, or a
     *  GO-side invite if this device already owns a group); for local Wi-Fi
     *  (Part 2) this is simply opening a TCP socket to the peer's advertised
     *  IP/port. [onOutcome] fires exactly once, true only once a link is
     *  actually usable (mirrors [WifiDirectManager.connect]'s onOutcome
     *  contract: true only on a real groupFormed observation, never on a
     *  request merely being ACCEPTED). */
    fun invite(peer: DiscoveredPeer, onOutcome: (Boolean) -> Unit)

    /** Current lifecycle state — see [TransportState] and
     *  [WifiDirectManager]'s P2pState -> TransportState mapping table (this
     *  file's PART 1 OUTPUT) for the total mapping every implementation
     *  must provide. */
    fun currentState(): TransportState

    /** This device's own address on the network this transport just
     *  established, once known — null before any network is ready. For
     *  Wi-Fi Direct there is no stable address until a group actually forms
     *  (see WifiDirectManager.localAddress's own doc for exactly which
     *  WifiP2pInfo field this reads); for local Wi-Fi it's the device's
     *  ordinary Wi-Fi IP, known as soon as [onNetworkReady] fires. */
    fun localAddress(): InetAddress?

    interface Callbacks {
        /** Fired whenever this transport's live peer list changes —
         *  replaces the previous list, does not diff it (callers that need
         *  transition tracking, e.g. WifiDirectManager's own
         *  trackPeerListTransitions-style logging, do that themselves). */
        fun onPeersChanged(peers: List<DiscoveredPeer>)

        /** Fired once THIS device has real IP reachability to talk to peers
         *  on — [isOwner] is meaningful only for a transport where
         *  [TransportCaps.canHost] is true (Wi-Fi Direct's group owner
         *  concept); a transport with no ownership concept (local Wi-Fi)
         *  always reports false. [ownerAddress] is the address to dial for
         *  the socket layer above — the group owner's address on Wi-Fi
         *  Direct, or (Part 2) not meaningful at all on a transport with no
         *  single owner, where every peer is reached by its OWN resolved
         *  address instead (see [DiscoveredPeer] / the Part 2 transport's
         *  own doc). */
        fun onNetworkReady(isOwner: Boolean, ownerAddress: InetAddress)

        /** Fired once the network this transport had established is gone —
         *  [reason] is a short, log-friendly string (mirrors
         *  WifiDirectManager's existing "why" convention in [transition]'s
         *  own log line), never shown to the user verbatim. */
        fun onNetworkLost(reason: String)

        /** An error this transport cannot recover from on its own — mirrors
         *  [WifiDirectManager.onFatalError]'s existing contract exactly
         *  (fired only past MAX_CHANNEL_REINITS, never for an ordinary
         *  retryable failure). */
        fun onTransportError(message: String)
    }
}

/** A peer this device can see, but is not yet (or no longer) linked to.
 *
 *  [transportPeerId] is stable and SCOPED TO ITS OWN TRANSPORT ONLY — Wi-Fi
 *  Direct's is a WifiP2pDevice.deviceAddress (a MAC), local Wi-Fi's (Part 2)
 *  is the mDNS-resolved host:port pair. Two [DiscoveredPeer]s from DIFFERENT
 *  transports never share a [transportPeerId] by construction — the ONLY
 *  identifier common to every transport is [resolvedShortNodeId], which is
 *  why [MeshTransportRegistry] dedupes on that field, never on
 *  [transportPeerId] (see the registry's own doc).
 *
 *  [resolvedShortNodeId] is the OCP short node id — the same truncated hex
 *  form already used for DNS-SD's "id"/"gp" TXT fields and MeshBleBeacon's
 *  invite-target (see OfflineCallActivity.dnsSdShortIdByAddress) — null
 *  until SOME side-channel (DNS-SD TXT record, BLE beacon, or — Part 2 — the
 *  mDNS TXT record itself) has resolved it; a peer can be visible with this
 *  still null (exactly today's "UNRESOLVED" nearbyDevices row). */
data class DiscoveredPeer(
    val transportPeerId: String,
    val displayName: String,
    val resolvedShortNodeId: String?,
    /** Signal quality, transport-defined units (Wi-Fi Direct exposes none
     *  for a P2P peer — always null there; a later transport with RSSI
     *  reports it here). Null means "unknown," never "zero signal." */
    val signalQuality: Int?,
    /** [MeshTransport.id] of whichever transport produced this sighting —
     *  see that field's own doc for why this is a plain id, not a live
     *  transport reference (keeps this a clean, comparable data class, and
     *  is all [MeshTransportRegistry] actually needs to record provenance). */
    val transportId: String
)

/** What a transport can do — read by [MeshTransportRegistry] today, and
 *  (later, not wired yet) by MeshElection for GO-eligibility scoring. */
data class TransportCaps(
    /** May this transport become the group owner / network host? Always
     *  false for a transport with no ownership concept (local Wi-Fi). */
    val canHost: Boolean,
    /** May a device on this transport act as a store-and-forward mule
     *  (MeshCarrier)? Independent of [canHost] — carrying is a protocol-
     *  layer behavior, unrelated to who owns the network underneath it. */
    val canRelay: Boolean,
    /** Enough bandwidth/latency headroom for real-time audio/video, as
     *  opposed to a hypothetical future low-bandwidth-only transport. */
    val supportsMedia: Boolean,
    val maxPeers: Int,
    /** True if every peer on this transport must already share a network
     *  this device didn't create (local Wi-Fi: true, needs an existing
     *  router/hotspot/AP). False for Wi-Fi Direct, which creates its own
     *  network on demand. */
    val requiresSharedNetwork: Boolean,
    /** PART 2.5: true for a transport that is NOT a socket at all — no
     *  persistent link, no [MeshTransport.Callbacks.onNetworkReady]/
     *  [onNetworkLost] pair, no [DiscoveredPeer] to [invite], no
     *  [InetAddress] to be [MeshTransport.localAddress]. [OpticalTransport]
     *  (a camera-and-screen QR channel) is the only transport this is true
     *  for today: it is one-shot (a single scan either completes or
     *  doesn't — there is no ongoing session to tear down), unidirectional
     *  per attempt (SHOW and SCAN are separate human actions, never a
     *  live two-way conversation), and human-aimed (a person points a
     *  camera at a screen — nothing here "discovers" a peer the way WFD/
     *  mDNS do). Every OTHER [MeshTransport] method OpticalTransport
     *  implements is a documented, honest degenerate (see its own class
     *  doc) rather than a real implementation of that method's contract —
     *  this flag is how a caller (e.g. a future MeshElection GO-eligibility
     *  scorer) tells "a real, if unusual, socket transport" from "not a
     *  socket, don't treat it like one." Defaults false so every transport
     *  written before this flag existed (Wi-Fi Direct, local Wi-Fi) is
     *  unchanged. */
    val manualNonContinuous: Boolean = false
)

/** Transport-agnostic lifecycle state — see [WifiDirectManager]'s
 *  P2pState -> TransportState mapping (PART 1 OUTPUT's mapping table) for
 *  why this mirrors P2pState's five states name-for-name: the underlying
 *  concepts (nothing in flight / scanning / negotiating a link / linked /
 *  a live link just ended) are genuinely transport-agnostic already, so an
 *  invented, differently-shaped state machine here would be renaming
 *  without adding meaning. */
enum class TransportState { IDLE, DISCOVERING, CONNECTING, CONNECTED, TEARDOWN }
