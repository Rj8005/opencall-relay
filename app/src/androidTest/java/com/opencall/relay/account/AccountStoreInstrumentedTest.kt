package com.opencall.relay.account

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PART 5.2/TESTS: "the account store round-trips through Keystore
 * wrapping." Needs a real AndroidKeyStore — same reason
 * [com.opencall.relay.offline.OfflineIdentityTest]'s class doc excludes key
 * generation/persistence from its JVM unit tests — so this is this
 * project's first instrumented test, run on-device/emulator, not via
 * `testDebugUnitTest`.
 */
@RunWith(AndroidJUnit4::class)
class AccountStoreInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun sipCredentialRoundTripsThroughKeystoreWrapping() {
        AccountStore.setSipCredential(context, "alice", "s3cret-p@ss")
        assertEquals("s3cret-p@ss", AccountStore.decryptSipPassword(context))
        assertEquals("alice", AccountStore.get(context).sipUsername)
    }

    @Test
    fun clearSipCredentialRemovesBothUsernameAndDecryptablePassword() {
        AccountStore.setSipCredential(context, "bob", "hunter2")
        AccountStore.clearSipCredential(context)
        assertNull(AccountStore.get(context).sipUsername)
        assertNull(AccountStore.decryptSipPassword(context))
    }

    @Test
    fun simNumberRoundTripsWithVerifiedFlag() {
        AccountStore.setSimNumber(context, "+15551234567", verified = true)
        val account = AccountStore.get(context)
        assertEquals("+15551234567", account.simNumber)
        assertEquals(true, account.simVerified)
    }

    @Test
    fun nodeIdIsStableAcrossRepeatedReads() {
        val first = AccountStore.get(context).nodeIdHex
        val second = AccountStore.get(context).nodeIdHex
        assertEquals(first, second)
    }
}
