package com.opencall.relay.offline

import android.content.Context
import android.util.Log
import java.net.InetAddress

/**
 * PART 2.5: [MeshTransport] implementation for the optical (camera-and-
 * screen QR) channel.
 *
 * HONEST ANSWER to 2.5's own question — does [MeshTransport] fit a one-shot,
 * unidirectional, human-aimed channel, or does it need a flag: IT NEEDS THE
 * FLAG. This interface was built for Wi-Fi Direct and local Wi-Fi — two
 * transports that both establish a persistent bidirectional socket, fire
 * [MeshTransport.Callbacks.onNetworkReady] once and [onNetworkLost] when
 * that same link eventually dies, and expose a stable [localAddress] for as
 * long as the link is up. Optical has NONE of that:
 *   - No persistent link — a SHOW/SCAN pairing is one exchange, not a
 *     session. There is nothing to tear down, so [onNetworkLost] would
 *     never have a real reason to fire.
 *   - No [InetAddress] — a scanned QR frame isn't delivered over IP at all;
 *     [localAddress] has nothing truthful to return but null.
 *   - No discovery in [MeshTransport]'s sense — [startDiscovery]/
 *     [stopDiscovery] presuppose the transport itself can find peers
 *     (mDNS, WFD peer-list callbacks); optical's only "discovery" is a
 *     person physically pointing a camera, which isn't something this
 *     class can start or stop.
 *   - No [invite] — there's no [DiscoveredPeer] handle to dial; the actual
 *     unit of work is "render N QR frames" (SHOW) or "decode a camera
 *     stream" (SCAN), neither of which is an outbound connection attempt.
 * Rather than fake any of the above (a rejected onOutcome(false) that
 * pretends to be a real connection attempt, a made-up loopback
 * [InetAddress], an [onNetworkReady] that fires for no real network) this
 * class implements every method as an HONEST, documented degenerate — see
 * each one below — and [MeshTransport.capabilities] sets
 * [TransportCaps.manualNonContinuous] = true so a caller can tell "a real,
 * if unusual, socket transport" from "not a socket, don't treat it like
 * one." The actual data-carrying work — building/parsing the signed
 * MeshFrame bytes optical carries (2.4) and the fountain-coded animated-QR
 * exchange (2.3) — lives in [OpticalFrame]/[OpticalFountain]/[OpticalFountainDecoder]
 * and the SHOW/SCAN screens that drive them directly; it does NOT run
 * through [invite]/[start] the way a WFD or local-Wi-Fi transfer would,
 * because there is no live link for those calls to act on.
 *
 * Registered into [MeshTransportRegistry] like any other transport (so
 * `all()`/`get("optical")` see it) — but, per [TransportCaps.canHost]=false,
 * [TransportCaps.maxPeers]=1, and the honest degenerates below, nothing in
 * [OfflineMediaTransport]'s live socket-routing engine is wired to actually
 * drive it; that wiring is the same "reported gap, not done in this pass"
 * shape as [LocalWifiTransport]'s own class doc already documents for its
 * Activity-level wiring.
 */
class OpticalTransport(private val context: Context) : MeshTransport {

    override val id: String = "optical"

    override val capabilities: TransportCaps = TransportCaps(
        canHost = false,
        canRelay = true,
        supportsMedia = false,
        maxPeers = 1,
        requiresSharedNetwork = false,
        manualNonContinuous = true
    )

    @Volatile private var callbacks: MeshTransport.Callbacks? = null

    /** No OS-level receiver/service to register — optical has nothing
     *  running in the background between a SHOW and a SCAN. Records
     *  [callbacks] only so a caller that DOES try to use this like a live
     *  transport gets a consistent (if inert) object, never a crash. */
    override fun start(callbacks: MeshTransport.Callbacks) {
        this.callbacks = callbacks
        Log.d("OFFTRACE", "OPTICAL: start() — no-op, manual/non-continuous transport, see class doc")
    }

    override fun stop() {
        callbacks = null
    }

    /** No-op, not an error — optical has no discovery phase of its own
     *  (see class doc); mirrors [MeshTransport.startDiscovery]'s documented
     *  contract for "a transport with no distinct discovery phase." */
    override fun startDiscovery() {}

    override fun stopDiscovery() {}

    /** There is no [DiscoveredPeer] handle to dial on this transport — the
     *  real unit of work is a QR SHOW/SCAN exchange, driven directly, not
     *  through this method. Reports failure immediately rather than
     *  hanging [onOutcome] forever or pretending to succeed. */
    override fun invite(peer: DiscoveredPeer, onOutcome: (Boolean) -> Unit) {
        Log.w("OFFTRACE", "OPTICAL: invite() has no meaning for a manual/non-continuous transport — see class doc")
        onOutcome(false)
    }

    /** Always IDLE — there is no connection lifecycle for a one-shot scan
     *  to be IN, so DISCOVERING/CONNECTING/CONNECTED/TEARDOWN would all be
     *  fiction. */
    override fun currentState(): TransportState = TransportState.IDLE

    /** Always null — a scanned QR frame is never delivered over IP; see
     *  class doc. */
    override fun localAddress(): InetAddress? = null
}
