package com.opencall.relay.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PHASE 5A: pure-JVM round-trip tests for [MeshLocation] — no Android/device
 *  dependency, runs under `./gradlew test`. */
class MeshLocationTest {

    @Test
    fun `round trip with a real fix`() {
        val original = MeshLocation(
            payloadVersion = MeshLocation.PAYLOAD_VERSION,
            hasFix = true,
            isMoving = true,
            batteryLow = false,
            msgSeq = 42L,
            latE7 = 377749000, // 37.7749
            lonE7 = -1224194000, // -122.4194
            accuracyMeters = 15,
            altitudeMeters = -50,
            batteryPercent = 63,
            unixSeconds = 1_700_000_000L,
            message = "need help"
        )
        val decoded = MeshLocation.decode(MeshLocation.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `round trip with no fix forces lat lon to zero`() {
        val original = MeshLocation.noFix(msgSeq = 7L, unixSeconds = 1_700_000_500L)
        val bytes = MeshLocation.encode(original)
        val decoded = MeshLocation.decode(bytes)
        assertNotNull(decoded)
        assertEquals(false, decoded!!.hasFix)
        assertEquals(0, decoded.latE7)
        assertEquals(0, decoded.lonE7)
    }

    @Test
    fun `a hasFix=false payload with non-zero coordinate bytes still decodes to zero`() {
        // Simulates a malformed/malicious peer: hasFix bit clear but lat/lon bytes
        // non-zero underneath — decode() must still force 0,0, never trust the raw
        // bytes once hasFix says there's no real fix.
        val withFix = MeshLocation(
            payloadVersion = MeshLocation.PAYLOAD_VERSION,
            hasFix = true,
            isMoving = false,
            batteryLow = false,
            msgSeq = 1L,
            latE7 = 123456789,
            lonE7 = 123456789,
            accuracyMeters = null,
            altitudeMeters = null,
            batteryPercent = null,
            unixSeconds = 1L,
            message = ""
        )
        val bytes = MeshLocation.encode(withFix)
        // Flip the hasFix bit off directly in the encoded bytes without touching
        // the (still non-zero) lat/lon bytes that follow it.
        bytes[1] = (bytes[1].toInt() and 0x01.inv()).toByte()
        val decoded = MeshLocation.decode(bytes)
        assertNotNull(decoded)
        assertEquals(false, decoded!!.hasFix)
        assertEquals(0, decoded.latE7)
        assertEquals(0, decoded.lonE7)
    }

    @Test
    fun `empty message round trips`() {
        val original = MeshLocation.noFix(msgSeq = 1L, unixSeconds = 1L)
        assertEquals("", original.message)
        val decoded = MeshLocation.decode(MeshLocation.encode(original))
        assertEquals("", decoded!!.message)
    }

    @Test
    fun `64 byte message round trips`() {
        val msg = "x".repeat(MeshLocation.MAX_MESSAGE_BYTES)
        val original = MeshLocation.noFix(msgSeq = 1L, unixSeconds = 1L).copy(message = msg)
        val bytes = MeshLocation.encode(original)
        assertEquals(MeshLocation.MAX_TOTAL_BYTES, bytes.size)
        val decoded = MeshLocation.decode(bytes)
        assertEquals(msg, decoded!!.message)
    }

    @Test
    fun `message over 64 bytes is truncated on encode, not thrown`() {
        val msg = "y".repeat(200)
        val original = MeshLocation.noFix(msgSeq = 1L, unixSeconds = 1L).copy(message = msg)
        val bytes = MeshLocation.encode(original)
        assertEquals(MeshLocation.MAX_TOTAL_BYTES, bytes.size)
        val decoded = MeshLocation.decode(bytes)
        assertEquals(MeshLocation.MAX_MESSAGE_BYTES, decoded!!.message.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `truncated buffer returns null instead of throwing`() {
        val full = MeshLocation.encode(MeshLocation.noFix(msgSeq = 1L, unixSeconds = 1L).copy(message = "hello"))
        // Chop it at every possible length, including inside the fixed header and
        // inside the variable-length message — none of these may throw.
        for (len in 0 until full.size) {
            val truncated = full.copyOf(len)
            val result = try {
                MeshLocation.decode(truncated)
            } catch (e: Exception) {
                throw AssertionError("decode threw on truncated length=$len: $e")
            }
            if (len < MeshLocation.HEADER_SIZE + "hello".toByteArray(Charsets.UTF_8).size) {
                assertNull("expected null at truncated length=$len", result)
            }
        }
    }

    @Test
    fun `unknown payload version returns null`() {
        val bytes = MeshLocation.encode(MeshLocation.noFix(msgSeq = 1L, unixSeconds = 1L))
        bytes[0] = (MeshLocation.PAYLOAD_VERSION + 1).toByte()
        assertNull(MeshLocation.decode(bytes))
    }

    @Test
    fun `unknown sentinels decode to null fields`() {
        val original = MeshLocation.noFix(msgSeq = 1L, unixSeconds = 1L)
        assertEquals(null, original.accuracyMeters)
        assertEquals(null, original.altitudeMeters)
        assertEquals(null, original.batteryPercent)
        val decoded = MeshLocation.decode(MeshLocation.encode(original))!!
        assertEquals(null, decoded.accuracyMeters)
        assertEquals(null, decoded.altitudeMeters)
        assertEquals(null, decoded.batteryPercent)
    }

    @Test
    fun `msgSeq round trips at the top of the uint32 range`() {
        val original = MeshLocation.noFix(msgSeq = 0xFFFFFFFEL, unixSeconds = 1L)
        val decoded = MeshLocation.decode(MeshLocation.encode(original))!!
        assertEquals(0xFFFFFFFEL, decoded.msgSeq)
        assertTrue(decoded.msgSeq > 0) // never reinterpreted as negative
    }

    // ── PHASE 5BC: v2 payload ────────────────────────────────────────────────

    @Test
    fun `v2 round trip carries every extension field`() {
        val original = MeshLocation(
            payloadVersion = MeshLocation.PAYLOAD_VERSION,
            hasFix = true,
            isMoving = true,
            batteryLow = false,
            msgSeq = 99L,
            latE7 = 377749000,
            lonE7 = -1224194000,
            accuracyMeters = 8,
            altitudeMeters = 120,
            batteryPercent = 71,
            unixSeconds = 1_700_000_100L,
            message = "on the ridge",
            locTier = MeshLocation.LOC_TIER_GPS_LIVE,
            altitudeBaroM = -85,
            pressureHpaX10 = 10132,
            headingDeg = 44, // encodes as 22*2=44, exactly representable
            speedCms = 130,
            seqSinceBoot = 4321
        )
        val decoded = MeshLocation.decode(MeshLocation.encode(original))
        assertEquals(original, decoded)
        assertEquals(2, decoded!!.payloadVersion)
        assertEquals(1013.2, decoded.pressureHpa)
    }

    @Test
    fun `v1 payload decoded by the v2 parser derives locTier from hasFix and leaves new fields unknown`() {
        // Hand-builds a payload using ONLY the v1 layout (version byte 1, no
        // extension block) — simulates an actual unupgraded v1 peer, not just an
        // object with defaults.
        val precise = java.nio.ByteBuffer.allocate(MeshLocation.HEADER_SIZE)
        precise.put(1.toByte())
        precise.put(0x01.toByte())
        precise.putInt(5)
        precise.putInt(377749000)
        precise.putInt(-1224194000)
        precise.putShort(20.toShort())
        precise.putShort(100.toShort())
        precise.put(0xFF.toByte()) // batteryPercent unknown
        precise.putInt(1_700_000_000)
        precise.put(0.toByte()) // messageLength = 0
        val decoded = MeshLocation.decode(precise.array())
        assertNotNull(decoded)
        assertEquals(1, decoded!!.payloadVersion)
        assertEquals(true, decoded.hasFix)
        assertEquals(MeshLocation.LOC_TIER_GPS_LIVE, decoded.locTier)
        assertEquals(null, decoded.altitudeBaroM)
        assertEquals(null, decoded.pressureHpaX10)
        assertEquals(null, decoded.headingDeg)
        assertEquals(null, decoded.speedCms)
        assertEquals(0, decoded.seqSinceBoot)
    }

    @Test
    fun `v1 no-fix payload decoded by the v2 parser derives locTier NONE`() {
        val precise = java.nio.ByteBuffer.allocate(MeshLocation.HEADER_SIZE)
        precise.put(1.toByte())
        precise.put(0x00.toByte()) // flags: hasFix=false
        precise.putInt(6)
        precise.putInt(0)
        precise.putInt(0)
        precise.putShort(0xFFFF.toShort())
        precise.putShort(0x8000.toShort())
        precise.put(0xFF.toByte())
        precise.putInt(1_700_000_000)
        precise.put(0.toByte())
        val decoded = MeshLocation.decode(precise.array())
        assertNotNull(decoded)
        assertEquals(MeshLocation.LOC_TIER_NONE, decoded!!.locTier)
    }

    @Test
    fun `v2 payload truncated inside the extension block returns null`() {
        val full = MeshLocation.encode(
            MeshLocation.noFix(msgSeq = 1L, unixSeconds = 1L).copy(locTier = MeshLocation.LOC_TIER_PASSIVE)
        )
        // Full v2 no-message payload is HEADER_SIZE + 0 + V2_EXTENSION_SIZE = 34.
        // Chop anywhere inside the extension block (indices 24..33).
        for (len in MeshLocation.HEADER_SIZE until full.size) {
            val truncated = full.copyOf(len)
            assertNull("expected null at truncated length=$len", MeshLocation.decode(truncated))
        }
        // And the full, untruncated payload must still decode.
        assertNotNull(MeshLocation.decode(full))
    }

    @Test
    fun `heading and speed sentinels round trip as unknown`() {
        val original = MeshLocation.noFix(msgSeq = 1L, unixSeconds = 1L)
        assertEquals(null, original.headingDeg)
        assertEquals(null, original.speedCms)
        val decoded = MeshLocation.decode(MeshLocation.encode(original))!!
        assertEquals(null, decoded.headingDeg)
        assertEquals(null, decoded.speedCms)
    }
}
