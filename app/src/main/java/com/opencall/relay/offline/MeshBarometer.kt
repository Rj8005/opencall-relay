package com.opencall.relay.offline

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.pow

/**
 * PHASE 5BC: barometric relative altitude. On a mountain, vertical separation
 * matters more than horizontal, and GPS altitude (±20-50m, worse on a slope) is
 * useless for it — a phone's barometer gets to roughly ±1m for the DIFFERENCE
 * between two nearby readings, even though neither one's absolute altitude is
 * trustworthy on its own. No manifest permission is required for
 * [Sensor.TYPE_PRESSURE] — it's a plain uncontrolled sensor like the
 * accelerometer, not a dangerous-permission-gated one.
 *
 * CALIBRATION: a fixed 1013.25 hPa sea-level reference drifts badly over a
 * multi-day trip as weather moves through — every hPa of reference error is
 * ~8m of altitude error. Instead, [recalibrateFromPeer] opportunistically resets
 * the local sea-level reference from any group member's own GPS altitude + the
 * pressure they reported at that moment, whenever that fix is good (accuracy
 * <10m) — see the caller in OfflineMediaTransport's TYPE_POSITION handler.
 *
 * Degrades cleanly: a phone with no barometer (common on budget models) simply
 * reports pressureHpa()/relativeAltitudeTo() as null forever; nothing else in the
 * app is allowed to depend on this sensor existing.
 */
class MeshBarometer private constructor(context: Context) {

    companion object {
        private const val SMOOTH_WINDOW = 10
        // ISA hypsometric formula constants: h = 44330 * (1 - (P/P0)^(1/5.255)).
        private const val ISA_ALTITUDE_COEFFICIENT = 44330.0
        private const val ISA_PRESSURE_EXPONENT = 1.0 / 5.255
        private const val STANDARD_SEA_LEVEL_HPA = 1013.25
        // Only trust a peer's GPS fix for calibration if it's at least this good —
        // a sloppy fix would poison the reference for everyone reading relative
        // altitude off it.
        const val CALIBRATION_MIN_ACCURACY_M = 10.0

        @Volatile private var instance: MeshBarometer? = null

        fun get(context: Context): MeshBarometer =
            instance ?: synchronized(this) {
                instance ?: MeshBarometer(context.applicationContext).also { instance = it }
            }
    }

    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val pressureSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)

    private val lock = Any()
    private var refCount = 0
    private var listener: SensorEventListener? = null
    private val recentSamples = ArrayDeque<Float>(SMOOTH_WINDOW)

    @Volatile private var seaLevelHpaRef: Double = STANDARD_SEA_LEVEL_HPA
    @Volatile private var calibrated: Boolean = false

    /** True iff this device has a barometer at all — callers should hide any
     *  barometric UI entirely rather than show a permanently-null value. */
    val isAvailable: Boolean get() = pressureSensor != null

    /** Session-scoped, ref-counted, same pattern as [OfflineLocationProvider] —
     *  call when a group session becomes active, balance with [stop]. No-op if
     *  there's no barometer on this device. */
    fun start() {
        synchronized(lock) {
            refCount++
            if (!isAvailable) {
                Log.d("OFFTRACE", "BARO: unavailable — no pressure sensor on this device")
                return
            }
            if (listener != null) return
            val mgr = sensorManager ?: return
            val sensor = pressureSensor ?: return
            val l = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    onPressureSample(event.values[0])
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            mgr.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            listener = l
            Log.d("OFFTRACE", "BARO: sensor registered")
        }
    }

    fun stop() {
        synchronized(lock) {
            if (refCount > 0) refCount--
            if (refCount > 0) return
            listener?.let { sensorManager?.unregisterListener(it) }
            listener = null
            recentSamples.clear()
        }
    }

    private fun onPressureSample(hpa: Float) {
        synchronized(lock) {
            // Wind gusts spike raw pressure momentarily — smooth over the last 10
            // samples rather than react to any single reading.
            if (recentSamples.size >= SMOOTH_WINDOW) recentSamples.removeFirst()
            recentSamples.addLast(hpa)
        }
    }

    /** Smoothed local pressure, hPa — null until at least one sample has arrived
     *  (or permanently null if there's no barometer). */
    fun smoothedPressureHpa(): Double? = synchronized(lock) {
        if (recentSamples.isEmpty()) null else recentSamples.average()
    }

    /** Opportunistic recalibration — called whenever ANY group member (including
     *  ourselves) reports a GPS fix with accuracy better than
     *  [CALIBRATION_MIN_ACCURACY_M], carrying both their reported GPS altitude and
     *  the barometric pressure they measured at that same moment. Resets the local
     *  sea-level reference so [relativeAltitudeTo] stays accurate as weather moves
     *  the true sea-level pressure over a multi-day trip. */
    fun recalibrateFromPeer(gpsAltitudeM: Double, pressureAtFixHpa: Double, fromNodeIdHex: String) {
        if (pressureAtFixHpa <= 0.0) return
        val newRef = pressureAtFixHpa / (1.0 - gpsAltitudeM / ISA_ALTITUDE_COEFFICIENT)
            .pow(1.0 / ISA_PRESSURE_EXPONENT)
        if (newRef.isNaN() || newRef.isInfinite() || newRef <= 0.0) return
        seaLevelHpaRef = newRef
        calibrated = true
        Log.d(
            "OFFTRACE",
            "BARO: recalibrated from $fromNodeIdHex gpsAlt=${gpsAltitudeM.toInt()}m"
        )
    }

    /** This device's own current barometric altitude estimate, using its own
     *  local calibration reference — informational only (wire field for a quick
     *  display fallback); the authoritative vertical-separation number is always
     *  [relativeAltitudeTo], computed from a pressure DIFFERENCE so reference
     *  error cancels. Null if there's no pressure reading yet or no barometer. */
    fun currentAltitudeEstimateM(): Int? {
        val myPressure = smoothedPressureHpa() ?: return null
        val alt = ISA_ALTITUDE_COEFFICIENT * (1.0 - (myPressure / seaLevelHpaRef).pow(ISA_PRESSURE_EXPONENT))
        if (alt.isNaN() || alt.isInfinite()) return null
        return alt.toInt()
    }

    /** Vertical separation from a peer, in metres — computed from the PRESSURE
     *  DIFFERENCE (both altitudes derived through the same local sea-level
     *  reference), not from two independently-referenced absolute altitudes, so a
     *  reference error mostly cancels rather than corrupting the separation.
     *  Positive means the peer is ABOVE us. Null if we have no pressure reading of
     *  our own, or there's no barometer at all. */
    fun relativeAltitudeTo(peerPressureHpa: Double): Int? {
        val myPressure = smoothedPressureHpa() ?: return null
        val ref = seaLevelHpaRef
        val myAlt = ISA_ALTITUDE_COEFFICIENT * (1.0 - (myPressure / ref).pow(ISA_PRESSURE_EXPONENT))
        val peerAlt = ISA_ALTITUDE_COEFFICIENT * (1.0 - (peerPressureHpa / ref).pow(ISA_PRESSURE_EXPONENT))
        val relative = (peerAlt - myAlt)
        if (relative.isNaN() || relative.isInfinite()) return null
        val rel = relative.toInt()
        Log.d(
            "OFFTRACE",
            "BARO: p=${"%.1f".format(myPressure)}hPa rel=${rel}m ref=${"%.2f".format(ref)}hPa"
        )
        return rel
    }
}
