package com.opencall.relay.offline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TOPO PHASE 1.2: pure-JVM tests for [TopoBackgroundDrawable.contourSampleYs]
 * — the sample-point generator [TopoBackgroundDrawable.onBoundsChange]
 * feeds into a real android.graphics.Path exactly once per size change.
 * Path/Canvas/Rect themselves can't be exercised here (this project has no
 * Robolectric and android.jar's stub throws on every method call under a
 * plain JVM test) — this is the SHAPE-defining logic pulled out so it's
 * testable without any of that, matching PartyRingView's
 * radiusFraction/pickDMax pattern.
 */
class TopoBackgroundDrawableTest {

    @Test
    fun `contourSampleYs is deterministic — the same inputs always produce the same points`() {
        val a = TopoBackgroundDrawable.contourSampleYs(1080f, 1920f, lineIndex = 2)
        val b = TopoBackgroundDrawable.contourSampleYs(1080f, 1920f, lineIndex = 2)
        assertArrayEquals(a, b, 0f)
    }

    @Test
    fun `different lines are not identical — six distinguishable contours, not one repeated`() {
        val lines = (0 until 6).map { TopoBackgroundDrawable.contourSampleYs(1080f, 1920f, it) }
        for (i in lines.indices) {
            for (j in lines.indices) {
                if (i == j) continue
                assertNotEquals("line $i and line $j are identical", lines[i].toList(), lines[j].toList())
            }
        }
    }

    @Test
    fun `every sample stays within the drawable's height — never a point off-canvas`() {
        val ys = TopoBackgroundDrawable.contourSampleYs(1080f, 1920f, lineIndex = 3)
        for (y in ys) {
            assertTrue("y=$y out of [0, 1920] bounds", y in -1f..1921f) // small epsilon for amplitude at the edges
        }
    }

    @Test
    fun `zero or negative size never throws and returns a non-null, fixed-length array`() {
        val zero = TopoBackgroundDrawable.contourSampleYs(0f, 0f, 0)
        val negative = TopoBackgroundDrawable.contourSampleYs(-5f, -5f, 0)
        assertEquals(25, zero.size) // sampleCount defaults to 24 -> 25 points
        assertEquals(25, negative.size)
    }

    @Test
    fun `sampleCount controls the exact array length`() {
        assertEquals(13, TopoBackgroundDrawable.contourSampleYs(1080f, 1920f, 0, sampleCount = 12).size)
    }
}
