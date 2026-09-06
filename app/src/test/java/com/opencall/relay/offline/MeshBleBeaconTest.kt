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

    // ── BUG (MAKE THE INVITE ACTUALLY TRANSMIT) FIX PART 4: invite-target field ──

    @Test
    fun `round trip with an active invite target carries it through decode`() {
        val bytes = MeshBleBeacon.OcpBeaconPayload.encode(
            nodeId = 0x1122334455667788L, sosActive = false, hasFix = false, batteryLow = false,
            batteryPercent = null, inviteTarget = 0x01A30D
        )
        val decoded = MeshBleBeacon.OcpBeaconPayload.decode(bytes)!!
        assertEquals(0x1122334455667788L, decoded.nodeId)
        assertEquals(0x01A30D, decoded.inviteTarget)
    }

    @Test
    fun `no invite target means the field is absent on decode, not zero`() {
        val bytes = MeshBleBeacon.OcpBeaconPayload.encode(1L, false, false, false, null, inviteTarget = null)
        val decoded = MeshBleBeacon.OcpBeaconPayload.decode(bytes)!!
        assertNull(decoded.inviteTarget)
    }

    @Test
    fun `an older build's 10-byte payload (no invite fields at all) still decodes fine`() {
        // Exactly what encode() produced before this field existed — the
        // additive-field compatibility this app's HELLO capability byte
        // already relies on (see MeshSigner.decodeHelloInner's doc).
        val legacyBytes = MeshBleBeacon.OcpBeaconPayload.encode(1L, sosActive = false, hasFix = false, batteryLow = false, batteryPercent = 77)
        assertEquals(10, legacyBytes.size)
        val decoded = MeshBleBeacon.OcpBeaconPayload.decode(legacyBytes)!!
        assertEquals(77, decoded.batteryPercent)
        assertNull(decoded.inviteTarget)
    }

    @Test
    fun `shortNodeId truncates to the low 24 bits, matching the DNS-SD id-gp convention`() {
        assertEquals(0x0001A30D, MeshBleBeacon.OcpBeaconPayload.shortNodeId(0x7fff00000001A30DL))
        assertEquals(0, MeshBleBeacon.OcpBeaconPayload.shortNodeId(0x1000000L))
    }

    @Test
    fun `an invite target matching the local short id is the match a scanner acts on`() {
        // BLE: invite adv target=$shortId / BLE: invite seen ... match=$b —
        // the actual comparison MeshBleBeacon.handleScanResult performs,
        // exercised here at the codec level without any Android BLE API.
        val hostNodeId = 0x7fff00000001A30DL
        val myNodeId = 0x0000000001A30DL // same low 24 bits, different device
        val bytes = MeshBleBeacon.OcpBeaconPayload.encode(
            hostNodeId, sosActive = false, hasFix = false, batteryLow = false, batteryPercent = null,
            inviteTarget = MeshBleBeacon.OcpBeaconPayload.shortNodeId(myNodeId)
        )
        val decoded = MeshBleBeacon.OcpBeaconPayload.decode(bytes)!!
        val mine = MeshBleBeacon.OcpBeaconPayload.shortNodeId(myNodeId)
        assertEquals(mine, decoded.inviteTarget)
    }

    // ── PART "HASSLE-FREE JOIN" 2.1/2.4: open-group advertisement round trip ──

    @Test
    fun `an open-group advertisement round-trips — groupOpen survives encode then decode`() {
        val bytes = MeshBleBeacon.OcpBeaconPayload.encode(
            nodeId = 0x1122334455667788L, sosActive = false, hasFix = false, batteryLow = false,
            batteryPercent = null, groupOpen = true
        )
        val decoded = MeshBleBeacon.OcpBeaconPayload.decode(bytes)!!
        assertEquals(0x1122334455667788L, decoded.nodeId)
        assertTrue(decoded.groupOpen)
    }

    @Test
    fun `groupOpen defaults to false when not set`() {
        val bytes = MeshBleBeacon.OcpBeaconPayload.encode(
            nodeId = 1L, sosActive = false, hasFix = false, batteryLow = false, batteryPercent = null
        )
        assertEquals(false, MeshBleBeacon.OcpBeaconPayload.decode(bytes)!!.groupOpen)
    }

    @Test
    fun `groupOpen composes independently with an active invite target — both survive together`() {
        val bytes = MeshBleBeacon.OcpBeaconPayload.encode(
            nodeId = 42L, sosActive = true, hasFix = false, batteryLow = false, batteryPercent = 80,
            inviteTarget = 0x0A0B0C, groupOpen = true
        )
        val decoded = MeshBleBeacon.OcpBeaconPayload.decode(bytes)!!
        assertTrue(decoded.groupOpen)
        assertEquals(0x0A0B0C, decoded.inviteTarget)
        assertTrue(decoded.sosActive)
    }

    @Test
    fun `the full host nodeId (not the truncated shortNodeId) is what an open-group advertisement carries`() {
        // Confirms 2.1's own question: BLE credential derivation needs the
        // FULL nodeId (WifiDirectManager.deriveHostNetworkName/Passphrase's
        // input), which is exactly what decoded.nodeId already is — no
        // separate field is needed for a scanner to recompute the group's
        // credentials from an open-group sighting.
        val hostNodeId = 0x7fff00000001A30DL
        val bytes = MeshBleBeacon.OcpBeaconPayload.encode(
            nodeId = hostNodeId, sosActive = false, hasFix = false, batteryLow = false,
            batteryPercent = null, groupOpen = true
        )
        val decoded = MeshBleBeacon.OcpBeaconPayload.decode(bytes)!!
        assertEquals(hostNodeId, decoded.nodeId)
        assertTrue(decoded.nodeId != MeshBleBeacon.OcpBeaconPayload.shortNodeId(hostNodeId).toLong())
    }
}
