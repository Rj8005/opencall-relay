package com.opencall.relay.offline

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * PHASE 7A: per-frame Ed25519 signing and verification — the mechanism that
 * makes every permission added in Phase 7B-7F actually enforceable instead of
 * decorative. See OfflineIdentity.kt for key generation/storage; this file
 * owns the WIRE PROTOCOL layered on top of it.
 *
 * SIGNED MATERIAL — the exact bytes covered, in order:
 *   srcId (8B) || dstId (8B) || type (1B) || timestampUnix (4B) || payload (nB)
 * The timestamp is INSIDE the signed material specifically so a captured frame
 * cannot be replayed later with a fresh timestamp slapped on — the signature
 * only ever covers the ORIGINAL timestamp it was created with. See [signIfNeeded]/
 * [verifyIncoming] for where each half of this actually happens.
 *
 * WIRE FORMAT for a signed type: payload' = payload || timestampUnix (4B) ||
 * signature (64B) — appended, never interleaved — so every existing payload
 * codec (MeshLocation, encodeCam, raw chat bytes, ...) is read exactly as
 * before; only [OfflineMediaTransport.writeFrame]/[routeFrame] ever see the
 * trailer.
 *
 * SIGNED vs NOT SIGNED: [isSignedType] is a COMPLEMENT (everything except
 * 1/2/3), not an enumerated allowlist — types 1 (codec config), 2 (video), and
 * 3 (audio) run at 30fps / a 20ms cadence; Ed25519 verification at that rate
 * is not viable on a phone CPU without real cost, and media CONTENT carries no
 * permission of its own (a bad actor forging a video FRAME can't do anything a
 * permission system needs to stop — forging a CALL_INVITE or an admin command
 * can). This exemption is a documented decision made once, here, not an
 * oversight silently repeated at every call site. Every other type — including
 * ones added after this file was written — is signed by default.
 *
 * REPLAY PROTECTION, two layers:
 *   1. A [REPLAY_WINDOW_SEC] window around local time — rejects anything too
 *      old OR too far in the future. Cross-device clock skew is real (a prior
 *      capture on this project showed "SOS: recv ... age=-1s" from ordinary
 *      clock drift between two phones) — the window is deliberately generous
 *      (120s) rather than assuming synced clocks, and checks BOTH directions
 *      (abs(skew), not just "too old") for exactly that reason.
 *   2. A bounded seen-signature cache rejects an EXACT repeat within that
 *      window (a byte-for-byte captured-and-replayed frame, not just an old
 *      one) — two different legitimate frames from the same sender always
 *      differ in payload/timestamp and therefore in signature, so this never
 *      collides with normal traffic.
 *
 * STRICT_SIGNING: see the constant's own doc — default TRUE. Flipping it to
 * false to test against an unsigned/mixed-version peer during development
 * makes every Phase 7 permission bypassable; this is stated here, not just in
 * a commit message, so it can't be missed by anyone reading this file later.
 */
class MeshSigner(
    private val context: Context,
    private val localNodeId: Long,
    private val routingTable: RoutingTable,
    /** Re-delivers a frame that was queued pending an unknown pubkey, once that
     *  pubkey resolves — bound by the owner to its own routeFrame, so a retried
     *  frame goes through the EXACT same path (dedupe, verification, dispatch)
     *  a fresh arrival would. */
    private val retryFrame: (MeshFrame.Header, ByteArray, PeerLink) -> Unit
) {
    sealed class VerifyResult {
        /** [innerPayload] has the timestamp+signature trailer already stripped —
         *  this is what dispatchLocal should see. Forwarding must use the
         *  ORIGINAL untouched payload (with trailer intact), never this one —
         *  see OfflineMediaTransport.routeFrame's wiring. */
        data class Accepted(val innerPayload: ByteArray) : VerifyResult()
        object Reject : VerifyResult()
        /** Pubkey for this srcId isn't known yet — the frame has been queued
         *  and will be retried via [retryFrame] if the pubkey resolves in time.
         *  The caller should just return without processing or forwarding. */
        object Queued : VerifyResult()
    }

    companion object {
        /** Shipping with this false makes every Phase 7 permission bypassable —
         *  an unsigned control frame (forged admin command, forged CALL_INVITE,
         *  forged name) is accepted instead of dropped. TRUE is the only
         *  correct value outside of deliberately testing against an
         *  old/mixed-version peer during development. */
        const val STRICT_SIGNING = true

        const val REPLAY_WINDOW_SEC = 120L
        private const val TIMESTAMP_BYTES = 4
        const val SIGNATURE_TRAILER_BYTES = TIMESTAMP_BYTES + OfflineIdentity.SIGNATURE_BYTES // 68

        private const val SEEN_SIG_CAPACITY = 512
        private const val PENDING_QUEUE_CAPACITY = 32
        private const val PENDING_TIMEOUT_MS = 3_000L
        private const val LEDGER_DIR_NAME = "ledger"
        private const val PUBKEY_FILE_NAME = "pubkeys.json"

        private val UNSIGNED_TYPES: Set<Byte> = setOf(1, 2, 3)

        /** Complement, not an allowlist — see class doc. */
        fun isSignedType(type: Byte): Boolean = type !in UNSIGNED_TYPES

        // ── Pure logic, extracted for unit testing without an Android Context ──
        // (see MeshSignerTest) — none of these touch `this`, a Context, or the
        // routing table, so they were always effectively static; the only
        // change here is making that explicit.

        /** Exact bytes covered by a signature — see class doc for why timestamp
         *  is INSIDE this, not appended alongside it unsigned. */
        fun buildSignedMaterial(srcId: Long, dstId: Long, type: Byte, timestamp: Long, payload: ByteArray): ByteArray {
            val buf = ByteBuffer.allocate(8 + 8 + 1 + 4 + payload.size)
            buf.putLong(srcId)
            buf.putLong(dstId)
            buf.put(type)
            buf.putInt((timestamp and 0xFFFFFFFFL).toInt())
            buf.put(payload)
            return buf.array()
        }

        /** SHA-256(pubkey)[0..8] as a big-endian Long — the same self-certifying
         *  formula as [OfflineIdentity]'s nodeId derivation, applied to an
         *  arbitrary (not necessarily local) pubkey. */
        fun deriveNodeId(pubkey: ByteArray): Long {
            val hash = MessageDigest.getInstance("SHA-256").digest(pubkey)
            return ByteBuffer.wrap(hash, 0, 8).long
        }

        /** Checks BOTH directions of skew (too old OR too far in the future) —
         *  see class doc's REPLAY PROTECTION section for why a generous
         *  symmetric window is used instead of assuming synced clocks. */
        fun isWithinReplayWindow(nowSec: Long, timestamp: Long): Boolean =
            abs(nowSec - timestamp) <= REPLAY_WINDOW_SEC
    }

    private val ledgerDir = File(context.filesDir, LEDGER_DIR_NAME).apply { mkdirs() }
    private val pubkeyFile = File(ledgerDir, PUBKEY_FILE_NAME)
    private val ioThread = HandlerThread("MeshSignerIO").apply { start() }
    private val ioHandler = Handler(ioThread.looper)

    private val seenLock = Any()
    private val seenSignatures = object : LinkedHashMap<String, Long>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > SEEN_SIG_CAPACITY
    }

    private data class Pending(val header: MeshFrame.Header, val payload: ByteArray, val fromLink: PeerLink, val queuedAtMs: Long)
    private val pending = ConcurrentHashMap<Long, MutableList<Pending>>()

    // ── Outbound ─────────────────────────────────────────────────────────────

    /** Appends timestamp+signature for a signed type; returns [payload]
     *  unchanged for 1/2/3. Called from OfflineMediaTransport.writeFrame/
     *  writeRawFrame — every outbound frame passes through here regardless of
     *  call site, so there's exactly one place this can be gotten wrong. */
    fun signIfNeeded(dst: Long, type: Byte, payload: ByteArray): ByteArray {
        if (!isSignedType(type)) return payload
        val timestamp = System.currentTimeMillis() / 1000L
        val signedMaterial = buildSignedMaterial(localNodeId, dst, type, timestamp, payload)
        val sig = OfflineIdentity.sign(context, signedMaterial)
        val buf = ByteBuffer.allocate(payload.size + SIGNATURE_TRAILER_BYTES)
        buf.put(payload)
        buf.putInt((timestamp and 0xFFFFFFFFL).toInt())
        buf.put(sig)
        return buf.array()
    }

    // ── Inbound ──────────────────────────────────────────────────────────────

    /** Called at the TOP of routeFrame, before dedupe, before dispatchLocal,
     *  before any forwarding. 1/2/3 bypass entirely (see [isSignedType]). */
    fun verifyIncoming(header: MeshFrame.Header, payload: ByteArray, fromLink: PeerLink): VerifyResult {
        if (!isSignedType(header.type)) return VerifyResult.Accepted(payload)

        if (payload.size < SIGNATURE_TRAILER_BYTES) {
            return if (STRICT_SIGNING) {
                Log.w(
                    "OFFTRACE",
                    "SIG: verify FAILED type=${header.type} from=${MeshFrame.hex(header.srcId)} reason=no_signature_trailer"
                )
                VerifyResult.Reject
            } else {
                Log.d("OFFTRACE", "SIG: unsigned control type=${header.type} — STRICT_SIGNING=$STRICT_SIGNING")
                VerifyResult.Accepted(payload)
            }
        }

        val splitAt = payload.size - SIGNATURE_TRAILER_BYTES
        val inner = payload.copyOfRange(0, splitAt)
        val trailer = ByteBuffer.wrap(payload, splitAt, SIGNATURE_TRAILER_BYTES)
        val timestamp = trailer.int.toLong() and 0xFFFFFFFFL
        val sig = ByteArray(OfflineIdentity.SIGNATURE_BYTES)
        trailer.get(sig)

        val nowSec = System.currentTimeMillis() / 1000L
        val skew = nowSec - timestamp
        if (!isWithinReplayWindow(nowSec, timestamp)) {
            Log.d("OFFTRACE", "SIG: replay rejected type=${header.type} from=${MeshFrame.hex(header.srcId)} skew=${skew}s")
            return VerifyResult.Reject
        }

        val sigKey = Base64.encodeToString(sig, Base64.NO_WRAP)
        synchronized(seenLock) {
            if (seenSignatures.containsKey(sigKey)) {
                Log.d("OFFTRACE", "SIG: replay rejected type=${header.type} from=${MeshFrame.hex(header.srcId)} skew=${skew}s")
                return VerifyResult.Reject
            }
            seenSignatures[sigKey] = System.currentTimeMillis()
        }

        val pubkey = pubkeyForVerification(header.srcId)
        if (pubkey == null) {
            queuePending(header, payload, fromLink)
            Log.d("OFFTRACE", "SIG: pubkey unknown for ${MeshFrame.hex(header.srcId)} — queued pending HELLO")
            return VerifyResult.Queued
        }

        val signedMaterial = buildSignedMaterial(header.srcId, header.dstId, header.type, timestamp, inner)
        return if (OfflineIdentity.verify(pubkey, signedMaterial, sig)) {
            Log.d("OFFTRACE", "SIG: verify ok type=${header.type} from=${MeshFrame.hex(header.srcId)}")
            VerifyResult.Accepted(inner)
        } else {
            Log.w("OFFTRACE", "SIG: verify FAILED type=${header.type} from=${MeshFrame.hex(header.srcId)} reason=bad_signature")
            VerifyResult.Reject
        }
    }

    data class DecodedHello(val nodeId: Long, val protocolVersion: Byte, val pubkey: ByteArray, val name: String)

    /** HELLO needs its OWN verification path, not [verifyIncoming]'s — that
     *  path looks up the sender's pubkey in [routingTable], which is exactly
     *  what HELLO itself is the FIRST delivery of; there is nothing to look up
     *  yet. Instead, this verifies the signature against the pubkey EMBEDDED
     *  in this same payload, then separately self-certifies that embedded
     *  pubkey against the claimed nodeId (SHA-256(pubkey)[0..8] ==
     *  header.srcId == the nodeId embedded in the payload). This is NOT
     *  circular: an attacker can embed any pubkey they like, but they can only
     *  produce a VALID signature under it if they hold the matching private
     *  key, and they can only make an arbitrary pubkey hash to a specific
     *  PRE-EXISTING nodeId by breaking SHA-256 preimage resistance — so a
     *  passing result here is only reachable by whoever actually holds that
     *  nodeId's real private key. Called directly from the read loop's HELLO
     *  intercept, which runs before routeFrame (see OfflineMediaTransport —
     *  HELLO never reaches routeFrame at all). */
    fun verifyHello(header: MeshFrame.Header, payload: ByteArray): DecodedHello? {
        val hasTrailer = payload.size >= SIGNATURE_TRAILER_BYTES
        val inner: ByteArray
        val timestamp: Long
        val sig: ByteArray?
        if (hasTrailer) {
            val splitAt = payload.size - SIGNATURE_TRAILER_BYTES
            inner = payload.copyOfRange(0, splitAt)
            val trailer = ByteBuffer.wrap(payload, splitAt, SIGNATURE_TRAILER_BYTES)
            timestamp = trailer.int.toLong() and 0xFFFFFFFFL
            sig = ByteArray(OfflineIdentity.SIGNATURE_BYTES).also { trailer.get(it) }
        } else {
            if (STRICT_SIGNING) {
                Log.w("OFFTRACE", "SIG: hello REJECTED ${MeshFrame.hex(header.srcId)} — no signature trailer")
                return null
            }
            Log.d("OFFTRACE", "SIG: unsigned control type=${header.type} — STRICT_SIGNING=$STRICT_SIGNING")
            inner = payload
            timestamp = 0L
            sig = null
        }

        val decoded = decodeHelloInner(inner) ?: run {
            Log.w("OFFTRACE", "SIG: hello REJECTED ${MeshFrame.hex(header.srcId)} — malformed payload")
            return null
        }
        if (decoded.nodeId != header.srcId) {
            Log.w("OFFTRACE", "SIG: hello REJECTED ${MeshFrame.hex(header.srcId)} — envelope srcId does not match embedded nodeId")
            return null
        }

        // The nodeId<->pubkey self-certification check (inside
        // recordVerifiedPubkey, below) always runs regardless of signing mode —
        // it costs nothing and catches a mismatched claim outright. Only the
        // Ed25519 signature check itself is gated by STRICT_SIGNING.
        if (sig != null) {
            val nowSec = System.currentTimeMillis() / 1000L
            val skew = nowSec - timestamp
            if (!isWithinReplayWindow(nowSec, timestamp)) {
                Log.d("OFFTRACE", "SIG: replay rejected type=${header.type} from=${MeshFrame.hex(header.srcId)} skew=${skew}s")
                return null
            }
            val sigKey = Base64.encodeToString(sig, Base64.NO_WRAP)
            synchronized(seenLock) {
                if (seenSignatures.containsKey(sigKey)) {
                    Log.d("OFFTRACE", "SIG: replay rejected type=${header.type} from=${MeshFrame.hex(header.srcId)} skew=${skew}s")
                    return null
                }
                seenSignatures[sigKey] = System.currentTimeMillis()
            }
            val signedMaterial = buildSignedMaterial(header.srcId, header.dstId, header.type, timestamp, inner)
            if (!OfflineIdentity.verify(decoded.pubkey, signedMaterial, sig)) {
                Log.w("OFFTRACE", "SIG: verify FAILED type=${header.type} from=${MeshFrame.hex(header.srcId)} reason=bad_signature")
                return null
            }
        }

        if (!recordVerifiedPubkey(decoded.nodeId, decoded.pubkey, decoded.name)) return null
        return decoded
    }

    private fun decodeHelloInner(payload: ByteArray): DecodedHello? {
        if (payload.size < 8 + 1 + 32 + 1) return null
        return try {
            val buf = ByteBuffer.wrap(payload)
            val nodeId = buf.long
            val version = buf.get()
            val pubkey = ByteArray(32)
            buf.get(pubkey)
            val nameLen = buf.get().toInt() and 0xFF
            val nameBytes = ByteArray(minOf(nameLen, buf.remaining()))
            buf.get(nameBytes)
            DecodedHello(nodeId, version, pubkey, String(nameBytes, Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    private fun pubkeyForVerification(srcId: Long): ByteArray? =
        if (srcId == localNodeId) OfflineIdentity.publicKeyBytes(context) else routingTable.pubkeyFor(srcId)

    // ── Pending-pubkey queue ─────────────────────────────────────────────────

    private fun queuePending(header: MeshFrame.Header, payload: ByteArray, fromLink: PeerLink) {
        val list = pending.getOrPut(header.srcId) { java.util.Collections.synchronizedList(mutableListOf()) }
        synchronized(list) {
            if (list.size >= PENDING_QUEUE_CAPACITY) list.removeAt(0)
            list.add(Pending(header, payload, fromLink, System.currentTimeMillis()))
        }
    }

    /** Call once a pubkey for [nodeId] becomes known (HELLO or roster) — retries
     *  anything queued for it that hasn't already exceeded the short timeout. */
    private fun drainPending(nodeId: Long) {
        val list = pending.remove(nodeId) ?: return
        val now = System.currentTimeMillis()
        val snapshot = synchronized(list) { list.toList() }
        snapshot.forEach { p ->
            if (now - p.queuedAtMs <= PENDING_TIMEOUT_MS) {
                retryFrame(p.header, p.payload, p.fromLink)
            }
        }
    }

    // ── Pubkey verification + persistence ───────────────────────────────────

    /** Self-authenticating check: [pubkey] is only accepted for [claimedNodeId]
     *  if SHA-256(pubkey)[0..8] actually equals it — "a nodeId IS its key," no
     *  PKI, no CA, no trust-on-first-use question. Used identically whether
     *  [pubkey] arrived via a direct HELLO or a GO-relayed ROSTER entry (see
     *  OfflineMediaTransport's wiring) — the check is the same regardless of
     *  path, since a valid pubkey for a given nodeId is unique either way. */
    fun recordVerifiedPubkey(claimedNodeId: Long, pubkey: ByteArray, displayNameForLog: String? = null): Boolean {
        if (pubkey.size != 32) {
            Log.w("OFFTRACE", "SIG: hello REJECTED ${MeshFrame.hex(claimedNodeId)} — malformed pubkey")
            return false
        }
        if (deriveNodeId(pubkey) != claimedNodeId) {
            Log.w("OFFTRACE", "SIG: hello REJECTED ${MeshFrame.hex(claimedNodeId)} — nodeId does not match pubkey")
            return false
        }
        routingTable.putVerifiedPubkey(claimedNodeId, pubkey)
        if (displayNameForLog != null) {
            Log.d("OFFTRACE", "SIG: hello verified ${MeshFrame.hex(claimedNodeId)} name=\"$displayNameForLog\" pubkey ok")
        }
        persistPubkeysAsync()
        drainPending(claimedNodeId)
        return true
    }

    /** Loads whatever was persisted last session — re-verifies every entry on
     *  load rather than trusting the file blindly (same "never trust a
     *  persisted file's integrity implicitly" posture as MeshLedger/MeshCarrier). */
    fun loadPersistedPubkeys(): Map<Long, ByteArray> {
        if (!pubkeyFile.exists()) return emptyMap()
        return try {
            val json = JSONObject(pubkeyFile.readText(Charsets.UTF_8))
            val arr = json.optJSONArray("entries") ?: return emptyMap()
            val result = mutableMapOf<Long, ByteArray>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val nodeId = o.getLong("nodeId")
                val pubkey = Base64.decode(o.getString("pubkey"), Base64.NO_WRAP)
                if (pubkey.size == 32 && deriveNodeId(pubkey) == nodeId) {
                    result[nodeId] = pubkey
                }
            }
            result
        } catch (e: Exception) {
            Log.w("OFFTRACE", "SIG: pubkey file discarded (malformed): ${e.message}")
            emptyMap()
        }
    }

    private fun persistPubkeysAsync() {
        ioHandler.post {
            try {
                val arr = JSONArray()
                routingTable.allVerifiedPubkeys().forEach { (id, key) ->
                    arr.put(
                        JSONObject().apply {
                            put("nodeId", id)
                            put("pubkey", Base64.encodeToString(key, Base64.NO_WRAP))
                        }
                    )
                }
                writeAtomic(pubkeyFile, JSONObject().apply { put("entries", arr) }.toString())
            } catch (e: Exception) {
                Log.w("OFFTRACE", "SIG: pubkey persist failed: ${e.message}")
            }
        }
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
                Log.w("OFFTRACE", "SIG: atomic rename failed for ${target.name}")
            }
        } catch (e: Exception) {
            Log.w("OFFTRACE", "SIG: write failed for ${target.name}: ${e.message}")
            try { tmp.delete() } catch (_: Exception) {}
        }
    }

    fun shutdown() {
        ioThread.quitSafely()
    }
}
