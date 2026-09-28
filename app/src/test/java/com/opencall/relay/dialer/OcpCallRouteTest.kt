package com.opencall.relay.dialer

import com.opencall.relay.dialer.route.Availability
import com.opencall.relay.dialer.route.CallTarget
import com.opencall.relay.dialer.route.OcpCallRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** PART B.2 test: OcpCallRoute is a stub — always unavailable, always a
 *  rejected handle, never a live call. */
class OcpCallRouteTest {

    @Test
    fun `id is ocp`() {
        assertEquals("ocp", OcpCallRoute().id)
    }

    @Test
    fun `canReach is always unavailable, for any target`() {
        val route = OcpCallRoute()
        assertEquals(Availability.UNAVAILABLE, route.canReach(CallTarget(e164Number = "+14155550100")).availability)
        assertEquals(Availability.UNAVAILABLE, route.canReach(CallTarget(ocpNodeId = 42L)).availability)
    }

    @Test
    fun `place always returns a rejected handle with the not-provisioned reason`() {
        val route = OcpCallRoute()
        val handle = route.place(CallTarget(e164Number = "+14155550100"))
        assertFalse(handle.requestAccepted)
        assertEquals("OpenCall number not provisioned", handle.failureReason)
        assertEquals("ocp", handle.routeId)
    }

    @Test
    fun `disconnect on a handle that was never accepted is a harmless no-op`() {
        val handle = OcpCallRoute().place(CallTarget(e164Number = "+14155550100"))
        handle.disconnect() // must not throw
    }
}
