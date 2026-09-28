package com.opencall.relay.dialer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PART B.4 tests: picker row composition and the skip-sheet decision —
 *  pure, no PhoneAccountHandle/Android dependency (see CallPickerModel's
 *  own doc for why). */
class CallPickerModelTest {

    @Test
    fun `one SIM, no mesh -- rows are Sim then Ocp, exactly one actionable`() {
        val rows = CallPickerModel.buildRows(listOf("SIM 1"), meshAvailable = false)
        assertEquals(2, rows.size)
        assertTrue(rows[0] is CallPickerModel.Row.Sim)
        assertEquals(CallPickerModel.Row.Ocp, rows[1])
        assertEquals(1, CallPickerModel.actionableRows(rows).size)
    }

    @Test
    fun `single-SIM, no-mesh case skips the sheet -- the single actionable route is that SIM`() {
        val rows = CallPickerModel.buildRows(listOf("SIM 1"), meshAvailable = false)
        val single = CallPickerModel.singleActionableRoute(rows)
        assertTrue(single is CallPickerModel.Row.Sim)
        assertEquals("SIM 1", (single as CallPickerModel.Row.Sim).label)
    }

    @Test
    fun `two SIMs, no mesh -- rows are Sim, Sim, Ocp, two actionable, sheet does not skip`() {
        val rows = CallPickerModel.buildRows(listOf("SIM 1", "SIM 2"), meshAvailable = false)
        assertEquals(3, rows.size)
        assertEquals(2, CallPickerModel.actionableRows(rows).size)
        assertNull("two actionable routes must never auto-skip the sheet", CallPickerModel.singleActionableRoute(rows))
    }

    @Test
    fun `one SIM plus mesh available -- rows are Sim, Ocp, Mesh, two actionable, sheet does not skip`() {
        val rows = CallPickerModel.buildRows(listOf("SIM 1"), meshAvailable = true)
        assertEquals(3, rows.size)
        assertEquals(CallPickerModel.Row.Mesh, rows[2])
        assertEquals(2, CallPickerModel.actionableRows(rows).size)
        assertNull(CallPickerModel.singleActionableRoute(rows))
    }

    @Test
    fun `no SIM, mesh available -- Ocp then Mesh, exactly one actionable -- mesh alone skips the sheet`() {
        val rows = CallPickerModel.buildRows(emptyList(), meshAvailable = true)
        assertEquals(2, rows.size)
        assertEquals(CallPickerModel.Row.Ocp, rows[0])
        assertEquals(CallPickerModel.Row.Mesh, rows[1])
        assertEquals(CallPickerModel.Row.Mesh, CallPickerModel.singleActionableRoute(rows))
    }

    @Test
    fun `no SIM, no mesh -- Ocp only, zero actionable, nothing to auto-place`() {
        val rows = CallPickerModel.buildRows(emptyList(), meshAvailable = false)
        assertEquals(listOf(CallPickerModel.Row.Ocp), rows)
        assertTrue(CallPickerModel.actionableRows(rows).isEmpty())
        assertNull(CallPickerModel.singleActionableRoute(rows))
    }

    @Test
    fun `Ocp is never actionable regardless of how many other rows exist`() {
        val rows = CallPickerModel.buildRows(listOf("SIM 1", "SIM 2"), meshAvailable = true)
        assertTrue(CallPickerModel.actionableRows(rows).none { it is CallPickerModel.Row.Ocp })
    }

    @Test
    fun `row order is always SIMs, then OpenCall, then Nearby`() {
        val rows = CallPickerModel.buildRows(listOf("SIM 1", "SIM 2"), meshAvailable = true)
        assertEquals(
            listOf(
                CallPickerModel.Row.Sim(0, "SIM 1"),
                CallPickerModel.Row.Sim(1, "SIM 2"),
                CallPickerModel.Row.Ocp,
                CallPickerModel.Row.Mesh
            ),
            rows
        )
    }
}
