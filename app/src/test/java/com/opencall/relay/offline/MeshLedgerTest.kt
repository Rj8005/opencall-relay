package com.opencall.relay.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PEER DIRECTION READOUT: pure-JVM tests for [MeshLedger.computeVector] (the
 * decision/computation core [MeshLedger.vectorTo] delegates to — extracted
 * specifically so it's testable without a Context, see that function's doc)
 * and [formatPeerVectorRow]. Covers the state-machine boundaries, the
 * distance floor, the search cone, and the NaN-heading text fallback.
 */
class MeshLedgerTest {

    private fun entry(
        lat: Double = 45.0,
        lon: Double = -122.0,
        acc: Int? = 10,
        headingDeg: Int? = null,
        speedCms: Int? = null,
        pressureHpaX10: Int? = null,
        altitudeBaroM: Int? = null,
        recvElapsedMs: Long = 0L
    ): MeshLedger.Entry = MeshLedger.Entry(
        latE7 = (lat * 1e7).toInt(),
        lonE7 = (lon * 1e7).toInt(),
        accuracyMeters = acc,
        altitudeBaroM = altitudeBaroM,
        pressureHpaX10 = pressureHpaX10,
        headingDeg = headingDeg,
        speedCms = speedCms,
        tier = MeshLocation.LOC_TIER_GPS_LIVE,
        receivedAtMs = 0L,
        recvElapsedMs = recvElapsedMs
    )

    // ── State machine boundaries (half-open: 59/60/299/300s) ────────────────

    @Test
    fun `59s and linkUp is LIVE`() {
        val v = MeshLedger.computeVector(entry(), entry(recvElapsedMs = 0L), null, false, linkUp = true, nowElapsedMs = 59_000L, vertM = null)
        assertEquals(MeshLedger.PeerState.LIVE, v.state)
        assertEquals(59L, v.ageS)
    }

    @Test
    fun `60s and linkUp is STALE, not LIVE`() {
        val v = MeshLedger.computeVector(entry(), entry(recvElapsedMs = 0L), null, false, linkUp = true, nowElapsedMs = 60_000L, vertM = null)
        assertEquals(MeshLedger.PeerState.STALE, v.state)
    }

    @Test
    fun `299s and linkUp is still STALE`() {
        val v = MeshLedger.computeVector(entry(), entry(recvElapsedMs = 0L), null, false, linkUp = true, nowElapsedMs = 299_000L, vertM = null)
        assertEquals(MeshLedger.PeerState.STALE, v.state)
    }

    @Test
    fun `300s and linkUp flips to LOST`() {
        val v = MeshLedger.computeVector(entry(), entry(recvElapsedMs = 0L), null, false, linkUp = true, nowElapsedMs = 300_000L, vertM = null)
        assertEquals(MeshLedger.PeerState.LOST, v.state)
    }

    @Test
    fun `link down is LOST at any age, even 0s`() {
        val v = MeshLedger.computeVector(entry(), entry(recvElapsedMs = 0L), null, false, linkUp = false, nowElapsedMs = 0L, vertM = null)
        assertEquals(MeshLedger.PeerState.LOST, v.state)
    }

    @Test
    fun `no position ever, BLE seen, is BLE_ONLY`() {
        val v = MeshLedger.computeVector(entry(), null, null, hasBlePresence = true, linkUp = false, nowElapsedMs = 0L, vertM = null)
        assertEquals(MeshLedger.PeerState.BLE_ONLY, v.state)
        assertTrue(v.distM.isNaN())
    }

    @Test
    fun `no position ever, no BLE, is UNKNOWN`() {
        val v = MeshLedger.computeVector(entry(), null, null, hasBlePresence = false, linkUp = false, nowElapsedMs = 0L, vertM = null)
        assertEquals(MeshLedger.PeerState.UNKNOWN, v.state)
    }

    // ── Age can never be negative (STEP 5) ───────────────────────────────────

    @Test
    fun `age is clamped at zero even if nowElapsedMs somehow precedes recvElapsedMs`() {
        val v = MeshLedger.computeVector(entry(), entry(recvElapsedMs = 10_000L), null, false, linkUp = true, nowElapsedMs = 5_000L, vertM = null)
        assertEquals(0L, v.ageS)
        assertTrue(v.ageS >= 0L)
    }

    // ── Distance floor suppression (STEP 3) ─────────────────────────────────

    @Test
    fun `distance well below combined accuracy suppresses bearing but keeps distM`() {
        // ~1.1m apart (1e-5 deg longitude at this latitude), combined accuracy 50m.
        val mine = entry(lat = 45.0, lon = -122.0, acc = 25)
        val theirs = entry(lat = 45.0, lon = -122.00001, acc = 25, recvElapsedMs = 0L)
        val v = MeshLedger.computeVector(mine, theirs, null, false, linkUp = true, nowElapsedMs = 0L, vertM = null)
        assertTrue("expected a real (non-NaN) small distance, got ${v.distM}", !v.distM.isNaN() && v.distM < 50f)
        assertTrue("expected bearing suppressed below the floor", v.bearingTrue.isNaN())
        assertEquals(50f, v.accFloorM, 0.01f)
    }

    @Test
    fun `distance clearly above the floor keeps a real bearing`() {
        val mine = entry(lat = 45.0, lon = -122.0, acc = 5)
        val theirs = entry(lat = 45.001, lon = -122.0, acc = 5, recvElapsedMs = 0L) // ~111m north
        val v = MeshLedger.computeVector(mine, theirs, null, false, linkUp = true, nowElapsedMs = 0L, vertM = null)
        assertTrue(v.distM > v.accFloorM)
        assertTrue(!v.bearingTrue.isNaN())
        assertEquals(0.0, v.bearingTrue.toDouble(), 1.0) // due north
    }

    // ── Search cone (LOST only) ──────────────────────────────────────────────

    @Test
    fun `search cone uses the peer's own last speed when known`() {
        val theirs = entry(speedCms = 100, recvElapsedMs = 0L) // 1.0 m/s
        val v = MeshLedger.computeVector(null, theirs, null, false, linkUp = false, nowElapsedMs = 100_000L, vertM = null) // ageS=100
        assertEquals(MeshLedger.PeerState.LOST, v.state)
        assertEquals(1.0f * 100 * 0.5f, v.coneMinM, 0.01f)
        assertEquals(1.0f * 100 * 1.5f, v.coneMaxM, 0.01f)
    }

    @Test
    fun `search cone falls back to the default hiker speed when unknown`() {
        val theirs = entry(speedCms = null, recvElapsedMs = 0L)
        val v = MeshLedger.computeVector(null, theirs, null, false, linkUp = false, nowElapsedMs = 100_000L, vertM = null)
        assertEquals(1.1f * 100 * 0.5f, v.coneMinM, 0.01f)
        assertEquals(1.1f * 100 * 1.5f, v.coneMaxM, 0.01f)
    }

    @Test
    fun `search cone is NaN outside LOST state`() {
        val v = MeshLedger.computeVector(null, entry(recvElapsedMs = 0L), null, false, linkUp = true, nowElapsedMs = 10_000L, vertM = null)
        assertEquals(MeshLedger.PeerState.LIVE, v.state)
        assertTrue(v.coneMinM.isNaN())
        assertTrue(v.coneMaxM.isNaN())
    }

    // ── Vertical: null unless BOTH sides have a real pressure reading ───────

    @Test
    fun `vertM passes through null when caller supplies null`() {
        // computeVector itself trusts the caller's pre-computed vertM (the
        // "both sides real barometer" guard lives in vectorTo, which needs a
        // real MeshBarometer instance — see that function's own doc); this
        // just proves the plumbing never invents a value.
        val v = MeshLedger.computeVector(entry(), entry(recvElapsedMs = 0L), null, false, linkUp = true, nowElapsedMs = 0L, vertM = null)
        assertNull(v.vertM)
    }

    @Test
    fun `vertM passes through a real caller-supplied value unchanged`() {
        val v = MeshLedger.computeVector(entry(), entry(recvElapsedMs = 0L), null, false, linkUp = true, nowElapsedMs = 0L, vertM = 42.5f)
        assertEquals(42.5f, v.vertM!!, 0.01f)
    }

    // ── NaN heading renders as "stationary" (STEP 4) ────────────────────────

    @Test
    fun `NaN peer heading renders as stationary, not a direction, in LIVE text`() {
        val v = MeshLedger.PeerVector(
            distM = 120f, bearingTrue = 22.5f, peerHeading = Float.NaN, speed = Float.NaN,
            ageS = 10L, vertM = null, state = MeshLedger.PeerState.LIVE, accFloorM = 10f
        )
        val text = formatPeerVectorRow("Raj", v, null)
        assertTrue("expected 'stationary' in: $text", text.contains("stationary"))
        assertTrue("must not show a direction word for a NaN heading: $text", !text.contains("heading"))
    }

    @Test
    fun `real peer heading and speed render as the worked LIVE example`() {
        // bearingTrue=22.5 (NNE, "which way I walk") and peerHeading=45
        // (NE, "which way THEY move") are deliberately DIFFERENT physical
        // quantities that needn't share a cardinal — matches the task's own
        // worked example exactly, mixed cardinals included.
        val v = MeshLedger.PeerVector(
            distM = 120f, bearingTrue = 22.5f, peerHeading = 45f, speed = 1.2f,
            ageS = 10L, vertM = null, state = MeshLedger.PeerState.LIVE, accFloorM = 10f
        )
        val text = formatPeerVectorRow("Raj", v, null)
        // PHASE 3 GLOBAL UI RULE: LIVE now always carries an age token too
        // ("a bare metre value is a bug") — ageS=10L -> "10s ago".
        assertEquals("Raj — 120 m NNE · heading NE 1.2 m/s · 10s ago", text)
    }

    @Test
    fun `LOST row matches the worked example exactly`() {
        val v = MeshLedger.PeerVector(
            distM = 320f, bearingTrue = 22.5f, peerHeading = 45f, speed = Float.NaN,
            ageS = 14 * 60L, vertM = null, state = MeshLedger.PeerState.LOST, accFloorM = 10f,
            coneMinM = 340f, coneMaxM = 1010f
        )
        val text = formatPeerVectorRow("Raj", v, null)
        assertEquals("Raj — LAST SEEN 14 min ago · 320 m NNE · heading NE · search 340–1010 m", text)
    }

    @Test
    fun `distance floor renders as here within N m with no cardinal`() {
        val v = MeshLedger.PeerVector(
            distM = 8f, bearingTrue = Float.NaN, peerHeading = Float.NaN, speed = Float.NaN,
            ageS = 5L, vertM = null, state = MeshLedger.PeerState.LIVE, accFloorM = 12.3f
        )
        val text = formatPeerVectorRow("Raj", v, null)
        // PHASE 3 GLOBAL UI RULE: ageS=5L -> "5s ago" appended.
        assertEquals("Raj — here (within 13 m) · stationary · 5s ago", text)
    }

    @Test
    fun `BLE_ONLY never shows a metres figure`() {
        val v = MeshLedger.PeerVector(Float.NaN, Float.NaN, Float.NaN, Float.NaN, 0L, null, MeshLedger.PeerState.BLE_ONLY)
        val text = formatPeerVectorRow("Raj", v, MeshLedger.BlePresence(-57, MeshLedger.Trend.CLOSER, 0L))
        assertEquals("Raj — nearby, getting closer", text)
        assertTrue(!text.contains("m)") && !Regex("\\d+ m\\b").containsMatchIn(text))
    }

    @Test
    fun `UNKNOWN renders direction unknown`() {
        val v = MeshLedger.PeerVector(Float.NaN, Float.NaN, Float.NaN, Float.NaN, 0L, null, MeshLedger.PeerState.UNKNOWN)
        assertEquals("Raj — direction unknown", formatPeerVectorRow("Raj", v, null))
    }

    // ── PHASE 3.10: age token present for fresh/stale fixes; never a bare
    // metre value regardless of state (null-fix states carry no metres at
    // all, so they trivially can't be "bare") ─────────────────────────────

    private fun bareMetreValue(text: String): Boolean =
        // A metres/km figure with no "ago"/"just now"/"min"/"s ago" anywhere
        // in the string would be a bare value — every state's own age/no-fix
        // wording already satisfies this, this just proves it directly.
        Regex("\\d+(\\.\\d+)?\\s*(m|km)\\b").containsMatchIn(text) &&
            !(text.contains("ago") || text.contains("just now"))

    @Test
    fun `fresh fix (LIVE) carries an age token, never a bare metre value`() {
        val v = MeshLedger.PeerVector(
            distM = 55f, bearingTrue = 10f, peerHeading = Float.NaN, speed = Float.NaN,
            ageS = 3L, vertM = null, state = MeshLedger.PeerState.LIVE, accFloorM = 5f
        )
        val text = formatPeerVectorRow("Raj", v, null)
        assertTrue("expected an age token in: $text", text.contains("ago") || text.contains("just now"))
        assertTrue("bare metre value in: $text", !bareMetreValue(text))
    }

    @Test
    fun `stale fix (STALE) carries an age token, never a bare metre value`() {
        val v = MeshLedger.PeerVector(
            distM = 200f, bearingTrue = 180f, peerHeading = Float.NaN, speed = Float.NaN,
            ageS = 150L, vertM = null, state = MeshLedger.PeerState.STALE, accFloorM = 5f
        )
        val text = formatPeerVectorRow("Raj", v, null)
        assertTrue("expected 'min ago' in: $text", text.contains("min ago"))
        assertTrue("bare metre value in: $text", !bareMetreValue(text))
    }

    @Test
    fun `null fix (UNKNOWN) has no metres to be bare — and none appear`() {
        val v = MeshLedger.PeerVector(Float.NaN, Float.NaN, Float.NaN, Float.NaN, 0L, null, MeshLedger.PeerState.UNKNOWN)
        val text = formatPeerVectorRow("Raj", v, null)
        assertTrue("unexpected metre value in UNKNOWN text: $text", !bareMetreValue(text))
    }

    @Test
    fun `LOST also never emits a bare metre value`() {
        val v = MeshLedger.PeerVector(
            distM = 320f, bearingTrue = 22.5f, peerHeading = 45f, speed = Float.NaN,
            ageS = 14 * 60L, vertM = null, state = MeshLedger.PeerState.LOST, accFloorM = 10f,
            coneMinM = 340f, coneMaxM = 1010f
        )
        val text = formatPeerVectorRow("Raj", v, null)
        assertTrue("bare metre value in LOST text: $text", !bareMetreValue(text))
    }

    // ── FIX 3: isLedgerTrackFileName — the filter that keeps
    // loadAllFromDisk from treating MeshSigner's pubkeys.json (which lives
    // in the same directory) as one of its own hex-nodeId track files. ──

    @Test
    fun `pubkeys json is excluded — the exact bug this fix closes`() {
        assertFalse(MeshLedger.isLedgerTrackFileName("pubkeys.json"))
    }

    @Test
    fun `a real hex nodeId track file is accepted`() {
        assertTrue(MeshLedger.isLedgerTrackFileName("3f2a91.json"))
        assertTrue(MeshLedger.isLedgerTrackFileName("0000000000000001.json"))
        assertTrue(MeshLedger.isLedgerTrackFileName("ffffffffffffffff.json")) // unsigned max
    }

    @Test
    fun `anything not ending in json is excluded`() {
        assertFalse(MeshLedger.isLedgerTrackFileName("3f2a91.json.tmp"))
        assertFalse(MeshLedger.isLedgerTrackFileName("3f2a91"))
    }

    @Test
    fun `an empty base name is excluded`() {
        assertFalse(MeshLedger.isLedgerTrackFileName(".json"))
    }

    @Test
    fun `any other non-hex-named json file sharing the directory is excluded too, not just pubkeys json specifically`() {
        assertFalse(MeshLedger.isLedgerTrackFileName("readme.json"))
        assertFalse(MeshLedger.isLedgerTrackFileName("backup-2024.json"))
        assertFalse(MeshLedger.isLedgerTrackFileName("nodeId with spaces.json"))
    }
}
