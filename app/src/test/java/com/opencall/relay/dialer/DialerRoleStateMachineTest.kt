package com.opencall.relay.dialer

import com.opencall.relay.dialer.role.DialerRoleState
import com.opencall.relay.dialer.role.DialerRoleStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PART 1.1 test: "role-request state machine covers granted/denied/revoked" —
 *  pure, no RoleManager/TelecomManager involved (see DialerRoleStateMachine's
 *  own doc for why the machine itself is Android-free). */
class DialerRoleStateMachineTest {

    @Test
    fun `a request that comes back DEFAULT is Granted`() {
        val m = DialerRoleStateMachine()
        m.onRequestLaunched()
        assertEquals(DialerRoleStateMachine.Event.Granted, m.onObserved(DialerRoleState.DEFAULT))
        assertTrue(m.currentlyDefault())
    }

    @Test
    fun `a request that comes back NOT_DEFAULT is Denied`() {
        val m = DialerRoleStateMachine()
        m.onRequestLaunched()
        assertEquals(DialerRoleStateMachine.Event.Denied, m.onObserved(DialerRoleState.NOT_DEFAULT))
        assertFalse(m.currentlyDefault())
    }

    @Test
    fun `role held then lost with no request in flight is Revoked`() {
        val m = DialerRoleStateMachine()
        m.onRequestLaunched()
        assertEquals(DialerRoleStateMachine.Event.Granted, m.onObserved(DialerRoleState.DEFAULT))
        // Time passes, no new request — e.g. the user changed the default
        // dialer in system Settings while this app was backgrounded.
        assertEquals(DialerRoleStateMachine.Event.Revoked, m.onObserved(DialerRoleState.NOT_DEFAULT))
    }

    @Test
    fun `re-observing the same state with no request in flight is NoChange`() {
        val m = DialerRoleStateMachine()
        assertEquals(DialerRoleStateMachine.Event.NoChange, m.onObserved(DialerRoleState.NOT_DEFAULT))
        assertEquals(DialerRoleStateMachine.Event.NoChange, m.onObserved(DialerRoleState.NOT_DEFAULT))
    }

    @Test
    fun `going from NOT_DEFAULT to DEFAULT with no request in flight is NoChange, not Granted`() {
        // Granted is reserved for the outcome of a request THIS machine was
        // told about — an external change landing on DEFAULT (e.g. this app
        // was made default via system Settings directly) is not "our
        // request succeeded," it's just a new observed state.
        val m = DialerRoleStateMachine()
        m.onObserved(DialerRoleState.NOT_DEFAULT)
        assertEquals(DialerRoleStateMachine.Event.NoChange, m.onObserved(DialerRoleState.DEFAULT))
    }

    @Test
    fun `a request flag is consumed by the very next observation only`() {
        val m = DialerRoleStateMachine()
        m.onRequestLaunched()
        m.onObserved(DialerRoleState.DEFAULT) // consumes the flag -> Granted
        // A LATER revoke must be classified as Revoked, not mistaken for
        // still being "mid-request."
        assertEquals(DialerRoleStateMachine.Event.Revoked, m.onObserved(DialerRoleState.NOT_DEFAULT))
    }

    @Test
    fun `denied then granted on a later separate request is Granted`() {
        val m = DialerRoleStateMachine()
        m.onRequestLaunched()
        assertEquals(DialerRoleStateMachine.Event.Denied, m.onObserved(DialerRoleState.NOT_DEFAULT))
        m.onRequestLaunched()
        assertEquals(DialerRoleStateMachine.Event.Granted, m.onObserved(DialerRoleState.DEFAULT))
    }
}
