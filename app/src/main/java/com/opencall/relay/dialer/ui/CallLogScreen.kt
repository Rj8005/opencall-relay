package com.opencall.relay.dialer.ui

import android.provider.CallLog
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.opencall.relay.R
import com.opencall.relay.dialer.data.CallLogRepository
import com.opencall.relay.dialer.data.ContactsRepository
import com.opencall.relay.dialer.data.DialerCallLogEntry

/**
 * PART 3.2: the call-log tab's content. A plain ScrollView+LinearLayout list
 * (like OfflineCallActivity's own roster/peer lists — see e.g.
 * buildCallsPeerRow) rather than a RecyclerView: this app has no
 * RecyclerView dependency today, and a manually-built list matches the
 * existing codebase's own pattern rather than introducing a new one for
 * what is, in practice, at most a few hundred rows (see
 * [CallLogRepository.queryRecent]'s own query LIMIT).
 *
 * PART 0: [CallLogRepository.queryRecent] AND, previously, one
 * [ContactsRepository.lookupNameForNumber] ContentResolver query PER GROUP
 * ROW (up to ~300 of them) all used to run synchronously on the main
 * thread on every open/refresh — the single worst main-thread I/O offender
 * in this pillar. Both now happen together in one [runOffMainThread] pass;
 * the main thread only ever builds the already-resolved rows.
 */
object CallLogScreen {

    private data class GroupWithName(val group: List<DialerCallLogEntry>, val displayName: String)

    fun build(activity: AppCompatActivity, onCall: (String) -> Unit): View {
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(activity).apply { addView(body) }
        refresh(activity, body, onCall)
        return scroll
    }

    private fun refresh(activity: AppCompatActivity, body: LinearLayout, onCall: (String) -> Unit) {
        activity.runOffMainThread(
            work = {
                val entries = CallLogRepository.queryRecent(activity)
                CallLogRepository.groupByNumber(entries).map { group ->
                    val latest = group.first()
                    val name = ContactsRepository.lookupNameForNumber(activity, latest.number)
                        ?: latest.cachedName?.takeIf { it.isNotBlank() }
                        ?: latest.number.ifBlank { "Unknown" }
                    GroupWithName(group, name)
                }
            },
            onResult = { groups ->
                body.removeAllViews()
                if (groups.isEmpty()) {
                    body.addView(emptyState(activity))
                    return@runOffMainThread
                }
                body.addView(clearAllRow(activity) { refresh(activity, body, onCall) })
                groups.forEach { body.addView(groupRow(activity, it, onCall) { refresh(activity, body, onCall) }) }
            }
        )
    }

    private fun emptyState(activity: AppCompatActivity): View {
        val density = activity.resources.displayMetrics.density
        return TextView(activity).apply {
            text = "No calls yet"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(activity, R.color.text_muted))
            setPadding(0, (48 * density).toInt(), 0, 0)
        }
    }

    private fun clearAllRow(activity: AppCompatActivity, onCleared: () -> Unit): View {
        val density = activity.resources.displayMetrics.density
        return TextView(activity).apply {
            text = "Clear all"
            textSize = 13f
            gravity = Gravity.END
            setTextColor(ContextCompat.getColor(activity, R.color.accent_blue))
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (4 * density).toInt())
            setOnClickListener {
                AlertDialog.Builder(activity)
                    .setTitle("Clear call log")
                    .setMessage("Delete every call log entry? This cannot be undone.")
                    .setPositiveButton("Delete all") { _, _ ->
                        activity.runOffMainThread(
                            work = { CallLogRepository.deleteAll(activity) },
                            onResult = { onCleared() }
                        )
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun typeGlyph(type: Int): Pair<String, Int> = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "↙" to R.color.text_secondary
        CallLog.Calls.OUTGOING_TYPE -> "↗" to R.color.accent_green
        CallLog.Calls.MISSED_TYPE -> "✖" to R.color.accent_blue
        CallLog.Calls.REJECTED_TYPE -> "⊘" to R.color.text_muted
        CallLog.Calls.BLOCKED_TYPE -> "⛔" to R.color.text_muted
        else -> "•" to R.color.text_muted
    }

    private fun groupRow(
        activity: AppCompatActivity,
        groupWithName: GroupWithName,
        onCall: (String) -> Unit,
        onChanged: () -> Unit
    ): View {
        val density = activity.resources.displayMetrics.density
        val group = groupWithName.group
        val latest = group.first()
        val name = groupWithName.displayName
        val (glyph, glyphColor) = typeGlyph(latest.type)

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (60 * density).toInt()
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
        }
        row.addView(TextView(activity).apply {
            text = glyph
            textSize = 18f
            setTextColor(ContextCompat.getColor(activity, glyphColor))
            setPadding(0, 0, (16 * density).toInt(), 0)
        })
        val textColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textColumn.addView(TextView(activity).apply {
            text = if (group.size > 1) "$name (${group.size})" else name
            textSize = 16f
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        })
        textColumn.addView(TextView(activity).apply {
            val timeLabel = DateUtils.getRelativeTimeSpanString(
                latest.timestampMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            )
            val durationLabel = if (latest.durationSeconds > 0) " · ${formatDuration(latest.durationSeconds)}" else ""
            val simLabel = latest.simAccountId?.let { " · $it" } ?: ""
            text = "$timeLabel$durationLabel$simLabel"
            textSize = 13f
            setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
        })
        row.addView(textColumn)
        row.setOnClickListener { onCall(latest.number) }
        row.setOnLongClickListener { showDetails(activity, group, onChanged); true }
        return row
    }

    private fun formatDuration(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

    /** Long-press: per-entry detail with per-entry AND whole-group delete —
     *  Part 3.2's "delete single or all." */
    private fun showDetails(activity: AppCompatActivity, group: List<DialerCallLogEntry>, onChanged: () -> Unit) {
        val lines = group.joinToString("\n") { entry ->
            val (glyph, _) = typeGlyph(entry.type)
            val time = DateUtils.formatDateTime(
                activity, entry.timestampMs,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME
            )
            "$glyph $time — ${formatDuration(entry.durationSeconds)}"
        }
        AlertDialog.Builder(activity)
            .setTitle(group.first().number)
            .setMessage(lines)
            .setPositiveButton("Delete this group") { _, _ ->
                activity.runOffMainThread(
                    work = { group.forEach { CallLogRepository.deleteEntry(activity, it.id) } },
                    onResult = { onChanged() }
                )
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
