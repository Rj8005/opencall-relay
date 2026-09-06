package com.opencall.relay.dialer.ui

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PART 0: the one place every ContactsContract/CallLog query and every
 * contact-photo decode in this pillar goes through — [work] runs on
 * [Dispatchers.Default] (off the main thread, where a ContentResolver
 * query or [android.graphics.BitmapFactory] decode belongs), [onResult]
 * runs back on the main thread, but ONLY if the Activity is still alive:
 * `lifecycleScope` itself is cancelled once this Activity's Lifecycle
 * reaches DESTROYED (so a slow query started just before rotation/finish
 * never resumes into a torn-down Activity), and the explicit
 * `isFinishing`/`isDestroyed` check below additionally covers the narrower
 * window where the Activity is finishing but hasn't reached DESTROYED yet
 * — the crash class this whole file exists to close off.
 *
 * PART 1.3: this app's other crash guard (".guarded()") wraps plain
 * Threads' run() bodies in a try/catch — it has no effect here, because
 * [work] never runs on a guarded Thread, it runs on a coroutine dispatched
 * onto [Dispatchers.Default]'s worker pool (see the crash at
 * CallLogRepository.kt:56: "FATAL EXCEPTION: DefaultDispatcher-worker-1",
 * which went straight past every ".guarded()" site to the process' default
 * uncaught-exception handler). lifecycleScope's own Job is a SupervisorJob,
 * so each `launch` call here is a root coroutine for exception-handling
 * purposes: an exception [work] doesn't catch itself is NOT propagated to
 * sibling coroutines or rethrown into the scope, it goes straight to
 * whichever [CoroutineExceptionHandler] is in the coroutine's OWN context —
 * installing one below is what actually intercepts it instead of falling
 * through to the default handler that kills the process.
 */
private val backgroundWorkExceptionHandler = CoroutineExceptionHandler { _, throwable ->
    Log.e("OFFTRACE", "BGWORK: uncaught exception in runOffMainThread work()", throwable)
}

fun <T> AppCompatActivity.runOffMainThread(work: () -> T, onResult: (T) -> Unit) {
    val activity = this
    val handler = CoroutineExceptionHandler { context, throwable ->
        backgroundWorkExceptionHandler.handleException(context, throwable)
        Handler(Looper.getMainLooper()).post {
            if (!activity.isFinishing && !activity.isDestroyed) {
                Toast.makeText(activity, "Something went wrong loading this — try again", Toast.LENGTH_SHORT).show()
            }
        }
    }
    lifecycleScope.launch(Dispatchers.Default + handler) {
        val result = work()
        withContext(Dispatchers.Main) {
            if (!isFinishing && !isDestroyed) {
                onResult(result)
            }
        }
    }
}
