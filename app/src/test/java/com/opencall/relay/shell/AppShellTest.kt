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

    // ONBOARDING REWRITE: PILLAR_1_ENABLED=true this release (Pillar 1 —
    // International — is back, see that flag's own doc). Phone stays the
    // default fallback regardless (restoreTab's fallback is decoupled from
    // the flag), but a persisted INTERNATIONAL value is no longer bounced
    // to Phone now that the tab is reachable again.

    @Test
    fun `restoreTab defaults to Phone when nothing was stored`() {
        assertEquals(AppTab.PHONE, AppShell.restoreTab(null))
    }

    @Test
    fun `restoreTab defaults to Phone on an unparseable value`() {
        assertEquals(AppTab.PHONE, AppShell.restoreTab("not-a-tab"))
    }

    @Test
    fun `restoreTab never restores into Offline`() {
        // Tab 3 is OfflineCallActivity, not a container inside MainActivity —
        // see AppShell.restoreTab's own doc for why.
        assertEquals(AppTab.PHONE, AppShell.restoreTab(AppTab.OFFLINE.name))
    }

    @Test
    fun `restoreTab restores a persisted International now that Pillar 1 is enabled`() {
        // With PILLAR_1_ENABLED=true, International is a reachable tab again —
        // a persisted value for it round-trips instead of being bounced to Phone.
        assertEquals(AppTab.INTERNATIONAL, AppShell.restoreTab(AppTab.INTERNATIONAL.name))
    }

    @Test
    fun `PILLAR_1_ENABLED is true this release — Tab 1 and the call-bridge Settings section are both shown`() {
        assertTrue(AppShell.PILLAR_1_ENABLED)
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
