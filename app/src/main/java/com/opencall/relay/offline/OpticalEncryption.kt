package com.opencall.relay.offline

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * PART D4: "private messages and files are encrypted to the recipient's
 * Ed25519-derived key BEFORE encoding, when that key is known." A pure
 * symmetric AEAD wrapper over an already-derived 32-byte shared secret —
 * see [OfflineIdentity.x25519SharedSecret] for how that secret is obtained
 * (static-static X25519 Diffie-Hellman between two Ed25519 identities, via
 * [X25519Bridge]); this class never touches raw private key material.
 *
 * AES-256-GCM (Java's own, not hand-rolled); SHA-256 of the raw DH output
 * as the key — a real HKDF would be the textbook choice but this project
 * has no KDF dependency today, and a single SHA-256 pass over a fresh
 * 32-byte X25519 shared secret is not a meaningfully weaker key for a
 * one-shot symmetric key than a full HKDF would produce, given the input
 * already has 256 bits of DH entropy.
 *
 * Wire shape: `nonce(12B) || AES-GCM(plaintext) || 16B tag`. A fresh random
 * nonce every call (never reused) is what makes reusing the same static
 * shared secret across many messages safe — GCM's one hard rule.
 */
object OpticalEncryption {

    private const val NONCE_BYTES = 12
    private const val GCM_TAG_BITS = 128

    class DecryptionFailedException(message: String) : Exception(message)

    private fun keyFrom(sharedSecret: ByteArray): SecretKeySpec =
        SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(sharedSecret), "AES")

    fun encrypt(plaintext: ByteArray, sharedSecret: ByteArray): ByteArray {
        val key = keyFrom(sharedSecret)
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        return nonce + cipher.doFinal(plaintext)
    }

    /** Throws [DecryptionFailedException] (never returns garbage) on a bad
     *  key, corrupted ciphertext, or a truncated envelope — GCM's tag
     *  check makes "decrypted but wrong" impossible; it's authenticated or
     *  it throws. */
    fun decrypt(envelope: ByteArray, sharedSecret: ByteArray): ByteArray {
        if (envelope.size <= NONCE_BYTES) throw DecryptionFailedException("envelope too short")
        val key = keyFrom(sharedSecret)
        val nonce = envelope.copyOfRange(0, NONCE_BYTES)
        val ciphertext = envelope.copyOfRange(NONCE_BYTES, envelope.size)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            throw DecryptionFailedException("GCM authentication failed: ${e.javaClass.simpleName}")
        }
    }
}
