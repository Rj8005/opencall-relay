package com.opencall.relay.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PEER DIRECTION READOUT: pure-JVM tests for [GeoUtils.geodesicDistanceAndBearing]
 * — the WGS84 Vincenty replacement for android.location.Location.distanceBetween
 * (see that function's doc for why the platform API can't be called directly in
 * this project's unit tests). "Known coordinate pairs" here are two
 * mathematically-derivable references, not looked-up city distances, so the
 * expected values aren't subject to transcription error:
 *   - one degree of longitude along the equator = (equatorial circumference)/360
 *     exactly, since the equator is itself a geodesic.
 *   - one degree of latitude along a meridian at the equator = the meridian
 *     radius of curvature at phi=0, times one degree in radians — meridians are
 *     geodesics too.
 */
class GeoUtilsTest {

    private val equatorialCircumferenceM = 2.0 * Math.PI * 6378137.0 // WGS84 semi-major axis

    @Test
    fun `one degree of longitude along the equator matches the equatorial circumference exactly`() {
        val expectedDistance = equatorialCircumferenceM / 360.0 // ~111319.49m
        val result = GeoUtils.geodesicDistanceAndBearing(0.0, 0.0, 0.0, 1.0)
        assertEquals(expectedDistance, result[0], 1.0) // within 1m
        assertEquals(90.0, result[1], 0.01) // due east
    }

    @Test
    fun `one degree of latitude along a meridian at the equator matches the meridian radius of curvature`() {
        val f = 1.0 / 298.257223563
        val e2 = f * (2 - f)
        val meridianRadiusAtEquator = 6378137.0 * (1 - e2) // M(0) = a(1-e^2)
        val expectedDistance = meridianRadiusAtEquator * Math.toRadians(1.0)
        val result = GeoUtils.geodesicDistanceAndBearing(0.0, 0.0, 1.0, 0.0)
        assertEquals(expectedDistance, result[0], 5.0) // within 5m
        assertEquals(0.0, result[1], 0.01) // due north
    }

    @Test
    fun `southward and westward bearings are correctly in the 180-360 range, not negated`() {
        val south = GeoUtils.geodesicDistanceAndBearing(1.0, 0.0, 0.0, 0.0)
        assertEquals(180.0, south[1], 0.01)
        val west = GeoUtils.geodesicDistanceAndBearing(0.0, 1.0, 0.0, 0.0)
        assertEquals(270.0, west[1], 0.01)
    }

    @Test
    fun `coincident points are zero distance with no crash`() {
        val result = GeoUtils.geodesicDistanceAndBearing(45.0, -122.0, 45.0, -122.0)
        assertEquals(0.0, result[0], 0.0)
        assertEquals(0.0, result[1], 0.0)
    }

    @Test
    fun `result is symmetric in distance for a real-world-scale pair`() {
        // Seattle-ish to Portland-ish — not asserting an exact km figure (that
        // would be a recalled, not derived, value), just that the geodesic is
        // well-behaved and symmetric in distance both directions.
        val forward = GeoUtils.geodesicDistanceAndBearing(47.6062, -122.3321, 45.5152, -122.6784)
        val reverse = GeoUtils.geodesicDistanceAndBearing(45.5152, -122.6784, 47.6062, -122.3321)
        assertEquals(forward[0], reverse[0], 1.0)
        assertTrue("expected a plausible ~230km distance, got ${forward[0]}", forward[0] in 200_000.0..260_000.0)
    }
}
