package com.opencall.relay.offline

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * B1 (diagnostic follow-up), part 1 of 2 (durable log only — no exporter
 * here, see the diagnostic report's B1 finding): append-only history of
 * SOS state changes (raised/cleared/acked), durable across a restart —
 * unlike MeshSosManager.sosEntries, which overwrites the latest state
 * per-sender in memory only and is lost on process death. This is the
 * data an incident report needs and sosEntries alone cannot provide:
 * WHEN each event happened, not just the current state.
 *
 * FORMAT: JSONL (one JSON object per line) under
 * filesDir/incidents/sos_events.jsonl — append-only, never rewritten in
 * place, so a crash mid-write can corrupt at most the LAST line (a future
 * reader should tolerate a truncated/malformed final line, not fail the
 * whole file). Chosen over MeshLedger/MeshCarrier's atomic-replace
 * (temp+fsync+rename) pattern deliberately: this is a growing history,
 * not a small mutable-in-place record — atomic-replacing the whole file
 * on every single SOS heartbeat/ack would mean rewriting an ever-growing
 * file every 30s an SOS stays active.
 *
 * Process-wide singleton (same [get] pattern as MeshLedger/MeshBarometer/
 * MeshCarrier) purely so MeshSosManager can depend on an already-Context-
 * bound instance rather than taking a raw Context itself — see that
 * class's own doc for why it's kept decoupled from Android internals.
 */
class IncidentLog private constructor(context: Context) {

    enum class EventType { RAISED, CLEARED, ACKED }

    companion object {
        private const val INCIDENTS_DIR_NAME = "incidents"
        private const val LOG_FILE_NAME = "sos_events.jsonl"

        @Volatile private var instance: IncidentLog? = null

        fun get(context: Context): IncidentLog =
            instance ?: synchronized(this) {
                instance ?: IncidentLog(context.applicationContext).also { instance = it }
            }
    }

    private val appContext = context.applicationContext
    private val writeLock = Any()
    private val logFile: File by lazy {
        val dir = File(appContext.filesDir, INCIDENTS_DIR_NAME).apply { mkdirs() }
        File(dir, LOG_FILE_NAME)
    }

    /** Appends one event line. Never throws — a failed write is logged and
     *  dropped, the same "must not risk the caller" posture as every other
     *  best-effort diagnostic write in this app — an SOS must never fail
     *  to actually fire because its OWN history log had a bad moment. */
    fun append(type: EventType, srcId: Long, extra: Map<String, Any?> = emptyMap()) {
        val json = JSONObject().apply {
            put("atMs", System.currentTimeMillis())
            put("type", type.name)
            put("nodeId", MeshFrame.hex(srcId))
            extra.forEach { (k, v) -> put(k, v) }
        }
        synchronized(writeLock) {
            try {
                FileOutputStream(logFile, /* append = */ true).use { fos ->
                    fos.write((json.toString() + "\n").toByteArray(Charsets.UTF_8))
                    fos.fd.sync()
                }
            } catch (e: Exception) {
                Log.w("OFFTRACE", "INCIDENT: log append failed: ${e.javaClass.simpleName}:${e.message}")
            }
        }
    }
}
