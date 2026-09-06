package com.opencall.relay.offline

import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap

/**
 * PHASE 3: v3 wire envelope, built/parsed in exactly one place so the GO's forwarding
 * loop and every client's send/receive call sites share identical framing.
 * [1B ver][8B srcId][8B dstId][1B ttl][1B type][4B BE len][payload]
 *
 * PHASE 3 additions over Phase 2's v2: HELLO now carries a display name (roster needs
 * something to show besides a hex id), and three new control types support the
 * switchboard: TYPE_ROSTER (membership fan-out), TYPE_BUSY / TYPE_HANGUP (1:1 call
 * lifecycle across a relay). Bumped to v3 — no attempt at v2 interop, same policy as
 * the v1->v2 jump.
 */
class MeshFrame {
    data class Header(val ver: Byte, val srcId: Long, val dstId: Long, val ttl: Byte, val type: Byte, val length: Int)

    companion object {
        const val VERSION: Byte = 3
        const val BROADCAST_ID: Long = -1L // all 64 bits set == 0xFFFFFFFFFFFFFFFF
        // PHASE 3: sentinel for "this link's peer hasn't sent HELLO yet" — deliberately
        // distinct from BROADCAST_ID so a not-yet-resolved link can never be mistaken
        // for (or accidentally satisfy a lookup against) the broadcast address.
        const val PENDING_ID: Long = -2L
        private const val HEADER_SIZE = 1 + 8 + 8 + 1 + 1 + 4 // ver+src+dst+ttl+type+len = 23 bytes

        fun encode(srcId: Long, dstId: Long, ttl: Byte, type: Byte, payload: ByteArray): ByteArray {
            val buf = ByteBuffer.allocate(HEADER_SIZE + payload.size)
            buf.put(VERSION)
            buf.putLong(srcId)
            buf.putLong(dstId)
            buf.put(ttl)
            buf.put(type)
            buf.putInt(payload.size)
            buf.put(payload)
            return buf.array()
        }

        /** Reads only the header — caller reads exactly `length` payload bytes itself
         *  (or skips them), same division of labor as the old type+length framing. */
        fun decodeHeader(din: DataInputStream): Header {
            val ver = din.readByte()
            val src = din.readLong()
            val dst = din.readLong()
            val ttl = din.readByte()
            val type = din.readByte()
            val len = din.readInt()
            return Header(ver, src, dst, ttl, type, len)
        }

        fun hex(id: Long): String = String.format("%016x", id)
    }
}

/**
 * PHASE 3: one PeerLink per connected socket — on the GO, one per group member
 * (Phase 3: up to ~8 for a WiFi Direct group); on a client, exactly one (the GO).
 *
 * nodeId/name start unresolved (PENDING_ID/"") and are filled in once this link's
 * HELLO arrives — see RoutingTable.resolve(). Every outbound write (both this node's
 * own frames and anything the GO forwards) goes through [enqueue].
 *
 * PHASE 8 STEP 6: three priority lanes, not one FIFO queue — control (every
 * type except AUDIO/FRAME) and audio are NEVER dropped by this class; only
 * the video lane sheds load, oldest-first, once it crosses its high-water
 * mark. The writer thread always drains control fully, then audio, then
 * video, so a peer that can't keep up degrades video first and only —
 * exactly the "never breaks call stability/audio, only video degrades"
 * requirement this phase is built around. One slow/dead peer's socket still
 * can never block progress on anyone else's — this stays entirely per-peer.
 */
class PeerLink(
    @Volatile var nodeId: Long,
    @Volatile var name: String,
    private val dataOut: DataOutputStream,
    val dataIn: DataInputStream
) {
    companion object {
        private const val CONTROL_QUEUE_CAPACITY = 128
        // OCP PHASE 1: every capacity below is a TIME budget, not a frame
        // count picked for its own sake — the old caps (video 64 @30fps =
        // 2.13s, audio 256 x20ms = 5.12s) were delay lines, not queues; a
        // socket that's merely slow (not dead) would happily sit at high-
        // water and add multiple SECONDS of standing lag before ever
        // tripping onSustainedBackpressure. Latency must be bounded by the
        // budget below, never by "however many frames fit."
        //   audio  ~200ms @ 20ms/chunk -> 200/20  = 10 chunks
        private const val AUDIO_QUEUE_CAPACITY = 10
        //   video  ~250ms @ 30fps      -> 250/1000*30 = 7.5 -> 8 frames
        private const val VIDEO_QUEUE_CAPACITY = 8
        // PHASE 8 STEP 6 (OCP PHASE 1: now 60% of the new budget, not the
        // old one): once the video lane holds more than this many frames,
        // newly-enqueued video evicts a queued video frame (IDR-preserving —
        // see evictOneVideoFrame) — keeps video latency bounded under
        // sustained pressure without ever touching the control/audio lanes.
        //   8 * 0.6 = 4.8 -> 5 (round to nearest int)
        private const val VIDEO_HIGH_WATER_MARK = 5
        // If the video lane stays over the high-water mark for this long
        // continuously, [onSustainedBackpressure] fires once — the owner (see
        // OfflineMediaTransport) marks this peer degraded per STEP 2's fault
        // isolation, since a socket that can't drain video for 10s straight
        // is functionally the same signal as a peer whose writes keep failing.
        private const val SUSTAINED_BACKPRESSURE_MS = 10_000L
        private const val DROP_LOG_INTERVAL_MS = 1_000L
        // Byte offset of MeshFrame's type field within an ENCODED frame:
        // ver(1) + srcId(8) + dstId(8) + ttl(1) — see MeshFrame.encode/HEADER_SIZE.
        // Mirrors (does not redefine) OfflineMediaTransport's frame-type
        // registry — TYPE_FRAME=2, TYPE_AUDIO=3; TYPE_CONFIG=1 is treated as
        // CONTROL here deliberately (see classifyLane's doc), not video.
        private const val TYPE_BYTE_OFFSET = 18
        private const val TYPE_FRAME: Byte = 2
        private const val TYPE_AUDIO: Byte = 3
        // OCP PHASE 1.2: payload offset within an ENCODED frame — mirrors
        // (does not redefine) MeshFrame.encode's layout: TYPE_BYTE_OFFSET(18)
        // + type(1) + length(4) = 23, same arithmetic as MeshFrame's own
        // private HEADER_SIZE constant.
        private const val PAYLOAD_OFFSET = 23
        private const val NAL_TYPE_IDR = 5

        /** Scans an ENCODED TYPE_FRAME's raw H.264 Annex-B payload for a NAL
         *  unit of type 5 (coded slice of an IDR picture), at either a 3- or
         *  4-byte start code. Pure and off-device-testable — used only to
         *  decide eviction order under video-queue pressure (see
         *  [evictOneVideoFrame]); never gates decode or forwarding, so a
         *  false negative on a malformed/truncated frame just falls back to
         *  ordinary oldest-first eviction, never a crash. Frames whose type
         *  byte isn't TYPE_FRAME are never passed in (see [classifyLane]),
         *  but this still degrades safely (returns false) if one were. */
        fun frameCarriesIdr(frame: ByteArray): Boolean = scanForIdr(frame, PAYLOAD_OFFSET)

        /** OCP PHASE 3.4: same scan, for a bare TYPE_FRAME(_TS) payload with
         *  no MeshFrame envelope at all (e.g. after routeFrame's
         *  resolveFrameAge has already stripped both the envelope header
         *  AND the 8B capture-timestamp prefix) — used only to decide the
         *  age-based drop's IDR exception, never eviction (that stays
         *  [frameCarriesIdr], on the full wire frame, in enqueueVideo). */
        fun payloadCarriesIdr(payload: ByteArray): Boolean = scanForIdr(payload, 0)

        private fun scanForIdr(bytes: ByteArray, startOffset: Int): Boolean {
            var i = startOffset
            val end = bytes.size
            while (i < end - 3) {
                val start4 = bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() &&
                    bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte()
                val start3 = !start4 && bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() &&
                    bytes[i + 2] == 1.toByte()
                if (start4 || start3) {
                    val nalStart = i + (if (start4) 4 else 3)
                    if (nalStart >= end) break
                    if ((bytes[nalStart].toInt() and 0x1F) == NAL_TYPE_IDR) return true
                    i = nalStart
                } else {
                    i++
                }
            }
            return false
        }
    }

    // PHASE 8 TRACK C3: the socket's real remote address, set once right
    // after construction (see OfflineMediaTransport.handleNewConnection) —
    // ground truth for where a relay-tree child could dial THIS peer
    // directly, needing no wire-protocol addition since it's observed, not
    // claimed. Also records whether this link is this device's own OUTBOUND
    // connection (dialed out) vs an ACCEPTED one — the only signal needed to
    // tell "my parent" from "my child" (see handleHelloFrame).
    var remoteAddress: java.net.InetAddress? = null
    var isOutbound: Boolean = false

    // PHASE 8 TRACK C2: srcIds this peer currently wants TYPE_FRAME (video)
    // from — null (the default, and what every link starts as) means
    // "everyone," the safe default for a peer that hasn't sent TYPE_SUBSCRIBE
    // yet (a pre-C2 client, or any small call where nobody ever crosses the
    // tile-budget threshold). emptySet() is a real, distinct value — "no video
    // right now" — and is honored exactly like any other subscribed set; the
    // two are never coalesced. See OfflineMediaTransport.handleSubscribeFrame
    // (writer) and forwardBroadcast's per-destination filter (reader).
    @Volatile var videoSubscription: Set<Long>? = null

    // OCP PHASE 5.1: same null-vs-empty shape as [videoSubscription] above,
    // for the LOW layer specifically — the exact set of srcIds this link
    // wants TYPE_FRAME_LOW from. null means "none requested yet" (the safe
    // default — unlike [videoSubscription]'s null-means-everyone, since the
    // low layer is a brand-new, opt-in-only stream with no legacy meaning
    // to preserve). See handleSubscribeFrame's extended parse.
    @Volatile var videoSubscriptionLow: Set<Long>? = null

    // OCP PHASE 3.2/G6: set once, from this link's own HELLO (see
    // OfflineMediaTransport.handleHelloFrame) — capability negotiation, not
    // a version bump (G6): a peer that never advertised support only ever
    // receives the legacy TYPE_FRAME/TYPE_AUDIO wrapping of the same bytes,
    // never the timestamped one. Defaults false — matches "a device that
    // has never sent HELLO capabilities is treated as not supporting it,"
    // the same safe-default posture PeerLink.videoSubscription's null
    // already uses for a pre-existing capability.
    @Volatile var supportsFrameAge: Boolean = false

    // OCP PHASE 5.1/G6: same capability-negotiation shape as
    // [supportsFrameAge] — set once from this link's own HELLO. A peer that
    // never advertised support only ever receives TYPE_CONFIG/TYPE_FRAME
    // (the legacy, always-understood pair), never TYPE_CONFIG_LOW/
    // TYPE_FRAME_LOW.
    @Volatile var supportsSimulcast: Boolean = false

    private val controlQueue = ArrayBlockingQueue<ByteArray>(CONTROL_QUEUE_CAPACITY)
    private val audioQueue = ArrayBlockingQueue<ByteArray>(AUDIO_QUEUE_CAPACITY)
    private val videoQueue = ArrayBlockingQueue<ByteArray>(VIDEO_QUEUE_CAPACITY)
    // Counts total items across all three lanes so the writer thread can block
    // (interruptibly, via close()'s existing interrupt()) when everything is
    // empty instead of busy-polling three queues.
    private val itemAvailable = java.util.concurrent.Semaphore(0)
    @Volatile private var writerThread: Thread? = null
    @Volatile private var closed = false
    private var videoDropCount = 0
    private var videoDropLogAtMs = 0L
    @Volatile private var overMarkSinceMs = 0L
    // OCP PHASE 0: cumulative, never reset — what the LAT line's dropV/dropA
    // report (distinct from videoDropCount above, which is a windowed
    // counter that resets every DROP_LOG_INTERVAL_MS for the QUEUE: log).
    private val totalVideoDrops = java.util.concurrent.atomic.AtomicLong(0)
    private val totalAudioDrops = java.util.concurrent.atomic.AtomicLong(0)

    /** Fired at most once, off the writer thread, the moment a write to this peer fails
     *  (broken pipe / reset). The caller (RoutingTable owner) should drop this link from
     *  the roster and rebroadcast — see OfflineMediaTransport.handlePeerDisconnected. */
    var onDead: ((PeerLink) -> Unit)? = null

    /** PHASE 8 STEP 6/STEP 2: fired at most once per sustained episode, off
     *  whichever thread enqueued the frame that tipped it over 10s, once the
     *  video lane has stayed over its high-water mark continuously that long.
     *  Never fired for control/audio backpressure — those lanes are sized to
     *  never realistically fill under a live connection. */
    var onSustainedBackpressure: ((PeerLink) -> Unit)? = null

    /** PHASE 8 STEP 6: fired once the video lane drops back under its
     *  high-water mark after a [onSustainedBackpressure] episode — the owner
     *  should clear that peer's degraded/unreachable marking. */
    var onBackpressureCleared: ((PeerLink) -> Unit)? = null

    fun startWriter() {
        val t = Thread({
            try {
                while (true) {
                    itemAvailable.acquire()
                    if (closed && controlQueue.isEmpty() && audioQueue.isEmpty() && videoQueue.isEmpty()) break
                    val frame = controlQueue.poll() ?: audioQueue.poll() ?: videoQueue.poll() ?: continue
                    dataOut.write(frame)
                    dataOut.flush()
                }
            } catch (_: InterruptedException) {
                // Expected on close()/interrupt — not a link failure.
            } catch (e: IOException) {
                if (!closed) {
                    Log.e("OFFTRACE", "MESH: writer for peer=${MeshFrame.hex(nodeId)} died: ${e.message}")
                    onDead?.invoke(this)
                }
            }
        }, "MeshWriter-${MeshFrame.hex(nodeId)}")
        // PHASE 8 TRACK B5: catches anything NOT already handled by the
        // IOException/InterruptedException catches above (e.g. a genuine
        // bug in the priority-queue logic) — logs the full stack to
        // OFFTRACE and lets only THIS peer's writer thread die, never the
        // whole process. Same guard OfflineMediaTransport applies to every
        // media thread it creates.
        t.setUncaughtExceptionHandler { thread, e ->
            Log.e("OFFTRACE", "CRASH-GUARD: ${thread.name} caught ${e.javaClass.simpleName}: ${e.message} - ${Log.getStackTraceString(e)}")
        }
        writerThread = t
        t.start()
    }

    /** Non-blocking hand-off — never called from this link's own writer thread, always
     *  from whichever thread produced the frame (a read loop doing forwarding, or this
     *  node's own camera/mic/chat senders). Classifies [frame] into a priority lane by
     *  its wire type byte; see [classifyLane]. */
    fun enqueue(frame: ByteArray) {
        if (closed) return
        when (classifyLane(frame)) {
            Lane.VIDEO -> enqueueVideo(frame)
            Lane.AUDIO -> enqueueNeverDrop(audioQueue, frame, "audio")
            Lane.CONTROL -> enqueueNeverDrop(controlQueue, frame, "control")
        }
    }

    private enum class Lane { CONTROL, AUDIO, VIDEO }

    /** TYPE_FRAME (video) is the only droppable lane. TYPE_CONFIG (csd) is
     *  deliberately classified CONTROL, not video — it is tiny, sent rarely,
     *  and losing it stalls that peer's decoder until the next reconfigure
     *  (see FIX 1-2/STEP 2's recovery path), which is far worse than the
     *  transient glitch of dropping an ordinary TYPE_FRAME. A frame too short
     *  to even contain a type byte is malformed framing, not a real payload —
     *  treated as CONTROL (never dropped) since there's nothing to classify. */
    private fun classifyLane(frame: ByteArray): Lane {
        if (frame.size <= TYPE_BYTE_OFFSET) return Lane.CONTROL
        return when (frame[TYPE_BYTE_OFFSET]) {
            TYPE_FRAME -> Lane.VIDEO
            TYPE_AUDIO -> Lane.AUDIO
            else -> Lane.CONTROL
        }
    }

    /** Control/audio: sized generously enough that a functioning socket never
     *  fills them (a truly dead connection is caught by the writer thread's
     *  own IOException/onDead path, well before either queue could grow this
     *  large). In the pathological case where one nonetheless fills, this
     *  still does not drop the NEW frame — it evicts the single oldest
     *  same-lane entry instead and logs loudly, since that is a genuine
     *  anomaly worth knowing about, not routine backpressure. */
    private fun enqueueNeverDrop(queue: ArrayBlockingQueue<ByteArray>, frame: ByteArray, laneName: String) {
        if (!queue.offer(frame)) {
            queue.poll()
            queue.offer(frame)
            if (laneName == "audio") totalAudioDrops.incrementAndGet()
            Log.w("OFFTRACE", "MESH: $laneName queue to peer=${MeshFrame.hex(nodeId)} FULL (unexpected) — oldest evicted")
        }
        itemAvailable.release()
    }

    private fun enqueueVideo(frame: ByteArray) {
        var dropped = false
        while (videoQueue.size > VIDEO_HIGH_WATER_MARK) {
            if (evictOneVideoFrame()) dropped = true else break
        }
        if (!videoQueue.offer(frame)) {
            if (evictOneVideoFrame()) dropped = true
            videoQueue.offer(frame)
        }
        itemAvailable.release()
        if (dropped) recordVideoDrop()
        trackBackpressure()
    }

    /** OCP PHASE 1.2: evicts the oldest queued video frame that does NOT
     *  carry an IDR (see [frameCarriesIdr]), scanning from the head
     *  (oldest) forward via the queue's own FIFO iterator. Only
     *  falls back to evicting the true head (which may be an IDR) if EVERY
     *  queued frame currently carries one — the queue must still shed load
     *  to stay bounded, and IDR-avoidance is a preference, not an
     *  invariant that can stall eviction altogether. */
    private fun evictOneVideoFrame(): Boolean {
        val it = videoQueue.iterator()
        while (it.hasNext()) {
            if (!frameCarriesIdr(it.next())) {
                it.remove()
                return true
            }
        }
        return videoQueue.poll() != null
    }

    private fun recordVideoDrop() {
        videoDropCount++
        totalVideoDrops.incrementAndGet()
        val now = System.currentTimeMillis()
        if (now - videoDropLogAtMs < DROP_LOG_INTERVAL_MS) return
        videoDropLogAtMs = now
        val n = videoDropCount
        videoDropCount = 0
        Log.d("OFFTRACE", "QUEUE: ${MeshFrame.hex(nodeId)} dropped $n video frames depth=${videoQueue.size}")
    }

    // ── OCP PHASE 0: LAT line accessors ─────────────────────────────────────
    // Read-only snapshots for OfflineMediaTransport's per-peer, 1/sec-
    // throttled LAT log — never used for control flow, so no synchronization
    // beyond the underlying concurrent collections'/atomics' own.
    /** Non-destructive (ArrayBlockingQueue.toList() copies, doesn't drain) —
     *  used by RoutingTableTest to verify IDR-preserving eviction order. */
    fun videoQueueSnapshot(): List<ByteArray> = videoQueue.toList()
    fun videoQueueDepth(): Int = videoQueue.size
    fun audioQueueDepth(): Int = audioQueue.size
    fun videoQueueCapacity(): Int = VIDEO_QUEUE_CAPACITY
    fun audioQueueCapacity(): Int = AUDIO_QUEUE_CAPACITY
    fun totalVideoDropsCount(): Long = totalVideoDrops.get()
    fun totalAudioDropsCount(): Long = totalAudioDrops.get()

    private fun trackBackpressure() {
        val now = System.currentTimeMillis()
        if (videoQueue.size > VIDEO_HIGH_WATER_MARK) {
            if (overMarkSinceMs == 0L) {
                overMarkSinceMs = now
            } else if (now - overMarkSinceMs >= SUSTAINED_BACKPRESSURE_MS) {
                overMarkSinceMs = now // re-arm — fires again if pressure keeps not clearing
                onSustainedBackpressure?.invoke(this)
            }
        } else if (overMarkSinceMs != 0L) {
            overMarkSinceMs = 0L
            onBackpressureCleared?.invoke(this)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        try { dataOut.close() } catch (_: Exception) {}
        try { dataIn.close() } catch (_: Exception) {}
        controlQueue.clear()
        audioQueue.clear()
        videoQueue.clear()
        writerThread?.interrupt()
    }
}

/**
 * PHASE 3: the GO's (or a client's) authoritative view of who else is reachable.
 * Keyed by resolved nodeId only — a link still waiting on its peer's HELLO
 * (nodeId == PENDING_ID) is not in this map yet; see OfflineMediaTransport.resolveLink.
 * On the GO this holds one entry per OTHER group member (self is never in the map,
 * see [roster]); on a client, at most one entry — the GO.
 */
class RoutingTable(private val localNodeId: Long, private val localName: String) {
    data class Member(val nodeId: Long, val name: String)

    private val peers = ConcurrentHashMap<Long, PeerLink>()
    // PHASE 7A: verified Ed25519 pubkeys, keyed by nodeId — populated only after
    // MeshSigner independently confirms SHA-256(pubkey)[0..8] == that nodeId (see
    // MeshSigner.handleVerifiedHello/handleVerifiedRoster), never from an
    // unverified claim. In-memory only here; MeshSigner owns persisting this
    // "alongside the ledger" so it survives a restart — this class stays a pure,
    // Context-free routing structure, same as before.
    private val verifiedPubkeys = ConcurrentHashMap<Long, ByteArray>()

    fun put(nodeId: Long, link: PeerLink) { peers[nodeId] = link }

    fun remove(nodeId: Long): PeerLink? = peers.remove(nodeId)

    /** Direct unicast lookup only — BROADCAST_ID is never a key in this table; callers
     *  fan out via [all] instead (see OfflineMediaTransport's forwarding path). */
    fun get(nodeId: Long): PeerLink? = peers[nodeId]

    fun all(): Collection<PeerLink> = peers.values

    fun allExcept(nodeId: Long): List<PeerLink> = peers.values.filter { it.nodeId != nodeId }

    /** Group size = every other member currently registered, plus this device itself. */
    fun size(): Int = peers.size + 1

    fun roster(): List<Member> =
        listOf(Member(localNodeId, localName)) + peers.values.map { Member(it.nodeId, it.name) }

    fun clear() {
        peers.values.forEach { it.close() }
        peers.clear()
    }

    // ── PHASE 7A: verified pubkey cache ─────────────────────────────────────────

    fun putVerifiedPubkey(nodeId: Long, pubkey: ByteArray) {
        verifiedPubkeys[nodeId] = pubkey
    }

    fun pubkeyFor(nodeId: Long): ByteArray? = verifiedPubkeys[nodeId]

    /** Seeds a set of already-verified pubkeys (e.g. loaded from disk at session
     *  start, or received from a trusted roster relay) without re-deriving
     *  anything — the caller is responsible for having verified each one. */
    fun seedVerifiedPubkeys(entries: Map<Long, ByteArray>) {
        verifiedPubkeys.putAll(entries)
    }

    fun allVerifiedPubkeys(): Map<Long, ByteArray> = verifiedPubkeys.toMap()
}
