package com.opencall.relay.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PART 1.4/TESTS: "tab state survives rotation and process death." The
 * actual Bundle/SharedPreferences plumbing around this lives in
 * MainActivity and needs a real Activity to exercise (no Robolectric in
 * this project — same constraint OfflineIdentityTest's class doc already
 * documents); what's pure, and what both the rotation path
 * (onRestoreInstanceState) and the process-death path (SharedPreferences)
 * actually share, is this decision function — that's what's under test here.
 */
class AppShellTest {

    @Test
    fun `restoreTab defaults to International when nothing was stored`() {
        assertEquals(AppTab.INTERNATIONAL, AppShell.restoreTab(null))
    }

    @Test
    fun `restoreTab defaults to International on an unparseable value`() {
        assertEquals(AppTab.INTERNATIONAL, AppShell.restoreTab("not-a-tab"))
    }

    @Test
    fun `restoreTab never restores into Offline`() {
        // Tab 3 is OfflineCallActivity, not a container inside MainActivity —
        // see AppShell.restoreTab's own doc for why.
        assertEquals(AppTab.INTERNATIONAL, AppShell.restoreTab(AppTab.OFFLINE.name))
    }

    @Test
    fun `restoreTab round-trips Phone`() {
        assertEquals(AppTab.PHONE, AppShell.restoreTab(AppTab.PHONE.name))
    }

    @Test
    fun `requiresLeavingCurrentActivity is false for the already-selected tab`() {
        assertFalse(AppShell.requiresLeavingCurrentActivity(AppTab.OFFLINE, AppTab.OFFLINE))
    }

    @Test
    fun `requiresLeavingCurrentActivity is true for a different tab`() {
        assertTrue(AppShell.requiresLeavingCurrentActivity(AppTab.OFFLINE, AppTab.PHONE))
        assertTrue(AppShell.requiresLeavingCurrentActivity(AppTab.OFFLINE, AppTab.INTERNATIONAL))
    }
}
