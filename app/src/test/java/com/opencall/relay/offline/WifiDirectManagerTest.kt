package com.opencall.relay.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FIX 1: pure-JVM tests for [WifiDirectManager.shouldBlockGroupTeardown] —
 * the decision core of guardedRemoveGroup/createGroup's live-group guard.
 * No WifiP2pManager/Channel needed (this project has no Robolectric) —
 * the actual chokepoint (guardedRemoveGroup) just feeds this function
 * whatever requestGroupInfo's async callback reports.
 */
class WifiDirectManagerTest {

    @Test
    fun `removeGroup is rejected when groupFormed=true with clients greater than 0`() {
        assertTrue(WifiDirectManager.shouldBlockGroupTeardown(formed = true, clientCount = 1, allowWhileLive = false))
        assertTrue(WifiDirectManager.shouldBlockGroupTeardown(formed = true, clientCount = 5, allowWhileLive = false))
    }

    @Test
    fun `removeGroup is allowed when groupFormed=false`() {
        assertFalse(WifiDirectManager.shouldBlockGroupTeardown(formed = false, clientCount = 0, allowWhileLive = false))
        // Even a nonsensical clientCount>0 with formed=false (shouldn't happen from a
        // real WifiP2pGroup, but the guard must not rely on that) doesn't block —
        // "formed" is the authoritative signal a group exists at all.
        assertFalse(WifiDirectManager.shouldBlockGroupTeardown(formed = false, clientCount = 3, allowWhileLive = false))
    }

    @Test
    fun `removeGroup is allowed when formed=true but clientCount is 0 — an empty group is safe to clear`() {
        assertFalse(WifiDirectManager.shouldBlockGroupTeardown(formed = true, clientCount = 0, allowWhileLive = false))
    }

    @Test
    fun `discovery restart (via the same guard clearStaleGroupThenDiscover consults) is rejected while a group is live`() {
        // clearStaleGroupThenDiscover's onBlocked path — which skips
        // discoverPeersInternal entirely — fires exactly when this returns
        // true, so this is the same assertion as "removeGroup is blocked"
        // from discovery's perspective: a live group blocks BOTH.
        assertTrue(WifiDirectManager.shouldBlockGroupTeardown(formed = true, clientCount = 2, allowWhileLive = false))
    }

    @Test
    fun `allowWhileLive is the deliberate disconnect() escape hatch — never bypassed by any other caller`() {
        // disconnect() (the intentional "leave this group" path) passes
        // allowWhileLive=true and must be allowed to proceed even with a
        // live, populated group — that's the whole point of leaving one.
        assertFalse(WifiDirectManager.shouldBlockGroupTeardown(formed = true, clientCount = 3, allowWhileLive = true))
        // Every other caller (pre-discovery cleanup, createGroup) passes
        // false and must still be blocked.
        assertTrue(WifiDirectManager.shouldBlockGroupTeardown(formed = true, clientCount = 3, allowWhileLive = false))
    }

    @Test
    fun `no group at all (formed=false, clients=0) never blocks, regardless of allowWhileLive`() {
        assertFalse(WifiDirectManager.shouldBlockGroupTeardown(formed = false, clientCount = 0, allowWhileLive = false))
        assertFalse(WifiDirectManager.shouldBlockGroupTeardown(formed = false, clientCount = 0, allowWhileLive = true))
    }

    // ── FIX 2c: discovery restart debounce ──────────────────────────────────

    @Test
    fun `a restart request below the age threshold is debounced`() {
        assertTrue(WifiDirectManager.shouldDebounceDiscoveryRestart(ageMs = 0L))
        assertTrue(WifiDirectManager.shouldDebounceDiscoveryRestart(ageMs = 1_000L))
        assertTrue(WifiDirectManager.shouldDebounceDiscoveryRestart(ageMs = 14_999L))
    }

    @Test
    fun `a restart request at or above the age threshold is honoured`() {
        assertFalse(WifiDirectManager.shouldDebounceDiscoveryRestart(ageMs = 15_000L))
        assertFalse(WifiDirectManager.shouldDebounceDiscoveryRestart(ageMs = 60_000L))
    }

    // ── OCP CONNECT REBUILD PART 1: state-machine legality ──────────────────

    @Test
    fun `discoverPeers-discoverServices are legal ONLY in DISCOVERING`() {
        assertTrue(WifiDirectManager.canDiscover(P2pState.DISCOVERING))
        assertFalse(WifiDirectManager.canDiscover(P2pState.IDLE))
        assertFalse(WifiDirectManager.canDiscover(P2pState.CONNECTING))
        assertFalse(WifiDirectManager.canDiscover(P2pState.CONNECTED))
        assertFalse(WifiDirectManager.canDiscover(P2pState.TEARDOWN))
    }

    @Test
    fun `connect-createGroup are legal from IDLE or DISCOVERING, never CONNECTING-CONNECTED-TEARDOWN`() {
        assertTrue(WifiDirectManager.canBeginConnect(P2pState.IDLE))
        assertTrue(WifiDirectManager.canBeginConnect(P2pState.DISCOVERING))
        assertFalse(WifiDirectManager.canBeginConnect(P2pState.CONNECTING))
        assertFalse(WifiDirectManager.canBeginConnect(P2pState.CONNECTED))
        assertFalse(WifiDirectManager.canBeginConnect(P2pState.TEARDOWN))
    }

    @Test
    fun `invitePeer is legal ONLY from CONNECTED — the opposite precondition from connect`() {
        assertTrue(WifiDirectManager.canInvite(P2pState.CONNECTED))
        assertFalse(WifiDirectManager.canInvite(P2pState.IDLE))
        assertFalse(WifiDirectManager.canInvite(P2pState.DISCOVERING))
        assertFalse(WifiDirectManager.canInvite(P2pState.CONNECTING))
        assertFalse(WifiDirectManager.canInvite(P2pState.TEARDOWN))
    }

    // ── OCP CONNECT REBUILD PART 4: BUSY retry cap + terminal reasons ───────

    @Test
    fun `BUSY retries cap at 3 total attempts`() {
        assertTrue(WifiDirectManager.shouldRetryConnectAfterBusy(attemptsMadeSoFar = 1))
        assertTrue(WifiDirectManager.shouldRetryConnectAfterBusy(attemptsMadeSoFar = 2))
        assertFalse(WifiDirectManager.shouldRetryConnectAfterBusy(attemptsMadeSoFar = 3))
        assertFalse(WifiDirectManager.shouldRetryConnectAfterBusy(attemptsMadeSoFar = 4))
    }

    @Test
    fun `reason=2 (BUSY) is the only retryable failure`() {
        assertFalse(WifiDirectManager.isTerminalConnectFailure(2)) // BUSY
    }

    @Test
    fun `reason=0 (ERROR) and reason=1 (P2P_UNSUPPORTED) are terminal — never retried`() {
        assertTrue(WifiDirectManager.isTerminalConnectFailure(0))
        assertTrue(WifiDirectManager.isTerminalConnectFailure(1))
    }

    @Test
    fun `an unrecognized failure reason is also treated as terminal, not retried indefinitely`() {
        assertTrue(WifiDirectManager.isTerminalConnectFailure(99))
    }

    // ── BUG (MAKE THE INVITE ACTUALLY TRANSMIT) FIX PART 3.2: createGroup-
    // fallback credential derivation ─────────────────────────────────────

    @Test
    fun `deriveFallbackNetworkName is order-independent — either side computes the identical string`() {
        val a = WifiDirectManager.deriveFallbackNetworkName(0xbb1c2b0c02dd475cUL.toLong(), 0x7fff00000001a30dL)
        val b = WifiDirectManager.deriveFallbackNetworkName(0x7fff00000001a30dL, 0xbb1c2b0c02dd475cUL.toLong())
        assertEquals(a, b)
        assertTrue(a.startsWith("DIRECT-ocp-"))
    }

    @Test
    fun `deriveFallbackPassphrase is order-independent — either side computes the identical string`() {
        val a = WifiDirectManager.deriveFallbackPassphrase(0xbb1c2b0c02dd475cUL.toLong(), 0x7fff00000001a30dL)
        val b = WifiDirectManager.deriveFallbackPassphrase(0x7fff00000001a30dL, 0xbb1c2b0c02dd475cUL.toLong())
        assertEquals(a, b)
    }

    @Test
    fun `deriveFallbackPassphrase is at least 8 characters (WPA2-PSK minimum)`() {
        val p = WifiDirectManager.deriveFallbackPassphrase(1L, 2L)
        assertTrue("passphrase '$p' is only ${p.length} chars", p.length >= 8)
    }

    @Test
    fun `deriveFallbackPassphrase differs for a different pair of nodeIds`() {
        val p1 = WifiDirectManager.deriveFallbackPassphrase(1L, 2L)
        val p2 = WifiDirectManager.deriveFallbackPassphrase(1L, 3L)
        assertFalse(p1 == p2)
    }

    @Test
    fun `deriveFallbackNetworkName and deriveFallbackPassphrase never produce the same string — different hash inputs`() {
        val name = WifiDirectManager.deriveFallbackNetworkName(1L, 2L)
        val pass = WifiDirectManager.deriveFallbackPassphrase(1L, 2L)
        assertFalse(name.removePrefix("DIRECT-ocp-") == pass.take(6))
    }

    // ── PART "HASSLE-FREE JOIN" 1.1: single-nodeId credential derivation ──

    @Test
    fun `deriveHostNetworkName is a pure function of the host nodeId alone`() {
        val a = WifiDirectManager.deriveHostNetworkName(0x1122334455667788L)
        val b = WifiDirectManager.deriveHostNetworkName(0x1122334455667788L)
        assertEquals(a, b)
        assertTrue(a.startsWith("DIRECT-ocp-"))
    }

    @Test
    fun `deriveHostPassphrase is a pure function of the host nodeId alone`() {
        val a = WifiDirectManager.deriveHostPassphrase(0x1122334455667788L)
        val b = WifiDirectManager.deriveHostPassphrase(0x1122334455667788L)
        assertEquals(a, b)
        assertTrue("passphrase '$a' is only ${a.length} chars", a.length >= 8)
    }

    @Test
    fun `two different host nodeIds derive different credentials`() {
        val nameA = WifiDirectManager.deriveHostNetworkName(1L)
        val nameB = WifiDirectManager.deriveHostNetworkName(2L)
        assertFalse(nameA == nameB)
        val passA = WifiDirectManager.deriveHostPassphrase(1L)
        val passB = WifiDirectManager.deriveHostPassphrase(2L)
        assertFalse(passA == passB)
    }

    @Test
    fun `deriveHostNetworkName and deriveHostPassphrase never produce the same string`() {
        val name = WifiDirectManager.deriveHostNetworkName(42L)
        val pass = WifiDirectManager.deriveHostPassphrase(42L)
        assertFalse(name.removePrefix("DIRECT-ocp-") == pass.take(6))
    }

    @Test
    fun `deriveHostNetworkName differs from deriveFallbackNetworkName for the same nodeId — independent derivations`() {
        // 1.1's host-alone formula must not collide with the pre-existing
        // two-nodeId fallback formula even when fed the same single value
        // twice (the closest analogue to "one nodeId" the two-arg version has).
        val host = WifiDirectManager.deriveHostNetworkName(7L)
        val fallback = WifiDirectManager.deriveFallbackNetworkName(7L, 7L)
        assertFalse(host == fallback)
    }

    @Test
    fun `explicit groups require API 29 (Q) or higher, negotiation is the fallback below it`() {
        assertFalse(WifiDirectManager.explicitGroupsSupported(28))
        assertTrue(WifiDirectManager.explicitGroupsSupported(29))
        assertTrue(WifiDirectManager.explicitGroupsSupported(34))
    }

    // ── BUG (GROUP FORMS, NOBODY JOINS) FIX / BUG (MAKE THE INVITE ACTUALLY
    // TRANSMIT) FIX PART 3.3: DNS-SD TXT record shape — gp is a short node
    // id again, not a MAC ────────────────────────────────────────────────

    @Test
    fun `gp is present whenever waitingForShortId is set`() {
        val record = WifiDirectManager.buildDnsSdRecord(
            displayName = "Alice", shortNodeIdHex = "abc123", protocolVersion = "7",
            waitingForShortId = "01a30d", groupNetworkName = null, groupPassphrase = null
        )
        assertEquals("01a30d", record["gp"])
    }

    @Test
    fun `ss and pw appear alongside an existing gp — re-publishing live credentials never drops the invite`() {
        val record = WifiDirectManager.buildDnsSdRecord(
            displayName = "Alice", shortNodeIdHex = "abc123", protocolVersion = "7",
            waitingForShortId = "01a30d", groupNetworkName = "DIRECT-ocp-ZNQCYD", groupPassphrase = "deadbeef1234"
        )
        assertEquals("01a30d", record["gp"])
        assertEquals("DIRECT-ocp-ZNQCYD", record["ss"])
        assertEquals("deadbeef1234", record["pw"])
    }

    @Test
    fun `gp, ss and pw are all absent from the record when null`() {
        val record = WifiDirectManager.buildDnsSdRecord(
            displayName = "Alice", shortNodeIdHex = "abc123", protocolVersion = "7",
            waitingForShortId = null, groupNetworkName = null, groupPassphrase = null
        )
        assertFalse(record.containsKey("gp"))
        assertFalse(record.containsKey("ss"))
        assertFalse(record.containsKey("pw"))
    }

    // ── BUG (QUIESCE IS DELETING THE PEER) FIX ──────────────────────────────

    @Test
    fun `the negotiate path (not explicit) skips quiesceBeforeConnect's stopPeerDiscovery step`() {
        assertTrue(WifiDirectManager.shouldSkipStopDiscoveryForQuiesce(explicit = false))
        assertFalse(WifiDirectManager.shouldSkipStopDiscoveryForQuiesce(explicit = true))
    }

    @Test
    fun `connect is not issued when the target is absent from requestPeers()`() {
        val known = listOf("aa:bb:cc:dd:ee:ff", "11:22:33:44:55:66")
        assertTrue(WifiDirectManager.isPeerPresent(known, "aa:bb:cc:dd:ee:ff"))
        assertFalse(WifiDirectManager.isPeerPresent(known, "02:57:c1:fe:9d:f2")) // the established capture's evicted peer
        assertFalse(WifiDirectManager.isPeerPresent(emptyList(), "aa:bb:cc:dd:ee:ff"))
    }

    @Test
    fun `a reason=0 failure triggers exactly one re-discover-and-retry cycle, never a loop`() {
        // First reason=0 for a deviceAddress-targeted attempt: recover.
        assertTrue(WifiDirectManager.shouldRecoverFromTerminalError(reason = 0, hasDeviceAddress = true, recoveryAttempted = false))
        // A second reason=0 for the SAME attempt (recoveryAttempted now true): no loop.
        assertFalse(WifiDirectManager.shouldRecoverFromTerminalError(reason = 0, hasDeviceAddress = true, recoveryAttempted = true))
    }

    @Test
    fun `reason=0 recovery never applies to a config with no deviceAddress (explicit-group path)`() {
        assertFalse(WifiDirectManager.shouldRecoverFromTerminalError(reason = 0, hasDeviceAddress = false, recoveryAttempted = false))
    }

    @Test
    fun `reason=1 (P2P_UNSUPPORTED) and other reasons never trigger recovery, only reason=0 does`() {
        assertFalse(WifiDirectManager.shouldRecoverFromTerminalError(reason = 1, hasDeviceAddress = true, recoveryAttempted = false))
        assertFalse(WifiDirectManager.shouldRecoverFromTerminalError(reason = 99, hasDeviceAddress = true, recoveryAttempted = false))
    }

    // ── PART 1.6: MeshTransport conformance ──────────────────────────────────

    @Test
    fun `every P2pState maps to exactly one TransportState — the mapping is total`() {
        val mapped = P2pState.values().associateWith { WifiDirectManager.mapP2pState(it) }
        assertEquals(P2pState.values().size, mapped.size)
        assertEquals(TransportState.IDLE, mapped[P2pState.IDLE])
        assertEquals(TransportState.DISCOVERING, mapped[P2pState.DISCOVERING])
        assertEquals(TransportState.CONNECTING, mapped[P2pState.CONNECTING])
        assertEquals(TransportState.CONNECTED, mapped[P2pState.CONNECTED])
        assertEquals(TransportState.TEARDOWN, mapped[P2pState.TEARDOWN])
    }

    @Test
    fun `Wi-Fi Direct's TransportCaps gates are readable and match PART 1_2's spec`() {
        val caps = WifiDirectManager.WIFI_DIRECT_CAPS
        assertTrue(caps.canHost)
        assertTrue(caps.canRelay)
        assertTrue(caps.supportsMedia)
        assertEquals(8, caps.maxPeers)
        assertFalse(caps.requiresSharedNetwork)
        assertEquals("wifi-direct", WifiDirectManager.TRANSPORT_ID)
    }

    @Test
    fun `mapPeer carries the device address, name and transport id through`() {
        val device = android.net.wifi.p2p.WifiP2pDevice().apply {
            deviceAddress = "aa:bb:cc:dd:ee:ff"
            deviceName = "Bob's Phone"
        }
        val peer = WifiDirectManager.mapPeer(device, "wifi-direct")
        assertEquals("aa:bb:cc:dd:ee:ff", peer.transportPeerId)
        assertEquals("Bob's Phone", peer.displayName)
        assertEquals("wifi-direct", peer.transportId)
        assertEquals(null, peer.resolvedShortNodeId)
        assertEquals(null, peer.signalQuality)
    }

    @Test
    fun `mapPeer falls back to the device address when the device name is blank`() {
        val device = android.net.wifi.p2p.WifiP2pDevice().apply {
            deviceAddress = "11:22:33:44:55:66"
            deviceName = ""
        }
        val peer = WifiDirectManager.mapPeer(device, "wifi-direct")
        assertEquals("11:22:33:44:55:66", peer.displayName)
    }

    @Test
    fun `mapPeer threads a resolved short node id through when supplied`() {
        val device = android.net.wifi.p2p.WifiP2pDevice().apply {
            deviceAddress = "aa:bb"
            deviceName = "X"
        }
        val peer = WifiDirectManager.mapPeer(device, "wifi-direct", resolvedShortNodeId = "a1b2c3")
        assertEquals("a1b2c3", peer.resolvedShortNodeId)
    }
}
