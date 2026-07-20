package com.opencall.relay.offline

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.DataChannel
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Self-contained, audio-only WebRTC session for the offline Wi-Fi-Direct
 * call. Independent of RelayService's PeerConnectionFactory/ADM — this one
 * uses an empty ICE server list (host candidates only, no STUN/TURN) and
 * the default JavaAudioDeviceModule (hardware AEC/NS left on, no GSM
 * bridge callbacks).
 */
class OfflineCallManager(private val appContext: Context) {

    companion object {
        private const val TAG = "OfflineCallManager"
    }

    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var adm: JavaAudioDeviceModule? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var signaling: LocalSignaling? = null
    private var isOfferer = false

    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var localSink: VideoSink? = null
    private var remoteSink: VideoSink? = null

    /** Reports PeerConnection ICE/connection state changes, e.g. "CONNECTED", "FAILED". */
    var onStateChange: ((String) -> Unit)? = null

    /** Reports a non-fatal setup failure (camera/track/SDP) so the caller can surface it instead of crashing. */
    var onError: ((String) -> Unit)? = null

    private var started = false

    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false

    private fun logSdp(label: String, sdp: String) {
        val vid = sdp.lines().filter { it.startsWith("m=video") || it.trim().startsWith("a=sendrecv") || it.trim().startsWith("a=recvonly") || it.trim().startsWith("a=sendonly") || it.trim().startsWith("a=inactive") }
        Log.d("SDPTRACE", "$label | m=video & directions: $vid")
        Log.d("SDPTRACE", "$label FULL:\n$sdp")
    }

    val eglBaseContext: EglBase.Context get() = eglBase!!.eglBaseContext

    fun setLocalSink(sink: VideoSink) {
        localSink = sink
        localVideoTrack?.addSink(sink)
    }

    fun setRemoteSink(sink: VideoSink) {
        remoteSink = sink
        remoteVideoTrack?.addSink(sink)
    }

    fun start(isGroupOwner: Boolean, signaling: LocalSignaling) {
        Log.d("OFFTRACE", "pcm: isOfferer=${!isGroupOwner} isGroupOwner=$isGroupOwner")
        if (started) {
            Log.w("OFFTRACE", "duplicate start blocked")
            return
        }
        started = true
        remoteDescriptionSet = false
        pendingRemoteCandidates.clear()

        this.signaling = signaling
        // Group owner answers, the connecting client offers.
        this.isOfferer = !isGroupOwner

        initWebRtc()
        signaling.onMessage = { msg -> handleSignal(msg) }
        setupPeerConnection()

        if (isOfferer) createOffer()
    }

    private fun initWebRtc() {
        if (factory != null) return
        try {
            val egl = EglBase.create()
            eglBase = egl
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(appContext)
                    .setEnableInternalTracer(false)
                    .setFieldTrials("WebRTC-IPv6Default/Disabled/WebRTC-BindUsingInterfaceName/Enabled/")
                    .createInitializationOptions()
            )
            val module = JavaAudioDeviceModule.builder(appContext).createAudioDeviceModule()
            adm = module
            val options = PeerConnectionFactory.Options()
            options.networkIgnoreMask = 0
            factory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setAudioDeviceModule(module)
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .createPeerConnectionFactory()

            Log.d(TAG, "Offline PeerConnectionFactory ready (default ADM, no GSM bridge)")
            Log.d("OFFTRACE", "step ok: factoryBuild")
        } catch (e: Throwable) {
            Log.e("OfflineCall", "setup failed", e)
            Log.e("OFFTRACE", "FATAL factoryBuild: ${e.javaClass.simpleName}: ${e.message}")
            onError?.invoke(e.message ?: e.toString())
        }
    }

    private fun setupPeerConnection() {
        try {
            setupPeerConnectionInternal()
        } catch (e: Throwable) {
            Log.e("OfflineCall", "setup failed", e)
            Log.e("OFFTRACE", "FATAL createPeerConnection: ${e.javaClass.simpleName}: ${e.message}")
            onError?.invoke(e.message ?: e.toString())
        }
    }

    private fun setupPeerConnectionInternal() {
        val config = PeerConnection.RTCConfiguration(emptyList<PeerConnection.IceServer>())
        config.disableIPv6OnWifi = true
        peerConnection = factory?.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                Log.d("OFFTRACE", "sig-> ice " + c.sdp)
                signaling?.send(JSONObject().apply {
                    put("type", "ice")
                    put("candidate", c.sdp)
                    put("sdpMid", c.sdpMid)
                    put("sdpMLineIndex", c.sdpMLineIndex)
                })
            }
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE state: $s")
                mainHandler.post { onStateChange?.invoke(s?.name ?: "UNKNOWN") }
            }
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState?) {
                Log.d(TAG, "PC state: $s")
                mainHandler.post { onStateChange?.invoke(s?.name ?: "UNKNOWN") }
            }
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(d: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                val t = receiver?.track()
                if (t is VideoTrack) {
                    remoteVideoTrack = t
                    mainHandler.post { remoteSink?.let { t.addSink(it) } }
                }
            }
        })
        Log.d("OFFTRACE", "step ok: createPeerConnection")

        setupLocalAudio()
        setupLocalVideo()
    }

    private fun setupLocalAudio() {
        try {
            setupLocalAudioInternal()
        } catch (e: Throwable) {
            Log.e("OfflineCall", "setup failed", e)
            Log.e("OFFTRACE", "FATAL audioSetup: ${e.javaClass.simpleName}: ${e.message}")
            onError?.invoke(e.message ?: e.toString())
        }
    }

    private fun setupLocalAudioInternal() {
        // FIX 7: audio is owned by OfflineMediaTransport now (AudioRecord -> type-3 PCM
        // frames -> AudioTrack, over the same 8889 socket). Keeping this WebRTC audio
        // track alive would just fight it for the microphone. Re-enable only if that
        // path is ever removed — same treatment as setupLocalVideoInternal below.
        Log.d("OFFTRACE", "webrtc audio disabled (media transport owns audio)")
        return

        @Suppress("UNREACHABLE_CODE")
        val source = factory?.createAudioSource(MediaConstraints())
        localAudioTrack = factory?.createAudioTrack("audio0", source)
        localAudioTrack?.setEnabled(true)
        peerConnection?.addTrack(localAudioTrack, listOf("offline_s0"))
        Log.d("OFFTRACE", "step ok: audioSetup")
    }

    private fun setupLocalVideo() {
        try {
            setupLocalVideoInternal()
        } catch (e: Throwable) {
            Log.e("OfflineCall", "setup failed", e)
            Log.e("OFFTRACE", "FATAL videoSetup: ${e.javaClass.simpleName}: ${e.message}")
            onError?.invoke(e.message ?: e.toString())
        }
    }

    private fun setupLocalVideoInternal() {
        // Camera is owned by OfflineMediaTransport (Camera2 direct pipeline).
        // Opening a second capturer here conflicts with that open handle on
        // single-sensor devices. Re-enable once WebRTC video is fully removed.
        Log.d("OFFTRACE", "webrtc video disabled (media transport owns camera)")
        return

        @Suppress("UNREACHABLE_CODE")
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "OfflineCall: camera not granted, audio-only")
            return
        }

        val egl = eglBase ?: return
        val enumerator = Camera2Enumerator(appContext)
        val deviceNames = enumerator.deviceNames
        val name = deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: deviceNames.firstOrNull()
        if (name == null) {
            Log.w(TAG, "OfflineCall: no camera available, audio-only")
            return
        }
        val capturer = enumerator.createCapturer(name, null)
        if (capturer == null) {
            Log.w(TAG, "OfflineCall: no usable camera, audio-only")
            return
        }
        videoCapturer = capturer

        val helper = SurfaceTextureHelper.create("CaptureThread", egl.eglBaseContext)
        surfaceTextureHelper = helper

        val source = factory?.createVideoSource(false) ?: return
        videoSource = source
        capturer.initialize(helper, appContext, source.capturerObserver)
        capturer.startCapture(1280, 720, 30)

        val track = factory?.createVideoTrack("video0", source) ?: return
        localVideoTrack = track
        track.setEnabled(true)
        localSink?.let { track.addSink(it) }
        peerConnection?.addTrack(track, listOf("offline_s0"))
        Log.d("OFFTRACE", "step ok: videoSetup")
    }

    private fun createOffer() {
        try {
            peerConnection?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription) {
                    logSdp("offer-created(local)", desc.description)
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() { logSdp("offer-setLocal", desc.description); sendSdp("sdp_offer", desc) }
                        override fun onSetFailure(e: String?) {
                            Log.e(TAG, "setLocal(offer) failed: $e")
                            Log.e("OFFTRACE", "setLocal(offer) failed: $e")
                        }
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, desc)
                }
                override fun onCreateFailure(e: String?) {
                    Log.e(TAG, "createOffer failed: $e")
                    Log.e("OFFTRACE", "createOffer failed: $e")
                }
                override fun onSetSuccess() {}
                override fun onSetFailure(p0: String?) {}
            }, MediaConstraints())
            Log.d("OFFTRACE", "step ok: createOffer")
        } catch (e: Throwable) {
            Log.e("OfflineCall", "setup failed", e)
            Log.e("OFFTRACE", "FATAL createOffer: ${e.javaClass.simpleName}: ${e.message}")
            onError?.invoke(e.message ?: e.toString())
        }
    }

    private fun createAnswer() {
        try {
            peerConnection?.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription) {
                    logSdp("answer-created(local)", desc.description)
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() { logSdp("answer-setLocal", desc.description); sendSdp("sdp_answer", desc) }
                        override fun onSetFailure(e: String?) {
                            Log.e(TAG, "setLocal(answer) failed: $e")
                            Log.e("OFFTRACE", "setLocal(answer) failed: $e")
                        }
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, desc)
                }
                override fun onCreateFailure(e: String?) {
                    Log.e(TAG, "createAnswer failed: $e")
                    Log.e("OFFTRACE", "createAnswer failed: $e")
                }
                override fun onSetSuccess() {}
                override fun onSetFailure(p0: String?) {}
            }, MediaConstraints())
            Log.d("OFFTRACE", "step ok: createAnswer")
        } catch (e: Throwable) {
            Log.e("OfflineCall", "setup failed", e)
            Log.e("OFFTRACE", "FATAL createAnswer: ${e.javaClass.simpleName}: ${e.message}")
            onError?.invoke(e.message ?: e.toString())
        }
    }

    private fun sendSdp(type: String, desc: SessionDescription) {
        signaling?.send(JSONObject().apply {
            put("type", type)
            put("sdp", JSONObject().apply {
                put("type", desc.type.canonicalForm())
                put("sdp", desc.description)
            })
        })
    }

    private fun handleSignal(msg: JSONObject) {
        when (msg.optString("type")) {
            "sdp_offer" -> {
                val sdpObj = msg.getJSONObject("sdp")
                val desc = SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(sdpObj.getString("type")),
                    sdpObj.getString("sdp")
                )
                peerConnection?.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        logSdp("offer-setRemote", desc.description)
                        remoteDescriptionSet = true
                        Log.d("OFFTRACE", "ice flush count=" + pendingRemoteCandidates.size + " remoteDescSet=" + remoteDescriptionSet)
                        pendingRemoteCandidates.forEach { peerConnection?.addIceCandidate(it) }
                        pendingRemoteCandidates.clear()
                        createAnswer()
                    }
                    override fun onSetFailure(e: String?) {
                        Log.e(TAG, "setRemote(offer) failed: $e")
                        Log.e("OFFTRACE", "setRemote(offer) failed: $e")
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, desc)
            }
            "sdp_answer" -> {
                val sdpObj = msg.getJSONObject("sdp")
                val desc = SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(sdpObj.getString("type")),
                    sdpObj.getString("sdp")
                )
                peerConnection?.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        logSdp("answer-setRemote", desc.description)
                        remoteDescriptionSet = true
                        Log.d("OFFTRACE", "ice flush count=" + pendingRemoteCandidates.size + " remoteDescSet=" + remoteDescriptionSet)
                        pendingRemoteCandidates.forEach { peerConnection?.addIceCandidate(it) }
                        pendingRemoteCandidates.clear()
                        Log.d(TAG, "remote answer set")
                    }
                    override fun onSetFailure(e: String?) {
                        Log.e(TAG, "setRemote(answer) failed: $e")
                        Log.e("OFFTRACE", "setRemote(answer) failed: $e")
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, desc)
            }
            "ice" -> {
                try {
                    val candidate = IceCandidate(
                        msg.getString("sdpMid"),
                        msg.getInt("sdpMLineIndex"),
                        msg.getString("candidate")
                    )
                    Log.d("OFFTRACE", "sig<- ice " + candidate.sdp)
                    if (remoteDescriptionSet) {
                        Log.d("OFFTRACE", "ice apply now " + candidate.sdp)
                        peerConnection?.addIceCandidate(candidate)
                    } else {
                        Log.d("OFFTRACE", "ice queued " + candidate.sdp)
                        pendingRemoteCandidates.add(candidate)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "addIceCandidate failed: ${e.message}")
                    Log.e("OFFTRACE", "addIceCandidate failed: ${e.message}")
                }
            }
        }
    }

    fun hangup() {
        try { localAudioTrack?.setEnabled(false) } catch (e: Exception) {}
        try { localAudioTrack?.dispose() } catch (e: Exception) {}
        localAudioTrack = null
        try { videoCapturer?.stopCapture() } catch (e: Exception) {}
        try { videoCapturer?.dispose() } catch (e: Exception) {}
        videoCapturer = null
        try { localVideoTrack?.let { localSink?.let { sink -> it.removeSink(sink) } } } catch (e: Exception) {}
        try { localVideoTrack?.dispose() } catch (e: Exception) {}
        localVideoTrack = null
        try { remoteVideoTrack?.let { remoteSink?.let { sink -> it.removeSink(sink) } } } catch (e: Exception) {}
        remoteVideoTrack = null
        try { videoSource?.dispose() } catch (e: Exception) {}
        videoSource = null
        try { surfaceTextureHelper?.dispose() } catch (e: Exception) {}
        surfaceTextureHelper = null
        localSink = null
        remoteSink = null
        remoteDescriptionSet = false
        pendingRemoteCandidates.clear()
        try { peerConnection?.close() } catch (e: Exception) {}
        peerConnection = null
        try { factory?.dispose() } catch (e: Exception) {}
        factory = null
        try { adm?.release() } catch (e: Exception) {}
        adm = null
        try { eglBase?.release() } catch (e: Exception) {}
        eglBase = null
        signaling?.onMessage = null
        signaling = null
    }
}
