package com.opencall.relay.onboarding

import com.opencall.relay.shell.AppShell
import com.opencall.relay.shell.AppTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ONBOARDING REWRITE: pure-JVM tests for OnboardingFlow's decision core.
 * OnboardingFlow itself is never instantiated here — its constructor needs a
 * real AppCompatActivity/FrameLayout (Views, a real Context) that this
 * project's plain-JVM test setup (no Robolectric) cannot provide, the same
 * constraint every other test in this suite documents (MeshSosManagerTest,
 * OfflineMediaTransportTest, GroupDecoderLockTest, etc.). What IS pure —
 * page-order/button-label logic, the display-name fallback, the
 * onboarding-complete gate, the landing tab, and the exact literal strings
 * the three screens render — is what's under test here.
 */
class OnboardingFlowTest {

    // ── Page order + the skip path — both completion routes land on Phone ──

    @Test
    fun `primary button reads Next on screens 1 and 2, Start only on screen 3`() {
        assertEquals("Next", OnboardingFlow.primaryButtonLabelFor(0))
        assertEquals("Next", OnboardingFlow.primaryButtonLabelFor(1))
        assertEquals("Start", OnboardingFlow.primaryButtonLabelFor(2))
    }

    @Test
    fun `there are exactly three screens`() {
        assertEquals(3, OnboardingFlow.PAGE_COUNT)
    }

    @Test
    fun `landing tab after onboarding is Phone while Pillar 1 is hidden`() {
        // Both completion paths (finishing screen 3's "Start", and "Skip"
        // from any screen) funnel through the SAME markComplete() ->
        // onFinished() call in OnboardingFlow, which MainActivity wires to
        // showTab(OnboardingFlow.landingTabAfterOnboarding()) — so proving
        // this one function is correct proves both paths land in the same
        // place, without needing two separate Activity-level tests.
        assertEquals(AppTab.PHONE, OnboardingFlow.landingTabAfterOnboarding())
        assertFalse("Pillar 1 must actually be hidden for Phone to be the right landing tab", AppShell.PILLAR_1_ENABLED)
    }

    @Test
    fun `landing tab tracks the Pillar 1 flag, not a hardcoded default`() {
        // Documents the CONTRACT (if PILLAR_1_ENABLED flips back on, the
        // landing tab must become International again) even though this
        // build's flag value is fixed — see the function's own doc.
        assertEquals(
            if (AppShell.PILLAR_1_ENABLED) AppTab.INTERNATIONAL else AppTab.PHONE,
            OnboardingFlow.landingTabAfterOnboarding()
        )
    }

    // ── "a stored display name skips onboarding on next launch" ────────────

    @Test
    fun `onboarding is skipped once setup_complete is stored true`() {
        assertTrue(OnboardingFlow.isOnboardingComplete(true))
    }

    @Test
    fun `onboarding is shown when setup_complete has never been stored`() {
        assertFalse(OnboardingFlow.isOnboardingComplete(false))
    }

    // ── "an empty display name falls back to a default rather than blocking" ──

    @Test
    fun `a blank typed name falls back to the existing default, never blocking`() {
        assertEquals("Pixel 7", OnboardingFlow.resolveDisplayNameToPersist("", "Pixel 7"))
        assertEquals("Pixel 7", OnboardingFlow.resolveDisplayNameToPersist("   ", "Pixel 7"))
    }

    @Test
    fun `a typed name is trimmed and used over the default`() {
        assertEquals("Alice", OnboardingFlow.resolveDisplayNameToPersist("Alice", "Pixel 7"))
        assertEquals("Alice", OnboardingFlow.resolveDisplayNameToPersist("  Alice  ", "Pixel 7"))
    }

    @Test
    fun `resolveDisplayNameToPersist never returns blank, for any input`() {
        listOf("", " ", "\t", "\n", "Alice", "  Bob  ").forEach { typed ->
            val resolved = OnboardingFlow.resolveDisplayNameToPersist(typed, "Pixel 7")
            assertTrue("resolved name must never be blank for typed=\"$typed\"", resolved.isNotBlank())
        }
    }

    // ── "no user-visible string in the new flow contains relay or node" ────

    @Test
    fun `no onboarding string mentions relay or node`() {
        OnboardingFlow.ONBOARDING_STRINGS.forEach { s ->
            val lower = s.lowercase()
            assertFalse("\"$s\" must not contain \"relay\"", lower.contains("relay"))
            assertFalse("\"$s\" must not contain \"node\"", lower.contains("node"))
        }
    }

    @Test
    fun `onboarding strings list is not accidentally empty`() {
        // A trivially-passing empty list would make the test above
        // meaningless — pin the exact count of screens' worth of copy.
        assertEquals(9, OnboardingFlow.ONBOARDING_STRINGS.size)
    }

    @Test
    fun `screen 2 covers exactly the three named things — phone, offline, SOS`() {
        assertTrue(OnboardingFlow.SCREEN2_ROW1.contains("Your phone, better"))
        assertTrue(OnboardingFlow.SCREEN2_ROW2.contains("Off the grid"))
        assertTrue(OnboardingFlow.SCREEN2_ROW3.contains("Emergency SOS"))
    }

    @Test
    fun `screen 3's only field is about a display name — no phone number, no server URL wording`() {
        val lower = OnboardingFlow.SCREEN3_LABEL.lowercase()
        assertFalse(lower.contains("number"))
        assertFalse(lower.contains("server"))
    }
}
