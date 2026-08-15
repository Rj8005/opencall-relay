package com.opencall.relay.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PHASE 7A STEP 5: pure-JVM tests for the display-name sanitizer/validator
 * and OfflineIdentity.verify's defensive length checks -- the pieces of this
 * file that need no Android Context. Key generation/persistence/Keystore
 * wrapping are NOT covered here (see MeshSignerTest's class doc); those need
 * a real AndroidKeyStore that only exists on-device.
 */
class OfflineIdentityTest {

    @Test
    fun `sanitizeDisplayName strips control characters and trims`() {
        assertEquals("Alice", OfflineIdentity.sanitizeDisplayName("Al\u0000ice"))
        assertEquals("Bob", OfflineIdentity.sanitizeDisplayName("  Bob  "))
    }

    @Test
    fun `sanitizeDisplayName truncates to 32 UTF-8 bytes without splitting a codepoint`() {
        val cleaned = OfflineIdentity.sanitizeDisplayName("x".repeat(40))
        assertEquals(32, cleaned.toByteArray(Charsets.UTF_8).size)

        // Each e-acute character is 2 UTF-8 bytes -- 20 of them is 40 bytes total;
        // the truncation must land on exactly 16 whole characters (32 bytes),
        // never emit a dangling half-character.
        val multiByteCleaned = OfflineIdentity.sanitizeDisplayName("\u00e9".repeat(20))
        assertEquals(16, multiByteCleaned.length)
        assertEquals(32, multiByteCleaned.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `validateDisplayName rejects blank or all-control input`() {
        assertNull(OfflineIdentity.validateDisplayName("   "))
        assertNull(OfflineIdentity.validateDisplayName("\u0000\u0001"))
    }

    @Test
    fun `validateDisplayName accepts and cleans a normal name`() {
        assertEquals("Rahul", OfflineIdentity.validateDisplayName("  Rahul  "))
    }

    @Test
    fun `verify rejects malformed pubkey or signature lengths without throwing`() {
        assertFalse(OfflineIdentity.verify(ByteArray(10), "x".toByteArray(), ByteArray(64)))
        assertFalse(OfflineIdentity.verify(ByteArray(32), "x".toByteArray(), ByteArray(10)))
    }
}
