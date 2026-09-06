package com.opencall.relay.dialer

import com.opencall.relay.dialer.route.Availability
import com.opencall.relay.dialer.route.CallHandle
import com.opencall.relay.dialer.route.CallRoute
import com.opencall.relay.dialer.route.CallRouteRegistry
import com.opencall.relay.dialer.route.CallTarget
import com.opencall.relay.dialer.route.CostHint
import com.opencall.relay.dialer.route.Reachability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** PART 4.1/4.2 tests — pure, using a fake [CallRoute] (no real
 *  TelecomManager/SimCallRoute involved; SimCallRoute's own Android-facing
 *  behaviour is out of scope for this project's Robolectric-free unit
 *  tests, same constraint as every other Android-framework class here). */
class CallRouteRegistryTest {

    private class FakeHandle(
        override val routeId: String,
        override val target: CallTarget,
        override val requestAccepted: Boolean,
        override val failureReason: String? = null
    ) : CallHandle {
        var disconnectCalled = false
        override fun disconnect() { disconnectCalled = true }
    }

    private class FakeRoute(
        override val id: String,
        private val reachability: Reachability = Reachability.available(CostHint.UNKNOWN)
    ) : CallRoute {
        var placeCallCount = 0
        var lastTarget: CallTarget? = null
        override fun canReach(target: CallTarget): Reachability = reachability
        override fun place(target: CallTarget): CallHandle {
            placeCallCount++
            lastTarget = target
            return FakeHandle(id, target, requestAccepted = true)
        }
    }

    @Test
    fun `with one route registered, placeVia behaves identically to calling the route directly`() {
        val route = FakeRoute("sim")
        val registry = CallRouteRegistry()
        registry.register(route)
        val target = CallTarget(e164Number = "+14155550100")

        val handle = registry.placeVia("sim", target)

        // Exactly one place() call reached the route — the registry added
        // no retries, no fan-out, no alternate-route fallback.
        assertEquals(1, route.placeCallCount)
        assertSame(target, route.lastTarget)
        assertTrue(handle!!.requestAccepted)
        assertEquals("sim", handle.routeId)
    }

    @Test
    fun `routesFor with one route registered returns exactly that route's own canReach answer, unmodified`() {
        val availableRoute = FakeRoute("sim", Reachability.available(CostHint.METERED))
        val registry = CallRouteRegistry()
        registry.register(availableRoute)
        val target = CallTarget(e164Number = "+14155550100")

        val result = registry.routesFor(target)

        assertEquals(1, result.size)
        assertSame(availableRoute, result.single().first)
        assertEquals(Availability.AVAILABLE, result.single().second.availability)
        assertEquals(CostHint.METERED, result.single().second.cost)
    }

    @Test
    fun `an unregistered route id returns null, not a fabricated handle`() {
        val registry = CallRouteRegistry()
        registry.register(FakeRoute("sim"))
        assertNull(registry.placeVia("ocp-voip", CallTarget(e164Number = "+14155550100")))
    }

    @Test
    fun `register then unregister leaves the registry empty`() {
        val registry = CallRouteRegistry()
        val route = FakeRoute("sim")
        registry.register(route)
        assertEquals(1, registry.all().size)
        registry.unregister("sim")
        assertTrue(registry.all().isEmpty())
        assertNull(registry.get("sim"))
    }

    @Test
    fun `with two routes registered, routesFor returns both, still each route's own unmodified answer`() {
        val sim = FakeRoute("sim", Reachability.available(CostHint.METERED))
        val voip = FakeRoute("ocp-voip", Reachability.unavailable())
        val registry = CallRouteRegistry()
        registry.register(sim)
        registry.register(voip)

        val result = registry.routesFor(CallTarget(e164Number = "+14155550100"))

        assertEquals(2, result.size)
        assertEquals(Availability.AVAILABLE, result.first { it.first.id == "sim" }.second.availability)
        assertEquals(Availability.UNAVAILABLE, result.first { it.first.id == "ocp-voip" }.second.availability)
    }

    @Test
    fun `CallTarget requires at least one identifier`() {
        var threw = false
        try {
            CallTarget(e164Number = null, ocpNodeId = null)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("CallTarget with neither identifier must throw", threw)
    }
}
