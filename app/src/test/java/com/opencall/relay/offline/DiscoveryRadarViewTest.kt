package com.opencall.relay.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FIX 4: pure-JVM tests for DiscoveryRadarView's companion (angle hashing,
 * RSSI banding, sweep timing/gating, UI-state derivation) — no View/Canvas
 * needed (this project has no Robolectric), matching PartyRingViewTest's
 * approach to the same class of logic.
 */
class DiscoveryRadarViewTest {

    // ── 4a: stable angle, no jitter, no bearing invented ────────────────────

    @Test
    fun `angle is stable across repeated calls for the same nodeId`() {
        val nodeId = 0x1234567890ABCDEFL
        val first = DiscoveryRadarView.stableAngleDeg(nodeId)
        repeat(5) {
            assertEquals(first, DiscoveryRadarView.stableAngleDeg(nodeId), 0f)
        }
    }

    @Test
    fun `angle differs for different nodeIds`() {
        val a = DiscoveryRadarView.stableAngleDeg(1L)
        val b = DiscoveryRadarView.stableAngleDeg(2L)
        val c = DiscoveryRadarView.stableAngleDeg(3L)
        assertNotEquals(a, b)
        assertNotEquals(b, c)
        assertNotEquals(a, c)
    }

    @Test
    fun `angle is always within 0 to 360`() {
        listOf(0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, 42L, -999999L).forEach { id ->
            val angle = DiscoveryRadarView.stableAngleDeg(id)
            assertTrue("angle=$angle out of range for nodeId=$id", angle >= 0f && angle < 360f)
        }
    }

    // ── 4b: RSSI banding at the boundaries — coarse bands only, never metres ──

    @Test
    fun `rssi banding at the near-mid boundary`() {
        assertEquals(DiscoveryRadarView.RssiBand.NEAR, DiscoveryRadarView.bandFor(-60))
        assertEquals(DiscoveryRadarView.RssiBand.MID, DiscoveryRadarView.bandFor(-61))
    }

    @Test
    fun `rssi banding at the mid-far boundary`() {
        assertEquals(DiscoveryRadarView.RssiBand.MID, DiscoveryRadarView.bandFor(-80))
        assertEquals(DiscoveryRadarView.RssiBand.FAR, DiscoveryRadarView.bandFor(-81))
    }

    @Test
    fun `a strong signal is NEAR and a weak one is FAR`() {
        assertEquals(DiscoveryRadarView.RssiBand.NEAR, DiscoveryRadarView.bandFor(-30))
        assertEquals(DiscoveryRadarView.RssiBand.FAR, DiscoveryRadarView.bandFor(-95))
    }

    @Test
    fun `no rssi at all is UNKNOWN, never defaulted into NEAR or MID`() {
        assertEquals(DiscoveryRadarView.RssiBand.UNKNOWN, DiscoveryRadarView.bandFor(null))
    }

    @Test
    fun `no band ever maps to a metres distance — only fixed ring fractions exist`() {
        // The only radii this view can ever draw for a device are these four
        // fractions of the drawable radius — there is no path from a band
        // to any other float, so no distance-in-metres figure can render.
        val allFractions = DiscoveryRadarView.RssiBand.values().map { DiscoveryRadarView.radiusFractionForBand(it) }
        assertEquals(setOf(0.35f, 0.65f, 0.9f), allFractions.toSet())
    }

    // ── 4c: sweep timing + the activity-lifecycle/power gate ────────────────

    @Test
    fun `sweep angle is 0 at the start of a revolution and completes a full circle`() {
        assertEquals(0f, DiscoveryRadarView.sweepAngleDeg(0L, 3000L), 0f)
        assertEquals(0f, DiscoveryRadarView.sweepAngleDeg(3000L, 3000L), 0f) // wraps exactly at the period
        assertEquals(180f, DiscoveryRadarView.sweepAngleDeg(1500L, 3000L), 0.01f)
    }

    @Test
    fun `sweeping requires the tab visible, the activity resumed, and no battery cliff`() {
        assertTrue(DiscoveryRadarView.shouldSweep(tabVisible = true, activityResumed = true, batteryCliffActive = false))
        assertFalse(DiscoveryRadarView.shouldSweep(tabVisible = false, activityResumed = true, batteryCliffActive = false))
        assertFalse(DiscoveryRadarView.shouldSweep(tabVisible = true, activityResumed = false, batteryCliffActive = false))
        assertFalse(DiscoveryRadarView.shouldSweep(tabVisible = true, activityResumed = true, batteryCliffActive = true))
    }

    // ── 4e: five UI states, data-driven ──────────────────────────────────────

    private fun device(status: DiscoveryRadarView.RadarStatus, id: Long = 1L) =
        DiscoveryRadarView.RadarDevice(id, "peer-$id", null, status)

    @Test
    fun `no devices and no live group is the sweeping state`() {
        assertEquals(DiscoveryRadarView.RadarUiState.NO_DEVICES_SWEEPING, DiscoveryRadarView.uiStateFor(emptyList(), groupLive = false))
    }

    @Test
    fun `devices present with nobody inviting or joined is the devices-found state`() {
        val devices = listOf(device(DiscoveryRadarView.RadarStatus.NOT_INVITED))
        assertEquals(DiscoveryRadarView.RadarUiState.DEVICES_FOUND, DiscoveryRadarView.uiStateFor(devices, groupLive = false))
    }

    @Test
    fun `any device inviting is the connecting state`() {
        val devices = listOf(device(DiscoveryRadarView.RadarStatus.NOT_INVITED, 1L), device(DiscoveryRadarView.RadarStatus.INVITING, 2L))
        assertEquals(DiscoveryRadarView.RadarUiState.CONNECTING, DiscoveryRadarView.uiStateFor(devices, groupLive = false))
    }

    @Test
    fun `any device joined is the connected state`() {
        val devices = listOf(device(DiscoveryRadarView.RadarStatus.INVITING, 1L), device(DiscoveryRadarView.RadarStatus.JOINED, 2L))
        assertEquals(DiscoveryRadarView.RadarUiState.CONNECTED, DiscoveryRadarView.uiStateFor(devices, groupLive = false))
    }

    @Test
    fun `a live group always wins, regardless of device statuses`() {
        val devices = listOf(device(DiscoveryRadarView.RadarStatus.JOINED))
        assertEquals(DiscoveryRadarView.RadarUiState.GROUP_LIVE_STOPPED, DiscoveryRadarView.uiStateFor(devices, groupLive = true))
        assertEquals(DiscoveryRadarView.RadarUiState.GROUP_LIVE_STOPPED, DiscoveryRadarView.uiStateFor(emptyList(), groupLive = true))
    }
}
