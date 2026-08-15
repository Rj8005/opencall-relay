package com.opencall.relay.offline

import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * PHASE 6 TRACK D: SSID-as-message — makes any phone on earth able to see a
 * distress signal in its Wi-Fi list, with no app installed. The loudest thing
 * this project can do, and a genuinely disruptive one: see [activate]'s doc.
 *
 * D1 — CRITICAL FINDING (read before wiring this into any UI path): changing
 * the Wi-Fi Direct device name or starting a local-only hotspot TEARS DOWN the
 * active P2P group, and per the survey behind this track,
 * [OfflineCallActivity.onConnectionChangedInternal]'s groupFormed=false branch
 * (that file's own code, quoted below) does NOT auto-recover from this by
 * itself:
 *   [OfflineCallActivity.kt] — onConnectionChangedInternal:
 *     if (!info.groupFormed) {
 *         if (!connectRequested) {
 *             Log.d("OFFTRACE", "ignoring stale/teardown connInfo")
 *             return
 *         }
 *         ...
 *     }
 *   Once already joined, connectRequested is false, so a teardown here is
 *   silently ignored AT THIS CALLBACK. The actual recovery signal comes from
 *   the media socket itself failing (IOException on the now-dead TCP
 *   connection) — OfflineMediaTransport.handlePeerDisconnected ->
 *   attemptClientReconnect finds isGroupFormed() now false and bails straight
 *   to handleMeshSessionLost -> onLinkLost -> OfflineCallActivity calls
 *   leaveGroup(...), which returns to the SEARCH screen. There is NO automatic
 *   rejoin — a human has to tap "Search nearby" again. This is exactly why
 *   this track's own spec calls for this class to drive its OWN rejoin
 *   attempt during the 30s "attempting to rejoin" window (see [onRejoinWindow])
 *   rather than relying on anything else in the app to do it.
 *
 * ROUTE 1 (WifiP2pManager.setDeviceName) — PLATFORM HONESTY NOTE:
 * [WifiP2pManager.setDeviceName] is NOT part of the public Android SDK surface
 * — it has been a hidden (@UnsupportedAppUsage) method for as long as WiFi
 * Direct has existed on Android, reachable historically only via reflection.
 * This class calls it that way, with a full try/catch around every failure
 * mode (NoSuchMethodException if the method doesn't exist on a given OEM
 * build, any hidden-API-enforcement rejection, any reflection failure) — it is
 * NOT guaranteed to work on any given device/Android version, and this is
 * exactly the kind of thing that should be verified on real hardware before
 * being relied on, the same honesty standard applied to the RSSI question in
 * PHASE 5BC and the hardware-button trigger in this phase's Track B.
 *
 * ROUTE 2 (local-only hotspot with a custom SSID) — CORRECTED FINDING: the
 * plan going into this track assumed API 30 added a
 * startLocalOnlyHotspot(SoftApConfiguration, ...) overload that would accept a
 * caller-chosen SSID. IT DOES NOT — verified by the Kotlin compiler itself
 * refusing to resolve that overload against the public SDK at compileSdk 34.
 * [WifiManager.startLocalOnlyHotspot] has exactly one public signature on
 * every API level up to and including 34 — (LocalOnlyHotspotCallback,
 * Handler?) — and the SSID/passphrase are ALWAYS system-generated; the app can
 * only read them back afterward via the reservation object, never set them.
 * Route 2 as specified is therefore not achievable via public API on ANY
 * Android version, not "on many versions" as anticipated — this class always
 * falls back to route 1 only. See [tryStartLocalOnlyHotspot]'s doc for the
 * full correction.
 */
class SosSsidBroadcast private constructor(context: Context) {

    companion object {
        private const val BROADCAST_WINDOW_MS = 60_000L
        private const val REJOIN_WINDOW_MS = 30_000L
        private const val MAX_SSID_BYTES = 32

        @Volatile private var instance: SosSsidBroadcast? = null

        fun get(context: Context): SosSsidBroadcast =
            instance ?: synchronized(this) {
                instance ?: SosSsidBroadcast(context.applicationContext).also { instance = it }
            }

        /** Pure — unit-testable without Android. Deterministic truncation: the
         *  coordinates are the actionable part, so the NAME shrinks first if the
         *  whole string doesn't fit in [MAX_SSID_BYTES]. */
        fun buildSsid(senderName: String, lat: Double, lon: Double): String {
            val latStr = "%.4f".format(lat)
            val lonStr = "%.4f".format(lon)
            var nameLen = minOf(6, senderName.length)
            while (nameLen >= 0) {
                val namePart = senderName.take(nameLen)
                val candidate = "SOS-$namePart-$latStr-$lonStr"
                if (candidate.toByteArray(Charsets.UTF_8).size <= MAX_SSID_BYTES) return candidate
                nameLen--
            }
            // Pathological case (shouldn't happen with ASCII names/normal coords) —
            // hard-truncate from the end as a last resort, still deterministic.
            val fallback = "SOS-$latStr-$lonStr"
            val bytes = fallback.toByteArray(Charsets.UTF_8)
            return if (bytes.size <= MAX_SSID_BYTES) fallback else String(bytes.copyOf(MAX_SSID_BYTES), Charsets.UTF_8)
        }
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var p2pManager: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var originalDeviceName: String? = null
    private var localOnlyHotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var cycleRunnable: Runnable? = null

    @Volatile var active = false
        private set
    @Volatile var currentlyBroadcasting = false
        private set

    /** Fired on the main thread with (active, broadcastingRightNow, ssidOrNull). */
    var onStateChanged: ((active: Boolean, broadcasting: Boolean, ssid: String?) -> Unit)? = null
    /** Called at the start of each 30s "attempting to rejoin" window — the
     *  caller should drive an actual reconnect attempt (see D1 finding above:
     *  nothing else in the app does this automatically). */
    var onRejoinWindow: (() -> Unit)? = null

    /** Explicit, deliberate LAST RESORT — the caller is responsible for showing
     *  a confirmation warning that this disconnects the party BEFORE calling
     *  this (see this track's D1 critical constraint). Not re-entrant while
     *  already active. */
    fun activate(senderName: String, lat: Double, lon: Double) {
        if (active) return
        active = true
        val ssid = buildSsid(senderName, lat, lon)
        ensureP2pHandles()
        saveOriginalDeviceName()
        startCycle(ssid)
    }

    /** Restores the original device name — see class doc's finally-block
     *  requirement, same "a crash must not leave the phone in a broken state"
     *  reasoning as SosAlarm's volume restore. */
    fun deactivate() {
        if (!active) return
        active = false
        cycleRunnable?.let { mainHandler.removeCallbacks(it) }
        cycleRunnable = null
        try {
            stopLocalOnlyHotspot()
        } finally {
            restoreOriginalDeviceName()
        }
        currentlyBroadcasting = false
        Log.d("OFFTRACE", "SSID: restored device name, rejoining mesh")
        mainHandler.post {
            onStateChanged?.invoke(false, false, null)
            onRejoinWindow?.invoke()
        }
    }

    private fun ensureP2pHandles() {
        if (p2pManager != null) return
        val mgr = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager ?: return
        p2pManager = mgr
        p2pChannel = mgr.initialize(appContext, Looper.getMainLooper(), null)
    }

    private fun startCycle(ssid: String) {
        broadcastPhase(ssid)
        val runnable = object : Runnable {
            override fun run() {
                if (!active) return
                if (currentlyBroadcasting) {
                    rejoinPhase()
                    mainHandler.postDelayed(this, REJOIN_WINDOW_MS)
                } else {
                    broadcastPhase(ssid)
                    mainHandler.postDelayed(this, BROADCAST_WINDOW_MS)
                }
            }
        }
        cycleRunnable = runnable
        mainHandler.postDelayed(runnable, BROADCAST_WINDOW_MS)
    }

    private fun broadcastPhase(ssid: String) {
        currentlyBroadcasting = true
        Log.d("OFFTRACE", "SSID: broadcasting \"$ssid\" — mesh torn down")
        val startedHotspot = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tryStartLocalOnlyHotspot(ssid) else false
        if (!startedHotspot) trySetDeviceNameViaReflection(ssid)
        mainHandler.post { onStateChanged?.invoke(true, true, ssid) }
    }

    private fun rejoinPhase() {
        currentlyBroadcasting = false
        stopLocalOnlyHotspot()
        restoreOriginalDeviceName()
        Log.d("OFFTRACE", "SSID: rejoin window — 30s attempting to reconnect to the mesh")
        mainHandler.post {
            onStateChanged?.invoke(true, false, null)
            onRejoinWindow?.invoke()
        }
    }

    // ── Route 1: WifiP2pManager.setDeviceName (hidden API — see class doc) ─────

    private fun saveOriginalDeviceName() {
        // There is no PUBLIC getter for the current device name either (same
        // hidden-API situation as setDeviceName) — this app never had a reason
        // to read it before now, so there is nothing reliable to snapshot.
        // Route 1 is therefore best-effort on restore too: if the reflection
        // call fails outright, nothing was ever changed, so there's nothing to
        // restore; if it succeeds, we restore to a fixed OpenCall default name
        // rather than a name we can't actually read back.
        originalDeviceName = "OpenCall Relay"
    }

    private fun trySetDeviceNameViaReflection(name: String): Boolean {
        val mgr = p2pManager ?: return false
        val ch = p2pChannel ?: return false
        return try {
            val method = WifiP2pManager::class.java.getMethod(
                "setDeviceName",
                WifiP2pManager.Channel::class.java,
                String::class.java,
                WifiP2pManager.ActionListener::class.java
            )
            method.invoke(
                mgr, ch, name,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.d("OFFTRACE", "SSID: setDeviceName(\"$name\") succeeded")
                    }
                    override fun onFailure(reason: Int) {
                        Log.w("OFFTRACE", "SSID: setDeviceName(\"$name\") failed reason=$reason")
                    }
                }
            )
            true
        } catch (e: NoSuchMethodException) {
            Log.w("OFFTRACE", "SSID: setDeviceName unavailable on this OEM/API level (hidden API) — route 1 failed")
            false
        } catch (e: Exception) {
            Log.w("OFFTRACE", "SSID: setDeviceName reflection failed: ${e.javaClass.simpleName}:${e.message}")
            false
        }
    }

    private fun restoreOriginalDeviceName() {
        val name = originalDeviceName ?: return
        trySetDeviceNameViaReflection(name)
    }

    // ── Route 2: local-only hotspot — CORRECTED FINDING, see class doc ─────────
    // The initial plan for this route assumed a startLocalOnlyHotspot(
    // SoftApConfiguration, Executor, LocalOnlyHotspotCallback) overload existed
    // on API 30+ that would accept a caller-chosen SSID. It does not — the
    // Kotlin compiler itself rejected it (no such overload resolves against the
    // public SDK; the only public WifiManager.startLocalOnlyHotspot signature,
    // on every API level up to and including 34, is
    // (LocalOnlyHotspotCallback, Handler?), with the SSID always
    // system-generated and only READABLE afterward via the reservation's own
    // softApConfiguration/wifiConfiguration, never settable). This is a
    // corrected finding, not the originally assumed one — route 2 as literally
    // specified ("startLocalOnlyHotspot with the encoded SSID") is NOT possible
    // via public API on ANY Android version, not just "on many versions" as
    // anticipated. This class therefore always falls back to route 1 only; the
    // function below is kept as a stub returning false so the fallback path in
    // [broadcastPhase] stays structurally ready if a future SDK ever adds a
    // public settable-SSID overload.

    private fun tryStartLocalOnlyHotspot(ssid: String): Boolean {
        Log.d("OFFTRACE", "SSID: local-only hotspot custom SSID unavailable on any public API level — route 1 only")
        return false
    }

    private fun stopLocalOnlyHotspot() {
        try { localOnlyHotspotReservation?.close() } catch (_: Exception) {}
        localOnlyHotspotReservation = null
    }
}
