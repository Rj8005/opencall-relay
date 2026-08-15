package com.opencall.relay.offline

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * PHASE 6 TRACK A: generalized store-and-forward — "every climber becomes a
 * data mule" for ANY frame type, not just SOS. PHASE 5BC hardcoded SOS carry
 * directly inside MeshSosManager (one raw-payload slot per sender, replayed to
 * every newly resolved peer via [OfflineMediaTransport]'s srcId-preserving
 * writeRawFrame) — see that mechanism's removal in MeshSosManager for the
 * migration this class replaces it with (survey: A1).
 *
 * Envelope (TYPE_STORE_FWD, big-endian, [encode]/[decode] below):
 *   msgId        16B  random UUID bytes, globally unique per logical message
 *   originId      8B  nodeId of the TRUE originator — unlike PHASE 5BC's
 *                      srcId-spoofing hack, this frame's own MeshFrame header
 *                      srcId is always whoever is CARRYING it right now (an
 *                      ordinary frame sent via the transport's normal
 *                      writeFrame); the true originator lives in this field,
 *                      fully decoupled from routing.
 *   finalDstId    8B  nodeId, or MeshFrame.BROADCAST_ID
 *   innerType     1B  the real type being carried (chat, SOS, position...)
 *   createdUnix   4B  uint32
 *   expiryMins    2B  uint16, 0 = never expire
 *   hopCount      1B  uint8 — incremented each time a NEW device becomes a
 *                      mule for a BROADCAST message (see [handleStoreFwdFrame])
 *   innerLen      2B  uint16
 *   inner         n   the original payload bytes, unmodified
 *   total: 42 + n bytes.
 *
 * DELIVERY: TYPE_SF_ACK (payload = 16B msgId) goes back to whoever handed us
 * this message — ONE hop, not the true originator. This mesh is a star
 * topology today (every client's one link is the GO — see PHASE 3's class
 * doc), so "back along the path" reduces to "back to my immediate sender" and
 * needs no path tracking; the GO, sitting at the hub, sees every ack and
 * offer, so single-hop ack propagation is already complete for this topology.
 *
 * DEDUPE: msgId, NOT (srcId,type,seq) — deliberately a SEPARATE cache from
 * [MeshSosManager]'s, which per this project's ongoing scope constraint stays
 * scoped to exactly SOS/FIND_REQ/FIND_RESP/POSITION and is never touched by
 * this class. Once a delivered inner SOS/FIND/POSITION frame is handed back to
 * [OfflineMediaTransport.dispatchCarriedInner], THAT function re-applies
 * MeshSosManager's own msgSeq-based dedupe to the inner payload — this class's
 * msgId dedupe only prevents ITS OWN queue/relay churn; it is not what
 * prevents a re-alarm. That guarantee is unchanged from PHASE 5BC (see the
 * proof in MeshSosManager's migrated SOS methods).
 *
 * EVICTION: queue capped at [MAX_QUEUE_SIZE]; over capacity evicts the OLDEST
 * non-SOS message first — an SOS is never evicted by capacity pressure, only
 * by its own CLEAR (via [remove]) or expiry.
 *
 * PERSISTENCE: one JSON file per msgId under context.filesDir/carry/, same
 * atomic (temp file + fsync + rename) and batched-flush rules as MeshLedger —
 * see that file's class doc for the reasoning (a battery pull must never leave
 * a torn file; a disk write per received frame would cost real battery).
 */
class MeshCarrier private constructor(context: Context) {

    data class Queued(
        val msgId: String,
        val originId: Long,
        val finalDstId: Long,
        val innerType: Byte,
        val createdUnix: Long,
        val expiryMins: Int,
        @Volatile var hopCount: Int,
        val inner: ByteArray,
        val deliveredTo: MutableSet<Long>
    )

    companion object {
        const val MAX_QUEUE_SIZE = 200
        const val MAX_HOP_COUNT = 8
        private const val DEDUPE_CAPACITY = 512
        private const val DEDUPE_TTL_MS = 30 * 60 * 1000L
        private const val CARRY_DIR_NAME = "carry"
        private const val FLUSH_INTERVAL_MS = 60_000L
        private const val ENVELOPE_HEADER_SIZE = 42

        @Volatile private var instance: MeshCarrier? = null

        fun get(context: Context): MeshCarrier =
            instance ?: synchronized(this) {
                instance ?: MeshCarrier(context.applicationContext).also { instance = it }
            }

        fun newMsgId(): String = UUID.randomUUID().toString()

        private fun uuidToBytes(id: UUID): ByteArray {
            val buf = ByteBuffer.allocate(16)
            buf.putLong(id.mostSignificantBits)
            buf.putLong(id.leastSignificantBits)
            return buf.array()
        }

        private fun bytesToUuid(bytes: ByteArray): UUID {
            val buf = ByteBuffer.wrap(bytes)
            return UUID(buf.long, buf.long)
        }

        /** Envelope encode — always the current layout, mirrors MeshLocation's
         *  encode()/decode() split for the same "pure, testable codec" reasons. */
        fun encode(q: Queued): ByteArray {
            val buf = ByteBuffer.allocate(ENVELOPE_HEADER_SIZE + q.inner.size)
            buf.put(uuidToBytes(UUID.fromString(q.msgId)))
            buf.putLong(q.originId)
            buf.putLong(q.finalDstId)
            buf.put(q.innerType)
            buf.putInt((q.createdUnix and 0xFFFFFFFFL).toInt())
            buf.putShort((q.expiryMins and 0xFFFF).toShort())
            buf.put((q.hopCount and 0xFF).toByte())
            buf.putShort((q.inner.size and 0xFFFF).toShort())
            buf.put(q.inner)
            return buf.array()
        }

        data class Envelope(
            val msgId: String,
            val originId: Long,
            val finalDstId: Long,
            val innerType: Byte,
            val createdUnix: Long,
            val expiryMins: Int,
            val hopCount: Int,
            val inner: ByteArray
        )

        /** Never throws — truncated/malformed input returns null. */
        fun decode(bytes: ByteArray): Envelope? {
            if (bytes.size < ENVELOPE_HEADER_SIZE) return null
            return try {
                val buf = ByteBuffer.wrap(bytes)
                val msgIdBytes = ByteArray(16)
                buf.get(msgIdBytes)
                val msgId = bytesToUuid(msgIdBytes).toString()
                val originId = buf.long
                val finalDstId = buf.long
                val innerType = buf.get()
                val createdUnix = buf.int.toLong() and 0xFFFFFFFFL
                val expiryMins = buf.short.toInt() and 0xFFFF
                val hopCount = buf.get().toInt() and 0xFF
                val innerLen = buf.short.toInt() and 0xFFFF
                if (buf.remaining() < innerLen) return null
                val inner = ByteArray(innerLen)
                buf.get(inner)
                Envelope(msgId, originId, finalDstId, innerType, createdUnix, expiryMins, hopCount, inner)
            } catch (_: Exception) {
                null
            }
        }

        /** Peeks just the 16-byte msgId prefix for the dedupe hook — never throws. */
        fun peekMsgId(bytes: ByteArray): String? {
            if (bytes.size < 16) return null
            return try { bytesToUuid(bytes.copyOf(16)).toString() } catch (_: Exception) { null }
        }

        fun ackPayload(msgId: String): ByteArray = uuidToBytes(UUID.fromString(msgId))

        fun decodeAckMsgId(payload: ByteArray): String? {
            if (payload.size < 16) return null
            return try { bytesToUuid(payload.copyOf(16)).toString() } catch (_: Exception) { null }
        }
    }

    private val appContext = context.applicationContext
    private val carryDir = File(appContext.filesDir, CARRY_DIR_NAME).apply { mkdirs() }
    private val queue = ConcurrentHashMap<String, Queued>()
    private val dirtyMsgIds = ConcurrentHashMap.newKeySet<String>()

    private val ioThread = HandlerThread("MeshCarrierIO").apply { start() }
    private val ioHandler = Handler(ioThread.looper)
    @Volatile private var periodicFlushScheduled = false
    private val periodicFlush = object : Runnable {
        override fun run() {
            flushDirty()
            if (periodicFlushScheduled) ioHandler.postDelayed(this, FLUSH_INTERVAL_MS)
        }
    }

    // msgId dedupe — separate cache from MeshSosManager's, see class doc.
    private val dedupeLock = Any()
    private val seenMsgIdAtMs = object : LinkedHashMap<String, Long>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > DEDUPE_CAPACITY
    }

    // Wiring — set once by the owner (OfflineMediaTransport) at construction.
    private var localNodeId: Long = 0L
    private var typeStoreFwd: Byte = 0
    private var typeSosProtected: Byte = 0
    var sendStoreFwd: ((dst: Long, payload: ByteArray) -> Unit)? = null
    var sendAck: ((dst: Long, payload: ByteArray) -> Unit)? = null
    /** Unwraps a delivered-to-us message back into the normal dispatch path
     *  (chat UI, SOS alert/ledger, etc.) — wired to
     *  OfflineMediaTransport.dispatchCarriedInner. */
    var dispatchInner: ((originId: Long, finalDstId: Long, innerType: Byte, inner: ByteArray) -> Unit)? = null

    init {
        ioHandler.post { loadAllFromDisk() }
    }

    /** Called once by the owner right after construction — see MeshSosManager's
     *  constructor-injection pattern; this class is a process-wide singleton
     *  (like MeshLedger/MeshBarometer) so its per-session identity/type-byte
     *  wiring is configured explicitly rather than passed to a constructor. */
    fun configure(localNodeId: Long, typeStoreFwd: Byte, typeSos: Byte) {
        this.localNodeId = localNodeId
        this.typeStoreFwd = typeStoreFwd
        this.typeSosProtected = typeSos
    }

    fun startPeriodicFlush() {
        if (periodicFlushScheduled) return
        periodicFlushScheduled = true
        ioHandler.postDelayed(periodicFlush, FLUSH_INTERVAL_MS)
    }

    fun stopPeriodicFlushAndFlushNow() {
        periodicFlushScheduled = false
        ioHandler.removeCallbacks(periodicFlush)
        ioHandler.post { flushDirty() }
    }

    fun flushNow() {
        ioHandler.post { flushDirty() }
    }

    // ── Queueing ─────────────────────────────────────────────────────────────

    /** Adds or updates (same [msgId] = upsert — e.g. a repeated SOS beacon reuses
     *  one msgId per episode rather than creating a new queue entry every 30s;
     *  see MeshSosManager) a message in the carry queue. [alreadyDeliveredTo]
     *  pre-seeds the delivered set with peers who already have it via the
     *  live/immediate broadcast path, so they're never redundantly re-offered. */
    fun put(
        msgId: String,
        originId: Long,
        finalDstId: Long,
        innerType: Byte,
        inner: ByteArray,
        expiryMins: Int,
        alreadyDeliveredTo: Set<Long> = emptySet(),
        hopCountOverride: Int? = null
    ) {
        val existing = queue[msgId]
        val q = Queued(
            msgId = msgId,
            originId = originId,
            finalDstId = finalDstId,
            innerType = innerType,
            createdUnix = existing?.createdUnix ?: (System.currentTimeMillis() / 1000L),
            expiryMins = expiryMins,
            hopCount = hopCountOverride ?: existing?.hopCount ?: 0,
            inner = inner,
            deliveredTo = (existing?.deliveredTo?.toMutableSet() ?: mutableSetOf()).apply { addAll(alreadyDeliveredTo) }
        )
        queue[msgId] = q
        dirtyMsgIds.add(msgId)
        evictIfOverCapacity()
        Log.d(
            "OFFTRACE",
            "CARRY: queued msgId=$msgId inner=$innerType dst=${MeshFrame.hex(finalDstId)} exp=${expiryMins}m"
        )
        flushNow()
    }

    /** Removes a message entirely — SOS CLEAR (matches PHASE 5BC's "CLEAR
     *  removes the cache entry" exactly) and full delivery to a single
     *  (non-broadcast) recipient both call this. */
    fun remove(msgId: String) {
        queue.remove(msgId)
        dirtyMsgIds.remove(msgId)
        ioHandler.post { try { File(carryDir, "$msgId.json").delete() } catch (_: Exception) {} }
    }

    private fun evictIfOverCapacity() {
        if (queue.size <= MAX_QUEUE_SIZE) return
        val candidates = queue.values.filter { it.innerType != typeSosProtected }.sortedBy { it.createdUnix }
        var overBy = queue.size - MAX_QUEUE_SIZE
        for (c in candidates) {
            if (overBy <= 0) break
            queue.remove(c.msgId)
            dirtyMsgIds.remove(c.msgId)
            val msgId = c.msgId
            ioHandler.post { try { File(carryDir, "$msgId.json").delete() } catch (_: Exception) {} }
            Log.d("OFFTRACE", "CARRY: evicted msgId=$msgId reason=capacity queue=${queue.size}")
            overBy--
        }
    }

    private fun pruneExpiredAndOverHop() {
        val now = System.currentTimeMillis() / 1000L
        val toDrop = queue.values.filter { q ->
            (q.expiryMins > 0 && now - q.createdUnix > q.expiryMins * 60L) || q.hopCount > MAX_HOP_COUNT
        }
        toDrop.forEach { q ->
            val reason = if (q.hopCount > MAX_HOP_COUNT) "hop_limit" else "expired"
            queue.remove(q.msgId)
            dirtyMsgIds.remove(q.msgId)
            val msgId = q.msgId
            ioHandler.post { try { File(carryDir, "$msgId.json").delete() } catch (_: Exception) {} }
            // BUG 1 FIX 1: SOS gets its own log line — an SOS that outlived its
            // expiry is a distinct, notable event (it means one WAS stuck
            // indefinitely before this fix; see class doc's expiryMins=0 note),
            // worth being able to grep for separately from ordinary chat/etc. churn.
            if (reason == "expired" && q.innerType == typeSosProtected) {
                val ageHours = (now - q.createdUnix) / 3600L
                Log.d("OFFTRACE", "CARRY: SOS expired msgId=$msgId age=${ageHours}h — dropped")
            } else {
                Log.d("OFFTRACE", "CARRY: evicted msgId=$msgId reason=$reason queue=${queue.size}")
            }
        }
    }

    /** BUG 1 FIX 4: user-initiated "Clear stored alerts" — wipes every currently
     *  queued entry of [type] (in practice: TYPE_SOS) regardless of expiry, so a
     *  stale test-session alert can never be replayed to a newly joined peer
     *  again. Returns the count removed. */
    fun clearAllOfType(type: Byte): Int {
        val ids = queue.values.filter { it.innerType == type }.map { it.msgId }
        ids.forEach { remove(it) }
        return ids.size
    }

    // ── Offering to a newly resolved peer ───────────────────────────────────

    /** Called by the owner whenever a NEW peer resolves — offers every
     *  undelivered message addressed to them (directly, or BROADCAST and not
     *  yet marked delivered to them), subject to expiry/hop-count pruning. */
    fun offerTo(peerId: Long) {
        pruneExpiredAndOverHop()
        val toOffer = queue.values.filter { q ->
            (q.finalDstId == peerId || q.finalDstId == MeshFrame.BROADCAST_ID) &&
                peerId !in q.deliveredTo && peerId != q.originId
        }
        if (toOffer.isEmpty()) return
        Log.d("OFFTRACE", "CARRY: offering ${toOffer.size} message(s) to new peer ${MeshFrame.hex(peerId)}")
        toOffer.forEach { q -> sendStoreFwd?.invoke(peerId, encode(q)) }
    }

    // ── Receiving ────────────────────────────────────────────────────────────

    fun isCarrierType(type: Byte): Boolean = type == typeStoreFwd

    /** Separate dedupe cache from MeshSosManager's — see class doc. */
    fun checkAndRecordDuplicate(payload: ByteArray): Boolean {
        val msgId = peekMsgId(payload) ?: return false
        val now = System.currentTimeMillis()
        synchronized(dedupeLock) {
            val it = seenMsgIdAtMs.entries.iterator()
            while (it.hasNext()) { if (now - it.next().value > DEDUPE_TTL_MS) it.remove() }
            if (seenMsgIdAtMs.containsKey(msgId)) return true
            seenMsgIdAtMs[msgId] = now
            return false
        }
    }

    fun handleStoreFwdFrame(header: MeshFrame.Header, payload: ByteArray) {
        val env = decode(payload) ?: return
        if (env.hopCount > MAX_HOP_COUNT) {
            Log.d("OFFTRACE", "CARRY: evicted msgId=${env.msgId} reason=hop_limit queue=${queue.size}")
            return
        }
        val forUs = env.finalDstId == MeshFrame.BROADCAST_ID || env.finalDstId == localNodeId
        if (forUs) {
            dispatchInner?.invoke(env.originId, env.finalDstId, env.innerType, env.inner)
            sendAck?.invoke(header.srcId, ackPayload(env.msgId))
        }
        if (env.finalDstId == MeshFrame.BROADCAST_ID) {
            // Become a mule too, so the message keeps propagating to peers this
            // device meets later — whoever handed it to us clearly already has
            // it, so pre-seed them into deliveredTo.
            put(
                env.msgId, env.originId, env.finalDstId, env.innerType, env.inner, env.expiryMins,
                alreadyDeliveredTo = setOf(header.srcId),
                hopCountOverride = env.hopCount + 1
            )
        }
    }

    fun handleAckFrame(header: MeshFrame.Header, payload: ByteArray) {
        val msgId = decodeAckMsgId(payload) ?: return
        val q = queue[msgId] ?: return
        q.deliveredTo.add(header.srcId)
        dirtyMsgIds.add(msgId)
        Log.d("OFFTRACE", "CARRY: delivered msgId=$msgId hops=${q.hopCount} to=${MeshFrame.hex(header.srcId)}")
        if (q.finalDstId != MeshFrame.BROADCAST_ID) {
            remove(msgId) // single recipient, fully delivered
        } else {
            flushNow()
        }
    }

    fun queueSize(): Int = queue.size

    // ── Persistence: atomic + batched, see class doc ────────────────────────────

    private fun flushDirty() {
        val ids = dirtyMsgIds.toList()
        ids.forEach { msgId ->
            dirtyMsgIds.remove(msgId)
            val q = queue[msgId] ?: return@forEach
            writeQueuedToDisk(q)
        }
    }

    private fun writeQueuedToDisk(q: Queued) {
        val root = JSONObject().apply {
            put("msgId", q.msgId)
            put("originId", q.originId)
            put("finalDstId", q.finalDstId)
            put("innerType", q.innerType.toInt())
            put("createdUnix", q.createdUnix)
            put("expiryMins", q.expiryMins)
            put("hopCount", q.hopCount)
            put("inner", android.util.Base64.encodeToString(q.inner, android.util.Base64.NO_WRAP))
            put("deliveredTo", JSONArray(q.deliveredTo.toList()))
        }
        writeAtomic(File(carryDir, "${q.msgId}.json"), root.toString())
    }

    private fun writeAtomic(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        try {
            FileOutputStream(tmp).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                Log.w("OFFTRACE", "CARRY: atomic rename failed for ${target.name}")
            }
        } catch (e: Exception) {
            Log.w("OFFTRACE", "CARRY: write failed for ${target.name}: ${e.message}")
            try { tmp.delete() } catch (_: Exception) {}
        }
    }

    private fun loadAllFromDisk() {
        val files = carryDir.listFiles { f -> f.name.endsWith(".json") } ?: return
        var loaded = 0
        files.forEach { file ->
            try {
                val o = JSONObject(file.readText(Charsets.UTF_8))
                val msgId = o.getString("msgId")
                val delivered = mutableSetOf<Long>()
                o.optJSONArray("deliveredTo")?.let { arr ->
                    for (i in 0 until arr.length()) delivered.add(arr.getLong(i))
                }
                val q = Queued(
                    msgId = msgId,
                    originId = o.getLong("originId"),
                    finalDstId = o.getLong("finalDstId"),
                    innerType = o.getInt("innerType").toByte(),
                    createdUnix = o.getLong("createdUnix"),
                    expiryMins = o.getInt("expiryMins"),
                    hopCount = o.getInt("hopCount"),
                    inner = android.util.Base64.decode(o.getString("inner"), android.util.Base64.NO_WRAP),
                    deliveredTo = delivered
                )
                queue[msgId] = q
                loaded++
            } catch (e: Exception) {
                Log.w("OFFTRACE", "CARRY: discarding malformed queue file ${file.name}: ${e.message}")
            }
        }
        pruneExpiredAndOverHop()
        Log.d("OFFTRACE", "CARRY: loaded $loaded queued message(s) from disk")
    }
}
