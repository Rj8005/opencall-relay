package com.opencall.relay.dialer.route

/**
 * PART 4 — the router seam. This file defines the interface ONLY; no routing
 * decision logic (which route wins for a given target) lives here or
 * anywhere yet — see [com.opencall.relay.dialer.route.CallRouteRegistry] for
 * why "one route registered" is deliberately behaviourally inert.
 *
 * SCOPE: [CallRoute] describes how to reach ONE kind of destination over ONE
 * kind of transport. [com.opencall.relay.dialer.route.SimCallRoute] (Part
 * 4.2) is the only implementation that exists today. A future
 * OcpVoipCallRoute and MeshCallRoute would implement the same interface —
 * see PART 4 OUTPUT's gap list (in the task report, not this file) for
 * exactly what this interface is still missing for those two.
 */

/** A destination this device might call. Carries whichever identifiers are
 *  known — a SIM/PSTN call only ever has [e164Number]; an OCP mesh peer may
 *  have only [ocpNodeId] (never resolved to a real phone number); a contact
 *  who is BOTH a phone contact and a known OCP peer carries both, letting
 *  [CallRoute.canReach] be asked per-route which one it can actually use.
 *  At least one of the two must be non-null. */
data class CallTarget(
    val e164Number: String? = null,
    val ocpNodeId: Long? = null,
    val displayName: String? = null
) {
    init {
        require(e164Number != null || ocpNodeId != null) {
            "CallTarget needs at least an e164Number or an ocpNodeId"
        }
    }
}

/** Whether — and how cheaply — a route believes it can reach a target RIGHT
 *  NOW. Deliberately not just a boolean: [Availability.UNKNOWN] is a real,
 *  distinct outcome (e.g. a mesh route that has no live peer list to check
 *  against yet), not the same as [Availability.UNAVAILABLE]. */
enum class Availability { AVAILABLE, UNAVAILABLE, UNKNOWN }

/** [CostHint.UNKNOWN] is the honest default for anything that can't state a
 *  real answer today — SimCallRoute, notably: a SIM call's actual cost
 *  depends on the user's carrier plan, which nothing in this app can see, so
 *  it must never claim FREE or a specific METERED rate it does not know. */
enum class CostHint { FREE, METERED, UNKNOWN }

/** [CallRoute.canReach]'s result — an availability verdict plus the cost
 *  hint the task's Part 4.1 asks for, bundled together since a caller always
 *  wants both to decide what to show a user (see PART 4 OUTPUT's gap list —
 *  UI-level "this route costs money" surfacing is one of the still-open
 *  gaps for a route that CAN answer this precisely, unlike SimCallRoute). */
data class Reachability(val availability: Availability, val cost: CostHint) {
    companion object {
        fun available(cost: CostHint) = Reachability(Availability.AVAILABLE, cost)
        fun unavailable() = Reachability(Availability.UNAVAILABLE, CostHint.UNKNOWN)
        fun unknown() = Reachability(Availability.UNKNOWN, CostHint.UNKNOWN)
    }
}

/** What [CallRoute.place] hands back. Placing a call over the platform's own
 *  Telecom stack (SimCallRoute) is fundamentally ASYNCHRONOUS and
 *  out-of-process — [TelecomManager.placeCall] fires a request; the actual
 *  live `Call` object only exists once the system's InCallService callback
 *  ([com.opencall.relay.dialer.telecom.OcpInCallService.onCallAdded]) fires,
 *  on its own schedule, and Telecom itself (not this app) owns that Call's
 *  lifecycle from then on. [CallHandle] is therefore deliberately thin — a
 *  receipt that the request was accepted or rejected, NOT a live call
 *  controller. Real in-call control (mute/hold/end/etc.) goes through
 *  [OcpInCallService]'s own Call.Callback tracking once the call exists,
 *  never through this handle. A future OcpVoipCallRoute/MeshCallRoute that
 *  owns its OWN call lifecycle end-to-end (no platform Telecom stack in the
 *  middle) could make a richer CallHandle that supports disconnect()
 *  directly — see the gap list. */
interface CallHandle {
    val routeId: String
    val target: CallTarget
    /** True if the underlying request (e.g. TelecomManager.placeCall) was
     *  accepted for processing. False means [place] failed outright — no
     *  permission, no reachable SIM, airplane mode, etc. — [failureReason]
     *  explains why. This is NOT "the call connected" — that's a Call.STATE_
     *  transition the InCallService alone can observe. */
    val requestAccepted: Boolean
    val failureReason: String?
    fun disconnect()
}

/** PART 4.1: the router seam itself. */
interface CallRoute {
    /** Stable, lowercase-hyphenated identity — "sim", "ocp-voip", "mesh" —
     *  used as a UI/log tag, never shown to the user verbatim. */
    val id: String

    /** Best-effort, synchronous, side-effect-free answer to "could this
     *  route reach [target] right now" — never blocks on network I/O; a
     *  route that needs to ask something remote returns
     *  [Availability.UNKNOWN] rather than block. */
    fun canReach(target: CallTarget): Reachability

    /** Attempts to start a call to [target] over this route. See
     *  [CallHandle]'s own doc for what the returned handle actually
     *  represents — this call itself must return promptly; it must not
     *  block waiting for the call to actually connect. */
    fun place(target: CallTarget): CallHandle
}
