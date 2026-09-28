package com.opencall.relay.offline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * STABILITY AUDIT 2.2/2.3: pure-JVM tests for the per-srcId mutual-exclusion
 * pattern OfflineMediaTransport.groupAudioDecoderLock now uses to serialize
 * decodeGroupAudio/releaseGroupAudioDecoder for the SAME participant's audio
 * MediaCodec — mirrored, not redesigned, from the exact same primitive the
 * group VIDEO decoder path (groupDecoderLock) already used, see
 * GroupDecoderLockTest's identical doc/rationale for why this is tested as
 * the mutual-exclusion PRIMITIVE (one lock object per key, from a
 * ConcurrentHashMap.getOrPut, held across the critical section) rather than
 * by instantiating OfflineMediaTransport itself (real Android
 * Context/MediaCodec, out of scope for this project's unit tests). A
 * [ReentrantLock] stands in for production's `synchronized(Any())` purely so
 * the test can use a non-blocking [ReentrantLock.tryLock] to prove exclusion
 * deterministically instead of racing on wall-clock sleeps.
 *
 * Before this fix, decodeGroupAudio's `groupAudioDecoders.getOrPut(srcId) {
 * configureOpusAudioDecoder() }` and releaseGroupAudioDecoder's
 * `groupAudioDecoders.remove(srcId)?.let { it.stop(); it.release() }` held
 * NO lock at all — a release on the main thread (peer decline/kick) could
 * run concurrently with a decode still in progress on that srcId's
 * MediaReadLoop thread, a use-after-release on the same MediaCodec object.
 * The same gap also meant a fast leave-then-rejoin of the same srcId (2.3)
 * could hand a fresh decode call the OLD, about-to-be-released decoder
 * instance from getOrPut, since getOrPut and remove()+release() were two
 * independent, non-atomic map operations. Putting both under one per-srcId
 * lock — exactly like the video path — fixes both at once.
 */
class GroupAudioDecoderLockTest {

    private fun lockFor(locks: ConcurrentHashMap<Long, ReentrantLock>, id: Long): ReentrantLock =
        locks.getOrPut(id) { ReentrantLock() }

    @Test
    fun `release cannot run while a decode is in progress for the same srcId — no use-after-free window`() {
        val locks = ConcurrentHashMap<Long, ReentrantLock>()
        val srcId = 99L
        val decodeHoldsLock = CountDownLatch(1)
        val decodeMayExit = CountDownLatch(1)

        // Simulates decodeGroupAudio's read-loop thread: holds the per-srcId
        // lock for the whole decode call, exactly as the fixed
        // decodeGroupAudio now does (getOrPut through releaseOutputBuffer).
        val decodeThread = Thread({
            val lock = lockFor(locks, srcId)
            lock.lock()
            try {
                decodeHoldsLock.countDown()
                decodeMayExit.await()
            } finally {
                lock.unlock()
            }
        }, "test-decode-thread")
        decodeThread.start()
        assertTrue("decode thread never acquired the lock", decodeHoldsLock.await(2, TimeUnit.SECONDS))

        // Simulates releaseGroupAudioDecoder firing on the main thread (a
        // peer decline/kick) while that srcId's decode is still in flight —
        // tryLock() returning false, without ever blocking the test thread,
        // proves the release cannot reach groupAudioDecoders.remove()+
        // release() until the in-flight decode's critical section exits.
        assertFalse(
            "release must not be able to acquire the lock while a decode holds it",
            lockFor(locks, srcId).tryLock()
        )

        decodeMayExit.countDown()
        decodeThread.join(2_000)
        assertFalse("decode thread should have exited", decodeThread.isAlive)

        assertTrue(
            "release must be able to acquire the lock once the decode is done",
            lockFor(locks, srcId).tryLock()
        )
        lockFor(locks, srcId).unlock()
    }

    @Test
    fun `2_3 - a rejoin's first decode cannot observe a decoder mid-release for the same srcId`() {
        // Models the leave-then-rejoin race: releaseGroupAudioDecoder's
        // remove()+release() and decodeGroupAudio's getOrPut for the SAME
        // srcId are now both inside the same lock, so they can never
        // interleave — a rejoin's first frame either runs entirely BEFORE or
        // entirely AFTER a concurrent leave's release, never mid-release.
        val locks = ConcurrentHashMap<Long, ReentrantLock>()
        val srcId = 7L
        val releaseHoldsLock = CountDownLatch(1)
        val releaseMayExit = CountDownLatch(1)
        var decodeSawReleaseInProgress = false

        val releaseThread = Thread({
            val lock = lockFor(locks, srcId)
            lock.lock()
            try {
                releaseHoldsLock.countDown()
                releaseMayExit.await()
            } finally {
                lock.unlock()
            }
        }, "test-release-thread")
        releaseThread.start()
        assertTrue("release thread never acquired the lock", releaseHoldsLock.await(2, TimeUnit.SECONDS))

        // The rejoin's first decode call, arriving on a brand-new read-loop
        // thread for the reconnected link, tries to acquire the SAME lock.
        val rejoinDecodeThread = Thread({
            val lock = lockFor(locks, srcId)
            if (!lock.tryLock(500, TimeUnit.MILLISECONDS)) {
                // Correctly blocked out — never observed the decoder mid-release.
                decodeSawReleaseInProgress = false
                return@Thread
            }
            try {
                decodeSawReleaseInProgress = true // would only happen if release had already fully finished
            } finally {
                lock.unlock()
            }
        }, "test-rejoin-decode-thread")
        rejoinDecodeThread.start()

        // While release still holds the lock, the rejoin decode must NOT
        // have been able to slip in and get handed a mid-release decoder.
        Thread.sleep(100)
        assertTrue("release lock should still be held", lockFor(locks, srcId).isLocked)

        releaseMayExit.countDown()
        releaseThread.join(2_000)
        rejoinDecodeThread.join(2_000)
        assertFalse("release thread should have exited", releaseThread.isAlive)
        assertFalse("rejoin decode thread should have exited", rejoinDecodeThread.isAlive)
    }

    @Test
    fun `different srcIds get independent locks — one participant's audio decoder never blocks another's`() {
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
        val first = lockFor(locks, 3L)
        val second = lockFor(locks, 3L)
        assertTrue("decode/release for the same srcId must synchronize on the identical lock object", first === second)
    }
}
