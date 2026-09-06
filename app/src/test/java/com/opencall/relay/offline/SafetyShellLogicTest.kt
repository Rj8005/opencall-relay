package com.opencall.relay.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OFFLINE UI STEP 5: pure-JVM tests for [BatteryCliff] and [SlideToSos] —
 * both are plain functions on plain values (Int/Boolean/Long in, no Android
 * dependency), so this needs no Context/Robolectric.
 */
class SafetyShellLogicTest {

    // ── Battery cliff: enter <=15% & not charging, exit >20% & charging ────

    @Test
    fun `enters cliff at exactly 15 percent not charging`() {
        assertTrue(BatteryCliff.nextCliffState(15, charging = false, currentlyInCliff = false))
    }

    @Test
    fun `does not enter at 16 percent not charging`() {
        assertFalse(BatteryCliff.nextCliffState(16, charging = false, currentlyInCliff = false))
    }

    // PHASE 2.4: the full 14/15/16/20/21% set the task calls out explicitly —
    // 15/16/20/21 were already covered above individually; 14% (comfortably
    // inside the <=15% enter zone, not just at its boundary) was the one gap.
    @Test
    fun `enters cliff at 14 percent not charging - inside the boundary, not just at it`() {
        assertTrue(BatteryCliff.nextCliffState(14, charging = false, currentlyInCliff = false))
    }

    @Test
    fun `does not enter at 10 percent WHILE charging`() {
        assertFalse(BatteryCliff.nextCliffState(10, charging = true, currentlyInCliff = false))
    }

    @Test
    fun `exits at exactly 21 percent charging`() {
        assertFalse(BatteryCliff.nextCliffState(21, charging = true, currentlyInCliff = true))
    }

    @Test
    fun `does not exit at exactly 20 percent charging - needs GREATER than 20`() {
        assertTrue(BatteryCliff.nextCliffState(20, charging = true, currentlyInCliff = true))
    }

    @Test
    fun `does not exit at 25 percent NOT charging`() {
        assertTrue(BatteryCliff.nextCliffState(25, charging = false, currentlyInCliff = true))
    }

    @Test
    fun `the 15-20 percent band never flips state on its own either direction`() {
        // Sitting anywhere in the hysteresis band changes nothing regardless
        // of charging state, for BOTH starting states.
        for (pct in 16..20) {
            assertFalse("pct=$pct should not enter from outside", BatteryCliff.nextCliffState(pct, charging = false, currentlyInCliff = false))
            assertTrue("pct=$pct should not exit from inside", BatteryCliff.nextCliffState(pct, charging = true, currentlyInCliff = true))
        }
    }

    @Test
    fun `cannot oscillate across a simulated battery drain and recharge cycle`() {
        var inCliff = false
        // Drain from 30% to 10% while not charging, then recharge to 25%.
        val pctSequence = listOf(30, 25, 20, 18, 16, 15, 14, 12, 10, 12, 15, 18, 20, 21, 22, 25)
        val chargingSequence = List(9) { false } + List(7) { true }
        val transitions = mutableListOf<Boolean>()
        pctSequence.zip(chargingSequence).forEach { (pct, charging) ->
            inCliff = BatteryCliff.nextCliffState(pct, charging, inCliff)
            transitions.add(inCliff)
        }
        // Enters exactly once (at pct=15, not charging) and exits exactly
        // once (at pct=21, charging) - never toggles back and forth.
        var flips = 0
        for (i in 1 until transitions.size) if (transitions[i] != transitions[i - 1]) flips++
        assertEquals(2, flips)
    }

    // ── Slide-to-SOS hold timing: fires at/after 3000ms, never before ──────

    @Test
    fun `does not fire at 2999ms`() {
        assertFalse(SlideToSos.shouldFire(2999L))
    }

    @Test
    fun `fires at exactly 3000ms`() {
        assertTrue(SlideToSos.shouldFire(3000L))
    }

    @Test
    fun `fires at 3001ms`() {
        assertTrue(SlideToSos.shouldFire(3001L))
    }

    @Test
    fun `does not fire at 0ms (touch-down instant)`() {
        assertFalse(SlideToSos.shouldFire(0L))
    }

    @Test
    fun `haptic intensity ramps from 0 to 1 across the hold and clamps beyond it`() {
        assertEquals(0f, SlideToSos.hapticIntensityFraction(0L), 0.001f)
        assertEquals(0.5f, SlideToSos.hapticIntensityFraction(1500L), 0.001f)
        assertEquals(1f, SlideToSos.hapticIntensityFraction(3000L), 0.001f)
        assertEquals(1f, SlideToSos.hapticIntensityFraction(5000L), 0.001f) // clamped, never > 1
    }
}
