package com.opencall.relay.dialer

import com.opencall.relay.dialer.route.Availability
import com.opencall.relay.dialer.route.CallHandle
import com.opencall.relay.dialer.route.CallRoute
import com.opencall.relay.dialer.route.CallRouteRegistry
import com.opencall.relay.dialer.route.CallTarget
import com.opencall.relay.dialer.route.CostHint
import com.opencall.relay.dialer.route.Reachability
import com.opencall.relay.dialer.telecom.EmergencyCallGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PART 2.4 test: "emergency numbers bypass the app entirely." Exercises
 *  [EmergencyCallGuard.isEmergencyNumberStatic] — the Context-free half of
 *  the check (this project has no Robolectric/mocking framework, so the
 *  Context/TelephonyManager-dependent half of [EmergencyCallGuard.isEmergencyNumber]
 *  isn't unit-testable here — see that function's own doc). This is also
 *  exactly the code path every API 26-28 device runs in production
 *  (TelephonyManager.isEmergencyNumber doesn't exist below API 29), so this
 *  is testing real production logic, not a stand-in for it. */
class EmergencyCallGuardTest {

    @Test
    fun `112, 911 and 100 are recognized`() {
        assertTrue(EmergencyCallGuard.isEmergencyNumberStatic("112"))
        assertTrue(EmergencyCallGuard.isEmergencyNumberStatic("911"))
        assertTrue(EmergencyCallGuard.isEmergencyNumberStatic("100"))
    }

    @Test
    fun `the full static fallback list is recognized`() {
        listOf("911", "112", "999", "000", "110", "119", "100", "101", "102", "108", "122")
            .forEach { assertTrue("$it should be recognized", EmergencyCallGuard.isEmergencyNumberStatic(it)) }
    }

    @Test
    fun `an ordinary phone number is not an emergency number`() {
        assertFalse(EmergencyCallGuard.isEmergencyNumberStatic("14155550100"))
        assertFalse(EmergencyCallGuard.isEmergencyNumberStatic("+919093257122"))
    }

    @Test
    fun `formatting characters do not defeat the match`() {
        assertTrue(EmergencyCallGuard.isEmergencyNumberStatic("9-1-1"))
        assertTrue(EmergencyCallGuard.isEmergencyNumberStatic(" 112 "))
    }

    @Test
    fun `a number that merely CONTAINS an emergency number is not itself one`() {
        // "1911" must not match "911" — this is a prefix/substring trap the
        // check must not fall into (a false positive here would just be
        // annoying — routed to the system dialer unnecessarily — but the
        // inverse mistake, matching too LOOSELY in a way that could
        // mis-classify an ordinary number, is exactly the kind of bug this
        // pins down).
        assertFalse(EmergencyCallGuard.isEmergencyNumberStatic("1911"))
        assertFalse(EmergencyCallGuard.isEmergencyNumberStatic("9110"))
    }

    @Test
    fun `blank input is never an emergency number`() {
        assertFalse(EmergencyCallGuard.isEmergencyNumberStatic(""))
        assertFalse(EmergencyCallGuard.isEmergencyNumberStatic("   "))
    }

    // PART B.6: the numbers named explicitly in the device-check brief —
    // individually named tests (not just membership in the bulk list above)
    // so a future regression against any ONE of these fails with its own
    // name, not a generic "list" failure.
    @Test
    fun `112 is recognized`() = assertTrue(EmergencyCallGuard.isEmergencyNumberStatic("112"))

    @Test
    fun `100 is recognized`() = assertTrue(EmergencyCallGuard.isEmergencyNumberStatic("100"))

    @Test
    fun `101 is recognized`() = assertTrue(EmergencyCallGuard.isEmergencyNumberStatic("101"))

    @Test
    fun `102 is recognized`() = assertTrue(EmergencyCallGuard.isEmergencyNumberStatic("102"))

    @Test
    fun `108 is recognized`() = assertTrue(EmergencyCallGuard.isEmergencyNumberStatic("108"))

    // PART B.6: "emergency numbers bypass the registry entirely." This
    // project has no Robolectric, so PhoneTabController.dial() itself can't
    // be driven here (it needs a real AppCompatActivity) — this instead
    // proves WHY dial()'s own isEmergencyNumber check must run before ANY
    // registry/route touch, not after or inside it: CallRouteRegistry has
    // NO emergency-number awareness of its own, by design (see CallRoute's
    // own doc) — it will happily report a plain SIM route AVAILABLE for
    // "112" exactly like any other number, because emergency-routing is
    // entirely the CALLER's responsibility, checked upstream, never the
    // registry's. If dial()'s ordering were ever broken (the guard moved
    // after the registry lookup), this is the gap that would let an
    // emergency number silently reach SimCallRoute's own permission/
    // airplane-mode gate instead of the guaranteed system handling.
    private class FakeRoute(override val id: String) : CallRoute {
        override fun canReach(target: CallTarget) = Reachability.available(CostHint.UNKNOWN)
        override fun place(target: CallTarget): CallHandle = object : CallHandle {
            override val routeId = id
            override val target = target
            override val requestAccepted = true
            override val failureReason: String? = null
            override fun disconnect() {}
        }
    }

    @Test
    fun `the registry itself has no emergency-number awareness -- the guard must run before it, not inside it`() {
        val registry = CallRouteRegistry()
        registry.register(FakeRoute("sim"))
        val emergencyNumber = "112"

        // The guard is what a real call site checks FIRST (see
        // PhoneTabController.dial): it must say true here, before the
        // target is even built.
        assertTrue(EmergencyCallGuard.isEmergencyNumberStatic(emergencyNumber))

        // And separately: the registry provides no safety net of its own —
        // it reports "112" as an ordinary AVAILABLE SIM target, proving the
        // guard is the ONLY thing standing between an emergency number and
        // an ordinary route, not a redundant second check.
        val result = registry.routesFor(CallTarget(e164Number = emergencyNumber))
        assertEquals(Availability.AVAILABLE, result.single().second.availability)
    }
}
