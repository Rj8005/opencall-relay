package com.opencall.relay.offline

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * PHASE 6 TRACK E: self-healing GO re-election — highest risk track in this
 * phase, built last and deliberately narrow in blast radius: every method here
 * is new, additive code, wired only into NEW callback hooks
 * (onGoLost/onElectionResult/onSplitBrainDetected) that nothing in the
 * existing, working connect/call code paths ever calls during normal
 * operation. A full group VIDEO call regression test is still required after
 * this lands — this file being additive-only reduces risk, it doesn't replace
 * that test (which this environment has no device to actually run — see the
 * track's final report for exactly what WAS and wasn't verified).
 *
 * DETERMINISTIC ELECTION — every node reaches the SAME answer with no
 * negotiation round-trip, because every node already has what it needs:
 *   score = (batteryPercent / 10) * 1000 + visiblePeerCount * 10 [+ CHARGING_BONUS if charging]
 *   winner = highest score; ties broken by LOWEST nodeId
 * Every device broadcasts its own [batteryPercent, visiblePeerCount, charging]
 * every 10s via TYPE_ELECTION_STATUS (see [handleElectionStatus]) — cheap (3
 * bytes), continuous, so by the time a GO-lost event fires, every surviving
 * node already has a fresh-enough picture of every other node's inputs to
 * compute the identical winner independently.
 *
 * CHARGING BONUS: same +0.4-of-max-weight pattern OfflineMediaTransport's
 * relayDelayMs already uses for charging-aware carry-relay scheduling (see
 * that function's doc) — scaled onto this formula's 0..1000 battery range,
 * a charging device gets a flat [CHARGING_BONUS] added to its score. Wire
 * format is backward compatible: [handleElectionStatus] treats a 2-byte
 * legacy payload (no charging byte) as charging=false, and this device
 * still sends the full 3-byte payload regardless of what it receives.
 *
 * visiblePeerCount is (Wi-Fi Direct roster size - self) + (fresh BLE-only
 * presence sightings not already counted in the roster) — see
 * OfflineMediaTransport's wiring. This deliberately rewards a centrally
 * positioned device (one that can see more of the party, over EITHER radio)
 * with GO duties, since that's also the device best positioned to relay for
 * everyone once it takes over.
 *
 * GO HEARTBEAT: whichever device currently believes itself GO broadcasts
 * TYPE_GO_HEARTBEAT (no payload) every 10s. A client that hears nothing for 3
 * intervals (30s) calls [onGoLost]. If the device receiving a heartbeat
 * believes ITSELF to be GO too, that's a split-brain — see
 * [handleGoHeartbeat]'s split-brain branch and [onSplitBrainDetected].
 */
class MeshElection(
    private val localNodeId: Long,
    private val typeGoHeartbeat: Byte,
    private val typeElectionStatus: Byte,
    private val sendFrame: (dst: Long, type: Byte, payload: ByteArray) -> Unit,
    private val isCurrentlyGo: () -> Boolean,
    private val visiblePeerCount: () -> Int,
    private val batteryPercent: () -> Int,
    /** Reuses whatever source of truth the caller already has for charging
     *  state — e.g. OfflineMediaTransport.currentBatteryPercentAndCharging()'s
     *  second element — rather than this class reading BatteryManager itself. */
    private val isCharging: () -> Boolean,
    /** Fired on the main thread once, when a CLIENT hasn't heard a GO heartbeat
     *  in 3 intervals (30s). Never fires on the device that IS the GO. */
    private val onGoLost: () -> Unit,
    /** Fired on the main thread with the deterministic election result —
     *  isSelf tells the caller whether IT should become the new GO
     *  (createGroup) or connect to [winnerId] as a client. */
    private val onElectionResult: (winnerId: Long, isSelf: Boolean) -> Unit,
    /** Fired on the main thread if this device is GO and hears a heartbeat
     *  from another device that ALSO believes itself GO — [otherGoId] is the
     *  other claimant. Per the split-brain guard: the LOWER nodeId stands
     *  down, so this only actually asks the caller to act when
     *  localNodeId &gt; otherGoId. */
    private val onSplitBrainDetected: (otherGoId: Long) -> Unit
) {
    private data class ScoreInput(val batteryPercent: Int, val peerCount: Int, val charging: Boolean, val updatedAtMs: Long)

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 10_000L
        private const val STATUS_INTERVAL_MS = 10_000L
        private const val WATCHDOG_CHECK_INTERVAL_MS = 5_000L
        private const val MISSED_HEARTBEATS_BEFORE_LOST = 3
        private const val GO_LOST_THRESHOLD_MS = HEARTBEAT_INTERVAL_MS * MISSED_HEARTBEATS_BEFORE_LOST
        // 0.4 of the formula's max battery contribution (1000, at 100% battery) —
        // same proportional weight relayDelayMs gives charging on its own 0..1
        // scale. Enough to let a charging device outrank an uncharged one with
        // somewhat higher battery, not enough to outrank a large peer-count lead.
        private const val CHARGING_BONUS = 400

        /** Pure, unit-testable scoring — no Android dependency. Existing 2-arg
         *  callers/tests are unaffected; [charging] defaults false. */
        fun scoreFor(batteryPercent: Int, peerCount: Int, charging: Boolean = false): Int =
            (batteryPercent.coerceIn(0, 100) / 10) * 1000 + peerCount.coerceAtLeast(0) * 10 +
                (if (charging) CHARGING_BONUS else 0)

        /** Pure, unit-testable winner selection: highest score, ties broken by
         *  LOWEST nodeId. [candidates] is (nodeId, batteryPercent, peerCount). */
        fun pickWinner(candidates: List<Triple<Long, Int, Int>>): Long {
            require(candidates.isNotEmpty()) { "pickWinner needs at least one candidate" }
            return candidates
                .sortedWith(compareByDescending<Triple<Long, Int, Int>> { scoreFor(it.second, it.third) }.thenBy { it.first })
                .first()
                .first
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scores = ConcurrentHashMap<Long, ScoreInput>()

    @Volatile private var lastGoHeartbeatAtMs = System.currentTimeMillis()
    @Volatile private var goLostFired = false
    @Volatile private var lastElectionRanking: List<Long> = emptyList()

    private var heartbeatRunnable: Runnable? = null
    private var statusRunnable: Runnable? = null
    private var watchdogRunnable: Runnable? = null

    /** Session-scoped: call once when a group session becomes active. */
    fun start() {
        lastGoHeartbeatAtMs = System.currentTimeMillis()
        goLostFired = false
        startHeartbeatBroadcast()
        startStatusBroadcast()
        startWatchdog()
    }

    fun stop() {
        heartbeatRunnable?.let { mainHandler.removeCallbacks(it) }
        statusRunnable?.let { mainHandler.removeCallbacks(it) }
        watchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        heartbeatRunnable = null
        statusRunnable = null
        watchdogRunnable = null
        scores.clear()
    }

    /** Call once a new GO is confirmed reachable again (either this device just
     *  became GO, or a client just reconnected to the elected winner) — resets
     *  the watchdog so a fresh 30s window starts from now, not from whenever
     *  the old GO was last heard. */
    fun resetWatchdog() {
        lastGoHeartbeatAtMs = System.currentTimeMillis()
        goLostFired = false
    }

    private fun startHeartbeatBroadcast() {
        val runnable = object : Runnable {
            override fun run() {
                if (isCurrentlyGo()) {
                    sendFrame(MeshFrame.BROADCAST_ID, typeGoHeartbeat, ByteArray(0))
                }
                mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
            }
        }
        heartbeatRunnable = runnable
        mainHandler.postDelayed(runnable, HEARTBEAT_INTERVAL_MS)
    }

    private fun startStatusBroadcast() {
        val runnable = object : Runnable {
            override fun run() {
                val buf = ByteBuffer.allocate(3)
                buf.put(batteryPercent().coerceIn(0, 100).toByte())
                buf.put(visiblePeerCount().coerceIn(0, 255).toByte())
                buf.put(if (isCharging()) 1.toByte() else 0.toByte())
                sendFrame(MeshFrame.BROADCAST_ID, typeElectionStatus, buf.array())
                mainHandler.postDelayed(this, STATUS_INTERVAL_MS)
            }
        }
        statusRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun startWatchdog() {
        val runnable = object : Runnable {
            override fun run() {
                if (!isCurrentlyGo() && !goLostFired) {
                    val silentMs = System.currentTimeMillis() - lastGoHeartbeatAtMs
                    if (silentMs > GO_LOST_THRESHOLD_MS) {
                        goLostFired = true
                        Log.d("OFFTRACE", "ELECT: GO lost after $MISSED_HEARTBEATS_BEFORE_LOST missed heartbeats")
                        mainHandler.post { onGoLost() }
                    }
                }
                mainHandler.postDelayed(this, WATCHDOG_CHECK_INTERVAL_MS)
            }
        }
        watchdogRunnable = runnable
        mainHandler.postDelayed(runnable, WATCHDOG_CHECK_INTERVAL_MS)
    }

    fun handleGoHeartbeat(header: MeshFrame.Header) {
        if (isCurrentlyGo() && header.srcId != localNodeId) {
            // Split-brain: two devices both think they're GO. Only the higher
            // nodeId is asked to act (the lower one stands down on ITS side,
            // symmetrically, when it receives OUR heartbeat) — see class doc.
            if (localNodeId > header.srcId) {
                Log.w("OFFTRACE", "ELECT: split-brain detected, other GO=${MeshFrame.hex(header.srcId)} — standing down (higher nodeId)")
                mainHandler.post { onSplitBrainDetected(header.srcId) }
            }
            return
        }
        lastGoHeartbeatAtMs = System.currentTimeMillis()
        goLostFired = false
    }

    fun handleElectionStatus(header: MeshFrame.Header, payload: ByteArray) {
        if (payload.size < 2) return
        val battery = payload[0].toInt() and 0xFF
        val peerCount = payload[1].toInt() and 0xFF
        // Backward compatible: a legacy 2-byte payload (no charging byte) reads as false.
        val charging = payload.size >= 3 && payload[2].toInt() != 0
        scores[header.srcId] = ScoreInput(battery, peerCount, charging, System.currentTimeMillis())
    }

    private data class Candidate(val nodeId: Long, val batteryPercent: Int, val peerCount: Int, val charging: Boolean)

    /** Runs the deterministic election NOW, using every score this device has
     *  heard (plus its own current inputs) — called once, right after
     *  [onGoLost] fires. Also caches the full ranking so [rankOf] can answer
     *  the staggered-reconnect delay for the caller. */
    fun runElection() {
        val candidates = mutableListOf(Candidate(localNodeId, batteryPercent(), visiblePeerCount(), isCharging()))
        scores.forEach { (nodeId, s) -> candidates.add(Candidate(nodeId, s.batteryPercent, s.peerCount, s.charging)) }
        val ranked = candidates
            .sortedWith(compareByDescending<Candidate> { scoreFor(it.batteryPercent, it.peerCount, it.charging) }.thenBy { it.nodeId })
        lastElectionRanking = ranked.map { it.nodeId }
        val winner = ranked.first().nodeId
        val scoresStr = ranked.joinToString(", ") {
            "${MeshFrame.hex(it.nodeId)}=${scoreFor(it.batteryPercent, it.peerCount, it.charging)}${if (it.charging) "(chg)" else ""}"
        }
        Log.d("OFFTRACE", "ELECT: scores=[$scoresStr] winner=${MeshFrame.hex(winner)} self=${winner == localNodeId}")
        onElectionResult(winner, winner == localNodeId)
    }

    /** 0-based rank in the last election's ranking (lower = acts sooner) — the
     *  caller multiplies this by 2s for the staggered-reconnect delay. -1 if
     *  this node wasn't part of the last computed ranking (shouldn't happen —
     *  [runElection] always includes localNodeId). */
    fun myRank(): Int = lastElectionRanking.indexOf(localNodeId)
}
