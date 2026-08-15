package com.opencall.relay.offline

import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * PHASE 5BC: layered location — GPS_PROVIDER (live/stale) plus PASSIVE_PROVIDER as
 * a zero-cost fallback that harvests fixes any other app on the phone obtains, so
 * this device can still show *something* when its own GPS hasn't locked yet
 * (indoors, pocket, cold start). Still deliberately GPS_PROVIDER + PASSIVE_PROVIDER
 * only — not NETWORK_PROVIDER (needs internet) and not FusedLocationProviderClient
 * (needs Play Services); SOS/Find must work in airplane mode with no network of any
 * kind.
 *
 * WARM START — the actual fix for PHASE 5A's "always no GPS fix on SOS press" bug:
 * [start]/[stop] are now called by the mesh session's own lifecycle
 * (OfflineMediaTransport.start()/stop()), not by SOS press — GPS gets minutes of
 * lead time to lock before anyone ever needs a fix, instead of being cold-started
 * at the exact moment [getBestFix] is first read. [burst] is a separate, additive
 * SOS-press boost: temporarily raises the update rate for 60s to try to catch a
 * tier upgrade, then settles back to the normal cadence. It does NOT itself start
 * GPS from cold — [start] is expected to already be running for the whole session.
 *
 * Single shared instance per process (see [get]) with ref-counted start/stop, since
 * more than one caller could plausibly need updates running at once without
 * fighting over provider registration or stopping it out from under each other.
 *
 * Four tiers, tried in order, tagged on every [Fix] so the UI can show provenance:
 *   GPS_LIVE   — a GPS_PROVIDER fix newer than 60s
 *   GPS_STALE  — a GPS_PROVIDER fix 60s–30min old
 *   PASSIVE    — a PASSIVE_PROVIDER fix, any age (no live GPS fix within 30min)
 *   NONE       — no coordinates at all
 * [getBestFix] NEVER fabricates coordinates — a genuine NONE returns null, and a
 * caller must treat null exactly like "nothing to show", the same hard contract
 * PHASE 5A's single-tier version already had.
 */
class OfflineLocationProvider private constructor(context: Context) {

    enum class Tier(val wireValue: Int) { NONE(0), GPS_LIVE(1), GPS_STALE(2), PASSIVE(3) }

    data class Fix(
        val latitude: Double,
        val longitude: Double,
        /** Null if the platform Location object never reported one. */
        val accuracyMeters: Float?,
        val altitudeMeters: Double?,
        /** Wall-clock time this fix was received, NOT a GPS-provided timestamp. */
        val fixTimeMs: Long,
        val tier: Tier
    )

    companion object {
        private const val NORMAL_UPDATE_INTERVAL_MS = 10_000L
        private const val BURST_UPDATE_INTERVAL_MS = 5_000L
        private const val BURST_DURATION_MS = 60_000L
        private const val MIN_UPDATE_DISTANCE_M = 0f
        private const val GPS_LIVE_MAX_AGE_MS = 60_000L
        private const val GPS_STALE_MAX_AGE_MS = 30 * 60_000L

        @Volatile private var instance: OfflineLocationProvider? = null

        fun get(context: Context): OfflineLocationProvider =
            instance ?: synchronized(this) {
                instance ?: OfflineLocationProvider(context.applicationContext).also { instance = it }
            }
    }

    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val lock = Any()
    private var refCount = 0
    private var gpsListener: LocationListener? = null
    private var passiveListener: LocationListener? = null
    private var burstRunnable: Runnable? = null

    @Volatile private var lastGpsFix: Fix? = null
    @Volatile private var lastPassiveFix: Fix? = null

    /** Session-scoped: call once when the mesh session becomes active, balanced by
     *  [stop] on session end. Ref-counted — safe to call from more than one caller
     *  without them fighting over provider registration. Reads
     *  getLastKnownLocation() from BOTH providers immediately, before waiting for
     *  any callback. */
    fun start() {
        synchronized(lock) {
            refCount++
            if (gpsListener != null || passiveListener != null) return // already running
            val mgr = locationManager ?: run {
                Log.w("OFFTRACE", "GPS: no fix — reason=no_location_manager")
                return
            }
            registerGps(mgr, NORMAL_UPDATE_INTERVAL_MS)
            registerPassive(mgr)
            Log.d("OFFTRACE", "GPS: warm start — session active")
        }
    }

    /** Balances a prior [start]; only actually tears down provider updates once
     *  every caller has stopped. Safe to call more times than [start] (clamped at
     *  0). Also cancels any in-flight [burst]. */
    fun stop() {
        synchronized(lock) {
            if (refCount > 0) refCount--
            if (refCount > 0) return
            val mgr = locationManager
            gpsListener?.let { l -> try { mgr?.removeUpdates(l) } catch (_: Exception) {} }
            passiveListener?.let { l -> try { mgr?.removeUpdates(l) } catch (_: Exception) {} }
            gpsListener = null
            passiveListener = null
            burstRunnable?.let { mainHandler.removeCallbacks(it) }
            burstRunnable = null
        }
    }

    /** SOS-press boost: re-registers GPS at 1-per-5s for 60s to try to upgrade the
     *  tier before anyone actually needs the fix, then settles back to the normal
     *  cadence — see class doc. No-op (logged) if [start] was never called; a
     *  caller in a burst-worthy moment (pressing SOS) is always already inside an
     *  active mesh session. */
    fun burst() {
        synchronized(lock) {
            val mgr = locationManager ?: return
            if (gpsListener == null) {
                Log.w("OFFTRACE", "GPS: burst requested but session not active — ignoring")
                return
            }
            burstRunnable?.let { mainHandler.removeCallbacks(it) }
            registerGps(mgr, BURST_UPDATE_INTERVAL_MS)
            Log.d("OFFTRACE", "GPS: burst mode 5s for 60s (SOS pressed)")
            val runnable = Runnable {
                synchronized(lock) {
                    if (gpsListener != null) {
                        registerGps(mgr, NORMAL_UPDATE_INTERVAL_MS)
                        Log.d("OFFTRACE", "GPS: burst mode ended — back to normal cadence")
                    }
                }
            }
            burstRunnable = runnable
            mainHandler.postDelayed(runnable, BURST_DURATION_MS)
        }
    }

    /** PHASE 6 TRACK B: overrides the normal 10s cadence — used by SosBeaconMode
     *  to drop GPS to 1/60s (or 1/5min after a long silence) once SOS has fired
     *  on this device and everything is being shed to stretch battery life.
     *  Cancels any in-flight [burst] (a burst re-arming at the old cadence right
     *  after this would defeat the point). No-op (logged) if [start] was never
     *  called. */
    fun setCadence(intervalMs: Long) {
        synchronized(lock) {
            val mgr = locationManager ?: return
            if (gpsListener == null) {
                Log.w("OFFTRACE", "GPS: setCadence requested but session not active — ignoring")
                return
            }
            burstRunnable?.let { mainHandler.removeCallbacks(it) }
            burstRunnable = null
            registerGps(mgr, intervalMs)
            Log.d("OFFTRACE", "GPS: cadence set to ${intervalMs / 1000}s")
        }
    }

    /** Restores the ordinary session cadence — beacon mode's manual exit path. */
    fun resumeNormalCadence() = setCadence(NORMAL_UPDATE_INTERVAL_MS)

    /** Caller holds [lock]. Re-registers GPS_PROVIDER at [intervalMs] — used for
     *  both the initial normal-cadence registration and a burst's faster one
     *  (re-requesting with a new interval is how LocationManager changes rate;
     *  there's no in-place update-interval call). Handles SecurityException,
     *  provider disabled, and GPS hardware absent, each with a distinct logged
     *  reason, per the class contract. */
    private fun registerGps(mgr: LocationManager, intervalMs: Long) {
        try {
            val hasGpsHardware = appContext.packageManager
                ?.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS) ?: true
            if (!hasGpsHardware) {
                Log.w("OFFTRACE", "GPS: no fix — reason=no_gps_hardware")
                return
            }
            if (!mgr.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                Log.w("OFFTRACE", "GPS: no fix — reason=gps_provider_disabled")
                return
            }
            gpsListener?.let { try { mgr.removeUpdates(it) } catch (_: Exception) {} }
            try {
                mgr.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { onGpsLocation(it) }
            } catch (_: SecurityException) {
                Log.w("OFFTRACE", "GPS: no fix — reason=security_exception:getLastKnownLocation")
            }
            val l = object : LocationListener {
                override fun onLocationChanged(location: Location) = onGpsLocation(location)
                @Deprecated("Deprecated in platform API, still required to override")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {
                    Log.w("OFFTRACE", "GPS: no fix — reason=provider_disabled_while_running")
                }
            }
            mgr.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, intervalMs, MIN_UPDATE_DISTANCE_M, l, Looper.getMainLooper()
            )
            gpsListener = l
        } catch (e: SecurityException) {
            Log.w("OFFTRACE", "GPS: no fix — reason=security_exception:${e.message}")
        } catch (e: Exception) {
            Log.w("OFFTRACE", "GPS: no fix — reason=start_failed:${e.javaClass.simpleName}:${e.message}")
        }
    }

    /** Caller holds [lock]. PASSIVE_PROVIDER costs no power of its own — it only
     *  receives fixes some other app on the device already requested — so it's
     *  registered at the platform's own delivery rate (no minTime/minDistance
     *  throttling here would change anything; we don't drive it). */
    private fun registerPassive(mgr: LocationManager) {
        try {
            try {
                mgr.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)?.let { onPassiveLocation(it) }
            } catch (_: SecurityException) {
                Log.w("OFFTRACE", "GPS: passive unavailable — reason=security_exception:getLastKnownLocation")
            }
            val l = object : LocationListener {
                override fun onLocationChanged(location: Location) = onPassiveLocation(location)
                @Deprecated("Deprecated in platform API, still required to override")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            mgr.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 0L, 0f, l, Looper.getMainLooper())
            passiveListener = l
        } catch (e: SecurityException) {
            Log.w("OFFTRACE", "GPS: passive unavailable — reason=security_exception:${e.message}")
        } catch (e: Exception) {
            Log.w("OFFTRACE", "GPS: passive unavailable — reason=${e.javaClass.simpleName}:${e.message}")
        }
    }

    private fun onGpsLocation(location: Location) {
        lastGpsFix = toFix(location)
        Log.d("OFFTRACE", "GPS: fix acquired acc=${location.accuracy}m provider=gps")
    }

    private fun onPassiveLocation(location: Location) {
        lastPassiveFix = toFix(location)
        Log.d("OFFTRACE", "GPS: passive fix acquired acc=${location.accuracy}m provider=passive")
    }

    private fun toFix(location: Location): Fix = Fix(
        latitude = location.latitude,
        longitude = location.longitude,
        accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
        altitudeMeters = if (location.hasAltitude()) location.altitude else null,
        fixTimeMs = System.currentTimeMillis(),
        tier = Tier.NONE // placeholder — real tier is computed fresh on every getBestFix() read
    )

    /** Null whenever there's nothing to report. Never fabricates coordinates — a
     *  genuine TIER_NONE returns null, exactly like PHASE 5A's single-tier
     *  contract. Tier is recomputed from age on every call rather than trusted from
     *  whenever the fix arrived, since a GPS_LIVE fix silently ages into GPS_STALE
     *  (and eventually past even that) purely by the clock ticking, with no new
     *  event to re-stamp it. */
    fun getBestFix(): Fix? {
        val gps = lastGpsFix
        if (gps != null) {
            val ageMs = System.currentTimeMillis() - gps.fixTimeMs
            if (ageMs <= GPS_LIVE_MAX_AGE_MS) {
                val fix = gps.copy(tier = Tier.GPS_LIVE)
                logTier(fix, ageMs, "gps")
                return fix
            }
            if (ageMs <= GPS_STALE_MAX_AGE_MS) {
                val fix = gps.copy(tier = Tier.GPS_STALE)
                logTier(fix, ageMs, "gps")
                return fix
            }
            // Older than 30 minutes — too stale even for GPS_STALE; fall through.
        }
        val passive = lastPassiveFix
        if (passive != null) {
            val ageMs = System.currentTimeMillis() - passive.fixTimeMs
            val fix = passive.copy(tier = Tier.PASSIVE)
            logTier(fix, ageMs, "passive")
            return fix
        }
        Log.d("OFFTRACE", "GPS: tier=0 acc=?m age=?s provider=none")
        return null
    }

    private fun logTier(fix: Fix, ageMs: Long, provider: String) {
        Log.d(
            "OFFTRACE",
            "GPS: tier=${fix.tier.wireValue} acc=${fix.accuracyMeters}m age=${ageMs / 1000}s provider=$provider"
        )
    }
}
