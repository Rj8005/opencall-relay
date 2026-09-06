package com.opencall.relay.offline

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

/** PART 2.2: how a peer's pubkey was first established. VERIFIED means a
 *  human physically scanned that peer's QR code (see
 *  OfflineCallActivity.handleScannedQr) — TOFU means it arrived over the
 *  air (a HELLO or GO-relayed roster entry, via
 *  [MeshSigner.recordVerifiedPubkey]) with nothing behind it but
 *  MeshSigner's own nodeId=SHA-256(pubkey)[0..8] self-certification. */
enum class TrustLevel { TOFU, VERIFIED }

data class TrustRecord(val pubkeyB64: String, val level: TrustLevel)

/**
 * PART 2.2: VERIFIED pairing. [MeshSigner.recordVerifiedPubkey]'s own
 * self-certification (nodeId IS SHA-256(pubkey)[0..8]) already makes "a
 * different pubkey for the SAME nodeId" require breaking SHA-256 preimage
 * resistance for a full 256-bit pubkey — but only a ~2^32-effort birthday
 * search against this app's TRUNCATED 8-byte nodeId, a real (if expensive)
 * attack. This store is the defense-in-depth layer on top of that: once a
 * nodeId's pubkey has been confirmed by a human scanning its QR code
 * (VERIFIED), a later sighting of a DIFFERENT pubkey under that same
 * nodeId — however it arrived, self-certifying or not — is refused
 * outright as an impersonation attempt, not silently accepted as a normal
 * key rotation. A TOFU-only nodeId has no such protection (nothing was
 * ever human-confirmed to violate) and simply re-records the latest
 * sighting, same as before this feature existed.
 *
 * [evaluate] is the pure decision core (see its own doc); [record] is the
 * thin Context-backed wrapper that persists across sessions, mirroring
 * [MeshSigner]'s own pubkeys.json pattern (pure JSON codec here uses
 * [java.util.Base64], not `android.util.Base64`, for the same JVM-unit-test
 * reason as [MeshSigner]'s own pure companion functions — this project has
 * no Robolectric, and the Android stub silently returns defaults instead of
 * throwing, which would make a broken encode/decode pass silently).
 */
object PeerTrustStore {

    sealed class TrustOutcome {
        data class Recorded(val level: TrustLevel) : TrustOutcome()
        /** The exact "impersonation, not a normal event" case 2.2 asks for:
         *  [nodeId] was already VERIFIED under [verifiedPubkeyB64], and this
         *  sighting claims a DIFFERENT pubkey. Nothing is written to the
         *  store when this is returned — the prior VERIFIED record stands
         *  untouched. */
        data class ImpersonationRefused(val nodeId: Long, val verifiedPubkeyB64: String, val incomingPubkeyB64: String) : TrustOutcome()
    }

    /** Pure — no Context, no file I/O, no Log — see PeerTrustStoreTest.
     *  [existing] is whatever this store currently holds for [nodeId] (null
     *  if never seen before).
     *
     *  Rules:
     *   - No existing record: recorded at whatever level this sighting came
     *     in at — first sighting, nothing to compare against.
     *   - Existing record, SAME pubkey: recorded at the HIGHER of the two
     *     levels — VERIFIED sticks; a device already confirmed by QR is
     *     never demoted back to TOFU just because its next HELLO arrived
     *     over the air with the same key it always had.
     *   - Existing record is VERIFIED, incoming pubkey DIFFERS: refused —
     *     see [ImpersonationRefused]'s own doc. Applies whether the
     *     incoming sighting is itself a fresh QR scan or an over-the-air
     *     HELLO; a second "verified" claim does not get to silently
     *     override the first.
     *   - Existing record is TOFU, incoming pubkey DIFFERS: NOT refused — a
     *     TOFU peer changing keys (reinstall, [OfflineIdentity.resetIdentity])
     *     is a normal event with no prior human confirmation to violate;
     *     recorded as a fresh sighting at the incoming level. */
    fun evaluate(existing: TrustRecord?, nodeId: Long, incomingPubkeyB64: String, incomingLevel: TrustLevel): TrustOutcome {
        if (existing == null) return TrustOutcome.Recorded(incomingLevel)
        if (existing.pubkeyB64 == incomingPubkeyB64) {
            val level = if (existing.level == TrustLevel.VERIFIED || incomingLevel == TrustLevel.VERIFIED) TrustLevel.VERIFIED else TrustLevel.TOFU
            return TrustOutcome.Recorded(level)
        }
        if (existing.level == TrustLevel.VERIFIED) {
            return TrustOutcome.ImpersonationRefused(nodeId, existing.pubkeyB64, incomingPubkeyB64)
        }
        return TrustOutcome.Recorded(incomingLevel)
    }

    /** {"entries":[{"nodeId":Long,"pubkey":base64,"level":"VERIFIED"|"TOFU"}]} —
     *  mirrors [MeshSigner.encodePubkeysJson]'s shape/style exactly. */
    fun encodeTrustJson(entries: Map<Long, TrustRecord>): String {
        val arr = JSONArray()
        entries.forEach { (id, record) ->
            arr.put(
                JSONObject().apply {
                    put("nodeId", id)
                    put("pubkey", record.pubkeyB64)
                    put("level", record.level.name)
                }
            )
        }
        return JSONObject().apply { put("entries", arr) }.toString()
    }

    /** One malformed entry is skipped, not fatal to the whole file — same
     *  posture as [MeshSigner.decodePubkeysJson]. */
    fun decodeTrustJson(raw: String): Map<Long, TrustRecord> = try {
        val json = JSONObject(raw)
        val arr = json.optJSONArray("entries") ?: JSONArray()
        val result = mutableMapOf<Long, TrustRecord>()
        for (i in 0 until arr.length()) {
            try {
                val o = arr.getJSONObject(i)
                val nodeId = o.getLong("nodeId")
                val pubkeyB64 = o.getString("pubkey")
                val level = TrustLevel.valueOf(o.getString("level"))
                result[nodeId] = TrustRecord(pubkeyB64, level)
            } catch (e: Exception) {
                // one malformed entry does not discard the rest of the file
            }
        }
        result
    } catch (e: Exception) {
        emptyMap()
    }

    fun pubkeyB64(pubkey: ByteArray): String = Base64.getEncoder().encodeToString(pubkey)

    // ── Context-backed persistence ──────────────────────────────────────────

    private const val LEDGER_DIR_NAME = "ledger"
    private const val TRUST_FILE_NAME = "trust.json"
    private val lock = Any()
    private var cache: MutableMap<Long, TrustRecord>? = null

    private fun file(context: Context): File =
        File(context.filesDir, LEDGER_DIR_NAME).apply { mkdirs() }.let { File(it, TRUST_FILE_NAME) }

    private fun load(context: Context): MutableMap<Long, TrustRecord> = synchronized(lock) {
        cache?.let { return it }
        val f = file(context)
        val loaded = if (f.exists()) {
            try {
                decodeTrustJson(f.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                Log.w("OFFTRACE", "TRUST: file discarded (malformed): ${e.message}")
                emptyMap()
            }
        } else emptyMap()
        val m = loaded.toMutableMap()
        cache = m
        m
    }

    private fun persist(context: Context, map: Map<Long, TrustRecord>) {
        try {
            file(context).writeText(encodeTrustJson(map), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w("OFFTRACE", "TRUST: persist failed: ${e.message}")
        }
    }

    fun levelFor(context: Context, nodeId: Long): TrustLevel? = synchronized(lock) { load(context)[nodeId]?.level }

    /** The single entry point every pubkey sighting funnels through — a QR
     *  scan calls this with [TrustLevel.VERIFIED], [MeshSigner.recordVerifiedPubkey]
     *  calls it with [TrustLevel.TOFU] for anything arriving over the air.
     *  See [evaluate] for the decision rules; on [TrustOutcome.ImpersonationRefused]
     *  nothing is persisted and this logs loudly (2.2: "refuse the link and
     *  warn loudly"). */
    fun record(context: Context, nodeId: Long, pubkey: ByteArray, level: TrustLevel): TrustOutcome = synchronized(lock) {
        val map = load(context)
        val incomingB64 = pubkeyB64(pubkey)
        val outcome = evaluate(map[nodeId], nodeId, incomingB64, level)
        when (outcome) {
            is TrustOutcome.Recorded -> {
                map[nodeId] = TrustRecord(incomingB64, outcome.level)
                persist(context, map)
            }
            is TrustOutcome.ImpersonationRefused -> {
                Log.w(
                    "OFFTRACE",
                    "TRUST: IMPERSONATION nodeId=${MeshFrame.hex(nodeId)} — pubkey differs from QR-verified record, REFUSED"
                )
            }
        }
        outcome
    }
}
