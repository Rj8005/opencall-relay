package com.opencall.relay.offline

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * PHASE 5A/5BC: SOS distress beacon, "find this peer" request/response, the
 * low-rate ambient TYPE_POSITION broadcast that feeds [MeshLedger], and
 * store-and-forward SOS carry — all layered on top of the existing mesh
 * envelope (see OfflineMediaTransport's class doc for the frame types
 * themselves) using [MeshLocation] as the shared wire payload.
 *
 * SECURITY NOTE — READ BEFORE RELYING ON THIS: SOS/FIND/POSITION frames are
 * completely UNAUTHENTICATED, the same trust model as every other frame type in
 * this protocol. [OfflineIdentity]'s private key is generated and immediately
 * discarded, so signing these frames is out of scope for this phase; this class
 * deliberately does not attempt any crypto.
 *
 * PHASE 5BC additions over PHASE 5A:
 *   - TYPE_POSITION: a 30s ambient broadcast, independent of any SOS, that feeds
 *     [ledger] on every receiver — this is the data the party's "where is
 *     everyone" screen and the lost-contact marker are built from.
 *   - TYPE_SOS_ACK: every device that hears an active SOS acks it back to the
 *     sender, so the sender's screen can show "SEEN BY k/N".
 *   - STORE-AND-FORWARD CARRY: every device caches every active TYPE_SOS it has
 *     seen (raw payload, original srcId preserved) and replays it to any newly
 *     resolved peer — see [replayCachedSosTo] — so an SOS survives its sender
 *     going permanently out of range. A CLEAR removes the cache entry, so it
 *     naturally stops being replayed (propagates the same way).
 *   - Barometer recalibration: any fix (self or peer) with GPS accuracy better
 *     than 10m opportunistically resets [barometer]'s local sea-level reference.
 *
 * Owns no socket/thread of its own beyond Handler-scheduled repeats — sending
 * goes through the [sendFrame]/[sendRawFrame] callbacks (wired by
 * OfflineMediaTransport to its own writeFrame/raw-send), keeping this class
 * decoupled from transport/routing internals and unit-testable in isolation.
 */
class MeshSosManager(
    private val localNodeId: Long,
    private val typeSos: Byte,
    private val typeFindReq: Byte,
    private val typeFindResp: Byte,
    private val typePosition: Byte,
    private val typeSosAck: Byte,
    private val locationProvider: OfflineLocationProvider,
    private val barometer: MeshBarometer,
    private val ledger: MeshLedger,
    /** PHASE 6 TRACK A: SOS carry now rides the generalized store-and-forward
     *  queue instead of this class's own hardcoded cache/replay — see
     *  [cacheForCarry] and the removal of PHASE 5BC's sosCache/replayCachedSosTo. */
    private val carrier: MeshCarrier,
    /** Wired by the owner to its own writeFrame — dst may be MeshFrame.BROADCAST_ID
     *  (SOS/POSITION) or a specific nodeId (FIND_REQ/FIND_RESP/SOS_ACK). Always
     *  stamps srcId=localNodeId, same as every other frame this device sends. */
    private val sendFrame: (dst: Long, type: Byte, payload: ByteArray) -> Unit,
    /** Current OTHER-member count (roster size minus self) — read fresh at each
     *  ack, purely for the "seenBy=k/N" denominator. */
    private val otherMemberCount: () -> Int,
    /** Fired on the main thread whenever a TYPE_SOS is received (including a
     *  CLEAR — check [SosEntry.active]) from any sender, including repeats. */
    private val onSosEntry: (SosEntry) -> Unit,
    /** Fired on the main thread whenever a TYPE_FIND_RESP is received. */
    private val onFindResponse: (SosEntry) -> Unit,
    /** PHASE 5BC: fired on the main thread whenever this device's own
     *  currently-active SOS gets a new acker — (seenByCount, otherMemberCount). */
    private val onSosAckUpdate: (Int, Int) -> Unit = { _, _ -> },
    /** PHASE 5BC: fired on the main thread whenever a peer's ledger track gets a
     *  fresh TYPE_POSITION or TYPE_SOS entry — the party-status screen's cue to
     *  refresh. */
    private val onPositionUpdated: (Long) -> Unit = {}
) {
    /** Shared shape for both an incoming SOS beacon and an incoming FIND_RESP —
     *  both carry the same [MeshLocation] payload, just addressed differently. */
    data class SosEntry(
        val srcId: Long,
        val hasFix: Boolean,
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Int?,
        /** When the FIX was taken (unix seconds), not when the frame was sent. */
        val fixUnixSeconds: Long,
        val message: String,
        val receivedAtMs: Long,
        /** False once this srcId's SOS has been explicitly cleared (see
         *  [stopSos]'s CLEAR convention) — irrelevant for a FIND_RESP entry,
         *  always true there. */
        val active: Boolean,
        /** BUG 1 FIX 2/3: false means "known about, but must not sound the
         *  siren" — either because it's a store-and-forward REPLAY of an SOS
         *  already more than 30 minutes old (see [handleSosFrame]'s isLive
         *  param), or because SosAlarm's local 5-minute auto-stop already fired
         *  for this sender once (see [suppressAlarmFor]) and it hasn't been
         *  re-armed by a CLEAR since. Always true for a FIND_RESP entry. An
         *  entry can still be [active] while not [alarmable] — it is shown in
         *  the UI as historical/already-acknowledged, just silently. */
        val alarmable: Boolean = true
    )

    companion object {
        private const val SOS_REPEAT_INTERVAL_MS = 30_000L
        private const val DEFAULT_POSITION_BROADCAST_INTERVAL_MS = 30_000L
        private const val FIND_RESPONSE_RATE_LIMIT_MS = 10_000L
        private const val DEDUPE_CACHE_CAPACITY = 128
        private const val DEDUPE_ENTRY_TTL_MS = 5 * 60 * 1000L
        // Convention, not a new payload field: stopSos() sends hasFix=false plus
        // this exact message so receivers can tell "distress cleared" apart from
        // "still in distress, just no GPS fix" (which is also hasFix=false).
        private const val CLEAR_MESSAGE = "CLEAR"
        // BUG 1 FIX 1: an SOS carry entry used to be queued with expiryMins=0
        // (never expire) — a device that never received CLEAR (e.g. its sender
        // went permanently out of range, or an earlier test session was simply
        // never cleared) stayed in the carry queue and got replayed to every
        // future newly-paired peer FOREVER, sounding their siren for an event
        // that was long over. 6 hours generously outlives any plausible active
        // rescue window on this app's own timescales (SOS_REPEAT_INTERVAL_MS is
        // 30s; MeshLedger's own ring buffer is 30 minutes) while guaranteeing an
        // abandoned/forgotten SOS eventually ages out.
        private const val SOS_CARRY_EXPIRY_MINS = 6 * 60
        // BUG 1 FIX 3: a store-and-forward REPLAY (isLive=false) of an SOS whose
        // ORIGINAL fix timestamp is older than this is historical — shown, but
        // must never sound the siren on the newly-joining peer that receives it.
        private const val HISTORICAL_REPLAY_AGE_SEC = 30 * 60L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val nextMsgSeq = AtomicLong(1)
    private val nextPositionSeqSinceBoot = AtomicLong(0)

    @Volatile private var sosActive = false
    /** PHASE 6 TRACK C: read-only view for MeshBleBeacon — true while THIS
     *  device's own SOS beacon is active. */
    val isOwnSosActive: Boolean get() = sosActive
    @Volatile private var sosMessage: String = ""
    private var sosRunnable: Runnable? = null
    private var positionRunnable: Runnable? = null
    @Volatile private var positionBroadcastIntervalMs: Long = DEFAULT_POSITION_BROADCAST_INTERVAL_MS

    private val lastFindResponseAtMs = ConcurrentHashMap<Long, Long>()
    val sosEntries = ConcurrentHashMap<Long, SosEntry>()
    val findResponses = ConcurrentHashMap<Long, SosEntry>()

    // PHASE 5BC: who has ack'd THIS device's own currently-active SOS.
    private val ackedByForMySos = ConcurrentHashMap.newKeySet<Long>()

    // PHASE 6 TRACK A: store-and-forward now rides MeshCarrier — this class only
    // tracks WHICH carrier msgId corresponds to which sender's current SOS
    // episode, so a repeated 30s beacon (or an incoming rebroadcast) upserts the
    // SAME queue entry instead of accumulating a new one every time. A CLEAR
    // removes the mapping AND the underlying carrier entry, which is how CLEAR
    // itself propagates via offer (same behaviour as PHASE 5BC's cache, see
    // class doc).
    private var ourSosMsgId: String? = null
    private val sosMsgIdBySender = ConcurrentHashMap<Long, String>()

    // ── Dedupe: (srcId, type, msgSeq) LRU, capacity + TTL bounded ───────────────
    private data class DedupeKey(val srcId: Long, val type: Byte, val msgSeq: Long)
    private val dedupeLock = Any()
    private val dedupeSeenAtMs = object : LinkedHashMap<DedupeKey, Long>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<DedupeKey, Long>?): Boolean =
            size > DEDUPE_CACHE_CAPACITY
    }

    /** True iff [type] is one of the types this class owns that participate in
     *  dedupe — SOS, FIND_REQ, FIND_RESP, and (PHASE 5BC) POSITION. Deliberately
     *  does NOT include SOS_ACK: an ack is idempotent by construction (a
     *  Set<Long> of ackers absorbs a duplicate delivery for free), so it doesn't
     *  need — and per this phase's scope constraint, must not consume — a slot
     *  in the shared dedupe cache. The transport uses this (or an equivalent
     *  inline check) to scope the dedupe hook so it can never fire for anything
     *  outside this exact set. */
    fun isSosFindType(type: Byte): Boolean =
        type == typeSos || type == typeFindReq || type == typeFindResp || type == typePosition

    /** Check-and-record in one call: returns true (a duplicate — caller should
     *  drop the frame, both from local handling AND forwarding) if this exact
     *  (srcId, type, msgSeq) was already seen within the last 5 minutes; records
     *  it as seen either way — malformed payloads (no decodable msgSeq) are never
     *  treated as duplicates — they fall through to the normal handler, which
     *  will independently fail to decode and drop them. */
    fun checkAndRecordDuplicate(srcId: Long, type: Byte, payload: ByteArray): Boolean {
        if (!isSosFindType(type)) return false
        val seq = MeshLocation.decode(payload)?.msgSeq ?: return false
        val key = DedupeKey(srcId, type, seq)
        val now = System.currentTimeMillis()
        synchronized(dedupeLock) {
            pruneExpiredLocked(now)
            if (dedupeSeenAtMs.containsKey(key)) return true
            dedupeSeenAtMs[key] = now
            return false
        }
    }

    private fun pruneExpiredLocked(now: Long) {
        val it = dedupeSeenAtMs.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value > DEDUPE_ENTRY_TTL_MS) it.remove()
        }
    }

    // ── SOS ──────────────────────────────────────────────────────────────────

    /** Sticky local state: broadcasts immediately, then every 30s until
     *  [stopSos] — the repeat is what reaches devices that join the group after
     *  the first broadcast already went out, AND (PHASE 5BC) is what makes an
     *  SOS "keep rebroadcasting at 30s indefinitely" when its sender is out of
     *  range and hears no ack — no separate mechanism needed for that. */
    fun startSos(message: String?) {
        sosActive = true
        sosMessage = message ?: ""
        ackedByForMySos.clear()
        // PHASE 6 TRACK A: one msgId per SOS EPISODE (not per 30s rebroadcast) —
        // every broadcastSos() call below upserts the SAME carrier queue entry.
        ourSosMsgId = MeshCarrier.newMsgId()
        sosRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                if (!sosActive) return
                broadcastSos(sosMessage)
                mainHandler.postDelayed(this, SOS_REPEAT_INTERVAL_MS)
            }
        }
        sosRunnable = runnable
        mainHandler.post(runnable)
    }

    /** Clears local state and sends one final TYPE_SOS carrying the CLEAR
     *  convention (see [CLEAR_MESSAGE]) so peers can dismiss this device's entry
     *  rather than have it linger until some external timeout. */
    fun stopSos() {
        sosActive = false
        sosRunnable?.let { mainHandler.removeCallbacks(it) }
        sosRunnable = null
        val seq = nextMsgSeq.getAndIncrement()
        val loc = MeshLocation.noFix(seq, nowUnixSeconds()).copy(message = CLEAR_MESSAGE)
        sendFrame(MeshFrame.BROADCAST_ID, typeSos, MeshLocation.encode(loc))
        Log.d("OFFTRACE", "SOS: broadcast seq=$seq hasFix=false msgLen=${CLEAR_MESSAGE.length}")
        sosMessage = ""
        // PHASE 6 TRACK A: CLEAR removes it from the carry queue too — matches
        // PHASE 5BC's "CLEAR removes the cache entry" exactly (see class doc).
        ourSosMsgId?.let { carrier.remove(it) }
        ourSosMsgId = null
    }

    private fun broadcastSos(message: String) {
        val seq = nextMsgSeq.getAndIncrement()
        val loc = buildLocationPayload(seq, message)
        val payload = MeshLocation.encode(loc)
        sendFrame(MeshFrame.BROADCAST_ID, typeSos, payload)
        Log.d(
            "OFFTRACE",
            "SOS: broadcast seq=$seq hasFix=${loc.hasFix} msgLen=${loc.message.toByteArray(Charsets.UTF_8).size}"
        )
        // PHASE 6 TRACK A: also upsert into the generalized carry queue so a
        // party member currently out of range gets it the moment they reconnect.
        // BUG 1 FIX 1: expiryMins=SOS_CARRY_EXPIRY_MINS (was 0/never) — see that
        // constant's doc for why an SOS must not be carriable forever.
        val msgId = ourSosMsgId ?: MeshCarrier.newMsgId().also { ourSosMsgId = it }
        carrier.put(msgId, localNodeId, MeshFrame.BROADCAST_ID, typeSos, payload, expiryMins = SOS_CARRY_EXPIRY_MINS)
    }

    /** Called by the owner (OfflineMediaTransport's dispatch) for an incoming
     *  TYPE_SOS — caller is responsible for the dedupe check via
     *  [checkAndRecordDuplicate] BEFORE calling this; this function does not
     *  re-check. PHASE 5BC: also records into [ledger], opportunistically
     *  recalibrates [barometer], updates the store-and-forward cache, and acks
     *  the sender.
     *
     *  BUG 1 FIX 3: [isLive] tells "genuinely live, off a real link right now"
     *  (the default — see OfflineMediaTransport.routeFrame's call site) apart
     *  from "a store-and-forward REPLAY just delivered by MeshCarrier" (see
     *  dispatchCarriedInner's call site, which passes false). A replay older
     *  than [HISTORICAL_REPLAY_AGE_SEC] is marked NOT alarmable — it is still
     *  recorded/shown, just never sounds the siren (see [SosEntry.alarmable]). */
    fun handleSosFrame(header: MeshFrame.Header, payload: ByteArray, isLive: Boolean = true) {
        val loc = MeshLocation.decode(payload) ?: return
        // FIX 4: loc.unixSeconds is the SENDER's clock, nowUnixSeconds() is ours —
        // cross-device clock skew (even a few seconds of drift) can make this go
        // negative; clamp rather than show a nonsensical "age=-1s".
        val ageSec = (nowUnixSeconds() - loc.unixSeconds).coerceAtLeast(0L)
        Log.d(
            "OFFTRACE",
            "SOS: recv from=${MeshFrame.hex(header.srcId)} seq=${loc.msgSeq} hasFix=${loc.hasFix} " +
                "age=${ageSec}s ttl=${header.ttl}"
        )
        val active = loc.message != CLEAR_MESSAGE
        // BUG 1 FIX 2/3: a REPLAY decides alarmability purely from the fix's own
        // age (a fresh receiver has no local suppression history to consult). A
        // LIVE rebroadcast of the SAME still-open episode instead PRESERVES
        // whatever local alarmable state already existed (so SosAlarm's 5-minute
        // auto-stop — see [suppressAlarmFor] — actually stays silenced across
        // subsequent 30s repeats, rather than looking like a fresh onset every
        // time); a fresh episode (no existing entry, or the previous one was
        // CLEARed) always starts alarmable.
        val alarmable = if (!active) {
            true
        } else if (!isLive) {
            ageSec <= HISTORICAL_REPLAY_AGE_SEC
        } else {
            sosEntries[header.srcId]?.takeIf { it.active }?.alarmable ?: true
        }
        if (!isLive && !alarmable) {
            Log.d("OFFTRACE", "SOS: replayed entry age=${ageSec / 60}m — historical, no siren")
        }
        val entry = SosEntry(
            srcId = header.srcId,
            hasFix = loc.hasFix,
            latitude = loc.latitude,
            longitude = loc.longitude,
            accuracyMeters = loc.accuracyMeters,
            fixUnixSeconds = loc.unixSeconds,
            message = if (active) loc.message else "",
            receivedAtMs = System.currentTimeMillis(),
            active = active,
            alarmable = alarmable
        )
        sosEntries[header.srcId] = entry
        if (loc.hasFix) {
            ledger.record(header.srcId, entryFromLocation(loc))
            ledger.clearLostContact(header.srcId)
            mainHandler.post { onPositionUpdated(header.srcId) }
        }
        maybeRecalibrateBarometer(loc, MeshFrame.hex(header.srcId))
        if (active && header.srcId != localNodeId) {
            sendFrame(header.srcId, typeSosAck, ByteArray(0))
        }
        mainHandler.post { onSosEntry(entry) }
    }

    /** PHASE 5BC: called by the owner for an incoming TYPE_SOS_ACK addressed to
     *  us — only meaningful while we have an active SOS of our own; a stray ack
     *  for an already-cleared SOS is ignored. Idempotent — acking twice from the
     *  same node is absorbed by the Set, never double-counted. */
    fun handleSosAckFrame(header: MeshFrame.Header) {
        if (!sosActive) return
        ackedByForMySos.add(header.srcId)
        val seenBy = ackedByForMySos.size
        val total = otherMemberCount()
        Log.d("OFFTRACE", "SOS: ack from=${MeshFrame.hex(header.srcId)} seenBy=$seenBy/$total")
        mainHandler.post { onSosAckUpdate(seenBy, total) }
    }

    /** Current ack progress for this device's own SOS, if any — (seenBy, total). */
    fun ackProgress(): Pair<Int, Int> = ackedByForMySos.size to otherMemberCount()

    /** BUG 1 FIX 2: called once SosAlarm's local 5-minute auto-stop timeout
     *  fires for [senderIds] — marks each of THEIR current entries as no
     *  longer alarmable, so the next ordinary 30s rebroadcast from the SAME
     *  still-active (uncleared) sender does not look like a fresh onset and
     *  re-trigger the siren (see [handleSosFrame]'s alarmable-preservation
     *  logic). Only an explicit CLEAR (or this process restarting) re-arms it.
     *  Does not touch [SosEntry.active] — the SOS itself is not being marked
     *  resolved, only silenced locally on THIS device. No-op for a srcId with
     *  no current entry (nothing to suppress). */
    fun suppressAlarmFor(senderIds: Set<Long>) {
        senderIds.forEach { id ->
            sosEntries[id]?.let { entry -> sosEntries[id] = entry.copy(alarmable = false) }
        }
    }

    /** BUG 1 FIX 4: user-initiated "Clear stored alerts" — wipes every OTHER
     *  sender's cached/historical SOS entry, both the in-memory display
     *  ([sosEntries]) and the underlying MeshCarrier queue, so a stale
     *  test-session alert can never be replayed to a newly joined peer again.
     *  Deliberately does NOT touch this device's OWN currently active SOS (see
     *  [ourSosMsgId]/[sosActive]) — a hygiene button must never silently
     *  cancel a real live emergency; that has its own explicit [stopSos] path.
     *  Returns the count of display entries cleared. */
    fun clearStoredAlerts(): Int {
        val n = sosEntries.size
        sosEntries.clear()
        sosMsgIdBySender.values.forEach { carrier.remove(it) }
        sosMsgIdBySender.clear()
        // Belt-and-suspenders: catches anything already carried (e.g. relayed
        // through this device as a mule) that never surfaced in sosEntries yet.
        carrier.clearAllOfType(typeSos)
        Log.d("OFFTRACE", "SOS: stored alerts cleared by user (n=$n)")
        return n
    }

    /** PHASE 6 TRACK B3: the carrier msgId tracking a given sender's current
     *  active SOS, if any — SosRelay needs this to mark a specific SOS as
     *  already-prompted after the user taps through to send. Covers both a
     *  received SOS (via [sosMsgIdBySender]) and this device's own (via
     *  [ourSosMsgId]). */
    fun carrierMsgIdFor(srcId: Long): String? =
        if (srcId == localNodeId) ourSosMsgId else sosMsgIdBySender[srcId]

    // ── PHASE 6 TRACK A: SOS carry, now generalized via MeshCarrier ─────────────

    /** Called by the owner ONLY for a genuinely LIVE (not carrier-delivered)
     *  incoming TYPE_SOS, right alongside [handleSosFrame] — see
     *  OfflineMediaTransport.routeFrame's call site. Queues/updates this SOS in
     *  the generalized carrier so it survives its sender going out of range;
     *  deliberately NOT called from [handleSosFrame] itself, so a
     *  carrier-delivered SOS (redispatched via
     *  OfflineMediaTransport.dispatchCarriedInner, which calls dispatchLocal
     *  directly and never routeFrame) doesn't re-queue itself —
     *  MeshCarrier.handleStoreFwdFrame already becomes a mule for that case. */
    fun cacheForCarry(srcId: Long, payload: ByteArray) {
        val loc = MeshLocation.decode(payload) ?: return
        if (loc.message != CLEAR_MESSAGE) {
            val msgId = sosMsgIdBySender.getOrPut(srcId) { MeshCarrier.newMsgId() }
            // BUG 1 FIX 1: see broadcastSos's identical change — this is the
            // "I heard someone else's SOS, now I'll carry it too" path.
            carrier.put(msgId, srcId, MeshFrame.BROADCAST_ID, typeSos, payload, expiryMins = SOS_CARRY_EXPIRY_MINS)
        } else {
            sosMsgIdBySender.remove(srcId)?.let { carrier.remove(it) }
        }
        // An SOS sent or received is exactly the kind of moment that must survive
        // a crash — flush immediately rather than wait for the periodic batch.
        ledger.flushImmediately(srcId)
    }

    // ── PHASE 5BC: ambient position broadcast (the ledger feed) ────────────────

    /** Session-scoped: call once when the mesh session becomes active, balanced
     *  by [stopPositionBroadcasts]. Sent whether or not any SOS is active. */
    fun startPositionBroadcasts() {
        positionRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                broadcastPosition()
                mainHandler.postDelayed(this, positionBroadcastIntervalMs)
            }
        }
        positionRunnable = runnable
        mainHandler.post(runnable)
    }

    fun stopPositionBroadcasts() {
        positionRunnable?.let { mainHandler.removeCallbacks(it) }
        positionRunnable = null
    }

    /** PHASE 6 TRACK B: overrides the ambient broadcast cadence — used by
     *  SosBeaconMode to drop from 30s to 60s once SOS has fired on this device.
     *  Takes effect on the NEXT scheduled broadcast (not immediately re-armed —
     *  a beacon-mode transition doesn't need sub-second precision). */
    fun setPositionBroadcastIntervalMs(ms: Long) {
        positionBroadcastIntervalMs = ms
        Log.d("OFFTRACE", "POS: broadcast interval set to ${ms / 1000}s")
    }

    private fun broadcastPosition() {
        val seq = nextMsgSeq.getAndIncrement()
        val loc = buildLocationPayload(seq, "")
        sendFrame(MeshFrame.BROADCAST_ID, typePosition, MeshLocation.encode(loc))
        Log.d(
            "OFFTRACE",
            "POS: bcast seq=$seq tier=${loc.locTier} baro=${loc.altitudeBaroM}m " +
                "hdg=${loc.headingDeg} spd=${loc.speedCms}"
        )
    }

    /** Called by the owner for an incoming TYPE_POSITION — dedupe-before-calling
     *  contract, same as [handleSosFrame]. Feeds [ledger] and opportunistically
     *  recalibrates [barometer]; fires no user-facing alert (unlike SOS). */
    fun handlePositionFrame(header: MeshFrame.Header, payload: ByteArray) {
        val loc = MeshLocation.decode(payload) ?: return
        // FIX 4: cross-device clock skew — see handleSosFrame's identical fix.
        val ageSec = (nowUnixSeconds() - loc.unixSeconds).coerceAtLeast(0L)
        Log.d("OFFTRACE", "POS: recv from=${MeshFrame.hex(header.srcId)} seq=${loc.msgSeq} age=${ageSec}s")
        if (loc.hasFix) {
            ledger.record(header.srcId, entryFromLocation(loc))
            ledger.clearLostContact(header.srcId)
            mainHandler.post { onPositionUpdated(header.srcId) }
        }
        maybeRecalibrateBarometer(loc, MeshFrame.hex(header.srcId))
    }

    private fun entryFromLocation(loc: MeshLocation): MeshLedger.Entry = MeshLedger.Entry(
        latE7 = loc.latE7,
        lonE7 = loc.lonE7,
        accuracyMeters = loc.accuracyMeters,
        altitudeBaroM = loc.altitudeBaroM,
        pressureHpaX10 = loc.pressureHpaX10,
        headingDeg = loc.headingDeg,
        speedCms = loc.speedCms,
        tier = loc.locTier,
        receivedAtMs = System.currentTimeMillis()
    )

    /** Opportunistic barometer recalibration — any fix (self or peer) with GPS
     *  accuracy better than [MeshBarometer.CALIBRATION_MIN_ACCURACY_M] resets
     *  the local sea-level reference. A fixed 1013.25 reference drifts badly
     *  over a multi-day trip as weather moves; this keeps it current whenever a
     *  good-enough fix happens to come with a simultaneous pressure reading. */
    private fun maybeRecalibrateBarometer(loc: MeshLocation, fromNodeIdHex: String) {
        if (!loc.hasFix) return
        if (loc.locTier != MeshLocation.LOC_TIER_GPS_LIVE && loc.locTier != MeshLocation.LOC_TIER_GPS_STALE) return
        val acc = loc.accuracyMeters ?: return
        if (acc >= MeshBarometer.CALIBRATION_MIN_ACCURACY_M) return
        val gpsAlt = loc.altitudeMeters ?: return
        val pressure = loc.pressureHpa ?: return
        barometer.recalibrateFromPeer(gpsAlt.toDouble(), pressure, fromNodeIdHex)
    }

    // ── FIND ─────────────────────────────────────────────────────────────────

    fun sendFindRequest(targetNodeId: Long) {
        val seq = nextMsgSeq.getAndIncrement()
        val loc = MeshLocation.noFix(seq, nowUnixSeconds())
        sendFrame(targetNodeId, typeFindReq, MeshLocation.encode(loc))
        Log.d("OFFTRACE", "FIND: req -> ${MeshFrame.hex(targetNodeId)} seq=$seq")
    }

    /** Called by the owner for an incoming TYPE_FIND_REQ addressed to us — same
     *  dedupe-before-calling contract as [handleSosFrame]. Rate-limited so a
     *  buggy or malicious peer spamming requests can't keep our GPS running
     *  continuously ("pin our GPS on"). */
    fun handleFindRequestFrame(header: MeshFrame.Header) {
        val now = System.currentTimeMillis()
        val last = lastFindResponseAtMs[header.srcId]
        if (last != null && now - last < FIND_RESPONSE_RATE_LIMIT_MS) {
            Log.d("OFFTRACE", "FIND: resp rate-limited for ${MeshFrame.hex(header.srcId)}")
            return
        }
        lastFindResponseAtMs[header.srcId] = now
        val seq = nextMsgSeq.getAndIncrement()
        val loc = buildLocationPayload(seq, "")
        sendFrame(header.srcId, typeFindResp, MeshLocation.encode(loc))
        Log.d("OFFTRACE", "FIND: resp from=${MeshFrame.hex(localNodeId)} hasFix=${loc.hasFix}")
    }

    /** Called by the owner for an incoming TYPE_FIND_RESP addressed to us. */
    fun handleFindResponseFrame(header: MeshFrame.Header, payload: ByteArray) {
        val loc = MeshLocation.decode(payload) ?: return
        Log.d("OFFTRACE", "FIND: resp from=${MeshFrame.hex(header.srcId)} hasFix=${loc.hasFix}")
        val entry = SosEntry(
            srcId = header.srcId,
            hasFix = loc.hasFix,
            latitude = loc.latitude,
            longitude = loc.longitude,
            accuracyMeters = loc.accuracyMeters,
            fixUnixSeconds = loc.unixSeconds,
            message = loc.message,
            receivedAtMs = System.currentTimeMillis(),
            active = true
        )
        findResponses[header.srcId] = entry
        if (loc.hasFix) {
            ledger.record(header.srcId, entryFromLocation(loc))
            ledger.clearLostContact(header.srcId)
        }
        maybeRecalibrateBarometer(loc, MeshFrame.hex(header.srcId))
        mainHandler.post { onFindResponse(entry) }
    }

    // ── Shared ───────────────────────────────────────────────────────────────

    private fun buildLocationPayload(seq: Long, message: String): MeshLocation {
        val fix = locationProvider.getBestFix()
        val pressureHpa = barometer.smoothedPressureHpa()
        val pressureHpaX10 = pressureHpa?.let { (it * 10.0).toInt() }
        val seqSinceBoot = (nextPositionSeqSinceBoot.getAndIncrement() and 0xFFFFL).toInt()
        return if (fix != null) {
            ledger.record(
                localNodeId,
                MeshLedger.Entry(
                    latE7 = (fix.latitude * 1e7).toInt(),
                    lonE7 = (fix.longitude * 1e7).toInt(),
                    accuracyMeters = fix.accuracyMeters?.toInt(),
                    altitudeBaroM = barometer.currentAltitudeEstimateM(),
                    pressureHpaX10 = pressureHpaX10,
                    headingDeg = null,
                    speedCms = null,
                    tier = fix.tier.wireValue,
                    receivedAtMs = System.currentTimeMillis()
                )
            )
            val (hdg, spd) = ledger.computeHeadingAndSpeed(localNodeId)
            val loc = MeshLocation(
                payloadVersion = MeshLocation.PAYLOAD_VERSION,
                hasFix = true,
                isMoving = (spd ?: 0) > 0,
                batteryLow = false,
                msgSeq = seq,
                latE7 = (fix.latitude * 1e7).toInt(),
                lonE7 = (fix.longitude * 1e7).toInt(),
                accuracyMeters = fix.accuracyMeters?.toInt(),
                altitudeMeters = fix.altitudeMeters?.toInt(),
                batteryPercent = null,
                unixSeconds = fix.fixTimeMs / 1000L,
                message = message,
                locTier = fix.tier.wireValue,
                altitudeBaroM = barometer.currentAltitudeEstimateM(),
                pressureHpaX10 = pressureHpaX10,
                headingDeg = hdg,
                speedCms = spd,
                seqSinceBoot = seqSinceBoot
            )
            maybeRecalibrateBarometer(loc, MeshFrame.hex(localNodeId))
            loc
        } else {
            MeshLocation.noFix(seq, nowUnixSeconds()).copy(
                message = message,
                locTier = MeshLocation.LOC_TIER_NONE,
                pressureHpaX10 = pressureHpaX10,
                seqSinceBoot = seqSinceBoot
            )
        }
    }

    private fun nowUnixSeconds(): Long = System.currentTimeMillis() / 1000L

    /** Idempotent teardown — stops the SOS and position repeats if running. Does
     *  not touch [locationProvider]/[barometer]/[ledger] (owner's responsibility,
     *  since they're shared with other callers — see their own ref-counted
     *  start/stop). */
    fun shutdown() {
        sosActive = false
        sosRunnable?.let { mainHandler.removeCallbacks(it) }
        sosRunnable = null
        positionRunnable?.let { mainHandler.removeCallbacks(it) }
        positionRunnable = null
    }
}
