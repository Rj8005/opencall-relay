package com.opencall.relay.offline

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/**
 * A2 (diagnostic follow-up): exercises OfflineIdentity's AndroidKeyStore
 * unwrap-failure -> regenerate path, which OfflineIdentityTest's class doc
 * explicitly excludes from the JVM suite ("need a real AndroidKeyStore that
 * only exists on-device") — same reason AccountStoreInstrumentedTest is this
 * project's other instrumented test. Confirms the three failure branches
 * OfflineIdentity.tryLoad documents (keystore_key_missing,
 * pubkey_privkey_mismatch, and the generic-exception catch-all for a
 * corrupt file) all result in a CLEAN regeneration — a new, fully
 * functional identity — rather than a crash or a silently broken one.
 *
 * NOT covered: KeyPermanentlyInvalidatedException (device-lock-changed).
 * OfflineIdentity's wrapping key deliberately never sets
 * setUserAuthenticationRequired (see that file's class doc) — the
 * production trigger for this exception needs an actual device lock
 * configuration change mid-test, which isn't something this harness can
 * simulate; the other two catch branches below exercise the same
 * "regenerate cleanly, log loudly, never crash" contract that branch shares.
 *
 * cached is private with no public reset-without-file-delete API (resetIdentity
 * deletes the file too, which isn't what these tests want to exercise), so
 * each test clears it via reflection — standing in for what a real process
 * death naturally does to that in-memory field, while leaving the on-disk
 * file and AndroidKeyStore state exactly as the test set them up.
 */
@RunWith(AndroidJUnit4::class)
class OfflineIdentityInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val keystoreAlias = "opencall_identity_wrap_key"
    private val identityFile = File(context.filesDir, "offline_mesh_identity.dat")

    private fun clearInMemoryCache() {
        val field = OfflineIdentity::class.java.getDeclaredField("cached")
        field.isAccessible = true
        field.set(OfflineIdentity, null)
    }

    private fun deleteKeystoreAlias() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(keystoreAlias)) ks.deleteEntry(keystoreAlias)
    }

    @Before
    fun cleanSlate() {
        // A clean start for every test: no leftover identity from a
        // previous test/run, so the "first identity" each test establishes
        // is genuinely freshly generated, not reused across test methods
        // sharing this process's OfflineIdentity singleton.
        OfflineIdentity.resetIdentity(context)
        deleteKeystoreAlias()
    }

    @After
    fun tearDown() {
        OfflineIdentity.resetIdentity(context)
        deleteKeystoreAlias()
    }

    /** Sanity check on the harness itself — establishes that a fresh
     *  identity is actually generated (keystore=true path), signs, and
     *  self-verifies, before any failure-injection test relies on that
     *  same setup working. */
    @Test
    fun freshIdentityIsUsableForSignAndVerify() {
        val pubkey = OfflineIdentity.publicKeyBytes(context)
        val msg = "hello mesh".toByteArray()
        val sig = OfflineIdentity.sign(context, msg)
        assertTrue(OfflineIdentity.verify(pubkey, msg, sig))
    }

    @Test
    fun regeneratesCleanlyWhenTheKeystoreWrappingKeyIsMissing() {
        val firstNodeId = OfflineIdentity.nodeId(context)
        assertTrue("identity file should exist after first generation", identityFile.exists())

        // Simulate the keystore_key_missing branch: the wrap key is gone
        // (e.g. keystore reset) but the encrypted identity file is still on
        // disk — loadWrappingKey() must return null, forcing regeneration.
        deleteKeystoreAlias()
        clearInMemoryCache()

        val secondNodeId = OfflineIdentity.nodeId(context)
        assertFalse(
            "a missing wrapping key must force a NEW nodeId, never silently reuse the old (now-unreadable) one",
            firstNodeId.contentEquals(secondNodeId)
        )
        assertTrue(OfflineIdentity.nodeId(context).contentEquals(secondNodeId)) // stable after regeneration

        // The regenerated identity must be fully functional, not just present.
        val pubkey = OfflineIdentity.publicKeyBytes(context)
        val msg = "post-regeneration".toByteArray()
        val sig = OfflineIdentity.sign(context, msg)
        assertTrue(OfflineIdentity.verify(pubkey, msg, sig))

        // And persisted cleanly — a subsequent load (new cache-clear, same
        // disk state) must reload the SAME regenerated identity, not
        // regenerate again on every call.
        clearInMemoryCache()
        assertArrayEquals(secondNodeId, OfflineIdentity.nodeId(context))
    }

    @Test
    fun regeneratesCleanlyWhenThePersistedPubkeyNoLongerMatchesTheDecryptedPrivateKey() {
        val firstNodeId = OfflineIdentity.nodeId(context)

        // Tamper only the pubkey field, leaving iv/encPrivKey (and the real
        // keystore wrap key) untouched — tryLoad decrypts fine but its
        // defensive pub.encoded == priv.generatePublicKey().encoded check
        // must catch the mismatch (pubkey_privkey_mismatch branch).
        val json = JSONObject(identityFile.readText(Charsets.UTF_8))
        val tamperedPubkey = ByteArray(32) { 0x42 }
        json.put("pubkey", android.util.Base64.encodeToString(tamperedPubkey, android.util.Base64.NO_WRAP))
        identityFile.writeText(json.toString(), Charsets.UTF_8)
        clearInMemoryCache()

        val secondNodeId = OfflineIdentity.nodeId(context)
        assertFalse(
            "a self-inconsistent file must force regeneration, never trust the mismatched pubkey",
            firstNodeId.contentEquals(secondNodeId)
        )
        val pubkey = OfflineIdentity.publicKeyBytes(context)
        val msg = "post-mismatch-regeneration".toByteArray()
        assertTrue(OfflineIdentity.verify(pubkey, msg, OfflineIdentity.sign(context, msg)))
    }

    @Test
    fun regeneratesCleanlyOnACorruptUnparseableIdentityFile() {
        OfflineIdentity.nodeId(context) // establishes a real keystore key + valid file first

        // Generic-exception catch-all: not even valid JSON.
        identityFile.writeText("{not valid json at all", Charsets.UTF_8)
        clearInMemoryCache()

        // Must not throw — regeneration is silent-to-the-caller by design
        // (see OfflineIdentity's class doc: errors are logged, never surfaced).
        val nodeId = OfflineIdentity.nodeId(context)
        assertTrue(nodeId.size == 8)
        val pubkey = OfflineIdentity.publicKeyBytes(context)
        val msg = "post-corruption-regeneration".toByteArray()
        assertTrue(OfflineIdentity.verify(pubkey, msg, OfflineIdentity.sign(context, msg)))
    }
}
