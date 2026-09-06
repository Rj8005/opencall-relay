package com.opencall.relay.offline

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * PART 2.4/2.7: pure-JVM tests for [OpticalFrame] — same spirit as
 * [MeshSignerTest] (whose keypair-generation helpers this mirrors exactly):
 * the Ed25519 keypair is generated locally via BouncyCastle rather than
 * through [OfflineIdentity.sign], since that path needs a real
 * AndroidKeyStore this project's JVM unit tests don't have. Everything else
 * exercised here — [MeshSigner.buildSignedMaterial], [MeshSigner.splitCarriedTrailer],
 * [MeshSigner.verifyCarriedTrailer], [OfflineIdentity.verify] — is the exact
 * production code the radio path uses too (see [OpticalFrame]'s own class
 * doc: "verification is identical").
 */
class OpticalFrameTest {

    private fun randomKeypair(): Pair<Ed25519PrivateKeyParameters, Ed25519PublicKeyParameters> {
        val seed = ByteArray(32)
        SecureRandom().nextBytes(seed)
        val priv = Ed25519PrivateKeyParameters(seed, 0)
        return priv to priv.generatePublicKey()
    }

    private fun signer(priv: Ed25519PrivateKeyParameters): (ByteArray) -> ByteArray = { bytes ->
        val s = Ed25519Signer()
        s.init(true, priv)
        s.update(bytes, 0, bytes.size)
        s.generateSignature()
    }

    private fun nodeIdFor(pub: Ed25519PublicKeyParameters): Long =
        ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(pub.encoded), 0, 8).long

    @Test
    fun `a group alert round trips — build, parse, verify`() {
        val (priv, pub) = randomKeypair()
        val nodeId = nodeIdFor(pub)
        val loc = MeshLocation.noFix(msgSeq = 1L, unixSeconds = 1_700_000_000L).copy(message = "help")

        val frame = OpticalFrame.buildSignedFrame(
            nodeId, MeshFrame.BROADCAST_ID, OfflineMediaTransport.TYPE_POSITION, MeshLocation.encode(loc), signer(priv)
        )

        val outcome = OpticalFrame.verify(frame) { pub.encoded }
        assertTrue(outcome is OpticalFrame.VerifyOutcome.Verified)
        val verified = outcome as OpticalFrame.VerifyOutcome.Verified
        assertEquals(nodeId, verified.header.srcId)
        assertEquals(OfflineMediaTransport.TYPE_POSITION, verified.header.type)
        val decodedLoc = MeshLocation.decode(verified.innerPayload)
        assertEquals("help", decodedLoc?.message)
    }

    @Test
    fun `a text chat round trips — build, parse, verify`() {
        val (priv, pub) = randomKeypair()
        val nodeId = nodeIdFor(pub)
        val envelope = ChatEnvelope.Text("hello via optical")

        val frame = OpticalFrame.buildSignedFrame(
            nodeId, MeshFrame.BROADCAST_ID, OfflineMediaTransport.TYPE_CHAT, ChatEnvelope.encode(envelope), signer(priv)
        )

        val outcome = OpticalFrame.verify(frame) { pub.encoded }
        val verified = outcome as OpticalFrame.VerifyOutcome.Verified
        val decoded = ChatEnvelope.decode(verified.innerPayload)
        assertTrue(decoded is ChatEnvelope.Text)
        assertEquals("hello via optical", (decoded as ChatEnvelope.Text).body)
    }

    @Test
    fun `a file attachment round trips through ChatEnvelope`() {
        val fileBytes = ByteArray(1024) { it.toByte() }
        val encoded = ChatEnvelope.encode(ChatEnvelope.FileAttachment("report.txt", fileBytes))
        val decoded = ChatEnvelope.decode(encoded)
        assertTrue(decoded is ChatEnvelope.FileAttachment)
        val file = decoded as ChatEnvelope.FileAttachment
        assertEquals("report.txt", file.name)
        assertArrayEquals(fileBytes, file.data)
    }

    @Test
    fun `an unknown-pubkey sender is reported as UnknownSender, not a failure`() {
        val (priv, pub) = randomKeypair()
        val nodeId = nodeIdFor(pub)
        val frame = OpticalFrame.buildSignedFrame(nodeId, MeshFrame.BROADCAST_ID, OfflineMediaTransport.TYPE_CHAT, "hi".toByteArray(), signer(priv))

        val outcome = OpticalFrame.verify(frame) { null }
        assertEquals(OpticalFrame.VerifyOutcome.UnknownSender, outcome)
    }

    @Test
    fun `a tampered payload is rejected as Tampered, not silently accepted`() {
        val (priv, pub) = randomKeypair()
        val nodeId = nodeIdFor(pub)
        val frame = OpticalFrame.buildSignedFrame(nodeId, MeshFrame.BROADCAST_ID, OfflineMediaTransport.TYPE_CHAT, "hi".toByteArray(), signer(priv))

        // Flip a byte inside the payload region (after the 23B header), well
        // clear of the header fields parseHeader relies on.
        val tampered = frame.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0xFF).toByte()

        val outcome = OpticalFrame.verify(tampered) { pub.encoded }
        assertEquals(OpticalFrame.VerifyOutcome.Tampered, outcome)
    }

    @Test
    fun `garbage bytes are Malformed, never throw`() {
        val outcome = OpticalFrame.verify(ByteArray(5)) { null }
        assertEquals(OpticalFrame.VerifyOutcome.Malformed, outcome)
    }

    @Test(expected = OpticalFrame.PayloadTooLargeException::class)
    fun `a payload over the 64KB cap is refused, not truncated or sent`() {
        val (priv, _) = randomKeypair()
        OpticalFrame.buildSignedFrame(1L, MeshFrame.BROADCAST_ID, OfflineMediaTransport.TYPE_CHAT, ByteArray(OpticalFrame.MAX_PAYLOAD_BYTES), signer(priv))
    }
}
