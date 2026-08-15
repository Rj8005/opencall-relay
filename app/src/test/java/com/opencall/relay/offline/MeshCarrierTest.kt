package com.opencall.relay.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PHASE 6 TRACK A: pure-JVM round-trip tests for [MeshCarrier]'s envelope
 *  encode/decode — same "no Android dependency" spirit as MeshLocationTest. */
class MeshCarrierTest {

    private fun sampleQueued(inner: ByteArray = byteArrayOf(1, 2, 3, 4)) = MeshCarrier.Queued(
        msgId = MeshCarrier.newMsgId(),
        originId = 0x1122334455667788L,
        finalDstId = MeshFrame.BROADCAST_ID,
        innerType = 20, // TYPE_SOS
        createdUnix = 1_700_000_000L,
        expiryMins = 0,
        hopCount = 3,
        inner = inner,
        deliveredTo = mutableSetOf(0xAAL, 0xBBL)
    )

    @Test
    fun `envelope round trips every field except deliveredTo (wire-local, not part of the envelope)`() {
        val q = sampleQueued()
        val bytes = MeshCarrier.encode(q)
        val decoded = MeshCarrier.decode(bytes)!!
        assertEquals(q.msgId, decoded.msgId)
        assertEquals(q.originId, decoded.originId)
        assertEquals(q.finalDstId, decoded.finalDstId)
        assertEquals(q.innerType, decoded.innerType)
        assertEquals(q.createdUnix, decoded.createdUnix)
        assertEquals(q.expiryMins, decoded.expiryMins)
        assertEquals(q.hopCount, decoded.hopCount)
        assertTrue(q.inner.contentEquals(decoded.inner))
    }

    @Test
    fun `empty inner payload round trips`() {
        val q = sampleQueued(inner = ByteArray(0))
        val decoded = MeshCarrier.decode(MeshCarrier.encode(q))!!
        assertEquals(0, decoded.inner.size)
    }

    @Test
    fun `peekMsgId matches the msgId a full decode would produce`() {
        val q = sampleQueued()
        val bytes = MeshCarrier.encode(q)
        assertEquals(MeshCarrier.decode(bytes)!!.msgId, MeshCarrier.peekMsgId(bytes))
    }

    @Test
    fun `truncated buffer returns null instead of throwing`() {
        val full = MeshCarrier.encode(sampleQueued(inner = byteArrayOf(9, 9)))
        for (len in 0 until full.size) {
            val truncated = full.copyOf(len)
            val result = try {
                MeshCarrier.decode(truncated)
            } catch (e: Exception) {
                throw AssertionError("decode threw on truncated length=$len: $e")
            }
            if (len < full.size) assertNull("expected null at truncated length=$len", result)
        }
    }

    @Test
    fun `ack payload round trips to the same msgId`() {
        val msgId = MeshCarrier.newMsgId()
        val payload = MeshCarrier.ackPayload(msgId)
        assertEquals(msgId, MeshCarrier.decodeAckMsgId(payload))
    }

    @Test
    fun `hop count and expiry survive the uint8 and uint16 wire widths`() {
        val q = sampleQueued().copy(hopCount = 255, expiryMins = 65535)
        val decoded = MeshCarrier.decode(MeshCarrier.encode(q))!!
        assertEquals(255, decoded.hopCount)
        assertEquals(65535, decoded.expiryMins)
    }
}
