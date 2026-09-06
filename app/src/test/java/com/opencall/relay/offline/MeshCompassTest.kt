package com.opencall.relay.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * PEER DIRECTION READOUT STEP 6: pure-JVM tests for [smoothAzimuth] — the EMA
 * smoothing extracted out of MeshCompass specifically so it's testable without
 * a real Context (this project has no Robolectric/Mockito dependency; see that
 * function's doc).
 */
class MeshCompassTest {

    private fun angularDelta(a: Float, b: Float): Float {
        var d = abs(a - b) % 360f
        if (d > 180f) d = 360f - d
        return d
    }

    @Test
    fun `first reading seeds the EMA directly, no snap from a default origin`() {
        val (_, _, deg) = smoothAzimuth(prevSin = 0.0, prevCos = 1.0, initialized = false, newAzimuthDeg = 200.0)
        assertEquals(200f, deg, 0.01f)
    }

    @Test
    fun `smoothing across the 359 to 0 wrap moves the short way, never toward 180`() {
        // Steady-state heading near 359 deg, then a real sample at 1 deg — the
        // true motion is a 2-degree step across the wrap; a naive EMA on the
        // raw degree value would compute (359*0.85 + 1*0.15) = ~305, i.e.
        // drift most of the way toward 180 - exactly backward. The sin/cos
        // formulation must instead stay close to 0/360.
        var s = 0.0
        var c = 1.0
        var initialized = false
        val steadyReadings = listOf(358.0, 359.0, 359.0, 358.0, 359.0)
        for (az in steadyReadings) {
            val (ns, nc, _) = smoothAzimuth(s, c, initialized, az)
            s = ns; c = nc; initialized = true
        }
        val (_, _, afterWrap) = smoothAzimuth(s, c, initialized, 1.0)
        assertTrue(
            "expected the smoothed value to stay near the 359/0 wrap, got $afterWrap",
            angularDelta(afterWrap, 359f) < 10f
        )
    }

    @Test
    fun `alpha of 1 tracks the input exactly, alpha of 0 never moves`() {
        val (_, _, fullTrack) = smoothAzimuth(prevSin = 0.0, prevCos = -1.0, initialized = true, newAzimuthDeg = 45.0, alpha = 1.0)
        assertEquals(45f, fullTrack, 0.01f)
        val (_, _, frozen) = smoothAzimuth(prevSin = 0.0, prevCos = -1.0, initialized = true, newAzimuthDeg = 45.0, alpha = 0.0)
        assertEquals(180f, frozen, 0.01f) // prevSin=0,prevCos=-1 -> 180 deg, unchanged
    }

    @Test
    fun `converges toward a steady new heading over repeated samples`() {
        var s = 0.0 // starts pointing at 0 deg (sin=0, cos=1)
        var c = 1.0
        var initialized = true
        var last = 0f
        repeat(50) {
            val (ns, nc, deg) = smoothAzimuth(s, c, initialized, 90.0)
            s = ns; c = nc; last = deg
        }
        assertEquals(90f, last, 1f) // converged within 1 degree after 50 samples
    }
}
