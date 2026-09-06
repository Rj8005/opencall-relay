package com.opencall.relay.dialer

import com.opencall.relay.dialer.telecom.EmergencyCallGuard
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
}
