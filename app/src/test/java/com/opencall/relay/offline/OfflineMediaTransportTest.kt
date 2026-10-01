package com.opencall.relay.offline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * PHASE 4: pure-JVM tests for the relay-suppression companion functions
 * (isRelaySuppressionAllowlisted / relayFrameId / isUrgentRelayFrame /
 * relayDelayMs) and [OfflineMediaTransport.RelayDedupeCache] — all plain
 * data/logic, no Context/socket/camera dependency, so this never
 * instantiates a real OfflineMediaTransport (this project has no
 * Robolectric — see PartyRingViewTest/MeshCompassTest for the same
 * constraint).
 */
class OfflineMediaTransportTest {

    // ── Allowlist (report 4.0): media types REJECTED, the 10 named types accepted ──
    // NOTE: 4.0's own explicit allowlist names CHAT(4) as INCLUDED ("SUPPRESSION
    // APPLIES ONLY TO: CHAT(4), ROSTER(11), SOS(20), ..."), so a literal "reject
    // types 1-6" would contradict 4.0 itself for type 4. 4.0 is the more
    // authoritative, detailed source ("the most important paragraph in this
    // document") and its rationale is sound — chat text, unlike audio/video,
    // tolerates a short delay fine and benefits from duplicate suppression in a
    // flooded mesh. This tests the actual media-critical types in 1-6
    // (CONFIG/FRAME/AUDIO/MODE/AUDIO_CODEC) are rejected, and separately
    // confirms CHAT(4) — the one deliberate exception — IS allowlisted.

    @Test
    fun `allowlist rejects the media-critical types in 1-6 — CONFIG, FRAME, AUDIO, MODE, AUDIO_CODEC`() {
        for (type in listOf(1, 2, 3, 5, 6)) {
            assertFalse("type=$type must NOT be allowlisted", OfflineMediaTransport.isRelaySuppressionAllowlisted(type.toByte()))
        }
    }

    @Test
    fun `CHAT(4) is the one deliberate exception within 1-6 — explicitly allowlisted per 4_0`() {
        assertTrue(OfflineMediaTransport.isRelaySuppressionAllowlisted(4))
    }

    @Test
    fun `allowlist accepts exactly the 10 named types`() {
        val allowlisted = setOf(4, 11, 20, 21, 22, 23, 24, 25, 26, 35) // CHAT/ROSTER/SOS/FIND_REQ/FIND_RESP/POSITION/SOS_ACK/STORE_FWD/SF_ACK/PHRASE
        for (type in allowlisted) {
            assertTrue("type=$type should be allowlisted", OfflineMediaTransport.isRelaySuppressionAllowlisted(type.toByte()))
        }
    }

    @Test
    fun `allowlist rejects every other type outside the named 10, for example 7-19 and 27-34`() {
        val allowlisted = setOf(4, 11, 20, 21, 22, 23, 24, 25, 26, 35)
        for (type in 0..40) {
            if (type in allowlisted) continue
            assertFalse("type=$type must NOT be allowlisted", OfflineMediaTransport.isRelaySuppressionAllowlisted(type.toByte()))
        }
    }

    // ── isUrgentRelayFrame: SOS/SOS_ACK/FIND_REQ always, PHRASE only if NEED_HELP ──

    @Test
    fun `SOS, SOS_ACK and FIND_REQ are always urgent regardless of payload`() {
        assertTrue(OfflineMediaTransport.isUrgentRelayFrame(20, ByteArray(0)))
        assertTrue(OfflineMediaTransport.isUrgentRelayFrame(24, ByteArray(0)))
        assertTrue(OfflineMediaTransport.isUrgentRelayFrame(21, ByteArray(0)))
    }

    @Test
    fun `PHRASE is urgent only when its wire code is NEED_HELP (6)`() {
        val needHelpPayload = byteArrayOf(6, 0, 0, 0, 0) // [1B code=6][4B seq]
        assertTrue(OfflineMediaTransport.isUrgentRelayFrame(35, needHelpPayload))
        val imOkPayload = byteArrayOf(0, 0, 0, 0, 0) // code=0 (IM_OK)
        assertFalse(OfflineMediaTransport.isUrgentRelayFrame(35, imOkPayload))
    }

    @Test
    fun `PHRASE with empty payload is never urgent (defensive, never crashes)`() {
        assertFalse(OfflineMediaTransport.isUrgentRelayFrame(35, ByteArray(0)))
    }

    @Test
    fun `every other type is never urgent`() {
        for (type in listOf(4, 11, 22, 23, 25, 26)) {
            assertFalse("type=$type must not be urgent", OfflineMediaTransport.isUrgentRelayFrame(type.toByte(), ByteArray(0)))
        }
    }

    // ── relayFrameId: stable on identical input, different on one flipped byte ──

    @Test
    fun `relayFrameId is stable for identical (srcId, type, payload)`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val a = OfflineMediaTransport.relayFrameId(42L, 4, payload)
        val b = OfflineMediaTransport.relayFrameId(42L, 4, payload)
        assertEquals(a, b)
    }

    @Test
    fun `relayFrameId differs when a single payload byte flips`() {
        val a = OfflineMediaTransport.relayFrameId(42L, 4, byteArrayOf(1, 2, 3, 4, 5))
        val b = OfflineMediaTransport.relayFrameId(42L, 4, byteArrayOf(1, 2, 3, 4, 6))
        assertTrue(a != b)
    }

    @Test
    fun `relayFrameId differs when srcId or type differ, same payload`() {
        val payload = byteArrayOf(9, 9, 9)
        val base = OfflineMediaTransport.relayFrameId(1L, 4, payload)
        assertTrue(base != OfflineMediaTransport.relayFrameId(2L, 4, payload))
        assertTrue(base != OfflineMediaTransport.relayFrameId(1L, 11, payload))
    }

    @Test
    fun `relayFrameId handles an empty payload without throwing`() {
        assertNotNull(OfflineMediaTransport.relayFrameId(1L, 4, ByteArray(0)))
    }

    // ── relayDelayMs: three NEVER gates, then the exact formula ──────────────

    private fun fixedRandom(v: Float): () -> Float = { v }

    @Test
    fun `relay disabled is always NEVER regardless of everything else`() {
        assertNull(OfflineMediaTransport.relayDelayMs(relayEnabled = false, relayOnlyWhenCharging = false, charging = true, batteryPct = 100, relayMinBattery = 0))
    }

    @Test
    fun `relay-only-when-charging blocks an unplugged device`() {
        assertNull(OfflineMediaTransport.relayDelayMs(relayEnabled = true, relayOnlyWhenCharging = true, charging = false, batteryPct = 100, relayMinBattery = 0))
    }

    @Test
    fun `relay-only-when-charging does NOT block a plugged-in device`() {
        assertNotNull(OfflineMediaTransport.relayDelayMs(relayEnabled = true, relayOnlyWhenCharging = true, charging = true, batteryPct = 100, relayMinBattery = 0))
    }

    @Test
    fun `below minimum battery and not charging is NEVER`() {
        assertNull(OfflineMediaTransport.relayDelayMs(relayEnabled = true, relayOnlyWhenCharging = false, charging = false, batteryPct = 10, relayMinBattery = 20))
    }

    @Test
    fun `below minimum battery but charging is NOT blocked by the battery gate`() {
        assertNotNull(OfflineMediaTransport.relayDelayMs(relayEnabled = true, relayOnlyWhenCharging = false, charging = true, batteryPct = 10, relayMinBattery = 20))
    }

    @Test
    fun `at exactly the minimum battery (not below) is allowed`() {
        assertNotNull(OfflineMediaTransport.relayDelayMs(relayEnabled = true, relayOnlyWhenCharging = false, charging = false, batteryPct = 20, relayMinBattery = 20))
    }

    // Exact formula, jitter pinned to 1.0 (random()=0.5 -> jitter=0.5+0.5=1.0)
    // so delay = 800 * (1.05 - score), at every requested percentage, charging
    // and not. Expected values are computed with the SAME Float arithmetic
    // relayDelayMs itself uses (not a hand-typed decimal) — IEEE 754 Float
    // subtraction/multiplication doesn't always land on an exact decimal
    // (e.g. 1.05f-0.60f isn't bit-exact 0.45f), so a hardcoded literal can be
    // off by 1ms after truncation even though the implementation is correct;
    // mirroring the exact expression is what makes this assertion meaningful
    // rather than fragile.
    private fun expectedDelayMs(batteryPct: Int, charging: Boolean): Long {
        var score = batteryPct / 100f
        if (charging) score = kotlin.math.min(1f, score + 0.4f)
        return (800L * (1.05f - score) * 1.0f).toLong() // jitter=1.0 (random()=0.5)
    }

    @Test
    fun `100 percent not charging`() {
        val d = OfflineMediaTransport.relayDelayMs(true, false, false, 100, 0, fixedRandom(0.5f))!!
        assertEquals(expectedDelayMs(100, false), d)
    }

    @Test
    fun `100 percent charging (score clamps at 1_0, does not exceed)`() {
        val d = OfflineMediaTransport.relayDelayMs(true, false, true, 100, 0, fixedRandom(0.5f))!!
        assertEquals(expectedDelayMs(100, true), d) // 1.0+0.4 clamps to 1.0, same as not-charging 100%
    }

    @Test
    fun `95 percent charging is approximately the documented 40ms`() {
        val d = OfflineMediaTransport.relayDelayMs(true, false, true, 95, 0, fixedRandom(0.5f))!!
        // score=0.95+0.4 clamped to 1.0 -> ~40ms at jitter=1.0
        assertEquals(expectedDelayMs(95, true), d)
        assertTrue("expected close to 40ms, got $d", kotlin.math.abs(d - 40L) <= 1L)
    }

    @Test
    fun `60 percent not charging is approximately the documented 360ms`() {
        val d = OfflineMediaTransport.relayDelayMs(true, false, false, 60, 0, fixedRandom(0.5f))!!
        // score=0.6 -> ~360ms at jitter=1.0
        assertEquals(expectedDelayMs(60, false), d)
        assertTrue("expected close to 360ms, got $d", kotlin.math.abs(d - 360L) <= 1L)
    }

    @Test
    fun `20 percent not charging`() {
        val d = OfflineMediaTransport.relayDelayMs(true, false, false, 20, 0, fixedRandom(0.5f))!!
        // score=0.2 -> ~680ms at jitter=1.0 (formula as specified — the
        // task's own "~800ms" is an approximation; the precise formula is
        // what's implemented and tested here).
        assertEquals(expectedDelayMs(20, false), d)
    }

    @Test
    fun `10 percent not charging`() {
        val d = OfflineMediaTransport.relayDelayMs(true, false, false, 10, 0, fixedRandom(0.5f))!!
        assertEquals(expectedDelayMs(10, false), d)
    }

    @Test
    fun `jitter is bounded to the documented 0_5 to 1_5 multiplier range`() {
        val base = 800.0 * (1.05 - 0.60) // 60%, not charging
        val low = OfflineMediaTransport.relayDelayMs(true, false, false, 60, 0, fixedRandom(0.0f))!! // jitter=0.5
        val high = OfflineMediaTransport.relayDelayMs(true, false, false, 60, 0, fixedRandom(0.999f))!! // jitter~1.499
        assertTrue("low=$low should be near half of base=$base", low <= (base * 0.55))
        assertTrue("high=$high should be near 1.5x base=$base", high >= (base * 1.4))
    }

    @Test
    fun `charging always scores at least as high as not charging at the same battery percent`() {
        val notCharging = OfflineMediaTransport.relayDelayMs(true, false, false, 50, 0, fixedRandom(0.5f))!!
        val charging = OfflineMediaTransport.relayDelayMs(true, false, true, 50, 0, fixedRandom(0.5f))!!
        // higher score -> (1.05-score) smaller -> shorter delay
        assertTrue("charging delay ($charging) should be <= not-charging delay ($notCharging)", charging <= notCharging)
    }

    // ── RelayDedupeCache: dupCount progression 0/1/2/3 ────────────────────

    @Test
    fun `first observation is not a duplicate, dupCount starts at 0`() {
        val cache = OfflineMediaTransport.RelayDedupeCache()
        val seen = cache.observe(1L, 0L)
        assertFalse(seen)
        assertEquals(0, cache.dupCountFor(1L))
    }

    @Test
    fun `dupCount increments 1, 2, 3 across repeated observations of the same id`() {
        val cache = OfflineMediaTransport.RelayDedupeCache()
        cache.observe(1L, 0L) // first: not a dup
        assertTrue(cache.observe(1L, 1L)) // dup #1
        assertEquals(1, cache.dupCountFor(1L))
        assertTrue(cache.observe(1L, 2L)) // dup #2
        assertEquals(2, cache.dupCountFor(1L))
        assertTrue(cache.observe(1L, 3L)) // dup #3
        assertEquals(3, cache.dupCountFor(1L))
    }

    @Test
    fun `K_SUPPRESS=2 means dupCount 0 and 1 still forward, 2 and 3 suppress`() {
        assertTrue("dupCount 0 < K_SUPPRESS(2) — forwards", 0 < OfflineMediaTransport.RELAY_K_SUPPRESS)
        assertTrue("dupCount 1 < K_SUPPRESS(2) — forwards", 1 < OfflineMediaTransport.RELAY_K_SUPPRESS)
        assertFalse("dupCount 2 is NOT < K_SUPPRESS(2) — suppressed", 2 < OfflineMediaTransport.RELAY_K_SUPPRESS)
        assertFalse("dupCount 3 is NOT < K_SUPPRESS(2) — suppressed", 3 < OfflineMediaTransport.RELAY_K_SUPPRESS)
    }

    @Test
    fun `different ids never share a dupCount`() {
        val cache = OfflineMediaTransport.RelayDedupeCache()
        cache.observe(1L, 0L)
        cache.observe(1L, 1L)
        cache.observe(2L, 0L)
        assertEquals(1, cache.dupCountFor(1L))
        assertEquals(0, cache.dupCountFor(2L))
    }

    // ── RelayDedupeCache bounds: 511/512/513, and TTL expiry ──────────────

    @Test
    fun `cache holds exactly 511 entries without evicting anything`() {
        val cache = OfflineMediaTransport.RelayDedupeCache(capacity = 512, ttlMs = 10_000_000L)
        for (i in 0 until 511) cache.observe(i.toLong(), 0L)
        assertEquals(511, cache.size())
        // id=0 (the oldest) must still be present — nothing evicted yet.
        assertTrue("id=0 should still be a known duplicate", cache.observe(0L, 1L))
    }

    @Test
    fun `cache holds exactly 512 entries (the cap) without evicting`() {
        val cache = OfflineMediaTransport.RelayDedupeCache(capacity = 512, ttlMs = 10_000_000L)
        for (i in 0 until 512) cache.observe(i.toLong(), 0L)
        assertEquals(512, cache.size())
        assertTrue("id=0 should still be present at exactly capacity", cache.observe(0L, 1L))
    }

    @Test
    fun `the 513th entry evicts the oldest (id=0), capping size at 512`() {
        val cache = OfflineMediaTransport.RelayDedupeCache(capacity = 512, ttlMs = 10_000_000L)
        for (i in 0 until 513) cache.observe(i.toLong(), 0L)
        assertEquals(512, cache.size())
        // id=0 was the oldest insertion order and must have been evicted —
        // observing it again now counts as a FIRST sighting, not a dup.
        assertFalse("id=0 should have been evicted, so this is a fresh sighting", cache.observe(0L, 1L))
        // id=512 (the most recent before this check) must still be present.
        assertTrue("id=512 should still be present", cache.observe(512L, 1L))
    }

    @Test
    fun `an entry older than the TTL is pruned and no longer counts as a duplicate`() {
        val cache = OfflineMediaTransport.RelayDedupeCache(capacity = 512, ttlMs = 1_000L)
        cache.observe(1L, 0L) // seen at t=0
        // Still within TTL at t=999 -> still a duplicate.
        assertTrue(cache.observe(1L, 999L))
        // Past TTL at t=1001 (1001 - 0 > 1000) -> pruned, fresh sighting.
        assertFalse(cache.observe(1L, 1_001L))
    }

    @Test
    fun `TTL expiry also drops the id's dupCount, not just its seen marker`() {
        val cache = OfflineMediaTransport.RelayDedupeCache(capacity = 512, ttlMs = 1_000L)
        cache.observe(1L, 0L)
        cache.observe(1L, 100L) // dup #1
        assertEquals(1, cache.dupCountFor(1L))
        cache.observe(1L, 1_500L) // past TTL -> pruned -> fresh sighting -> dupCount reset
        assertEquals(0, cache.dupCountFor(1L))
    }

    // ── Pending deferral cancellation (TEARDOWN) ──────────────────────────
    // OfflineMediaTransport.cancelAllPendingRelayDeferrals() itself needs a
    // real transport instance (sockets/camera/context) to construct, which
    // this project's plain-JVM test setup (no Robolectric) can't do. This
    // proves the underlying JDK mechanism it relies on — cancel(false) on a
    // ScheduledFuture before it fires prevents the task from ever running —
    // which is the exact primitive cancelAllPendingRelayDeferrals uses.

    @Test
    fun `cancelling a scheduled future before it fires prevents the task from running`() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            var ran = false
            val future = scheduler.schedule({ ran = true }, 500, TimeUnit.MILLISECONDS)
            future.cancel(false)
            Thread.sleep(700) // well past when it would have fired
            assertFalse("task must not have run after cancellation", ran)
        } finally {
            scheduler.shutdownNow()
        }
    }

    @Test
    fun `an uncancelled scheduled future does run — sanity check for the test above`() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            var ran = false
            scheduler.schedule({ ran = true }, 100, TimeUnit.MILLISECONDS)
            Thread.sleep(400)
            assertTrue("task should have run when not cancelled", ran)
        } finally {
            scheduler.shutdownNow()
        }
    }

    // ── OCP PHASE 2.2: seedMixSpeakers (tickGoMix's initial-commit seeding) ─

    @Test
    fun `seedMixSpeakers prefers the most recent VAD-active peers when any exist`() {
        val seed = OfflineMediaTransport.seedMixSpeakers(
            rawMixSpeakers = listOf(5L, 6L, 7L, 8L),
            participants = listOf(1L, 5L, 6L, 7L, 8L),
            localNodeId = 1L,
            joinSequence = emptyMap()
        )
        assertEquals(listOf(5L, 6L, 7L), seed) // take(3), VAD ranking order preserved
    }

    @Test
    fun `seedMixSpeakers falls back to join order when no VAD data exists yet`() {
        val seed = OfflineMediaTransport.seedMixSpeakers(
            rawMixSpeakers = emptyList(),
            participants = listOf(1L, 2L, 3L, 4L, 5L),
            localNodeId = 1L,
            joinSequence = mapOf(2L to 3, 3L to 1, 4L to 2, 5L to 0)
        )
        // Excludes localNodeId(1), ranked by join order ascending: 5(0),3(1),4(2)
        assertEquals(listOf(5L, 3L, 4L), seed)
    }

    @Test
    fun `seedMixSpeakers returns empty when there is nothing to seed from`() {
        val seed = OfflineMediaTransport.seedMixSpeakers(
            rawMixSpeakers = emptyList(),
            participants = listOf(1L), // only self
            localNodeId = 1L,
            joinSequence = emptyMap()
        )
        assertTrue(seed.isEmpty())
    }

    // ── OCP PHASE 0.4: LAT line throttles to 1/sec/peer ─────────────────

    @Test
    fun `LAT line throttles to 1 per second per peer under a 100-frame burst`() {
        // Simulates 100 frames arriving ~10ms apart (a realistic burst,
        // faster than any real audio/video cadence) for ONE peer — mirrors
        // maybeLogPeerLatency's own lastLogAtMs-per-peer bookkeeping.
        val baseMs = 1_700_000_000_000L
        var lastLogAtMs = 0L
        var logCount = 0
        var now = baseMs
        repeat(100) {
            if (OfflineMediaTransport.shouldLogLatNow(lastLogAtMs, now)) {
                logCount++
                lastLogAtMs = now
            }
            now += 10L // 100 frames * 10ms = 1000ms of simulated burst
        }
        // ~1000ms of burst at 1/sec throttling -> exactly 1 or 2 log lines,
        // never anywhere near 100.
        assertTrue("expected 1-2 log lines, got $logCount", logCount in 1..2)
    }

    @Test
    fun `LAT line logs immediately for a peer never logged before`() {
        assertTrue(OfflineMediaTransport.shouldLogLatNow(0L, 1_700_000_000_000L))
    }

    @Test
    fun `LAT line does not log again within the same second`() {
        val now = 1_700_000_000_000L
        assertFalse(OfflineMediaTransport.shouldLogLatNow(now - 500L, now))
    }

    @Test
    fun `LAT line throttling is independent per peer`() {
        val now = 1_700_000_000_000L
        val lastLogAtMsByPeer = mutableMapOf(1L to now - 100L, 2L to now - 1_500L)
        assertFalse(OfflineMediaTransport.shouldLogLatNow(lastLogAtMsByPeer.getValue(1L), now))
        assertTrue(OfflineMediaTransport.shouldLogLatNow(lastLogAtMsByPeer.getValue(2L), now))
    }

    // ── OCP PHASE 3.3: FrameAgeTracker — delta-based age, not wall-clock ───

    @Test
    fun `first frame from a source has age 0 — establishes the baseline`() {
        val tracker = OfflineMediaTransport.FrameAgeTracker()
        val age = tracker.ageMs(srcId = 1L, theirStampMicros = 5_000_000L, nowMicros = 5_000_000L)
        assertEquals(0L, age)
    }

    @Test
    fun `age reflects real network delay even when sender clock is an hour off from receiver clock`() {
        val tracker = OfflineMediaTransport.FrameAgeTracker()
        val hourMicros = 3_600_000_000L
        // Baseline: sender's clock reads an hour BEHIND this device's — first
        // frame arrives with zero network delay (age 0 by definition).
        tracker.ageMs(srcId = 1L, theirStampMicros = 0L, nowMicros = hourMicros)
        // A LATER frame, captured 1s after the first (per sender's clock),
        // but delayed 50ms in the network on top of that — nowMicros must
        // account for BOTH the capture-time advance and the added delay.
        val age = tracker.ageMs(srcId = 1L, theirStampMicros = 1_000_000L, nowMicros = hourMicros + 1_000_000L + 50_000L)
        assertEquals(50L, age)
    }

    @Test
    fun `age is monotonic across 1000 synthetic frames at a steady 20ms cadence`() {
        val tracker = OfflineMediaTransport.FrameAgeTracker()
        var theirStamp = 0L
        var now = 10_000_000L // arbitrary boot-relative offset
        var prevAge = -1L
        repeat(1000) {
            val age = tracker.ageMs(srcId = 7L, theirStampMicros = theirStamp, nowMicros = now)
            // Steady cadence, no queueing modeled — age should stay pinned
            // near 0 (same delta every frame), never drift upward.
            assertTrue("age should stay ~0, got $age at frame $it", age in 0..2)
            prevAge = age
            theirStamp += 20_000L
            now += 20_000L
        }
        assertTrue(prevAge in 0..2)
    }

    @Test
    fun `a frame delayed in the network shows a larger age than an on-time one`() {
        val tracker = OfflineMediaTransport.FrameAgeTracker()
        tracker.ageMs(srcId = 2L, theirStampMicros = 0L, nowMicros = 0L) // baseline
        val onTime = tracker.ageMs(srcId = 2L, theirStampMicros = 20_000L, nowMicros = 20_000L)
        val delayed = tracker.ageMs(srcId = 2L, theirStampMicros = 40_000L, nowMicros = 340_000L) // 300ms late
        assertTrue(delayed > onTime)
        assertEquals(300L, delayed)
    }

    @Test
    fun `a monotonic decrease in sender timestamp re-baselines instead of producing a huge or negative age`() {
        val tracker = OfflineMediaTransport.FrameAgeTracker()
        tracker.ageMs(srcId = 3L, theirStampMicros = 1_000_000L, nowMicros = 1_000_000L)
        tracker.ageMs(srcId = 3L, theirStampMicros = 1_020_000L, nowMicros = 1_020_000L)
        // Sender restarted — its monotonic clock reset back near zero.
        val afterReset = tracker.ageMs(srcId = 3L, theirStampMicros = 100L, nowMicros = 1_040_100L)
        assertEquals(0L, afterReset) // re-baselined, not a multi-second spike
        // Next frame: captured 20ms after the reset frame, PLUS a 20ms
        // network delay on top — nowMicros must add both.
        val next = tracker.ageMs(srcId = 3L, theirStampMicros = 20_100L, nowMicros = 1_040_100L + 20_000L + 20_000L)
        assertEquals(20L, next)
    }

    @Test
    fun `age never goes negative even with clock jitter`() {
        val tracker = OfflineMediaTransport.FrameAgeTracker()
        tracker.ageMs(srcId = 4L, theirStampMicros = 1000L, nowMicros = 1000L)
        // A frame that (due to jitter) appears to arrive "before" its own
        // capture relative to the baseline — must clamp to 0, never negative.
        val age = tracker.ageMs(srcId = 4L, theirStampMicros = 1000L, nowMicros = 999L)
        assertTrue(age >= 0L)
    }

    // ── OCP PHASE 3.4: shouldKeepAgedFrame — the actual lag fix ────────────

    @Test
    fun `a frame within budget is always kept`() {
        assertTrue(OfflineMediaTransport.shouldKeepAgedFrame(ageMs = 100L, budgetMs = 200L, isIdr = false, isAtLeastAsNewAsLastAcceptedIdr = false))
    }

    @Test
    fun `an over-budget non-IDR frame is dropped`() {
        assertFalse(OfflineMediaTransport.shouldKeepAgedFrame(ageMs = 300L, budgetMs = 200L, isIdr = false, isAtLeastAsNewAsLastAcceptedIdr = true))
    }

    @Test
    fun `an over-budget IDR with no newer IDR yet accepted is kept`() {
        assertTrue(OfflineMediaTransport.shouldKeepAgedFrame(ageMs = 300L, budgetMs = 250L, isIdr = true, isAtLeastAsNewAsLastAcceptedIdr = true))
    }

    @Test
    fun `an over-budget IDR that is OLDER than an already-accepted newer IDR is still dropped`() {
        assertFalse(OfflineMediaTransport.shouldKeepAgedFrame(ageMs = 300L, budgetMs = 250L, isIdr = true, isAtLeastAsNewAsLastAcceptedIdr = false))
    }

    @Test
    fun `audio and video use their own documented budgets — 200ms and 250ms`() {
        assertTrue(OfflineMediaTransport.shouldKeepAgedFrame(200L, 200L, false, false))  // audio, exactly at budget
        assertFalse(OfflineMediaTransport.shouldKeepAgedFrame(201L, 200L, false, false)) // audio, 1ms over
        assertTrue(OfflineMediaTransport.shouldKeepAgedFrame(250L, 250L, false, false))  // video, exactly at budget
        assertFalse(OfflineMediaTransport.shouldKeepAgedFrame(251L, 250L, false, false)) // video, 1ms over
    }

    // ── OCP PHASE 4.3: tileBudget/maxLiveCameras follow the probe, no flat 4 ─

    @Test
    fun `deriveTileBudget follows probe results of 2, 4, 6, 8`() {
        assertEquals(2, OfflineMediaTransport.deriveTileBudget(3))  // probed=3 -> 3-1=2
        assertEquals(4, OfflineMediaTransport.deriveTileBudget(5))  // probed=5 -> 5-1=4
        assertEquals(6, OfflineMediaTransport.deriveTileBudget(7))  // probed=7 -> 7-1=6
        assertEquals(8, OfflineMediaTransport.deriveTileBudget(9))  // probed=9 -> 9-1=8
    }

    @Test
    fun `deriveTileBudget is coerced into 2 to 8 — never below the floor or above the ceiling`() {
        assertEquals(2, OfflineMediaTransport.deriveTileBudget(1))   // 1-1=0 -> floors at 2
        assertEquals(2, OfflineMediaTransport.deriveTileBudget(0))
        assertEquals(8, OfflineMediaTransport.deriveTileBudget(20))  // 20-1=19 -> ceilings at 8
    }

    @Test
    fun `deriveMaxLiveCameras is coerced into 2 to MAX_GROUP_PARTICIPANTS(8) — never a flat 4`() {
        assertEquals(2, OfflineMediaTransport.deriveMaxLiveCameras(1))
        assertEquals(4, OfflineMediaTransport.deriveMaxLiveCameras(5))
        assertEquals(8, OfflineMediaTransport.deriveMaxLiveCameras(100)) // still capped at the WFD group ceiling
    }

    // ── OCP PHASE 5.1/5.2: splitHighLow — decoder count never expands ──────

    @Test
    fun `below the simulcast threshold, everyone is HIGH and low is empty — byte-for-byte Phase 4 behavior`() {
        val (high, low) = OfflineMediaTransport.splitHighLow(
            visiblePeersRanked = listOf(1L, 2L, 3L), pin = null, activeSpeakerId = null, simulcastActive = false
        )
        assertEquals(setOf(1L, 2L, 3L), high)
        assertTrue(low.isEmpty())
    }

    @Test
    fun `once active, pinned tile and active speaker are HIGH, everyone else is LOW`() {
        val (high, low) = OfflineMediaTransport.splitHighLow(
            visiblePeersRanked = listOf(1L, 2L, 3L, 4L), pin = 1L, activeSpeakerId = 3L, simulcastActive = true
        )
        assertEquals(setOf(1L, 3L), high)
        assertEquals(setOf(2L, 4L), low)
    }

    @Test
    fun `with no pin and no active speaker, the highest-ranked visible peer becomes HIGH so the grid is never all-low`() {
        val (high, low) = OfflineMediaTransport.splitHighLow(
            visiblePeersRanked = listOf(5L, 6L, 7L), pin = null, activeSpeakerId = null, simulcastActive = true
        )
        assertEquals(setOf(5L), high)
        assertEquals(setOf(6L, 7L), low)
    }

    @Test
    fun `high and low never overlap, and together cover exactly the visible set`() {
        val (high, low) = OfflineMediaTransport.splitHighLow(
            visiblePeersRanked = listOf(1L, 2L, 3L, 4L, 5L), pin = 2L, activeSpeakerId = null, simulcastActive = true
        )
        assertTrue((high intersect low).isEmpty())
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L), high + low)
    }

    @Test
    fun `pin or speaker not currently visible is simply not added to HIGH`() {
        // Pinned peer isn't camera-on right now (not in the visible list) —
        // must not appear in either set.
        val (high, low) = OfflineMediaTransport.splitHighLow(
            visiblePeersRanked = listOf(2L, 3L), pin = 99L, activeSpeakerId = null, simulcastActive = true
        )
        assertEquals(setOf(2L), high) // falls back to highest-ranked visible peer
        assertEquals(setOf(3L), low)
        assertTrue(99L !in high && 99L !in low)
    }

    // ── OCP PHASE 5.4: thermalStatusString ──────────────────────────────────

    @Test
    fun `thermalStatusString maps every documented status`() {
        assertEquals("none", OfflineMediaTransport.thermalStatusString(0))     // THERMAL_STATUS_NONE
        assertEquals("light", OfflineMediaTransport.thermalStatusString(1))    // THERMAL_STATUS_LIGHT
        assertEquals("moderate", OfflineMediaTransport.thermalStatusString(2)) // THERMAL_STATUS_MODERATE
        assertEquals("severe", OfflineMediaTransport.thermalStatusString(3))   // THERMAL_STATUS_SEVERE
        assertEquals("critical", OfflineMediaTransport.thermalStatusString(4))
        assertEquals("emergency", OfflineMediaTransport.thermalStatusString(5))
        assertEquals("shutdown", OfflineMediaTransport.thermalStatusString(6))
    }

    @Test
    fun `thermalStatusString never throws on an unrecognized value`() {
        assertEquals("unknown", OfflineMediaTransport.thermalStatusString(999))
        assertEquals("unknown", OfflineMediaTransport.thermalStatusString(-1))
    }

    // ── Step 3: TYPE_ATTACHMENT_META encode/decode ──────────────────────────

    @Test
    fun `attachment meta round-trips a VOICE entry exactly`() {
        val meta = OfflineMediaTransport.AttachmentMeta(
            msgId = java.util.UUID.randomUUID().toString(),
            kind = OfflineMediaTransport.AttachmentKind.VOICE,
            bodySize = 123456,
            durationMs = 4200
        )
        val decoded = OfflineMediaTransport.decodeAttachmentMeta(OfflineMediaTransport.encodeAttachmentMeta(meta))
        assertEquals(meta, decoded)
    }

    @Test
    fun `attachment meta round-trips an IMAGE entry's filename and mimeType exactly`() {
        val meta = OfflineMediaTransport.AttachmentMeta(
            msgId = java.util.UUID.randomUUID().toString(),
            kind = OfflineMediaTransport.AttachmentKind.IMAGE,
            bodySize = 200_000,
            filename = "summit-view.jpg",
            mimeType = "image/jpeg"
        )
        val decoded = OfflineMediaTransport.decodeAttachmentMeta(OfflineMediaTransport.encodeAttachmentMeta(meta))
        assertEquals(meta, decoded)
    }

    @Test
    fun `attachment meta round-trips a DOCUMENT entry exactly`() {
        val meta = OfflineMediaTransport.AttachmentMeta(
            msgId = java.util.UUID.randomUUID().toString(),
            kind = OfflineMediaTransport.AttachmentKind.DOCUMENT,
            bodySize = 50_000,
            filename = "route-plan.pdf",
            mimeType = "application/pdf"
        )
        val decoded = OfflineMediaTransport.decodeAttachmentMeta(OfflineMediaTransport.encodeAttachmentMeta(meta))
        assertEquals(meta, decoded)
    }

    @Test
    fun `attachment meta round-trips a LOCATION entry with bodySize 0 — inline, no fetch`() {
        val meta = OfflineMediaTransport.AttachmentMeta(
            msgId = java.util.UUID.randomUUID().toString(),
            kind = OfflineMediaTransport.AttachmentKind.LOCATION,
            bodySize = 0,
            latE7 = 457_000_000,
            lonE7 = -1_220_000_000
        )
        val decoded = OfflineMediaTransport.decodeAttachmentMeta(OfflineMediaTransport.encodeAttachmentMeta(meta))
        assertEquals(meta, decoded)
        assertTrue(OfflineMediaTransport.AttachmentKind.LOCATION.isInline)
    }

    @Test
    fun `attachment meta round-trips a CONTACT entry with bodySize 0 — inline, no fetch`() {
        val meta = OfflineMediaTransport.AttachmentMeta(
            msgId = java.util.UUID.randomUUID().toString(),
            kind = OfflineMediaTransport.AttachmentKind.CONTACT,
            bodySize = 0,
            contactName = "Alice Smith",
            contactPhone = "+15551234567"
        )
        val decoded = OfflineMediaTransport.decodeAttachmentMeta(OfflineMediaTransport.encodeAttachmentMeta(meta))
        assertEquals(meta, decoded)
        assertTrue(OfflineMediaTransport.AttachmentKind.CONTACT.isInline)
    }

    @Test
    fun `VOICE and IMAGE and DOCUMENT are fetch-gated, not inline`() {
        assertFalse(OfflineMediaTransport.AttachmentKind.VOICE.isInline)
        assertFalse(OfflineMediaTransport.AttachmentKind.IMAGE.isInline)
        assertFalse(OfflineMediaTransport.AttachmentKind.DOCUMENT.isInline)
    }

    @Test
    fun `attachment meta decode returns null for anything shorter than the fixed header`() {
        assertNull(OfflineMediaTransport.decodeAttachmentMeta(ByteArray(10)))
        assertNull(OfflineMediaTransport.decodeAttachmentMeta(ByteArray(0)))
    }

    @Test
    fun `attachment meta decode returns null for an unrecognized kind`() {
        // Unlike the old voice-note decode (which deferred codec rejection
        // to the caller), an unknown AttachmentKind IS a decode failure here
        // — every other field's shape depends on knowing which kind it is,
        // so there's no safe partial decode to hand back.
        val msgId = java.util.UUID.randomUUID()
        val buf = java.nio.ByteBuffer.allocate(16 + 1 + 4 + 2)
        buf.putLong(msgId.mostSignificantBits)
        buf.putLong(msgId.leastSignificantBits)
        buf.put(99.toByte()) // not a real AttachmentKind wireId
        buf.putInt(0)
        buf.putShort(0)
        assertNull(OfflineMediaTransport.decodeAttachmentMeta(buf.array()))
    }

    @Test
    fun `MAX_ATTACHMENT_BODY_BYTES actually covers a worst-case voice recording at the recorder's real bitrate`() {
        // fix (real bug, Step 1): the cap this guards against regressing —
        // every voice note sent before Step 1 was silently dropped on
        // receive because no cap existed for it at all (fell to the 1024B
        // else branch in maxPayloadFor). Worst case: VoiceNoteRecorder's
        // 32000bps AAC for the full VOICE_NOTE_MAX_DURATION_MS — comfortably
        // under the shared attachment body cap, with real margin for
        // container overhead.
        val bitrateBytesPerSec = 32000 / 8
        val worstCaseAudioBytes = bitrateBytesPerSec * (OfflineMediaTransport.VOICE_NOTE_MAX_DURATION_MS / 1000)
        assertTrue(
            "worst-case voice note body ($worstCaseAudioBytes bytes) must fit under the shared attachment cap",
            worstCaseAudioBytes < OfflineMediaTransport.MAX_ATTACHMENT_BODY_BYTES
        )
    }

    // NOTE on 2.5's "no window exists where raw is suppressed AND
    // goMixLive is false": isGoMixReplacingBroadcastAudio (private,
    // OfflineMediaTransport.kt) is now `return goMixLive.get()` for
    // TYPE_AUDIO — a single-line read of the same flag tickGoMix only ever
    // sets true AFTER a frame has actually been sent (see tickGoMix's
    // `if (mixesBuilt > 0) { ...; goMixLive.compareAndSet(false, true) }`)
    // and sets false as the very FIRST statement once eligibility is lost
    // (`if (goMixLive.compareAndSet(true, false))`, before
    // committedMixSpeakers is even cleared). The invariant holds by
    // construction — there is no code path that can observe
    // suppressDownward=true while goMixLive=false, since they read the
    // identical AtomicBoolean. Not independently unit-testable without a
    // live transport instance (private, socket-backed); see the OUTPUT
    // report's Phase 2 section for the file:line proof.
}
