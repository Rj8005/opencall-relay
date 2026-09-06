package com.opencall.relay.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * PART D / D1/D5: pure-JVM concurrency tests reproducing the exact admit
 * algorithm OfflineMediaTransport.addGroupCallParticipant now uses — a
 * single synchronized(participants) block covering the contains-check,
 * size-check, add(), and AtomicInteger join-ordinal assignment together, so
 * concurrent per-peer read threads calling this for different nodeIds can
 * never both pass the size check before either add() runs. Real
 * OfflineMediaTransport/MeshSosManager instances are never constructed here
 * (Context-dependent — same constraint as MeshSignerTest/MeshSosManagerTest/
 * GroupDecoderLockTest); this proves the underlying admission PATTERN, which
 * production code applies verbatim.
 */
class GroupCallAdmitTest {

    private val MAX_GROUP_PARTICIPANTS = 8

    /** Verbatim reproduction of addGroupCallParticipant's synchronized
     *  decision block (see OfflineMediaTransport.kt) — contains-check,
     *  size-check, add, and the AtomicInteger ordinal assignment as ONE
     *  atomic operation under the set's own intrinsic lock. */
    private fun tryAdmit(
        participants: MutableSet<Long>,
        joinSequence: MutableMap<Long, Int>,
        joinSequenceCounter: AtomicInteger,
        nodeId: Long
    ): Boolean {
        return synchronized(participants) {
            when {
                nodeId in participants -> true
                participants.size >= MAX_GROUP_PARTICIPANTS -> false
                else -> {
                    participants.add(nodeId)
                    joinSequence.putIfAbsent(nodeId, joinSequenceCounter.getAndIncrement())
                    true
                }
            }
        }
    }

    @Test
    fun `concurrent joins never exceed MAX_GROUP_PARTICIPANTS`() {
        val participants = Collections.synchronizedSet(mutableSetOf<Long>())
        val joinSequence = ConcurrentHashMap<Long, Int>()
        val joinSequenceCounter = AtomicInteger(0)

        val joinerCount = 40 // far more than the cap, to force real contention
        val start = CountDownLatch(1)
        val done = CountDownLatch(joinerCount)
        val admittedCount = AtomicInteger(0)

        val threads = (0 until joinerCount).map { i ->
            Thread({
                start.await()
                if (tryAdmit(participants, joinSequence, joinSequenceCounter, nodeId = i.toLong())) {
                    admittedCount.incrementAndGet()
                }
                done.countDown()
            }, "test-joiner-$i")
        }
        threads.forEach { it.start() }
        start.countDown()
        assertTrue("joiners did not finish in time", done.await(5, TimeUnit.SECONDS))

        assertEquals("participant set must never exceed the cap", MAX_GROUP_PARTICIPANTS, participants.size)
        assertEquals("exactly MAX_GROUP_PARTICIPANTS admits should have succeeded", MAX_GROUP_PARTICIPANTS, admittedCount.get())
    }

    @Test
    fun `concurrent joins never issue duplicate join ordinals`() {
        val participants = Collections.synchronizedSet(mutableSetOf<Long>())
        val joinSequence = ConcurrentHashMap<Long, Int>()
        val joinSequenceCounter = AtomicInteger(0)

        // Cap raised above the joiner count so every joiner is admitted —
        // isolates the ordinal-uniqueness property from the capacity one.
        val joinerCount = 30
        val start = CountDownLatch(1)
        val done = CountDownLatch(joinerCount)

        val threads = (0 until joinerCount).map { i ->
            Thread({
                start.await()
                synchronized(participants) {
                    participants.add(i.toLong())
                    joinSequence.putIfAbsent(i.toLong(), joinSequenceCounter.getAndIncrement())
                }
                done.countDown()
            }, "test-joiner-$i")
        }
        threads.forEach { it.start() }
        start.countDown()
        assertTrue("joiners did not finish in time", done.await(5, TimeUnit.SECONDS))

        val ordinals = joinSequence.values.toList()
        assertEquals("every joiner should have an ordinal", joinerCount, ordinals.size)
        assertEquals("no two joiners may share an ordinal", ordinals.size, ordinals.toSet().size)
        assertEquals("ordinals must be exactly 0 until joinerCount, no gaps or repeats", (0 until joinerCount).toSet(), ordinals.toSet())
    }

    @Test
    fun `a re-join after a transient reconnect keeps its original ordinal`() {
        val participants = Collections.synchronizedSet(mutableSetOf<Long>())
        val joinSequence = ConcurrentHashMap<Long, Int>()
        val joinSequenceCounter = AtomicInteger(0)

        tryAdmit(participants, joinSequence, joinSequenceCounter, nodeId = 1L)
        tryAdmit(participants, joinSequence, joinSequenceCounter, nodeId = 2L)
        val originalOrdinalFor1 = joinSequence[1L]

        // Same nodeId "re-joins" (already in participants) — putIfAbsent must
        // not reassign it a fresh ordinal.
        tryAdmit(participants, joinSequence, joinSequenceCounter, nodeId = 1L)
        assertEquals(originalOrdinalFor1, joinSequence[1L])
    }

    // ── PART D / D2: setGroupTileSurface's membership guard ─────────────────

    /** Verbatim reproduction of setGroupTileSurface's D2 guard: if [nodeId]
     *  is not currently a call participant, any surface entry for them is
     *  cleared and no decoder configuration is attempted. Returns true iff a
     *  decoder configuration would be attempted (i.e. the guard passed). */
    private fun wouldConfigureDecoder(participants: Set<Long>, tileSurfaces: MutableMap<Long, String>, nodeId: Long, surface: String): Boolean {
        if (nodeId !in participants) {
            tileSurfaces.remove(nodeId)
            return false
        }
        tileSurfaces[nodeId] = surface
        return true
    }

    @Test
    fun `a departed peer's late surface does not create a decoder`() {
        val participants = setOf(1L, 2L) // nodeId 3 has already left
        val tileSurfaces = mutableMapOf<Long, String>()

        val attempted = wouldConfigureDecoder(participants, tileSurfaces, nodeId = 3L, surface = "late-surface")
        assertTrue("a departed peer's surface must not trigger decoder configuration", !attempted)
        assertTrue("no stale surface entry should remain for a departed peer", 3L !in tileSurfaces)
    }

    @Test
    fun `a current participant's surface is still registered normally`() {
        val participants = setOf(1L, 2L)
        val tileSurfaces = mutableMapOf<Long, String>()

        val attempted = wouldConfigureDecoder(participants, tileSurfaces, nodeId = 2L, surface = "surface-for-2")
        assertTrue(attempted)
        assertEquals("surface-for-2", tileSurfaces[2L])
    }
}
