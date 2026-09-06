package com.opencall.relay.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PART 2.2/2.7: pure-JVM tests for [PeerTrustStore.evaluate] — the decision
 *  core behind VERIFIED-vs-TOFU pairing and impersonation refusal. No
 *  Context/file I/O involved (see [PeerTrustStore.record] for the thin
 *  Context-backed wrapper around this). */
class PeerTrustStoreTest {

    private val nodeId = 0x00000000004d2000L
    private val pubkeyA = "AAAA"
    private val pubkeyB = "BBBB"

    @Test
    fun `first sighting is recorded at whatever level it arrived at`() {
        val outcomeTofu = PeerTrustStore.evaluate(null, nodeId, pubkeyA, TrustLevel.TOFU)
        assertEquals(PeerTrustStore.TrustOutcome.Recorded(TrustLevel.TOFU), outcomeTofu)

        val outcomeVerified = PeerTrustStore.evaluate(null, nodeId, pubkeyA, TrustLevel.VERIFIED)
        assertEquals(PeerTrustStore.TrustOutcome.Recorded(TrustLevel.VERIFIED), outcomeVerified)
    }

    @Test
    fun `same pubkey arriving TOFU after VERIFIED stays VERIFIED — never demoted`() {
        val existing = TrustRecord(pubkeyA, TrustLevel.VERIFIED)
        val outcome = PeerTrustStore.evaluate(existing, nodeId, pubkeyA, TrustLevel.TOFU)
        assertEquals(PeerTrustStore.TrustOutcome.Recorded(TrustLevel.VERIFIED), outcome)
    }

    @Test
    fun `same pubkey arriving VERIFIED after TOFU is promoted to VERIFIED`() {
        val existing = TrustRecord(pubkeyA, TrustLevel.TOFU)
        val outcome = PeerTrustStore.evaluate(existing, nodeId, pubkeyA, TrustLevel.VERIFIED)
        assertEquals(PeerTrustStore.TrustOutcome.Recorded(TrustLevel.VERIFIED), outcome)
    }

    @Test
    fun `a VERIFIED record with a DIFFERENT incoming pubkey is refused as impersonation`() {
        val existing = TrustRecord(pubkeyA, TrustLevel.VERIFIED)
        val outcome = PeerTrustStore.evaluate(existing, nodeId, pubkeyB, TrustLevel.TOFU)
        assertTrue(outcome is PeerTrustStore.TrustOutcome.ImpersonationRefused)
        val refused = outcome as PeerTrustStore.TrustOutcome.ImpersonationRefused
        assertEquals(nodeId, refused.nodeId)
        assertEquals(pubkeyA, refused.verifiedPubkeyB64)
        assertEquals(pubkeyB, refused.incomingPubkeyB64)
    }

    @Test
    fun `a VERIFIED record refuses a DIFFERENT pubkey even when the new sighting is itself a QR scan`() {
        // A second "verified" claim does not get to silently override the first.
        val existing = TrustRecord(pubkeyA, TrustLevel.VERIFIED)
        val outcome = PeerTrustStore.evaluate(existing, nodeId, pubkeyB, TrustLevel.VERIFIED)
        assertTrue(outcome is PeerTrustStore.TrustOutcome.ImpersonationRefused)
    }

    @Test
    fun `a TOFU-only record with a DIFFERENT pubkey is NOT refused — a normal key rotation`() {
        val existing = TrustRecord(pubkeyA, TrustLevel.TOFU)
        val outcome = PeerTrustStore.evaluate(existing, nodeId, pubkeyB, TrustLevel.TOFU)
        assertEquals(PeerTrustStore.TrustOutcome.Recorded(TrustLevel.TOFU), outcome)
    }

    @Test
    fun `trust JSON round trips through encode then decode`() {
        val entries = mapOf(
            nodeId to TrustRecord(pubkeyA, TrustLevel.VERIFIED),
            (nodeId + 1) to TrustRecord(pubkeyB, TrustLevel.TOFU)
        )
        val json = PeerTrustStore.encodeTrustJson(entries)
        val decoded = PeerTrustStore.decodeTrustJson(json)
        assertEquals(entries, decoded)
    }

    @Test
    fun `malformed trust JSON decodes to an empty map, never throws`() {
        assertEquals(emptyMap<Long, TrustRecord>(), PeerTrustStore.decodeTrustJson("not json"))
    }
}
