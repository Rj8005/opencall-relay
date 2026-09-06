package com.opencall.relay.dialer

import com.opencall.relay.dialer.data.DialerContact
import com.opencall.relay.dialer.ui.T9Matcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PART 3.1 tests: T9 mapping and per-word contact matching — pure,
 *  no Android dependency. */
class T9MatcherTest {

    @Test
    fun `digitsFor maps B-O-O-K to 2665`() {
        assertEquals("2665", T9Matcher.digitsFor("Book"))
    }

    @Test
    fun `2665 matches a contact named Book`() {
        assertTrue(T9Matcher.matches("2665", "Book"))
    }

    @Test
    fun `2665 matches a contact whose second word is Book — the Anna Book case`() {
        // The task's own example: "typing 2665 matches 'Book' and 'Anna'" —
        // read as: among a contact set containing "Book" and "Anna Book",
        // typing 2665 surfaces both, the second on its surname's word.
        assertTrue(T9Matcher.matches("2665", "Anna Book"))
    }

    @Test
    fun `2665 does not match Anna alone`() {
        // A=2 N=6 N=6 A=2 -> "2662", which does not start with "2665".
        assertFalse(T9Matcher.matches("2665", "Anna"))
    }

    @Test
    fun `a T9 prefix (not just exact) match works`() {
        // "26" is the T9 prefix of "Book" (2665...) — a partial typed
        // sequence must match as the user is still typing.
        assertTrue(T9Matcher.matches("26", "Book"))
    }

    @Test
    fun `a non-matching digit sequence does not match`() {
        assertFalse(T9Matcher.matches("999", "Book"))
    }

    @Test
    fun `empty digits never match anything`() {
        assertFalse(T9Matcher.matches("", "Book"))
        assertFalse(T9Matcher.matches("", ""))
    }

    @Test
    fun `non-digit input never matches`() {
        assertFalse(T9Matcher.matches("26a5", "Book"))
    }

    @Test
    fun `filterContacts returns only matching contacts, empty digits returns none`() {
        val contacts = listOf(
            DialerContact(1, "Book", listOf("+15551230001"), false, null),
            DialerContact(2, "Anna Book", listOf("+15551230002"), false, null),
            DialerContact(3, "Zeta", listOf("+15551230003"), false, null)
        )
        val results = T9Matcher.filterContacts("2665", contacts)
        assertEquals(setOf("Book", "Anna Book"), results.map { it.displayName }.toSet())
        assertTrue(T9Matcher.filterContacts("", contacts).isEmpty())
    }

    @Test
    fun `case is ignored`() {
        assertTrue(T9Matcher.matches("2665", "BOOK"))
        assertTrue(T9Matcher.matches("2665", "book"))
    }
}
