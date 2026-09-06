package com.opencall.relay.dialer.data

import android.content.ContentUris
import android.content.Context
import android.provider.CallLog
import com.opencall.relay.dialer.role.DialerPermissions

/** One [CallLog.Calls] row, trimmed to what Part 3.2's screen shows. */
data class DialerCallLogEntry(
    val id: Long,
    val number: String,
    val cachedName: String?,
    /** One of [CallLog.Calls.INCOMING_TYPE]/[CallLog.Calls.OUTGOING_TYPE]/
     *  [CallLog.Calls.MISSED_TYPE]/[CallLog.Calls.REJECTED_TYPE]/etc. */
    val type: Int,
    val timestampMs: Long,
    val durationSeconds: Long,
    /** [CallLog.Calls.PHONE_ACCOUNT_ID] — which SIM this call used, if the
     *  platform recorded one; null on a single-SIM device or an older
     *  entry. */
    val simAccountId: String?
)

/**
 * PART 3.2: call log from [CallLog.Calls]. Same permission-gated,
 * never-throws contract as [ContactsRepository] — an ungranted READ_CALL_LOG
 * (or, for delete, WRITE_CALL_LOG) means "empty/no-op," never an exception
 * the UI has to catch.
 */
object CallLogRepository {

    fun isReadPermissionGranted(context: Context): Boolean =
        DialerPermissions.isGranted(context, DialerPermissions.READ_CALL_LOG)

    fun isWritePermissionGranted(context: Context): Boolean =
        DialerPermissions.isGranted(context, DialerPermissions.WRITE_CALL_LOG)

    /** Pure gate, extracted so "call-log queries handle a missing-permission
     *  state" is directly unit-testable without a real ContentResolver
     *  (this project has no Robolectric — see CallLogRepositoryTest). */
    fun shouldQuery(hasReadPermission: Boolean): Boolean = hasReadPermission

    fun queryRecent(context: Context, limit: Int = 300): List<DialerCallLogEntry> {
        if (!shouldQuery(isReadPermissionGranted(context))) return emptyList()
        val out = mutableListOf<DialerCallLogEntry>()
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.PHONE_ACCOUNT_ID
        )
        try {
            // FIX (crash 09-01 21:43:57.543): LIMIT embedded in sortOrder
            // ("$DATE DESC LIMIT $limit") throws IllegalArgumentException
            // ("Invalid token LIMIT") on Android 11+ — the platform's
            // ContentResolver now validates sortOrder as column-references
            // only. QUERY_ARG_LIMIT in the Bundle overload is the documented
            // replacement (added API 26, same as this module's minSdk) and
            // carries no such restriction.
            val queryArgs = android.os.Bundle().apply {
                putStringArray(
                    android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS,
                    arrayOf(CallLog.Calls.DATE)
                )
                putInt(
                    android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION,
                    android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
                )
                putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, limit)
            }
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, projection, queryArgs, null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
                val numberCol = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val nameCol = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                val typeCol = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val dateCol = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val durCol = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                val simCol = cursor.getColumnIndexOrThrow(CallLog.Calls.PHONE_ACCOUNT_ID)
                while (cursor.moveToNext()) {
                    out.add(
                        DialerCallLogEntry(
                            id = cursor.getLong(idCol),
                            number = cursor.getString(numberCol) ?: "",
                            cachedName = cursor.getString(nameCol),
                            type = cursor.getInt(typeCol),
                            timestampMs = cursor.getLong(dateCol),
                            durationSeconds = cursor.getLong(durCol),
                            simAccountId = cursor.getString(simCol)
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            return emptyList()
        }
        return out
    }

    /** PART 3.2: "grouped by contact" — since the raw log only ever carries
     *  a number (and a possibly-stale [DialerCallLogEntry.cachedName]),
     *  grouping key is the number itself; the UI resolves a live display
     *  name per group via [ContactsRepository.lookupNameForNumber]
     *  separately (this function stays a pure, Context-free grouping step —
     *  see CallLogRepositoryTest). Preserves each group's own most-recent-
     *  first order (the input is assumed already sorted by
     *  [queryRecent]'s own `DATE DESC`), and preserves GROUP order by each
     *  group's most recent entry. */
    fun groupByNumber(entries: List<DialerCallLogEntry>): List<List<DialerCallLogEntry>> {
        val groups = LinkedHashMap<String, MutableList<DialerCallLogEntry>>()
        entries.forEach { entry ->
            groups.getOrPut(normalizeForGrouping(entry.number)) { mutableListOf() }.add(entry)
        }
        return groups.values.toList()
    }

    /** Loose normalization for grouping only (never shown to the user,
     *  never used for dialing) — strips everything but digits and a leading
     *  '+' so "+1 415-555-0100" and "14155550100" fall into the same group. */
    private fun normalizeForGrouping(number: String): String =
        number.filter { it.isDigit() }.trimStart('0').ifEmpty { number }

    fun deleteEntry(context: Context, id: Long): Boolean {
        if (!isWritePermissionGranted(context)) return false
        return try {
            val uri = ContentUris.withAppendedId(CallLog.Calls.CONTENT_URI, id)
            context.contentResolver.delete(uri, null, null) > 0
        } catch (e: SecurityException) {
            false
        }
    }

    fun deleteAll(context: Context): Boolean {
        if (!isWritePermissionGranted(context)) return false
        return try {
            context.contentResolver.delete(CallLog.Calls.CONTENT_URI, null, null) >= 0
        } catch (e: SecurityException) {
            false
        }
    }
}
