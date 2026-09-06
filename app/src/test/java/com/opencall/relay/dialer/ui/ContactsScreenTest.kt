package com.opencall.relay.dialer.ui

import com.opencall.relay.dialer.data.OcpAccountRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PART 3.2/TESTS: "a contact with no matching OCP account shows only SIM
 * routing." Exercises [ContactsScreen.hasOcpRoute] directly — pure,
 * Android-free, no Context/View needed.
 */
class ContactsScreenTest {

    @Test
    fun `no matching OCP account shows only SIM routing`() {
        val ocpMatches = emptyMap<String, OcpAccountRef>() // the honest "no endpoint / lookup failed" state
        assertFalse(ContactsScreen.hasOcpRoute(listOf("+15551234567"), ocpMatches))
    }

    @Test
    fun `a matching E164 number shows the OCP route`() {
        val ocpMatches = mapOf("+15551234567" to OcpAccountRef("+15551234567", "a1b2c3"))
        assertTrue(ContactsScreen.hasOcpRoute(listOf("+15551234567"), ocpMatches))
    }

    @Test
    fun `matching is order-independent across multiple numbers`() {
        val ocpMatches = mapOf("+919876543210" to OcpAccountRef("+919876543210", null))
        assertTrue(ContactsScreen.hasOcpRoute(listOf("+15551234567", "+919876543210"), ocpMatches))
    }

    @Test
    fun `a contact with no phone numbers is never OCP-routed`() {
        val ocpMatches = mapOf("+15551234567" to OcpAccountRef("+15551234567", null))
        assertFalse(ContactsScreen.hasOcpRoute(emptyList(), ocpMatches))
    }

    @Test
    fun `normalizeForMatch strips separators and adds a leading plus`() {
        assertEquals("+15551234567", ContactsScreen.normalizeForMatch("1 (555) 123-4567"))
        assertEquals("+15551234567", ContactsScreen.normalizeForMatch("+1 555-123-4567"))
    }

    @Test
    fun `an unnormalized stored number still matches a normalized server entry`() {
        val ocpMatches = mapOf("+15551234567" to OcpAccountRef("+15551234567", null))
        assertTrue(ContactsScreen.hasOcpRoute(listOf("1 (555) 123-4567"), ocpMatches))
    }
}
