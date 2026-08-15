package com.opencall.relay.offline

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import kotlin.math.sin

/**
 * PHASE 5BC: the SOS siren — must be audible on a phone in a pocket, on silent,
 * during an active call, wearing gloves. Generates its own tone with [AudioTrack]
 * (no asset file): a two-tone alternating sweep, ~650Hz/~900Hz, 500ms each,
 * looped via [AudioTrack.setLoopPoints] so no feeder thread is needed.
 *
 * STREAM CHOICE: [AudioAttributes.USAGE_ALARM] + [AudioAttributes.CONTENT_TYPE_SONIFICATION]
 * — this is what routes to STREAM_ALARM under the modern AudioTrack.Builder API
 * (there's no separate raw stream-type argument to pass once you're using
 * AudioAttributes). Deliberately NOT USAGE_MEDIA/STREAM_MUSIC: a group call sets
 * AudioManager.MODE_IN_COMMUNICATION for its own AudioTrack playback (see
 * OfflineMediaTransport.setupAudioRouting) — a media-stream siren would get
 * ducked or routed to the earpiece under that mode. This class also NEVER calls
 * requestAudioFocus — doing so would duck or stop the call's own Opus playback,
 * and someone already on a call is exactly who most needs to hear this.
 *
 * STATE-KEYED, NOT FRAME-KEYED: [onActiveSendersChanged] is driven by the current
 * SET of active SOS senders (recomputed from MeshSosManager.sosEntries on every
 * received frame, SOS or otherwise), not by individual frame arrivals — the
 * siren starts on an empty->non-empty transition and stops on non-empty->empty,
 * so a 30s rebroadcast from an already-known sender (same key already in the
 * set) never touches it. See the empty/non-empty diff below.
 */
class SosAlarm private constructor(context: Context) {

    companion object {
        private const val AUTO_STOP_MS = 5 * 60_000L
        private const val TONE_A_HZ = 650.0
        private const val TONE_B_HZ = 900.0
        private const val TONE_SEGMENT_MS = 500
        private const val SAMPLE_RATE = 44_100
        private const val AMPLITUDE_SCALE = 0.85
        private const val VIBRATE_ON_MS = 500L
        private const val VIBRATE_OFF_MS = 500L

        @Volatile private var instance: SosAlarm? = null

        fun get(context: Context): SosAlarm =
            instance ?: synchronized(this) {
                instance ?: SosAlarm(context.applicationContext).also { instance = it }
            }
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val vibrator = appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    private val mainHandler = Handler(Looper.getMainLooper())

    private val lock = Any()
    /** The last SET of active sender nodeIds passed in — comparing ITS
     *  emptiness against the new call's is the entire "state-keyed" mechanism.
     *  Deliberately NOT compared element-by-element: a rebroadcast from an
     *  already-known sender leaves this set exactly as it was (same map key),
     *  so no transition is observed and nothing retriggers. */
    private var previousActiveSenders: Set<Long> = emptySet()
    @Volatile private var sounding = false
    private var previousAlarmVolume: Int = -1
    private var audioTrack: AudioTrack? = null
    private var timeoutRunnable: Runnable? = null

    /** Non-null while at least one member's SOS is active — drives the
     *  full-screen alert's visibility independent of whether sound is currently
     *  playing (see [silence]). */
    var onSirenSoundingChanged: ((Boolean) -> Unit)? = null

    /** BUG 1 FIX 2: fired (with the set of senders that WERE sounding) whenever
     *  [forceStopOnTimeout] fires — silencing the sound alone is not enough
     *  (that's all the OLD behavior did, which is exactly what let a stale SOS
     *  persist indefinitely); the owner must also mark those senders' state
     *  locally so a later rebroadcast from the same still-uncleared sender
     *  doesn't look like a fresh onset and re-siren. See
     *  MeshSosManager.suppressAlarmFor, which this is wired to. */
    var onAutoStopTimeout: ((Set<Long>) -> Unit)? = null

    /** Called whenever the set of currently-active (non-CLEAR) SOS senders
     *  changes, for ANY reason — a new SOS, a rebroadcast refresh, or a CLEAR.
     *  [activeSenderIds] must already exclude this device's own nodeId — the
     *  device that pressed SOS does not siren itself (enforced by the caller:
     *  MeshSosManager.sosEntries is only ever populated from RECEIVED frames,
     *  never from this device's own broadcast, so it's naturally excluded). */
    fun onActiveSendersChanged(activeSenderIds: Set<Long>) {
        synchronized(lock) {
            val wasEmpty = previousActiveSenders.isEmpty()
            val isEmpty = activeSenderIds.isEmpty()
            previousActiveSenders = activeSenderIds
            when {
                wasEmpty && !isEmpty -> startInternal(activeSenderIds.size)
                !wasEmpty && isEmpty -> stopInternal("cleared")
                // else: no empty<->non-empty transition — do not retrigger.
            }
        }
    }

    /** Local "Silence" — stops sound/vibration on THIS device only. Does NOT
     *  touch [previousActiveSenders] or clear any SOS/alert state, so a later
     *  rebroadcast from the SAME still-active sender(s) correctly stays silent
     *  (no empty->non-empty transition happened), while a genuinely NEW SOS
     *  onset (a real transition) sounds again normally. */
    fun silence() {
        synchronized(lock) {
            if (!sounding) return
            stopSoundAndRestoreVolume()
            stopVibration()
            timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            timeoutRunnable = null
            sounding = false
            onSirenSoundingChanged?.invoke(false)
            Log.d("OFFTRACE", "SOS: siren STOP reason=silenced")
        }
    }

    private fun startInternal(senderCount: Int) {
        if (sounding) return
        sounding = true
        Log.d("OFFTRACE", "SOS: siren START senders=$senderCount")
        startSound()
        startVibration()
        onSirenSoundingChanged?.invoke(true)
        val r = Runnable { forceStopOnTimeout() }
        timeoutRunnable = r
        mainHandler.postDelayed(r, AUTO_STOP_MS)
    }

    private fun forceStopOnTimeout() {
        synchronized(lock) {
            if (!sounding) return
            val senders = previousActiveSenders
            stopInternal("timeout_5min_no_clear")
            // A sender who's out of range never sends CLEAR, so the tracked set
            // never naturally empties — reset OUR OWN empty/non-empty diffing so
            // a genuinely NEW SOS (a different sender, or this one resuming after
            // an explicit CLEAR) still sounds again.
            previousActiveSenders = emptySet()
            // BUG 1 FIX 2: silencing the sound alone used to be the entire fix —
            // but the underlying SOS stayed marked "active" in the caller's own
            // state, so the SAME sender's next ordinary 30s rebroadcast looked
            // like a brand-new onset the moment [onActiveSendersChanged] saw
            // empty->non-empty again, and the siren came right back. The owner
            // must mark local state too (see onAutoStopTimeout's doc).
            Log.d("OFFTRACE", "SOS: auto-stop 5min — state cleared, carry marked")
            onAutoStopTimeout?.invoke(senders)
        }
    }

    private fun stopInternal(reason: String) {
        if (!sounding) return
        sounding = false
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
        stopSoundAndRestoreVolume()
        stopVibration()
        onSirenSoundingChanged?.invoke(false)
        Log.d("OFFTRACE", "SOS: siren STOP reason=$reason")
    }

    // ── Sound ────────────────────────────────────────────────────────────────

    private fun startSound() {
        try {
            previousAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)

            val toneFrames = SAMPLE_RATE * TONE_SEGMENT_MS / 1000
            val totalFrames = toneFrames * 2
            val pcm = ShortArray(totalFrames)
            for (i in 0 until toneFrames) pcm[i] = sineSample(TONE_A_HZ, i)
            for (i in 0 until toneFrames) pcm[toneFrames + i] = sineSample(TONE_B_HZ, i)

            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(totalFrames * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(pcm, 0, totalFrames)
            track.setLoopPoints(0, totalFrames, -1) // -1 = loop forever
            track.play()
            audioTrack = track
        } catch (e: Exception) {
            Log.w("OFFTRACE", "SOS: siren sound start failed: ${e.message}")
        }
    }

    /** Deliberately structured so the volume restore happens in a finally block —
     *  a crash tearing down the AudioTrack must never leave the phone stuck at
     *  max alarm volume. */
    private fun stopSoundAndRestoreVolume() {
        try {
            try {
                audioTrack?.stop()
            } finally {
                audioTrack?.release()
                audioTrack = null
            }
        } catch (e: Exception) {
            Log.w("OFFTRACE", "SOS: siren sound stop failed: ${e.message}")
        } finally {
            if (previousAlarmVolume >= 0) {
                try {
                    audioManager.setStreamVolume(AudioManager.STREAM_ALARM, previousAlarmVolume, 0)
                } catch (_: Exception) {
                }
                previousAlarmVolume = -1
            }
        }
    }

    private fun sineSample(freqHz: Double, sampleIndex: Int): Short {
        val angle = 2.0 * Math.PI * freqHz * sampleIndex / SAMPLE_RATE
        return (sin(angle) * Short.MAX_VALUE * AMPLITUDE_SCALE).toInt().toShort()
    }

    // ── Vibration ────────────────────────────────────────────────────────────

    private fun startVibration() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            val pattern = longArrayOf(0, VIBRATE_ON_MS, VIBRATE_OFF_MS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            Log.w("OFFTRACE", "SOS: vibration start failed: ${e.message}")
        }
    }

    private fun stopVibration() {
        try { vibrator?.cancel() } catch (_: Exception) {}
    }
}
