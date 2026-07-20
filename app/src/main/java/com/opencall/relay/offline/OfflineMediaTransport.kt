package com.opencall.relay.offline

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TWO-WAY video transport: each device's camera → the other device's screen,
 * full-duplex over a single TCP socket (port 8889). Each side runs both a
 * Camera2 → MediaCodec H.264 encoder → socket write path, and a socket read →
 * MediaCodec decoder → Surface path, on the same connected socket. Completely
 * independent of WebRTC.
 *
 * The Group Owner accepts the one connection (ServerSocket.accept()) and the
 * client makes the one connection (Socket connect()); once that socket is up,
 * both roles behave identically — camera+encoder writing frames out, and a
 * read loop feeding the decoder from whatever the other side sends.
 *
 * Wire framing (binary, big-endian), identical in both directions:
 *   [1 byte type][4 bytes length][payload]
 *   type 1 = codec config (SPS + PPS, sent once before the first frame)
 *   type 2 = video access unit (H.264 keyframe or delta)
 *   type 3 = audio chunk, 20ms @ 16kHz mono — either raw PCM 16-bit, or Opus-encoded,
 *            per whatever [AudioCodec] that sender announced in its own type-6 frame
 *            (each direction negotiates its own codec independently; never assumed)
 *   type 4 = chat message (UTF-8 text, capped at MAX_CHAT_PAYLOAD_BYTES)
 *   type 5 = mode control (one byte mode id; see [CallMode]), sent once by the
 *            call initiator before any media so the callee configures matching
 *            sender halves. If a type 1/2/3 frame arrives before type 5, the
 *            receiving side falls back to assuming CallMode.VIDEO (back-compat).
 *   type 6 = audio codec control (one byte [AudioCodec] id), sent by whichever side
 *            is about to start its own audio sender, before that side's first type-3
 *            frame. If a type-3 frame arrives before type 6, the receiving side falls
 *            back to assuming AudioCodec.PCM (back-compat with pre-Opus peers).
 */
class OfflineMediaTransport(
    private val context: Context,
    private val isGroupOwner: Boolean,
    private val groupOwnerAddress: InetAddress?,
    // TASK 2: non-null means this side is the call initiator and already knows the
    // mode (chosen via the pre-connect dialog) — it will announce it over the wire
    // via a type-5 frame. Null means this side is the callee and must wait to learn
    // the mode from the wire (either an explicit type-5 frame, or back-compat
    // inference from the first media frame).
    private val initialMode: CallMode?,
    private val onError: (String) -> Unit
) {
    /** TASK 2: which halves of the transport run this call. Symmetric — both peers
     *  end up running the same mode. */
    enum class CallMode(val wireId: Byte) {
        VIDEO(1), AUDIO(2), CHAT(3);
        companion object {
            fun fromWireId(id: Byte): CallMode? = values().firstOrNull { it.wireId == id }
        }
    }

    /** OPUS: which codec a given direction's audio is encoded with. Each side probes
     *  its own hardware/software Opus encoder support independently and announces the
     *  result — the two directions of one call can legitimately differ. */
    enum class AudioCodec(val wireId: Byte) {
        OPUS(1), PCM(2);
        companion object {
            fun fromWireId(id: Byte): AudioCodec? = values().firstOrNull { it.wireId == id }
        }
    }

    companion object {
        private const val TAG = "OfflineMediaTransport"
        private const val MEDIA_PORT = 8889
        private const val CONNECT_RETRIES = 10
        private const val CONNECT_RETRY_DELAY_MS = 500L
        private const val TYPE_CONFIG: Byte = 1
        private const val TYPE_FRAME: Byte = 2
        private const val TYPE_AUDIO: Byte = 3
        private const val TYPE_CHAT: Byte = 4
        private const val TYPE_MODE: Byte = 5
        private const val TYPE_AUDIO_CODEC: Byte = 6
        private const val MAX_CHAT_PAYLOAD_BYTES = 4096
        private const val WIDTH = 1280
        private const val HEIGHT = 720
        private const val FPS = 30
        private const val BITRATE = 2_000_000
        private const val AUDIO_SAMPLE_RATE = 16000
        private const val AUDIO_CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val AUDIO_CHUNK_BYTES = 640 // 20ms @ 16kHz mono 16-bit
        private const val MAX_CAMERA_IN_USE_RETRIES = 3
        private const val CAMERA_IN_USE_RETRY_DELAY_MS = 700L
        private const val SOCKET_READ_TIMEOUT_MS = 5000

        // OPUS: encoder params. Same sample rate/channel count as the PCM path so the
        // two are interchangeable per-frame with no resampling.
        private const val OPUS_CHANNEL_COUNT = 1
        private const val OPUS_BITRATE = 20000
        private const val OPUS_HEADER_SIZE = 19 // "OpusHead" id header, no channel mapping table (mono)
        // RFC 7845 §4.2: pre-skip is always expressed in 48kHz samples regardless of the
        // actual decode sample rate. We have no way to read MediaCodec's Opus encoder's
        // true algorithmic lookahead, and for a live two-way call trimming a few ms of
        // lookahead is inaudible — use 0 (no trim) rather than guess a value that's
        // probably wrong anyway. Same reasoning for seek preroll: this is a live stream,
        // never seeked, so it doesn't apply.
        private const val OPUS_PRE_SKIP_SAMPLES_48K = 0
        private const val OPUS_SEEK_PREROLL_NS = 0L
        private const val AUDIO_BYTES_LOG_WINDOW_MS = 5000L

        // FIX: queue-isolated codec threads. Capacities are ~200ms (encode) / ~400ms
        // (decode) of audio — enough to absorb a brief scheduling hiccup without the
        // producer ever blocking, small enough that a backed-up consumer sheds the
        // oldest chunk instead of growing unbounded latency.
        private const val OPUS_ENCODE_QUEUE_CAPACITY = 10
        private const val OPUS_DECODE_QUEUE_CAPACITY = 20
        private const val OPUS_QUEUE_POLL_TIMEOUT_MS = 200L
        private const val DROP_LOG_INTERVAL = 100
        private const val OPUS_ENCODE_STOP_JOIN_MS = 500L
        private const val OPUS_DECODE_STOP_JOIN_MS = 500L

        // FIX: hardened mic path — AudioRecord construction/start is verified rather
        // than assumed, and a run of read() failures rebuilds it in place instead of
        // spinning silently forever.
        private const val AUDIO_RECORD_RETRY_DELAY_MS = 500L
        private const val MIC_READ_ERROR_REBUILD_THRESHOLD = 100 // ~2s at 20ms/chunk
        private const val MAX_MIC_REBUILD_ATTEMPTS = 3
        private const val MIC_READ_ERROR_LOG_INTERVAL = 50
        private const val MIC_ZERO_READ_LOG_INTERVAL = 250

        // FIX: decode-queue drop warning is windowed, not cumulative — a healthy call
        // should show ~0 drops, so "every Nth drop ever" is the wrong signal. Only warn
        // if the drop RATE itself looks like a problem.
        private const val DROP_WARN_WINDOW_MS = 10_000L
        private const val DROP_WARN_THRESHOLD = 50
    }

    private val running = AtomicBoolean(false)
    // FIX 2: separate from `running` — latches false exactly once on the first
    // I/O failure on either direction, so a single link-death event triggers exactly
    // one teardown regardless of which thread (write or read) notices it first.
    private val alive = AtomicBoolean(true)
    private val mainHandler = Handler(Looper.getMainLooper())

    // TASK 2: latches true exactly once, when this side's sender halves (camera/mic,
    // per-mode) have been started — guards against double-starting from both the
    // initiator's upfront call and a racing wire-triggered resolution.
    private val sendersStarted = AtomicBoolean(false)
    @Volatile private var resolvedMode: CallMode? = null

    // TASK 1: chat writes must never run on the caller's thread (chat is sent from a
    // UI click/IME action, i.e. the main thread) — a single-thread queue keeps chat
    // I/O off-main while still going through writeFrame's existing synchronized(out)
    // lock discipline shared with the audio/video writer threads.
    private var chatThread: HandlerThread? = null
    private var chatHandler: Handler? = null

    // Sockets
    private var serverSocket: ServerSocket? = null
    private var socket: Socket? = null
    private var dataIn: DataInputStream? = null
    private var dataOut: DataOutputStream? = null

    // Camera2 (local send path — runs on both roles)
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    // Encoder (local send path — runs on both roles)
    private var encoder: MediaCodec? = null
    private var encoderInputSurface: Surface? = null

    // Decoder (remote receive path — runs on both roles)
    private var decoder: MediaCodec? = null
    @Volatile private var displaySurface: Surface? = null

    // Audio (both send and receive paths — run on both roles)
    private var audioRecord: AudioRecord? = null
    @Volatile private var audioTrack: AudioTrack? = null
    private var audioWriteErrCount = 0
    private var audioTrackWriteCount = 0

    // OPUS: send-side codec (this side's own choice, probed once in startAudioSender)
    // and the encoder itself, only non-null when that probe succeeded.
    @Volatile private var localAudioCodec: AudioCodec = AudioCodec.PCM
    private var audioEncoder: MediaCodec? = null
    // OPUS: receive-side codec — learned from the peer's type-6 frame; null (falls
    // back to PCM) until then, same back-compat shape as CallMode resolution.
    @Volatile private var remoteAudioCodec: AudioCodec? = null
    private var audioDecoder: MediaCodec? = null

    // FIX: isolates all Opus/MediaCodec work off the realtime mic-capture loop
    // ("MediaAudioSend") and the shared socket read loop ("MediaReadLoop"). Each
    // queue has exactly one producer and one consumer thread, so the plain Int drop
    // counters below are safe without synchronization.
    private val opusEncodeQueue = ArrayBlockingQueue<ByteArray>(OPUS_ENCODE_QUEUE_CAPACITY)
    private var opusEncodeThread: Thread? = null
    private var opusEncodeDropCount = 0 // written by MediaAudioSend (producer) only
    private var opusEncodeInputDropCount = 0 // written by MediaOpusEncode (consumer) only

    private val opusDecodeQueue = ArrayBlockingQueue<ByteArray>(OPUS_DECODE_QUEUE_CAPACITY)
    private var opusDecodeThread: Thread? = null
    private var opusDecodeDropCount = 0 // written by MediaReadLoop (producer) only
    // FIX: windowed drop-rate tracking (see DROP_WARN_WINDOW_MS/DROP_WARN_THRESHOLD),
    // also written only by MediaReadLoop (same producer as opusDecodeDropCount above).
    private var decodeDropWindowCount = 0
    private var decodeDropWindowStartMs = 0L

    // FIX: Android's Opus MediaCodec decoder outputs PCM at whatever rate/channel count
    // it reports via INFO_OUTPUT_FORMAT_CHANGED — NOT necessarily AUDIO_SAMPLE_RATE,
    // which is only what we configured the DECODER's input side with (in practice this
    // is commonly 48000 regardless). Defaults assume 48000/mono (point 5's guard) in
    // case the format never arrives before the first output buffer; the format-changed
    // handler corrects these the moment it fires. Touched only by "MediaOpusDecode".
    private var opusOutputSampleRate = 48000
    private var opusOutputChannelCount = OPUS_CHANNEL_COUNT

    // OPUS: dev-time bandwidth instrumentation — each pair of vars is touched by
    // exactly one thread (send: MediaAudioSend, recv: MediaReadLoop) so plain fields
    // are safe without synchronization. Logs actual audio payload bytes/sec on the
    // wire so the PCM->Opus reduction is directly observable in logcat.
    private var audioSendBytesAccum = 0L
    private var audioSendWindowStartMs = 0L
    private var audioRecvBytesAccum = 0L
    private var audioRecvWindowStartMs = 0L

    // FIX 1: audio routing (mode + speaker), applied once when the audio paths start
    // and restored on stop().
    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private var previousAudioMode = AudioManager.MODE_NORMAL
    private var audioRoutingApplied = false

    /** Fired once, off the failing thread, on the first unrecoverable I/O error on the
     *  media socket (either direction). The transport has already torn itself down
     *  (stop()) by the time this fires — the caller just needs to end its own session. */
    var onLinkLost: (() -> Unit)? = null

    /** TASK 1: fired on the main thread whenever the decoder reports the REMOTE peer's
     *  actual decoded frame size (post-crop), so the caller can letterbox instead of
     *  stretching to fill a same-aspect-assumed view. */
    var onVideoSize: ((Int, Int) -> Unit)? = null

    /** TASK 2: fired on the main thread with a decoded chat message from the peer. */
    var onChatMessage: ((String) -> Unit)? = null

    /** TASK 2: fired on the main thread, at most once, once this side knows which
     *  [CallMode] the call is running in (immediately for the initiator, or once
     *  learned from the wire for the callee) — the caller uses this to finish
     *  building the mode-appropriate UI. */
    var onModeResolved: ((CallMode) -> Unit)? = null

    /** Call before start() to provide the Surface the REMOTE peer's decoded video renders to. */
    fun setDisplaySurface(surface: Surface) {
        displaySurface = surface
    }

    /** TASK 2: chat path is always on regardless of [CallMode]. Safe to call from any
     *  thread (the UI thread, typically) — the actual write is posted to [chatHandler]
     *  so it never runs on the caller's thread (TASK 1: this used to run the socket
     *  write inline, which threw NetworkOnMainThreadException on the UI thread and was
     *  then misclassified as link death, tearing the whole call down).
     *  Returns false (and sends nothing) if the transport isn't connected — the caller
     *  should surface that to the user (e.g. a toast); this never triggers teardown. */
    fun sendChat(text: String): Boolean {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_CHAT_PAYLOAD_BYTES) {
            logW("MEDIA: chat message too large (${bytes.size} bytes) — not sending")
            return false
        }
        if (!running.get() || !alive.get()) {
            logW("MEDIA: chat send dropped — not connected")
            return false
        }
        val handler = chatHandler ?: return false
        return handler.post {
            writeFrame(TYPE_CHAT, bytes)
            log("MEDIA: chat sent len=${bytes.size}")
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val ht = HandlerThread("MediaChatWrite").also { it.start() }
        chatThread = ht
        chatHandler = Handler(ht.looper)
        if (isGroupOwner) startAsServer() else startAsClient()
    }

    /** Idempotent — safe to call more than once (explicit hangup racing a link-death
     *  teardown, for example) and safe to call from any thread.
     *  FIX: ordering matters — mic loop, then its encode consumer, then the socket
     *  (which unblocks the read loop's blocked din.readByte()), then the read loop's
     *  decode consumer, then AudioTrack. Camera/encoder/decoder (video) release order
     *  below is unchanged from before this fix. */
    fun stop() {
        running.set(false)
        try { audioRecord?.stop() } catch (_: Exception) {} // unblocks a pending rec.read() promptly
        stopOpusEncodeThread()
        closeSockets()
        releaseCamera()
        releaseEncoder()
        releaseDecoder()
        stopOpusDecodeThread()
        releaseAudio()
        restoreAudioRouting()
        chatThread?.quitSafely()
        chatThread = null
        chatHandler = null
    }

    // ── FIX 2: single-shot link-death teardown ────────────────────────────────

    private fun handleLinkLost(reason: String) {
        if (!alive.compareAndSet(true, false)) return // already handled elsewhere, silent
        logE("MEDIA: link lost — tearing down ($reason)")
        mainHandler.post { onLinkLost?.invoke() }
        stop()
    }

    // ── Wire framing ──────────────────────────────────────────────────────────

    private fun writeFrame(type: Byte, data: ByteArray) {
        if (!alive.get()) return // link already known dead — no-op
        val out = dataOut ?: return
        try {
            synchronized(out) {
                out.writeByte(type.toInt())
                out.writeInt(data.size)
                out.write(data)
                out.flush()
            }
        } catch (e: IOException) {
            // TASK 1: only a genuine I/O failure on the socket streams is link death.
            handleLinkLost("writeFrame: ${e.message}")
        } catch (e: RuntimeException) {
            // TASK 1: a programming error (e.g. NetworkOnMainThreadException from calling
            // writeFrame off the caller's thread) is NOT link death — log loudly and keep
            // the session alive. This used to be caught by a blanket `catch (e: Exception)`
            // and misclassified as a dead link, tearing the whole call down.
            logE("OFFTRACE: MEDIA: programming error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ── Connection setup: one socket, full-duplex, same on both roles ────────

    private fun startAsServer() {
        Thread({
            log("MEDIA: waiting for peer on port $MEDIA_PORT")
            try {
                val srv = ServerSocket(MEDIA_PORT)
                serverSocket = srv
                val client = srv.accept()
                if (!running.get()) { client.close(); return@Thread }
                onSocketConnected(client)
            } catch (e: Exception) {
                if (running.get()) reportError("server socket: ${e.message}")
            }
        }, "MediaServerAccept").start()
    }

    private fun startAsClient() {
        Thread({
            val addr = groupOwnerAddress ?: run { reportError("no group owner address"); return@Thread }
            var attempt = 0
            while (running.get() && attempt < CONNECT_RETRIES) {
                try {
                    log("MEDIA: connecting to $addr:$MEDIA_PORT (attempt ${attempt + 1})")
                    val s = Socket(addr, MEDIA_PORT)
                    onSocketConnected(s)
                    return@Thread
                } catch (e: ConnectException) {
                    attempt++
                    try { Thread.sleep(CONNECT_RETRY_DELAY_MS) } catch (_: InterruptedException) { return@Thread }
                } catch (e: Exception) {
                    attempt++
                    logW("MEDIA: connect attempt $attempt failed: ${e.message}")
                    try { Thread.sleep(CONNECT_RETRY_DELAY_MS) } catch (_: InterruptedException) { return@Thread }
                }
            }
            if (running.get()) reportError("could not connect to peer")
        }, "MediaClientConnect").start()
    }

    /**
     * Called once the single socket is connected (server accept() or client connect()
     * succeeded), on whichever thread did the connecting. Same socket, same code path,
     * on both roles: start the local camera+encoder→socket writer and the socket
     * reader→decoder, so both directions run concurrently full-duplex.
     */
    private fun onSocketConnected(s: Socket) {
        // FIX 4: both accept() and connect() sides converge here — apply once, covers both.
        try {
            s.tcpNoDelay = true
            s.keepAlive = true
            s.soTimeout = SOCKET_READ_TIMEOUT_MS
        } catch (e: Exception) {
            logW("MEDIA: could not set socket options: ${e.message}")
        }
        socket = s
        dataOut = DataOutputStream(s.getOutputStream())
        dataIn = DataInputStream(s.getInputStream())
        log("MEDIA: socket connected — starting read loop")
        // FIX: decode consumer must be ready before the read loop can hand it any
        // TYPE_AUDIO payload — started first, drains opusDecodeQueue independently.
        startOpusDecodeThread()
        Thread({ runReadLoop() }, "MediaReadLoop").start()

        // TASK 2: the initiator already knows the mode (chosen before connecting) —
        // announce it before any media, then start this side's matching sender halves.
        // The callee does neither yet; it waits for the type-5 frame (or back-compat
        // fallback) in the read loop.
        val mode = initialMode
        if (mode != null) {
            log("MEDIA: initiator — announcing mode=$mode")
            writeFrame(TYPE_MODE, byteArrayOf(mode.wireId))
            startSendersForMode(mode)
        }
    }

    /** TASK 2: starts exactly once (guarded by [sendersStarted]) the sender halves that
     *  match [mode] — same decision on both peers, so the call ends up symmetric no
     *  matter which side is the initiator. Chat is not gated here; it's always on. */
    private fun startSendersForMode(mode: CallMode) {
        if (!sendersStarted.compareAndSet(false, true)) return
        resolvedMode = mode
        log("MEDIA: mode resolved -> $mode — starting matching sender halves")
        if (mode == CallMode.VIDEO || mode == CallMode.AUDIO) {
            setupAudioRouting()
            startAudioSender()
        }
        if (mode == CallMode.VIDEO) {
            startEncoderThenCamera()
        }
        mainHandler.post { onModeResolved?.invoke(mode) }
    }

    private fun startEncoderThenCamera() {
        try {
            val enc = MediaCodec.createEncoderByType("video/avc")
            val fmt = MediaFormat.createVideoFormat("video/avc", WIDTH, HEIGHT).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = enc.createInputSurface()
            encoderInputSurface = inputSurface
            enc.start()
            encoder = enc
            log("MEDIA: encoder started")
            Thread({ drainEncoderLoop() }, "MediaEncoderDrain").start()
            openCamera(inputSurface)
        } catch (e: Exception) {
            if (running.get()) reportError("encoder setup: ${e.message}")
        }
    }

    private fun drainEncoderLoop() {
        val enc = encoder ?: return
        val info = MediaCodec.BufferInfo()
        // Accumulate all CODEC_CONFIG buffers (usually SPS + PPS) before sending.
        // They arrive before any real frames, so we batch them into a single type-1
        // message to keep the receiver's decoder configuration simple.
        val pendingCsd = mutableListOf<ByteArray>()
        var csdSent = false
        var frameCount = 0

        while (running.get()) {
            val idx = enc.dequeueOutputBuffer(info, 10_000L)
            if (idx < 0) continue

            val buf = enc.getOutputBuffer(idx)
            if (buf == null) { enc.releaseOutputBuffer(idx, false); continue }

            val bytes = ByteArray(info.size)
            buf.position(info.offset)
            buf.limit(info.offset + info.size)
            buf.get(bytes)
            enc.releaseOutputBuffer(idx, false)

            when {
                info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 -> break

                info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 -> {
                    pendingCsd.add(bytes)
                    log("MEDIA: buffered codec config ${bytes.size} bytes")
                }

                else -> {
                    if (!csdSent && pendingCsd.isNotEmpty()) {
                        val combined = combineByteArrays(pendingCsd)
                        writeFrame(TYPE_CONFIG, combined)
                        csdSent = true
                        log("MEDIA: sent codec config ${combined.size} bytes")
                    }
                    writeFrame(TYPE_FRAME, bytes)
                    frameCount++
                    if (frameCount % 30 == 0) {
                        log("MEDIA: sent frame $frameCount bytes=${bytes.size}")
                    }
                }
            }
        }
        log("MEDIA: encoder drain thread exiting")
    }

    /** FIX: this loop now does ONLY rec.read() plus a non-blocking hand-off to
     *  [opusEncodeQueue] — no MediaCodec call and no socket write happens on this
     *  thread anymore. That work used to run inline here and could steal >5ms of a
     *  20ms budget per chunk; it's now owned entirely by "MediaOpusEncode". A run of
     *  read() failures rebuilds the AudioRecord in place instead of spinning silently
     *  (see MIC_READ_ERROR_REBUILD_THRESHOLD). */
    @SuppressLint("MissingPermission")
    private fun startAudioSender() {
        startOpusEncodeThread()
        Thread({
            try {
                var rec = createAndStartAudioRecord() ?: return@Thread
                audioRecord = rec
                log("MEDIA: audio recorder started")

                val chunk = ByteArray(AUDIO_CHUNK_BYTES)
                var audioFrameCount = 0
                var consecutiveReadErrors = 0
                var zeroReadCount = 0
                var rebuildAttempts = 0
                while (running.get()) {
                    val n = rec.read(chunk, 0, chunk.size)
                    if (n < 0) {
                        consecutiveReadErrors++
                        if (consecutiveReadErrors % MIC_READ_ERROR_LOG_INTERVAL == 0) {
                            logE("OFFTRACE: MEDIA: mic read err=$n")
                        }
                        if (consecutiveReadErrors >= MIC_READ_ERROR_REBUILD_THRESHOLD) {
                            consecutiveReadErrors = 0
                            rebuildAttempts++
                            if (rebuildAttempts > MAX_MIC_REBUILD_ATTEMPTS) {
                                reportError("mic unrecoverable after $MAX_MIC_REBUILD_ATTEMPTS rebuild attempts")
                                return@Thread
                            }
                            logW("OFFTRACE: MEDIA: mic read failing, rebuilding AudioRecord (attempt $rebuildAttempts/$MAX_MIC_REBUILD_ATTEMPTS)")
                            try { rec.stop() } catch (_: Exception) {}
                            try { rec.release() } catch (_: Exception) {}
                            val rebuilt = createAndStartAudioRecord()
                                ?: return@Thread // createAndStartAudioRecord() already reportError'd
                            rec = rebuilt
                            audioRecord = rec
                            log("MEDIA: mic recovered")
                        }
                        continue
                    }
                    if (n == 0) {
                        zeroReadCount++
                        if (zeroReadCount % MIC_ZERO_READ_LOG_INTERVAL == 0) {
                            log("MEDIA: mic read n=0 count=$zeroReadCount")
                        }
                        continue
                    }
                    consecutiveReadErrors = 0
                    zeroReadCount = 0
                    // Always a fresh copy: this array crosses to another thread via the
                    // queue, so the buffer rec.read() reuses next iteration must not
                    // alias it (previously safe only because encode/write happened
                    // synchronously, in-line, before the next read call).
                    val payload = chunk.copyOf(n)
                    audioFrameCount++
                    if (audioFrameCount % 50 == 0) {
                        log("MEDIA: sent audio $audioFrameCount")
                        // Pre-encode: always the raw mic PCM, regardless of codec.
                        log("MEDIA: mic peak=${peakAmplitude(payload)}")
                    }
                    if (!opusEncodeQueue.offer(payload)) {
                        opusEncodeQueue.poll() // drop oldest — realtime audio, never accumulate
                        opusEncodeQueue.offer(payload)
                        opusEncodeDropCount++
                        if (opusEncodeDropCount % DROP_LOG_INTERVAL == 0) {
                            logW("OFFTRACE: MEDIA: enc queue dropped $opusEncodeDropCount")
                        }
                    }
                }
            } catch (e: Exception) {
                if (running.get()) reportError("audio sender: ${e.message}")
            }
        }, "MediaAudioSend").start()
    }

    /** FIX: constructs + starts an AudioRecord, verifying AudioRecord.state after
     *  construction and recordingState after startRecording() rather than assuming
     *  either succeeded — retries once (after AUDIO_RECORD_RETRY_DELAY_MS) at whichever
     *  point fails before giving up. Returns null (having already called reportError)
     *  if the retry also fails; the caller must not proceed. */
    private fun createAndStartAudioRecord(): AudioRecord? {
        repeat(2) { attempt ->
            val rec = buildAudioRecordOrNull() ?: return@repeat
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                logE("OFFTRACE: MEDIA: AudioRecord INIT FAILED state=${rec.state} (attempt ${attempt + 1})")
                try { rec.release() } catch (_: Exception) {}
                if (attempt == 0) {
                    try { Thread.sleep(AUDIO_RECORD_RETRY_DELAY_MS) } catch (_: InterruptedException) { return null }
                }
                return@repeat
            }
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                logE("OFFTRACE: MEDIA: AudioRecord START FAILED recordingState=${rec.recordingState} (attempt ${attempt + 1})")
                try { rec.stop() } catch (_: Exception) {}
                try { rec.release() } catch (_: Exception) {}
                if (attempt == 0) {
                    try { Thread.sleep(AUDIO_RECORD_RETRY_DELAY_MS) } catch (_: InterruptedException) { return null }
                }
                return@repeat
            }
            return rec
        }
        reportError("AudioRecord init/start failed after retry")
        return null
    }

    @SuppressLint("MissingPermission")
    private fun buildAudioRecordOrNull(): AudioRecord? {
        return try {
            val minBuf = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_IN, AUDIO_ENCODING)
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                AUDIO_SAMPLE_RATE,
                AUDIO_CHANNEL_IN,
                AUDIO_ENCODING,
                minBuf * 2
            )
        } catch (e: Exception) {
            logE("OFFTRACE: MEDIA: AudioRecord construction threw: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** FIX: owns every Opus-encoder MediaCodec call, and (per spec) the PCM fallback
     *  writeFrame too, so "MediaAudioSend" never touches the socket either way.
     *  Codec negotiation (probe + type-6 announcement) moved here verbatim from the
     *  old startAudioSender() — same decision, same order, same wire content, just
     *  off the mic-capture thread. */
    private fun startOpusEncodeThread() {
        val t = Thread({
            // OPUS: probe for hardware/software Opus encoder support once, before
            // touching the queue. Never assume the peer has (or lacks) it either —
            // announce whatever we land on via type-6, before any type-3 frame.
            val opusEnc = createOpusEncoderOrNull()
            audioEncoder = opusEnc
            localAudioCodec = if (opusEnc != null) AudioCodec.OPUS else AudioCodec.PCM
            log("OFFTRACE: MEDIA: audio codec=${localAudioCodec.name.lowercase()} negotiated (send)")
            writeFrame(TYPE_AUDIO_CODEC, byteArrayOf(localAudioCodec.wireId))

            try {
                while (running.get()) {
                    val payload = opusEncodeQueue.poll(OPUS_QUEUE_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: continue
                    if (opusEnc != null) {
                        encodeAndSendOpus(opusEnc, payload)
                    } else {
                        writeFrame(TYPE_AUDIO, payload)
                        trackAudioSendBytes(payload.size)
                    }
                }
            } catch (_: InterruptedException) {
                // Expected: stop() interrupts this thread to break out of queue.poll().
            } finally {
                // FIX: released here, on the thread that owns it, only after this loop
                // has definitely stopped touching it — never concurrently with a
                // dequeue call. releaseAudio() still clears audioEncoder defensively
                // as a fallback for the rare case stop()'s join() times out.
                if (opusEnc != null) {
                    try { opusEnc.stop() } catch (_: Exception) {}
                    try { opusEnc.release() } catch (_: Exception) {}
                    audioEncoder = null
                }
            }
        }, "MediaOpusEncode")
        opusEncodeThread = t
        t.start()
    }

    /** OPUS: tries to stand up a MediaCodec Opus encoder; returns null (never throws)
     *  if this device doesn't have one, so the caller can fall back to PCM. */
    private fun createOpusEncoderOrNull(): MediaCodec? {
        return try {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, AUDIO_SAMPLE_RATE, OPUS_CHANNEL_COUNT).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, OPUS_BITRATE)
                setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                // FIX: realtime priority hint — without it, some vendor codec
                // implementations schedule this instance best-effort under contention
                // with the concurrently-running H.264 video codec + camera pipeline.
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            enc
        } catch (e: Exception) {
            logW("MEDIA: opus encoder unavailable (${e.javaClass.simpleName}: ${e.message}) — falling back to PCM")
            null
        }
    }

    /** OPUS: feeds one 20ms raw-PCM chunk into the encoder and writes out whatever
     *  encoded packet(s) it produces as type-3 frames. Same call cadence as the PCM
     *  path (one call per queued chunk) — the encoder may buffer internally, so a
     *  given call can legitimately produce zero or more output packets. Runs on
     *  "MediaOpusEncode" only, never on the mic-capture thread. */
    private fun encodeAndSendOpus(enc: MediaCodec, pcm: ByteArray) {
        try {
            // FIX: now that this runs on its own dedicated thread, the timeout no
            // longer steals budget from a realtime loop — kept small anyway (was
            // 10_000L). On failure to get an input buffer in time, just drop this
            // chunk and count it; realtime audio never accumulates/re-queues.
            val inIdx = enc.dequeueInputBuffer(2_000L)
            if (inIdx >= 0) {
                val buf = enc.getInputBuffer(inIdx)!!
                buf.clear()
                buf.put(pcm)
                enc.queueInputBuffer(inIdx, 0, pcm.size, System.nanoTime() / 1000, 0)
            } else {
                opusEncodeInputDropCount++
                if (opusEncodeInputDropCount % DROP_LOG_INTERVAL == 0) {
                    logW("OFFTRACE: MEDIA: opus encoder input dequeue dropped $opusEncodeInputDropCount")
                }
            }
            val info = MediaCodec.BufferInfo()
            while (true) {
                val outIdx = enc.dequeueOutputBuffer(info, 0)
                if (outIdx < 0) break
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                    val outBuf = enc.getOutputBuffer(outIdx)
                    if (outBuf != null) {
                        val bytes = ByteArray(info.size)
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        outBuf.get(bytes)
                        writeFrame(TYPE_AUDIO, bytes)
                        trackAudioSendBytes(bytes.size)
                    }
                }
                enc.releaseOutputBuffer(outIdx, false)
            }
        } catch (e: Exception) {
            if (running.get()) reportError("opus encode: ${e.message}")
        }
    }

    /** FIX: interrupt + bounded join so a blocked queue.poll() can't hang stop().
     *  The encode MediaCodec is released inside the thread's own finally block (see
     *  startOpusEncodeThread) once join() confirms it's no longer running. */
    private fun stopOpusEncodeThread() {
        val t = opusEncodeThread ?: return
        t.interrupt()
        try { t.join(OPUS_ENCODE_STOP_JOIN_MS) } catch (_: InterruptedException) {}
        opusEncodeThread = null
        opusEncodeQueue.clear()
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(encoderSurface: Surface) {
        val ht = HandlerThread("MediaCameraThread").also { it.start() }
        cameraThread = ht
        val handler = Handler(ht.looper)
        cameraHandler = handler

        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // ── Phase 1: enumerate every sensor and log the full truth ────────────
        // backwardCompat=false identifies depth/aux/logical sensors that can't
        // create a standard capture session (fail with FUNCTION_NOT_IMPLEMENTED).
        // We must exclude those ids regardless of LENS_FACING.
        val frontIds  = mutableListOf<String>()  // LENS_FACING_FRONT + backwardCompat
        val backIds   = mutableListOf<String>()  // LENS_FACING_BACK  + backwardCompat
        val otherIds  = mutableListOf<String>()  // any other facing  + backwardCompat

        for (id in mgr.cameraIdList) {
            val ch = mgr.getCameraCharacteristics(id)
            val facing = ch.get(CameraCharacteristics.LENS_FACING)
            val caps   = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            val backwardCompat = caps?.contains(
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) == true
            log("MEDIA: cam id=$id facing=$facing backwardCompat=$backwardCompat")
            if (!backwardCompat) continue
            when (facing) {
                CameraCharacteristics.LENS_FACING_FRONT -> frontIds.add(id)
                CameraCharacteristics.LENS_FACING_BACK  -> backIds.add(id)
                else                                    -> otherIds.add(id)
            }
        }

        // ── Phase 2: build priority-ordered candidate list ────────────────────
        // front-backwardCompat → back-backwardCompat → any-backwardCompat
        val usableIds: List<String> = frontIds + backIds + otherIds
        if (usableIds.isEmpty()) {
            logE("MEDIA: no usable camera (no backwardCompat sensor found)")
            reportError("no usable camera"); return
        }

        val facingLabel = { id: String ->
            when { id in frontIds -> "front"; id in backIds -> "back"; else -> "other" }
        }
        log("MEDIA: selected cameraId=${usableIds[0]} facing=${facingLabel(usableIds[0])}")

        // ── Phase 3: open with automatic fallback through the candidate list ──
        // Each onError advances to the next usable id so we never give up on a
        // single broken sensor (common on MediaTek devices with aux depth cameras).
        val attemptIdx = intArrayOf(0)
        // FIX 6: ERROR_CAMERA_IN_USE/ERROR_MAX_CAMERAS_IN_USE are usually transient —
        // a just-killed previous process's camera handle the OS hasn't reclaimed yet.
        // Retry the SAME id a few times before giving up on it and moving on.
        val inUseRetryCount = intArrayOf(0)

        @SuppressLint("MissingPermission")
        fun tryOpen() {
            val id = usableIds[attemptIdx[0]]
            log("MEDIA: opening cameraId=$id facing=${facingLabel(id)}")
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    inUseRetryCount[0] = 0
                    cameraDevice = camera
                    startCaptureSession(camera, encoderSurface)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close(); cameraDevice = null
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close(); cameraDevice = null
                    logE("MEDIA: camera onError id=$id code=$error")

                    val transientInUse = error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE ||
                        error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE
                    if (transientInUse && inUseRetryCount[0] < MAX_CAMERA_IN_USE_RETRIES && running.get()) {
                        inUseRetryCount[0]++
                        log("MEDIA: camera in use (code=$error), retrying id=$id in " +
                            "${CAMERA_IN_USE_RETRY_DELAY_MS}ms (${inUseRetryCount[0]}/$MAX_CAMERA_IN_USE_RETRIES)")
                        handler.postDelayed({
                            if (running.get()) {
                                try { tryOpen() } catch (e: Exception) {
                                    if (running.get()) reportError("camera open: ${e.message}")
                                }
                            }
                        }, CAMERA_IN_USE_RETRY_DELAY_MS)
                        return
                    }

                    inUseRetryCount[0] = 0
                    attemptIdx[0]++
                    if (attemptIdx[0] < usableIds.size && running.get()) {
                        logW("MEDIA: trying next candidate " +
                                "(${attemptIdx[0] + 1}/${usableIds.size})")
                        try { tryOpen() } catch (e: Exception) {
                            if (running.get()) reportError("camera open: ${e.message}")
                        }
                    } else {
                        if (running.get()) reportError("camera open failed (id=$id code=$error)")
                    }
                }
            }, handler)
        }

        tryOpen()
    }

    private fun startCaptureSession(camera: CameraDevice, encoderSurface: Surface) {
        try {
            camera.createCaptureSession(
                listOf(encoderSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (!running.get()) { session.close(); return }
                        captureSession = session
                        try {
                            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                .apply { addTarget(encoderSurface) }
                                .build()
                            session.setRepeatingRequest(req, null, cameraHandler)
                            log("MEDIA: camera capture running")
                        } catch (e: Exception) {
                            if (running.get()) reportError("capture request: ${e.message}")
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        reportError("capture session config failed")
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            if (running.get()) reportError("createCaptureSession: ${e.message}")
        }
    }

    // ── Receive path: socket → decoder → Surface (runs on both roles) ─────────

    private fun runReadLoop() {
        val din = dataIn ?: return
        // Accumulate type-1 packets (may be more than one if encoder splits SPS/PPS)
        // and configure the decoder on the first type-2 packet that follows.
        val pendingCsd = mutableListOf<ByteArray>()
        var decoderReady = false
        var frameCount = 0
        var audioFrameCount = 0
        var unknownTypeLogged = false
        // FIX 4: per-iteration try/catch so a SocketTimeoutException (SO_TIMEOUT just
        // firing because nothing arrived within the window — the link itself is fine)
        // loops back around instead of being treated as link death.
        while (alive.get() && running.get()) {
            try {
                val type = din.readByte()
                val length = din.readInt()
                val payload = ByteArray(length)
                din.readFully(payload)

                when (type) {
                    TYPE_CONFIG -> {
                        // TASK 2 back-compat: a media frame arriving before any type-5
                        // control frame means the peer is an older/initiator-less sender
                        // that never announced a mode — assume VIDEO, same as always.
                        if (resolvedMode == null) startSendersForMode(CallMode.VIDEO)
                        pendingCsd.add(payload)
                    }
                    TYPE_FRAME -> {
                        if (resolvedMode == null) startSendersForMode(CallMode.VIDEO)
                        if (!decoderReady) {
                            if (pendingCsd.isEmpty()) continue
                            configureDecoder(combineByteArrays(pendingCsd))
                            pendingCsd.clear()
                            decoderReady = decoder != null
                        }
                        if (decoderReady) {
                            feedDecoder(payload)
                            frameCount++
                            if (frameCount % 30 == 0) log("MEDIA: recv frame $frameCount")
                        }
                    }
                    // FIX: this thread does ZERO codec and ZERO AudioTrack work now — a
                    // queue hand-off only. Both feedOpusAudio (MediaCodec) and
                    // feedAudioTrackPcm (AudioTrack.write, which can itself block on
                    // backpressure) used to run inline here, ahead of the next
                    // TYPE_FRAME's read; both now run on "MediaOpusDecode" instead.
                    TYPE_AUDIO -> {
                        if (resolvedMode == null) startSendersForMode(CallMode.VIDEO)
                        trackAudioRecvBytes(payload.size)
                        if (!opusDecodeQueue.offer(payload)) {
                            opusDecodeQueue.poll() // drop oldest — a lost 20ms of audio is
                            opusDecodeQueue.offer(payload) // inaudible; a stalled video pipe is not.
                            trackDecodeQueueDrop()
                        }
                        audioFrameCount++
                        if (audioFrameCount % 50 == 0) log("MEDIA: recv audio $audioFrameCount")
                    }
                    TYPE_CHAT -> {
                        // TASK 2: chat path is always on, independent of mode resolution.
                        if (payload.size > MAX_CHAT_PAYLOAD_BYTES) {
                            logW("MEDIA: oversized chat frame len=${payload.size} — ignoring")
                        } else {
                            log("MEDIA: chat recv len=${payload.size}")
                            val text = String(payload, Charsets.UTF_8)
                            mainHandler.post { onChatMessage?.invoke(text) }
                        }
                    }
                    TYPE_MODE -> {
                        if (payload.size != 1) {
                            logW("MEDIA: malformed mode control frame len=${payload.size} — ignoring")
                        } else {
                            val mode = CallMode.fromWireId(payload[0])
                            if (mode == null) {
                                logW("MEDIA: unknown mode id=${payload[0]} — ignoring")
                            } else {
                                log("MEDIA: received mode control frame -> $mode")
                                startSendersForMode(mode)
                            }
                        }
                    }
                    TYPE_AUDIO_CODEC -> {
                        if (payload.size != 1) {
                            logW("MEDIA: malformed audio-codec control frame len=${payload.size} — ignoring")
                        } else {
                            val codec = AudioCodec.fromWireId(payload[0])
                            if (codec == null) {
                                logW("MEDIA: unknown audio codec id=${payload[0]} — ignoring")
                            } else {
                                remoteAudioCodec = codec
                                log("OFFTRACE: MEDIA: audio codec=${codec.name.lowercase()} negotiated (recv)")
                            }
                        }
                    }
                    else -> {
                        if (!unknownTypeLogged) {
                            logW("MEDIA: unknown frame type=$type len=${payload.size} — skipping")
                            unknownTypeLogged = true
                        }
                    }
                }
            } catch (e: SocketTimeoutException) {
                // Just a quiet link within the 5s window — not death. Loop condition
                // re-checks alive/running above.
                continue
            } catch (e: IOException) {
                // TASK 1: only a genuine I/O failure on the socket streams is link death.
                handleLinkLost("read loop: ${e.message}")
                break
            } catch (e: RuntimeException) {
                // TASK 1: a programming error here is not link death — log and keep reading.
                logE("OFFTRACE: MEDIA: programming error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun configureDecoder(csd: ByteArray) {
        // The display surface is set on the main thread after the SurfaceView becomes
        // visible. The socket connection + camera open on the peer's side take enough
        // time that the surface is almost always ready by the time its first config
        // packet arrives, but we poll briefly to handle the rare race.
        var waited = 0
        while (displaySurface == null && waited < 3_000 && running.get()) {
            try { Thread.sleep(50) } catch (_: InterruptedException) { return }
            waited += 50
        }
        val surface = displaySurface ?: run { reportError("display surface unavailable"); return }
        try {
            val fmt = MediaFormat.createVideoFormat("video/avc", WIDTH, HEIGHT).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            }
            val dec = MediaCodec.createDecoderByType("video/avc")
            dec.configure(fmt, surface, null, 0)
            dec.start()
            decoder = dec
            log("MEDIA: decoder configured (csd ${csd.size} bytes)")
        } catch (e: Exception) {
            if (running.get()) reportError("decoder configure: ${e.message}")
        }
    }

    private fun feedDecoder(data: ByteArray) {
        val dec = decoder ?: return
        try {
            val idx = dec.dequeueInputBuffer(10_000L)
            if (idx >= 0) {
                val buf = dec.getInputBuffer(idx)!!
                buf.clear()
                buf.put(data)
                dec.queueInputBuffer(idx, 0, data.size, System.nanoTime() / 1000, 0)
            }
            val info = MediaCodec.BufferInfo()
            while (true) {
                val out = dec.dequeueOutputBuffer(info, 0)
                when (out) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> break
                    // TASK 1: the decoder's actual output size (post-crop) is only known
                    // once it reports this — may differ from the encoder's configured
                    // WIDTHxHEIGHT after rotation/crop, and is what the display side
                    // needs to letterbox correctly instead of stretching.
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> handleOutputFormatChanged(dec.outputFormat)
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> { /* deprecated, no-op */ }
                    else -> dec.releaseOutputBuffer(out, true)
                }
            }
        } catch (e: Exception) {
            if (running.get()) reportError("feedDecoder: ${e.message}")
        }
    }

    private fun handleOutputFormatChanged(format: MediaFormat) {
        try {
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            // Crop keys aren't exposed as MediaFormat constants; these are the
            // standard (if informally documented) string keys MediaCodec populates.
            val cropLeft = if (format.containsKey("crop-left")) format.getInteger("crop-left") else 0
            val cropTop = if (format.containsKey("crop-top")) format.getInteger("crop-top") else 0
            val cropRight = if (format.containsKey("crop-right")) format.getInteger("crop-right") else width - 1
            val cropBottom = if (format.containsKey("crop-bottom")) format.getInteger("crop-bottom") else height - 1
            val cropWidth = cropRight - cropLeft + 1
            val cropHeight = cropBottom - cropTop + 1
            log("MEDIA: video size ${cropWidth}x${cropHeight}")
            mainHandler.post { onVideoSize?.invoke(cropWidth, cropHeight) }
        } catch (e: Exception) {
            if (running.get()) reportError("output format changed: ${e.message}")
        }
    }

    /** OPUS: the single sink for linear PCM regardless of where it came from — straight
     *  off the wire (PCM codec, always AUDIO_SAMPLE_RATE/AUDIO_CHANNEL_OUT) or just out
     *  of [feedOpusAudio]'s decoder (Opus codec, whatever rate/channels it actually
     *  reported). Peak logging lives here so it's always post-decode, never on
     *  compressed bytes. */
    private fun feedAudioTrackPcm(data: ByteArray, sampleRate: Int, channelConfig: Int) {
        val track = ensureAudioTrack(sampleRate, channelConfig) ?: return
        try {
            val written = track.write(data, 0, data.size)
            if (written <= 0) {
                audioWriteErrCount++
                // Once per 50 to avoid spam under a sustained failure.
                if (audioWriteErrCount % 50 == 1) {
                    logE("MEDIA: audioTrack write err=$written")
                }
            }
            audioTrackWriteCount++
            if (audioTrackWriteCount % 50 == 0) {
                log("MEDIA: spk peak=${peakAmplitude(data)}")
            }
        } catch (e: Exception) {
            if (running.get()) reportError("audioTrack write: ${e.message}")
        }
    }

    /** FIX: owns both feedOpusAudio (MediaCodec) and feedAudioTrackPcm (AudioTrack,
     *  which can itself block on backpressure) for BOTH audio codecs — the read loop
     *  hands off raw wire bytes here and does no codec/AudioTrack work of its own. */
    private fun startOpusDecodeThread() {
        val t = Thread({
            try {
                while (running.get()) {
                    val payload = opusDecodeQueue.poll(OPUS_QUEUE_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: continue
                    // OPUS: never assume — if this direction's codec hasn't been announced
                    // yet (no type-6 seen), back-compat assume PCM, same as the pre-Opus wire.
                    when (remoteAudioCodec ?: AudioCodec.PCM) {
                        AudioCodec.OPUS -> feedOpusAudio(payload)
                        // FIX: PCM fallback path keeps building the AudioTrack at the
                        // fixed wire-format constants, as before — only the Opus path's
                        // actual (variable) decoder output rate needs to be discovered.
                        AudioCodec.PCM -> feedAudioTrackPcm(payload, AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_OUT)
                    }
                }
            } catch (_: InterruptedException) {
                // Expected: stop() interrupts this thread to break out of queue.poll().
            } finally {
                // FIX: released here, on the thread that owns it, only after this loop
                // has definitely stopped touching it. releaseAudio() still clears
                // audioDecoder defensively as a fallback for the rare case stop()'s
                // join() times out.
                val dec = audioDecoder
                if (dec != null) {
                    try { dec.stop() } catch (_: Exception) {}
                    try { dec.release() } catch (_: Exception) {}
                    audioDecoder = null
                }
            }
        }, "MediaOpusDecode")
        opusDecodeThread = t
        t.start()
    }

    /** FIX: interrupt + bounded join so a blocked queue.poll() can't hang stop().
     *  The decode MediaCodec is released inside the thread's own finally block (see
     *  startOpusDecodeThread) once join() confirms it's no longer running. */
    private fun stopOpusDecodeThread() {
        val t = opusDecodeThread ?: return
        t.interrupt()
        try { t.join(OPUS_DECODE_STOP_JOIN_MS) } catch (_: InterruptedException) {}
        opusDecodeThread = null
        opusDecodeQueue.clear()
    }

    /** OPUS: feeds one received Opus packet into the (lazily configured) decoder and
     *  hands whatever linear PCM comes out to [feedAudioTrackPcm]. Mirrors the video
     *  feedDecoder() shape but with no Surface/display-race concerns — the decoder's
     *  csd is fixed and known locally, so it configures immediately on first use. Runs
     *  on "MediaOpusDecode" only, never on the shared socket read loop.
     *  FIX: now handles INFO_OUTPUT_FORMAT_CHANGED — the previous "if (outIdx < 0)
     *  break" swallowed that event outright, so the decoder's ACTUAL output sample
     *  rate (commonly 48000, not the 16000 we configured it with) was never read; the
     *  AudioTrack was then built at the wrong rate, playing 3x slower than realtime. */
    private fun feedOpusAudio(data: ByteArray) {
        var dec = audioDecoder
        if (dec == null) {
            dec = configureOpusAudioDecoder() ?: return
            audioDecoder = dec
        }
        try {
            val inIdx = dec.dequeueInputBuffer(10_000L)
            if (inIdx >= 0) {
                val buf = dec.getInputBuffer(inIdx)!!
                buf.clear()
                buf.put(data)
                dec.queueInputBuffer(inIdx, 0, data.size, System.nanoTime() / 1000, 0)
            }
            val info = MediaCodec.BufferInfo()
            while (true) {
                val outIdx = dec.dequeueOutputBuffer(info, 0)
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> handleOpusOutputFormatChanged(dec.outputFormat)
                    outIdx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> { /* deprecated, no-op */ }
                    outIdx >= 0 -> {
                        if (info.size > 0) {
                            val outBuf = dec.getOutputBuffer(outIdx)
                            if (outBuf != null) {
                                val pcm = ByteArray(info.size)
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                outBuf.get(pcm)
                                feedAudioTrackPcm(pcm, opusOutputSampleRate, channelCountToOutConfig(opusOutputChannelCount))
                            }
                        }
                        dec.releaseOutputBuffer(outIdx, false)
                    }
                }
            }
        } catch (e: Exception) {
            if (running.get()) reportError("opus decode: ${e.message}")
        }
    }

    /** FIX: Android's Opus decoder reports its ACTUAL output rate/channel count here —
     *  not necessarily what we configured it with (AUDIO_SAMPLE_RATE). Captured so
     *  [feedAudioTrackPcm]'s AudioTrack matches reality instead of assuming constants. */
    private fun handleOpusOutputFormatChanged(format: MediaFormat) {
        val rate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else {
            opusOutputSampleRate
        }
        val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else {
            opusOutputChannelCount
        }
        log("OFFTRACE: MEDIA: opus decoder out rate=$rate ch=$channels")
        opusOutputSampleRate = rate
        opusOutputChannelCount = channels
    }

    private fun channelCountToOutConfig(channelCount: Int): Int = when (channelCount) {
        1 -> AudioFormat.CHANNEL_OUT_MONO
        2 -> AudioFormat.CHANNEL_OUT_STEREO
        else -> AudioFormat.CHANNEL_OUT_MONO
    }

    /** OPUS: builds the decoder's fixed csd-0/1/2 locally — see the header comment on
     *  OPUS_PRE_SKIP_SAMPLES_48K/OPUS_SEEK_PREROLL_NS for why these are 0 here, not
     *  something we'd otherwise receive from the peer or a container. */
    private fun configureOpusAudioDecoder(): MediaCodec? {
        return try {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, AUDIO_SAMPLE_RATE, OPUS_CHANNEL_COUNT).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(buildOpusIdHeader()))
                setByteBuffer("csd-1", ByteBuffer.wrap(buildOpusCsd1()))
                setByteBuffer("csd-2", ByteBuffer.wrap(buildOpusCsd2()))
                // FIX: realtime priority hint — see the matching comment on the encoder side.
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            dec.configure(fmt, null, null, 0)
            dec.start()
            log("MEDIA: opus audio decoder configured")
            dec
        } catch (e: Exception) {
            if (running.get()) reportError("opus audio decoder configure: ${e.message}")
            null
        }
    }

    /** OPUS csd-0: the Ogg Opus "Identification Header" (RFC 7845 §5.1), 19 bytes for
     *  mono/stereo (channel mapping family 0, no mapping table). Implemented field-by-
     *  field per the spec and Android's documented MediaCodec Opus decoder contract —
     *  this exact layout is the well-known failure point for MediaCodec Opus decode. */
    private fun buildOpusIdHeader(): ByteArray {
        val buf = ByteBuffer.allocate(OPUS_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("OpusHead".toByteArray(Charsets.US_ASCII)) // magic, 8 bytes
        buf.put(1) // version
        buf.put(OPUS_CHANNEL_COUNT.toByte()) // channel count
        buf.putShort(OPUS_PRE_SKIP_SAMPLES_48K.toShort()) // pre-skip, uint16 LE, at 48kHz
        buf.putInt(AUDIO_SAMPLE_RATE) // input sample rate, uint32 LE — informational only
        buf.putShort(0) // output gain
        buf.put(0) // channel mapping family 0 = mono/stereo, no mapping table follows
        return buf.array()
    }

    /** OPUS csd-1: codec delay in NANOSECONDS as an 8-byte native-endian long, per
     *  Android's MediaCodec Opus decoder contract — NOT the same unit as the header's
     *  pre-skip field (samples), which is why this is computed rather than reused raw. */
    private fun buildOpusCsd1(): ByteArray {
        val delayNs = OPUS_PRE_SKIP_SAMPLES_48K.toLong() * 1_000_000_000L / 48_000L
        return ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(delayNs).array()
    }

    /** OPUS csd-2: seek pre-roll in NANOSECONDS as an 8-byte native-endian long. */
    private fun buildOpusCsd2(): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(OPUS_SEEK_PREROLL_NS).array()

    /** OPUS: dev-time bandwidth instrumentation (see field comments) — logs the actual
     *  audio payload bytes/sec on the wire every ~5s so the PCM->Opus reduction is
     *  directly observable rather than inferred. */
    private fun trackAudioSendBytes(n: Int) {
        val now = System.currentTimeMillis()
        if (audioSendWindowStartMs == 0L) audioSendWindowStartMs = now
        audioSendBytesAccum += n
        val elapsed = now - audioSendWindowStartMs
        if (elapsed >= AUDIO_BYTES_LOG_WINDOW_MS) {
            log("OFFTRACE: MEDIA: audio send bytes/sec=${audioSendBytesAccum * 1000 / elapsed}")
            audioSendBytesAccum = 0L
            audioSendWindowStartMs = now
        }
    }

    private fun trackAudioRecvBytes(n: Int) {
        val now = System.currentTimeMillis()
        if (audioRecvWindowStartMs == 0L) audioRecvWindowStartMs = now
        audioRecvBytesAccum += n
        val elapsed = now - audioRecvWindowStartMs
        if (elapsed >= AUDIO_BYTES_LOG_WINDOW_MS) {
            log("OFFTRACE: MEDIA: audio recv bytes/sec=${audioRecvBytesAccum * 1000 / elapsed}")
            audioRecvBytesAccum = 0L
            audioRecvWindowStartMs = now
        }
    }

    /** FIX: counts decode-queue drops (lifetime total, for context) but only WARNS if
     *  more than DROP_WARN_THRESHOLD happen within a DROP_WARN_WINDOW_MS window — a
     *  healthy call should sit at ~0 drops, so a cumulative "every Nth ever" trigger
     *  would either fire too late (rare drops taking forever to add up) or not reflect
     *  an actual ongoing problem. */
    private fun trackDecodeQueueDrop() {
        opusDecodeDropCount++
        val now = System.currentTimeMillis()
        if (decodeDropWindowStartMs == 0L) decodeDropWindowStartMs = now
        decodeDropWindowCount++
        val elapsed = now - decodeDropWindowStartMs
        if (elapsed >= DROP_WARN_WINDOW_MS) {
            if (decodeDropWindowCount > DROP_WARN_THRESHOLD) {
                logW("OFFTRACE: MEDIA: dec queue dropped $decodeDropWindowCount times in ${elapsed}ms (total=$opusDecodeDropCount)")
            }
            decodeDropWindowCount = 0
            decodeDropWindowStartMs = now
        }
    }

    /** FIX: returns the current AudioTrack if it already matches (sampleRate,
     *  channelConfig); otherwise tears down whatever's there (if anything) and builds
     *  one that does. Called on every write — cheap when nothing changed (a couple of
     *  field reads), only actually rebuilds on first use or on a genuine format change. */
    private fun ensureAudioTrack(sampleRate: Int, channelConfig: Int): AudioTrack? {
        val existing = audioTrack
        if (existing != null && existing.sampleRate == sampleRate && existing.channelConfiguration == channelConfig) {
            return existing
        }
        if (existing != null) {
            try { existing.stop() } catch (_: Exception) {}
            try { existing.release() } catch (_: Exception) {}
            audioTrack = null
        }
        return createAudioTrack(sampleRate, channelConfig)
    }

    private fun createAudioTrack(sampleRate: Int, channelConfig: Int): AudioTrack? {
        return try {
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AUDIO_ENCODING)
            val track = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(AUDIO_ENCODING)
                    .build(),
                minBuf * 2,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            track.play()
            audioTrack = track
            log("MEDIA: audio track created and playing (rate=$sampleRate channelConfig=$channelConfig)")
            track
        } catch (e: Exception) {
            if (running.get()) reportError("audioTrack create: ${e.message}")
            null
        }
    }

    // ── Shared util ───────────────────────────────────────────────────────────

    // CASE B2 instrumentation: max abs value of the 16-bit signed little-endian PCM
    // samples in a chunk. Distinguishes "dead mic sending silence" (peak~0) from
    // "audio is fine, something downstream/routing is wrong" (peak high, nothing heard).
    private fun peakAmplitude(data: ByteArray): Int {
        var peak = 0
        var i = 0
        while (i + 1 < data.size) {
            val lo = data[i].toInt() and 0xFF
            val hi = data[i + 1].toInt() and 0xFF
            val sample = ((hi shl 8) or lo).toShort().toInt()
            val abs = kotlin.math.abs(sample)
            if (abs > peak) peak = abs
            i += 2
        }
        return peak
    }

    private fun combineByteArrays(parts: List<ByteArray>): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var offset = 0
        for (p in parts) { p.copyInto(out, offset); offset += p.size }
        return out
    }

    // ── Teardown ──────────────────────────────────────────────────────────────

    private fun closeSockets() {
        try { socket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        socket = null; serverSocket = null; dataIn = null; dataOut = null
    }

    private fun releaseCamera() {
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }

    private fun releaseEncoder() {
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        encoder = null
        try { encoderInputSurface?.release() } catch (_: Exception) {}
        encoderInputSurface = null
    }

    private fun releaseDecoder() {
        try { decoder?.stop() } catch (_: Exception) {}
        try { decoder?.release() } catch (_: Exception) {}
        decoder = null
    }

    private fun releaseAudio() {
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
        try { audioEncoder?.stop() } catch (_: Exception) {}
        try { audioEncoder?.release() } catch (_: Exception) {}
        audioEncoder = null
        try { audioDecoder?.stop() } catch (_: Exception) {}
        try { audioDecoder?.release() } catch (_: Exception) {}
        audioDecoder = null
    }

    // FIX 1: USAGE_VOICE_COMMUNICATION alone routes to the earpiece at the voice-call
    // volume — inaudible unless something explicitly asks for the speaker. Applied once
    // when the audio paths start; restored on stop() regardless of how it ends.
    private fun setupAudioRouting() {
        try {
            previousAudioMode = audioManager.mode
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val speaker = audioManager.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker != null) {
                    audioManager.setCommunicationDevice(speaker)
                } else {
                    logW("MEDIA: no TYPE_BUILTIN_SPEAKER communication device available")
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = true
            }
            audioRoutingApplied = true
            log("MEDIA: audio routed to speaker, mode=IN_COMMUNICATION")
        } catch (e: Exception) {
            if (running.get()) reportError("audio routing setup: ${e.message}")
        }
    }

    private fun restoreAudioRouting() {
        if (!audioRoutingApplied) return
        audioRoutingApplied = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
            }
            audioManager.mode = previousAudioMode
        } catch (_: Exception) {
            // best-effort restore on teardown — nothing more useful to do here
        }
    }

    // ── Logging ───────────────────────────────────────────────────────────────
    // FIX 1: every log here used to be tagged only "OfflineMediaTransport", invisible
    // to captures filtered on "OFFTRACE" like the rest of this codebase. Duplicate
    // everything so it shows up in both.

    private fun log(msg: String) {
        Log.d(TAG, msg)
        Log.d("OFFTRACE", msg)
    }

    private fun logW(msg: String) {
        Log.w(TAG, msg)
        Log.w("OFFTRACE", msg)
    }

    private fun logE(msg: String) {
        Log.e(TAG, msg)
        Log.e("OFFTRACE", msg)
    }

    private fun reportError(msg: String) {
        logE(msg)
        mainHandler.post { onError(msg) }
    }
}
