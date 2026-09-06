package com.opencall.relay.dialer.ui

import com.opencall.relay.dialer.data.DialerContact

/**
 * PART 3.1: T9 contact search — typing "2665" (the digits for B-O-O-K)
 * matches a contact named "Book" (whole-name match) and ALSO a contact
 * whose full name is e.g. "Anna Book" (matches on the second WORD) — that
 * per-word matching is deliberate: real contact names are frequently
 * "first last," and a caller may remember-and-type either half's letters.
 * Pure, Context-free — every case here is directly unit-testable (see
 * T9MatcherTest).
 */
object T9Matcher {

    private val LETTER_TO_DIGIT: Map<Char, Char> = buildMap {
        "abc".forEach { put(it, '2') }
        "def".forEach { put(it, '3') }
        "ghi".forEach { put(it, '4') }
        "jkl".forEach { put(it, '5') }
        "mno".forEach { put(it, '6') }
        "pqrs".forEach { put(it, '7') }
        "tuv".forEach { put(it, '8') }
        "wxyz".forEach { put(it, '9') }
    }

    /** The T9 digit string for [word] — non-letter characters (apostrophes,
     *  hyphens, etc.) are simply skipped, not mapped to anything. */
    fun digitsFor(word: String): String =
        word.lowercase().mapNotNull { LETTER_TO_DIGIT[it] }.joinToString("")

    /** True if [digits] is a non-empty T9 PREFIX match of any whitespace-
     *  separated word in [name]. Empty [digits] matches nothing — an empty
     *  keypad shows no T9 results, distinct from "search not attempted." */
    fun matches(digits: String, name: String): Boolean {
        if (digits.isEmpty() || !digits.all { it.isDigit() }) return false
        return name.split(Regex("\\s+")).any { word -> digitsFor(word).startsWith(digits) }
    }

    /** PART 3.1: the actual keypad-search entry point — [digits] is the
     *  keypad's current typed sequence; returns every contact with at least
     *  one matching word, in the input list's own order (the caller is
     *  expected to have already sorted [contacts], e.g. alphabetically). */
    fun filterContacts(digits: String, contacts: List<DialerContact>): List<DialerContact> =
        if (digits.isEmpty()) emptyList() else contacts.filter { matches(digits, it.displayName) }
}
