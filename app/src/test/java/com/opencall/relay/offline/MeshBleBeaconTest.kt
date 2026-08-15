package com.opencall.relay.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PHASE 6 TRACK C: pure-JVM round-trip tests for the OCP-native BLE
 *  manufacturer-data codec — same spirit as MeshLocationTest/MeshCarrierTest. */
class MeshBleBeaconTest {

    @Test
    fun `round trip with all flags set and a known battery level`() {
        val bytes = MeshBleBeacon.OcpBeaconPayload.encode(
            nodeId = 0x1122334455667788L, sosActive = true, hasFix = true, batteryLow = true, batteryPercent = 42
        )
        val decoded = MeshBleBeacon.OcpBeaconPayload.decode(bytes)!!
        assertEquals(0x1122334455667788L, decoded.nodeId)
        assertTrue(decoded.sosActive)
        assertTrue(decoded.hasFix)
        assertTrue(decoded.batteryLow)
        assertEquals(42, decoded.batteryPercent)
    }

    @Test
    fun `round trip with no flags and unknown battery`() {
        val bytes = MeshBleBeacon.OcpBeaconPayload.encode(
            nodeId = 1L, sosActive = false, hasFix = false, batteryLow = false, batteryPercent = null
        )
        val decoded = MeshBleBeacon.OcpBeaconPayload.decode(bytes)!!
        assertEquals(false, decoded.sosActive)
        assertEquals(false, decoded.hasFix)
        assertEquals(false, decoded.batteryLow)
        assertEquals(null, decoded.batteryPercent)
    }

    @Test
    fun `truncated payload returns null instead of throwing`() {
        val full = MeshBleBeacon.OcpBeaconPayload.encode(1L, true, true, true, 50)
        for (len in 0 until full.size) {
            val truncated = full.copyOf(len)
            val result = try {
                MeshBleBeacon.OcpBeaconPayload.decode(truncated)
            } catch (e: Exception) {
                throw AssertionError("decode threw on truncated length=$len: $e")
            }
            assertNull("expected null at truncated length=$len", result)
        }
    }
}
