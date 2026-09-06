package com.opencall.relay.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OFFLINE UI STEP 4: pure-JVM round-trip tests for
 * [OfflineMediaTransport.PhraseCode]'s wire codec — same spirit as
 * MeshLocationTest/MeshBleBeaconTest. [OfflineMediaTransport.PhraseCode] is a
 * nested enum with a pure companion codec; referencing it does not require
 * constructing an OfflineMediaTransport instance (no Context needed), same
 * as OfflineMediaTransport.CallMode.fromWireId already being called without
 * one elsewhere in this codebase.
 */
class PhraseCodeTest {

    @Test
    fun `round trip every known phrase code`() {
        OfflineMediaTransport.PhraseCode.values().forEach { phrase ->
            val bytes = OfflineMediaTransport.PhraseCode.encode(phrase.code, seq = 12345L)
            val (code, seq) = OfflineMediaTransport.PhraseCode.decode(bytes)!!
            assertEquals(phrase.code, code)
            assertEquals(12345L, seq)
            assertEquals(phrase, OfflineMediaTransport.PhraseCode.fromCode(code))
        }
    }

    @Test
    fun `payload is exactly 5 bytes - 1 code + 4 seq`() {
        val bytes = OfflineMediaTransport.PhraseCode.encode(0, 1L)
        assertEquals(5, bytes.size)
    }

    @Test
    fun `seq round trips across the full uint32 range`() {
        val cases = listOf(0L, 1L, 4294967295L /* 0xFFFFFFFF */, 2147483648L /* 0x80000000 */)
        cases.forEach { seq ->
            val bytes = OfflineMediaTransport.PhraseCode.encode(1, seq)
            val (_, decodedSeq) = OfflineMediaTransport.PhraseCode.decode(bytes)!!
            assertEquals(seq, decodedSeq)
        }
    }

    @Test
    fun `malformed (wrong length) payload decodes to null, never throws`() {
        assertNull(OfflineMediaTransport.PhraseCode.decode(ByteArray(0)))
        assertNull(OfflineMediaTransport.PhraseCode.decode(ByteArray(4)))
        assertNull(OfflineMediaTransport.PhraseCode.decode(ByteArray(6)))
    }

    // ── STEP 4 / OUTPUT #7: unknown code is decodable (not malformed), and
    // fromCode correctly classifies it as unrecognized — this is the data-
    // layer half of "an unknown phraseCode is still relayed onward" (the
    // other half — that routeFrame's forwarding decision runs BEFORE
    // dispatchLocal/handlePhraseFrame and never consults the decoded code at
    // all — is a code-path fact, not something a unit test alone proves; see
    // OUTPUT #7's citation). ────────────────────────────────────────────────

    @Test
    fun `reserved code 7 is structurally valid but classifies as unknown`() {
        val bytes = OfflineMediaTransport.PhraseCode.encode(7, 1L)
        val (code, _) = OfflineMediaTransport.PhraseCode.decode(bytes)!!
        assertEquals(7, code)
        assertNull("code 7 is reserved - must never resolve to a real phrase", OfflineMediaTransport.PhraseCode.fromCode(code))
    }

    @Test
    fun `an arbitrary out-of-table code (99) still decodes successfully`() {
        val bytes = OfflineMediaTransport.PhraseCode.encode(99, 1L)
        val decoded = OfflineMediaTransport.PhraseCode.decode(bytes)
        assertNotNull("decode must succeed for any structurally-valid payload regardless of code value", decoded)
        assertEquals(99, decoded!!.first)
        assertNull(OfflineMediaTransport.PhraseCode.fromCode(99))
    }

    @Test
    fun `every real phrase has non-empty display text`() {
        OfflineMediaTransport.PhraseCode.values().forEach { assertTrue(it.text.isNotBlank()) }
    }

    @Test
    fun `no two phrases share the same wire code`() {
        val codes = OfflineMediaTransport.PhraseCode.values().map { it.code }
        assertEquals("duplicate code(s) found", codes.size, codes.toSet().size)
    }

    // ── PHASE 5.1/5.4: codes 0-6 KEEP THEIR EXACT PRE-EXISTING VALUES —
    // a phrase that changes meaning between app versions is a safety bug.
    // Asserted individually, not as a set, so a future edit that swaps two
    // codes' text (same set, wrong pairing) is caught too.

    @Test
    fun `code 0 is still exactly IM_OK, I'm OK`() {
        assertEquals("I'm OK", OfflineMediaTransport.PhraseCode.fromCode(0)!!.text)
        assertEquals(OfflineMediaTransport.PhraseCode.IM_OK, OfflineMediaTransport.PhraseCode.fromCode(0))
    }

    @Test
    fun `code 1 is still exactly HOLD_POSITION, Hold position`() {
        assertEquals("Hold position", OfflineMediaTransport.PhraseCode.fromCode(1)!!.text)
        assertEquals(OfflineMediaTransport.PhraseCode.HOLD_POSITION, OfflineMediaTransport.PhraseCode.fromCode(1))
    }

    @Test
    fun `code 2 is still exactly MOVING_TO_YOU, Moving to you`() {
        assertEquals("Moving to you", OfflineMediaTransport.PhraseCode.fromCode(2)!!.text)
        assertEquals(OfflineMediaTransport.PhraseCode.MOVING_TO_YOU, OfflineMediaTransport.PhraseCode.fromCode(2))
    }

    @Test
    fun `code 3 is still exactly TURNING_BACK, Turning back`() {
        assertEquals("Turning back", OfflineMediaTransport.PhraseCode.fromCode(3)!!.text)
        assertEquals(OfflineMediaTransport.PhraseCode.TURNING_BACK, OfflineMediaTransport.PhraseCode.fromCode(3))
    }

    @Test
    fun `code 4 is still exactly WEATHER_TURNING, Weather turning`() {
        assertEquals("Weather turning", OfflineMediaTransport.PhraseCode.fromCode(4)!!.text)
        assertEquals(OfflineMediaTransport.PhraseCode.WEATHER_TURNING, OfflineMediaTransport.PhraseCode.fromCode(4))
    }

    @Test
    fun `code 5 is still exactly REGROUP_LAST_POINT, Regroup last point`() {
        assertEquals("Regroup last point", OfflineMediaTransport.PhraseCode.fromCode(5)!!.text)
        assertEquals(OfflineMediaTransport.PhraseCode.REGROUP_LAST_POINT, OfflineMediaTransport.PhraseCode.fromCode(5))
    }

    @Test
    fun `code 6 is still exactly NEED_HELP, Need help`() {
        assertEquals("Need help", OfflineMediaTransport.PhraseCode.fromCode(6)!!.text)
        assertEquals(OfflineMediaTransport.PhraseCode.NEED_HELP, OfflineMediaTransport.PhraseCode.fromCode(6))
    }

    @Test
    fun `code 7 remains reserved even after the PHASE 5_1 expansion`() {
        assertNull(OfflineMediaTransport.PhraseCode.fromCode(7))
    }

    @Test
    fun `new codes start at 8, none reuse 0-7`() {
        val newCodes = OfflineMediaTransport.PhraseCode.values().filter { it.code >= 8 }.map { it.code }
        assertTrue("expected some codes >= 8 after the PHASE 5.1 expansion", newCodes.isNotEmpty())
        assertTrue("no new code may fall in the reserved/legacy 0-7 range", newCodes.all { it >= 8 })
    }

    @Test
    fun `the table has grown toward 70 codes as specified`() {
        assertTrue(
            "expected the table to have grown substantially beyond the original 7, got ${OfflineMediaTransport.PhraseCode.values().size}",
            OfflineMediaTransport.PhraseCode.values().size in 60..70
        )
    }

    @Test
    fun `phrase payload code portion is exactly 1 byte for every code in the table`() {
        OfflineMediaTransport.PhraseCode.values().forEach { phrase ->
            val bytes = OfflineMediaTransport.PhraseCode.encode(phrase.code, 0L)
            // [1B code][4B seq] = 5 bytes total; the code itself occupies
            // exactly bytes[0], one byte, for every code in the table
            // (including the largest, 69, which still fits 0-255 unsigned).
            assertEquals(5, bytes.size)
            assertEquals(phrase.code, bytes[0].toInt() and 0xFF)
        }
    }

    @Test
    fun `an unknown code renders as unknown message (fromCode null) but still decodes structurally`() {
        val bytes = OfflineMediaTransport.PhraseCode.encode(200, 1L)
        val (code, _) = OfflineMediaTransport.PhraseCode.decode(bytes)!!
        assertEquals(200, code)
        assertNull("code 200 is outside the table -> unknown message, per handlePhraseFrame's doc", OfflineMediaTransport.PhraseCode.fromCode(200))
    }

    @Test
    fun `every category is represented at least once`() {
        val categories = OfflineMediaTransport.PhraseCode.values().map { it.category }.toSet()
        assertEquals(OfflineMediaTransport.PhraseCategory.values().toSet(), categories)
    }
}
