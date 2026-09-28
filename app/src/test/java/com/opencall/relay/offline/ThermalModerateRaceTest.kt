package com.opencall.relay.offline

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * STABILITY AUDIT 1b: pure-JVM proof of the race-safety pattern now applied
 * to OfflineMediaTransport.applyThermalStatus's THERMAL_STATUS_MODERATE
 * branch — capture a local val from the `@Volatile groupCall` field BEFORE
 * using it, exactly as the THERMAL_STATUS_SEVERE branch a few lines above
 * already did. OfflineMediaTransport itself is never instantiated here (real
 * Android Context/PowerManager, out of scope for this project's unit tests)
 * — same reasoning as GroupDecoderLockTest/GroupAudioDecoderLockTest: this
 * proves the PATTERN (one volatile read, captured locally, used only through
 * that local from then on) is safe against a concurrent writer nulling the
 * field mid-check — the exact race between the main-thread thermal listener
 * and endGroupCallState() running on a read-loop thread when the last-but-
 * one participant leaves — which the buggy shape
 * (`if (x != null) use(x!!)`, re-reading the volatile field a SECOND time
 * inside the branch) was not safe against.
 */
class ThermalModerateRaceTest {

    private class Holder<T> {
        @Volatile var value: T? = null
    }

    /** The FIXED shape (this fix, and the pre-existing SEVERE branch): one
     *  volatile read, captured locally, used from the local for the rest of
     *  the branch. Mirrors `val gc = groupCall; if (gc != null)
     *  applyResolutionLadder(gc.participants.size) else ...`. */
    private fun raceSafeParticipantCount(holder: Holder<IntArray>): Int {
        val gc = holder.value
        return if (gc != null) gc[0] else -1
    }

    @Test
    fun `capturing a local val before use never throws even under sustained concurrent nulling`() {
        val holder = Holder<IntArray>()
        holder.value = intArrayOf(4)
        val running = AtomicInteger(1)
        val exceptions = AtomicInteger(0)
        val iterations = 50_000

        // Concurrent writer: repeatedly nulls and restores the field, exactly
        // like endGroupCallState() nulling groupCall on the read-loop thread
        // while applyThermalStatus reads it on the main thread — the exact
        // trigger is a participant leaving (dropping the count below 2)
        // landing at the same moment a THERMAL_STATUS_MODERATE callback fires.
        val writer = Thread({
            while (running.get() == 1) {
                holder.value = null
                holder.value = intArrayOf(4)
            }
        }, "test-groupCall-writer")
        writer.start()

        repeat(iterations) {
            try {
                raceSafeParticipantCount(holder)
            } catch (e: Exception) {
                exceptions.incrementAndGet()
            }
        }
        running.set(0)
        writer.join(2_000)

        assertEquals("the race-safe local-val pattern must never throw under concurrent nulling", 0, exceptions.get())
    }

    @Test
    fun `the race-safe read returns a consistent value even when the field is null at the moment of the check`() {
        val holder = Holder<IntArray>()
        holder.value = null
        assertEquals(-1, raceSafeParticipantCount(holder))
        holder.value = intArrayOf(7)
        assertEquals(7, raceSafeParticipantCount(holder))
    }
}
