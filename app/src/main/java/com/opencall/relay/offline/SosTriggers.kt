package com.opencall.relay.offline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * PHASE 6 TRACK B2: hands-free SOS triggers — a climber in a crevasse cannot
 * unlock a phone wearing mitts. EVERY trigger goes through the SAME 30-second
 * audible cancel countdown before it actually fires [onFire] — false alarms in
 * a party of 20 will get this feature switched off permanently, which is worse
 * than not having it at all. Each trigger is individually toggleable
 * (SharedPreferences("opencall"), keys below) and all default OFF except
 * volume-down.
 *
 * PLATFORM HONESTY NOTE — hardware-button long-press: there is no standard,
 * cross-device API for a 3rd-party app to intercept a long-press of a
 * dedicated hardware button. KEYCODE_POWER is reserved by the OS and never
 * even reaches an app's dispatchKeyEvent on stock Android; an OEM "assist key"
 * (Samsung's Bixby key etc.) sometimes CAN be remapped, but only through
 * per-OEM broadcasts/settings with no common implementation. [isEnabled] for
 * this trigger is wired up and the settings toggle exists, but [registerAll]
 * deliberately does not attempt to hook anything for it — see the comment at
 * that call site. This is the same kind of honest platform-limit call as
 * dropping RSSI-based proximity in PHASE 5BC rather than faking it.
 *
 * VOLUME-DOWN WITH THE SCREEN OFF: implemented via an active [MediaSession] —
 * the standard (if imperfect — behaviour varies by OEM and by whether another
 * app's media session is more "active") mechanism for routing hardware volume
 * key events to an app that isn't in the foreground.
 */
class SosTriggers private constructor(context: Context) {

    companion object {
        private const val PREFS_NAME = "opencall"
        const val PREF_VOLUME_DOWN = "trigger_volume_down"
        const val PREF_BUTTON_LONGPRESS = "trigger_button_longpress"
        const val PREF_FREEFALL = "trigger_freefall"
        const val PREF_NO_MOTION = "trigger_no_motion"

        private const val VOLUME_PRESS_COUNT = 5
        private const val VOLUME_WINDOW_MS = 3_000L
        private const val COUNTDOWN_MS = 30_000L
        private const val FREEFALL_THRESHOLD_MPS2 = 2.0f
        private const val FREEFALL_MIN_MS = 150L
        private const val FREEFALL_MAX_MS = 2_000L
        private const val IMPACT_THRESHOLD_MPS2 = 25.0f
        private const val MOTION_THRESHOLD_MPS2 = 1.2f
        private const val NO_MOTION_CHECK_INTERVAL_MS = 60_000L
        private const val NO_MOTION_THRESHOLD_MS = 20 * 60_000L

        private const val NOTIF_CHANNEL_ID = "sos_trigger_channel"
        private const val NOTIF_ID = 3001
        private const val ACTION_CANCEL_TRIGGER = "com.opencall.relay.offline.CANCEL_SOS_TRIGGER"

        @Volatile private var instance: SosTriggers? = null

        fun get(context: Context): SosTriggers =
            instance ?: synchronized(this) {
                instance ?: SosTriggers(context.applicationContext).also { instance = it }
            }
    }

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** Supplied by the caller (OfflineCallActivity) — true when THIS device has
     *  no other roster members right now, the "separated from the party" half
     *  of the no-motion trigger's condition. */
    var isolatedFromParty: (() -> Boolean)? = null
    /** Fired (main thread) once a countdown completes without cancellation —
     *  wired by the caller to mediaTransport?.startSos(...). */
    var onFire: (() -> Unit)? = null
    /** Fired (main thread) on every countdown tick, secondsRemaining counting
     *  down from 30 — purely for an optional in-app visual, the notification
     *  itself is the reliable/always-available surface. */
    var onCountdownTick: ((secondsRemaining: Int, triggerName: String) -> Unit)? = null
    var onCountdownEnded: (() -> Unit)? = null

    private var mediaSession: MediaSession? = null
    private var sensorManager: SensorManager? = null
    private var accelListener: SensorEventListener? = null
    private var cancelReceiver: BroadcastReceiver? = null
    private var noMotionCheckRunnable: Runnable? = null

    private val volumeDownTimestamps = ArrayDeque<Long>()
    private var fallingSinceMs = 0L
    @Volatile private var lastMotionAtMs = System.currentTimeMillis()

    @Volatile private var countdownActive = false
    private var countdownRunnable: Runnable? = null
    private var countdownTickRunnable: Runnable? = null
    private var countdownEndsAtMs = 0L
    private var beepTrack: AudioTrack? = null

    fun isEnabled(key: String): Boolean = prefs.getBoolean(key, key == PREF_VOLUME_DOWN)

    fun setEnabled(key: String, enabled: Boolean) {
        prefs.edit().putBoolean(key, enabled).apply()
    }

    /** Session-scoped: call when a group session becomes active. */
    fun registerAll() {
        createNotificationChannel()
        registerMediaSessionForVolumeKeys()
        registerAccelerometer()
        registerCancelReceiver()
        val runnable = object : Runnable {
            override fun run() {
                checkNoMotion()
                mainHandler.postDelayed(this, NO_MOTION_CHECK_INTERVAL_MS)
            }
        }
        noMotionCheckRunnable = runnable
        mainHandler.postDelayed(runnable, NO_MOTION_CHECK_INTERVAL_MS)
        // Hardware-button long-press: deliberately not hooked up here — see
        // class doc's platform honesty note. The settings toggle exists so a
        // future per-OEM integration has somewhere to plug in without another
        // settings-schema change.
    }

    fun unregisterAll() {
        mediaSession?.release()
        mediaSession = null
        accelListener?.let { sensorManager?.unregisterListener(it) }
        accelListener = null
        cancelReceiver?.let { try { appContext.unregisterReceiver(it) } catch (_: Exception) {} }
        cancelReceiver = null
        noMotionCheckRunnable?.let { mainHandler.removeCallbacks(it) }
        noMotionCheckRunnable = null
        cancelCountdown()
    }

    // ── Volume-down x5 ───────────────────────────────────────────────────────

    private fun registerMediaSessionForVolumeKeys() {
        try {
            val session = MediaSession(appContext, "OpenCallSosTrigger")
            session.setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val event: KeyEvent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    }
                    if (event != null && event.action == KeyEvent.ACTION_DOWN &&
                        event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                    ) {
                        onVolumeDownPress()
                        return true
                    }
                    return super.onMediaButtonEvent(mediaButtonIntent)
                }
            })
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PAUSED, 0, 1f)
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE)
                    .build()
            )
            session.isActive = true
            mediaSession = session
        } catch (e: Exception) {
            Log.w("OFFTRACE", "TRIGGER: media session setup failed: ${e.message}")
        }
    }

    /** Also callable directly from OfflineCallActivity.dispatchKeyEvent while
     *  foregrounded — same counter, harmless if both paths ever fire for the
     *  same physical press (Android key-dispatch routes a given press to
     *  either the focused window or the media session, not reliably both). */
    fun onVolumeDownPress() {
        if (!isEnabled(PREF_VOLUME_DOWN)) return
        val now = System.currentTimeMillis()
        volumeDownTimestamps.addLast(now)
        while (volumeDownTimestamps.isNotEmpty() && now - volumeDownTimestamps.first() > VOLUME_WINDOW_MS) {
            volumeDownTimestamps.removeFirst()
        }
        if (volumeDownTimestamps.size >= VOLUME_PRESS_COUNT) {
            volumeDownTimestamps.clear()
            fireTrigger("volume_down_x5")
        }
    }

    // ── Freefall + impact, no-motion ────────────────────────────────────────

    private fun registerAccelerometer() {
        val mgr = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val sensor = mgr.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: run {
            Log.d("OFFTRACE", "TRIGGER: no accelerometer — freefall/no-motion triggers unavailable")
            return
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val mag = sqrt(
                    event.values[0] * event.values[0] +
                        event.values[1] * event.values[1] +
                        event.values[2] * event.values[2]
                )
                onAccelerometerSample(mag)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        mgr.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        accelListener = listener
        sensorManager = mgr
    }

    private fun onAccelerometerSample(magnitude: Float) {
        val now = System.currentTimeMillis()
        if (abs(magnitude - SensorManager.GRAVITY_EARTH) > MOTION_THRESHOLD_MPS2) {
            lastMotionAtMs = now
        }
        if (!isEnabled(PREF_FREEFALL)) return
        if (magnitude < FREEFALL_THRESHOLD_MPS2) {
            if (fallingSinceMs == 0L) fallingSinceMs = now
            return
        }
        if (fallingSinceMs != 0L) {
            val fellForMs = now - fallingSinceMs
            fallingSinceMs = 0L
            if (fellForMs in FREEFALL_MIN_MS..FREEFALL_MAX_MS && magnitude > IMPACT_THRESHOLD_MPS2) {
                fireTrigger("freefall_impact")
            }
        }
    }

    private fun checkNoMotion() {
        if (!isEnabled(PREF_NO_MOTION)) return
        val now = System.currentTimeMillis()
        if (now - lastMotionAtMs < NO_MOTION_THRESHOLD_MS) return
        if (isolatedFromParty?.invoke() != true) return // stationary WITH the group is not a trigger
        fireTrigger("no_motion_20min_separated")
    }

    // ── Shared 30s audible cancel countdown ─────────────────────────────────

    private fun fireTrigger(name: String) {
        if (countdownActive) return // one countdown at a time
        Log.d("OFFTRACE", "TRIGGER: $name fired — 30s cancel window open")
        countdownActive = true
        countdownEndsAtMs = System.currentTimeMillis() + COUNTDOWN_MS
        showCountdownNotification(name)
        startBeep()
        val tick = object : Runnable {
            override fun run() {
                if (!countdownActive) return
                val remainingMs = countdownEndsAtMs - System.currentTimeMillis()
                val remainingSec = (remainingMs / 1000L).toInt().coerceAtLeast(0)
                onCountdownTick?.invoke(remainingSec, name)
                updateCountdownNotification(name, remainingSec)
                if (remainingMs > 0) mainHandler.postDelayed(this, 1_000L)
            }
        }
        countdownTickRunnable = tick
        mainHandler.post(tick)
        val fire = Runnable { completeCountdown() }
        countdownRunnable = fire
        mainHandler.postDelayed(fire, COUNTDOWN_MS)
    }

    fun cancelCountdown() {
        if (!countdownActive) return
        countdownActive = false
        countdownRunnable?.let { mainHandler.removeCallbacks(it) }
        countdownRunnable = null
        countdownTickRunnable?.let { mainHandler.removeCallbacks(it) }
        countdownTickRunnable = null
        stopBeep()
        notificationManager.cancel(NOTIF_ID)
        Log.d("OFFTRACE", "TRIGGER: cancelled by user")
        mainHandler.post { onCountdownEnded?.invoke() }
    }

    private fun completeCountdown() {
        if (!countdownActive) return
        countdownActive = false
        countdownTickRunnable?.let { mainHandler.removeCallbacks(it) }
        countdownTickRunnable = null
        stopBeep()
        notificationManager.cancel(NOTIF_ID)
        mainHandler.post {
            onCountdownEnded?.invoke()
            onFire?.invoke()
        }
    }

    private fun registerCancelReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ACTION_CANCEL_TRIGGER) cancelCountdown()
            }
        }
        val filter = IntentFilter(ACTION_CANCEL_TRIGGER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
        cancelReceiver = receiver
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID, "SOS trigger countdown", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Cancel window before an automatic SOS trigger fires" }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /** Reliable regardless of screen/foreground state — a high-priority
     *  notification with a one-tap Cancel action, the same class of mechanism
     *  Android uses for incoming calls (see IncomingCallActivity elsewhere in
     *  this app), rather than a custom overlay window with its own per-OEM
     *  permission reliability problems. */
    private fun showCountdownNotification(triggerName: String) {
        updateCountdownNotification(triggerName, (COUNTDOWN_MS / 1000L).toInt())
    }

    private fun updateCountdownNotification(triggerName: String, secondsRemaining: Int) {
        val cancelIntent = Intent(ACTION_CANCEL_TRIGGER).setPackage(appContext.packageName)
        val cancelPending = PendingIntent.getBroadcast(
            appContext, 0, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(appContext, NOTIF_CHANNEL_ID)
            .setContentTitle("SOS will fire in ${secondsRemaining}s")
            .setContentText("Trigger: $triggerName — tap Cancel if this was a false alarm")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "CANCEL", cancelPending)
            .build()
        notificationManager.notify(NOTIF_ID, notification)
    }

    // ── Countdown beep — short, distinct from SosAlarm's siren ─────────────────

    private fun startBeep() {
        try {
            val sampleRate = 22_050
            val toneMs = 150
            val frames = sampleRate * toneMs / 1000
            val pcm = ShortArray(frames)
            for (i in 0 until frames) {
                val angle = 2.0 * Math.PI * 1200.0 * i / sampleRate
                pcm[i] = (sin(angle) * Short.MAX_VALUE * 0.7).toInt().toShort()
            }
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(frames * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(pcm, 0, frames)
            // Beep once per second, quiet gap otherwise — one loop covering 1s
            // with 150ms of tone would need silence padding; simplest reliable
            // approach is a short one-shot re-triggered every second instead of
            // a single looped buffer, since MODE_STATIC loop points would just
            // repeat the tone back-to-back with no gap.
            beepTrack = track
            scheduleNextBeep()
        } catch (e: Exception) {
            Log.w("OFFTRACE", "TRIGGER: countdown beep start failed: ${e.message}")
        }
    }

    private fun scheduleNextBeep() {
        if (!countdownActive) return
        try { beepTrack?.stop(); beepTrack?.reloadStaticData(); beepTrack?.play() } catch (_: Exception) {}
        mainHandler.postDelayed({ scheduleNextBeep() }, 1_000L)
    }

    private fun stopBeep() {
        try { beepTrack?.stop(); beepTrack?.release() } catch (_: Exception) {}
        beepTrack = null
    }
}
