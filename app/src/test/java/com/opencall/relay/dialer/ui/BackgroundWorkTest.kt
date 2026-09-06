package com.opencall.relay.dialer.ui

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PART 1.4 test for the coroutine crash guard: [runOffMainThread] can't be
 * driven directly in a JVM unit test (it needs a real AppCompatActivity /
 * lifecycleScope — this project has no Robolectric, see
 * CallLogRepositoryTest's own doc), so this exercises the EXACT mechanism
 * [runOffMainThread] now relies on — a coroutine launched as a direct child
 * of a SupervisorJob, with a [CoroutineExceptionHandler] in its own launch
 * context — and asserts the two guarantees that mechanism is supposed to
 * provide: the throw is intercepted rather than propagating, and it does
 * NOT cancel the scope or any sibling coroutine (i.e. it can't take the rest
 * of the app down with it, which is what actually happened at
 * CallLogRepository.kt:56 before this fix).
 */
class BackgroundWorkTest {

    @Test
    fun `an exception in work() reaches the handler instead of escaping the coroutine`() {
        var caught: Throwable? = null
        val handler = CoroutineExceptionHandler { _, throwable -> caught = throwable }
        val scope = CoroutineScope(SupervisorJob())

        val job = scope.launch(Dispatchers.Unconfined + handler) {
            throw IllegalArgumentException("Invalid token LIMIT")
        }
        runBlocking { job.join() }

        assertTrue(caught is IllegalArgumentException)
    }

    @Test
    fun `a failing coroutine does not cancel its SupervisorJob scope or a sibling`() {
        val handler = CoroutineExceptionHandler { _, _ -> /* swallow, as backgroundWorkExceptionHandler does */ }
        val scope = CoroutineScope(SupervisorJob())
        var siblingRan = false

        val failing = scope.launch(Dispatchers.Unconfined + handler) {
            throw RuntimeException("boom")
        }
        val sibling = scope.launch(Dispatchers.Unconfined + handler) {
            siblingRan = true
        }
        runBlocking {
            failing.join()
            sibling.join()
        }

        assertTrue(siblingRan)
        assertTrue(scope.isActive)
    }
}
