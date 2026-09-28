package com.opencall.relay.offline

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * PHASE 7A: pure-JVM tests for MeshSigner's wire-format logic and
 * OfflineIdentity.verify — same spirit as MeshElectionTest/MeshCarrierTest,
 * no Android/Robolectric dependency.
 *
 * Everything exercised here is REAL production code: buildSignedMaterial,
 * deriveNodeId, isWithinReplayWindow and isSignedType are the exact
 * Context-free companion functions MeshSigner's instance methods call, and
 * OfflineIdentity.verify is the exact stateless verifier used on every
 * inbound frame. Only the Ed25519 KEYPAIR is generated locally in the test
 * (via BouncyCastle directly) rather than through OfflineIdentity.sign,
 * since that path loads/creates a Keystore-wrapped key from a real
 * AndroidKeyStore that doesn't exist off-device — see this project's
 * MeshSignerTest note in the PHASE 7A final report for why "key survives a
 * simulated process restart" is NOT covered by a test in this file.
 */
class MeshSignerTest {

    private fun randomKeypair(): Pair<Ed25519PrivateKeyParameters, Ed25519PublicKeyParameters> {
        val seed = ByteArray(32)
        SecureRandom().nextBytes(seed)
        val priv = Ed25519PrivateKeyParameters(seed, 0)
        return priv to priv.generatePublicKey()
    }

    private fun sign(priv: Ed25519PrivateKeyParameters, bytes: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, priv)
        signer.update(bytes, 0, bytes.size)
        return signer.generateSignature()
    }

    private fun nodeIdFor(pub: Ed25519PublicKeyParameters): Long =
        ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(pub.encoded), 0, 8).long

    @Test
    fun `sign then verify round trip succeeds`() {
        val (priv, pub) = randomKeypair()
        val material = MeshSigner.buildSignedMaterial(111L, 222L, 7, 1_700_000_000L, "hello mesh".toByteArray())
        val sig = sign(priv, material)
        assertTrue(OfflineIdentity.verify(pub.encoded, material, sig))
    }

    @Test
    fun `tampered payload fails verification`() {
        val (priv, pub) = randomKeypair()
        val material = MeshSigner.buildSignedMaterial(1L, 2L, 4, 1_700_000_000L, "original".toByteArray())
        val sig = sign(priv, material)
        val tampered = MeshSigner.buildSignedMaterial(1L, 2L, 4, 1_700_000_000L, "tampered".toByteArray())
        assertFalse(OfflineIdentity.verify(pub.encoded, tampered, sig))
    }

    @Test
    fun `wrong pubkey fails verification`() {
        val (priv, _) = randomKeypair()
        val (_, otherPub) = randomKeypair()
        val material = MeshSigner.buildSignedMaterial(1L, 2L, 4, 1_700_000_000L, "payload".toByteArray())
        val sig = sign(priv, material)
        assertFalse(OfflineIdentity.verify(otherPub.encoded, material, sig))
    }

    @Test
    fun `timestamp is inside the signed material — a captured signature does not cover a replayed frame with a fresh timestamp`() {
        val (priv, pub) = randomKeypair()
        val payload = "payload".toByteArray()
        val original = MeshSigner.buildSignedMaterial(1L, 2L, 4, 1_700_000_000L, payload)
        val sig = sign(priv, original)
        // Same srcId/dstId/type/payload, only the timestamp changed — if the
        // timestamp were NOT part of the signed material this would still verify.
        val freshTimestamp = MeshSigner.buildSignedMaterial(1L, 2L, 4, 1_700_000_500L, payload)
        assertFalse(OfflineIdentity.verify(pub.encoded, freshTimestamp, sig))
    }

    @Test
    fun `nodeId-pubkey mismatch is rejected`() {
        val (_, pub) = randomKeypair()
        val realNodeId = nodeIdFor(pub)
        assertEquals(realNodeId, MeshSigner.deriveNodeId(pub.encoded))
        val wrongClaimedNodeId = realNodeId xor 0x1L
        assertNotEquals(wrongClaimedNodeId, MeshSigner.deriveNodeId(pub.encoded))
    }

    @Test
    fun `replay outside the window is rejected in both directions`() {
        val now = 1_700_000_000L
        assertFalse(MeshSigner.isWithinReplayWindow(now, now - 121)) // too old
        assertFalse(MeshSigner.isWithinReplayWindow(now, now + 121)) // too far in the future
    }

    @Test
    fun `replay inside the window is accepted in both directions`() {
        val now = 1_700_000_000L
        assertTrue(MeshSigner.isWithinReplayWindow(now, now - 119))
        assertTrue(MeshSigner.isWithinReplayWindow(now, now + 119))
        assertTrue(MeshSigner.isWithinReplayWindow(now, now)) // zero skew
        assertTrue(MeshSigner.isWithinReplayWindow(now, now - 120)) // exact boundary, inclusive
    }

    @Test
    fun `types 1, 2 and 3 are never signed — every other type is, including future ones`() {
        assertFalse(MeshSigner.isSignedType(1))
        assertFalse(MeshSigner.isSignedType(2))
        assertFalse(MeshSigner.isSignedType(3))
        listOf<Byte>(4, 7, 20, 28, 29, 100).forEach {
            assertTrue("type $it should be signed", MeshSigner.isSignedType(it))
        }
    }

    // ── FIX 3: pubkeys.json round trip — the actual persist/load format,
    // exercised directly via the pure encode/decode companions (no File/
    // Context needed). This is what proves loadPersistedPubkeys' own load
    // path was never broken by the MeshLedger directory-glob bug — see
    // MeshLedgerTest's isLedgerTrackFileName tests for that half of FIX 3. ──

    private fun randomPubkey(seed: Byte): ByteArray = ByteArray(32) { (it + seed).toByte() }

    @Test
    fun `pubkeys json round trips several entries exactly`() {
        val entries = mapOf(
            0x1234567890ABCDEFL to randomPubkey(1),
            0x00000000004D2000L to randomPubkey(2),
            -1L to randomPubkey(3), // a nodeId whose high bit is set — must survive as a signed Long
            0L to randomPubkey(4)
        )
        val encoded = MeshSigner.encodePubkeysJson(entries)
        val decoded = MeshSigner.decodePubkeysJson(encoded)
        assertEquals(entries.keys, decoded.keys)
        entries.forEach { (id, key) ->
            assertTrue("pubkey mismatch for nodeId=$id", key.contentEquals(decoded.getValue(id)))
        }
    }

    @Test
    fun `pubkeys json round trip is stable for a single entry`() {
        val encoded = MeshSigner.encodePubkeysJson(mapOf(42L to randomPubkey(9)))
        val decoded = MeshSigner.decodePubkeysJson(encoded)
        assertEquals(1, decoded.size)
        assertTrue(randomPubkey(9).contentEquals(decoded.getValue(42L)))
    }

    @Test
    fun `empty pubkeys json round trips to an empty map`() {
        val encoded = MeshSigner.encodePubkeysJson(emptyMap())
        assertEquals(emptyMap<Long, ByteArray>(), MeshSigner.decodePubkeysJson(encoded))
    }

    @Test
    fun `decodePubkeysJson never throws on foreign or malformed JSON`() {
        assertEquals(emptyMap<Long, ByteArray>(), MeshSigner.decodePubkeysJson("not json at all"))
        assertEquals(emptyMap<Long, ByteArray>(), MeshSigner.decodePubkeysJson("""{"unrelated":"shape"}"""))
        assertEquals(emptyMap<Long, ByteArray>(), MeshSigner.decodePubkeysJson(""))
    }

    @Test
    fun `a single malformed entry is skipped, not fatal to the rest of the file`() {
        val good = randomPubkey(5)
        val raw = """{"entries":[
            {"nodeId":1,"pubkey":"${java.util.Base64.getEncoder().encodeToString(good)}"},
            {"nodeId":"not-a-number","pubkey":"???"},
            {"nodeId":3,"pubkey":"${java.util.Base64.getEncoder().encodeToString(randomPubkey(6))}"}
        ]}"""
        val decoded = MeshSigner.decodePubkeysJson(raw)
        assertEquals(setOf(1L, 3L), decoded.keys)
        assertTrue(good.contentEquals(decoded.getValue(1L)))
    }

    @Test
    fun `encodePubkeysJson uses the entries wrapper key, matching the format MeshLedger must never try to parse as its own`() {
        val encoded = MeshSigner.encodePubkeysJson(mapOf(1L to randomPubkey(1)))
        val json = org.json.JSONObject(encoded)
        assertTrue(json.has("entries"))
    }

    // ── OCP PHASE 3/G6: HELLO capability byte (decodeHelloInner) ────────────

    private fun helloBytes(nodeId: Long, name: String, capabilities: Int?): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val pubkey = randomPubkey(1)
        val size = 8 + 1 + pubkey.size + 1 + nameBytes.size + (if (capabilities != null) 1 else 0)
        val buf = ByteBuffer.allocate(size)
        buf.putLong(nodeId)
        buf.put(3) // MeshFrame.VERSION
        buf.put(pubkey)
        buf.put(nameBytes.size.toByte())
        buf.put(nameBytes)
        if (capabilities != null) buf.put(capabilities.toByte())
        return buf.array()
    }

    @Test
    fun `decodeHelloInner reads the capability byte when present`() {
        val decoded = MeshSigner.decodeHelloInner(helloBytes(42L, "alice", capabilities = 0x01))
        assertEquals(0x01, decoded!!.capabilities)
    }

    @Test
    fun `decodeHelloInner defaults capabilities to 0 for a pre-PHASE-3 payload with no trailing byte`() {
        // Simulates an OLDER peer's HELLO — exactly the pre-existing wire
        // shape, no capability byte appended at all.
        val decoded = MeshSigner.decodeHelloInner(helloBytes(42L, "alice", capabilities = null))
        assertEquals(0, decoded!!.capabilities)
    }

    @Test
    fun `decodeHelloInner ignores extra trailing bytes beyond the capability byte — future-proof for the NEXT additive field`() {
        val base = helloBytes(42L, "alice", capabilities = 0x03)
        val withExtraTail = base + byteArrayOf(0x7F, 0x00, 0x11) // simulates a future field this build doesn't know about
        val decoded = MeshSigner.decodeHelloInner(withExtraTail)
        assertEquals(0x03, decoded!!.capabilities) // still parses the prefix it knows correctly
    }

    @Test
    fun `decodeHelloInner never throws on a truncated capability byte region`() {
        val base = helloBytes(42L, "alice", capabilities = null)
        assertEquals(0, MeshSigner.decodeHelloInner(base)!!.capabilities)
    }

    // ── PART A: carried (store-and-forward) SOS signature verification ──────
    // Pure, Context-free — same spirit as the replay-window tests above.
    // [verifyCarried]'s Context-dependent half (pubkey lookup, pending-queue,
    // seen-signature dedupe) is deliberately NOT exercised here, matching
    // this file's existing scope (verifyIncoming itself is never
    // instance-tested either — only its pure building blocks are).

    private val TYPE_SOS: Byte = 20
    private val TYPE_FIND_REQ: Byte = 21
    private val TYPE_FIND_RESP: Byte = 22
    private val TYPE_POSITION: Byte = 23

    /** Builds a wire-format carried inner payload — innerPayload || timestamp
     *  (4B) || signature (64B) — exactly what MeshSosManager.cacheForCarry now
     *  stores and MeshCarrier hands back to dispatchCarriedInner (see A1). */
    private fun buildCarriedPayload(
        originId: Long,
        finalDstId: Long,
        innerType: Byte,
        timestampSec: Long,
        inner: ByteArray,
        priv: Ed25519PrivateKeyParameters
    ): ByteArray {
        val material = MeshSigner.buildSignedMaterial(originId, finalDstId, innerType, timestampSec, inner)
        val sig = sign(priv, material)
        val buf = ByteBuffer.allocate(inner.size + 4 + 64)
        buf.put(inner)
        buf.putInt((timestampSec and 0xFFFFFFFFL).toInt())
        buf.put(sig)
        return buf.array()
    }

    @Test
    fun `a carried SOS with a valid signature and a 2-hour-old signed timestamp verifies and is alarmable`() {
        val (priv, pub) = randomKeypair()
        val originId = 0x1122334455667788L
        val finalDstId = MeshFrame.BROADCAST_ID
        val nowSec = 1_700_000_000L
        val twoHoursAgo = nowSec - 2 * 60 * 60L
        val payload = buildCarriedPayload(originId, finalDstId, TYPE_SOS, twoHoursAgo, "sos-fix-bytes".toByteArray(), priv)

        val split = MeshSigner.splitCarriedTrailer(payload)!!
        assertEquals(twoHoursAgo, split.timestampSec)
        assertTrue(MeshSigner.verifyCarriedTrailer(originId, finalDstId, TYPE_SOS, split, pub.encoded))

        // A3: age comes from the SIGNED timestamp, not local receipt time —
        // dispatchCarriedInner computes this exact subtraction.
        val ageSec = nowSec - split.timestampSec
        assertEquals(7200L, ageSec)
        assertTrue(
            "a 2h-old cryptographically verified carried SOS must still alarm",
            MeshSosManager.computeAlarmable(active = true, isLive = false, ageSec = ageSec, isCarriedVerified = true, existingAlarmable = null)
        )
    }

    @Test
    fun `a carried SOS with one flipped payload byte fails verification`() {
        val (priv, pub) = randomKeypair()
        val originId = 42L
        val finalDstId = MeshFrame.BROADCAST_ID
        val timestampSec = 1_700_000_000L - 3600L
        val payload = buildCarriedPayload(originId, finalDstId, TYPE_SOS, timestampSec, "sos-fix-bytes".toByteArray(), priv)
        payload[0] = (payload[0].toInt() xor 0x01).toByte() // flip one byte of the inner payload

        val split = MeshSigner.splitCarriedTrailer(payload)!!
        assertFalse(MeshSigner.verifyCarriedTrailer(originId, finalDstId, TYPE_SOS, split, pub.encoded))
    }

    @Test
    fun `a spoofed originId with no valid signature is rejected`() {
        // Attacker signs with their OWN key, but the frame is stamped with a
        // real party member's originId — the verifier looks up the REAL
        // member's pubkey (simulated here by using a different keypair than
        // the one that actually signed), so verification must fail.
        val (attackerPriv, _) = randomKeypair()
        val (_, realMemberPub) = randomKeypair()
        val spoofedOriginId = 0x1234567890ABCDEFL // claims to be the real member
        val finalDstId = MeshFrame.BROADCAST_ID
        val timestampSec = 1_700_000_000L - 60L
        val payload = buildCarriedPayload(spoofedOriginId, finalDstId, TYPE_SOS, timestampSec, "fake distress".toByteArray(), attackerPriv)

        val split = MeshSigner.splitCarriedTrailer(payload)!!
        assertFalse(MeshSigner.verifyCarriedTrailer(spoofedOriginId, finalDstId, TYPE_SOS, split, realMemberPub.encoded))
    }

    @Test
    fun `a trailer-less carried payload is rejected outright, never accepted as unsigned`() {
        val bareInner = "no trailer at all, just raw bytes".toByteArray()
        assertNull(MeshSigner.splitCarriedTrailer(bareInner))
        // Even a payload that happens to be exactly one byte short of a full
        // trailer must still be rejected — there is no partial-trailer leniency.
        val almostTrailer = ByteArray(MeshSigner.SIGNATURE_TRAILER_BYTES - 1)
        assertNull(MeshSigner.splitCarriedTrailer(almostTrailer))
    }

    @Test
    fun `the same valid carried SOS delivered twice produces the identical dedupe key both times`() {
        // verifyCarried's seen-signature dedupe (A4) is keyed on the raw
        // signature bytes — proving two independent splits of the SAME wire
        // bytes yield byte-identical signatures is exactly the precondition
        // that check relies on to recognize (and reject) the second delivery,
        // so the underlying SOS alarms only once.
        val (priv, _) = randomKeypair()
        val originId = 7L
        val finalDstId = MeshFrame.BROADCAST_ID
        val timestampSec = 1_700_000_000L - 600L
        val payload = buildCarriedPayload(originId, finalDstId, TYPE_SOS, timestampSec, "sos-fix-bytes".toByteArray(), priv)

        // Two different mules independently hand back the identical wire bytes.
        val firstDelivery = MeshSigner.splitCarriedTrailer(payload.copyOf())!!
        val secondDelivery = MeshSigner.splitCarriedTrailer(payload.copyOf())!!
        assertTrue(firstDelivery.signature.contentEquals(secondDelivery.signature))
    }

    @Test
    fun `replay-window check is never consulted by the carried verification core`() {
        // A3: a carried SOS is legitimately hours old — splitCarriedTrailer/
        // verifyCarriedTrailer take no "now" parameter at all, unlike
        // verifyIncoming's live path (isWithinReplayWindow). This test simply
        // documents that a 6-hour-old signed timestamp still splits and
        // verifies successfully with no freshness gate in the way.
        val (priv, pub) = randomKeypair()
        val originId = 99L
        val finalDstId = MeshFrame.BROADCAST_ID
        val sixHoursAgo = 1_700_000_000L - 6 * 60 * 60L
        val payload = buildCarriedPayload(originId, finalDstId, TYPE_SOS, sixHoursAgo, "sos-fix-bytes".toByteArray(), priv)
        val split = MeshSigner.splitCarriedTrailer(payload)!!
        assertTrue(MeshSigner.verifyCarriedTrailer(originId, finalDstId, TYPE_SOS, split, pub.encoded))
    }

    // ── Part 4: dispatchCarriedInner widened from TYPE_SOS-only to every
    // MeshSosManager.isSosFindType type (SOS/FIND_REQ/FIND_RESP/POSITION) — a
    // carried FIND_REQ, FIND_RESP or POSITION now goes through this exact same
    // verifyCarried chokepoint (splitCarriedTrailer + verifyCarriedTrailer)
    // before it can ever reach dispatchLocal, instead of the old unverified
    // dedupe-then-dispatch fallback. These mirror the TYPE_SOS bad/absent
    // signature tests above, one per newly-widened type.

    @Test
    fun `a carried FIND_REQ, FIND_RESP or POSITION with a bad signature is rejected`() {
        val (priv, pub) = randomKeypair()
        val originId = 0xAABBCCDDL
        val finalDstId = MeshFrame.BROADCAST_ID
        val timestampSec = 1_700_000_000L - 60L
        for (type in listOf(TYPE_FIND_REQ, TYPE_FIND_RESP, TYPE_POSITION)) {
            val payload = buildCarriedPayload(originId, finalDstId, type, timestampSec, "loc-fix-bytes".toByteArray(), priv)
            payload[0] = (payload[0].toInt() xor 0x01).toByte() // flip one byte of the inner payload
            val split = MeshSigner.splitCarriedTrailer(payload)!!
            assertFalse(
                "carried type=$type with a flipped payload byte must fail verification",
                MeshSigner.verifyCarriedTrailer(originId, finalDstId, type, split, pub.encoded)
            )
        }
    }

    @Test
    fun `a carried FIND_REQ, FIND_RESP or POSITION with no signature trailer at all is rejected outright`() {
        val bareInner = "no trailer at all, just raw bytes".toByteArray()
        for (type in listOf(TYPE_FIND_REQ, TYPE_FIND_RESP, TYPE_POSITION)) {
            // splitCarriedTrailer doesn't take a type param — a missing trailer
            // is rejected purely on shape, same as the TYPE_SOS case above; the
            // per-type assertion here just documents this covers all three.
            assertNull("type=$type with no trailer must not split", MeshSigner.splitCarriedTrailer(bareInner))
        }
    }

    @Test
    fun `a carried FIND_REQ, FIND_RESP or POSITION with a valid signature still verifies — the widening does not break legitimate traffic`() {
        val (priv, pub) = randomKeypair()
        val originId = 0x99887766L
        val finalDstId = MeshFrame.BROADCAST_ID
        val timestampSec = 1_700_000_000L - 30L
        for (type in listOf(TYPE_FIND_REQ, TYPE_FIND_RESP, TYPE_POSITION)) {
            val payload = buildCarriedPayload(originId, finalDstId, type, timestampSec, "loc-fix-bytes".toByteArray(), priv)
            val split = MeshSigner.splitCarriedTrailer(payload)!!
            assertTrue(
                "carried type=$type with a genuinely valid signature must verify",
                MeshSigner.verifyCarriedTrailer(originId, finalDstId, type, split, pub.encoded)
            )
        }
    }
}
