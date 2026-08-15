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
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.BindException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * TWO-WAY video transport: each device's camera → the other device's screen,
 * full-duplex over TCP (port 8889). Each side runs both a Camera2 → MediaCodec
 * H.264 encoder → socket write path, and a socket read → MediaCodec decoder →
 * Surface path. Completely independent of WebRTC.
 *
 * PHASE 3 — GO AS SWITCHBOARD: this class now has two layers, where Phase 1/2 had
 * only one:
 *   1) MESH LAYER (join the group, stays up for the whole session): the Group Owner
 *      no longer accepts one client and stops — [startAsServer] loops, accepting
 *      every member of the WiFi Direct group (~8) as its own [PeerLink] (see
 *      RoutingTable.kt), each with its own read-loop thread. A client still makes
 *      ONE connect() to the GO, same as always. Every accepted/connected link
 *      exchanges a TYPE_HELLO (now carrying a display name, not just a node id) and
 *      is then routable by node id; joins/leaves are announced mesh-wide via a
 *      TYPE_ROSTER broadcast (see [broadcastRoster]).
 *   2) CALL LAYER (ephemeral, 1:1, layered on top): a "call" is no longer this
 *      object's entire lifetime — it's a resettable sub-session addressed to
 *      whichever roster member the local user tapped ([placeCall]) or whoever's
 *      TYPE_MODE frame just arrived (handleModeFrame). [endLocalCallState] tears
 *      down just the camera/mic/decoder/audio pipeline for that one call, leaving
 *      the mesh (roster, other links) untouched, so a fresh [placeCall] can follow
 *      immediately. Exactly one call may be active at a time (v1 — see class docs
 *      on TYPE_MODE/TYPE_BUSY below); a second inbound TYPE_MODE while busy gets a
 *      TYPE_BUSY reply instead of being accepted.
 *
 * FORWARDING (the GO's core new job): every frame's dst is resolved by
 * [routeFrame] — dst == self or BROADCAST is handled locally (and, on the GO,
 * BROADCAST is also fanned out to every other link); any other dst is looked up
 * O(1) in the RoutingTable and the raw frame bytes are written straight through
 * ([forwardUnicast]/[forwardBroadcast]) — no codec or media work ever happens on
 * that path, so the GO never decodes a call it isn't a party to. Each PeerLink
 * owns a bounded send queue + dedicated writer thread (see PeerLink in
 * RoutingTable.kt), so one slow/dead peer can never stall forwarding to anyone
 * else.
 *
 * WIRE ENVELOPE (v3, breaking change from Phase 2's v2, no interop):
 *   [1B ver=0x03][8B srcId][8B dstId][1B ttl][1B type][4B BE len][payload]
 * Payload types:
 *   type 1 = codec config (SPS + PPS, sent once before the first video frame)
 *   type 2 = video access unit (H.264 keyframe or delta)
 *   type 3 = audio chunk, 20ms @ 16kHz mono — raw PCM 16-bit or Opus, per whichever
 *            [AudioCodec] that direction's sender announced via its own type-6 frame
 *   type 4 = chat message (UTF-8, capped at MAX_CHAT_PAYLOAD_BYTES); dst=BROADCAST is
 *            group chat (many-to-many), dst=a specific node id is direct 1:1 chat —
 *            always on, independent of any active call
 *   type 5 = MODE — call proposal (one byte [CallMode] id), dst=the callee. Accepted
 *            implicitly (starts the call, symmetric sender halves on both sides)
 *            unless the callee already has a DIFFERENT active call, in which case it
 *            replies with type 9 (BUSY) instead. v1 media calls are 1:1 only, even
 *            when relayed through the GO — see class doc above.
 *   type 6 = audio codec control (one byte [AudioCodec] id), sent before that side's
 *            first type-3 frame so the receiver knows how to decode it
 *   type 7 = HELLO — 8B nodeId + 1B protocol version + 1B nameLen + name bytes, sent
 *            by every link the moment its socket connects, dst=BROADCAST (nobody
 *            knows anybody's id yet). Resolves that PeerLink's identity — see
 *            [handleHelloFrame].
 *   type 8 = HANGUP (no payload) — dst=the current call partner; ends that 1:1 call
 *            on both ends without touching the mesh connection itself.
 *   type 9 = BUSY (no payload) — dst=whoever just sent a MODE this node declined
 *            because it's already in a different call.
 *   type 11 = ROSTER — GO-originated, dst=BROADCAST, sent on every join/leave:
 *             [1B count][ repeated: 8B nodeId, 1B nameLen, name bytes ]. Only the GO
 *             ever originates this; a client applies it to update its own roster UI.
 *
 * PHASE 3B — GROUP CALLS (multi-party audio + active-speaker video), additive on top
 * of everything above — 1:1 calls and group chat are unchanged and mutually exclusive
 * with a group call (starting one while in the other declines/no-ops, same as the
 * existing 1:1 busy semantics; see [tryBeginGroupCall]/[tryBeginCall]):
 *   type 12 = CALL_INVITE — [1B mode: 1=audio 2=video][8B callId], dst=BROADCAST.
 *             Any member may originate one; the GO is authoritative for the resulting
 *             call's participant set regardless of who proposed it — see
 *             [handleCallInviteFrame]. Also (re-)sent unicast by the GO to a member
 *             whose HELLO just resolved, so a late joiner sees the in-progress call
 *             (point 2's "late join" — no separate request frame needed).
 *   type 13 = CALL_ACCEPT — [8B callId], dst=the GO. Adds the sender to the GO's
 *             authoritative participant set; rejected (TYPE_BUSY, reason=1) past the
 *             8-participant hard cap.
 *   type 14 = CALL_LEAVE — [8B callId], dst=the GO. Removes the sender; the call ends
 *             once participants < 2.
 *   type 15 = VAD — [1B speaking][2B energy, BE unsigned], dst=the GO. Sent by every
 *             participant on state change plus a 2/sec heartbeat — see
 *             [startAudioSender]'s group-call branch. Feeds [GroupCallMixer]'s
 *             top-N-by-energy speaker selection AND this class's own debounced
 *             active-speaker (video) decision.
 *   type 16 = SPEAKER — [8B nodeId][1B pinned 0/1], dst=BROADCAST, GO-originated on
 *             change only. Names who should currently be sending video; a member
 *             seeing itself named starts its camera, anyone else stops theirs — see
 *             [handleSpeakerFrame]. The pinned flag distinguishes a host pin (point 8)
 *             from an ordinary VAD-driven switch, for UI purposes only.
 *   type 17 = PARTICIPANTS — [1B count][N x 8B nodeId], dst=BROADCAST, GO-originated
 *             on every join/leave.
 *   TYPE_BUSY is reused for the group-call-full rejection: a 1-byte payload
 *   [reasonCode=1] means "call is full" (0-byte payload keeps meaning the existing
 *   1:1 busy-with-someone-else, see [handleBusyFrame]).
 *
 * PHASE 5A — SOS / FIND (additive, no interaction with any call state above):
 * all three share the [MeshLocation] payload (see that file for the exact byte
 * layout) — a per-sender monotonic msgSeq plus an optional GPS fix, unsigned
 * (see MeshSosManager's class doc for why). Work standalone, any time this
 * device is joined to the mesh, independent of any active 1:1 or group call.
 *   type 20 = SOS — dst=BROADCAST. A sticky local distress beacon, re-sent every
 *             30s while active so devices that join later still see it; a final
 *             frame with hasFix's bit clear and message="CLEAR" dismisses it on
 *             every receiver — see [MeshSosManager.stopSos].
 *   type 21 = FIND_REQ — dst=a specific nodeId, "where are you". The addressee
 *             replies with type 22, rate-limited to one reply per requester per
 *             10s — see [MeshSosManager.handleFindRequestFrame].
 *   type 22 = FIND_RESP — dst=the original requester, reply to a FIND_REQ.
 *   SOS and FIND_REQ/FIND_RESP are deduplicated on (srcId, type, msgSeq) — see
 *   [routeFrame]'s dedupe hook — scoped to exactly these three types; nothing
 *   above this paragraph is affected.
 *
 * Audio (PHASE 3D — GO does zero codec work): each participant's own mic is
 * VAD-gated (RMS energy vs. an adaptive noise floor, 300ms hangover) — Opus is only
 * transmitted while speaking, same "don't send silence" spirit as everywhere else in
 * this class — and sent dst=BROADCAST, exactly like video. The GO never decodes or
 * mixes audio; [forwardBroadcast] fans every participant's stream out to everyone
 * else as pure bytes, same as it always has for video. Each receiver — GO included —
 * decodes every currently-live remote sender with its own per-sender Opus decoder and
 * sums the results locally before playback; [GroupCallMixer] survives only as a
 * lightweight, thread-free VAD ranking used purely for the speaker-highlight border
 * (see its class doc — its old per-tick decode-all-and-remix loop, and the CPU
 * overrun it caused, are gone).
 *
 * Video: every camera-on participant's stream exists on the wire simultaneously
 * (PHASE 3C multi-tile grid — no longer gated to a single "active speaker"); each
 * is forwarded dst=BROADCAST exactly like any other relayed media — [forwardBroadcast]
 * never decodes, so this needs no separate handling on the forward path. TYPE_SPEAKER
 * (2-second-debounced VAD winner, or a host pin overriding it) now drives only which
 * tile gets a highlight border, not who's allowed to send video.
 */
class OfflineMediaTransport(
    private val context: Context,
    private val isGroupOwner: Boolean,
    private val groupOwnerAddress: InetAddress?,
    // PHASE 3: this device's own display name, announced in HELLO so every other
    // member's roster can show something better than a hex id.
    private val localDisplayName: String,
    // IDLE-SESSION FIX: lets a client tell a transient socket drop (worth reconnecting)
    // apart from the underlying WiFi Direct group itself having gone away (not worth
    // it) — see attemptClientReconnect(). The caller (OfflineCallActivity) backs this
    // with the latest WifiP2pInfo.groupFormed it has observed, updated unconditionally
    // on every connection-changed callback, independent of this transport's own state.
    private val isGroupFormed: () -> Boolean,
    private val onError: (String) -> Unit
) {
    /** Which halves of the transport run a given call. Symmetric — both peers in a
     *  call end up running the same mode. */
    enum class CallMode(val wireId: Byte) {
        VIDEO(1), AUDIO(2), CHAT(3);
        companion object {
            fun fromWireId(id: Byte): CallMode? = values().firstOrNull { it.wireId == id }
        }
    }

    /** Which codec a given direction's audio is encoded with. Each side probes its own
     *  hardware/software Opus encoder support independently and announces the result —
     *  the two directions of one call can legitimately differ. */
    enum class AudioCodec(val wireId: Byte) {
        OPUS(1), PCM(2);
        companion object {
            fun fromWireId(id: Byte): AudioCodec? = values().firstOrNull { it.wireId == id }
        }
    }

    /** PHASE 3B: group call mode — note the wire ids are 1=audio/2=video, the REVERSE
     *  of [CallMode]'s 1=video/2=audio; this is its own enum rather than reusing
     *  CallMode because the two wire contracts (TYPE_MODE vs TYPE_CALL_INVITE) were
     *  specified independently and don't share a byte encoding. */
    enum class GroupCallMode(val wireId: Byte) {
        AUDIO(1), VIDEO(2);
        companion object {
            fun fromWireId(id: Byte): GroupCallMode? = values().firstOrNull { it.wireId == id }
        }
    }

    /** PHASE 3B: GO-authoritative state for the current group call — null when none is
     *  active. Every device (GO included) keeps one of these once it's a participant,
     *  but only the GO's copy is authoritative; a client's is just a mirror of the
     *  GO's last TYPE_PARTICIPANTS/TYPE_SPEAKER broadcasts, used purely for its own UI.
     *
     *  FIX 1: [established] fixes the "every call self-destructs on creation" defect —
     *  a brand-new call always has exactly 1 participant (the founder), which used to
     *  immediately satisfy "fewer than 2 participants -> end the call". A call now
     *  starts in a RINGING state (established=false, exactly 1 participant is normal
     *  and expected) and only becomes subject to the "<2 ends the call" rule once it
     *  has ever reached 2+ participants — see [evaluateGroupCallAfterChange] /
     *  [handleParticipantsFrame]. Never reverts to false once true for this call's
     *  lifetime. */
    private class GroupCallState(val callId: Long, val mode: GroupCallMode, val initiatorId: Long) {
        val participants: MutableSet<Long> = java.util.Collections.synchronizedSet(mutableSetOf())
        @Volatile var activeSpeakerId: Long? = null
        @Volatile var pinnedId: Long? = null
        @Volatile var established: Boolean = false
        // PHASE 3C: GO-authoritative per-participant camera state (mirrored on
        // clients via TYPE_CAM broadcasts, same as [participants]) — true entries
        // are who's actually occupying a MAX_LIVE_CAMERAS slot. Absent == off.
        val camStates: MutableMap<Long, Boolean> = ConcurrentHashMap()
    }

    companion object {
        private const val MEDIA_PORT = 8889

        // FIX 3: process-level singleton guard. FIX 4 (idle-session) left onDestroy()
        // gated on isFinishing so a system-reclaimed (non-finishing) Activity doesn't
        // kill a healthy transport — but that means a freshly re-created Activity had
        // no way to know an old transport instance might still be alive and bound to
        // port 8889. Call [stopOrphanedInstance] right before constructing a new
        // instance; [start] registers the new one, [stop] unregisters itself.
        @Volatile private var activeInstance: OfflineMediaTransport? = null

        /** Stops whatever transport instance is currently registered (if any) before
         *  the caller constructs a new one — prevents two ServerSockets ever
         *  competing for port 8889 (see FIX 3's BindException handling in
         *  [startAsServer]). Safe to call when there is no previous instance. */
        fun stopOrphanedInstance() {
            val existing = activeInstance
            if (existing != null) {
                Log.w("OFFTRACE", "MEDIA: stopping orphaned transport before new start")
                existing.stop()
            }
            activeInstance = null
        }

        private const val CONNECT_RETRIES = 10
        private const val CONNECT_RETRY_DELAY_MS = 500L
        // IDLE-SESSION FIX: a client's one uplink to the GO dying doesn't necessarily
        // mean the WiFi Direct group itself is gone (e.g. a transient radio hiccup right
        // after screen-off) — retried before giving up on the whole mesh session, see
        // attemptClientReconnect().
        private const val RECONNECT_RETRIES = 5
        private const val RECONNECT_RETRY_DELAY_MS = 2000L
        private const val TYPE_CONFIG: Byte = 1
        private const val TYPE_FRAME: Byte = 2
        private const val TYPE_AUDIO: Byte = 3
        private const val TYPE_CHAT: Byte = 4
        private const val TYPE_MODE: Byte = 5
        private const val TYPE_AUDIO_CODEC: Byte = 6
        private const val TYPE_HELLO: Byte = 7
        private const val TYPE_HANGUP: Byte = 8
        private const val TYPE_BUSY: Byte = 9
        private const val TYPE_ROSTER: Byte = 11
        private const val TYPE_CALL_INVITE: Byte = 12
        private const val TYPE_CALL_ACCEPT: Byte = 13
        private const val TYPE_CALL_LEAVE: Byte = 14
        private const val TYPE_VAD: Byte = 15
        private const val TYPE_SPEAKER: Byte = 16
        private const val TYPE_PARTICIPANTS: Byte = 17
        // PHASE 3C: [8B nodeId][1B on/off]. Dual-purpose by role: client -> GO is a
        // REQUEST (GO must arbitrate against MAX_LIVE_CAMERAS before it's true), GO ->
        // BROADCAST is the authoritative announcement of an accepted state change —
        // same "request to the GO, GO re-broadcasts authoritatively" shape as
        // TYPE_VAD/TYPE_CALL_ACCEPT, not a plain client-originated broadcast, so a
        // denial never needs to be un-forwarded after the fact. See handleCamFrame.
        private const val TYPE_CAM: Byte = 18
        // dst=the requester only, no payload — sent instead of the TYPE_CAM broadcast
        // when MAX_LIVE_CAMERAS is already reached and this wasn't already-on.
        private const val TYPE_CAM_DENIED: Byte = 19
        // PHASE 5A: SOS / FIND-over-mesh — see the class doc's SOS/FIND paragraph
        // below, MeshSosManager for the actual logic, and MeshLocation for the
        // shared payload. Purely additive; types 1-19 are unchanged. Gap at 10
        // deliberately left alone.
        private const val TYPE_SOS: Byte = 20
        private const val TYPE_FIND_REQ: Byte = 21
        private const val TYPE_FIND_RESP: Byte = 22
        // PHASE 5BC: additive on top of PHASE 5A, same MeshLocation payload (bumped
        // to v2 — see that file). Types 1-22 are unchanged.
        //   type 23 = POSITION — low-rate (30s) ambient broadcast, dst=BROADCAST,
        //             sent by every device in a group session whether or not any SOS
        //             is active — the MeshLedger feed. See MeshSosManager.
        //   type 24 = SOS_ACK — no payload, dst=the original SOS sender, sent by
        //             every device that hears an active SOS. Deliberately OUTSIDE
        //             the SOS/FIND/POSITION dedupe scope (see
        //             MeshSosManager.isSosFindType's doc) — an ack is idempotent by
        //             construction (a Set of ackers absorbs a duplicate for free).
        private const val TYPE_POSITION: Byte = 23
        private const val TYPE_SOS_ACK: Byte = 24
        // PHASE 6 TRACK A: generalized store-and-forward — see MeshCarrier.kt for
        // the envelope layout and the SOS-carry migration off PHASE 5BC's
        // hardcoded, srcId-spoofing special case. Types 1-24 are unchanged.
        //   type 25 = STORE_FWD — dst=a specific carrier hop or BROADCAST, carries
        //             ANY inner frame type (chat, SOS, position...) plus routing
        //             metadata (true originId, finalDstId, expiry, hop count).
        //   type 26 = SF_ACK — payload=16B msgId, dst=whoever handed us the
        //             STORE_FWD frame (one hop back, not the true originator —
        //             see MeshCarrier's class doc on why that's sufficient for
        //             this mesh's star topology).
        private const val TYPE_STORE_FWD: Byte = 25
        private const val TYPE_SF_ACK: Byte = 26
        // PHASE 6 TRACK E: self-healing GO re-election — see MeshElection.kt.
        // Types 1-26 are unchanged.
        //   type 27 = GO_HEARTBEAT — dst=BROADCAST, no payload, sent every 10s by
        //             whichever device currently believes itself GO. 3 missed
        //             intervals (30s) is how a client declares the GO lost.
        //   type 28 = ELECTION_STATUS — dst=BROADCAST, sent every 10s by EVERY
        //             device (GO and clients alike): [1B batteryPercent][1B
        //             visiblePeerCount]. This is the "ledger" of election inputs
        //             every node needs so the election itself needs no
        //             negotiation round-trip — see MeshElection's class doc.
        private const val TYPE_GO_HEARTBEAT: Byte = 27
        private const val TYPE_ELECTION_STATUS: Byte = 28
        // type 29 = KEYFRAME_REQUEST — dst=a specific srcId, no payload. Sent by a
        // receiver whose group-tile decoder for that srcId was JUST configured
        // (see configureGroupDecoder's requestKeyframeAfter param) and has
        // nothing to decode until srcId's next periodic keyframe, which could be
        // an arbitrarily long wait. On receipt, the target simply calls its own
        // existing local requestKeyFrame() — no new encoder-side logic, this is
        // the same IDR request that FIX (see requestKeyFrame's doc) already
        // performs locally in response to other triggers, now reachable from a
        // remote peer instead of only from local camera-state events.
        private const val TYPE_KEYFRAME_REQUEST: Byte = 29
        private const val MAX_CHAT_PAYLOAD_BYTES = 4096
        // Envelope header (42B, see MeshCarrier.ENVELOPE_HEADER_SIZE) + the
        // largest inner payload this mesh currently carries (a chat message).
        private const val MAX_STORE_FWD_PAYLOAD_BYTES = 42 + MAX_CHAT_PAYLOAD_BYTES
        // PHASE 7A STEP 5: matches the display-name validation limit exactly
        // (see OfflineCallActivity's showDisplayNameDialog) — this wire-level
        // truncation is a defensive floor, not the primary enforcement point.
        private const val MAX_NAME_BYTES = 32

        // PHASE 3B: group calls
        private const val MAX_GROUP_PARTICIPANTS = 8 // WiFi Direct GO client ceiling
        private const val BUSY_REASON_CALL_FULL: Byte = 1
        // PHASE 3C: simultaneous-live-camera ceiling — the honest WiFi Direct radio
        // limit; without this, every participant's camera turning on saturates the
        // link. Audio-only participation beyond this cap is unlimited (up to
        // MAX_GROUP_PARTICIPANTS).
        private const val MAX_LIVE_CAMERAS = 4
        // TYPE_SPEAKER's nodeId field sentinel for "nobody is currently speaking" —
        // distinct from MeshFrame's own PENDING_ID/BROADCAST_ID, which belong to the
        // envelope layer, not this application-level concept.
        private const val NO_SPEAKER_ID: Long = -3L
        // FIX 1: a founding call that never reaches 2 participants (nobody answers)
        // ends itself cleanly after this long rather than ringing forever.
        private const val GROUP_CALL_RINGING_TIMEOUT_MS = 45_000L
        // VAD: 20ms RMS energy vs. an adaptive noise floor (tracks the floor up
        // quickly on quiet frames, down slowly so a sudden loud burst doesn't
        // instantly redefine "quiet"), plus hangover so a word's trailing consonants
        // aren't clipped the instant energy dips.
        private const val VAD_HANGOVER_MS = 300L
        // FIX 2: was 500ms against GroupCallMixer's VAD_STALE_MS=600ms — only a 100ms
        // margin, so a single delayed/dropped heartbeat could flip a still-speaking
        // participant to "stale" on the mixer side. 300ms heartbeat vs. 1500ms stale
        // is a full 5x margin.
        private const val VAD_HEARTBEAT_MS = 300L
        private const val VAD_SPEAK_THRESHOLD_MULT = 3 // energy must exceed floor*this to count as speech
        private const val VAD_FLOOR_RISE_RATE = 32 // fast-attack toward a quieter floor (out of 1024)
        private const val VAD_FLOOR_FALL_RATE = 4  // slow-decay toward a louder floor — avoids the
                                                     // floor chasing a sustained talker upward
        // Active-speaker (video) debounce: a challenger must lead continuously for
        // this long before the GO actually switches who's sending video — point 6.
        private const val ACTIVE_SPEAKER_DEBOUNCE_MS = 2_000L

        private const val TTL_UNICAST: Byte = 8
        private const val TTL_BROADCAST: Byte = 4
        private const val MAX_VIDEO_PAYLOAD_BYTES = 256 * 1024
        private const val MAX_AUDIO_PAYLOAD_BYTES = 4096
        private const val MAX_CONFIG_PAYLOAD_BYTES = 4096
        private const val MAX_CONTROL_PAYLOAD_BYTES = 1024 // mode/audio-codec/hello/busy/hangup/roster
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

        private const val OPUS_CHANNEL_COUNT = 1
        private const val OPUS_BITRATE = 20000
        private const val OPUS_HEADER_SIZE = 19
        private const val OPUS_PRE_SKIP_SAMPLES_48K = 0
        private const val OPUS_SEEK_PREROLL_NS = 0L
        private const val AUDIO_BYTES_LOG_WINDOW_MS = 5000L

        private const val OPUS_ENCODE_QUEUE_CAPACITY = 10
        private const val OPUS_DECODE_QUEUE_CAPACITY = 20
        private const val OPUS_QUEUE_POLL_TIMEOUT_MS = 200L

        // PHASE 3D: local group-audio mixing (see mixGroupPcm/mixAndPlayGroupAudio) —
        // same soft-limiter shape GroupCallMixer used to apply GO-side, now applied
        // by each receiver over whichever remote streams are currently live.
        private const val GROUP_AUDIO_LIMITER_THRESHOLD = 26000
        private const val GROUP_AUDIO_LIMITER_RATIO = 4
        // A sender's last decoded chunk older than this is dropped from the mix —
        // they've gone quiet (VAD gates transmission) and their stale last chunk
        // must not get replayed into every subsequent mix forever. ~5x a 20ms chunk
        // interval, generous margin for normal inter-packet jitter.
        private const val GROUP_AUDIO_STALE_MS = 100L
        private const val GROUP_MIX_LOG_INTERVAL_MS = 1_000L
        private const val DROP_LOG_INTERVAL = 100
        // FIX 1: hard cap per call-scoped media thread when tearing down just the
        // current call (endLocalCallState/endGroupCallState) — see stopCallThreads().
        private const val CALL_THREAD_JOIN_MS = 500L

        private const val AUDIO_RECORD_RETRY_DELAY_MS = 500L
        private const val MIC_READ_ERROR_REBUILD_THRESHOLD = 100
        private const val MAX_MIC_REBUILD_ATTEMPTS = 3
        private const val MIC_READ_ERROR_LOG_INTERVAL = 50
        private const val MIC_ZERO_READ_LOG_INTERVAL = 250

        private const val DROP_WARN_WINDOW_MS = 10_000L
        private const val DROP_WARN_THRESHOLD = 50

        // PHASE 3: GO forwarding logs every control frame but samples media frames per
        // (src,dst,type) flow — a video/audio call forwards hundreds of frames/sec and
        // logging every one would drown out everything else.
        private const val FORWARD_MEDIA_LOG_SAMPLE = 50
        private const val UNKNOWN_DST_LOG_INTERVAL = 100
    }

    private val running = AtomicBoolean(false)
    // FIX 1: `running` is mesh-scoped (true for the whole session) — it never flips
    // on a call-only teardown (endLocalCallState/endGroupCallState), so a media loop
    // gated only on `running` survives every hangup and keeps spinning against an
    // AudioRecord/encoder that teardown is about to release out from under it (the
    // root cause of the mic's err=-3 busy-spin and the encoder drain thread's
    // IllegalStateException crash). Every call-scoped media loop now also checks
    // this; it's flipped true when a call actually starts (startSendersForMode /
    // startGroupCallAudio) and false FIRST, before any resource is released, in
    // endLocalCallState/endGroupCallState/stop() — see stopCallThreads().
    private val callActive = AtomicBoolean(false)
    // PHASE 3: latches false exactly once when the MESH session itself dies — a
    // client's one uplink dying, or the GO's own accept loop failing fatally. Losing
    // ONE peer among several on the GO is a roster change, not this.
    private val alive = AtomicBoolean(true)
    private val mainHandler = Handler(Looper.getMainLooper())

    // PHASE 3: call-state is now a resettable sub-session, guarded by callLock so a
    // locally-placed call and an inbound TYPE_MODE can't race each other into a torn
    // state. activeCallPeerId is the single source of truth for "am I in a call, and
    // with whom" — null means idle (mesh joined, no call).
    private val callLock = Any()
    @Volatile private var activeCallPeerId: Long? = null
    @Volatile private var activeCallPeerName: String = ""
    // Set only while a locally-placed call hasn't yet been confirmed or declined —
    // used solely to attribute an inbound TYPE_BUSY to the right outgoing attempt.
    @Volatile private var pendingOutgoingCallPeerId: Long? = null
    @Volatile private var resolvedMode: CallMode? = null

    // PHASE 3B: group-call state, guarded by the SAME callLock as the 1:1 fields
    // above — the two are mutually exclusive (see tryBeginGroupCall/tryBeginCall), so
    // one lock covers "what kind of call, if any, am I in" atomically for both.
    @Volatile private var groupCall: GroupCallState? = null
    // GO only — created when this device's first group call starts, released when it
    // ends. Never touched on a client.
    private var groupCallMixer: GroupCallMixer? = null
    // GO only — the debounced (2s continuous lead) active-speaker decision feeding
    // TYPE_SPEAKER broadcasts; separate from GroupCallMixer's raw, undebounced
    // top-speaker signal (see onActiveSpeakersChanged wiring in startGroupCallMixer).
    @Volatile private var speakerCandidateId: Long? = null
    @Volatile private var speakerCandidateSinceMs: Long = 0L
    // FIX 2: gates the once-per-second "candidate pending" log in onRawActiveSpeakersChanged.
    private var lastSpeakerCandidateLogMs: Long = 0L
    // FIX 4: the callId whose audio senders are currently started — startGroupCallAudio
    // is the one function all four join paths (initiator/acceptor x GO/client) funnel
    // through, so guarding there catches a duplicate accept regardless of which path
    // it came from (e.g. a double-tap on "Join" before the dialog disables itself).
    @Volatile private var audioSendersStartedForCallId: Long? = null

    private var chatThread: HandlerThread? = null
    private var chatHandler: Handler? = null

    // PHASE 3: the GO keeps its ServerSocket open for the whole session (loops on
    // accept()); a client's one outbound Socket is owned by its single PeerLink.
    private var serverSocket: ServerSocket? = null
    private val linkCounter = AtomicInteger(0)
    // Links whose peer hasn't sent HELLO yet — not yet routable by node id.
    private val pendingLinks = ConcurrentHashMap.newKeySet<PeerLink>()
    private val routingTable: RoutingTable
    // PHASE 3: every node id this device has ever heard a name for (self, direct
    // links, and anyone mentioned in a ROSTER broadcast) — the single source of
    // truth for display names, since a call partner may be a relayed peer this
    // device has no direct PeerLink to at all.
    private val knownNames = ConcurrentHashMap<Long, String>()
    private var incompatibleVersionLogged = false
    private var wrongDstDropCount = 0
    private var ttlZeroDropCount = 0
    private var unknownDstDropCount = 0
    private var staleMediaDropCount = 0
    // FIX 4: media arriving with no active call at all (previously silently
    // implicit-started a VIDEO call and opened the camera — see acceptMediaFrame).
    private var noActiveCallMediaDropCount = 0
    private val forwardLogCounters = ConcurrentHashMap<Long, Int>()
    private val disconnectedLinks = ConcurrentHashMap.newKeySet<PeerLink>()

    /** This device's stable mesh node id — first 8 bytes of SHA-256(Ed25519 public
     *  key), persisted across launches. PHASE 3: public so the Activity can tell "is
     *  this roster row me" apart from other members. */
    val localNodeId: Long = run {
        val idBytes = OfflineIdentity.nodeId(context)
        val id = ByteBuffer.wrap(idBytes).long
        Log.d("OFFTRACE", "MESH: local nodeId=${MeshFrame.hex(id)}")
        id
    }

    init {
        routingTable = RoutingTable(localNodeId, localDisplayName)
        knownNames[localNodeId] = localDisplayName
    }

    // PHASE 7A: real cryptographic identity — see MeshSigner's class doc. Not a
    // process-wide singleton (unlike ledger/carrier/etc.) since its pending-
    // pubkey queue holds live PeerLink references scoped to this session.
    private val meshSigner = MeshSigner(
        context = context,
        localNodeId = localNodeId,
        routingTable = routingTable,
        retryFrame = { header, payload, fromLink -> routeFrame(header, payload, fromLink) }
    )

    // PHASE 5A/5BC: SOS/FIND/POSITION/carry — see MeshSosManager's class doc.
    // Constructed once, works independent of any active call. sendFrame/
    // sendRawFrame are wired straight to this class's own writeFrame/
    // writeRawFrame so MeshSosManager never touches routingTable/sockets itself;
    // the callbacks forward to this class's own public vars so the Activity
    // never needs to know MeshSosManager (or MeshLedger/MeshBarometer) exists.
    private val locationProvider = OfflineLocationProvider.get(context)
    val barometer = MeshBarometer.get(context)
    val ledger = MeshLedger.get(context)
    // PHASE 6 TRACK A: generalized store-and-forward — see MeshCarrier.kt.
    // Process-wide singleton (same pattern as ledger/barometer); [configure] and
    // the callback wiring below are this transport instance's own session setup.
    val carrier = MeshCarrier.get(context)
    // PHASE 6 TRACK B1: beacon mode — see SosBeaconMode.kt. Process-wide
    // singleton, same pattern as the others above.
    val sosBeaconMode = SosBeaconMode.get(context)
    // PHASE 6 TRACK B2/B3: hands-free triggers and cellular relay.
    val sosTriggers = SosTriggers.get(context)
    val sosRelay = SosRelay.get(context)
    // PHASE 6 TRACK C: BLE presence + standard beacon broadcast.
    val bleBeacon = MeshBleBeacon.get(context)
    // PHASE 6 TRACK E: self-healing GO re-election — see MeshElection.kt. Not a
    // process-wide singleton (unlike the others above) since its heartbeat/
    // watchdog state is inherently per-session, same lifecycle as
    // meshSosManager.
    val meshElection = MeshElection(
        localNodeId = localNodeId,
        typeGoHeartbeat = TYPE_GO_HEARTBEAT,
        typeElectionStatus = TYPE_ELECTION_STATUS,
        sendFrame = { dst, type, payload -> writeFrame(dst, type, payload) },
        isCurrentlyGo = { isGroupOwner },
        visiblePeerCount = { visiblePeerCountForElection() },
        batteryPercent = { readBatteryPercentForElection() },
        onGoLost = { onGoLost?.invoke() },
        onElectionResult = { winnerId, isSelf -> onElectionResult?.invoke(winnerId, isSelf) },
        onSplitBrainDetected = { otherGoId -> onSplitBrainDetected?.invoke(otherGoId) }
    )

    /** PHASE 6 TRACK E: fired on the main thread when this CLIENT hasn't heard a
     *  GO heartbeat in 30s — the caller should show "GROUP OWNER LOST —
     *  RECONNECTING" and call [meshElection]'s election once it has gathered
     *  enough context, or simply react to [onElectionResult] directly (it fires
     *  independently once the caller calls runElection()). */
    var onGoLost: (() -> Unit)? = null
    /** PHASE 6 TRACK E: fired on the main thread with the deterministic
     *  election outcome — see MeshElection.onElectionResult's doc. */
    var onElectionResult: ((winnerId: Long, isSelf: Boolean) -> Unit)? = null
    /** PHASE 6 TRACK E: fired on the main thread when a split-brain is detected
     *  and THIS device (higher nodeId) should stand down. */
    var onSplitBrainDetected: ((otherGoId: Long) -> Unit)? = null

    /** PHASE 6 TRACK E: rewards a centrally positioned device with GO duties —
     *  Wi-Fi Direct roster reach plus any fresh (&lt;30s) BLE-only sighting not
     *  already counted in that roster (see MeshBleBeacon's "NEARBY, NOT
     *  CONNECTED" state — a genuinely separate signal from roster membership). */
    private fun visiblePeerCountForElection(): Int {
        val wifiDirectPeers = currentOtherMemberCount()
        val bleOnly = ledger.knownNodeIds().count { id ->
            id != localNodeId && routingTable.get(id) == null &&
                ledger.blePresenceFor(id)?.let { System.currentTimeMillis() - it.seenAtMs < 30_000L } == true
        }
        return wifiDirectPeers + bleOnly
    }

    private fun readBatteryPercentForElection(): Int {
        return try {
            val filter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            val status = context.registerReceiver(null as android.content.BroadcastReceiver?, filter)
            val level = status?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = status?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level < 0 || scale <= 0) 0 else (level * 100 / scale)
        } catch (e: Exception) {
            0
        }
    }
    // PHASE 6 TRACK C: explicit type annotation required — onSosEntry below
    // self-references meshSosManager (to check isOwnSosActive/sosEntries for
    // bleBeacon.setSosActive), and Kotlin's type inference can't resolve a
    // property's own type while evaluating an initializer that references the
    // property itself ("recursive problem") without this annotation.
    private val meshSosManager: MeshSosManager = MeshSosManager(
        localNodeId = localNodeId,
        typeSos = TYPE_SOS,
        typeFindReq = TYPE_FIND_REQ,
        typeFindResp = TYPE_FIND_RESP,
        typePosition = TYPE_POSITION,
        typeSosAck = TYPE_SOS_ACK,
        locationProvider = locationProvider,
        barometer = barometer,
        ledger = ledger,
        carrier = carrier,
        sendFrame = { dst, type, payload -> writeFrame(dst, type, payload) },
        otherMemberCount = { (routingTable.size() - 1).coerceAtLeast(0) },
        onSosEntry = { entry -> handleMeshSosEntryForBle(entry) },
        onFindResponse = { entry -> onFindResponse?.invoke(entry) },
        onSosAckUpdate = { seenBy, total -> onSosAckProgress?.invoke(seenBy, total) },
        onPositionUpdated = { nodeId -> onPositionUpdated?.invoke(nodeId) }
    )

    /** PHASE 6 TRACK C: bound as a method reference (not an inline lambda) from
     *  meshSosManager's own constructor call — a lambda declared INSIDE that
     *  call cannot reference the meshSosManager property itself (Kotlin treats
     *  any textual reference to a val inside its own initializer as reading an
     *  uninitialized variable, even one that would only actually run later); a
     *  reference to a separately-declared function sidesteps that restriction
     *  since the function body is resolved by name, not evaluated at the call
     *  site. Escalates BLE advertising to format-rotation + faster scan the
     *  moment ANY sender's SOS becomes active — see MeshBleBeacon.setSosActive. */
    private fun handleMeshSosEntryForBle(entry: MeshSosManager.SosEntry) {
        bleBeacon.setSosActive(meshSosManager.isOwnSosActive || meshSosManager.sosEntries.any { it.value.active })
        onSosEntry?.invoke(entry)
    }

    init {
        carrier.configure(localNodeId, TYPE_STORE_FWD, TYPE_SOS)
        carrier.sendStoreFwd = { dst, payload -> writeFrame(dst, TYPE_STORE_FWD, payload) }
        carrier.sendAck = { dst, payload -> writeFrame(dst, TYPE_SF_ACK, payload) }
        carrier.dispatchInner = { originId, finalDstId, innerType, inner ->
            dispatchCarriedInner(originId, finalDstId, innerType, inner)
        }
        sosTriggers.onFire = { startSos(null) }
        // BUG 1 FIX 5: hardened — "roster currently has 0 other members" alone
        // is too weak a signal (true for a device that was simply never paired
        // with anyone, or during the brief window before the first HELLO
        // completes right after registerAll() runs); requiring an actual
        // ledger-tracked LostContact event means this can only be true once
        // this device really WAS with someone and a roster-diff lost-contact
        // fired for them (see MeshLedger.hasAnyLostContact's doc).
        sosTriggers.isolatedFromParty = { currentOtherMemberCount() <= 0 && ledger.hasAnyLostContact() }
        sosTriggers.onCountdownTick = { secondsRemaining, name -> onTriggerCountdownTick?.invoke(secondsRemaining, name) }
        sosTriggers.onCountdownEnded = { onTriggerCountdownEnded?.invoke() }
        sosRelay.pendingSosProvider = { pendingSosForRelay() }
        sosRelay.onRelayPromptReady = { prompt -> mainHandler.post { onRelayPromptReady?.invoke(prompt) } }
        bleBeacon.onPresenceUpdated = { nodeId -> onBlePresenceUpdated?.invoke(nodeId) }
    }

    /** PHASE 6 TRACK C: fired on the main thread whenever a BLE-only sighting
     *  (not currently reachable over Wi-Fi Direct) updates. */
    var onBlePresenceUpdated: ((Long) -> Unit)? = null

    /** PHASE 6 TRACK B3: current active (non-CLEAR) SOS entries, mapped to what
     *  SosRelay needs to compose an SMS — msgId comes from MeshSosManager's own
     *  carrier-tracking map so a relay prompt and the underlying carried message
     *  refer to the same queue entry. */
    private fun pendingSosForRelay(): List<SosRelay.PendingSos> =
        meshSosManager.sosEntries.values.filter { it.active }.mapNotNull { entry ->
            val msgId = meshSosManager.carrierMsgIdFor(entry.srcId) ?: return@mapNotNull null
            val latest = ledger.latestEntry(entry.srcId)
            val vert = latest?.pressureHpaX10?.let { barometer.relativeAltitudeTo(it / 10.0) }
            SosRelay.PendingSos(
                msgId = msgId,
                senderName = nameFor(entry.srcId),
                latitude = if (entry.hasFix) entry.latitude else null,
                longitude = if (entry.hasFix) entry.longitude else null,
                locTier = latest?.tier ?: MeshLocation.LOC_TIER_NONE,
                // FIX 4: local clock, but clamp — this age lands directly in an
                // emergency SMS body via SosRelay, which must never show negative.
                fixAgeSec = ((System.currentTimeMillis() - entry.receivedAtMs) / 1000).coerceAtLeast(0L),
                verticalSeparationM = vert,
                message = entry.message
            )
        }

    /** PHASE 6 TRACK B2: fired on the main thread on every countdown tick while
     *  a hands-free trigger's 30s cancel window is open. */
    var onTriggerCountdownTick: ((secondsRemaining: Int, triggerName: String) -> Unit)? = null
    var onTriggerCountdownEnded: (() -> Unit)? = null
    /** PHASE 6 TRACK B3: fired on the main thread when cell signal returns with
     *  a still-pending SOS to relay — the caller should show the "TAP TO SEND"
     *  full-screen prompt. */
    var onRelayPromptReady: ((SosRelay.RelayPrompt) -> Unit)? = null

    /** PHASE 6 TRACK A: unwraps a message [MeshCarrier] just delivered to us back
     *  into the exact same dispatch path a genuinely live frame of [innerType]
     *  would take — reuses [dispatchLocal]'s existing per-type handlers (chat UI,
     *  SOS alert/ledger, etc.) rather than duplicating any of them. Re-applies
     *  [MeshSosManager]'s own msgSeq-based dedupe to the inner payload first, so
     *  the "never re-alarm a device that already saw this SOS" guarantee from
     *  PHASE 5BC is unchanged — see MeshCarrier's class doc. Deliberately calls
     *  [dispatchLocal] directly, NOT [routeFrame] — a carried message's forwarding
     *  onward is MeshCarrier's own peer-by-peer offer, not a TTL broadcast fan-out. */
    private fun dispatchCarriedInner(originId: Long, finalDstId: Long, innerType: Byte, inner: ByteArray) {
        if (meshSosManager.isSosFindType(innerType) &&
            meshSosManager.checkAndRecordDuplicate(originId, innerType, inner)
        ) {
            log("MESH: dropped duplicate carried inner type=$innerType from=${MeshFrame.hex(originId)}")
            return
        }
        val syntheticHeader = MeshFrame.Header(MeshFrame.VERSION, originId, finalDstId, TTL_UNICAST, innerType, inner.size)
        // BUG 1 FIX 3: isLive=false — this is a store-and-forward REPLAY, not a
        // frame that just arrived off a real link. See dispatchLocal's isLive
        // param / MeshSosManager.handleSosFrame's doc for what this changes
        // (only TYPE_SOS's alarmability; every other type ignores it).
        dispatchLocal(syntheticHeader, inner, isLive = false)
    }

    // Camera2 (local send path) — shared by 1:1 and group video, since a device only
    // ever runs its OWN single camera regardless of call type.
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    // PHASE 3C: second capture-session target for this device's own grid tile
    // preview — registered by the Activity via [setLocalPreviewSurface], additive
    // to the encoder's own input surface (Camera2 supports multiple simultaneous
    // output targets from one capture session). Null on a 1:1 call (no self-tile).
    @Volatile private var localPreviewSurface: Surface? = null
    // FIX: identity of the preview Surface the CURRENTLY RUNNING capture session
    // was actually built with (null = none) — set once, in startCaptureSession's
    // onConfigured, the single source of truth for "does the running session
    // already have a preview target". Doubles as the "already rebuilt for this
    // surface" guard so setGroupTileSurface-style races/repeat calls can't loop.
    @Volatile private var capturingWithPreviewSurface: Surface? = null
    // FIX: identity of a preview Surface a rebuild has already been POSTED for but
    // hasn't completed yet — closes the window between setLocalPreviewSurface
    // being called and capturingWithPreviewSurface actually updating, where a
    // rapid repeat call with the same Surface would otherwise schedule a second,
    // redundant rebuild before the first one's onConfigured has even fired.
    @Volatile private var previewRebuildPendingFor: Surface? = null

    // Encoder (local send path) — one instance, this device's own camera only.
    private var encoder: MediaCodec? = null
    private var encoderInputSurface: Surface? = null
    // FIX 2: a plain camera TOGGLE (applyCamState's off branch) calls
    // releaseEncoder() directly, without going through the callActive=false
    // signal the full call-teardown path uses (see releaseCallThreads' doc) —
    // callActive stays true the whole time, since audio/VAD/etc. must keep
    // running. drainEncoderLoop has no way to notice a mid-toggle release is
    // coming without a dedicated signal, so it raced releaseEncoder() and
    // touched an already-released MediaCodec. This flag is that signal: flipped
    // false as the very FIRST thing releaseEncoder() does, checked by the drain
    // loop before every call into the encoder.
    @Volatile private var encoderRunning = false
    // FIX: this device's own last-broadcast combined csd (SPS+PPS), captured once
    // drainEncoderLoop's one-shot TYPE_CONFIG actually goes out — replayed to a
    // late-joining peer by sendGroupCallStateTo, since that peer's connection
    // didn't exist yet for the original one-shot broadcast to ever reach it. Reset
    // per call, same lifecycle as decoderReady/pendingCsd below.
    @Volatile private var lastCsdOut: ByteArray? = null

    // Decoder (remote receive path, 1:1 ONLY) — PHASE 3: this call's decode
    // bookkeeping, reset per call by endLocalCallState() rather than living for the
    // transport's whole lifetime (Phase 2 had exactly one call per transport
    // instance). Group calls use the per-sender maps below instead — see
    // [configureGroupDecoder]/[feedGroupDecoder].
    private var decoder: MediaCodec? = null
    @Volatile private var displaySurface: Surface? = null
    private val pendingCsd = mutableListOf<ByteArray>()
    private var decoderReady = false
    private var videoFrameCountRecv = 0
    private var audioFrameCountRecv = 0
    // FIX (STEP 2b): was a one-shot Boolean — a second, DIFFERENT unknown type
    // logged nothing at all after the first one ever seen. Diagnostic-only fix;
    // dropping behaviour (still silently skipped) is unchanged.
    private val unknownTypesLogged = mutableSetOf<Byte>()

    // PHASE 3C: multi-tile group video receive path — one decoder per REMOTE
    // sender currently sending TYPE_FRAME, each bound to its own tile's Surface
    // (registered by the Activity via [setGroupTileSurface]). Independent of
    // [decoder] above; never touched by a 1:1 call. Video is no longer gated to a
    // single "active speaker" — every camera-on participant gets their own entry.
    private val groupDecoders = ConcurrentHashMap<Long, MediaCodec>()
    private val groupPendingCsd = ConcurrentHashMap<Long, MutableList<ByteArray>>()
    private val groupDecoderReady = ConcurrentHashMap<Long, Boolean>()
    private val groupTileSurfaces = ConcurrentHashMap<Long, Surface>()
    private val groupVideoFrameCountRecv = ConcurrentHashMap<Long, Int>()
    // Set only while awaiting the GO's accept (TYPE_CAM broadcast) or deny
    // (TYPE_CAM_DENIED) for THIS device's own most recent camera-on request.
    @Volatile private var groupCallCameraPending = false
    // FIX 3: purely diagnostic — [setGroupCallCameraOn]'s own guard already
    // correctly checks live captureSession state (see isLocalCameraOn/FIX E's
    // doc), so this field changes no behaviour. It exists because
    // groupCallCameraPending resets to false the moment a request is APPLIED
    // (see applyCamState), not once the capture session actually finishes
    // configuring — so it was never a valid stand-in for "is the camera really
    // on" and its OFFTRACE log line was misleading anyone reading it as one.
    @Volatile private var cameraOn = false
    @Volatile private var groupCallMicMuted = false

    // PHASE 3D: multi-sender group AUDIO receive path — mirrors the video maps
    // immediately above: one Opus decoder per REMOTE sender currently transmitting
    // TYPE_AUDIO, decoded PCM summed locally (see mixAndPlayGroupAudio) rather than
    // received pre-mixed from the GO. Never touched by a 1:1 call (that keeps using
    // the single audioDecoder/opusDecodeQueue below).
    private val groupAudioDecoders = ConcurrentHashMap<Long, MediaCodec>()
    // Each sender's own announced outgoing codec (see TYPE_AUDIO_CODEC handling) —
    // defaults to PCM, same fallback convention as the 1:1 path's remoteAudioCodec.
    private val groupRemoteAudioCodec = ConcurrentHashMap<Long, AudioCodec>()
    private val groupLatestPcm = ConcurrentHashMap<Long, ByteArray>()
    private val groupLatestPcmMs = ConcurrentHashMap<Long, Long>()
    // Defaults to the raw-PCM sample rate (not Opus's typical 48kHz) so a mix that
    // never involves an Opus decoder — e.g. every sender fell back to PCM — still
    // plays back at the correct pitch/speed by default; overwritten by whatever an
    // actual Opus decoder reports the first time one configures (see
    // handleGroupAudioOutputFormatChanged). All senders share one negotiated rate/
    // channel count here — a deliberate simplification (see mixGroupPcm's doc).
    private var groupOpusOutputSampleRate = AUDIO_SAMPLE_RATE
    private var groupOpusOutputChannelCount = OPUS_CHANNEL_COUNT
    private var lastGroupMixLogMs = 0L

    // Audio (both send and receive paths)
    private var audioRecord: AudioRecord? = null
    @Volatile private var audioTrack: AudioTrack? = null
    private var audioWriteErrCount = 0
    private var audioTrackWriteCount = 0

    @Volatile private var localAudioCodec: AudioCodec = AudioCodec.PCM
    private var audioEncoder: MediaCodec? = null
    @Volatile private var remoteAudioCodec: AudioCodec? = null
    private var audioDecoder: MediaCodec? = null

    private val opusEncodeQueue = ArrayBlockingQueue<ByteArray>(OPUS_ENCODE_QUEUE_CAPACITY)
    private var opusEncodeThread: Thread? = null
    private var opusEncodeDropCount = 0
    private var opusEncodeInputDropCount = 0

    private val opusDecodeQueue = ArrayBlockingQueue<ByteArray>(OPUS_DECODE_QUEUE_CAPACITY)
    private var opusDecodeThread: Thread? = null
    private var opusDecodeDropCount = 0
    private var decodeDropWindowCount = 0
    private var decodeDropWindowStartMs = 0L

    // FIX 1: hard references so stopCallThreads() can interrupt+join them —
    // previously only opusEncodeThread/opusDecodeThread were tracked; the mic
    // capture thread and the H.264 encoder drain thread were never joined at all.
    private var audioSendThread: Thread? = null
    private var encoderDrainThread: Thread? = null

    private var opusOutputSampleRate = 48000
    private var opusOutputChannelCount = OPUS_CHANNEL_COUNT

    private var audioSendBytesAccum = 0L
    private var audioSendWindowStartMs = 0L
    private var audioRecvBytesAccum = 0L
    private var audioRecvWindowStartMs = 0L

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private var previousAudioMode = AudioManager.MODE_NORMAL
    private var audioRoutingApplied = false

    /** Fired once, off the failing thread, when the MESH session itself is lost — a
     *  client's one uplink to the GO dying, or (rarely) the GO's own accept loop
     *  failing fatally. NOT fired just because one other member disconnects from the
     *  GO — that's [onRosterUpdated]. The transport has already torn itself down
     *  (stop()) by the time this fires. Non-null String is a protocol mismatch. */
    var onLinkLost: ((String?) -> Unit)? = null

    /** Fired on the main thread with the REMOTE peer's actual decoded frame size. */
    var onVideoSize: ((Int, Int) -> Unit)? = null

    /** PHASE 3: fired on the main thread with a chat message — fromNodeId/fromName
     *  identify the sender (who may be a relayed peer, never a direct link on a
     *  client), isGroup is true for a BROADCAST (group chat) message and false for
     *  direct 1:1 chat. */
    var onChatMessage: ((fromNodeId: Long, fromName: String, text: String, isGroup: Boolean) -> Unit)? = null

    /** PHASE 3: fired on the main thread once a call is running — for the initiator,
     *  right after [placeCall]; for the callee, once an explicit TYPE_MODE frame is
     *  accepted (FIX 4: media can no longer implicit-start a call — see
     *  [acceptMediaFrame]). Identifies the call partner, since any given session may
     *  place/receive several calls with different members over time. */
    var onModeResolved: ((peerId: Long, peerName: String, mode: CallMode) -> Unit)? = null

    /** PHASE 3: fired on the main thread when the CURRENT call ends, for any reason
     *  (local hangup, remote hangup, call partner's link dying) — the mesh session
     *  itself stays up; the caller should return to the roster screen. */
    var onCallEnded: ((reason: String) -> Unit)? = null

    /** PHASE 3: fired on the main thread when this device's own outgoing [placeCall]
     *  was declined because the callee is already in a different call. */
    var onCallBusy: ((peerName: String) -> Unit)? = null

    /** PHASE 3: fired on the main thread with the full current membership (self
     *  included) whenever it changes — initial join, another member joining/leaving. */
    var onRosterUpdated: ((List<RoutingTable.Member>) -> Unit)? = null

    /** IDLE-SESSION FIX: fired on the main thread for each client reconnect attempt
     *  (see attemptClientReconnect) — NOT fatal by itself; onLinkLost still fires if
     *  every attempt fails (or the group is no longer formed). */
    var onReconnecting: ((attempt: Int, max: Int) -> Unit)? = null

    /** PHASE 3B: fired on the main thread when a group call invite arrives (from
     *  anyone but self — an initiator handles its own start synchronously, see
     *  [startGroupCall]) — the caller should show an incoming-group-call prompt. */
    var onGroupCallInvite: ((fromNodeId: Long, fromName: String, mode: GroupCallMode, callId: Long) -> Unit)? = null

    /** PHASE 3B: fired on the main thread once this device is itself a confirmed
     *  participant (right after [startGroupCall]/[acceptGroupCall], or — for the
     *  GO — the moment it registers a client-originated invite). */
    var onGroupCallStarted: ((mode: GroupCallMode, callId: Long) -> Unit)? = null

    /** PHASE 3B: fired on the main thread with the full current participant id list
     *  whenever it changes (join/leave) — mirrors [onRosterUpdated]'s shape. */
    var onGroupCallParticipants: ((List<Long>) -> Unit)? = null

    /** PHASE 3B: fired on the main thread when the debounced active speaker changes —
     *  null means nobody is currently speaking. [pinned] is true if this is a host
     *  pin overriding VAD rather than an ordinary speaker switch. */
    var onGroupCallSpeaker: ((nodeId: Long?, pinned: Boolean) -> Unit)? = null

    /** PHASE 3B: fired on the main thread when the group call ends for this device —
     *  local leave, the call falling below 2 participants, or the mesh session dying. */
    var onGroupCallEnded: ((reason: String) -> Unit)? = null

    /** PHASE 3B: fired on the main thread when this device's own [startGroupCall] (as
     *  a non-GO joiner would experience if the call were already full) or
     *  [acceptGroupCall] was rejected — currently only for the 8-participant cap. */
    var onGroupCallRejected: ((reason: String) -> Unit)? = null

    /** PHASE 3C: fired on the main thread whenever any participant's (including this
     *  device's own) camera state changes — the grid should flip that nodeId's tile
     *  between live video and its avatar placeholder. */
    var onGroupCallCamState: ((nodeId: Long, on: Boolean) -> Unit)? = null

    /** PHASE 3C: fired on the main thread when THIS device's own camera-on request
     *  was denied because MAX_LIVE_CAMERAS was already reached. */
    var onGroupCallCamDenied: (() -> Unit)? = null

    /** PHASE 3C: fired on the main thread with a REMOTE sender's actual decoded
     *  tile size — per-participant equivalent of [onVideoSize], which stays 1:1-only. */
    var onGroupTileVideoSize: ((nodeId: Long, width: Int, height: Int) -> Unit)? = null

    /** PHASE 5A: fired on the main thread whenever a TYPE_SOS is received from any
     *  sender, including repeats and a CLEAR (see [MeshSosManager.SosEntry.active]).
     *  Independent of any call state — see MeshSosManager's class doc. */
    var onSosEntry: ((MeshSosManager.SosEntry) -> Unit)? = null

    /** PHASE 5A: fired on the main thread whenever a TYPE_FIND_RESP is received. */
    var onFindResponse: ((MeshSosManager.SosEntry) -> Unit)? = null

    /** PHASE 5BC: fired on the main thread whenever this device's own active SOS
     *  gets a new acker — (seenByCount, otherMemberCount). */
    var onSosAckProgress: ((Int, Int) -> Unit)? = null

    /** PHASE 5BC: fired on the main thread whenever a peer's ledger track gets a
     *  fresh entry (TYPE_POSITION or TYPE_SOS with a fix) — the party-status
     *  screen's cue to refresh that member's row. */
    var onPositionUpdated: ((Long) -> Unit)? = null

    /** PHASE 5BC: current set of active (non-CLEAR) SOS sender nodeIds — never
     *  includes [localNodeId], since [MeshSosManager.sosEntries] is only ever
     *  populated from RECEIVED frames. Feed this to SosAlarm.onActiveSendersChanged
     *  after every [onSosEntry] callback.
     *  BUG 1 FIX 3: also requires [MeshSosManager.SosEntry.alarmable] — a
     *  historical replay or a locally auto-stopped sender stays in [sosEntries]
     *  (still shown in the UI) but drops out of THIS set, so it can never
     *  re-trigger SosAlarm. */
    fun activeSosSenderIds(): Set<Long> = meshSosManager.sosEntries.filterValues { it.active && it.alarmable }.keys

    /** PHASE 5BC: this device's own SOS ack progress, (seenBy, total). */
    fun sosAckProgress(): Pair<Int, Int> = meshSosManager.ackProgress()

    /** BUG 1 FIX 2: pass-through to MeshSosManager.suppressAlarmFor — see that
     *  method's doc. Wired from SosAlarm.onAutoStopTimeout by the owner. */
    fun suppressSosAlarmFor(senderIds: Set<Long>) = meshSosManager.suppressAlarmFor(senderIds)

    /** BUG 1 FIX 4: pass-through to MeshSosManager.clearStoredAlerts — "Clear
     *  stored alerts" in settings. Returns the count of entries cleared. */
    fun clearStoredSosAlerts(): Int = meshSosManager.clearStoredAlerts()

    /** PHASE 6 TRACK B: beacon mode's radio-shedding knobs — see SosBeaconMode.kt.
     *  Deliberately thin pass-throughs so beacon mode never needs to know
     *  MeshSosManager/OfflineLocationProvider exist, same "owner never leaks
     *  internals" pattern as the rest of this class's public surface. */
    fun setPositionBroadcastIntervalMs(ms: Long) = meshSosManager.setPositionBroadcastIntervalMs(ms)
    fun setLocationCadence(ms: Long) = locationProvider.setCadence(ms)
    fun resumeNormalLocationCadence() = locationProvider.resumeNormalCadence()

    /** PHASE 6 TRACK B: current OTHER-member roster size — SosTriggers' "no
     *  motion for 20 minutes while separated from the party" check uses this to
     *  tell "alone" apart from "merely stationary with the group". */
    fun currentOtherMemberCount(): Int = (routingTable.size() - 1).coerceAtLeast(0)

    fun setDisplaySurface(surface: Surface) {
        displaySurface = surface
    }

    /** PHASE 3C: registers (or, with a null [surface], unregisters — e.g. on
     *  surfaceDestroyed) the Surface a given remote participant's grid tile decodes
     *  into. Safe to call before that participant's video has started — the decoder
     *  is configured lazily on their first TYPE_CONFIG/TYPE_FRAME (see
     *  [configureGroupDecoder]), which requires this to already be registered.
     *
     *  FIX: the tile Surface and the sender's one-shot TYPE_CONFIG broadcast race
     *  each other — if csd already arrived and was buffered (see dispatchLocal's
     *  TYPE_FRAME branch, which now keeps it buffered on a failed configure instead
     *  of discarding it) before this Surface showed up, retry the configure right
     *  here now that it exists, rather than waiting on a TYPE_FRAME that will just
     *  see decoderReady already stuck false with nothing left to configure from. */
    fun setGroupTileSurface(nodeId: Long, surface: Surface?) {
        if (surface == null) {
            groupTileSurfaces.remove(nodeId)
            return
        }
        groupTileSurfaces[nodeId] = surface
        if (groupDecoderReady[nodeId] != true) {
            val csd = groupPendingCsd[nodeId]
            if (!csd.isNullOrEmpty()) {
                log("OFFTRACE: MEDIA: csd retry on surface for ${MeshFrame.hex(nodeId)}")
                configureGroupDecoder(nodeId, combineByteArrays(csd), requestKeyframeAfter = true)
                if (groupDecoders.containsKey(nodeId)) {
                    groupPendingCsd.remove(nodeId)
                    groupDecoderReady[nodeId] = true
                }
            }
        }
    }

    /** PHASE 3C: registers (or, with null, unregisters) this device's OWN grid-tile
     *  preview surface — a second Camera2 capture target alongside the encoder's
     *  input surface (see [openCamera]). No effect on a 1:1 call (no self-tile
     *  there). Safe to call before the camera has started — the normal
     *  [openCamera]/[startCaptureSession] first-time path picks it up naturally.
     *
     *  FIX: if the capture session is ALREADY running without a preview target
     *  (the common case — the tile Surface is created asynchronously by the
     *  Activity's view layout, typically after the camera has already opened and
     *  started its encoder-only session), that running session is never otherwise
     *  told about a later-arriving Surface — [openCamera] only reads
     *  [localPreviewSurface] once, at initial session construction. Detect that
     *  case here and rebuild the session in place (same CameraDevice, same
     *  encoder — see [rebuildCaptureSessionForPreview]) rather than leaving local
     *  preview permanently blank for the rest of the call. */
    fun setLocalPreviewSurface(surface: Surface?) {
        localPreviewSurface = surface
        if (surface == null || !surface.isValid) return
        if (surface === capturingWithPreviewSurface) return // running session already has it
        if (surface === previewRebuildPendingFor) return // a rebuild for it is already in flight
        val handler = cameraHandler ?: return // camera hasn't started yet — startCaptureSession will pick it up
        previewRebuildPendingFor = surface
        handler.post { rebuildCaptureSessionForPreview(surface) }
    }

    /** GO/client-agnostic, camera-thread-only: closes and recreates the capture
     *  session with BOTH the encoder surface and [surface] as targets, on the
     *  SAME already-open [cameraDevice] — never reopens the camera, never
     *  restarts the encoder, never touches audio. No-op if anything has moved on
     *  since this was scheduled (surface superseded/cleared, session already
     *  rebuilt for it, camera not currently open). */
    private fun rebuildCaptureSessionForPreview(surface: Surface) {
        if (previewRebuildPendingFor === surface) previewRebuildPendingFor = null
        if (surface !== localPreviewSurface) return // superseded/cleared since this was posted
        if (surface === capturingWithPreviewSurface) return // already applied
        if (!surface.isValid) return
        val camera = cameraDevice ?: return
        val encSurface = encoderInputSurface ?: return
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        startCaptureSession(camera, encSurface)
        log("OFFTRACE: MEDIA: capture session rebuilt with preview surface")
    }

    /** Direct 1:1 chat to the current call partner. Returns false (sends nothing) if
     *  there's no active call — use [sendGroupChat] for broadcast. Safe from any
     *  thread; the actual write is posted off-caller onto [chatHandler]. */
    fun sendChat(text: String): Boolean {
        val target = activeCallPeerId ?: run {
            logW("MEDIA: sendChat with no active call — use sendGroupChat for broadcast")
            return false
        }
        return enqueueChatSend(target, text)
    }

    /** PHASE 3: group chat — dst=BROADCAST, usable any time this device is joined to
     *  the mesh, independent of whether a 1:1 call is active. */
    fun sendGroupChat(text: String): Boolean = enqueueChatSend(MeshFrame.BROADCAST_ID, text)

    /** PHASE 5A: starts (or restarts, with a possibly-updated message) this
     *  device's own SOS beacon — see MeshSosManager.startSos. Usable any time
     *  this device is joined to the mesh, independent of any 1:1 or group call.
     *  PHASE 5BC: no longer starts location from cold — the mesh session itself
     *  already keeps it warm (see start()) — this only requests a [burst] to try
     *  to upgrade the tier before the first beacon's fix is read. */
    fun startSos(message: String? = null) {
        locationProvider.burst()
        meshSosManager.startSos(message)
        // PHASE 6 TRACK B1: SOS firing on THIS (sending) device is what enters
        // beacon mode — sheds camera/mic/call, drops broadcast/GPS cadence, and
        // duty-cycles Wi-Fi. See SosBeaconMode's class doc for exactly what that
        // does and does not mean on stock Android.
        sosBeaconMode.enter(this)
        bleBeacon.setSosActive(true)
    }

    /** PHASE 5A: clears this device's own SOS beacon — see MeshSosManager.stopSos.
     *  PHASE 5BC: no longer stops location — it's session-scoped now (see stop()),
     *  since TYPE_POSITION ambient broadcasts and the ledger need it whether or
     *  not an SOS is active. */
    fun stopSos() {
        meshSosManager.stopSos()
        // PHASE 6 TRACK B1: the emergency is resolved — leave beacon mode
        // automatically (a user who wants OUT of beacon mode without clearing
        // the SOS still has SosBeaconMode.exit() as a separate manual escape
        // hatch, per that class's doc).
        sosBeaconMode.exit()
        bleBeacon.setSosActive(meshSosManager.sosEntries.any { it.value.active })
    }

    /** PHASE 5A: "where are you" — see MeshSosManager.sendFindRequest. */
    fun sendFindRequest(targetNodeId: Long) {
        meshSosManager.sendFindRequest(targetNodeId)
    }

    private fun enqueueChatSend(dst: Long, text: String): Boolean {
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
            writeFrame(dst, TYPE_CHAT, bytes)
            log("MEDIA: chat sent len=${bytes.size} dst=${if (dst == MeshFrame.BROADCAST_ID) "broadcast" else MeshFrame.hex(dst)}")
            // PHASE 6 TRACK A5: group chat also rides the generalized carrier, so
            // a member who's currently out of range gets it when they return —
            // 1:1 direct chat is unchanged/not carried (out of this track's
            // stated scope). Peers already in the roster just got this live via
            // the writeFrame above, so they're pre-seeded as already-delivered —
            // only someone who reconnects LATER will ever be offered it.
            if (dst == MeshFrame.BROADCAST_ID) {
                val alreadyPresent = routingTable.roster().map { it.nodeId }.toSet()
                carrier.put(
                    MeshCarrier.newMsgId(), localNodeId, MeshFrame.BROADCAST_ID, TYPE_CHAT, bytes,
                    expiryMins = 24 * 60,
                    alreadyDeliveredTo = alreadyPresent
                )
            }
        }
    }

    /** PHASE 3: initiator API — places a 1:1 call to a specific roster member (found
     *  via a TYPE_ROSTER broadcast, see [onRosterUpdated]). Transparent whether that
     *  member is directly connected or must be relayed through the GO; addressing
     *  (dst=targetNodeId) is all that matters, same as every other frame. No-op with
     *  a log if this device is already in a different call. */
    fun placeCall(targetNodeId: Long, targetName: String, mode: CallMode) {
        if (!running.get() || !alive.get()) {
            logW("MEDIA: placeCall while not connected — ignoring")
            return
        }
        if (!tryBeginCall(targetNodeId, targetName)) {
            logW("MEDIA: placeCall to ${MeshFrame.hex(targetNodeId)} while already in a call — ignoring")
            return
        }
        pendingOutgoingCallPeerId = targetNodeId
        log("MEDIA: placing call to ${MeshFrame.hex(targetNodeId)} ($targetName) mode=$mode " +
            "— v1 media calls are 1:1 only, relayed if not directly connected")
        writeFrame(targetNodeId, TYPE_MODE, byteArrayOf(mode.wireId))
        startSendersForMode(mode)
        mainHandler.post { onModeResolved?.invoke(targetNodeId, targetName, mode) }
    }

    /** PHASE 3: ends the current call only — sends TYPE_HANGUP to the partner and
     *  tears down this call's camera/mic/decoder pipeline, but leaves the mesh
     *  connection (roster, other links) untouched. No-op if no call is active. */
    fun endCall() {
        val partner = activeCallPeerId ?: run {
            logW("MEDIA: endCall with no active call — ignoring")
            return
        }
        writeFrame(partner, TYPE_HANGUP, ByteArray(0))
        endLocalCallState("local hangup")
    }

    /** Atomically starts a call with peerId unless one is already active with someone
     *  else, OR a group call is active (PHASE 3B: the two are mutually exclusive —
     *  see the class doc). Returns false (no state changed) if busy; true
     *  (activeCallPeerId now set) otherwise — including the harmless case where
     *  peerId was already the active partner (idempotent, e.g. a duplicate MODE
     *  frame). */
    private fun tryBeginCall(peerId: Long, peerName: String): Boolean {
        synchronized(callLock) {
            if (groupCall != null) return false
            val active = activeCallPeerId
            if (active != null && active != peerId) return false
            activeCallPeerId = peerId
            activeCallPeerName = peerName
            return true
        }
    }

    // PHASE 7A STEP 5: falls back to a SHORT nodeId hex (matches OfflineCallActivity's
    // shortId()) when no verified name is known yet, not the full 16-hex-char id.
    private fun nameFor(nodeId: Long): String = knownNames[nodeId] ?: MeshFrame.hex(nodeId).takeLast(6)

    // ── PHASE 3B: group calls ──────────────────────────────────────────────────

    private data class PendingGroupInvite(val callId: Long, val mode: GroupCallMode, val initiatorId: Long)
    @Volatile private var pendingInvite: PendingGroupInvite? = null

    /** A client has exactly one physical link (the GO) — every group-call control
     *  frame a client sends (ACCEPT/LEAVE/VAD) is addressed to it directly, same
     *  reasoning as [writeFrame]'s client branch, just made explicit here since these
     *  (unlike most frames) are never meant for BROADCAST or a third member. Null on
     *  the GO itself (never needed there) or before the uplink resolves. */
    private fun uplinkNodeId(): Long? = if (isGroupOwner) null else routingTable.all().firstOrNull()?.nodeId

    /** Atomically starts (or, if [callId] matches, idempotently rejoins) group-call
     *  state unless a DIFFERENT call — 1:1 or group — is already active. */
    private fun tryBeginGroupCall(callId: Long, mode: GroupCallMode, initiatorId: Long): Boolean {
        synchronized(callLock) {
            if (activeCallPeerId != null) return false
            val existing = groupCall
            if (existing != null && existing.callId != callId) return false
            if (existing == null) groupCall = GroupCallState(callId, mode, initiatorId)
            return true
        }
    }

    /** PHASE 3B: initiator API — proposes a new group call to the whole mesh
     *  (dst=BROADCAST). This device joins immediately and optimistically (same
     *  "fire and hope" pattern as [placeCall]) rather than waiting for anyone else's
     *  ack; no-ops with a log if already in a different call. */
    fun startGroupCall(mode: GroupCallMode) {
        if (!running.get() || !alive.get()) {
            logW("MEDIA: startGroupCall while not connected — ignoring")
            return
        }
        val callId = kotlin.random.Random.nextLong()
        if (!tryBeginGroupCall(callId, mode, initiatorId = localNodeId)) {
            logW("MEDIA: startGroupCall while already in a call — ignoring")
            return
        }
        log("MEDIA: starting group call callId=${MeshFrame.hex(callId)} mode=$mode " +
            "— group calls are many-to-many audio, but exactly one video stream at a time " +
            "— RINGING until a 2nd participant joins (see FIX 1)")
        if (isGroupOwner) {
            // FIX 1: addGroupCallParticipant is GO-only (it broadcasts the
            // authoritative TYPE_PARTICIPANTS) — a non-GO initiator must NOT call it
            // for its own join (that was the other half of the self-destruct defect:
            // a client's own local join was triggering a bogus authoritative-looking
            // broadcast). See the else branch below.
            startGroupCallMixer()
            addGroupCallParticipant(localNodeId, rejectDst = null)
        } else {
            // Not authoritative — just this device's own local view that it's "in"
            // the call it just proposed. The GO's own TYPE_PARTICIPANTS (once it
            // processes our TYPE_CALL_INVITE below, see handleCallInviteFrame) is the
            // real source of truth and will overwrite this via handleParticipantsFrame.
            groupCall?.participants?.add(localNodeId)
        }
        startGroupCallAudio()
        // FIX D: TYPE_CALL_INVITE must go out BEFORE the cam-on request. For a
        // non-GO initiator, setGroupCallCameraOn(true) sends a unicast TYPE_CAM to
        // the GO — both frames travel the same single per-link FIFO queue
        // (writeFrame always routes a client's outbound frames through its one
        // uplink regardless of dst), so whichever is enqueued first is
        // GUARANTEED to arrive first. With cam-on sent first (the old order), the
        // GO's handleCamRequestFrame saw `groupCall == null` (its own copy of
        // this call doesn't exist until it processes the invite that hadn't
        // arrived yet) and silently dropped the request every single time — not
        // a race, a 100%-reproducible ordering bug. Sending the invite first
        // guarantees the GO's handleCallInviteFrame has already created its
        // groupCall by the time the cam-on request lands.
        writeFrame(MeshFrame.BROADCAST_ID, TYPE_CALL_INVITE, encodeCallInvite(mode, callId))
        // PHASE 3C: every participant joins camera-ON for a VIDEO call — subject to
        // the same MAX_LIVE_CAMERAS arbitration as any later manual toggle (see
        // setGroupCallCameraOn); a founder over the cap simply can't happen (count
        // starts at 0), but this still goes through the real request path rather
        // than a special-cased "always granted" founder shortcut.
        if (mode == GroupCallMode.VIDEO) setGroupCallCameraOn(true)
        scheduleRingingTimeout(callId)
        mainHandler.post { onGroupCallStarted?.invoke(mode, callId) }
    }

    /** FIX 1c: a founding call that never reaches 2 participants within
     *  GROUP_CALL_RINGING_TIMEOUT_MS ends itself cleanly ("no one answered") rather
     *  than ringing forever. The wire has no dedicated cancel/timeout frame, so this
     *  is a purely local decision — every device holding its own copy of this call
     *  (the initiator, and separately the GO if it isn't the initiator — see the
     *  call site in handleCallInviteFrame) schedules and evaluates this
     *  independently against its own [GroupCallState.established]. */
    private fun scheduleRingingTimeout(callId: Long) {
        mainHandler.postDelayed({
            val gc = groupCall
            if (gc != null && gc.callId == callId && !gc.established) {
                log("MESH: group call callId=${MeshFrame.hex(callId)} ringing timeout — no one answered")
                endGroupCallState("no one answered")
            }
        }, GROUP_CALL_RINGING_TIMEOUT_MS)
    }

    /** PHASE 3B: accepts the most recent invite for [callId] (see [onGroupCallInvite]
     *  — the transport tracks which invite is "pending" internally so the caller only
     *  needs to echo the id back). No-op with a log if the invite is stale/unknown or
     *  this device is already in a different call. */
    fun acceptGroupCall(callId: Long) {
        if (!running.get() || !alive.get()) {
            logW("MEDIA: acceptGroupCall while not connected — ignoring")
            return
        }
        val invite = pendingInvite?.takeIf { it.callId == callId } ?: run {
            logW("MEDIA: acceptGroupCall for unknown/stale callId=${MeshFrame.hex(callId)} — ignoring")
            return
        }
        if (!tryBeginGroupCall(invite.callId, invite.mode, invite.initiatorId)) {
            logW("MEDIA: acceptGroupCall while already in a call — ignoring")
            return
        }
        log("MEDIA: accepted group call callId=${MeshFrame.hex(callId)}")
        if (isGroupOwner) {
            startGroupCallMixer()
            addGroupCallParticipant(localNodeId, rejectDst = null)
        } else {
            val go = uplinkNodeId()
            if (go != null) writeFrame(go, TYPE_CALL_ACCEPT, encodeCallId(callId))
        }
        startGroupCallAudio()
        // PHASE 3C: camera-ON by default for a VIDEO call, same as the founder path
        // in startGroupCall — arbitrated by the GO against MAX_LIVE_CAMERAS; a
        // joiner past the cap simply stays audio-only (see onGroupCallCamDenied)
        // until a slot frees, per the "never silent failure" requirement.
        if (invite.mode == GroupCallMode.VIDEO) setGroupCallCameraOn(true)
        pendingInvite = null
        mainHandler.post { onGroupCallStarted?.invoke(invite.mode, callId) }
    }

    /** PHASE 3B: leaves the current group call only — the mesh session (and this
     *  device's roster membership) is untouched, same spirit as [endCall]. No-op if
     *  no group call is active. */
    fun leaveGroupCall() {
        val gc = groupCall ?: run {
            logW("MEDIA: leaveGroupCall with no active group call — ignoring")
            return
        }
        if (isGroupOwner) {
            removeGroupCallParticipant(localNodeId)
        } else {
            val go = uplinkNodeId()
            if (go != null) writeFrame(go, TYPE_CALL_LEAVE, encodeCallId(gc.callId))
        }
        endGroupCallState("local leave")
    }

    /** PHASE 3B point 8: host pin. Only takes effect on a device that is BOTH the GO
     *  AND the call's initiator — "GO enforces host-only", and pin requests have no
     *  dedicated wire frame in this phase's protocol, so a non-GO host currently has
     *  no path to request one remotely (see [canPin] — the UI should hide the pin
     *  affordance rather than let it silently no-op). [nodeId] = null clears the pin,
     *  reverting to plain VAD-driven speaker selection. */
    fun requestPin(nodeId: Long?) {
        if (!canPin()) {
            logW("MEDIA: pin request ignored — not host+GO")
            return
        }
        val gc = groupCall ?: return
        gc.pinnedId = nodeId
        log("MEDIA: host pin -> ${nodeId?.let { MeshFrame.hex(it) } ?: "cleared"}")
        if (nodeId != null) {
            applySpeakerChange(nodeId, pinned = true)
            broadcastSpeaker(nodeId, pinned = true)
        }
        // Clearing the pin doesn't force an immediate switch — the next VAD-driven
        // debounce tick (see onRawActiveSpeakersChanged) picks the speaker back up
        // naturally, same as if no pin had ever been set.
    }

    /** Whether THIS device can currently exercise [requestPin] — see that function's
     *  doc for why pinning is GO+host-only in this phase. */
    fun canPin(): Boolean = isGroupOwner && groupCall?.initiatorId == localNodeId

    private fun encodeCallInvite(mode: GroupCallMode, callId: Long): ByteArray {
        val buf = ByteBuffer.allocate(9)
        buf.put(mode.wireId)
        buf.putLong(callId)
        return buf.array()
    }

    private fun encodeCallId(callId: Long): ByteArray = ByteBuffer.allocate(8).putLong(callId).array()

    private fun encodeParticipants(ids: List<Long>): ByteArray {
        val capped = ids.take(255)
        val buf = ByteBuffer.allocate(1 + capped.size * 8)
        buf.put(capped.size.toByte())
        capped.forEach { buf.putLong(it) }
        return buf.array()
    }

    private fun encodeSpeaker(nodeId: Long, pinned: Boolean): ByteArray {
        val buf = ByteBuffer.allocate(9)
        buf.putLong(nodeId)
        buf.put(if (pinned) 1 else 0)
        return buf.array()
    }

    /** GO-only: adds a participant to the authoritative set (capped at
     *  MAX_GROUP_PARTICIPANTS — point 10), rebroadcasting TYPE_PARTICIPANTS on
     *  success. Past the cap, [rejectDst] (if given — null for this device's own
     *  join, which can never be over-cap) gets a TYPE_BUSY(reason=full) instead. */
    private fun addGroupCallParticipant(nodeId: Long, rejectDst: Long?): Boolean {
        val gc = groupCall ?: return false
        if (nodeId in gc.participants) return true
        if (gc.participants.size >= MAX_GROUP_PARTICIPANTS) {
            logW("MESH: group call full ($MAX_GROUP_PARTICIPANTS) — rejecting join from ${MeshFrame.hex(nodeId)}")
            if (rejectDst != null) writeFrame(rejectDst, TYPE_BUSY, byteArrayOf(BUSY_REASON_CALL_FULL))
            return false
        }
        gc.participants.add(nodeId)
        evaluateGroupCallAfterChange()
        return true
    }

    /** GO-only: removes a participant; ends the call for everyone once fewer than 2
     *  remain (point 2). */
    private fun removeGroupCallParticipant(nodeId: Long) {
        val gc = groupCall ?: return
        if (!gc.participants.remove(nodeId)) return
        groupCallMixer?.removeParticipant(nodeId)
        // PHASE 3C: free their camera slot (if any) and this device's own decoder/
        // tile resources for them — a departed participant must never keep
        // occupying a MAX_LIVE_CAMERAS slot, and their stale decoder would just leak.
        gc.camStates.remove(nodeId)
        releaseGroupDecoder(nodeId)
        releaseGroupAudioDecoder(nodeId)
        if (gc.activeSpeakerId == nodeId) {
            // Route through applySpeakerChange (not a direct field mutation) so the
            // GO's OWN decoder/camera state and onGroupCallSpeaker UI callback reset
            // too — this runs on the GO, which never "receives" its own broadcast the
            // way a client's handleSpeakerFrame would.
            applySpeakerChange(null, pinned = false)
            broadcastSpeaker(null, pinned = false)
        }
        evaluateGroupCallAfterChange()
    }

    /** GO-only: rebroadcasts the current membership and, once the call has ever
     *  reached 2+ participants (see [GroupCallState.established] — FIX 1), ends it
     *  if a change just dropped it back below 2 (for the GO itself too — everyone
     *  else learns via that same final TYPE_PARTICIPANTS broadcast, see
     *  [handleParticipantsFrame]). A founding call sitting at 1 participant is
     *  RINGING, not ending — see [scheduleRingingTimeout] for the "nobody answered"
     *  case instead. */
    private fun evaluateGroupCallAfterChange() {
        val gc = groupCall ?: return
        if (gc.participants.size >= 2) gc.established = true
        broadcastParticipants()
        if (gc.established && gc.participants.size < 2) {
            log("MESH: group call ending — fewer than 2 participants remain")
            endGroupCallState("fewer than 2 participants remain")
        }
    }

    private fun broadcastParticipants() {
        val gc = groupCall ?: return
        val ids = gc.participants.toList()
        writeFrame(MeshFrame.BROADCAST_ID, TYPE_PARTICIPANTS, encodeParticipants(ids))
        mainHandler.post { onGroupCallParticipants?.invoke(ids) }
    }

    private fun broadcastSpeaker(nodeId: Long?, pinned: Boolean) {
        writeFrame(MeshFrame.BROADCAST_ID, TYPE_SPEAKER, encodeSpeaker(nodeId ?: NO_SPEAKER_ID, pinned))
    }

    // ── PHASE 3C: per-participant camera toggle (multi-tile grid video) ────────

    private fun encodeCam(nodeId: Long, on: Boolean): ByteArray {
        val buf = ByteBuffer.allocate(9)
        buf.putLong(nodeId)
        buf.put(if (on) 1 else 0)
        return buf.array()
    }

    /** GO-only: true if [nodeId] already holds a slot (idempotent re-request) or a
     *  slot is free under MAX_LIVE_CAMERAS. */
    private fun camSlotAvailable(nodeId: Long): Boolean {
        val gc = groupCall ?: return false
        return gc.camStates[nodeId] == true || gc.camStates.count { it.value } < MAX_LIVE_CAMERAS
    }

    /** GO-only: authoritative application of an accepted camera-state change — sets
     *  [GroupCallState.camStates] and, for THIS device's own id, actually starts or
     *  stops the local camera+encoder (a client applies the same state via
     *  [handleCamBroadcastFrame], which never touches its own camera unless the
     *  changed nodeId happens to be itself). For any OTHER participant turning off,
     *  frees their tile's decoder immediately rather than waiting for them to leave
     *  the call entirely — same resource-promptness spirit as
     *  [GroupCallMixer.computeTopSpeakers]'s decoder release. */
    private fun applyCamState(nodeId: Long, on: Boolean) {
        val gc = groupCall ?: return
        gc.camStates[nodeId] = on
        if (nodeId == localNodeId) {
            groupCallCameraPending = false
            if (on) {
                if (encoder == null) startEncoderThenCamera()
            } else {
                releaseCamera()
                releaseEncoder()
            }
        } else if (!on) {
            releaseGroupDecoder(nodeId)
        } else {
            // FIX: a remote participant's camera just came on — if this device is
            // also sending video, their freshly-created tile for OUR stream is
            // exactly the kind of new decoder that benefits from an immediate IDR
            // instead of waiting out KEY_I_FRAME_INTERVAL.
            requestKeyFrame()
        }
        mainHandler.post { onGroupCallCamState?.invoke(nodeId, on) }
    }

    private fun broadcastCam(nodeId: Long, on: Boolean) {
        writeFrame(MeshFrame.BROADCAST_ID, TYPE_CAM, encodeCam(nodeId, on))
    }

    /** FIX G: single source of truth for "is my own camera actually running" —
     *  the same [captureSession]-liveness check [setGroupCallCameraOn]'s guard
     *  uses, exposed so the Activity's toggle button can decide what to request
     *  from reality rather than a separately-tracked UI mirror that could drift
     *  out of sync with it (e.g. after a dropped/denied request). */
    fun isLocalCameraOn(): Boolean = captureSession != null

    /** PHASE 3C: public toggle API — the Activity's camera button calls this
     *  directly. Turning ON is arbitrated against MAX_LIVE_CAMERAS (this device's
     *  own request goes through the exact same acceptance path a remote
     *  participant's would, via [camSlotAvailable] — no special-cased "founder
     *  always wins" shortcut); turning OFF is always accepted, since it only ever
     *  frees a slot. The GO decides its own request synchronously (no wire round
     *  trip needed, same reasoning [GroupCallMixer.updateVad] uses for the GO's own
     *  VAD); a client sends a request to the GO and waits for either the resulting TYPE_CAM
     *  broadcast (camera actually starts then, in [applyCamState]) or
     *  TYPE_CAM_DENIED — it never starts its camera optimistically.
     *
     *  FIX E: the ON guard now checks whether the capture session is ACTUALLY
     *  live ([captureSession] non-null, set only inside startCaptureSession's
     *  onConfigured), never [groupCallCameraPending] alone — that flag records
     *  intent ("a request is in flight"), and if that request is ever silently
     *  dropped (network hiccup, or — before FIX D — a guaranteed ordering bug),
     *  it would stay true forever with no camera ever running, making every
     *  later tap a permanent, silent no-op. deviceOpen/sessionLive are derived
     *  fresh from the live cameraDevice/captureSession fields on every call, so
     *  they self-heal automatically on any open/configure failure (those fields
     *  are already nulled by CameraDevice.StateCallback's onError/onDisconnected)
     *  — no separate failure-handling state to keep in sync. */
    fun setGroupCallCameraOn(on: Boolean) {
        val gc = groupCall
        val deviceOpen = cameraDevice != null
        val sessionLive = captureSession != null
        val callActiveNow = gc != null
        log("OFFTRACE: MEDIA: setGroupCallCameraOn(request=$on) flag=$groupCallCameraPending " +
            "cameraOn=$cameraOn deviceOpen=$deviceOpen sessionLive=$sessionLive callActive=$callActiveNow")
        if (gc == null) {
            logW("OFFTRACE: MEDIA: cam request ignored — reason=no_active_call")
            return
        }
        if (gc.mode != GroupCallMode.VIDEO) {
            logW("OFFTRACE: MEDIA: cam request ignored — reason=not_video_mode")
            return
        }
        if (on && sessionLive) {
            logW("OFFTRACE: MEDIA: cam request ignored — reason=already_running")
            return
        }
        if (isGroupOwner) {
            if (on && !camSlotAvailable(localNodeId)) {
                logW("MESH: cam request denied, ${gc.camStates.count { it.value }} live")
                mainHandler.post { onGroupCallCamDenied?.invoke() }
                return
            }
            applyCamState(localNodeId, on)
            broadcastCam(localNodeId, on)
        } else {
            val go = uplinkNodeId() ?: run {
                logW("OFFTRACE: MEDIA: cam request ignored — reason=no_uplink")
                return
            }
            if (on) groupCallCameraPending = true
            writeFrame(go, TYPE_CAM, encodeCam(localNodeId, on))
        }
    }

    /** PHASE 3C: explicit hard mute — independent of (and layered on top of) the
     *  existing VAD "don't transmit silence" gate, forcing VAD itself to report
     *  not-speaking while muted rather than merely suppressing the wire send (so a
     *  muted participant's tile also stops showing a stale "speaking" indicator) —
     *  see the `!groupCallMicMuted &&` guard in [startAudioSender]'s group branch. */
    fun setGroupCallMicMuted(muted: Boolean) {
        groupCallMicMuted = muted
    }

    /** GO-only: arbitrates an inbound camera-state request from [header.srcId]
     *  against MAX_LIVE_CAMERAS. Off requests are never denied. */
    private fun handleCamRequestFrame(header: MeshFrame.Header, payload: ByteArray) {
        if (payload.size != 9) {
            logW("MESH: malformed CAM len=${payload.size} — ignoring")
            return
        }
        val nodeId = ByteBuffer.wrap(payload, 0, 8).long
        val on = payload[8].toInt() != 0
        if (nodeId != header.srcId) {
            logW("MESH: CAM nodeId mismatch from ${MeshFrame.hex(header.srcId)} — ignoring")
            return
        }
        val gc = groupCall ?: return
        if (gc.mode != GroupCallMode.VIDEO) return // camera state is meaningless in an audio-only group call
        if (on && !camSlotAvailable(nodeId)) {
            writeFrame(nodeId, TYPE_CAM_DENIED, ByteArray(0))
            logW("MESH: cam request denied, ${gc.camStates.count { it.value }} live")
            return
        }
        applyCamState(nodeId, on)
        broadcastCam(nodeId, on)
    }

    /** Client-side: applies the GO's authoritative camera-state announcement —
     *  covers every participant including this device's own id (the confirmation
     *  of its own earlier request). */
    private fun handleCamBroadcastFrame(payload: ByteArray) {
        if (payload.size != 9) {
            logW("MESH: malformed CAM len=${payload.size} — ignoring")
            return
        }
        val nodeId = ByteBuffer.wrap(payload, 0, 8).long
        val on = payload[8].toInt() != 0
        applyCamState(nodeId, on)
    }

    /** Client-side: this device's own most recent camera-on request was denied. */
    private fun handleCamDeniedFrame() {
        groupCallCameraPending = false
        mainHandler.post { onGroupCallCamDenied?.invoke() }
    }

    /** GO-only: pushes an unresolved-until-now peer the current call's full state —
     *  invite, participants, and active speaker (if any) — the moment their HELLO
     *  resolves, so joining mid-call (point 2's "late join") needs no separate
     *  request/response frame; the GO just proactively re-sends what a fresh member
     *  missed. */
    private fun sendGroupCallStateTo(peerId: Long) {
        val gc = groupCall ?: return
        writeFrame(peerId, TYPE_CALL_INVITE, encodeCallInvite(gc.mode, gc.callId))
        writeFrame(peerId, TYPE_PARTICIPANTS, encodeParticipants(gc.participants.toList()))
        gc.activeSpeakerId?.let { writeFrame(peerId, TYPE_SPEAKER, encodeSpeaker(it, gc.pinnedId == it)) }
        // FIX: this device's own one-shot TYPE_CONFIG (see drainEncoderLoop) already
        // went out, if at all, before this peer's connection even existed — replay
        // the last csd we actually sent so a late joiner isn't permanently stuck
        // with nothing to configure a decoder for THIS device's stream from.
        lastCsdOut?.let {
            writeFrame(peerId, TYPE_CONFIG, it)
            log("OFFTRACE: MEDIA: replayed csd to ${MeshFrame.hex(peerId)}")
            requestKeyFrame()
        }
        // PHASE 3C: a late joiner otherwise sees every existing live camera as "off"
        // until its owner happens to toggle it again — proactively replay the
        // current on-state of each so their grid renders correctly from the start.
        gc.camStates.filterValues { it }.keys.forEach { writeFrame(peerId, TYPE_CAM, encodeCam(it, true)) }
    }

    /** Tears down just this device's OWN group-call media pipeline and clears its
     *  local group-call state. Safe to call when no group call is active (no-op).
     *  Mirrors [endLocalCallState]'s call-only (not mesh-wide) teardown scope. */
    private fun endGroupCallState(reason: String) {
        synchronized(callLock) {
            if (groupCall == null) return
            groupCall = null
        }
        speakerCandidateId = null
        speakerCandidateSinceMs = 0L
        pendingInvite = null
        audioSendersStartedForCallId = null
        groupCallMixer?.stop()
        groupCallMixer = null
        // FIX 1: same ordering as endLocalCallState — flip false and join every
        // call-scoped media thread before releasing the resources they touch.
        callActive.set(false)
        stopCallThreads()
        releaseCamera()
        releaseEncoder()
        releaseDecoder()
        releaseAllGroupDecoders()
        releaseAllGroupAudioDecoders()
        groupOpusOutputSampleRate = AUDIO_SAMPLE_RATE
        groupOpusOutputChannelCount = OPUS_CHANNEL_COUNT
        groupTileSurfaces.clear()
        localPreviewSurface = null
        groupCallCameraPending = false
        groupCallMicMuted = false
        releaseAudio()
        restoreAudioRouting()
        pendingCsd.clear()
        decoderReady = false
        videoFrameCountRecv = 0
        audioFrameCountRecv = 0
        unknownTypesLogged.clear()
        remoteAudioCodec = null
        localAudioCodec = AudioCodec.PCM
        log("MEDIA: group call ended ($reason)")
        mainHandler.post { onGroupCallEnded?.invoke(reason) }
    }

    private fun updateKnownNames(members: List<RoutingTable.Member>) {
        members.forEach { knownNames[it.nodeId] = it.name }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        // FIX 3: registers this as the process's current transport — see
        // stopOrphanedInstance(), which the caller (OfflineCallActivity) is expected
        // to invoke before ever constructing a new instance.
        activeInstance = this
        val ht = HandlerThread("MediaChatWrite").also { it.start() }
        chatThread = ht
        chatHandler = Handler(ht.looper)
        // PHASE 5BC: warm start — the mesh session becoming active is what starts
        // GPS now, not an SOS press (see OfflineLocationProvider's class doc for
        // why the old cold-start-on-SOS design always reported "no GPS fix").
        // Balanced by the matching stop calls in stop() below. Same session-scoped
        // pattern for the barometer, the ledger's periodic flush, and the ambient
        // TYPE_POSITION broadcast (sent whether or not any SOS is active).
        locationProvider.start()
        barometer.start()
        ledger.startPeriodicFlush()
        carrier.startPeriodicFlush()
        meshSosManager.startPositionBroadcasts()
        sosTriggers.registerAll()
        sosRelay.register()
        bleBeacon.isConnectedOverWifiDirect = { nodeId -> routingTable.get(nodeId) != null }
        bleBeacon.start(localNodeId)
        meshElection.start()
        // PHASE 7A: seed verified pubkeys persisted from a prior session — a
        // peer's key survives a restart the same way the ledger/carry queue do.
        routingTable.seedVerifiedPubkeys(meshSigner.loadPersistedPubkeys())
        if (isGroupOwner) startAsServer() else startAsClient()
    }

    /** Idempotent full teardown — the whole mesh session, not just the current call.
     *  FIX (Phase 2, still true): mic loop → its encode consumer → sockets (which
     *  unblocks every read loop's blocked read) → decode consumer → AudioTrack. */
    fun stop() {
        running.set(false)
        // FIX 3: only clear the singleton pointer if it's still pointing at THIS
        // instance — an older instance's own stop() (e.g. via stopOrphanedInstance())
        // must never null out a newer instance's registration.
        if (activeInstance === this) activeInstance = null
        // FIX 1: flip false, then join every call-scoped media thread, BEFORE
        // releasing any of the resources they touch — see stopCallThreads().
        callActive.set(false)
        try { audioRecord?.stop() } catch (_: Exception) {}
        stopCallThreads()
        closeSockets()
        releaseCamera()
        releaseEncoder()
        releaseDecoder()
        releaseAllGroupDecoders()
        releaseAllGroupAudioDecoders()
        groupOpusOutputSampleRate = AUDIO_SAMPLE_RATE
        groupOpusOutputChannelCount = OPUS_CHANNEL_COUNT
        groupTileSurfaces.clear()
        localPreviewSurface = null
        groupCallCameraPending = false
        groupCallMicMuted = false
        releaseAudio()
        restoreAudioRouting()
        groupCallMixer?.stop()
        groupCallMixer = null
        synchronized(callLock) {
            activeCallPeerId = null
            activeCallPeerName = ""
            pendingOutgoingCallPeerId = null
            resolvedMode = null
            groupCall = null
        }
        speakerCandidateId = null
        speakerCandidateSinceMs = 0L
        pendingInvite = null
        audioSendersStartedForCallId = null
        chatThread?.quitSafely()
        chatThread = null
        chatHandler = null
        // PHASE 5A/5BC: stop the SOS/position repeat timers and balance whatever
        // ref-counts start() left behind — safe to call even if nothing was ever
        // started (see each provider's own clamped ref-count).
        meshSosManager.shutdown()
        locationProvider.stop()
        barometer.stop()
        ledger.stopPeriodicFlushAndFlushNow()
        carrier.stopPeriodicFlushAndFlushNow()
        sosTriggers.unregisterAll()
        sosRelay.unregister()
        sosBeaconMode.exit()
        bleBeacon.stop()
        meshElection.stop()
        meshSigner.shutdown()
    }

    // ── PHASE 3: call-only teardown (mesh stays up) ───────────────────────────

    /** Tears down just the current call's media pipeline and resets call state.
     *  Safe to call when no call is active (no-op then). [notifyEnded] is false only
     *  for the BUSY path, which fires [onCallBusy] instead of [onCallEnded]. */
    private fun endLocalCallState(reason: String, notifyEnded: Boolean = true) {
        synchronized(callLock) {
            if (activeCallPeerId == null && resolvedMode == null) return
            activeCallPeerId = null
            activeCallPeerName = ""
            pendingOutgoingCallPeerId = null
            resolvedMode = null
        }
        // FIX 1: flip false, then join every call-scoped media thread, BEFORE
        // releasing any of AudioRecord/AudioTrack/encoder/decoder/camera — a thread
        // still mid-iteration on one of those gets a chance to notice callActive is
        // now false and exit on its own instead of calling into a resource that's
        // being torn down out from under it on another thread.
        callActive.set(false)
        stopCallThreads()
        releaseCamera()
        releaseEncoder()
        releaseDecoder()
        releaseAudio()
        restoreAudioRouting()
        pendingCsd.clear()
        decoderReady = false
        videoFrameCountRecv = 0
        audioFrameCountRecv = 0
        unknownTypesLogged.clear()
        remoteAudioCodec = null
        localAudioCodec = AudioCodec.PCM
        log("MEDIA: call ended ($reason)")
        if (notifyEnded) mainHandler.post { onCallEnded?.invoke(reason) }
    }

    // ── PHASE 3: mesh-level (whole-session) teardown ──────────────────────────

    private fun handleMeshSessionLost(reason: String, uiMessage: String? = null) {
        if (!alive.compareAndSet(true, false)) return
        logE("MEDIA: mesh session lost — tearing down ($reason)")
        mainHandler.post { onLinkLost?.invoke(uiMessage) }
        stop()
    }

    /** Fired once (idempotent — guarded by [disconnectedLinks]) when a link's writer
     *  or reader hits a fatal I/O error. Removes the peer from the roster (GO
     *  rebroadcasts), ends the current call if that peer was the partner, and — for a
     *  client, whose only link IS the mesh — either retries reconnecting to the GO
     *  (IDLE-SESSION FIX, [retryable] cases — a transient socket drop) or escalates
     *  straight to a full session loss ([retryable] = false — a confirmed protocol
     *  version mismatch, which reconnecting can never fix). */
    private fun handlePeerDisconnected(link: PeerLink, uiMessage: String? = null, retryable: Boolean = true) {
        if (!disconnectedLinks.add(link)) return
        pendingLinks.remove(link)
        val hadId = link.nodeId
        val removed = if (hadId != MeshFrame.PENDING_ID) routingTable.remove(hadId) else null
        link.close()
        if (removed != null) {
            log("MESH: peer ${MeshFrame.hex(hadId)} (${link.name}) disconnected, group size ${routingTable.size()}")
            if (activeCallPeerId == hadId) {
                endLocalCallState("call partner disconnected")
            }
            // PHASE 3B: a mesh-level disconnect also removes them from any active
            // group call, same as an explicit TYPE_CALL_LEAVE would — otherwise a
            // dropped participant would linger in the authoritative set forever.
            if (isGroupOwner && groupCall != null) removeGroupCallParticipant(hadId)
            if (isGroupOwner) broadcastRoster()
        }
        if (!isGroupOwner) {
            if (retryable) {
                attemptClientReconnect(uiMessage)
            } else {
                handleMeshSessionLost("GO connection lost: ${uiMessage ?: "incompatible protocol"}", uiMessage)
            }
        }
    }

    /** IDLE-SESSION FIX: a client's single uplink to the GO dying doesn't necessarily
     *  mean the underlying WiFi Direct group is gone — it may be a transient WiFi radio
     *  hiccup (e.g. power-save renegotiation right after screen-off). Retries connect()
     *  to the GO's known address up to RECONNECT_RETRIES times before giving up on the
     *  whole mesh session. Bails immediately (no point retrying) if [isGroupFormed]
     *  says the WiFi Direct group itself is already gone. The GO side needs no
     *  matching change — its accept loop never exits on a client dropping (see
     *  startAsServer) — it will simply accept the new socket when it arrives, and the
     *  reused handleNewConnection() re-sends TYPE_HELLO so the GO's RoutingTable
     *  re-keys this device under the same (stable, persisted) node id. Never called on
     *  the GO — see the single call site in handlePeerDisconnected. */
    private fun attemptClientReconnect(uiMessage: String?) {
        if (!running.get() || !alive.get()) return
        if (!isGroupFormed()) {
            log("MESH: not reconnecting — WiFi Direct group no longer formed")
            handleMeshSessionLost("GO connection lost: group no longer formed", uiMessage)
            return
        }
        val addr = groupOwnerAddress
        if (addr == null) {
            handleMeshSessionLost("GO connection lost: no group owner address", uiMessage)
            return
        }
        Thread({
            var attempt = 0
            while (running.get() && alive.get() && attempt < RECONNECT_RETRIES) {
                attempt++
                if (!isGroupFormed()) {
                    log("MESH: group no longer formed — abandoning reconnect at attempt $attempt")
                    break
                }
                log("MESH: reconnect attempt $attempt/$RECONNECT_RETRIES to GO at $addr:$MEDIA_PORT")
                mainHandler.post { onReconnecting?.invoke(attempt, RECONNECT_RETRIES) }
                try {
                    Thread.sleep(RECONNECT_RETRY_DELAY_MS)
                } catch (_: InterruptedException) { return@Thread }
                if (!running.get() || !alive.get()) return@Thread
                try {
                    val s = Socket(addr, MEDIA_PORT)
                    log("MESH: reconnect succeeded on attempt $attempt")
                    handleNewConnection(s)
                    return@Thread
                } catch (e: Exception) {
                    logW("MESH: reconnect attempt $attempt failed: ${e.message}")
                }
            }
            if (running.get() && alive.get()) {
                handleMeshSessionLost("GO connection lost after $RECONNECT_RETRIES reconnect attempts", uiMessage)
            }
        }, "MediaReconnect").start()
    }

    // ── Wire framing ──────────────────────────────────────────────────────────

    /** Builds the v3 envelope and hands it to the right [PeerLink](s). A client has
     *  exactly one physical link (the GO) — every outgoing frame goes out that one
     *  link regardless of its final dst; the GO does the real per-member addressing,
     *  since only it has more than one link. */
    private fun writeFrame(dst: Long, type: Byte, data: ByteArray) {
        writeRawFrame(localNodeId, dst, type, data)
    }

    /** PHASE 5BC: same routing as [writeFrame] but with an explicit [srcId] rather
     *  than always stamping [localNodeId] — needed by store-and-forward SOS
     *  replay ([MeshSosManager.replayCachedSosTo]), which must preserve the
     *  ORIGINAL sender's nodeId so the replay target's own dedupe cache (keyed on
     *  srcId+type+msgSeq) correctly recognizes a frame it has already seen via a
     *  different path and never re-alarms for it. */
    private fun writeRawFrame(srcId: Long, dst: Long, type: Byte, data: ByteArray) {
        if (!alive.get()) return
        val ttl = if (dst == MeshFrame.BROADCAST_ID) TTL_BROADCAST else TTL_UNICAST
        // PHASE 7A: sign for a control type — see MeshSigner.signIfNeeded (a
        // no-op passthrough for 1/2/3). Always signs with THIS DEVICE's own
        // key regardless of [srcId] — every current call site passes
        // srcId=localNodeId (see writeFrame's delegation; the PHASE 6 TRACK A
        // migration removed the one path that used to pass someone else's id
        // here, in favour of MeshCarrier's envelope-level originId field), so
        // this is never asked to forge a signature for another device's id.
        val signedData = meshSigner.signIfNeeded(dst, type, data)
        val frame = MeshFrame.encode(srcId, dst, ttl, type, signedData)
        if (!isGroupOwner) {
            val uplink = routingTable.all().firstOrNull()
            if (uplink == null) {
                logW("MEDIA: writeRawFrame — no uplink to GO yet, dropped type=$type")
                return
            }
            uplink.enqueue(frame)
            return
        }
        if (dst == MeshFrame.BROADCAST_ID) {
            routingTable.all().forEach { it.enqueue(frame) }
        } else {
            val link = routingTable.get(dst)
            if (link == null) {
                logW("MEDIA: writeRawFrame dst=${MeshFrame.hex(dst)} unknown — dropped type=$type")
                return
            }
            link.enqueue(frame)
        }
    }

    // ── Connection setup ───────────────────────────────────────────────────────

    /** PHASE 3: loops on accept() instead of stopping after one client — a WiFi
     *  Direct group owner serves ~8 members. Each accepted socket gets its own
     *  [PeerLink] and read-loop thread; the accept loop itself never blocks on any
     *  of them. IDLE-SESSION FIX (3c): this loop is exactly what lets a reconnecting
     *  client's fresh connect() succeed — a client dropping only ever reaches
     *  handlePeerDisconnected (removes that one peer, rebroadcasts the roster); it
     *  never touches `running`/`serverSocket`, so this while(running.get()) loop is
     *  still sitting on accept() the whole time, ready for that client to come back. */
    private fun startAsServer() {
        Thread({
            log("MEDIA: GO listening for peers on port $MEDIA_PORT")
            try {
                val srv = ServerSocket(MEDIA_PORT)
                serverSocket = srv
                while (running.get()) {
                    val client = try {
                        srv.accept()
                    } catch (e: IOException) {
                        if (running.get()) logW("MEDIA: accept failed: ${e.message}")
                        break
                    }
                    if (!running.get()) { client.close(); break }
                    handleNewConnection(client)
                }
            } catch (e: BindException) {
                // FIX 3c: this must never fail silently — it's the exact symptom of
                // an orphaned transport instance (see stopOrphanedInstance()) still
                // holding the port from a previous, undestroyed Activity session.
                logE("OFFTRACE: MEDIA: PORT 8889 ALREADY BOUND — orphaned transport alive")
                if (running.get()) reportError("Port 8889 already in use — a previous session may still be running")
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
                    handleNewConnection(s)
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

    /** Called once a socket is connected, on whichever thread did the connecting (GO
     *  accept loop, or client connect). Wraps it in a PeerLink (identity unresolved
     *  until its HELLO arrives), starts that link's writer + its own read-loop
     *  thread, and sends our own HELLO directly on it (bypassing the routing table —
     *  we don't know the peer's node id yet, so there's nothing to look up). */
    private fun handleNewConnection(s: Socket) {
        try {
            s.tcpNoDelay = true
            s.keepAlive = true
            s.soTimeout = SOCKET_READ_TIMEOUT_MS
        } catch (e: Exception) {
            logW("MEDIA: could not set socket options: ${e.message}")
        }
        val link = PeerLink(MeshFrame.PENDING_ID, "", DataOutputStream(s.getOutputStream()), DataInputStream(s.getInputStream()))
        link.onDead = { deadLink -> handlePeerDisconnected(deadLink) }
        pendingLinks.add(link)
        link.startWriter()
        val idx = linkCounter.incrementAndGet()
        log("MEDIA: socket connected — starting read loop #$idx")
        Thread({ runReadLoop(link) }, "MediaReadLoop-$idx").start()
        link.enqueue(MeshFrame.encode(localNodeId, MeshFrame.BROADCAST_ID, TTL_BROADCAST, TYPE_HELLO, helloPayload()))
    }

    /** PHASE 7A: now carries the full 32-byte Ed25519 public key, and the whole
     *  payload (including the display name) is signed — see MeshSigner's class
     *  doc. HELLO is sent via a direct link.enqueue(...), not writeFrame (see
     *  the one call site below), so signing has to happen here explicitly
     *  rather than falling out of the usual writeFrame/writeRawFrame path. */
    private fun helloPayload(): ByteArray {
        val nameBytes = localDisplayName.toByteArray(Charsets.UTF_8).let {
            if (it.size > MAX_NAME_BYTES) it.copyOf(MAX_NAME_BYTES) else it
        }
        val pubkey = OfflineIdentity.publicKeyBytes(context)
        val buf = ByteBuffer.allocate(8 + 1 + pubkey.size + 1 + nameBytes.size)
        buf.putLong(localNodeId)
        buf.put(MeshFrame.VERSION)
        buf.put(pubkey)
        buf.put(nameBytes.size.toByte())
        buf.put(nameBytes)
        return meshSigner.signIfNeeded(MeshFrame.BROADCAST_ID, TYPE_HELLO, buf.array())
    }

    /** PHASE 3: resolves a link's identity the moment its HELLO arrives — registers
     *  it in the routing table under its real node id, and (GO only) logs the join
     *  and rebroadcasts the roster. Ignores a stray duplicate HELLO on an
     *  already-resolved link. */
    /** PHASE 7A: [header] is now required — verifyHello needs the envelope's own
     *  srcId/dstId/type to check the signature against, and to cross-check
     *  against the nodeId embedded in the payload itself (see MeshSigner.
     *  verifyHello's doc). HELLO bypasses routeFrame entirely (see the read
     *  loop's intercept below), so this is the ONLY place HELLO ever gets
     *  verified — nothing upstream does it for us. */
    private fun handleHelloFrame(header: MeshFrame.Header, link: PeerLink, payload: ByteArray) {
        val decoded = meshSigner.verifyHello(header, payload) ?: run {
            handlePeerDisconnected(link, "Identity verification failed", retryable = false)
            return
        }
        val peerId = decoded.nodeId
        if (decoded.protocolVersion != MeshFrame.VERSION) {
            if (!incompatibleVersionLogged) {
                incompatibleVersionLogged = true
                logE("OFFTRACE: MESH: incompatible protocol ver=${decoded.protocolVersion} (hello payload)")
            }
            handlePeerDisconnected(link, "Update the app on both phones", retryable = false)
            return
        }
        val name = decoded.name.ifEmpty { MeshFrame.hex(peerId) }

        if (link.nodeId != MeshFrame.PENDING_ID) return // already resolved — stray duplicate

        link.nodeId = peerId
        link.name = name
        pendingLinks.remove(link)
        routingTable.put(peerId, link)
        knownNames[peerId] = name
        log("OFFTRACE: MESH: peer nodeId=${MeshFrame.hex(peerId)} name=$name resolved")
        // PHASE 6 TRACK A: store-and-forward — offer every still-undelivered
        // carried message (SOS included, migrated off PHASE 5BC's hardcoded
        // replay — see MeshCarrier's class doc) to this newly resolved peer,
        // whichever direction the resolution happened (GO accepting a client, or
        // a client resolving the GO) — either side may hold messages the other
        // lacks after being apart. Subject to the normal dedupe on the offer
        // target's end, so a peer that already saw a given message via some
        // other path never gets re-processed for it.
        carrier.offerTo(peerId)
        if (isGroupOwner) {
            log("MESH: GO accepted peer ${MeshFrame.hex(peerId)}, group size ${routingTable.size()}")
            broadcastRoster()
            // PHASE 3B point 2: late join — a member connecting mid-call gets the full
            // current call state pushed to it immediately, no separate request needed.
            sendGroupCallStateTo(peerId)
        }
    }

    // ── PHASE 3: roster ────────────────────────────────────────────────────────

    /** GO-only: sends the full current membership to every connected member and
     *  updates this device's own roster UI. Never called on a client — clients only
     *  ever apply an inbound TYPE_ROSTER (see [handleRosterFrame]). */
    private fun broadcastRoster() {
        val members = routingTable.roster()
        updateKnownNames(members)
        writeFrame(MeshFrame.BROADCAST_ID, TYPE_ROSTER, encodeRoster(members))
        applyRosterDiffForLostContact(members)
        mainHandler.post { onRosterUpdated?.invoke(members) }
    }

    // PHASE 5BC: there is no dedicated onPeerLost callback anywhere in this
    // transport (see the survey behind this phase) — a member simply disappears
    // from the next roster snapshot. Diffing successive rosters here is the only
    // way MeshLedger finds out a peer is gone, on both the GO (broadcastRoster)
    // and a client (handleRosterFrame) — either can observe a membership drop
    // first depending on who's authoritative.
    private var previousRosterIds: Set<Long> = emptySet()

    private fun applyRosterDiffForLostContact(members: List<RoutingTable.Member>) {
        val newIds = members.map { it.nodeId }.toSet()
        val lost = previousRosterIds - newIds
        lost.forEach { id -> if (id != localNodeId) ledger.markLostContact(id, localNodeId) }
        previousRosterIds = newIds
    }

    /** PHASE 7A: now also carries each member's 32-byte pubkey — without this,
     *  an INDIRECT peer (relayed through the GO, never directly HELLO'd) would
     *  have no way to ever learn that member's pubkey at all, since HELLO
     *  itself only ever reaches whichever single link it arrived on (see the
     *  read loop's HELLO intercept — it never reaches routeFrame, so it's
     *  never forwarded). Roster IS already broadcast/relayed mesh-wide, so
     *  piggybacking pubkey distribution on it (rather than inventing a new
     *  relay mechanism) reuses existing, working infrastructure. */
    private fun encodeRoster(members: List<RoutingTable.Member>): ByteArray {
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)
        // Every member here already had its own HELLO verified (directly, or
        // via an earlier roster relay) — this mapNotNull is a defensive floor,
        // not an expected path; it should never actually omit anyone.
        val withPubkeys = members.mapNotNull { m ->
            val pubkey = if (m.nodeId == localNodeId) OfflineIdentity.publicKeyBytes(context) else routingTable.pubkeyFor(m.nodeId)
            if (pubkey == null) {
                logW("OFFTRACE: MESH: roster encode — no verified pubkey for ${MeshFrame.hex(m.nodeId)}, omitting")
                null
            } else {
                m to pubkey
            }
        }.take(255)
        dos.writeByte(withPubkeys.size)
        for ((m, pubkey) in withPubkeys) {
            dos.writeLong(m.nodeId)
            dos.write(pubkey)
            val nameBytes = m.name.toByteArray(Charsets.UTF_8).let {
                if (it.size > MAX_NAME_BYTES) it.copyOf(MAX_NAME_BYTES) else it
            }
            dos.writeByte(nameBytes.size)
            dos.write(nameBytes)
        }
        return bos.toByteArray()
    }

    private fun handleRosterFrame(payload: ByteArray) {
        if (isGroupOwner) return // the GO is authoritative; it never applies an inbound roster
        try {
            val din = DataInputStream(ByteArrayInputStream(payload))
            val count = din.readUnsignedByte()
            val members = ArrayList<RoutingTable.Member>(count)
            repeat(count) {
                val id = din.readLong()
                val pubkey = ByteArray(32)
                din.readFully(pubkey)
                val nameLen = din.readUnsignedByte()
                val nameBytes = ByteArray(nameLen)
                din.readFully(nameBytes)
                val name = String(nameBytes, Charsets.UTF_8)
                // PHASE 7A: self-authenticating even via a GO relay — same check
                // a direct HELLO gets (nodeId == SHA-256(pubkey)[0..8]). The GO
                // cannot fabricate a false pairing for anyone else — it doesn't
                // hold their private key — it can only correctly relay what it
                // already independently verified when that member connected.
                if (id == localNodeId || meshSigner.recordVerifiedPubkey(id, pubkey, name)) {
                    members.add(RoutingTable.Member(id, name))
                } else {
                    logW("OFFTRACE: MESH: roster entry for ${MeshFrame.hex(id)} rejected — pubkey/nodeId mismatch")
                }
            }
            updateKnownNames(members)
            log("MESH: roster updated, ${members.size} member(s)")
            applyRosterDiffForLostContact(members)
            mainHandler.post { onRosterUpdated?.invoke(members) }
        } catch (e: Exception) {
            logW("MESH: malformed ROSTER frame: ${e.message}")
        }
    }

    // ── Receive path: per-link read loop → route (local dispatch / forward) ───

    /** PHASE 3: one of these runs per connected link (N of them concurrently on the
     *  GO, exactly one on a client). Frames not addressed to us are handed to
     *  [routeFrame], which forwards them O(1) without ever touching a codec. */
    private fun runReadLoop(link: PeerLink) {
        val din = link.dataIn
        while (alive.get() && running.get()) {
            try {
                val header = MeshFrame.decodeHeader(din)
                if (header.ver != MeshFrame.VERSION) {
                    if (!incompatibleVersionLogged) {
                        incompatibleVersionLogged = true
                        logE("OFFTRACE: MESH: incompatible protocol ver=${header.ver}")
                    }
                    handlePeerDisconnected(link, "Update the app on both phones", retryable = false)
                    break
                }
                if (header.length < 0) {
                    logE("OFFTRACE: MESH: corrupt frame length=${header.length} — tearing down link")
                    handlePeerDisconnected(link)
                    break
                }
                if (header.ttl <= 0) {
                    ttlZeroDropCount++
                    if (ttlZeroDropCount % DROP_LOG_INTERVAL == 0) {
                        logW("OFFTRACE: MESH: dropped ttl=0 frame count=$ttlZeroDropCount")
                    }
                    skipFully(din, header.length)
                    continue
                }
                val maxLen = maxPayloadFor(header.type)
                if (header.length > maxLen) {
                    logW("OFFTRACE: MESH: dropping oversized frame type=${header.type} len=${header.length} (max $maxLen)")
                    skipFully(din, header.length)
                    continue
                }

                val payload = ByteArray(header.length)
                din.readFully(payload)

                if (header.type == TYPE_HELLO) {
                    handleHelloFrame(header, link, payload)
                    continue
                }
                if (link.nodeId == MeshFrame.PENDING_ID) {
                    // Anything before this link's own HELLO isn't attributable to a
                    // known sender — never dispatch or forward on its behalf.
                    logW("OFFTRACE: MESH: frame type=${header.type} from unresolved link — dropping")
                    continue
                }
                routeFrame(header, payload, link)
            } catch (e: SocketTimeoutException) {
                continue // just a quiet link within the read-timeout window — not death
            } catch (e: IOException) {
                handlePeerDisconnected(link, e.message)
                break
            } catch (e: RuntimeException) {
                logE("OFFTRACE: MEDIA: programming error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /** PHASE 3 core dispatch: dst==self or BROADCAST is ours to handle (and, on the
     *  GO, BROADCAST also fans out to everyone else); anything else is forwarded —
     *  pure demux, no codec/media work, so the GO never decodes a call it isn't
     *  party to. */
    private fun routeFrame(header: MeshFrame.Header, payload: ByteArray, fromLink: PeerLink) {
        // PHASE 7A: verification runs FIRST — before dedupe, before
        // cacheForCarry, before dispatchLocal, before any forwarding. See
        // MeshSigner's class doc for the exact bytes covered. Types 1/2/3
        // (media) bypass this entirely and return their payload unchanged
        // (see MeshSigner.isSignedType) — no crypto work is ever done for
        // them, at any rate, let alone 30fps.
        //
        // [innerPayload] (trailer stripped, verified) is what dedupe/cache/
        // dispatch use from here on; the ORIGINAL untouched [payload] (trailer
        // still attached, for signed types) is what forwarding uses below —
        // forwarding must relay the ORIGINAL sender's signature byte-for-byte,
        // never re-sign under this device's own key.
        val innerPayload = when (val result = meshSigner.verifyIncoming(header, payload, fromLink)) {
            is MeshSigner.VerifyResult.Accepted -> result.innerPayload
            MeshSigner.VerifyResult.Reject -> return
            MeshSigner.VerifyResult.Queued -> return
        }

        // PHASE 5A/5BC dedupe hook — meshSosManager.isSosFindType is the single
        // source of truth for this scope: TYPE_SOS (20) / TYPE_FIND_REQ (21) /
        // TYPE_FIND_RESP (22) / TYPE_POSITION (23). Deliberately excludes
        // TYPE_SOS_ACK (24) — see MeshSosManager.isSosFindType's doc. For every
        // other type (1-19, including every media/group-call type) this whole
        // block is skipped and routing below runs exactly as it always has.
        if (meshSosManager.isSosFindType(header.type) &&
            meshSosManager.checkAndRecordDuplicate(header.srcId, header.type, innerPayload)
        ) {
            val seq = MeshLocation.decode(innerPayload)?.msgSeq
            log("MESH: dropped duplicate type=${header.type} from=${MeshFrame.hex(header.srcId)} seq=$seq")
            return
        }
        // PHASE 6 TRACK A: MeshCarrier owns a SEPARATE dedupe cache keyed on
        // msgId, not (srcId,type,seq) — see MeshCarrier's class doc for why this
        // must not share meshSosManager's cache/scope.
        if (carrier.isCarrierType(header.type) && carrier.checkAndRecordDuplicate(innerPayload)) {
            log("MESH: dropped duplicate carried msgId from=${MeshFrame.hex(header.srcId)}")
            return
        }
        // PHASE 6 TRACK A: this is a genuinely LIVE (not carrier-delivered) TYPE_SOS
        // reaching routeFrame directly off a real link — cache it for future
        // store-and-forward. Deliberately placed here, not inside
        // MeshSosManager.handleSosFrame, so a carrier-delivered SOS (redispatched
        // via dispatchCarriedInner, which calls dispatchLocal directly and never
        // routeFrame) doesn't re-queue itself — MeshCarrier.handleStoreFwdFrame
        // already becomes a mule for that case. See MeshSosManager.cacheForCarry.
        if (header.type == TYPE_SOS) {
            meshSosManager.cacheForCarry(header.srcId, innerPayload)
        }
        // PHASE 6 TRACK B1: someone is trying to reach us — beacon mode (if
        // active on THIS device) keeps its radio in the low-latency window a
        // while longer instead of duty-cycling down. No-op, cheap, if beacon
        // mode was never entered.
        sosBeaconMode.onInboundFrame()
        when {
            header.dstId == localNodeId -> {
                logIfRelayed(header, fromLink)
                dispatchLocal(header, innerPayload)
            }
            header.dstId == MeshFrame.BROADCAST_ID -> {
                logIfRelayed(header, fromLink)
                dispatchLocal(header, innerPayload)
                if (isGroupOwner) forwardBroadcast(header, payload, fromLink)
            }
            else -> {
                if (isGroupOwner) {
                    forwardUnicast(header, payload)
                } else {
                    wrongDstDropCount++
                    if (wrongDstDropCount % DROP_LOG_INTERVAL == 0) {
                        logW("OFFTRACE: MESH: dropped frame not addressed to us dst=${MeshFrame.hex(header.dstId)} count=$wrongDstDropCount")
                    }
                }
            }
        }
    }

    /** A client has exactly one link (the GO) — if a frame's immediate sender isn't
     *  who its src claims, it was relayed through the GO from a third member. */
    private fun logIfRelayed(header: MeshFrame.Header, fromLink: PeerLink) {
        if (!isGroupOwner && header.srcId != fromLink.nodeId) {
            log("MESH: relayed frame recv from ${MeshFrame.hex(header.srcId)}")
        }
    }

    private fun forwardBroadcast(header: MeshFrame.Header, payload: ByteArray, fromLink: PeerLink) {
        val newTtl = header.ttl - 1
        if (newTtl <= 0) {
            ttlZeroDropCount++
            return
        }
        logForward(header, newTtl.toByte())
        val frame = MeshFrame.encode(header.srcId, header.dstId, newTtl.toByte(), header.type, payload)
        routingTable.allExcept(fromLink.nodeId).forEach { it.enqueue(frame) }
    }

    private fun forwardUnicast(header: MeshFrame.Header, payload: ByteArray) {
        val target = routingTable.get(header.dstId)
        if (target == null) {
            unknownDstDropCount++
            if (unknownDstDropCount % UNKNOWN_DST_LOG_INTERVAL == 0) {
                logW("OFFTRACE: MESH: forward dropped — unknown dst=${MeshFrame.hex(header.dstId)} count=$unknownDstDropCount")
            }
            return
        }
        val newTtl = header.ttl - 1
        if (newTtl <= 0) {
            ttlZeroDropCount++
            return
        }
        logForward(header, newTtl.toByte())
        val frame = MeshFrame.encode(header.srcId, header.dstId, newTtl.toByte(), header.type, payload)
        target.enqueue(frame)
    }

    /** Every control frame is logged; media frames (CONFIG/FRAME/AUDIO) are sampled
     *  once per FORWARD_MEDIA_LOG_SAMPLE per (src,dst,type) flow to avoid drowning
     *  logcat during an active relayed call. */
    private fun logForward(header: MeshFrame.Header, ttl: Byte) {
        val isMedia = header.type == TYPE_CONFIG || header.type == TYPE_FRAME || header.type == TYPE_AUDIO
        if (isMedia) {
            val key = (header.srcId * 1_000_003L) xor (header.dstId * 97L) xor header.type.toLong()
            val n = forwardLogCounters.merge(key, 1) { a, b -> a + b } ?: 1
            if (n % FORWARD_MEDIA_LOG_SAMPLE != 0) return
        }
        log("MESH: forwarding type=${header.type} from ${MeshFrame.hex(header.srcId)} to ${MeshFrame.hex(header.dstId)} ttl=$ttl")
    }

    /** Frames addressed to us or to BROADCAST land here. */
    /** [isLive] defaults true (a frame that just arrived off a real link, via
     *  [routeFrame]) — [dispatchCarriedInner] passes false for a
     *  store-and-forward replay. Consulted ONLY by the TYPE_SOS branch below
     *  (see MeshSosManager.handleSosFrame's isLive param / BUG 1 FIX 3); every
     *  other type, including 1/2/3 media, ignores it entirely. */
    private fun dispatchLocal(header: MeshFrame.Header, payload: ByteArray, isLive: Boolean = true) {
        when (header.type) {
            TYPE_CONFIG -> {
                val gc = groupCall
                if (gc != null) {
                    // PHASE 3C: video is per-participant now, independent of who's
                    // speaking — accept csd from any current participant (not gated
                    // to gc.activeSpeakerId, which is highlight-only — see
                    // applySpeakerChange). Buffered per-sender until their TYPE_FRAME
                    // configures that sender's own tile decoder.
                    if (header.srcId !in gc.participants) return
                    groupPendingCsd.getOrPut(header.srcId) { mutableListOf() }.add(payload)
                    return
                }
                if (!acceptMediaFrame(header.srcId)) return
                pendingCsd.add(payload)
            }
            TYPE_FRAME -> {
                val gc = groupCall
                if (gc != null) {
                    if (header.srcId !in gc.participants) return
                    if (groupDecoderReady[header.srcId] != true) {
                        val csd = groupPendingCsd[header.srcId]
                        if (csd.isNullOrEmpty()) return
                        configureGroupDecoder(header.srcId, combineByteArrays(csd))
                        // Only consume the buffered csd once it actually configured a
                        // decoder — if the tile Surface wasn't ready yet (see
                        // configureGroupDecoder's "no tile surface yet" branch), keep it
                        // buffered so setGroupTileSurface's retry (once the Surface
                        // shows up) still has something to configure with.
                        if (groupDecoders.containsKey(header.srcId)) {
                            groupPendingCsd.remove(header.srcId)
                            groupDecoderReady[header.srcId] = true
                        }
                    }
                    if (groupDecoderReady[header.srcId] == true) {
                        feedGroupDecoder(header.srcId, payload)
                        val n = (groupVideoFrameCountRecv[header.srcId] ?: 0) + 1
                        groupVideoFrameCountRecv[header.srcId] = n
                        if (n % 30 == 0) log("MEDIA: recv group tile frame ${MeshFrame.hex(header.srcId)} $n")
                    }
                    return
                }
                val accepted = acceptMediaFrame(header.srcId)
                if (!accepted) return
                if (!decoderReady) {
                    if (pendingCsd.isEmpty()) return
                    configureDecoder(combineByteArrays(pendingCsd))
                    pendingCsd.clear()
                    decoderReady = decoder != null
                }
                if (decoderReady) {
                    feedDecoder(payload)
                    videoFrameCountRecv++
                    if (videoFrameCountRecv % 30 == 0) log("MEDIA: recv frame $videoFrameCountRecv")
                }
            }
            TYPE_AUDIO -> {
                val gc = groupCall
                if (gc != null) {
                    // PHASE 3D: broadcast fan-in, same shape as group video — decode
                    // THIS sender's own stream (per-sender decoder, never shared with
                    // any other sender's) and fold the result into the local mix. GO
                    // and client run the identical code here; forwarding to everyone
                    // ELSE happens separately, upstream, in forwardBroadcast, without
                    // ever touching this function.
                    if (header.srcId !in gc.participants) return
                    trackAudioRecvBytes(payload.size)
                    val codec = groupRemoteAudioCodec[header.srcId] ?: AudioCodec.PCM
                    val pcm = if (codec == AudioCodec.PCM) payload else decodeGroupAudio(header.srcId, payload)
                    if (pcm != null) {
                        groupLatestPcm[header.srcId] = pcm
                        groupLatestPcmMs[header.srcId] = System.currentTimeMillis()
                        mixAndPlayGroupAudio()
                    }
                    audioFrameCountRecv++
                    if (audioFrameCountRecv % 50 == 0) log("MEDIA: recv audio $audioFrameCountRecv")
                    return
                }
                if (!acceptMediaFrame(header.srcId)) return
                trackAudioRecvBytes(payload.size)
                if (!opusDecodeQueue.offer(payload)) {
                    opusDecodeQueue.poll()
                    opusDecodeQueue.offer(payload)
                    trackDecodeQueueDrop()
                }
                audioFrameCountRecv++
                if (audioFrameCountRecv % 50 == 0) log("MEDIA: recv audio $audioFrameCountRecv")
            }
            TYPE_CHAT -> handleChatFrame(header, payload)
            TYPE_MODE -> handleModeFrame(header, payload)
            TYPE_AUDIO_CODEC -> {
                val gc = groupCall
                if (gc != null) {
                    // PHASE 3D: every participant announces its OWN outgoing codec,
                    // broadcast (see audioDst) — every OTHER participant (not just the
                    // GO) needs this to know how to decode that specific sender's
                    // TYPE_AUDIO frames.
                    if (payload.size == 1) {
                        AudioCodec.fromWireId(payload[0])?.let { groupRemoteAudioCodec[header.srcId] = it }
                    }
                    return
                }
                if (activeCallPeerId != header.srcId) return
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
            TYPE_BUSY -> handleBusyFrame(header, payload)
            TYPE_HANGUP -> handleHangupFrame(header)
            TYPE_ROSTER -> handleRosterFrame(payload)
            TYPE_CALL_INVITE -> handleCallInviteFrame(header, payload)
            TYPE_CALL_ACCEPT -> handleCallAcceptFrame(header, payload)
            TYPE_CALL_LEAVE -> handleCallLeaveFrame(header, payload)
            TYPE_VAD -> handleVadFrame(header, payload)
            TYPE_SPEAKER -> handleSpeakerFrame(header, payload)
            TYPE_PARTICIPANTS -> handleParticipantsFrame(payload)
            TYPE_CAM -> if (isGroupOwner) handleCamRequestFrame(header, payload) else handleCamBroadcastFrame(payload)
            TYPE_CAM_DENIED -> handleCamDeniedFrame()
            TYPE_SOS -> meshSosManager.handleSosFrame(header, payload, isLive)
            TYPE_FIND_REQ -> meshSosManager.handleFindRequestFrame(header)
            TYPE_FIND_RESP -> meshSosManager.handleFindResponseFrame(header, payload)
            TYPE_POSITION -> meshSosManager.handlePositionFrame(header, payload)
            TYPE_SOS_ACK -> meshSosManager.handleSosAckFrame(header)
            TYPE_STORE_FWD -> carrier.handleStoreFwdFrame(header, payload)
            TYPE_SF_ACK -> carrier.handleAckFrame(header, payload)
            TYPE_GO_HEARTBEAT -> meshElection.handleGoHeartbeat(header)
            TYPE_ELECTION_STATUS -> meshElection.handleElectionStatus(header, payload)
            TYPE_KEYFRAME_REQUEST -> requestKeyFrame()
            else -> {
                if (unknownTypesLogged.add(header.type)) {
                    logW("MEDIA: unknown frame type=${header.type} len=${payload.size} — skipping")
                }
            }
        }
    }

    /** Gates CONFIG/FRAME/AUDIO to the current call partner. FIX 4: no longer
     *  implicit-starts a VIDEO call (and opens the camera) for media arriving with
     *  no active call state — that back-compat fallback let a stray/misrouted frame
     *  (e.g. the phantom broadcast audio a self-destructed group call used to leak —
     *  see FIX 1) silently open someone's camera. A call now only ever starts via an
     *  explicit TYPE_MODE frame (see [handleModeFrame]); anything else arriving with
     *  no active call is dropped, rate-limited log only. */
    private fun acceptMediaFrame(srcId: Long): Boolean {
        val active = activeCallPeerId
        if (active == srcId) return true
        if (active != null) {
            staleMediaDropCount++
            if (staleMediaDropCount % DROP_LOG_INTERVAL == 0) {
                logW("OFFTRACE: MEDIA: dropped media from non-active peer=${MeshFrame.hex(srcId)} " +
                    "(active call with ${MeshFrame.hex(active)}) count=$staleMediaDropCount")
            }
            return false
        }
        noActiveCallMediaDropCount++
        if (noActiveCallMediaDropCount % DROP_LOG_INTERVAL == 0) {
            logW("OFFTRACE: MEDIA: media frame with no active call — dropped (count=$noActiveCallMediaDropCount)")
        }
        return false
    }

    private fun handleModeFrame(header: MeshFrame.Header, payload: ByteArray) {
        if (payload.size != 1) {
            logW("MEDIA: malformed mode control frame len=${payload.size} — ignoring")
            return
        }
        val mode = CallMode.fromWireId(payload[0])
        if (mode == null) {
            logW("MEDIA: unknown mode id=${payload[0]} — ignoring")
            return
        }
        val currentPartner = activeCallPeerId
        if (currentPartner == header.srcId) return // duplicate MODE for the call already running
        val name = nameFor(header.srcId)
        if (!tryBeginCall(header.srcId, name)) {
            val partnerLabel = currentPartner?.let { MeshFrame.hex(it) } ?: "?"
            log("MEDIA: busy — declining call from ${MeshFrame.hex(header.srcId)} " +
                "(active call with $partnerLabel)")
            writeFrame(header.srcId, TYPE_BUSY, ByteArray(0))
            return
        }
        log("MEDIA: incoming call from ${MeshFrame.hex(header.srcId)} ($name) mode=$mode " +
            "— v1 media calls are 1:1 only, relayed if not directly connected")
        startSendersForMode(mode)
        mainHandler.post { onModeResolved?.invoke(header.srcId, name, mode) }
    }

    private fun handleBusyFrame(header: MeshFrame.Header, payload: ByteArray) {
        // PHASE 3B point 10: a 1-byte payload is the group-call-full rejection; the
        // existing 0-byte payload keeps meaning "busy with a different 1:1 call".
        if (payload.size == 1 && payload[0] == BUSY_REASON_CALL_FULL) {
            log("MESH: group call join rejected by ${MeshFrame.hex(header.srcId)} — call is full")
            endGroupCallState("rejected — call full")
            mainHandler.post { onGroupCallRejected?.invoke("Group call is full") }
            return
        }
        if (pendingOutgoingCallPeerId != header.srcId) return
        val name = activeCallPeerName
        log("MEDIA: call to ${MeshFrame.hex(header.srcId)} declined — busy")
        endLocalCallState("peer busy", notifyEnded = false)
        mainHandler.post { onCallBusy?.invoke(name) }
    }

    private fun handleHangupFrame(header: MeshFrame.Header) {
        if (activeCallPeerId != header.srcId) return // not our current call partner — stray, ignore
        log("MEDIA: peer ${MeshFrame.hex(header.srcId)} hung up")
        endLocalCallState("peer hung up")
    }

    private fun handleChatFrame(header: MeshFrame.Header, payload: ByteArray) {
        if (payload.size > MAX_CHAT_PAYLOAD_BYTES) {
            logW("MEDIA: oversized chat frame len=${payload.size} — ignoring")
            return
        }
        val isGroup = header.dstId == MeshFrame.BROADCAST_ID
        val fromName = nameFor(header.srcId)
        val text = String(payload, Charsets.UTF_8)
        log("MEDIA: chat recv len=${payload.size} from=${MeshFrame.hex(header.srcId)} group=$isGroup")
        mainHandler.post { onChatMessage?.invoke(header.srcId, fromName, text, isGroup) }
    }

    // ── PHASE 3B: group call frame handlers ─────────────────────────────────────

    /** Fires on every OTHER member (the initiator handled its own start synchronously
     *  in [startGroupCall] — it never receives its own broadcast back, same
     *  self-delivery non-issue as chat/roster). The GO additionally registers this as
     *  the mesh's authoritative call the moment it sees it, regardless of who
     *  proposed it. */
    private fun handleCallInviteFrame(header: MeshFrame.Header, payload: ByteArray) {
        if (payload.size != 9) {
            logW("MESH: malformed CALL_INVITE len=${payload.size} — ignoring")
            return
        }
        val mode = GroupCallMode.fromWireId(payload[0]) ?: run {
            logW("MESH: unknown group call mode id=${payload[0]} — ignoring")
            return
        }
        val callId = ByteBuffer.wrap(payload, 1, 8).long
        if (groupCall?.callId == callId) return // already in this call — duplicate/late-join resend
        val fromName = nameFor(header.srcId)

        if (isGroupOwner) {
            if (!tryBeginGroupCall(callId, mode, initiatorId = header.srcId)) {
                logW("MESH: ignoring group call invite from ${MeshFrame.hex(header.srcId)} — already in a different call")
                return
            }
            startGroupCallMixer()
            addGroupCallParticipant(header.srcId, rejectDst = null)
            // FIX 1d: the GO's OWN authoritative copy needs the same ringing timeout
            // as the initiator's (this device may not be the initiator here — a
            // client proposed this call) — otherwise a call nobody ever joins would
            // ring on the GO forever with no local mechanism to end it.
            scheduleRingingTimeout(callId)
        }
        pendingInvite = PendingGroupInvite(callId, mode, header.srcId)
        log("MEDIA: group call invite from ${MeshFrame.hex(header.srcId)} ($fromName) mode=$mode")
        mainHandler.post { onGroupCallInvite?.invoke(header.srcId, fromName, mode, callId) }
    }

    private fun handleCallAcceptFrame(header: MeshFrame.Header, payload: ByteArray) {
        if (!isGroupOwner) return // ACCEPT is always addressed to the GO — shouldn't reach anyone else
        if (payload.size != 8) {
            logW("MESH: malformed CALL_ACCEPT len=${payload.size} — ignoring")
            return
        }
        val callId = ByteBuffer.wrap(payload).long
        val gc = groupCall
        if (gc == null || gc.callId != callId) {
            logW("MESH: CALL_ACCEPT for unknown/stale callId from ${MeshFrame.hex(header.srcId)} — ignoring")
            return
        }
        addGroupCallParticipant(header.srcId, rejectDst = header.srcId)
    }

    private fun handleCallLeaveFrame(header: MeshFrame.Header, payload: ByteArray) {
        if (!isGroupOwner) return // LEAVE is always addressed to the GO
        if (payload.size != 8) {
            logW("MESH: malformed CALL_LEAVE len=${payload.size} — ignoring")
            return
        }
        val callId = ByteBuffer.wrap(payload).long
        val gc = groupCall
        if (gc == null || gc.callId != callId) return
        log("MEDIA: ${MeshFrame.hex(header.srcId)} left the group call")
        removeGroupCallParticipant(header.srcId)
    }

    private fun handleVadFrame(header: MeshFrame.Header, payload: ByteArray) {
        if (!isGroupOwner) return // VAD is client -> GO only
        if (payload.size != 3) {
            logW("MESH: malformed VAD len=${payload.size} — ignoring")
            return
        }
        val speaking = payload[0].toInt() != 0
        val energy = ((payload[1].toInt() and 0xFF) shl 8) or (payload[2].toInt() and 0xFF)
        groupCallMixer?.updateVad(header.srcId, speaking, energy)
    }

    /** The GO decides this locally (see [onRawActiveSpeakersChanged]/[applySpeakerChange])
     *  and never receives its own broadcast — this only ever runs on a receiving
     *  client. */
    private fun handleSpeakerFrame(header: MeshFrame.Header, payload: ByteArray) {
        if (isGroupOwner) return
        if (payload.size < 8) {
            logW("MESH: malformed SPEAKER len=${payload.size} — ignoring")
            return
        }
        val buf = ByteBuffer.wrap(payload)
        val nodeId = buf.long
        val pinned = payload.size >= 9 && payload[8].toInt() != 0
        applySpeakerChange(if (nodeId == NO_SPEAKER_ID) null else nodeId, pinned)
    }

    private fun handleParticipantsFrame(payload: ByteArray) {
        val gc = groupCall ?: return // not in a group call — ignore a stray/late frame
        if (isGroupOwner) return // the GO is authoritative; it never applies an inbound copy
        try {
            val din = DataInputStream(ByteArrayInputStream(payload))
            val count = din.readUnsignedByte()
            val ids = (0 until count).map { din.readLong() }
            if (localNodeId !in ids) {
                log("MESH: group call ended (we were removed)")
                endGroupCallState("call ended")
                return
            }
            // FIX 1: mirror the GO's own established-gating (evaluateGroupCallAfterChange)
            // — a founding call reported with just 1 participant (the founder) is RINGING,
            // not over. Only tear down locally once the call has ever reached 2+ and then
            // dropped back below.
            if (ids.size >= 2) gc.established = true
            if (gc.established && ids.size < 2) {
                log("MESH: group call ended (participants dropped below 2)")
                endGroupCallState("call ended")
                return
            }
            gc.participants.clear()
            gc.participants.addAll(ids)
            // PHASE 3C: anyone dropped off the roster (e.g. disconnected without an
            // explicit TYPE_CAM off) shouldn't keep a stale tile decoder or camState
            // entry around — mirrors removeGroupCallParticipant's GO-side cleanup.
            val idSet = ids.toSet()
            gc.camStates.keys.toList().forEach { id ->
                if (id !in idSet) { gc.camStates.remove(id); releaseGroupDecoder(id); releaseGroupAudioDecoder(id) }
            }
            log("MESH: group call participants updated, ${ids.size} member(s)")
            mainHandler.post { onGroupCallParticipants?.invoke(ids) }
        } catch (e: Exception) {
            logW("MESH: malformed PARTICIPANTS frame: ${e.message}")
        }
    }

    /** PHASE 7A: a signed type's wire payload is [MeshSigner.SIGNATURE_TRAILER_BYTES]
     *  (68) bytes bigger than its own content cap, since signIfNeeded appends
     *  timestamp+signature on top — without this allowance, a maximally-sized
     *  signed chat message (or any other type already near its cap) would be
     *  rejected as "oversized" by the receiver's own bounds check purely
     *  because of the trailer. Types 1/2/3 are never signed, so they get no
     *  allowance — their caps are exactly as before. */
    private fun maxPayloadFor(type: Byte): Int {
        val base = when (type) {
            TYPE_CHAT -> MAX_CHAT_PAYLOAD_BYTES
            TYPE_FRAME -> MAX_VIDEO_PAYLOAD_BYTES
            TYPE_AUDIO -> MAX_AUDIO_PAYLOAD_BYTES
            TYPE_CONFIG -> MAX_CONFIG_PAYLOAD_BYTES
            TYPE_MODE, TYPE_AUDIO_CODEC, TYPE_HELLO, TYPE_BUSY, TYPE_HANGUP, TYPE_ROSTER,
            TYPE_CALL_INVITE, TYPE_CALL_ACCEPT, TYPE_CALL_LEAVE, TYPE_VAD, TYPE_SPEAKER, TYPE_PARTICIPANTS,
            TYPE_CAM, TYPE_CAM_DENIED ->
                MAX_CONTROL_PAYLOAD_BYTES
            TYPE_STORE_FWD -> MAX_STORE_FWD_PAYLOAD_BYTES
            else -> MAX_CONTROL_PAYLOAD_BYTES
        }
        return if (MeshSigner.isSignedType(type)) base + MeshSigner.SIGNATURE_TRAILER_BYTES else base
    }

    /** Discards exactly `n` bytes so a dropped (ttl/oversized) frame's payload
     *  doesn't desync the next frame's header read. */
    private fun skipFully(din: DataInputStream, n: Int) {
        var remaining = n
        while (remaining > 0) {
            val skipped = din.skipBytes(remaining)
            if (skipped <= 0) break
            remaining -= skipped
        }
    }

    // ── Call setup: sender halves (unchanged internals, now resettable per call) ─

    /** Starts exactly once per call (guarded by [resolvedMode]) the sender halves
     *  that match [mode] — same decision on both peers, so the call ends up
     *  symmetric no matter which side is the initiator. Chat is not gated here; it's
     *  always on. Reset by [endLocalCallState] so the next call can start fresh. */
    private fun startSendersForMode(mode: CallMode) {
        if (resolvedMode != null) return
        resolvedMode = mode
        // FIX 1: marks this call's media loops as allowed to run — checked alongside
        // `running` by every one of them, and flipped false FIRST (before any
        // AudioRecord/encoder/decoder/camera teardown) in endLocalCallState.
        callActive.set(true)
        log("MEDIA: mode resolved -> $mode — starting matching sender halves")
        startOpusDecodeThread() // ready for inbound audio regardless of our own mode
        if (mode == CallMode.VIDEO || mode == CallMode.AUDIO) {
            setupAudioRouting()
            startAudioSender()
        }
        if (mode == CallMode.VIDEO) {
            startEncoderThenCamera()
        }
    }

    // ── PHASE 3B: group call audio (mixer wiring + camera-follows-speaker) ──────

    /** PHASE 3D: this device's own audio contribution to a just-(re)joined group
     *  call — always mic capture + VAD, regardless of AUDIO vs VIDEO call mode;
     *  camera only ever starts later, if/when this device's own camera is toggled
     *  on (see [setGroupCallCameraOn]) — joining a call is not the same as being
     *  on-screen. Sending is now identical to a 1:1 call in every respect except
     *  [audioDst] (BROADCAST instead of a single peer) — GO included, no more
     *  bypass (see [startAudioSender]). Receiving is per-sender decode + local
     *  mixing, NOT the 1:1 opusDecodeQueue/MediaOpusDecode thread — see
     *  [dispatchLocal]'s TYPE_AUDIO branch and [decodeGroupAudio]. */
    private fun startGroupCallAudio() {
        // FIX 4: all four join paths (initiator/acceptor x GO/client) funnel through
        // here — guard against a duplicate accept (e.g. a double-tap on "Join" before
        // the dialog disables itself, or two acceptGroupCall calls racing) spawning a
        // second mic-capture thread / second CALL_ACCEPT for the same call.
        val callId = groupCall?.callId
        if (callId != null && audioSendersStartedForCallId == callId) {
            logW("MEDIA: startGroupCallAudio callId=${MeshFrame.hex(callId)} already started — ignoring duplicate")
            return
        }
        audioSendersStartedForCallId = callId
        // FIX 1: see the matching comment in startSendersForMode.
        callActive.set(true)
        // PHASE 3D: no more 1:1-shaped opusDecodeQueue/MediaOpusDecode thread here —
        // group audio decode is per-sender and driven inline off dispatchLocal (see
        // decodeGroupAudio), same as group video's decoders.
        setupAudioRouting()
        startAudioSender()
    }

    /** GO-only, idempotent: creates and starts the VAD-ranking-only
     *  [GroupCallMixer] — its ONLY remaining job is feeding this class's own
     *  2-second speaker-highlight debounce (see [onRawActiveSpeakersChanged]); it no
     *  longer decodes, mixes, or distributes audio (see that class's doc for why —
     *  the per-tick decode-all loop this used to run was the actual source of the
     *  GO's overrun/degradation problem). */
    private fun startGroupCallMixer() {
        if (groupCallMixer != null) return
        val mixer = GroupCallMixer(
            localNodeId = localNodeId,
            onActiveSpeakersChanged = { ordered -> onRawActiveSpeakersChanged(ordered) }
        )
        mixer.start()
        groupCallMixer = mixer
    }

    /** GO-only, point 6: implements the 2-second continuous-lead debounce on top of
     *  GroupCallMixer's raw (instantaneous) top-speaker signal, so a brief crosstalk
     *  blip doesn't flap the one video stream back and forth. A host pin (see
     *  [requestPin]) overrides this entirely until cleared. */
    private fun onRawActiveSpeakersChanged(rankedActive: List<Long>) {
        val gc = groupCall ?: return
        if (gc.pinnedId != null) return // pin overrides VAD — see requestPin
        val leader = rankedActive.firstOrNull()
        val now = System.currentTimeMillis()
        // FIX 2: a momentary gap between words/sentences reports leader=null on some
        // ticks (the sender's own VAD hangover expires between phrases, so no chunk
        // arrives that tick — see GroupCallMixer.computeTopSpeakers) — that is NOT a
        // change of candidate, just a lull. Resetting the hold timer on every such
        // null was what made ACTIVE_SPEAKER_DEBOUNCE_MS effectively unreachable in
        // practice, since real speech is full of these gaps. Only a genuinely
        // DIFFERENT non-null leader restarts the clock; a null tick leaves whatever
        // candidate/timer was already pending untouched.
        if (leader != null && leader != speakerCandidateId) {
            speakerCandidateId = leader
            speakerCandidateSinceMs = now
        }
        val candidate = speakerCandidateId
        if (candidate == null || candidate == gc.activeSpeakerId) return // nothing pending, or already this
        val heldMs = now - speakerCandidateSinceMs
        if (now - lastSpeakerCandidateLogMs >= 1000L) {
            lastSpeakerCandidateLogMs = now
            log("OFFTRACE: SPK: candidate=${MeshFrame.hex(candidate)} heldMs=$heldMs")
        }
        if (heldMs < ACTIVE_SPEAKER_DEBOUNCE_MS) return // hasn't led long enough yet
        applySpeakerChange(candidate, pinned = false)
        broadcastSpeaker(candidate, pinned = false)
    }

    /** Applies a speaker change to THIS device's own state/media pipeline — called
     *  both by the GO's own debounce decision above (direct call, no wire — see the
     *  self-delivery note on [startGroupCall]) and by a receiving client's
     *  [handleSpeakerFrame]. Point 7: exactly one video stream on the wire — this
     *  device starts its camera iff newly named, stops immediately if it just
     *  stopped being named, and any receiving client resets its decoder either way
     *  (a new sender's csd never applies to whatever decoder, if any, the previous
     *  one configured). */
    /** PHASE 3C: highlight-only — video is now per-participant (see
     *  [setGroupCallCameraOn]/[applyCamState]) and no longer controlled by who's
     *  speaking, so this no longer touches the camera, encoder, or any decoder; it
     *  purely updates which tile the grid should highlight as "currently speaking"
     *  (via [onGroupCallSpeaker]). TYPE_SPEAKER/VAD machinery upstream of this
     *  (computeTopSpeakers, the 2s debounce) is otherwise unchanged. */
    private fun applySpeakerChange(nodeId: Long?, pinned: Boolean) {
        val gc = groupCall ?: return
        if (gc.activeSpeakerId == nodeId) return
        gc.activeSpeakerId = nodeId
        gc.pinnedId = if (pinned) nodeId else null
        log("MEDIA: group call speaker highlight -> ${nodeId?.let { MeshFrame.hex(it) } ?: "none"} pinned=$pinned")
        mainHandler.post { onGroupCallSpeaker?.invoke(nodeId, pinned) }
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
            encoderRunning = true
            log("MEDIA: encoder started")
            val drainThread = Thread({ drainEncoderLoop() }, "MediaEncoderDrain")
            encoderDrainThread = drainThread
            drainThread.start()
            openCamera(inputSurface)
        } catch (e: Exception) {
            if (running.get()) reportError("encoder setup: ${e.message}")
        }
    }

    /** FIX: asks this device's own running encoder for an immediate IDR rather than
     *  waiting up to KEY_I_FRAME_INTERVAL (1s) for the next scheduled one — called
     *  whenever a new video consumer for this device's stream may have just shown
     *  up (a late joiner replaying csd, or a remote participant's camera turning
     *  on), so the first frame it actually receives after (re)configuring its
     *  decoder has a real chance of being decodable rather than a P-frame with
     *  nothing to reference. No-op if this device isn't currently sending video. */
    private fun requestKeyFrame() {
        val enc = encoder ?: return
        try {
            val params = Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            enc.setParameters(params)
            log("OFFTRACE: MEDIA: keyframe requested")
        } catch (e: Exception) {
            if (running.get()) logW("OFFTRACE: MEDIA: keyframe request failed: ${e.message}")
        }
    }

    private fun drainEncoderLoop() {
        val info = MediaCodec.BufferInfo()
        val pendingCsdOut = mutableListOf<ByteArray>()
        var csdSent = false
        var frameCount = 0

        // FIX 3: the whole loop is wrapped — this used to have no try/catch at all,
        // and captured `encoder` into a local `enc` exactly once at entry. releaseEncoder()
        // (called from endLocalCallState/endGroupCallState, possibly on another thread)
        // can null the field and release the underlying codec at any point; calling into
        // that stale reference threw an uncaught IllegalStateException that killed the
        // whole process. The reference is now re-read from the field every iteration
        // instead, and any exception here always exits the thread rather than propagating.
        try {
            while (running.get() && callActive.get() && encoderRunning) {
                // FIX 2: re-check right after grabbing the reference too — the
                // while condition above only guarantees this was true at the
                // START of the iteration; a camera toggle's releaseEncoder()
                // can still flip it false on another thread between the check
                // and here. This is the guard replacing the old catch-and-exit.
                val enc = encoder ?: break
                if (!encoderRunning) break
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
                        pendingCsdOut.add(bytes)
                        log("MEDIA: buffered codec config ${bytes.size} bytes")
                    }

                    else -> {
                        if (!csdSent && pendingCsdOut.isNotEmpty()) {
                            val combined = combineByteArrays(pendingCsdOut)
                            writeFrame(videoDst(), TYPE_CONFIG, combined)
                            csdSent = true
                            lastCsdOut = combined
                            log("MEDIA: sent codec config ${combined.size} bytes")
                        }
                        writeFrame(videoDst(), TYPE_FRAME, bytes)
                        frameCount++
                        if (frameCount % 30 == 0) {
                            log("MEDIA: sent frame $frameCount bytes=${bytes.size}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            handleMediaLoopException("encoder drain", e)
        }
        log("MEDIA: encoder drain thread exiting")
    }

    /** dst for this call's outgoing AUDIO frames — for a 1:1 call, the partner; for
     *  a group call, always BROADCAST (PHASE 3D — same shape as [videoDst] now):
     *  every participant needs everyone else's raw stream directly, not just the
     *  GO, since decoding+mixing now happens locally on each receiver instead of
     *  once on the GO — see [dispatchLocal]'s TYPE_AUDIO branch. */
    private fun audioDst(): Long {
        groupCall?.let { return MeshFrame.BROADCAST_ID }
        return activeCallPeerId ?: MeshFrame.BROADCAST_ID
    }

    /** dst for this call's outgoing VIDEO frames — for a 1:1 call, the partner; for a
     *  group call, always BROADCAST, since every participant (not just the GO) needs
     *  to see whoever the current active speaker is (point 7) — the GO forwards it
     *  exactly like any other broadcast media, never decoding it along the way (see
     *  forwardBroadcast). */
    private fun videoDst(): Long {
        groupCall?.let { return MeshFrame.BROADCAST_ID }
        return activeCallPeerId ?: MeshFrame.BROADCAST_ID
    }

    @SuppressLint("MissingPermission")
    private fun startAudioSender() {
        // PHASE 3D: the GO's own mic now goes through the exact same Opus-encode-
        // and-broadcast pipeline as any client's — no more bypass (see audioDst).
        startOpusEncodeThread()
        val t = Thread({
            try {
                var rec = createAndStartAudioRecord() ?: return@Thread
                audioRecord = rec
                log("MEDIA: audio recorder started")

                val chunk = ByteArray(AUDIO_CHUNK_BYTES)
                var audioFrameCount = 0
                var consecutiveReadErrors = 0
                var zeroReadCount = 0
                var rebuildAttempts = 0
                // PHASE 3B: VAD state (point 3) — only consulted while groupCall != null;
                // a 1:1 call transmits continuously exactly as it always has (point 12).
                var noiseFloor = 0
                var speaking = false
                var lastSpeechMs = 0L
                var lastVadSentMs = 0L
                while (running.get() && callActive.get()) {
                    val n = rec.read(chunk, 0, chunk.size)
                    if (n <= 0) {
                        // FIX 1d: mic loop safety net — every non-positive read backs off
                        // 5ms before retrying, regardless of which branch below runs, so
                        // this can never busy-spin (the original bug: a released
                        // AudioRecord returning err=-3 forever with no pause between
                        // read() calls, pegging a core).
                        consecutiveReadErrors++
                        if (n < 0) {
                            if (consecutiveReadErrors % MIC_READ_ERROR_LOG_INTERVAL == 0) {
                                logE("OFFTRACE: MEDIA: mic read err=$n")
                            }
                        } else {
                            zeroReadCount++
                            if (zeroReadCount % MIC_ZERO_READ_LOG_INTERVAL == 0) {
                                log("MEDIA: mic read n=0 count=$zeroReadCount")
                            }
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
                                ?: return@Thread
                            rec = rebuilt
                            audioRecord = rec
                            log("MEDIA: mic recovered")
                        }
                        try { Thread.sleep(5) } catch (_: InterruptedException) { return@Thread }
                        continue
                    }
                    consecutiveReadErrors = 0
                    zeroReadCount = 0
                    val payload = chunk.copyOf(n)
                    audioFrameCount++
                    if (audioFrameCount % 50 == 0) {
                        log("MEDIA: sent audio $audioFrameCount")
                        log("MEDIA: mic peak=${peakAmplitude(payload)}")
                    }

                    if (groupCall != null) {
                        // PHASE 3B: adaptive-noise-floor VAD — floor tracks DOWN fast
                        // toward a quieter reading (background noise settling) and UP
                        // slowly toward a louder one (so a sustained talker doesn't
                        // redefine their own voice as "the new floor"). Hangover keeps
                        // "speaking" true for VAD_HANGOVER_MS past the last loud sample
                        // so trailing consonants aren't clipped.
                        val energy = rmsEnergy(payload)
                        noiseFloor = when {
                            noiseFloor == 0 -> energy
                            energy < noiseFloor -> noiseFloor - (noiseFloor - energy) * VAD_FLOOR_RISE_RATE / 1024
                            else -> noiseFloor + (energy - noiseFloor) * VAD_FLOOR_FALL_RATE / 1024
                        }
                        val now = System.currentTimeMillis()
                        // PHASE 3C: explicit hard mute (setGroupCallMicMuted) forces VAD
                        // itself to report not-speaking rather than just suppressing the
                        // wire send below — otherwise a muted participant's tile would
                        // keep showing a stale "speaking" indicator, and the GO's mixer
                        // would keep them occupying an active-speaker slot.
                        val loud = !groupCallMicMuted && energy > noiseFloor * VAD_SPEAK_THRESHOLD_MULT
                        if (loud) lastSpeechMs = now
                        val wasSpeaking = speaking
                        speaking = loud || (now - lastSpeechMs) < VAD_HANGOVER_MS
                        if (speaking != wasSpeaking || now - lastVadSentMs >= VAD_HEARTBEAT_MS) {
                            lastVadSentMs = now
                            if (isGroupOwner) {
                                groupCallMixer?.updateVad(localNodeId, speaking, energy)
                            } else {
                                sendVad(speaking, energy)
                            }
                        }
                        if (!speaking) continue // point 3: transmit Opus ONLY while speaking
                        // PHASE 3D: GO and client both fall through to the normal
                        // Opus-encode queue below — the GO no longer has a mixer
                        // bypass (see startAudioSender/audioDst).
                    }

                    if (!opusEncodeQueue.offer(payload)) {
                        opusEncodeQueue.poll()
                        opusEncodeQueue.offer(payload)
                        opusEncodeDropCount++
                        if (opusEncodeDropCount % DROP_LOG_INTERVAL == 0) {
                            logW("OFFTRACE: MEDIA: enc queue dropped $opusEncodeDropCount")
                        }
                    }
                }
            } catch (e: Exception) {
                // FIX 1/3: this thread is now also unblocked via callActive.set(false)
                // + Thread.interrupt() (see stopCallThreads()), on top of the pre-existing
                // audioRecord.stop() unblock — funnel every exception through the shared
                // classifier rather than deciding "error or clean shutdown" ad hoc here.
                handleMediaLoopException("audio sender", e)
            }
        }, "MediaAudioSend")
        audioSendThread = t
        t.start()
    }

    /** PHASE 3B point 3: 20ms RMS amplitude of 16-bit signed PCM, coerced to fit the
     *  wire's 2-byte unsigned energy field. */
    private fun rmsEnergy(data: ByteArray): Int {
        var sumSquares = 0L
        var count = 0
        var i = 0
        while (i + 1 < data.size) {
            val lo = data[i].toInt() and 0xFF
            val hi = data[i + 1].toInt() and 0xFF
            val sample = ((hi shl 8) or lo).toShort().toInt()
            sumSquares += sample.toLong() * sample.toLong()
            count++
            i += 2
        }
        if (count == 0) return 0
        return kotlin.math.sqrt((sumSquares / count).toDouble()).toInt().coerceIn(0, 65535)
    }

    /** PHASE 3B: client -> GO only (the GO updates its own VAD state directly via
     *  GroupCallMixer.updateVad — see the call site above). */
    private fun sendVad(speaking: Boolean, energy: Int) {
        val go = uplinkNodeId() ?: return
        val buf = ByteBuffer.allocate(3)
        buf.put(if (speaking) 1 else 0)
        buf.putShort(energy.toShort())
        writeFrame(go, TYPE_VAD, buf.array())
    }

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

    private fun startOpusEncodeThread() {
        val t = Thread({
            val opusEnc = createOpusEncoderOrNull()
            audioEncoder = opusEnc
            localAudioCodec = if (opusEnc != null) AudioCodec.OPUS else AudioCodec.PCM
            log("OFFTRACE: MEDIA: audio codec=${localAudioCodec.name.lowercase()} negotiated (send)")
            writeFrame(audioDst(), TYPE_AUDIO_CODEC, byteArrayOf(localAudioCodec.wireId))

            try {
                while (running.get() && callActive.get()) {
                    val payload = opusEncodeQueue.poll(OPUS_QUEUE_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: continue
                    if (opusEnc != null) {
                        encodeAndSendOpus(opusEnc, payload)
                    } else {
                        writeFrame(audioDst(), TYPE_AUDIO, payload)
                        trackAudioSendBytes(payload.size)
                    }
                }
            } catch (e: Exception) {
                handleMediaLoopException("opus encode", e)
            } finally {
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

    private fun createOpusEncoderOrNull(): MediaCodec? {
        return try {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, AUDIO_SAMPLE_RATE, OPUS_CHANNEL_COUNT).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, OPUS_BITRATE)
                setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
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

    private fun encodeAndSendOpus(enc: MediaCodec, pcm: ByteArray) {
        try {
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
                        writeFrame(audioDst(), TYPE_AUDIO, bytes)
                        trackAudioSendBytes(bytes.size)
                    }
                }
                enc.releaseOutputBuffer(outIdx, false)
            }
        } catch (e: Exception) {
            if (running.get() && callActive.get()) reportError("opus encode: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(encoderSurface: Surface) {
        val ht = HandlerThread("MediaCameraThread").also { it.start() }
        cameraThread = ht
        val handler = Handler(ht.looper)
        cameraHandler = handler

        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val frontIds  = mutableListOf<String>()
        val backIds   = mutableListOf<String>()
        val otherIds  = mutableListOf<String>()

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

        val usableIds: List<String> = frontIds + backIds + otherIds
        if (usableIds.isEmpty()) {
            logE("MEDIA: no usable camera (no backwardCompat sensor found)")
            reportError("no usable camera"); return
        }

        val facingLabel = { id: String ->
            when { id in frontIds -> "front"; id in backIds -> "back"; else -> "other" }
        }
        log("MEDIA: selected cameraId=${usableIds[0]} facing=${facingLabel(usableIds[0])}")

        val attemptIdx = intArrayOf(0)
        val inUseRetryCount = intArrayOf(0)

        @SuppressLint("MissingPermission")
        fun tryOpen() {
            val id = usableIds[attemptIdx[0]]
            log("MEDIA: opening cameraId=$id facing=${facingLabel(id)}")
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    inUseRetryCount[0] = 0
                    // FIX 1: camera feed — the open completed asynchronously; if the call
                    // ended in the meantime, don't hand a live camera to a session that
                    // has nothing left to feed.
                    if (!running.get() || !callActive.get()) { camera.close(); return }
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
                            if (running.get() && callActive.get()) {
                                try { tryOpen() } catch (e: Exception) {
                                    if (running.get()) reportError("camera open: ${e.message}")
                                }
                            }
                        }, CAMERA_IN_USE_RETRY_DELAY_MS)
                        return
                    }

                    inUseRetryCount[0] = 0
                    attemptIdx[0]++
                    if (attemptIdx[0] < usableIds.size && running.get() && callActive.get()) {
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
            // PHASE 3C: this device's own grid tile (group video only — null on a
            // 1:1 call, see setLocalPreviewSurface) is a SECOND simultaneous output
            // target on the same capture session, not a separate camera open —
            // Camera2 supports multiple targets from one repeating request.
            val previewSurface = localPreviewSurface
            val targets = if (previewSurface != null) listOf(encoderSurface, previewSurface) else listOf(encoderSurface)
            camera.createCaptureSession(
                targets,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (!running.get() || !callActive.get()) { session.close(); return }
                        captureSession = session
                        // FIX 3: the camera is only ACTUALLY running once this
                        // callback fires, not merely once a request is granted.
                        cameraOn = true
                        // FIX: single source of truth for "does the session that's
                        // actually running right now have a preview target" — read
                        // by setLocalPreviewSurface to decide whether a later-
                        // arriving Surface needs a rebuild or was already picked up
                        // by this very call.
                        capturingWithPreviewSurface = previewSurface
                        try {
                            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                .apply {
                                    addTarget(encoderSurface)
                                    previewSurface?.let { addTarget(it) }
                                }
                                .build()
                            session.setRepeatingRequest(req, null, cameraHandler)
                            val hasPreview = previewSurface != null
                            log("MEDIA: camera capture running (preview=$hasPreview)")
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

    // ── Receive path: decoder/AudioTrack (unchanged internals) ─────────────────

    private fun configureDecoder(csd: ByteArray) {
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

    // ── PHASE 3C: multi-tile group video receive (per-sender decoder) ──────────
    // Same MediaCodec usage as the 1:1 decoder above, just keyed by srcId instead
    // of living in a single field — one instance per remote camera-on participant,
    // each bound to that participant's own tile Surface (registered by the
    // Activity via setGroupTileSurface before any frame can be decoded for them).

    /** [requestKeyframeAfter] is true only from setGroupTileSurface's csd-retry
     *  path — that's the specific case where the tile Surface arrived late
     *  enough that srcId's encoder is already steady-state, so this freshly
     *  configured decoder has nothing to decode until srcId's next periodic
     *  keyframe (an arbitrarily long wait) unless we ask directly. The normal
     *  live TYPE_FRAME arrival path (this function's other call site) doesn't
     *  need this — that path is already about to feed the very frame that
     *  triggered the configure. */
    private fun configureGroupDecoder(srcId: Long, csd: ByteArray, requestKeyframeAfter: Boolean = false) {
        val surface = groupTileSurfaces[srcId] ?: run {
            logW("OFFTRACE: MEDIA: no tile surface yet for ${MeshFrame.hex(srcId)} — dropping csd")
            return
        }
        try {
            val fmt = MediaFormat.createVideoFormat("video/avc", WIDTH, HEIGHT).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            }
            val dec = MediaCodec.createDecoderByType("video/avc")
            dec.configure(fmt, surface, null, 0)
            dec.start()
            groupDecoders[srcId] = dec
            log("MEDIA: group tile decoder configured for ${MeshFrame.hex(srcId)} (csd ${csd.size} bytes)")
            if (requestKeyframeAfter) {
                writeFrame(srcId, TYPE_KEYFRAME_REQUEST, ByteArray(0))
                Log.d("OFFTRACE", "MEDIA: keyframe requested after csd retry for ${MeshFrame.hex(srcId)}")
            }
        } catch (e: Exception) {
            if (running.get()) logE("OFFTRACE: MEDIA: group decoder configure for ${MeshFrame.hex(srcId)}: ${e.message}")
        }
    }

    private fun feedGroupDecoder(srcId: Long, data: ByteArray) {
        val dec = groupDecoders[srcId] ?: return
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
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> handleGroupOutputFormatChanged(srcId, dec.outputFormat)
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> { /* deprecated, no-op */ }
                    else -> dec.releaseOutputBuffer(out, true)
                }
            }
        } catch (e: Exception) {
            if (running.get()) logE("OFFTRACE: MEDIA: feedGroupDecoder ${MeshFrame.hex(srcId)}: ${e.message}")
        }
    }

    private fun handleGroupOutputFormatChanged(srcId: Long, format: MediaFormat) {
        try {
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            val cropLeft = if (format.containsKey("crop-left")) format.getInteger("crop-left") else 0
            val cropTop = if (format.containsKey("crop-top")) format.getInteger("crop-top") else 0
            val cropRight = if (format.containsKey("crop-right")) format.getInteger("crop-right") else width - 1
            val cropBottom = if (format.containsKey("crop-bottom")) format.getInteger("crop-bottom") else height - 1
            val cropWidth = cropRight - cropLeft + 1
            val cropHeight = cropBottom - cropTop + 1
            log("MEDIA: group tile video size ${MeshFrame.hex(srcId)} ${cropWidth}x${cropHeight}")
            mainHandler.post { onGroupTileVideoSize?.invoke(srcId, cropWidth, cropHeight) }
        } catch (e: Exception) {
            if (running.get()) logE("OFFTRACE: MEDIA: group output format changed ${MeshFrame.hex(srcId)}: ${e.message}")
        }
    }

    private fun releaseGroupDecoder(srcId: Long) {
        groupDecoders.remove(srcId)?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
        groupPendingCsd.remove(srcId)
        groupDecoderReady.remove(srcId)
        groupVideoFrameCountRecv.remove(srcId)
    }

    private fun releaseAllGroupDecoders() {
        groupDecoders.keys.toList().forEach { releaseGroupDecoder(it) }
    }

    private fun feedAudioTrackPcm(data: ByteArray, sampleRate: Int, channelConfig: Int) {
        val track = ensureAudioTrack(sampleRate, channelConfig) ?: return
        try {
            val written = track.write(data, 0, data.size)
            if (written <= 0) {
                audioWriteErrCount++
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

    private fun startOpusDecodeThread() {
        val t = Thread({
            try {
                while (running.get() && callActive.get()) {
                    val payload = opusDecodeQueue.poll(OPUS_QUEUE_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: continue
                    when (remoteAudioCodec ?: AudioCodec.PCM) {
                        AudioCodec.OPUS -> feedOpusAudio(payload)
                        AudioCodec.PCM -> feedAudioTrackPcm(payload, AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_OUT)
                    }
                }
            } catch (e: Exception) {
                handleMediaLoopException("opus decode", e)
            } finally {
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

    private fun configureOpusAudioDecoder(): MediaCodec? {
        return try {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, AUDIO_SAMPLE_RATE, OPUS_CHANNEL_COUNT).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(buildOpusIdHeader()))
                setByteBuffer("csd-1", ByteBuffer.wrap(buildOpusCsd1()))
                setByteBuffer("csd-2", ByteBuffer.wrap(buildOpusCsd2()))
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

    // ── PHASE 3D: multi-sender group AUDIO receive (per-sender decode + local mix) ──
    // Same MediaCodec usage/csd as the 1:1 Opus decoder above (configureOpusAudioDecoder
    // is shared), just one instance per remote sender instead of one shared field —
    // exact same pattern as the group VIDEO decoders further up this file. Called
    // directly off dispatchLocal, on the read loop thread, same as the video path.

    private fun decodeGroupAudio(srcId: Long, opusBytes: ByteArray): ByteArray? {
        val dec = groupAudioDecoders.getOrPut(srcId) { configureOpusAudioDecoder() ?: return null }
        return try {
            val inIdx = dec.dequeueInputBuffer(10_000L)
            if (inIdx >= 0) {
                val buf = dec.getInputBuffer(inIdx)!!
                buf.clear()
                buf.put(opusBytes)
                dec.queueInputBuffer(inIdx, 0, opusBytes.size, System.nanoTime() / 1000, 0)
            }
            val chunks = mutableListOf<ByteArray>()
            val info = MediaCodec.BufferInfo()
            while (true) {
                val outIdx = dec.dequeueOutputBuffer(info, 0)
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> handleGroupAudioOutputFormatChanged(dec.outputFormat)
                    outIdx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> { /* deprecated, no-op */ }
                    outIdx >= 0 -> {
                        if (info.size > 0) {
                            val outBuf = dec.getOutputBuffer(outIdx)
                            if (outBuf != null) {
                                val pcm = ByteArray(info.size)
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                outBuf.get(pcm)
                                chunks.add(pcm)
                            }
                        }
                        dec.releaseOutputBuffer(outIdx, false)
                    }
                }
            }
            if (chunks.isEmpty()) null else if (chunks.size == 1) chunks[0] else combineByteArrays(chunks)
        } catch (e: Exception) {
            logE("OFFTRACE: MEDIA: group audio decode for ${MeshFrame.hex(srcId)} failed: ${e.message}")
            null
        }
    }

    /** All senders are assumed to negotiate the same Opus output format (fixed csd,
     *  same encoder config everywhere), so — deliberate simplification, see
     *  [groupOpusOutputSampleRate]'s doc — this updates ONE shared rate/channel
     *  pair rather than tracking it per sender. */
    private fun handleGroupAudioOutputFormatChanged(format: MediaFormat) {
        if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            groupOpusOutputSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        }
        if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            groupOpusOutputChannelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        }
        log("OFFTRACE: MEDIA: group audio decoder out rate=$groupOpusOutputSampleRate ch=$groupOpusOutputChannelCount")
    }

    /** Sums every currently-live remote sender's latest decoded chunk into one
     *  buffer (simple sample-by-sample addition + soft clip — no encode) and
     *  writes it straight to this device's own AudioTrack, applying the existing
     *  MODE_IN_COMMUNICATION + speaker routing [feedAudioTrackPcm] already carries.
     *  Called on every arriving TYPE_AUDIO frame from any sender — with more than
     *  one participant talking at once this can trigger more than once per real
     *  20ms window; AudioTrack's own buffering absorbs that burst (same tradeoff
     *  the old GO-side tick-based mixer never had to make, in exchange for having
     *  no tick loop / no per-tick budget to overrun at all). */
    private fun mixAndPlayGroupAudio() {
        val now = System.currentTimeMillis()
        val active = groupLatestPcm.filterKeys { id -> (now - (groupLatestPcmMs[id] ?: 0L)) < GROUP_AUDIO_STALE_MS }
        if (active.isEmpty()) return
        val mixed = mixGroupPcm(active.values.toList())
        feedAudioTrackPcm(mixed, groupOpusOutputSampleRate, channelCountToOutConfig(groupOpusOutputChannelCount))
        if (now - lastGroupMixLogMs >= GROUP_MIX_LOG_INTERVAL_MS) {
            lastGroupMixLogMs = now
            log("OFFTRACE: MIX-LOCAL: mixing ${active.size} streams")
        }
    }

    /** Same sum-then-soft-clip math GroupCallMixer used to run GO-side — see that
     *  class's old mixPcm/softLimit for the original. Mixing buffers that may
     *  originate from senders with genuinely different sample rates (e.g. one
     *  fell back to raw 16kHz PCM while others are 48kHz-decoded Opus) without
     *  resampling is a known, pre-existing simplification carried over unchanged
     *  from that design — not something this rewrite introduces. */
    private fun mixGroupPcm(buffers: List<ByteArray>): ByteArray {
        if (buffers.isEmpty()) return ByteArray(AUDIO_CHUNK_BYTES)
        if (buffers.size == 1) return buffers[0]
        val sampleCount = buffers.minOf { it.size } / 2
        val out = ByteArray(sampleCount * 2)
        for (i in 0 until sampleCount) {
            var sum = 0
            for (b in buffers) {
                val lo = b[i * 2].toInt() and 0xFF
                val hi = b[i * 2 + 1].toInt() and 0xFF
                sum += ((hi shl 8) or lo).toShort().toInt()
            }
            val limited = softLimitGroupAudio(sum)
            out[i * 2] = (limited and 0xFF).toByte()
            out[i * 2 + 1] = ((limited shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun softLimitGroupAudio(sample: Int): Int {
        val abs = kotlin.math.abs(sample)
        if (abs <= GROUP_AUDIO_LIMITER_THRESHOLD) return sample
        val over = abs - GROUP_AUDIO_LIMITER_THRESHOLD
        val compressed = GROUP_AUDIO_LIMITER_THRESHOLD + over / GROUP_AUDIO_LIMITER_RATIO
        val signed = if (sample < 0) -compressed else compressed
        return signed.coerceIn(-32767, 32767)
    }

    private fun releaseGroupAudioDecoder(srcId: Long) {
        groupAudioDecoders.remove(srcId)?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
        groupRemoteAudioCodec.remove(srcId)
        groupLatestPcm.remove(srcId)
        groupLatestPcmMs.remove(srcId)
    }

    /** Releases every per-sender decoder AND clears the codec/PCM bookkeeping maps
     *  outright — [releaseGroupAudioDecoder] alone would miss a PCM-fallback sender
     *  (see [decodeGroupAudio]'s call site: PCM payloads never create a decoder
     *  entry at all, but still populate groupRemoteAudioCodec/groupLatestPcm), which
     *  would otherwise leak stale entries into the next call. */
    private fun releaseAllGroupAudioDecoders() {
        groupAudioDecoders.values.forEach { try { it.stop(); it.release() } catch (_: Exception) {} }
        groupAudioDecoders.clear()
        groupRemoteAudioCodec.clear()
        groupLatestPcm.clear()
        groupLatestPcmMs.clear()
    }

    private fun buildOpusIdHeader(): ByteArray {
        val buf = ByteBuffer.allocate(OPUS_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("OpusHead".toByteArray(Charsets.US_ASCII))
        buf.put(1)
        buf.put(OPUS_CHANNEL_COUNT.toByte())
        buf.putShort(OPUS_PRE_SKIP_SAMPLES_48K.toShort())
        buf.putInt(AUDIO_SAMPLE_RATE)
        buf.putShort(0)
        buf.put(0)
        return buf.array()
    }

    private fun buildOpusCsd1(): ByteArray {
        val delayNs = OPUS_PRE_SKIP_SAMPLES_48K.toLong() * 1_000_000_000L / 48_000L
        return ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(delayNs).array()
    }

    private fun buildOpusCsd2(): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(OPUS_SEEK_PREROLL_NS).array()

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

    /** FIX 3: shared exception classifier for every call-scoped media loop (mic
     *  capture, Opus encode/decode, H.264 encoder drain). An InterruptedException or
     *  IllegalStateException landing while [callActive] (or [running]) is already
     *  false is this thread's own teardown racing a just-released codec/AudioRecord —
     *  expected, not a bug — so it's logged quietly. Anything else, or either of
     *  those two while a call is still supposedly active, is a real problem and is
     *  logged as an error. Either way this never rethrows — the loop's own try/catch
     *  always lets the thread exit normally rather than crashing the process. */
    private fun handleMediaLoopException(loopName: String, e: Throwable) {
        if (e is InterruptedException) Thread.currentThread().interrupt()
        val expectedTeardown = (e is InterruptedException || e is IllegalStateException) &&
            (!callActive.get() || !running.get())
        if (expectedTeardown) {
            log("OFFTRACE: MEDIA: $loopName exiting cleanly (${e.javaClass.simpleName})")
        } else {
            logE("OFFTRACE: MEDIA: $loopName error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** FIX 1: joins every call-scoped media thread with a [CALL_THREAD_JOIN_MS] cap
     *  each — called with [callActive] already false, BEFORE any of
     *  AudioRecord/AudioTrack/encoder/decoder/camera is actually released, so a
     *  thread still mid-iteration on one of those objects gets a chance to notice
     *  callActive==false and exit on its own instead of calling into a resource
     *  that's being released out from under it on another thread. */
    private fun stopCallThreads() {
        val threads = listOfNotNull(audioSendThread, opusEncodeThread, opusDecodeThread, encoderDrainThread)
        var joined = 0
        var timedOut = 0
        threads.forEach { t ->
            t.interrupt()
            try { t.join(CALL_THREAD_JOIN_MS) } catch (_: InterruptedException) {}
            if (t.isAlive) timedOut++ else joined++
        }
        audioSendThread = null
        opusEncodeThread = null
        opusDecodeThread = null
        encoderDrainThread = null
        opusEncodeQueue.clear()
        opusDecodeQueue.clear()
        log("OFFTRACE: MEDIA: call threads stopped ($joined joined, $timedOut timed out)")
    }

    private fun closeSockets() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        pendingLinks.forEach { it.close() }
        pendingLinks.clear()
        routingTable.clear()
    }

    private fun releaseCamera() {
        cameraOn = false
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
        // FIX: a fresh camera session (next call, or this device's camera toggled
        // back on) must re-evaluate preview from scratch rather than think it
        // already has one from a session that no longer exists.
        capturingWithPreviewSurface = null
        previewRebuildPendingFor = null
    }

    private fun releaseEncoder() {
        // FIX 2: signal the drain loop BEFORE touching the codec at all — see
        // encoderRunning's doc. Grabbing a local reference and nulling the
        // field here (rather than after stop/release) also means the drain
        // loop's own `encoder ?: break` sees null as early as possible.
        encoderRunning = false
        val enc = encoder
        encoder = null
        try { enc?.stop() } catch (_: Exception) {}
        try { enc?.release() } catch (_: Exception) {}
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
        }
    }

    // ── Logging ───────────────────────────────────────────────────────────────

    // FIX 5: this used to ALSO log under a separate "OfflineMediaTransport" tag
    // (Log.d(TAG, msg) below Log.d("OFFTRACE", msg)) — every OFFTRACE-tagged
    // line in this file was really two physical Log calls for one logical
    // event, doubling logcat volume and corrupting any count-based diagnostic
    // over this file's output. "OFFTRACE" is the sole convention every other
    // file in this app already logs under (MeshLedger, MeshSosManager,
    // MeshCarrier, ...) — this file was the only one with a second sink.
    private fun log(msg: String) {
        Log.d("OFFTRACE", msg)
    }

    private fun logW(msg: String) {
        Log.w("OFFTRACE", msg)
    }

    private fun logE(msg: String) {
        Log.e("OFFTRACE", msg)
    }

    private fun reportError(msg: String) {
        logE(msg)
        mainHandler.post { onError(msg) }
    }
}
