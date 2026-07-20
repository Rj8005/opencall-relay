package com.opencall.relay

import android.content.Context
import android.media.*
import android.util.Log
import kotlinx.coroutines.*

class AudioBridge(private val context: Context) {

    companion object {
        const val TAG = "AudioBridge"
        const val SAMPLE_RATE = 8000
        const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        const val FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var gsmRecorder: AudioRecord? = null   // captures C's voice from GSM
    private var gsmPlayer: AudioTrack? = null      // injects A's voice into GSM
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Callbacks
    var onGSMAudio: ((ByteArray) -> Unit)? = null  // C's voice → send to A via WebRTC
    var getWebRTCAudio: (() -> ByteArray?)? = null // A's voice → inject into GSM

    fun start() {
        if (isRunning) return
        isRunning = true
        Log.d(TAG, "Starting audio bridge")

        setupGSMCapture()
        setupGSMPlayback()
        startCapture()
        startPlayback()
    }

    private fun setupGSMCapture() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_IN, FORMAT) * 2

        // Try sources in order — best first
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_DOWNLINK,  // incoming GSM audio
            MediaRecorder.AudioSource.VOICE_CALL,      // both directions
            MediaRecorder.AudioSource.VOICE_UPLINK,    // outgoing GSM audio
            MediaRecorder.AudioSource.VOICE_COMMUNICATION // VoIP fallback
        )

        for (source in sources) {
            try {
                val recorder = AudioRecord(
                    source, SAMPLE_RATE, CHANNEL_IN, FORMAT, bufferSize)
                if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                    gsmRecorder = recorder
                    Log.d(TAG, "✅ GSM capture: source=$source")
                    return
                } else {
                    recorder.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Source $source failed: ${e.message}")
            }
        }
        Log.e(TAG, "❌ All GSM capture sources failed")
    }

    private fun setupGSMPlayback() {
        val bufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_OUT, FORMAT) * 2

        try {
            gsmPlayer = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_OUT)
                        .setEncoding(FORMAT)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            gsmPlayer?.play()
            Log.d(TAG, "✅ GSM playback initialized")
        } catch (e: Exception) {
            Log.e(TAG, "GSM playback setup failed: ${e.message}")
        }
    }

    private fun startCapture() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_IN, FORMAT) * 2

        scope.launch {
            try {
                gsmRecorder?.startRecording()
                Log.d(TAG, "📻 GSM capture started")

                val buffer = ByteArray(bufferSize)
                var frames = 0

                while (isRunning) {
                    val read = gsmRecorder?.read(buffer, 0, bufferSize) ?: break
                    if (read > 0) {
                        frames++

                        // Check if we're getting real audio
                        if (frames % 100 == 0) {
                            val max = buffer.take(read)
                                .map { kotlin.math.abs(it.toInt()) }
                                .maxOrNull() ?: 0
                            Log.d(TAG, "GSM capture frame $frames maxLevel=$max")
                        }

                        // Send C's voice to A via WebRTC callback
                        onGSMAudio?.invoke(buffer.copyOf(read))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Capture error: ${e.message}")
            }
        }
    }

    private fun startPlayback() {
        scope.launch {
            Log.d(TAG, "🔊 GSM playback loop started")
            while (isRunning) {
                // Get A's voice from WebRTC
                val audioData = getWebRTCAudio?.invoke()
                if (audioData != null && audioData.isNotEmpty()) {
                    gsmPlayer?.write(audioData, 0, audioData.size)
                } else {
                    delay(10) // wait for audio data
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        scope.cancel()
        try {
            gsmRecorder?.stop()
            gsmRecorder?.release()
            gsmRecorder = null
        } catch (e: Exception) { }
        try {
            gsmPlayer?.stop()
            gsmPlayer?.release()
            gsmPlayer = null
        } catch (e: Exception) { }
        Log.d(TAG, "Audio bridge stopped")
    }
}
