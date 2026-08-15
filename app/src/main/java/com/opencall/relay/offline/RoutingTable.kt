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
 * own frames and anything the GO forwards) goes through [enqueue]: a bounded queue
 * drained by this link's own writer thread, so one slow/dead peer's socket can never
 * block progress on anyone else's — the queue drops the OLDEST frame on overflow
 * rather than the producer blocking or the newest frame being rejected, since for both
 * media and control traffic here a stale frame is worse than a missing one.
 */
class PeerLink(
    @Volatile var nodeId: Long,
    @Volatile var name: String,
    private val dataOut: DataOutputStream,
    val dataIn: DataInputStream
) {
    companion object {
        private const val SEND_QUEUE_CAPACITY = 64
        private const val DROP_LOG_INTERVAL = 100
        private val POISON_PILL = ByteArray(0)
    }

    private val sendQueue = ArrayBlockingQueue<ByteArray>(SEND_QUEUE_CAPACITY)
    @Volatile private var writerThread: Thread? = null
    @Volatile private var closed = false
    private var dropCount = 0

    /** Fired at most once, off the writer thread, the moment a write to this peer fails
     *  (broken pipe / reset). The caller (RoutingTable owner) should drop this link from
     *  the roster and rebroadcast — see OfflineMediaTransport.handlePeerDisconnected. */
    var onDead: ((PeerLink) -> Unit)? = null

    fun startWriter() {
        val t = Thread({
            try {
                while (true) {
                    val frame = sendQueue.take()
                    if (frame === POISON_PILL) break
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
        writerThread = t
        t.start()
    }

    /** Non-blocking hand-off — never called from this link's own writer thread, always
     *  from whichever thread produced the frame (a read loop doing forwarding, or this
     *  node's own camera/mic/chat senders). */
    fun enqueue(frame: ByteArray) {
        if (closed) return
        if (!sendQueue.offer(frame)) {
            sendQueue.poll()
            sendQueue.offer(frame)
            dropCount++
            if (dropCount % DROP_LOG_INTERVAL == 0) {
                Log.w("OFFTRACE", "MESH: send queue to peer=${MeshFrame.hex(nodeId)} dropped $dropCount frames (overflow)")
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        try { dataOut.close() } catch (_: Exception) {}
        try { dataIn.close() } catch (_: Exception) {}
        sendQueue.clear()
        sendQueue.offer(POISON_PILL)
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
