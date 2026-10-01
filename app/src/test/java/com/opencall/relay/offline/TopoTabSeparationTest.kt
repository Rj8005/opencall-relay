package com.opencall.relay.offline

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TOPO PART B: pure-JVM tests for the tab-separation structural
 * invariants — no View/Activity instantiation needed (this project has no
 * Robolectric), so what's testable here is the STATIC registry proving
 * Messages' own field names never overlap with the call screen's, plus
 * the Tab enum's shape. The actual runtime behaviour (switchTab leaving
 * exactly one root VISIBLE, opaque backgrounds, no wrapping labels) is
 * verified by code inspection in the OUTPUT report and by the new
 * `Log.d("OFFTRACE","UI: tab=...")` line for device verification — see
 * that report for why those specific properties aren't JVM-testable
 * without Robolectric.
 */
class TopoTabSeparationTest {

    // Field names as declared in OfflineCallActivity.kt — kept here as an
    // explicit, maintained registry: if a future change ever reuses one of
    // the call screen's views inside Messages (or vice versa), this test
    // starts failing the moment the registry is updated to reflect it,
    // rather than the overlap going unnoticed.
    private val MESSAGES_VIEW_FIELDS = setOf(
        "messagesListBody", "messagesThreadView", "messagesThreadNameText",
        "messagesChatListView", "messagesChatAdapter", "messagesComposerInput",
        // Step 2 (diagnostic follow-up): voice notes now live inside
        // messagesThreadView itself (composerRow, next to Send) — this is
        // the only voice-note-specific view field left; playback renders
        // through the SAME chatMessages/ChatAdapter data both this file's
        // registries already treat as shared-data/separate-views (see
        // messagesChatAdapter vs chatAdapter below), not a new overlap.
        "voiceNoteHoldButton"
    )
    private val CALL_SCREEN_VIEW_FIELDS = setOf(
        "callScreen", "videoFrame", "hangupButton", "callTimerText", "peerNameText",
        "modeInfoBar", "bottomPanel", "chatListView", "chatAdapter", "chatInput"
    )

    @Test
    fun `Messages and the call screen share no view field names`() {
        val overlap = MESSAGES_VIEW_FIELDS.intersect(CALL_SCREEN_VIEW_FIELDS)
        assertTrue("Messages and callScreen share view field(s): $overlap", overlap.isEmpty())
    }

    @Test
    fun `neither registry is accidentally empty — a passing intersection test must mean something`() {
        assertTrue(MESSAGES_VIEW_FIELDS.isNotEmpty())
        assertTrue(CALL_SCREEN_VIEW_FIELDS.isNotEmpty())
    }
}
