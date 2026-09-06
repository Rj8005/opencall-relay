package com.opencall.relay.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TOPO PHASE 3: pure-JVM tests for the Messages tab's testable logic —
 * emoji-over-CHAT round trip, the voice-note 30s record cap, and the
 * voice-note carry battery guard. No Context/View needed (this project has
 * no Robolectric).
 */
class TopoMessagesTest {

    // ── 3.3: emoji rides the EXISTING CHAT payload unchanged ────────────────

    @Test
    fun `a 4-byte emoji round trips through UTF-8 encode-decode exactly`() {
        val emoji = "😂" // U+1F602, a 4-byte UTF-8 sequence (F0 9F 98 82)
        val bytes = emoji.toByteArray(Charsets.UTF_8)
        assertEquals(4, bytes.size)
        assertEquals(emoji, String(bytes, Charsets.UTF_8))
    }

    @Test
    fun `every quick-reaction emoji round trips and stays well under the CHAT payload cap`() {
        // MAX_CHAT_PAYLOAD_BYTES is 4096 (OfflineMediaTransport.kt) — this
        // proves the emoji row can never come close to it, let alone the
        // per-message 4-byte case above.
        OfflineCallActivity.QUICK_REACTION_EMOJI.forEach { emoji ->
            val bytes = emoji.toByteArray(Charsets.UTF_8)
            assertTrue("emoji '$emoji' encoded to 0 bytes", bytes.isNotEmpty())
            assertTrue("emoji '$emoji' unexpectedly large: ${bytes.size} bytes", bytes.size < 4096)
            assertEquals(emoji, String(bytes, Charsets.UTF_8))
        }
    }

    // ── 3.4: voice note 30s record cap ───────────────────────────────────────

    @Test
    fun `a voice note at or under 30s is not rejected`() {
        assertFalse(OfflineCallActivity.isVoiceNoteTooLong(0L))
        assertFalse(OfflineCallActivity.isVoiceNoteTooLong(15_000L))
        assertFalse(OfflineCallActivity.isVoiceNoteTooLong(OfflineCallActivity.VOICE_NOTE_MAX_MS))
    }

    @Test
    fun `a voice note over 30s is rejected`() {
        assertTrue(OfflineCallActivity.isVoiceNoteTooLong(OfflineCallActivity.VOICE_NOTE_MAX_MS + 1))
        assertTrue(OfflineCallActivity.isVoiceNoteTooLong(45_000L))
    }

    // ── 3.4: BATTERY GUARD — a 300KB voice note is NOT carried at 40%
    // unplugged, IS carried at 60% (or unplugged at >50%), and the offer is
    // carried unconditionally in both cases. Below the 200KB threshold,
    // size never blocks carrying regardless of battery. ────────────────────

    private val THREE_HUNDRED_KB = 300 * 1024

    @Test
    fun `a 300KB voice note payload is NOT carried at 40 percent battery, unplugged`() {
        assertFalse(OfflineCallActivity.shouldCarryVoiceNotePayload(THREE_HUNDRED_KB, batteryPct = 40, charging = false))
    }

    @Test
    fun `a 300KB voice note payload IS carried at 60 percent battery`() {
        assertTrue(OfflineCallActivity.shouldCarryVoiceNotePayload(THREE_HUNDRED_KB, batteryPct = 60, charging = false))
    }

    @Test
    fun `a 300KB voice note payload IS carried at 40 percent battery if charging`() {
        assertTrue(OfflineCallActivity.shouldCarryVoiceNotePayload(THREE_HUNDRED_KB, batteryPct = 40, charging = true))
    }

    @Test
    fun `a voice note payload at or under the 200KB threshold is always carried, regardless of battery`() {
        assertTrue(OfflineCallActivity.shouldCarryVoiceNotePayload(200 * 1024, batteryPct = 1, charging = false))
        assertTrue(OfflineCallActivity.shouldCarryVoiceNotePayload(1, batteryPct = 1, charging = false))
    }

    @Test
    fun `the offer is carried unconditionally in every case — low battery, unplugged, huge payload`() {
        assertTrue(OfflineCallActivity.shouldCarryVoiceNoteOffer())
    }

    @Test
    fun `TYPE_VOICE_NOTE reserves the next free frame type number after TYPE_PHRASE (35) — 36`() {
        assertEquals(36.toByte(), OfflineMediaTransport.TYPE_VOICE_NOTE)
    }
}
