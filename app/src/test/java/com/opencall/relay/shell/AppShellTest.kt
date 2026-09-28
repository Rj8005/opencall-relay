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

    // ONBOARDING REWRITE: with PILLAR_1_ENABLED=false (Pillar 1 hidden this
    // release, see that flag's own doc), every fallback below now resolves
    // to Phone, not International — landing on a hidden, unreachable tab
    // would leave the user with no way to navigate anywhere.

    @Test
    fun `restoreTab defaults to Phone when nothing was stored, while Pillar 1 is hidden`() {
        assertEquals(AppTab.PHONE, AppShell.restoreTab(null))
    }

    @Test
    fun `restoreTab defaults to Phone on an unparseable value, while Pillar 1 is hidden`() {
        assertEquals(AppTab.PHONE, AppShell.restoreTab("not-a-tab"))
    }

    @Test
    fun `restoreTab never restores into Offline`() {
        // Tab 3 is OfflineCallActivity, not a container inside MainActivity —
        // see AppShell.restoreTab's own doc for why.
        assertEquals(AppTab.PHONE, AppShell.restoreTab(AppTab.OFFLINE.name))
    }

    @Test
    fun `restoreTab falls back to Phone for a persisted International, while Pillar 1 is hidden`() {
        // A value stored before PILLAR_1_ENABLED existed (or from a build
        // where it was true) must not resolve to a tab that's no longer in
        // the bottom bar.
        assertEquals(AppTab.PHONE, AppShell.restoreTab(AppTab.INTERNATIONAL.name))
    }

    @Test
    fun `PILLAR_1_ENABLED is false this release — Tab 1 and the call-bridge Settings section are both hidden`() {
        assertFalse(AppShell.PILLAR_1_ENABLED)
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
