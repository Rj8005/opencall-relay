package com.opencall.relay.offline

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * STABILITY AUDIT 3.1a: pure-JVM proof of the "log at ERROR and keep looping"
 * pattern now applied to LocalSignaling.startKeepalive's keepaliveThread.
 * LocalSignaling itself is never instantiated here — its constructor builds
 * a `Handler(Looper.getMainLooper())`, a real Android main-thread Looper this
 * project's plain-JVM test setup (no Robolectric) cannot provide, the same
 * constraint noted throughout this test suite (MeshSosManagerTest,
 * OfflineMediaTransportTest) — so, mirroring GroupDecoderLockTest/
 * GroupAudioDecoderLockTest/ThermalModerateRaceTest, this proves the LOOP
 * PATTERN itself: a try/catch around the WHOLE loop body that logs and
 * continues on a caught Exception, rather than the old shape where only
 * Thread.sleep's InterruptedException was caught and anything else thrown
 * anywhere else in the body (ping build, degraded/recovered transitions,
 * reportPeerGone) propagated uncaught and silently killed the thread — this
 * is the actual dead-link watchdog for the whole WFD group connection, so a
 * silent death of it means the UI keeps showing "connected" for a link that
 * has no detection left at all.
 */
class LocalSignalingKeepaliveTest {

    /** Mirrors startKeepalive's fixed loop shape exactly: a per-iteration
     *  try/catch, InterruptedException still exits the loop (the intentional
     *  stop signal on close()), any OTHER exception is logged and the loop
     *  continues to the next iteration instead of the thread dying. */
    private fun runKeepaliveLikeLoop(
        running: () -> Boolean,
        iterationCount: AtomicInteger,
        exceptionsCaught: AtomicInteger,
        body: (iteration: Int) -> Unit
    ) {
        var i = 0
        while (running()) {
            try {
                body(i)
                iterationCount.incrementAndGet()
            } catch (_: InterruptedException) {
                return
            } catch (e: Exception) {
                exceptionsCaught.incrementAndGet()
            }
            i++
        }
    }

    @Test
    fun `a thrown exception on one iteration does not stop the loop — later iterations still run`() {
        val totalIterations = 20
        val faultyIteration = 5
        val iterationCount = AtomicInteger(0)
        val exceptionsCaught = AtomicInteger(0)
        val reachedFinalIteration = CountDownLatch(1)

        var i = 0
        runKeepaliveLikeLoop(
            running = { i < totalIterations },
            iterationCount = iterationCount,
            exceptionsCaught = exceptionsCaught
        ) { iteration ->
            i = iteration + 1
            if (iteration == faultyIteration) {
                throw RuntimeException("simulated exception, e.g. a bad ping JSON build")
            }
            if (iteration == totalIterations - 1) {
                reachedFinalIteration.countDown()
            }
        }

        assertTrue("the loop should have reached its final iteration despite the fault", reachedFinalIteration.count == 0L)
        assertTrue("exactly one exception should have been caught", exceptionsCaught.get() == 1)
        // Every iteration except the one that threw completed normally.
        assertTrue(
            "expected ${totalIterations - 1} successful iterations, got ${iterationCount.get()}",
            iterationCount.get() == totalIterations - 1
        )
    }

    @Test
    fun `a thread running this loop pattern survives a thrown exception and keeps looping`() {
        val totalIterations = 30
        val faultyIterations = setOf(3, 10, 21)
        val iterationCount = AtomicInteger(0)
        val exceptionsCaught = AtomicInteger(0)
        val done = CountDownLatch(1)

        val thread = Thread({
            var i = 0
            runKeepaliveLikeLoop(
                running = { i < totalIterations },
                iterationCount = iterationCount,
                exceptionsCaught = exceptionsCaught
            ) { iteration ->
                i = iteration + 1
                if (iteration in faultyIterations) throw IllegalStateException("simulated fault at $iteration")
            }
            done.countDown()
        }, "test-keepalive-like-thread")
        thread.start()

        assertTrue("thread should complete all iterations, not die on the first fault", done.await(2, TimeUnit.SECONDS))
        thread.join(2_000)
        assertTrue("thread must have exited normally, not via an uncaught exception", !thread.isAlive)
        assertTrue("expected ${faultyIterations.size} caught exceptions", exceptionsCaught.get() == faultyIterations.size)
        assertTrue(
            "expected ${totalIterations - faultyIterations.size} successful iterations",
            iterationCount.get() == totalIterations - faultyIterations.size
        )
    }

    @Test
    fun `InterruptedException still exits the loop — the intentional stop-on-close signal is preserved`() {
        val iterationCount = AtomicInteger(0)
        val exceptionsCaught = AtomicInteger(0)
        var loopExited = false

        runKeepaliveLikeLoop(
            running = { true }, // would spin forever if InterruptedException didn't exit it
            iterationCount = iterationCount,
            exceptionsCaught = exceptionsCaught
        ) { iteration ->
            if (iteration == 2) throw InterruptedException("simulated close()/interrupt")
        }
        loopExited = true

        assertTrue("loop must exit on InterruptedException, not treat it as a recoverable fault", loopExited)
        assertTrue("InterruptedException must not be counted as a caught/logged exception", exceptionsCaught.get() == 0)
        assertTrue("iterations 0 and 1 should have completed before the interrupt at 2", iterationCount.get() == 2)
    }
}
