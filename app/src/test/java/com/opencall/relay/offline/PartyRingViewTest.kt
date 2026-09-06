package com.opencall.relay.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OFFLINE UI STEP 2: pure-JVM tests for [PartyRingView]'s companion math —
 * radius log-scaling and dMax hysteresis. Neither function touches Canvas/
 * Paint/View machinery (both are plain companion functions operating on
 * Float in, Float out), so referencing them here never instantiates a real
 * View and never touches the Android stub jar's "not mocked" methods.
 */
class PartyRingViewTest {

    // ── radiusFraction: log scaling ──────────────────────────────────────

    @Test
    fun `zero distance is the centre`() {
        assertEquals(0f, PartyRingView.radiusFraction(0f, 100f), 0.001f)
    }

    @Test
    fun `distance equal to d0 (25m) is a real, non-trivial fraction`() {
        val f = PartyRingView.radiusFraction(25f, 100f)
        // ln(1+25/25)/ln(1+100/25) = ln(2)/ln(5) ~= 0.4307
        assertEquals(0.4307f, f, 0.001f)
    }

    @Test
    fun `distance equal to dMax is exactly the rim`() {
        assertEquals(1f, PartyRingView.radiusFraction(100f, 100f), 0.001f)
    }

    @Test
    fun `distance beyond dMax clamps to the rim, never past it`() {
        assertEquals(1f, PartyRingView.radiusFraction(1000f, 100f), 0.001f)
        assertEquals(1f, PartyRingView.radiusFraction(1_000_000f, 100f), 0.001f)
    }

    @Test
    fun `radius fraction is monotonically increasing with distance`() {
        val fractions = listOf(0f, 5f, 25f, 50f, 75f, 100f).map { PartyRingView.radiusFraction(it, 100f) }
        for (i in 1 until fractions.size) {
            assertTrue("expected fractions[$i]=${fractions[i]} >= fractions[${i - 1}]=${fractions[i - 1]}", fractions[i] >= fractions[i - 1])
        }
    }

    @Test
    fun `negative or non-finite distance clamps to centre instead of propagating garbage`() {
        assertEquals(0f, PartyRingView.radiusFraction(-5f, 100f), 0.001f)
        assertEquals(0f, PartyRingView.radiusFraction(Float.NaN, 100f), 0.001f)
    }

    // ── pickDMax: snap + hysteresis ──────────────────────────────────────

    @Test
    fun `first pick with no current value snaps to the natural step`() {
        assertEquals(250f, PartyRingView.pickDMax(180f, null), 0.001f)
        assertEquals(100f, PartyRingView.pickDMax(0f, null), 0.001f)
        assertEquals(2500f, PartyRingView.pickDMax(9999f, null), 0.001f) // beyond every step -> largest
    }

    @Test
    fun `growth is immediate the moment the peer exceeds the current step`() {
        assertEquals(500f, PartyRingView.pickDMax(260f, 250f), 0.001f)
    }

    @Test
    fun `shrink does not happen just under the current step - hysteresis band`() {
        // natural for 260m would be 500 already selected; now peer moves to
        // 240m (natural=250, one step below 500) - still inside the
        // hysteresis band (not comfortably under the SMALLER step), so it
        // must NOT flap back down yet.
        assertEquals(500f, PartyRingView.pickDMax(240f, 500f), 0.001f)
    }

    @Test
    fun `shrink happens once comfortably inside the next smaller step`() {
        // peer at 90m with current dMax=500 -> natural=100, well under the
        // smaller step (250) below the current one -> shrinks.
        assertEquals(100f, PartyRingView.pickDMax(90f, 500f), 0.001f)
    }

    @Test
    fun `oscillating right at a step boundary settles and cannot flap`() {
        var dMax: Float? = null
        // Bounce between 240 and 260 (straddling the 250 step) repeatedly.
        // 260's natural step is 500 (250 < 260 <= 500), so the very first
        // high reading grows dMax to 500; every subsequent reading (both
        // 240 and 260) must then stay at 500 — 240 is nowhere near
        // 500*DMAX_SHRINK_MARGIN(=200), and 260 doesn't exceed 500 either.
        val sequence = listOf(260f, 240f, 260f, 240f, 260f, 240f)
        val results = sequence.map { d -> PartyRingView.pickDMax(d, dMax).also { dMax = it } }
        results.forEach { assertEquals(500f, it, 0.001f) }
    }

    @Test
    fun `once the peer genuinely moves closer, it does eventually shrink`() {
        var dMax: Float? = PartyRingView.pickDMax(260f, null) // -> 500
        dMax = PartyRingView.pickDMax(80f, dMax) // well under 500*0.4=200 -> shrinks
        assertEquals(100f, dMax, 0.001f)
    }

    // ── PHASE 1.5a/1.5d: renderModeFor — rim-arc dispatch ────────────────

    @Test
    fun `UNKNOWN renders as a rim arc, never a point`() {
        assertEquals(PartyRingView.RenderMode.RIM_ARC, PartyRingView.renderModeFor(MeshLedger.PeerState.UNKNOWN))
    }

    @Test
    fun `BLE_ONLY renders as a rim arc, never a point`() {
        assertEquals(PartyRingView.RenderMode.RIM_ARC, PartyRingView.renderModeFor(MeshLedger.PeerState.BLE_ONLY))
    }

    @Test
    fun `neither UNKNOWN nor BLE_ONLY is ever POINT (no bearing-positioned point)`() {
        assertTrue(PartyRingView.renderModeFor(MeshLedger.PeerState.UNKNOWN) != PartyRingView.RenderMode.POINT)
        assertTrue(PartyRingView.renderModeFor(MeshLedger.PeerState.BLE_ONLY) != PartyRingView.RenderMode.POINT)
    }

    @Test
    fun `LOST renders as the search cone, LIVE and STALE render as a point`() {
        assertEquals(PartyRingView.RenderMode.LOST_CONE, PartyRingView.renderModeFor(MeshLedger.PeerState.LOST))
        assertEquals(PartyRingView.RenderMode.POINT, PartyRingView.renderModeFor(MeshLedger.PeerState.LIVE))
        assertEquals(PartyRingView.RenderMode.POINT, PartyRingView.renderModeFor(MeshLedger.PeerState.STALE))
    }

    // ── PHASE 1.5d: cached range labels recompute exactly on dMax change ──

    @Test
    fun `dMaxChanged is true on the very first pick`() {
        assertTrue(PartyRingView.dMaxChanged(250f, null))
    }

    @Test
    fun `dMaxChanged is false when dMax is unchanged — peers moved, dMax did not`() {
        assertFalse(PartyRingView.dMaxChanged(250f, 250f))
    }

    @Test
    fun `dMaxChanged is true whenever dMax actually moves, grow or shrink`() {
        assertTrue(PartyRingView.dMaxChanged(500f, 250f))
        assertTrue(PartyRingView.dMaxChanged(100f, 500f))
    }

    @Test
    fun `formatRangeLabel matches metres under 1km and km at or above it`() {
        assertEquals("0m", PartyRingView.formatRangeLabel(0f))
        assertEquals("250m", PartyRingView.formatRangeLabel(250f))
        assertEquals("999m", PartyRingView.formatRangeLabel(999f))
        assertEquals("1.0km", PartyRingView.formatRangeLabel(1000f))
        assertEquals("2.5km", PartyRingView.formatRangeLabel(2500f))
    }

    // ── PHASE 1.6d: the range-ring labels PartyRingView actually draws are
    // three formatRangeLabel(dMax * fraction) calls, fraction in
    // {0.25, 0.60, 1.0} — proving the formula is never empty for any real
    // dMax step is the off-device proxy for "cachedRingLabels is never
    // all-empty after a setPeers with non-zero dMax" (instantiating the real
    // View to read its private cachedRingLabels field would need
    // Robolectric, which this project doesn't have).

    @Test
    fun `all three range labels are non-empty for every real dMax step`() {
        val fractions = floatArrayOf(0.25f, 0.60f, 1.0f)
        for (dMax in PartyRingView.DMAX_STEPS_M) {
            for (f in fractions) {
                val label = PartyRingView.formatRangeLabel(dMax * f)
                assertTrue("label for dMax=$dMax fraction=$f was empty", label.isNotEmpty())
            }
        }
    }

    // ── PHASE 1.6d: drawn count == input count for a mixed peer-state set ──

    @Test
    fun `every PeerState maps to a non-SKIP render mode — drawn count always equals input count`() {
        val mixed = listOf(
            MeshLedger.PeerState.LIVE, MeshLedger.PeerState.STALE, MeshLedger.PeerState.LOST,
            MeshLedger.PeerState.BLE_ONLY, MeshLedger.PeerState.UNKNOWN
        )
        val drawn = mixed.count { PartyRingView.renderModeFor(it) != PartyRingView.RenderMode.SKIP }
        assertEquals(mixed.size, drawn)
    }

    @Test
    fun `no PeerState value maps to SKIP today`() {
        for (state in MeshLedger.PeerState.values()) {
            assertTrue(
                "PeerState.$state unexpectedly maps to SKIP — a peer would silently vanish from the ring",
                PartyRingView.renderModeFor(state) != PartyRingView.RenderMode.SKIP
            )
        }
    }
}
