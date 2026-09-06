package com.opencall.relay.dialer

import android.provider.CallLog
import com.opencall.relay.dialer.data.CallLogRepository
import com.opencall.relay.dialer.data.DialerCallLogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PART 3.2 test: "call-log queries handle an empty log and a
 *  missing-permission state." [CallLogRepository.shouldQuery] and
 *  [CallLogRepository.groupByNumber] are the pure halves of the repository
 *  (this project has no Robolectric, so the real ContentResolver-backed
 *  query itself isn't unit-testable — see CallLogRepository's own doc). */
class CallLogRepositoryTest {

    private fun entry(id: Long, number: String, type: Int = CallLog.Calls.OUTGOING_TYPE, dateMs: Long = id * 1000) =
        DialerCallLogEntry(id, number, null, type, dateMs, 30, null)

    @Test
    fun `shouldQuery is false without permission — the missing-permission state`() {
        assertFalse(CallLogRepository.shouldQuery(hasReadPermission = false))
    }

    @Test
    fun `shouldQuery is true with permission`() {
        assertTrue(CallLogRepository.shouldQuery(hasReadPermission = true))
    }

    @Test
    fun `groupByNumber on an empty log returns an empty list`() {
        assertTrue(CallLogRepository.groupByNumber(emptyList()).isEmpty())
    }

    @Test
    fun `groupByNumber groups repeated calls to the same number together`() {
        val entries = listOf(
            entry(3, "+14155550100"),
            entry(2, "+14155550199"),
            entry(1, "+14155550100")
        )
        val groups = CallLogRepository.groupByNumber(entries)
        assertEquals(2, groups.size)
        assertEquals(2, groups.first { it.first().number == "+14155550100" }.size)
        assertEquals(1, groups.first { it.first().number == "+14155550199" }.size)
    }

    @Test
    fun `groupByNumber treats formatting differences as the same number`() {
        val entries = listOf(entry(1, "+1 (415) 555-0100"), entry(2, "14155550100"))
        val groups = CallLogRepository.groupByNumber(entries)
        assertEquals(1, groups.size)
        assertEquals(2, groups.single().size)
    }

    @Test
    fun `a single entry is its own group`() {
        val groups = CallLogRepository.groupByNumber(listOf(entry(1, "+14155550100")))
        assertEquals(1, groups.size)
        assertEquals(1, groups.single().size)
    }
}
