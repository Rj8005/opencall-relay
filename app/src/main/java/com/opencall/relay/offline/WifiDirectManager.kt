package com.opencall.relay.offline

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.net.InetAddress
import java.nio.ByteBuffer
import java.security.MessageDigest

/** OCP CONNECT REBUILD PART 1: the single serialized state machine every
 *  WifiP2pManager call in this class is gated by — see [WifiDirectManager]'s
 *  own class doc for the rules each transition enforces. */
enum class P2pState { IDLE, DISCOVERING, CONNECTING, CONNECTED, TEARDOWN }

/**
 * Thin wrapper around WifiP2pManager: discovery, connect, and the
 * broadcast receiver plumbing. Caller is responsible for holding
 * ACCESS_FINE_LOCATION (+ NEARBY_WIFI_DEVICES on API 33+) before calling
 * startDiscovery()/connect() — the underlying WifiP2pManager calls throw
 * SecurityException otherwise.
 *
 * OCP CONNECT REBUILD: this is the ONLY class in the app that may call
 * WifiP2pManager — every discoverPeers/discoverServices/removeGroup/
 * connect/cancelConnect/createGroup call anywhere goes through [state]'s
 * gate (see [logCall]/[transition]). No caller elsewhere in the app may
 * touch WifiP2pManager directly (confirmed — grep for
 * "import android.net.wifi.p2p" outside this file: only data-class type
 * imports, WifiP2pManager itself is never imported anywhere else).
 *
 * STATES: IDLE (nothing in flight) -> DISCOVERING (a scan is running) ->
 * CONNECTING (a connect/createGroup attempt is in flight, discovery
 * quiesced first — see [beginConnectSequence]) -> CONNECTED (groupFormed
 * observed true — NOT connect()'s onSuccess, see part 4) -> TEARDOWN (a
 * live group just ended) -> back to IDLE.
 */
class WifiDirectManager(private val context: Context) : MeshTransport {

    companion object {
        private const val TAG = "WifiDirectManager"
        // PART 1 EXTRACTION: this device's WifiP2pManager GO-client ceiling —
        // mirrors OfflineMediaTransport's own private MAX_GROUP_PARTICIPANTS
        // (that one governs the mesh-protocol admit path; this one is purely
        // TransportCaps.maxPeers's source value — see that field's doc). Not
        // shared as a single constant across files: OfflineMediaTransport's
        // is private and this extraction must not touch that file's
        // internals (G4 is scoped to WifiDirectManager, but there is no
        // reason to widen OfflineMediaTransport's surface for a number that
        // is, today, coincidentally the same for a different reason).
        private const val TRANSPORT_MAX_PEERS = 8
        const val TRANSPORT_ID = "wifi-direct"

        /** PART 1.2/1.6: the actual TransportCaps values — a plain
         *  companion val (no instance state involved) so this is directly
         *  readable from a unit test without constructing a
         *  WifiDirectManager. See [WifiDirectManager.capabilities]'s doc for
         *  why an instance can't exist in this project's test suite at all. */
        val WIFI_DIRECT_CAPS = TransportCaps(
            canHost = true,
            canRelay = true,
            supportsMedia = true,
            maxPeers = TRANSPORT_MAX_PEERS,
            requiresSharedNetwork = false
        )

        /** PART 1 EXTRACTION: total mapping, P2pState -> TransportState — see
         *  MeshTransport.currentState's doc. Pure/Context-free, directly
         *  unit-testable (see WifiDirectManagerTest). */
        fun mapP2pState(state: P2pState): TransportState = when (state) {
            P2pState.IDLE -> TransportState.IDLE
            P2pState.DISCOVERING -> TransportState.DISCOVERING
            P2pState.CONNECTING -> TransportState.CONNECTING
            P2pState.CONNECTED -> TransportState.CONNECTED
            P2pState.TEARDOWN -> TransportState.TEARDOWN
        }

        /** PART 1.2: pure mapping, WifiP2pDevice -> DiscoveredPeer — extracted
         *  for direct unit testing (see WifiDirectManagerTest).
         *  [resolvedShortNodeId] defaults to null: WifiDirectManager itself
         *  never resolves a WifiP2pDevice to an OCP short node id (that
         *  resolution happens in OfflineCallActivity, via DNS-SD TXT
         *  records / BLE — see dnsSdShortIdByAddress); a caller that has it
         *  passes it through. */
        fun mapPeer(device: WifiP2pDevice, transportId: String, resolvedShortNodeId: String? = null): DiscoveredPeer =
            DiscoveredPeer(
                transportPeerId = device.deviceAddress,
                displayName = device.deviceName?.takeIf { it.isNotBlank() } ?: device.deviceAddress,
                resolvedShortNodeId = resolvedShortNodeId,
                signalQuality = null,
                transportId = transportId
            )
        private const val MAX_DISCOVERY_RETRIES = 3
        private const val DISCOVERY_RETRY_DELAY_MS = 2000L
        private const val MAX_CHANNEL_REINITS = 3
        // BUG 2 FIX: DNS-SD local service — see class doc's "pre-connect display
        // name" section. Instance name is arbitrary/unused for matching (peers are
        // matched by TXT record, not this string); the service TYPE is what a
        // discoverer filters on.
        private const val DNSSD_INSTANCE_NAME = "_opencall"
        private const val DNSSD_SERVICE_TYPE = "_opencall._tcp"
        // FIX 2c: a discovery restart (startDiscovery called while
        // isDiscovering is already true) stops the in-flight scan — including
        // any service-discovery request issued alongside it — before starting
        // a fresh one. Evidence: 20:30:34.438 "a discovery is already
        // pending, stopping it first" -> P2P-FIND-STOPPED 1ms later, ~1.0s
        // after the discoverServices() call it cancelled. Below this age, a
        // restart request is very likely to be racing (and losing to) that
        // same in-flight service-discovery window, so it's debounced instead
        // of honoured immediately.
        private const val DISCOVERY_RESTART_MIN_AGE_MS = 15_000L

        // BUG (DISCOVERY IS ONE-SHOT) FIX: a single discoverServices+
        // discoverPeers pass, however successful, is a snapshot — a peer
        // whose TXT record didn't happen to be in flight at that exact
        // moment (the established capture: ZERO "DNSSD: rx" lines in 19s,
        // one addServiceRequest/discoverServices call total) never gets
        // another chance. 12s: slow enough to never look like the 3s loop
        // that caused the BUSY storm this rebuild's Part 4 exists to close,
        // fast enough that a real OpenCall peer is found well inside
        // RESOLVE_GIVEUP_MS's 20s window below. This is the INTERNAL
        // keepalive — see armDiscoveryCadence's doc for why it bypasses
        // DISCOVERY_RESTART_MIN_AGE_MS entirely rather than being subject
        // to it like an external restart request is.
        private const val DISCOVERY_REFRESH_MS = 12_000L

        // ── OCP CONNECT REBUILD PART 2: quiesce sequence timing ──────────────
        private const val QUIESCE_STEP_TIMEOUT_MS = 3_000L
        private const val QUIESCE_SETTLE_DELAY_MS = 500L

        // ── OCP CONNECT REBUILD PART 3: explicit groups (API 29+) ────────────
        private const val EXPLICIT_GROUP_MIN_SDK = Build.VERSION_CODES.Q
        private const val NETWORK_NAME_PREFIX = "DIRECT-ocp-"

        // ── OCP CONNECT REBUILD PART 4: connect must terminate ───────────────
        private const val CONNECT_DEADLINE_MS = 25_000L
        private const val CONNECT_BUSY_MAX_ATTEMPTS = 3
        private const val CONNECT_BUSY_RETRY_DELAY_MS = 3_000L

        // BUG (QUIESCE IS DELETING THE PEER) FIX: the established capture —
        // stopPeerDiscovery at .728, wpa_supplicant's own P2P-DEVICE-LOST at
        // .733 (5ms later), connect() against that now-gone deviceAddress at
        // .275/548ms-later returning reason=0 — is OUR OWN stopPeerDiscovery
        // call evicting the peer from the framework's list before connect()
        // ever runs. Recovery: one discoverPeers() pass, then poll
        // requestPeers() every [PEER_RECOVERY_POLL_INTERVAL_MS] up to
        // [PEER_RECOVERY_MAX_ATTEMPTS] times (10 * 1s = 10s, per spec).
        private const val PEER_RECOVERY_MAX_ATTEMPTS = 10
        private const val PEER_RECOVERY_POLL_INTERVAL_MS = 1_000L

        /** FIX 1b: pure decision core of guardedRemoveGroup/createGroup's
         *  live-group guard — extracted so it's directly testable without a
         *  real WifiP2pManager/Channel (this project has no Robolectric).
         *  True iff the teardown/re-creation attempt must be BLOCKED: a
         *  group is currently formed with at least one client, and this
         *  particular caller isn't the deliberate "leave this group"
         *  escape hatch (allowWhileLive — see guardedRemoveGroup's doc for
         *  why disconnect() alone passes true here). */
        fun shouldBlockGroupTeardown(formed: Boolean, clientCount: Int, allowWhileLive: Boolean): Boolean =
            formed && clientCount > 0 && !allowWhileLive

        /** FIX 2c: pure decision core of startDiscovery's restart debounce —
         *  extracted so it's directly testable without a real
         *  WifiP2pManager/Channel/SystemClock. */
        fun shouldDebounceDiscoveryRestart(ageMs: Long): Boolean = ageMs < DISCOVERY_RESTART_MIN_AGE_MS

        // ── OCP CONNECT REBUILD PART 1: state-machine legality — pure,
        // extracted so every gate is directly unit-testable. ──────────────

        /** discoverPeers/discoverServices are legal ONLY in DISCOVERING —
         *  Part 1's own words. A caller landing here from any other state
         *  (including a stray retry timer tick whose state moved on to
         *  CONNECTING while it was waiting) is rejected, never silently
         *  allowed through. */
        fun canDiscover(state: P2pState): Boolean = state == P2pState.DISCOVERING

        /** connect/createGroup are legal from IDLE (nothing in flight) or
         *  DISCOVERING (the ordinary case — a scan is normally running when
         *  the user taps someone; [beginConnectSequence]'s own step (b) is
         *  what stops it). Never legal from CONNECTING (no double-connect
         *  races), CONNECTED (already there), or TEARDOWN (a teardown must
         *  finish settling first). */
        fun canBeginConnect(state: P2pState): Boolean = state == P2pState.IDLE || state == P2pState.DISCOVERING

        /** invitePeer (a GO adding a NEW member to a group it already
         *  hosts) is legal ONLY from CONNECTED — the exact opposite
         *  precondition from connect/createGroup, and deliberately NOT
         *  routed through the quiesce sequence (removeGroup there would
         *  destroy the very group being invited into — see
         *  guardedRemoveGroup's own doc on this exact failure mode). */
        fun canInvite(state: P2pState): Boolean = state == P2pState.CONNECTED

        /** OCP CONNECT REBUILD PART 4: pure BUSY-retry cap — extracted for
         *  direct unit testing. [attemptsMadeSoFar] is 1 after the FIRST
         *  connect() call; retrying is allowed while under
         *  CONNECT_BUSY_MAX_ATTEMPTS (3) total attempts. The >=3s spacing
         *  requirement is satisfied structurally — every retry is always
         *  scheduled exactly CONNECT_BUSY_RETRY_DELAY_MS (3000ms) later,
         *  never sooner — see [scheduleBusyRetry]. */
        fun shouldRetryConnectAfterBusy(attemptsMadeSoFar: Int): Boolean = attemptsMadeSoFar < CONNECT_BUSY_MAX_ATTEMPTS

        /** OCP CONNECT REBUILD PART 4: reason=0 (ERROR) and reason=1
         *  (P2P_UNSUPPORTED) are terminal — report immediately, never
         *  retry. reason=2 (BUSY) is the only retryable one. Any other
         *  value (a reason this app doesn't specifically recognize) is
         *  treated as terminal too — retrying an unknown failure
         *  indefinitely would be worse than surfacing it. */
        fun isTerminalConnectFailure(reason: Int): Boolean = reason != WifiP2pManager.BUSY

        /** BUG (QUIESCE IS DELETING THE PEER) FIX PART 1: pure decision core
         *  of whether quiesceBeforeConnect skips its stopPeerDiscovery step —
         *  true exactly for the negotiate/deviceAddress path ([explicit] is
         *  connect()'s own `explicitGroupsSupported(sdkInt) && networkName !=
         *  null && passphrase != null`). Deliberately NOT a string comparison
         *  against connectPath == "negotiate" — see [quiesceBeforeConnect]'s
         *  own doc for why that string is shared with a case (host()'s bare
         *  createGroup fallback) that must NOT skip the step. */
        fun shouldSkipStopDiscoveryForQuiesce(explicit: Boolean): Boolean = !explicit

        /** BUG (QUIESCE IS DELETING THE PEER) FIX PART 3: pure decision core
         *  of [verifyPeerThenConnect]/[recoverVanishedPeerThenConnect]'s
         *  presence check — extracted so "connect is not issued when the
         *  target is absent from requestPeers()" is directly testable
         *  without a real WifiP2pDeviceList (this project has no
         *  Robolectric). */
        fun isPeerPresent(deviceAddresses: List<String>, target: String): Boolean = target in deviceAddresses

        /** BUG (QUIESCE IS DELETING THE PEER) FIX PART 4: pure decision core
         *  of [issueConnect]'s one-shot reason=0 recovery — extracted so
         *  "exactly one re-discover-and-retry cycle, never a loop" is
         *  directly testable. True only the FIRST time reason=0 is seen for
         *  a deviceAddress-targeted attempt; [recoveryAttempted] (set true
         *  the instant this fires) makes a second reason=0 for the SAME
         *  connect() attempt fall straight through to the ordinary terminal
         *  path instead. */
        fun shouldRecoverFromTerminalError(reason: Int, hasDeviceAddress: Boolean, recoveryAttempted: Boolean): Boolean =
            reason == 0 && hasDeviceAddress && !recoveryAttempted

        // BUG (MAKE THE INVITE ACTUALLY TRANSMIT) FIX PART 3.2: standard
        // RFC 4648 base32 (no padding) — only the leading [maxChars]
        // characters this app ever actually keeps are computed.
        private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

        private fun base32Encode(bytes: ByteArray, maxChars: Int): String {
            val sb = StringBuilder(maxChars)
            var buffer = 0L
            var bitsInBuffer = 0
            for (b in bytes) {
                buffer = (buffer shl 8) or (b.toLong() and 0xFF)
                bitsInBuffer += 8
                while (bitsInBuffer >= 5 && sb.length < maxChars) {
                    bitsInBuffer -= 5
                    sb.append(BASE32_ALPHABET[((buffer ushr bitsInBuffer) and 0x1F).toInt()])
                }
                if (sb.length >= maxChars) break
            }
            return sb.toString()
        }

        /** The two 8-byte big-endian nodeIds, lower value first — the one
         *  piece of input material both [deriveFallbackNetworkName] and
         *  [deriveFallbackPassphrase] share, order-independent so either
         *  device (GO or joiner) arrives at the identical 16 bytes from
         *  nothing but the two nodeIds. */
        private fun sortedNodeIdBytes(nodeIdA: Long, nodeIdB: Long): ByteArray {
            val lo = minOf(nodeIdA, nodeIdB)
            val hi = maxOf(nodeIdA, nodeIdB)
            return ByteBuffer.allocate(16).putLong(lo).putLong(hi).array()
        }

        /** BUG (MAKE THE INVITE ACTUALLY TRANSMIT) FIX PART 3.2: the
         *  createGroup-FALLBACK network name (item 3 — reached only after
         *  connect()'s 25s deadline; see [host]'s doc) — "DIRECT-" is a hard
         *  Android P2P requirement (the framework rejects/rewrites a group
         *  network name not starting with it). SHA-256(min||max), base32,
         *  first 6 chars — both sides derive the SAME name from nothing but
         *  the two nodeIds, nothing secret crosses the wire. */
        fun deriveFallbackNetworkName(nodeIdA: Long, nodeIdB: Long): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(sortedNodeIdBytes(nodeIdA, nodeIdB))
            return NETWORK_NAME_PREFIX + base32Encode(digest, 6)
        }

        /** BUG (MAKE THE INVITE ACTUALLY TRANSMIT) FIX PART 3.2: the
         *  createGroup-FALLBACK WPA2-PSK passphrase — a DIFFERENT SHA-256
         *  input (literal "ocp-pw" prefix) than
         *  [deriveFallbackNetworkName]'s, so the name and the passphrase
         *  are never derivable from one another. base32, first 12 chars —
         *  comfortably over WPA2's 8-char minimum, well under its 63-char
         *  maximum. */
        fun deriveFallbackPassphrase(nodeIdA: Long, nodeIdB: Long): String {
            val material = "ocp-pw".toByteArray(Charsets.US_ASCII) + sortedNodeIdBytes(nodeIdA, nodeIdB)
            val digest = MessageDigest.getInstance("SHA-256").digest(material)
            return base32Encode(digest, 12)
        }

        /** PART "HASSLE-FREE JOIN" 1.1: the HOST-AND-SHOW flow's credential
         *  derivation — deliberately from ONE nodeId only, unlike
         *  [deriveFallbackNetworkName]/[deriveFallbackPassphrase] above
         *  (which need BOTH sides' nodeIds because that fallback is reached
         *  only once a SPECIFIC peer's invite has already stalled for 25s).
         *  Here there is no peer to wait on at all: the host derives these
         *  the instant "Start a group" is tapped, so [host] can run
         *  immediately with no discovery, no invite frame, no timeout — and
         *  any joiner (QR scan, or a BLE "group open" sighting — see
         *  MeshBleBeacon's own doc) recomputes the IDENTICAL pair from
         *  nothing but the host's nodeId, which is all either join path
         *  ever needs to learn.
         *
         *    networkName = "DIRECT-ocp-" + base32(sha256(hostNodeId))[0..5]
         *    passphrase  = base32(sha256("ocp-pw" || hostNodeId))[0..12]
         *
         *  Same base32/prefix/length choices as the fallback pair above —
         *  "DIRECT-" is a hard Android P2P requirement, the "ocp-pw" prefix
         *  on the passphrase input is a DIFFERENT SHA-256 input than the
         *  network name's, so one is never derivable from the other. */
        fun deriveHostNetworkName(hostNodeId: Long): String {
            val bytes = ByteBuffer.allocate(8).putLong(hostNodeId).array()
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return NETWORK_NAME_PREFIX + base32Encode(digest, 6)
        }

        fun deriveHostPassphrase(hostNodeId: Long): String {
            val material = "ocp-pw".toByteArray(Charsets.US_ASCII) + ByteBuffer.allocate(8).putLong(hostNodeId).array()
            val digest = MessageDigest.getInstance("SHA-256").digest(material)
            return base32Encode(digest, 12)
        }

        fun explicitGroupsSupported(sdkInt: Int): Boolean = sdkInt >= EXPLICIT_GROUP_MIN_SDK

        /** BUG (GROUP FORMS, NOBODY JOINS) FIX: pure TXT-record shape,
         *  extracted from [registerLocalService] so "gp survives alongside a
         *  live group's ss/pw" is directly testable without a real
         *  WifiP2pManager/Channel. Each field is present iff its input is
         *  non-null — the record's whole point is that re-publishing
         *  credentials (ss/pw) is never what silently drops an
         *  already-in-flight invite (gp).
         *
         *  BUG (MAKE THE INVITE ACTUALLY TRANSMIT) FIX PART 3.3: [waitingForShortId]
         *  is the invited peer's SHORT NODE ID again, never a MAC — a
         *  previous revision tried the peer's raw P2P deviceAddress instead,
         *  on the theory that a joiner could recognize its own address; that
         *  theory doesn't survive contact with hardware ("P2P: this device
         *  address=02:00:00:00:00:00" — Android hides the real local P2P MAC
         *  behind a fixed placeholder, so a peer can never recognize itself
         *  in a gp=&lt;mac&gt; field). This DNS-SD "gp" is now purely a
         *  best-effort SECOND attempt alongside BLE (see MeshBleBeacon's
         *  invite-target field) for the createGroup fallback (item 3) to
         *  reach a peer whose real nodeId BLE has already resolved — not the
         *  primary invite mechanism (that's connect()/PBC, see
         *  [WifiDirectManager.connect]'s doc; this class doesn't even try to
         *  transmit anything via createGroup, it just advertises and waits). */
        fun buildDnsSdRecord(
            displayName: String,
            shortNodeIdHex: String,
            protocolVersion: String,
            waitingForShortId: String?,
            groupNetworkName: String?,
            groupPassphrase: String?
        ): Map<String, String> = buildMap {
            put("n", displayName)
            put("id", shortNodeIdHex)
            put("v", protocolVersion)
            if (waitingForShortId != null) put("gp", waitingForShortId)
            if (groupNetworkName != null) put("ss", groupNetworkName)
            if (groupPassphrase != null) put("pw", groupPassphrase)
        }
    }

    private val manager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var registered = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var channelReinitCount = 0

    private var p2pEnabled = false
    private var discoveryRetryCount = 0
    // FIX 2c: elapsedRealtime of the current discovery window's start — used
    // to debounce a restart request that would otherwise cancel it too soon.
    private var lastDiscoveryStartAtMs = 0L
    // FIX 6d: set by requestGroupInfo(), read via groupSsid().
    private var currentGroupSsid: String? = null
    // PART 1 EXTRACTION: MeshTransport-only caches — see requestPeers'/
    // requestConnectionInfo's own doc comments at their write sites. Neither
    // is read by any pre-existing (non-MeshTransport) code path.
    private var lastKnownPeers: WifiP2pDeviceList? = null
    private var lastGroupOwnerAddress: InetAddress? = null
    private var transportCallbacks: MeshTransport.Callbacks? = null
    // BUG (DISCOVERY IS ONE-SHOT) FIX: the recurring DISCOVERY_REFRESH_MS
    // keepalive — see armDiscoveryCadence/cancelDiscoveryCadence, driven
    // entirely off [transition] so it can never keep ticking once state
    // leaves DISCOVERING (a connect attempt, a formed group, teardown).
    private var discoveryCadenceRunnable: Runnable? = null
    // Incremented once per discovery pass (the whole addServiceRequest ->
    // discoverServices -> discoverPeers chain) — purely a diagnostic
    // counter, read by onDiscoveryPassComplete's caller.
    private var discoveryPassCount = 0

    // ── OCP CONNECT REBUILD PART 1: the one state field ─────────────────────
    @Volatile private var state: P2pState = P2pState.IDLE

    /** Logged exactly once per actual transition — a call that leaves the
     *  state unchanged (e.g. re-entering DISCOVERING while already there)
     *  does not re-log. BUG (DISCOVERY IS ONE-SHOT) FIX: this is also the
     *  ONE place the discovery cadence is armed/disarmed — entering
     *  DISCOVERING from anywhere else starts it, leaving DISCOVERING for
     *  anywhere else stops it, regardless of which caller triggered the
     *  transition.
     *
     *  BUG (REVERT SERVICE-REQUEST CLEAR) FIX: this is now ALSO the one
     *  place [dnsSdServiceRequest] gets created and cleared — created once
     *  on entering DISCOVERING ([ensureDnsSdServiceRequest]), cleared from
     *  the framework's side once on LEAVING DISCOVERING
     *  ([clearDnsSdServiceRequests]). Neither happens inside a pass anymore
     *  (see [runDiscoveryPass]) — the working 11:15 session's per-pass shape
     *  was addServiceRequest -> discoverServices -> discoverPeers with NO
     *  clear in between; clearServiceRequests before every addServiceRequest
     *  (added to fight accumulation) is what the 20:31 session's zero
     *  "DNSSD: rx" lines trace back to. */
    private fun transition(to: P2pState, why: String) {
        val from = state
        if (from == to) return
        state = to
        // PART 1.5: this class's own log lines keep the pre-existing "P2P:"
        // prefix unchanged (see this function's own doc — every transition
        // funnels through here, so this one line covers the overwhelming
        // majority of them without touching the ~50 scattered call/result
        // log lines throughout the rest of the file); the transport id is
        // now ALSO on it, and the dedicated XPORT: line below carries the
        // full transport-registry-relevant summary (state/peer count/caps)
        // for once a second transport exists (Part 2) and these lines stop
        // being unambiguous on their own.
        Log.d("OFFTRACE", "P2P[$id]: state $from -> $to reason=$why")
        Log.d(
            "OFFTRACE",
            "XPORT: id=$id state=${mapP2pState(to)} peers=${lastKnownPeers?.deviceList?.size ?: 0} caps=$capabilities"
        )
        if (to == P2pState.DISCOVERING) {
            armDiscoveryCadence()
            ensureDnsSdServiceRequest()
        } else if (from == P2pState.DISCOVERING) {
            cancelDiscoveryCadence()
            clearDnsSdServiceRequests()
        }
    }

    /** BUG (REVERT SERVICE-REQUEST CLEAR) FIX: creates [dnsSdServiceRequest]
     *  exactly once — the SAME object is reused by every addServiceRequest
     *  call across every discovery pass and every DISCOVERING session
     *  thereafter (re-adding the same instance does not accumulate; see
     *  [issueServiceRequest]). A no-op once the field is already set. */
    private fun ensureDnsSdServiceRequest() {
        if (dnsSdServiceRequest != null) return
        dnsSdServiceRequest = WifiP2pDnsSdServiceRequest.newInstance()
    }

    /** BUG (REVERT SERVICE-REQUEST CLEAR) FIX: the ONLY two places
     *  clearServiceRequests may run — here (leaving DISCOVERING) and
     *  [teardown]. Never inside [runDiscoveryPass]/[issueServiceRequest] —
     *  that hot path is addServiceRequest -> discoverServices ->
     *  discoverPeers ONLY, exactly the shape of the 11:15 session that
     *  worked. Removes the framework-side registration only; the Kotlin
     *  [dnsSdServiceRequest] object itself is left alone so the NEXT
     *  DISCOVERING session's first pass can re-add the same instance
     *  without needing [ensureDnsSdServiceRequest] to run again. */
    @SuppressLint("MissingPermission")
    private fun clearDnsSdServiceRequests() {
        val ch = channel ?: return
        logCall("clearServiceRequests", "leaving-discovering")
        manager?.clearServiceRequests(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "clearServiceRequests: success") }
            override fun onFailure(reason: Int) { Log.d(TAG, "clearServiceRequests failed: $reason (nothing to clear is normal)") }
        })
    }

    /** BUG (DISCOVERY IS ONE-SHOT) FIX: schedules the NEXT discovery pass
     *  DISCOVERY_REFRESH_MS from now, self-rescheduling as long as [state]
     *  is still DISCOVERING each time it fires. Calls [runDiscoveryPass]
     *  directly — NOT [startDiscovery] — so this internal keepalive is
     *  never subject to [shouldDebounceDiscoveryRestart]: that debounce
     *  exists to stop an EXTERNAL restart request from cancelling an
     *  in-flight window (see its own doc), which is exactly what would
     *  happen every single tick if the cadence went through startDiscovery
     *  instead (12s < the 15s debounce threshold, so every tick would
     *  either be silently swallowed or — worse — call stopPeerDiscovery
     *  first and cancel the very scan it just started). */
    private fun armDiscoveryCadence() {
        cancelDiscoveryCadence()
        val runnable = object : Runnable {
            override fun run() {
                if (!canDiscover(state)) return // no longer DISCOVERING — do not reschedule
                runDiscoveryPass()
                mainHandler.postDelayed(this, DISCOVERY_REFRESH_MS)
            }
        }
        discoveryCadenceRunnable = runnable
        mainHandler.postDelayed(runnable, DISCOVERY_REFRESH_MS)
    }

    private fun cancelDiscoveryCadence() {
        discoveryCadenceRunnable?.let { mainHandler.removeCallbacks(it) }
        discoveryCadenceRunnable = null
    }

    /** OCP CONNECT REBUILD PART 6: every WifiP2pManager call in this class
     *  goes through this first — makes a stray discovery restart (or any
     *  other unexpected call) visible in one grep regardless of which
     *  function issued it. [api] is the literal WifiP2pManager method name
     *  about to be invoked; [tag] identifies the calling function. */
    private fun logCall(api: String, tag: String) {
        // PART 1.5: transport id prefix — see transition()'s identical note.
        Log.d("OFFTRACE", "P2P[$id]: call=$api from=$tag state=$state")
    }

    var onPeersChanged: ((WifiP2pDeviceList) -> Unit)? = null
    var onConnectionChanged: ((WifiP2pInfo) -> Unit)? = null
    var onP2pStateChanged: ((Boolean) -> Unit)? = null
    // FIX 2d: the framework clears the service-discovery request and (per
    // some OEMs) the advertised local service on some discovery-stopped
    // transitions — the caller re-arms both here rather than this class
    // trying to remember display name/nodeId/version itself (that's
    // Activity-owned data). The caller also decides WHETHER re-arming is
    // appropriate right now (e.g. not while FIX 1c has discovery
    // deliberately paused for a live group) — this class only reports the
    // transition.
    var onDiscoveryStopped: (() -> Unit)? = null

    /** Fired when the P2P channel is lost and cannot be recovered after MAX_CHANNEL_REINITS. */
    var onFatalError: ((String) -> Unit)? = null

    /** BUG (GROUP FORMS, NOBODY JOINS) FIX: fired with the live group's client
     *  list every time [WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION] fires
     *  while [state] is CONNECTED — see [logGroupClients]. This is the only
     *  reliable source of "did the invited peer actually associate", since
     *  connect()'s onSuccess/groupFormed are both signals about THIS device's
     *  own link, not about who's in the group. */
    var onGroupClientsChanged: ((List<WifiP2pDevice>) -> Unit)? = null

    /** BUG (MAKE THE INVITE ACTUALLY TRANSMIT) FIX PART 1.4/3.1: fired the
     *  moment [connect]'s 25s deadline actually expires (see
     *  [armConnectDeadline]) — the ONE signal the caller needs to fall back
     *  to createGroup() (item 3), as opposed to a fast BUSY-exhausted or
     *  terminal-reason failure, which [onOutcome] alone already distinguishes
     *  by how quickly it fires. Fired exactly once per connect attempt, right
     *  after the deadline's own teardown starts and before [onOutcome]/
     *  [pendingConnectOutcome] resolve — this is the survivable path for
     *  reinvokePersistentGroup HAL hiccups (08-21: getClientList threw inside
     *  it) too: the app makes no special case for that exception, it simply
     *  lets this same deadline fire and falls through to the same fallback. */
    var onConnectDeadlineFired: ((peerTag: String) -> Unit)? = null

    /** BUG (DISCOVERY IS ONE-SHOT) FIX: fired once at the end of every
     *  discovery pass (success or failure, at whichever step it stopped) —
     *  [pass] is the 1-based counter, [liveServiceRequests] is always 0 or 1
     *  (see [liveServiceRequestCount]). The caller pairs this with its own
     *  found/resolved counts for the one-line-per-pass summary. */
    var onDiscoveryPassComplete: ((pass: Int, liveServiceRequests: Int) -> Unit)? = null

    // BUG 2 FIX: DNS-SD — a pre-connect display-name hint. UNVERIFIED: no
    // signature exists before HELLO, this is whatever the OTHER device's TXT
    // record claims. Never use this for any permission decision, never cache
    // it as a verified name — see [onServiceTxtRecordFound]'s own doc.
    private var dnsSdListenersRegistered = false
    private var dnsSdServiceRequest: WifiP2pDnsSdServiceRequest? = null

    /** [deviceAddress]->TXT record map (see [DNSSD_SERVICE_TYPE]'s n/id/v/gp
     *  keys — "gp" is OCP CONNECT REBUILD PART 3's addition, see
     *  [registerLocalService]'s doc). UNVERIFIED — same caveat as the class
     *  doc above. */
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
                        val s = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        p2pEnabled = s == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        onP2pStateChanged?.invoke(p2pEnabled)
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        Log.d("OFFTRACE", "P2P_CONNECTION_CHANGED fired")
                        requestConnectionInfo()
                        // BUG (GROUP FORMS, NOBODY JOINS) FIX: the only way to see an
                        // actual client association (AP-STA-CONNECTED) rather than just
                        // this device's own groupFormed — see logGroupClients's doc.
                        if (state == P2pState.CONNECTED) logGroupClients()
                    }
                    WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                        val s = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1)
                        if (s == WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED) {
                            // OCP CONNECT REBUILD PART 1: a discovery-stopped
                            // transition only ever moves DISCOVERING -> IDLE —
                            // never touches CONNECTING/CONNECTED/TEARDOWN (the
                            // quiesce sequence's own stopPeerDiscovery call
                            // already accounted for that leg explicitly).
                            if (state == P2pState.DISCOVERING) transition(P2pState.IDLE, "discovery-stopped")
                            // FIX 2d: the framework clears the service-discovery
                            // request (and, on some OEMs, the advertised local
                            // service) on this transition — let the caller
                            // decide whether to re-arm both now.
                            Log.d("OFFTRACE", "P2P-FIND-STOPPED")
                            onDiscoveryStopped?.invoke()
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
     *  [runDiscoveryPass] below). Safe to call more than once — called from
     *  [init] (before any discovery ever starts) AND defensively at the top
     *  of every [runDiscoveryPass], so a first addServiceRequest can never
     *  race ahead of these listeners being armed.
     *
     *  BUG (DISCOVERY IS ONE-SHOT) FIX: zero "DNSSD: rx" lines in a whole
     *  capture is ambiguous — it could mean no peer ever responded, or it
     *  could mean these listeners were never actually armed (or armed too
     *  late). The log below removes that ambiguity: it fires exactly once,
     *  the moment registration actually completes, so its ABSENCE from a
     *  capture is itself proof the listeners were never armed at all. */
    @SuppressLint("MissingPermission")
    private fun registerDnsSdListeners() {
        if (dnsSdListenersRegistered) return
        val ch = channel ?: return
        // Confirmed via the compileSdk android.jar (javap): the platform API is
        // ONE combined setter, not separate setDnsSdResponseListener/
        // setDnsSdTxtRecordListener calls — txt/srv below are therefore
        // always armed together, never one without the other.
        manager?.setDnsSdResponseListeners(
            ch,
            WifiP2pManager.DnsSdServiceResponseListener { _, _, _ ->
                // Service-instance events carry no TXT data of their own on most
                // OEMs — the TXT listener below is the reliable source for the
                // fields this app actually needs (n/id/v/gp).
            },
            WifiP2pManager.DnsSdTxtRecordListener { _, record, device ->
                // BUG (REVERT SERVICE-REQUEST CLEAR) FIX PART 3: the raw
                // callback, logged the INSTANT it fires — before any of the
                // parsing/gating below. A record this app ends up discarding
                // (an unrecognized key, a peer whose row later gets dropped)
                // is still visible in a capture; the "DNSSD: rx" line further
                // down only reflects the FILTERED n/id/gp view.
                Log.d("OFFTRACE", "DNSSD: raw txt from=${device.deviceAddress} keys=${record.keys}")
                val name = record["n"]
                val idHex = record["id"]
                // BUG 2: UNVERIFIED — no signature exists before HELLO. This is
                // purely a discovery-list hint; the caller must never treat it
                // as authenticated (see [onServiceTxtRecordFound]'s doc).
                // FIX 2b: used to require BOTH "n" and "id" (via `if (name !=
                // null)` gating everything, here AND a second time in
                // OfflineCallActivity's own consumer) — a record carrying only
                // one of the two was dropped twice with zero trace. Forward
                // whenever ANY of the recognized fields is present; the
                // consumer decides what it can use with what it's got.
                val gp = record["gp"]
                val forwarded = name != null || idHex != null || gp != null
                Log.d("OFFTRACE", "DNSSD: rx ${device.deviceAddress} n=$name id=$idHex gp=$gp forwarded=$forwarded")
                if (forwarded) {
                    onServiceTxtRecordFound?.invoke(device.deviceAddress, record)
                }
            }
        )
        dnsSdListenersRegistered = true
        Log.d("OFFTRACE", "DNSSD: listeners armed txt=true srv=true")
    }

    /** BUG 2 FIX: advertises this device's display name/nodeId/protocol version
     *  over Wi-Fi Direct service discovery so a peer's "nearby devices" list can
     *  show a real name before any connection (and therefore before HELLO) is
     *  possible — see WifiP2pManager.setDeviceName's rejection (reason=0 on
     *  target hardware, plus it tears down the P2P group) for why this exists
     *  instead. Clears any previously-registered local service first so
     *  re-registering after a display-name change never leaves two stale TXT
     *  records advertised at once.
     *
     *  OCP CONNECT REBUILD PART 3 / BUG (MAKE THE INVITE ACTUALLY TRANSMIT)
     *  FIX PART 3.3: [waitingForShortId], when non-null, adds a "gp" ("group
     *  ping") field carrying the invited peer's SHORT NODE ID — the SIGNAL a
     *  joiner watches for in [onServiceTxtRecordFound] to know THIS device
     *  just became an explicit GO and is specifically waiting for a peer
     *  whose own resolved nodeId matches. This is DNS-SD's best-effort
     *  attempt at the same signal MeshBleBeacon's invite-target field
     *  carries independently — reachable only for a peer BLE (or any other
     *  channel) has already resolved a real nodeId for; see [host]'s doc for
     *  why createGroup (what this accompanies) is a FALLBACK, not the
     *  primary invite.
     *
     *  BUG (GROUP FORMS, NOBODY JOINS) FIX: [groupNetworkName]/[groupPassphrase],
     *  when non-null, add "ss"/"pw" fields carrying the GO's actual live group
     *  credentials (read from WifiP2pGroup by the caller — the framework is the
     *  source of truth for what the group ACTUALLY is, not the derived values
     *  [deriveFallbackNetworkName]/[deriveFallbackPassphrase] compute before
     *  it exists). The joiner still falls back to deriving them if these are
     *  absent (an older peer, or a caller that hasn't read group info back yet).
     *
     *  SECURITY NOTE: DNS-SD TXT records are broadcast in the clear — anyone
     *  in Wi-Fi Direct range can read "pw" off the air, same as they could
     *  read it off any other unauthenticated Wi-Fi Direct invite. This is a
     *  deliberate tradeoff, not an oversight: it only exposes the WFD-layer
     *  group password, and this app's actual security (peer identity, message
     *  integrity) rests entirely on the Ed25519 HELLO/signing layer that runs
     *  *after* this link exists — see MeshSigner's class doc. */
    @SuppressLint("MissingPermission")
    fun registerLocalService(
        displayName: String,
        shortNodeIdHex: String,
        protocolVersion: String,
        waitingForShortId: String? = null,
        groupNetworkName: String? = null,
        groupPassphrase: String? = null,
        onResult: ((Boolean) -> Unit)? = null
    ) {
        val ch = channel
        // FIX 2a: these two bails used to be completely silent — a
        // registration that never even attempted (channel/manager not ready
        // yet) looked identical in logcat to one that succeeded. Evidence:
        // the 20:30 capture has zero "DNSSD: local register" lines at all,
        // where the 13:35 capture had one at startup — this is exactly the
        // gap that made the difference undiagnosable.
        if (ch == null) {
            Log.w("OFFTRACE", "DNSSD: local register false reason=channel_null")
            onResult?.invoke(false)
            return
        }
        if (manager == null) {
            Log.w("OFFTRACE", "DNSSD: local register false reason=manager_null")
            onResult?.invoke(false)
            return
        }
        val record = buildDnsSdRecord(displayName, shortNodeIdHex, protocolVersion, waitingForShortId, groupNetworkName, groupPassphrase)
        val info = WifiP2pDnsSdServiceInfo.newInstance(DNSSD_INSTANCE_NAME, DNSSD_SERVICE_TYPE, record)
        logCall("clearLocalServices", "registerLocalService")
        manager.clearLocalServices(ch, object : WifiP2pManager.ActionListener {
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
        logCall("addLocalService", "addLocalServiceInternal")
        manager?.addLocalService(ch, info, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d("OFFTRACE", "DNSSD: local register true reason=ok name=\"$displayName\"")
                onResult?.invoke(true)
            }
            override fun onFailure(reason: Int) {
                Log.w("OFFTRACE", "DNSSD: local register false reason=$reason")
                onResult?.invoke(false)
            }
        })
    }

    /** BUG (REVERT SERVICE-REQUEST CLEAR) FIX: the ONE-request-live count —
     *  0 or 1, never more, WITHOUT ever clearing inside a pass. [dnsSdServiceRequest]
     *  is created exactly once, on entering DISCOVERING ([ensureDnsSdServiceRequest]),
     *  and re-added (not recreated) by every pass — re-adding the SAME instance
     *  does not accumulate a second live request on the framework's side; that's
     *  a property of addServiceRequest, not something this class has to enforce
     *  by clearing first. */
    private fun liveServiceRequestCount(): Int = if (dnsSdServiceRequest != null) 1 else 0

    /** BUG (REVERT SERVICE-REQUEST CLEAR) FIX: back to exactly the 11:15
     *  session's per-pass shape — addServiceRequest -> (await onSuccess) ->
     *  discoverServices -> (await onSuccess) -> discoverPeers, no
     *  clearServiceRequests anywhere in this chain. The previous revision
     *  added a clearServiceRequests step before every addServiceRequest
     *  specifically to stop requests from piling up — but that clear, 5ms
     *  ahead of the next addServiceRequest and NOT awaited by anything, is
     *  exactly what the 20:31 session's zero "DNSSD: rx" traces back to;
     *  accumulation is instead prevented structurally now, by holding one
     *  request object for the whole DISCOVERING session and clearing it only
     *  on leaving DISCOVERING (see [transition]/[clearDnsSdServiceRequests]).
     *  Still legal only in DISCOVERING ([canDiscover], re-checked at every
     *  step). Called by [armDiscoveryCadence] directly, and by
     *  [clearStaleGroupThenDiscover] for the very first pass of a fresh
     *  discovery session. */
    @SuppressLint("MissingPermission")
    private fun runDiscoveryPass(onResult: ((Boolean) -> Unit)? = null) {
        if (!canDiscover(state)) {
            Log.d("OFFTRACE", "P2P: discovery pass SKIPPED state=$state (no longer DISCOVERING)")
            onResult?.invoke(false)
            return
        }
        val ch = channel
        if (ch == null) {
            onResult?.invoke(false)
            return
        }
        // BUG (DISCOVERY IS ONE-SHOT) FIX PART 4: re-armed defensively on
        // every pass (a no-op after the first — see the function's own
        // dnsSdListenersRegistered guard) so a first addServiceRequest can
        // never race ahead of these listeners.
        registerDnsSdListeners()
        discoveryPassCount++
        val pass = discoveryPassCount
        issueServiceRequest(ch, pass, onResult)
    }

    @SuppressLint("MissingPermission")
    private fun issueServiceRequest(ch: WifiP2pManager.Channel, pass: Int, onResult: ((Boolean) -> Unit)?) {
        if (!canDiscover(state)) {
            completePass(pass, false, onResult)
            return
        }
        // BUG (REVERT SERVICE-REQUEST CLEAR) FIX: the field is normally
        // already set by ensureDnsSdServiceRequest (on entering DISCOVERING)
        // by the time any pass runs — the `?:` here is defensive only, never
        // the primary creation path.
        val request = dnsSdServiceRequest ?: WifiP2pDnsSdServiceRequest.newInstance().also { dnsSdServiceRequest = it }
        logCall("addServiceRequest", "runDiscoveryPass")
        manager?.addServiceRequest(ch, request, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = issueDiscoverServices(ch, pass, onResult)
            override fun onFailure(reason: Int) {
                Log.w("OFFTRACE", "DNSSD: addServiceRequest failed reason=$reason pass=$pass")
                completePass(pass, false, onResult)
            }
        }) ?: completePass(pass, false, onResult)
    }

    @SuppressLint("MissingPermission")
    private fun issueDiscoverServices(ch: WifiP2pManager.Channel, pass: Int, onResult: ((Boolean) -> Unit)?) {
        if (!canDiscover(state)) {
            completePass(pass, false, onResult)
            return
        }
        logCall("discoverServices", "runDiscoveryPass")
        manager?.discoverServices(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "discoverServices: success pass=$pass")
                // BUG (DISCOVERY IS ONE-SHOT) FIX PART 2: chained here, on
                // discoverServices' OWN success callback — never fired
                // alongside it. This is the one scan-trigger-at-a-time
                // ordering: discoverPeers only issues once the framework has
                // confirmed it accepted the service-discovery request.
                discoverPeersInternal(pass, onResult)
            }
            override fun onFailure(reason: Int) {
                Log.w("OFFTRACE", "DNSSD: discoverServices failed reason=$reason pass=$pass")
                completePass(pass, false, onResult)
            }
        }) ?: completePass(pass, false, onResult)
    }

    private fun completePass(pass: Int, ok: Boolean, onResult: ((Boolean) -> Unit)?) {
        onDiscoveryPassComplete?.invoke(pass, liveServiceRequestCount())
        onResult?.invoke(ok)
    }

    /** OCP CONNECT REBUILD PART 1: the ONLY entry point that may move state
     *  INTO DISCOVERING — rejects outright from CONNECTING/CONNECTED/
     *  TEARDOWN (a discovery scan has no business running while a connect
     *  attempt or a live group exists; that's exactly the bug class this
     *  whole rebuild exists to close). From IDLE, transitions to
     *  DISCOVERING BEFORE the framework call, matching Part 1's own
     *  wording for connect/createGroup. From DISCOVERING already, this is
     *  the pre-existing debounced-restart behavior — state doesn't change
     *  (still DISCOVERING), so no new transition log fires. */
    @SuppressLint("MissingPermission")
    fun startDiscovery(onResult: ((Boolean) -> Unit)? = null) {
        if (state == P2pState.CONNECTING || state == P2pState.CONNECTED || state == P2pState.TEARDOWN) {
            Log.d("OFFTRACE", "P2P: startDiscovery REJECTED state=$state")
            onResult?.invoke(false)
            return
        }
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
        if (state == P2pState.DISCOVERING) {
            // FIX 2c: a restart this soon after the current window began is
            // very likely to cancel an in-flight service-discovery request
            // along with it (see DISCOVERY_RESTART_MIN_AGE_MS's doc) —
            // debounced below that age rather than honoured immediately.
            // The current scan is already running, so this is a no-op
            // success, not a failure.
            val age = SystemClock.elapsedRealtime() - lastDiscoveryStartAtMs
            if (shouldDebounceDiscoveryRestart(age)) {
                Log.d("OFFTRACE", "P2P: discovery restart DEBOUNCED age=${age}ms (< ${DISCOVERY_RESTART_MIN_AGE_MS}ms)")
                onResult?.invoke(true)
                return
            }
            Log.d(TAG, "startDiscovery: a discovery is already pending, stopping it first")
            Log.d("OFFTRACE", "P2P: discovery restart age=${age}ms")
            stopPeerDiscovery { clearStaleGroupThenDiscover(onResult) }
            return
        }
        transition(P2pState.DISCOVERING, "startDiscovery")
        clearStaleGroupThenDiscover(onResult)
    }

    @SuppressLint("MissingPermission")
    fun stopPeerDiscovery(onResult: ((Boolean) -> Unit)? = null) {
        if (channel == null) {
            Log.w(TAG, "stopPeerDiscovery: channel is null, ignoring")
            if (state == P2pState.DISCOVERING) transition(P2pState.IDLE, "stopPeerDiscovery-no-channel")
            onResult?.invoke(false)
            return
        }
        logCall("stopPeerDiscovery", "stopPeerDiscovery")
        manager?.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "stopPeerDiscovery: success")
                if (state == P2pState.DISCOVERING) transition(P2pState.IDLE, "stopPeerDiscovery")
                onResult?.invoke(true)
            }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "stopPeerDiscovery failed: $reason")
                if (state == P2pState.DISCOVERING) transition(P2pState.IDLE, "stopPeerDiscovery-failed")
                onResult?.invoke(false)
            }
        })
    }

    /** FIX 1b: the ONLY path any code in this class may use to actually
     *  invoke WifiP2pManager.removeGroup() — every other call site below
     *  (clearStaleGroupThenDiscover, disconnect, createGroup) routes
     *  through this. Queries the AUTHORITATIVE state (requestGroupInfo,
     *  which carries the real clientList) immediately before acting —
     *  never trusts a locally-cached flag, since that's exactly what let
     *  the original bug destroy a group with a connected client seconds
     *  after it formed (see clearStaleGroupThenDiscover's old
     *  unconditional removeGroup, which used to fire from this same spot
     *  before every single discovery start).
     *
     *  OCP CONNECT REBUILD PART 1: additionally never runs while
     *  state==CONNECTING or CONNECTED UNLESS [allowWhileLive] — this is the
     *  literal gate Part 1 asks for ("the pre-discovery removeGroup must
     *  not run in CONNECTING or CONNECTED"), layered on top of the
     *  pre-existing client-count guard below (belt and suspenders: either
     *  guard alone already blocks the pre-discovery case, since a live
     *  group always has state==CONNECTED AND clients>0).
     *
     *  [allowWhileLive] is the one deliberate escape hatch: [disconnect]
     *  is the INTENTIONAL "leave this group" path, and removeGroup is the
     *  only mechanism that actually leaves a group with peers still in
     *  it — blocking it unconditionally would make leaving a live group
     *  impossible, not just the accidental/opportunistic teardown this
     *  guard exists to stop. Every OTHER caller (pre-discovery cleanup,
     *  createGroup) leaves this false — the hard, no-exception guard the
     *  task describes applies to them. The connect sequence's own step (c)
     *  ALSO passes true — it's mid-CONNECTING, tearing down a STALE group
     *  left over from a previous session on purpose, not the live one this
     *  guard exists to protect.
     *
     *  [onBlocked] fires (and the real removeGroup is never called) when
     *  a group is currently formed with one or more clients and
     *  [allowWhileLive] is false. [onDone] fires otherwise, with whether
     *  the (attempted) removeGroup itself succeeded — matching the
     *  pre-existing "failure is normal/expected when nothing was there to
     *  remove" behaviour every caller already handled.
     *
     *  The required diagnostic line is logged EXACTLY once, immediately
     *  before the real removeGroup call, using the same formed/clients
     *  values the guard just used to decide — so if formed=true ever
     *  appears on it again, the guard itself has a hole, not just this
     *  one call site. */
    @SuppressLint("MissingPermission")
    private fun guardedRemoveGroup(
        reason: String,
        tag: String,
        allowWhileLive: Boolean = false,
        onBlocked: () -> Unit = {},
        onDone: (Boolean) -> Unit
    ) {
        if (channel == null) {
            Log.w(TAG, "guardedRemoveGroup($reason): channel is null, ignoring")
            onDone(false)
            return
        }
        // OCP CONNECT REBUILD PART 1: the literal gate — quote-able on its
        // own, independent of the requestGroupInfo-based client-count check
        // below (which would ALSO block a live group, but this is the
        // exact state check Part 1 asks for).
        if ((state == P2pState.CONNECTING || state == P2pState.CONNECTED) && !allowWhileLive) {
            Log.w("OFFTRACE", "P2P: removeGroup BLOCKED reason=$reason state=$state (CONNECTING/CONNECTED)")
            onBlocked()
            return
        }
        manager?.requestGroupInfo(channel) { group ->
            // BUG (TEARDOWN CRASH) FIX: [channel] can go null between this
            // requestGroupInfo call being issued and its callback firing —
            // teardown() nulls it from an unrelated caller while this is in
            // flight. The channel captured further up this function is now
            // stale; re-read the current property and bail rather than
            // handing WifiP2pManager.removeGroup a null Channel, which throws
            // IllegalArgumentException("Channel needs to be initialized") on
            // this (main) thread and kills the process.
            val ch = channel
            if (ch == null) {
                Log.w(TAG, "guardedRemoveGroup($reason): channel went null before requestGroupInfo callback, ignoring")
                onDone(false)
                return@requestGroupInfo
            }
            val formed = group != null
            val clients = group?.clientList?.size ?: 0
            if (shouldBlockGroupTeardown(formed, clients, allowWhileLive)) {
                Log.w(TAG, "P2P: removeGroup BLOCKED reason=$reason — group is LIVE with $clients client(s)")
                Log.w("OFFTRACE", "P2P: removeGroup BLOCKED reason=$reason formed=true clients=$clients")
                onBlocked()
                return@requestGroupInfo
            }
            Log.d("OFFTRACE", "P2P: removeGroup reason=$reason formed=$formed clients=$clients")
            logCall("removeGroup", tag)
            manager?.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "removeGroup ($reason): success")
                    onDone(true)
                }
                override fun onFailure(failReason: Int) {
                    Log.d(TAG, "removeGroup ($reason) failed: $failReason (no group is expected here)")
                    onDone(false)
                }
            })
        }
    }

    @SuppressLint("MissingPermission")
    private fun clearStaleGroupThenDiscover(onResult: ((Boolean) -> Unit)?) {
        if (channel == null) {
            Log.w(TAG, "clearStaleGroupThenDiscover: channel is null, ignoring")
            onResult?.invoke(false)
            return
        }
        discoveryRetryCount = 0
        // FIX 1: was an unconditional manager.removeGroup() before every
        // discovery start (comment used to read "Clears a group left
        // behind by a prior crashed/killed session") — on a LIVE group
        // with a connected client, this destroyed the group it had just
        // formed. Now routed through the one chokepoint above, which
        // refuses to touch a live group; per FIX 1c, a live group also
        // means discovery itself does not restart (onBlocked below never
        // calls runDiscoveryPass).
        guardedRemoveGroup(
            reason = "pre-discovery",
            tag = "clearStaleGroupThenDiscover",
            onBlocked = {
                Log.d(TAG, "clearStaleGroupThenDiscover: SKIPPED — group is live, discovery not restarted")
                onResult?.invoke(false)
            },
            // BUG (DISCOVERY IS ONE-SHOT) FIX: this used to call
            // discoverPeersInternal directly (bare peer discovery only) —
            // now runs the full addServiceRequest->discoverServices->
            // discoverPeers chain as this session's FIRST pass (the
            // request itself already exists by now — see [transition]'s
            // ensureDnsSdServiceRequest call on the DISCOVERING entry that
            // preceded this). armDiscoveryCadence takes over every pass
            // after this one.
            onDone = { runDiscoveryPass(onResult) }
        )
    }

    @SuppressLint("MissingPermission")
    private fun discoverPeersInternal(pass: Int, onResult: ((Boolean) -> Unit)?) {
        if (!canDiscover(state)) {
            Log.d("OFFTRACE", "P2P: discoverPeers REJECTED state=$state (legal only in DISCOVERING)")
            completePass(pass, false, onResult)
            return
        }
        if (channel == null) {
            Log.w(TAG, "discoverPeersInternal: channel is null, ignoring")
            completePass(pass, false, onResult)
            return
        }
        logCall("discoverPeers", "discoverPeersInternal")
        manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "discoverPeers: success")
                lastDiscoveryStartAtMs = SystemClock.elapsedRealtime() // FIX 2c: stamped at the moment a window actually begins, not merely requested
                discoveryRetryCount = 0
                completePass(pass, true, onResult)
            }
            override fun onFailure(reason: Int) {
                Log.e(TAG, "discoverPeers failed: $reason")
                // OCP CONNECT REBUILD PART 1: the periodic retry timer below
                // checks state on every tick and bails unless still
                // DISCOVERING — a connect attempt (or anything else) that
                // moved state on while this retry was waiting must not
                // resurrect a discoverPeers call underneath it.
                if (reason == WifiP2pManager.BUSY && discoveryRetryCount < MAX_DISCOVERY_RETRIES) {
                    discoveryRetryCount++
                    Log.w(TAG, "discoverPeers retry $discoveryRetryCount after BUSY")
                    mainHandler.postDelayed(
                        {
                            if (!canDiscover(state)) {
                                Log.d("OFFTRACE", "P2P: discoverPeers retry SKIPPED state=$state (no longer DISCOVERING)")
                                completePass(pass, false, onResult)
                                return@postDelayed
                            }
                            discoverPeersInternal(pass, onResult)
                        },
                        DISCOVERY_RETRY_DELAY_MS
                    )
                } else {
                    Log.e("OFFTRACE", "discoverPeers failed (final): reason=$reason")
                    completePass(pass, false, onResult)
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
        manager?.requestPeers(channel) { peers ->
            // C1 / BUG (TEARDOWN CRASH) FIX: channel can go null between this
            // requestPeers call and its callback firing — teardown() nulls it
            // from an unrelated caller while this is in flight. See
            // guardedRemoveGroup's identical fix for the crash this guards
            // against on a call that DOES touch channel; this callback
            // doesn't itself, but bailing keeps every requestXxx callback in
            // this class following the same rule rather than only the ones
            // that happen to crash today.
            if (channel == null) {
                Log.w(TAG, "requestPeers: channel went null before callback, ignoring")
                return@requestPeers
            }
            // PART 1 EXTRACTION: cached purely for invite()'s DiscoveredPeer ->
            // WifiP2pDevice resolution below — see that function's doc. Additive
            // field write only; nothing existing reads it.
            lastKnownPeers = peers
            trackPeerListTransitions(peers, "peers-changed")
            onPeersChanged?.invoke(peers)
        }
    }

    // BUG (QUIESCE IS DELETING THE PEER) FIX PART 5: last known status per
    // address, across every requestPeers() result this class has ever seen
    // (regardless of which caller/why triggered the fetch) — so a peer
    // silently dropping out of the framework's own list (wpa_supplicant's
    // P2P-DEVICE-LOST, invisible anywhere in this app's own log before this)
    // is visible immediately, not just inferable after the fact from a
    // connect() failure five hundred milliseconds later.
    private val knownPeerStatus = mutableMapOf<String, Int>()

    /** Logs every membership/status transition in [peers] against what this
     *  class last saw — an address appearing for the first time, an
     *  existing address's status changing, or (the exact class of bug this
     *  fix addresses) an address that was known and is now simply ABSENT
     *  from the list. [why] identifies the caller (the ordinary
     *  peers-changed broadcast, or one of the pre-connect/recovery checks
     *  below) so a capture shows not just THAT a peer vanished but which
     *  code path was looking when it did. */
    private fun trackPeerListTransitions(peers: WifiP2pDeviceList, why: String) {
        val current = peers.deviceList.associate { it.deviceAddress to it.status }
        current.forEach { (addr, status) ->
            val prev = knownPeerStatus[addr]
            if (prev != status) {
                Log.d("OFFTRACE", "P2P: peer $addr ${prev ?: "ABSENT"} -> $status src=$why")
            }
        }
        knownPeerStatus.keys.toList().forEach { addr ->
            if (addr !in current) {
                Log.d("OFFTRACE", "P2P: peer $addr ${knownPeerStatus[addr]} -> GONE src=$why")
            }
        }
        knownPeerStatus.clear()
        knownPeerStatus.putAll(current)
    }

    /** BUG (GROUP FORMS, NOBODY JOINS) FIX: called on every CONNECTION_CHANGED
     *  broadcast while [state] is CONNECTED so a capture always shows whether a
     *  client actually associated (AP-STA-CONNECTED), not just that this
     *  device's own group exists. Re-reads [channel] itself rather than
     *  trusting a value captured before the async requestGroupInfo call — see
     *  [guardedRemoveGroup]'s teardown-race fix for why that matters. */
    @SuppressLint("MissingPermission")
    private fun logGroupClients() {
        val ch = channel ?: return
        manager?.requestGroupInfo(ch) { group ->
            if (channel == null) return@requestGroupInfo
            val clients = group?.clientList?.toList() ?: emptyList()
            Log.d("OFFTRACE", "P2P: clients=${clients.size} names=${clients.map { it.deviceName }}")
            onGroupClientsChanged?.invoke(clients)
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestConnectionInfo() {
        if (channel == null) {
            Log.w(TAG, "requestConnectionInfo: channel is null, ignoring")
            return
        }
        manager?.requestConnectionInfo(channel) { info ->
            // C1 / BUG (TEARDOWN CRASH) FIX: channel can go null between this
            // requestConnectionInfo call and its callback firing — teardown()
            // nulls it from an unrelated caller while this is in flight. See
            // guardedRemoveGroup's identical fix.
            if (channel == null) {
                Log.w(TAG, "requestConnectionInfo: channel went null before callback, ignoring")
                return@requestConnectionInfo
            }
            // OCP CONNECT REBUILD PART 4: THIS device's own state machine
            // moves to CONNECTED/TEARDOWN off the SAME authoritative signal
            // the Activity uses (groupFormed) — never off connect()'s
            // onSuccess, which only means the framework accepted the
            // request (see beginConnectSequence's ActionListener, which
            // deliberately does NOT transition state on success).
            if (info.groupFormed) {
                if (state == P2pState.CONNECTING) {
                    cancelConnectDeadline()
                    transition(P2pState.CONNECTED, "groupFormed")
                    val outcome = pendingConnectOutcome
                    pendingConnectOutcome = null
                    outcome?.invoke(true)
                }
            } else if (state == P2pState.CONNECTED) {
                transition(P2pState.TEARDOWN, "groupFormed=false")
                transition(P2pState.IDLE, "teardown-settled")
            }
            // PART 1 EXTRACTION: cached purely for localAddress() below —
            // additive field write only, nothing existing reads it.
            lastGroupOwnerAddress = if (info.groupFormed) info.groupOwnerAddress else null
            onConnectionChanged?.invoke(info)
        }
    }

    // ── OCP CONNECT REBUILD PART 2: quiesce-then-connect sequence ───────────

    /** Runs [action], waiting for its callback OR [QUIESCE_STEP_TIMEOUT_MS]
     *  (3s), whichever comes first — never both, and [then] fires exactly
     *  once either way. [ignoreFailure]=true (used for step (d),
     *  cancelConnect) means [action]'s own success/failure result doesn't
     *  matter, only that it completed or timed out. */
    private fun quiesceStep(stepName: String, action: (onDone: () -> Unit) -> Unit, then: () -> Unit) {
        var completed = false
        val timeoutRunnable = Runnable {
            if (completed) return@Runnable
            completed = true
            Log.w("OFFTRACE", "P2P: quiesce step=$stepName TIMED OUT after ${QUIESCE_STEP_TIMEOUT_MS}ms — proceeding anyway")
            then()
        }
        mainHandler.postDelayed(timeoutRunnable, QUIESCE_STEP_TIMEOUT_MS)
        action {
            if (completed) return@action
            completed = true
            mainHandler.removeCallbacks(timeoutRunnable)
            then()
        }
    }

    /** OCP CONNECT REBUILD PART 2: the strictly-ordered pre-connect
     *  sequence — (a) state->CONNECTING already done by the caller before
     *  this runs; (b) stopPeerDiscovery, UNLESS [skipStopDiscovery]; (c)
     *  removeGroup ONLY if a group is currently formed; (d) cancelConnect,
     *  failure ignored; (e) 500ms settle delay, UNLESS [skipStopDiscovery];
     *  then [onReady]. Never restarts discovery on failure inside this
     *  sequence — that is Part 4's job, in the caller.
     *
     *  BUG (QUIESCE IS DELETING THE PEER) FIX: [skipStopDiscovery] is true
     *  for [connect]'s negotiate/deviceAddress path ONLY — the established
     *  capture (stopPeerDiscovery at .728, wpa_supplicant's own
     *  P2P-DEVICE-LOST 5ms later, connect() against that now-evicted
     *  deviceAddress 548ms after THAT returning reason=0) is our own
     *  stopPeerDiscovery call deleting the very peer connect() is about to
     *  target. Android's connect() already stops discovery itself during
     *  negotiation (the framework's job, not this class's), and the peer
     *  entry has to survive until the call actually happens. [host]'s
     *  explicit/createGroup paths always pass false — neither targets a
     *  specific peer address, so there is nothing stopPeerDiscovery could
     *  delete out from under them; they keep the full sequence. NOTE: this
     *  is NOT the same thing as [connectPath] == "negotiate" as a string —
     *  [host]'s own bare-createGroup fallback (MeshElection's re-formation)
     *  ALSO computes connectPath="negotiate" despite having no peer address
     *  at all, which is exactly why this is a separate, explicit boolean
     *  rather than a string comparison inside this function. */
    private fun quiesceBeforeConnect(skipStopDiscovery: Boolean, onReady: () -> Unit) {
        val afterStopDiscovery: () -> Unit = {
            quiesceStep("removeGroupIfFormed", { done ->
                val ch = channel
                if (ch == null) { done(); return@quiesceStep }
                manager?.requestGroupInfo(ch) { group ->
                    // C1 / BUG (TEARDOWN CRASH) FIX: channel can go null
                    // between this requestGroupInfo call and its callback
                    // firing — teardown() nulls it from an unrelated caller
                    // while this is in flight. guardedRemoveGroup already
                    // re-checks channel itself before ever touching it, but
                    // this bails before even attempting the call, matching
                    // every other requestXxx callback in this class.
                    if (channel == null) {
                        Log.w(TAG, "quiesceBeforeConnect: channel went null before requestGroupInfo callback, ignoring")
                        done()
                        return@requestGroupInfo
                    }
                    if (group != null) {
                        guardedRemoveGroup(reason = "pre-connect-quiesce", tag = "quiesceBeforeConnect", allowWhileLive = true) { done() }
                    } else {
                        done()
                    }
                } ?: done()
            }) {
                quiesceStep("cancelConnect", { done ->
                    val ch = channel
                    if (ch == null) { done(); return@quiesceStep }
                    logCall("cancelConnect", "quiesceBeforeConnect")
                    manager?.cancelConnect(ch, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() = done()
                        override fun onFailure(reason: Int) = done() // ignored per spec
                    }) ?: done()
                }) {
                    // BUG (QUIESCE IS DELETING THE PEER) FIX PART 2: this
                    // 500ms static settle is exactly what turned a tap into
                    // a 548ms-later connect() in the established capture —
                    // cancelConnect's own callback (immediately above) is
                    // sufficient on the negotiate path; only skipped there,
                    // never for host()'s paths.
                    if (skipStopDiscovery) onReady() else mainHandler.postDelayed(onReady, QUIESCE_SETTLE_DELAY_MS)
                }
            }
        }
        if (skipStopDiscovery) {
            afterStopDiscovery()
        } else {
            quiesceStep("stopPeerDiscovery", { done -> stopPeerDiscovery { done() } }, afterStopDiscovery)
        }
    }

    // ── OCP CONNECT REBUILD PART 4: connect deadline + BUSY retry state ─────
    private var connectDeadlineRunnable: Runnable? = null
    private var connectAttemptCount = 0
    private var connectStartedAtMs = 0L
    private var connectPath = "unknown"
    private var connectPeerTag = ""
    private var pendingConnectOutcome: ((Boolean) -> Unit)? = null
    // BUG (QUIESCE IS DELETING THE PEER) FIX PART 2: stamped the instant
    // connect() is called (before quiesce/presence-check run at all) — the
    // tapToCallMs log measures from here to the moment issueConnect actually
    // fires, i.e. exactly the quiesce-sequence overhead this fix shrinks.
    private var connectRequestedAtMs = 0L
    // BUG (QUIESCE IS DELETING THE PEER) FIX PART 4: at most ONE
    // re-discover-and-retry per connect() attempt on a reason=0 failure —
    // reset in connect() itself, consumed the first time issueConnect's
    // onFailure sees reason=0, never retried a second time for the same attempt.
    private var recoveryAttempted = false

    private fun armConnectDeadline(peerTag: String) {
        cancelConnectDeadline()
        val runnable = Runnable {
            connectDeadlineRunnable = null
            val elapsed = SystemClock.elapsedRealtime() - connectStartedAtMs
            Log.d("OFFTRACE", "P2P: connect timeout peer=$peerTag elapsedMs=$elapsed path=$connectPath")
            val ch = channel
            if (ch != null) {
                logCall("cancelConnect", "connectDeadline")
                manager?.cancelConnect(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {}
                    override fun onFailure(reason: Int) {}
                })
            }
            guardedRemoveGroup(reason = "connect-timeout", tag = "connectDeadline", allowWhileLive = true) {
                transition(P2pState.IDLE, "connect-timeout")
                val outcome = pendingConnectOutcome
                pendingConnectOutcome = null
                outcome?.invoke(false)
                // BUG (MAKE THE INVITE ACTUALLY TRANSMIT) FIX PART 1.4/3.1:
                // fired before startDiscovery() resumes ordinary scanning —
                // the caller's fallback (createGroup) needs CONNECTING just
                // released (already true here) but gets first say before
                // discovery restarts underneath it.
                onConnectDeadlineFired?.invoke(peerTag)
                startDiscovery()
            }
        }
        connectDeadlineRunnable = runnable
        connectStartedAtMs = SystemClock.elapsedRealtime()
        mainHandler.postDelayed(runnable, CONNECT_DEADLINE_MS)
    }

    private fun cancelConnectDeadline() {
        connectDeadlineRunnable?.let { mainHandler.removeCallbacks(it) }
        connectDeadlineRunnable = null
    }

    /** Shared terminal path for a connect attempt that will never succeed —
     *  BUSY exhausted, or an immediately-terminal reason (0/1). Restores
     *  IDLE, restarts discovery, and reports failure exactly once. */
    private fun failConnectAttempt(reason: String) {
        cancelConnectDeadline()
        transition(P2pState.IDLE, reason)
        val outcome = pendingConnectOutcome
        pendingConnectOutcome = null
        outcome?.invoke(false)
        startDiscovery()
    }

    private fun issueConnect(config: WifiP2pConfig, path: String, peerTag: String) {
        val ch = channel ?: run {
            Log.w(TAG, "issueConnect: channel is null, ignoring")
            failConnectAttempt("channel-null")
            return
        }
        connectAttemptCount++
        logCall("connect", "issueConnect")
        // BUG (MAKE THE INVITE ACTUALLY TRANSMIT) FIX PART 1.3: the closest
        // available signal that a frame actually left the radio. Android's
        // WifiP2pManager exposes no broadcast or listener for the
        // supplicant-level PROV-DISC-PBC-REQ/RESP or GO-NEG-REQUEST/SUCCESS
        // events themselves — those are visible only in the system's own
        // logcat (from wpa_supplicant/the framework's internal service),
        // never delivered to an app. connect() accepting the request (this
        // line, for the negotiate/PBC path specifically — config.deviceAddress
        // non-null) is the nearest thing this app can observe to "provision
        // discovery was sent"; the response side is approximated separately
        // by watching WifiP2pDevice.status transitions on
        // WIFI_P2P_PEERS_CHANGED_ACTION (see OfflineCallActivity.onPeersChanged),
        // since that status field (AVAILABLE -> INVITED -> CONNECTED) is the
        // one place the framework surfaces negotiation progress to apps at all.
        if (config.deviceAddress != null) {
            Log.d("OFFTRACE", "P2P: provdisc sent peer=${config.deviceAddress} intent=${config.groupOwnerIntent}")
        }
        manager?.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                // OCP CONNECT REBUILD PART 4: onSuccess means ONLY that the
                // framework accepted the request — state stays CONNECTING.
                // requestConnectionInfo's groupFormed observation (above) is
                // the one and only path to CONNECTED.
                Log.d("OFFTRACE", "connect onSuccess attempt=$connectAttemptCount path=$path")
            }
            override fun onFailure(reason: Int) {
                Log.e("OFFTRACE", "connect onFailure reason=$reason attempt=$connectAttemptCount path=$path")
                if (!isTerminalConnectFailure(reason)) {
                    // reason == BUSY, retryable.
                    if (shouldRetryConnectAfterBusy(connectAttemptCount)) {
                        Log.w("OFFTRACE", "P2P: connect BUSY, retry ${connectAttemptCount + 1}/$CONNECT_BUSY_MAX_ATTEMPTS in ${CONNECT_BUSY_RETRY_DELAY_MS}ms")
                        mainHandler.postDelayed({
                            if (state != P2pState.CONNECTING) return@postDelayed
                            issueConnect(config, path, peerTag)
                        }, CONNECT_BUSY_RETRY_DELAY_MS)
                        return
                    }
                    Log.e("OFFTRACE", "P2P: connect gave up after $connectAttemptCount BUSY attempts")
                    failConnectAttempt("busy-exhausted")
                    return
                }
                // BUG (QUIESCE IS DELETING THE PEER) FIX PART 4: reason=0
                // (ERROR) is still terminal — never retried a SECOND time —
                // but recoverable exactly ONCE: the established capture's
                // "connect TERMINAL reason=0" was this exact failure mode
                // (peer evicted from the framework's list by our own
                // stopPeerDiscovery, connect() rejecting a now-unknown
                // deviceAddress). One re-discover-and-retry cycle costs
                // nothing when the peer genuinely is still there and just
                // needs re-finding; [recoveryAttempted] guarantees this
                // never loops — a second reason=0 for the SAME attempt
                // falls straight through to the ordinary terminal path
                // below. reason=1 (P2P_UNSUPPORTED) and anything else
                // unrecognized skip recovery entirely — a HAL-level
                // rejection unrelated to peer-list staleness.
                if (shouldRecoverFromTerminalError(reason, config.deviceAddress != null, recoveryAttempted)) {
                    recoveryAttempted = true
                    Log.w("OFFTRACE", "P2P: connect reason=0 — one re-discover-and-retry for peer=${config.deviceAddress}")
                    recoverVanishedPeerThenConnect(config.deviceAddress!!, peerTag, PEER_RECOVERY_MAX_ATTEMPTS)
                    return
                }
                // reason == ERROR(0, recovery already spent) or
                // P2P_UNSUPPORTED(1) or anything else unrecognized —
                // terminal, report immediately, never retry.
                Log.e("OFFTRACE", "P2P: connect TERMINAL reason=$reason — not retrying")
                failConnectAttempt("terminal-reason-$reason")
            }
        })
    }

    private fun issueCreateGroup(config: WifiP2pConfig?, path: String, peerTag: String) {
        val ch = channel ?: run {
            Log.w(TAG, "issueCreateGroup: channel is null, ignoring")
            failConnectAttempt("channel-null")
            return
        }
        connectAttemptCount++
        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d("OFFTRACE", "ELECT: createGroup onSuccess attempt=$connectAttemptCount path=$path")
            }
            override fun onFailure(reason: Int) {
                Log.e("OFFTRACE", "ELECT: createGroup onFailure reason=$reason attempt=$connectAttemptCount path=$path")
                if (!isTerminalConnectFailure(reason) && shouldRetryConnectAfterBusy(connectAttemptCount)) {
                    mainHandler.postDelayed({
                        if (state != P2pState.CONNECTING) return@postDelayed
                        issueCreateGroup(config, path, peerTag)
                    }, CONNECT_BUSY_RETRY_DELAY_MS)
                    return
                }
                failConnectAttempt("createGroup-failed-$reason")
            }
        }
        if (config != null) {
            logCall("createGroup(config)", "issueCreateGroup")
            manager?.createGroup(ch, config, listener)
        } else {
            logCall("createGroup", "issueCreateGroup")
            manager?.createGroup(ch, listener)
        }
    }

    /** OCP CONNECT REBUILD PART 1/2/4: the single entry point for JOINING a
     *  peer — replaces the old bare [connect]. Gated on [canBeginConnect],
     *  transitions to CONNECTING BEFORE any framework call (Part 1),
     *  quiesces (Part 2), then issues either the explicit-group config
     *  (Part 3, API 29+) or the legacy negotiation config as a fallback —
     *  either way through [issueConnect], which owns the 25s deadline and
     *  BUSY-retry cap (Part 4). [onOutcome] fires EXACTLY once: true only
     *  on a real groupFormed observation, false on timeout/terminal
     *  failure/BUSY-exhaustion. */
    @SuppressLint("MissingPermission")
    fun connect(device: WifiP2pDevice, peerTag: String, networkName: String? = null, passphrase: String? = null, onOutcome: ((Boolean) -> Unit)? = null) {
        if (!canBeginConnect(state)) {
            Log.d("OFFTRACE", "P2P: connect REJECTED peer=$peerTag state=$state (legal only from IDLE/DISCOVERING)")
            onOutcome?.invoke(false)
            return
        }
        transition(P2pState.CONNECTING, "connect:$peerTag")
        connectAttemptCount = 0
        pendingConnectOutcome = onOutcome
        recoveryAttempted = false
        connectRequestedAtMs = SystemClock.elapsedRealtime()
        val explicit = explicitGroupsSupported(Build.VERSION.SDK_INT) && networkName != null && passphrase != null
        connectPath = if (explicit) "explicit" else "negotiate"
        connectPeerTag = peerTag
        Log.d("OFFTRACE", "P2P: join path=$connectPath api=${Build.VERSION.SDK_INT}")
        // BUG (QUIESCE IS DELETING THE PEER) FIX PART 1: skipStopDiscovery
        // is true ONLY for the negotiate/deviceAddress path — see
        // quiesceBeforeConnect's own doc for why this can't just be
        // `connectPath == "negotiate"` (host()'s bare-createGroup fallback
        // shares that same string with no peer address involved at all).
        quiesceBeforeConnect(skipStopDiscovery = shouldSkipStopDiscoveryForQuiesce(explicit)) {
            if (explicit) {
                val config = WifiP2pConfig.Builder()
                    .setNetworkName(networkName!!)
                    .setPassphrase(passphrase!!)
                    .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
                    .build()
                armConnectDeadline(peerTag)
                issueConnect(config, connectPath, peerTag)
            } else {
                // BUG (MAKE THE INVITE ACTUALLY TRANSMIT) FIX PART 1.1: THE
                // primary invite path — deviceAddress is what makes this
                // connect() call actually address and transmit toward a
                // specific peer (provision discovery, then GO negotiation),
                // unlike the explicit Builder config above, which never sets
                // deviceAddress at all. wps.setup=PBC is the no-PIN/
                // no-passphrase-exchange handshake — the framework
                // negotiates real WPA2 credentials over the air during GO
                // negotiation, so nothing needs to be pre-shared here.
                //
                // BUG (QUIESCE IS DELETING THE PEER) FIX PART 2/3: no longer
                // built and issued right here — verifyPeerThenConnect does
                // the presence check (and the tapToCallMs log) first, THEN
                // builds this exact config once the peer is confirmed
                // present (or recovered).
                verifyPeerThenConnect(device.deviceAddress, peerTag)
            }
        }
    }

    /** BUG (QUIESCE IS DELETING THE PEER) FIX PART 3: [negotiateConfigFor]
     *  duplicated inline three times before this — the exact config
     *  [connect]'s negotiate path, [verifyPeerThenConnect], and
     *  [recoverVanishedPeerThenConnect] all need. */
    private fun negotiateConfigFor(deviceAddress: String): WifiP2pConfig = WifiP2pConfig().apply {
        this.deviceAddress = deviceAddress
        groupOwnerIntent = 15
        wps.setup = WpsInfo.PBC
    }

    /** BUG (QUIESCE IS DELETING THE PEER) FIX PART 2/3: runs immediately
     *  after quiesce, right before the connect() call itself — the exact
     *  point the established capture shows the peer already gone
     *  (stopPeerDiscovery evicted it from the framework's list before this
     *  moment ever arrived). Logs the tap-to-call gap and whether the
     *  target is still present in a FRESH requestPeers() result; only calls
     *  connect() when it is. Absent -> [recoverVanishedPeerThenConnect]
     *  instead of ever calling connect() against a peer already known to be
     *  gone. */
    @SuppressLint("MissingPermission")
    private fun verifyPeerThenConnect(deviceAddress: String, peerTag: String) {
        val ch = channel ?: run {
            Log.w(TAG, "verifyPeerThenConnect: channel is null, ignoring")
            failConnectAttempt("channel-null")
            return
        }
        manager?.requestPeers(ch) { peers ->
            // C1 / BUG (TEARDOWN CRASH) FIX: channel can go null between this
            // requestPeers call and its callback firing — teardown() nulls it
            // from an unrelated caller while this is in flight. issueConnect/
            // recoverVanishedPeerThenConnect already re-check channel
            // themselves before touching it, but this bails first, matching
            // every other requestXxx callback in this class.
            if (channel == null) {
                Log.w(TAG, "verifyPeerThenConnect: channel went null before callback, ignoring")
                failConnectAttempt("channel-null")
                return@requestPeers
            }
            trackPeerListTransitions(peers, "pre-connect-check")
            val peerKnown = isPeerPresent(peers.deviceList.map { it.deviceAddress }, deviceAddress)
            val elapsed = SystemClock.elapsedRealtime() - connectRequestedAtMs
            Log.d("OFFTRACE", "P2P: connect issued tapToCallMs=$elapsed peer=$deviceAddress peerKnown=$peerKnown")
            if (!peerKnown) {
                Log.w("OFFTRACE", "P2P: peer vanished before connect mac=$deviceAddress — re-discovering")
                recoverVanishedPeerThenConnect(deviceAddress, peerTag, PEER_RECOVERY_MAX_ATTEMPTS)
                return@requestPeers
            }
            armConnectDeadline(peerTag)
            issueConnect(negotiateConfigFor(deviceAddress), connectPath, peerTag)
        } ?: failConnectAttempt("channel-null")
    }

    /** BUG (QUIESCE IS DELETING THE PEER) FIX PART 3/4: shared by
     *  [verifyPeerThenConnect] (peer already gone before the first attempt)
     *  and [issueConnect]'s reason=0 handling (peer gone by the time the
     *  framework actually rejected the request) — exactly ONE
     *  discoverPeers() pass, fired only on the first call
     *  ([attemptsLeft] == [PEER_RECOVERY_MAX_ATTEMPTS]), then polls
     *  requestPeers() every [PEER_RECOVERY_POLL_INTERVAL_MS] until the
     *  peer reappears or [attemptsLeft] is exhausted (10 * 1s = 10s).
     *  Deliberately calls WifiP2pManager.discoverPeers directly rather than
     *  going through [runDiscoveryPass]/[startDiscovery] — both are gated
     *  to DISCOVERING only ([canDiscover]), and state stays CONNECTING for
     *  this entire recovery; this is a narrow, one-shot exception to that
     *  gate, not a reopening of it. */
    @SuppressLint("MissingPermission")
    private fun recoverVanishedPeerThenConnect(deviceAddress: String, peerTag: String, attemptsLeft: Int) {
        if (state != P2pState.CONNECTING) return // attempt abandoned elsewhere (disconnect/teardown) — do not resurrect it
        val ch = channel ?: run {
            Log.w(TAG, "recoverVanishedPeerThenConnect: channel is null, ignoring")
            failConnectAttempt("channel-null")
            return
        }
        if (attemptsLeft == PEER_RECOVERY_MAX_ATTEMPTS) {
            logCall("discoverPeers", "recoverVanishedPeerThenConnect")
            manager?.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(reason: Int) { Log.w("OFFTRACE", "P2P: recovery discoverPeers failed reason=$reason") }
            })
        }
        if (attemptsLeft <= 0) {
            Log.e("OFFTRACE", "P2P: peer never reappeared mac=$deviceAddress — giving up")
            failConnectAttempt("peer-vanished")
            return
        }
        mainHandler.postDelayed({
            if (state != P2pState.CONNECTING) return@postDelayed
            val c = channel ?: run { failConnectAttempt("channel-null"); return@postDelayed }
            manager?.requestPeers(c) { peers ->
                // C1 / BUG (TEARDOWN CRASH) FIX: channel can go null between
                // this requestPeers call and its callback firing — see
                // verifyPeerThenConnect's identical fix above.
                if (channel == null) {
                    Log.w(TAG, "recoverVanishedPeerThenConnect: channel went null before callback, ignoring")
                    failConnectAttempt("channel-null")
                    return@requestPeers
                }
                trackPeerListTransitions(peers, "recovery-poll")
                if (isPeerPresent(peers.deviceList.map { it.deviceAddress }, deviceAddress)) {
                    Log.d("OFFTRACE", "P2P: peer reappeared mac=$deviceAddress")
                    armConnectDeadline(peerTag)
                    issueConnect(negotiateConfigFor(deviceAddress), connectPath, peerTag)
                } else {
                    recoverVanishedPeerThenConnect(deviceAddress, peerTag, attemptsLeft - 1)
                }
            } ?: failConnectAttempt("channel-null")
        }, PEER_RECOVERY_POLL_INTERVAL_MS)
    }

    /** FIX 6a: GO-initiated invite — the GO calls this (not [connect]) to add a
     *  newcomer to a group that already exists. OCP CONNECT REBUILD PART 1:
     *  legal ONLY from CONNECTED (see [canInvite]'s doc for why this is
     *  deliberately NOT routed through the quiesce sequence). Under the hood
     *  it's the exact same WifiP2pManager.connect() call [connect] makes
     *  (same groupOwnerIntent=15 config) — the framework distinguishes the
     *  two cases itself: since a group is already formed here, it treats
     *  this connect() as an invitation for [device] to join it, rather than
     *  a fresh negotiation. Newcomers are ADDED BY the group this way; they
     *  never reliably self-join an already-formed group by calling
     *  [connect] on their own end (see FIX 6c in OfflineCallActivity). */
    @SuppressLint("MissingPermission")
    fun invitePeer(device: WifiP2pDevice, onResult: ((Boolean) -> Unit)? = null) {
        if (!canInvite(state)) {
            Log.d("OFFTRACE", "P2P: invitePeer REJECTED state=$state (legal only from CONNECTED)")
            onResult?.invoke(false)
            return
        }
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
        logCall("connect(invite)", "invitePeer")
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

    /** OCP CONNECT REBUILD PART 1/2/3/4: the single entry point for BECOMING
     *  GO — replaces the old bare [createGroup]. Same gate/quiesce/deadline
     *  shape as [connect]. [networkName]/[passphrase] non-null (API 29+)
     *  builds an EXPLICIT group (Part 3); null falls back to the legacy
     *  negotiated createGroup (used by MeshElection's re-formation path,
     *  which has no specific peer to derive a passphrase against — see
     *  OfflineCallActivity.becomeNewGoAfterElection). */
    @SuppressLint("MissingPermission")
    fun host(peerTag: String, networkName: String? = null, passphrase: String? = null, onOutcome: ((Boolean) -> Unit)? = null) {
        if (!canBeginConnect(state)) {
            Log.d("OFFTRACE", "P2P: host REJECTED peer=$peerTag state=$state (legal only from IDLE/DISCOVERING)")
            onOutcome?.invoke(false)
            return
        }
        transition(P2pState.CONNECTING, "host:$peerTag")
        connectAttemptCount = 0
        pendingConnectOutcome = onOutcome
        val explicit = explicitGroupsSupported(Build.VERSION.SDK_INT) && networkName != null && passphrase != null
        connectPath = if (explicit) "explicit" else "negotiate"
        connectPeerTag = peerTag
        Log.d("OFFTRACE", "P2P: join path=$connectPath api=${Build.VERSION.SDK_INT}")
        // BUG (QUIESCE IS DELETING THE PEER) FIX: always the full sequence
        // here — neither of host()'s two paths (explicit group, or the bare
        // createGroup fallback that ALSO happens to compute
        // connectPath="negotiate") targets a specific peer deviceAddress,
        // so there's no peer entry stopPeerDiscovery could delete out from
        // under either of them (see quiesceBeforeConnect's own doc for why
        // that string alone can't be trusted to make this distinction).
        quiesceBeforeConnect(skipStopDiscovery = false) {
            val config = if (explicit) {
                WifiP2pConfig.Builder()
                    .setNetworkName(networkName!!)
                    .setPassphrase(passphrase!!)
                    // BUG (GROUP FORMS, NOBODY JOINS) FIX: AUTO picked 5220MHz on
                    // this hardware — worse range for a mesh app and not
                    // universally supported by every joiner's radio. 2.4GHz is.
                    .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                    .build()
            } else null
            armConnectDeadline(peerTag)
            issueCreateGroup(config, connectPath, peerTag)
        }
    }

    /** PHASE 6 TRACK E: unilaterally becomes GO of a brand-new group,
     *  bypassing quiesce/deadline entirely — this is
     *  [MeshElection]'s winner re-forming the mesh after the old GO is
     *  lost, called from a context where state is already IDLE/TEARDOWN
     *  (the old group is confirmed gone) and speed matters more than the
     *  general-purpose ceremony [host] performs. Deliberately does NOT call
     *  removeGroup() first (unlike [host]/clearStaleGroupThenDiscover) — by
     *  the time this is called the old group is already gone (that's WHY an
     *  election ran), so there's nothing stale to clear, and an
     *  unconditional removeGroup() here could race a just-started
     *  createGroup() on a slower device. */
    @SuppressLint("MissingPermission")
    fun createGroupForElection(onResult: ((Boolean) -> Unit)? = null) {
        if (channel == null) {
            Log.w(TAG, "createGroupForElection: channel is null, ignoring")
            onResult?.invoke(false)
            return
        }
        transition(P2pState.CONNECTING, "createGroupForElection")
        // FIX 1b: defensive twin of guardedRemoveGroup's check — this never
        // called removeGroup itself (see this function's own doc,
        // unchanged above), but forming a SECOND group on top of a live one
        // would be exactly as destructive as removing it, so it gets the
        // same authoritative live-check before proceeding.
        manager?.requestGroupInfo(channel) { group ->
            // BUG (TEARDOWN CRASH) FIX: same re-read-before-use as
            // guardedRemoveGroup — channel can go null while this callback is
            // in flight (teardown() racing this election path).
            val ch = channel
            if (ch == null) {
                Log.w(TAG, "createGroupForElection: channel went null before requestGroupInfo callback, ignoring")
                transition(P2pState.IDLE, "createGroupForElection-channel-null")
                onResult?.invoke(false)
                return@requestGroupInfo
            }
            val formed = group != null
            val clients = group?.clientList?.size ?: 0
            if (shouldBlockGroupTeardown(formed, clients, allowWhileLive = false)) {
                Log.w(TAG, "P2P: createGroup BLOCKED — a group is already LIVE with $clients client(s)")
                Log.w("OFFTRACE", "P2P: createGroup BLOCKED formed=true clients=$clients")
                transition(P2pState.CONNECTED, "createGroupForElection-blocked")
                onResult?.invoke(false)
                return@requestGroupInfo
            }
            logCall("createGroup", "createGroupForElection")
            manager?.createGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d("OFFTRACE", "ELECT: createGroup onSuccess")
                    onResult?.invoke(true)
                }
                override fun onFailure(reason: Int) {
                    Log.e("OFFTRACE", "ELECT: createGroup onFailure reason=$reason")
                    transition(P2pState.IDLE, "createGroupForElection-failed")
                    onResult?.invoke(false)
                }
            })
        }
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
            // C1 / BUG (TEARDOWN CRASH) FIX: channel can go null between this
            // requestGroupInfo call and its callback firing — teardown()
            // nulls it from an unrelated caller while this is in flight.
            if (channel == null) {
                Log.w(TAG, "requestGroupInfo: channel went null before callback, ignoring")
                onResult?.invoke(null)
                return@requestGroupInfo
            }
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

    /** Current state, for callers that need to display or gate on it (e.g.
     *  Part 5's "reject the tap while resolving" doesn't need this, but a
     *  future caller might). PART 1 EXTRACTION: this is now the
     *  [MeshTransport] override — it returned the raw [P2pState] before this
     *  extraction, but had ZERO callers anywhere in the app at that point
     *  (confirmed by grep), so repointing its return type is a pure
     *  interface-conformance change, not a behavior change for anything
     *  that existed before this file. The raw [state] field itself, and
     *  every internal P2pState-based branch in this class, is completely
     *  untouched — see [mapP2pState] for the total mapping. */
    override fun currentState(): TransportState = mapP2pState(state)

    @SuppressLint("MissingPermission")
    fun disconnect() {
        cancelConnectDeadline()
        val outcome = pendingConnectOutcome
        pendingConnectOutcome = null
        outcome?.invoke(false)
        if (channel == null) {
            Log.w(TAG, "disconnect: channel is null, ignoring")
            transition(P2pState.IDLE, "disconnect-no-channel")
            return
        }
        logCall("cancelConnect", "disconnect")
        manager?.cancelConnect(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "cancelConnect: success") }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "cancelConnect failed: $reason")
                Log.w("OFFTRACE", "cancelConnect failed: $reason")
            }
        })
        transition(P2pState.TEARDOWN, "disconnect")
        // FIX 1b: routed through the same chokepoint as every other
        // removeGroup call — allowWhileLive=true here deliberately, since
        // disconnect() IS the intentional "leave this group" action
        // (called from OfflineCallActivity.leaveGroup()); removeGroup is
        // the only mechanism that actually leaves a group with peers still
        // in it, so this is the one caller the hard guard must not block.
        guardedRemoveGroup(reason = "disconnect", tag = "disconnect", allowWhileLive = true) {
            transition(P2pState.IDLE, "disconnect-done")
        }
    }

    fun teardown() {
        mainHandler.removeCallbacksAndMessages(null)
        cancelConnectDeadline()
        pendingConnectOutcome = null
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
        // discoveryCadenceRunnable's own postDelayed is already wiped by
        // mainHandler.removeCallbacksAndMessages(null) above — nulled here
        // too so a stale reference isn't left behind.
        discoveryCadenceRunnable = null
        discoveryPassCount = 0
        state = P2pState.IDLE
    }

    // ── PART 1 EXTRACTION: MeshTransport conformance ────────────────────────
    // Everything below is ADDITIVE — new members implementing the interface
    // by delegating to the existing, unchanged methods above. No existing
    // method's signature, body, or behavior was altered to make this fit
    // (the one exception, currentState()'s return type, is covered by its
    // own doc above: it had no callers to begin with).

    override val id: String get() = TRANSPORT_ID

    /** PART 1.2: canHost=true (this transport creates and can own a group),
     *  canRelay=true, supportsMedia=true (this is the app's original, only
     *  audio/video-capable transport), maxPeers=[TRANSPORT_MAX_PEERS] (see
     *  that constant's doc), requiresSharedNetwork=false (Wi-Fi Direct forms
     *  its own network on demand — see local-Wi-Fi's Part 2 opposite).
     *  Delegates to the companion's [WIFI_DIRECT_CAPS] — a plain val, not
     *  computed from any instance state — so PART 1.6's "TransportCaps gates
     *  are readable" test can read the real values without constructing a
     *  WifiDirectManager (this project has no Robolectric/mocking
     *  framework, so an instance — which touches a real Context in its own
     *  property initializers — can never exist in a unit test). */
    override val capabilities: TransportCaps get() = WIFI_DIRECT_CAPS

    /** PART 1.2: wires the four [MeshTransport.Callbacks] methods onto this
     *  class's existing [onPeersChanged]/[onConnectionChanged]/[onFatalError]
     *  vars (translating payload types at the boundary — [mapPeer]/
     *  [WifiP2pInfo]'s groupFormed/isGroupOwner/groupOwnerAddress), THEN
     *  calls the existing, unchanged [init]. NOT currently called by
     *  OfflineCallActivity — see PART 1 OUTPUT's leak list for why
     *  [onPeersChanged]/[onConnectionChanged]/[onP2pStateChanged] stay
     *  directly assigned by the Activity today (their payload types and,
     *  for onP2pStateChanged, the very concept, don't fit this interface).
     *  Assigning both this translation AND the Activity's own richer
     *  handlers to the same vars would silently let whichever assignment
     *  runs LAST win — this method exists to make the interface genuinely,
     *  correctly implementable (and unit-testable in principle), not to be
     *  double-wired alongside the Activity's direct assignments. */
    override fun start(callbacks: MeshTransport.Callbacks) {
        transportCallbacks = callbacks
        onPeersChanged = { list -> callbacks.onPeersChanged(mapPeers(list)) }
        onConnectionChanged = { info ->
            if (info.groupFormed) {
                callbacks.onNetworkReady(info.isGroupOwner, info.groupOwnerAddress)
            } else {
                callbacks.onNetworkLost("groupFormed=false")
            }
        }
        onFatalError = { msg -> callbacks.onTransportError(msg) }
        init()
    }

    override fun stop() {
        transportCallbacks = null
        teardown()
    }

    /** PART 1.2: the interface's zero-arg discovery start — delegates to the
     *  existing [startDiscovery] overload with no result callback. The
     *  richer overload (with an onResult callback) is UNCHANGED and stays
     *  the one several OfflineCallActivity call sites use directly — see
     *  PART 1 OUTPUT's leak list for why those aren't converted (losing the
     *  result callback would be an observable behavior change, forbidden by
     *  G3). */
    override fun startDiscovery() {
        startDiscovery(onResult = null)
    }

    override fun stopDiscovery() {
        stopPeerDiscovery(onResult = null)
    }

    /** PART 1.2/1.3: resolves [peer] back to a live WifiP2pDevice via
     *  [lastKnownPeers] (see that field's doc) and delegates to the
     *  existing [connect]. Deliberately simpler than OfflineCallActivity's
     *  own sendWifiInvite: it does not branch on isLocalGroupOwner
     *  (invitePeer vs connect) the way that Activity method does — that
     *  branch is Activity-level orchestration state this class has no
     *  reason to duplicate. This makes the interface genuinely correct and
     *  callable, but OfflineCallActivity's actual invite call sites stay on
     *  the concrete [connect]/[invitePeer] methods directly — see PART 1
     *  OUTPUT's leak list. */
    override fun invite(peer: DiscoveredPeer, onOutcome: (Boolean) -> Unit) {
        val device = lastKnownPeers?.deviceList?.firstOrNull { it.deviceAddress == peer.transportPeerId }
        if (device == null) {
            Log.w(TAG, "invite: no known WifiP2pDevice for transportPeerId=${peer.transportPeerId}")
            onOutcome(false)
            return
        }
        connect(device, peerTag = peer.transportPeerId, onOutcome = onOutcome)
    }

    /** PART 1.2: the group owner's address once a group has actually formed
     *  (see requestConnectionInfo's [lastGroupOwnerAddress] write) — null
     *  before then. Wi-Fi Direct has no OTHER stable notion of "this
     *  device's own address" (a plain client's own IP is DHCP-assigned by
     *  the GO and never surfaced by WifiP2pManager at all); this is the one
     *  address the socket layer above actually dials (see
     *  OfflineMediaTransport's connect-to-groupOwnerAddress path). */
    override fun localAddress(): InetAddress? = lastGroupOwnerAddress

    private fun mapPeers(list: WifiP2pDeviceList): List<DiscoveredPeer> =
        list.deviceList.map { mapPeer(it, id) }
}
