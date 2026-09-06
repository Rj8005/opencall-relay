package com.opencall.relay.offline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * PART B / B1/B3: pure-JVM tests for the per-srcId mutual-exclusion pattern
 * OfflineMediaTransport.groupDecoderLock uses to serialize feedGroupDecoder/
 * configureGroupDecoder/releaseGroupDecoder for the SAME participant's
 * MediaCodec. OfflineMediaTransport itself is never instantiated here (real
 * Android Context/MediaCodec, out of scope for this project's unit tests —
 * same reason noted in MeshSignerTest/MeshSosManagerTest) — instead this
 * proves the mutual-exclusion PRIMITIVE (one lock object per key, from a
 * ConcurrentHashMap.getOrPut, held across the critical section) provides the
 * exact guarantee production code relies on: a release cannot run while a
 * feed for the same srcId is still in progress, and two different srcIds
 * never block each other. A [ReentrantLock] stands in for production's
 * `synchronized(Any())` purely so the test can use a non-blocking
 * [ReentrantLock.tryLock] to prove exclusion deterministically instead of
 * racing on wall-clock sleeps — the mutual-exclusion guarantee is identical.
 */
class GroupDecoderLockTest {

    private fun lockFor(locks: ConcurrentHashMap<Long, ReentrantLock>, id: Long): ReentrantLock =
        locks.getOrPut(id) { ReentrantLock() }

    @Test
    fun `the release path cannot run while a feed is in progress for the same srcId`() {
        val locks = ConcurrentHashMap<Long, ReentrantLock>()
        val srcId = 42L
        val feedHoldsLock = CountDownLatch(1)
        val feedMayExit = CountDownLatch(1)

        val feedThread = Thread({
            val lock = lockFor(locks, srcId)
            lock.lock()
            try {
                feedHoldsLock.countDown()
                feedMayExit.await()
            } finally {
                lock.unlock()
            }
        }, "test-feed-thread")
        feedThread.start()
        assertTrue("feed thread never acquired the lock", feedHoldsLock.await(2, TimeUnit.SECONDS))

        // B1/B3: release (or configure) for the SAME srcId must be blocked
        // while a feed for that srcId is still inside its critical section —
        // tryLock() returning false, without ever blocking the test thread,
        // is proof of that.
        assertFalse(
            "release must not be able to acquire the lock while feed holds it",
            lockFor(locks, srcId).tryLock()
        )

        feedMayExit.countDown()
        feedThread.join(2_000)
        assertFalse("feed thread should have exited", feedThread.isAlive)

        // Once feed releases the lock, release (or a fresh configure) can proceed.
        assertTrue(
            "release must be able to acquire the lock once the feed is done",
            lockFor(locks, srcId).tryLock()
        )
        lockFor(locks, srcId).unlock()
    }

    @Test
    fun `different srcIds get independent locks — one participant's decoder never blocks another's`() {
        val locks = ConcurrentHashMap<Long, ReentrantLock>()
        lockFor(locks, 1L).lock()
        try {
            assertTrue(
                "a different srcId's lock must be independently acquirable",
                lockFor(locks, 2L).tryLock()
            )
            lockFor(locks, 2L).unlock()
        } finally {
            lockFor(locks, 1L).unlock()
        }
    }

    @Test
    fun `getOrPut returns the SAME lock object for repeated lookups of the same srcId`() {
        val locks = ConcurrentHashMap<Long, ReentrantLock>()
        val first = lockFor(locks, 7L)
        val second = lockFor(locks, 7L)
        assertTrue("feed/configure/release for the same srcId must synchronize on the identical lock object", first === second)
    }
}
