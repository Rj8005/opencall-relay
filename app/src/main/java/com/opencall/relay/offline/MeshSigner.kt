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
    private val retryFrame: (MeshFrame.Header, ByteArray, PeerLink) -> Unit,
    /** PART A: re-delivers a carried (store-and-forward) frame that was
     *  queued pending an unknown originId pubkey — the carried-path
     *  equivalent of [retryFrame], wired by the owner to its own
     *  dispatchCarriedInner so a retried carried frame goes through the
     *  EXACT same verify-then-dispatch path a fresh delivery would. See
     *  [verifyCarried]/[drainPendingCarried]. */
    private val retryCarried: (originId: Long, finalDstId: Long, innerType: Byte, inner: ByteArray, carrierId: Long, hopCount: Int) -> Unit
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

    /** PART A: result of [verifyCarried] — same three-way shape as
     *  [VerifyResult], but [Accepted] also carries the frame's SIGNED wire
     *  timestamp (A3), the one age signal a store-and-forward mule cannot
     *  forge, which the caller uses IN PLACE OF the inner payload's own
     *  embedded timestamp for any freshness/alarmability decision. */
    sealed class CarriedVerifyResult {
        data class Accepted(val innerPayload: ByteArray, val signedTimestampSec: Long) : CarriedVerifyResult()
        object Reject : CarriedVerifyResult()
        object Queued : CarriedVerifyResult()
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

        // OCP PHASE 3.1/3.2: TYPE_FRAME_TS(37)/TYPE_AUDIO_TS(38) carry the
        // exact same raw, unsigned media bytes as legacy TYPE_FRAME(2)/
        // TYPE_AUDIO(3) — only an 8B capture-timestamp prefix added, no
        // different in signing cost/semantics — so they join the same
        // unsigned set, purely additive (types 1-35 and this set's original
        // three members are unchanged). OCP PHASE 5.1: TYPE_CONFIG_LOW(39)/
        // TYPE_FRAME_LOW(40) are the low-layer video stream's csd/frame
        // types — same raw, high-frequency media shape as TYPE_CONFIG(1)/
        // TYPE_FRAME(2), so they join this set for the identical reason.
        private val UNSIGNED_TYPES: Set<Byte> = setOf(1, 2, 3, 37, 38, 39, 40)

        /** Complement, not an allowlist — see class doc. */
        fun isSignedType(type: Byte): Boolean = type !in UNSIGNED_TYPES

        /** Pure HELLO payload parse — moved here (from a private instance
         *  method) so OCP PHASE 3/G6's additive capability byte is directly
         *  unit-testable (see MeshSignerTest). [payload] is the INNER hello
         *  bytes (trailer already stripped by the caller, see verifyHello). */
        fun decodeHelloInner(payload: ByteArray): DecodedHello? {
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
                // OCP PHASE 3/G6: additive capability byte — an OLDER peer's
                // HELLO simply has no bytes left here (buf.remaining()==0),
                // which reads as capabilities=0 (nothing advertised), never a
                // parse failure. A NEWER peer talking to an OLD build of this
                // same function is the mirror case: this exact code already
                // stops reading right after nameBytes and never even looks at
                // whatever bytes follow — the tail is silently ignored, which
                // is G6's whole requirement, proven by this function's own
                // control flow (there is no "reject extra trailing bytes"
                // check anywhere above or below this line).
                val capabilities = if (buf.remaining() >= 1) buf.get().toInt() and 0xFF else 0
                DecodedHello(nodeId, version, pubkey, String(nameBytes, Charsets.UTF_8), capabilities)
            } catch (e: Exception) {
                null
            }
        }

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

        /** PART A: a carried frame's timestamp+signature trailer, split out —
         *  same layout as the live path's [SIGNATURE_TRAILER_BYTES], but a
         *  pure/Context-free function (like [isWithinReplayWindow]) so the
         *  carried-verification core is directly unit-testable. Returns null
         *  for anything shorter than the trailer — A5: a trailer-less carried
         *  payload is NEVER accepted, not even under STRICT_SIGNING=false (see
         *  [verifyCarried], which never consults that flag). */
        data class SplitCarriedTrailer(val innerPayload: ByteArray, val timestampSec: Long, val signature: ByteArray)

        fun splitCarriedTrailer(payload: ByteArray): SplitCarriedTrailer? {
            if (payload.size < SIGNATURE_TRAILER_BYTES) return null
            val splitAt = payload.size - SIGNATURE_TRAILER_BYTES
            val inner = payload.copyOfRange(0, splitAt)
            val trailer = ByteBuffer.wrap(payload, splitAt, SIGNATURE_TRAILER_BYTES)
            val timestamp = trailer.int.toLong() and 0xFFFFFFFFL
            val sig = ByteArray(OfflineIdentity.SIGNATURE_BYTES)
            trailer.get(sig)
            return SplitCarriedTrailer(inner, timestamp, sig)
        }

        /** PART A: verifies a carried frame's signature against an EXPLICIT
         *  [pubkey] — pure, no replay-window check (A3: a carried SOS is
         *  legitimately hours old), no Context/dedupe/queueing involvement.
         *  [originId]/[finalDstId]/[innerType] rebuild the EXACT signed
         *  material the true originator produced (MeshCarrier's own envelope
         *  fields — never this device's local nodeId or a synthetic unicast
         *  dstId a carried replay might otherwise be dispatched under). */
        fun verifyCarriedTrailer(
            originId: Long,
            finalDstId: Long,
            innerType: Byte,
            split: SplitCarriedTrailer,
            pubkey: ByteArray
        ): Boolean {
            val signedMaterial = buildSignedMaterial(originId, finalDstId, innerType, split.timestampSec, split.innerPayload)
            return OfflineIdentity.verify(pubkey, signedMaterial, split.signature)
        }

        /** FIX 3: pure encode/decode for pubkeys.json's exact on-disk shape
         *  — {"entries":[{"nodeId":Long,"pubkey":base64},...]} — extracted
         *  so MeshSignerTest can round-trip it without a Context (no File
         *  I/O, no HandlerThread). Uses java.util.Base64 (available since
         *  API 26, this project's exact minSdk) rather than
         *  android.util.Base64 specifically so these are safe to call from
         *  a plain JVM test — android.util.Base64 is an Android-framework
         *  stub that throws "not mocked" outside a real device/Robolectric
         *  (this project has neither). persistPubkeysAsync/
         *  loadPersistedPubkeys below now delegate to these rather than
         *  duplicating the format. */
        fun encodePubkeysJson(entries: Map<Long, ByteArray>): String {
            val arr = JSONArray()
            entries.forEach { (id, key) ->
                arr.put(
                    JSONObject().apply {
                        put("nodeId", id)
                        put("pubkey", java.util.Base64.getEncoder().encodeToString(key))
                    }
                )
            }
            return JSONObject().apply { put("entries", arr) }.toString()
        }

        /** Returns every STRUCTURALLY valid entry (right JSON shape, pubkey
         *  base64-decodes) — does NOT re-verify pubkey.size==32 or
         *  deriveNodeId(pubkey)==nodeId (that's loadPersistedPubkeys' own
         *  job, same "never trust a persisted file's integrity implicitly"
         *  posture as the rest of this class); a single malformed entry is
         *  skipped, not fatal to the whole file. Empty map (never throws)
         *  for anything from a totally malformed/foreign JSON string. */
        fun decodePubkeysJson(raw: String): Map<Long, ByteArray> {
            return try {
                val json = JSONObject(raw)
                val arr = json.optJSONArray("entries") ?: return emptyMap()
                val result = mutableMapOf<Long, ByteArray>()
                for (i in 0 until arr.length()) {
                    try {
                        val o = arr.getJSONObject(i)
                        val nodeId = o.getLong("nodeId")
                        val pubkey = java.util.Base64.getDecoder().decode(o.getString("pubkey"))
                        result[nodeId] = pubkey
                    } catch (e: Exception) {
                        // one malformed entry does not discard the rest of the file
                    }
                }
                result
            } catch (e: Exception) {
                emptyMap()
            }
        }
    }

    private val ledgerDir = File(context.filesDir, LEDGER_DIR_NAME).apply { mkdirs() }
    private val pubkeyFile = File(ledgerDir, PUBKEY_FILE_NAME)
    private val ioThread = HandlerThread("MeshSignerIO").apply { start() }
    private val ioHandler = Handler(ioThread.looper)

    // PHASE 8 STEP 9: "SIG: verify ok" used to fire unconditionally on every
    // signed frame — with a 300ms VAD heartbeat per client this scales with N
    // the same way the old unthrottled per-frame relay-forward log line did
    // (see OfflineMediaTransport.logIfRelayed's doc). Only the SUCCESS case
    // is throttled; verify FAILED / replay rejected / pubkey unknown stay
    // unthrottled since those are rare and security-relevant.
    private val verifyOkCount = ConcurrentHashMap<Long, Int>()
    private val verifyOkLogAtMs = ConcurrentHashMap<Long, Long>()

    private fun logVerifyOk(header: MeshFrame.Header) {
        val key = (header.srcId * 1_000_003L) xor header.type.toLong()
        val n = (verifyOkCount[key] ?: 0) + 1
        verifyOkCount[key] = n
        val now = System.currentTimeMillis()
        val last = verifyOkLogAtMs[key] ?: 0L
        if (now - last < 1_000L) return
        verifyOkLogAtMs[key] = now
        verifyOkCount[key] = 0
        Log.d("OFFTRACE", "SIG: verify ok type=${header.type} from=${MeshFrame.hex(header.srcId)} x$n")
    }

    private val seenLock = Any()
    private val seenSignatures = object : LinkedHashMap<String, Long>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > SEEN_SIG_CAPACITY
    }

    private data class Pending(val header: MeshFrame.Header, val payload: ByteArray, val fromLink: PeerLink, val queuedAtMs: Long)
    private val pending = ConcurrentHashMap<Long, MutableList<Pending>>()

    // PART A: separate pending queue for carried frames — see [verifyCarried]/
    // [retryCarried]. Kept distinct from [pending] since a carried frame has
    // no live PeerLink to replay against.
    private data class PendingCarried(
        val originId: Long,
        val finalDstId: Long,
        val innerType: Byte,
        val inner: ByteArray,
        val carrierId: Long,
        val hopCount: Int,
        val queuedAtMs: Long
    )
    private val pendingCarried = ConcurrentHashMap<Long, MutableList<PendingCarried>>()

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
            logVerifyOk(header)
            VerifyResult.Accepted(inner)
        } else {
            Log.w("OFFTRACE", "SIG: verify FAILED type=${header.type} from=${MeshFrame.hex(header.srcId)} reason=bad_signature")
            VerifyResult.Reject
        }
    }

    /** PART A: verification for a frame delivered via MeshCarrier's
     *  store-and-forward — NOT a frame that just arrived off a real link
     *  (see OfflineMediaTransport.dispatchCarriedInner's call site). Differs
     *  from [verifyIncoming] in three deliberate ways:
     *   1. NO replay-window check (A3) — a carried frame is legitimately
     *      hours old; [REPLAY_WINDOW_SEC]=120s would reject every valid one.
     *      [CarriedVerifyResult.Accepted.signedTimestampSec] is what the
     *      caller uses instead, as an age the attacker cannot forge.
     *   2. A missing/short trailer is ALWAYS rejected (A5) — never accepted
     *      under STRICT_SIGNING=false; that flag is a live-path dev-testing
     *      escape hatch only, never consulted here. A spoofable siren is
     *      worse than a missed one from a stale build.
     *   3. Pubkey lookup runs BEFORE the sig-dedupe record (opposite order
     *      from [verifyIncoming]) so a frame queued pending an unknown
     *      pubkey can actually succeed once retried via
     *      [drainPendingCarried] — recording the signature as "seen" before
     *      the pubkey resolves would make its own retry look like a replay
     *      of itself. */
    fun verifyCarried(originId: Long, finalDstId: Long, innerType: Byte, inner: ByteArray, carrierId: Long, hopCount: Int): CarriedVerifyResult {
        val split = splitCarriedTrailer(inner) ?: run {
            Log.w("OFFTRACE", "SIG: carried verify FAILED type=$innerType from=${MeshFrame.hex(originId)} reason=no_signature_trailer")
            return CarriedVerifyResult.Reject
        }

        val pubkey = pubkeyForVerification(originId)
        if (pubkey == null) {
            queuePendingCarried(originId, finalDstId, innerType, inner, carrierId, hopCount)
            Log.d("OFFTRACE", "SIG: pubkey unknown for carried ${MeshFrame.hex(originId)} — queued pending HELLO")
            return CarriedVerifyResult.Queued
        }

        // A4: the SAME seen-signature dedupe as the live path — a genuine
        // carried SOS delivered twice (e.g. via two different mules, each
        // assigning their own carrier msgId to the same underlying signed
        // frame) must not be verified-and-alarmed twice.
        val sigKey = Base64.encodeToString(split.signature, Base64.NO_WRAP)
        synchronized(seenLock) {
            if (seenSignatures.containsKey(sigKey)) {
                Log.d("OFFTRACE", "SIG: carried replay rejected type=$innerType from=${MeshFrame.hex(originId)}")
                return CarriedVerifyResult.Reject
            }
            seenSignatures[sigKey] = System.currentTimeMillis()
        }

        if (!verifyCarriedTrailer(originId, finalDstId, innerType, split, pubkey)) {
            Log.w("OFFTRACE", "SIG: carried verify FAILED type=$innerType from=${MeshFrame.hex(originId)} reason=bad_signature")
            return CarriedVerifyResult.Reject
        }
        Log.d("OFFTRACE", "SIG: carried verify ok type=$innerType from=${MeshFrame.hex(originId)}")
        return CarriedVerifyResult.Accepted(split.innerPayload, split.timestampSec)
    }

    // OCP PHASE 3/G6: [capabilities] defaults to 0 — a peer whose HELLO
    // predates this field (or was truncated to exactly the pre-existing
    // prefix) advertises nothing, the safe default (see decodeHelloInner's
    // trailing-byte read below). Bit meanings are OfflineMediaTransport's
    // to define (e.g. CAP_FRAME_AGE) — this class only carries the raw byte.
    data class DecodedHello(
        val nodeId: Long,
        val protocolVersion: Byte,
        val pubkey: ByteArray,
        val name: String,
        val capabilities: Int = 0
    )

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

    // PART A: carried-frame equivalent of queuePending/drainPending — see
    // [verifyCarried]'s doc for why pubkey resolution has to run before the
    // sig-dedupe record on this path.
    private fun queuePendingCarried(originId: Long, finalDstId: Long, innerType: Byte, inner: ByteArray, carrierId: Long, hopCount: Int) {
        val list = pendingCarried.getOrPut(originId) { java.util.Collections.synchronizedList(mutableListOf()) }
        synchronized(list) {
            if (list.size >= PENDING_QUEUE_CAPACITY) list.removeAt(0)
            list.add(PendingCarried(originId, finalDstId, innerType, inner, carrierId, hopCount, System.currentTimeMillis()))
        }
    }

    private fun drainPendingCarried(nodeId: Long) {
        val list = pendingCarried.remove(nodeId) ?: return
        val now = System.currentTimeMillis()
        val snapshot = synchronized(list) { list.toList() }
        snapshot.forEach { p ->
            if (now - p.queuedAtMs <= PENDING_TIMEOUT_MS) {
                retryCarried(p.originId, p.finalDstId, p.innerType, p.inner, p.carrierId, p.hopCount)
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
        // PART 2.2: this pubkey self-certifies against claimedNodeId, but a
        // different pubkey for the SAME nodeId could still reach here via a
        // birthday-bound SHA-256 truncation collision (~2^32 effort against
        // this app's 8-byte nodeId) — cheaper than the 256-bit preimage
        // break the check above already rules out. Anything arriving over
        // this path (HELLO/roster) is TOFU; if a human already confirmed a
        // DIFFERENT pubkey for this nodeId by scanning its QR code
        // (PeerTrustStore holds it as VERIFIED), that takes precedence and
        // this claim is refused outright — see PeerTrustStore.evaluate.
        val trustOutcome = PeerTrustStore.record(context, claimedNodeId, pubkey, TrustLevel.TOFU)
        if (trustOutcome is PeerTrustStore.TrustOutcome.ImpersonationRefused) {
            Log.w("OFFTRACE", "SIG: hello REJECTED ${MeshFrame.hex(claimedNodeId)} — pubkey differs from QR-verified record (impersonation)")
            return false
        }
        routingTable.putVerifiedPubkey(claimedNodeId, pubkey)
        if (displayNameForLog != null) {
            Log.d("OFFTRACE", "SIG: hello verified ${MeshFrame.hex(claimedNodeId)} name=\"$displayNameForLog\" pubkey ok")
        }
        persistPubkeysAsync()
        drainPending(claimedNodeId)
        drainPendingCarried(claimedNodeId)
        return true
    }

    /** Loads whatever was persisted last session — re-verifies every entry on
     *  load rather than trusting the file blindly (same "never trust a
     *  persisted file's integrity implicitly" posture as MeshLedger/MeshCarrier). */
    fun loadPersistedPubkeys(): Map<Long, ByteArray> {
        if (!pubkeyFile.exists()) return emptyMap()
        return try {
            // FIX 3: delegates to the pure decodePubkeysJson (see its doc) —
            // this instance method's only remaining job is the extra
            // re-verification step (size==32 && deriveNodeId matches),
            // which needs deriveNodeId but no File/Context, so it stays
            // here rather than in the pure decoder.
            decodePubkeysJson(pubkeyFile.readText(Charsets.UTF_8))
                .filter { (nodeId, pubkey) -> pubkey.size == 32 && deriveNodeId(pubkey) == nodeId }
        } catch (e: Exception) {
            Log.w("OFFTRACE", "SIG: pubkey file discarded (malformed): ${e.message}")
            emptyMap()
        }
    }

    private fun persistPubkeysAsync() {
        ioHandler.post {
            try {
                // FIX 3: delegates to the pure encodePubkeysJson (see its doc).
                writeAtomic(pubkeyFile, encodePubkeysJson(routingTable.allVerifiedPubkeys()))
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
