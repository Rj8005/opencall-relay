package com.opencall.relay.offline

import org.junit.Assert.assertTrue
import org.junit.Test

/** PHASE 6 TRACK D: pure-JVM tests for the SSID-encoding/truncation logic. */
class SosSsidBroadcastTest {

    @Test
    fun `normal name and coordinates fit within 32 bytes untruncated`() {
        val ssid = SosSsidBroadcast.buildSsid("Alice", 37.7749, -122.4194)
        assertTrue(ssid.toByteArray(Charsets.UTF_8).size <= 32)
        assertTrue(ssid.startsWith("SOS-Alice-"))
        assertTrue(ssid.contains("37.7749"))
        assertTrue(ssid.contains("-122.4194"))
    }

    @Test
    fun `long name is truncated deterministically to fit 32 bytes`() {
        val ssid = SosSsidBroadcast.buildSsid("ExtremelyLongClimberName", -89.9999, -179.9999)
        assertTrue(ssid.toByteArray(Charsets.UTF_8).size <= 32)
    }

    @Test
    fun `worst-case negative coordinates still fit`() {
        val ssid = SosSsidBroadcast.buildSsid("Bob", -89.1234, -179.1234)
        assertTrue(ssid.toByteArray(Charsets.UTF_8).size <= 32)
    }

    @Test
    fun `empty name still produces a valid ssid`() {
        val ssid = SosSsidBroadcast.buildSsid("", 0.0, 0.0)
        assertTrue(ssid.toByteArray(Charsets.UTF_8).size <= 32)
        assertTrue(ssid.startsWith("SOS-"))
    }
}
