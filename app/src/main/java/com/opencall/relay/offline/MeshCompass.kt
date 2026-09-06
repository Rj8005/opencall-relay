package com.opencall.relay.offline

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * PEER DIRECTION READOUT: this device's own TRUE-north azimuth, for rotating
 * the on-screen direction arrow ONLY — see the class doc on [Reading] and
 * OfflineCallActivity's wiring for the hard rule this exists to enforce:
 * rotating the phone must NEVER change any text row, only the arrow's
 * View.rotation. Nothing in this file reads MeshLedger or builds row text;
 * it has no way to influence it even by accident.
 *
 * TYPE_ROTATION_VECTOR (fused, sensor-fusion-quality heading) at
 * SENSOR_DELAY_UI — not the raw magnetometer, and not the accelerometer+
 * magnetometer getRotationMatrix() pairing, both of which are noisier and
 * need their own tilt compensation this sensor already does internally.
 *
 * DECLINATION: converts the sensor's magnetic azimuth to TRUE north via
 * [GeomagneticField], using whatever this device's own last known position
 * was (see [updateReferencePosition]) — declination changes slowly with
 * location, so a slightly stale reference position is fine; a missing one
 * (never fixed yet) falls back to 0 declination (magnetic north) rather than
 * blocking the arrow entirely.
 *
 * SMOOTHING: EMA (alpha=0.15) on the running sin/cos components, atan2'd
 * back out to degrees on every read — NOT a plain EMA on the raw degree
 * value, which would violate the 359->0 wrap (an EMA blending 359 and 1
 * degrees numerically would drift toward 180, exactly backward).
 *
 * ACCURACY: SENSOR_STATUS_ACCURACY_LOW/UNRELIABLE flips [Reading.accurate]
 * false — the caller (OfflineCallActivity) is expected to hide the arrow
 * entirely (not just freeze it) whenever that's true, per the task's
 * explicit "text still shown, arrow hidden" requirement.
 *
 * LIFECYCLE: [start]/[stop] are meant to be called from onResume/onPause,
 * not session-scoped like OfflineLocationProvider/MeshBarometer — no sensor
 * work should happen while the screen is off, and a compass reading has no
 * use outside the one screen that shows the arrow.
 */
class MeshCompass private constructor(context: Context) {

    /** [trueAzimuthDeg] is always a real 0..360 value once started — the
     *  smoothing state starts from the first real reading, never a 0
     *  placeholder that would make the arrow visibly snap on the first
     *  sample. [accurate] gates whether the CALLER should show the arrow at
     *  all; this class keeps computing/smoothing regardless (so accuracy
     *  recovering doesn't need to re-warm the EMA from scratch). */
    data class Reading(val trueAzimuthDeg: Float, val accurate: Boolean)

    companion object {
        private const val EMA_ALPHA = 0.15
        private const val CMP_LOG_INTERVAL_MS = 5_000L

        @Volatile private var instance: MeshCompass? = null

        fun get(context: Context): MeshCompass =
            instance ?: synchronized(this) {
                instance ?: MeshCompass(context.applicationContext).also { instance = it }
            }
    }

    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotationSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private var listener: SensorEventListener? = null
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    @Volatile private var emaSin = 0.0
    @Volatile private var emaCos = 1.0
    @Volatile private var emaInitialized = false
    @Volatile private var sensorAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
    @Volatile private var refLat: Double? = null
    @Volatile private var refLon: Double? = null
    @Volatile private var refAltM: Double = 0.0
    private var lastCmpLogAtMs = 0L

    /** Fired on every sensor sample, main thread (SensorManager's default
     *  Looper.getMainLooper() registration — see [start]). */
    var onReading: ((Reading) -> Unit)? = null

    val isAvailable: Boolean get() = rotationSensor != null

    /** Call from onResume. No-op (logged once) if this device has no
     *  TYPE_ROTATION_VECTOR sensor — [isAvailable] lets the caller decide
     *  whether to even try. Safe to call while already started. */
    fun start() {
        if (listener != null) return
        val mgr = sensorManager ?: return
        val sensor = rotationSensor ?: run {
            Log.d("OFFTRACE", "CMP: unavailable — no rotation vector sensor")
            return
        }
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) = onRotationVector(event)
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                sensorAccuracy = accuracy
            }
        }
        mgr.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
        listener = l
    }

    /** Call from onPause — no sensor work while the screen is off. */
    fun stop() {
        listener?.let { sensorManager?.unregisterListener(it) }
        listener = null
        emaInitialized = false
    }

    /** Opportunistic — call whenever this device's own best fix changes.
     *  Declination changes slowly with position, so this doesn't need to be
     *  precise or frequent; a stale reference position is fine. */
    fun updateReferencePosition(latitude: Double, longitude: Double, altitudeMeters: Double) {
        refLat = latitude
        refLon = longitude
        refAltM = altitudeMeters
    }

    private fun onRotationVector(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)
        val magneticAzDeg = (Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0

        val lat = refLat
        val lon = refLon
        val declination = if (lat != null && lon != null) {
            GeomagneticField(lat.toFloat(), lon.toFloat(), refAltM.toFloat(), System.currentTimeMillis()).declination.toDouble()
        } else {
            0.0
        }
        val trueAzDeg = (magneticAzDeg + declination + 360.0) % 360.0

        // EMA on sin/cos, not on the raw degree value — see class doc for
        // why (the 359->0 wrap).
        val (newSin, newCos, smoothedDeg) = smoothAzimuth(
            prevSin = emaSin, prevCos = emaCos, initialized = emaInitialized, newAzimuthDeg = trueAzDeg
        )
        emaSin = newSin
        emaCos = newCos
        emaInitialized = true

        val accurate = sensorAccuracy != SensorManager.SENSOR_STATUS_UNRELIABLE &&
            sensorAccuracy != SensorManager.SENSOR_STATUS_ACCURACY_LOW
        onReading?.invoke(Reading(smoothedDeg, accurate))

        val now = System.currentTimeMillis()
        if (now - lastCmpLogAtMs >= CMP_LOG_INTERVAL_MS) {
            lastCmpLogAtMs = now
            Log.d("OFFTRACE", "CMP: az=${smoothedDeg.toInt()} decl=${"%.1f".format(declination)} acc=$sensorAccuracy")
        }
    }
}

/** PEER DIRECTION READOUT STEP 6: pure EMA smoothing on the sin/cos
 *  components of an azimuth reading, extracted as a free (non-Android,
 *  off-device-testable) function — see MeshCompassTest — since MeshCompass
 *  itself needs a real Context to construct (this project has no Robolectric
 *  dependency, same reasoning as GeoUtils.geodesicDistanceAndBearing's doc).
 *  Deliberately NOT an EMA on the raw degree value, which would break at the
 *  359->0 wrap (naively blending 359 and 1 would drift toward 180, exactly
 *  backward) — smoothing the sin/cos pair and re-deriving degrees via atan2
 *  has no wrap discontinuity at all. Returns (newEmaSin, newEmaCos,
 *  smoothedDegrees). [initialized]=false seeds the EMA state directly from
 *  the first reading instead of blending against an arbitrary starting
 *  sin/cos pair, so the very first sample never visibly snaps. */
fun smoothAzimuth(
    prevSin: Double,
    prevCos: Double,
    initialized: Boolean,
    newAzimuthDeg: Double,
    alpha: Double = 0.15
): Triple<Double, Double, Float> {
    val rad = Math.toRadians(newAzimuthDeg)
    val newSin: Double
    val newCos: Double
    if (!initialized) {
        newSin = sin(rad)
        newCos = cos(rad)
    } else {
        newSin = alpha * sin(rad) + (1 - alpha) * prevSin
        newCos = alpha * cos(rad) + (1 - alpha) * prevCos
    }
    val smoothedDeg = ((Math.toDegrees(atan2(newSin, newCos)) + 360.0) % 360.0).toFloat()
    return Triple(newSin, newCos, smoothedDeg)
}
