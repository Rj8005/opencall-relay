package com.opencall.relay.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PART 1.6: pure-JVM tests for [MeshTransportRegistry] — fake transports
 * only, no Context/Android dependency (same spirit as every other test in
 * this project).
 */
class MeshTransportRegistryTest {

    private fun peer(id: String, resolvedShortNodeId: String?, transportId: String) =
        DiscoveredPeer(
            transportPeerId = id,
            displayName = "peer-$id",
            resolvedShortNodeId = resolvedShortNodeId,
            signalQuality = null,
            transportId = transportId
        )

    @Test
    fun `with one transport registered, merged peers are returned completely unchanged`() {
        val registry = MeshTransportRegistry()
        val peers = listOf(
            peer("aa:bb", "abc123", "wifi-direct"),
            peer("cc:dd", null, "wifi-direct")
        )
        registry.updatePeers("wifi-direct", peers)
        assertEquals(peers, registry.mergedPeers())
    }

    @Test
    fun `a peer seen on both transports appears once, with local-wifi preferred`() {
        val registry = MeshTransportRegistry()
        val wfdSighting = peer("aa:bb:cc", "shortid1", "wifi-direct")
        val localWifiSighting = peer("192.168.1.5:5000", "shortid1", "local-wifi")
        registry.updatePeers("wifi-direct", listOf(wfdSighting))
        registry.updatePeers("local-wifi", listOf(localWifiSighting))

        val merged = registry.mergedPeers()
        assertEquals(1, merged.size)
        assertEquals("local-wifi", merged.single().transportId)
    }

    @Test
    fun `preference holds regardless of which transport reported first`() {
        val registry = MeshTransportRegistry()
        val wfdSighting = peer("aa:bb:cc", "shortid1", "wifi-direct")
        val localWifiSighting = peer("192.168.1.5:5000", "shortid1", "local-wifi")
        // Opposite registration order from the test above.
        registry.updatePeers("local-wifi", listOf(localWifiSighting))
        registry.updatePeers("wifi-direct", listOf(wfdSighting))

        val merged = registry.mergedPeers()
        assertEquals(1, merged.size)
        assertEquals("local-wifi", merged.single().transportId)
    }

    @Test
    fun `two peers with no resolved short node id are never deduped against each other`() {
        val registry = MeshTransportRegistry()
        val a = peer("aa:bb", null, "wifi-direct")
        val b = peer("cc:dd", null, "wifi-direct")
        registry.updatePeers("wifi-direct", listOf(a, b))
        assertEquals(listOf(a, b), registry.mergedPeers())
    }

    @Test
    fun `dedup key is resolvedShortNodeId, not transportPeerId`() {
        // Same short node id, totally different transport-scoped ids (as
        // would genuinely happen: a WFD MAC vs an IP:port pair) — must still
        // dedupe to one row.
        val registry = MeshTransportRegistry()
        registry.updatePeers("wifi-direct", listOf(peer("02:11:22:33:44:55", "abcdef", "wifi-direct")))
        registry.updatePeers("local-wifi", listOf(peer("10.0.0.7:9000", "abcdef", "local-wifi")))
        assertEquals(1, registry.mergedPeers().size)
    }

    @Test
    fun `register and unregister track the transport set`() {
        val registry = MeshTransportRegistry()
        val fake = FakeTransport("wifi-direct")
        registry.register(fake)
        assertEquals(listOf(fake), registry.all())
        assertTrue(registry.get("wifi-direct") === fake)

        registry.unregister("wifi-direct")
        assertTrue(registry.all().isEmpty())
        assertNull(registry.get("wifi-direct"))
    }

    @Test
    fun `unregistering a transport drops its peers from the merged list`() {
        val registry = MeshTransportRegistry()
        registry.updatePeers("wifi-direct", listOf(peer("aa:bb", "id1", "wifi-direct")))
        registry.updatePeers("local-wifi", listOf(peer("10.0.0.1:1", "id2", "local-wifi")))
        assertEquals(2, registry.mergedPeers().size)

        registry.unregister("wifi-direct")
        assertEquals(1, registry.mergedPeers().size)
        assertEquals("local-wifi", registry.mergedPeers().single().transportId)
    }

    // ── mergePeers as a pure function — same scenarios, exercised directly ──

    @Test
    fun `mergePeers is a pure function usable without a registry instance`() {
        val peers = listOf(
            peer("a", "x", "wifi-direct"),
            peer("b", "x", "local-wifi"),
            peer("c", null, "wifi-direct")
        )
        val merged = MeshTransportRegistry.mergePeers(peers)
        assertEquals(2, merged.size)
        assertTrue(merged.any { it.transportPeerId == "c" })
        assertTrue(merged.any { it.transportId == "local-wifi" && it.transportPeerId == "b" })
    }

    private class FakeTransport(override val id: String) : MeshTransport {
        override val capabilities = TransportCaps(
            canHost = false, canRelay = true, supportsMedia = true, maxPeers = 32, requiresSharedNetwork = true
        )
        override fun start(callbacks: MeshTransport.Callbacks) {}
        override fun stop() {}
        override fun startDiscovery() {}
        override fun stopDiscovery() {}
        override fun invite(peer: DiscoveredPeer, onOutcome: (Boolean) -> Unit) { onOutcome(false) }
        override fun currentState(): TransportState = TransportState.IDLE
        override fun localAddress(): java.net.InetAddress? = null
    }
}
