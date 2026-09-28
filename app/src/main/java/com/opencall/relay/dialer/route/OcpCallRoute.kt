package com.opencall.relay.dialer.route

/**
 * PART B.2: a STUB — no SIP, no WebRTC, no network code. This exists purely
 * so the line picker (see PhoneTabController) has a real, registered row to
 * grey out ("Call with OpenCall — Not provisioned") rather than special-
 * casing "OCP isn't wired up yet" in the UI layer. [canReach] is always
 * [Reachability.unavailable] and [place] always fails with a fixed reason —
 * there is no code path in this class that could ever place a real call.
 * When OCP calling is actually built, this class's body changes; the id
 * ("ocp") and its registration site do not.
 */
class OcpCallRoute : CallRoute {

    override val id: String = "ocp"

    override fun canReach(target: CallTarget): Reachability = Reachability.unavailable()

    override fun place(target: CallTarget): CallHandle = OcpCallHandle(target)
}

private class OcpCallHandle(override val target: CallTarget) : CallHandle {
    override val routeId: String = "ocp"
    override val requestAccepted: Boolean = false
    override val failureReason: String? = "OpenCall number not provisioned"
    override fun disconnect() {
        // Never a live call — requestAccepted is always false, so there is
        // nothing here to disconnect.
    }
}
