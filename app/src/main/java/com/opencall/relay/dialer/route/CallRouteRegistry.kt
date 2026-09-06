package com.opencall.relay.dialer.route

/**
 * PART 4.2/4.3: holds every registered [CallRoute] and exposes, per
 * [CallTarget], which of them could reach it. Deliberately does NOTHING
 * else — no route is preferred over another, no route is ever chosen
 * automatically on the caller's behalf, no result is cached. That is
 * exactly what makes "with one route registered, behaviour must be
 * identical to placing a call directly" true by construction rather than by
 * a special case: [routesFor] with one registered route always returns a
 * one-element list wrapping that route's own [CallRoute.canReach] answer,
 * unmodified, and [placeVia] always calls straight through to
 * [CallRoute.place] — see [CallRouteRegistryTest].
 */
class CallRouteRegistry {

    private val routes = LinkedHashMap<String, CallRoute>()

    fun register(route: CallRoute) {
        routes[route.id] = route
    }

    fun unregister(routeId: String) {
        routes.remove(routeId)
    }

    fun get(routeId: String): CallRoute? = routes[routeId]

    fun all(): List<CallRoute> = routes.values.toList()

    /** PART 4.3: "every contact row exposes the routes that could reach
     *  it" — the exact list a row's UI iterates to build its buttons. Order
     *  follows registration order (insertion-ordered map); with SIM the
     *  only thing ever registered, this is always a one-element list. Does
     *  NOT filter out [Availability.UNAVAILABLE] entries — the UI decides
     *  whether to grey out a button or hide it; this call reports what
     *  every registered route says, full stop. */
    fun routesFor(target: CallTarget): List<Pair<CallRoute, Reachability>> =
        all().map { it to it.canReach(target) }

    /** Places a call over the route named [routeId] — a thin, no-decision
     *  passthrough to that route's own [CallRoute.place]. Returns null only
     *  if no route with that id is registered (nothing to delegate to);
     *  every other failure mode surfaces through the returned
     *  [CallHandle]'s own requestAccepted/failureReason, exactly as calling
     *  the route directly would. */
    fun placeVia(routeId: String, target: CallTarget): CallHandle? =
        routes[routeId]?.place(target)
}
