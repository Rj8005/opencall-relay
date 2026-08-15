package com.opencall.relay.offline

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * PHASE 3D: GO-side speaker-HIGHLIGHT-only VAD ranking for group calls.
 *
 * This class used to also decode every active speaker's Opus, mix their PCM, and
 * re-distribute a personalized stream to every participant — that per-tick loop
 * (bounded by a 20ms budget, up to MAX_ACTIVE_SPEAKERS_DEFAULT concurrent
 * MediaCodec instances) was the actual cause of the GO's "overran 20ms budget"
 * degradation, which in turn starved both audio and video for every receiver.
 *
 * Audio is no longer mixed or re-encoded anywhere in this app: the GO now forwards
 * each participant's Opus stream exactly like video (dst=BROADCAST, pure byte
 * relay via [OfflineMediaTransport]'s existing forwardBroadcast — see that class's
 * updated doc), and every device — GO included — decodes whichever remote streams
 * are currently live into PCM and sums them locally before writing to its own
 * AudioTrack. See [OfflineMediaTransport.decodeGroupAudio]/[mixAndPlayGroupAudio].
 *
 * What's left here is purely cosmetic: ranking VAD-reported energy to decide whose
 * tile gets the "currently speaking" highlight border. No MediaCodec, no per-tick
 * loop, no dedicated thread — [updateVad] recomputes synchronously and cheaply on
 * whichever thread calls it (heartbeat-paced, a few times a second per
 * participant), which is more than sufficient for a UI highlight.
 */
class GroupCallMixer(
    private val localNodeId: Long,
    /** Called synchronously (on whichever thread called [updateVad]) whenever the
     *  top-N-by-energy currently-speaking set changes — ORDERED loudest-first
     *  (index 0, if present, is the single current leader) so the transport can use
     *  it directly as the raw, undebounced input to its own active-speaker-for-
     *  highlight decision. */
    private val onActiveSpeakersChanged: (List<Long>) -> Unit
) {
    companion object {
        private const val MAX_TRACKED_SPEAKERS = 3
        // A participant's last VAD update older than this is treated as "not
        // speaking" even if the last known state said otherwise — protects against
        // a stale "speaking=true" surviving a dropped TYPE_VAD heartbeat. 1500ms
        // vs. OfflineMediaTransport's 300ms heartbeat is a full 5x margin.
        private const val VAD_STALE_MS = 1500L
    }

    private class VadState { @Volatile var speaking = false; @Volatile var energy = 0; @Volatile var lastUpdateMs = 0L }

    private val vadStates = ConcurrentHashMap<Long, VadState>()
    @Volatile private var lastRanked: List<Long> = emptyList()

    fun start() {
        Log.d("OFFTRACE", "MIX: speaker-highlight tracker started")
    }

    /** Idempotent. */
    fun stop() {
        vadStates.clear()
        lastRanked = emptyList()
        Log.d("OFFTRACE", "MIX: speaker-highlight tracker stopped")
    }

    fun updateVad(nodeId: Long, speaking: Boolean, energy: Int) {
        val v = vadStates.getOrPut(nodeId) { VadState() }
        v.speaking = speaking
        v.energy = energy
        v.lastUpdateMs = System.currentTimeMillis()
        recompute()
    }

    fun removeParticipant(nodeId: Long) {
        vadStates.remove(nodeId)
        recompute()
    }

    /** Ranks currently-speaking (non-stale) participants by energy (loudest first),
     *  keeps only the top [MAX_TRACKED_SPEAKERS], and fires [onActiveSpeakersChanged]
     *  only when that ranked list actually changed — cheap enough to just recompute
     *  from scratch on every [updateVad] call rather than incrementally maintain. */
    private fun recompute() {
        val now = System.currentTimeMillis()
        val ranked = vadStates.entries
            .filter { (_, v) -> v.speaking && (now - v.lastUpdateMs) < VAD_STALE_MS }
            .sortedByDescending { it.value.energy }
            .take(MAX_TRACKED_SPEAKERS)
            .map { it.key }
        if (ranked != lastRanked) {
            lastRanked = ranked
            onActiveSpeakersChanged(ranked)
        }
    }
}
