package com.opencall.relay.offline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** PART D-REMEDIATION: pure-JVM tests for OCP's own [OpticalFountain]/
 *  [OpticalFountainDecoder] — independent design, no external reference. */
class OpticalFountainTest {

    private val symbolSize = 64

    @Test
    fun `a 32KB payload reconstructs from a shuffled subset with 20 percent dropped`() {
        val payload = ByteArray(32 * 1024)
        Random(42).nextBytes(payload)
        val payloadId = 777
        val k = OpticalFountain.symbolCount(payload.size, symbolSize)
        val totalFrames = (k * 2.0).toInt()
        val allFrames = (0 until totalFrames).map { OpticalFountain.encodeFrame(payloadId, payload, symbolSize, it) }

        val shuffled = allFrames.shuffled(Random(1234))
        val dropped = shuffled.drop((shuffled.size * 0.2).toInt())

        val decoder = OpticalFountainDecoder(symbolSize)
        dropped.forEach { decoder.offer(it) }

        assertTrue("decoder did not reach completion (${decoder.progress()})", decoder.isComplete())
        assertArrayEquals(payload, decoder.reconstruct())
    }

    @Test
    fun `frames arrive in ANY order — order-independent decode`() {
        val payload = "the quick brown fox jumps over the lazy dog, repeated many times so this is bigger than one symbol"
            .repeat(20).toByteArray()
        val payloadId = 9
        val k = OpticalFountain.symbolCount(payload.size, symbolSize)
        val frames = (0 until (k + 10)).map { OpticalFountain.encodeFrame(payloadId, payload, symbolSize, it) }

        val decoder = OpticalFountainDecoder(symbolSize)
        frames.reversed().forEach { decoder.offer(it) }

        assertTrue(decoder.isComplete())
        assertArrayEquals(payload, decoder.reconstruct())
    }

    @Test
    fun `a duplicate frame is discarded, never corrupts progress`() {
        val payload = ByteArray(500) { it.toByte() }
        val payloadId = 3
        val decoder = OpticalFountainDecoder(symbolSize)
        val frame0 = OpticalFountain.encodeFrame(payloadId, payload, symbolSize, 0)

        assertTrue(decoder.offer(frame0))
        assertFalse("a duplicate must not count as new progress", decoder.offer(frame0))
        assertEquals(1, decoder.progress().first)
    }

    @Test
    fun `a CRC failure on one frame drops it, never corrupts the reconstructed result`() {
        val payload = ByteArray(4000) { (it * 7).toByte() }
        val payloadId = 55
        val k = OpticalFountain.symbolCount(payload.size, symbolSize)
        val decoder = OpticalFountainDecoder(symbolSize)

        val goodFrame0 = OpticalFountain.encodeFrame(payloadId, payload, symbolSize, 0)
        val wireBytes = OpticalFountain.serializeFrame(goodFrame0)
        wireBytes[wireBytes.size - 1] = (wireBytes[wireBytes.size - 1].toInt() xor 0xFF).toByte()
        assertEquals(null, OpticalFountain.deserializeFrame(wireBytes)) // CRC mismatch — dropped at deserialize

        for (i in 0 until (k + 10)) {
            decoder.offer(OpticalFountain.encodeFrame(payloadId, payload, symbolSize, i))
        }

        assertTrue(decoder.isComplete())
        assertArrayEquals(payload, decoder.reconstruct())
    }

    @Test(expected = OpticalFountain.PayloadTooLargeException::class)
    fun `a payload over the cap is refused before a single frame is encoded`() {
        OpticalFountain.encodeFrame(1, ByteArray(OpticalFountain.MAX_PAYLOAD_BYTES + 1), symbolSize, 0)
    }

    @Test
    fun `a frame from a different payloadId is ignored, never mixed into this transfer`() {
        val payloadA = ByteArray(300) { 1 }
        val payloadB = ByteArray(300) { 2 }
        val decoder = OpticalFountainDecoder(symbolSize)
        val k = OpticalFountain.symbolCount(payloadA.size, symbolSize)
        for (i in 0 until k) decoder.offer(OpticalFountain.encodeFrame(100, payloadA, symbolSize, i))
        assertFalse(decoder.offer(OpticalFountain.encodeFrame(200, payloadB, symbolSize, 0)))
        assertTrue(decoder.isComplete())
        assertArrayEquals(payloadA, decoder.reconstruct())
    }

    @Test
    fun `a pairing-sized payload round trips`() {
        val payload = "{\"v\":1,\"nodeId\":\"00000000004d2000\"}".toByteArray()
        val decoder = OpticalFountainDecoder(symbolSize)
        val k = OpticalFountain.symbolCount(payload.size, symbolSize)
        for (i in 0 until k) decoder.offer(OpticalFountain.encodeFrame(1, payload, symbolSize, i))
        assertArrayEquals(payload, decoder.reconstruct())
    }
}
