package com.opencall.relay.offline

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log

/**
 * PHASE 6 TRACK B1: cold destroys lithium capacity — at -15C you lose roughly
 * half of it. Once SOS has fired on the SENDING device, this class sheds
 * everything not needed to keep the party findable, targeting ~2h of normal
 * runtime turning into 12h+.
 *
 * WHAT THIS CAN AND CANNOT ACTUALLY DO ON STOCK ANDROID (read before relying on
 * the numbers above) — being honest about platform limits here the same way
 * the RSSI-proximity idea was dropped in PHASE 5BC rather than faked:
 *   - This app CANNOT literally power off the Wi-Fi radio — WifiManager.
 *     setWifiEnabled() has been restricted to system/device-owner apps since
 *     API 29. "Duty-cycled: 20s on, 40s off" is implemented as releasing this
 *     session's WifiLock (see OfflineCallService.releaseWifiLockForBeacon)
 *     during "off" windows, letting the platform's OWN standard Wi-Fi
 *     power-save policy apply (which DOES meaningfully cut power vs. the
 *     FULL_LOW_LATENCY/FULL_HIGH_PERF lock held the rest of the session), and
 *     reacquiring it for "on" windows — the mesh SOCKET stays open throughout
 *     (closing it would drop this device from the group, defeating the
 *     purpose); only the radio's performance mode is cycled.
 *   - This app CANNOT force the screen off — there is no public API for that.
 *     What it does: never acquires a SCREEN_*_WAKE_LOCK (only PARTIAL, for
 *     CPU), sets the window's own brightness to minimum while beacon mode's
 *     screen is showing, and tells the user to lock the phone. It does NOT
 *     write the user's global screen-timeout setting — that needs
 *     WRITE_SETTINGS and would be a genuinely bad thing for an app to silently
 *     change.
 *   - Camera/audio-capture/encoder teardown is done ENTIRELY through
 *     [OfflineMediaTransport]'s own existing, unmodified public teardown
 *     methods ([OfflineMediaTransport.endCall]/[leaveGroupCall]) — this class
 *     never touches encoder/decoder/camera/Opus code itself.
 */
class SosBeaconMode private constructor(context: Context) {

    companion object {
        private const val TAG_ON_MS = 20_000L
        private const val TAG_OFF_MS = 40_000L
        private const val STAY_UP_AFTER_INBOUND_MS = 60_000L
        private const val POSITION_INTERVAL_MS = 60_000L
        private const val GPS_INTERVAL_NORMAL_MS = 60_000L
        private const val GPS_INTERVAL_QUIET_MS = 5 * 60_000L
        private const val QUIET_THRESHOLD_MS = 30 * 60_000L
        private const val BATTERY_LOG_INTERVAL_MS = 60 * 60_000L

        @Volatile private var instance: SosBeaconMode? = null

        fun get(context: Context): SosBeaconMode =
            instance ?: synchronized(this) {
                instance ?: SosBeaconMode(context.applicationContext).also { instance = it }
            }
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile var active = false
        private set
    private var wakeLock: PowerManager.WakeLock? = null
    private var transport: OfflineMediaTransport? = null

    private var dutyCycleRunnable: Runnable? = null
    private var dutyCycleIsOn = true
    @Volatile private var lastInboundAtMs = 0L
    @Volatile private var lastContactAtMs = 0L
    private var quietGpsApplied = false

    private var startedAtMs = 0L
    private var startBatteryPct = -1
    private var batteryLogRunnable: Runnable? = null

    var onStateChanged: ((active: Boolean) -> Unit)? = null

    /** Called once, on SOS firing on THIS device — sheds everything. Safe to
     *  call more than once (no-op if already active). */
    fun enter(transport: OfflineMediaTransport) {
        if (active) return
        active = true
        this.transport = transport
        startedAtMs = System.currentTimeMillis()
        lastContactAtMs = startedAtMs
        quietGpsApplied = false
        startBatteryPct = readBatteryPercent()

        // Tear down anything media-related via the transport's OWN existing,
        // unmodified public teardown paths — never touches encoder/decoder/
        // camera/Opus code directly.
        transport.endCall()
        transport.leaveGroupCall()

        acquirePartialWakeLock()
        transport.setPositionBroadcastIntervalMs(POSITION_INTERVAL_MS)
        transport.setLocationCadence(GPS_INTERVAL_NORMAL_MS)
        startDutyCycle()
        startBatteryLogging()

        Log.d("OFFTRACE", "BEACON: mode ON — radios duty-cycled, batt=$startBatteryPct%")
        mainHandler.post { onStateChanged?.invoke(true) }
    }

    /** Manual exit — restores normal cadence, releases the beacon wake lock, and
     *  makes sure the Wi-Fi lock is back in its normal (held) state. Does NOT
     *  clear the SOS itself — that's a separate, explicit user action (same
     *  distinction as SosAlarm.silence not clearing SOS state). */
    fun exit() {
        if (!active) return
        active = false
        dutyCycleRunnable?.let { mainHandler.removeCallbacks(it) }
        dutyCycleRunnable = null
        batteryLogRunnable?.let { mainHandler.removeCallbacks(it) }
        batteryLogRunnable = null
        if (!dutyCycleIsOn) OfflineCallService.reacquireWifiLockForBeacon()
        dutyCycleIsOn = true
        releaseWakeLock()
        transport?.resumeNormalLocationCadence()
        Log.d("OFFTRACE", "BEACON: mode OFF — manual exit")
        mainHandler.post { onStateChanged?.invoke(false) }
        transport = null
    }

    /** Call on EVERY inbound mesh frame while beacon mode is active — someone is
     *  trying to reach us, so the radio stays in its "on" (low-latency) window
     *  for a further [STAY_UP_AFTER_INBOUND_MS], and the 30-minute quiet timer
     *  that would otherwise drop GPS to 1/5min resets. */
    fun onInboundFrame() {
        if (!active) return
        lastInboundAtMs = System.currentTimeMillis()
        lastContactAtMs = lastInboundAtMs
        if (quietGpsApplied) {
            quietGpsApplied = false
            transport?.setLocationCadence(GPS_INTERVAL_NORMAL_MS)
            Log.d("OFFTRACE", "GPS: cadence restored to 60s — contact resumed")
        }
        if (!dutyCycleIsOn) {
            dutyCycleIsOn = true
            OfflineCallService.reacquireWifiLockForBeacon()
        }
    }

    private fun startDutyCycle() {
        dutyCycleIsOn = true
        val runnable = object : Runnable {
            override fun run() {
                if (!active) return
                val now = System.currentTimeMillis()
                val stayUpForInbound = now - lastInboundAtMs < STAY_UP_AFTER_INBOUND_MS
                if (dutyCycleIsOn && !stayUpForInbound) {
                    dutyCycleIsOn = false
                    OfflineCallService.releaseWifiLockForBeacon()
                    mainHandler.postDelayed(this, TAG_OFF_MS)
                } else {
                    dutyCycleIsOn = true
                    OfflineCallService.reacquireWifiLockForBeacon()
                    mainHandler.postDelayed(this, TAG_ON_MS)
                }
                if (!quietGpsApplied && now - lastContactAtMs > QUIET_THRESHOLD_MS) {
                    quietGpsApplied = true
                    transport?.setLocationCadence(GPS_INTERVAL_QUIET_MS)
                    Log.d("OFFTRACE", "GPS: cadence dropped to 5min — 30min with no contact")
                }
            }
        }
        dutyCycleRunnable = runnable
        mainHandler.postDelayed(runnable, TAG_ON_MS)
    }

    private fun acquirePartialWakeLock() {
        try {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenCall:SosBeacon").apply { acquire() }
        } catch (e: Exception) {
            Log.w("OFFTRACE", "BEACON: wake lock acquire failed: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    private fun startBatteryLogging() {
        val runnable = object : Runnable {
            override fun run() {
                if (!active) return
                val pct = readBatteryPercent()
                val uptimeHours = (System.currentTimeMillis() - startedAtMs) / 3_600_000.0
                if (pct >= 0 && startBatteryPct >= 0 && uptimeHours > 0.0) {
                    val drainedPct = (startBatteryPct - pct).coerceAtLeast(0)
                    val drainRatePerHour = drainedPct / uptimeHours
                    val estRemainingHours = if (drainRatePerHour > 0.0) pct / drainRatePerHour else Double.POSITIVE_INFINITY
                    Log.d(
                        "OFFTRACE",
                        "BEACON: batt=$pct% uptime=${"%.1f".format(uptimeHours)}h " +
                            "est_remaining=${if (estRemainingHours.isFinite()) "%.1f".format(estRemainingHours) else "?"}h"
                    )
                }
                mainHandler.postDelayed(this, BATTERY_LOG_INTERVAL_MS)
            }
        }
        batteryLogRunnable = runnable
        mainHandler.postDelayed(runnable, BATTERY_LOG_INTERVAL_MS)
    }

    private fun readBatteryPercent(): Int {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus: Intent? = appContext.registerReceiver(null as BroadcastReceiver?, filter)
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level < 0 || scale <= 0) -1 else (level * 100 / scale)
        } catch (e: Exception) {
            -1
        }
    }
}
