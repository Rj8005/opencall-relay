package com.opencall.relay.offline

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
}
