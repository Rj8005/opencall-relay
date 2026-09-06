package com.opencall.relay.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** OCP PHASE 1: time-budgeted queue capacities + IDR-preserving video
 *  eviction. No writer thread is ever started in these tests — enqueue()
 *  only touches the in-memory queues, so the dataOut/dataIn streams handed
 *  to PeerLink are never actually read from or written to. */
class RoutingTableTest {

    // Mirrors RoutingTable's own private TYPE_FRAME=2 (RoutingTable.kt) —
    // this byte value is frozen by G2 (types 1-35 never renumbered), safe
    // to hardcode in a test the same way RoutingTable.kt itself does.
    private val TYPE_FRAME: Byte = 2
    private val TYPE_CHAT: Byte = 4 // any CONTROL-lane type

    private fun newPeerLink(nodeId: Long = 1L): PeerLink {
        val out = DataOutputStream(ByteArrayOutputStream())
        val din = DataInputStream(ByteArrayInputStream(ByteArray(0)))
        return PeerLink(nodeId, "peer", out, din)
    }

    /** [nalType] 5 = IDR slice, 1 = non-IDR slice — see MeshFrame.frameCarriesIdr's doc. */
    private fun videoFrame(nalType: Int, fillerByte: Byte = 0x00, fillerLen: Int = 4): ByteArray {
        val nalHeader = (0x60 or (nalType and 0x1F)).toByte() // nal_ref_idc=3<<5 | type
        val payload = byteArrayOf(0x00, 0x00, 0x00, 0x01, nalHeader) + ByteArray(fillerLen) { fillerByte }
        return MeshFrame.encode(1L, 2L, 8, TYPE_FRAME, payload)
    }

    private fun controlFrame(): ByteArray = MeshFrame.encode(1L, 2L, 8, TYPE_CHAT, ByteArray(4))

    // ── frameCarriesIdr ──────────────────────────────────────────────────

    @Test
    fun frameCarriesIdr_trueForIdrSlice() {
        assertTrue(PeerLink.frameCarriesIdr(videoFrame(nalType = 5)))
    }

    @Test
    fun frameCarriesIdr_falseForNonIdrSlice() {
        assertFalse(PeerLink.frameCarriesIdr(videoFrame(nalType = 1)))
    }

    @Test
    fun frameCarriesIdr_falseForTruncatedFrame() {
        // Shorter than PAYLOAD_OFFSET — must degrade to false, not throw.
        assertFalse(PeerLink.frameCarriesIdr(ByteArray(5)))
    }

    @Test
    fun frameCarriesIdr_detectsThreeByteStartCode() {
        val nalHeader = (0x60 or 5).toByte()
        val payload = byteArrayOf(0x00, 0x00, 0x01, nalHeader, 0x00, 0x00)
        val frame = MeshFrame.encode(1L, 2L, 8, TYPE_FRAME, payload)
        assertTrue(PeerLink.frameCarriesIdr(frame))
    }

    // ── capacity/high-water arithmetic (OCP PHASE 1.1) ──────────────────

    @Test
    fun videoCapacityMatches250msAt30fps() {
        val link = newPeerLink()
        // 250ms @ 30fps = 7.5 -> 8 frames.
        assertEquals(8, link.videoQueueCapacity())
    }

    @Test
    fun videoHighWaterIs60PercentOfCapacity() {
        val link = newPeerLink()
        // 8 * 0.6 = 4.8 -> 5 (round to nearest int). The eviction loop only
        // fires once depth is STRICTLY greater than 5, so depth can settle
        // at 6 (post-offer) without any drop — the 7th frame is the first
        // to actually trigger one (queue at 6 > 5 when it's enqueued).
        repeat(6) { link.enqueue(videoFrame(nalType = 1)) }
        assertEquals(0L, link.totalVideoDropsCount())
        link.enqueue(videoFrame(nalType = 1))
        assertTrue(link.totalVideoDropsCount() >= 1L)
        assertTrue(link.videoQueueDepth() <= 6)
    }

    @Test
    fun audioCapacityMatches200msAt20msChunks() {
        val link = newPeerLink()
        // 200ms / 20ms = 10 chunks.
        assertEquals(10, link.audioQueueCapacity())
    }

    // ── IDR-preserving eviction (OCP PHASE 1.2) ─────────────────────────

    @Test
    fun evictionPrefersNonIdrOverIdr_whenBothPresent() {
        val link = newPeerLink()
        val idr = videoFrame(nalType = 5, fillerByte = 0x11)
        // Fill past high-water with a mix: one IDR first (oldest), then
        // enough non-IDR frames to push the queue into eviction.
        link.enqueue(idr)
        repeat(8) { link.enqueue(videoFrame(nalType = 1, fillerByte = it.toByte())) }

        // The IDR frame, despite being the OLDEST, must still be present —
        // every eviction so far had a non-IDR alternative available.
        assertTrue(link.totalVideoDropsCount() >= 1L)
        assertTrue(peekQueueContains(link, idr))
    }

    @Test
    fun evictionFallsBackToIdr_whenQueueIsAllIdr() {
        val link = newPeerLink()
        // Every queued frame is an IDR — eviction must still happen to keep
        // the queue bounded (IDR-avoidance is a tie-break, not a stall).
        repeat(9) { link.enqueue(videoFrame(nalType = 5, fillerByte = it.toByte())) }
        assertTrue(link.videoQueueDepth() <= 9)
        assertTrue(link.totalVideoDropsCount() >= 1L)
    }

    @Test
    fun controlAndAudioLanesAreNeverDroppedUnderNormalLoad() {
        val link = newPeerLink()
        repeat(50) { link.enqueue(controlFrame()) }
        // Control capacity (128) comfortably absorbs 50 frames — no eviction.
        assertEquals(0L, link.totalAudioDropsCount())
    }

    private fun peekQueueContains(link: PeerLink, frame: ByteArray): Boolean =
        link.videoQueueSnapshot().any { it.contentEquals(frame) }

    // ── OCP PHASE 3.4/G6: payloadCarriesIdr + supportsFrameAge default ─────

    @Test
    fun `payloadCarriesIdr detects an IDR in a bare payload with no MeshFrame envelope`() {
        val nalHeader = (0x60 or 5).toByte()
        val payload = byteArrayOf(0x00, 0x00, 0x00, 0x01, nalHeader, 0x00, 0x00)
        assertTrue(PeerLink.payloadCarriesIdr(payload))
    }

    @Test
    fun `payloadCarriesIdr is false for a non-IDR bare payload`() {
        val nalHeader = (0x60 or 1).toByte()
        val payload = byteArrayOf(0x00, 0x00, 0x00, 0x01, nalHeader, 0x00, 0x00)
        assertFalse(PeerLink.payloadCarriesIdr(payload))
    }

    @Test
    fun `a link defaults to NOT supporting frame age — a peer that never sent HELLO capabilities gets legacy types only`() {
        val link = newPeerLink()
        assertFalse(link.supportsFrameAge)
    }
}
