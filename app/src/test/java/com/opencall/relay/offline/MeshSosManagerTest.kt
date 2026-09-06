package com.opencall.relay.offline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PART A: pure-JVM tests for [MeshSosManager.computeAlarmable] — the
 * Context-free alarmability decision extracted so this logic is directly
 * testable, same "pure companion" spirit as MeshSigner.isWithinReplayWindow.
 * MeshSosManager itself is never instantiated here (its constructor needs a
 * real Android Context via OfflineLocationProvider/MeshBarometer/MeshLedger/
 * MeshCarrier — out of scope for this project's Robolectric-free unit tests,
 * same reason [MeshSigner.verifyIncoming]/[MeshSigner.verifyCarried] are
 * never instance-tested in MeshSignerTest either).
 */
class MeshSosManagerTest {

    private val sixHoursSec = 6 * 60 * 60L
    private val thirtyMinSec = 30 * 60L

    @Test
    fun `a CLEAR is always alarmable regardless of live, carried or age`() {
        assertTrue(MeshSosManager.computeAlarmable(active = false, isLive = true, ageSec = 0, isCarriedVerified = false, existingAlarmable = false))
        assertTrue(MeshSosManager.computeAlarmable(active = false, isLive = false, ageSec = 999_999, isCarriedVerified = true, existingAlarmable = false))
    }

    @Test
    fun `a live rebroadcast preserves whatever alarmable state already existed`() {
        assertTrue(MeshSosManager.computeAlarmable(active = true, isLive = true, ageSec = 0, isCarriedVerified = false, existingAlarmable = true))
        assertFalse(MeshSosManager.computeAlarmable(active = true, isLive = true, ageSec = 0, isCarriedVerified = false, existingAlarmable = false))
    }

    @Test
    fun `a live rebroadcast with no prior entry defaults to alarmable`() {
        assertTrue(MeshSosManager.computeAlarmable(active = true, isLive = true, ageSec = 0, isCarriedVerified = false, existingAlarmable = null))
    }

    // ── PART A / A3: a cryptographically VERIFIED carried SOS uses the wide
    // (6h, same as SOS_CARRY_EXPIRY_MINS) ceiling, not the strict 30-minute
    // one — see MeshSosManager's CARRIED_SOS_ALARM_MAX_AGE_SEC doc. ──────────

    @Test
    fun `a 2-hour-old verified carried SOS is accepted and alarms`() {
        val twoHoursSec = 2 * 60 * 60L
        assertTrue(
            MeshSosManager.computeAlarmable(active = true, isLive = false, ageSec = twoHoursSec, isCarriedVerified = true, existingAlarmable = null)
        )
    }

    @Test
    fun `a verified carried SOS right at the 6-hour ceiling still alarms`() {
        assertTrue(
            MeshSosManager.computeAlarmable(active = true, isLive = false, ageSec = sixHoursSec, isCarriedVerified = true, existingAlarmable = null)
        )
    }

    @Test
    fun `a verified carried SOS past the 6-hour ceiling no longer alarms`() {
        assertFalse(
            MeshSosManager.computeAlarmable(active = true, isLive = false, ageSec = sixHoursSec + 1, isCarriedVerified = true, existingAlarmable = null)
        )
    }

    // ── Unauthenticated-replay path (isCarriedVerified = false) keeps the
    // original, stricter 30-minute ceiling — untouched by PART A. ───────────

    @Test
    fun `an unauthenticated replay past 30 minutes does not alarm`() {
        assertFalse(
            MeshSosManager.computeAlarmable(active = true, isLive = false, ageSec = thirtyMinSec + 1, isCarriedVerified = false, existingAlarmable = null)
        )
    }

    @Test
    fun `an unauthenticated replay within 30 minutes still alarms`() {
        assertTrue(
            MeshSosManager.computeAlarmable(active = true, isLive = false, ageSec = thirtyMinSec - 1, isCarriedVerified = false, existingAlarmable = null)
        )
    }

    @Test
    fun `a 2-hour-old UNVERIFIED replay does not alarm — only a signature-verified carry gets the wide ceiling`() {
        val twoHoursSec = 2 * 60 * 60L
        assertFalse(
            MeshSosManager.computeAlarmable(active = true, isLive = false, ageSec = twoHoursSec, isCarriedVerified = false, existingAlarmable = null)
        )
    }
}
