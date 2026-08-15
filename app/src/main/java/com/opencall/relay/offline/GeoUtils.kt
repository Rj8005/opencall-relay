package com.opencall.relay.offline

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * PHASE 5BC: pure-math geo helpers for the UI layer — horizontal distance and
 * bearing computed FROM COORDINATES (haversine/initial-bearing), never from a
 * magnetometer, since phone compasses are unreliable near rock and metal. Also
 * WGS84 lat/lon -> MGRS, since search-and-rescue works in MGRS, not decimal
 * degrees. No Android imports — plain math, same "unit-testable off-device"
 * spirit as MeshLocation.
 *
 * MGRS SIMPLIFICATIONS (documented, not silently wrong): this implementation
 * uses the standard 6-degree UTM zones and does not apply the Norway (zone 32V)
 * or Svalbard (31X/33X/35X/37X) irregular-zone-width exceptions, and does not
 * handle the polar regions outside -80/84 latitude (UPS) — those are rare edge
 * cases for a mountaineering use case and the error only affects which zone
 * digit is reported right at those specific boundaries, not general accuracy.
 */
object GeoUtils {
    private const val EARTH_RADIUS_M = 6_371_000.0

    // WGS84 ellipsoid constants, standard Snyder/USGS transverse Mercator series.
    private const val UTM_A = 6378137.0
    private const val UTM_F = 1.0 / 298.257223563
    private const val UTM_E2 = UTM_F * (2 - UTM_F)
    private const val UTM_E4 = UTM_E2 * UTM_E2
    private const val UTM_E6 = UTM_E4 * UTM_E2
    private const val UTM_K0 = 0.9996

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_M * c
    }

    /** Initial bearing, degrees 0-359.999, from (lat1,lon1) to (lat2,lon2). */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private val COMPASS_POINTS = arrayOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"
    )

    fun compassPoint(bearingDeg: Double): String {
        val idx = (((bearingDeg / 22.5) + 0.5).toInt()).mod(16)
        return COMPASS_POINTS[idx]
    }

    /** WGS84 lat/lon -> MGRS string, e.g. "11S MT 12345 67890". Returns a plain
     *  message (never coordinates) outside UTM's -80..84 latitude range. */
    fun toMgrs(lat: Double, lon: Double): String {
        if (lat < -80.0 || lat > 84.0) return "MGRS unavailable (outside UTM range)"

        val zone = ((lon + 180.0) / 6.0).toInt() + 1
        val lon0 = Math.toRadians((zone - 1) * 6 - 180 + 3.0)
        val latRad = Math.toRadians(lat)
        val lonRad = Math.toRadians(lon)

        val ep2 = UTM_E2 / (1 - UTM_E2)
        val nVal = UTM_A / sqrt(1 - UTM_E2 * sin(latRad).pow(2))
        val t = tan(latRad).pow(2)
        val c = ep2 * cos(latRad).pow(2)
        val aTerm = cos(latRad) * (lonRad - lon0)
        val m = UTM_A * (
            (1 - UTM_E2 / 4 - 3 * UTM_E4 / 64 - 5 * UTM_E6 / 256) * latRad -
                (3 * UTM_E2 / 8 + 3 * UTM_E4 / 32 + 45 * UTM_E6 / 1024) * sin(2 * latRad) +
                (15 * UTM_E4 / 256 + 45 * UTM_E6 / 1024) * sin(4 * latRad) -
                (35 * UTM_E6 / 3072) * sin(6 * latRad)
            )

        val easting = UTM_K0 * nVal * (
            aTerm + (1 - t + c) * aTerm.pow(3) / 6.0 +
                (5 - 18 * t + t * t + 72 * c - 58 * ep2) * aTerm.pow(5) / 120.0
            ) + 500000.0
        var northing = UTM_K0 * (
            m + nVal * tan(latRad) * (
                aTerm.pow(2) / 2.0 +
                    (5 - t + 9 * c + 4 * c * c) * aTerm.pow(4) / 24.0 +
                    (61 - 58 * t + t * t + 600 * c - 330 * ep2) * aTerm.pow(6) / 720.0
                )
            )
        if (lat < 0) northing += 10_000_000.0

        val bandLetters = "CDEFGHJKLMNPQRSTUVWX"
        val bandIdx = ((lat + 80.0) / 8.0).toInt().coerceIn(0, bandLetters.length - 1)
        val band = bandLetters[bandIdx]

        val colSets = arrayOf("ABCDEFGH", "JKLMNPQR", "STUVWXYZ")
        val colSet = colSets[(zone - 1) % 3]
        val colIdx = ((easting / 100000.0).toInt() - 1).mod(8)
        val colLetter = colSet[colIdx]

        val rowLettersBase = "ABCDEFGHJKLMNPQRSTUV"
        var rowIdx = (northing / 100000.0).toInt().mod(20)
        if (zone % 2 == 0) rowIdx = (rowIdx + 5).mod(20)
        val rowLetter = rowLettersBase[rowIdx]

        val e5 = (easting.toInt().mod(100000)).toString().padStart(5, '0')
        val n5 = (northing.toInt().mod(100000)).toString().padStart(5, '0')

        return "$zone$band $colLetter$rowLetter $e5 $n5"
    }
}
