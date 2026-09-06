package com.opencall.relay.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TOPO PHASE 1: pure-JVM tests for [OfflineCallActivity.TopoPalette] — no
 * Context/View needed (every function just takes a [OfflineCallActivity.TopoMode]
 * and returns an Int colour), matching this project's no-Robolectric
 * constraint.
 */
class TopoPaletteTest {

    // android.graphics.Color's static methods throw "not mocked" under this
    // project's plain-JVM unit tests (this project has no Robolectric, and
    // no testOptions.unitTests.isReturnDefaultValues override in
    // app/build.gradle) — plain bit-arithmetic equivalents instead, exactly
    // matching Color.rgb/red/green/blue's real ARGB_8888 packing.
    private fun redOf(c: Int) = (c shr 16) and 0xFF
    private fun greenOf(c: Int) = (c shr 8) and 0xFF
    private fun blueOf(c: Int) = c and 0xFF
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private val roles: List<(OfflineCallActivity.TopoMode) -> Int> = listOf(
        OfflineCallActivity.TopoPalette::bgBase,
        OfflineCallActivity.TopoPalette::bgSurface,
        OfflineCallActivity.TopoPalette::bgRaised,
        OfflineCallActivity.TopoPalette::contour,
        OfflineCallActivity.TopoPalette::accent,
        OfflineCallActivity.TopoPalette::onAccent,
        OfflineCallActivity.TopoPalette::textPrimary,
        OfflineCallActivity.TopoPalette::textSecondary,
        OfflineCallActivity.TopoPalette::textMuted,
        OfflineCallActivity.TopoPalette::ok,
        OfflineCallActivity.TopoPalette::warn,
        OfflineCallActivity.TopoPalette::lost,
        OfflineCallActivity.TopoPalette::danger,
        OfflineCallActivity.TopoPalette::dangerBg
    )

    @Test
    fun `every role returns a distinct value per mode`() {
        for (role in roles) {
            val day = role(OfflineCallActivity.TopoMode.DAY)
            val night = role(OfflineCallActivity.TopoMode.NIGHT)
            val cliff = role(OfflineCallActivity.TopoMode.CLIFF)
            assertNotEquals("DAY == NIGHT for a role", day, night)
            assertNotEquals("NIGHT == CLIFF for a role", night, cliff)
            assertNotEquals("DAY == CLIFF for a role", day, cliff)
        }
    }

    @Test
    fun `CLIFF mode is strictly black and white — every role's channels are all equal (grayscale)`() {
        for (role in roles) {
            val c = role(OfflineCallActivity.TopoMode.CLIFF)
            val r = redOf(c)
            val g = greenOf(c)
            val b = blueOf(c)
            assertTrue("CLIFF role has a non-grayscale colour: r=$r g=$g b=$b", r == g && g == b)
        }
    }

    @Test
    fun `NIGHT mode is red monochrome — every role has zero green and zero blue`() {
        for (role in roles) {
            val c = role(OfflineCallActivity.TopoMode.NIGHT)
            assertEquals("NIGHT role has non-zero green", 0, greenOf(c))
            assertEquals("NIGHT role has non-zero blue", 0, blueOf(c))
        }
    }

    @Test
    fun `danger is never reused as an ordinary accent value in DAY mode`() {
        assertNotEquals(
            "danger must stay reserved for SOS — never equal to the ordinary accent colour",
            OfflineCallActivity.TopoPalette.danger(OfflineCallActivity.TopoMode.DAY),
            OfflineCallActivity.TopoPalette.accent(OfflineCallActivity.TopoMode.DAY)
        )
    }

    // ── compatibility layer — old NightPalette call sites still work ────────

    @Test
    fun `fg matches textPrimary for the equivalent boolean-day-night mapping`() {
        assertEquals(OfflineCallActivity.TopoPalette.textPrimary(OfflineCallActivity.TopoMode.DAY), OfflineCallActivity.TopoPalette.fg(false))
        assertEquals(OfflineCallActivity.TopoPalette.textPrimary(OfflineCallActivity.TopoMode.NIGHT), OfflineCallActivity.TopoPalette.fg(true))
    }

    @Test
    fun `the legacy night-mode red values are preserved exactly (200,0,0 and 140,0,0)`() {
        assertEquals(rgb(200, 0, 0), OfflineCallActivity.TopoPalette.fg(true))
        assertEquals(rgb(140, 0, 0), OfflineCallActivity.TopoPalette.mutedFg(true))
    }
}
