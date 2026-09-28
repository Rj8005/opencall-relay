package com.opencall.relay.dialer.ui

/**
 * PART B.4: pure row-composition logic behind the line picker —
 * Android-free by design (this project has no Robolectric, and
 * [android.telecom.PhoneAccountHandle] can't be constructed in a JVM unit
 * test — see PhoneTabController's own doc for the established "pure core +
 * thin Android-facing wrapper" pattern this follows, same as
 * DialerRoleStateMachine/CallLogRepository.shouldQuery). [PhoneTabController]
 * resolves real [android.telecom.PhoneAccountHandle]s and the real mesh
 * route's [com.opencall.relay.dialer.route.Reachability] into the plain
 * values this takes, and turns [Row]s back into real UI/dial calls.
 */
object CallPickerModel {

    /** One row the picker sheet would show, in the fixed display order
     *  [buildRows] always produces: SIM(s), then OpenCall, then Nearby. */
    sealed class Row {
        /** One per callable SIM account, in [android.telecom.TelecomManager
         *  .callCapablePhoneAccounts] order — always actionable. [accountIndex]
         *  is that position, the only handle the Android-facing builder
         *  needs to resolve back to the real PhoneAccountHandle. */
        data class Sim(val accountIndex: Int, val label: String) : Row()
        /** Always present, never actionable today — see OcpCallRoute's own
         *  doc for why "OpenCall" has no real route yet. */
        object Ocp : Row()
        /** Present ONLY when the mesh route reports AVAILABLE. */
        object Mesh : Row()
    }

    /** [simLabels] — one label per callable SIM account (from
     *  [com.opencall.relay.dialer.route.SimCallRoute.labelFor], in
     *  [com.opencall.relay.dialer.route.SimCallRoute.callCapableAccounts]
     *  order). [meshAvailable] — whether [com.opencall.relay.dialer.route
     *  .CallRouteRegistry.routesFor] reported the mesh route's
     *  [com.opencall.relay.dialer.route.Availability.AVAILABLE] for this
     *  target. Row order is fixed, never reordered by caller input. */
    fun buildRows(simLabels: List<String>, meshAvailable: Boolean): List<Row> {
        val rows = mutableListOf<Row>()
        simLabels.forEachIndexed { index, label -> rows.add(Row.Sim(index, label)) }
        rows.add(Row.Ocp)
        if (meshAvailable) rows.add(Row.Mesh)
        return rows
    }

    /** Every row a tap on it would actually do something useful for — every
     *  [Row.Sim], plus [Row.Mesh] if present. [Row.Ocp] never counts: it is
     *  always greyed/non-actionable, by construction, regardless of how many
     *  other rows exist. */
    fun actionableRows(rows: List<Row>): List<Row> = rows.filterNot { it is Row.Ocp }

    /** The one actionable row, if [rows] has EXACTLY one — the sheet must
     *  never appear in this case; the caller places the call directly
     *  instead (see PhoneTabController.dial). Null if zero or more than one
     *  row is actionable — 0 means "nothing this device can currently
     *  reach," which the caller handles as its own error state before ever
     *  building rows at all. */
    fun singleActionableRoute(rows: List<Row>): Row? = actionableRows(rows).singleOrNull()
}
