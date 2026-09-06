package com.opencall.relay.dialer.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PART 1.4 regression test for the crash at CallLogRepository.kt:56
 * (IllegalArgumentException: "Invalid token LIMIT" — LIMIT embedded in
 * sortOrder, rejected by Android 11+'s ContentResolver). This needs a real
 * CallLogProvider round-trip (Bundle query args, actual cursor), so it's
 * instrumented rather than a JVM unit test — same reasoning as
 * AccountStoreInstrumentedTest's own doc.
 *
 * Grants READ_CALL_LOG via the instrumentation's UiAutomation (shell
 * `pm grant`) rather than pulling in androidx.test:rules' GrantPermissionRule,
 * since that's the only piece of that dependency this single test needs.
 */
@RunWith(AndroidJUnit4::class)
class CallLogRepositoryInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun queryRecentReturnsABoundedResultSetWithoutThrowing() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            context.packageName, android.Manifest.permission.READ_CALL_LOG
        )
        // The crash was an IllegalArgumentException thrown straight out of
        // ContentResolver.query — this call not throwing IS the regression
        // test; the size assertion just also confirms the limit still works
        // now that it travels via QUERY_ARG_LIMIT instead of sortOrder.
        val result = CallLogRepository.queryRecent(context, limit = 5)
        assertTrue(result.size <= 5)
    }
}
