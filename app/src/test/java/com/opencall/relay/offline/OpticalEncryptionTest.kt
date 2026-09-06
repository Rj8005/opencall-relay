package com.opencall.relay.offline

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.security.SecureRandom

/** PART D4/D7: encrypt/decrypt round trip + tamper rejection, driven by a
 *  shared secret computed the same way [OfflineIdentity.x25519SharedSecret]
 *  would (this file can't call that directly — it needs a real Context —
 *  so it reproduces the DH by hand from two in-test Ed25519 seeds, same
 *  spirit as [X25519BridgeTest]'s own two-sided agreement test). */
class OpticalEncryptionTest {

    private fun randomSeed(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private fun pubOf(seed: ByteArray): ByteArray = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded

    private fun sharedSecret(mySeed: ByteArray, otherPub: ByteArray): ByteArray {
        val myScalar = X25519Bridge.privateScalarFrom(mySeed)
        val otherX25519Pub = X25519Bridge.publicKeyFrom(otherPub)
        val out = ByteArray(32)
        X25519Agreement().apply { init(X25519PrivateKeyParameters(myScalar, 0)) }
            .calculateAgreement(X25519PublicKeyParameters(otherX25519Pub, 0), out, 0)
        return out
    }

    @Test
    fun `a message encrypted to the recipient decrypts back to the original plaintext`() {
        val senderSeed = randomSeed()
        val recipientSeed = randomSeed()
        val plaintext = "this is a private message".toByteArray()

        val envelope = OpticalEncryption.encrypt(plaintext, sharedSecret(senderSeed, pubOf(recipientSeed)))
        val decrypted = OpticalEncryption.decrypt(envelope, sharedSecret(recipientSeed, pubOf(senderSeed)))

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `is unreadable without the recipient's own key`() {
        val senderSeed = randomSeed()
        val recipientSeed = randomSeed()
        val wrongRecipientSeed = randomSeed()
        val envelope = OpticalEncryption.encrypt("secret file bytes".toByteArray(), sharedSecret(senderSeed, pubOf(recipientSeed)))

        try {
            OpticalEncryption.decrypt(envelope, sharedSecret(wrongRecipientSeed, pubOf(senderSeed)))
            org.junit.Assert.fail("decryption with the wrong recipient key must not succeed")
        } catch (e: OpticalEncryption.DecryptionFailedException) {
            // expected
        }
    }

    @Test(expected = OpticalEncryption.DecryptionFailedException::class)
    fun `a tampered envelope fails authentication rather than decrypting to garbage`() {
        val senderSeed = randomSeed()
        val recipientSeed = randomSeed()
        val envelope = OpticalEncryption.encrypt("data".toByteArray(), sharedSecret(senderSeed, pubOf(recipientSeed))).copyOf()
        envelope[envelope.size - 1] = (envelope[envelope.size - 1].toInt() xor 0xFF).toByte()
        OpticalEncryption.decrypt(envelope, sharedSecret(recipientSeed, pubOf(senderSeed)))
    }

    @Test
    fun `two encryptions of the same plaintext use different nonces and produce different ciphertext`() {
        val senderSeed = randomSeed()
        val recipientPub = pubOf(randomSeed())
        val key = sharedSecret(senderSeed, recipientPub)
        val a = OpticalEncryption.encrypt("same message".toByteArray(), key)
        val b = OpticalEncryption.encrypt("same message".toByteArray(), key)
        assertFalse(a.contentEquals(b))
    }
}
