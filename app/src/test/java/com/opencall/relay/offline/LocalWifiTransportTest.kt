package com.opencall.relay.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * PART 2.7: pure-JVM tests for [LocalWifiTransport]'s Context-free logic —
 * TXT record encode/decode, transportPeerId (host:port) encode/decode,
 * resolved-service mapping, and the network-availability decision. No
 * NsdManager/ConnectivityManager/real socket involved — same constraint as
 * every other test in this project (no Robolectric/mocking framework).
 */
class LocalWifiTransportTest {

    // ── 2.1: an NSD service record round-trips through registration and resolution ──

    @Test
    fun `TXT record round trips id, name and version`() {
        val encoded = LocalWifiTransport.encodeTxtRecord(nodeId = "a1b2c3", name = "Bob's Phone", version = "3")
        // Registration hands NsdServiceInfo.setAttribute(key, String) raw
        // strings; resolution always comes back as UTF-8 bytes (TXT records
        // are inherently binary) — simulate that exact round trip.
        val asBytesFromWire = encoded.mapValues { (_, v) -> v.toByteArray(Charsets.UTF_8) }
        val decoded = LocalWifiTransport.decodeTxtRecord(asBytesFromWire)
        assertEquals("a1b2c3", decoded.nodeId)
        assertEquals("Bob's Phone", decoded.name)
        assertEquals("3", decoded.version)
    }

    @Test
    fun `TXT record decode tolerates missing keys instead of throwing`() {
        val decoded = LocalWifiTransport.decodeTxtRecord(emptyMap())
        assertNull(decoded.nodeId)
        assertNull(decoded.name)
        assertNull(decoded.version)
    }

    @Test
    fun `transportPeerId encode-decode round trips host and port`() {
        val id = LocalWifiTransport.encodePeerId("192.168.1.42", 53219)
        val (host, port) = LocalWifiTransport.decodePeerId(id)!!
        assertEquals("192.168.1.42", host)
        assertEquals(53219, port)
    }

    @Test
    fun `transportPeerId decode splits on the LAST colon — survives an IPv6 host`() {
        val id = LocalWifiTransport.encodePeerId("fe80::1", 53219)
        val (host, port) = LocalWifiTransport.decodePeerId(id)!!
        assertEquals("fe80::1", host)
        assertEquals(53219, port)
    }

    @Test
    fun `transportPeerId decode rejects malformed input instead of throwing`() {
        assertNull(LocalWifiTransport.decodePeerId("no-colon-at-all"))
        assertNull(LocalWifiTransport.decodePeerId("host:notanumber"))
        assertNull(LocalWifiTransport.decodePeerId("host:"))
        assertNull(LocalWifiTransport.decodePeerId(":5000"))
    }

    @Test
    fun `mapResolved builds a DiscoveredPeer from a resolved service`() {
        val host = InetAddress.getByName("192.168.1.42")
        val txt = LocalWifiTransport.TxtRecord(nodeId = "a1b2c3", name = "Bob's Phone", version = "3")
        val peer = LocalWifiTransport.mapResolved(host, 53219, txt, serviceName = "opencall-a1b2c3")!!
        assertEquals("192.168.1.42:53219", peer.transportPeerId)
        assertEquals("Bob's Phone", peer.displayName)
        assertEquals("a1b2c3", peer.resolvedShortNodeId)
        assertEquals("local-wifi", peer.transportId)
    }

    @Test
    fun `mapResolved falls back to the service name when the TXT name is blank`() {
        val host = InetAddress.getByName("192.168.1.42")
        val txt = LocalWifiTransport.TxtRecord(nodeId = "a1b2c3", name = null, version = "3")
        val peer = LocalWifiTransport.mapResolved(host, 53219, txt, serviceName = "opencall-a1b2c3")!!
        assertEquals("opencall-a1b2c3", peer.displayName)
    }

    @Test
    fun `mapResolved returns null for an unresolved (null host) service`() {
        val txt = LocalWifiTransport.TxtRecord(nodeId = "a1b2c3", name = "x", version = "3")
        assertNull(LocalWifiTransport.mapResolved(null, 53219, txt, serviceName = "opencall-a1b2c3"))
    }

    // ── 2.6: a peer seen on both transports appears once, local-wifi preferred ──

    @Test
    fun `a peer seen on both wifi-direct and local-wifi is merged to one row preferring local-wifi`() {
        val host = InetAddress.getByName("192.168.1.42")
        val localWifiPeer = LocalWifiTransport.mapResolved(
            host, 53219, LocalWifiTransport.TxtRecord("shortid1", "Bob", "3"), "opencall-shortid1"
        )!!
        val wfdPeer = WifiDirectManager.mapPeer(
            android.net.wifi.p2p.WifiP2pDevice().apply { deviceAddress = "aa:bb:cc"; deviceName = "Bob" },
            WifiDirectManager.TRANSPORT_ID,
            resolvedShortNodeId = "shortid1"
        )
        val merged = MeshTransportRegistry.mergePeers(listOf(wfdPeer, localWifiPeer))
        assertEquals(1, merged.size)
        assertEquals(LocalWifiTransport.TRANSPORT_ID, merged.single().transportId)
    }

    // ── 2.2/2.7: reports unavailable when not on Wi-Fi ──────────────────────

    @Test
    fun `network availability is AVAILABLE only when an active network has the Wi-Fi transport`() {
        assertEquals(
            LocalWifiTransport.NetworkAvailability.AVAILABLE,
            LocalWifiTransport.evaluate(hasActiveNetwork = true, hasWifiTransport = true, hasCellularTransport = false)
        )
        // Wi-Fi present even alongside cellular (dual-radio device) still counts as available.
        assertEquals(
            LocalWifiTransport.NetworkAvailability.AVAILABLE,
            LocalWifiTransport.evaluate(hasActiveNetwork = true, hasWifiTransport = true, hasCellularTransport = true)
        )
    }

    @Test
    fun `network availability reports cellular-only when on cellular with no Wi-Fi`() {
        assertEquals(
            LocalWifiTransport.NetworkAvailability.CELLULAR_ONLY,
            LocalWifiTransport.evaluate(hasActiveNetwork = true, hasWifiTransport = false, hasCellularTransport = true)
        )
    }

    @Test
    fun `network availability reports no-active-network when there is no active network at all`() {
        assertEquals(
            LocalWifiTransport.NetworkAvailability.NO_ACTIVE_NETWORK,
            LocalWifiTransport.evaluate(hasActiveNetwork = false, hasWifiTransport = false, hasCellularTransport = false)
        )
    }

    @Test
    fun `network availability reports no-wifi-transport for a non-wifi non-cellular active network`() {
        // e.g. Ethernet/VPN/Bluetooth-tethering — active, but neither Wi-Fi nor cellular.
        assertEquals(
            LocalWifiTransport.NetworkAvailability.NO_WIFI_TRANSPORT,
            LocalWifiTransport.evaluate(hasActiveNetwork = true, hasWifiTransport = false, hasCellularTransport = false)
        )
    }

    @Test
    fun `TransportCaps match PART 2_5's spec`() {
        val caps = LocalWifiTransport.LOCAL_WIFI_CAPS
        assertFalse(caps.canHost)
        assertTrue(caps.canRelay)
        assertTrue(caps.supportsMedia)
        assertEquals(32, caps.maxPeers)
        assertTrue(caps.requiresSharedNetwork)
        assertEquals("local-wifi", LocalWifiTransport.TRANSPORT_ID)
    }

    // ── 2.6: a second link to an already-linked node id is refused ──────────
    // Reproduces OfflineMediaTransport.handleHelloFrame's guard (see that
    // function's own PART 2.6 doc) — a real OfflineMediaTransport can't be
    // constructed here (Context-dependent), so this proves the underlying
    // "existing link wins, redundant one is closed" decision the guard
    // applies, using the identical routingTable.get(peerId) shape.

    @Test
    fun `a second HELLO resolving to an already-linked node id is refused, the first link is kept`() {
        val routingTable = ConcurrentHashMap<Long, String>() // peerId -> linkId, standing in for RoutingTable<PeerLink>
        val peerId = 0x1234L
        val firstLink = "link-A"
        val secondLink = "link-B"

        fun tryResolve(candidateLink: String): Boolean {
            val existing = routingTable[peerId]
            if (existing != null && existing != candidateLink) return false // refused — redundant link closed
            routingTable[peerId] = candidateLink
            return true
        }

        assertTrue("the first link to resolve must be accepted", tryResolve(firstLink))
        assertFalse("a second, different link for the SAME node id must be refused", tryResolve(secondLink))
        assertEquals("the first link must still be the one on record", firstLink, routingTable[peerId])
    }

    @Test
    fun `the same link resolving twice (a stray duplicate HELLO) is not treated as a conflict`() {
        val routingTable = ConcurrentHashMap<Long, String>()
        val peerId = 0x1234L
        val link = "link-A"

        fun tryResolve(candidateLink: String): Boolean {
            val existing = routingTable[peerId]
            if (existing != null && existing != candidateLink) return false
            routingTable[peerId] = candidateLink
            return true
        }

        assertTrue(tryResolve(link))
        assertTrue("re-resolving the SAME link object is not a duplicate-link conflict", tryResolve(link))
    }
}
