package com.opencall.relay.offline

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * PHASE 5BC: the continuous position ledger — a rolling per-node track built from
 * every TYPE_POSITION (and TYPE_SOS) fix this device has ever received, so the
 * party can answer "where was the missing member, which way were they heading,
 * and how far above or below us" even after their phone never reaches anyone
 * again. This is what makes the system useful when the missing phone is dead,
 * buried, or permanently out of range — everything else in this phase is either
 * feeding this (location tiers, barometer, position broadcasts) or reading from
 * it (the siren's alert screen, the party-status screen, store-and-forward carry).
 *
 * PERSISTENCE: one JSON file per known node under context.filesDir/ledger/ (see
 * [ledgerDir]) — see the per-decision writeup this phase was built against: raw
 * files (org.json, already a dependency, no new one added), same pattern
 * [OfflineIdentity] already uses for its key file. Writes are ATOMIC (temp file +
 * fsync + rename — a battery pull mid-write must never leave a torn file behind)
 * and BATCHED (a periodic flush, not one disk write per received frame — at 20
 * peers on a 30s cadence that would be ~40 writes/minute on the actual survival
 * constraint, the phone's battery). Immediate flush is reserved for the moments
 * that actually matter: an SOS sent or received, a lost-contact event, or the
 * hosting screen pausing/stopping — those are exactly the moments the phone
 * might not survive to the next scheduled flush.
 *
 * RSSI TREND — NOTE FOR THE RECORD: Wi-Fi Direct exposes no per-link RSSI to
 * either a GO or a client on any Android API level (confirmed by survey before
 * this phase was built; WifiManager.getScanResults()/ScanResult.level was
 * considered and rejected — API 28+ throttles scans to 4 per 2 minutes, it only
 * works client->GO not GO->client, and outdoors GPS dominates anyway). [trendTo]
 * below is a GPS-derived replacement: CLOSER/FARTHER/STEADY from the haversine
 * distance between this device's own last fixes and the target's, not signal
 * strength. Do not re-investigate RSSI for this — it isn't reachable here.
 */
class MeshLedger private constructor(context: Context) {

    /** One received (or self-broadcast) position sample. */
    data class Entry(
        val latE7: Int,
        val lonE7: Int,
        val accuracyMeters: Int?,
        val altitudeBaroM: Int?,
        val pressureHpaX10: Int?,
        val headingDeg: Int?,
        val speedCms: Int?,
        val tier: Int,
        val receivedAtMs: Long
    ) {
        val latitude: Double get() = latE7 / 1e7
        val longitude: Double get() = lonE7 / 1e7
    }

    enum class Trend { CLOSER, FARTHER, STEADY, UNKNOWN }

    data class LostContact(
        val lastEntry: Entry,
        val lostAtMs: Long,
        val trend: Trend,
        /** Heading/speed computed from the last up-to-3 fixes BEFORE contact was
         *  lost — independent of [lastEntry]'s own headingDeg/speedCms (which is
         *  just whatever that one sender self-reported, if anything). */
        val computedHeadingDeg: Int?,
        val computedSpeedCms: Int?
    )

    /** Never a single point — a false-precision dot sends searchers to the wrong
     *  place. [minRangeM]/[maxRangeM] bound a plausible search radius from the
     *  last known position at 0.3-1.5 m/s (a lost climber's plausible pace range,
     *  including possibly stationary/injured) times elapsed time. */
    data class SearchCone(
        val nodeId: Long,
        val originLat: Double,
        val originLon: Double,
        val bearingDeg: Double?,
        val elapsedMs: Long,
        val minRangeM: Double,
        val maxRangeM: Double
    )

    /** PHASE 6 TRACK C: a peer heard over BLE but not currently reachable over
     *  Wi-Fi Direct — "NEARBY, NOT CONNECTED" in the UI, a genuinely new state
     *  (walk toward them and the mesh will re-form). Deliberately NOT persisted
     *  to disk — BLE presence is live/transient, unlike the position track it
     *  sits alongside; a stale presence reading surviving a restart would be
     *  actively misleading ("nearby" hours later, when they may be gone). */
    data class BlePresence(val rssiDbm: Int, val trend: Trend, val seenAtMs: Long)

    private class Track {
        val entries = ArrayDeque<Entry>(RING_CAPACITY)
        @Volatile var lostContact: LostContact? = null
        @Volatile var dirty: Boolean = false
        @Volatile var blePresence: BlePresence? = null
    }

    companion object {
        private const val RING_CAPACITY = 60 // 30 min at a 30s cadence
        private const val PRUNE_AGE_MS = 24 * 60 * 60 * 1000L
        private const val FLUSH_INTERVAL_MS = 60_000L
        // A lost climber's plausible pace range — stationary/injured up to a
        // brisk hike; the search cone spans this times elapsed time.
        private const val MIN_SPEED_MPS = 0.3
        private const val MAX_SPEED_MPS = 1.5
        private const val EARTH_RADIUS_M = 6_371_000.0
        private const val DEFAULT_ACCURACY_M = 30 // fallback when a fix's own accuracy is unknown
        private const val LEDGER_DIR_NAME = "ledger"

        @Volatile private var instance: MeshLedger? = null

        fun get(context: Context): MeshLedger =
            instance ?: synchronized(this) {
                instance ?: MeshLedger(context.applicationContext).also { instance = it }
            }
    }

    private val appContext = context.applicationContext
    private val ledgerDir = File(appContext.filesDir, LEDGER_DIR_NAME).apply { mkdirs() }
    private val tracks = ConcurrentHashMap<Long, Track>()

    private val ioThread = HandlerThread("MeshLedgerIO").apply { start() }
    private val ioHandler = Handler(ioThread.looper)
    @Volatile private var periodicFlushScheduled = false
    private val periodicFlush = object : Runnable {
        override fun run() {
            flushAllDirty()
            if (periodicFlushScheduled) ioHandler.postDelayed(this, FLUSH_INTERVAL_MS)
        }
    }

    init {
        ioHandler.post { loadAllFromDisk() }
    }

    // ── Session lifecycle ────────────────────────────────────────────────────

    /** Call when a group session becomes active — starts the periodic flush.
     *  Safe to call more than once. */
    fun startPeriodicFlush() {
        if (periodicFlushScheduled) return
        periodicFlushScheduled = true
        ioHandler.postDelayed(periodicFlush, FLUSH_INTERVAL_MS)
    }

    /** Call when the session ends — cancels the periodic flush and does one final
     *  flush of anything outstanding. */
    fun stopPeriodicFlushAndFlushNow() {
        periodicFlushScheduled = false
        ioHandler.removeCallbacks(periodicFlush)
        ioHandler.post { flushAllDirty() }
    }

    /** Call on the hosting screen's onPause/onStop — an immediate flush WITHOUT
     *  cancelling the periodic schedule, since the session itself keeps running
     *  (screen off is not session end — see OfflineCallService's class doc).
     *  Per the persistence decision this phase was built against: pause/stop is
     *  exactly the kind of moment that might not survive to the next scheduled
     *  flush. */
    fun flushNow() {
        ioHandler.post { flushAllDirty() }
    }

    // ── Recording ────────────────────────────────────────────────────────────

    fun record(nodeId: Long, entry: Entry) {
        val track = tracks.getOrPut(nodeId) { Track() }
        synchronized(track) {
            if (track.entries.size >= RING_CAPACITY) track.entries.removeFirst()
            track.entries.addLast(entry)
            // Fresh contact supersedes any stale "lost" marker.
            track.lostContact = null
            track.dirty = true
        }
    }

    /** Called when a peer disappears from the roster (see OfflineMediaTransport's
     *  roster-diff — there is no dedicated onPeerLost callback, see survey 1f).
     *  Freezes the track's last entry into a [LostContact] marker with a
     *  GPS-derived trend and heading/speed computed from the last up-to-3 fixes.
     *  No-op if this node has no recorded entries at all. Flushes immediately —
     *  this is exactly the kind of moment that must survive a crash. */
    fun markLostContact(nodeId: Long, myNodeId: Long) {
        val track = tracks[nodeId] ?: return
        val marker = synchronized(track) {
            if (track.entries.isEmpty()) return
            val (hdg, spd) = headingAndSpeedFromLastFixes(track.entries)
            val lc = LostContact(
                lastEntry = track.entries.last(),
                lostAtMs = System.currentTimeMillis(),
                trend = trendTo(nodeId, myNodeId),
                computedHeadingDeg = hdg,
                computedSpeedCms = spd
            )
            track.lostContact = lc
            track.dirty = true
            lc
        }
        Log.d(
            "OFFTRACE",
            "LEDGER: lost contact ${MeshFrame.hex(nodeId)} hdg=${marker.computedHeadingDeg} " +
                "spd=${marker.computedSpeedCms} rssiTrend=${marker.trend}"
        )
        flushOne(nodeId)
    }

    /** Clears a stale lost-contact marker once the peer is heard from again —
     *  called right before/alongside [record] resumes; kept as its own function
     *  so callers can log "LAST SEEN clears" distinctly from an ordinary update. */
    fun clearLostContact(nodeId: Long) {
        tracks[nodeId]?.let { t -> synchronized(t) { t.lostContact = null } }
    }

    fun lostContactFor(nodeId: Long): LostContact? = tracks[nodeId]?.lostContact

    /** BUG 1 FIX 5: true iff at least one previously-known node currently has an
     *  unresolved [LostContact] marker — i.e. this device WAS with someone and
     *  a real roster-diff lost-contact EVENT fired for them (see
     *  [markLostContact]'s call site in OfflineMediaTransport), not merely
     *  "the roster happens to be empty right now". Used to harden
     *  SosTriggers' no-motion "separated from party" gate — a momentary empty
     *  roster (e.g. the brief window before the first HELLO completes, or a
     *  device that was simply never paired with anyone) must not count as
     *  "separated"; an actual tracked loss must. */
    fun hasAnyLostContact(): Boolean = tracks.values.any { it.lostContact != null }

    fun latestEntry(nodeId: Long): Entry? =
        tracks[nodeId]?.let { t -> synchronized(t) { t.entries.lastOrNull() } }

    fun trackFor(nodeId: Long): List<Entry> =
        tracks[nodeId]?.let { t -> synchronized(t) { t.entries.toList() } } ?: emptyList()

    fun knownNodeIds(): Set<Long> = tracks.keys.toSet()

    /** PHASE 6 TRACK C: records a BLE sighting — an ESTIMATE, never presented as
     *  a fix (see MeshBleBeacon's class doc: never mistake a signal-strength
     *  guess for a GPS fix). */
    fun recordBlePresence(nodeId: Long, rssiDbm: Int, trend: Trend) {
        val track = tracks.getOrPut(nodeId) { Track() }
        track.blePresence = BlePresence(rssiDbm, trend, System.currentTimeMillis())
    }

    fun blePresenceFor(nodeId: Long): BlePresence? = tracks[nodeId]?.blePresence

    fun clearBlePresence(nodeId: Long) {
        tracks[nodeId]?.blePresence = null
    }

    /** Public wrapper around the same heading/speed derivation used internally
     *  for a lost-contact marker — used by [MeshSosManager] to fill in this
     *  device's OWN heading/speed on its outgoing TYPE_POSITION/TYPE_SOS
     *  broadcasts, computed from its own recent track rather than a
     *  magnetometer (unreliable near rock/metal — see the phase's UI doc). */
    fun computeHeadingAndSpeed(nodeId: Long): Pair<Int?, Int?> =
        headingAndSpeedFromLastFixes(trackFor(nodeId))

    // ── GPS-derived trend (replaces RSSI trend — see class doc) ────────────────

    /** CLOSER/FARTHER/STEADY over the last few fixes on both sides. STEADY
     *  whenever the delta is smaller than the combined accuracy of both fixes —
     *  never report a trend smaller than the error bars. UNKNOWN if either side
     *  has fewer than 2 fixes to compare. */
    fun trendTo(nodeId: Long, myNodeId: Long): Trend {
        val theirs = trackFor(nodeId)
        val mine = trackFor(myNodeId)
        if (theirs.size < 2 || mine.size < 2) return Trend.UNKNOWN
        val theirsWindow = theirs.takeLast(3)
        val mineWindow = mine.takeLast(3)
        val distNow = haversineMeters(
            mineWindow.last().latitude, mineWindow.last().longitude,
            theirsWindow.last().latitude, theirsWindow.last().longitude
        )
        val distEarlier = haversineMeters(
            mineWindow.first().latitude, mineWindow.first().longitude,
            theirsWindow.first().latitude, theirsWindow.first().longitude
        )
        val combinedAccuracy =
            (mineWindow.last().accuracyMeters ?: DEFAULT_ACCURACY_M) +
                (theirsWindow.last().accuracyMeters ?: DEFAULT_ACCURACY_M)
        val delta = distNow - distEarlier
        return when {
            abs(delta) <= combinedAccuracy -> Trend.STEADY
            delta > 0 -> Trend.FARTHER
            else -> Trend.CLOSER
        }
    }

    // ── Search cone ──────────────────────────────────────────────────────────

    /** Last known position, bearing of travel, elapsed time since that fix, and a
     *  plausible-distance RANGE (never a single point). Returns null if nothing
     *  has ever been recorded for this node. */
    fun searchCone(nodeId: Long): SearchCone? {
        val track = tracks[nodeId] ?: return null
        val (entries, lc) = synchronized(track) { track.entries.toList() to track.lostContact }
        val last = entries.lastOrNull() ?: return null
        val elapsedMs = System.currentTimeMillis() - last.receivedAtMs
        val bearing = (lc?.computedHeadingDeg ?: headingAndSpeedFromLastFixes(entries).first)?.toDouble()
        val elapsedSec = (elapsedMs / 1000.0).coerceAtLeast(0.0)
        val minRange = MIN_SPEED_MPS * elapsedSec
        val maxRange = MAX_SPEED_MPS * elapsedSec
        val cone = SearchCone(
            nodeId = nodeId,
            originLat = last.latitude,
            originLon = last.longitude,
            bearingDeg = bearing,
            elapsedMs = elapsedMs,
            minRangeM = minRange,
            maxRangeM = maxRange
        )
        Log.d(
            "OFFTRACE",
            "LEDGER: cone ${MeshFrame.hex(nodeId)} bearing=${bearing?.toInt()} " +
                "range=${minRange.toInt()}-${maxRange.toInt()}m"
        )
        return cone
    }

    // ── Heading/speed/haversine geometry ────────────────────────────────────────

    /** Heading (initial bearing, degrees 0-359) and speed (cm/s) computed from the
     *  oldest vs. newest of the last up-to-3 entries. Null/null if fewer than 2
     *  entries or a zero/negative time delta. */
    private fun headingAndSpeedFromLastFixes(entries: List<Entry>): Pair<Int?, Int?> {
        val window = entries.takeLast(3)
        if (window.size < 2) return null to null
        val a = window.first()
        val b = window.last()
        val dtSec = (b.receivedAtMs - a.receivedAtMs) / 1000.0
        if (dtSec <= 0.0) return null to null
        val distM = haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)
        val speedCms = ((distM / dtSec) * 100.0).toInt().coerceIn(0, 254)
        val heading = initialBearingDeg(a.latitude, a.longitude, b.latitude, b.longitude)
        return heading.toInt() to speedCms
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_M * c
    }

    private fun initialBearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        val bearing = Math.toDegrees(atan2(y, x))
        return (bearing + 360.0) % 360.0
    }

    // ── Persistence: atomic + batched, see class doc ────────────────────────────

    private fun flushOne(nodeId: Long) {
        ioHandler.post {
            val track = tracks[nodeId] ?: return@post
            writeTrackToDisk(nodeId, track)
        }
    }

    private fun flushAllDirty() {
        tracks.forEach { (nodeId, track) ->
            if (track.dirty) writeTrackToDisk(nodeId, track)
        }
    }

    /** Called on an SOS sent/received or a lost-contact event — those moments must
     *  survive a crash, unlike an ordinary POSITION update which can wait for the
     *  next periodic flush. */
    fun flushImmediately(nodeId: Long) = flushOne(nodeId)

    private fun writeTrackToDisk(nodeId: Long, track: Track) {
        val (entries, lc) = synchronized(track) {
            track.dirty = false
            track.entries.toList() to track.lostContact
        }
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject().apply {
                    put("lat", e.latE7)
                    put("lon", e.lonE7)
                    put("acc", e.accuracyMeters ?: JSONObject.NULL)
                    put("baro", e.altitudeBaroM ?: JSONObject.NULL)
                    put("phx10", e.pressureHpaX10 ?: JSONObject.NULL)
                    put("hdg", e.headingDeg ?: JSONObject.NULL)
                    put("spd", e.speedCms ?: JSONObject.NULL)
                    put("tier", e.tier)
                    put("t", e.receivedAtMs)
                }
            )
        }
        val root = JSONObject().apply {
            put("entries", arr)
            if (lc != null) {
                put(
                    "lost",
                    JSONObject().apply {
                        put("lostAt", lc.lostAtMs)
                        put("trend", lc.trend.name)
                        put("hdg", lc.computedHeadingDeg ?: JSONObject.NULL)
                        put("spd", lc.computedSpeedCms ?: JSONObject.NULL)
                    }
                )
            }
        }
        writeAtomic(File(ledgerDir, "${MeshFrame.hex(nodeId)}.json"), root.toString())
    }

    /** Temp file + fsync + rename — a battery pull or cold shutdown mid-write must
     *  leave the previous good file intact, never a truncated one. */
    private fun writeAtomic(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        try {
            FileOutputStream(tmp).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                Log.w("OFFTRACE", "LEDGER: atomic rename failed for ${target.name}")
            }
        } catch (e: Exception) {
            Log.w("OFFTRACE", "LEDGER: write failed for ${target.name}: ${e.message}")
            try { tmp.delete() } catch (_: Exception) {}
        }
    }

    private fun loadAllFromDisk() {
        val files = ledgerDir.listFiles { f -> f.name.endsWith(".json") } ?: return
        val now = System.currentTimeMillis()
        var loaded = 0
        files.forEach { file ->
            try {
                val nodeId = java.lang.Long.parseUnsignedLong(file.name.removeSuffix(".json"), 16)
                val root = JSONObject(file.readText(Charsets.UTF_8))
                val arr = root.optJSONArray("entries") ?: JSONArray()
                val track = Track()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val t = o.optLong("t")
                    if (now - t > PRUNE_AGE_MS) continue // prune-by-age runs on load
                    track.entries.addLast(
                        Entry(
                            latE7 = o.getInt("lat"),
                            lonE7 = o.getInt("lon"),
                            accuracyMeters = o.optIntOrNull("acc"),
                            altitudeBaroM = o.optIntOrNull("baro"),
                            pressureHpaX10 = o.optIntOrNull("phx10"),
                            headingDeg = o.optIntOrNull("hdg"),
                            speedCms = o.optIntOrNull("spd"),
                            tier = o.optInt("tier"),
                            receivedAtMs = t
                        )
                    )
                }
                while (track.entries.size > RING_CAPACITY) track.entries.removeFirst()
                root.optJSONObject("lost")?.let { lo ->
                    track.lostContact = LostContact(
                        lastEntry = track.entries.lastOrNull() ?: return@let,
                        lostAtMs = lo.optLong("lostAt"),
                        trend = try { Trend.valueOf(lo.optString("trend")) } catch (_: Exception) { Trend.UNKNOWN },
                        computedHeadingDeg = lo.optIntOrNull("hdg"),
                        computedSpeedCms = lo.optIntOrNull("spd")
                    )
                }
                tracks[nodeId] = track
                loaded++
            } catch (e: Exception) {
                Log.w("OFFTRACE", "LEDGER: discarding malformed file ${file.name}: ${e.message}")
            }
        }
        Log.d("OFFTRACE", "LEDGER: loaded $loaded track(s) from disk")
    }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (isNull(key) || !has(key)) null else optInt(key)
}
