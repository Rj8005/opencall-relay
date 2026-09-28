package com.opencall.relay.offline

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File

/**
 * B5 (diagnostic follow-up): mic-capture-to-file for a push-to-talk voice
 * note — press-and-hold [start]/[stop], independent of any active call
 * (unlike the live-call Opus MediaCodec pipeline in OfflineMediaTransport,
 * which only runs during a call session). See TYPE_VOICE_NOTE's wire doc
 * in OfflineMediaTransport.kt for why this uses AAC/MPEG_4 rather than
 * Opus (MediaRecorder's Opus output needs API 29+; this app's minSdk is
 * 26) and [OfflineMediaTransport.VOICE_NOTE_MAX_DURATION_MS] for the
 * shared duration cap this class enforces.
 *
 * One instance per caller (the UI holding the press-and-hold control) —
 * not a process-wide singleton like MeshLedger/MeshBarometer/etc., since
 * recording is inherently a single in-progress-or-not UI interaction, not
 * shared session state.
 */
class VoiceNoteRecorder(private val context: Context) {

    data class Result(val audioBytes: ByteArray, val durationMs: Int)

    companion object {
        // Below this, MediaRecorder.stop() typically hasn't written a
        // usable file yet anyway (throws IllegalStateException — see
        // [stop]'s catch) — treated as an accidental tap, not a real
        // message, even on a device where stop() doesn't throw for it.
        private const val MIN_DURATION_MS = 400
    }

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAtElapsedMs: Long = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var maxDurationRunnable: Runnable? = null

    val isRecording: Boolean get() = recorder != null

    /** Starts recording. Returns false immediately (no-op) if already
     *  recording, or if RECORD_AUDIO isn't granted — this app already
     *  requests it up-front for the mesh session (see
     *  OfflineCallActivity.requiredPermissions); this is a defensive
     *  check, not a new permission-request flow.
     *
     *  [onAutoStopped] fires on the main thread if
     *  [OfflineMediaTransport.VOICE_NOTE_MAX_DURATION_MS] is hit while
     *  still held — the caller should treat this exactly like the user
     *  releasing right then (finish the gesture, send the result). */
    fun start(onAutoStopped: (Result) -> Unit): Boolean {
        if (recorder != null) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w("OFFTRACE", "VOICENOTE: RECORD_AUDIO not granted — cannot record")
            return false
        }
        val file = File(context.cacheDir, "voice_note_${System.currentTimeMillis()}.m4a")
        @Suppress("DEPRECATION")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        return try {
            rec.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(32000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = rec
            outputFile = file
            startedAtElapsedMs = SystemClock.elapsedRealtime()
            val runnable = Runnable {
                Log.d("OFFTRACE", "VOICENOTE: max duration reached, auto-stopping")
                stop()?.let { onAutoStopped(it) }
            }
            maxDurationRunnable = runnable
            mainHandler.postDelayed(runnable, OfflineMediaTransport.VOICE_NOTE_MAX_DURATION_MS.toLong())
            Log.d("OFFTRACE", "VOICENOTE: recording started")
            true
        } catch (e: Exception) {
            Log.w("OFFTRACE", "VOICENOTE: recorder start failed: ${e.javaClass.simpleName}:${e.message}")
            try { rec.release() } catch (_: Exception) {}
            file.delete()
            recorder = null
            outputFile = null
            false
        }
    }

    /** Stops and returns the finished recording, or null if nothing was
     *  recording or the recording was too short (accidental tap). Always
     *  cleans up the temp file itself — [Result.audioBytes] is the file's
     *  content read into memory, not a lingering file handle the caller
     *  must remember to delete. */
    fun stop(): Result? {
        maxDurationRunnable?.let { mainHandler.removeCallbacks(it) }
        maxDurationRunnable = null
        val rec = recorder ?: return null
        val file = outputFile
        recorder = null
        outputFile = null
        val durationMs = (SystemClock.elapsedRealtime() - startedAtElapsedMs).toInt()
        try {
            rec.stop()
        } catch (e: Exception) {
            // MediaRecorder.stop() throws IllegalStateException if called too
            // soon after start() (no data was ever written) — this IS the
            // "accidental tap, nothing to send" case, not a real error.
            Log.d("OFFTRACE", "VOICENOTE: stop() threw (likely too-short recording): ${e.message}")
            rec.release()
            file?.delete()
            return null
        }
        rec.release()
        if (durationMs < MIN_DURATION_MS) {
            file?.delete()
            return null
        }
        val bytes = try {
            file?.readBytes()
        } catch (e: Exception) {
            Log.w("OFFTRACE", "VOICENOTE: reading recorded file failed: ${e.message}")
            null
        }
        file?.delete()
        Log.d("OFFTRACE", "VOICENOTE: recording stopped durationMs=$durationMs bytes=${bytes?.size ?: 0}")
        return bytes?.let { Result(it, durationMs) }
    }

    /** Discards an in-progress recording without producing a Result — e.g.
     *  the user dragged off the button before releasing. Idempotent. */
    fun cancel() {
        maxDurationRunnable?.let { mainHandler.removeCallbacks(it) }
        maxDurationRunnable = null
        val rec = recorder ?: return
        recorder = null
        try { rec.stop() } catch (_: Exception) {}
        try { rec.release() } catch (_: Exception) {}
        outputFile?.delete()
        outputFile = null
        Log.d("OFFTRACE", "VOICENOTE: recording cancelled")
    }
}
