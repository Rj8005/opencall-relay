package com.opencall.relay.offline

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class X25519BridgeTest {

    @Test
    fun `RFC 7748 clamping bits are set correctly`() {
        val scalar = X25519Bridge.privateScalarFrom(ByteArray(32) { 0xFF.toByte() })
        assertTrue("low 3 bits of byte 0 must be clear", scalar[0].toInt() and 0x07 == 0)
        assertTrue("high bit of byte 31 must be clear", scalar[31].toInt() and 0x80 == 0)
        assertTrue("bit 6 of byte 31 must be set", scalar[31].toInt() and 0x40 != 0)
    }

    /** THE key correctness test — see X25519Bridge's own doc: if this ever
     *  fails, two real peers doing static-static DH would derive different
     *  shared secrets and silently fail to talk. */
    @Test
    fun `deriving my own X25519 public key from my private scalar matches deriving it from my Ed25519 public key`() {
        val seed = ByteArray(32)
        SecureRandom().nextBytes(seed)
        val edPriv = Ed25519PrivateKeyParameters(seed, 0)
        val edPub = edPriv.generatePublicKey().encoded

        val x25519PrivScalar = X25519Bridge.privateScalarFrom(seed)
        val fromPrivate = X25519Bridge.publicKeyFromPrivateScalar(x25519PrivScalar)
        val fromPublic = X25519Bridge.publicKeyFrom(edPub)

        assertArrayEquals(
            "birational conversion of the Ed25519 pubkey must equal scalarmult_base(clamped seed hash)",
            fromPrivate, fromPublic
        )
    }

    @Test
    fun `two independently derived keypairs agree on the same DH shared secret from both sides`() {
        val seedA = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val seedB = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val edPubA = Ed25519PrivateKeyParameters(seedA, 0).generatePublicKey().encoded
        val edPubB = Ed25519PrivateKeyParameters(seedB, 0).generatePublicKey().encoded

        val scalarA = X25519Bridge.privateScalarFrom(seedA)
        val scalarB = X25519Bridge.privateScalarFrom(seedB)

        // A's view: my scalar + B's derived pubkey (from B's Ed25519 pubkey, the only
        // thing A ever has of B's).
        val sharedFromA = ByteArray(32)
        X25519Agreement().apply { init(X25519PrivateKeyParameters(scalarA, 0)) }
            .calculateAgreement(X25519PublicKeyParameters(X25519Bridge.publicKeyFrom(edPubB), 0), sharedFromA, 0)

        // B's view: my scalar + A's derived pubkey.
        val sharedFromB = ByteArray(32)
        X25519Agreement().apply { init(X25519PrivateKeyParameters(scalarB, 0)) }
            .calculateAgreement(X25519PublicKeyParameters(X25519Bridge.publicKeyFrom(edPubA), 0), sharedFromB, 0)

        assertArrayEquals("static-static DH must agree from both sides", sharedFromA, sharedFromB)
    }

    @Test
    fun `two different Ed25519 keypairs derive different X25519 public keys`() {
        val a = X25519Bridge.publicKeyFrom(Ed25519PrivateKeyParameters(ByteArray(32) { 1 }, 0).generatePublicKey().encoded)
        val b = X25519Bridge.publicKeyFrom(Ed25519PrivateKeyParameters(ByteArray(32) { 2 }, 0).generatePublicKey().encoded)
        assertFalse(a.contentEquals(b))
    }
}
