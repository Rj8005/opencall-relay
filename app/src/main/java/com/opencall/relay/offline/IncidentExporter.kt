package com.opencall.relay.offline

import android.content.Context

/**
 * B1 (diagnostic follow-up), part 2 of 2: builds a human-readable incident
 * report from the two data sources this app already durably persists —
 * IncidentLog's SOS event history (part 1) and MeshLedger's position
 * tracks. Plain text, not JSON/PDF: this app has no PDF library, and
 * plain text is directly shareable via the standard Android share sheet
 * (email, Save to Drive/Files, copy) with no new storage permission or
 * file-picker plumbing needed — see the caller (OfflineCallActivity's
 * SOS section) for how it's actually surfaced.
 *
 * SCOPE: reads existing data only. Does not add any new capture — see
 * IncidentLog's own doc for exactly what timeline data exists (raised/
 * cleared/acked, timestamped) and MeshLedger's for what position data
 * exists (per-node track history + last-known lost-contact state).
 * "Who-heard-whom" is covered by the ACKED entries in the timeline (each
 * one names the acking node) — this app has no broader path-tracing
 * concept to report beyond that (see MeshCarrier's class doc: this mesh
 * is a star topology, one hop to the GO, so "who relayed for whom"
 * isn't a thing distinct from "who acked").
 */
object IncidentExporter {

    private fun timestamp(atMs: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date(atMs))

    private fun eventLine(e: IncidentLog.Event): String {
        val who = if (e.extra["self"] == true) "this device" else "node ${e.nodeIdHex}"
        val detail = when (e.type) {
            IncidentLog.EventType.RAISED -> {
                val msg = (e.extra["message"] as? String)?.takeIf { it.isNotBlank() }
                val hasFix = e.extra["hasFix"] as? Boolean
                buildString {
                    append("SOS RAISED by $who")
                    if (hasFix == true) append(" (has GPS fix)")
                    if (hasFix == false) append(" (no GPS fix)")
                    if (msg != null) append(" — \"$msg\"")
                }
            }
            IncidentLog.EventType.CLEARED -> "SOS CLEARED by $who"
            IncidentLog.EventType.ACKED -> {
                val seenBy = e.extra["seenBy"]
                val total = e.extra["total"]
                "Acknowledged by $who (seen by $seenBy/$total)"
            }
        }
        return "${timestamp(e.atMs)}  $detail"
    }

    /** Builds the full report as one plain-text string. */
    fun buildReport(context: Context): String {
        val appContext = context.applicationContext
        val events = IncidentLog.get(appContext).readAll().sortedBy { it.atMs }
        val ledger = MeshLedger.get(appContext)

        val sb = StringBuilder()
        sb.appendLine("OpenCall Relay — Incident Report")
        sb.appendLine("Generated: ${timestamp(System.currentTimeMillis())}")
        sb.appendLine()

        sb.appendLine("== SOS Event Timeline ==")
        if (events.isEmpty()) {
            sb.appendLine("(no SOS events recorded on this device)")
        } else {
            events.forEach { sb.appendLine(eventLine(it)) }
        }
        sb.appendLine()

        sb.appendLine("== Known Positions (last seen) ==")
        val nodeIds = ledger.knownNodeIds().sortedBy { MeshFrame.hex(it) }
        if (nodeIds.isEmpty()) {
            sb.appendLine("(no position data recorded on this device)")
        } else {
            nodeIds.forEach { id ->
                sb.appendLine("node ${MeshFrame.hex(id)}")
                val latest = ledger.latestEntry(id)
                if (latest != null) {
                    val acc = latest.accuracyMeters?.let { "±${it}m" } ?: "accuracy unknown"
                    sb.appendLine("  last position: ${latest.latitude}, ${latest.longitude} ($acc) at ${timestamp(latest.receivedAtMs)}")
                } else {
                    sb.appendLine("  no position fix recorded — presence known from other traffic only")
                }
                ledger.lostContactFor(id)?.let { lc ->
                    sb.appendLine("  LOST CONTACT at ${timestamp(lc.lostAtMs)} — last trend: ${lc.trend}")
                }
            }
        }

        return sb.toString()
    }
}
