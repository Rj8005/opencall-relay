package com.opencall.relay.offline

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.math.BigInteger
import java.security.MessageDigest

/**
 * PART D4: derives an X25519 (Diffie-Hellman) keypair FROM this app's
 * existing Ed25519 identity, so "encrypted to the recipient's Ed25519-
 * derived key" is literal, not a separate key the recipient has to publish.
 *
 * TWO DIFFERENT DERIVATIONS, because a private key and a public key need
 * different math:
 *
 *  - MY OWN X25519 private scalar: `clamp(SHA-512(seed)[0:32])` —
 *    [privateScalarFrom]. This is not a conversion trick so much as a fact:
 *    RFC 8032 Ed25519 key generation computes its own signing scalar `a`
 *    with EXACTLY this formula (hash the seed with SHA-512, clamp the
 *    first 32 bytes) — X25519's own clamped scalar (RFC 7748) is the
 *    identical operation. [OfflineIdentity] already stores the 32-byte
 *    Ed25519 seed; this just repeats the same hash+clamp Ed25519 key
 *    generation already does, so it carries no material risk beyond
 *    "did I copy the clamp bits right" (pinned by
 *    [X25519BridgeTest]'s RFC 7748 clamp test).
 *
 *  - A PEER'S X25519 public key, from only their Ed25519 PUBLIC key (never
 *    their private seed, which this device never has): the birational map
 *    between Curve25519's Edwards and Montgomery forms, `u = (1+y)/(1-y)
 *    mod p` — [publicKeyFrom]. This is the one piece of hand-written finite-
 *    field math in this file (BigInteger modular arithmetic, not point
 *    multiplication) and is the standard formula libsodium's
 *    `crypto_sign_ed25519_pk_to_curve25519` also uses.
 *
 * SELF-CONSISTENCY, not cross-engine verification: [X25519BridgeTest]
 * proves that for one keypair, "scalar-multiply the base point by my OWN
 * derived private scalar" and "run the birational map on my OWN Ed25519
 * public key" land on the IDENTICAL Montgomery point — the one property
 * that would catch a broken conversion (if it didn't hold, two peers doing
 * static-static Diffie-Hellman would derive DIFFERENT shared secrets and
 * silently fail to talk, with no error to point at). This environment has
 * no second device to run an actual two-party handshake against a real
 * decimen.app/libsodium instance — see this task's own OUTPUT section for
 * that caveat stated plainly, not glossed over.
 */
object X25519Bridge {

    private val P = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))

    /** RFC 7748 §5's clamp, applied to the low 32 bytes of SHA-512(seed) —
     *  the same operation RFC 8032 Ed25519 key generation performs to get
     *  its own scalar `a`. */
    fun privateScalarFrom(ed25519Seed: ByteArray): ByteArray {
        require(ed25519Seed.size == 32) { "Ed25519 seed must be 32 bytes" }
        val hash = MessageDigest.getInstance("SHA-512").digest(ed25519Seed)
        val scalar = hash.copyOf(32)
        scalar[0] = (scalar[0].toInt() and 0xF8).toByte()
        scalar[31] = (scalar[31].toInt() and 0x7F).toByte()
        scalar[31] = (scalar[31].toInt() or 0x40).toByte()
        return scalar
    }

    /** X25519 public key matching [privateScalarFrom]'s scalar — delegates
     *  the actual Montgomery-ladder scalar multiplication to BouncyCastle's
     *  well-tested [X25519PrivateKeyParameters], not hand-written point
     *  math. */
    fun publicKeyFromPrivateScalar(scalar: ByteArray): ByteArray =
        X25519PrivateKeyParameters(scalar, 0).generatePublicKey().encoded

    /** Birational Edwards-y -> Montgomery-u conversion of a PEER's Ed25519
     *  public key — the only path available when this device holds their
     *  public key but never their private seed. [ed25519PublicKey] is the
     *  standard 32-byte little-endian encoding (RFC 8032): y-coordinate
     *  with the x sign bit packed into the top bit, which is masked off
     *  before decoding y — this function never uses that sign bit (the
     *  birational map only needs y). */
    fun publicKeyFrom(ed25519PublicKey: ByteArray): ByteArray {
        require(ed25519PublicKey.size == 32) { "Ed25519 public key must be 32 bytes" }
        val yBytes = ed25519PublicKey.copyOf()
        yBytes[31] = (yBytes[31].toInt() and 0x7F).toByte() // clear the x sign bit — not part of y
        val y = decodeLittleEndian(yBytes).mod(P)
        val numerator = BigInteger.ONE.add(y).mod(P)
        val denominator = BigInteger.ONE.subtract(y).mod(P)
        val u = numerator.multiply(denominator.modInverse(P)).mod(P)
        return encodeLittleEndian(u, 32)
    }

    private fun decodeLittleEndian(bytes: ByteArray): BigInteger {
        val be = ByteArray(bytes.size) { bytes[bytes.size - 1 - it] } // reverse to big-endian for BigInteger
        return BigInteger(1, be)
    }

    private fun encodeLittleEndian(value: BigInteger, length: Int): ByteArray {
        val be = value.toByteArray().let { raw ->
            // BigInteger.toByteArray() may prepend a sign byte or be shorter than `length`.
            when {
                raw.size == length -> raw
                raw.size == length + 1 && raw[0] == 0.toByte() -> raw.copyOfRange(1, raw.size)
                raw.size < length -> ByteArray(length - raw.size) + raw
                else -> raw.copyOfRange(raw.size - length, raw.size)
            }
        }
        return ByteArray(length) { be[length - 1 - it] } // reverse to little-endian
    }
}
