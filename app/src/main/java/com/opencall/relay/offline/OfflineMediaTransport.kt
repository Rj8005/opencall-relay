package com.opencall.relay.offline

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
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
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
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
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
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

    /** PHASE 5.1: which quick-phrase category a code belongs to, for the
     *  bottom-sheet's grouping. Purely a UI grouping — never travels on the
     *  wire (only [PhraseCode.code] does, 1 byte, unchanged format). */
    enum class PhraseCategory(val label: String) { EVERYDAY("Everyday"), OUTDOOR("Outdoor"), EMERGENCY("Emergency") }

    /** OFFLINE UI STEP 4 / PHASE 5.1: the closed set of canned phrases — NO
     *  free text ever rides TYPE_PHRASE (see that type's wire doc); a
     *  receiver always maps [code] through this table to a LOCAL string,
     *  never trusts bytes off the wire as displayable text. Code 7 is
     *  deliberately reserved (no entry here) — [fromCode] returns null for
     *  it exactly like any other unrecognized code, which is what drives
     *  "unknown message" rendering (see handlePhraseFrame's doc: still
     *  relayed, just not understood).
     *
     *  PHASE 5.1: codes 0-6 KEEP THEIR EXACT PRE-EXISTING (code, text) pairs
     *  byte-for-byte — a phrase that changes meaning between app versions is
     *  a safety bug (see PhraseCodeTest's per-code assertions). Code 7 stays
     *  reserved. New codes start at 8, wire format stays the same 1 byte
     *  (fits to 255; this table uses under 70). isUrgentRelayFrame (PHASE 4)
     *  deliberately still checks ONLY code==6 (NEED_HELP) — none of the new
     *  codes below are added to that bypass; that set is unchanged by this
     *  phase. */
    enum class PhraseCode(val code: Int, val text: String, val category: PhraseCategory) {
        IM_OK(0, "I'm OK", PhraseCategory.EVERYDAY),
        HOLD_POSITION(1, "Hold position", PhraseCategory.EVERYDAY),
        MOVING_TO_YOU(2, "Moving to you", PhraseCategory.EVERYDAY),
        TURNING_BACK(3, "Turning back", PhraseCategory.EVERYDAY),
        WEATHER_TURNING(4, "Weather turning", PhraseCategory.OUTDOOR),
        REGROUP_LAST_POINT(5, "Regroup last point", PhraseCategory.EVERYDAY),
        NEED_HELP(6, "Need help", PhraseCategory.EMERGENCY),
        // code 7 reserved — no entry.

        // ── Everyday (8-27) ──────────────────────────────────────────────
        ON_MY_WAY(8, "On my way", PhraseCategory.EVERYDAY),
        RUNNING_LATE(9, "Running late", PhraseCategory.EVERYDAY),
        ARRIVED(10, "Arrived", PhraseCategory.EVERYDAY),
        WAITING_FOR_YOU(11, "Waiting for you", PhraseCategory.EVERYDAY),
        READY_TO_GO(12, "Ready to go", PhraseCategory.EVERYDAY),
        TAKING_A_BREAK(13, "Taking a break", PhraseCategory.EVERYDAY),
        LOST_SIGNAL_EARLIER(14, "Lost signal earlier", PhraseCategory.EVERYDAY),
        CHECK_IN(15, "Check-in — all fine", PhraseCategory.EVERYDAY),
        NEED_FIVE_MINUTES(16, "Need five minutes", PhraseCategory.EVERYDAY),
        GO_AHEAD_WITHOUT_ME(17, "Go ahead without me", PhraseCategory.EVERYDAY),
        CATCHING_UP(18, "Catching up", PhraseCategory.EVERYDAY),
        STOPPED_FOR_PHOTOS(19, "Stopped for photos", PhraseCategory.EVERYDAY),
        STOPPED_FOR_FOOD(20, "Stopped for food", PhraseCategory.EVERYDAY),
        BATHROOM_BREAK(21, "Bathroom break", PhraseCategory.EVERYDAY),
        CHANGING_ROUTE(22, "Changing route", PhraseCategory.EVERYDAY),
        FOUND_A_SHORTCUT(23, "Found a shortcut", PhraseCategory.EVERYDAY),
        TRAIL_BLOCKED(24, "Trail blocked", PhraseCategory.EVERYDAY),
        GROUP_SPLITTING_UP(25, "Group splitting up", PhraseCategory.EVERYDAY),
        MEETING_POINT_AHEAD(26, "Meeting point ahead", PhraseCategory.EVERYDAY),
        ALL_ACCOUNTED_FOR(27, "All accounted for", PhraseCategory.EVERYDAY),

        // ── Outdoor (28-47) ──────────────────────────────────────────────
        LOW_ON_WATER(28, "Low on water", PhraseCategory.OUTDOOR),
        LOW_ON_FOOD(29, "Low on food", PhraseCategory.OUTDOOR),
        LOW_ON_BATTERY(30, "Low on battery", PhraseCategory.OUTDOOR),
        GOOD_CAMPSITE_AHEAD(31, "Good campsite ahead", PhraseCategory.OUTDOOR),
        WATER_SOURCE_AHEAD(32, "Water source ahead", PhraseCategory.OUTDOOR),
        STEEP_TERRAIN_AHEAD(33, "Steep terrain ahead", PhraseCategory.OUTDOOR),
        RIVER_CROSSING_AHEAD(34, "River crossing ahead", PhraseCategory.OUTDOOR),
        WILDLIFE_SPOTTED(35, "Wildlife spotted", PhraseCategory.OUTDOOR),
        TRAIL_MARKER_LOST(36, "Trail marker lost", PhraseCategory.OUTDOOR),
        SETTING_UP_CAMP(37, "Setting up camp", PhraseCategory.OUTDOOR),
        BREAKING_CAMP(38, "Breaking camp", PhraseCategory.OUTDOOR),
        SUNSET_SOON_MOVE(39, "Sunset soon — move", PhraseCategory.OUTDOOR),
        FOG_ROLLING_IN(40, "Fog rolling in", PhraseCategory.OUTDOOR),
        GOOD_SIGNAL_HERE(41, "Good signal here", PhraseCategory.OUTDOOR),
        NO_SIGNAL_AHEAD(42, "No signal ahead", PhraseCategory.OUTDOOR),
        ICE_ON_TRAIL(43, "Ice on trail", PhraseCategory.OUTDOOR),
        ROCKFALL_RISK(44, "Rockfall risk", PhraseCategory.OUTDOOR),
        STREAM_CROSSED_SAFELY(45, "Stream crossed safely", PhraseCategory.OUTDOOR),
        SUMMIT_REACHED(46, "Summit reached", PhraseCategory.OUTDOOR),
        DESCENDING_NOW(47, "Descending now", PhraseCategory.OUTDOOR),

        // ── Emergency (48-69) ────────────────────────────────────────────
        // Only NEED_HELP(6) is urgent-bypassed (PHASE 4.3) — these are
        // catalog entries forwarded via the normal PHRASE(35) allowlisted
        // path, same as everyday/outdoor phrases.
        INJURED_MINOR(48, "Injured — minor", PhraseCategory.EMERGENCY),
        INJURED_SERIOUS(49, "Injured — serious, need assistance", PhraseCategory.EMERGENCY),
        LOST_TRAIL(50, "Lost the trail", PhraseCategory.EMERGENCY),
        SEPARATED_FROM_GROUP(51, "Separated from group", PhraseCategory.EMERGENCY),
        WEATHER_EMERGENCY(52, "Weather emergency", PhraseCategory.EMERGENCY),
        ANIMAL_THREAT(53, "Animal threat nearby", PhraseCategory.EMERGENCY),
        NEED_FIRST_AID(54, "Need first aid supplies", PhraseCategory.EMERGENCY),
        NEED_WATER_URGENT(55, "Need water urgently", PhraseCategory.EMERGENCY),
        STRANDED(56, "Stranded — cannot proceed", PhraseCategory.EMERGENCY),
        CALLING_FOR_RESCUE(57, "Calling for rescue", PhraseCategory.EMERGENCY),
        STAY_WHERE_YOU_ARE(58, "Stay where you are", PhraseCategory.EMERGENCY),
        SENDING_HELP(59, "Sending help your way", PhraseCategory.EMERGENCY),
        HELP_ARRIVED(60, "Help arrived", PhraseCategory.EMERGENCY),
        FALSE_ALARM(61, "False alarm — disregard", PhraseCategory.EMERGENCY),
        EQUIPMENT_FAILURE(62, "Equipment failure", PhraseCategory.EMERGENCY),
        SHELTER_NEEDED(63, "Shelter needed", PhraseCategory.EMERGENCY),
        HYPOTHERMIA_RISK(64, "Hypothermia risk", PhraseCategory.EMERGENCY),
        DEHYDRATION_RISK(65, "Dehydration risk", PhraseCategory.EMERGENCY),
        NIGHTFALL_STRANDED(66, "Stranded after nightfall", PhraseCategory.EMERGENCY),
        GROUP_REGROUPED(67, "Group regrouped safely", PhraseCategory.EMERGENCY),
        EVERYONE_SAFE(68, "Everyone safe", PhraseCategory.EMERGENCY),
        EMERGENCY_OVER(69, "Emergency over", PhraseCategory.EMERGENCY);

        companion object {
            fun fromCode(code: Int): PhraseCode? = values().firstOrNull { it.code == code }

            /** Pure wire codec — [1B code][4B seq], no signature envelope
             *  here (that's applied uniformly by writeRawFrame/
             *  meshSigner.signIfNeeded for every signed type, PHRASE
             *  included — see TYPE_PHRASE's own doc). Off-device-testable
             *  (see PhraseCodeTest) without constructing an
             *  OfflineMediaTransport, same "pure codec" pattern as
             *  MeshLocation.encode/decode. */
            fun encode(code: Int, seq: Long): ByteArray {
                val buf = ByteBuffer.allocate(5)
                buf.put((code and 0xFF).toByte())
                buf.putInt((seq and 0xFFFFFFFFL).toInt())
                return buf.array()
            }

            /** Returns (code, seq) for any structurally-valid 5-byte
             *  payload — [code] may be OUTSIDE the known table (including
             *  the reserved 7): decode still succeeds, only [fromCode]
             *  distinguishes "known" from "unknown" afterward. Null only for
             *  a malformed (wrong-length) payload — never throws. */
            fun decode(bytes: ByteArray): Pair<Int, Long>? {
                if (bytes.size != 5) return null
                val buf = ByteBuffer.wrap(bytes)
                val code = buf.get().toInt() and 0xFF
                val seq = buf.int.toLong() and 0xFFFFFFFFL
                return code to seq
            }
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
        // PART 2.4 (optical transfer): visible (not private) — OpticalFrame.kt
        // reuses this exact byte so a text/file payload sent optically is a
        // real TYPE_CHAT frame, wire-identical to one sent over radio.
        const val TYPE_CHAT: Byte = 4
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
        // PHASE 8 TRACK C2: SELECTIVE SUBSCRIPTION — claims the gap left at 10.
        // dst=this device's current uplink (the GO, in today's flat topology —
        // see uplinkNodeId()), one hop only. Payload [1B count][count*8B
        // srcId]: the exact set of srcIds this device currently wants
        // TYPE_FRAME (video) from — see handleSubscribeFrame/
        // maybeSendVideoSubscription. Never affects TYPE_AUDIO or any control
        // type, including this one. A device that has never sent one is
        // treated as "wants everyone" (see PeerLink.videoSubscription's doc)
        // so a pre-C2 client, or any 2/3-device call where nobody ever crosses
        // the tile-budget threshold, behaves exactly as before this track.
        private const val TYPE_SUBSCRIBE: Byte = 10
        // PHASE 5A: SOS / FIND-over-mesh — see the class doc's SOS/FIND paragraph
        // below, MeshSosManager for the actual logic, and MeshLocation for the
        // shared payload. Purely additive; types 1-19 are unchanged.
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
        // PART 2.4 (optical transfer): visible (not private) — OpticalFrame.kt
        // reuses this exact byte so a Group Alert sent optically is a real
        // TYPE_POSITION frame, wire-identical to one sent over radio.
        const val TYPE_POSITION: Byte = 23
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
        // PHASE 8 TRACK C3: relay tree. Types 1-29 unchanged; these are all
        // one-hop-to-uplink or GO-broadcast, same shape as TYPE_VAD/TYPE_ROSTER.
        //   type 30 = LINK_REPORT — dst=BROADCAST, every node, every 10s:
        //             [8B rttToUplinkMs][1B bleCount][bleCount*(8B nodeId,1B
        //             rssiDbm-signed)] — GO-side input to computeTree.
        //   type 31 = LINK_PROBE — dst=current uplink, one hop, [8B echoToken].
        //   type 32 = LINK_PROBE_ACK — dst=original requester, one hop back,
        //             [8B echoToken] — the LINK_PROBE/ACK round trip is this
        //             device's own RTT-to-uplink measurement.
        //   type 33 = TREE_ASSIGN — dst=BROADCAST, GO only, on rebuild:
        //             [4B genId][1B nodeCount][nodeCount*(8B nodeId,8B
        //             parentNodeId-or-TREE_ROOT_SENTINEL,4B ipv4,2B port)].
        //   type 34 = UPLINK_STATUS — dst=BROADCAST (GO-consumed), any node,
        //             on actual-parent change: [8B actualParentNodeId][1B
        //             mode: 0=assigned,1=fallback-to-go].
        private const val TYPE_LINK_REPORT: Byte = 30
        private const val TYPE_LINK_PROBE: Byte = 31
        private const val TYPE_LINK_PROBE_ACK: Byte = 32
        private const val TYPE_TREE_ASSIGN: Byte = 33
        private const val TYPE_UPLINK_STATUS: Byte = 34
        // OFFLINE UI: canned-phrase messaging — dst=BROADCAST, same
        // live+carry dual delivery as TYPE_CHAT (see enqueueChatSend's
        // pattern, mirrored by sendPhrase). [1B phraseCode][4B seq] — no
        // free text on the wire (see PhraseCode's doc); the existing
        // TYPE_CHAT free-text path is untouched, these are deliberately
        // separate types.
        private const val TYPE_PHRASE: Byte = 35

        // TOPO PHASE 3.4 / B5 (diagnostic follow-up, NOW WIRED): push-to-talk
        // voice notes. Payload: [1B codecId][4B BE durationMs][audio bytes,
        // remaining] — a deviation from this constant's ORIGINAL reservation
        // comment (which assumed Opus, LE byte order): the confirmed capture
        // path is a standalone MediaRecorder (see VoiceNoteRecorder.kt), and
        // MediaRecorder's Opus output (OutputFormat.OGG + AudioEncoder.OPUS)
        // is API 29+ only — this app's minSdk is 26. AAC/MPEG_4
        // (VOICE_NOTE_CODEC_AAC_MP4) is broadly supported since long before
        // API 26 and needs no version gate; BE matches every other
        // multi-byte field this app's wire format uses elsewhere (see e.g.
        // TYPE_FRAME_TS's identical BE convention). [codecId] is stamped so
        // a future second codec is self-describing on the wire, same
        // discipline as AudioCodec's own wireId byte for live calls.
        //
        // DELIVERY: exactly [sendPhrase]'s existing broadcast pattern (a
        // one-shot message, not a repeating heartbeat like SOS) — live
        // writeFrame to whoever's connected now, plus carrier.put with
        // alreadyDeliveredTo=current roster so MeshCarrier only ever offers
        // it to someone who reconnects LATER, never re-delivers to someone
        // who already got the live copy. No separate content-level dedupe
        // needed on receive, same reasoning as TYPE_PHRASE/TYPE_CHAT.
        const val TYPE_VOICE_NOTE: Byte = 36
        const val VOICE_NOTE_CODEC_AAC_MP4: Byte = 1
        const val VOICE_NOTE_CARRY_EXPIRY_MINS = 24 * 60
        // Recording-side cap (enforced by VoiceNoteRecorder, not this class) —
        // documented here too since it bounds this payload's realistic max
        // size for MAX_VOICE_NOTE_PAYLOAD_BYTES below.
        const val VOICE_NOTE_MAX_DURATION_MS = 30_000
        // fix (real bug, every voice note sent so far was silently dropped
        // on receive): TYPE_VOICE_NOTE was never added to maxPayloadFor's
        // when, so it fell to the `else -> MAX_CONTROL_PAYLOAD_BYTES` branch
        // (1024B, ~1092B signed) — the OLD comment above this block claiming
        // "well under any practical mesh-frame size concern" was never
        // actually checked against that. Worst case at VoiceNoteRecorder's
        // real settings (32000 bps = 4000 B/s, 30s): 4000*30 = 120,000 bytes
        // raw AAC. 144*1024=147,456 bytes — about 27KB of margin for
        // MPEG_4 container overhead (moov/mdat/ftyp atoms) and encoder
        // bitrate variance — comfortably under MAX_VIDEO_PAYLOAD_BYTES (256KB), the
        // largest existing precedent, confirming the wire framing itself
        // (MeshFrame's 4B BE length prefix, not a 16-bit field — up to ~2GB
        // in principle) was never the real constraint; this is purely an
        // application-policy cap, same as every other MAX_*_PAYLOAD_BYTES.
        const val MAX_VOICE_NOTE_PAYLOAD_BYTES = 144 * 1024

        // OCP PHASE 3.1 (AUTHORISED WIRE ADDITION #1): timestamped video/audio
        // — next free numbers after TYPE_VOICE_NOTE(36, reserved but not yet
        // wired). Types 1-36 are UNCHANGED; VERSION stays 3 (G2). Payload =
        // [8B BE monotonic capture micros] + the SAME raw encoder bytes
        // TYPE_FRAME/TYPE_AUDIO already carry, unchanged. Sent only toward a
        // peer whose HELLO advertised CAP_FRAME_AGE (G6) — see
        // PeerLink.supportsFrameAge / writeRawFrame's dual-wrap. An older
        // peer that never advertised support only ever receives the legacy
        // type, so it needs no awareness these two numbers exist at all.
        private const val TYPE_FRAME_TS: Byte = 37
        private const val TYPE_AUDIO_TS: Byte = 38
        // OCP PHASE 5.1 (AUTHORISED WIRE ADDITION #2): the low-layer video
        // stream — next free numbers after TYPE_AUDIO_TS(38). The brief
        // asks for "the next free type" (singular); correctness needs TWO
        // here for the exact reason Phase 3 needed two despite similarly
        // loose phrasing — a receiver's decoder needs its OWN low-resolution
        // csd, never mixed with the high stream's (TYPE_CONFIG/TYPE_FRAME
        // are UNCHANGED and untouched by this — a low-layer-unaware peer
        // never receives either new type, see CAP_SIMULCAST below). Types
        // 1-38 unchanged; VERSION stays 3 (G2).
        private const val TYPE_CONFIG_LOW: Byte = 39
        private const val TYPE_FRAME_LOW: Byte = 40
        // OCP PHASE 3/G6: HELLO capability bitfield — bit 0 only until now.
        // Additive: a future capability takes the next free bit, never
        // renumbers an existing one.
        private const val CAP_FRAME_AGE = 0x01
        // OCP PHASE 5.1/G6: advertised only once this build actually
        // understands TYPE_CONFIG_LOW/TYPE_FRAME_LOW and the extended
        // TYPE_SUBSCRIBE low-layer list — see maybeSendVideoSubscription.
        private const val CAP_SIMULCAST = 0x02
        // OCP PHASE 5.1: 320x240 ~250kbps — deliberately far below
        // WIDTH_DEFAULT/HEIGHT_DEFAULT/BITRATE_DEFAULT; this is a grid
        // THUMBNAIL feed, never the pinned/active-speaker tile's stream.
        private const val LOW_LAYER_WIDTH = 320
        private const val LOW_LAYER_HEIGHT = 240
        private const val LOW_LAYER_BITRATE = 250_000
        // Lower than FPS_DEFAULT(30) — a thumbnail tile doesn't need full
        // motion smoothness, and halving fps roughly halves bandwidth on
        // top of the resolution cut, for the participants>4 case this only
        // ever runs in.
        private const val LOW_LAYER_FPS = 15
        // OCP PHASE 3.4: age budgets — the actual lag fix. A frame older
        // than its budget is dropped before decode (and, on a relay, before
        // it would otherwise be forwarded — see resolveFrameAge/routeFrame).
        private const val AUDIO_AGE_BUDGET_MS = 200L
        private const val VIDEO_AGE_BUDGET_MS = 250L

        /** OCP PHASE 3.4: pure keep/drop decision — [isIdr] and
         *  [isAtLeastAsNewAsLastAcceptedIdr] are only ever consulted when
         *  [ageMs] is already over [budgetMs] (an in-budget frame is always
         *  kept outright); extracted for direct unit testing. */
        fun shouldKeepAgedFrame(ageMs: Long, budgetMs: Long, isIdr: Boolean, isAtLeastAsNewAsLastAcceptedIdr: Boolean): Boolean {
            if (ageMs <= budgetMs) return true
            return isIdr && isAtLeastAsNewAsLastAcceptedIdr
        }

        /** OCP PHASE 4.3: pure derivations — extracted from [initTileBudget]
         *  for direct unit testing of "no hardcoded 4 anywhere." */
        /** OCP PHASE 5.4: pure mapping from PowerManager's THERMAL_STATUS_*
         *  int constants to the short string used in both the CAP line
         *  (Phase 0.1) and the THERMAL: log line (Phase 5.4) — extracted so
         *  it's directly unit-testable without a PowerManager instance. */
        fun thermalStatusString(status: Int): String = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> "unknown"
        }

        /** OCP CONNECT REBUILD PART 6: same probing logic as the instance-
         *  level probeMaxAvcDecoderInstances/probeMaxAvcEncoderInstances,
         *  exposed as a pure companion function so the CAP line can print
         *  at APP START, before any OfflineMediaTransport instance exists
         *  (no call has ever needed to succeed for this to run — see
         *  OfflineCallActivity.logCapabilityLineAtAppStart). */
        fun probeMaxAvcInstancesStatic(isEncoder: Boolean): Int {
            return try {
                val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                var min = Int.MAX_VALUE
                for (info in list.codecInfos) {
                    if (info.isEncoder != isEncoder) continue
                    if (!info.supportedTypes.any { it.equals("video/avc", ignoreCase = true) }) continue
                    val caps = try { info.getCapabilitiesForType("video/avc") } catch (e: Exception) { continue }
                    val n = caps.maxSupportedInstances
                    if (n > 0) min = minOf(min, n)
                }
                if (min == Int.MAX_VALUE) DECODER_PROBE_FALLBACK else min
            } catch (e: Exception) {
                Log.w("OFFTRACE", "SCALE: ${if (isEncoder) "encoder" else "decoder"} probe failed (${e.message}) — using fallback=$DECODER_PROBE_FALLBACK")
                DECODER_PROBE_FALLBACK
            }
        }

        fun deriveTileBudget(probedDecoders: Int): Int = (probedDecoders - 1).coerceIn(TILE_BUDGET_MIN, TILE_BUDGET_CEILING)
        fun deriveMaxLiveCameras(probedDecoders: Int): Int = (probedDecoders - 1).coerceIn(MIN_LIVE_CAMERAS, MAX_GROUP_PARTICIPANTS)

        /** B5: pure encode/decode for TYPE_VOICE_NOTE's payload — see that
         *  constant's wire doc for the exact layout. Extracted for direct
         *  unit testing without a constructed transport, same "pure
         *  companion" pattern as MeshLocation.encode/decode. */
        fun encodeVoiceNotePayload(codecId: Byte, durationMs: Int, audioBytes: ByteArray): ByteArray =
            ByteBuffer.allocate(1 + 4 + audioBytes.size).apply {
                put(codecId)
                putInt(durationMs)
                put(audioBytes)
            }.array()

        data class DecodedVoiceNote(val codecId: Byte, val durationMs: Int, val audioBytes: ByteArray)

        /** Null for a payload too short to even hold the fixed header (5
         *  bytes) — an unrecognized [DecodedVoiceNote.codecId] in an
         *  otherwise well-formed payload is NOT null here; the caller
         *  decides whether to reject an unknown codec (see
         *  handleVoiceNoteFrame), same "decode succeeds, dispatch decides"
         *  split MeshLocation.decode uses. */
        fun decodeVoiceNotePayload(payload: ByteArray): DecodedVoiceNote? {
            if (payload.size < 5) return null
            val buf = ByteBuffer.wrap(payload)
            val codecId = buf.get()
            val durationMs = buf.int
            val audioBytes = ByteArray(payload.size - 5).also { buf.get(it) }
            return DecodedVoiceNote(codecId, durationMs, audioBytes)
        }

        /** OCP PHASE 5.1: splits [visiblePeersRanked] (everyone this device
         *  intends to decode SOMETHING for — pin-first, then speaker, then
         *  recency, then join order; see evaluateTileBudget's own
         *  comparator, which is what actually produces this ordering) into
         *  a HIGH set (pinned tile + active speaker only) and a LOW set
         *  (everyone else) — only when [simulcastActive]; otherwise every
         *  visible peer stays HIGH and low is empty, i.e. byte-for-byte
         *  Phase 4 behavior for any call at or below
         *  SIMULCAST_PARTICIPANT_THRESHOLD. If NEITHER pin nor active
         *  speaker is currently visible (nobody pinned, nobody speaking
         *  yet), the highest-ranked visible peer becomes HIGH so the grid
         *  is never 100% low-resolution. Decoder COUNT is never affected
         *  either way — every peer in [visiblePeersRanked] already has (or
         *  is about to get) a decoder slot; this only decides which bytes
         *  feed it. */
        fun splitHighLow(
            visiblePeersRanked: List<Long>,
            pin: Long?,
            activeSpeakerId: Long?,
            simulcastActive: Boolean
        ): Pair<Set<Long>, Set<Long>> {
            if (!simulcastActive) return visiblePeersRanked.toSet() to emptySet()
            val visible = visiblePeersRanked.toSet()
            var high = setOfNotNull(pin, activeSpeakerId).filter { it in visible }.toSet()
            if (high.isEmpty()) high = visiblePeersRanked.take(1).toSet()
            return high to (visible - high)
        }

        // ── PHASE 4: relay suppression (battery-weighted delay + duplicate
        // suppression) — pure, off-device-testable companions. Types 1-19
        // (every media/group-call type, including TYPE_AUDIO/TYPE_FRAME)
        // have NO dedup today and carry live audio/video — deferring those
        // by hundreds of ms would destroy every call on the mesh. This is
        // therefore an explicit ALLOWLIST, never an exclusion list: a new
        // media type added later defaults to the existing immediate-forward
        // path (routeFrame only calls into this suppression path when
        // isRelaySuppressionAllowlisted(header.type) is true).
        private val RELAY_SUPPRESSION_ALLOWLIST: Set<Byte> = setOf(
            TYPE_CHAT, TYPE_ROSTER, TYPE_SOS, TYPE_FIND_REQ, TYPE_FIND_RESP,
            TYPE_POSITION, TYPE_SOS_ACK, TYPE_STORE_FWD, TYPE_SF_ACK, TYPE_PHRASE
        )
        fun isRelaySuppressionAllowlisted(type: Byte): Boolean = type in RELAY_SUPPRESSION_ALLOWLIST

        const val RELAY_K_SUPPRESS = 2
        const val RELAY_CACHE_CAPACITY = 512
        const val RELAY_CACHE_TTL_MS = 10 * 60 * 1000L

        /** 64-bit FNV-1a over (srcId, type, payload) — no wire field added,
         *  purely an in-memory dedup key for the relay-suppression caches
         *  (a THIRD cache; MeshSosManager's and MeshCarrier's own dedupe
         *  caches, above, are never touched by this phase). Stable across
         *  identical inputs; a single flipped payload byte changes it. */
        fun relayFrameId(srcId: Long, type: Byte, payload: ByteArray): Long {
            var hash = -3750763034362895579L // FNV-1a 64 offset basis (0xcbf29ce484222325)
            val prime = 1099511628211L
            for (i in 7 downTo 0) {
                hash = hash xor ((srcId ushr (i * 8)) and 0xFF)
                hash *= prime
            }
            hash = hash xor (type.toLong() and 0xFF)
            hash *= prime
            for (b in payload) {
                hash = hash xor (b.toLong() and 0xFF)
                hash *= prime
            }
            return hash
        }

        /** SOS(20)/SOS_ACK(24)/FIND_REQ(21) unconditionally, or PHRASE(35)
         *  whose first payload byte (the wire code — see PhraseCode.encode's
         *  [1B code][4B seq] doc) is NEED_HELP(6). Bypasses ALL THREE
         *  relayDelayMs gates (disabled / not-charging-only / below-minimum-
         *  battery) and is never delayed or dupCount-suppressed — see
         *  routeFrame's call site. */
        fun isUrgentRelayFrame(type: Byte, payload: ByteArray): Boolean = when (type) {
            TYPE_SOS, TYPE_SOS_ACK, TYPE_FIND_REQ -> true
            TYPE_PHRASE -> payload.isNotEmpty() && (payload[0].toInt() and 0xFF) == PhraseCode.NEED_HELP.code
            else -> false
        }

        /** Null means NEVER relay this device's deferred copy (one of the
         *  three gates fired). [random] is injectable (defaults to a real
         *  RNG) so tests can pin the jitter term and assert the deterministic
         *  base formula exactly. Formula and constants exactly as specified:
         *  score = batteryPct/100, +0.4 (capped at 1.0) if charging; delay =
         *  800ms * (1.05 - score) * (0.5 + random[0,1)). */
        fun relayDelayMs(
            relayEnabled: Boolean,
            relayOnlyWhenCharging: Boolean,
            charging: Boolean,
            batteryPct: Int,
            relayMinBattery: Int,
            random: () -> Float = { kotlin.random.Random.nextFloat() }
        ): Long? {
            if (!relayEnabled) return null
            if (relayOnlyWhenCharging && !charging) return null
            if (batteryPct < relayMinBattery && !charging) return null
            var score = batteryPct / 100f
            if (charging) score = kotlin.math.min(1f, score + 0.4f)
            val jitter = 0.5f + random()
            return (800L * (1.05f - score) * jitter).toLong()
        }

        /** OCP PHASE 2.2: pure seeding logic for tickGoMix's initial
         *  committedMixSpeakers commit on a threshold crossing — extracted
         *  so it's directly unit-testable without a live transport
         *  instance. Prefers the most recent VAD-active peers
         *  ([rawMixSpeakers], already ranked by GroupCallMixer); falls back
         *  to the first up-to-3 participants by join order only when no VAD
         *  data exists yet at all (e.g. the 4th participant joins silently,
         *  before anyone has spoken a word since the crossing). */
        fun seedMixSpeakers(
            rawMixSpeakers: List<Long>,
            participants: Collection<Long>,
            localNodeId: Long,
            joinSequence: Map<Long, Int>
        ): List<Long> {
            val fromRaw = rawMixSpeakers.take(3)
            if (fromRaw.isNotEmpty()) return fromRaw
            return participants.filter { it != localNodeId }
                .sortedBy { joinSequence[it] ?: Int.MAX_VALUE }
                .take(3)
        }

        private const val MAX_CHAT_PAYLOAD_BYTES = 4096
        // Envelope header (42B, see MeshCarrier.ENVELOPE_HEADER_SIZE) + the
        // largest inner payload this mesh currently carries. fix: was sized
        // for a chat message (4096B) — a CARRIED voice note (TYPE_STORE_FWD
        // is the outer frame's type for anything traveling via MeshCarrier,
        // so it's THIS constant, not MAX_VOICE_NOTE_PAYLOAD_BYTES, that
        // gated a carried voice note's receive-side bounds check) would
        // have been silently dropped here too, even after fixing the direct
        // TYPE_VOICE_NOTE case above. Voice notes (~144KB cap) are now the
        // largest thing this mesh ever carries, by a wide margin over every
        // other carried type (SOS/chat/phrase) — sized for that.
        private const val MAX_STORE_FWD_PAYLOAD_BYTES = 42 + MAX_VOICE_NOTE_PAYLOAD_BYTES
        // PHASE 7A STEP 5: matches the display-name validation limit exactly
        // (see OfflineCallActivity's showDisplayNameDialog) — this wire-level
        // truncation is a defensive floor, not the primary enforcement point.
        private const val MAX_NAME_BYTES = 32

        // PHASE 3B: group calls
        private const val MAX_GROUP_PARTICIPANTS = 8 // WiFi Direct GO client ceiling
        private const val BUSY_REASON_CALL_FULL: Byte = 1
        // PHASE 3C / OCP PHASE 4.3: simultaneous-live-camera ceiling — the
        // honest WiFi Direct radio limit; without this, every participant's
        // camera turning on saturates the link. Audio-only participation
        // beyond this cap is unlimited (up to MAX_GROUP_PARTICIPANTS). REMOVED
        // the flat "= 4" — see [initTileBudget], which now derives this
        // device's own maxLiveCameras from the SAME decoder probe tileBudget
        // uses, coerced into 2..MAX_GROUP_PARTICIPANTS. 2 is this app's
        // documented floor (a stronger device can host more; nothing weaker
        // than 2 simultaneous cameras is a useful video call at all).
        private const val MIN_LIVE_CAMERAS = 2
        // PHASE 8 STEP 2: automatic recovery for a degraded group tile decoder.
        private const val DEGRADED_RETRY_INTERVAL_MS = 10_000L
        private const val DEGRADED_MAX_RETRY_ATTEMPTS = 5
        // PHASE 8 STEP 3: GO-side audio mixing — BELOW this participant count,
        // the existing forward-and-mix-locally path (unchanged, see audioDst/
        // dispatchLocal's TYPE_AUDIO branch) stays exactly as it works today.
        // AT or above it, the GO stops relaying raw per-sender audio and
        // instead mixes+redistributes (see tickGoMix).
        private const val GO_MIX_PARTICIPANT_THRESHOLD = 4
        // OCP PHASE 5.1: the low encoder runs only once a group call
        // actually needs grid thumbnails at all — reuses
        // GO_MIX_PARTICIPANT_THRESHOLD's ">4" boundary deliberately (not a
        // second, independently-tunable number), matching this file's own
        // established pattern for its other N-scaling behaviors (see
        // TREE_MIN_SIZE_FOR_RELAY's identical reuse, below).
        private const val SIMULCAST_PARTICIPANT_THRESHOLD = GO_MIX_PARTICIPANT_THRESHOLD
        // PHASE 8 TRACK C3: relay tree — GO=depth0, direct children=depth1,
        // grandchildren=depth2. TREE_MIN_SIZE_FOR_RELAY reuses
        // GO_MIX_PARTICIPANT_THRESHOLD deliberately (not a second,
        // independently-tunable number) — this is the ONE size threshold
        // where any of this phase's N-scaling behavior activates at all;
        // below it, computeTree's own first check never even looks at RSSI/
        // RTT and every node's parent is unconditionally the GO (see
        // computeTree's doc — this is also the <=3-device proof).
        private const val TREE_FANOUT_CAP = 3
        private const val TREE_MAX_DEPTH = 2
        private const val TREE_MIN_SIZE_FOR_RELAY = GO_MIX_PARTICIPANT_THRESHOLD
        private const val TREE_REBUILD_COOLDOWN_MS = 5_000L
        private const val LINK_REPORT_INTERVAL_MS = 10_000L
        private const val LINK_PROBE_INTERVAL_MS = 10_000L
        private const val RSSI_STALE_MS = 30_000L
        // A candidate parent must be measurably better than the GO directly —
        // avoids reshuffling the tree over noise-level RSSI/RTT differences.
        private const val RSSI_MARGIN_DBM = 10
        // GO-only, unreachability confirmed after this many silent LINK_REPORT
        // cycles (~30s) — same order of magnitude as GO_HEARTBEAT's 3-miss
        // (30s) GO-loss detection, deliberately not tighter.
        private const val LINK_REPORT_STALE_MS = 30_000L
        // Sentinel "no parent — I am the root" value for treeParentOf/
        // assignedParentId — deliberately NOT MeshFrame.BROADCAST_ID or
        // PENDING_ID, which already mean other things in this codebase; a
        // fixed value nowhere near a real SHA-256-derived nodeId.
        private const val TREE_ROOT_SENTINEL: Long = Long.MIN_VALUE
        private const val GO_MIX_TICK_MS = 20L
        private const val MIX_SPEAKER_EVAL_INTERVAL_MS = 500L
        private const val MIX_SPEAKER_HOLD_MS = 1_500L
        // Sentinel key into groupAudioDecoders/groupLatestPcm for a CLIENT's
        // single incoming GO-mixed stream — there is exactly one, never one
        // per sender, so it is never keyed by a real nodeId. Distinct from
        // every other sentinel this file/MeshFrame already defines.
        private const val GO_MIX_DECODER_KEY: Long = -100L
        // PHASE 8 STEP 4: video tile decoder budget — runs on EVERY device
        // (unlike STEP 3's audio mixing, which is GO-only), since decoder
        // limits are a per-DEVICE hardware constraint. Fallback used only if
        // MediaCodecList probing itself throws — see probeMaxAvcDecoderInstances.
        private const val DECODER_PROBE_FALLBACK = 4
        // OCP PHASE 4.3: REMOVED the flat TILE_BUDGET_MAX=4 — tileBudget is
        // now coerced into TILE_BUDGET_MIN..TILE_BUDGET_CEILING (2..8) around
        // whatever probeMaxAvcDecoderInstances actually reports, so a
        // stronger device is no longer artificially capped at the same 4
        // tiles as the weakest one this app supports. See [initTileBudget].
        private const val TILE_BUDGET_MIN = 2
        private const val TILE_BUDGET_CEILING = 8
        private const val TILE_BUDGET_EVAL_INTERVAL_MS = 1_000L
        private const val TILE_SWAP_HYSTERESIS_MS = 3_000L
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
        // PHASE 8 TRACK C3: bumped 4->6 — a broadcast now potentially crosses
        // up to 3 real hops (grandchild -> its parent -> GO -> other parent ->
        // other grandchild, TREE_MAX_DEPTH=2 each side) plus margin for a
        // make-before-break transition window; a plain constant tune, not a
        // frame-type-value change, so it doesn't touch the "never change an
        // existing frame type constant" rule (that's about the type byte).
        private const val TTL_BROADCAST: Byte = 6
        private const val MAX_VIDEO_PAYLOAD_BYTES = 256 * 1024
        private const val MAX_AUDIO_PAYLOAD_BYTES = 4096
        private const val MAX_CONFIG_PAYLOAD_BYTES = 4096
        private const val MAX_CONTROL_PAYLOAD_BYTES = 1024 // mode/audio-codec/hello/busy/hangup/roster
        // PHASE 8 STEP 5: DEFAULT/tier-1 values only now — see the mutable
        // WIDTH/HEIGHT/FPS/BITRATE instance fields below (this class's own
        // resolution ladder rewrites those live as participant count
        // crosses a tier boundary; these constants are what a fresh call
        // always starts at, and what LADDER_TIER_1 always resolves to).
        private const val WIDTH_DEFAULT = 1280
        private const val HEIGHT_DEFAULT = 720
        private const val FPS_DEFAULT = 30
        private const val BITRATE_DEFAULT = 2_000_000
        // PHASE 8 STEP 5 / TRACK C5 REVISED LADDER: resolution ladder tiers —
        // see ladderTierFor. Bitrates are standard-ish mobile-video
        // conventions for each resolution (not given explicitly by the spec,
        // which only fixes resolution+fps per tier). Bumped from the original
        // (4/8) breakpoints to (8/20) now that C2 (selective subscription) and
        // C3 (relay tree) remove the bandwidth ceiling that justified the more
        // conservative original tiering — quality now HOLDS at 720p up to 8
        // peers, per this phase's explicit requirement. <=8 still reuses the
        // exact pre-existing default values, so a 2/3 device call remains
        // byte-for-byte identical to before this phase.
        private const val LADDER_TIER1_MAX = 8
        private const val LADDER_TIER2_MAX = 20
        private const val LADDER_TIER3_MAX = 20
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
        // PART B / B2: raised from the original 500ms — Thread.interrupt() does
        // NOT unblock a thread parked inside a native AudioRecord.read() or
        // MediaCodec.dequeueOutputBuffer() call (only Java-level blocking, like
        // a BlockingQueue.poll, actually responds to it), so 500ms left too
        // little margin for those calls' own timeouts to naturally elapse and
        // let the loop notice callActive==false on its own. This alone is
        // still just a best-effort budget, not a guarantee — see
        // [CODEC_RELEASE_WAIT_MS]/[waitForCodecFree] for the actual gate.
        private const val CALL_THREAD_JOIN_MS = 1_500L
        // PART B / B2: a SEPARATE, second wait — applied at the point a
        // release function is about to call stop()/release() on a codec/
        // AudioRecord a worker thread might still be inside, gated on that
        // worker's OWN "I am inside a native call right now" flag rather than
        // trusting stopCallThreads()'s join() alone. Short: by the time a
        // release function runs, stopCallThreads() has already spent up to
        // [CALL_THREAD_JOIN_MS] joining — this is only the final margin for
        // the flag to clear.
        private const val CODEC_RELEASE_WAIT_MS = 300L

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
        // OCP PHASE 0.2: LAT line — 1/sec/peer, per the brief's own spec.
        private const val LAT_LOG_INTERVAL_MS = 1_000L

        /** Pure throttle decision — extracted from [maybeLogPeerLatency] so
         *  "1/sec/peer" is directly unit-testable without a live transport
         *  instance (this project has no Robolectric — see
         *  OfflineMediaTransportTest's class doc for the same constraint). */
        fun shouldLogLatNow(lastLogAtMs: Long, now: Long): Boolean = now - lastLogAtMs >= LAT_LOG_INTERVAL_MS
        // OCP PHASE 3.5: sentinel for "no age known yet" — a peer that has
        // never sent a TYPE_FRAME_TS/TYPE_AUDIO_TS frame (never advertised
        // CAP_FRAME_AGE, or hasn't sent media since resolving).
        private const val AGE_MS_NOT_YET_IMPLEMENTED = -1L
    }

    /** Pure, off-device-testable — the exact seen/dupCount/bounds/TTL logic
     *  scheduleAllowlistedForward uses, extracted into its own plain class
     *  (no Android/Context dependency) specifically so cache bounds and
     *  TTL-expiry behavior are unit-testable without a real
     *  OfflineMediaTransport instance (which needs sockets/camera/audio to
     *  construct). Matches MeshSosManager's/MeshCarrier's own
     *  LinkedHashMap+removeEldestEntry+prune-on-access pattern exactly.
     *  Declared here, at class level rather than inside the companion
     *  object above, for the same reason PartyRingView.RenderMode is: a
     *  type nested inside a companion object is only reachable from outside
     *  as Outer.Companion.Nested, not the shorter Outer.Nested this class's
     *  own test file needs. */
    class RelayDedupeCache(
        private val capacity: Int = RELAY_CACHE_CAPACITY,
        private val ttlMs: Long = RELAY_CACHE_TTL_MS
    ) {
        private val seenAtMs = object : LinkedHashMap<Long, Long>(16, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long>?): Boolean = size > capacity
        }
        private val dupCount = HashMap<Long, Int>()

        /** Prunes TTL-expired entries, then: true + increments dupCount if
         *  [id] was already seen; false + records it as newly-seen
         *  otherwise. */
        @Synchronized
        fun observe(id: Long, nowMs: Long): Boolean {
            prune(nowMs)
            val seen = seenAtMs.containsKey(id)
            if (seen) dupCount[id] = (dupCount[id] ?: 0) + 1 else seenAtMs[id] = nowMs
            return seen
        }

        @Synchronized
        fun dupCountFor(id: Long): Int = dupCount[id] ?: 0

        @Synchronized
        fun size(): Int = seenAtMs.size

        private fun prune(nowMs: Long) {
            val it = seenAtMs.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (nowMs - e.value > ttlMs) {
                    dupCount.remove(e.key)
                    it.remove()
                }
            }
        }
    }

    /** OCP PHASE 3.3: per-source clock-offset baseline + age computation.
     *  Pure, off-device-testable (no Android/Context dependency — same
     *  rationale as [RelayDedupeCache], declared here rather than inside the
     *  companion object for the identical reason that class's own doc gives).
     *  Clocks are NOT synchronized between devices, so age is a DELTA
     *  against a baseline recorded on this source's FIRST frame, never a
     *  raw comparison of [theirStampMicros] against this device's own clock
     *  — see [ageMs]'s doc for the exact formula. */
    class FrameAgeTracker {
        private class Baseline(@Volatile var offsetMicros: Long, @Volatile var lastTheirStampMicros: Long)
        private val baselines = ConcurrentHashMap<Long, Baseline>()

        /** [nowMicros] and [theirStampMicros] must be the SAME kind of
         *  monotonic-since-boot micros both sides already use for encoder
         *  PTS (System.nanoTime()/1000 — see writeRawFrame's capture-time
         *  call site) — never System.currentTimeMillis(), which is
         *  wall-clock and unrelated to either device's boot-relative clock.
         *  On the first frame from [srcId]: offset = nowMicros -
         *  theirStampMicros (this call returns age=0). On every later
         *  frame: age = (nowMicros - theirStampMicros) - offset — the
         *  elapsed time since capture, correct regardless of the constant
         *  absolute offset between the two devices' independent clocks, AS
         *  LONG AS both clocks tick at the same rate (true of monotonic
         *  device clocks modulo drift). Re-baselines (offset recomputed)
         *  if [theirStampMicros] ever goes backward relative to the last
         *  frame seen from this source — a sender clock reset/restart mid-
         *  session, the one case a fixed offset would otherwise silently
         *  invalidate for the rest of the call. */
        fun ageMs(srcId: Long, theirStampMicros: Long, nowMicros: Long): Long {
            val b = baselines.compute(srcId) { _, existing ->
                if (existing == null) {
                    Baseline(offsetMicros = nowMicros - theirStampMicros, lastTheirStampMicros = theirStampMicros)
                } else {
                    if (theirStampMicros < existing.lastTheirStampMicros) {
                        existing.offsetMicros = nowMicros - theirStampMicros
                    }
                    existing.lastTheirStampMicros = theirStampMicros
                    existing
                }
            }!!
            val ageMicros = (nowMicros - theirStampMicros) - b.offsetMicros
            return (ageMicros / 1000L).coerceAtLeast(0L)
        }

        fun reset(srcId: Long) { baselines.remove(srcId) }
        fun resetAll() { baselines.clear() }
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

    // PHASE 8 STEP 3: GO-side audio mixing (participants >= 4 only — see
    // GO_MIX_PARTICIPANT_THRESHOLD). Everything here runs on its OWN
    // dedicated thread (goMixHandler/tickGoMix), never the read/write
    // threads — the original GO-mixing attempt's documented failure (see
    // GroupCallMixer's class doc) was a per-tick decode-all loop running
    // inline; this avoids that by only ever reading PCM that each sender's
    // OWN read thread already decoded into groupLatestPcm (unchanged).
    private var goMixThread: HandlerThread? = null
    private var goMixHandler: Handler? = null
    private var goMixRunnable: Runnable? = null
    private var goMixEncoder: MediaCodec? = null
    // Raw (undebounced) top-3 from GroupCallMixer — same source
    // onRawActiveSpeakersChanged already consumes for the video-highlight
    // debounce; this is a SEPARATE hysteresis on top of the same signal (see
    // evaluateMixSpeakerHysteresis) since flapping here means creating/
    // destroying Opus decode work, not just moving a UI highlight.
    @Volatile private var rawMixSpeakers: List<Long> = emptyList()
    private var pendingMixSpeakers: List<Long> = emptyList()
    private var pendingMixSpeakersSinceMs = 0L
    private var committedMixSpeakers: List<Long> = emptyList()
    // OCP PHASE 2.1: single source of truth for "is the GO mix actually
    // live and delivering audio right now" — read by
    // isGoMixReplacingBroadcastAudio (raw forwarding suppression) and
    // dispatchLocal's TYPE_AUDIO branch (GO's own local-playback gate)
    // instead of either one re-deriving it from the raw participant count.
    // Flips true only once tickGoMix has actually sent this episode's first
    // mixed frame; flips false the instant eligibility is lost, in the same
    // tick, before any frame is dropped — see tickGoMix.
    private val goMixLive = AtomicBoolean(false)
    // OCP PHASE 0.3/2.4: last time ANY audio — raw-forwarded (forwardBroadcast)
    // or GO-mixed (tickGoMix) or the GO's own local raw-mix playback
    // (dispatchLocal) — was actually delivered to at least one recipient.
    // Consumed only by tickGoMix's gapMs computation on a threshold crossing.
    @Volatile private var lastAudioDeliveredAtMs = 0L
    private var lastMixSpeakerEvalMs = 0L
    private var lastGoMixLogMs = 0L
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
    // OCP PHASE 3.3/3.4: see FrameAgeTracker's/resolveFrameAge's docs.
    private val frameAgeTracker = FrameAgeTracker()
    private val lastAcceptedIdrCaptureMicros = ConcurrentHashMap<Long, Long>()
    private var staleAgedFrameDropCount = 0
    // OCP PHASE 3.5: most recent age computed for ANY TYPE_FRAME_TS/
    // TYPE_AUDIO_TS frame from this srcId (kept EVEN for a frame that was
    // then dropped for being over budget — the LAT line should show the
    // real, current age, not silently reset to "unknown" the moment
    // degradation starts). Read by maybeLogPeerLatency; -1 (never
    // overwritten) means this peer has never sent a timestamped frame.
    private val lastKnownAgeMs = ConcurrentHashMap<Long, Long>()
    // Throttles logIfRelayed's "MESH: relayed $n frames from ..." line
    // (below) — this used to log every single relayed frame unconditionally
    // (419 lines in 25s on a 3-device call, 76% of all OFFTRACE output),
    // same noise problem as feedGroupDecoder's per-frame exceptions — see
    // logDroppedGroupFrame for the identical pattern this mirrors.
    private val relayedFrameCount = ConcurrentHashMap<Long, Int>()
    private val relayedLogAtMs = ConcurrentHashMap<Long, Long>()
    // OCP PHASE 0.2: LAT line throttle — one entry per peer nodeId.
    private val lastLatLogAtMs = ConcurrentHashMap<Long, Long>()
    private var unknownDstDropCount = 0
    private var staleMediaDropCount = 0
    // FIX 4: media arriving with no active call at all (previously silently
    // implicit-started a VIDEO call and opened the camera — see acceptMediaFrame).
    private var noActiveCallMediaDropCount = 0
    private val forwardLogCounters = ConcurrentHashMap<Long, Int>()
    private val disconnectedLinks = ConcurrentHashMap.newKeySet<PeerLink>()

    // ── PHASE 8 TRACK C3: relay tree ─────────────────────────────────────────
    // routingTable itself is UNCHANGED — still holds every direct neighbor
    // (parent included) exactly as it always has (see hasChildren()'s doc for
    // why redefining it was unnecessary and would have broken several
    // existing call sites — currentOtherMemberCount, isConnectedOverWifiDirect,
    // handlePeerDisconnected's active-call detection — that all assume a
    // client's uplink is a normal routingTable entry). This section only adds
    // a parallel, purely-additive notion of "which of my direct neighbors is
    // my uplink."
    //
    // nodeId of this device's current uplink neighbor — the one directly-
    // connected PeerLink THIS device itself dialed out to (as opposed to
    // accepted). Null on the GO (always root) and null before this device's
    // first connection resolves.
    @Volatile private var parentNodeId: Long? = null
    // Staged during a make-before-break reassignment (see reassignParentIfNeeded)
    // — the NEW parent link, not yet promoted. Every enqueue() before the swap
    // still targets the OLD (still fully live) parentNodeId's link, so no
    // audio/video interruption ever happens mid-swap.
    @Volatile private var pendingParentLink: PeerLink? = null
    // From the latest TYPE_TREE_ASSIGN — what the GO wants this node's parent
    // to be. TREE_ROOT_SENTINEL = "the GO" (also this field's initial value,
    // which is why every device's fallback target is always the GO by
    // default, with zero tree-specific code needing to run first).
    @Volatile private var assignedParentId: Long = TREE_ROOT_SENTINEL
    // GO-computed, mirrored to every node via TYPE_TREE_ASSIGN — nodeId ->
    // its assigned parent nodeId. Used only by nextHopFor's multi-hop routing
    // decision; empty (and never consulted) for the whole life of any call
    // that never crosses TREE_MIN_SIZE_FOR_RELAY participants.
    private val treeParentOf = ConcurrentHashMap<Long, Long>()
    private val treeAssignedAddress = ConcurrentHashMap<Long, InetSocketAddress>()
    // GO-only: nodeId -> the real, socket-observed remote address a HELLO
    // resolved from (see handleHelloFrame) — ground truth, unspoofable, no
    // wire-protocol addition needed. Assembled into TYPE_TREE_ASSIGN's payload.
    private val nodeDialAddress = ConcurrentHashMap<Long, InetSocketAddress>()
    @Volatile private var treeGenId = 0
    @Volatile private var lastTreeRebuildAtMs = 0L
    // Any node can accept CHILD connections once the GO assigns it some —
    // lazily bound the first time that happens (see ensureRelayServerStarted).
    // Still null for the entire life of a call that never grows a relay node.
    private var relayServerSocket: ServerSocket? = null
    // GO-side inputs to computeTree — refreshed by each node's periodic
    // TYPE_LINK_REPORT broadcast (RTT to ITS OWN uplink, plus fresh BLE
    // neighbor RSSI) and the TYPE_LINK_PROBE/PROBE_ACK round trip (this
    // device's own RTT measurement to whatever it currently dials as uplink).
    private val rttToUplinkByNode = ConcurrentHashMap<Long, Long>()
    private val bleRssiByNodePair = ConcurrentHashMap<Long, ConcurrentHashMap<Long, Int>>()
    private val lastLinkReportAtMs = ConcurrentHashMap<Long, Long>()

    // ── PHASE 4: relay suppression instance state ────────────────────────────
    // Bounded exactly like MeshSosManager's/MeshCarrier's own dedupe caches
    // (LinkedHashMap + removeEldestEntry, cap 512, TTL 10min, pruned on
    // access) — a THIRD cache, scoped only to this suppression path; neither
    // of theirs is ever touched here. Logic lives in the pure, directly-
    // tested RelayDedupeCache (see companion object) — this is just an
    // instance of it, @Synchronized internally so no extra lock is needed here.
    private val relayDedupeCache = RelayDedupeCache()
    // Single dedicated background thread — relay deferrals are lightweight
    // (eventually call an existing forward* function), never CPU-bound, so
    // one thread is enough. Deliberately NOT a per-link reader thread (those
    // must stay free to keep reading their socket, see runReadLoop) and NOT
    // mainHandler (UI-only, see that field's doc) — a clearly separate,
    // named, daemon thread so it can never keep the process alive on its own.
    private val relayScheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "RelaySuppression").apply { isDaemon = true } }
    private val relayDeferrals = ConcurrentHashMap<Long, ScheduledFuture<*>>()
    private var pendingProbeToken: Long = 0L
    private var pendingProbeSentAtMs: Long = 0L
    // This device's own last-measured RTT to ITS OWN current uplink — what it
    // reports in its own TYPE_LINK_REPORT. Null until the first PROBE/ACK
    // round trip completes.
    @Volatile private var rttToOwnUplinkMs: Long? = null
    // The GO's real nodeId — captured once, the moment this device's very
    // FIRST connection ever resolves (always a direct dial to the GO; see
    // startAsClient/handleHelloFrame), so a later TYPE_TREE_ASSIGN that
    // reassigns this node straight back to TREE_ROOT_SENTINEL ("the GO") has
    // a real nodeId to dial/compare against without needing a second sentinel
    // meaning. Never set on the GO itself (it has no uplink to record).
    @Volatile private var goNodeId: Long? = null
    private var linkTickerRunnable: Runnable? = null

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
        retryFrame = { header, payload, fromLink -> routeFrame(header, payload, fromLink) },
        // PART A: retries a carried frame that was queued pending an unknown
        // originId pubkey — see MeshSigner.verifyCarried/drainPendingCarried.
        retryCarried = { originId, finalDstId, innerType, inner, carrierId, hopCount ->
            dispatchCarriedInner(originId, finalDstId, innerType, inner, carrierId, hopCount)
        }
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
    // B1 (diagnostic follow-up): durable SOS raised/cleared/acked history —
    // see IncidentLog.kt. Process-wide singleton, same pattern as the others above.
    val incidentLog = IncidentLog.get(context)
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
        isCharging = { currentBatteryPercentAndCharging().second },
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
        incidentLog = incidentLog,
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
        carrier.dispatchInner = { originId, finalDstId, innerType, inner, carrierId, hopCount ->
            dispatchCarriedInner(originId, finalDstId, innerType, inner, carrierId, hopCount)
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
    private fun dispatchCarriedInner(originId: Long, finalDstId: Long, innerType: Byte, inner: ByteArray, carrierId: Long, hopCount: Int) {
        // PART A: a carried SOS is the one carried type with real-world
        // alarm consequences — it must be cryptographically verified against
        // its claimed originId before it can ever reach dispatchLocal/
        // handleSosFrame, unlike every other carried type below (which keeps
        // the exact pre-existing dedupe-then-dispatch behavior). See
        // MeshSigner.verifyCarried's doc for the full A2-A5 rationale.
        if (meshSosManager.isSosFindType(innerType)) {
            when (val result = meshSigner.verifyCarried(originId, finalDstId, innerType, inner, carrierId, hopCount)) {
                is MeshSigner.CarriedVerifyResult.Accepted -> {
                    // Re-applies MeshSosManager's own msgSeq-based dedupe to the
                    // VERIFIED inner payload (trailer already stripped by
                    // verifyCarried) — same "never re-alarm a device that
                    // already saw this SOS" guarantee as every other carried
                    // type below, and the same shape the live path dedupes in
                    // (see routeFrame — always on the trailer-stripped bytes).
                    if (meshSosManager.checkAndRecordDuplicate(originId, innerType, result.innerPayload)) {
                        log("MESH: dropped duplicate carried inner type=$innerType from=${MeshFrame.hex(originId)}")
                        return
                    }
                    val syntheticHeader = MeshFrame.Header(
                        MeshFrame.VERSION, originId, finalDstId, TTL_UNICAST, innerType, result.innerPayload.size
                    )
                    // A3: age comes from the SIGNED wire timestamp, never local
                    // receipt time or the payload's own embedded unixSeconds —
                    // see MeshSosManager.handleSosFrame's carriedVerifiedAgeSec doc.
                    val carriedAgeSec = (System.currentTimeMillis() / 1000L - result.signedTimestampSec).coerceAtLeast(0L)
                    dispatchLocal(
                        syntheticHeader, result.innerPayload, isLive = false,
                        carrierInfo = carrierId to hopCount, carriedAgeSecOverride = carriedAgeSec
                    )
                }
                MeshSigner.CarriedVerifyResult.Reject -> {
                    log("MESH: dropped carried SOS from=${MeshFrame.hex(originId)} reason=verify_failed")
                }
                MeshSigner.CarriedVerifyResult.Queued -> {
                    log("MESH: carried SOS from=${MeshFrame.hex(originId)} queued pending pubkey")
                }
            }
            return
        }
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
        // OFFLINE UI STEP 4: carrierId/hopCount passed through so a
        // PHRASE/CHAT handler can show "carried via X, N hops" — every OTHER
        // existing handler simply ignores the new parameter (default null),
        // unchanged behavior.
        dispatchLocal(syntheticHeader, inner, isLive = false, carrierInfo = carrierId to hopCount)
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
    // OCP PHASE 5.1: the low-layer simulcast encoder — mirrors encoder/
    // encoderInputSurface/encoderRunning exactly, one instance, only ever
    // running alongside the high encoder (never instead of it, never
    // outliving it — see releaseEncoder's fold-in). See shouldRunLowEncoder.
    private var lowEncoder: MediaCodec? = null
    private var lowEncoderInputSurface: Surface? = null
    @Volatile private var lowEncoderRunning = false
    private var lowEncoderDrainThread: Thread? = null
    // FIX: this device's own last-broadcast combined csd (SPS+PPS), captured once
    // drainEncoderLoop's one-shot TYPE_CONFIG actually goes out — replayed to a
    // late-joining peer by sendGroupCallStateTo, since that peer's connection
    // didn't exist yet for the original one-shot broadcast to ever reach it. Reset
    // per call, same lifecycle as decoderReady/pendingCsd below.
    @Volatile private var lastCsdOut: ByteArray? = null

    // PHASE 8 STEP 5: THIS device's own outgoing encoder ladder settings —
    // mutable (unlike the old const vals), rewritten by applyResolutionLadder
    // whenever participant count crosses a tier boundary. Always start a
    // fresh call at the tier-1/default values, same as before this phase.
    @Volatile private var WIDTH = WIDTH_DEFAULT
    @Volatile private var HEIGHT = HEIGHT_DEFAULT
    @Volatile private var FPS = FPS_DEFAULT
    @Volatile private var BITRATE = BITRATE_DEFAULT
    private data class LadderTier(val width: Int, val height: Int, val fps: Int, val bitrate: Int)
    private val LADDER_TIER1 = LadderTier(WIDTH_DEFAULT, HEIGHT_DEFAULT, FPS_DEFAULT, BITRATE_DEFAULT)
    private val LADDER_TIER2 = LadderTier(640, 360, 20, 700_000)
    private val LADDER_TIER3 = LadderTier(320, 240, 15, 300_000)
    @Volatile private var currentLadderTier: LadderTier? = null
    // OCP PHASE 5.4: see applyResolutionLadder's doc.
    @Volatile private var thermalForcedTier: LadderTier? = null

    /** <=4: unchanged tier-1 defaults (byte-identical to pre-Phase-8
     *  behavior — this is what keeps the 2/3-device path exactly as it was).
     *  5-8/9-20 step down resolution+fps+bitrate together. >20 has no tier
     *  of its own here — MAX_LIVE_CAMERAS/MAX_GROUP_PARTICIPANTS already cap
     *  this app well below 20 today (see this function's own doc comment at
     *  its call site for why), so tier-3 values double as the "if a camera
     *  is ever explicitly opted into beyond 20" fallback; ordinary camera
     *  activation is already opt-in-only (see [applyCamState]), so "video
     *  off by default" past this tier is already the structural default,
     *  not something this function needs to separately enforce. */
    private fun ladderTierFor(participantCount: Int): LadderTier = when {
        participantCount <= LADDER_TIER1_MAX -> LADDER_TIER1
        participantCount <= LADDER_TIER2_MAX -> LADDER_TIER2
        else -> LADDER_TIER3
    }

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
    // PART B / B1: MediaCodec is not thread-safe — feedGroupDecoder runs on
    // that srcId's own MediaReadLoop-$idx thread at up to 30fps, while
    // configureGroupDecoder/releaseGroupDecoder for the SAME srcId can fire
    // from the main thread (setGroupTileSurface, retryDegradedGroupPeers,
    // group-call teardown) or from a DIFFERENT read thread mid reconnect
    // churn. One lock object per srcId — never one global lock, which would
    // serialize every participant's decoder against every other's — held by
    // every feed/configure/release for that srcId, NEVER across a socket
    // write (see configureGroupDecoder's keyframe request, deliberately
    // issued after the lock is released).
    private val groupDecoderLocks = ConcurrentHashMap<Long, Any>()
    private fun groupDecoderLock(srcId: Long): Any = groupDecoderLocks.getOrPut(srcId) { Any() }
    private val groupPendingCsd = ConcurrentHashMap<Long, MutableList<ByteArray>>()
    private val groupDecoderReady = ConcurrentHashMap<Long, Boolean>()
    private val groupTileSurfaces = ConcurrentHashMap<Long, Surface>()
    private val groupVideoFrameCountRecv = ConcurrentHashMap<Long, Int>()
    // FIX 1 (3-device black-tile bug): the LAST csd seen for this srcId, kept
    // for as long as they remain in the call — unlike groupPendingCsd (which
    // is only ever populated up to the FIRST successful configure, then
    // discarded, see the TYPE_FRAME dispatch branch below), this survives so
    // a decoder can be REBUILT later (see FIX 2/setGroupTileSurface) without
    // waiting for a fresh TYPE_CONFIG that a steady-state encoder never
    // resends. Written on every TYPE_CONFIG; cleared only when the peer
    // actually leaves the call (removeGroupCallParticipant /
    // handleParticipantsFrame's departure diff) — deliberately NOT cleared by
    // [releaseGroupDecoder], which also runs on an ordinary camera-off (the
    // peer is still IN the call then, and their next camera-on doesn't always
    // re-send csd either).
    private val groupCsdCache = ConcurrentHashMap<Long, ByteArray>()
    // FIX 3: srcIds whose decoder is known-broken (its Surface was destroyed
    // out from under it — see feedGroupDecoder) until a rebuild replaces it.
    // Checked BEFORE ever touching the MediaCodec again, so one real
    // exception produces one log line instead of 32 identical ones at 30fps.
    private val groupDecoderBroken = ConcurrentHashMap.newKeySet<Long>()
    private val groupDecoderDropLogAtMs = ConcurrentHashMap<Long, Long>()

    // PHASE 8 STEP 2: fault isolation — a peer's video decoder is entirely
    // separate state from groupCall.participants, so a decoder failure here
    // can NEVER end the call or touch any other peer; see
    // markGroupPeerDegraded/markGroupPeerRecovered (called from
    // configureGroupDecoder/feedGroupDecoder) and retryDegradedGroupPeers
    // (the 10s/5-attempt automatic recovery). A degraded peer keeps sending
    // and receiving audio/chat/control normally — only their tile decoder is
    // affected.
    private class PeerVideoHealth { @Volatile var attempts: Int = 0; @Volatile var lastRetryAtMs: Long = 0L }
    private val groupPeerHealth = ConcurrentHashMap<Long, PeerVideoHealth>()
    private val groupPeerDegraded = ConcurrentHashMap.newKeySet<Long>()
    private var degradedRetryRunnable: Runnable? = null

    /** Fired on the main thread whenever a group tile's VIDEO health flips —
     *  true = "video unavailable" (show their name, keep audio), false =
     *  recovered. Never fired for a peer leaving the call (that's
     *  onGroupCallParticipants) — this is purely about a still-present
     *  participant's decoder. */
    var onGroupTileDegraded: ((nodeId: Long, degraded: Boolean) -> Unit)? = null

    /** True while [nodeId]'s group tile video is degraded (decoder broken,
     *  automatic recovery in progress or exhausted) — the Activity uses this
     *  (alongside [onGroupTileDegraded]) to decide whether to show the
     *  "video unavailable" overlay instead of a frozen/black surface. */
    fun isGroupPeerDegraded(nodeId: Long): Boolean = nodeId in groupPeerDegraded

    // PHASE 8 STEP 4: video tile decoder budget — runs on EVERY device
    // (GO and client alike), since MediaCodec instance limits are a
    // per-device hardware property, not a GO-specific concept like STEP 3's
    // audio mixing. See probeMaxAvcDecoderInstances/evaluateTileBudget.
    private var tileBudget = TILE_BUDGET_MIN
    // OCP PHASE 0.1: cached from initTileBudget's one-time probe — the CAP
    // line reuses these rather than re-querying MediaCodecList per call.
    private var probedDecoderCount = DECODER_PROBE_FALLBACK
    private var probedEncoderCount = DECODER_PROBE_FALLBACK
    // OCP PHASE 4.3: REMOVED the flat MAX_LIVE_CAMERAS=4 const — derived
    // from the same probe as tileBudget, see [initTileBudget].
    private var maxLiveCameras = MIN_LIVE_CAMERAS
    private val lastSpokeAtMs = ConcurrentHashMap<Long, Long>()
    private val joinSequence = ConcurrentHashMap<Long, Int>()
    // D1: was a plain Int — addGroupCallParticipant's getAndIncrement() runs
    // inside a synchronized(gc.participants) block anyway (see that
    // function's doc), so this AtomicInteger is redundant with that lock for
    // THAT one call site, but keeps the counter itself safe against any
    // future read/reset that doesn't happen to hold the same lock (e.g. the
    // resetTileBudgetState() reset below, which runs on a different thread's
    // call path and never touched this field's atomicity before).
    private val joinSequenceCounter = AtomicInteger(0)
    @Volatile private var pinnedTilePeer: Long? = null
    private val tileSwapAtMs = ConcurrentHashMap<Long, Long>()
    private var lastTileBudgetEvalMs = 0L
    private val tileBudgetExcluded = ConcurrentHashMap.newKeySet<Long>()

    /** Fired on the main thread whenever a peer's tile budget-exclusion
     *  status flips — true means "not currently decoded, show their last
     *  frame frozen with a dimmed overlay (or avatar if none ever
     *  arrived)"; false means they now have a live decoder again. Distinct
     *  from [onGroupTileDegraded] — this is a capacity decision, not a
     *  failure. */
    var onGroupTileBudgetChanged: ((nodeId: Long, excluded: Boolean) -> Unit)? = null

    fun isGroupPeerBudgetExcluded(nodeId: Long): Boolean = nodeId in tileBudgetExcluded

    /** Called by the Activity when the user taps a tile — a pinned peer is
     *  always kept in the decode budget regardless of speaking history. Pass
     *  null to clear (same tap-to-toggle UI convention as onTileTapped). */
    fun setTileBudgetPin(nodeId: Long?) {
        pinnedTilePeer = nodeId
    }

    // PHASE 8 STEP 6/STEP 2: peers whose OUTBOUND queue to them (see
    // PeerLink's three priority lanes) has stayed over its high-water mark
    // for 10s straight — a FORWARDING-side signal, deliberately kept separate
    // from [groupPeerDegraded] (which is about decoding THEIR incoming
    // video): retrying a decoder would do nothing for an outbound queue
    // that's backed up. Shown in the roster as unreachable; clears
    // automatically once their queue drains.
    private val meshPeerUnreachable = ConcurrentHashMap.newKeySet<Long>()

    /** Fired on the main thread whenever a peer's mesh reachability (NOT
     *  their call-participant status — they remain a full roster/call member
     *  throughout) flips due to sustained outbound queue backpressure. */
    var onPeerReachabilityChanged: ((nodeId: Long, unreachable: Boolean) -> Unit)? = null

    fun isPeerUnreachable(nodeId: Long): Boolean = nodeId in meshPeerUnreachable

    private fun handleSustainedBackpressure(link: PeerLink) {
        val srcId = link.nodeId
        if (srcId == MeshFrame.PENDING_ID) return
        if (meshPeerUnreachable.add(srcId)) {
            Log.d("OFFTRACE", "SCALE: peer ${MeshFrame.hex(srcId)} DEGRADED reason=queue_backpressure attempt=1")
            mainHandler.post { onPeerReachabilityChanged?.invoke(srcId, true) }
        }
    }

    private fun handleBackpressureCleared(link: PeerLink) {
        val srcId = link.nodeId
        if (meshPeerUnreachable.remove(srcId)) {
            Log.d("OFFTRACE", "SCALE: peer ${MeshFrame.hex(srcId)} recovered")
            mainHandler.post { onPeerReachabilityChanged?.invoke(srcId, false) }
        }
    }
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
    // STABILITY AUDIT 2.2: MediaCodec is not thread-safe — decodeGroupAudio runs
    // on that srcId's own MediaReadLoop-$idx thread on every inbound TYPE_AUDIO
    // frame, while releaseGroupAudioDecoder for the SAME srcId can fire from the
    // main thread (peer decline/kick, group-call teardown) or from a DIFFERENT
    // read thread mid reconnect churn — the exact same race the video decoders'
    // groupDecoderLocks/groupDecoderLock exist to prevent (see that field's own
    // doc just above the video maps). One lock object per srcId, held by every
    // decode/release for that srcId, mirrored exactly from the video path.
    private val groupAudioDecoderLocks = ConcurrentHashMap<Long, Any>()
    private fun groupAudioDecoderLock(srcId: Long): Any = groupAudioDecoderLocks.getOrPut(srcId) { Any() }
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

    // PART B / B2: set true by the worker thread itself immediately before a
    // blocking NATIVE call (AudioRecord.read / MediaCodec.dequeue*) that
    // Thread.interrupt() cannot unblock, false immediately after that call
    // returns (in a finally, so an exception clears it too). A release
    // function must never call stop()/release() on the underlying codec/
    // AudioRecord while its owning thread's flag is still true — see
    // [waitForCodecFree] and releaseEncoder/releaseLowEncoder/releaseAudio.
    @Volatile private var audioSendInsideRecord = false
    @Volatile private var encoderDrainInsideCodec = false
    @Volatile private var lowEncoderDrainInsideCodec = false
    @Volatile private var opusEncodeInsideCodec = false
    @Volatile private var opusDecodeInsideCodec = false

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
     *  see decoderReady already stuck false with nothing left to configure from.
     *
     *  FIX 2 (3-device black-tile bug): a SECOND case — [nodeId] already has a
     *  live entry in [groupDecoders], meaning this is a Surface arriving for a
     *  peer that was ALREADY being decoded (their old Surface was just
     *  destroyed, e.g. by a grid reshape — see rebuildGroupCallGrid's FIX 4,
     *  or by the Activity/window being recreated). A MediaCodec's output
     *  Surface is fixed for that codec's lifetime — it can never be
     *  redirected to a new Surface — so the old decoder is released and a
     *  fresh one configured against the new Surface, using [groupCsdCache]
     *  (FIX 1) since groupPendingCsd was already consumed at first configure
     *  and a steady-state encoder never resends csd on its own. */
    fun setGroupTileSurface(nodeId: Long, surface: Surface?) {
        if (surface == null) {
            groupTileSurfaces.remove(nodeId)
            return
        }
        // D2: a late surfaceCreated can arrive AFTER [nodeId] has already
        // left the call (Surface creation is asynchronous view-layout work,
        // racing an ordinary departure) — without this guard, that stale
        // callback would go on to configure a fresh decoder for someone no
        // longer in [groupCall.participants], leaking a MediaCodec that
        // nothing will ever release (departure's own cleanup already ran and
        // won't run again for them).
        val gc = groupCall
        if (gc == null || nodeId !in gc.participants) {
            groupTileSurfaces.remove(nodeId)
            return
        }
        groupTileSurfaces[nodeId] = surface
        if (groupDecoders.containsKey(nodeId)) {
            val csd = groupCsdCache[nodeId]
            if (csd == null) {
                logW("OFFTRACE: MEDIA: surface changed for ${MeshFrame.hex(nodeId)} but no cached csd — cannot rebuild decoder")
                return
            }
            releaseGroupDecoder(nodeId)
            configureGroupDecoder(nodeId, csd, requestKeyframeAfter = true)
            if (groupDecoders.containsKey(nodeId)) {
                groupDecoderReady[nodeId] = true
                Log.d("OFFTRACE", "MEDIA: decoder rebuilt for ${MeshFrame.hex(nodeId)} on new surface — keyframe requested")
            }
            return
        }
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

    private var nextPhraseSeq = java.util.concurrent.atomic.AtomicLong(0L)

    /** OFFLINE UI STEP 4: mesh-wide, always available (no active call needed)
     *  — same live+carry dual delivery as [sendGroupChat] (see
     *  enqueueChatSend's identical pattern), deliberately NOT reusing
     *  TYPE_CHAT (see TYPE_PHRASE's wire doc: separate types, never merged).
     *  Returns the msgId used for carrier tracking (heard-by/hop-count
     *  lookups via MeshCarrier.deliveredCountFor/hopCountFor), or null if
     *  nothing was sent. */
    /** [targetNodeId] null (default) broadcasts mesh-wide — every current
     *  member gets it live, and it's queued for carry to anyone who
     *  reconnects later (see enqueueChatSend's identical pattern). A real
     *  nodeId sends a genuine 1:1 unicast instead (dst=targetNodeId,
     *  eligible for store-and-forward carry to exactly that recipient, never
     *  broadcast to anyone else) — used by the long-press "send to that peer
     *  only" gesture. */
    fun sendPhrase(phrase: PhraseCode, targetNodeId: Long? = null): String? {
        if (!running.get() || !alive.get()) {
            logW("MEDIA: phrase send dropped — not connected")
            return null
        }
        val handler = chatHandler ?: return null
        val seq = nextPhraseSeq.getAndIncrement()
        val payload = PhraseCode.encode(phrase.code, seq)
        val msgId = MeshCarrier.newMsgId()
        val dst = targetNodeId ?: MeshFrame.BROADCAST_ID
        handler.post {
            writeFrame(dst, TYPE_PHRASE, payload)
            Log.d("OFFTRACE", "PHRASE: send code=${phrase.code} seq=$seq")
            if (dst == MeshFrame.BROADCAST_ID) {
                val alreadyPresent = routingTable.roster().map { it.nodeId }.toSet()
                carrier.put(
                    msgId, localNodeId, MeshFrame.BROADCAST_ID, TYPE_PHRASE, payload,
                    expiryMins = 24 * 60,
                    alreadyDeliveredTo = alreadyPresent
                )
            } else {
                val alreadyDelivered = if (routingTable.get(dst) != null) setOf(dst) else emptySet()
                carrier.put(
                    msgId, localNodeId, dst, TYPE_PHRASE, payload,
                    expiryMins = 24 * 60,
                    alreadyDeliveredTo = alreadyDelivered
                )
            }
        }
        return msgId
    }

    /** OFFLINE UI STEP 4: [code] outside [PhraseCode]'s table (including the
     *  reserved 7, or a genuinely malformed/future value) still fires
     *  [onPhraseReceived] with a null PhraseCode — the UI renders "unknown
     *  message" (never drops the notification) — and, critically, this
     *  function never returns early or throws for an unrecognized code, so
     *  routeFrame's forwarding (which already ran before dispatchLocal ever
     *  reaches here — see routeFrame's broadcast branch) is completely
     *  unaffected: an unknown phrase is relayed exactly like a known one. */
    private fun handlePhraseFrame(header: MeshFrame.Header, payload: ByteArray, carrierInfo: Pair<Long, Int>?) {
        val (code, seq) = PhraseCode.decode(payload) ?: run {
            logW("MEDIA: malformed PHRASE len=${payload.size} — ignoring")
            return
        }
        val (carrierId, hopCount) = carrierInfo ?: (null to null)
        val ageS = 0L // live or just-delivered — see class doc, no separate receive-side age concept for phrases
        Log.d(
            "OFFTRACE",
            "PHRASE: recv from=${MeshFrame.hex(header.srcId)} code=$code hops=${hopCount ?: 0} age=${ageS}s"
        )
        mainHandler.post { onPhraseReceived?.invoke(header.srcId, code, seq, carrierId, hopCount) }
    }

    /** [carrierId]/[hopCount] non-null only when this arrived via
     *  store-and-forward (see dispatchCarriedInner) — null for a live
     *  broadcast, where "carrier" isn't a meaningful concept (the sender IS
     *  the carrier). [code] outside PhraseCode's table renders "unknown
     *  message" — see handlePhraseFrame's doc. */
    var onPhraseReceived: ((fromNodeId: Long, code: Int, seq: Long, carrierId: Long?, hopCount: Int?) -> Unit)? = null

    // ── B5 (diagnostic follow-up): push-to-talk voice notes ─────────────────

    /** One received voice note — [audioBytes] is the raw AAC/MPEG_4 file
     *  content exactly as recorded (see VoiceNoteRecorder), ready to hand
     *  straight to a MediaPlayer via a temp file or MediaDataSource. No
     *  msgId here (unlike MeshCarrier's own Queued envelope) — a LIVE
     *  delivery never carries one (see TYPE_VOICE_NOTE's wire doc: the
     *  payload deliberately doesn't embed it, matching TYPE_PHRASE's
     *  leaner shape), so this class doesn't pretend to have one either. */
    data class VoiceNote(
        val srcId: Long,
        val durationMs: Int,
        val audioBytes: ByteArray,
        val receivedAtMs: Long,
        val carrierId: Long?,
        val hopCount: Int?
    )

    // Bounded so a long session's worth of received voice notes can't grow
    // this in-memory list (and its raw audio bytes) unboundedly — oldest
    // dropped first, same shape as MeshSosManager's dedupe cache eviction.
    private val MAX_VOICE_NOTES_RETAINED = 30
    private val voiceNotesInternal = mutableListOf<VoiceNote>()
    private val voiceNotesLock = Any()

    /** Snapshot of every voice note received this session, oldest first —
     *  read fresh by the inbox UI when it opens, same "pull current state,
     *  don't require having been subscribed since session start" contract
     *  as [sosEntries]. */
    val voiceNotes: List<VoiceNote> get() = synchronized(voiceNotesLock) { voiceNotesInternal.toList() }

    /** Fired on the main thread whenever a new voice note is received
     *  (live or carried — [VoiceNote.carrierId] tells which). */
    var onVoiceNoteReceived: ((VoiceNote) -> Unit)? = null

    /** Records-and-sends entry point is [VoiceNoteRecorder] (mic capture);
     *  this is the send-over-the-mesh half, called once recording stops
     *  with the finished file's bytes. Broadcast only (no 1:1 targeting,
     *  matching "push-to-talk voice-note BROADCAST" — unlike [sendPhrase],
     *  which supports both). Exactly [sendPhrase]'s existing broadcast
     *  pattern: live now, carried for anyone who reconnects later, current
     *  roster pre-marked as already-delivered so they're never double-sent. */
    fun sendVoiceNote(audioBytes: ByteArray, durationMs: Int) {
        if (!running.get() || !alive.get()) {
            logW("MEDIA: voice note send dropped — not connected")
            return
        }
        val handler = chatHandler ?: return
        val payload = encodeVoiceNotePayload(VOICE_NOTE_CODEC_AAC_MP4, durationMs, audioBytes)
        val msgId = MeshCarrier.newMsgId()
        handler.post {
            writeFrame(MeshFrame.BROADCAST_ID, TYPE_VOICE_NOTE, payload)
            Log.d("OFFTRACE", "VOICENOTE: send durationMs=$durationMs bytes=${audioBytes.size}")
            val alreadyPresent = routingTable.roster().map { it.nodeId }.toSet()
            carrier.put(
                msgId, localNodeId, MeshFrame.BROADCAST_ID, TYPE_VOICE_NOTE, payload,
                expiryMins = VOICE_NOTE_CARRY_EXPIRY_MINS,
                alreadyDeliveredTo = alreadyPresent
            )
        }
    }

    /** [carrierInfo] non-null only for a store-and-forward delivery — same
     *  contract as [handlePhraseFrame]'s identical parameter. */
    private fun handleVoiceNoteFrame(header: MeshFrame.Header, payload: ByteArray, carrierInfo: Pair<Long, Int>?) {
        val decoded = decodeVoiceNotePayload(payload) ?: run {
            logW("MEDIA: malformed VOICE_NOTE len=${payload.size} — ignoring")
            return
        }
        if (decoded.codecId != VOICE_NOTE_CODEC_AAC_MP4) {
            logW("MEDIA: VOICE_NOTE unknown codecId=${decoded.codecId} — ignoring (future codec this build doesn't understand)")
            return
        }
        val durationMs = decoded.durationMs
        val audioBytes = decoded.audioBytes
        val (carrierId, hopCount) = carrierInfo ?: (null to null)
        Log.d(
            "OFFTRACE",
            "VOICENOTE: recv from=${MeshFrame.hex(header.srcId)} durationMs=$durationMs bytes=${audioBytes.size} " +
                "hops=${hopCount ?: 0}"
        )
        val note = VoiceNote(header.srcId, durationMs, audioBytes, System.currentTimeMillis(), carrierId, hopCount)
        synchronized(voiceNotesLock) {
            voiceNotesInternal.add(note)
            while (voiceNotesInternal.size > MAX_VOICE_NOTES_RETAINED) voiceNotesInternal.removeAt(0)
        }
        mainHandler.post { onVoiceNoteReceived?.invoke(note) }
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
    // PHASE 8 TRACK C3: was routingTable.all().firstOrNull()?.nodeId — correct
    // ONLY while routingTable is guaranteed to hold at most one entry (the
    // pre-tree flat topology). The instant a relay node also gains children,
    // that map holds both the parent AND every child with no ordering
    // guarantee, making firstOrNull() silently wrong. parentNodeId is
    // maintained explicitly (see handleHelloFrame) specifically so this stays
    // correct at any tree depth; on a plain client (today's only shape) it is
    // set to exactly the same single link firstOrNull() used to find.
    private fun uplinkNodeId(): Long? = if (isGroupOwner) null else parentNodeId

    /** True once this node relays for anyone besides its own uplink — i.e. it
     *  has at least one direct neighbor in routingTable that ISN'T
     *  parentNodeId. On the GO, parentNodeId is always null, so this is just
     *  "does the GO have any members at all" — identical in spirit to
     *  routingTable.all().isNotEmpty(), matching today's always-true-once-
     *  joined GO behavior exactly. On every non-relay node (the entire
     *  <=3-device path, and any node the tree never assigned children to),
     *  this is false, making every "isGroupOwner || hasChildren()" gate
     *  below reduce to exactly "isGroupOwner" — today's exact condition. */
    private fun hasChildren(): Boolean = routingTable.all().any { it.nodeId != parentNodeId }

    /** OFFLINE UI STEP 2 (cut vertex): true when removing [nodeId] would
     *  disconnect at least two OTHER members from each other — the existing
     *  relay-tree data (see TRACK C3's treeParentOf, mirrored to every node
     *  and populated by maybeRebuildTree/computeTree on EVERY join regardless
     *  of mesh size — even a flat 2/3-device mesh gets a full
     *  everyone-maps-to-the-GO entry) is exactly a topology graph already, so
     *  this reads it rather than computing a fresh articulation-point search.
     *  A relay node (assigned as someone's parent in treeParentOf) is always
     *  a cut vertex for its own subtree. The GO is a cut vertex once there
     *  are at least two OTHER members (treeParentOf.size counts exactly
     *  that, mesh-wide, on every node) — any fewer and there's nothing left
     *  for its removal to disconnect FROM each other. */
    fun isArticulationPoint(nodeId: Long): Boolean {
        val isGo = if (nodeId == localNodeId) isGroupOwner else (goNodeId == nodeId)
        if (isGo) return treeParentOf.size >= 2
        return treeParentOf.values.contains(nodeId)
    }

    /** Signal Deck (diagnostic follow-up): true iff [nodeId] is reached over
     *  ONE hop from this device — either [nodeId] is MY direct child in the
     *  relay tree, or [nodeId] is my own parent (the node I dial directly).
     *  Everyone else is reached via a relay (treeParentOf, mirrored to
     *  every node and populated on every join regardless of mesh size —
     *  see isArticulationPoint's identical doc — already IS a topology
     *  graph; this reads it rather than tracking a separate "direct link"
     *  flag per peer). On a flat/default mesh (everyone maps straight to
     *  the GO) this is simply "am I the GO, or is nodeId the GO" — the
     *  general form subsumes that case without a separate branch. */
    fun isDirectlyConnected(nodeId: Long): Boolean =
        treeParentOf[nodeId] == localNodeId || treeParentOf[localNodeId] == nodeId

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
            addGroupCallParticipant(localNodeId, rejectDst = null)
        } else {
            // Not authoritative — just this device's own local view that it's "in"
            // the call it just proposed. The GO's own TYPE_PARTICIPANTS (once it
            // processes our TYPE_CALL_INVITE below, see handleCallInviteFrame) is the
            // real source of truth and will overwrite this via handleParticipantsFrame.
            groupCall?.participants?.add(localNodeId)
        }
        // PHASE 8 TRACK C4: unconditional — startGroupCallMixer() is its own
        // isGroupOwner-or-hasChildren guard, so this single call site covers
        // the GO, a relay node that already has children when the call
        // starts, and (safely, as a no-op) every plain leaf.
        startGroupCallMixer()
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
            addGroupCallParticipant(localNodeId, rejectDst = null)
        } else {
            val go = uplinkNodeId()
            if (go != null) writeFrame(go, TYPE_CALL_ACCEPT, encodeCallId(callId))
        }
        startGroupCallMixer() // PHASE 8 TRACK C4: unconditional, see startGroupCall's identical comment
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
    // D1: outcome of the atomic admit decision below — ADMITTED/ALREADY_IN
    // both return true from addGroupCallParticipant, but only ADMITTED needs
    // evaluateGroupCallAfterChange(); FULL needs the reject path. Kept as a
    // tiny local enum rather than reusing Boolean so the three cases can't
    // be confused with each other at the call site.
    private enum class ParticipantAdmitResult { ADMITTED, ALREADY_IN, FULL }

    private fun addGroupCallParticipant(nodeId: Long, rejectDst: Long?): Boolean {
        val gc = groupCall ?: return false
        // D1: contains-check, size-check, add, AND the join-ordinal
        // assignment must all happen as ONE atomic operation — this runs on
        // a per-peer read thread, and two peers joining at the SAME instant
        // (size == MAX_GROUP_PARTICIPANTS - 1) could otherwise both pass the
        // size check before either call's add() runs, overrunning the cap by
        // however many joins raced. synchronized(gc.participants) uses the
        // exact intrinsic lock Collections.synchronizedSet already
        // serializes its own individual add()/size()/contains() calls on
        // (see GroupCallState.participants) — this closes the gap BETWEEN
        // those calls rather than adding a second, independent lock that
        // could itself be acquired out of order against the set's own.
        // joinSequenceCounter is an AtomicInteger for the same reason: a
        // plain `Int++` read-modify-write race here could hand two
        // concurrent joiners the identical ordinal.
        val outcome = synchronized(gc.participants) {
            when {
                nodeId in gc.participants -> ParticipantAdmitResult.ALREADY_IN
                gc.participants.size >= MAX_GROUP_PARTICIPANTS -> ParticipantAdmitResult.FULL
                else -> {
                    gc.participants.add(nodeId)
                    // PHASE 8 STEP 4: stable join-order ordinal for the tile budget's
                    // last tie-break — putIfAbsent so a re-join (e.g. after a transient
                    // reconnect) doesn't reset someone to the back of the queue.
                    joinSequence.putIfAbsent(nodeId, joinSequenceCounter.getAndIncrement())
                    ParticipantAdmitResult.ADMITTED
                }
            }
        }
        return when (outcome) {
            ParticipantAdmitResult.ALREADY_IN -> true
            ParticipantAdmitResult.FULL -> {
                // PHASE 8 STEP 7: current/max travel WITH the reason byte now — see
                // handleBusyFrame's decode — so the rejected device can show
                // "Group is full - N of M connected" instead of a bare notice.
                // Platform-honesty note: Android's WifiP2pManager API exposes no
                // official "max GO clients" query, so MAX_GROUP_PARTICIPANTS stays
                // a configured ceiling (the same one this app enforces for mesh-
                // level admission — see handleNewConnection) rather than something
                // actually probed from the OS/driver.
                val current = gc.participants.size
                logW("MESH: group call full ($current/$MAX_GROUP_PARTICIPANTS) — rejecting join from ${MeshFrame.hex(nodeId)}")
                Log.d("OFFTRACE", "ADMIT: rejected ${MeshFrame.hex(nodeId)} reason=full $current/$MAX_GROUP_PARTICIPANTS")
                // B1-style discipline: never hold the participants lock across
                // a socket write — this runs after the synchronized block above
                // has already exited.
                if (rejectDst != null) {
                    writeFrame(
                        rejectDst, TYPE_BUSY,
                        byteArrayOf(BUSY_REASON_CALL_FULL, current.coerceIn(0, 255).toByte(), MAX_GROUP_PARTICIPANTS.toByte())
                    )
                }
                false
            }
            ParticipantAdmitResult.ADMITTED -> {
                evaluateGroupCallAfterChange()
                true
            }
        }
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
        // FIX 1: genuinely gone — the persistent csd cache is only worth
        // keeping while they might still come back with the same encoder
        // state; see the field's own doc.
        groupCsdCache.remove(nodeId)
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
        // PHASE 8 STEP 5: the GO's own encoder adapts to its OWN count the
        // same way every client adapts to theirs (see handleParticipantsFrame)
        // — both read the SAME gc.participants.size, so every device always
        // agrees on the tier.
        applyResolutionLadder(gc.participants.size)
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
        return gc.camStates[nodeId] == true || gc.camStates.count { it.value } < maxLiveCameras
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
        stopGoMixTicker()
        resetTileBudgetState()
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

    // ── PHASE 8 TRACK B5: crash guard for every media/mesh thread ──────────────

    /** Applied to EVERY thread this transport creates (read loops, server
     *  accept, encoder/decoder/camera/audio-send/audio-decode, GO-mix,
     *  ladder-reconfig — every call site below). Logs the full stack trace
     *  to OFFTRACE the moment anything on that thread throws uncaught, then
     *  lets the thread die WITHOUT propagating further — no rethrow, no
     *  System.exit(). Per-thread UncaughtExceptionHandlers pre-empt
     *  whatever default handler Android would otherwise use (which
     *  terminates the whole process even for a background thread), so a bug
     *  on any ONE media thread can now only ever end that ONE thread. B1-B4's
     *  per-peer state isolation (groupPeerHealth/groupDecoders/etc, already
     *  keyed by srcId) is what keeps the CALL itself alive after that; this
     *  is the last line of defense underneath all of it, for whatever
     *  exception type isn't already caught by a more specific try/catch. */
    private fun Thread.guarded(): Thread {
        setUncaughtExceptionHandler { t, e ->
            Log.e("OFFTRACE", "CRASH-GUARD: ${t.name} caught ${e.javaClass.simpleName}: ${e.message} - ${Log.getStackTraceString(e)}")
        }
        return this
    }

    /** D4: same crash-guard as [guarded], but for a per-peer MediaReadLoop-
     *  $idx thread specifically. A bare [guarded] here would just log and
     *  let the thread die — this peer's frames would simply stop arriving,
     *  which looks EXACTLY like a genuinely quiet peer, not the broken link
     *  it actually is; nothing marks them faulted, nothing tells the UI,
     *  nothing tries to reconnect. Reuses [handlePeerDisconnected] — the
     *  SAME cleanup+reconnect+UI-message path an ordinary IOException
     *  already takes inside [runReadLoop]'s own try/catch — so a read loop
     *  dying from whatever residual exception type isn't already caught
     *  there (an Error, or anything escaping the try block itself) surfaces
     *  exactly the same way a normal socket death does, instead of silently.
     *  Safe to call from the dying thread itself: [handlePeerDisconnected]
     *  is already called directly from [runReadLoop]'s own catch blocks on
     *  this exact thread today, and is idempotent (guarded by
     *  [disconnectedLinks]) if it somehow ran twice for the same link. */
    private fun Thread.guardedReadLoop(link: PeerLink): Thread {
        setUncaughtExceptionHandler { t, e ->
            Log.e("OFFTRACE", "CRASH-GUARD: ${t.name} caught ${e.javaClass.simpleName}: ${e.message} - ${Log.getStackTraceString(e)}")
            handlePeerDisconnected(link, "connection lost (${e.javaClass.simpleName})")
        }
        return this
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        // FIX 3: registers this as the process's current transport — see
        // stopOrphanedInstance(), which the caller (OfflineCallActivity) is expected
        // to invoke before ever constructing a new instance.
        activeInstance = this
        val ht = HandlerThread("MediaChatWrite").also { it.guarded().start() }
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
        // PHASE 8 STEP 2: automatic decoder-recovery ticker — session-scoped,
        // same start/stop pairing as every other periodic mechanism here.
        scheduleGroupPeerRetries()
        // PHASE 8 STEP 4: this device's own decoder-instance ceiling —
        // probed once per session, not per call (a device's hardware limit
        // doesn't change mid-session).
        initTileBudget()
        if (isGroupOwner) startAsServer() else startAsClient()
        // PHASE 8 TRACK C3: RTT/RSSI reporting — a no-op on the GO (see
        // sendLinkReport/sendLinkProbe's own isGroupOwner guards); harmless
        // to always start, session-scoped like every other ticker above.
        startLinkTickers()
        // OCP PHASE 5.4: session-scoped, same start/stop pairing as every
        // other mechanism above — matched by unregisterThermalListener() in
        // stop().
        registerThermalListener()
    }

    /** Idempotent full teardown — the whole mesh session, not just the current call.
     *  FIX (Phase 2, still true): mic loop → its encode consumer → sockets (which
     *  unblocks every read loop's blocked read) → decode consumer → AudioTrack. */
    fun stop() {
        running.set(false)
        // PHASE 4 TEARDOWN: Activity-destroy path — see
        // cancelAllPendingRelayDeferrals's doc. relayScheduler itself is
        // also shut down here (not just its pending tasks cancelled) since
        // this whole transport instance is going away.
        cancelAllPendingRelayDeferrals()
        relayScheduler.shutdownNow()
        // FIX 3: only clear the singleton pointer if it's still pointing at THIS
        // instance — an older instance's own stop() (e.g. via stopOrphanedInstance())
        // must never null out a newer instance's registration.
        if (activeInstance === this) activeInstance = null
        // FIX 1: flip false, then join every call-scoped media thread, BEFORE
        // releasing any of the resources they touch — see stopCallThreads().
        callActive.set(false)
        stopGroupPeerRetries()
        groupPeerDegraded.clear()
        groupPeerHealth.clear()
        try { audioRecord?.stop() } catch (_: Exception) {}
        unregisterThermalListener()
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
        stopGoMixTicker()
        stopLinkTickers()
        relayServerSocket?.let { try { it.close() } catch (_: Exception) {} }
        relayServerSocket = null
        parentNodeId = null
        pendingParentLink = null
        assignedParentId = TREE_ROOT_SENTINEL
        treeParentOf.clear()
        treeAssignedAddress.clear()
        nodeDialAddress.clear()
        rttToUplinkByNode.clear()
        bleRssiByNodePair.clear()
        lastLinkReportAtMs.clear()
        goNodeId = null
        rttToOwnUplinkMs = null
        resetTileBudgetState()
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

    /** PHASE 8 TRACK A5: GO-only — explicitly drops a peer the local user just
     *  Declined (see OfflineCallActivity.maybeShowIncomingInviteDialogs). Not a
     *  new teardown path: reuses [handlePeerDisconnected]'s exact cleanup
     *  (routingTable removal, group-call participant removal, roster
     *  rebroadcast) with retryable=false so the GO side never tries to
     *  reconnect someone that was just deliberately removed. A no-op if
     *  [nodeId] isn't currently a connected peer (already gone some other way). */
    fun declineIncomingPeer(nodeId: Long) {
        if (!isGroupOwner) return
        val link = routingTable.get(nodeId) ?: return
        handlePeerDisconnected(link, "declined by group owner", retryable = false)
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
        // PHASE 4 TEARDOWN: link-teardown path — see
        // cancelAllPendingRelayDeferrals's doc for why this cancels every
        // pending deferral, not just ones addressed to this link.
        cancelAllPendingRelayDeferrals()
        // PHASE 8 STEP 6: a genuine disconnect (not sustained backpressure)
        // supersedes any "unreachable" marking — they're actually gone now,
        // not just slow; stale state here would wrongly survive a reconnect
        // under the same (persisted) nodeId.
        meshPeerUnreachable.remove(hadId)
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
            if (isGroupOwner) maybeRebuildTree("leave")
        }
        // PHASE 8 TRACK C3: only losing THE PARENT means "my uplink to the
        // mesh is gone" — on a plain client (today's only shape, and every
        // 2/3-device call forever) hadId is always parentNodeId anyway, since
        // routingTable holds exactly one entry, so this condition is a no-op
        // there. It only starts to matter once a relay node also has
        // children: losing a CHILD must never trigger reconnecting to the GO
        // or tearing down this device's own session — the child (or the GO's
        // next tree rebuild) is responsible for that side, not this node.
        if (!isGroupOwner && hadId == parentNodeId) {
            // Clear the now-dead id BEFORE reconnecting — attemptClientReconnect's
            // success path re-resolves via handleHelloFrame, whose
            // "parentNodeId == null" branch is what lets a plain (non-tree)
            // reconnect claim the new link; leaving the stale id in place
            // would silently block that and leave uplinkNodeId()/
            // writeRawFrame pointing at a closed socket forever.
            parentNodeId = null
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
                    handleNewConnection(s, outbound = true)
                    return@Thread
                } catch (e: Exception) {
                    logW("MESH: reconnect attempt $attempt failed: ${e.message}")
                }
            }
            if (running.get() && alive.get()) {
                handleMeshSessionLost("GO connection lost after $RECONNECT_RETRIES reconnect attempts", uiMessage)
            }
        }, "MediaReconnect").guarded().start()
    }

    // ── Wire framing ──────────────────────────────────────────────────────────

    /** Builds the v3 envelope and hands it to the right [PeerLink](s). PHASE 8
     *  TRACK C3: routing is now [nextHopFor] uniformly for GO and non-GO
     *  alike — see that function's doc for why this is behavior-IDENTICAL to
     *  the old GO-only-branches-differently code on both the GO and a plain
     *  client (routingTable.get(dstId) is always tried first and is the only
     *  branch either of those two shapes can ever actually take), and only
     *  starts choosing a different path once an actual relay node exists. */
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
        // OCP PHASE 3.1/3.2: this device's OWN media origination — capture
        // the timestamp exactly here, as close to "just produced" as this
        // pipeline gets (mic-read/encode -> writeFrame with nothing queued
        // in between on this path — queueing happens downstream, in
        // PeerLink's own send queues, which is exactly the latency this
        // timestamp exists to measure). "One encoder, one encode" — [data]
        // is the SAME already-encoded bytes handed to every recipient
        // either way; only the wrapper differs, chosen per-link below.
        val captureMicros: Long? = when (type) {
            TYPE_FRAME, TYPE_AUDIO -> System.nanoTime() / 1000L
            else -> null
        }
        val tsType = when (type) { TYPE_FRAME -> TYPE_FRAME_TS; TYPE_AUDIO -> TYPE_AUDIO_TS; else -> type }
        val legacyFrame = MeshFrame.encode(srcId, dst, ttl, type, signedData)
        val tsFrame = captureMicros?.let {
            val tsPayload = ByteBuffer.allocate(8 + signedData.size).putLong(it).put(signedData).array()
            MeshFrame.encode(srcId, dst, ttl, tsType, tsPayload)
        }
        fun frameFor(link: PeerLink) = if (tsFrame != null && link.supportsFrameAge) tsFrame else legacyFrame
        if (dst == MeshFrame.BROADCAST_ID) {
            // PHASE 8 TRACK C3: flood every direct neighbor — parent AND
            // children are both plain routingTable entries (see hasChildren()'s
            // doc), so this needs no up/down distinction at all. On a plain
            // client (routingTable = {my one uplink}) this sends to exactly
            // that one link, identical to before this track.
            //
            // OCP PHASE 5.1/G6: TYPE_CONFIG_LOW/TYPE_FRAME_LOW are BRAND NEW
            // types with no legacy fallback (unlike TYPE_FRAME_TS/TYPE_AUDIO_TS,
            // which degrade to the always-understood legacy pair) — a link
            // that never advertised CAP_SIMULCAST must receive NEITHER at
            // all, never merely "a wrapper it doesn't understand." Every
            // other type (including TYPE_CONFIG/TYPE_FRAME themselves) is
            // completely unaffected by this gate.
            routingTable.all().forEach { link ->
                if ((type == TYPE_CONFIG_LOW || type == TYPE_FRAME_LOW) && !link.supportsSimulcast) return@forEach
                link.enqueue(frameFor(link))
            }
            return
        }
        val target = nextHopFor(dst)
        if (target == null) {
            logW("MEDIA: writeRawFrame dst=${MeshFrame.hex(dst)} unknown — dropped type=$type")
            return
        }
        target.enqueue(frameFor(target))
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
                runAcceptLoop(srv)
            } catch (e: BindException) {
                // FIX 3c: this must never fail silently — it's the exact symptom of
                // an orphaned transport instance (see stopOrphanedInstance()) still
                // holding the port from a previous, undestroyed Activity session.
                logE("OFFTRACE: MEDIA: PORT 8889 ALREADY BOUND — orphaned transport alive")
                if (running.get()) reportError("Port 8889 already in use — a previous session may still be running")
            } catch (e: Exception) {
                if (running.get()) reportError("server socket: ${e.message}")
            }
        }, "MediaServerAccept").guarded().start()
    }

    /** PHASE 8 TRACK C3: factored out of startAsServer so a relay node's
     *  lazily-bound child-accept socket (see ensureRelayServerStarted) runs
     *  the exact same accept loop the GO always has — every accepted
     *  connection is, by construction, someone's CHILD (an inbound/accepted
     *  socket), see handleNewConnection's outbound=false. */
    private fun runAcceptLoop(srv: ServerSocket) {
        while (running.get()) {
            val client = try {
                srv.accept()
            } catch (e: IOException) {
                if (running.get()) logW("MEDIA: accept failed: ${e.message}")
                break
            }
            if (!running.get()) { client.close(); break }
            handleNewConnection(client, outbound = false)
        }
    }

    private fun startAsClient() {
        Thread({
            val addr = groupOwnerAddress ?: run { reportError("no group owner address"); return@Thread }
            var attempt = 0
            while (running.get() && attempt < CONNECT_RETRIES) {
                try {
                    log("MEDIA: connecting to $addr:$MEDIA_PORT (attempt ${attempt + 1})")
                    val s = Socket(addr, MEDIA_PORT)
                    handleNewConnection(s, outbound = true)
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
        }, "MediaClientConnect").guarded().start()
    }

    /** PART 2.4: the ONE new entry point local-wifi (or any future transport
     *  that produces raw already-connected sockets instead of a single
     *  group-owner address) needs — a thin public wrapper over the existing,
     *  UNCHANGED [handleNewConnection]. This is deliberately the full extent
     *  of the change: PeerLink, MeshFrame, the read loops and the writer
     *  threads (see [handleNewConnection]'s own doc) are exactly as they
     *  were before Part 2. A transport that already has a connected [socket]
     *  (LocalWifiTransport.invite()'s dial, or its own accept loop) calls
     *  this instead of duplicating any of [handleNewConnection]'s
     *  PeerLink/HELLO/read-loop setup. Safe to call from any thread — same
     *  contract [handleNewConnection] already has (its existing callers are
     *  MediaServerAccept/MediaClientConnect, neither the main thread). */
    fun adoptPeerSocket(socket: Socket, outbound: Boolean) {
        handleNewConnection(socket, outbound)
    }

    /** Called once a socket is connected, on whichever thread did the connecting (GO
     *  accept loop, or client connect). Wraps it in a PeerLink (identity unresolved
     *  until its HELLO arrives), starts that link's writer + its own read-loop
     *  thread, and sends our own HELLO directly on it (bypassing the routing table —
     *  we don't know the peer's node id yet, so there's nothing to look up).
     *  PHASE 8 TRACK C3: [outbound] records which side initiated this socket —
     *  true for every call site that dialed OUT (startAsClient,
     *  attemptClientReconnect, reassignParentIfNeeded's make-before-break
     *  dial), false for startAsServer's/ensureRelayServerStarted's accept()
     *  loops — see handleHelloFrame for how this decides parentNodeId vs an
     *  ordinary routingTable child entry once the link resolves.
     *  [markAsPendingParent] is set ONLY by the make-before-break reassignment
     *  dial (dialNewParent) — assigned synchronously, before the writer/
     *  reader threads below ever start, so there is no window where the new
     *  link's own read-loop thread could process its HELLO reply before
     *  pendingParentLink is visible to handleHelloFrame's check. */
    private fun handleNewConnection(s: Socket, outbound: Boolean, markAsPendingParent: Boolean = false) {
        try {
            s.tcpNoDelay = true
            s.keepAlive = true
            s.soTimeout = SOCKET_READ_TIMEOUT_MS
        } catch (e: Exception) {
            logW("MEDIA: could not set socket options: ${e.message}")
        }
        val link = PeerLink(MeshFrame.PENDING_ID, "", DataOutputStream(s.getOutputStream()), DataInputStream(s.getInputStream()))
        link.remoteAddress = s.inetAddress
        link.isOutbound = outbound
        if (markAsPendingParent) pendingParentLink = link
        link.onDead = { deadLink -> handlePeerDisconnected(deadLink) }
        // PHASE 8 STEP 6/STEP 2: sustained outbound-queue backpressure is the
        // practical equivalent of "this peer's socket write keeps failing" —
        // marks them unreachable (roster-visible) without touching the
        // proven onDead/reconnect path above, which stays reserved for an
        // ACTUAL broken socket.
        link.onSustainedBackpressure = { peerLink -> handleSustainedBackpressure(peerLink) }
        link.onBackpressureCleared = { peerLink -> handleBackpressureCleared(peerLink) }
        pendingLinks.add(link)
        link.startWriter()
        val idx = linkCounter.incrementAndGet()
        log("MEDIA: socket connected — starting read loop #$idx")
        // D4: guardedReadLoop, not the generic guarded() — see its doc.
        Thread({ runReadLoop(link) }, "MediaReadLoop-$idx").guardedReadLoop(link).start()
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
        // OCP PHASE 3/G6: one additive capability byte appended after the
        // pre-existing name field — see MeshSigner.decodeHelloInner's doc
        // for why an older peer parsing THIS payload safely ignores it.
        val capabilities = CAP_FRAME_AGE or CAP_SIMULCAST
        val buf = ByteBuffer.allocate(8 + 1 + pubkey.size + 1 + nameBytes.size + 1)
        buf.putLong(localNodeId)
        buf.put(MeshFrame.VERSION)
        buf.put(pubkey)
        buf.put(nameBytes.size.toByte())
        buf.put(nameBytes)
        buf.put(capabilities.toByte())
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

        // PART 2.6: never open two links to the same node id. Wi-Fi Direct's
        // star topology made this structurally impossible before Part 2 —
        // a client only ever dials the GO once, the GO only ever accepts —
        // but local-wifi lets BOTH sides dial each other directly by IP, so
        // a genuine race (A invites B while B independently invites A) can
        // resolve the SAME peerId on two separate live sockets. Keep
        // whichever link resolved FIRST; close this redundant second one via
        // the exact same cleanup path (handlePeerDisconnected) an ordinary
        // socket death already uses — hadId is still PENDING_ID here (this
        // link's own nodeId assignment below hasn't run yet), so that
        // cleanup path removes nothing from routingTable and skips the
        // roster/tree side effects, touching only this one redundant link.
        val existingLink = routingTable.get(peerId)
        if (existingLink != null && existingLink !== link) {
            Log.w("OFFTRACE", "MESH: duplicate link for ${MeshFrame.hex(peerId)} — closing the redundant one")
            handlePeerDisconnected(link, retryable = false)
            return
        }

        link.nodeId = peerId
        link.name = name
        // OCP PHASE 3/G6: capability negotiation — this link only ever
        // receives TYPE_FRAME_TS/TYPE_AUDIO_TS once its OWN HELLO advertised
        // support; see writeRawFrame/forwardBroadcast/forwardUnicast's
        // per-link dual-wrap.
        link.supportsFrameAge = (decoded.capabilities and CAP_FRAME_AGE) != 0
        link.supportsSimulcast = (decoded.capabilities and CAP_SIMULCAST) != 0
        pendingLinks.remove(link)
        routingTable.put(peerId, link)
        knownNames[peerId] = name
        log("OFFTRACE: MESH: peer nodeId=${MeshFrame.hex(peerId)} name=$name resolved")
        // PHASE 8 TRACK C3: this link is OUR OWN outbound connection resolving
        // — either this device's very first uplink (parentNodeId still null:
        // today's only case, and every 2/3-device call forever) or a
        // make-before-break reassignment's new parent (pendingParentLink ===
        // link, only ever set by reassignParentIfNeeded). An accepted
        // (inbound, child) link never touches parentNodeId.
        if (link.isOutbound) {
            if (pendingParentLink === link) {
                promotePendingParent(link)
            } else if (parentNodeId == null) {
                parentNodeId = peerId
                // This is, by construction, this device's very first-ever
                // connection (every node always dials the GO directly first —
                // see startAsClient) — so the peer it just resolved to IS the
                // GO. Captured once here rather than re-derived later, since
                // once a tree exists this device's live parentNodeId may no
                // longer be the GO at all.
                if (goNodeId == null) goNodeId = peerId
            }
        }
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
            // PHASE 8 TRACK C3: ground-truth dial address for this peer —
            // needed only if the tree ever assigns someone else to dial THEM
            // directly; harmless to always record. See computeTree/
            // buildTreeAssignPayload for the only readers.
            link.remoteAddress?.let { nodeDialAddress[peerId] = InetSocketAddress(it, MEDIA_PORT) }
            maybeRebuildTree("join")
        }
    }

    /** PHASE 8 TRACK C3: make-before-break's atomic swap — see
     *  reassignParentIfNeeded's doc for the full sequencing. By the time this
     *  runs, [newLink] is already fully live (its own HELLO round-trip just
     *  completed), so flipping parentNodeId here is the ONLY moment any
     *  outbound traffic starts targeting it; every enqueue() before this line
     *  went to the OLD link, which is still open until the explicit close()
     *  below — no gap where neither link is usable. */
    private fun promotePendingParent(newLink: PeerLink) {
        val oldId = parentNodeId
        val old = oldId?.let { routingTable.get(it) }
        parentNodeId = newLink.nodeId
        pendingParentLink = null
        if (old != null && oldId != null && oldId != newLink.nodeId) {
            // Deliberate, planned retirement — not a failure. Pre-marking it
            // means the old link's OWN read-loop thread (which will see an
            // IOException the instant close() below runs) finds
            // handlePeerDisconnected's disconnectedLinks guard already
            // tripped and does nothing further — no spurious reconnect
            // attempt for a connection we are intentionally replacing.
            disconnectedLinks.add(old)
            routingTable.remove(oldId)
            old.close()
            Log.d("OFFTRACE", "TREE: retired old parent ${MeshFrame.hex(oldId)}, new parent ${MeshFrame.hex(newLink.nodeId)}")
        }
        writeFrame(MeshFrame.BROADCAST_ID, TYPE_UPLINK_STATUS, uplinkStatusPayload(newLink.nodeId, mode = 0))
    }

    // ── PHASE 8 TRACK C3: relay tree — computation, assignment, reassignment ───

    private fun uplinkStatusPayload(actualParentId: Long, mode: Int): ByteArray {
        val buf = ByteBuffer.allocate(9)
        buf.putLong(actualParentId)
        buf.put(mode.toByte())
        return buf.array()
    }

    /** GO-consumed only — every other node ignores its own copy (this is a
     *  BROADCAST type so it floods the same way everything else does, see
     *  hasChildren()'s doc; only the GO needs to act on it). Deliberately
     *  coarse: any report at all just re-triggers computeTree on its normal
     *  debounce rather than trying to finely diff exactly what changed — the
     *  cooldown in [maybeRebuildTree] is what keeps this cheap. */
    private fun handleUplinkStatusFrame(header: MeshFrame.Header, payload: ByteArray) {
        if (!isGroupOwner || payload.size < 9) return
        Log.d("OFFTRACE", "TREE: uplink status from ${MeshFrame.hex(header.srcId)}")
        maybeRebuildTree("uplink_status")
    }

    /** GO-only. Below TREE_MIN_SIZE_FOR_RELAY this returns everyone directly
     *  under the GO UNCONDITIONALLY — no RSSI/RTT input is ever consulted —
     *  which is both the "flat topology stays flat for small calls" behavior
     *  and, combined with parentNodeId only ever changing via a real
     *  TYPE_TREE_ASSIGN (see reassignParentIfNeeded), the concrete mechanism
     *  behind the <=3-device-unchanged proof: this function never even runs
     *  its relay-selection logic for a call that size. */
    private fun computeTree(memberIds: List<Long>): Map<Long, Long> {
        val others = memberIds.filter { it != localNodeId }
        if (others.size < TREE_MIN_SIZE_FOR_RELAY) {
            return others.associateWith { TREE_ROOT_SENTINEL }
        }
        val parentOf = mutableMapOf<Long, Long>()
        val depthOf = mutableMapOf<Long, Int>()
        val childCount = mutableMapOf<Long, Int>()
        // Worst-link-to-uplink first — a node with no LINK_REPORT yet sorts
        // last (rtt defaults to 0, i.e. "assume fine"), so it's never
        // preferred as a demotion candidate ahead of one with real evidence
        // it needs help. Ties broken by nodeId for a fully deterministic
        // ordering (repeated computeTree calls on identical inputs always
        // produce the identical tree).
        val ordered = others.sortedWith(
            compareByDescending<Long> { rttToUplinkByNode[it] ?: 0L }.thenBy { it }
        )
        for (id in ordered) {
            var bestParent: Long? = null
            var bestDepth = Int.MAX_VALUE
            for (candidate in ordered) {
                if (candidate == id) continue
                val cParent = parentOf[candidate] ?: continue // not placed yet this pass
                val cDepth = depthOf[candidate] ?: continue
                if (cDepth + 1 > TREE_MAX_DEPTH) continue
                if ((childCount[candidate] ?: 0) >= TREE_FANOUT_CAP) continue
                if (!linkQualifies(id, candidate)) continue
                if (cDepth < bestDepth) {
                    bestDepth = cDepth
                    bestParent = candidate
                }
            }
            if (bestParent != null) {
                parentOf[id] = bestParent
                depthOf[id] = bestDepth + 1
                childCount[bestParent] = (childCount[bestParent] ?: 0) + 1
            } else {
                parentOf[id] = TREE_ROOT_SENTINEL
                depthOf[id] = 1
            }
        }
        return parentOf
    }

    /** True only when [candidateParent] is MEASURABLY better positioned than
     *  the GO itself for [candidateChild] to reach — RSSI (BLE overlap
     *  between the two, whichever direction was reported) preferred when
     *  available, RTT-to-uplink as the fallback. A pair with neither signal
     *  never qualifies — computeTree's default (parent = the GO) is always
     *  legal and is what a group with uniformly decent links simply never
     *  moves away from. */
    private fun linkQualifies(candidateChild: Long, candidateParent: Long): Boolean {
        val rssiToCandidate = bleRssiByNodePair[candidateChild]?.get(candidateParent)
            ?: bleRssiByNodePair[candidateParent]?.get(candidateChild)
        if (rssiToCandidate != null) {
            val rssiToGo = bleRssiByNodePair[candidateChild]?.get(localNodeId)
                ?: bleRssiByNodePair[localNodeId]?.get(candidateChild)
            return rssiToGo == null || rssiToCandidate > rssiToGo + RSSI_MARGIN_DBM
        }
        val rttCandidate = rttToUplinkByNode[candidateParent]
        val rttChild = rttToUplinkByNode[candidateChild]
        return rttCandidate != null && rttChild != null && rttCandidate < rttChild
    }

    /** GO-only, debounced by TREE_REBUILD_COOLDOWN_MS. Triggered by a join, a
     *  leave, or an UPLINK_STATUS report — never by anything time-based, so a
     *  quiet mesh never recomputes for no reason. */
    private fun maybeRebuildTree(reason: String) {
        if (!isGroupOwner) return
        val now = System.currentTimeMillis()
        if (now - lastTreeRebuildAtMs < TREE_REBUILD_COOLDOWN_MS) return
        lastTreeRebuildAtMs = now
        val memberIds = routingTable.roster().map { it.nodeId }
        val newTree = computeTree(memberIds)
        treeParentOf.clear()
        treeParentOf.putAll(newTree)
        treeGenId++
        val maxDepth = newTree.keys.maxOfOrNull { depthOfInTree(it, newTree) } ?: 0
        Log.d("OFFTRACE", "TREE: rebuilt reason=$reason nodes=${newTree.size} maxDepth=$maxDepth")
        val myChildren = newTree.filterValues { it == TREE_ROOT_SENTINEL }.keys
        Log.d("OFFTRACE", "TREE: parent=root children=[${myChildren.joinToString(",") { MeshFrame.hex(it) }}] depth=0")
        broadcastTreeAssign()
    }

    private fun depthOfInTree(nodeId: Long, tree: Map<Long, Long>): Int {
        var cur = tree[nodeId] ?: return 0
        var depth = 1
        var hops = 0
        while (cur != TREE_ROOT_SENTINEL && hops < TREE_MAX_DEPTH + 2) {
            cur = tree[cur] ?: break
            depth++
            hops++
        }
        return depth
    }

    private fun broadcastTreeAssign() {
        writeFrame(MeshFrame.BROADCAST_ID, TYPE_TREE_ASSIGN, buildTreeAssignPayload())
        // The GO applies its own authoritative copy directly rather than
        // waiting to "receive" its own broadcast (which forwardBroadcast
        // never loops back to the sender anyway).
        applyTreeAssignment(treeGenId, treeParentOf.toMap())
    }

    /** [4B genId][1B nodeCount][nodeCount*(8B nodeId,8B parentNodeId,4B ipv4,2B port)] */
    private fun buildTreeAssignPayload(): ByteArray {
        val entries = treeParentOf.entries.toList().take(255)
        val buf = ByteBuffer.allocate(4 + 1 + entries.size * 22)
        buf.putInt(treeGenId)
        buf.put(entries.size.toByte())
        entries.forEach { (nodeId, parent) ->
            buf.putLong(nodeId)
            buf.putLong(parent)
            val addrBytes = nodeDialAddress[nodeId]?.address?.address
            buf.put(if (addrBytes != null && addrBytes.size == 4) addrBytes else ByteArray(4))
            buf.putShort((nodeDialAddress[nodeId]?.port ?: MEDIA_PORT).toShort())
        }
        return buf.array()
    }

    /** Every node applies this (GO included, via broadcastTreeAssign's direct
     *  call) — genId guards against a stale/out-of-order copy arriving after
     *  a newer one already applied (possible across multi-hop delivery at
     *  real tree depth; irrelevant, but harmless, at depth 0). */
    private fun handleTreeAssignFrame(payload: ByteArray) {
        try {
            val din = DataInputStream(ByteArrayInputStream(payload))
            val genId = din.readInt()
            if (genId <= treeGenId && treeParentOf.isNotEmpty()) return
            val count = din.readUnsignedByte()
            val newTree = mutableMapOf<Long, Long>()
            val addresses = mutableMapOf<Long, InetSocketAddress>()
            repeat(count) {
                val nodeId = din.readLong()
                val parent = din.readLong()
                val ipBytes = ByteArray(4)
                din.readFully(ipBytes)
                val port = din.readUnsignedShort()
                newTree[nodeId] = parent
                if (ipBytes.any { it != 0.toByte() }) {
                    try {
                        addresses[nodeId] = InetSocketAddress(InetAddress.getByAddress(ipBytes), port)
                    } catch (_: Exception) {
                    }
                }
            }
            treeAssignedAddress.clear()
            treeAssignedAddress.putAll(addresses)
            applyTreeAssignment(genId, newTree)
        } catch (e: Exception) {
            logW("OFFTRACE: MESH: malformed TREE_ASSIGN: ${e.message}")
        }
    }

    private fun applyTreeAssignment(genId: Int, newTree: Map<Long, Long>) {
        treeGenId = genId
        if (treeParentOf !== newTree) {
            treeParentOf.clear()
            treeParentOf.putAll(newTree)
        }
        assignedParentId = newTree[localNodeId] ?: TREE_ROOT_SENTINEL
        // A newly-acquired child (assigned to ME by this tree) means this
        // node must be able to accept inbound connections now, even if it
        // has never been a relay before — see ensureRelayServerStarted's doc
        // for why this is always safe to call, including on the GO (already
        // listening) or repeatedly (idempotent).
        if (newTree.values.any { it == localNodeId }) ensureRelayServerStarted()
        if (!isGroupOwner) reassignParentIfNeeded()
    }

    /** Resolves where to dial for [assignedParentId] — falls back to the
     *  existing, completely unmodified groupOwnerAddress/MEDIA_PORT whenever
     *  no assignment exists yet or its address wasn't received. This is the
     *  literal fallback path: nothing new is written for it, only the
     *  decision of which address to try first is new. */
    private fun resolveUplinkTarget(): Pair<InetAddress, Int>? {
        if (assignedParentId != TREE_ROOT_SENTINEL) {
            treeAssignedAddress[assignedParentId]?.let { return it.address to it.port }
        }
        val addr = groupOwnerAddress ?: return null
        return addr to MEDIA_PORT
    }

    /** Called whenever this node's assignedParentId might have just changed.
     *  A no-op whenever the assignment already matches the live parent —
     *  which is EVERY time for a plain client under TREE_MIN_SIZE_FOR_RELAY
     *  (assignedParentId is always TREE_ROOT_SENTINEL there, resolving to
     *  goNodeId, which is exactly what parentNodeId already is) — so this
     *  function runs its dial logic exactly zero times for the entire life
     *  of a 2/3-device call. */
    private fun reassignParentIfNeeded() {
        if (isGroupOwner) return
        val target = if (assignedParentId == TREE_ROOT_SENTINEL) goNodeId else assignedParentId
        if (target == null || target == parentNodeId) return
        val (addr, port) = resolveUplinkTarget() ?: return
        dialNewParent(target, addr, port)
    }

    /** Make-before-break: dials [addr]/[port] and, ONLY once its own HELLO
     *  round-trip fully resolves (see handleHelloFrame's pendingParentLink
     *  branch -> promotePendingParent), swaps parentNodeId to it and retires
     *  the old link — every enqueue() in between still targets the OLD,
     *  still-fully-live link, so audio/video is never interrupted by a tree
     *  rebuild. A dial or resolve failure here is not retried in a loop
     *  (unlike attemptClientReconnect's dedicated retry for an unexpected
     *  drop) — it just reports fallback status and leaves the CURRENT parent
     *  untouched; the next debounced rebuild gets another chance. */
    private fun dialNewParent(targetId: Long, addr: InetAddress, port: Int) {
        if (pendingParentLink != null) return // a reassignment is already in flight
        Thread({
            try {
                val s = Socket(addr, port)
                Log.d("OFFTRACE", "TREE: dialing new parent ${MeshFrame.hex(targetId)} at $addr:$port")
                handleNewConnection(s, outbound = true, markAsPendingParent = true)
                val dialed = pendingParentLink
                mainHandler.postDelayed({
                    if (dialed != null && pendingParentLink === dialed && dialed.nodeId == MeshFrame.PENDING_ID) {
                        Log.d("OFFTRACE", "TREE: new parent ${MeshFrame.hex(targetId)} never resolved — abandoning")
                        pendingParentLink = null
                        disconnectedLinks.add(dialed)
                        dialed.close()
                        parentNodeId?.let { writeFrame(MeshFrame.BROADCAST_ID, TYPE_UPLINK_STATUS, uplinkStatusPayload(it, mode = 1)) }
                    }
                }, SOCKET_READ_TIMEOUT_MS * 2L)
            } catch (e: Exception) {
                logW("OFFTRACE: MESH: TREE reassignment dial to ${MeshFrame.hex(targetId)} failed: ${e.message}")
                parentNodeId?.let { writeFrame(MeshFrame.BROADCAST_ID, TYPE_UPLINK_STATUS, uplinkStatusPayload(it, mode = 1)) }
            }
        }, "MediaTreeReassign").guarded().start()
    }

    // ── PHASE 8 TRACK C3: link quality reporting (RTT probe + RSSI report) ─────

    private fun buildLinkReportPayload(): ByteArray {
        val rtt = rttToOwnUplinkMs ?: -1L
        val neighbors = ledger.knownNodeIds()
            .mapNotNull { id ->
                ledger.blePresenceFor(id)?.let { p ->
                    if (System.currentTimeMillis() - p.seenAtMs < RSSI_STALE_MS) id to p.rssiDbm else null
                }
            }.take(255)
        val buf = ByteBuffer.allocate(8 + 1 + neighbors.size * 9)
        buf.putLong(rtt)
        buf.put(neighbors.size.toByte())
        neighbors.forEach { (id, rssi) -> buf.putLong(id); buf.put(rssi.coerceIn(-128, 127).toByte()) }
        return buf.array()
    }

    private fun sendLinkReport() {
        if (isGroupOwner) return // nothing to report — the GO has no uplink
        writeFrame(MeshFrame.BROADCAST_ID, TYPE_LINK_REPORT, buildLinkReportPayload())
    }

    private fun handleLinkReportFrame(header: MeshFrame.Header, payload: ByteArray) {
        if (!isGroupOwner) return
        try {
            val din = DataInputStream(ByteArrayInputStream(payload))
            val rtt = din.readLong()
            if (rtt >= 0) rttToUplinkByNode[header.srcId] = rtt
            val count = din.readUnsignedByte()
            val map = bleRssiByNodePair.getOrPut(header.srcId) { ConcurrentHashMap() }
            repeat(count) {
                val id = din.readLong()
                val rssi = din.readByte().toInt()
                map[id] = rssi
            }
            lastLinkReportAtMs[header.srcId] = System.currentTimeMillis()
        } catch (e: Exception) {
            logW("OFFTRACE: MESH: malformed LINK_REPORT from ${MeshFrame.hex(header.srcId)}: ${e.message}")
        }
    }

    private fun sendLinkProbe() {
        if (isGroupOwner) return
        val dst = uplinkNodeId() ?: return
        pendingProbeToken = System.nanoTime()
        pendingProbeSentAtMs = System.currentTimeMillis()
        val buf = ByteBuffer.allocate(8)
        buf.putLong(pendingProbeToken)
        writeFrame(dst, TYPE_LINK_PROBE, buf.array())
    }

    /** Replies directly to whoever asked — nextHopFor(header.srcId) resolves
     *  straight to them since a PROBE's sender is always a direct neighbor
     *  (dst=current uplink, one hop only, by construction — see TYPE_LINK_PROBE's
     *  wire doc). Works correctly on a relay node replying to a CHILD's probe
     *  too, not just the GO — writeFrame's nextHopFor-based routing tries the
     *  direct-neighbor lookup before ever falling back to "send up." */
    private fun handleLinkProbeFrame(header: MeshFrame.Header, payload: ByteArray) {
        if (payload.size < 8) return
        writeFrame(header.srcId, TYPE_LINK_PROBE_ACK, payload)
    }

    private fun handleLinkProbeAckFrame(header: MeshFrame.Header, payload: ByteArray) {
        if (payload.size < 8) return
        val token = ByteBuffer.wrap(payload).long
        if (token != pendingProbeToken) return // stale/duplicate ack — ignore
        rttToOwnUplinkMs = (System.currentTimeMillis() - pendingProbeSentAtMs).coerceAtLeast(0L)
    }

    private fun startLinkTickers() {
        if (linkTickerRunnable != null) return
        val r = object : Runnable {
            override fun run() {
                sendLinkReport()
                sendLinkProbe()
                mainHandler.postDelayed(this, LINK_REPORT_INTERVAL_MS)
            }
        }
        linkTickerRunnable = r
        mainHandler.postDelayed(r, LINK_REPORT_INTERVAL_MS)
    }

    private fun stopLinkTickers() {
        linkTickerRunnable?.let { mainHandler.removeCallbacks(it) }
        linkTickerRunnable = null
    }

    /** Any node — not just the GO — can accept CHILD connections once
     *  assigned some (see applyTreeAssignment). Idempotent (a second call is
     *  a no-op) and safe to call on the GO, which is already listening via
     *  startAsServer — this never binds a second socket there. Reuses
     *  [runAcceptLoop], the exact same accept-loop body startAsServer already
     *  runs, just bound lazily instead of unconditionally at session start. */
    private fun ensureRelayServerStarted() {
        if (isGroupOwner || relayServerSocket != null) return
        Thread({
            try {
                val srv = ServerSocket(MEDIA_PORT)
                relayServerSocket = srv
                log("MEDIA: relay node listening for children on port $MEDIA_PORT")
                runAcceptLoop(srv)
            } catch (e: Exception) {
                logW("OFFTRACE: MESH: relay server bind failed: ${e.message}")
            }
        }, "MediaRelayAccept").guarded().start()
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
    // D1: @Volatile so a read outside the lock below always sees the latest
    // published value, PLUS the read-modify-write in
    // applyRosterDiffForLostContact synchronized — broadcastRoster (GO) can
    // fire from more than one triggering read thread (a join on one peer's
    // link racing a leave on another's), and a plain `var` read-then-write
    // here would let two concurrent callers both read the SAME stale
    // previousRosterIds, each compute "lost" against it, and then clobber
    // each other's write — either missing a genuine lost-contact event or
    // firing a spurious one.
    @Volatile private var previousRosterIds: Set<Long> = emptySet()
    private val previousRosterIdsLock = Any()

    private fun applyRosterDiffForLostContact(members: List<RoutingTable.Member>) {
        synchronized(previousRosterIdsLock) {
            val newIds = members.map { it.nodeId }.toSet()
            val lost = previousRosterIds - newIds
            previousRosterIds = newIds
            lost
        }.forEach { id -> if (id != localNodeId) ledger.markLostContact(id, localNodeId) }
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

    // ── PHASE 4: relay suppression — battery-weighted delay + duplicate
    // suppression, ALLOWLISTed types only (see RELAY_SUPPRESSION_ALLOWLIST's
    // doc). Called from routeFrame's broadcast/unicast branches INSTEAD OF
    // forwardBroadcast/forwardUnicast for those types; every other type
    // (1-19, incl. all media/group-call types) keeps calling forwardBroadcast/
    // forwardUnicast directly, completely unchanged by this phase.

    /** Same sticky-broadcast read OfflineCallActivity.readBatteryPercent()
     *  already uses (registerReceiver(null, ...) synchronously returns the
     *  last sticky ACTION_BATTERY_CHANGED, no persistent receiver
     *  registered) — safe to call from any thread. Missing battery data
     *  (level/scale unavailable) assumes 100%/not-charging rather than
     *  blocking relay on absent data — the three explicit gates (disabled/
     *  charging-only/minimum-battery) are the only things allowed to say
     *  NEVER. */
    private fun currentBatteryPercentAndCharging(): Pair<Int, Boolean> {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 100
        val plugged = status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return pct to (plugged != 0)
    }

    /** GO-only (report D5/4.4) — bleRssiByNodePair is populated only on the
     *  GO (handleLinkReportFrame early-returns if !isGroupOwner), so a
     *  client always returns false here: it has no data to confirm coverage
     *  with, and this deliberately never infers/fabricates it from RSSI or
     *  anything else. An empty local neighbour set ALSO returns false
     *  (never "trivially covered" from an absence of data) — only a real,
     *  non-empty local BLE neighbour set that is a genuine subset of
     *  [srcId]'s reported set counts as covered. */
    private fun neighborsCoveredBy(srcId: Long): Boolean {
        if (!isGroupOwner) return false
        val mine = ledger.knownNodeIds().filter { id ->
            val p = ledger.blePresenceFor(id)
            p != null && System.currentTimeMillis() - p.seenAtMs < RSSI_STALE_MS
        }.toSet()
        if (mine.isEmpty()) return false
        val theirs = bleRssiByNodePair[srcId]?.keys ?: return false
        return mine.all { it in theirs }
    }

    /** routeFrame's entry point for any RELAY_SUPPRESSION_ALLOWLIST type,
     *  replacing a direct forwardBroadcast/forwardUnicast call for those
     *  types only. THREADING: runs on the calling reader thread only up to
     *  the point where it schedules onto [relayScheduler] (a single
     *  dedicated background thread, never a reader thread, never
     *  mainHandler); the actual forward — forwardBroadcast/forwardUnicast,
     *  via [forwardNowAllowlisted] — always executes on relayScheduler's
     *  thread, both for the immediate (urgent/covered) and deferred cases,
     *  so there is exactly one thread ever calling into them from this
     *  path, never the reader thread racing the scheduler thread. The seen/
     *  dupCount caches are only ever touched inside [relayCacheLock] here —
     *  routeFrame's EXISTING dedupe hooks (MeshSosManager/MeshCarrier) use
     *  their own separate locks/state and are never touched by this
     *  function. forwardBroadcast/forwardUnicast's own shared state
     *  (routingTable, PeerLink.enqueue) is already safe for concurrent
     *  callers — routeFrame already calls them from N concurrent reader
     *  threads today (one per PeerLink), so relayScheduler's thread is
     *  simply one more already-supported concurrent caller, not a new kind
     *  of access. */
    private fun scheduleAllowlistedForward(header: MeshFrame.Header, payload: ByteArray, fromLink: PeerLink, broadcast: Boolean) {
        val id = relayFrameId(header.srcId, header.type, payload)
        val now = System.currentTimeMillis()
        val alreadySeen = relayDedupeCache.observe(id, now)
        if (alreadySeen) {
            Log.d("OFFTRACE", "RELAY: suppressed id=$id dups=${relayDedupeCache.dupCountFor(id)}")
            return
        }
        if (header.ttl <= 0) return
        if (isUrgentRelayFrame(header.type, payload)) {
            Log.d("OFFTRACE", "RELAY: urgent bypass type=${header.type}")
            relayScheduler.execute { forwardNowAllowlisted(header, payload, fromLink, broadcast, id) }
            return
        }
        // GO-only, see neighborsCoveredBy's doc — always false on a client.
        if (isGroupOwner && neighborsCoveredBy(header.srcId)) {
            return
        }
        val prefs = context.getSharedPreferences("opencall", Context.MODE_PRIVATE)
        val relayEnabled = prefs.getBoolean("relay_enabled", true)
        val relayOnlyWhenCharging = prefs.getBoolean("relay_only_charging", false)
        val relayMinBattery = prefs.getInt("relay_min_battery", 20)
        val (pct, charging) = currentBatteryPercentAndCharging()
        val delay = relayDelayMs(relayEnabled, relayOnlyWhenCharging, charging, pct, relayMinBattery)
        if (delay == null) return // one of the three gates said NEVER
        Log.d("OFFTRACE", "RELAY: defer id=$id delay=${delay}ms batt=$pct% chg=$charging")
        val future = relayScheduler.schedule({
            relayDeferrals.remove(id)
            if (relayDedupeCache.dupCountFor(id) < RELAY_K_SUPPRESS) {
                forwardNowAllowlisted(header, payload, fromLink, broadcast, id)
            }
        }, delay, TimeUnit.MILLISECONDS)
        relayDeferrals[id] = future
    }

    private fun forwardNowAllowlisted(header: MeshFrame.Header, payload: ByteArray, fromLink: PeerLink, broadcast: Boolean, id: Long) {
        Log.d("OFFTRACE", "RELAY: sent id=$id ttl=${header.ttl}")
        if (broadcast) forwardBroadcast(header, payload, fromLink) else forwardUnicast(header, payload)
    }

    /** TEARDOWN: cancels every still-pending deferral — called from both
     *  handlePeerDisconnected (per-link teardown) and stop() (whole-transport/
     *  Activity-destroy teardown). Deliberately cancels ALL pending
     *  deferrals on ANY link loss, not just ones targeting that link — a
     *  broadcast deferral has no single target link (forwardBroadcast fans
     *  out to routingTable.allExcept(...) freshly AT FIRE TIME), so "this
     *  link is gone" can invalidate one even if it wasn't the original
     *  sender. This is a safe, conservative over-cancellation, not a
     *  correctness requirement by itself — PeerLink.enqueue() already
     *  no-ops on a closed link (`if (closed) return`, RoutingTable.kt) and
     *  forwardUnicast/forwardBroadcast both resolve their targets FRESH at
     *  execution time (nextHopFor / routingTable.allExcept), never a stale
     *  reference — so a deferral firing against an already-torn-down link
     *  was already safe before this function existed. This exists to
     *  satisfy the letter of the requirement AND to stop doing pointless
     *  scheduled work once there is nothing left to relay to. */
    private fun cancelAllPendingRelayDeferrals() {
        relayDeferrals.values.forEach { it.cancel(false) }
        relayDeferrals.clear()
    }

    /** PHASE 3 core dispatch: dst==self or BROADCAST is ours to handle (and, on the
     *  GO, BROADCAST also fans out to everyone else); anything else is forwarded —
     *  pure demux, no codec/media work, so the GO never decodes a call it isn't
     *  party to. */
    private fun routeFrame(header: MeshFrame.Header, payload: ByteArray, fromLink: PeerLink) {
        // OCP PHASE 0.2: piggybacks on read-thread traffic already happening
        // every frame (same pattern as maybeEvaluateTileBudget) — internally
        // throttled to 1/sec per peer, see maybeLogPeerLatency.
        maybeLogPeerLatency(fromLink)
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

        // OCP PHASE 3.4: age-based drop — BOTH at this device as a receiver
        // (about to dispatchLocal below) AND, on the GO/a relay, before this
        // same frame would otherwise be forwarded (both branches below reuse
        // [resolved]) — a single check upstream of both satisfies "drop at
        // the receiver, and also at the GO before forwarding" in one place.
        // A null result means "over budget, no IDR exception applies" —
        // return immediately, never dispatched, never forwarded, never
        // consumes relay uplink. For every type OTHER than
        // TYPE_FRAME_TS/TYPE_AUDIO_TS, [resolveFrameAge] is a pure
        // passthrough (see its doc) — routing for every other type below is
        // byte-for-byte unchanged from before this phase.
        val resolved = resolveFrameAge(header, innerPayload) ?: run {
            staleAgedFrameDropCount++
            if (staleAgedFrameDropCount % DROP_LOG_INTERVAL == 0) {
                logW("OFFTRACE: MESH: dropped aged frame src=${MeshFrame.hex(header.srcId)} type=${header.type} count=$staleAgedFrameDropCount")
            }
            return
        }
        val rHeader = resolved.header
        val rPayload = resolved.payload

        // PHASE 5A/5BC dedupe hook — meshSosManager.isSosFindType is the single
        // source of truth for this scope: TYPE_SOS (20) / TYPE_FIND_REQ (21) /
        // TYPE_FIND_RESP (22) / TYPE_POSITION (23). Deliberately excludes
        // TYPE_SOS_ACK (24) — see MeshSosManager.isSosFindType's doc. For every
        // other type (1-19, including every media/group-call type) this whole
        // block is skipped and routing below runs exactly as it always has.
        if (meshSosManager.isSosFindType(rHeader.type) &&
            meshSosManager.checkAndRecordDuplicate(rHeader.srcId, rHeader.type, rPayload)
        ) {
            val seq = MeshLocation.decode(rPayload)?.msgSeq
            logPerSrcThrottled(rHeader.srcId) { "MESH: dropped duplicate type=${rHeader.type} from=${MeshFrame.hex(rHeader.srcId)} seq=$seq" }
            return
        }
        // PHASE 6 TRACK A: MeshCarrier owns a SEPARATE dedupe cache keyed on
        // msgId, not (srcId,type,seq) — see MeshCarrier's class doc for why this
        // must not share meshSosManager's cache/scope.
        if (carrier.isCarrierType(rHeader.type) && carrier.checkAndRecordDuplicate(rPayload)) {
            logPerSrcThrottled(rHeader.srcId) { "MESH: dropped duplicate carried msgId from=${MeshFrame.hex(rHeader.srcId)}" }
            return
        }
        // PHASE 6 TRACK A: this is a genuinely LIVE (not carrier-delivered) TYPE_SOS
        // reaching routeFrame directly off a real link — cache it for future
        // store-and-forward. Deliberately placed here, not inside
        // MeshSosManager.handleSosFrame, so a carrier-delivered SOS (redispatched
        // via dispatchCarriedInner, which calls dispatchLocal directly and never
        // routeFrame) doesn't re-queue itself — MeshCarrier.handleStoreFwdFrame
        // already becomes a mule for that case. See MeshSosManager.cacheForCarry.
        // PART A / A1: cache [payload] — the UNTOUCHED wire bytes for this
        // frame, trailer (timestamp+signature) still attached — NOT [rPayload],
        // which verifyIncoming already stripped down to the inner payload
        // above. No wire change: that trailer already crossed the wire right
        // here, on this exact live receipt; this only changes what gets
        // cached for later replay. See MeshSigner.verifyCarried, which needs
        // that trailer to authenticate a carried SOS before it can alarm.
        if (rHeader.type == TYPE_SOS) {
            meshSosManager.cacheForCarry(rHeader.srcId, payload)
        }
        // PHASE 8 TRACK C2: one-hop only (dst=this device's immediate
        // uplink, see maybeSendVideoSubscription) — handled directly against
        // [fromLink] rather than threaded through dispatchLocal (which is
        // deliberately transport-agnostic and has ~15 call sites, including
        // carrier-replay, that never carry a live PeerLink). Never forwarded
        // further; a subscription only ever describes what its IMMEDIATE
        // sender wants from THIS device.
        if (rHeader.type == TYPE_SUBSCRIBE) {
            handleSubscribeFrame(fromLink, rPayload)
            return
        }
        // PHASE 6 TRACK B1: someone is trying to reach us — beacon mode (if
        // active on THIS device) keeps its radio in the low-latency window a
        // while longer instead of duty-cycling down. No-op, cheap, if beacon
        // mode was never entered.
        sosBeaconMode.onInboundFrame()
        when {
            rHeader.dstId == localNodeId -> {
                logIfRelayed(rHeader, fromLink)
                dispatchLocal(rHeader, rPayload)
            }
            rHeader.dstId == MeshFrame.BROADCAST_ID -> {
                logIfRelayed(rHeader, fromLink)
                dispatchLocal(rHeader, rPayload)
                // PHASE 8 STEP 3: TYPE_AUDIO stops being relayed raw once
                // GO-mixing has taken over for this call — tickGoMix's own
                // unicast distribution replaces it entirely. Every other
                // type, and TYPE_AUDIO itself below the threshold, forwards
                // exactly as before this phase.
                // PHASE 8 TRACK C3: was isGroupOwner alone — a relay node
                // (hasChildren()) now also floods a broadcast onward to its
                // own neighbors (see forwardBroadcast's doc: byte-identical
                // to today's GO-only behavior when hasChildren() is false,
                // which is every node for the entire life of any call that
                // never crosses TREE_MIN_SIZE_FOR_RELAY). PHASE 8 TRACK C4:
                // the isGoMixReplacingBroadcastAudio check used to gate this
                // WHOLE call; it now lives inside forwardBroadcast itself,
                // applied PER RECIPIENT — see that function's doc for why
                // that's what lets raw audio keep reaching the GO at any
                // tree depth while still cutting the redundant sideways/
                // downward relay a mixing node's own tick already replaces.
                if (isGroupOwner || hasChildren()) {
                    // PHASE 4: allowlisted types (report 4.0's explicit set —
                    // never types 1-19, which carry audio/video and have no
                    // dedup today) route through the battery-weighted
                    // delay/suppression path instead of forwarding
                    // immediately; every other type is completely untouched.
                    if (isRelaySuppressionAllowlisted(rHeader.type)) {
                        scheduleAllowlistedForward(rHeader, rPayload, fromLink, broadcast = true)
                    } else {
                        forwardBroadcast(rHeader, rPayload, fromLink, resolved.captureMicros, resolved.isLowLayer)
                    }
                }
            }
            else -> {
                // PHASE 8 TRACK C3: was isGroupOwner alone — see forwardBroadcast's
                // gate above for the identical reasoning; hasChildren() is
                // false for every non-relay node, so this reduces to exactly
                // today's condition there.
                if (isGroupOwner || hasChildren()) {
                    // PHASE 4: same allowlist gate as the broadcast branch above.
                    if (isRelaySuppressionAllowlisted(rHeader.type)) {
                        scheduleAllowlistedForward(rHeader, rPayload, fromLink, broadcast = false)
                    } else {
                        forwardUnicast(rHeader, rPayload, resolved.captureMicros)
                    }
                } else {
                    wrongDstDropCount++
                    if (wrongDstDropCount % DROP_LOG_INTERVAL == 0) {
                        logW("OFFTRACE: MESH: dropped frame not addressed to us dst=${MeshFrame.hex(rHeader.dstId)} count=$wrongDstDropCount")
                    }
                }
            }
        }
    }

    /** OCP PHASE 3.1-3.4: resolves a possibly-timestamped incoming frame.
     *  For any type other than TYPE_FRAME_TS/TYPE_AUDIO_TS this is a pure
     *  passthrough — [ResolvedFrame.captureMicros] is null and [header]/
     *  [payload] are returned completely unchanged, so every other type's
     *  routing is byte-for-byte identical to before this phase. For the two
     *  timestamped types: strips the 8B BE capture-micros prefix, computes
     *  age via [frameAgeTracker] (a DELTA against a per-source baseline —
     *  see that class's doc — never a raw wall-clock comparison), and
     *  returns null (drop) if the frame is over its budget UNLESS it is a
     *  video IDR at least as new as the last IDR this device accepted (see
     *  [shouldKeepAgedFrame]). The returned header always carries the
     *  LEGACY type (2/3) — every downstream consumer (dispatchLocal,
     *  forwardBroadcast/forwardUnicast's non-timestamped fallback) is
     *  completely unaware timestamped framing exists at all. */
    private fun resolveFrameAge(header: MeshFrame.Header, payload: ByteArray): ResolvedFrame? {
        // OCP PHASE 5.1: TYPE_CONFIG_LOW/TYPE_FRAME_LOW carry no timestamp
        // prefix (the low layer isn't age-tracked — see this phase's report)
        // and no age budget — just a type normalization, mirroring the
        // TS types' "downstream never sees the wire-level distinction"
        // shape, marked via [ResolvedFrame.isLowLayer] instead.
        when (header.type) {
            TYPE_CONFIG_LOW -> return ResolvedFrame(header.copy(type = TYPE_CONFIG), payload, null, isLowLayer = true)
            TYPE_FRAME_LOW -> return ResolvedFrame(header.copy(type = TYPE_FRAME), payload, null, isLowLayer = true)
            else -> {}
        }
        val legacyType = when (header.type) {
            TYPE_FRAME_TS -> TYPE_FRAME
            TYPE_AUDIO_TS -> TYPE_AUDIO
            else -> return ResolvedFrame(header, payload, null)
        }
        if (payload.size < 8) return ResolvedFrame(header.copy(type = legacyType), payload, null)
        val captureMicros = ByteBuffer.wrap(payload, 0, 8).long
        val stripped = payload.copyOfRange(8, payload.size)
        val nowMicros = System.nanoTime() / 1000L
        val ageMs = frameAgeTracker.ageMs(header.srcId, captureMicros, nowMicros)
        lastKnownAgeMs[header.srcId] = ageMs
        val isVideo = legacyType == TYPE_FRAME
        val budgetMs = if (isVideo) VIDEO_AGE_BUDGET_MS else AUDIO_AGE_BUDGET_MS
        val isIdr = isVideo && PeerLink.payloadCarriesIdr(stripped)
        val lastIdrMicros = lastAcceptedIdrCaptureMicros[header.srcId] ?: -1L
        if (!shouldKeepAgedFrame(ageMs, budgetMs, isIdr, captureMicros >= lastIdrMicros)) return null
        if (isIdr) lastAcceptedIdrCaptureMicros[header.srcId] = captureMicros
        return ResolvedFrame(header.copy(type = legacyType), stripped, captureMicros)
    }

    private data class ResolvedFrame(
        val header: MeshFrame.Header,
        val payload: ByteArray,
        val captureMicros: Long?,
        val isLowLayer: Boolean = false
    )

    /** A client has exactly one link (the GO) — if a frame's immediate sender isn't
     *  who its src claims, it was relayed through the GO from a third member.
     *  Throttled to at most one line per srcId per second (same pattern as
     *  [logDroppedGroupFrame]) — relay behaviour itself is unchanged, only how
     *  often it's logged; [n] carries how many relayed frames were coalesced
     *  into this one line. */
    private fun logIfRelayed(header: MeshFrame.Header, fromLink: PeerLink) {
        if (isGroupOwner || header.srcId == fromLink.nodeId) return
        val srcId = header.srcId
        val n = (relayedFrameCount[srcId] ?: 0) + 1
        relayedFrameCount[srcId] = n
        val now = System.currentTimeMillis()
        val last = relayedLogAtMs[srcId] ?: 0L
        if (now - last < 1_000L) return
        relayedLogAtMs[srcId] = now
        relayedFrameCount[srcId] = 0
        Log.d("OFFTRACE", "MESH: relayed $n frames from ${MeshFrame.hex(srcId)}")
    }

    /** PHASE 8 TRACK C2: [payload] starts with [1B count][count*8B srcId] —
     *  the exact set of srcIds [fromLink] currently wants TYPE_FRAME (high)
     *  from. Malformed input (truncated count, wrong length) is dropped,
     *  never crashes the read loop — same defensive posture as every other
     *  frame parser here. count==0 is a real, valid value (see
     *  PeerLink.videoSubscription's doc on why null and emptySet() are
     *  never coalesced).
     *
     *  OCP PHASE 5.1/G6: additively extended with a SECOND, identically-
     *  shaped [1B count][count*8B srcId] block for the LOW layer — read
     *  only if bytes remain after the high list (an older peer's SUBSCRIBE
     *  simply has none, which reads as "no low subscriptions," never a
     *  parse failure; mirrors MeshSigner.decodeHelloInner's exact pattern
     *  for the identical reason). A NEWER peer's SUBSCRIBE talking to an
     *  OLD build of this function is the mirror case — this exact code
     *  already stops reading right after the high list's ids and never
     *  looks at what follows, so the low block is silently ignored by an
     *  old peer, satisfying G6 without that peer needing any awareness the
     *  low layer exists. */
    private fun handleSubscribeFrame(fromLink: PeerLink, payload: ByteArray) {
        try {
            val din = DataInputStream(ByteArrayInputStream(payload))
            val count = din.readUnsignedByte()
            val ids = (0 until count).map { din.readLong() }.toSet()
            fromLink.videoSubscription = ids
            var lowIds: Set<Long>? = null
            if (din.available() >= 1) {
                val lowCount = din.readUnsignedByte()
                lowIds = (0 until lowCount).map { din.readLong() }.toSet()
                fromLink.videoSubscriptionLow = lowIds
            }
            Log.d(
                "OFFTRACE",
                "SUB: ${MeshFrame.hex(fromLink.nodeId)} subscribed to [${ids.joinToString(",") { MeshFrame.hex(it) }}] " +
                    "(${ids.size} streams) low=[${lowIds?.joinToString(",") { MeshFrame.hex(it) } ?: ""}] (${lowIds?.size ?: 0} streams)"
            )
        } catch (e: Exception) {
            logW("OFFTRACE: MESH: malformed SUBSCRIBE from ${MeshFrame.hex(fromLink.nodeId)}: ${e.message}")
        }
    }

    /** PHASE 8 TRACK C4: [isGoMixReplacingBroadcastAudio] suppression is
     *  applied PER RECIPIENT here, not as an all-or-nothing gate at the call
     *  site — every recipient gets it EXCEPT [parentNodeId] (null on the GO,
     *  so this exception is a pure no-op there, matching today's GO
     *  behavior exactly: the GO has no parent, so it suppresses to
     *  everyone, exactly as before this track). A relay therefore ALWAYS
     *  still relays raw TYPE_AUDIO upward toward the GO even once it stops
     *  relaying it sideways/downward (replaced by its own tickGoMix variants
     *  instead) — which is what lets the GO's own, completely unmodified
     *  mixing continue to see every participant's real audio at any tree
     *  depth, without a relay ever needing to synthesize a second, separately
     *  wire-typed "here's my subtree's mix" contribution upward. */
    /** [captureMicros] is non-null only when this frame arrived as
     *  TYPE_FRAME_TS/TYPE_AUDIO_TS and already survived resolveFrameAge's
     *  age check (see routeFrame) — [header.type] itself is ALWAYS the
     *  legacy TYPE_FRAME/TYPE_AUDIO by the time it reaches here (resolveFrameAge
     *  rewrites it), so every existing check below (isGoMixReplacingBroadcastAudio,
     *  the TYPE_FRAME subscription filter) is untouched by OCP PHASE 3 — this
     *  function only additionally re-wraps [payload] with the ORIGINAL
     *  capture timestamp for whichever downstream links advertised support,
     *  same "one encode, two possible wrappers, chosen per-link" shape as
     *  writeRawFrame's origination path. */
    /** [isLowLayer] is true only when this frame arrived as
     *  TYPE_CONFIG_LOW/TYPE_FRAME_LOW and [resolveFrameAge] already
     *  normalized [header.type] down to TYPE_CONFIG/TYPE_FRAME for this
     *  call — mirrors [captureMicros]'s "always the legacy type by the time
     *  it reaches here" shape (see that param's doc on the function above). */
    private fun forwardBroadcast(
        header: MeshFrame.Header, payload: ByteArray, fromLink: PeerLink,
        captureMicros: Long? = null, isLowLayer: Boolean = false
    ) {
        val newTtl = header.ttl - 1
        if (newTtl <= 0) {
            ttlZeroDropCount++
            return
        }
        logForward(header, newTtl.toByte())
        val suppressDownward = isGoMixReplacingBroadcastAudio(header.type)
        // OCP PHASE 2.4/0.3: liveness signal consumed only by tickGoMix's
        // gapMs computation — a raw TYPE_AUDIO frame that actually went out
        // (not suppressed) counts as "audio was just delivered," same as a
        // GO-mixed frame going out (see tickGoMix) or the GO's own local
        // raw-mix playback (see dispatchLocal). Harmless outside an active
        // >=4-participant call — just an extra timestamp write.
        if (header.type == TYPE_AUDIO && !suppressDownward) lastAudioDeliveredAtMs = System.currentTimeMillis()

        // OCP PHASE 5.1: the low-layer branch — re-wrap as TYPE_CONFIG_LOW/
        // TYPE_FRAME_LOW ONLY toward a link that both advertised
        // CAP_SIMULCAST and (for TYPE_FRAME specifically) subscribed to
        // this srcId's LOW layer. TYPE_CONFIG_LOW mirrors TYPE_CONFIG's own
        // pre-existing "broadcast to everyone, no subscription filter"
        // shape — only the capability gate applies. A link that never
        // advertised simulcast support gets NEITHER type at all (no legacy
        // fallback exists for a brand-new type — see writeRawFrame's
        // identical gate for why).
        if (isLowLayer) {
            val lowType = if (header.type == TYPE_FRAME) TYPE_FRAME_LOW else TYPE_CONFIG_LOW
            val lowFrame = MeshFrame.encode(header.srcId, header.dstId, newTtl.toByte(), lowType, payload)
            routingTable.allExcept(fromLink.nodeId).forEach { link ->
                if (!link.supportsSimulcast) return@forEach
                if (lowType == TYPE_FRAME_LOW) {
                    // OCP PHASE 5.1: null means "never subscribed to any low
                    // stream" — unlike the HIGH list, null here is NOT
                    // "everyone" (see PeerLink.videoSubscriptionLow's doc);
                    // this is an opt-in-only stream with no legacy default
                    // to preserve, so the safe default is "send nothing."
                    val subLow = link.videoSubscriptionLow
                    if (subLow == null || header.srcId !in subLow) return@forEach
                }
                link.enqueue(lowFrame)
            }
            return
        }

        val legacyFrame = MeshFrame.encode(header.srcId, header.dstId, newTtl.toByte(), header.type, payload)
        val tsType = when (header.type) { TYPE_FRAME -> TYPE_FRAME_TS; TYPE_AUDIO -> TYPE_AUDIO_TS; else -> null }
        val tsFrame = if (captureMicros != null && tsType != null) {
            val tsPayload = ByteBuffer.allocate(8 + payload.size).putLong(captureMicros).put(payload).array()
            MeshFrame.encode(header.srcId, header.dstId, newTtl.toByte(), tsType, tsPayload)
        } else null
        // PHASE 8 TRACK C2: only TYPE_FRAME (video) is ever filtered by a
        // destination's subscription — TYPE_AUDIO and every control type,
        // including TYPE_SUBSCRIBE itself, always fan out to everyone,
        // exactly as before this track. null (never subscribed) means
        // "everyone" — see PeerLink.videoSubscription's doc.
        routingTable.allExcept(fromLink.nodeId).forEach { link ->
            if (suppressDownward && link.nodeId != parentNodeId) return@forEach
            if (header.type == TYPE_FRAME) {
                val sub = link.videoSubscription
                if (sub != null && header.srcId !in sub) return@forEach
            }
            link.enqueue(if (tsFrame != null && link.supportsFrameAge) tsFrame else legacyFrame)
        }
    }

    /** OCP PHASE 2.1: true only for TYPE_AUDIO once the GO mix is actually
     *  LIVE — [goMixLive], not the raw participant-count threshold this used
     *  to read directly. That one-line change is the entire crossfade: raw
     *  relay keeps flowing through every participant-count crossing until
     *  tickGoMix has actually produced and sent this session's first mixed
     *  frame (see tickGoMix's goMixLive.compareAndSet(false, true) call
     *  site), and resumes the INSTANT eligibility is lost (tickGoMix flips
     *  goMixLive back to false before returning) — see routeFrame's
     *  broadcast branch (the only call site) and tickGoMix (what replaces
     *  the relay this suppresses). False for every other type
     *  unconditionally. */
    private fun isGoMixReplacingBroadcastAudio(type: Byte): Boolean {
        if (type != TYPE_AUDIO) return false
        return goMixLive.get()
    }

    /** PHASE 8 TRACK C3: the one routing decision every dst-addressed send
     *  (both locally-originated, via writeRawFrame, and forwarded, via
     *  forwardUnicast) now shares. Direct neighbor (parent OR child) is
     *  always tried first — on the GO or a plain client with a flat tree,
     *  this is the ONLY branch that can ever match (routingTable holds every
     *  neighbor there is), making this function's observable behavior
     *  IDENTICAL to today's plain routingTable.get(dstId) for both of those
     *  cases; it only starts choosing between "down to a child's subtree" and
     *  "up to my parent" once an actual relay node with tree depth exists. */
    private fun nextHopFor(dstId: Long): PeerLink? {
        routingTable.get(dstId)?.let { return it }
        childOwning(dstId)?.let { return routingTable.get(it) }
        return parentNodeId?.let { routingTable.get(it) } // null on the GO — nowhere further up
    }

    /** Walks [treeParentOf] up from [dstId] looking for MY direct child whose
     *  subtree contains it — bounded by TREE_MAX_DEPTH+2 so a malformed/stale
     *  map can never spin. Returns null (never my descendant, or the map has
     *  no entry at all — true for the entire life of any call that never
     *  crosses TREE_MIN_SIZE_FOR_RELAY) rather than throwing. */
    private fun childOwning(dstId: Long): Long? {
        var cur = dstId
        var hops = 0
        while (hops < TREE_MAX_DEPTH + 2) {
            val parent = treeParentOf[cur] ?: return null
            if (parent == localNodeId) return cur
            cur = parent
            hops++
        }
        return null
    }

    /** See [forwardBroadcast]'s doc for [captureMicros]'s meaning — identical
     *  per-link dual-wrap, just against a single target instead of a fan-out. */
    private fun forwardUnicast(header: MeshFrame.Header, payload: ByteArray, captureMicros: Long? = null) {
        val target = nextHopFor(header.dstId)
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
        val tsType = when (header.type) { TYPE_FRAME -> TYPE_FRAME_TS; TYPE_AUDIO -> TYPE_AUDIO_TS; else -> null }
        if (captureMicros != null && tsType != null && target.supportsFrameAge) {
            val tsPayload = ByteBuffer.allocate(8 + payload.size).putLong(captureMicros).put(payload).array()
            target.enqueue(MeshFrame.encode(header.srcId, header.dstId, newTtl.toByte(), tsType, tsPayload))
            return
        }
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
    private fun dispatchLocal(
        header: MeshFrame.Header,
        payload: ByteArray,
        isLive: Boolean = true,
        carrierInfo: Pair<Long, Int>? = null,
        // PART A: set only by dispatchCarriedInner's verified-carried-SOS
        // branch — the signed wire timestamp's age, threaded through to
        // MeshSosManager.handleSosFrame's carriedVerifiedAgeSec. Every other
        // call site (and every other type) leaves this null, unchanged.
        carriedAgeSecOverride: Long? = null
    ) {
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
                    // PHASE 8 STEP 5: a csd arriving for a srcId that ALREADY
                    // has an active decoder means their encoder was just
                    // reconfigured — steady-state operation never resends
                    // csd on its own (drainEncoderLoop's csdSent is a
                    // one-shot-per-encoder-lifetime flag) — almost certainly
                    // a resolution-ladder tier change. The OLD decoder can
                    // never decode the NEW resolution's bitstream, so rebuild
                    // immediately via the exact same FIX 2 path setGroupTileSurface
                    // uses for a surface change, rather than buffering this
                    // into groupPendingCsd where nothing would ever consume it.
                    if (groupDecoders.containsKey(header.srcId)) {
                        groupCsdCache[header.srcId] = payload
                        if (groupTileSurfaces[header.srcId] != null) {
                            releaseGroupDecoder(header.srcId)
                            configureGroupDecoder(header.srcId, payload, requestKeyframeAfter = true)
                            if (groupDecoders.containsKey(header.srcId)) {
                                groupDecoderReady[header.srcId] = true
                                Log.d(
                                    "OFFTRACE",
                                    "MEDIA: decoder rebuilt for ${MeshFrame.hex(header.srcId)} on resolution change — keyframe requested"
                                )
                            }
                        }
                        return
                    }
                    val pending = groupPendingCsd.getOrPut(header.srcId) { mutableListOf() }.apply { add(payload) }
                    // FIX 1: persistent cache, independent of groupPendingCsd's
                    // consume-once lifecycle — see the field's own doc.
                    groupCsdCache[header.srcId] = combineByteArrays(pending)
                    return
                }
                if (!acceptMediaFrame(header.srcId)) return
                pendingCsd.add(payload)
            }
            TYPE_FRAME -> {
                val gc = groupCall
                if (gc != null) {
                    if (header.srcId !in gc.participants) return
                    // PHASE 8 STEP 4: rate-limited internally to 1/sec — cheap
                    // enough to piggyback on read-thread traffic that's
                    // already happening every video frame. May swap at most
                    // one peer's decoder per call (see evaluateTileBudget).
                    maybeEvaluateTileBudget()
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
                    // PHASE 8 STEP 3: a GO-mixed frame — UNICAST (dst=me)
                    // from my own uplink. Only tickGoMix ever sends TYPE_AUDIO
                    // unicast (every ordinary per-sender frame is BROADCAST,
                    // see audioDst), so this is a zero-ambiguity signal, not a
                    // heuristic — see buildGoMixPayload's doc.
                    if (!isGroupOwner && header.srcId == uplinkNodeId() && header.dstId == localNodeId) {
                        decodeAndPlayGoMixedAudio(payload)
                        return
                    }
                    // PHASE 3D: broadcast fan-in, same shape as group video — decode
                    // THIS sender's own stream (per-sender decoder, never shared with
                    // any other sender's) and fold the result into the local mix. GO
                    // and client run the identical code here; forwarding to everyone
                    // ELSE happens separately, upstream, in forwardBroadcast, without
                    // ever touching this function.
                    if (header.srcId !in gc.participants) return
                    // PHASE 8 STEP 3: once GO-mixing has taken over (>= 4
                    // participants), the GO only needs PCM for the current
                    // top-3 VAD speakers — decoding every sender here would
                    // reintroduce the exact O(N) decode cost this step
                    // exists to eliminate. Below the threshold (or ever, on
                    // a client — this transport's own tickGoMix never runs
                    // there) this is always false, unchanged from before.
                    // OCP PHASE 2.1: gated on goMixLive (actually live), not
                    // the raw threshold — see isGoMixReplacingBroadcastAudio's
                    // doc. While the mix is still spinning up post-crossing
                    // (or spinning down), the GO keeps decoding EVERY sender
                    // so its own local raw-mix playback below stays correct.
                    if (isGroupOwner && goMixLive.get() && header.srcId !in committedMixSpeakers) {
                        audioFrameCountRecv++
                        return
                    }
                    trackAudioRecvBytes(payload.size)
                    val codec = groupRemoteAudioCodec[header.srcId] ?: AudioCodec.PCM
                    val pcm = if (codec == AudioCodec.PCM) payload else decodeGroupAudio(header.srcId, payload)
                    if (pcm != null) {
                        groupLatestPcm[header.srcId] = pcm
                        groupLatestPcmMs[header.srcId] = System.currentTimeMillis()
                        // PHASE 8 STEP 3 / OCP PHASE 2.1: local mix-and-play
                        // runs whenever the GO mix ISN'T actually live yet —
                        // below the threshold, on a client always, or on the
                        // GO during the crossfade gap right after/before a
                        // crossing. Once goMixLive is true, tickGoMix (its
                        // own dedicated thread) owns playback — calling this
                        // here too would double-play the GO's own audio.
                        if (!isGroupOwner || !goMixLive.get()) {
                            mixAndPlayGroupAudio()
                            lastAudioDeliveredAtMs = System.currentTimeMillis()
                        }
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
            TYPE_PHRASE -> handlePhraseFrame(header, payload, carrierInfo)
            TYPE_VOICE_NOTE -> handleVoiceNoteFrame(header, payload, carrierInfo)
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
            TYPE_SOS -> meshSosManager.handleSosFrame(header, payload, isLive, carriedAgeSecOverride)
            TYPE_FIND_REQ -> meshSosManager.handleFindRequestFrame(header)
            TYPE_FIND_RESP -> meshSosManager.handleFindResponseFrame(header, payload)
            TYPE_POSITION -> meshSosManager.handlePositionFrame(header, payload)
            TYPE_SOS_ACK -> meshSosManager.handleSosAckFrame(header)
            TYPE_STORE_FWD -> carrier.handleStoreFwdFrame(header, payload)
            TYPE_SF_ACK -> carrier.handleAckFrame(header, payload)
            TYPE_GO_HEARTBEAT -> meshElection.handleGoHeartbeat(header)
            TYPE_ELECTION_STATUS -> meshElection.handleElectionStatus(header, payload)
            TYPE_KEYFRAME_REQUEST -> requestKeyFrame()
            TYPE_LINK_REPORT -> handleLinkReportFrame(header, payload)
            TYPE_LINK_PROBE -> handleLinkProbeFrame(header, payload)
            TYPE_LINK_PROBE_ACK -> handleLinkProbeAckFrame(header, payload)
            TYPE_TREE_ASSIGN -> handleTreeAssignFrame(payload)
            TYPE_UPLINK_STATUS -> handleUplinkStatusFrame(header, payload)
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
        // PHASE 3B point 10 / PHASE 8 STEP 7: a non-empty payload starting with
        // BUSY_REASON_CALL_FULL is the group-call-full rejection — originally a
        // bare 1-byte reason, now optionally followed by [current][max] (see
        // addGroupCallParticipant); the existing 0-byte payload keeps meaning
        // "busy with a different 1:1 call". `>= 1` (not `== 1`/`== 3`) so an
        // older peer's 1-byte-only rejection still decodes correctly.
        if (payload.isNotEmpty() && payload[0] == BUSY_REASON_CALL_FULL) {
            val message = if (payload.size >= 3) {
                val current = payload[1].toInt() and 0xFF
                val max = payload[2].toInt() and 0xFF
                "Group is full - $current of $max connected"
            } else {
                "Group call is full"
            }
            log("MESH: group call join rejected by ${MeshFrame.hex(header.srcId)} — $message")
            endGroupCallState("rejected — call full")
            // PHASE 8 STEP 7: rejection ends only the CALL attempt — this
            // device's mesh socket to the GO is untouched, so MeshCarrier
            // store-and-forward messaging keeps working exactly as it does
            // for any other mesh member (see MeshCarrier's class doc — its
            // queue/offer path never reads groupCall state at all).
            mainHandler.post { onGroupCallRejected?.invoke(message) }
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
        logPerSrcThrottled(header.srcId) { "MEDIA: chat recv len=${payload.size} from=${MeshFrame.hex(header.srcId)} group=$isGroup" }
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
        // PHASE 8 TRACK C4: was isGroupOwner alone — VAD's dst is (and
        // always was) uplinkNodeId(), i.e. "my current parent" (see
        // sendVad), which is the GO in today's flat topology and unchanged
        // wire bytes either way; the only thing that generalizes is WHO is
        // allowed to receive/act on one, since a relay node is now also a
        // legitimate "someone's uplink."
        if (!hasChildren()) return
        // FIX 5: 4th byte is a free-running send counter (see sendVad's doc) —
        // read only to accept the new length; never consulted for any logic,
        // it exists purely so consecutive identical (speaking, energy)
        // heartbeats never produce byte-identical signed material.
        if (payload.size != 4) {
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
            // PHASE 8 STEP 4: mirrors the GO's own ordinal assignment (see
            // addGroupCallParticipant) — ids arrives in the GO's stable
            // insertion order (broadcastParticipants' toList() on a
            // LinkedHashSet), and putIfAbsent means an id already known
            // keeps its original ordinal across later snapshots.
            ids.forEachIndexed { idx, id -> joinSequence.putIfAbsent(id, idx) }
            // PHASE 3C: anyone dropped off the roster (e.g. disconnected without an
            // explicit TYPE_CAM off) shouldn't keep a stale tile decoder or camState
            // entry around — mirrors removeGroupCallParticipant's GO-side cleanup.
            val idSet = ids.toSet()
            gc.camStates.keys.toList().forEach { id ->
                if (id !in idSet) {
                    gc.camStates.remove(id)
                    releaseGroupDecoder(id)
                    releaseGroupAudioDecoder(id)
                    // FIX 1: mirrors removeGroupCallParticipant's GO-side cleanup.
                    groupCsdCache.remove(id)
                }
            }
            log("MESH: group call participants updated, ${ids.size} member(s)")
            // PHASE 8 STEP 5: this client's own encoder adapts to the SAME
            // count the GO just used for its own — see
            // evaluateGroupCallAfterChange's identical call.
            applyResolutionLadder(ids.size)
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
            // fix: was missing entirely (fell to the else branch, 1024B) —
            // see MAX_VOICE_NOTE_PAYLOAD_BYTES's own doc for the derivation.
            TYPE_VOICE_NOTE -> MAX_VOICE_NOTE_PAYLOAD_BYTES
            TYPE_FRAME -> MAX_VIDEO_PAYLOAD_BYTES
            TYPE_AUDIO -> MAX_AUDIO_PAYLOAD_BYTES
            // OCP PHASE 3.1: same cap as their legacy counterpart, +8 for the
            // capture-micros prefix — without this, the read loop's own
            // bounds check (below) would reject every timestamped video
            // frame as "oversized" the moment it exceeds MAX_CONTROL_PAYLOAD_BYTES(1024).
            TYPE_FRAME_TS -> MAX_VIDEO_PAYLOAD_BYTES + 8
            TYPE_AUDIO_TS -> MAX_AUDIO_PAYLOAD_BYTES + 8
            // OCP PHASE 5.1: the low layer is a SMALLER stream (320x240 vs
            // up to 720p+) but reuses the same caps as its high counterpart
            // rather than a tighter one — simplest correct bound, and this
            // is a diagnostic ceiling (dropping oversized frames), not a
            // real per-call budget.
            TYPE_CONFIG_LOW -> MAX_CONFIG_PAYLOAD_BYTES
            TYPE_FRAME_LOW -> MAX_VIDEO_PAYLOAD_BYTES
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
        logCapabilityLine()
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
        logCapabilityLine()
        // FIX 1: see the matching comment in startSendersForMode.
        callActive.set(true)
        // PHASE 3D: no more 1:1-shaped opusDecodeQueue/MediaOpusDecode thread here —
        // group audio decode is per-sender and driven inline off dispatchLocal (see
        // decodeGroupAudio), same as group video's decoders.
        setupAudioRouting()
        startAudioSender()
    }

    /** PHASE 8 TRACK C4: was GO-only — now also runs on any relay node (a
     *  no-op, safe to call unconditionally, on a plain leaf: hasChildren() is
     *  false there for the entire life of any call that never crosses
     *  TREE_MIN_SIZE_FOR_RELAY, which is every 2/3-device call forever).
     *  Idempotent: creates and starts the VAD-ranking-only [GroupCallMixer]
     *  — its ONLY remaining job is feeding this class's own 2-second
     *  speaker-highlight debounce (see [onRawActiveSpeakersChanged]); it no
     *  longer decodes, mixes, or distributes audio (see that class's doc for why —
     *  the per-tick decode-all loop this used to run was the actual source of the
     *  GO's overrun/degradation problem). */
    private fun startGroupCallMixer() {
        if (!isGroupOwner && !hasChildren()) return
        if (groupCallMixer != null) return
        val mixer = GroupCallMixer(
            localNodeId = localNodeId,
            onActiveSpeakersChanged = { ordered -> onRawActiveSpeakersChanged(ordered) }
        )
        mixer.start()
        groupCallMixer = mixer
        startGoMixTicker()
    }

    /** GO-only, point 6: implements the 2-second continuous-lead debounce on top of
     *  GroupCallMixer's raw (instantaneous) top-speaker signal, so a brief crosstalk
     *  blip doesn't flap the one video stream back and forth. A host pin (see
     *  [requestPin]) overrides this entirely until cleared. */
    private fun onRawActiveSpeakersChanged(rankedActive: List<Long>) {
        // PHASE 8 STEP 3: same raw top-3 signal also feeds the GO-mix speaker
        // hysteresis (evaluateMixSpeakerHysteresis) — read on the dedicated
        // goMixHandler thread, hence @Volatile rather than plumbing yet
        // another callback through GroupCallMixer for the identical data.
        rawMixSpeakers = rankedActive
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
        // PHASE 8 STEP 4: this fires identically on every device (GO's own
        // debounce decision, or a client applying an inbound TYPE_SPEAKER —
        // see this function's call sites), so "most recently spoke" for the
        // tile budget's selection order is populated everywhere without any
        // new wire traffic — it rides the existing speaker-highlight signal.
        if (nodeId != null) lastSpokeAtMs[nodeId] = System.currentTimeMillis()
        log("MEDIA: group call speaker highlight -> ${nodeId?.let { MeshFrame.hex(it) } ?: "none"} pinned=$pinned")
        mainHandler.post { onGroupCallSpeaker?.invoke(nodeId, pinned) }
    }

    // ── PHASE 8 STEP 5: resolution ladder ───────────────────────────────────

    /** Called whenever this device's participant count is known to have
     *  changed (see call sites: evaluateGroupCallAfterChange on the GO,
     *  handleParticipantsFrame on a client) — every device runs this
     *  independently, computing the SAME tier from the SAME participant
     *  count it already tracks locally (see [GroupCallState.participants]),
     *  so no explicit "GO broadcasts the ladder tier" wire message is
     *  needed: TYPE_PARTICIPANTS already IS that broadcast (its whole
     *  payload is the participant list this function's count comes from).
     *  A tier that hasn't changed is a no-op; a genuine change reconfigures
     *  ONLY if this device currently has an active outgoing encoder — a
     *  device with no camera on just adopts the new WIDTH/HEIGHT/FPS/BITRATE
     *  for whenever it next starts one. */
    /** OCP PHASE 5.4: [thermalForcedTier] (set by [applyThermalStatus]),
     *  when non-null, OVERRIDES the participant-count ladder entirely —
     *  the single point both mechanisms funnel through, so a participant
     *  join/leave arriving mid-throttle can never silently undo it, and
     *  clearing the override (thermal back to NONE/LIGHT) naturally falls
     *  through to whatever the participant count says it should be,
     *  1:1 calls included (ladderTierFor(0) resolves to LADDER_TIER1,
     *  exactly restoring a 1:1 call's untouched default). */
    private fun applyResolutionLadder(participantCount: Int) {
        val tier = thermalForcedTier ?: ladderTierFor(participantCount)
        if (tier === currentLadderTier) return
        currentLadderTier = tier
        WIDTH = tier.width
        HEIGHT = tier.height
        FPS = tier.fps
        BITRATE = tier.bitrate
        Log.d("OFFTRACE", "SCALE: participants=$participantCount videoBudget=$tileBudget ladder=${tier.width}x${tier.height}@${tier.fps}")
        if (encoder != null) reconfigureEncoderForLadderChange()
    }

    /** Full stop/start of THIS device's own camera+encoder against the
     *  now-updated WIDTH/HEIGHT/FPS/BITRATE — reuses releaseCamera/
     *  releaseEncoder/startEncoderThenCamera exactly as-is (the same
     *  functions every normal call start/stop already goes through), on a
     *  dedicated one-shot thread so the (potentially slow) Camera2
     *  close()/open() cycle never blocks whichever thread noticed the tier
     *  change (the read thread, in the common case — see
     *  applyResolutionLadder's call sites). A freshly (re)started encoder
     *  ALWAYS re-sends csd on its first output (drainEncoderLoop's csdSent
     *  is thread-local, reset every call) and its first frame is always a
     *  sync frame by construction — satisfying "re-send csd plus an IDR"
     *  without any extra logic; requestKeyFrame() below is a documented
     *  belt-and-suspenders on top of that. On the RECEIVING end, the fresh
     *  csd arriving for an srcId that already has an active decoder is what
     *  triggers every remote peer's own rebuild — see dispatchLocal's
     *  TYPE_CONFIG branch. */
    private fun reconfigureEncoderForLadderChange() {
        Thread({
            try {
                releaseCamera()
                releaseEncoder()
                startEncoderThenCamera()
                requestKeyFrame()
            } catch (e: Exception) {
                if (running.get()) logE("OFFTRACE: SCALE: ladder reconfigure failed: ${e.message}")
            }
        }, "MediaLadderReconfig").guarded().start()
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
            val drainThread = Thread({ drainEncoderLoop() }, "MediaEncoderDrain").guarded()
            encoderDrainThread = drainThread
            drainThread.start()
            // OCP PHASE 5.1: the low encoder (if this call currently needs
            // one — see shouldRunLowEncoder) is created and started BEFORE
            // openCamera so its input Surface exists in time to be added as
            // a THIRD simultaneous capture-session target, same pattern
            // startCaptureSession already uses for the local preview
            // Surface. A failure here is logged and swallowed, never fatal
            // to the call — the low layer is additive; losing it just means
            // grid tiles fall back to a still frame (Phase 4's existing
            // behavior), never a broken call.
            if (shouldRunLowEncoder()) startLowEncoder()
            openCamera(inputSurface)
        } catch (e: Exception) {
            if (running.get()) reportError("encoder setup: ${e.message}")
        }
    }

    /** OCP PHASE 5.1: the low encoder runs only for an active GROUP call
     *  past SIMULCAST_PARTICIPANT_THRESHOLD — a 1:1 call, or a group call
     *  at or below the threshold, never starts it (this function returning
     *  false there is what keeps every smaller call's camera pipeline
     *  byte-for-byte what it was before this phase). */
    private fun shouldRunLowEncoder(): Boolean {
        val gc = groupCall ?: return false
        return gc.participants.size > SIMULCAST_PARTICIPANT_THRESHOLD
    }

    /** Mirrors [startEncoderThenCamera]'s encoder-creation half exactly, at
     *  LOW_LAYER_WIDTH/HEIGHT/BITRATE/FPS. Never calls openCamera itself —
     *  the caller (startEncoderThenCamera) adds [lowEncoderInputSurface] as
     *  an extra capture-session target alongside the high encoder's. */
    private fun startLowEncoder() {
        try {
            val enc = MediaCodec.createEncoderByType("video/avc")
            val fmt = MediaFormat.createVideoFormat("video/avc", LOW_LAYER_WIDTH, LOW_LAYER_HEIGHT).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, LOW_LAYER_BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, LOW_LAYER_FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = enc.createInputSurface()
            lowEncoderInputSurface = inputSurface
            enc.start()
            lowEncoder = enc
            lowEncoderRunning = true
            log("MEDIA: low-layer encoder started (${LOW_LAYER_WIDTH}x$LOW_LAYER_HEIGHT)")
            val drainThread = Thread({ drainLowEncoderLoop() }, "MediaLowEncoderDrain").guarded()
            lowEncoderDrainThread = drainThread
            drainThread.start()
        } catch (e: Exception) {
            logW("OFFTRACE: SCALE: low encoder setup failed (${e.message}) — grid falls back to Phase 4 stills")
            releaseLowEncoder()
        }
    }

    /** Mirrors [drainEncoderLoop] exactly, sending TYPE_CONFIG_LOW/
     *  TYPE_FRAME_LOW instead of TYPE_CONFIG/TYPE_FRAME — writeRawFrame's
     *  broadcast branch (see its OCP PHASE 5.1 gate) is what actually
     *  restricts these two brand-new types to CAP_SIMULCAST peers only. */
    private fun drainLowEncoderLoop() {
        val info = MediaCodec.BufferInfo()
        val pendingCsdOut = mutableListOf<ByteArray>()
        var csdSent = false
        var frameCount = 0
        try {
            while (running.get() && callActive.get() && lowEncoderRunning) {
                val enc = lowEncoder ?: break
                if (!lowEncoderRunning) break
                // B2: see drainEncoderLoop's identical guard — releaseLowEncoder
                // gates on this before calling enc.stop()/release().
                lowEncoderDrainInsideCodec = true
                val idx = try { enc.dequeueOutputBuffer(info, 10_000L) } finally { lowEncoderDrainInsideCodec = false }
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
                    info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 -> pendingCsdOut.add(bytes)
                    else -> {
                        if (!csdSent && pendingCsdOut.isNotEmpty()) {
                            writeFrame(videoDst(), TYPE_CONFIG_LOW, combineByteArrays(pendingCsdOut))
                            csdSent = true
                        }
                        writeFrame(videoDst(), TYPE_FRAME_LOW, bytes)
                        frameCount++
                        if (frameCount % 30 == 0) log("MEDIA: sent low-layer frame $frameCount bytes=${bytes.size}")
                    }
                }
            }
        } catch (e: Exception) {
            handleMediaLoopException("low encoder drain", e)
        }
        log("MEDIA: low encoder drain thread exiting")
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
                // B2: flag cleared in the finally regardless of outcome — see
                // waitForCodecFree/releaseEncoder, which gate on this before
                // ever calling enc.stop()/release() from another thread.
                encoderDrainInsideCodec = true
                val idx = try { enc.dequeueOutputBuffer(info, 10_000L) } finally { encoderDrainInsideCodec = false }
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
                    // B2: AudioRecord.read() is a native blocking call that
                    // Thread.interrupt() cannot unblock — releaseAudio() gates
                    // on this flag before ever calling audioRecord.stop()/
                    // release() from another thread (see waitForCodecFree).
                    audioSendInsideRecord = true
                    val n = try { rec.read(chunk, 0, chunk.size) } finally { audioSendInsideRecord = false }
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
                            // PHASE 8 TRACK C4: was if(isGroupOwner){local}else{send}
                            // — a relay node now needs BOTH: its own local
                            // mixer (for its own children's ranking) AND a
                            // send to its own parent (so its speaking status
                            // propagates further up the tree). On the GO
                            // (no uplink) sendVad is a no-op via
                            // uplinkNodeId()'s own null guard, so this is
                            // still exactly "local only" there — identical to
                            // today. On a plain leaf, hasChildren() is always
                            // false, so this is still exactly "send only" —
                            // identical to today for the entire life of any
                            // 2/3-device call.
                            if (hasChildren()) groupCallMixer?.updateVad(localNodeId, speaking, energy)
                            if (!isGroupOwner) sendVad(speaking, energy)
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
        }, "MediaAudioSend").guarded()
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

    // FIX 5: MeshSigner's seen-signature replay cache rejects an EXACT repeat
    // of a signed frame's material within its window — VAD's payload used to
    // carry nothing that changes between periodic heartbeat resends (see
    // VAD_HEARTBEAT_MS=300ms), so two genuine heartbeats landing in the same
    // second (MeshSigner's timestamp granularity) with the same (speaking,
    // energy) produced byte-identical signed material and were wrongly
    // dropped as replays ("SIG: replay rejected type=15 ... skew=0s"). A
    // free-running counter appended to the payload makes every send unique
    // WITHOUT touching MeshSigner's shared replay-cache logic (used by every
    // other signed type) or weakening VAD's own replay protection — chosen
    // over exempting type 15 from the cache because it fixes the actual root
    // cause (a payload with no varying state) rather than special-casing the
    // security layer, and matches this codebase's existing convention of a
    // msgSeq/counter field on every other periodically-resent frame type
    // (see MeshLocation.msgSeq, MeshSosManager.nextMsgSeq).
    private var vadSendCounter: Byte = 0

    /** PHASE 3B: client -> GO only (the GO updates its own VAD state directly via
     *  GroupCallMixer.updateVad — see the call site above). */
    private fun sendVad(speaking: Boolean, energy: Int) {
        val go = uplinkNodeId() ?: return
        val buf = ByteBuffer.allocate(4)
        buf.put(if (speaking) 1 else 0)
        buf.putShort(energy.toShort())
        buf.put(vadSendCounter)
        vadSendCounter = (vadSendCounter + 1).toByte()
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
        }, "MediaOpusEncode").guarded()
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
        // B2: this whole function's body is codec I/O on opusEncodeThread —
        // releaseAudio() gates on this flag before calling audioEncoder.stop()/
        // release() from another thread (see waitForCodecFree).
        opusEncodeInsideCodec = true
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
        } finally {
            opusEncodeInsideCodec = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(encoderSurface: Surface) {
        val ht = HandlerThread("MediaCameraThread").also { it.guarded().start() }
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

    /** OCP PHASE 5.1: [allowLowLayer] defaults true — [startEncoderThenCamera]
     *  always calls this that way; a config failure with the low encoder's
     *  Surface included (some Camera2 HALs cap concurrent stream count —
     *  UNVERIFIED on real hardware, see this phase's report) retries ONCE
     *  with allowLowLayer=false, which is a strict subset of the previous
     *  target list and therefore never fails for a NEW reason. The low
     *  layer being unavailable this session degrades to Phase 4's existing
     *  still-frame fallback — never a broken call. */
    private fun startCaptureSession(camera: CameraDevice, encoderSurface: Surface, allowLowLayer: Boolean = true) {
        try {
            // PHASE 3C: this device's own grid tile (group video only — null on a
            // 1:1 call, see setLocalPreviewSurface) is a SECOND simultaneous output
            // target on the same capture session, not a separate camera open —
            // Camera2 supports multiple targets from one repeating request.
            val previewSurface = localPreviewSurface
            // OCP PHASE 5.1: a THIRD simultaneous target, same pattern —
            // null whenever the low encoder isn't running (every call
            // smaller than SIMULCAST_PARTICIPANT_THRESHOLD, or this is the
            // post-failure retry), a pure no-op for every existing call shape.
            val lowSurface = if (allowLowLayer) lowEncoderInputSurface else null
            val targets = listOfNotNull(encoderSurface, previewSurface, lowSurface)
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
                                    lowSurface?.let { addTarget(it) }
                                }
                                .build()
                            session.setRepeatingRequest(req, null, cameraHandler)
                            val hasPreview = previewSurface != null
                            log("MEDIA: camera capture running (preview=$hasPreview low=${lowSurface != null})")
                        } catch (e: Exception) {
                            if (running.get()) reportError("capture request: ${e.message}")
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (allowLowLayer && lowSurface != null) {
                            logW("OFFTRACE: SCALE: capture session config failed WITH low-layer target — retrying without it")
                            releaseLowEncoder()
                            startCaptureSession(camera, encoderSurface, allowLowLayer = false)
                            return
                        }
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

    // ── PHASE 8 STEP 4: video tile decoder budget ───────────────────────────────

    /** Queries the platform's actual AVC decoder instance ceiling — this
     *  device's OWN hardware limit, not an assumption. Takes the SMALLEST
     *  maxSupportedInstances reported across every video/avc decoder this
     *  device exposes (usually just one, but some devices list more than
     *  one implementation of the same mime type). Falls back to
     *  DECODER_PROBE_FALLBACK only if the platform query itself throws or
     *  reports nothing usable — see this app's own established "probe, don't
     *  assume; state the fallback plainly when probing genuinely isn't
     *  possible" convention (same spirit as the WFD client-limit note in
     *  addGroupCallParticipant). */
    private fun probeMaxAvcDecoderInstances(): Int = probeMaxAvcInstances(isEncoder = false)

    /** OCP PHASE 0.1: mirrors [probeMaxAvcDecoderInstances] exactly, for
     *  encoder instances instead — used only for the CAP diagnostic line
     *  today (this app runs a single local encoder; nothing currently
     *  arbitrates against this the way tileBudget arbitrates decoders). */
    private fun probeMaxAvcEncoderInstances(): Int = probeMaxAvcInstances(isEncoder = true)

    private fun probeMaxAvcInstances(isEncoder: Boolean): Int {
        return try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            var min = Int.MAX_VALUE
            for (info in list.codecInfos) {
                if (info.isEncoder != isEncoder) continue
                if (!info.supportedTypes.any { it.equals("video/avc", ignoreCase = true) }) continue
                val caps = try { info.getCapabilitiesForType("video/avc") } catch (e: Exception) { continue }
                val n = caps.maxSupportedInstances
                if (n > 0) min = minOf(min, n)
            }
            if (min == Int.MAX_VALUE) DECODER_PROBE_FALLBACK else min
        } catch (e: Exception) {
            val kind = if (isEncoder) "encoder" else "decoder"
            logW("OFFTRACE: SCALE: $kind probe failed (${e.message}) — using fallback=$DECODER_PROBE_FALLBACK")
            DECODER_PROBE_FALLBACK
        }
    }

    /** Called once per session (see [start]). OCP PHASE 4.3: tileBudget =
     *  (probed-1) coerced into TILE_BUDGET_MIN..TILE_BUDGET_CEILING (2..8) —
     *  the "-1" leaves headroom below the platform's own advertised ceiling
     *  (this device's 1:1-call decoder, and MediaCodec allocation failures
     *  right at the advertised max are a known real-world quirk on some
     *  OEMs); the 2..8 range replaces the old flat "capped at 4 regardless"
     *  — a stronger device now actually gets to use more of what it probed.
     *  maxLiveCameras derives from the SAME probe, coerced into
     *  MIN_LIVE_CAMERAS..MAX_GROUP_PARTICIPANTS (2..8) — likewise no longer
     *  a flat 4 for every device regardless of hardware. */
    private fun initTileBudget() {
        val probed = probeMaxAvcDecoderInstances()
        probedDecoderCount = probed
        probedEncoderCount = probeMaxAvcEncoderInstances()
        tileBudget = deriveTileBudget(probed)
        maxLiveCameras = deriveMaxLiveCameras(probed)
        Log.d("OFFTRACE", "SCALE: decoder probe maxInstances=$probed budget=$tileBudget maxLiveCameras=$maxLiveCameras")
    }

    /** OCP PHASE 0.1: current PowerManager thermal status as a short string
     *  — getCurrentThermalStatus() is API 29+ (this app's minSdk is 26), so
     *  below that this is always "n/a" (no on-device thermal signal exists
     *  pre-Q; Phase 5.4's listener is likewise a no-op there). Never throws —
     *  a diagnostic read must not risk the caller (call-start logging). */
    private fun currentThermalStatusString(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "n/a"
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            thermalStatusString(pm.currentThermalStatus)
        } catch (e: Exception) {
            "n/a"
        }
    }

    // OCP PHASE 5.4: this device's own thermal-status listener — API 29+
    // only (Build.VERSION.SDK_INT guarded at every call site, matching
    // currentThermalStatusString's own precedent); a pure no-op object
    // below that. Registered once per session (see start()), unregistered
    // in stop() — see registerThermalListener/unregisterThermalListener.
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    /** Fired (main thread) with a human-readable reason the moment SEVERE
     *  thermal forces this device's own camera off — the Activity uses this
     *  to show a visible "camera off — device too hot" message rather than
     *  a silently blank/frozen local tile. Audio and SOS are never touched
     *  by any thermal status — see [applyThermalStatus]'s doc. */
    var onThermalCameraForcedOff: ((reason: String) -> Unit)? = null

    private fun registerThermalListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val listener = PowerManager.OnThermalStatusChangedListener { status -> applyThermalStatus(status) }
            pm.addThermalStatusListener(listener)
            thermalListener = listener
            applyThermalStatus(pm.currentThermalStatus) // pick up an already-hot device at call start, not just future transitions
        } catch (e: Exception) {
            logW("OFFTRACE: THERMAL: listener registration failed: ${e.message}")
        }
    }

    private fun unregisterThermalListener() {
        val listener = thermalListener ?: return
        thermalListener = null
        thermalForcedTier = null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.removeThermalStatusListener(listener)
        } catch (e: Exception) {
            logW("OFFTRACE: THERMAL: listener removal failed: ${e.message}")
        }
    }

    /** OCP PHASE 5.4: MODERATE throttles this device's own encoder to
     *  LADDER_TIER3 (320x240@15fps, the same cheap tier the participant-
     *  count ladder already uses at scale — see [applyResolutionLadder]'s
     *  doc for how the two mechanisms share one override slot without
     *  racing) — a throttled encoder still running at full resolution
     *  would reproduce exactly the lag Phase 1/3 removed. SEVERE goes
     *  further: this device's own camera is turned off outright (group
     *  calls only — see this function's own doc on the 1:1 gap) via the
     *  SAME [setGroupCallCameraOn] path a manual toggle uses, so every
     *  other participant sees an ordinary, correctly-announced camera-off,
     *  not a frozen tile. Below MODERATE (NONE/LIGHT), any override clears
     *  and the ladder reverts to whatever the participant count says.
     *  Audio/SOS are NEVER touched by any branch here — this function only
     *  ever calls into camera/encoder state. */
    private fun applyThermalStatus(status: Int) {
        val statusStr = thermalStatusString(status)
        when {
            status >= PowerManager.THERMAL_STATUS_SEVERE -> {
                thermalForcedTier = LADDER_TIER3
                val gc = groupCall
                val hadCameraOn = gc != null && gc.camStates[localNodeId] == true
                if (hadCameraOn) {
                    setGroupCallCameraOn(false)
                    mainHandler.post { onThermalCameraForcedOff?.invoke("Device is too hot — camera turned off to cool down") }
                } else if (gc == null && encoder != null) {
                    // 1:1 VIDEO call: no clean mid-call camera-off protocol
                    // exists in this app (see this function's doc) — best
                    // effort is the same throttle MODERATE applies.
                    applyResolutionLadder(0)
                }
                Log.d("OFFTRACE", "THERMAL: status=$statusStr cameraForcedOff=$hadCameraOn")
            }
            status == PowerManager.THERMAL_STATUS_MODERATE -> {
                thermalForcedTier = LADDER_TIER3
                // STABILITY AUDIT 1b: [groupCall] is @Volatile and can be
                // nulled by endGroupCallState() on a different thread (e.g.
                // the read-loop thread, once the participant count drops
                // below 2) between this check and a re-read — capture a
                // local val, exactly as the SEVERE branch above already does.
                val gc = groupCall
                if (gc != null) applyResolutionLadder(gc.participants.size) else if (encoder != null) applyResolutionLadder(0)
                Log.d("OFFTRACE", "THERMAL: status=$statusStr encoderThrottled=true")
            }
            else -> {
                if (thermalForcedTier != null) {
                    thermalForcedTier = null
                    val gc = groupCall
                    if (gc != null) applyResolutionLadder(gc.participants.size) else if (encoder != null) applyResolutionLadder(0)
                }
                Log.d("OFFTRACE", "THERMAL: status=$statusStr encoderThrottled=false")
            }
        }
    }

    /** OCP PHASE 0.1: this device's own current live-camera count — the
     *  group-call camState map when in a group call, else 1 iff this
     *  device's own encoder is currently running a VIDEO-mode 1:1 call. */
    private fun currentLiveCameraCount(): Int {
        groupCall?.let { gc -> return gc.camStates.count { it.value } }
        return if (resolvedMode == CallMode.VIDEO && encoder != null) 1 else 0
    }

    /** OCP PHASE 0.1: "Log on every device at call start" — called from both
     *  [startSendersForMode] (1:1) and [startGroupCallAudio] (group). Reuses
     *  the session's cached probe results ([probedDecoderCount]/
     *  [probedEncoderCount], set once in [initTileBudget]) rather than
     *  re-querying MediaCodecList per call. */
    private fun logCapabilityLine() {
        Log.d(
            "OFFTRACE",
            "CAP: probedDecoders=$probedDecoderCount probedEncoders=$probedEncoderCount " +
                "tileBudget=$tileBudget liveCameras=${currentLiveCameraCount()} thermal=${currentThermalStatusString()}"
        )
    }

    // PHASE 8 TRACK C2: null = never sent yet — distinct from emptySet() (a
    // real, sent "no video wanted" state), same null-vs-empty distinction as
    // PeerLink.videoSubscription on the receiving end.
    private var lastSentSubscription: Set<Long>? = null
    // OCP PHASE 5.1: same shape, for the low-layer list this device sends.
    private var lastSentSubscriptionLow: Set<Long>? = null

    /** Sends TYPE_SUBSCRIBE to this device's current uplink (see
     *  uplinkNodeId()) whenever [desiredHigh]/[desiredLow] actually change —
     *  a no-op on the GO (uplinkNodeId() is null there; the GO enforces its
     *  own tile budget locally with no wire message needed) and a no-op for
     *  any call small enough that neither differs from its last-sent value,
     *  which is exactly what keeps a 2/3-device call from ever sending this
     *  frame at all. OCP PHASE 5.1: [desiredLow] additively extends the
     *  wire payload — see handleSubscribeFrame's doc on the receiving end. */
    private fun maybeSendVideoSubscription(desiredHigh: Set<Long>, desiredLow: Set<Long> = emptySet()) {
        if (desiredHigh == lastSentSubscription && desiredLow == lastSentSubscriptionLow) return
        val dst = uplinkNodeId() ?: return
        lastSentSubscription = desiredHigh
        lastSentSubscriptionLow = desiredLow
        val buf = ByteBuffer.allocate(1 + desiredHigh.size * 8 + 1 + desiredLow.size * 8)
        buf.put(desiredHigh.size.coerceIn(0, 255).toByte())
        desiredHigh.forEach { buf.putLong(it) }
        buf.put(desiredLow.size.coerceIn(0, 255).toByte())
        desiredLow.forEach { buf.putLong(it) }
        writeFrame(dst, TYPE_SUBSCRIBE, buf.array())
    }

    /** OCP PHASE 0.2: one LAT line per peer per second, throttled via
     *  [lastLatLogAtMs] — called from every incoming frame (see routeFrame),
     *  same piggyback pattern as [maybeEvaluateTileBudget]. qd is this
     *  DEVICE's own shared opusDecodeQueue (there is exactly one, not one
     *  per peer — a 1:1-call-only queue; group calls decode per-sender
     *  inline off dispatchLocal instead, see decodeGroupAudio), reported
     *  identically on every peer's row since it isn't truly per-peer state;
     *  every other field (qv/qa/dropV/dropA) IS genuinely this peer's own
     *  PeerLink state. rttMs prefers this node's own measured RTT to its
     *  uplink when [link] IS that uplink, else the GO-side aggregate from
     *  LINK_REPORT (rttToUplinkByNode) reported BY that peer; -1 if neither
     *  is known yet (e.g. before the first LINK_PROBE/ACK round trip).
     *  ageMs is [AGE_MS_NOT_YET_IMPLEMENTED] until Phase 3 wires a real
     *  per-frame timestamp — see that phase's TYPE_FRAME_TS/TYPE_AUDIO_TS. */
    private fun maybeLogPeerLatency(link: PeerLink) {
        val now = System.currentTimeMillis()
        val last = lastLatLogAtMs[link.nodeId] ?: 0L
        if (!shouldLogLatNow(last, now)) return
        lastLatLogAtMs[link.nodeId] = now
        val rtt = rttToUplinkByNode[link.nodeId]
            ?: (if (link.nodeId == uplinkNodeId()) rttToOwnUplinkMs else null)
            ?: -1L
        Log.d(
            "OFFTRACE",
            "LAT: peer=${MeshFrame.hex(link.nodeId)} " +
                "qv=${link.videoQueueDepth()}/${link.videoQueueCapacity()} " +
                "qa=${link.audioQueueDepth()}/${link.audioQueueCapacity()} " +
                "qd=${opusDecodeQueue.size} " +
                "dropV=${link.totalVideoDropsCount()} dropA=${link.totalAudioDropsCount()} " +
                "ageMs=${lastKnownAgeMs[link.nodeId] ?: AGE_MS_NOT_YET_IMPLEMENTED} rttMs=$rtt"
        )
    }

    /** Rate-limited to TILE_BUDGET_EVAL_INTERVAL_MS — called opportunistically
     *  off the existing group-video TYPE_FRAME dispatch path (see
     *  dispatchLocal), not a dedicated thread; cheap enough (a sort over at
     *  most MAX_GROUP_PARTICIPANTS-1 candidates) to piggyback on read-thread
     *  traffic that is already happening every frame, same pattern as this
     *  file's other throttled-but-inline checks. Runs on EVERY device (GO
     *  and client alike) — decoder limits are per-device hardware, not a
     *  GO-only concept like STEP 3's audio mixing. */
    private fun maybeEvaluateTileBudget() {
        val now = System.currentTimeMillis()
        if (now - lastTileBudgetEvalMs < TILE_BUDGET_EVAL_INTERVAL_MS) return
        lastTileBudgetEvalMs = now
        evaluateTileBudget(now)
        maybeToggleLowEncoder()
    }

    // OCP PHASE 5.1: reentrancy guard — reconfigureEncoderForLadderChange
    // runs on its own background thread (camera close/open is slow); this
    // prevents a second eval tick from queuing a duplicate restart while
    // one is still in flight.
    private val lowEncoderReconfigureInFlight = AtomicBoolean(false)

    /** Piggybacks on [maybeEvaluateTileBudget]'s existing 1s throttle — this
     *  device's own participant-count crossing is not a per-frame event,
     *  every-second is more than enough responsiveness. Only acts while
     *  THIS device's own camera is actually on ([cameraOn]) — an audio-only
     *  participant has no capture session to add a low-encoder target to at
     *  all, and starting/stopping an encoder with no camera pipeline behind
     *  it would be meaningless. */
    private fun maybeToggleLowEncoder() {
        if (!cameraOn) return
        if (shouldRunLowEncoder() == lowEncoderRunning) return
        if (!lowEncoderReconfigureInFlight.compareAndSet(false, true)) return
        // Same shape as reconfigureEncoderForLadderChange (camera close/open
        // on a dedicated one-shot thread, never the caller's) — inlined
        // rather than calling that shared function directly so this flag's
        // clear-on-completion (not clear-on-thread-launch) actually guards
        // the whole operation, including a restart slower than the 1s
        // outer throttle.
        Thread({
            try {
                releaseCamera()
                releaseEncoder()
                startEncoderThenCamera()
                requestKeyFrame()
            } catch (e: Exception) {
                if (running.get()) logE("OFFTRACE: SCALE: low-layer toggle reconfigure failed: ${e.message}")
            } finally {
                lowEncoderReconfigureInFlight.set(false)
            }
        }, "MediaLowLayerToggle").guarded().start()
    }

    /** Selection order: pinned peer first, then currently speaking
     *  ([GroupCallState.activeSpeakerId]), then most recently spoke (see
     *  [lastSpokeAtMs], populated by [applySpeakerChange] on every device),
     *  then join order ([joinSequence]) as the final tie-break. Only ever
     *  swaps ONE peer per call (oldest-held-slot out, highest-ranked-missing
     *  in) and only past TILE_SWAP_HYSTERESIS_MS, so a brief VAD blip can't
     *  thrash decoders — the next eval tick (1s later) picks up any further
     *  swap still needed. Reuses configureGroupDecoder/releaseGroupDecoder
     *  exactly (the FIX 2 rebuild path), so success/failure/degraded
     *  bookkeeping is identical to every other decoder lifecycle event. */
    private fun evaluateTileBudget(now: Long) {
        val gc = groupCall ?: return
        val camOnPeers = gc.camStates.filterValues { it }.keys.filter { it != localNodeId }
        val currentlyDecoded = groupDecoders.keys.toSet()

        // OCP PHASE 5.1: once a group call actually needs grid thumbnails
        // (participants > SIMULCAST_PARTICIPANT_THRESHOLD), every visible
        // tile EXCEPT the pinned one and the active speaker subscribes to
        // the LOW layer instead of HIGH — real bandwidth savings for every
        // "just visible in the grid" tile, decoder count unaffected either
        // way (see splitHighLow's doc).
        val simulcastActive = gc.participants.size > SIMULCAST_PARTICIPANT_THRESHOLD
        val pin = pinnedTilePeer

        if (camOnPeers.size <= tileBudget) {
            // Under budget — nothing excluded; clear any stale exclusion
            // markers left over from a participant count that has since dropped.
            if (tileBudgetExcluded.isNotEmpty()) {
                val cleared = tileBudgetExcluded.toList()
                tileBudgetExcluded.clear()
                cleared.forEach { id -> mainHandler.post { onGroupTileBudgetChanged?.invoke(id, false) } }
            }
            // PHASE 8 TRACK C2: under budget — this device wants every
            // currently-camera-on peer's video, nothing to restrict on
            // DECODER COUNT — OCP PHASE 5.1 still splits which LAYER each
            // one gets.
            val (high, low) = splitHighLow(camOnPeers, pin, gc.activeSpeakerId, simulcastActive)
            maybeSendVideoSubscription(high, low)
            return
        }

        val ranked = camOnPeers.sortedWith(
            compareByDescending<Long> { it == pin }
                .thenByDescending { it == gc.activeSpeakerId }
                .thenByDescending { lastSpokeAtMs[it] ?: 0L }
                .thenBy { joinSequence[it] ?: Int.MAX_VALUE }
        )
        val want = ranked.take(tileBudget).toSet()
        // PHASE 8 TRACK C2: over budget — subscribe to exactly the ranked set
        // this device actually intends to decode, regardless of whether a
        // decoder swap physically executes this tick (the hysteresis/surface-
        // readiness checks below only gate the local codec swap, not what
        // this device is willing to receive). OCP PHASE 5.1: split within
        // that same decoded set by layer — see above.
        val (wantHigh, wantLow) = splitHighLow(ranked.take(tileBudget), pin, gc.activeSpeakerId, simulcastActive)
        maybeSendVideoSubscription(wantHigh, wantLow)

        val newlyExcluded = camOnPeers.filter { it !in want && it !in tileBudgetExcluded }
        val newlyIncluded = tileBudgetExcluded.filter { it in want }
        (newlyExcluded + newlyIncluded).forEach { id ->
            val excluded = id in newlyExcluded
            if (excluded) tileBudgetExcluded.add(id) else tileBudgetExcluded.remove(id)
            mainHandler.post { onGroupTileBudgetChanged?.invoke(id, excluded) }
        }

        val toDrop = currentlyDecoded - want
        val toAdd = want - currentlyDecoded
        if (toDrop.isEmpty() || toAdd.isEmpty()) return
        val dropCandidate = toDrop.minByOrNull { tileSwapAtMs[it] ?: 0L } ?: return
        if (now - (tileSwapAtMs[dropCandidate] ?: 0L) < TILE_SWAP_HYSTERESIS_MS) return
        val addCandidate = toAdd.firstOrNull() ?: return
        val csd = groupCsdCache[addCandidate] ?: return
        if (groupTileSurfaces[addCandidate] == null) return

        releaseGroupDecoder(dropCandidate)
        configureGroupDecoder(addCandidate, csd, requestKeyframeAfter = true)
        if (groupDecoders.containsKey(addCandidate)) {
            tileSwapAtMs[addCandidate] = now
            Log.d(
                "OFFTRACE",
                "SCALE: tile swap out=${MeshFrame.hex(dropCandidate)} in=${MeshFrame.hex(addCandidate)} reason=budget"
            )
        }
    }

    private fun resetTileBudgetState() {
        lastSpokeAtMs.clear()
        joinSequence.clear()
        joinSequenceCounter.set(0)
        pinnedTilePeer = null
        tileSwapAtMs.clear()
        lastTileBudgetEvalMs = 0L
        if (tileBudgetExcluded.isNotEmpty()) {
            val cleared = tileBudgetExcluded.toList()
            tileBudgetExcluded.clear()
            cleared.forEach { id -> mainHandler.post { onGroupTileBudgetChanged?.invoke(id, false) } }
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
        // B1: holds the per-srcId lock for the codec create/configure/start
        // work only — [configured] tracks success so the keyframe request (a
        // socket write) can be issued AFTER the lock is released, never under it.
        var configured = false
        synchronized(groupDecoderLock(srcId)) {
            try {
                val fmt = MediaFormat.createVideoFormat("video/avc", WIDTH, HEIGHT).apply {
                    setByteBuffer("csd-0", ByteBuffer.wrap(csd))
                }
                val dec = MediaCodec.createDecoderByType("video/avc")
                dec.configure(fmt, surface, null, 0)
                dec.start()
                groupDecoders[srcId] = dec
                // FIX 3: a freshly (re)configured decoder is healthy again.
                groupDecoderBroken.remove(srcId)
                // PHASE 8 STEP 2: every configure success is a recovery point,
                // whether this is the first-ever configure, FIX 2's surface-change
                // rebuild, or an automatic retry — see markGroupPeerRecovered.
                markGroupPeerRecovered(srcId)
                log("MEDIA: group tile decoder configured for ${MeshFrame.hex(srcId)} (csd ${csd.size} bytes)")
                configured = true
            } catch (e: Exception) {
                if (running.get()) logE("OFFTRACE: MEDIA: group decoder configure for ${MeshFrame.hex(srcId)}: ${e.message}")
                // PHASE 8 STEP 2: fault isolation — this srcId's tile goes
                // degraded; nothing else about the call is touched (not
                // groupCall.participants, not any other peer's state).
                markGroupPeerDegraded(srcId, "configure_failed")
            }
        }
        if (configured && requestKeyframeAfter) {
            writeFrame(srcId, TYPE_KEYFRAME_REQUEST, ByteArray(0))
            Log.d("OFFTRACE", "MEDIA: keyframe requested after csd retry for ${MeshFrame.hex(srcId)}")
        }
    }

    /** FIX 3: [srcId] is skipped entirely (no MediaCodec call at all) once its
     *  decoder is known-broken — a decoder whose Surface was destroyed out
     *  from under it (see setGroupTileSurface's FIX 2 rebuild, which is what
     *  actually recovers it) throws on EVERY subsequent call at 30fps, which
     *  used to mean one identical exception+log line per frame; this instead
     *  throttles to at most one drop notice per srcId per second. */
    private fun feedGroupDecoder(srcId: Long, data: ByteArray) {
        if (srcId in groupDecoderBroken) {
            logDroppedGroupFrame(srcId)
            return
        }
        // B1: same per-srcId lock configureGroupDecoder/releaseGroupDecoder
        // hold — this runs on srcId's own MediaReadLoop-$idx thread at up to
        // 30fps, so this lock is on the hot path; it guards only the
        // MediaCodec calls below, no socket I/O.
        synchronized(groupDecoderLock(srcId)) {
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
                // FIX 3: mark broken so every subsequent frame this second (and
                // until a rebuild replaces this decoder) short-circuits above
                // instead of re-entering a MediaCodec that will just throw again.
                groupDecoderBroken.add(srcId)
                if (running.get()) logE("OFFTRACE: MEDIA: feedGroupDecoder ${MeshFrame.hex(srcId)}: ${e.message}")
                logDroppedGroupFrame(srcId)
                // PHASE 8 STEP 2: fault isolation — see configureGroupDecoder's
                // identical call; automatic recovery (retryDegradedGroupPeers)
                // will attempt to rebuild this decoder every 10s.
                markGroupPeerDegraded(srcId, "feed_failed")
            }
        }
    }

    // ── PHASE 8 STEP 2: fault isolation / automatic recovery ────────────────

    /** Marks [srcId]'s tile video degraded — fires [onGroupTileDegraded] (main
     *  thread) only on the empty->non-empty transition, so a repeatedly
     *  failing feed doesn't spam the UI callback. Never touches
     *  groupCall.participants: a degraded peer is still fully in the call. */
    private fun markGroupPeerDegraded(srcId: Long, reason: String) {
        val health = groupPeerHealth.getOrPut(srcId) { PeerVideoHealth() }
        health.attempts++
        val firstTime = groupPeerDegraded.add(srcId)
        Log.d("OFFTRACE", "SCALE: peer ${MeshFrame.hex(srcId)} DEGRADED reason=$reason attempt=${health.attempts}")
        if (firstTime) mainHandler.post { onGroupTileDegraded?.invoke(srcId, true) }
    }

    /** Clears degraded state and fires [onGroupTileDegraded] with recovered=true
     *  — called from every successful [configureGroupDecoder], whether that's
     *  the first-ever configure, a surface-change rebuild, or an automatic retry. */
    private fun markGroupPeerRecovered(srcId: Long) {
        if (groupPeerDegraded.remove(srcId)) {
            groupPeerHealth.remove(srcId)
            Log.d("OFFTRACE", "SCALE: peer ${MeshFrame.hex(srcId)} recovered")
            mainHandler.post { onGroupTileDegraded?.invoke(srcId, false) }
        } else {
            groupPeerHealth.remove(srcId)
        }
    }

    /** Silently drops any degraded/health bookkeeping for [srcId] — called from
     *  [releaseGroupDecoder] (camera-off OR genuine departure), where the
     *  decoder going away is INTENTIONAL, not a recovery — no log, no UI
     *  callback (the Activity already handles cam-off/departure through
     *  onGroupCallCamState/onGroupCallParticipants). */
    private fun clearGroupPeerHealth(srcId: Long) {
        groupPeerDegraded.remove(srcId)
        groupPeerHealth.remove(srcId)
    }

    private fun scheduleGroupPeerRetries() {
        if (degradedRetryRunnable != null) return
        val r = object : Runnable {
            override fun run() {
                retryDegradedGroupPeers()
                mainHandler.postDelayed(this, DEGRADED_RETRY_INTERVAL_MS)
            }
        }
        degradedRetryRunnable = r
        mainHandler.postDelayed(r, DEGRADED_RETRY_INTERVAL_MS)
    }

    private fun stopGroupPeerRetries() {
        degradedRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        degradedRetryRunnable = null
    }

    /** Automatic recovery: retries a degraded peer's decoder once every 10s,
     *  up to DEGRADED_MAX_RETRY_ATTEMPTS total, using the persistent csd cache
     *  (see groupCsdCache's doc) and whatever Surface is currently registered
     *  for them. Past the attempt cap, this simply stops retrying — the peer
     *  stays audio-only permanently (the attempt=5 DEGRADED log line already
     *  on record is the "leave it audio-only and log it" evidence; nothing
     *  else fires). Reuses configureGroupDecoder exactly, so success/failure
     *  bookkeeping (markGroupPeerRecovered/Degraded) is identical to every
     *  other configure call site. */
    private fun retryDegradedGroupPeers() {
        if (groupPeerDegraded.isEmpty()) return
        val now = System.currentTimeMillis()
        groupPeerDegraded.toList().forEach { srcId ->
            val health = groupPeerHealth[srcId] ?: return@forEach
            if (health.attempts >= DEGRADED_MAX_RETRY_ATTEMPTS) return@forEach
            if (now - health.lastRetryAtMs < DEGRADED_RETRY_INTERVAL_MS) return@forEach
            val csd = groupCsdCache[srcId] ?: return@forEach
            if (groupTileSurfaces[srcId]?.isValid != true) return@forEach
            health.lastRetryAtMs = now
            Log.d("OFFTRACE", "SCALE: retrying degraded peer ${MeshFrame.hex(srcId)} attempt=${health.attempts + 1}")
            configureGroupDecoder(srcId, csd, requestKeyframeAfter = true)
        }
    }

    private fun logDroppedGroupFrame(srcId: Long) {
        val now = System.currentTimeMillis()
        val last = groupDecoderDropLogAtMs[srcId] ?: 0L
        if (now - last < 1_000L) return
        groupDecoderDropLogAtMs[srcId] = now
        Log.d("OFFTRACE", "MEDIA: dropping frame for ${MeshFrame.hex(srcId)} — decoder not ready")
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

    /** Frees this srcId's decoder — called both when they genuinely leave the
     *  call AND on an ordinary camera-off (still IN the call, see
     *  [applyCamState]). FIX 1: deliberately does NOT touch [groupCsdCache]
     *  here — only an actual departure clears that (see the field's doc) — so
     *  a decoder released here can still be correctly rebuilt later via
     *  [setGroupTileSurface] if their camera comes back on and their Surface
     *  happens to cycle before a fresh TYPE_CONFIG arrives. */
    private fun releaseGroupDecoder(srcId: Long) {
        // B1: same per-srcId lock feedGroupDecoder/configureGroupDecoder
        // hold — without it, a release racing a feed on the SAME decoder
        // object is a use-after-release on a MediaCodec, which is not
        // thread-safe and does not fail cleanly.
        synchronized(groupDecoderLock(srcId)) {
            groupDecoders.remove(srcId)?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
        }
        groupPendingCsd.remove(srcId)
        groupDecoderReady.remove(srcId)
        groupVideoFrameCountRecv.remove(srcId)
        // FIX 3: a released decoder is no longer "broken", it's just gone —
        // don't let a stale broken-flag confuse a later fresh configure.
        groupDecoderBroken.remove(srcId)
        groupDecoderDropLogAtMs.remove(srcId)
        // PHASE 8 STEP 2: silent — cam-off/departure is intentional, not a
        // recovery; see clearGroupPeerHealth's doc.
        clearGroupPeerHealth(srcId)
    }

    private fun releaseAllGroupDecoders() {
        groupDecoders.keys.toList().forEach { releaseGroupDecoder(it) }
        // FIX 1: the whole group call is ending here (see call sites) — every
        // participant is "leaving" at once, so the persistent csd cache goes too.
        groupCsdCache.clear()
        groupDecoderLocks.clear()
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
        }, "MediaOpusDecode").guarded()
        opusDecodeThread = t
        t.start()
    }

    private fun feedOpusAudio(data: ByteArray) {
        var dec = audioDecoder
        if (dec == null) {
            dec = configureOpusAudioDecoder() ?: return
            audioDecoder = dec
        }
        // B2: this whole function's body is codec I/O on opusDecodeThread —
        // releaseAudio() gates on this flag before calling audioDecoder.stop()/
        // release() from another thread (see waitForCodecFree).
        opusDecodeInsideCodec = true
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
        } finally {
            opusDecodeInsideCodec = false
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
    // is shared), just one instance per remote sender instead of one shared field.
    // STABILITY AUDIT 2.2: called directly off dispatchLocal, on that srcId's own
    // read loop thread — same as the group VIDEO decoders — and now actually
    // synchronized under [groupAudioDecoderLock] the same way, so a concurrent
    // [releaseGroupAudioDecoder] on the main thread can never race a decode call
    // for the same srcId onto the same (possibly just-released) MediaCodec.

    private fun decodeGroupAudio(srcId: Long, opusBytes: ByteArray): ByteArray? {
        // B1 (mirrors feedGroupDecoder): holds the per-srcId lock for the whole
        // decode call — getOrPut's configure included — never across a socket
        // write (there is none on this path).
        synchronized(groupAudioDecoderLock(srcId)) {
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
        // STABILITY AUDIT 2.2: same per-srcId lock decodeGroupAudio holds —
        // without it, a release racing a decode on the SAME MediaCodec object
        // is a use-after-release, exactly as releaseGroupDecoder's identical
        // comment (video path) describes.
        synchronized(groupAudioDecoderLock(srcId)) {
            groupAudioDecoders.remove(srcId)?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
        }
        groupRemoteAudioCodec.remove(srcId)
        groupLatestPcm.remove(srcId)
        groupLatestPcmMs.remove(srcId)
    }

    // ── PHASE 8 STEP 3: GO-side audio mixing (>= 4 participants only) ──────────
    // Below GO_MIX_PARTICIPANT_THRESHOLD, none of this runs — the existing
    // forward-and-mix-locally path above (audioDst/dispatchLocal's TYPE_AUDIO
    // branch/mixAndPlayGroupAudio) is completely unchanged and untouched.

    /** Fired on the main thread with the current GO-mix speaker set (empty
     *  when GO-mixing isn't active) — a SET (up to 3), unlike
     *  [onGroupCallSpeaker]'s single debounced highlight leader, so the UI
     *  can show a "speaking" indicator for anyone currently being mixed in. */
    var onGoMixSpeakersChanged: ((List<Long>) -> Unit)? = null

    private fun startGoMixTicker() {
        if (goMixThread != null) return
        val ht = HandlerThread("GoAudioMix").also { it.guarded().start() }
        goMixThread = ht
        val handler = Handler(ht.looper)
        goMixHandler = handler
        val r = object : Runnable {
            override fun run() {
                try { tickGoMix() } catch (e: Exception) { logE("OFFTRACE: MIX-GO: tick failed: ${e.message}") }
                handler.postDelayed(this, GO_MIX_TICK_MS)
            }
        }
        goMixRunnable = r
        handler.postDelayed(r, GO_MIX_TICK_MS)
    }

    private fun stopGoMixTicker() {
        goMixRunnable?.let { goMixHandler?.removeCallbacks(it) }
        goMixRunnable = null
        goMixHandler = null
        goMixThread?.quitSafely()
        goMixThread = null
        goMixEncoder?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
        goMixEncoder = null
        rawMixSpeakers = emptyList()
        pendingMixSpeakers = emptyList()
        pendingMixSpeakersSinceMs = 0L
        // OCP PHASE 2.1: teardown — same as a down-crossing, raw forwarding
        // must never stay suppressed against a mixer that no longer runs.
        goMixLive.set(false)
        if (committedMixSpeakers.isNotEmpty()) {
            committedMixSpeakers = emptyList()
            mainHandler.post { onGoMixSpeakersChanged?.invoke(emptyList()) }
        }
    }

    /** PHASE 8 TRACK C4: runs entirely on the dedicated goMixHandler thread —
     *  was GO-only, now any node with children (hasChildren()) independently
     *  runs this for its OWN subtree, using its OWN groupCallMixer/
     *  committedMixSpeakers/groupLatestPcm (all per-transport-instance fields
     *  — i.e. already naturally scoped to "this device's own view" with zero
     *  new state needed). routingTable.all() here means exactly what it
     *  means everywhere else in this class post-C3 — every direct neighbor,
     *  parent included — so the distribution loop below explicitly excludes
     *  parentNodeId: a relay's mixed-down variants are for ITS CHILDREN only;
     *  its own contribution reaches its parent via the UNCHANGED raw-audio
     *  broadcast path (see forwardBroadcast's directional suppression — a
     *  relay always still relays raw TYPE_AUDIO upward even while it stops
     *  relaying it sideways/downward), so the GO's OWN mixing continues to
     *  see every participant's real audio at any tree depth without this
     *  function needing to synthesize or forward a second, pre-mixed
     *  contribution upward — avoiding a second wire format entirely. No-ops
     *  below the participant threshold OR on a plain leaf (hasChildren()
     *  false) — the below-4 path, and the entire 2/3-device path, are never
     *  touched by this function running in the background. */
    private fun tickGoMix() {
        val gc = groupCall
        val eligible = gc != null && hasChildren() && gc.participants.size >= GO_MIX_PARTICIPANT_THRESHOLD
        if (!eligible) {
            // OCP PHASE 2.3: down-crossing (or never-was-eligible, a no-op
            // compareAndSet). Flip live OFF first — isGoMixReplacingBroadcastAudio
            // is read on the read-loop thread and will see raw forwarding
            // re-enabled as early as this write is visible. Raw relay was
            // never actually stopped AT THE SENDER (every device always
            // broadcasts its own raw TYPE_AUDIO — see audioDst), so it
            // resumes on literally the next frame with no synthetic delay.
            if (goMixLive.compareAndSet(true, false)) {
                logMixCrossing(gc, live = false, gapMs = 0L)
            }
            if (committedMixSpeakers.isNotEmpty()) {
                committedMixSpeakers = emptyList()
                mainHandler.post { onGoMixSpeakersChanged?.invoke(emptyList()) }
            }
            return
        }
        val now = System.currentTimeMillis()
        // OCP PHASE 2.2: seed the speaker set immediately on crossing —
        // never wait out MIX_SPEAKER_HOLD_MS's hysteresis for the very
        // FIRST commit (that hysteresis, via evaluateMixSpeakerHysteresis
        // below, still applies to every CHANGE after this initial seed).
        if (committedMixSpeakers.isEmpty()) {
            val seed = seedMixSpeakers(rawMixSpeakers, gc!!.participants, localNodeId, joinSequence)
            if (seed.isNotEmpty()) {
                committedMixSpeakers = seed
                pendingMixSpeakers = seed
                pendingMixSpeakersSinceMs = now
                mainHandler.post { onGoMixSpeakersChanged?.invoke(committedMixSpeakers) }
            }
        }
        if (now - lastMixSpeakerEvalMs >= MIX_SPEAKER_EVAL_INTERVAL_MS) {
            lastMixSpeakerEvalMs = now
            evaluateMixSpeakerHysteresis(now)
        }
        val speakers = committedMixSpeakers
        if (speakers.isEmpty()) return
        // OCP PHASE 0.3: snapshot BEFORE this tick sends anything — if this
        // tick is the one that flips goMixLive true, gapMs is the silence
        // window between the last audio that actually flowed and this
        // tick's delivery (see lastAudioDeliveredAtMs's doc).
        val gapBaseline = lastAudioDeliveredAtMs

        val fresh = speakers.mapNotNull { id ->
            if (now - (groupLatestPcmMs[id] ?: 0L) >= GROUP_AUDIO_STALE_MS) null
            else groupLatestPcm[id]?.let { id to it }
        }
        if (fresh.isEmpty()) return
        val speakerIds = fresh.map { it.first }

        var mixesBuilt = 0
        var clientsServed = 0

        // Variant 1: the full top-3 mix — everyone NOT currently one of the
        // speakers gets this (including this device's own playback, if the
        // GO itself isn't currently speaking).
        val fullMixPcm = mixGroupPcm(fresh.map { it.second })
        val fullMixOpus = encodeGoMixOpus(fullMixPcm)
        if (fullMixOpus != null) {
            mixesBuilt++
            val fullPayload = buildGoMixPayload(speakerIds, fullMixOpus)
            // PHASE 8 TRACK C4: exclude parentNodeId (null on the GO, so a
            // pure no-op there) — a relay's mixed variant is for its own
            // children only; the parent gets this node's contribution via
            // the unchanged raw-audio path, not this synthesized payload.
            routingTable.all().forEach { link ->
                if (link.nodeId != parentNodeId && link.nodeId !in speakers) {
                    writeFrame(link.nodeId, TYPE_AUDIO, fullPayload)
                    clientsServed++
                }
            }
            if (localNodeId !in speakers) {
                feedAudioTrackPcm(fullMixPcm, groupOpusOutputSampleRate, channelCountToOutConfig(groupOpusOutputChannelCount))
            }
        }

        // Variants 2-4: for EACH current speaker, the mix of everyone ELSE
        // currently speaking (excluding their own voice) — at most 3 more
        // distinct mixes, so at most 4 total regardless of N.
        fresh.forEach { (excludeId, _) ->
            val others = fresh.filter { it.first != excludeId }
            val variantPcm = if (others.isEmpty()) ByteArray(AUDIO_CHUNK_BYTES) else mixGroupPcm(others.map { it.second })
            val variantOpus = encodeGoMixOpus(variantPcm) ?: return@forEach
            mixesBuilt++
            if (excludeId == localNodeId) {
                feedAudioTrackPcm(variantPcm, groupOpusOutputSampleRate, channelCountToOutConfig(groupOpusOutputChannelCount))
            } else {
                val variantPayload = buildGoMixPayload(others.map { it.first }, variantOpus)
                writeFrame(excludeId, TYPE_AUDIO, variantPayload)
                clientsServed++
            }
        }

        // OCP PHASE 2.1/0.3: something was actually delivered this tick —
        // update the liveness timestamp, and if this is the tick that
        // crosses live=false->true, fire the crossing log with the
        // measured gap. compareAndSet makes the log fire exactly once per
        // episode, not on every subsequent tick.
        if (mixesBuilt > 0) {
            lastAudioDeliveredAtMs = now
            if (goMixLive.compareAndSet(false, true)) {
                logMixCrossing(gc, live = true, gapMs = (now - gapBaseline).coerceAtLeast(0L))
            }
        }

        if (now - lastGoMixLogMs >= GROUP_MIX_LOG_INTERVAL_MS) {
            lastGoMixLogMs = now
            Log.d(
                "OFFTRACE",
                "MIX-GO: speakers=[${speakerIds.joinToString(",") { MeshFrame.hex(it) }}] mixes=$mixesBuilt clients=$clientsServed"
            )
            // PHASE 8 TRACK C4: required log format — subtree size = this
            // node's own direct children (routingTable minus its parent),
            // i.e. exactly who this tick's mixed variants were built for.
            val subtreeSize = routingTable.all().count { it.nodeId != parentNodeId }
            Log.d(
                "OFFTRACE",
                "MIX: subtree=$subtreeSize speakers=[${speakerIds.joinToString(",") { MeshFrame.hex(it) }}] passed up"
            )
        }
    }

    /** OCP PHASE 0.3: fires exactly once per threshold crossing (see the
     *  compareAndSet call sites in tickGoMix) — a SEPARATE, one-shot log
     *  from the continuously-repeating MIX-GO/"MIX: subtree=..." diagnostic
     *  above. gapMs measures elapsed time since audio last actually flowed
     *  (see lastAudioDeliveredAtMs) and should stay small — bounded by
     *  GO_MIX_TICK_MS/one audio chunk interval, tens of ms — through every
     *  crossing; a multi-hundred-ms value means the crossfade leaked and
     *  raw relay was briefly suppressed with nothing live to replace it. */
    private fun logMixCrossing(gc: GroupCallState?, live: Boolean, gapMs: Long) {
        val n = gc?.participants?.size ?: 0
        val ids = committedMixSpeakers.joinToString(",") { MeshFrame.hex(it) }
        Log.d("OFFTRACE", "MIX: n=$n goMixLive=$live speakers=[$ids] gapMs=$gapMs")
    }

    /** Re-evaluates the COMMITTED speaker set from [rawMixSpeakers] (fed by
     *  GroupCallMixer's VAD ranking) with hysteresis: a candidate set only
     *  becomes committed once it has been the raw ranking continuously for
     *  MIX_SPEAKER_HOLD_MS. Flapping here means creating/destroying Opus
     *  decode work for whoever enters/leaves the set, unlike the video
     *  active-speaker highlight's debounce (onRawActiveSpeakersChanged),
     *  which only moves a UI border. */
    private fun evaluateMixSpeakerHysteresis(now: Long) {
        val raw = rawMixSpeakers
        if (raw.toSet() != pendingMixSpeakers.toSet()) {
            pendingMixSpeakers = raw
            pendingMixSpeakersSinceMs = now
        }
        if (raw.toSet() == committedMixSpeakers.toSet()) return
        if (now - pendingMixSpeakersSinceMs < MIX_SPEAKER_HOLD_MS) return
        committedMixSpeakers = pendingMixSpeakers
        mainHandler.post { onGoMixSpeakersChanged?.invoke(committedMixSpeakers) }
    }

    private fun ensureGoMixEncoder(): MediaCodec? {
        goMixEncoder?.let { return it }
        val enc = createOpusEncoderOrNull() ?: return null
        goMixEncoder = enc
        return enc
    }

    /** Synchronous encode+drain, called sequentially up to 4x per tick (once
     *  per distinct mix variant) from [tickGoMix] — always on the dedicated
     *  goMixHandler thread, never read/write threads. Reuses ONE persistent
     *  encoder instance across every call rather than recreating one per
     *  mix; on any exception the encoder is torn down and rebuilt on the
     *  NEXT tick rather than risking feeding a codec left in a bad state. */
    private fun encodeGoMixOpus(pcm: ByteArray): ByteArray? {
        val enc = ensureGoMixEncoder() ?: return null
        return try {
            val inIdx = enc.dequeueInputBuffer(5_000L)
            if (inIdx >= 0) {
                val buf = enc.getInputBuffer(inIdx)!!
                buf.clear()
                buf.put(pcm)
                enc.queueInputBuffer(inIdx, 0, pcm.size, System.nanoTime() / 1000, 0)
            }
            val chunks = mutableListOf<ByteArray>()
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
                        chunks.add(bytes)
                    }
                }
                enc.releaseOutputBuffer(outIdx, false)
            }
            if (chunks.isEmpty()) null else combineByteArrays(chunks)
        } catch (e: Exception) {
            logE("OFFTRACE: MIX-GO: encode failed: ${e.message}")
            try { enc.stop(); enc.release() } catch (_: Exception) {}
            goMixEncoder = null
            null
        }
    }

    /** Wire shape for a GO-mixed frame: [1B speakerCount][speakerCount * 8B
     *  speakerId][opus bytes]. Sent as an ordinary TYPE_AUDIO frame — no new
     *  frame type — but UNICAST (dst = one specific recipient) rather than
     *  BROADCAST, which is what lets the receiver ([dispatchLocal]'s
     *  TYPE_AUDIO branch) tell a GO-mixed frame apart from an ordinary
     *  per-sender broadcast one with zero ambiguity: only this new code path
     *  ever sends TYPE_AUDIO unicast, so "unicast, from my uplink, to me" is
     *  a fully reliable, collision-free signal (see decodeAndPlayGoMixedAudio). */
    private fun buildGoMixPayload(speakerIds: List<Long>, opus: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(1 + speakerIds.size * 8 + opus.size)
        buf.put(speakerIds.size.coerceIn(0, 255).toByte())
        speakerIds.forEach { buf.putLong(it) }
        buf.put(opus)
        return buf.array()
    }

    /** Client-only receive path for a GO-mixed frame — decodes via a SINGLE
     *  persistent decoder (there is exactly one incoming mixed stream now,
     *  never one per sender), plays it directly, and reports the embedded
     *  speaker ids for the UI. Runs on the read thread, same as every other
     *  dispatchLocal branch — this is one decode per received frame, no
     *  different in cost from the ordinary per-1:1-call decode path it
     *  parallels; the EXPENSIVE work (mixing N-way, encoding up to 4
     *  variants) already happened once on the GO's own dedicated thread. */
    private fun decodeAndPlayGoMixedAudio(payload: ByteArray) {
        if (payload.isEmpty()) return
        val count = payload[0].toInt() and 0xFF
        val headerLen = 1 + count * 8
        if (payload.size < headerLen) {
            logW("OFFTRACE: MIX-GO: malformed mixed-audio payload len=${payload.size}")
            return
        }
        val speakerIds = ArrayList<Long>(count)
        val buf = ByteBuffer.wrap(payload, 1, count * 8)
        repeat(count) { speakerIds.add(buf.long) }
        val opus = payload.copyOfRange(headerLen, payload.size)
        val pcm = decodeGroupAudio(GO_MIX_DECODER_KEY, opus) ?: return
        feedAudioTrackPcm(pcm, groupOpusOutputSampleRate, channelCountToOutConfig(groupOpusOutputChannelCount))
        mainHandler.post { onGoMixSpeakersChanged?.invoke(speakerIds) }
    }

    /** Releases every per-sender decoder AND clears the codec/PCM bookkeeping maps
     *  outright — [releaseGroupAudioDecoder] alone would miss a PCM-fallback sender
     *  (see [decodeGroupAudio]'s call site: PCM payloads never create a decoder
     *  entry at all, but still populate groupRemoteAudioCodec/groupLatestPcm), which
     *  would otherwise leak stale entries into the next call. */
    private fun releaseAllGroupAudioDecoders() {
        // STABILITY AUDIT 2.2: delegates to the locked single-srcId release
        // (same structure as releaseAllGroupDecoders' video-path equivalent)
        // instead of iterating groupAudioDecoders.values directly, which used
        // to release every decoder with no lock held at all.
        groupAudioDecoders.keys.toList().forEach { releaseGroupAudioDecoder(it) }
        groupRemoteAudioCodec.clear()
        groupLatestPcm.clear()
        groupLatestPcmMs.clear()
        groupAudioDecoderLocks.clear()
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
            if (t.isAlive) {
                timedOut++
                // B2: named, not just counted — join() timing out on a thread
                // parked inside a native AudioRecord.read()/MediaCodec call
                // (interrupt() doesn't reach those) is exactly the scenario
                // [waitForCodecFree] exists to still gate the actual release
                // on, but it's worth surfacing which thread it was.
                logW("OFFTRACE: MEDIA: call thread '${t.name}' still alive after ${CALL_THREAD_JOIN_MS}ms join")
            } else {
                joined++
            }
        }
        audioSendThread = null
        opusEncodeThread = null
        opusDecodeThread = null
        encoderDrainThread = null
        opusEncodeQueue.clear()
        opusDecodeQueue.clear()
        log("OFFTRACE: MEDIA: call threads stopped ($joined joined, $timedOut timed out)")
    }

    /** PART B / B2: waits for a worker thread's own "I am inside a native
     *  codec/AudioRecord call right now" flag to clear before the caller is
     *  allowed to actually stop()/release() that codec/AudioRecord — a join()
     *  timeout in [stopCallThreads] is NOT proof the thread is safely out of
     *  a native call (Thread.interrupt() does not unblock AudioRecord.read()
     *  or MediaCodec.dequeueOutputBuffer()), so release gates on this instead
     *  of trusting join() alone. Logs [threadName] if the flag never clears
     *  within [CODEC_RELEASE_WAIT_MS] — release proceeds regardless at that
     *  point (an unbounded wait would hang teardown forever), but the log
     *  makes that rare race visible instead of a silent native crash. */
    private fun waitForCodecFree(threadName: String, insideCodec: () -> Boolean) {
        if (!insideCodec()) return
        val deadline = System.currentTimeMillis() + CODEC_RELEASE_WAIT_MS
        while (insideCodec() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        if (insideCodec()) {
            logW("OFFTRACE: MEDIA: '$threadName' still inside its codec after ${CODEC_RELEASE_WAIT_MS}ms — releasing anyway")
        }
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
        // B2: gate on the drain thread's own flag, not just encoderRunning —
        // it only clears that flag from INSIDE its dequeueOutputBuffer call's
        // finally, so this waits for it to actually finish that call.
        waitForCodecFree("MediaEncoderDrain") { encoderDrainInsideCodec }
        val enc = encoder
        encoder = null
        try { enc?.stop() } catch (_: Exception) {}
        try { enc?.release() } catch (_: Exception) {}
        try { encoderInputSurface?.release() } catch (_: Exception) {}
        encoderInputSurface = null
        // OCP PHASE 5.1: the low encoder is always lifecycle-paired with the
        // high one (never outlives it) — folded in here rather than added
        // to all 6 of this function's call sites individually, so every
        // existing teardown path picks it up automatically.
        releaseLowEncoder()
    }

    /** OCP PHASE 5.1: mirrors [releaseEncoder] exactly, for the low-layer
     *  simulcast encoder. Safe to call whether or not the low encoder was
     *  ever actually running (every field is nullable/flag-guarded). */
    private fun releaseLowEncoder() {
        lowEncoderRunning = false
        // B2: see releaseEncoder's identical gate.
        waitForCodecFree("MediaLowEncoderDrain") { lowEncoderDrainInsideCodec }
        val enc = lowEncoder
        lowEncoder = null
        try { enc?.stop() } catch (_: Exception) {}
        try { enc?.release() } catch (_: Exception) {}
        try { lowEncoderInputSurface?.release() } catch (_: Exception) {}
        lowEncoderInputSurface = null
    }

    private fun releaseDecoder() {
        try { decoder?.stop() } catch (_: Exception) {}
        try { decoder?.release() } catch (_: Exception) {}
        decoder = null
    }

    private fun releaseAudio() {
        // B2: audioSendThread (rec.read), opusEncodeThread (audioEncoder) and
        // opusDecodeThread (audioDecoder) each self-release their OWN codec
        // once their loop notices callActive==false — these calls are the
        // belt-and-suspenders path for whatever a stopCallThreads() join()
        // timeout left behind, so each gates on that thread's own flag first.
        waitForCodecFree("MediaAudioSend") { audioSendInsideRecord }
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
        waitForCodecFree("MediaOpusEncode") { opusEncodeInsideCodec }
        try { audioEncoder?.stop() } catch (_: Exception) {}
        try { audioEncoder?.release() } catch (_: Exception) {}
        audioEncoder = null
        waitForCodecFree("MediaOpusDecode") { opusDecodeInsideCodec }
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

    // LOGGING cleanup (welcome, not required): these dedupe-drop/chat-recv
    // lines were the three remaining unthrottled per-frame OFFTRACE call
    // sites — throttled 1/sec per srcId, matching the exact pattern
    // logIfRelayed/relayedLogAtMs already established for the relay-forward
    // line above. A dedupe HIT is rarer than a plain relayed frame, but
    // under a genuine retransmission storm (the scenario this whole relay-
    // suppression phase exists for) it can still fire at frame rate.
    private val dedupeDropLogAtMs = ConcurrentHashMap<Long, Long>()
    private fun logPerSrcThrottled(srcId: Long, msg: () -> String) {
        val now = System.currentTimeMillis()
        val last = dedupeDropLogAtMs[srcId] ?: 0L
        if (now - last < 1_000L) return
        dedupeDropLogAtMs[srcId] = now
        log(msg())
    }

    private fun logE(msg: String) {
        Log.e("OFFTRACE", msg)
    }

    private fun reportError(msg: String) {
        logE(msg)
        mainHandler.post { onError(msg) }
    }
}
