package com.opencall.relay.offline

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.RingtoneManager
import android.net.Uri
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.graphics.Color
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.opencall.relay.CapabilityProbe
import kotlin.math.roundToInt

/**
 * Minimal, fully-programmatic UI (no layout resource) for discovering a
 * Wi-Fi-Direct peer/group and running an offline call over it entirely via
 * OfflineMediaTransport (camera/mic -> MediaCodec -> raw socket). Built
 * entirely in com.opencall.relay.offline so it never touches
 * RelayService/AudioBridge/SMS/GSM/relay code.
 *
 * PHASE 3 — GO AS SWITCHBOARD: tapping a nearby device no longer immediately
 * starts a call — it JOINS a WiFi Direct group (the tapper's device becomes/joins
 * the group; up to ~8 members). Once joined, [groupScreen] shows the live roster
 * (see OfflineMediaTransport.onRosterUpdated) instead of going straight to the call
 * screen. Tapping a roster member brings up the same 3-mode dialog as before, but
 * now addresses a specific member (mediaTransport.placeCall) — that member may be
 * the GO, or another client relayed through it; this screen never needs to know
 * which. Hanging up (mediaTransport.endCall) returns to the roster, not to
 * re-discovery — the underlying group stays joined until "Leave group".
 *
 * PART 4 (three-tab restructure): this Activity is now Tab 3 ("Offline") of
 * the app's shell — reached via `startActivity(..., FLAG_ACTIVITY_
 * REORDER_TO_FRONT)` from [com.opencall.relay.MainActivity], NEVER via
 * `finish()`, in either direction. That matters: [onDestroy] below only
 * tears the mesh session down when `isFinishing == true` (a genuine user
 * exit), and [onStop] already never tears anything down at all — so as long
 * as tab-switch navigation never finishes this Activity, an active mesh
 * session survives switching to Tab 1/2 exactly as it already survives the
 * screen turning off (this behaviour predates this restructure — see the
 * `onStop`/`onDestroy` comments below — this doc just states the guarantee
 * tab-switching now depends on). `android:launchMode="singleTask"` in the
 * manifest is what lets [android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT]
 * reuse the existing instance instead of creating a duplicate.
 *
 * The only edits made to this file for that restructure are additive/
 * cosmetic: wrapping the existing root view with the shared top bar +
 * global 3-tab bottom bar in [buildUi] (search `AppShell` below), and
 * reading [EXTRA_OPEN_SETTINGS_TAB] in [onCreate]/[onNewIntent] so
 * Settings' "Offline mesh settings" row can jump straight to this
 * Activity's own Settings sub-tab. Nothing about WifiDirectManager,
 * OfflineMediaTransport, or any mesh/transport/signaling logic changed.
 */

/** PART D5: media type an OCP-specific optical payload (Group Alert / chat
 *  note) is packed as — built in [OfflineCallActivity.packOcpFrame],
 *  recognized in [OpticalScanActivity.showReceivedFile]. File-scope (not
 *  `private` — needed from both files), not inside a companion object,
 *  since a `const val` needs to live at file scope, in an object, or in
 *  the ONE companion object a class may have; this class's existing
 *  companion object is large and unrelated, and a class may not have two. */
const val OCP_FRAME_MEDIA_TYPE = "application/x-opencall-meshframe"

/** PART "HASSLE-FREE JOIN" 2.3: the known Wi-Fi Direct GO client ceiling —
 *  a BLE-advertising concern only ("stop saying the group is open once it
 *  can't actually take another joiner"), independent of
 *  OfflineMediaTransport's own private MAX_GROUP_PARTICIPANTS (the
 *  mesh-level admission cap that constant enforces). */
private const val WIFI_DIRECT_GO_CLIENT_CEILING = 8

/** PART "HASSLE-FREE JOIN" item 4: an open-group BLE sighting older than
 *  this is dropped from the Nearby list — comfortably past the ~5s
 *  advertisement rotation interval (MeshBleBeacon.ROTATE_INTERVAL_MS)
 *  without being so long a genuinely-gone host lingers on screen. */
private const val OPEN_GROUP_STALE_MS = 15_000L

class OfflineCallActivity : AppCompatActivity() {

    companion object {
        // PART 4.1/4.2: set by SettingsActivity's "Offline mesh settings" row
        // — see this Activity's class doc for why that row jumps back in
        // here instead of this Settings tab's content moving out.
        const val EXTRA_OPEN_SETTINGS_TAB = "open_settings_tab"
        private const val PERM_REQUEST = 5001
        // Step 7: separate request code — a denial/grant here must not be
        // confused with (or trigger) PERM_REQUEST's own proceedToDiscovery.
        private const val REQUEST_CONTACTS_PERMISSION = 5002
        private const val GROUP_FORMATION_TIMEOUT_MS = 30_000L
        // BUG (GROUP FORMS, NOBODY JOINS) FIX: the tapper's "waiting for the
        // invited peer to associate" deadline — covers both "group never
        // formed" and "group formed but clients stayed 0", the whole window
        // the old code left the UI stuck on indefinitely.
        private const val INVITE_TIMEOUT_MS = 60_000L
        // BUG (DISCOVERY IS ONE-SHOT) FIX: a device still UNRESOLVED this
        // long after its first sighting will never publish an OpenCall TXT
        // record — it's not "still resolving", it's a non-OCP Wi-Fi Direct
        // device (a TV, a printer, ...). See NearbyDevice.firstSeenAtMs.
        private const val RESOLVE_GIVEUP_MS = 20_000L
        // PEER DIRECTION READOUT STEP 8: "Text row recomputed at max 4 Hz."
        private const val PARTY_ROW_REFRESH_INTERVAL_MS = 250L
        // CASE E1: guards against re-chaining the handler on every Activity recreation
        // (rotation, etc.) — install exactly once per process.
        private var uncaughtHandlerInstalled = false
        // FIX 5: one-time-per-process, same pattern as uncaughtHandlerInstalled above —
        // avoids re-prompting for battery exemption on every group join.
        private var batteryPromptShown = false

        // ── PHASE 1.5: pure, off-device-testable companions for partyView()/
        // partyStatusLine() (report C4/1.2) — plain data in, plain data out,
        // no Activity/Context/mediaTransport touched, so
        // OfflineCallActivityTest can exercise the merge/format logic
        // directly. The instance methods below are now thin wrappers that
        // just supply live data (roster, ledger.knownNodeIds(), localNodeId,
        // nameForGroupParticipant) to these.

        /** OCP PHASE 5.3: Point 7: 1 = full, 2 = split, 3-4 = 2x2, 5-6 = 2x3,
         *  7-8 = 3x3. MAX_GROUP_PARTICIPANTS (8, see OfflineMediaTransport —
         *  still the real WiFi Direct GO client ceiling, not raised by this
         *  phase; see OCP PHASE 4.5's report) means 8 is this function's
         *  actual ceiling — 5-9 used to all bucket into one 3x3 shape,
         *  leaving 5 with 4 empty cells and 6 with 3; 2x3 gives those two a
         *  real fit (5 leaves 1 empty cell, 6 is exact). 7/8 still share
         *  3x3 (7 leaves 2 empty, 8 leaves exactly 1) — see
         *  [localTileWeightFor] for how 8's one leftover cell is handled
         *  without an obviously blank gap. Pure — see this class's own
         *  PHASE 1.5 precedent above for why (OfflineCallActivityTest). */
        /** BUG (DISCOVERY IS ONE-SHOT) FIX: pure decision core of the
         *  "Finding…" vs "Not an OpenCall device" row label (and of the
         *  distinguishing log line refreshNearbyDevices emits per row).
         *  [resolved] is [reachability] != UNRESOLVED; [ageMs] is elapsed
         *  time since NearbyDevice.firstSeenAtMs. Display-only — since BUG
         *  (INVITE MUST NOT DEPEND ON DNS-SD RESOLUTION), resolution status
         *  no longer gates the invite tap at all (see sendInvite), only what
         *  an UNRESOLVED row's subtitle SAYS.
         *  (ResolveVerdict itself is declared at class level, alongside
         *  Reachability/InviteStatus, not in here — a type nested directly
         *  inside a companion object isn't reachable as OfflineCallActivity.X,
         *  only as OfflineCallActivity.Companion.X.) */
        fun resolveVerdict(resolved: Boolean, ageMs: Long): ResolveVerdict = when {
            resolved -> ResolveVerdict.RESOLVED
            ageMs >= RESOLVE_GIVEUP_MS -> ResolveVerdict.NOT_AN_OPENCALL_DEVICE
            else -> ResolveVerdict.STILL_RESOLVING
        }

        /** BUG (GROUP FORMS, NOBODY JOINS) FIX: pure decision core of
         *  onGroupClientsChanged — true iff the peer this device is
         *  currently waiting on (via "gp") has actually associated with the
         *  live group. Takes plain address strings (not WifiP2pDevice) so
         *  it's directly testable without any Android framework type. */
        fun invitedPeerAssociated(clientAddresses: List<String>, targetAddress: String?): Boolean =
            targetAddress != null && clientAddresses.contains(targetAddress)

        fun gridDimensionsFor(n: Int): Pair<Int, Int> = when {
            n <= 1 -> 1 to 1
            n == 2 -> 1 to 2
            n <= 4 -> 2 to 2
            n <= 6 -> 2 to 3
            else -> 3 to 3
        }

        /** STABILITY AUDIT 1a: GridLayout.setColumnCount/setRowCount throws
         *  IllegalArgumentException("columnCount must be >= max grid index")
         *  if any currently-attached child still holds a column/row spec
         *  index from a WIDER grid than the count being set — exactly what
         *  happens whenever participant count decreases and rebuildGroupCallGrid
         *  shrinks (rows, cols) without first detaching the stale children
         *  (confirmed real-hardware crash at rebuildGroupCallGrid:7538/7539).
         *
         *  True iff applying (newRows, newCols) to a grid currently sized
         *  (oldRows, oldCols) is a shrink in either dimension — the only case
         *  that can trip the invariant above; a grow (or same-size) is always
         *  safe to apply directly, since every existing child's spec index is
         *  already within the new, larger-or-equal bounds. Deliberately NOT
         *  "always clear first" (which is what the focus-mode branch five
         *  lines away does, safely, because focus mode always detaches every
         *  OTHER tile by design): unconditionally clearing here would defeat
         *  addTileToGrid's FIX 4 optimization (re-specing an already-parented
         *  tile's LayoutParams in place instead of detach+re-add) on every
         *  ordinary GROWTH reshape too, reintroducing the exact "3-device
         *  black-tile bug" FIX 4 was written to prevent (see rebuildGroupCallGrid's
         *  class doc) as a side effect of fixing the shrink crash. */
        fun gridRebuildMustClearFirst(oldRows: Int, oldCols: Int, newRows: Int, newCols: Int): Boolean =
            newRows < oldRows || newCols < oldCols

        /** OCP PHASE 5.3: at exactly 8 participants, 3x3 has one leftover
         *  cell — rather than an obviously blank gap, the LOCAL tile
         *  (always visible, never the most important tile to a viewer
         *  watching OTHER people) takes a reduced weight so the grid reads
         *  as an intentional layout, not a rendering gap. Every other count
         *  (including 7, which leaves TWO empty cells either way) keeps
         *  every tile equal-weight, unchanged. */
        fun localTileWeightFor(participantCount: Int, nodeId: Long, localNodeId: Long?): Float =
            if (participantCount == 8 && nodeId == localNodeId) 0.5f else 1f

        /** Union of [rosterMembers] (live, mesh-connectivity-verified) and
         *  [ledgerKnownIds] (anyone ever position/BLE-recorded, regardless
         *  of current connectivity), [localId] excluded, roster membership
         *  winning where both know the same nodeId (its name is
         *  authoritative there). */
        fun mergePartyView(
            rosterMembers: List<RoutingTable.Member>,
            ledgerKnownIds: Set<Long>,
            localId: Long,
            nameFor: (Long) -> String
        ): List<PartyEntry> {
            val entries = LinkedHashMap<Long, PartyEntry>()
            rosterMembers.forEach { m ->
                if (m.nodeId == localId) return@forEach
                entries[m.nodeId] = PartyEntry(m.nodeId, m.name, PartyOrigin.IN_ROSTER)
            }
            ledgerKnownIds.forEach { id ->
                if (id == localId || id in entries) return@forEach
                entries[id] = PartyEntry(id, nameFor(id), PartyOrigin.LEDGER_ONLY)
            }
            return entries.values.toList()
        }

        /** "N connected" (IN_ROSTER) plus, only when non-zero, ", M last
         *  seen" (LEDGER_ONLY). */
        fun formatPartyStatusLine(entries: List<PartyEntry>): String {
            val connected = entries.count { it.origin == PartyOrigin.IN_ROSTER }
            val lastSeen = entries.count { it.origin == PartyOrigin.LEDGER_ONLY }
            return if (lastSeen > 0) "$connected connected, $lastSeen last seen" else "$connected connected"
        }

        // PHASE 1.6d: pure companion for feedPartyRing's core transform — a
        // nodeId is dropped ONLY when [vectorLookup] returns null (mirrors
        // computePeerVector's only null case: mediaTransport being null).
        // MeshLedger.vectorTo() itself never returns null — an unknown peer
        // comes back as PeerState.UNKNOWN with NaN fields, not absent — so a
        // ledger-only UNKNOWN peer always survives this mapNotNull.
        fun buildRingPeers(
            nodeIds: Set<Long>,
            vectorLookup: (Long) -> MeshLedger.PeerVector?,
            nameFor: (Long) -> String,
            isArticulation: (Long) -> Boolean,
            rssiTrend: (Long) -> MeshLedger.Trend?,
            // Signal Deck (diagnostic follow-up): defaults to "always direct"
            // so every existing caller/test that never set this keeps
            // today's (teal) behavior unchanged — see RingPeer.isDirect's doc.
            isDirect: (Long) -> Boolean = { true }
        ): List<PartyRingView.RingPeer> = nodeIds.mapNotNull { nodeId ->
            val v = vectorLookup(nodeId) ?: return@mapNotNull null
            PartyRingView.RingPeer(nodeId, nameFor(nodeId), v, isArticulation(nodeId), rssiTrend(nodeId), isDirect(nodeId))
        }

        // PHASE 2.3: DISPLAY-only short ID. Excludes I, O, 0, 1 — the four
        // classic visually-ambiguous glyphs — leaving exactly 32 symbols
        // (24 letters + 8 digits), so this is a genuine base32 alphabet, not
        // an approximation. This is DELIBERATELY NOT used for the
        // pre-connect WFD correlation at :372/:439 or the advertised short
        // id at :725/:727 (still hex.takeLast(6)) — changing one side of
        // that correlation without the other breaks discovery; see those
        // call sites' own comments for the coordinated-pass note. Neither
        // this nor the hex form ever enters MeshSigner's verification path
        // (that always uses the full 8-byte nodeId + pubkey — see
        // MeshSigner.kt's own doc).
        // PHASE 2.2 COLLISION WARNING: SosTriggers.PREF_BUTTON_LONGPRESS is
        // "trigger_button_longpress" — this is a DIFFERENT key, deliberately,
        // so the new in-call button-mode toggle can never read back the SOS
        // hands-free trigger's stored value or vice versa (see
        // OfflineCallActivityTest for the independence assertion).
        const val PREF_CALL_BUTTON_LONGPRESS = "call_button_longpress"
        // FIX 4g: the list-view fallback toggle's persisted choice — radar
        // defaults ON (it's the new primary UI) but is worse than a list at
        // 8+ devices per the task spec, so this must survive an app restart
        // rather than resetting to radar every launch.
        private const val PREF_RADAR_VIEW_ENABLED = "radar_view_enabled"
        private const val SHORT_ID_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        fun shortIdBase32(nodeId: Long): String {
            val sb = StringBuilder(6)
            // Top 30 bits of the 64-bit nodeId, 5 bits/char, MSB-first.
            for (i in 0 until 6) {
                val shift = 64 - 5 * (i + 1)
                val bits = ((nodeId ushr shift) and 0x1FL).toInt()
                sb.append(SHORT_ID_ALPHABET[bits])
            }
            return sb.toString()
        }

        // PHASE 3.10: pure QR invite payload encode/decode — no Context, no
        // zxing, no Activity state — so OfflineCallActivityTest can round-
        // trip it and exercise the malformed-input path directly.
        // BUG (MAKE THE INVITE ACTUALLY TRANSMIT) FIX PART 6: [ss]/[pw],
        // when present, are the QR-generating device's ACTUAL live group
        // credentials (whatever it's hosting right now) — this is the most
        // deterministic of the three invite paths precisely because the
        // scanner needs to derive NOTHING: the nodeId identifies who to
        // connect to and ss/pw are the real, already-working credentials,
        // read straight off the code, same "the framework is the source of
        // truth" reasoning as the GO's DNS-SD "ss"/"pw" republish.
        // PART 2.1: the QR protocol's own version — bumped only for a
        // wire-incompatible change to this schema (none has happened yet;
        // every field below is exactly what shipped as v1). [decodeQrPayload]
        // rejects anything below this (missing "v", or "v":0) outright,
        // rather than silently guessing at a pre-versioning payload's shape.
        const val QR_PROTOCOL_VERSION = 1

        data class QrInvitePayload(
            val version: Int,
            val nodeId: Long,
            val pubkeyB64: String,
            val name: String,
            val groupId: String,
            val ss: String? = null,
            val pw: String? = null
        )

        // PART "WHY THE QR JOIN FAILS" C3: one function used to emit BOTH a
        // working payload (real ss/pw) and a useless one (null ss/pw,
        // depending on runtime timing — see A1/A2) from the SAME nullable-
        // parameter signature. That possibility is removed at the type
        // level, not just patched at a call site: a HOSTING payload's ss/pw
        // are non-nullable — a caller CANNOT construct one without already
        // holding real credentials — and a PAIRING (identity-only, no
        // group) payload is a separate function that has no ss/pw
        // parameters to omit in the first place.

        /** The ONLY payload capable of representing a joinable hosted group.
         *  [ss]/[pw] are non-null BY CONSTRUCTION — there is no way to call
         *  this with missing credentials short of passing the string
         *  literal "" (see [showHostQrScreen]/[hasValidHostCredentials],
         *  the runtime gate that stops precisely that). */
        fun encodeHostingQrPayload(nodeIdHex: String, pubkeyB64: String, name: String, groupId: String, ss: String, pw: String): String =
            org.json.JSONObject().apply {
                put("v", QR_PROTOCOL_VERSION)
                put("nodeId", nodeIdHex)
                put("pubkey", pubkeyB64)
                put("name", name)
                put("groupId", groupId)
                put("ss", ss)
                put("pw", pw)
            }.toString()

        /** Pure identity, no group — the "Show QR code" pairing-only flow
         *  (Settings/Tell-a-friend). Structurally cannot carry ss/pw at all;
         *  there is no parameter to accidentally leave null. */
        fun encodePairingQrPayload(nodeIdHex: String, pubkeyB64: String, name: String): String =
            org.json.JSONObject().apply {
                put("v", QR_PROTOCOL_VERSION)
                put("nodeId", nodeIdHex)
                put("pubkey", pubkeyB64)
                put("name", name)
                put("groupId", "")
            }.toString()

        /** C1/C2/C6: pure gate deciding whether [showHostQrScreen] may be
         *  reached at all — the ONE thing standing between
         *  [WifiP2pGroup]'s async result and a QR render, extracted so "a
         *  QR that cannot work never appears on screen" is directly
         *  testable without a real WifiP2pGroup/Context (this project has
         *  no Robolectric — same reasoning as every other pure decision
         *  core in this file, e.g. [shouldRecoverFromTerminalError]-style
         *  extraction elsewhere in this codebase). Blank counts as
         *  missing, not just null — an empty-string networkName/passphrase
         *  is exactly as useless as a null one. */
        fun hasValidHostCredentials(networkName: String?, passphrase: String?): Boolean =
            !networkName.isNullOrBlank() && !passphrase.isNullOrBlank()

        /** Never throws — a malformed/foreign QR payload (bad JSON, missing
         *  "nodeId", a "nodeId" that isn't valid hex, a missing/zero "v", ...)
         *  returns null rather than propagating an exception, so the caller
         *  can never crash or half-join on a bad scan (3.10's own
         *  requirement). BUG (MAKE THE INVITE ACTUALLY TRANSMIT) FIX PART 6:
         *  QR was completely silent — not one "QR:" line in any capture —
         *  so a failure here now logs its reason instead of just vanishing
         *  into a null. PART 2.1: only rejects on version < 1 (no version
         *  concept at all); it does NOT reject a version newer than
         *  [QR_PROTOCOL_VERSION] — a future additive version's payload still
         *  round-trips through this same v1-shaped read, same forward-
         *  compatible philosophy as [MeshSigner.decodeHelloInner]'s trailing-
         *  byte handling. */
        fun decodeQrPayload(raw: String): QrInvitePayload? = try {
            val json = org.json.JSONObject(raw)
            val version = json.optInt("v", 0)
            if (version < 1) {
                Log.w("OFFTRACE", "QR: decode failed reason=missing_or_invalid_version version=$version")
                null
            } else {
                val nodeIdHex = json.getString("nodeId")
                val nodeId = java.math.BigInteger(nodeIdHex, 16).toLong()
                val pubkeyB64 = json.optString("pubkey", "")
                val name = json.optString("name", nodeIdHex.takeLast(6))
                val groupId = json.optString("groupId", "")
                val ss = if (json.has("ss")) json.getString("ss") else null
                val pw = if (json.has("pw")) json.getString("pw") else null
                QrInvitePayload(version, nodeId, pubkeyB64, name, groupId, ss, pw)
            }
        } catch (e: Exception) {
            Log.w("OFFTRACE", "QR: decode failed reason=${e.javaClass.simpleName}:${e.message}")
            null
        }

        /** FIX 2: pure decision core of onConnectionChangedInternal's real-
         *  vs-stale check — extracted so it's directly testable. True
         *  ("real teardown, must call handleRealGroupTeardown") iff
         *  [formedGeneration] is a genuine, currently-live generation
         *  number (>0); false ("stale/duplicate noise, ignore") for 0 —
         *  either nothing has ever formed yet, or this generation's
         *  teardown was already processed and reset. An "older
         *  generation" callback is, by construction, indistinguishable
         *  from "already processed" here — both correctly read as stale,
         *  since formedGeneration is reset to 0 the instant its teardown
         *  is handled, so a SECOND/duplicate/late callback for that same
         *  now-past generation can never be misread as a fresh real one. */
        fun isRealGroupTeardown(formedGeneration: Int): Boolean = formedGeneration > 0

        // ── FIX 1: two pre-real-nodeId keyspaces a WFD sighting can live
        // under before it's ever confirmed against roster/BLE, moved here
        // (unchanged bodies) from instance-level functions so they compose
        // with resolveDiscoveredDeviceSlot below and are directly testable.
        // SYNTHETIC_KEY_TAG (pre-existing, PHASE 8 TRACK A): a WFD sighting
        // whose DNS-SD shortId doesn't match any currently-known real
        // nodeId. ADDRESS_KEY_TAG (FIX 1a, new): a WFD sighting with NO
        // DNS-SD shortId at all yet — this is what makes a device visible
        // the instant it's discovered rather than only once a TXT record
        // has arrived. The two tags differ by bit 48 (SYNTHETIC has it set,
        // ADDRESS has it clear) so a key from either keyspace can never be
        // mistaken for the other, or — short of the same ~1-in-65536 risk
        // the original synthetic-key design already accepted — for a real
        // SHA-256-derived nodeId.
        private const val SYNTHETIC_KEY_TAG = 0x7FFF000000000000L
        private const val ADDRESS_KEY_TAG = 0x7FFE000000000000L

        /** shortId is remote-controlled TXT data (see onServiceTxtRecordFound's
         *  UNVERIFIED doc) — never trust it's valid hex; a malformed value just
         *  falls back to a stable hash instead of crashing the discovery path. */
        fun syntheticKeyFor(shortId: String): Long =
            SYNTHETIC_KEY_TAG or ((shortId.toLongOrNull(16) ?: shortId.hashCode().toLong()) and 0xFFFFFFL)
        // Exact top-16-bit equality, not an AND-subset check: SYNTHETIC_KEY_TAG
        // (0x7FFF...) and ADDRESS_KEY_TAG (0x7FFE...) differ by a single bit,
        // and 0x7FFE's bit pattern is a strict SUBSET of 0x7FFF's — an
        // AND-subset check (`id and TAG == TAG`) would therefore have
        // mis-classified every address key as ALSO a synthetic key (caught
        // by OfflineCallActivityTest's own collision test). `ushr 48`
        // compares the exact top 16 bits instead, so the two keyspaces are
        // genuinely mutually exclusive.
        fun isSyntheticKey(id: Long): Boolean = (id ushr 48) == (SYNTHETIC_KEY_TAG ushr 48)

        /** FIX 1a: a stable, collision-free key for a WFD sighting with no
         *  DNS-SD correlation yet — deviceAddress (a MAC) is a String, and
         *  nearbyDevices is keyed by Long, so this maps one onto the other.
         *  Pure function of the address alone: the SAME device sighted
         *  again (same MAC) always lands on the SAME key, so repeat
         *  sightings merge into one row instead of one-per-scan — see
         *  resolveDiscoveredDeviceSlot's doc for the one known limitation
         *  (MAC randomization across rescans, if the OS does that, would
         *  defeat this — an accepted tradeoff of keying by address at all,
         *  which is what this fix explicitly asks for). */
        fun addressKeyFor(deviceAddress: String): Long =
            ADDRESS_KEY_TAG or (deviceAddress.hashCode().toLong() and 0xFFFFFFL)
        fun isAddressKey(id: Long): Boolean = (id ushr 48) == (ADDRESS_KEY_TAG ushr 48)

        /** FIX 1a/1b: pure decision core of refreshNearbyDevices' WFD-
         *  discovery step. [shortId]=null (no TXT record has arrived at
         *  all for this deviceAddress) always yields [resolved]=false — the
         *  device still gets a key ([addressKeyFor]) and therefore still
         *  renders, just under Reachability.UNRESOLVED — this is the exact
         *  behavior change that fixes "1 device found, 0 rows shown".
         *  [realNodeIdForShortId] is a caller-supplied lookup (does this
         *  shortId match an already roster/BLE-known real nodeId) — kept
         *  as a plain nullable Long parameter rather than a lambda so this
         *  stays trivially testable. */
        data class DiscoveredDeviceSlot(val key: Long, val resolved: Boolean)
        fun resolveDiscoveredDeviceSlot(deviceAddress: String, shortId: String?, realNodeIdForShortId: Long?): DiscoveredDeviceSlot =
            if (shortId == null) DiscoveredDeviceSlot(addressKeyFor(deviceAddress), resolved = false)
            else DiscoveredDeviceSlot(realNodeIdForShortId ?: syntheticKeyFor(shortId), resolved = true)

        // ── FIX 3: "my id is ?" — the actual identity nodeId is available
        // synchronously at process start via OfflineIdentity.nodeId(context),
        // completely independent of mediaTransport (which stays null for the
        // entire pre-connection life of the app — see localShortId's doc).
        // nodeIdBytesToLong is the EXACT conversion
        // OfflineMediaTransport.localNodeId's own init block performs
        // (OfflineMediaTransport.kt:1033-1038) — pulled out here, pure, so
        // every DISPLAY site (and this test) never needs mediaTransport to
        // exist.
        fun nodeIdBytesToLong(nodeId: ByteArray): Long = java.nio.ByteBuffer.wrap(nodeId).long
        fun localShortIdFor(nodeIdBytes: ByteArray): String = shortIdBase32(nodeIdBytesToLong(nodeIdBytes))

        // TOPO PHASE 3.2: see ChatEntry.transport's doc — the one honest
        // transport value this app's architecture can ever report.
        const val TRANSPORT_WIFI_DIRECT = "Wi-Fi Direct"

        // TOPO PHASE 3.3: one quick-reaction row, 6 emoji, for gloved taps —
        // no custom picker (the system keyboard already sends emoji as
        // ordinary UTF-8 text on the existing CHAT payload).
        val QUICK_REACTION_EMOJI = listOf("👍", "🆗", "❤️", "😂", "😮", "🙏")

        // ── TOPO PHASE 3.4: voice messages ──────────────────────────────
        // Hard cap — enforced at record time, not just at send time, so a
        // stuck/forgotten hold can never produce an over-length clip.
        const val VOICE_NOTE_MAX_MS = 30_000L
        fun isVoiceNoteTooLong(durationMs: Long): Boolean = durationMs > VOICE_NOTE_MAX_MS

        // BATTERY GUARD (stated back per the task's own instruction, before
        // implementing it): this device must never CARRY (relay-and-store
        // on behalf of) another node's voice note once it's over 200KB,
        // UNLESS this device's battery is above 50% OR it's charging. The
        // OFFER (the small "someone nearby has a voice note for you"
        // announcement) is carried regardless of size/battery — only the
        // large payload itself waits for a direct link. Below 200KB, size
        // never blocks carrying at all (only large clips are gated).
        const val VOICE_NOTE_CARRY_SIZE_THRESHOLD_BYTES = 200 * 1024

        /** Pure decision core — battery percent/charging exactly as
         *  BatteryCliff already reads them elsewhere in this file. Returns
         *  true iff the PAYLOAD may be carried right now; the OFFER is a
         *  separate, always-true path (see [shouldCarryVoiceNoteOffer]). */
        fun shouldCarryVoiceNotePayload(sizeBytes: Int, batteryPct: Int, charging: Boolean): Boolean =
            sizeBytes <= VOICE_NOTE_CARRY_SIZE_THRESHOLD_BYTES || batteryPct > 50 || charging

        /** The offer is carried unconditionally — battery/size never gate
         *  it. A separate, trivial function rather than folding into
         *  [shouldCarryVoiceNotePayload] so each call site's intent (offer
         *  vs. payload) is explicit and can never be accidentally swapped. */
        fun shouldCarryVoiceNoteOffer(): Boolean = true
    }

    // PHASE 1.2/1.5: see mergePartyView/formatPartyStatusLine above for the
    // pure logic; not private anymore so OfflineCallActivityTest can
    // construct these directly.
    enum class PartyOrigin { IN_ROSTER, LEDGER_ONLY }
    data class PartyEntry(val nodeId: Long, val name: String, val origin: PartyOrigin)

    private lateinit var wifiDirect: WifiDirectManager
    // PART 1.3: [wifiDirect] typed as the interface for call sites that map
    // cleanly onto it — see PART 1 OUTPUT's leak list for every call site
    // that does NOT and stays on [wifiDirect] directly.
    private val transport: MeshTransport get() = wifiDirect
    // PART 1.4: registered with [wifiDirect] once, in onCreate. Not yet read
    // by anything else in this Activity — see MeshTransportRegistry's own
    // doc for why "one transport registered, behaviour indistinguishable
    // from today" is a real guarantee, not an approximation.
    private val transportRegistry = MeshTransportRegistry()
    private var signaling: LocalSignaling? = null

    private lateinit var statusText: TextView
    private lateinit var peerListView: ListView
    private lateinit var searchButton: Button
    // FIX 4: radar discovery UI — a second, separate view alongside
    // peerListView (never a replacement for it, see radarViewToggleButton),
    // built from the SAME nearbyDevices map via buildRadarDevices(), and
    // tapping a dot routes through the SAME sendInvite() choke point every
    // other invite entry point already uses — see DiscoveryRadarView's
    // class doc.
    private lateinit var discoveryRadarView: DiscoveryRadarView
    private lateinit var radarStatusText: TextView
    private lateinit var radarViewToggleButton: Button
    private var radarViewEnabled = true
    // PART "HASSLE-FREE JOIN" item 4: the Nearby screen's new primary UI —
    // "Start a group," the BLE open-groups list, and "Scan a code." The
    // OLD per-peer invite list above (peerListView/discoveryRadarView/
    // radarViewToggleButton/radarStatusText/searchButton) is set GONE by
    // default now (see buildUi) rather than deleted outright — ripping out
    // every one of their ~80 other call sites (buildNearbyDeviceRow,
    // sendInvite, the mid-call mid-group "invite more people" flow, the
    // radar tap handler, refreshNearbyDevices) safely, in one pass, in a
    // 7000-line file this interconnected, was judged more risk than this
    // task's remaining scope justified — see this task's own OUTPUT for
    // that call stated plainly. What ships here is what item 4 actually
    // asked for from the USER'S side: the Nearby screen a person sees and
    // uses is the two things it names, nothing else.
    private lateinit var startHostingButton: Button
    private lateinit var openGroupsListView: ListView
    private lateinit var openGroupsEmptyLabel: TextView
    private lateinit var scanCodeButton: Button
    private lateinit var openGroupsAdapter: OpenGroupsAdapter
    private data class OpenGroupSighting(val hostNodeId: Long, var rssi: Int, var lastSeenAtMs: Long)
    private val openGroupSightings = LinkedHashMap<Long, OpenGroupSighting>()

    private inner class OpenGroupsAdapter : BaseAdapter() {
        fun rows(): List<OpenGroupSighting> = openGroupSightings.values.sortedByDescending { it.rssi }
        override fun getCount(): Int = rows().size
        override fun getItem(position: Int): OpenGroupSighting = rows()[position]
        override fun getItemId(position: Int): Long = rows()[position].hostNodeId
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val sighting = rows()[position]
            val density = resources.displayMetrics.density
            val row = LinearLayout(this@OfflineCallActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = (56 * density).toInt()
                setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
            }
            row.addView(TextView(this@OfflineCallActivity).apply {
                text = "Group ${MeshFrame.hex(sighting.hostNodeId).takeLast(6)} · ${sighting.rssi}dBm"
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this@OfflineCallActivity).apply {
                text = "Join"
                setOnClickListener { joinOpenGroup(sighting.hostNodeId) }
            })
            return row
        }
    }

    /** 2.2: "one tap joins" — the no-camera path, sharing the exact same
     *  discovery-free explicit-group [wifiDirect.connect] call
     *  [handleScannedQr] uses (see that function's own doc for why a
     *  placeholder [WifiP2pDevice] is correct here — the explicit branch
     *  never reads its address), with credentials RE-DERIVED locally from
     *  [hostNodeId] rather than transmitted (see
     *  WifiDirectManager.deriveHostNetworkName/deriveHostPassphrase). */
    private fun joinOpenGroup(hostNodeId: Long) {
        if (connectRequested || isLocalGroupOwner) return
        connectRequested = true
        formedAtLeastOnceThisAttempt = false
        val networkName = WifiDirectManager.deriveHostNetworkName(hostNodeId)
        val passphrase = WifiDirectManager.deriveHostPassphrase(hostNodeId)
        Log.d("OFFTRACE", "INVITE: path=connect-ble peer=${MeshFrame.hex(hostNodeId)} reason=ble-open-group")
        wifiDirect.connect(WifiP2pDevice(), MeshFrame.hex(hostNodeId), networkName, passphrase) { ok ->
            runOnUiThread { if (!ok) connectRequested = false }
        }
        Toast.makeText(this, "Joining…", Toast.LENGTH_SHORT).show()
    }

    private fun refreshOpenGroupsEmptyState() {
        openGroupsEmptyLabel.visibility = if (openGroupSightings.isEmpty()) View.VISIBLE else View.GONE
    }

    /** A host that stopped advertising (left, filled up) must eventually
     *  drop off this list even though nothing tells us so directly — same
     *  "prune what we haven't heard from in a while" posture as any other
     *  presence signal in this app. Piggybacks on the existing 3s
     *  refreshNearbyDevices tick rather than its own timer. */
    private fun pruneStaleOpenGroups() {
        val cutoff = System.currentTimeMillis() - OPEN_GROUP_STALE_MS
        val removed = openGroupSightings.values.removeAll { it.lastSeenAtMs < cutoff }
        if (removed) {
            openGroupsAdapter.notifyDataSetChanged()
            refreshOpenGroupsEmptyState()
        }
    }

    /** PART "HASSLE-FREE JOIN" 2.2: [MeshBleBeacon] is a process-wide
     *  singleton independent of [mediaTransport] (which stays null for
     *  this device's entire pre-connection life — see [localNodeId]'s own
     *  doc), so this is a SEPARATE start() call from
     *  [OfflineMediaTransport]'s own (line ~3071, unchanged, still runs
     *  once a session exists) — the join-discovery path this task adds
     *  has to work BEFORE any connection, which the transport-owned start
     *  call alone never covers. [MeshBleBeacon.start] is idempotent
     *  ("if (running) return"), so calling it from both places is safe.
     *  Also called from [leaveGroup] — that path's own
     *  `mediaTransport?.stop()` can stop this same singleton (no
     *  reference counting exists between the two owners), so the Nearby
     *  screen's own discovery needs to explicitly resume it. */
    private fun startPreConnectionBleDiscovery() {
        MeshBleBeacon.get(applicationContext).apply {
            onOpenGroupSeen = { hostNodeId, rssi ->
                openGroupSightings[hostNodeId] = OpenGroupSighting(hostNodeId, rssi, System.currentTimeMillis())
                openGroupsAdapter.notifyDataSetChanged()
                refreshOpenGroupsEmptyState()
            }
            start(localNodeId())
        }
    }
    // TOPO PART B9: nearbyHeader wraps statusText — see buildUi's doc for
    // why this replaced a permanent root-level statusText + displayNameButton.
    private lateinit var nearbyHeader: LinearLayout
    private lateinit var hangupButton: Button
    private lateinit var searchScreen: LinearLayout
    private lateinit var callScreen: LinearLayout
    private lateinit var errorText: TextView

    // PHASE 3: roster screen — shown after a WiFi Direct group is joined, before any
    // call is placed. Lists every member (self included); tapping one opens the same
    // call-mode dialog the old peer-tap flow had, tapping "Group chat" opens the
    // chat overlay in broadcast mode.
    private lateinit var groupScreen: LinearLayout
    private lateinit var rosterListView: ListView
    private var roster: List<RoutingTable.Member> = emptyList()

    // ── PHASE 3: Home screen restructure ─────────────────────────────────────
    private lateinit var identityShortIdText: TextView
    private lateinit var identityNameText: TextView
    private lateinit var groupCallVoiceButton: Button
    private lateinit var groupCallVideoButton: Button
    private lateinit var groupCallReasonText: TextView
    private lateinit var groupMembersHeader: TextView
    // Signal Deck (diagnostic follow-up): the always-visible ring card's
    // own text — replaces ringSummaryButton/ringExpanded (deleted, no
    // collapse state left to track).
    private lateinit var signalDeckDimLabel: TextView
    private lateinit var signalDeckGroupNameText: TextView
    private lateinit var signalDeckStatusText: TextView
    private lateinit var themeToggleButton: Button

    // PHASE 5A: SOS / FIND-over-mesh — roster screen only, no new Activity/screen.
    private lateinit var sosButton: Button
    private lateinit var sosAlertsText: TextView
    private var sosActive = false

    // ── OFFLINE UI: party ring home screen + phrase grid + safety shell ─────
    private lateinit var partyRingView: PartyRingView
    private lateinit var slideToSosView: SlideToSosView
    private lateinit var phraseGrid: GridLayout
    private lateinit var carryChip: TextView
    // Signal Deck (diagnostic follow-up): nightModeButton DELETED — replaced
    // by the top-right circular icon (buildThemeToggleIcon), see Step 2's
    // commit. Theme toggle state/persistence (nightModeEnabled/setNightMode)
    // is unchanged, only this button is gone.
    private lateinit var batteryCliffBanner: TextView
    private var nightModeEnabled = false
    private var inBatteryCliff = false
    // PHASE 1.6a: the ring's data pipeline (renderPartyStatus ->
    // refreshPartyRowTexts -> feedPartyRing -> setPeers) used to run ONLY
    // while partyStatusOverlay (a separate, normally-GONE "where is
    // everyone" overlay) was visible — meaning on an ordinary Home session
    // it never ran at all: zero ring markers, all-empty range labels, even
    // though the status line (a different code path) showed real numbers.
    // Defaults true (Home is on screen at launch). PART 1 (batch A):
    // updateForegroundState() is what sets this now — true unless a
    // full-bleed call, an open message thread, or the Groups/Settings
    // overlay is covering Home.
    private var homeTabVisible = true
    // True whenever party-ring/party-status data should be kept fresh:
    // Home showing (ring visible) OR the overlay showing — never
    // unconditionally, since a refresh loop ticking while the Activity is
    // backgrounded or a call is full-screen would waste battery for no
    // visible benefit (the opposite of this app's purpose). onResume/
    // onPause already gate the refresh loop's existence entirely; this
    // gates what it does on top of that.
    private fun shouldRefreshPartyData(): Boolean =
        homeTabVisible || (::partyStatusOverlay.isInitialized && partyStatusOverlay.visibility == View.VISIBLE)

    // ── PART 1 (batch A): single vertical scroll, no nested nav bar ─────────
    // mainScroll/scrollBody replace the old contentFrame+bottomNav pair.
    // scrollBody holds, top to bottom: the Nearby section (nearbyFrame —
    // searchScreen/groupScreen, exactly the same mutual-exclusion pattern
    // applyHomeVisibility already used, just re-parented) and the lazily
    // scroll-triggered Messages/Calls sections (see addLazySection). Groups
    // and Settings are no longer sections at all — groupsOverlay/
    // offlineSettingsOverlay wrap their EXISTING, UNCHANGED
    // buildGroupsScreen()/buildSettingsScreen() output as full-screen
    // overlays instead (opened by a header tap / EXTRA_OPEN_SETTINGS_TAB).
    //
    // callScreen and groupCallScreen are deliberately NOT scrollBody
    // children — both need a bounded/full viewport (videoFrame's own
    // SurfaceView letterboxing, groupCallGrid's weight=1f) that a
    // WRAP_CONTENT scroll section cannot give them without rewriting that
    // layout logic (PART 1.7: re-host, don't rewrite). Both stay direct
    // `root` children with weight=1f, full-bleed over everything else
    // (header/row/mainScroll hidden) while active — exactly how
    // groupCallScreen already worked pre-batch-A; callScreen now gets the
    // identical treatment via setFullBleedCallActive. messagesThreadView
    // (weight=1f ListView + composer) has the same problem and gets the
    // same fix — see openMessageThread/closeOpenMessageThread.
    private lateinit var mainScroll: android.widget.ScrollView
    private lateinit var scrollBody: LinearLayout
    private lateinit var startGroupScanRow: LinearLayout
    private lateinit var groupsOverlay: LinearLayout
    private lateinit var offlineSettingsOverlay: LinearLayout
    private lateinit var overlayRoot: FrameLayout
    // TOPO PHASE 1.1: declared at class level, not inside TopoPalette's
    // companion object — a type nested inside a companion object is only
    // reachable from outside as Outer.Companion.Nested, not the shorter
    // Outer.Nested a test file would need (this exact gotcha bit
    // PartyRingView.RenderMode and OfflineMediaTransport.RelayDedupeCache
    // earlier in this project).
    enum class TopoMode { DAY, NIGHT, CLIFF }

    private var groupsScreenView: LinearLayout? = null
    private var messagesScreenView: LinearLayout? = null
    private var callsScreenView: LinearLayout? = null
    private var settingsScreenView: android.widget.ScrollView? = null
    // TOPO PHASE 2.2/2.3: the SOS section — a full-screen overlay opened by
    // the always-visible top-right SOS button, built via the SAME
    // buildFullScreenOverlay mechanism sosOverlay/partyStatusOverlay
    // already use. Deliberately a SEPARATE overlay from sosOverlay: that
    // one is the REACTIVE "someone else's SOS is going off, here it is,
    // silence it" alert; this one is the PROACTIVE "arm/manage MY OWN SOS
    // + settings" control panel — different concerns, see each one's doc.
    private lateinit var sosSectionOverlay: LinearLayout
    private lateinit var groupAlertBar: TextView

    private var needHelpArmedAtMs = 0L
    private val phraseMessages = mutableListOf<PhraseMessageEntry>()
    private data class PhraseMessageEntry(
        val msgId: String?,
        val fromName: String,
        val code: Int,
        val sentByMe: Boolean,
        val carrierName: String?,
        val hopCount: Int?
    )
    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            evaluateBatteryCliff()
        }
    }
    private val sosEntries = mutableMapOf<Long, MeshSosManager.SosEntry>()
    private val findResponses = mutableMapOf<Long, MeshSosManager.SosEntry>()

    // PHASE 5BC: full-screen SOS alert (over roster AND over an active call) plus
    // a separate party-status screen — both are the SAME overlay container
    // repurposed, since only one is ever shown at a time and they share almost
    // all of their per-member rendering. Neither touches videoFrame/GroupTile/
    // the call surfaces — this is a sibling layer stacked ABOVE the existing
    // `root` LinearLayout inside a wrapping FrameLayout (see buildUi).
    private lateinit var sosOverlay: LinearLayout
    private lateinit var sosOverlayBody: LinearLayout
    private lateinit var partyStatusOverlay: LinearLayout
    private lateinit var partyStatusOverlayBody: LinearLayout
    private lateinit var partyStatusButton: Button

    // ── PEER DIRECTION READOUT ──────────────────────────────────────────────
    // Persistent per-row views (name mirrors GroupTile's reparent-not-recreate
    // pattern) so the arrow can be updated at full sensor rate independently
    // of the text, which is recomputed at a throttled 4Hz — see
    // startPartyRowRefreshLoop/updatePartyArrows. Party-status screen only;
    // the SOS overlay (renderSosOverlay/buildMemberCard) shows the same
    // vector TEXT but no arrow and no live tracking, unchanged from its
    // existing on-demand-only rendering.
    private class PartyRow(val card: LinearLayout, val arrowView: TextView, val summaryLine: TextView) {
        var lastBearingTrue: Float = Float.NaN
    }
    private val partyRows = LinkedHashMap<Long, PartyRow>()
    private val partyRefreshHandler = Handler(Looper.getMainLooper())
    private var partyRefreshRunnable: Runnable? = null
    private val meshCompass: MeshCompass by lazy { MeshCompass.get(applicationContext) }
    private lateinit var sosAlarm: SosAlarm
    // PHASE 6 TRACK B: hands-free trigger settings entry point.
    private lateinit var sosSettingsButton: Button
    private val handledRelayPrompts = mutableSetOf<String>()
    // PHASE 6 TRACK D: last-resort SSID broadcast — same direct-singleton
    // pattern as sosAlarm above (doesn't need any OfflineMediaTransport wiring,
    // it operates at the WifiP2pManager level, independent of the mesh frames).
    private lateinit var sosSsidBroadcast: SosSsidBroadcast
    private lateinit var ssidBroadcastButton: Button

    // B5 (diagnostic follow-up): push-to-talk voice notes — recorder is
    // per-Activity-instance (not a process-wide singleton, see its own
    // class doc), player is swapped out (stop previous, start next) so
    // only one voice note ever plays at a time.
    private val voiceNoteRecorder: VoiceNoteRecorder by lazy { VoiceNoteRecorder(applicationContext) }
    private var voiceNotePlayer: android.media.MediaPlayer? = null
    // Step 2 (diagnostic follow-up): identifies the playing entry by
    // ChatEntry.id, not a row View — see toggleVoiceNoteEntryPlayback's doc.
    private var playingChatEntryId: Long? = null
    private lateinit var voiceNoteHoldButton: Button
    // Step 4: scattered-for-now entry point — Step 8 replaces this (and the
    // other per-kind buttons Steps 5-7 add) with one consolidated "+" menu.
    private lateinit var imageAttachmentButton: Button
    private lateinit var documentAttachmentButton: Button
    private lateinit var locationAttachmentButton: Button
    private lateinit var contactAttachmentButton: Button

    // PHASE 6 TRACK E: self-healing GO re-election state — see handleGoLost/
    // handleElectionResult/becomeNewGoAfterElection/waitForInviteAfterElection.
    private var reconnectingAfterGoLoss = false
    private var groupCallModeBeforeGoLoss: OfflineMediaTransport.GroupCallMode? = null
    // Auto-invite dedupe during the post-election window — invitePeer() per
    // discovered device address at most once per election, not once per
    // onPeersChanged tick (which fires repeatedly while discovery runs).
    private val invitedDuringElection = mutableSetOf<String>()
    // FIX 6: true once this device is confirmed as this group's owner — gates the
    // "Add to group" invite rows (GO-only) on the roster screen, and the discovery
    // this device keeps running while there to keep finding invitable peers.
    private var isLocalGroupOwner = false

    // PHASE 3C: group call screen — a responsive GRID of per-participant tiles
    // (live video, letterboxed, or an avatar+initials placeholder when that
    // participant's camera is off), replacing the old single-active-speaker view.
    // A separate screen from callScreen (1:1) entirely, so the existing 1:1/group-
    // chat flow is untouched (point 12) — the two share the transport's camera/
    // decoder pipeline (mutually exclusive, never both active) but not any UI.
    private lateinit var groupCallScreen: LinearLayout
    private lateinit var groupCallGrid: GridLayout
    private lateinit var groupCallStatusText: TextView
    private lateinit var groupCallCameraButton: Button
    private lateinit var groupCallMicButton: Button
    private lateinit var leaveGroupCallButton: Button
    private var groupCallParticipants: List<Long> = emptyList()
    private var groupCallActiveSpeaker: Long? = null
    private var groupCallPinned = false
    private var groupCallMode: OfflineMediaTransport.GroupCallMode? = null
    // Per-participant camera on/off, mirrored from the transport's TYPE_CAM state —
    // drives each tile's live-video-vs-avatar choice.
    private var groupCallCamStates: MutableMap<Long, Boolean> = mutableMapOf()
    private var groupCallLocalCameraOn = false
    private var groupCallLocalMicMuted = false
    // Non-null while one tile is shown fullscreen (tap-to-focus, point 9); tapping
    // that same tile again clears it and returns to the grid.
    private var groupCallFocusedTile: Long? = null

    /** One grid tile's views, built once per participant and reused across grid
     *  rebuilds (reparented, never recreated) so its SurfaceView/decoder binding
     *  survives a layout change — only [videoWidth]/[videoHeight] (the last known
     *  decoded size, for re-letterboxing) are mutable. */
    private data class GroupTile(
        val container: FrameLayout,
        val surfaceView: SurfaceView,
        val avatarText: TextView,
        val nameLabel: TextView,
        val speakingDot: TextView,
        val batteryText: TextView,
        // PHASE 8 STEP 2/4: a dim scrim + status line, shown over the
        // surface for two DIFFERENT reasons — "video unavailable" (decoder
        // broken, STEP 2) or "not decoded right now" (tile budget, STEP 4,
        // frame stays frozen underneath since the surface just isn't being
        // fed anymore) — see updateTileContent for which text wins if both
        // were ever somehow true at once (degraded takes priority).
        val statusScrim: View,
        val statusText: TextView,
        var videoWidth: Int = 0,
        var videoHeight: Int = 0
    )
    private val groupTiles = LinkedHashMap<Long, GroupTile>()

    // Direct-MediaCodec transport — the offline call's entire media path
    // (camera/mic/encode/decode/socket), independent of WebRTC.
    private lateinit var videoFrame: FrameLayout
    private var mediaRemoteView: SurfaceView? = null
    private var mediaTransport: OfflineMediaTransport? = null
    private var mediaSurface: Surface? = null

    // TASK 1: the decoder's actual reported size (post-crop), used to letterbox
    // mediaRemoteView instead of letting it stretch to fill videoFrame.
    private var remoteVideoWidth = 0
    private var remoteVideoHeight = 0

    // TASK 2: minimal in-memory chat overlay — no persistence, cleared on hangup/link-lost.
    // TOPO PHASE 3.2: [transport] is read from the actual receive path, not
    // guessed — confirmed by inspection that OfflineMediaTransport.kt's
    // only inbound/outbound frame path is java.net.Socket/ServerSocket over
    // the Wi-Fi Direct link (grepped for BluetoothSocket/BLE frame
    // handling: none exists — MeshBleBeacon.kt is presence/RSSI-only,
    // never carries a MeshFrame). So every chat frame's real transport is
    // unambiguously "Wi-Fi Direct" in this app today; there is no genuine
    // Bluetooth data-path case to distinguish. Per the task's own "if it
    // cannot be determined, omit rather than guess" rule, this constant is
    // the honest answer, not a placeholder — a UI label that changed
    // per-message would be fabricating a distinction this app's transport
    // layer doesn't have.
    /** Step 3 (diagnostic follow-up): [attachment] is null for an ordinary
     *  text entry — when set, ChatAdapter.getView renders a kind-specific
     *  row (Play/Download/etc., via AttachmentRef's own fetch state)
     *  instead of entry.text (text still carries a sensible fallback
     *  label). [id] is a local-only identity for tracking which entry is
     *  currently playing — ListView recycles row Views on
     *  notifyDataSetChanged, so a View reference can't survive as playback
     *  state; see toggleAttachmentPlayback's doc. Never sent over the wire. */
    private data class ChatEntry(
        val text: String,
        val fromMe: Boolean,
        val transport: String = TRANSPORT_WIFI_DIRECT,
        val attachment: AttachmentRef? = null,
        val id: Long = System.nanoTime()
    )
    private val chatMessages = mutableListOf<ChatEntry>()
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var chatListView: ListView
    private lateinit var chatInput: EditText
    private lateinit var bottomPanel: LinearLayout

    // TOPO PHASE 3.1: per-thread preview state — keyed by nodeId, or
    // MeshFrame.BROADCAST_ID for the group thread (the same sentinel
    // OfflineMediaTransport.sendGroupChat already targets). Populated
    // ADDITIVELY alongside the existing appendChatMessage/onTransportChatMessage
    // (their own behaviour is unchanged) so the Messages tab's thread list
    // has something to read without altering the working chat overlay at
    // all. In-memory only, same lifetime as chatMessages itself.
    private data class ThreadPreview(var lastText: String, var lastFromMe: Boolean, var lastAtMs: Long, var unread: Int)
    private val threadPreviews = mutableMapOf<Long, ThreadPreview>()

    /** B2 (diagnostic follow-up): the chat-wipe half of DuressPin.trigger()
     *  — chatMessages/threadPreviews are private, in-memory, instance-scoped
     *  state, so DuressPin (a standalone Context-only object) cannot reach
     *  them; a caller wiring the duress trigger inside a live
     *  OfflineCallActivity calls this directly instead. Not wired to
     *  anything yet — see DuressPin's own class doc for the open question
     *  on where the trigger itself lives. Safe to call even if chat was
     *  never opened this session (chatAdapter may not exist yet). */
    fun wipeChatForDuress() {
        chatMessages.clear()
        threadPreviews.clear()
        if (::chatAdapter.isInitialized) chatAdapter.notifyDataSetChanged()
        Log.w("OFFTRACE", "DURESS: chat wiped")
    }

    // Which thread's overlay is currently open — that thread's unread count
    // never increments while it's the one on screen.
    private var openThreadKey: Long? = null

    // PHASE 3: callScreen is now shared between a real 1:1 call and the group-chat-only
    // screen (no camera/mic involved, no placeCall) — this flag distinguishes them for
    // onSendChatClicked/onHangupClicked.
    private var isGroupChatScreen = false
    private var connectedPeerName = ""
    // TOPO PHASE 3.1: nodeId counterpart to connectedPeerName — set at the
    // exact same 5 call sites (mirroring that field), so the outgoing
    // thread key for 1:1 chat is known without touching
    // OfflineMediaTransport's private activeCallPeerId.
    private var connectedPeerId: Long? = null
    private lateinit var modeInfoBar: LinearLayout
    private lateinit var peerNameText: TextView
    private lateinit var callTimerText: TextView
    private val timerHandler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null
    private var callStartElapsedMs = 0L

    private var devices: List<WifiP2pDevice> = emptyList()
    private var p2pEnabled = false
    private var searchPending = false
    // BUG (MAKE THE INVITE ACTUALLY TRANSMIT) FIX PART 1.3/2: last observed
    // WifiP2pDevice.status per address — Android exposes no direct callback
    // for provision-discovery/GO-negotiation events (see
    // WifiDirectManager.issueConnect's doc); this status field transitioning
    // AVAILABLE -> INVITED -> CONNECTED on WIFI_P2P_PEERS_CHANGED_ACTION is
    // the nearest proxy the framework gives an app at all.
    private val lastKnownPeerStatus = mutableMapOf<String, Int>()
    // A peer address this device has already surfaced an incoming-invite
    // dialog/notification for — an invite this device hasn't yet
    // accepted/declined keeps re-appearing in every peers-changed broadcast
    // otherwise (same "don't re-announce" shape as A5's announcedIncoming).
    private val announcedIncomingP2pInvite = mutableSetOf<String>()


    // BUG 2 FIX: deviceAddress -> display name, from Wi-Fi Direct DNS-SD TXT
    // records (see WifiDirectManager.onServiceTxtRecordFound) — a PRE-CONNECT
    // hint only. UNVERIFIED: no signature exists before HELLO, so this must
    // never be treated as authenticated or cached as a verified name; once
    // connected, the signed HELLO/ROSTER name (via nameFor/nameForGroupParticipant)
    // takes over completely and this map is never consulted again for that peer.
    private val dnsSdNamesByAddress = mutableMapOf<String, String>()
    // PHASE 8 TRACK A: MAC address -> short nodeId hex (the "id" TXT field —
    // see WifiDirectManager.registerLocalService's doc), same UNVERIFIED
    // pre-connect status as dnsSdNamesByAddress. This is what lets the
    // nearby list correlate "this WFD device" with "this BLE-seen nodeId" —
    // without it, a device seen over BOTH transports would show as two
    // separate rows since MAC address and nodeId are otherwise unrelated
    // keyspaces before HELLO ever runs.
    private val dnsSdShortIdByAddress = mutableMapOf<String, String>()

    // ── PHASE 8 TRACK A / FIX 1: merged nearby-device list + one-tap invite ─────
    // Single source of truth for "every OCP device this phone currently knows
    // about", keyed by nodeId once resolved OR by a stable synthetic/address
    // key before that (see DiscoveredDeviceSlot's doc) — a device seen over
    // BOTH Wi-Fi Direct and BLE must resolve to exactly one row, see A2. FIX 1:
    // a raw WifiP2pDevice enters this map IMMEDIATELY on discovery, under
    // Reachability.UNRESOLVED, keyed by its deviceAddress — it no longer waits
    // for a DNS-SD TXT record to exist at all (that was the "1 device found,
    // 0 rows shown" bug: this map used to require one).
    // OCP CONNECT REBUILD PART 5: non-private (matching PartyOrigin's own
    // precedent above) so resolveVerdict/buildNearbyDeviceRow's label logic
    // is directly testable.
    enum class Reachability { UNRESOLVED, WIFI_DIRECT, BLE_ONLY, IN_GROUP }
    // BUG (DISCOVERY IS ONE-SHOT) FIX: see resolveVerdict's doc (companion
    // object) for why this lives here rather than beside it.
    enum class ResolveVerdict { RESOLVED, STILL_RESOLVING, NOT_AN_OPENCALL_DEVICE }
    private enum class InviteStatus { NOT_INVITED, INVITING, JOINED, FAILED }
    private data class NearbyDevice(
        val nodeId: Long,
        var wifiDevice: WifiP2pDevice?,
        var blePresence: MeshLedger.BlePresence?,
        var displayName: String,
        var reachability: Reachability,
        var inviteStatus: InviteStatus = InviteStatus.NOT_INVITED,
        var failReason: String? = null,
        // FIX 1b: the identity DiscoveryRadarView's stableAngleDeg is keyed
        // on — set ONCE, at this row's first creation, and carried forward
        // verbatim across every later upgrade/rekey (address key -> real
        // nodeId). [nodeId] itself DOES change across that rekey (a new
        // NearbyDevice object replaces the old one, keyed differently in
        // [nearbyDevices]); radarSeed deliberately does not, so the row's
        // on-screen position never jumps just because it got resolved.
        val radarSeed: Long = nodeId,
        // BUG (DISCOVERY IS ONE-SHOT) FIX: stamped once, at this row's FIRST
        // sighting — same "set once, carried forward across every
        // upgrade/rekey" contract as radarSeed above, since it answers "how
        // long has THIS physical device been visible", not "how long has its
        // current key existed". Backs RESOLVE_GIVEUP_MS: a device still
        // UNRESOLVED this long after first appearing is not an OpenCall
        // device (it will never publish a TXT record), not "still resolving".
        val firstSeenAtMs: Long = SystemClock.elapsedRealtime()
    )
    private val nearbyDevices = LinkedHashMap<Long, NearbyDevice>()
    // Every nodeId THIS device has tapped Invite on — the other half of A5's
    // "who initiated" test: a roster member who resolves WITHOUT this device
    // ever having invited them is, by construction, someone who invited US.
    private val pendingInvites = mutableSetOf<Long>()
    private lateinit var nearbyDeviceAdapter: NearbyDeviceAdapter
    private lateinit var inviteListView: ListView
    private lateinit var inviteListAdapter: NearbyDeviceAdapter
    // A4: set only while showMidCallInviteDialog()'s dialog is on screen.
    private var midCallDialogAdapter: NearbyDeviceAdapter? = null
    private val nearbyRefreshHandler = Handler(Looper.getMainLooper())
    private var nearbyRefreshScheduled = false
    private var groupCallInviteButton: Button? = null

    /** BLE-only rows are actionable (A3 says every row gets an invite button,
     *  with no distinction drawn for BLE-only reachability) — tapping one just
     *  means "go find them over Wi-Fi Direct first"; this is how long an
     *  invite stays in that searching state before giving up. */
    private val bleInviteWfdTimeoutMs = 20_000L

    // The DNS-SD TXT "id" field is only a 6-hex-char/24-bit SUFFIX of a real
    // nodeId (see WifiDirectManager.registerLocalService — full 64-bit ids
    // don't fit a TXT record's size budget), so a Wi-Fi-Direct sighting with
    // no BLE/roster overlap yet can never be resolved to its real nodeId
    // before actually connecting. Such a device is still shown (A2 requires
    // every OCP device be listed) under a SYNTHETIC key instead — see
    // syntheticKeyFor/addressKeyFor/resolveDiscoveredDeviceSlot in the
    // companion object above — and gets migrated onto its real nodeId the
    // moment BLE or the roster resolves the same short id (see step 2/3 below).

    /** Rebuilds [nearbyDevices] from the three live sources (roster, Wi-Fi
     *  Direct discovery + DNS-SD correlation, BLE presence) — the single choke
     *  point every trigger (peers-changed, a TXT record arriving, a roster
     *  update, the periodic BLE-refresh tick) funnels through, so the merged
     *  list is always assembled the same way. Preserves each existing entry's
     *  inviteStatus/failReason across a rebuild — those are UI-invite state,
     *  not discovery state, and must never reset just because a scan tick ran. */
    // FIX 1d: throttled once per device per 10s — a device that's genuinely
    // never going anywhere (already in-group, or self) would otherwise log
    // on every ~3s refresh tick forever.
    private val lastDroppedLogAtMs = mutableMapOf<String, Long>()
    private fun logDroppedDevice(d: WifiP2pDevice, why: String) {
        val now = System.currentTimeMillis()
        val last = lastDroppedLogAtMs[d.deviceAddress] ?: 0L
        if (now - last < 10_000L) return
        lastDroppedLogAtMs[d.deviceAddress] = now
        Log.d("OFFTRACE", "NEARBY: dropped ${d.deviceAddress} name=${d.deviceName} reason=$why")
    }

    private fun refreshNearbyDevices() {
        val localId = mediaTransport?.localNodeId
        val inGroupIds = roster.mapNotNull { it.nodeId.takeIf { id -> id != localId } }.toSet()

        // 1) Roster — authoritative, verified names, always wins. Also
        // promotes any synthetic-keyed row for this same physical device
        // (matched by short-id suffix) onto the now-known real nodeId.
        roster.forEach { m ->
            if (m.nodeId == localId) return@forEach
            promoteSyntheticEntry(m.nodeId)
            val existing = nearbyDevices[m.nodeId]
            nearbyDevices[m.nodeId] = (existing ?: NearbyDevice(m.nodeId, null, null, m.name, Reachability.IN_GROUP)).apply {
                displayName = m.name
                reachability = Reachability.IN_GROUP
                inviteStatus = InviteStatus.JOINED
                failReason = null
            }
        }

        // 2) Wi-Fi Direct discovery. FIX 1a: every raw sighting gets a row —
        // this no longer waits for a DNS-SD TXT record to exist at all (that
        // was the "1 device found, 0 rows shown" bug: dnsSdShortIdByAddress
        // could easily still be empty for a device seen only 13ms earlier by
        // onPeersChanged, since TXT records arrive over a SEPARATE, slower
        // service-discovery scan). No shortId yet -> keyed by deviceAddress,
        // Reachability.UNRESOLVED (resolveDiscoveredDeviceSlot). A shortId
        // that matches a real (BLE/roster-known) nodeId, or else a shortId-
        // synthetic key, both count as resolved -> WIFI_DIRECT, same as before.
        devices.forEach { d ->
            val shortId = dnsSdShortIdByAddress[d.deviceAddress]
            val realNodeIdForShortId = shortId?.let { sid ->
                nearbyDevices.keys.firstOrNull { !isSyntheticKey(it) && !isAddressKey(it) && MeshFrame.hex(it).takeLast(6) == sid }
            }
            val slot = resolveDiscoveredDeviceSlot(d.deviceAddress, shortId, realNodeIdForShortId)
            val nodeId = slot.key
            val addressKey = addressKeyFor(d.deviceAddress)
            if (nodeId in inGroupIds || nodeId == localId) {
                logDroppedDevice(d, "already in-group or self")
                if (addressKey != nodeId) nearbyDevices.remove(addressKey) // FIX 1b: clean up a now-superseded UNRESOLVED ghost row
                nearbyDevices[nodeId]?.wifiDevice = d
                return@forEach
            }
            val name = dnsSdNamesByAddress[d.deviceAddress] ?: d.deviceName ?: shortId ?: d.deviceAddress
            val reachabilityNow = if (slot.resolved) Reachability.WIFI_DIRECT else Reachability.UNRESOLVED
            if (nodeId != addressKey) {
                // FIX 1b: resolved THIS pass, under a key different from the
                // address key it may have been tracked under before — UPGRADE
                // any prior UNRESOLVED sighting IN PLACE: same radarSeed (no
                // radar jump), same inviteStatus/failReason (no in-flight
                // invite lost), removed from its old key so it is never
                // rendered twice. "Replace the display name" (not just
                // fill-if-blank) per this fix's own spec, since a resolved
                // name is strictly more authoritative than the raw WFD one.
                val priorUnresolved = nearbyDevices.remove(addressKey)
                val existing = nearbyDevices[nodeId] ?: priorUnresolved
                val hadNoWifiBefore = existing?.wifiDevice == null
                nearbyDevices[nodeId] = NearbyDevice(
                    nodeId = nodeId,
                    wifiDevice = d,
                    blePresence = existing?.blePresence,
                    displayName = name,
                    reachability = reachabilityNow,
                    inviteStatus = existing?.inviteStatus ?: InviteStatus.NOT_INVITED,
                    failReason = existing?.failReason,
                    radarSeed = existing?.radarSeed ?: nodeId,
                    // BUG (DISCOVERY IS ONE-SHOT) FIX: same carry-forward
                    // contract as radarSeed above — this is a rekey of the
                    // SAME physical sighting, not a new one.
                    firstSeenAtMs = existing?.firstSeenAtMs ?: SystemClock.elapsedRealtime()
                )
                if (existing?.inviteStatus == InviteStatus.INVITING && hadNoWifiBefore) {
                    sendWifiInvite(nodeId, d)
                }
            } else {
                // Still unresolved (or resolved with the key unchanged —
                // impossible today since a resolved key is always tagged
                // differently from an address key, kept as an explicit branch
                // rather than assumed) — ordinary in-place update, same
                // object identity where possible.
                val existing = nearbyDevices[nodeId]
                val hadNoWifiBefore = existing?.wifiDevice == null
                nearbyDevices[nodeId] = (existing ?: NearbyDevice(nodeId, d, null, name, reachabilityNow, radarSeed = nodeId)).apply {
                    wifiDevice = d
                    reachability = reachabilityNow
                    if (existing == null || existing.displayName.isBlank()) displayName = name
                    if (inviteStatus == InviteStatus.INVITING && hadNoWifiBefore) {
                        sendWifiInvite(nodeId, d)
                    }
                }
            }
        }

        // 3) BLE presence — nodeId is already known directly, no correlation
        // needed; only "live" sightings (same 30s window buildMemberCard already
        // uses) count as currently reachable. Also promotes any synthetic entry
        // for the same device (matched by short-id suffix) onto this real id.
        val ledger = mediaTransport?.ledger
        if (ledger != null) {
            ledger.knownNodeIds().forEach { nodeId ->
                if (nodeId == localId || nodeId in inGroupIds) return@forEach
                val presence = ledger.blePresenceFor(nodeId) ?: return@forEach
                if (System.currentTimeMillis() - presence.seenAtMs >= 30_000L) return@forEach
                promoteSyntheticEntry(nodeId)
                val existing = nearbyDevices[nodeId]
                val name = existing?.displayName?.takeIf { it.isNotBlank() } ?: MeshFrame.hex(nodeId).takeLast(6)
                nearbyDevices[nodeId] = (existing ?: NearbyDevice(nodeId, null, presence, name, Reachability.BLE_ONLY)).apply {
                    blePresence = presence
                    if (reachability != Reachability.IN_GROUP && wifiDevice == null) reachability = Reachability.BLE_ONLY
                }
            }
        }

        // Anyone no longer IN_GROUP, not Wi-Fi-discovered, and with no recent
        // BLE presence is stale — drop them so the list never grows unbounded.
        val staleIds = nearbyDevices.values.filter { d ->
            d.reachability != Reachability.IN_GROUP &&
                d.wifiDevice == null &&
                (d.blePresence == null || System.currentTimeMillis() - d.blePresence!!.seenAtMs >= 30_000L) &&
                d.inviteStatus != InviteStatus.INVITING
        }.map { it.nodeId }
        staleIds.forEach { nearbyDevices.remove(it) }

        val unresolvedCount = nearbyDevices.values.count { it.reachability == Reachability.UNRESOLVED }
        val wifiCount = nearbyDevices.values.count { it.reachability == Reachability.WIFI_DIRECT }
        val bleCount = nearbyDevices.values.count { it.reachability == Reachability.BLE_ONLY }
        val groupCount = nearbyDevices.values.count { it.reachability == Reachability.IN_GROUP }
        // FIX 1d: raw (OS-level WifiP2pDeviceList) vs rendered (visibleNearbyDevices,
        // the SAME collection the header count and the row adapters read) in
        // one line — this is what would have made "1 device found, 0 rows"
        // diagnosable from a log alone.
        Log.d(
            "OFFTRACE",
            "NEARBY: raw=${devices.size} rendered=${visibleNearbyDevices().size} " +
                "(unresolved=$unresolvedCount wifi=$wifiCount ble=$bleCount inGroup=$groupCount)"
        )
        // BUG (DISCOVERY IS ONE-SHOT) FIX: one line per row, per pass — the
        // "still resolving" vs "gave up" distinction (resolveVerdict) needs
        // to be visible in a capture, not just inferred from the UI.
        val nowMs = SystemClock.elapsedRealtime()
        nearbyDevices.values.forEach { d ->
            val resolved = d.reachability != Reachability.UNRESOLVED
            val age = nowMs - d.firstSeenAtMs
            val verdict = resolveVerdict(resolved, age)
            Log.d("OFFTRACE", "NEARBY: row=${d.displayName} age=$age resolved=$resolved verdict=$verdict")
        }

        // PART "HASSLE-FREE JOIN" item 4: the old "QR-scanned nodeId not
        // yet in range — retry once discovered" queue is gone along with
        // the invite flow it served (see handleScannedQr's own doc) — a
        // scanned QR now either joins immediately (it carries live
        // credentials) or says so and stops, nothing left to retry.
        pruneStaleOpenGroups()

        notifyNearbyAdaptersChanged()
    }

    /** Migrates a synthetic-keyed row (see the field doc above) onto [realNodeId]
     *  once its short-id suffix is confirmed to belong to a now-known real
     *  nodeId — carries inviteStatus/failReason across so an in-flight invite
     *  (and A6's pendingInvites/A5's announcedIncoming bookkeeping) survives
     *  the key change instead of silently resetting. */
    private fun promoteSyntheticEntry(realNodeId: Long) {
        val shortId = MeshFrame.hex(realNodeId).takeLast(6)
        val synthetic = nearbyDevices.remove(syntheticKeyFor(shortId)) ?: return
        val real = nearbyDevices[realNodeId]
        if (real == null) {
            nearbyDevices[realNodeId] = NearbyDevice(
                nodeId = realNodeId,
                wifiDevice = synthetic.wifiDevice,
                blePresence = null,
                displayName = synthetic.displayName,
                reachability = Reachability.WIFI_DIRECT,
                inviteStatus = synthetic.inviteStatus,
                failReason = synthetic.failReason,
                radarSeed = synthetic.radarSeed // FIX 1b: carry the stable angle forward across this rekey too
            )
        } else if (real.inviteStatus == InviteStatus.NOT_INVITED) {
            real.wifiDevice = real.wifiDevice ?: synthetic.wifiDevice
            real.inviteStatus = synthetic.inviteStatus
            real.failReason = synthetic.failReason
        }
        if (syntheticKeyFor(shortId) in pendingInvites) {
            pendingInvites.remove(syntheticKeyFor(shortId))
            pendingInvites.add(realNodeId)
        }
    }

    private fun notifyNearbyAdaptersChanged() {
        if (::nearbyDeviceAdapter.isInitialized) nearbyDeviceAdapter.notifyDataSetChanged()
        if (::inviteListAdapter.isInitialized) {
            inviteListView.visibility = if (isLocalGroupOwner && groupScreen.visibility == View.VISIBLE) View.VISIBLE else View.GONE
            inviteListAdapter.notifyDataSetChanged()
        }
        midCallDialogAdapter?.notifyDataSetChanged()
        // FIX 4: single chokepoint after every nearbyDevices mutation — the
        // radar view (and its status label) is fed here alongside the two
        // list adapters above, never from a separate polling path.
        if (::discoveryRadarView.isInitialized) {
            val radarDevices = buildRadarDevices()
            discoveryRadarView.setDevices(radarDevices, currentGroupFormed)
            if (::radarStatusText.isInitialized) {
                val state = DiscoveryRadarView.uiStateFor(radarDevices, currentGroupFormed)
                radarStatusText.text = radarStateLabel(state, radarDevices.size)
            }
        }
    }

    /** Replaces the old, dead `refreshNearbyOverlayIfVisible()` call site (it
     *  had no definition anywhere in the repo) — a TXT record arriving is one
     *  of refreshNearbyDevices' own trigger points now. */
    private fun refreshNearbyOverlayIfVisible() {
        refreshNearbyDevices()
    }

    // FIX 1c: "Peer discovery on an active GO is what led here" — the
    // periodic refreshNearbyDevices() tick is what (via sendInvite for any
    // BLE-only/not-yet-WFD-visible row) called wifiDirect.startDiscovery()
    // continuously, including while this device was hosting a live group.
    // WifiDirectManager's own guard now blocks the destructive outcome
    // structurally, but the loop itself has no business scanning for NEW
    // peers while a group is live — paused for the duration and resumed
    // only on a confirmed real teardown (handleRealGroupTeardown). The loop
    // keeps RUNNING (still reschedules itself) while paused so it can react
    // the moment it's resumed, rather than needing to be restarted.
    private var nearbyRefreshPaused = false

    private fun startNearbyRefreshLoop() {
        if (nearbyRefreshScheduled) return
        nearbyRefreshScheduled = true
        val r = object : Runnable {
            override fun run() {
                if (!nearbyRefreshPaused) refreshNearbyDevices()
                nearbyRefreshHandler.postDelayed(this, 3_000L)
            }
        }
        nearbyRefreshHandler.post(r)
    }

    private fun pauseNearbyRefreshLoop() {
        if (nearbyRefreshPaused) return
        nearbyRefreshPaused = true
        Log.d("OFFTRACE", "P2P: nearby refresh loop paused — group is live")
    }

    private fun resumeNearbyRefreshLoop() {
        if (!nearbyRefreshPaused) return
        nearbyRefreshPaused = false
        Log.d("OFFTRACE", "P2P: nearby refresh loop resumed — group ended")
    }

    /** A6: single choke point every invite path (search-screen tap, "Add to
     *  group" tap, mid-call invite tap) now routes through — a second tap
     *  while already INVITING/JOINED is a no-op, so double-tap can never
     *  create two join attempts. */
    private fun sendInvite(nodeId: Long) {
        val dev = nearbyDevices[nodeId] ?: return
        if (dev.inviteStatus == InviteStatus.INVITING || dev.inviteStatus == InviteStatus.JOINED) return
        // BUG (INVITE MUST NOT DEPEND ON DNS-SD RESOLUTION) FIX: the
        // resolution gate that used to sit here (OCP CONNECT REBUILD PART 5
        // — rejecting a tap on an ADDRESS_KEY_TAG-placeholder-keyed,
        // UNRESOLVED row) is gone. That gate existed because inviting via
        // the placeholder key used to mean deriving a passphrase from it as
        // if it were a real nodeId (the established log's "INVITE went to
        // placeholder nodeId 7ffe000000900627"). The invite path below no
        // longer derives anything from the target's nodeId at all — this
        // device becomes GO and advertises "gp"=the peer's raw P2P
        // deviceAddress (see sendWifiInvite/startWaitingForExplicitJoin) —
        // so a peer visible in ordinary P2P discovery (dev.wifiDevice !=
        // null below) can always be invited, resolved or not.
        pendingInvites.add(nodeId)
        dev.inviteStatus = InviteStatus.INVITING
        dev.failReason = null
        notifyNearbyAdaptersChanged()
        // BUG (INVITE MUST NOT DEPEND ON DNS-SD RESOLUTION) FIX: the only
        // gate left is whether a real P2P-discovered WifiP2pDevice is known
        // — dev.reachability (resolved or UNRESOLVED) plays no part here at
        // all, unlike the removed shouldRejectInviteTap check above.
        val wfd = dev.wifiDevice
        if (wfd != null) {
            sendWifiInvite(nodeId, wfd)
        } else {
            dev.failReason = "searching via Wi-Fi Direct…"
            logInvitePath("wait-for-wfd", MeshFrame.hex(nodeId), transmitted = false, reason = "no-wifi-device-yet")
            // BUG (DISCOVERY IS ONE-SHOT) FIX: startDiscovery() runs the
            // whole addServiceRequest->discoverServices->discoverPeers chain
            // itself (and keeps re-issuing it on a 12s cadence) — a separate
            // discoverServices() call here used to fire 1ms apart from the
            // discoverPeers this triggered, and wpa_supplicant rejected the
            // second scan trigger outright.
            transport.startDiscovery() // PART 1.3: converted — bare call, no result callback
            notifyNearbyAdaptersChanged()
            nearbyRefreshHandler.postDelayed({
                val d = nearbyDevices[nodeId]
                if (d != null && d.inviteStatus == InviteStatus.INVITING && d.wifiDevice == null) {
                    d.inviteStatus = InviteStatus.FAILED
                    d.failReason = "not found nearby"
                    notifyNearbyAdaptersChanged()
                }
            }, bleInviteWfdTimeoutMs)
        }
    }

    /** BUG (MAKE THE INVITE ACTUALLY TRANSMIT) FIX PART 5: single source of
     *  truth for the invite-path log — every branch below (and the
     *  createGroup fallback, and invitePeer) reports through this instead of
     *  a bespoke line per call site, so "transmitted" can never silently
     *  mean something different in two places. transmitted=true ONLY for a
     *  call that actually puts a frame on the radio toward the peer
     *  (connect()'s provision-discovery/GO-negotiation, invitePeer());
     *  createGroup() and any TXT/BLE advertisement update are transmitted=false
     *  — they change local state and wait, they don't reach out. */
    private fun logInvitePath(path: String, peer: String, transmitted: Boolean, reason: String) {
        Log.d("OFFTRACE", "INVITE: path=$path peer=$peer transmitted=$transmitted reason=$reason")
    }

    private fun sendWifiInvite(nodeId: Long, device: WifiP2pDevice) {
        val peerTag = MeshFrame.hex(nodeId)
        val callback: (Boolean) -> Unit = { ok ->
            runOnUiThread {
                if (!ok) {
                    // BUG (MAKE THE INVITE ACTUALLY TRANSMIT) FIX PART 1.4:
                    // the createGroup fallback (attemptCreateGroupFallback,
                    // triggered by wifiDirect.onConnectDeadlineFired) already
                    // took over for this exact nodeId — this connect()
                    // callback firing false right after IS the SAME deadline,
                    // not a second failure; don't mark FAILED out from under
                    // the fallback that's now in charge of this row's status.
                    if (connectFallbackInProgressForNodeId == nodeId) {
                        connectFallbackInProgressForNodeId = null
                        return@runOnUiThread
                    }
                    if (!isLocalGroupOwner) connectRequested = false
                    pendingInviteTargetNodeId = null
                    pendingInviteTargetDevice = null
                    stopWaitingForExplicitJoin()
                    val d = nearbyDevices[nodeId] ?: return@runOnUiThread
                    d.inviteStatus = InviteStatus.FAILED
                    // FIX 2d: wifiDirect.connect()'s ActionListener.onFailure
                    // only reports whether the connect REQUEST was accepted —
                    // it is a different, earlier signal than
                    // onConnectionChangedInternal's own groupFormed
                    // observation, and can fire even after a real WFD-level
                    // handshake completed (the bug report's exact case: a
                    // completed EAPOL 4-way handshake, still shown "connect
                    // failed"). formedAtLeastOnceThisAttempt is set true the
                    // moment THIS attempt ever sees groupFormed=true, so this
                    // distinguishes "never got anywhere" from "connected,
                    // then it came apart" instead of showing the same
                    // generic message for both.
                    d.failReason = when {
                        formedAtLeastOnceThisAttempt -> "connected, but the group disappeared"
                        device.isGroupOwner -> "they already have a group"
                        else -> "connect failed"
                    }
                    notifyNearbyAdaptersChanged()
                } else {
                    pendingInviteTargetNodeId = null
                    pendingInviteTargetDevice = null
                }
                // success: stays INVITING until the roster actually shows them
                // JOINED (refreshNearbyDevices, driven by onRosterUpdated).
            }
        }
        // LEAK (PART 1.3): sendWifiInvite as a whole needs a raw WifiP2pDevice
        // AND the isLocalGroupOwner branch below (invitePeer vs connect) —
        // MeshTransport.invite(DiscoveredPeer, onOutcome) has no such branch
        // and DiscoveredPeer carries no WifiP2pDevice. Both calls in this
        // function stay direct.
        if (isLocalGroupOwner) {
            logInvitePath("invitePeer", peerTag, transmitted = true, reason = "already-go")
            wifiDirect.invitePeer(device, callback)
        } else {
            // BUG (MAKE THE INVITE ACTUALLY TRANSMIT) FIX PART 1: THE
            // invite is connect(), not createGroup(). The old code here
            // called wifiDirect.host() (createGroup) as the FIRST and ONLY
            // thing an invite tap did — createGroup() stands up a soft-AP
            // and waits for the peer to read a DNS-SD TXT record and let
            // themselves in; it transmits nothing toward any specific
            // device (see WifiDirectManager's class doc rewrite). connect()
            // with a deviceAddress-targeted legacy config is what actually
            // sends provision discovery over the air toward [device] — see
            // WifiDirectManager.connect's negotiate-path doc.
            // createGroup (attemptCreateGroupFallback) is now the FALLBACK,
            // tried once only if this connect() hits its 25s deadline
            // (wifiDirect.onConnectDeadlineFired, wired in onCreate).
            connectRequested = true
            formedAtLeastOnceThisAttempt = false // FIX 2d: fresh attempt — reset the "did we ever actually form" tracker
            pendingInviteTargetNodeId = nodeId
            pendingInviteTargetDevice = device
            logInvitePath("connect", peerTag, transmitted = true, reason = "pbc-provdisc")
            wifiDirect.connect(device, peerTag, onOutcome = callback)
        }
    }

    /** BUG (MAKE THE INVITE ACTUALLY TRANSMIT) FIX PART 1.4/3.1: fired from
     *  [WifiDirectManager.onConnectDeadlineFired] — the ONE signal that the
     *  PRIMARY connect() attempt (above) ran its full 25s without a
     *  groupFormed observation, survivable including a reinvokePersistentGroup
     *  HAL hiccup (08-21: getClientList threw inside it; this app makes no
     *  special case for that, it just lets this same deadline fire). Only
     *  acts when [peerTag] matches an invite-tap connect currently in flight
     *  (never for the BLE-triggered or DNS-SD-triggered join attempts, or
     *  MeshElection's own connect() calls — none of those set
     *  [pendingInviteTargetNodeId]). Falls back to createGroup ONCE — this
     *  function clears the pending-target fields the instant it acts, so a
     *  second deadline (there won't be one for the SAME attempt, but
     *  defensively) can't retry twice. */
    private fun onConnectDeadlineFired(peerTag: String) {
        val nodeId = pendingInviteTargetNodeId ?: return
        val device = pendingInviteTargetDevice ?: return
        if (MeshFrame.hex(nodeId) != peerTag) return
        pendingInviteTargetNodeId = null
        pendingInviteTargetDevice = null
        // BUG (MAKE THE INVITE ACTUALLY TRANSMIT) FIX PART 3: the fallback's
        // derived credentials (3.2) need the PEER's real nodeId — [nodeId]
        // here is only real if this row was actually resolved (by BLE, the
        // roster, or DNS-SD) before the tap; an ADDRESS_KEY_TAG/synthetic
        // placeholder can't be used as a real identity to derive a shared
        // passphrase from, so there is no fallback for a peer nobody has
        // ever identified — the connect() failure alone stands.
        if (isSyntheticKey(nodeId) || isAddressKey(nodeId)) {
            Log.d("OFFTRACE", "P2P: connect deadline peer=$peerTag — no resolved nodeId, no fallback possible")
            return
        }
        connectFallbackInProgressForNodeId = nodeId
        attemptCreateGroupFallback(nodeId, device)
    }

    /** BUG (MAKE THE INVITE ACTUALLY TRANSMIT) FIX PART 3.1: the DEMOTED
     *  second attempt — createGroup() plus DNS-SD/BLE advertising, tried
     *  exactly once, only after connect()'s deadline (see
     *  [onConnectDeadlineFired]). Derives networkName/passphrase per 3.2
     *  from the two real nodeIds (order-independent — the peer, once it
     *  recognizes the "gp"/BLE invite-target signal, computes the identical
     *  values itself with nothing secret ever crossing the wire). */
    private fun attemptCreateGroupFallback(targetNodeId: Long, targetDevice: WifiP2pDevice) {
        val peerTag = MeshFrame.hex(targetNodeId)
        logInvitePath("createGroup-fallback", peerTag, transmitted = false, reason = "connect-deadline")
        connectRequested = true
        formedAtLeastOnceThisAttempt = false
        startWaitingForExplicitJoin(targetNodeId, targetDevice.deviceAddress)
        val localNodeId = nodeIdBytesToLong(OfflineIdentity.nodeId(applicationContext))
        val networkName = WifiDirectManager.deriveFallbackNetworkName(localNodeId, targetNodeId)
        val passphrase = WifiDirectManager.deriveFallbackPassphrase(localNodeId, targetNodeId)
        // LEAK (PART 1.3): host() (explicit createGroup with a derived
        // networkName/passphrase) has no interface equivalent — Wi-Fi-Direct
        // is the only transport with a "become the owner and wait" concept.
        wifiDirect.host(peerTag, networkName, passphrase) { ok ->
            runOnUiThread {
                connectFallbackInProgressForNodeId = null
                if (!ok) {
                    connectRequested = false
                    stopWaitingForExplicitJoin()
                    val d = nearbyDevices[targetNodeId] ?: return@runOnUiThread
                    d.inviteStatus = InviteStatus.FAILED
                    d.failReason = "connect failed"
                    notifyNearbyAdaptersChanged()
                    logInvitePath("createGroup-fallback", peerTag, transmitted = false, reason = "createGroup-failed")
                }
                // success: same "stays INVITING until roster shows JOINED" contract.
            }
        }
    }

    // OCP CONNECT REBUILD PART 3: the target this device is currently
    // hosting an explicit group FOR — null when not waiting on anyone. Drives
    // the "gp" TXT field (see registerDnsSdLocalService).
    // BUG (GROUP FORMS, NOBODY JOINS) FIX: this used to be cleared the moment
    // the GO's OWN group formed (onConnectionChangedInternal's unconditional
    // stopWaitingForExplicitJoin() call) — but group formation is a signal
    // about this device's own link, not about whether the INVITED peer ever
    // associated (the established capture: group formed at 11:16:00.818,
    // clients=0 forever after). gp must now survive group formation and stay
    // set until one of three things actually happens: the invited peer's
    // device shows up in the live group's client list (onGroupClientsChanged
    // below), the 60s invite deadline expires (armInviteDeadline), or the
    // group/attempt is abandoned (leaveGroup -> resetConnectionAttemptState).
    // BUG (MAKE THE INVITE ACTUALLY TRANSMIT) FIX PART 3.3: the peer's raw
    // P2P deviceAddress — used ONLY for onGroupClientsChanged's "did the
    // peer we're waiting on actually associate" check and for the invite
    // deadline's display name lookup. NOT what gp carries anymore (see
    // groupWaitingForShortId below) — Android hides this device's OWN P2P
    // MAC behind a fixed placeholder ("02:00:00:00:00:00"), so a MAC-based
    // gp marker is unmatchable by the peer; only the ASSOCIATION check
    // (comparing the live group's real client addresses, which are NOT
    // hidden) still legitimately uses an address.
    private var groupWaitingForPeerAddress: String? = null
    // BUG (MAKE THE INVITE ACTUALLY TRANSMIT) FIX PART 3.3: what "gp"
    // actually carries now — the invited peer's SHORT NODE ID, resolvable
    // by BLE (or DNS-SD/roster) independent of this device's own hidden P2P
    // MAC. Also what MeshBleBeacon's invite-target field advertises (item 4).
    private var groupWaitingForShortId: String? = null
    // BUG (GROUP FORMS, NOBODY JOINS) FIX: this device's own live group
    // credentials, once known from requestGroupInfo (see
    // onConnectionChangedInternal's GO branch) — published as "ss"/"pw" on
    // every subsequent registerDnsSdLocalService() call. Still needed: DNS-SD's
    // "gp"/"id" fields are truncated 24-bit shortIds, not full nodeIds, so a
    // peer relying on DNS-SD ALONE cannot derive deriveFallbackNetworkName/
    // Passphrase independently (unlike the BLE path, whose invite-target
    // rides the same advertisement as the sender's full 64-bit nodeId — see
    // MeshBleBeacon.OcpBeaconPayload) — ss/pw are that peer's only route to
    // working credentials. Cleared whenever this device stops hosting
    // (leaveGroup/resetConnectionAttemptState).
    private var hostedGroupNetworkName: String? = null
    private var hostedGroupPassphrase: String? = null
    private var inviteDeadlineRunnable: Runnable? = null
    private var inviteDeadlineStartedAtMs = 0L
    // BUG (MAKE THE INVITE ACTUALLY TRANSMIT) FIX PART 1: the invite-tap's
    // PRIMARY connect() attempt in flight, if any — correlates
    // wifiDirect's process-wide onConnectDeadlineFired callback back to
    // THIS specific pending invite (see onConnectDeadlineFired's doc).
    // Non-null only between sendWifiInvite's connect() call and either its
    // own outcome or the fallback taking over.
    private var pendingInviteTargetNodeId: Long? = null
    private var pendingInviteTargetDevice: WifiP2pDevice? = null
    // Set the instant attemptCreateGroupFallback takes over for a nodeId —
    // tells sendWifiInvite's own connect() callback (which still fires
    // false right after the SAME deadline) not to mark the row FAILED; the
    // fallback owns that row's status from here.
    private var connectFallbackInProgressForNodeId: Long? = null

    /** BUG (MAKE THE INVITE ACTUALLY TRANSMIT) FIX PART 3/4: only ever
     *  called from [attemptCreateGroupFallback] now — the PRIMARY connect()
     *  path (item 1) needs no advertising at all, since it reaches the peer
     *  directly. [targetNodeId] must already be a real, resolved nodeId
     *  (checked by the caller) so [groupWaitingForShortId]/the BLE
     *  invite-target are both meaningful. */
    private fun startWaitingForExplicitJoin(targetNodeId: Long, targetAddress: String) {
        groupWaitingForPeerAddress = targetAddress
        groupWaitingForShortId = MeshFrame.hex(targetNodeId).takeLast(6)
        registerDnsSdLocalService()
        val targetShortId = MeshBleBeacon.OcpBeaconPayload.shortNodeId(targetNodeId)
        mediaTransport?.bleBeacon?.setInviteTarget(targetShortId)
        Log.d("OFFTRACE", "BLE: invite adv target=$groupWaitingForShortId")
        armInviteDeadline(targetAddress)
    }

    private fun stopWaitingForExplicitJoin() {
        cancelInviteDeadline()
        if (groupWaitingForPeerAddress == null && groupWaitingForShortId == null) return
        groupWaitingForPeerAddress = null
        groupWaitingForShortId = null
        mediaTransport?.bleBeacon?.setInviteTarget(null)
        registerDnsSdLocalService()
    }

    /** BUG (GROUP FORMS, NOBODY JOINS) FIX: the UI used to sit on "Connected —
     *  joining group…" / "Inviting…" indefinitely whenever the invited peer
     *  never associated. Armed the moment this device starts waiting (tap
     *  time — covers both "group never formed" and "group formed but
     *  clients stayed 0"); cancelled the moment the peer actually associates
     *  ([onGroupClientsChanged]) or waiting otherwise ends
     *  ([stopWaitingForExplicitJoin]). */
    private fun armInviteDeadline(targetAddress: String) {
        cancelInviteDeadline()
        inviteDeadlineStartedAtMs = SystemClock.elapsedRealtime()
        val runnable = Runnable {
            inviteDeadlineRunnable = null
            // LEAK (PART 1.3): requestGroupInfo(WifiP2pGroup) — no interface
            // equivalent (client-list introspection is Wi-Fi-Direct-specific).
            wifiDirect.requestGroupInfo { group ->
                val clients = group?.clientList?.size ?: 0
                val elapsed = SystemClock.elapsedRealtime() - inviteDeadlineStartedAtMs
                Log.d("OFFTRACE", "P2P: invite timeout peer=$targetAddress elapsedMs=$elapsed clients=$clients")
                runOnUiThread {
                    // leaveGroup is the existing shared teardown path — clears
                    // gp (resetConnectionAttemptState -> stopWaitingForExplicitJoin),
                    // removeGroup (wifiDirect.disconnect()), and resumes
                    // discovery (proceedToDiscovery()), all in one place
                    // rather than duplicating that sequence here.
                    val name = nearbyDevices.values.firstOrNull { it.wifiDevice?.deviceAddress == targetAddress }?.displayName ?: "Peer"
                    leaveGroup("invite-timeout", "$name didn't join")
                }
            }
        }
        inviteDeadlineRunnable = runnable
        timeoutHandler.postDelayed(runnable, INVITE_TIMEOUT_MS)
    }

    private fun cancelInviteDeadline() {
        inviteDeadlineRunnable?.let { timeoutHandler.removeCallbacks(it) }
        inviteDeadlineRunnable = null
    }

    /** BUG (GROUP FORMS, NOBODY JOINS) FIX: [WifiDirectManager.onGroupClientsChanged]
     *  callback — the actual "did the invited peer associate" check (clients>0
     *  with a matching device address), as opposed to groupFormed (which only
     *  ever reflects this device's own link). Clears gp and cancels the invite
     *  deadline the moment the awaited peer shows up; a no-op for everyone else
     *  (mid-call invites joining an already-settled group, or clients arriving
     *  while this device isn't waiting on anyone in particular).
     *  BUG (INVITE MUST NOT DEPEND ON DNS-SD RESOLUTION) FIX: the marker IS
     *  the peer's address now, so this needs no nearbyDevices lookup at all
     *  to get one. */
    private fun onGroupClientsChanged(clients: List<WifiP2pDevice>) {
        val targetAddress = groupWaitingForPeerAddress ?: return
        if (invitedPeerAssociated(clients.map { it.deviceAddress }, targetAddress)) {
            Log.d("OFFTRACE", "P2P: invited peer associated ($targetAddress) — clearing gp")
            stopWaitingForExplicitJoin()
        }
    }

    // OCP CONNECT REBUILD PART 3: keys this device has already attempted
    // (or is currently attempting) an auto-join for — prevents repeated
    // gp-signal callbacks (fired on every discovery pass / BLE sighting,
    // not just once) from issuing a second connect() while one is already
    // in flight for the same peer. BUG (MAKE THE INVITE ACTUALLY TRANSMIT)
    // FIX: shared by both trigger channels below, prefixed ("dnssd:"/"ble:")
    // so a peer reachable by both never collides into one shared entry —
    // each channel gets its own attempt.
    private val explicitJoinAttempted = mutableSetOf<String>()

    /** OCP CONNECT REBUILD PART 3: the DNS-SD half of the joiner-side "gp"
     *  signal — called from [onServiceTxtRecordFound] when a peer's TXT
     *  record advertises "gp" matching THIS device's own short node id AND
     *  has already published its live group's "ss"/"pw". DNS-SD's own "gp"/
     *  "id" fields are truncated 24-bit shortIds, not full nodeIds, so this
     *  channel has no way to derive matching credentials independently —
     *  ss/pw read straight off the wire are its ONLY source; until both
     *  arrive, this simply isn't called yet (the same "gp" TXT record keeps
     *  re-arriving every discovery pass until they do). A no-op if this
     *  device is already mid-connect/hosting, or already attempted for this
     *  exact peer. */
    private fun maybeAutoJoinExplicitGroupViaDnsSd(hostAddress: String, networkName: String, passphrase: String) {
        if (isLocalGroupOwner || connectRequested) return
        if (!explicitJoinAttempted.add("dnssd:$hostAddress")) return
        val device = nearbyDevices.values.firstOrNull { it.wifiDevice?.deviceAddress == hostAddress }?.wifiDevice ?: return
        Log.d("OFFTRACE", "P2P: auto-join triggered host=$hostAddress via gp TXT signal (dns-sd)")
        connectRequested = true
        formedAtLeastOnceThisAttempt = false
        // LEAK (PART 1.3): raw WifiP2pDevice + explicit networkName/passphrase.
        wifiDirect.connect(device, hostAddress, networkName, passphrase) { ok ->
            runOnUiThread {
                if (!ok) {
                    connectRequested = false
                    explicitJoinAttempted.remove("dnssd:$hostAddress")
                }
            }
        }
    }

    /** BUG (MAKE THE INVITE ACTUALLY TRANSMIT) FIX PART 4.2: the BLE half of
     *  the joiner-side signal — called from MeshBleBeacon's invite-target
     *  match when a peer's advertisement carries an invite-target equal to
     *  THIS device's own short node id. Unlike DNS-SD, the SAME BLE
     *  advertisement already carries the sender's full 64-bit nodeId
     *  ([hostNodeId] — see OcpBeaconPayload's own nodeId field), so
     *  credentials are derived directly per 3.2, no ss/pw round-trip needed.
     *
     *  LIMITATION: still needs an actual WifiP2pDevice to call connect() on
     *  — BLE and Wi-Fi Direct are separate radios/identity namespaces with
     *  no address correlation between them in this app (the same gap that
     *  keeps a WFD sighting and a BLE sighting of the same physical device
     *  as two separate nearbyDevices rows until DNS-SD bridges them, which
     *  this whole bug thread is about not depending on). Falls back to "the
     *  one currently-unresolved WFD sighting, if there's exactly one" —
     *  correct in the tested single-peer scenario, not a general multi-peer
     *  solution. */
    private fun maybeAutoJoinExplicitGroupViaBle(hostNodeId: Long) {
        if (isLocalGroupOwner || connectRequested) return
        val key = "ble:${MeshFrame.hex(hostNodeId)}"
        if (!explicitJoinAttempted.add(key)) return
        val device = nearbyDevices[hostNodeId]?.wifiDevice
            ?: nearbyDevices.values.singleOrNull { it.wifiDevice != null && it.reachability == Reachability.UNRESOLVED }?.wifiDevice
        if (device == null) {
            explicitJoinAttempted.remove(key) // not yet visible over WFD — try again next sighting
            return
        }
        val localNodeId = nodeIdBytesToLong(OfflineIdentity.nodeId(applicationContext))
        val networkName = WifiDirectManager.deriveFallbackNetworkName(localNodeId, hostNodeId)
        val passphrase = WifiDirectManager.deriveFallbackPassphrase(localNodeId, hostNodeId)
        Log.d("OFFTRACE", "P2P: auto-join triggered host=${MeshFrame.hex(hostNodeId)} via BLE invite-target")
        connectRequested = true
        formedAtLeastOnceThisAttempt = false
        // LEAK (PART 1.3): raw WifiP2pDevice + explicit networkName/passphrase.
        wifiDirect.connect(device, MeshFrame.hex(hostNodeId), networkName, passphrase) { ok ->
            runOnUiThread {
                if (!ok) {
                    connectRequested = false
                    explicitJoinAttempted.remove(key)
                }
            }
        }
    }

    /** A5: a roster member this device never [sendInvite]'d is, by
     *  construction, someone who invited US — surfaced once, the first time
     *  their nodeId is seen. Accept is a no-op (the mesh-level join already
     *  happened, same auto-admit as every existing 2/3-device join — this
     *  dialog never gates that, see the OUTPUT proof for why gating it would
     *  risk the working small-party path); Decline actively undoes it: this
     *  device leaves (if it's the one who just joined uninvited) or, if this
     *  device is the GO, drops that peer via declineIncomingPeer. */
    private val announcedIncoming = mutableSetOf<Long>()
    private fun maybeShowIncomingInviteDialogs(members: List<RoutingTable.Member>) {
        val localId = mediaTransport?.localNodeId ?: return
        members.forEach { m ->
            if (m.nodeId == localId) return@forEach
            if (m.nodeId in pendingInvites) return@forEach
            if (!announcedIncoming.add(m.nodeId)) return@forEach
            AlertDialog.Builder(this)
                .setTitle("Invited by ${m.name}")
                .setMessage("${m.name} added you to their mesh group.")
                .setCancelable(false)
                .setPositiveButton("Accept", null)
                .setNegativeButton("Decline") { _, _ ->
                    if (isLocalGroupOwner) {
                        mediaTransport?.declineIncomingPeer(m.nodeId)
                    } else {
                        leaveGroup("declined invite from ${m.name}")
                    }
                }
                .show()
        }
    }

    /** Shared row builder — one Invite-button row per [NearbyDevice], reused by
     *  the search screen's [peerListView], the roster screen's [inviteListView],
     *  and the mid-call invite dialog (A4) so there is exactly one place this
     *  row's look/behavior is defined. */
    private fun buildNearbyDeviceRow(device: NearbyDevice, convertView: View?): View {
        val density = resources.displayMetrics.density
        val row = (convertView as? LinearLayout) ?: LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
        }
        row.removeAllViews()
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val reachWord = when (device.reachability) {
            // FIX 1a: visible immediately, before any DNS-SD correlation —
            // "FINDING…" rather than a reachability word that implies more
            // is known than actually is.
            // BUG (DISCOVERY IS ONE-SHOT) FIX: past RESOLVE_GIVEUP_MS with no
            // TXT record ever arriving, "Finding…" stops being honest — this
            // device will never publish an OpenCall service record (a TV, a
            // printer, ...). BUG (INVITE MUST NOT DEPEND ON DNS-SD
            // RESOLUTION): the tap itself is no longer blocked either way
            // (see sendInvite) — this only changes the label.
            Reachability.UNRESOLVED -> {
                val age = SystemClock.elapsedRealtime() - device.firstSeenAtMs
                when (resolveVerdict(resolved = false, ageMs = age)) {
                    ResolveVerdict.NOT_AN_OPENCALL_DEVICE -> "Not an OpenCall device"
                    else -> "Finding…" // TOPO 1.3: sentence case
                }
            }
            Reachability.WIFI_DIRECT -> "Wi-Fi Direct"
            Reachability.BLE_ONLY -> "Bluetooth only"
            Reachability.IN_GROUP -> "In group"
        }
        val rssi = device.blePresence?.rssiDbm
        val subtitle = if (rssi != null) "$reachWord · ${rssi}dBm" else reachWord
        // PHASE 1.5 (report F7 gap): was Color.WHITE/Color.LTGRAY/
        // Color.rgb(255,120,120), never consulting nightModeEnabled — rows
        // are rebuilt by the adapter on every notifyDataSetChanged(), so
        // reading NightPalette here (rather than caching) is enough; no
        // separate re-apply hook needed.
        info.addView(TextView(this).apply { text = device.displayName; textSize = 16f; setTextColor(TopoPalette.fg(nightModeEnabled)) })
        info.addView(TextView(this).apply {
            text = if (device.inviteStatus == InviteStatus.FAILED && device.failReason != null) {
                "$subtitle · ${device.failReason}"
            } else subtitle
            textSize = 12f
            setTextColor(if (device.inviteStatus == InviteStatus.FAILED) TopoPalette.failFg(nightModeEnabled) else TopoPalette.mutedFg(nightModeEnabled))
        })
        row.addView(info)
        val button = Button(this).apply {
            when (device.inviteStatus) {
                InviteStatus.NOT_INVITED -> { text = "Invite"; isEnabled = true }
                InviteStatus.INVITING -> { text = "Inviting…"; isEnabled = false }
                InviteStatus.JOINED -> { text = "Joined"; isEnabled = false }
                InviteStatus.FAILED -> { text = "Retry"; isEnabled = true }
            }
            // PHASE 3 item 9: the old peerListView tap handler this row's
            // Invite button replaced had a try/catch surfacing "tap handler
            // threw: ..." (errorText) — sendInvite() had none, so a silent
            // exception here would be undiagnosable in the field. Same
            // surfacing mechanism restored, at the new tap site.
            setOnClickListener {
                try {
                    sendInvite(device.nodeId)
                } catch (t: Throwable) {
                    Log.e("OFFTRACE", "invite tap handler threw", t)
                    errorText.text = "invite tap handler threw: ${t.message ?: t.toString()}"
                    errorText.visibility = View.VISIBLE
                }
            }
        }
        row.addView(button)
        return row
    }

    /** FIX 1d: THE single function both the header count (onPeersChanged)
     *  and every rendered row (nearbyDeviceAdapter, buildRadarDevices)
     *  read — this is what makes "count says 1, rows show 0" structurally
     *  impossible rather than merely correct today: there is no second
     *  place left to compute "how many/which devices" that could drift
     *  out of sync with this one. UNRESOLVED devices are included — FIX 1a
     *  requires them visible immediately, same as any other reachability. */
    private fun visibleNearbyDevices(): List<NearbyDevice> =
        nearbyDevices.values.filter { it.reachability != Reachability.IN_GROUP }

    /** FIX 4/1e: same [visibleNearbyDevices] as the list adapters — a second
     *  projection of the one source of truth, not a second data path.
     *  inviteStatus maps 1:1 onto [DiscoveryRadarView.RadarStatus].
     *  [radarSeed] (not [NearbyDevice.nodeId]) is what the radar angle is
     *  keyed on — see that field's doc for why: nodeId itself can change
     *  (a row is rekeyed from an address key onto a real nodeId once
     *  resolved) but radarSeed never does, so an UNRESOLVED device's dot
     *  never jumps the moment it resolves. */
    private fun buildRadarDevices(): List<DiscoveryRadarView.RadarDevice> =
        visibleNearbyDevices().map { d ->
            DiscoveryRadarView.RadarDevice(
                nodeId = d.nodeId,
                radarSeed = d.radarSeed,
                displayName = d.displayName,
                rssiDbm = d.blePresence?.rssiDbm,
                status = when (d.inviteStatus) {
                    InviteStatus.NOT_INVITED -> DiscoveryRadarView.RadarStatus.NOT_INVITED
                    InviteStatus.INVITING -> DiscoveryRadarView.RadarStatus.INVITING
                    InviteStatus.JOINED -> DiscoveryRadarView.RadarStatus.JOINED
                    InviteStatus.FAILED -> DiscoveryRadarView.RadarStatus.FAILED
                }
            )
        }

    private fun radarStateLabel(state: DiscoveryRadarView.RadarUiState, deviceCount: Int): String = when (state) {
        DiscoveryRadarView.RadarUiState.NO_DEVICES_SWEEPING -> "Sweeping for nearby devices…"
        DiscoveryRadarView.RadarUiState.DEVICES_FOUND -> "$deviceCount device${if (deviceCount == 1) "" else "s"} found — tap to invite"
        DiscoveryRadarView.RadarUiState.CONNECTING -> "Connecting…"
        DiscoveryRadarView.RadarUiState.CONNECTED -> "Connected"
        DiscoveryRadarView.RadarUiState.GROUP_LIVE_STOPPED -> "Group is live — scan stopped"
    }

    // FIX 4c: plain flag flipped from onResume/onPause — this project has no
    // androidx.lifecycle dependency to query resumed-state from elsewhere,
    // and every other screen-visibility gate in this file (homeTabVisible,
    // nearbyRefreshPaused) already follows this same explicit-flag pattern
    // rather than reaching for a lifecycle-aware component.
    private var activityResumed = false

    /** FIX 4c: single call site pushing shouldSweep's decision into the
     *  view, ANDing the activity-lifecycle/power gate (shouldSweep itself)
     *  with the data-driven "a group is live" gate (uiStateFor) — see
     *  DiscoveryRadarView.shouldSweep's doc for why those two are kept as
     *  separate functions but always combined here. */
    private fun updateRadarSweeping() {
        if (!::discoveryRadarView.isInitialized) return
        val lifecycleAllows = DiscoveryRadarView.shouldSweep(homeTabVisible, activityResumed, inBatteryCliff)
        discoveryRadarView.setSweeping(lifecycleAllows && !currentGroupFormed)
    }

    /** FIX 4g: swaps which of discoveryRadarView/peerListView is visible —
     *  never both, never neither. The radar keeps receiving setDevices()
     *  either way (see notifyNearbyAdaptersChanged) so switching back to it
     *  is never stale; only its sweep animation is separately gated (see
     *  [updateRadarSweeping]) to avoid animating off-screen. */
    private fun applyRadarViewMode() {
        radarViewToggleButton.text = if (radarViewEnabled) "List view" else "Radar view"
        discoveryRadarView.visibility = if (radarViewEnabled) View.VISIBLE else View.GONE
        radarStatusText.visibility = if (radarViewEnabled) View.VISIBLE else View.GONE
        peerListView.visibility = if (radarViewEnabled) View.GONE else View.VISIBLE
        updateRadarSweeping()
    }

    private inner class NearbyDeviceAdapter : BaseAdapter() {
        // FIX 1d: was a caller-supplied filter lambda — every construction
        // site passed the identical predicate anyway (see
        // visibleNearbyDevices), so this now calls that one function
        // directly rather than leaving room for a future call site to pass
        // a subtly different filter and reintroduce the count/rows split.
        private fun items(): List<NearbyDevice> = visibleNearbyDevices().sortedBy { it.displayName }
        override fun getCount() = items().size
        override fun getItem(position: Int): NearbyDevice = items()[position]
        override fun getItemId(position: Int) = items()[position].nodeId
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            buildNearbyDeviceRow(items()[position], convertView)
    }

    // CASE 2 fix: tracks whether *this* device actually issued connect(), so a
    // groupFormed=false broadcast can be told apart from stale/teardown noise.
    private var connectRequested = false
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var groupFormationTimeoutRunnable: Runnable? = null

    // IDLE-SESSION FIX: unconditionally kept in sync with the latest
    // WifiP2pInfo.groupFormed seen (even the "ignoring stale/teardown connInfo" early
    // return in onConnectionChangedInternal used to leave this unobservable) — backs
    // the isGroupFormed lambda handed to OfflineMediaTransport so its client-side
    // reconnect can tell "transient socket drop" apart from "the group itself is gone".
    private var currentGroupFormed = false
    // True until the media transport itself gives up (onLinkLost) — see onPeerGone's
    // use of this below: a signaling-channel keepalive timeout alone no longer tears
    // the group down if the media channel still looks healthy.
    private var mediaLinkAlive = true

    // ── FIX 2: real-vs-stale groupFormed=false, by GENERATION not by
    // connectRequested. The old check ("if (!connectRequested) treat as
    // stale") only worked for the CLIENT that had just called connect() —
    // the GO (which never calls connect(); it creates/hosts) always had
    // connectRequested==false, so a REAL teardown of a group the GO was
    // hosting was permanently misclassified as stale noise and silently
    // dropped: no leaveGroup(), no socket close, no recovery, forever.
    // groupGeneration increments once per CONFIRMED formation;
    // formedGeneration is that same number for as long as WE believe that
    // generation's group is still up, reset to 0 the moment we act on its
    // teardown. A groupFormed=false callback is REAL iff formedGeneration
    // > 0 (we currently believe a group is live) — anything else (never
    // formed yet, or this generation's teardown already processed) is
    // genuinely stale/duplicate noise, not a second teardown to act on.
    private var groupGeneration = 0
    private var formedGeneration = 0
    // FIX 2d: distinguishes "never connected" from "connected then the
    // group vanished" for the CLIENT-side timeout message — set true the
    // moment THIS connection attempt (since connectRequested was last set
    // true) ever sees a real formation, before any later teardown. Reset
    // whenever a fresh attempt starts (sendWifiInvite's client branch).
    private var formedAtLeastOnceThisAttempt = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installUncaughtExceptionLogger()
        logCapabilityLineAtAppStart()
        buildUi()
        applyOpenSettingsExtra(intent)
        handleShareSheetIntent(intent)
        startPreConnectionBleDiscovery()
        // PART 1 (batch A): back press closes whichever overlay/open-thread
        // is on top first (Groups, Settings, an open message thread), same
        // "handle it myself sometimes, otherwise defer to the system"
        // AndroidX pattern as before — only what "on top" means changed
        // (no more non-Home tabs to return from; a live call's own back
        // behaviour is unchanged, this never touches callScreen/
        // groupCallScreen).
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    ::groupsOverlay.isInitialized && groupsOverlay.visibility == View.VISIBLE -> closeGroupsOverlay()
                    ::offlineSettingsOverlay.isInitialized && offlineSettingsOverlay.visibility == View.VISIBLE -> closeOfflineSettingsOverlay()
                    ::messagesThreadView.isInitialized && messagesThreadView.visibility == View.VISIBLE -> closeOpenMessageThread()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
        sosAlarm = SosAlarm.get(applicationContext)
        // BUG 1 FIX 2: silencing the sound alone left the underlying SOS marked
        // active, so the sender's next ordinary rebroadcast re-triggered the
        // siren — this also marks it non-alarmable (both on the transport's
        // MeshSosManager and this Activity's own display mirror) so it actually
        // stays silenced, while still showing as historical (see renderSosAlerts).
        sosAlarm.onAutoStopTimeout = { senders ->
            runOnUiThread {
                mediaTransport?.suppressSosAlarmFor(senders)
                senders.forEach { id -> sosEntries[id]?.let { sosEntries[id] = it.copy(alarmable = false) } }
                renderSosAlerts()
                val activeSenders = mediaTransport?.activeSosSenderIds() ?: emptySet()
                renderSosOverlay(activeSenders)
                sosOverlay.visibility = if (activeSenders.isNotEmpty()) View.VISIBLE else View.GONE
            }
        }
        sosSsidBroadcast = SosSsidBroadcast.get(applicationContext)
        sosSsidBroadcast.onRejoinWindow = {
            // PHASE 6 TRACK D: deliberately NOT the full leaveGroup() — that
            // also shuts down mediaTransport (and with it MeshSosManager,
            // discarding the very SOS beacon/carry state this last-resort mode
            // exists to publicize). Just resume ordinary peer discovery; the
            // existing WifiDirectManager/OfflineMediaTransport reconnect logic
            // picks back up on its own if the same group re-forms.
            // LEAK (PART 1.3): result callback — MeshTransport.startDiscovery()
            // has no result path; dropping this Log.w would be a behavior change.
            runOnUiThread { wifiDirect.startDiscovery { ok -> if (!ok) Log.w("OFFTRACE", "SSID: rejoin discovery failed") } }
        }
        sosSsidBroadcast.onStateChanged = { active, broadcasting, ssid ->
            runOnUiThread { updateSsidBroadcastButtonUi(active, broadcasting, ssid) }
        }
        maybeShowSosOnboarding()

        wifiDirect = WifiDirectManager(applicationContext)
        transportRegistry.register(wifiDirect) // PART 1.4
        // PART 2.5: registered like any other transport so all()/get("optical")
        // see it — its start()/startDiscovery()/invite() are honest no-ops
        // (see OpticalTransport's own class doc), so registering it here is
        // inert with respect to the live socket-routing engine; the SHOW/SCAN
        // screens (2.6) drive OpticalFrame/OpticalFountain directly instead.
        transportRegistry.register(OpticalTransport(applicationContext))
        // LEAK (PART 1.3): onPeersChanged(WifiP2pDeviceList) is a raw WFD
        // type this Activity's whole nearbyDevices/wifiDevice model is built
        // on — converting it means rewriting that model to DiscoveredPeer,
        // not just this call site. Stays on wifiDirect directly.
        wifiDirect.onPeersChanged = { list -> onPeersChanged(list) }
        // LEAK (PART 1.3): onConnectionChanged(WifiP2pInfo) — Activity-side
        // handler reads multiple WifiP2pInfo fields and drives isLocalGroupOwner/
        // UI state directly; MeshTransport.Callbacks' onNetworkReady/onNetworkLost
        // split doesn't cover this method's shape without rewriting it.
        wifiDirect.onConnectionChanged = { info -> onConnectionChanged(info) }
        // LEAK (PART 1.3): no interface concept for "P2P radio enabled/disabled"
        // at all — MeshTransport.Callbacks has no equivalent.
        wifiDirect.onP2pStateChanged = { enabled -> onP2pStateChanged(enabled) }
        // LEAK (PART 1.3): this handler's body IS generic enough to map onto
        // Callbacks.onTransportError, but it is wired together with the three
        // leaks directly above/below it, all sharing wifiDirect.init() below —
        // splitting just this one onto transport.start(callbacks) while its
        // siblings stay direct would mean two separate wiring paths setting
        // overlapping state, one silently winning over the other. Left here,
        // consistent with its group, rather than partially converted.
        wifiDirect.onFatalError = { msg ->
            runOnUiThread {
                errorText.text = msg
                errorText.visibility = View.VISIBLE
                statusText.text = "Wi-Fi Direct error"
                Log.e("OFFTRACE", "fatal: $msg")
            }
        }
        // LEAK (PART 1.3): no interface concept for "live group client list"
        // — Wi-Fi-Direct-specific (List<WifiP2pDevice>).
        // BUG (GROUP FORMS, NOBODY JOINS) FIX: the only signal that the
        // specific peer we're waiting on actually associated, rather than
        // just "our own group exists" — see groupWaitingForPeerAddress's doc.
        wifiDirect.onGroupClientsChanged = { clients -> onGroupClientsChanged(clients) }
        // BUG (MAKE THE INVITE ACTUALLY TRANSMIT) FIX PART 1.4/3.1: the
        // ONE signal that the invite-tap's primary connect() attempt ran
        // its full 25s — see onConnectDeadlineFired's own doc for why this
        // only acts on an invite-tap's connect (never MeshElection's, or
        // the DNS-SD/BLE-triggered auto-joins above, none of which set
        // pendingInviteTargetNodeId).
        // LEAK (PART 1.3): no interface concept for "the primary connect
        // attempt's own deadline fired" — this is Wi-Fi-Direct-specific
        // connect-sequence internals (OCP CONNECT REBUILD PART 4).
        wifiDirect.onConnectDeadlineFired = { peerTag -> onConnectDeadlineFired(peerTag) }
        // BUG (DISCOVERY IS ONE-SHOT) FIX: one line per discovery pass, so the
        // next capture reads directly instead of needing to be reconstructed
        // from individual addServiceRequest/discoverServices/discoverPeers lines.
        // LEAK (PART 1.3): no interface concept for a "discovery pass" —
        // Wi-Fi-Direct-specific diagnostic (addServiceRequest ->
        // discoverServices -> discoverPeers chain internals).
        wifiDirect.onDiscoveryPassComplete = { pass, liveReqs ->
            runOnUiThread {
                val foundPeers = nearbyDevices.values.count { it.wifiDevice != null }
                val resolved = nearbyDevices.values.count { it.reachability != Reachability.UNRESOLVED }
                Log.d("OFFTRACE", "DISC: pass=$pass reqs=$liveReqs foundPeers=$foundPeers resolved=$resolved")
            }
        }
        // BUG 2 FIX: pre-connect display-name hint via DNS-SD TXT record — see
        // WifiDirectManager.onServiceTxtRecordFound's doc. UNVERIFIED.
        // FIX 2b: used to require "n" present (if (name != null)) before
        // storing EITHER field — a record carrying only "id" was dropped
        // here too, on top of WifiDirectManager's own now-fixed gate. Store
        // whichever field(s) actually arrived.
        // LEAK (PART 1.3): no interface concept for a DNS-SD TXT record —
        // Wi-Fi-Direct-specific discovery mechanism (local-wifi's Part 2
        // mDNS TXT record is a different, transport-owned concept, not
        // exposed through MeshTransport either — see that transport's doc).
        wifiDirect.onServiceTxtRecordFound = { address, record ->
            val name = record["n"]
            val id = record["id"]
            val gp = record["gp"]
            // BUG (GROUP FORMS, NOBODY JOINS) FIX: the GO's actual live group
            // credentials — see maybeAutoJoinExplicitGroupViaDnsSd's doc for
            // why DNS-SD needs these (its own "gp"/"id" fields are truncated
            // shortIds, not full nodeIds, so this channel can't derive
            // matching credentials independently the way BLE can).
            val ss = record["ss"]
            val pw = record["pw"]
            if (name != null || id != null) {
                runOnUiThread {
                    if (name != null) dnsSdNamesByAddress[address] = name
                    if (id != null) dnsSdShortIdByAddress[address] = id
                    refreshPeerListUi()
                    refreshNearbyOverlayIfVisible()
                }
            }
            // OCP CONNECT REBUILD PART 3 / BUG (MAKE THE INVITE ACTUALLY
            // TRANSMIT) FIX PART 3.3: "gp" is this peer telling us it just
            // became an explicit GO waiting specifically for US — matched
            // against our own SHORT NODE ID again (not a P2P deviceAddress:
            // Android hides this device's own local P2P MAC behind a fixed
            // placeholder, "P2P: this device address=02:00:00:00:00:00" —
            // that whole approach was unmatchable by construction). Only
            // acted on once ss/pw are ALSO present — see
            // maybeAutoJoinExplicitGroupViaDnsSd's doc for why there's no
            // derivation fallback to act on before then.
            if (gp != null && ss != null && pw != null) {
                runOnUiThread {
                    val myShortId = MeshFrame.hex(nodeIdBytesToLong(OfflineIdentity.nodeId(applicationContext))).takeLast(6)
                    if (gp == myShortId) {
                        maybeAutoJoinExplicitGroupViaDnsSd(address, ss, pw)
                    }
                }
            }
        }
        // FIX 2d: the framework clears the service-discovery request (and,
        // on some OEMs, the advertised local service) on some
        // discovery-stopped transitions. Only re-arm while it's actually
        // useful to: not while FIX 1c has discovery deliberately paused for
        // a live group (nearbyRefreshPaused mirrors that exact condition).
        // LEAK (PART 1.3): no interface concept for "the framework cleared
        // our service-discovery request out from under us" — Wi-Fi-Direct-
        // specific OEM quirk (FIX 2d). The re-arm call inside this closure
        // (PART 1.3, below) IS converted — a bare, argument-less
        // startDiscovery() matches the interface exactly.
        wifiDirect.onDiscoveryStopped = {
            runOnUiThread {
                if (!nearbyRefreshPaused) {
                    // BUG (DISCOVERY IS ONE-SHOT) FIX: discoverServices() no
                    // longer exists as a standalone entry point — it's a
                    // private step inside startDiscovery()'s chained pass
                    // (see WifiDirectManager.runDiscoveryPass). By the time
                    // this fires, state has already moved to IDLE (or is
                    // CONNECTING, if a connect's quiesce triggered the stop),
                    // so startDiscovery() here re-arms discovery — including
                    // service discovery — from scratch when that's legal, and
                    // is a harmless no-op (state gate) when it isn't.
                    transport.startDiscovery() // PART 1.3: converted — bare call, no result callback
                    registerDnsSdLocalService()
                }
            }
        }
        // LEAK (PART 1.3): transport.start(callbacks) exists and is a
        // correct, complete MeshTransport.start implementation (see
        // WifiDirectManager.start's own doc) — but it would wire the SAME
        // four onPeersChanged/onConnectionChanged/onFatalError/(no
        // onP2pStateChanged equivalent) vars this Activity already assigns
        // directly above, for reasons documented at each of those leaks.
        // Calling both here would mean two wiring paths silently racing to
        // set the same vars; kept on the direct, unambiguous init() call.
        wifiDirect.init()
        registerDnsSdLocalService()
        // PHASE 8 TRACK A: periodic tick is what keeps BLE-only rows' rssi/trend
        // current and drives the stale-entry sweep in refreshNearbyDevices —
        // Wi-Fi-Direct/roster-driven refreshes alone would never update a row
        // for a device that's only ever seen over BLE.
        startNearbyRefreshLoop()
        // OFFLINE UI STEP 5: restore the persisted night-mode choice.
        setNightMode(getSharedPreferences("opencall", MODE_PRIVATE).getBoolean("night_mode", false))
    }

    /** BUG 2 FIX: advertises this device's OfflineIdentity display name over
     *  Wi-Fi Direct DNS-SD so it's visible in a peer's discovery list BEFORE any
     *  connection (and therefore before HELLO) is possible — see
     *  WifiDirectManager.registerLocalService's doc for why setDeviceName isn't
     *  usable here. Called once at startup and again from [showDisplayNameDialog]
     *  whenever the name actually changes, so an edit takes effect without an
     *  app restart. */
    /** OCP CONNECT REBUILD PART 6: this line has never printed — it used to
     *  live at CALL start, and no call has ever succeeded (that's this
     *  whole rebuild's reason for existing). Probing decoder/encoder
     *  instance counts needs nothing but MediaCodecList (no Context, no
     *  mesh session, no OfflineMediaTransport instance), so it runs at APP
     *  start instead — see OfflineMediaTransport.probeMaxAvcInstancesStatic.
     *  tileBudget is the SAME deriveTileBudget formula the real session
     *  will use once one exists — a preview, not a placeholder.
     *  liveCameras is always 0 here (no call can be active this early). */
    private fun logCapabilityLineAtAppStart() {
        val probedDecoders = OfflineMediaTransport.probeMaxAvcInstancesStatic(isEncoder = false)
        val probedEncoders = OfflineMediaTransport.probeMaxAvcInstancesStatic(isEncoder = true)
        val tileBudget = OfflineMediaTransport.deriveTileBudget(probedDecoders)
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val pm = getSystemService(POWER_SERVICE) as? PowerManager
                pm?.let { OfflineMediaTransport.thermalStatusString(it.currentThermalStatus) } ?: "n/a"
            } catch (e: Exception) { "n/a" }
        } else "n/a"
        Log.d(
            "OFFTRACE",
            "CAP: probedDecoders=$probedDecoders probedEncoders=$probedEncoders " +
                "tileBudget=$tileBudget liveCameras=0 thermal=$thermal"
        )
    }

    private fun registerDnsSdLocalService() {
        val nodeId = OfflineIdentity.nodeId(applicationContext)
        val shortNodeIdHex = OfflineIdentity.hex(nodeId).takeLast(6)
        val name = OfflineIdentity.displayName(applicationContext)
        // FIX 2a: proves this path was actually reached at all — the 20:30
        // capture had zero "DNSSD: local register" lines (WifiDirectManager's
        // own success/failure log, now fixed to fire on every exit path),
        // which could mean either "never called" or "called but silently
        // bailed on a null channel"; this line disambiguates the first case.
        // OCP CONNECT REBUILD PART 3 / BUG (MAKE THE INVITE ACTUALLY
        // TRANSMIT) FIX PART 3.3: "gp" is the invited peer's SHORT NODE ID
        // again — see groupWaitingForShortId's doc for why a MAC-based gp
        // (the previous revision's approach) is unmatchable.
        val waitingForShortId = groupWaitingForShortId
        Log.d(
            "OFFTRACE",
            "DNSSD: registerDnsSdLocalService() called id=$shortNodeIdHex name=\"$name\" " +
                "gp=$waitingForShortId ss=$hostedGroupNetworkName"
        )
        // LEAK (PART 1.3): DNS-SD service registration — Wi-Fi-Direct-specific
        // discovery mechanism (local-wifi's Part 2 mDNS registration is a
        // different, transport-owned concept, not exposed through MeshTransport).
        wifiDirect.registerLocalService(
            name, shortNodeIdHex, MeshFrame.VERSION.toString(),
            waitingForShortId, hostedGroupNetworkName, hostedGroupPassphrase
        )
    }

    /**
     * CASE E1: no Thread.setDefaultUncaughtExceptionHandler existed anywhere in the
     * app, so a genuine uncaught crash and an OS-initiated background kill produced
     * the identical symptom (pid change, nothing in logcat). This closes that gap for
     * real crashes; it chains to whatever handler was already installed (if any) and
     * rethrows into it so process-death/crash-reporting semantics are unchanged —
     * this only adds a log line before that happens.
     */
    private fun installUncaughtExceptionLogger() {
        if (uncaughtHandlerInstalled) return
        uncaughtHandlerInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val stackLines = throwable.stackTrace.take(5).joinToString("\n") { "    at $it" }
            Log.e(
                "OFFTRACE",
                "FATAL UNCAUGHT on ${thread.name}: ${throwable.javaClass.simpleName}: ${throwable.message}\n$stackLines"
            )
            previous?.uncaughtException(thread, throwable)
        }
    }

    // PART 4.1/4.2: singleTask means a second launch while this Activity is
    // already on top (Settings' "Offline mesh settings" row, or the global
    // tab bar re-selecting Tab 3) arrives here, not in onCreate.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyOpenSettingsExtra(intent)
        handleShareSheetIntent(intent)
    }

    private fun applyOpenSettingsExtra(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_OPEN_SETTINGS_TAB, false)) {
            openOfflineSettingsOverlay()
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
            background = topoBackground // TOPO PHASE 1.2: the one root background, everywhere
        }
        applyTopoMode() // sets topoBackground's initial mode before first draw

        // PART 1.1/1.3 (batch A): "Offline mesh" title + the existing live
        // state line (statusText — every `statusText.text = ...` call site
        // elsewhere in this file is unaffected, only its container/position
        // changed) — the whole block is tappable, opening the Groups
        // overlay (see openGroupsOverlay/buildGroupsScreen, unchanged).
        nearbyHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener { openGroupsOverlay() }
        }
        nearbyHeader.addView(TextView(this).apply {
            text = "Offline mesh"
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(TopoPalette.fg(nightModeEnabled))
        })
        statusText = TextView(this).apply {
            // PART 3.6 (batch B): was "Offline Call" — a static leftover
            // title that read as a duplicate directly under "Offline mesh"
            // above it. This TextView's real job (unchanged, ~15 call
            // sites throughout this file: searching/connecting/
            // reconnecting/signaling-error/partyStatusLine etc.) is the
            // live mesh state line — "Not connected" is just this line's
            // correct value at cold start, before any of those fire.
            text = "Not connected"
            textSize = 18f
            setPadding(0, 0, 0, 24)
        }
        nearbyHeader.addView(statusText)
        root.addView(nearbyHeader)

        // PART 1.1: [ Start a group ] [ Scan a code ] — two equal buttons,
        // directly under the header. startHostingButton/scanCodeButton are
        // still constructed further down, unchanged — only their addView()
        // target moves, from searchScreen into this row.
        startGroupScanRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(startGroupScanRow)

        // PART 1.2: created here so searchScreen/groupScreen can be parented
        // into it below (same visibility-switching pattern they already
        // used as contentFrame's children — only the parent changed, and
        // callScreen is no longer part of this trio, see the class-level
        // doc on mainScroll for why). Added to scrollBody itself further
        // down, once construction of Home's original content is complete.
        val nearbyFrame = FrameLayout(this)

        // TOPO PART B9: displayNameButton REMOVED — it was a full-width
        // "My name: ..." button, permanently visible on every tab (A9),
        // duplicating Settings' own "Display name" row (buildSettingsScreen,
        // "Identity" section) exactly. Configuration, not action — it
        // belongs only in Settings, which already has it; not duplicated.

        // TOPO PART B3: opaque bgBase on every tab root — see switchTab's doc.
        searchScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(TopoPalette.bgBase(currentTopoMode()))
        }
        searchButton = Button(this).apply {
            text = "Search nearby"
            setOnClickListener { onSearchClicked() }
        }
        // PHASE 8 TRACK A: A2/A3 — merged Wi-Fi-Direct+BLE list, one INVITE
        // button per row (built by buildNearbyDeviceRow), no separate tap
        // handler needed — each row's own button drives sendInvite().
        peerListView = ListView(this)
        nearbyDeviceAdapter = NearbyDeviceAdapter()
        peerListView.adapter = nearbyDeviceAdapter

        // FIX 4: radar discovery UI — a second, separate view alongside
        // peerListView (see DiscoveryRadarView's class doc for why this is
        // NOT a PartyRingView change). 4g: list-view fallback toggle,
        // persisted so the choice survives a restart — radar is worse than
        // a list at 8+ devices per the task spec, so this is a real escape
        // hatch, not a decorative option.
        val radarDensity = resources.displayMetrics.density
        radarViewEnabled = getSharedPreferences("opencall", MODE_PRIVATE).getBoolean(PREF_RADAR_VIEW_ENABLED, true)
        discoveryRadarView = DiscoveryRadarView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (280 * radarDensity).toInt())
        }
        // 4d: the ONLY connect path — same sendInvite() choke point every
        // other invite entry point uses — wrapped in the same try/catch
        // buildNearbyDeviceRow's Invite-button handler already has, so a
        // radar-tap failure is surfaced on screen exactly like a list-tap
        // failure, not silently lost.
        discoveryRadarView.onDeviceTapped = { nodeId ->
            try {
                sendInvite(nodeId)
            } catch (t: Throwable) {
                Log.e("OFFTRACE", "radar tap handler threw", t)
                errorText.text = "radar tap handler threw: ${t.message ?: t.toString()}"
                errorText.visibility = View.VISIBLE
            }
        }
        radarStatusText = TextView(this).apply {
            textSize = 14f
            setPadding(0, (8 * radarDensity).toInt(), 0, (8 * radarDensity).toInt())
            setTextColor(TopoPalette.mutedFg(nightModeEnabled))
        }
        radarViewToggleButton = Button(this).apply {
            setOnClickListener {
                radarViewEnabled = !radarViewEnabled
                getSharedPreferences("opencall", MODE_PRIVATE).edit().putBoolean(PREF_RADAR_VIEW_ENABLED, radarViewEnabled).apply()
                applyRadarViewMode()
            }
        }
        // PART "HASSLE-FREE JOIN" item 4: "The Nearby screen becomes two
        // things and nothing else: a big 'Start a group' button, and a
        // list of open groups nearby (from BLE), each with one Join
        // button. Plus 'Scan a code'." This is now the FIRST content on
        // the screen — the old per-peer invite list below is hidden by
        // default (visibility GONE — see this field's own class-level doc
        // for why "hidden," not deleted).
        val nearbyDensity = resources.displayMetrics.density
        startHostingButton = Button(this).apply {
            text = "Start a group"
            textSize = 18f
            setPadding(0, (20 * nearbyDensity).toInt(), 0, (20 * nearbyDensity).toInt())
            setOnClickListener { startHosting() }
        }
        startGroupScanRow.addView(startHostingButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        searchScreen.addView(TextView(this).apply {
            text = "Open groups nearby"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(TopoPalette.mutedFg(nightModeEnabled))
            setPadding((16 * nearbyDensity).toInt(), (16 * nearbyDensity).toInt(), (16 * nearbyDensity).toInt(), (4 * nearbyDensity).toInt())
        })
        openGroupsEmptyLabel = TextView(this).apply {
            text = "None seen yet — stay nearby, this updates automatically"
            textSize = 13f
            setTextColor(TopoPalette.mutedFg(nightModeEnabled))
            setPadding((16 * nearbyDensity).toInt(), 0, (16 * nearbyDensity).toInt(), (8 * nearbyDensity).toInt())
        }
        searchScreen.addView(openGroupsEmptyLabel)
        openGroupsAdapter = OpenGroupsAdapter()
        openGroupsListView = ListView(this).apply { adapter = openGroupsAdapter }
        searchScreen.addView(openGroupsListView)
        scanCodeButton = Button(this).apply {
            text = "Scan a code"
            setOnClickListener { showScanQrDialog() }
        }
        startGroupScanRow.addView(scanCodeButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // Old per-peer invite UI — hidden, not deleted; see the class-level
        // doc on startHostingButton's field group for why.
        searchButton.visibility = View.GONE
        radarViewToggleButton.visibility = View.GONE
        discoveryRadarView.visibility = View.GONE
        radarStatusText.visibility = View.GONE
        peerListView.visibility = View.GONE
        searchScreen.addView(searchButton)
        searchScreen.addView(radarViewToggleButton)
        searchScreen.addView(discoveryRadarView)
        searchScreen.addView(radarStatusText)
        searchScreen.addView(peerListView)
        applyRadarViewMode()
        nearbyFrame.addView(searchScreen) // PART 1.2 (batch A): was contentFrame.addView — now Nearby content, inside nearbyFrame

        // PHASE 3: roster screen — built once here, shown between "joined the group"
        // and "placed/received a call".
        groupScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(TopoPalette.bgBase(currentTopoMode())) // TOPO PART B3
            visibility = View.GONE
        }
        // OFFLINE UI STEP 5: SOS ARMING is now slideToSosView's slide-then-
        // hold gesture (below) — sosButton itself becomes CANCEL-ONLY, tap
        // active while an SOS is already running (matches the "(tap to
        // cancel)" text updateSosButtonUi already shows), disabled
        // otherwise. Same mediaTransport?.stopSos() call as before — only
        // the ARM path's trigger changed, never the underlying SOS
        // lifecycle (see OUTPUT proof #6).
        sosButton = Button(this).apply {
            text = "Group Alert"
            isEnabled = false
            setOnClickListener {
                if (sosActive) {
                    mediaTransport?.stopSos()
                    sosActive = false
                    updateSosButtonUi()
                }
            }
        }
        slideToSosView = SlideToSosView(this).apply {
            // OFFLINE UI STEP 5: the ONLY call this makes is the exact same
            // mediaTransport?.startSos(null) the old plain-tap button made —
            // see OUTPUT proof #6 for why the payload/ack/carry path is
            // untouched by this gesture swap. PART 3.3 (batch B): factored
            // into armGroupAlert() so buildGroupAlertBar's own 3s hold fires
            // through the exact same call, never a second copy of it.
            onArmed = { armGroupAlert() }
        }
        // PHASE 5A: incoming SOS/Find results — deliberately a SEPARATE element
        // from rosterListView, not a row folded into it, so an incoming SOS can't
        // be silently lost among ordinary peer rows. GONE (nothing shown) whenever
        // there's nothing to report.
        sosAlertsText = TextView(this).apply {
            textSize = 13f
            setPadding(16, 12, 16, 12)
            visibility = View.GONE
        }
        // PHASE 6 TRACK B: hands-free trigger toggles + emergency contacts.
        sosSettingsButton = Button(this).apply {
            text = "Group Alert settings"
            setOnClickListener { showSosSettingsDialog() }
        }
        // PHASE 6 TRACK D: last-resort SSID broadcast — explicit confirmation
        // required every time (see SosSsidBroadcast's class doc: this disconnects
        // the whole party from the mesh while active).
        ssidBroadcastButton = Button(this).apply {
            text = "Last resort: broadcast SSID"
            setOnClickListener { onSsidBroadcastButtonClicked() }
        }
        // PHASE 5BC: party-status screen — every known ledger member (not just
        // active SOS senders), including anyone currently out of contact.
        partyStatusButton = Button(this).apply {
            text = "Party status"
            setOnClickListener {
                renderPartyStatus()
                partyStatusOverlay.visibility = View.VISIBLE
            }
        }
        rosterListView = ListView(this)
        rosterListView.setOnItemClickListener { _, _, position, _ -> onRosterItemClicked(position) }
        // PHASE 5A: long-press a roster row to "Find" that peer — tap alone is
        // already taken (opens the call-mode dialog), so this needs its own
        // gesture rather than a conflicting second tap target.
        rosterListView.setOnItemLongClickListener { _, _, position, _ ->
            onRosterItemLongClicked(position)
        }
        // TOPO PART B2: "End session" MOVED to Settings > Mesh network (see
        // buildSettingsScreen) — a group-teardown control is not a
        // messaging/roster action. leaveGroupButton itself is unused now
        // (kept as a lateinit field only because other code may still
        // reference the type; the actual button lives in Settings).
        // PHASE 8 TRACK A: A2/A3 — replaces the old "Add to group: X" plain-text
        // trailing rows with the same merged, stateful invite row every other
        // surface uses (GO-only, same as the rows it replaces — see
        // notifyNearbyAdaptersChanged's visibility gate).
        val inviteHeader = TextView(this).apply {
            text = "Nearby — invite"
            textSize = 14f
            setPadding(0, 24, 0, 8)
        }
        inviteListView = ListView(this)
        inviteListAdapter = NearbyDeviceAdapter()
        inviteListView.adapter = inviteListAdapter
        inviteListView.visibility = View.GONE
        val ringDensity = resources.displayMetrics.density

        // ── Signal Deck (diagnostic follow-up) top bar: replaces the old
        // identity strip (short ID/name/edit — still reachable via
        // Settings' own "Display name" row, see showDisplayNameDialog's
        // other call site) with a dim label + bold group name, and a
        // top-right circular theme-toggle icon. identityShortIdText/
        // identityNameText are intentionally not constructed here anymore
        // — updateIdentityStrip's own ::isInitialized guard makes that a
        // safe no-op, not a crash, for its one remaining call site.
        val signalDeckTopBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (48 * ringDensity).toInt()
        }
        val signalDeckTopBarLabels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        signalDeckDimLabel = TextView(this).apply {
            text = "OCP · MESH ACTIVE"
            textSize = 11f
            setTextColor(TopoPalette.textMuted(currentTopoMode()))
        }
        signalDeckGroupNameText = TextView(this).apply {
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(TopoPalette.fg(nightModeEnabled))
        }
        signalDeckTopBarLabels.addView(signalDeckDimLabel)
        signalDeckTopBarLabels.addView(signalDeckGroupNameText)
        signalDeckTopBar.addView(signalDeckTopBarLabels)
        signalDeckTopBar.addView(buildThemeToggleIcon())
        groupScreen.addView(signalDeckTopBar)

        // ── PHASE 3 item 3: group call bar — PRIMARY action, reaches the SAME
        // mediaTransport.startGroupCall() call showStartGroupCallDialog()
        // already makes (no new call path). Disabled with a reason string at
        // 0 connected peers. Signal Deck: voice is an outline button, video
        // is filled amber (TopoPalette.accent) — video is the heavier-weight
        // action on this screen, same "filled = primary" convention the new
        // "Bring someone in" card's rows don't need but a two-choice row does.
        val groupCallBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = (64 * ringDensity).toInt()
        }
        groupCallVoiceButton = Button(this).apply {
            text = "Voice call"
            setOnClickListener { mediaTransport?.startGroupCall(OfflineMediaTransport.GroupCallMode.AUDIO) }
        }
        groupCallVideoButton = Button(this).apply {
            text = "Video call"
            setOnClickListener { mediaTransport?.startGroupCall(OfflineMediaTransport.GroupCallMode.VIDEO) }
        }
        applyCallButtonStyles()
        groupCallBar.addView(groupCallVoiceButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
            marginEnd = (8 * ringDensity).toInt()
        })
        groupCallBar.addView(groupCallVideoButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        groupCallReasonText = settingsInfoLine("")
        groupScreen.addView(groupCallBar)
        groupScreen.addView(groupCallReasonText)
        // Not in the new card list, but not explicitly asked to be removed
        // either (only "Quick phrase" was) — DELETED: confirmed redundant
        // with the roster's own synthetic "Group chat (N member(s))" row
        // (renderRosterList, position 0), which predates this change and
        // reaches the same destination. No reachability lost.

        // ── Signal Deck (diagnostic follow-up) party ring card — ALWAYS
        // visible now (no ringSummaryButton collapse/expand): the NaN-
        // bearing fallback added to PartyRingView.drawLiveOrStalePeer means
        // a connected peer with no resolved bearing still draws (as a rim
        // arc in its own color) instead of vanishing, so there's no longer
        // a "nothing useful to show" case worth hiding behind a tap. The
        // whole card is tappable to open the full partyStatusOverlay — same
        // destination the old separate "Party status" button opened (see
        // that button's own doc; it's still constructed, just not shown,
        // for setNightMode's color-update call site).
        //
        // Background: TopoPalette.bgRaised, not TopoBackgroundDrawable's
        // contour texture — checked first per the task's own instruction;
        // that texture is already the Activity root's background (see
        // topoBackground/applyTopoMode), so reapplying it to this card
        // would double the contour pattern rather than add one, and this
        // view has no exposed hook to reuse just its fill without its
        // Paths anyway (see that class's own "one root-level Drawable...
        // not per-view" doc).
        batteryCliffBanner = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(16, 12, 16, 12)
            visibility = View.GONE
        }
        groupScreen.addView(batteryCliffBanner)
        val ringCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(TopoPalette.bgRaised(currentTopoMode()))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                renderPartyStatus()
                partyStatusOverlay.visibility = View.VISIBLE
            }
        }
        signalDeckStatusText = TextView(this).apply {
            textSize = 14f
            setTextColor(TopoPalette.textSecondary(currentTopoMode()))
            setPadding((12 * ringDensity).toInt(), (10 * ringDensity).toInt(), (12 * ringDensity).toInt(), (6 * ringDensity).toInt())
        }
        partyRingView = PartyRingView(this).apply {
            onMarkerTapped = { nodeId -> showPeerDetailForRing(nodeId) }
            onMarkerLongPressed = { nodeId -> sendPhraseTo(nodeId) }
        }
        ringCard.addView(signalDeckStatusText)
        ringCard.addView(
            partyRingView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (280 * ringDensity).toInt())
        )
        groupScreen.addView(
            ringCard,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (8 * ringDensity).toInt()
                bottomMargin = (8 * ringDensity).toInt()
            }
        )
        carryChip = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding((16 * ringDensity).toInt(), (10 * ringDensity).toInt(), (16 * ringDensity).toInt(), (10 * ringDensity).toInt())
            setBackgroundColor(Color.argb(180, 60, 60, 60))
            visibility = View.GONE
            setOnClickListener { showCarryQueueDialog() }
        }
        groupScreen.addView(carryChip)
        updateRingCardState()

        // ── Signal Deck (diagnostic follow-up): ONE consolidated "bring
        // someone in" card, replacing the old inviteQrRow (Invite/Show my
        // QR code/Scan QR code) AND searchScreen's separate "Scan a code"
        // entry point — three rows, same underlying actions as before,
        // just one card instead of five overlapping buttons across two
        // screens. "Your code" uses fetchGroupInfoThenShowHostQr (the
        // live-polling "who's joined" screen, see its own doc for why
        // showHostQrScreen itself is never called directly) rather than
        // the older, simpler showInviteQrDialog — per the task's explicit
        // naming of showHostQrScreen as the target.
        groupScreen.addView(settingsSectionHeader("Bring someone in"))
        val bringSomeoneInCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(TopoPalette.bgRaised(currentTopoMode()))
        }
        bringSomeoneInCard.addView(buildBringSomeoneInRow("⬚", "Your code", "Show a QR code for others to scan") { fetchGroupInfoThenShowHostQr() })
        bringSomeoneInCard.addView(buildSignalDeckDivider())
        bringSomeoneInCard.addView(buildBringSomeoneInRow("⌕", "Scan a code", "Scan someone else's invite code") { showScanQrDialog() })
        bringSomeoneInCard.addView(buildSignalDeckDivider())
        bringSomeoneInCard.addView(buildBringSomeoneInRow("↗", "Share invite link", "Send a link via any app") { shareInviteLink() })
        groupScreen.addView(bringSomeoneInCard)

        // TOPO PHASE 2.3: sosButton/slideToSosView/sosSettingsButton/
        // ssidBroadcastButton/sosAlertsText MOVED into sosSectionOverlay
        // (built below, opened by the always-visible top-right SOS button)
        // — not duplicated here. partyStatusButton itself is still
        // constructed (setNightMode's colour-update call site still
        // references it) but no longer shown — tapping the ring card above
        // reaches the same partyStatusOverlay now.

        // ── PHASE 3 item 5: member list header — Signal Deck: no longer
        // paired with its own "Invite" button (the "Bring someone in" card
        // above already covers invite; showMidCallInviteDialog, the
        // per-nearby-device picker, stays reachable from the "Nearby —
        // invite" list below, which is a live list, not a button). ──────
        groupMembersHeader = TextView(this).apply { textSize = 16f; setPadding(0, 24, 0, 0) }
        groupScreen.addView(groupMembersHeader)
        groupScreen.addView(rosterListView)
        groupScreen.addView(inviteHeader)
        groupScreen.addView(inviteListView)
        // TOPO PART B2: "End session" button moved to Settings > Mesh network.
        nearbyFrame.addView(groupScreen) // PART 1.2 (batch A): was contentFrame.addView — now Nearby content, inside nearbyFrame

        buildGroupCallScreen(root)

        callScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // TOPO PART B3: opaque tab-root background — cosmetic only, the
            // outer container behind videoFrame's own child views; does not
            // touch the video surface/grid itself (G4).
            setBackgroundColor(TopoPalette.bgBase(currentTopoMode()))
            visibility = View.GONE
        }

        videoFrame = FrameLayout(this)

        // Plain SurfaceView for the MediaCodec decoder output. holder.surface is passed
        // to the transport once surfaceCreated fires (always before the first encoded
        // frame arrives).
        val mediaView = SurfaceView(this)
        mediaRemoteView = mediaView
        mediaView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                mediaSurface = holder.surface
                mediaTransport?.setDisplaySurface(holder.surface)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { mediaSurface = null }
        })
        videoFrame.addView(
            mediaView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        val density = resources.displayMetrics.density

        // CALL MODES: shown instead of the video views in AUDIO/CHAT mode (no camera
        // feed to display) — peer name + a running call timer.
        modeInfoBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        peerNameText = TextView(this).apply {
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        callTimerText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            text = "00:00"
        }
        modeInfoBar.addView(peerNameText)
        modeInfoBar.addView(callTimerText)
        videoFrame.addView(
            modeInfoBar,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
        )

        errorText = TextView(this).apply {
            setTextColor(Color.RED)
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
            visibility = View.GONE
        }
        videoFrame.addView(
            errorText,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
        )

        // TASK 2: chat overlay — semi-transparent message list + input row, stacked with
        // the hangup button into one bottom-anchored panel over the video (never pushes
        // the video area around; just floats on top of it, hence semi-transparent).
        // PHASE 3: also reused, full-screen (see applyUiForMode), for the group-chat-only
        // screen (no camera/mic involved there).
        chatListView = ListView(this).apply {
            setBackgroundColor(Color.argb(120, 0, 0, 0))
            divider = null
            dividerHeight = 0
            isFocusable = false
            transcriptMode = ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL
        }
        chatAdapter = ChatAdapter()
        chatListView.adapter = chatAdapter

        chatInput = EditText(this).apply {
            hint = "Message"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            // TOPO PART B6: was raw Color.argb(160,255,255,255) — light grey
            // on a dark screen (Part A's A7). bgRaised/textPrimary/textMuted,
            // matching messagesComposerInput's own composer exactly.
            setBackgroundColor(TopoPalette.bgRaised(currentTopoMode()))
            setTextColor(TopoPalette.textPrimary(currentTopoMode()))
            setHintTextColor(TopoPalette.textMuted(currentTopoMode()))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { onSendChatClicked(); true } else false
            }
        }
        val sendButton = Button(this).apply {
            text = "Send"
            setOnClickListener { onSendChatClicked() }
        }
        val chatInputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            addView(chatInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(sendButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        // TOPO PHASE 3.3: no custom picker — emoji are plain UTF-8 text that
        // rides the EXISTING CHAT payload with zero protocol work (see
        // OUTPUT proof #7). This row just inserts one of 6 emoji into
        // chatInput and calls the SAME onSendChatClicked() the Send button
        // uses — never a second send path.
        val emojiRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), 0)
            QUICK_REACTION_EMOJI.forEach { emoji ->
                addView(Button(this@OfflineCallActivity).apply {
                    text = emoji
                    textSize = 20f
                    minWidth = (48 * density).toInt()
                    setOnClickListener {
                        chatInput.setText(emoji)
                        onSendChatClicked()
                    }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }

        hangupButton = Button(this).apply {
            text = "Hang up" // TOPO 1.3: sentence case
            setOnClickListener { onHangupClicked() }
        }
        val hangupRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, (8 * density).toInt(), 0, (16 * density).toInt())
            addView(hangupButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(chatListView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (200 * density).toInt()))
            addView(emojiRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(chatInputRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(hangupRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        videoFrame.addView(
            bottomPanel,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
            }
        )

        // TASK 1: re-letterbox whenever videoFrame's own size changes (rotation, etc.).
        videoFrame.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val widthChanged = (right - left) != (oldRight - oldLeft)
            val heightChanged = (bottom - top) != (oldBottom - oldTop)
            if (widthChanged || heightChanged) {
                applyVideoAspectRatio(remoteVideoWidth, remoteVideoHeight)
            }
        }

        callScreen.addView(
            videoFrame,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        )
        // PART 1 (batch A): callScreen is a direct `root` child with its own
        // weight=1f, exactly like groupCallScreen already was — see the
        // class-level doc on [mainScroll] for why (videoFrame's letterboxing
        // is keyed to ITS OWN full-viewport layout size, which a WRAP_CONTENT
        // scroll section can't give it). GONE by default (set above); shown
        // full-bleed by updateFullBleedCallState, called from every existing
        // callScreen.visibility call site.
        root.addView(
            callScreen,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f }
        )

        // PART 1.2/1.7 (batch A): messagesThreadView built here (not lazily,
        // inside buildMessagesScreen — see that function's own doc) and
        // added as a root-level weight=1f sibling, same full-bleed
        // treatment as callScreen, for the same reason (its ListView is
        // itself weight=1f — needs a bounded, full viewport, not a scroll
        // section). GONE by default (set inside buildMessagesThreadView
        // itself).
        buildMessagesThreadView() // assigns the messagesThreadView field itself
        root.addView(
            messagesThreadView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f }
        )
        // fix (crash): messagesListBody (built inside buildMessagesScreen,
        // via lazyMessagesScreen) used to only exist once the Messages
        // placeholder below scrolled into view — openMessageThread() (now
        // reachable directly from the roster's "Group chat" row, see that
        // fix) touches messagesListBody unconditionally with no
        // isInitialized guard, so a tap before ever scrolling there crashed
        // with UninitializedPropertyAccessException. Built eagerly here
        // instead, same treatment buildMessagesThreadView already gets just
        // above — NOT restoring the lazy-scroll trigger as the ONLY path (that
        // was the original discoverability bug this whole fix chain started
        // from). lazyMessagesScreen() is idempotent (caches messagesScreenView),
        // so addLazySection below still registers its own placeholder/trigger
        // safely — if that ever fires, it just gets back this same
        // already-built instance.
        lazyMessagesScreen()

        // PART 1.2 (batch A): nearbyFrame (searchScreen/groupScreen, eager —
        // exactly as eager as they always were) plus Messages/Calls, each
        // scroll-triggered lazy (see addLazySection) — replacing
        // contentFrame+bottomNav. Groups/Settings are no longer sections at
        // all (PART 1.3/1.4) — see openGroupsOverlay/openOfflineSettingsOverlay.
        // Messages' own content is no longer actually deferred by this (see
        // the eager lazyMessagesScreen() call just above) — addLazySection
        // is kept here only so its placeholder sizing/swap-in behavior for
        // Messages stays identical to Calls', not because construction still
        // waits on it.
        scrollBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollBody.addView(nearbyFrame)
        addLazySection(scrollBody) { lazyMessagesScreen() }
        addLazySection(scrollBody) { lazyCallsScreen() }
        mainScroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            addView(scrollBody, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(
            mainScroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f }
        )

        // PHASE 5BC: wrap the existing `root` in a FrameLayout so the SOS alert /
        // party-status overlays can stack ABOVE it — over the roster screen AND
        // over an active call screen, per the spec, without touching anything
        // inside `root` (searchScreen/groupScreen/callScreen/videoFrame/tiles are
        // all untouched siblings underneath).
        overlayRoot = FrameLayout(this)
        overlayRoot.addView(
            root,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        sosOverlay = buildFullScreenOverlay(
            titleText = "🔔 Group Alert", // TOPO 1.3: sentence case; PART 5.2: renamed, see report
            titleBg = TopoPalette.danger(currentTopoMode()), // TOPO 1.4: was a raw Color.argb literal — SOS is the one legitimate use of the danger role
            onCloseSilenceAll = { sosAlarm.silence() }
        ) { sosOverlayBody = it }
        partyStatusOverlay = buildFullScreenOverlay(
            titleText = "Party status",
            titleBg = TopoPalette.bgRaised(currentTopoMode()), // TOPO 1.4: was raw Color.DKGRAY
            onCloseSilenceAll = null
        ) { body ->
            // B1/Step 3 (diagnostic follow-up): relocated here from the SOS/
            // Group Alert overlay — a user looks for group/roster data here,
            // not behind the emergency control. Added as a STATIC row on
            // [body] itself, never on the nested list below — renderPartyStatus
            // clears/rebuilds that nested container per-peer (including a
            // full removeAllViews() on the "no position data yet" empty
            // state), which would silently wipe a button added directly to it.
            body.addView(settingsButtonRow(
                "Export SOS timeline + last-known positions",
                "Export"
            ) {
                val report = IncidentExporter.buildReport(applicationContext)
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "OpenCall Relay — Incident Report")
                    putExtra(Intent.EXTRA_TEXT, report)
                }, "Share incident report via"))
            })
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            body.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            partyStatusOverlayBody = list
        }
        sosSectionOverlay = buildSosSectionOverlay()
        overlayRoot.addView(
            sosOverlay,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        overlayRoot.addView(
            partyStatusOverlay,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        overlayRoot.addView(
            sosSectionOverlay,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        applyTopoMode()

        // PART 3.1 (batch B): the floating top-right circle is gone — see
        // buildGlobalShellWrapper for where buildGroupAlertBar() replaces it.
        val shellWrapper = buildGlobalShellWrapper(overlayRoot)
        setContentView(shellWrapper)
        // PART 2.2: targetSdk 36 — edge-to-edge is mandatory, no opt-out.
        // Applied to the OUTERMOST wrapper (top bar + this Activity's own
        // untouched content + global tab bar) — see AppShell.
        // applySystemBarInsets's own doc for why one padding call here
        // covers the top-right SOS button, the group-call grid, and every
        // other thing nested inside it, with zero changes to any of that
        // existing, deliberately-untouched code.
        com.opencall.relay.shell.AppShell.applySystemBarInsets(shellWrapper)
    }

    // PART 1/4.2: purely additive wrap — overlayRoot (everything above,
    // including this Activity's own existing 5-tab Nearby/Messages/Calls/
    // Groups/Settings sub-nav) is untouched and unmoved; this only adds the
    // shared top bar above it and the global 3-tab pillar bar below it, so
    // Tab 3 carries the same chrome Tabs 1/2 (MainActivity) do. See this
    // class's doc for why tab-switch navigation here never calls finish().
    private fun buildGlobalShellWrapper(overlayRoot: View): View {
        val topBar = com.opencall.relay.shell.AppShell.buildTopBar(this) {
            startActivity(Intent(this, com.opencall.relay.settings.SettingsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            })
        }
        val globalTabBar = com.opencall.relay.shell.AppShell.buildBottomTabBar(
            this, com.opencall.relay.shell.AppTab.OFFLINE
        ) { tab ->
            if (com.opencall.relay.shell.AppShell.requiresLeavingCurrentActivity(com.opencall.relay.shell.AppTab.OFFLINE, tab)) {
                startActivity(Intent(this, com.opencall.relay.MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    putExtra(com.opencall.relay.MainActivity.EXTRA_SELECT_TAB, tab.name)
                })
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(topBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(overlayRoot, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            // PART 3.2 (batch B): pinned directly above AppShell's tab bar,
            // tab-3-only by construction — only OfflineCallActivity's own
            // chrome builder ever calls this, MainActivity's shell never does.
            addView(buildGroupAlertBar(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(
                com.opencall.relay.shell.AppShell.buildDivider(this@OfflineCallActivity),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * resources.displayMetrics.density).toInt())
            )
            addView(globalTabBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    /** PART 3.3 (batch B): the ONE place that actually fires an alert —
     *  slideToSosView's slide-then-hold (inside the Group Alert section)
     *  and buildGroupAlertBar's own straight 3s hold (pinned to the bottom
     *  of the Offline scroll) both call this and nothing else, so there is
     *  exactly one arm path regardless of which gesture triggered it (same
     *  "never a second copy of the payload/ack/carry side effect"
     *  requirement Batch A's OUTPUT proof #6 already established). */
    private fun armGroupAlert() {
        mediaTransport?.startSos(null)
        sosActive = true
        updateSosButtonUi()
        sosButton.isEnabled = true
    }

    /** PART 3.2/3.3 (batch B): replaces the old floating top-right circle
     *  (buildTopRightSosButton, deleted — see the batch report) with a
     *  fixed, full-width bar pinned above AppShell's tab bar, tab-3-only by
     *  construction (only buildGlobalShellWrapper, this Activity's own
     *  chrome builder, ever calls this). A SHORT TAP ONLY EVER OPENS
     *  sosSectionOverlay — never fires an alarm by itself, exactly the old
     *  button's guarantee. Firing needs a full 3.0s hold, timed by
     *  [SlideToSos] — the SAME pure hold-timing object slideToSosView
     *  already uses (HOLD_MS/shouldFire), reused verbatim here rather than
     *  reimplemented; only the touch wiring is new; the straight-hold
     *  gesture, not slideToSosView's slide-then-hold, so it can't reuse
     *  that View directly. */
    private fun buildGroupAlertBar(): View {
        val density = resources.displayMetrics.density
        val bar = TextView(this)
        groupAlertBar = bar
        var downAtElapsedMs = 0L
        // True once THIS press-and-hold gesture has already fired armGroupAlert()
        // (sosActive flips to true mid-hold, before the finger lifts) — its own
        // release must NOT also be read as the separate "tap while active to
        // cancel" gesture below, or arming would immediately self-cancel.
        var armedThisGesture = false
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                if (sosActive) return // became active some other way mid-hold — nothing left to fire
                val held = android.os.SystemClock.elapsedRealtime() - downAtElapsedMs
                if (SlideToSos.shouldFire(held)) {
                    armedThisGesture = true
                    armGroupAlert() // also calls updateSosButtonUi(), which refreshes this bar's text/background
                    return
                }
                mainHandler.postDelayed(this, 50L)
            }
        }
        bar.apply {
            text = "Hold 3s for group alert"
            gravity = Gravity.CENTER
            textSize = 14f
            minHeight = (52 * density).toInt()
            // fix: restore cancel path for active group alert — idle/active
            // styling now lives in updateSosButtonUi, the single place that
            // already tracks sosActive transitions from every path (this
            // gesture, a reconnect re-arm, a mode change) — applied once more
            // here so the bar starts in the right state if built while
            // already active (e.g. after a GO-election reconnect re-arm).
            applyGroupAlertBarStyle(sosActive)
            setOnTouchListener { _, event ->
                if (sosActive) {
                    // Active state: cancel is a PLAIN TAP, no hold required —
                    // cancelling must be easy, unlike arming (deliberately
                    // hard, 3s hold, see SlideToSos's own doc).
                    when (event.action) {
                        android.view.MotionEvent.ACTION_UP -> {
                            if (armedThisGesture) {
                                // This exact press is the SAME gesture that just
                                // armed the alert (tick fired mid-hold) — its
                                // release must not also cancel what it just armed.
                                armedThisGesture = false
                            } else {
                                // Explicit confirmation this is a deliberate
                                // cancel tap, not the rebroadcast loop simply
                                // not firing — distinguishable in a log from
                                // every other "SOS: ..." line.
                                Log.d("OFFTRACE", "SOS: cancelled by=${MeshFrame.hex(mediaTransport?.localNodeId ?: 0L)}")
                                mediaTransport?.stopSos()
                                sosActive = false
                                updateSosButtonUi()
                            }
                            true
                        }
                        android.view.MotionEvent.ACTION_CANCEL -> {
                            armedThisGesture = false
                            true
                        }
                        else -> true // swallow DOWN so this branch owns the gesture start to finish
                    }
                } else {
                    when (event.action) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            downAtElapsedMs = android.os.SystemClock.elapsedRealtime()
                            armedThisGesture = false
                            text = "Hold…"
                            mainHandler.postDelayed(tick, 50L)
                            true
                        }
                        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                            mainHandler.removeCallbacks(tick)
                            val wasTap = !armedThisGesture && event.action == android.view.MotionEvent.ACTION_UP
                            if (!armedThisGesture) text = "Hold 3s for group alert"
                            if (wasTap) {
                                // Never fires anything — only shows the section. See doc above.
                                sosSectionOverlay.visibility = View.VISIBLE
                                renderSosAlerts() // refresh the active-SOS list the moment the section opens
                            }
                            true
                        }
                        else -> false
                    }
                }
            }
        }
        return bar
    }

    /** fix: restore cancel path for active group alert — idle: outlined red
     *  ("Hold 3s for group alert"); active: filled red ("Alert active · tap
     *  to cancel"). Called from [buildGroupAlertBar] (initial state) and
     *  [updateSosButtonUi] (every subsequent sosActive transition, from
     *  whichever path caused it — this gesture, a reconnect re-arm, or a
     *  mode/theme change), so the two views can never fall out of sync,
     *  same guarantee this file's existing sosButton/groupAlertBar comment
     *  already documents. No-op if the bar hasn't been built yet (Settings/
     *  other screens construct this Activity's chrome before the Offline
     *  tab's own views in some code paths). */
    private fun applyGroupAlertBarStyle(active: Boolean) {
        if (!::groupAlertBar.isInitialized) return
        val mode = currentTopoMode()
        if (active) {
            groupAlertBar.text = "Alert active · tap to cancel"
            groupAlertBar.setTextColor(TopoPalette.onAccent(mode))
            groupAlertBar.setBackgroundColor(TopoPalette.danger(mode))
        } else {
            groupAlertBar.text = "Hold 3s for group alert"
            groupAlertBar.setTextColor(TopoPalette.danger(mode))
            val density = resources.displayMetrics.density
            groupAlertBar.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(TopoPalette.dangerBg(mode))
                setStroke((2 * density).toInt(), TopoPalette.danger(mode))
            }
        }
    }

    /** TOPO PHASE 2.3: everything SOS-related, moved (not rebuilt) into one
     *  section — slideToSosView (the only path that ever fires an alarm),
     *  the active-SOS list (sosAlertsText, unchanged), cancel (sosButton,
     *  already cancel-only), trigger settings, siren/hold-duration info
     *  (moved from Settings' old "Safety" section), beacon/SSID broadcast,
     *  and the PLB disclaimer line (also moved from Settings). */
    private fun buildSosSectionOverlay(): LinearLayout {
        val density = resources.displayMetrics.density
        return buildFullScreenOverlay(
            titleText = "Group Alert",
            titleBg = TopoPalette.danger(currentTopoMode()),
            onCloseSilenceAll = null
        ) { body ->
            body.addView(
                slideToSosView,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (64 * density).toInt()).apply {
                    setMargins(0, (12 * density).toInt(), 0, (12 * density).toInt())
                }
            )
            // PART 3.5 (batch B): sosButton (the redundant plain button)
            // removed — slideToSosView is the one control now. NOTE: this
            // also removes the only UI path that could CANCEL an
            // already-active Group Alert (sosButton was cancel-only while
            // active, see armGroupAlert's own doc) — flagged in the batch
            // report, not silently dropped.
            body.addView(sosAlertsText)
            body.addView(settingsSectionHeader("Trigger settings"))
            body.addView(sosSettingsButton)
            body.addView(settingsInfoLine("Group Alert hold duration: ${SlideToSos.HOLD_MS / 1000.0}s (fixed — a consistent hold time is part of what makes the gesture hard to trigger by accident)"))
            body.addView(settingsInfoLine("Siren volume: always maximum during an active Group Alert. This is intentional and cannot be lowered, so a real alert is never accidentally missed."))
            body.addView(settingsSectionHeader("Beacon"))
            body.addView(ssidBroadcastButton)
            // PART 5.2: the required disclaimer, added (not replacing the
            // existing PLB line below, which is renamed and kept).
            body.addView(settingsInfoLine(
                "Group Alert notifies nearby devices in your mesh over Wi-Fi Direct. It is not a substitute for calling emergency services."
            ))
            body.addView(settingsInfoLine(
                "OpenCall's Group Alert supplements, but does NOT replace, a personal locator beacon (PLB) or satellite messenger. Carry one on any serious trip."
            ))
            // Step 3 (diagnostic follow-up): Incident report export MOVED to
            // partyStatusOverlay — see that overlay's own build site. A user
            // looks for group/roster data there, not behind the emergency
            // control this overlay is.
            // Step 4 (diagnostic follow-up): Voice notes MOVED to the
            // Messages screen (buildMessagesScreen) — a voice message is a
            // normal communication feature with no business living behind
            // the emergency control; see that build site.
        }
    }

    /** Step 3 (diagnostic follow-up): UI-side mutable view of one
     *  attachment. [msgId] identifies it on the wire/disk; a plain class
     *  (not data class) deliberately — [fetchedBytes]/[requesting] mutate
     *  IN PLACE on the SAME instance (shared between [attachmentRefsByMsgId]
     *  and whichever ChatEntry holds it), so the SAME chat bubble
     *  transitions from placeholder to available without ever needing to
     *  find-and-replace the ChatEntry itself. [isAvailable]: an inline kind
     *  (LOCATION/CONTACT) has nothing to fetch — it arrived complete in meta. */
    private class AttachmentRef(
        val msgId: String,
        val kind: OfflineMediaTransport.AttachmentKind,
        val senderNodeId: Long,
        val meta: OfflineMediaTransport.AttachmentMeta,
        var fetchedBytes: ByteArray? = null,
        var requesting: Boolean = false
    ) {
        val isAvailable: Boolean get() = kind.isInline || fetchedBytes != null
    }

    // Step 3: msgId -> the live AttachmentRef instance, so a later
    // onAttachmentDataReceived(msgId, bytes) can find and mutate the exact
    // object already referenced by an existing ChatEntry/bubble.
    private val attachmentRefsByMsgId = mutableMapOf<String, AttachmentRef>()

    /** Step 3 (diagnostic follow-up): wired to
     *  OfflineMediaTransport.onAttachmentMetaReceived — appends a
     *  placeholder (or, for an inline kind, the complete message) into
     *  chatMessages, same list/render path text messages use. Mirrors
     *  onTransportChatMessage's existing pattern: appended unconditionally
     *  (chatMessages has no per-thread filtering — see that function's own
     *  doc) plus a thread-preview update. Every kind sendAttachment
     *  supports today is broadcast-only, so this is always the Group
     *  thread's preview. */
    private fun onAttachmentMetaReceived(state: OfflineMediaTransport.AttachmentState) {
        val meta = state.meta
        val ref = AttachmentRef(meta.msgId, meta.kind, state.senderNodeId, meta)
        attachmentRefsByMsgId[meta.msgId] = ref
        val who = nameForGroupParticipant(state.senderNodeId)
        val (bodyText, previewText) = when (meta.kind) {
            OfflineMediaTransport.AttachmentKind.VOICE ->
                "sent a voice note" to "🎤 Voice note (${formatVoiceNoteDuration(meta.durationMs ?: 0)})"
            OfflineMediaTransport.AttachmentKind.IMAGE ->
                "sent a photo" to "📷 Photo"
            OfflineMediaTransport.AttachmentKind.DOCUMENT ->
                "sent a document" to "📄 ${meta.filename ?: "Document"}"
            OfflineMediaTransport.AttachmentKind.LOCATION ->
                "shared a location" to "📍 Location"
            OfflineMediaTransport.AttachmentKind.CONTACT ->
                "shared a contact" to "👤 ${meta.contactName ?: "Contact"}"
        }
        appendChatMessage(text = "[Group] $who $bodyText", fromMe = false, attachment = ref)
        recordThreadPreview(MeshFrame.BROADCAST_ID, previewText, fromMe = false)
    }

    /** Step 3: wired to OfflineMediaTransport.onAttachmentDataReceived —
     *  mutates the EXISTING AttachmentRef in place (see that class's own
     *  doc) rather than appending a new entry; a no-op if nothing is
     *  tracking this msgId (e.g. a stray/duplicate DATA frame). */
    private fun onAttachmentDataReceived(msgId: String, bytes: ByteArray) {
        val ref = attachmentRefsByMsgId[msgId] ?: return
        ref.fetchedBytes = bytes
        ref.requesting = false
        refreshChatAdapters()
    }

    /** Step 3: sends a just-recorded voice note via the generic attachment
     *  protocol and appends it to this device's own scrollback immediately
     *  — unlike a received attachment, the sender already HAS the body
     *  (sendAttachment wrote it to disk before this call even returns), so
     *  there's no Download gate for your own sent message, same as how a
     *  sent text message appears immediately via appendChatMessage(fromMe=true). */
    private fun sendVoiceNoteAttachment(audioBytes: ByteArray, durationMs: Int) {
        val transport = mediaTransport ?: return
        val msgId = transport.sendAttachment(OfflineMediaTransport.AttachmentKind.VOICE, audioBytes, durationMs = durationMs) ?: return
        val meta = OfflineMediaTransport.AttachmentMeta(msgId, OfflineMediaTransport.AttachmentKind.VOICE, audioBytes.size, durationMs = durationMs)
        val ref = AttachmentRef(msgId, OfflineMediaTransport.AttachmentKind.VOICE, transport.localNodeId, meta, fetchedBytes = audioBytes)
        attachmentRefsByMsgId[msgId] = ref
        appendChatMessage(text = "Voice note", fromMe = true, attachment = ref)
    }

    /** User tapped Download on a fetch-gated attachment's placeholder row —
     *  no-op if already fetched or already in flight (button is disabled
     *  for both, this is belt-and-suspenders against a stray double-tap). */
    private fun requestAttachmentDownload(ref: AttachmentRef) {
        if (ref.fetchedBytes != null || ref.requesting) return
        ref.requesting = true
        refreshChatAdapters()
        mediaTransport?.requestAttachment(ref.msgId, ref.senderNodeId)
    }

    /** Step 4: tapping an IMAGE thumbnail opens it in an external viewer —
     *  needs a content:// Uri (FileProvider), not the raw file:// path,
     *  since targetSdk 36 throws FileUriExposedException on the latter.
     *  [OfflineMediaTransport.attachmentBodyFile] only returns non-null once
     *  the body is actually on disk (sent-by-self or already-downloaded),
     *  which the IMAGE row's own thumbnail-vs-placeholder branch already
     *  guarantees before this is ever called. */
    private fun viewAttachmentFile(ref: AttachmentRef) {
        val transport = mediaTransport ?: return
        val file = transport.attachmentBodyFile(ref.msgId) ?: return
        val uri = try {
            androidx.core.content.FileProvider.getUriForFile(this, "$packageName.attachments", file)
        } catch (e: Exception) {
            Log.w("OFFTRACE", "ATTACH: FileProvider uri failed: ${e.javaClass.simpleName}:${e.message}")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, ref.meta.mimeType ?: "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No app can open this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatVoiceNoteDuration(durationMs: Int): String {
        val totalSec = (durationMs / 1000).coerceIn(0, 99)
        return "0:%02d".format(totalSec)
    }

    private fun formatAttachmentBytes(bytes: Int): String =
        if (bytes >= 1024) "%.0fKB".format(bytes / 1024.0) else "${bytes}B"

    /** Step 3 (diagnostic follow-up): per-kind content row inside an
     *  attachment chat bubble. VOICE is fully built out here (Play/Stop
     *  once fetched); IMAGE/DOCUMENT get a generic Download-gated row for
     *  now — Steps 4/5's own job is a richer preview (a thumbnail, a
     *  proper filename+size row), hung off this SAME dispatch rather than
     *  a parallel one. LOCATION/CONTACT render their complete (inline,
     *  no-fetch) content directly — nothing THIS step ever sends either
     *  kind yet (that's Steps 6/7), but the generic plumbing already
     *  supports displaying one correctly the moment something does. */
    private fun buildAttachmentRow(ref: AttachmentRef, entry: ChatEntry, fg: Int): View {
        val density = resources.displayMetrics.density
        return when (ref.kind) {
            OfflineMediaTransport.AttachmentKind.VOICE -> LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                if (ref.fetchedBytes != null) {
                    addView(Button(this@OfflineCallActivity).apply {
                        text = if (playingChatEntryId == entry.id) "Stop" else "Play"
                        setOnClickListener { toggleAttachmentPlayback(ref, entry) }
                    })
                } else {
                    addView(Button(this@OfflineCallActivity).apply {
                        text = if (ref.requesting) "Requesting…" else "Download"
                        isEnabled = !ref.requesting
                        setOnClickListener { requestAttachmentDownload(ref) }
                    })
                }
                addView(TextView(this@OfflineCallActivity).apply {
                    text = "🎤 ${formatVoiceNoteDuration(ref.meta.durationMs ?: 0)}"
                    setTextColor(fg)
                    setPadding((8 * density).toInt(), 0, 0, 0)
                })
            }
            // Step 4: IMAGE gets its own branch (thumbnail once downloaded)
            // — split out of what used to be a single IMAGE/DOCUMENT branch;
            // DOCUMENT below is untouched, deliberately staying the generic
            // filename+size+Download row (Step 5's own explicit scope: "no
            // preview").
            OfflineMediaTransport.AttachmentKind.IMAGE -> LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val bytes = ref.fetchedBytes
                val bitmap = bytes?.let { b -> try { android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size) } catch (e: Exception) { null } }
                if (bitmap != null) {
                    addView(android.widget.ImageView(this@OfflineCallActivity).apply {
                        setImageBitmap(bitmap)
                        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        layoutParams = LinearLayout.LayoutParams((160 * density).toInt(), (160 * density).toInt())
                        setOnClickListener { viewAttachmentFile(ref) }
                    })
                } else {
                    addView(LinearLayout(this@OfflineCallActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        val label = "${ref.meta.filename ?: "Photo"}" + if (bytes == null) " (${formatAttachmentBytes(ref.meta.bodySize)})" else " (couldn't preview)"
                        addView(TextView(this@OfflineCallActivity).apply {
                            text = "📷 $label"
                            setTextColor(fg)
                        })
                        if (bytes == null) {
                            addView(Button(this@OfflineCallActivity).apply {
                                text = if (ref.requesting) "Requesting…" else "Download"
                                isEnabled = !ref.requesting
                                setPadding((8 * density).toInt(), 0, 0, 0)
                                setOnClickListener { requestAttachmentDownload(ref) }
                            })
                        }
                    })
                }
            }
            OfflineMediaTransport.AttachmentKind.DOCUMENT -> LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val label = "${ref.meta.filename ?: "Document"} (${formatAttachmentBytes(ref.meta.bodySize)})"
                addView(TextView(this@OfflineCallActivity).apply {
                    text = "📄 $label"
                    setTextColor(fg)
                })
                if (ref.fetchedBytes == null) {
                    addView(Button(this@OfflineCallActivity).apply {
                        text = if (ref.requesting) "Requesting…" else "Download"
                        isEnabled = !ref.requesting
                        setPadding((8 * density).toInt(), 0, 0, 0)
                        setOnClickListener { requestAttachmentDownload(ref) }
                    })
                }
            }
            OfflineMediaTransport.AttachmentKind.LOCATION -> TextView(this).apply {
                val lat = (ref.meta.latE7 ?: 0) / 1e7
                val lon = (ref.meta.lonE7 ?: 0) / 1e7
                text = "📍 %.5f, %.5f".format(lat, lon)
                setTextColor(fg)
            }
            OfflineMediaTransport.AttachmentKind.CONTACT -> TextView(this).apply {
                text = "👤 ${ref.meta.contactName ?: "Contact"}${ref.meta.contactPhone?.let { " · $it" } ?: ""}"
                setTextColor(fg)
            }
        }
    }

    /** Only one attachment plays at a time, identified by ChatEntry.id (not
     *  a View reference — ListView recycles/rebuilds row Views on every
     *  notifyDataSetChanged, so a row-based reference from the old
     *  SOS-overlay list design doesn't survive here). Tapping the
     *  currently-playing entry's button again stops it. VOICE only today —
     *  nothing else this app sends is audio. */
    private fun toggleAttachmentPlayback(ref: AttachmentRef, entry: ChatEntry) {
        val wasThisEntry = playingChatEntryId == entry.id
        stopVoiceNotePlayback()
        if (wasThisEntry) return
        val audio = ref.fetchedBytes ?: return
        val player = android.media.MediaPlayer()
        try {
            player.setDataSource(VoiceNoteDataSource(audio))
            player.setOnCompletionListener { stopVoiceNotePlayback() }
            player.setOnErrorListener { _, _, _ -> stopVoiceNotePlayback(); true }
            player.prepare()
            player.start()
            voiceNotePlayer = player
            playingChatEntryId = entry.id
            refreshChatAdapters()
        } catch (e: Exception) {
            Log.w("OFFTRACE", "ATTACH: playback failed: ${e.javaClass.simpleName}:${e.message}")
            try { player.release() } catch (_: Exception) {}
            Toast.makeText(this, "Couldn't play voice note", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopVoiceNotePlayback() {
        voiceNotePlayer?.let { p ->
            try { p.stop() } catch (_: Exception) {}
            try { p.release() } catch (_: Exception) {}
        }
        voiceNotePlayer = null
        if (playingChatEntryId != null) {
            playingChatEntryId = null
            refreshChatAdapters()
        }
    }

    /** chatMessages/chatAdapter are shared between the live in-call chat
     *  panel (chatListView) and the Messages thread view
     *  (messagesChatListView) — see appendChatMessage's own doc for the
     *  precedent this mirrors exactly. */
    private fun refreshChatAdapters() {
        if (::chatAdapter.isInitialized) chatAdapter.notifyDataSetChanged()
        if (::messagesChatAdapter.isInitialized) messagesChatAdapter.notifyDataSetChanged()
    }

    /** Plays straight from an in-memory attachment body — no temp file, no
     *  cleanup-on-disk to forget (the body is ALSO on disk via
     *  AttachmentStore, independently — this just avoids a redundant read
     *  for playback). [close] is a no-op: the backing ByteArray is owned by
     *  the AttachmentRef this came from, not this class. */
    private class VoiceNoteDataSource(private val data: ByteArray) : android.media.MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val length = minOf(size.toLong(), data.size - position).toInt()
            System.arraycopy(data, position.toInt(), buffer, offset, length)
            return length
        }
        override fun getSize(): Long = data.size.toLong()
        override fun close() {}
    }

    // ── PART 1 (batch A): single scroll, overlay-based Groups/Settings ──────

    /** PART 1.2: true scroll-triggered laziness — [build] runs the first
     *  time [scrollBody]'s ScrollView parent actually scrolls this
     *  section's placeholder into the viewport (checked via
     *  View.getLocalVisibleRect, the same "is any part of this view
     *  currently on screen" primitive the platform itself exposes for
     *  this), never eagerly at construction time. Also checked once,
     *  post-layout, to cover a section that's already on screen without
     *  any scrolling (a short mesh session, a tall device). */
    private fun addLazySection(container: LinearLayout, build: () -> View) {
        val placeholder = FrameLayout(this)
        container.addView(placeholder, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0))
        var built = false
        val rect = android.graphics.Rect()
        fun maybeBuild() {
            if (built) return
            if (!placeholder.getLocalVisibleRect(rect)) return
            built = true
            val content = build()
            val index = container.indexOfChild(placeholder)
            container.removeViewAt(index)
            container.addView(content, index, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        placeholder.viewTreeObserver.addOnScrollChangedListener { maybeBuild() }
        placeholder.post { maybeBuild() }
    }

    /** PART 1: replaces switchTab's "is Nearby the active tab" gate — Nearby
     *  has no more sibling tabs to switch away from (Messages/Calls are
     *  always-present sections; Groups/Settings are overlays that cover
     *  everything anyway, so root's own content doesn't need to hide under
     *  them). What genuinely still competes for root's weighted space is
     *  callScreen/groupCallScreen (full-bleed call UI) and messagesThreadView
     *  (open chat thread) — see the class-level doc on [mainScroll]. Same
     *  [homeTabVisible] field, same downstream consumers
     *  (updateRadarSweeping/shouldRefreshPartyData) as before — only what
     *  drives it changed. Call after toggling any covering state. */
    private fun updateForegroundState() {
        val callActive = (::callScreen.isInitialized && callScreen.visibility == View.VISIBLE) ||
            (::groupCallScreen.isInitialized && groupCallScreen.visibility == View.VISIBLE)
        val threadOpen = ::messagesThreadView.isInitialized && messagesThreadView.visibility == View.VISIBLE
        val overlayOpen = (::groupsOverlay.isInitialized && groupsOverlay.visibility == View.VISIBLE) ||
            (::offlineSettingsOverlay.isInitialized && offlineSettingsOverlay.visibility == View.VISIBLE)
        homeTabVisible = !(callActive || threadOpen || overlayOpen)
        updateRadarSweeping()

        val chrome = if (callActive || threadOpen) View.GONE else View.VISIBLE
        if (::nearbyHeader.isInitialized) nearbyHeader.visibility = chrome
        if (::startGroupScanRow.isInitialized) startGroupScanRow.visibility = chrome
        if (::mainScroll.isInitialized) mainScroll.visibility = chrome
    }

    /** PART 1.3: Groups is no longer a section — header tap opens its
     *  EXISTING, unrewritten content (buildGroupsScreen) as a full-screen
     *  overlay via the existing buildFullScreenOverlay(). Built once, lazily,
     *  on first open. */
    private fun openGroupsOverlay() {
        if (!::groupsOverlay.isInitialized) {
            groupsOverlay = buildFullScreenOverlay(
                titleText = "Groups",
                titleBg = TopoPalette.bgRaised(currentTopoMode()),
                onCloseSilenceAll = null
            ) { body -> body.addView(lazyGroupsScreen()) }
            overlayRoot.addView(
                groupsOverlay,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            )
        }
        refreshGroupsScreen()
        groupsOverlay.visibility = View.VISIBLE
        updateForegroundState()
    }

    private fun closeGroupsOverlay() {
        if (::groupsOverlay.isInitialized) groupsOverlay.visibility = View.GONE
        updateForegroundState()
    }

    /** PART 1.4/5.2 (batch A carrying the existing entry point forward):
     *  Settings is reached via the native gear (AppShell top bar →
     *  SettingsActivity → "Offline mesh settings" row →
     *  EXTRA_OPEN_SETTINGS_TAB, unchanged) — this is where that extra now
     *  lands. buildSettingsScreen() already returns its OWN ScrollView (it
     *  is self-scrolling), so unlike Groups this does NOT go through
     *  buildFullScreenOverlay (which would nest a second ScrollView inside
     *  its own — the same "cannot re-host without a layout rewrite" problem
     *  as callScreen/messagesThreadView, see PART 1.7). A small dedicated
     *  title row mirrors buildFullScreenOverlay's look, then hosts the
     *  ScrollView directly with weight=1f (bounded, no nested-scroll). */
    private fun openOfflineSettingsOverlay() {
        if (!::offlineSettingsOverlay.isInitialized) {
            offlineSettingsOverlay = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.argb(255, 20, 20, 20))
                visibility = View.GONE
            }
            val titleRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(TopoPalette.bgRaised(currentTopoMode()))
                setPadding(24, 48, 24, 24)
            }
            titleRow.addView(TextView(this).apply {
                text = "Offline mesh settings"
                textSize = 22f
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            titleRow.addView(Button(this).apply {
                text = "Close"
                setOnClickListener { closeOfflineSettingsOverlay() }
            })
            offlineSettingsOverlay.addView(titleRow)
            offlineSettingsOverlay.addView(
                lazySettingsScreen(),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f }
            )
            overlayRoot.addView(
                offlineSettingsOverlay,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            )
        }
        offlineSettingsOverlay.visibility = View.VISIBLE
        updateForegroundState()
    }

    private fun closeOfflineSettingsOverlay() {
        if (::offlineSettingsOverlay.isInitialized) offlineSettingsOverlay.visibility = View.GONE
        updateForegroundState()
    }

    // PART 1.5: searchScreen/groupScreen mutual exclusion is driven entirely
    // by direct visibility sets at each real state-change site (group
    // joined, group left, group call ended — see e.g. exitGroupCallScreen),
    // exactly as it already was even before batch A: applyHomeVisibility's
    // only actual caller was switchTab's own "restore whichever was visible
    // before you tabbed away," a concern that no longer exists now that
    // Nearby has no sibling tab to switch away from. Removing that
    // now-callerless wrapper is not a behaviour change — every real
    // search<->group transition below is untouched.

    private fun groupsScreenOrNull(): LinearLayout? = groupsScreenView
    private fun messagesScreenOrNull(): LinearLayout? = messagesScreenView
    private fun callsScreenOrNull(): LinearLayout? = callsScreenView
    private fun settingsScreenOrNull(): android.widget.ScrollView? = settingsScreenView

    private fun lazyGroupsScreen(): LinearLayout {
        groupsScreenView?.let { return it }
        val v = buildGroupsScreen()
        groupsScreenView = v
        return v
    }

    private fun lazyMessagesScreen(): LinearLayout {
        messagesScreenView?.let { return it }
        val v = buildMessagesScreen()
        messagesScreenView = v
        return v
    }

    private fun lazyCallsScreen(): LinearLayout {
        callsScreenView?.let { return it }
        val v = buildCallsScreen()
        callsScreenView = v
        return v
    }

    private fun lazySettingsScreen(): android.widget.ScrollView {
        settingsScreenView?.let { return it }
        val v = buildSettingsScreen()
        settingsScreenView = v
        return v
    }

    // ── PHASE 2: shared empty-state builder (GLOBAL UI RULES) ────────────────
    private fun buildEmptyState(icon: String, boldLine: String, instruction: String): LinearLayout {
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((32 * density).toInt(), (96 * density).toInt(), (32 * density).toInt(), (32 * density).toInt())
            addView(TextView(this@OfflineCallActivity).apply { text = icon; textSize = 48f; gravity = Gravity.CENTER })
            addView(TextView(this@OfflineCallActivity).apply {
                text = boldLine
                textSize = 20f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(TopoPalette.fg(nightModeEnabled))
                gravity = Gravity.CENTER
                setPadding(0, (16 * density).toInt(), 0, (4 * density).toInt())
            })
            addView(TextView(this@OfflineCallActivity).apply {
                text = instruction
                textSize = 16f
                setTextColor(TopoPalette.mutedFg(nightModeEnabled))
                gravity = Gravity.CENTER
            })
        }
    }

    // ── PHASE 5.2 shell (built now, content wired in Phase 5): Groups tab ───
    private lateinit var groupsListBody: LinearLayout

    private fun buildGroupsScreen(): LinearLayout {
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(TopoPalette.bgBase(currentTopoMode())) // TOPO PART B3
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), 0)
            val actionRow = LinearLayout(this@OfflineCallActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            actionRow.addView(Button(this@OfflineCallActivity).apply {
                text = "Create new"
                setOnClickListener { showStartGroupCallDialog() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            actionRow.addView(Button(this@OfflineCallActivity).apply {
                text = "Join via QR"
                setOnClickListener { showScanQrDialog() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(actionRow)
            groupsListBody = LinearLayout(this@OfflineCallActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(groupsListBody)
        }
    }

    /** PHASE 5.2: this app's mesh model is single-group (one Wi-Fi Direct
     *  group per device at a time — see WifiDirectManager's class doc), so
     *  there is at most ONE real entry to ever show here: the group this
     *  device is currently in, with its real roster-derived member count
     *  (partyView()'s IN_ROSTER count — the same single source Phase 1.2
     *  established, never a second count). "No groups" is the honest empty
     *  state otherwise — never a fabricated list. Wired to the EXISTING
     *  group path (showCallModeDialog/showMidCallInviteDialog/
     *  leaveGroup) — no parallel group implementation. */
    private fun refreshGroupsScreen() {
        if (!::groupsListBody.isInitialized) return
        groupsListBody.removeAllViews()
        val connected = partyView().count { it.origin == PartyOrigin.IN_ROSTER }
        if (connected == 0) {
            groupsListBody.addView(buildEmptyState("👥", "No groups", "Create a new group or join one via QR to get started."))
            return
        }
        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (56 * density).toInt()
            setBackgroundColor(TopoPalette.cardBg(nightModeEnabled))
            isClickable = true
            setOnClickListener { closeGroupsOverlay() } // PART 1.3 (batch A): Groups is an overlay now — dismiss it, revealing Nearby (the roster) underneath, same destination "switchTab(Tab.NEARBY)" used to land on
        }
        row.addView(TextView(this).apply {
            text = "Current group ($connected member${if (connected == 1) "" else "s"})"
            textSize = 18f
            setTextColor(TopoPalette.fg(nightModeEnabled))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        groupsListBody.addView(row)
    }

    // ── PHASE 5.3 shell (built now, content wired in Phase 5): History tab ──
    // TOPO PHASE 2.1: the old History tab (a single stub with a Chats/Call
    // history toggle, never wired to real data) is retired — its two
    // placeholder states split 1:1 onto these two new tabs. Phase 3 builds
    // Messages out for real (threads); Phase 4 builds Calls out for real
    // (active call state + history).
    private lateinit var messagesListBody: LinearLayout
    // TOPO PART B1: everything below is Messages' OWN view tree — none of
    // these are callScreen's videoFrame/hangupButton/callTimerText/
    // modeInfoBar/bottomPanel/chatListView/chatInput. Only the underlying
    // chatMessages DATA list is shared (see appendChatMessage's doc) —
    // no View object is shared with the call screen.
    private lateinit var messagesThreadView: LinearLayout
    private lateinit var messagesThreadNameText: TextView
    private lateinit var messagesChatListView: ListView
    private lateinit var messagesChatAdapter: ChatAdapter
    private lateinit var messagesComposerInput: EditText

    /** TOPO PHASE 3.1 / PART B1: a thread LIST or an OPEN THREAD, never both
     *  (see [openMessageThread]/[closeOpenMessageThread]). One row per
     *  current roster member + one "Group" row. Tapping a row calls
     *  [openMessageThread] — never startDirectCall/openGroupChat, never
     *  touches callScreen at all. Preview/unread state comes from
     *  [threadPreviews], populated additively (see [recordThreadPreview]).
     *  PART 1.2 (batch A): this returns ONLY the thread-list part now —
     *  bounded, WRAP_CONTENT rows, safe to re-host as a scroll section (see
     *  addLazySection). [messagesThreadView] itself (a weight=1f ListView +
     *  composer — needs a full, bounded viewport, not a scroll section, see
     *  PART 1.7) is built separately in buildUi() and lives as a root-level
     *  full-bleed sibling instead, exactly like callScreen. */
    private fun buildMessagesScreen(): LinearLayout {
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), 0)
            setBackgroundColor(TopoPalette.bgBase(currentTopoMode()))
            // Step 2 (diagnostic follow-up): voice notes MOVED into
            // messagesThreadView itself (buildMessagesThreadView) — this is
            // the thread LIST, which openMessageThread() never shows.
            messagesListBody = LinearLayout(this@OfflineCallActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(messagesListBody)
        }
    }

    /** Back row (name + back affordance) + own ListView/adapter + emoji
     *  strip (B8: 40dp, horizontally scrollable, directly above the
     *  composer) + composer row. GONE until [openMessageThread] shows it. */
    private fun buildMessagesThreadView(): LinearLayout {
        val density = resources.displayMetrics.density
        val mode = currentTopoMode()
        messagesThreadView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val backRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (56 * density).toInt()
        }
        backRow.addView(Button(this).apply {
            text = "← Back"
            setOnClickListener { closeOpenMessageThread() }
        })
        messagesThreadNameText = TextView(this).apply {
            textSize = 18f
            setTextColor(TopoPalette.textPrimary(mode))
            setPadding((12 * density).toInt(), 0, 0, 0)
        }
        backRow.addView(messagesThreadNameText)
        messagesThreadView.addView(backRow)

        messagesChatListView = ListView(this).apply {
            divider = null
            dividerHeight = 0
        }
        messagesChatAdapter = ChatAdapter()
        messagesChatListView.adapter = messagesChatAdapter
        messagesThreadView.addView(messagesChatListView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // B8: 40dp reactions, one horizontally-scrollable row, directly above the composer.
        val emojiScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val emojiRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        QUICK_REACTION_EMOJI.forEach { emoji ->
            emojiRow.addView(Button(this@OfflineCallActivity).apply {
                text = emoji
                textSize = 18f
                val size = (40 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(4, 4, 4, 4) }
                setOnClickListener { if (sendChatText(emoji)) Unit }
            })
        }
        emojiScroll.addView(emojiRow)
        messagesThreadView.addView(emojiScroll)

        messagesComposerInput = EditText(this).apply {
            hint = "Message"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setBackgroundColor(TopoPalette.bgRaised(mode)) // TOPO B6: never light grey on dark
            setTextColor(TopoPalette.textPrimary(mode))
            setHintTextColor(TopoPalette.textMuted(mode))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    if (sendChatText(text?.toString()?.trim().orEmpty())) setText("")
                    true
                } else false
            }
        }
        val sendBtn = Button(this).apply {
            text = "Send"
            setOnClickListener {
                if (sendChatText(messagesComposerInput.text?.toString()?.trim().orEmpty())) messagesComposerInput.setText("")
            }
        }
        // Step 3 (diagnostic follow-up): hold-to-talk, next to Send — see
        // this function's own doc for why it lives here, not a separate
        // section. Visibility toggled by openMessageThread (GONE by default
        // here, before any thread is open): sendAttachment is broadcast-only
        // for every kind today (no 1:1 targeting), so this only makes sense
        // on the Group thread, not a 1:1 one.
        voiceNoteHoldButton = Button(this).apply {
            text = "🎤"
            visibility = View.GONE
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        val started = voiceNoteRecorder.start { result ->
                            runOnUiThread {
                                voiceNoteHoldButton.text = "🎤"
                                sendVoiceNoteAttachment(result.audioBytes, result.durationMs)
                            }
                        }
                        voiceNoteHoldButton.text = if (started) "●" else "🎤"
                        if (!started) {
                            Toast.makeText(this@OfflineCallActivity, "Couldn't start recording", Toast.LENGTH_SHORT).show()
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        voiceNoteHoldButton.text = "🎤"
                        voiceNoteRecorder.stop()?.let { result -> sendVoiceNoteAttachment(result.audioBytes, result.durationMs) }
                        true
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        voiceNoteHoldButton.text = "🎤"
                        voiceNoteRecorder.cancel()
                        true
                    }
                    else -> false
                }
            }
        }
        // Step 4: scattered-for-now — see imageAttachmentButton's own doc.
        imageAttachmentButton = Button(this).apply {
            text = "📷"
            visibility = View.GONE
            setOnClickListener { startPickImageAttachment() }
        }
        // Step 5: same pattern as imageAttachmentButton.
        documentAttachmentButton = Button(this).apply {
            text = "📄"
            visibility = View.GONE
            setOnClickListener { startPickDocumentAttachment() }
        }
        // Step 6: same pattern, one-shot send (no picker — see shareCurrentLocation).
        locationAttachmentButton = Button(this).apply {
            text = "📍"
            visibility = View.GONE
            setOnClickListener { shareCurrentLocation() }
        }
        // Step 7: same pattern, opens the contact picker dialog.
        contactAttachmentButton = Button(this).apply {
            text = "👤"
            visibility = View.GONE
            setOnClickListener { shareContact() }
        }
        val composerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            addView(messagesComposerInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(imageAttachmentButton)
            addView(documentAttachmentButton)
            addView(locationAttachmentButton)
            addView(contactAttachmentButton)
            addView(voiceNoteHoldButton)
            addView(sendBtn)
        }
        messagesThreadView.addView(composerRow)
        return messagesThreadView
    }

    private fun refreshMessagesScreen() {
        if (!::messagesListBody.isInitialized) return
        messagesListBody.removeAllViews()
        val localId = mediaTransport?.localNodeId
        val threads = mutableListOf<Pair<Long, String>>()
        if (roster.any { it.nodeId != localId }) threads.add(MeshFrame.BROADCAST_ID to "Group")
        roster.forEach { m -> if (m.nodeId != localId) threads.add(m.nodeId to m.name) }
        if (threads.isEmpty()) {
            messagesListBody.addView(buildEmptyState("💬", "No messages yet", "Join or start a group to message someone."))
            return
        }
        threads.forEach { (key, name) -> messagesListBody.addView(buildThreadRow(key, name)) }
    }

    private fun formatThreadTime(atMs: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(atMs))

    private fun buildThreadRow(threadKey: Long, name: String): View {
        val density = resources.displayMetrics.density
        val mode = currentTopoMode()
        val preview = threadPreviews[threadKey]
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt())
            minimumHeight = (56 * density).toInt() // TOPO 1.3: min touch target
            setBackgroundColor(TopoPalette.bgSurface(mode))
            isClickable = true
            // TOPO PART B1: openMessageThread — Messages' own thread view,
            // never startDirectCall/openGroupChat (those show callScreen).
            setOnClickListener {
                if (threadKey == MeshFrame.BROADCAST_ID) {
                    openMessageThread(threadKey, name, member = null)
                } else {
                    roster.firstOrNull { it.nodeId == threadKey }?.let { openMessageThread(threadKey, name, it) }
                }
            }
        }
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        info.addView(TextView(this).apply { text = name; textSize = 18f; setTextColor(TopoPalette.textPrimary(mode)) })
        info.addView(TextView(this).apply {
            text = preview?.let { (if (it.lastFromMe) "You: " else "") + it.lastText } ?: "No messages yet"
            textSize = 14f
            setTextColor(TopoPalette.textSecondary(mode))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(info)
        if (preview != null) {
            row.addView(TextView(this).apply {
                text = formatThreadTime(preview.lastAtMs)
                textSize = 12f
                setTextColor(TopoPalette.textMuted(mode))
                setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
            })
        }
        val unread = preview?.unread ?: 0
        if (unread > 0) {
            row.addView(TextView(this).apply {
                text = unread.toString()
                textSize = 12f
                setTextColor(TopoPalette.onAccent(mode))
                setBackgroundColor(TopoPalette.accent(mode))
                setPadding((10 * density).toInt(), (4 * density).toInt(), (10 * density).toInt(), (4 * density).toInt())
            })
        }
        return row
    }

    private lateinit var callsListBody: LinearLayout

    /** TOPO PHASE 4.2: active call state at top (when one is live) + one row
     *  per roster member below, each with a phone and a camera button.
     *  Both buttons reach the EXACT SAME code today's roster-tap flow
     *  uses — showCallModeDialog(member) is the negotiated (video/audio/
     *  message) path; the phone/camera buttons here skip the dialog and
     *  call startDirectCall(member, mode) directly with the mode already
     *  decided by which icon was tapped — see OUTPUT proof #5 for both
     *  call sites. No new call path, no audio/video pipeline touched. */
    private fun buildCallsScreen(): LinearLayout {
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(TopoPalette.bgBase(currentTopoMode())) // TOPO PART B3
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), 0)
            callsListBody = LinearLayout(this@OfflineCallActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(callsListBody)
        }
    }

    private fun refreshCallsScreen() {
        if (!::callsListBody.isInitialized) return
        callsListBody.removeAllViews()
        val mode = currentTopoMode()
        val localId = mediaTransport?.localNodeId
        val callLive = ::callScreen.isInitialized && callScreen.visibility == View.VISIBLE && !isGroupChatScreen
        if (callLive) {
            callsListBody.addView(TextView(this).apply {
                text = "On a call with ${connectedPeerName.ifBlank { "peer" }}"
                textSize = 16f
                setTextColor(TopoPalette.ok(mode))
                setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
            })
        }
        val members = roster.filter { it.nodeId != localId }
        if (members.isEmpty()) {
            callsListBody.addView(buildEmptyState("📞", "No one to call yet", "Join or start a group, then call someone from here."))
            return
        }
        members.forEach { member -> callsListBody.addView(buildCallsPeerRow(member)) }
    }

    private fun buildCallsPeerRow(member: RoutingTable.Member): View {
        val density = resources.displayMetrics.density
        val mode = currentTopoMode()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (56 * density).toInt() // TOPO 1.3: min touch target
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            setBackgroundColor(TopoPalette.bgSurface(mode))
        }
        row.addView(TextView(this).apply {
            text = member.name
            textSize = 18f
            setTextColor(TopoPalette.textPrimary(mode))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        // OUTPUT proof #5, call site 1: phone icon -> startDirectCall(member, AUDIO) directly.
        row.addView(Button(this).apply {
            text = "📞"
            textSize = 20f
            minWidth = (56 * density).toInt()
            setOnClickListener { startDirectCall(member, OfflineMediaTransport.CallMode.AUDIO) }
        })
        // OUTPUT proof #5, call site 2: camera icon -> startDirectCall(member, VIDEO) directly.
        row.addView(Button(this).apply {
            text = "🎥"
            textSize = 20f
            minWidth = (56 * density).toInt()
            setOnClickListener { startDirectCall(member, OfflineMediaTransport.CallMode.VIDEO) }
        })
        return row
    }

    // ── PHASE 3 item 8 / 2.2 "Tell a friend": QR invite — NEW, shared by the
    // Settings screen's "Show QR code" row and Home's Invite/QR entry point.
    // Wi-Fi Direct itself has no QR-based join primitive — a QR code cannot
    // carry a dynamically-assigned WFD MAC address or perform WFD's own
    // discovery/negotiation. So the QR here carries IDENTITY (nodeId,
    // pubkey, display name) for out-of-band verification; "joins through the
    // EXISTING group-join path" means: once the scanned nodeId is also seen
    // via the normal WFD/BLE discovery this device already runs continuously
    // (startNearbyRefreshLoop), scanning simply calls the same sendInvite()
    // every other row's Invite button calls — never a second join path.

    // FIX 3: this device's own identity nodeId, available synchronously at
    // process start — OfflineIdentity.nodeId(context) loads-or-creates the
    // Keystore-backed keypair on first call, cached thereafter (see
    // OfflineIdentity.kt), completely independent of mediaTransport (which
    // stays null for the entire pre-connection life of the app — see
    // leaveGroup/onConnectionChangedInternal for where it's assigned). This
    // is the EXACT conversion OfflineMediaTransport.localNodeId's own init
    // block performs (OfflineMediaTransport.kt:1033-1038); every DISPLAY
    // site below reads it from here now, never from mediaTransport.
    private fun localNodeId(): Long = nodeIdBytesToLong(OfflineIdentity.nodeId(applicationContext))

    private var localShortIdLogged = false
    private fun localShortId(): String {
        val id = localShortIdFor(OfflineIdentity.nodeId(applicationContext))
        if (!localShortIdLogged) {
            localShortIdLogged = true
            Log.d("OFFTRACE", "ID: localShortId=$id transportNull=${mediaTransport == null}")
        }
        return id
    }

    /** The general-purpose, group-independent "here's who I am" QR — used
     *  ONLY by [showInviteQrDialog] (Settings/Tell-a-friend). Never reads
     *  hostedGroupNetworkName/hostedGroupPassphrase — see
     *  [encodePairingQrPayload]'s own doc for why this shape structurally
     *  cannot carry (real or accidentally-null) group credentials at all. */
    private fun pairingQrPayload(): String {
        val nodeIdHex = MeshFrame.hex(localNodeId())
        val pubkeyB64 = android.util.Base64.encodeToString(OfflineIdentity.publicKeyBytes(applicationContext), android.util.Base64.NO_WRAP)
        val name = OfflineIdentity.displayName(applicationContext)
        return encodePairingQrPayload(nodeIdHex, pubkeyB64, name)
    }

    private fun showInviteQrDialog() {
        val hints = mapOf(
            com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M
        )
        val bitmap = try {
            com.journeyapps.barcodescanner.BarcodeEncoder().encodeBitmap(pairingQrPayload(), com.google.zxing.BarcodeFormat.QR_CODE, 600, 600, hints)
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't generate QR code: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }
        val image = android.widget.ImageView(this).apply { setImageBitmap(bitmap) }
        AlertDialog.Builder(this)
            .setTitle("Scan to join")
            .setView(image)
            .setPositiveButton("Close", null)
            .show()
    }

    // ── PART "HASSLE-FREE JOIN" 1 — HOST AND SHOW ───────────────────────────
    // PART "WHY THE QR JOIN FAILS" C1: ONE ordering, not two racing
    // callbacks — host()'s onOutcome(true) chains DIRECTLY into
    // fetchGroupInfoThenShowHostQr()'s OWN requestGroupInfo() call, and
    // ONLY that callback may ever call showHostQrScreen, passing the real
    // networkName/passphrase as ARGUMENTS — never by reading
    // hostedGroupNetworkName/hostedGroupPassphrase (the SEPARATE mutable
    // fields onConnectionChangedInternal's own, independent
    // requestGroupInfo() call still populates for DNS-SD/BLE's unrelated
    // purposes — see that call site's own doc; this path never reads
    // them). showHostQrScreen's own parameters are non-nullable Strings,
    // so it is not merely unlikely but IMPOSSIBLE to reach it with missing
    // credentials — the compiler enforces it, not a runtime check.

    /** 1.1: the ONE button that replaces the old invite-a-specific-peer
     *  flow — createGroup runs immediately with credentials derived from
     *  this device's OWN nodeId (see WifiDirectManager.deriveHostNetworkName/
     *  deriveHostPassphrase). No peer is selected, resolved, or even
     *  visible yet; nothing here calls discovery of any kind. */
    private fun startHosting() {
        // PART "HASSLE-FREE JOIN" item 4: the OLD "Search nearby" button
        // (now hidden — see its own field doc) was the ONLY UI path that
        // ever triggered onSearchClicked()'s permission-rationale-then-
        // request ceremony (Location/Nearby/Mic/Camera/Bluetooth) — hiding
        // it must not silently orphan that ceremony, since host()/BLE both
        // degrade quietly on a missing permission rather than prompting for
        // it themselves. Same "tap again once granted" pattern the old
        // button already had (permission grant is async; there's no
        // existing callback wiring to auto-resume this specific call).
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            onSearchClicked()
            return
        }
        val myNodeId = localNodeId()
        val networkName = WifiDirectManager.deriveHostNetworkName(myNodeId)
        val passphrase = WifiDirectManager.deriveHostPassphrase(myNodeId)
        wifiDirect.host("start-group", networkName, passphrase) { ok ->
            runOnUiThread {
                if (ok) {
                    // 1.2/C1: "the moment the group forms" — onOutcome
                    // fires true only on a real groupFormed observation
                    // (see WifiDirectManager.host's own doc), and THIS
                    // call — never a second, independent one — is what
                    // fetches the real credentials before anything is drawn.
                    fetchGroupInfoThenShowHostQr()
                } else {
                    Toast.makeText(this, "Couldn't start a group — try again", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private var hostQrDialog: android.app.Dialog? = null
    private var hostQrRoot: LinearLayout? = null
    private var hostQrPollRunnable: Runnable? = null
    private var qrGenerationTimeoutRunnable: Runnable? = null
    private var qrGenerationDeadlineHit = false

    /** C2: 5s deadline armed BEFORE the async call, so it fires even if
     *  requestGroupInfo's callback never returns at all — a QR that
     *  cannot work must never be left rendering indefinitely, let alone
     *  appear on screen. */
    private fun fetchGroupInfoThenShowHostQr() {
        showHostQrLoadingDialog()
        qrGenerationDeadlineHit = false
        val timeout = Runnable {
            if (qrGenerationDeadlineHit) return@Runnable
            qrGenerationDeadlineHit = true
            Log.w("OFFTRACE", "QR: gen ABORTED reason=timeout_5s groupFormed=$currentGroupFormed")
            showHostQrAbortedState()
        }
        qrGenerationTimeoutRunnable = timeout
        nearbyRefreshHandler.postDelayed(timeout, 5_000L)
        wifiDirect.requestGroupInfo { group ->
            runOnUiThread {
                if (qrGenerationDeadlineHit) return@runOnUiThread // timeout already fired and handled this
                qrGenerationDeadlineHit = true
                qrGenerationTimeoutRunnable?.let { nearbyRefreshHandler.removeCallbacks(it) }
                qrGenerationTimeoutRunnable = null
                val networkName = group?.networkName
                val passphrase = group?.passphrase
                if (networkName != null && passphrase != null && hasValidHostCredentials(networkName, passphrase)) {
                    showHostQrScreen(networkName, passphrase)
                } else {
                    Log.w(
                        "OFFTRACE",
                        "QR: gen ABORTED reason=${if (group == null) "no_group_info" else "null_or_blank_credentials"} " +
                            "groupFormed=$currentGroupFormed"
                    )
                    showHostQrAbortedState()
                }
            }
        }
    }

    private fun showHostQrLoadingDialog() {
        hostQrDialog?.dismiss()
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setCancelable(true)
        val density = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding((16 * density).toInt(), (32 * density).toInt(), (16 * density).toInt(), (24 * density).toInt())
        }
        root.addView(TextView(this).apply {
            text = "Setting up your group…"
            textSize = 18f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(0, (64 * density).toInt(), 0, 0)
        })
        dialog.setContentView(root)
        dialog.setOnDismissListener {
            hostQrPollRunnable?.let { nearbyRefreshHandler.removeCallbacks(it) }
            hostQrPollRunnable = null
            qrGenerationTimeoutRunnable?.let { nearbyRefreshHandler.removeCallbacks(it) }
            qrGenerationTimeoutRunnable = null
            hostQrDialog = null
            hostQrRoot = null
        }
        // C5: keep the screen on while this is up — a QR nobody can scan
        // because the screen locked mid-hosting is the same failure mode
        // as a stale one.
        dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hostQrDialog = dialog
        hostQrRoot = root
        dialog.show()
    }

    /** C2: "A QR that cannot work must never appear on screen" — shown
     *  in place of the loading state (never a fresh dialog popping over
     *  it), offering only Retry (re-runs the exact same C1 chain) or
     *  Cancel. */
    private fun showHostQrAbortedState() {
        val root = hostQrRoot ?: return
        root.removeAllViews()
        val density = resources.displayMetrics.density
        root.addView(TextView(this).apply {
            text = "Couldn't read group details — tap to retry"
            textSize = 17f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(0, (64 * density).toInt(), 0, (24 * density).toInt())
        })
        root.addView(Button(this).apply {
            text = "Retry"
            setOnClickListener { fetchGroupInfoThenShowHostQr() }
        })
        root.addView(Button(this).apply {
            text = "Cancel"
            setOnClickListener { hostQrDialog?.dismiss() }
        })
    }

    /** 1.2/1.4/C5: full-screen, high-contrast, brightness forced to
     *  maximum, largest square that fits, a >=4-module white quiet zone,
     *  screen kept on. [networkName]/[passphrase] are NON-NULLABLE — see
     *  this section's own C1 doc for why that is a real, compiler-enforced
     *  guarantee, not just a convention. Stays up — showing WHO HAS
     *  JOINED, live, polled straight from the group's own client list —
     *  until the operator taps Done, so several people can join in
     *  sequence off the one code. */
    private fun showHostQrScreen(networkName: String, passphrase: String) {
        val root = hostQrRoot ?: run {
            // Defensive only — every real call site goes through
            // showHostQrLoadingDialog() first (fetchGroupInfoThenShowHostQr).
            showHostQrLoadingDialog()
            hostQrRoot!!
        }
        root.removeAllViews()
        val density = resources.displayMetrics.density
        root.addView(TextView(this).apply {
            text = "Scan to join"
            textSize = 22f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        })
        // C5: a quiet zone of >=4 modules — zxing's own MARGIN hint is in
        // MODULES, not pixels, so "4" here IS "4 modules" directly.
        val hints = mapOf(
            com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M,
            com.google.zxing.EncodeHintType.MARGIN to 4
        )
        // C5: the largest square that fits — full display width, or full
        // remaining height under the title/joined-label/button, whichever
        // is smaller.
        val qrSize = minOf(
            resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels / 2
        ) - (32 * density).toInt()
        val nodeIdHex = MeshFrame.hex(localNodeId())
        val pubkeyB64 = android.util.Base64.encodeToString(OfflineIdentity.publicKeyBytes(applicationContext), android.util.Base64.NO_WRAP)
        val name = OfflineIdentity.displayName(applicationContext)
        val content = encodeHostingQrPayload(nodeIdHex, pubkeyB64, name, nodeIdHex, networkName, passphrase)
        Log.d("OFFTRACE", "QR: generated payload=$content")
        val qrVersionForLog = try {
            com.google.zxing.qrcode.encoder.Encoder.encode(content, com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M, java.util.Hashtable(hints)).version.versionNumber
        } catch (e: Exception) {
            -1
        }
        // B1/C1: ss/pw logged from the SAME non-null arguments the payload
        // was just built from — never from the separate mutable fields —
        // so this line is now structurally incapable of showing a mismatch
        // between what was logged and what was actually encoded.
        Log.d(
            "OFFTRACE",
            "QR: gen ss=$networkName pwLen=${passphrase.length} node=$nodeIdHex " +
                "groupFormed=$currentGroupFormed len=${content.toByteArray(Charsets.UTF_8).size} ver=$qrVersionForLog"
        )
        val bitmap = try {
            com.journeyapps.barcodescanner.BarcodeEncoder().encodeBitmap(content, com.google.zxing.BarcodeFormat.QR_CODE, qrSize, qrSize, hints)
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't generate QR code: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }
        root.addView(android.widget.ImageView(this).apply { setImageBitmap(bitmap) }, LinearLayout.LayoutParams(qrSize, qrSize).apply {
            topMargin = (24 * density).toInt()
        })
        val joinedLabel = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, (24 * density).toInt(), 0, 0)
            text = "Waiting for people to join…"
        }
        root.addView(joinedLabel)
        root.addView(Button(this).apply {
            text = "Done"
            setOnClickListener { hostQrDialog?.dismiss() }
        })

        // C5: max brightness while the QR is up, restored on dismiss —
        // moved here (from the loading state) so it's active for exactly
        // the window a scannable code is actually on screen.
        val dialog = hostQrDialog
        dialog?.window?.let { w ->
            w.attributes = w.attributes.apply { screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
        }
        dialog?.setOnDismissListener {
            hostQrPollRunnable?.let { nearbyRefreshHandler.removeCallbacks(it) }
            hostQrPollRunnable = null
            qrGenerationTimeoutRunnable?.let { nearbyRefreshHandler.removeCallbacks(it) }
            qrGenerationTimeoutRunnable = null
            dialog.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } }
            hostQrDialog = null
            hostQrRoot = null
        }

        // 1.4: "who has joined, live, from the group's client list."
        val poll = object : Runnable {
            override fun run() {
                if (hostQrDialog !== dialog) return // dismissed — stop polling
                wifiDirect.requestGroupInfo { group ->
                    if (hostQrDialog !== dialog) return@requestGroupInfo
                    val names = group?.clientList?.map { it.deviceName?.ifBlank { it.deviceAddress } ?: it.deviceAddress } ?: emptyList()
                    runOnUiThread {
                        joinedLabel.text = if (names.isEmpty()) "Waiting for people to join…" else "${names.size} joined: ${names.joinToString(", ")}"
                    }
                }
                nearbyRefreshHandler.postDelayed(this, 2_000L)
            }
        }
        hostQrPollRunnable = poll
        nearbyRefreshHandler.post(poll)
    }

    /** PART "WHY THE QR JOIN FAILS" B2-B5: launches [PairingScanActivity]
     *  (a continuous-decode scanner) instead of the old single-shot
     *  IntentIntegrator/PortraitCaptureActivity flow — see that class's own
     *  doc for exactly why (IntentIntegrator's public API has no
     *  visibility into "the camera is running but decoding nothing," which
     *  B2 needs). Net behavior unchanged: still ends by handing a raw
     *  decoded string to [handleScannedQr] via [onActivityResult]. */
    private fun showScanQrDialog() {
        startActivityForResult(Intent(this, PairingScanActivity::class.java), PairingScanActivity.REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // PART 2.6 (optical transfer): checked first, own request code —
        // IntentIntegrator.parseActivityResult below is expected to return
        // null for a request code it doesn't own, but this way that's never
        // relied on.
        if (requestCode == REQUEST_PICK_OPTICAL_FILE) {
            if (resultCode == RESULT_OK) data?.data?.let { handlePickedOpticalFile(it) }
            return
        }
        // Step 4: own request code, same "checked before the legacy
        // IntentIntegrator parse below" reasoning as REQUEST_PICK_OPTICAL_FILE.
        if (requestCode == REQUEST_PICK_IMAGE_ATTACHMENT) {
            if (resultCode == RESULT_OK) data?.data?.let { handlePickedImageAttachment(it) }
            return
        }
        // Step 5: own request code, same pattern.
        if (requestCode == REQUEST_PICK_DOCUMENT_ATTACHMENT) {
            if (resultCode == RESULT_OK) data?.data?.let { handlePickedDocumentAttachment(it) }
            return
        }
        // PART "WHY THE QR JOIN FAILS": PairingScanActivity's own request
        // code, checked before the legacy IntentIntegrator parse below
        // (which is expected to return null for a code it doesn't own, but
        // this way that is never relied on) — see showScanQrDialog's doc.
        if (requestCode == PairingScanActivity.REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                data?.getStringExtra(PairingScanActivity.EXTRA_RESULT_RAW)?.let { handleScannedQr(it) }
            }
            return
        }
        val scan = com.google.zxing.integration.android.IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (scan == null) return // not our request code — nothing else in this Activity uses onActivityResult today
        val raw = scan.contents
        if (raw == null) return // user cancelled the scan — not an error, nothing to report
        handleScannedQr(raw)
    }

    // ── PART D1/D5/D-REMEDIATION: optical file transfer, OCP's own
    // independent wire format (see OpticalFountain's class doc for why
    // this is no longer a port of an AGPL-licensed project). Every send
    // path here packs an OpticalFileContainer (D1: up to 64MB, not this
    // app's old 64KB ceiling) and hands the packed bytes to the generic
    // OpticalShowActivity, which shows the size/estimated-duration screen
    // and the Start/Stop controls (D1) — this Activity never starts a
    // transfer itself, only builds the bytes.

    private val REQUEST_PICK_OPTICAL_FILE = 9201
    private val REQUEST_PICK_IMAGE_ATTACHMENT = 9202
    private val REQUEST_PICK_DOCUMENT_ATTACHMENT = 9203

    private fun launchOpticalShow(container: OpticalFileContainer.PackedFile, title: String) {
        startActivity(Intent(this, OpticalShowActivity::class.java).apply {
            putExtra(OpticalShowActivity.EXTRA_PAYLOAD, container.container)
            putExtra(OpticalShowActivity.EXTRA_TITLE, title)
        })
    }

    /** An OCP-specific payload (a signed Group Alert or chat note) rides the
     *  SAME optical pipeline as a generic file — wrapped as a small file
     *  with mediaType [OCP_FRAME_MEDIA_TYPE], so the receiving side
     *  recognizes the type and additionally verifies the Ed25519 signature
     *  inside (see OpticalScanActivity). */
    private fun packOcpFrame(name: String, signedFrameBytes: ByteArray): OpticalFileContainer.PackedFile =
        OpticalFileContainer.packFile(name, OCP_FRAME_MEDIA_TYPE, signedFrameBytes)

    private fun showSendGroupAlertDialog() {
        val input = android.widget.EditText(this).apply { hint = "Optional message" }
        AlertDialog.Builder(this)
            .setTitle("Send Group Alert")
            .setView(input)
            .setPositiveButton("Send") { _, _ ->
                val lm = getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
                val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val fix = if (hasPermission) {
                    try {
                        lm?.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                            ?: lm?.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                    } catch (e: SecurityException) {
                        null
                    }
                } else null
                val nowSec = System.currentTimeMillis() / 1000L
                val loc = if (fix != null) {
                    MeshLocation(
                        payloadVersion = MeshLocation.PAYLOAD_VERSION, hasFix = true, isMoving = false, batteryLow = false,
                        msgSeq = nowSec, latE7 = (fix.latitude * 1e7).toInt(), lonE7 = (fix.longitude * 1e7).toInt(),
                        accuracyMeters = fix.accuracy.toInt(), altitudeMeters = null, batteryPercent = null,
                        unixSeconds = nowSec, message = input.text.toString().take(MeshLocation.MAX_MESSAGE_BYTES)
                    )
                } else {
                    MeshLocation.noFix(nowSec, nowSec).copy(message = input.text.toString().take(MeshLocation.MAX_MESSAGE_BYTES))
                }
                val signed = OpticalFrame.buildGroupAlert(applicationContext, localNodeId(), loc)
                launchOpticalShow(packOcpFrame("alert.ocpframe", signed), "Group Alert")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSendTextNoteDialog() {
        val input = android.widget.EditText(this)
        AlertDialog.Builder(this)
            .setTitle("Send text note")
            .setView(input)
            .setPositiveButton("Send") { _, _ ->
                val text = input.text.toString()
                if (text.isBlank()) return@setPositiveButton
                val signed = OpticalFrame.buildChat(applicationContext, localNodeId(), MeshFrame.BROADCAST_ID, ChatEnvelope.Text(text))
                launchOpticalShow(packOcpFrame("note.ocpframe", signed), "Text note")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startPickOpticalFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_PICK_OPTICAL_FILE)
    }

    /** D5: also the landing point for a file shared into this app from the
     *  Android share sheet (ACTION_SEND) — see [handleShareSheetIntent],
     *  which reads the same content:// [uri] this file picker does and
     *  calls straight into this function. */
    private fun handlePickedOpticalFile(uri: android.net.Uri) {
        val bytes = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
        if (bytes == null) {
            Toast.makeText(this, "Couldn't read that file", Toast.LENGTH_SHORT).show()
            return
        }
        val name = queryDisplayName(uri) ?: "file"
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        val packed = try {
            OpticalFileContainer.packFile(name, mimeType, bytes)
        } catch (e: OpticalFileContainer.OpticalContainerException) {
            // D1: refuse clearly rather than run for an absurd duration.
            Toast.makeText(this, "Can't send that file optically: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        launchOpticalShow(packed, "File: $name")
    }

    /** Step 4: same ACTION_OPEN_DOCUMENT picker pattern as
     *  [startPickOpticalFile], filtered to image mimetypes via [Intent.setType]
     *  plus EXTRA_MIME_TYPES (the belt-and-suspenders pair some pickers need
     *  to actually narrow their listing rather than just their icon). */
    private fun startPickImageAttachment() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(intent, REQUEST_PICK_IMAGE_ATTACHMENT)
    }

    /** Step 4: mirrors [handlePickedOpticalFile]'s read-bytes-from-uri shape,
     *  but feeds the generic attachment protocol (sendAttachment) instead of
     *  the optical pipeline — rejects outright over MAX_ATTACHMENT_BODY_BYTES
     *  rather than chunking, per this step's explicit scope. */
    private fun handlePickedImageAttachment(uri: android.net.Uri) {
        val bytes = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
        if (bytes == null) {
            Toast.makeText(this, "Couldn't read that image", Toast.LENGTH_SHORT).show()
            return
        }
        if (bytes.size > OfflineMediaTransport.MAX_ATTACHMENT_BODY_BYTES) {
            Toast.makeText(this, "Image too large (${formatAttachmentBytes(bytes.size)}, max ${formatAttachmentBytes(OfflineMediaTransport.MAX_ATTACHMENT_BODY_BYTES)})", Toast.LENGTH_LONG).show()
            return
        }
        val name = queryDisplayName(uri) ?: "image"
        val mimeType = contentResolver.getType(uri) ?: "image/jpeg"
        sendFileAttachment(OfflineMediaTransport.AttachmentKind.IMAGE, bytes, name, mimeType)
    }

    /** Step 5: same ACTION_OPEN_DOCUMENT picker as [startPickImageAttachment],
     *  but any mimetype — this is document sharing, not a narrowed photo
     *  picker. */
    private fun startPickDocumentAttachment() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_PICK_DOCUMENT_ATTACHMENT)
    }

    /** Step 5: mirrors [handlePickedImageAttachment] exactly — same read,
     *  same MAX_ATTACHMENT_BODY_BYTES reject-over-cap, same sendFileAttachment
     *  call, just DOCUMENT instead of IMAGE and no mimetype narrowing. */
    private fun handlePickedDocumentAttachment(uri: android.net.Uri) {
        val bytes = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
        if (bytes == null) {
            Toast.makeText(this, "Couldn't read that file", Toast.LENGTH_SHORT).show()
            return
        }
        if (bytes.size > OfflineMediaTransport.MAX_ATTACHMENT_BODY_BYTES) {
            Toast.makeText(this, "File too large (${formatAttachmentBytes(bytes.size)}, max ${formatAttachmentBytes(OfflineMediaTransport.MAX_ATTACHMENT_BODY_BYTES)})", Toast.LENGTH_LONG).show()
            return
        }
        val name = queryDisplayName(uri) ?: "file"
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        sendFileAttachment(OfflineMediaTransport.AttachmentKind.DOCUMENT, bytes, name, mimeType)
    }

    /** Step 4/5: shared send path for a fetch-gated file attachment
     *  (IMAGE/DOCUMENT) picked from the device — mirrors
     *  [sendVoiceNoteAttachment]'s "sender already has the body, no Download
     *  gate for your own sent message" pattern exactly. */
    private fun sendFileAttachment(kind: OfflineMediaTransport.AttachmentKind, bytes: ByteArray, filename: String, mimeType: String) {
        val transport = mediaTransport ?: return
        val msgId = transport.sendAttachment(kind, bytes, filename = filename, mimeType = mimeType) ?: return
        val meta = OfflineMediaTransport.AttachmentMeta(msgId, kind, bytes.size, filename = filename, mimeType = mimeType)
        val ref = AttachmentRef(msgId, kind, transport.localNodeId, meta, fetchedBytes = bytes)
        attachmentRefsByMsgId[msgId] = ref
        val label = if (kind == OfflineMediaTransport.AttachmentKind.IMAGE) "Photo" else "Document"
        appendChatMessage(text = label, fromMe = true, attachment = ref)
    }

    /** Step 6: one-shot "share my current location" — LOCATION is an inline
     *  AttachmentKind (see its own doc), so this is a single META frame,
     *  never a fetch round trip. Reuses the mesh session's own
     *  already-running OfflineLocationProvider via
     *  [OfflineMediaTransport.currentLocationFix] rather than starting a
     *  second GPS session. */
    private fun shareCurrentLocation() {
        val transport = mediaTransport ?: return
        val fix = transport.currentLocationFix()
        if (fix == null) {
            Toast.makeText(this, "No location fix yet", Toast.LENGTH_SHORT).show()
            return
        }
        val latE7 = (fix.latitude * 1e7).toInt()
        val lonE7 = (fix.longitude * 1e7).toInt()
        val msgId = transport.sendAttachment(OfflineMediaTransport.AttachmentKind.LOCATION, ByteArray(0), latE7 = latE7, lonE7 = lonE7) ?: return
        val meta = OfflineMediaTransport.AttachmentMeta(msgId, OfflineMediaTransport.AttachmentKind.LOCATION, 0, latE7 = latE7, lonE7 = lonE7)
        val ref = AttachmentRef(msgId, OfflineMediaTransport.AttachmentKind.LOCATION, transport.localNodeId, meta)
        attachmentRefsByMsgId[msgId] = ref
        appendChatMessage(text = "Location", fromMe = true, attachment = ref)
    }

    /** Step 7: entry point — requests READ_CONTACTS if not already granted
     *  (same ContextCompat/ActivityCompat pattern as [onSearchClicked],
     *  separate request code so its result can't be confused with
     *  PERM_REQUEST's own flow), then opens the picker. */
    private fun shareContact() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_CONTACTS_PERMISSION)
            return
        }
        showContactPickerDialog()
    }

    /** Step 7: reuses [com.opencall.relay.dialer.data.ContactsRepository]'s
     *  existing ContactsContract query (Pillar 2's own contacts list/T9
     *  search source) rather than a second, parallel contacts query here. */
    private fun showContactPickerDialog() {
        val contacts = com.opencall.relay.dialer.data.ContactsRepository.queryContacts(this)
        if (contacts.isEmpty()) {
            Toast.makeText(this, "No contacts found", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = contacts.map { "${it.displayName} (${it.phoneNumbers.firstOrNull() ?: "no number"})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Share a contact")
            .setItems(labels) { _, which ->
                val c = contacts[which]
                val number = c.phoneNumbers.firstOrNull()
                if (number == null) {
                    Toast.makeText(this, "That contact has no phone number", Toast.LENGTH_SHORT).show()
                } else {
                    sendContactAttachment(c.displayName, number)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Step 7: CONTACT is an inline AttachmentKind (see Step 3) — name+phone
     *  travel complete in a single META frame, same no-fetch-gate shape as
     *  [shareCurrentLocation]'s LOCATION. */
    private fun sendContactAttachment(name: String, phone: String) {
        val transport = mediaTransport ?: return
        val msgId = transport.sendAttachment(OfflineMediaTransport.AttachmentKind.CONTACT, ByteArray(0), contactName = name, contactPhone = phone) ?: return
        val meta = OfflineMediaTransport.AttachmentMeta(msgId, OfflineMediaTransport.AttachmentKind.CONTACT, 0, contactName = name, contactPhone = phone)
        val ref = AttachmentRef(msgId, OfflineMediaTransport.AttachmentKind.CONTACT, transport.localNodeId, meta)
        attachmentRefsByMsgId[msgId] = ref
        appendChatMessage(text = "Contact: $name", fromMe = true, attachment = ref)
    }

    private fun queryDisplayName(uri: android.net.Uri): String? = try {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    /** D5: this Activity is the target of an incoming ACTION_SEND share
     *  (see AndroidManifest.xml's intent-filter on it) — reads the shared
     *  stream URI and routes it through the exact same
     *  [handlePickedOpticalFile] path the in-app file picker uses, so
     *  "share a photo from Gallery" and "pick a file from Settings" are
     *  one code path, not two. Called from [onCreate]/[onNewIntent]. */
    private fun handleShareSheetIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM) ?: return
        handlePickedOpticalFile(uri)
    }

    /** Parses [raw] defensively — a malformed/foreign QR code must never
     *  crash or half-join (3.10's own requirement): decodeQrPayload catches
     *  everything and returns null; this just reacts to that, touching no
     *  mesh/roster state on a bad scan. */
    private fun handleScannedQr(raw: String) {
        Log.d("OFFTRACE", "QR: scanned len=${raw.length} prefix=${raw.take(24)}")
        val parsed = decodeQrPayload(raw)
        if (parsed == null) {
            Log.d("OFFTRACE", "QR: gate=decodeQrPayload passed=false reason=malformed_or_missing_version")
            Toast.makeText(this, "That doesn't look like an OpenCall invite QR code", Toast.LENGTH_SHORT).show()
            return
        }
        // B3: logged straight from the parsed struct, not re-derived —
        // this is exactly what the rest of this function sees.
        Log.d(
            "OFFTRACE",
            "QR: parsed ver=${parsed.version} node=${MeshFrame.hex(parsed.nodeId)} pubkeyLen=${parsed.pubkeyB64.length} " +
                "ss=${parsed.ss} pwLen=${parsed.pw?.length}"
        )
        val (nodeId, name) = parsed.nodeId to parsed.name
        // PART 2.2: VERIFIED pairing — a pubkey that arrived by a human
        // physically scanning this QR code is the strongest identity signal
        // this app has, stronger than anything that ever arrives over the
        // air (see PeerTrustStore's own doc). Self-certify FIRST (same
        // nodeId=SHA-256(pubkey)[0..8] check MeshSigner.recordVerifiedPubkey
        // does for a HELLO) — a QR with an internally inconsistent
        // nodeId/pubkey pair is refused before it ever reaches the trust
        // store, exactly like a forged HELLO would be.
        val pubkeyBytes = try {
            if (parsed.pubkeyB64.isNotBlank()) android.util.Base64.decode(parsed.pubkeyB64, android.util.Base64.NO_WRAP) else null
        } catch (e: IllegalArgumentException) {
            null
        }
        if (pubkeyBytes != null) {
            val selfCertOk = pubkeyBytes.size == 32 && MeshSigner.deriveNodeId(pubkeyBytes) == nodeId
            Log.d("OFFTRACE", "QR: gate=pubkey_self_certification passed=$selfCertOk reason=${if (selfCertOk) "ok" else "nodeId_pubkey_mismatch"}")
            if (!selfCertOk) {
                Log.w("OFFTRACE", "QR: scan REJECTED ${MeshFrame.hex(nodeId)} — nodeId does not match pubkey")
                Toast.makeText(this, "This QR code's identity doesn't check out — not scanning it", Toast.LENGTH_LONG).show()
                return
            }
            val trustOutcome = PeerTrustStore.record(applicationContext, nodeId, pubkeyBytes, TrustLevel.VERIFIED)
            val impersonation = trustOutcome is PeerTrustStore.TrustOutcome.ImpersonationRefused
            Log.d("OFFTRACE", "QR: gate=peer_trust_store passed=${!impersonation} reason=${if (impersonation) "impersonation_refused" else "ok"}")
            if (impersonation) {
                Log.w("OFFTRACE", "QR: scan REJECTED ${MeshFrame.hex(nodeId)} — pubkey differs from a previously VERIFIED scan (impersonation)")
                AlertDialog.Builder(this)
                    .setTitle("⚠ Possible impersonation")
                    .setMessage("$name's QR code no longer matches the identity you verified for them before. Refusing to link — if this is really $name, re-verify in person.")
                    .setPositiveButton("OK", null)
                    .show()
                return
            }
        } else {
            Log.d("OFFTRACE", "QR: gate=pubkey_self_certification passed=true reason=no_pubkey_field_to_check")
        }
        // PART "HASSLE-FREE JOIN" 1.3/3.1: "scan then join, nothing in
        // between" — NO discovery dependency. The QR already carries the
        // host's ACTUAL live credentials (see qrPayload's doc), and
        // WifiDirectManager.connect()'s EXPLICIT branch (both networkName
        // and passphrase non-null) never reads the device parameter's
        // deviceAddress at all — that field only matters on the OTHER
        // (negotiate) branch, which this call never takes (see connect()'s
        // own doc: "unlike the explicit Builder config above, which never
        // sets deviceAddress at all"). So whether or not this host happens
        // to already be a WFD-DISCOVERED nearbyDevices entry is irrelevant
        // — a placeholder WifiP2pDevice() is exactly as good as a real one
        // here, and this device connects on nothing but the scanned code,
        // with service discovery never entered into it.
        val hasCredentials = parsed.ss != null && parsed.pw != null
        Log.d("OFFTRACE", "QR: gate=credentials_present passed=$hasCredentials reason=${if (hasCredentials) "ok" else "ss_or_pw_null_in_scanned_payload"}")
        if (hasCredentials) {
            val readyToConnect = !connectRequested && !isLocalGroupOwner
            Log.d(
                "OFFTRACE",
                "QR: gate=ready_to_connect passed=$readyToConnect reason=${
                    if (readyToConnect) "ok" else if (connectRequested) "connect_already_requested" else "already_hosting"
                }"
            )
            if (readyToConnect) {
                connectRequested = true
                formedAtLeastOnceThisAttempt = false
                val discoveredDevice = nearbyDevices[nodeId]?.wifiDevice
                Log.d(
                    "OFFTRACE",
                    "INVITE: path=connect-qr peer=${MeshFrame.hex(nodeId)} transmitted=true reason=qr-explicit " +
                        "viaDiscovery=${discoveredDevice != null}"
                )
                // B5
                Log.d("OFFTRACE", "QR: join ss=${parsed.ss} pwLen=${parsed.pw?.length} path=explicit")
                wifiDirect.connect(discoveredDevice ?: WifiP2pDevice(), MeshFrame.hex(nodeId), parsed.ss, parsed.pw) { ok ->
                    runOnUiThread { if (!ok) connectRequested = false }
                }
                Toast.makeText(this, "Joining $name…", Toast.LENGTH_SHORT).show()
            }
            return
        }
        // A QR with no live ss/pw (the scanned device isn't currently
        // hosting) has nothing to join directly — nothing left to fall
        // back to now that the old invite-a-specific-peer flow is gone
        // (see item 4's own removal).
        Toast.makeText(this, "$name isn't hosting a group right now", Toast.LENGTH_LONG).show()
    }

    // ── PHASE 2.2: Settings screen — small shared row builders ───────────────
    private fun settingsSectionHeader(text: String): TextView {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(TopoPalette.mutedFg(nightModeEnabled))
            setPadding(0, (24 * density).toInt(), 0, (8 * density).toInt())
        }
    }

    private fun settingsInfoLine(text: String): TextView {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(TopoPalette.mutedFg(nightModeEnabled))
            setPadding(0, (4 * density).toInt(), 0, (8 * density).toInt())
        }
    }

    // ── Signal Deck (diagnostic follow-up) shared row builders ──────────────

    /** 40dp circular icon button, sun/moon swapped by [nightModeEnabled] —
     *  Step 2's own top bar; built here (not AppShell) because it's wired
     *  directly to this Activity's existing setNightMode/nightModeEnabled,
     *  which other screens don't have a notion of yet (see the diagnostic
     *  report's Step 5 flag) — reusing this build site, not duplicating
     *  it, is Step 5's job once that's resolved. */
    private fun buildThemeToggleIcon(): Button {
        val density = resources.displayMetrics.density
        themeToggleButton = Button(this).apply {
            textSize = 18f
            setPadding(0, 0, 0, 0)
            setOnClickListener { setNightMode(!nightModeEnabled) }
        }
        themeToggleButton.layoutParams = LinearLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt())
        applyThemeToggleIcon()
        return themeToggleButton
    }

    private fun applyThemeToggleIcon() {
        if (!::themeToggleButton.isInitialized) return
        val mode = currentTopoMode()
        themeToggleButton.text = if (nightModeEnabled) "☀" else "🌙" // tap to SWITCH TO the mode shown
        themeToggleButton.setTextColor(TopoPalette.fg(nightModeEnabled))
        themeToggleButton.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(TopoPalette.bgRaised(mode))
        }
    }

    /** "icon + title + subtitle + chevron" row, per the Signal Deck task
     *  spec — a new shape, distinct from [settingsButtonRow] (which pairs
     *  a label with its own trailing BUTTON, not a chevron-as-affordance
     *  whole-row tap target). */
    private fun buildBringSomeoneInRow(icon: String, title: String, subtitle: String, onClick: () -> Unit): View {
        val density = resources.displayMetrics.density
        val mode = currentTopoMode()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (64 * density).toInt()
            setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        row.addView(TextView(this).apply {
            text = icon
            textSize = 22f
            setTextColor(TopoPalette.accent(mode))
            minWidth = (40 * density).toInt()
        })
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding((12 * density).toInt(), 0, 0, 0)
        }
        labels.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(TopoPalette.textPrimary(mode))
        })
        labels.addView(TextView(this).apply {
            text = subtitle
            textSize = 13f
            setTextColor(TopoPalette.textSecondary(mode))
        })
        row.addView(labels)
        row.addView(TextView(this).apply {
            text = "›"
            textSize = 20f
            setTextColor(TopoPalette.textMuted(mode))
        })
        return row
    }

    private fun buildSignalDeckDivider(): View {
        val density = resources.displayMetrics.density
        return View(this).apply {
            setBackgroundColor(TopoPalette.contour(currentTopoMode()))
        }.also { it.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()) }
    }

    /** Same share-text/intent [settingsButtonRow]'s existing "Share an
     *  invite link" Settings row already uses — one implementation, two
     *  entry points, not a second copy. */
    private fun shareInviteLink() {
        val text = "Join me on OpenCall mesh — my id is ${localShortId()}"
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }, "Share via"))
    }

    /** Voice = outline, Video = filled amber (TopoPalette.accent) — video
     *  is the heavier-weight action on this row. Re-applied by setNightMode
     *  (mode changes recolour both) and called once at construction. */
    private fun applyCallButtonStyles() {
        if (!::groupCallVoiceButton.isInitialized) return
        val mode = currentTopoMode()
        val density = resources.displayMetrics.density
        groupCallVoiceButton.setTextColor(TopoPalette.accent(mode))
        groupCallVoiceButton.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke((2 * density).toInt(), TopoPalette.accent(mode))
        }
        groupCallVideoButton.setTextColor(TopoPalette.onAccent(mode))
        groupCallVideoButton.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(TopoPalette.accent(mode))
        }
    }

    /** Label + toggle button bound directly to a SharedPreferences("opencall")
     *  boolean key — the SAME prefs file/name every other pref in this app
     *  already uses (SosTriggers/SosRelay/OfflineIdentity/night_mode). Never
     *  introduces a second prefs file. */
    private fun settingsToggleRow(label: String, prefKey: String, default: Boolean, onChanged: ((Boolean) -> Unit)? = null): LinearLayout {
        val density = resources.displayMetrics.density
        val prefs = getSharedPreferences("opencall", MODE_PRIVATE)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (56 * density).toInt()
        }
        val labelView = TextView(this).apply {
            text = label
            textSize = 18f
            setTextColor(TopoPalette.fg(nightModeEnabled))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val toggleBtn = Button(this)
        fun refresh(v: Boolean) { toggleBtn.text = if (v) "On" else "Off" }
        refresh(prefs.getBoolean(prefKey, default))
        toggleBtn.setOnClickListener {
            val newVal = !prefs.getBoolean(prefKey, default)
            prefs.edit().putBoolean(prefKey, newVal).apply()
            refresh(newVal)
            onChanged?.invoke(newVal)
        }
        row.addView(labelView)
        row.addView(toggleBtn)
        return row
    }

    private fun settingsButtonRow(label: String, buttonText: String, sub: String? = null, onClick: () -> Unit): LinearLayout {
        val density = resources.displayMetrics.density
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (56 * density).toInt()
        }
        val labelCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        labelCol.addView(TextView(this).apply {
            text = label
            textSize = 18f
            setTextColor(TopoPalette.fg(nightModeEnabled))
        })
        if (sub != null) labelCol.addView(settingsInfoLine(sub))
        row.addView(labelCol)
        row.addView(Button(this).apply { text = buttonText; setOnClickListener { onClick() } })
        col.addView(row)
        return col
    }

    /** Minimum-battery-level stepper for Mesh network's "relay_min_battery" —
     *  plain +/-5 buttons rather than a SeekBar (matches this app's existing
     *  button-based interaction style throughout; no new widget class). */
    private fun settingsStepperRow(label: String, prefKey: String, default: Int, min: Int, max: Int, step: Int, unit: String): LinearLayout {
        val density = resources.displayMetrics.density
        val prefs = getSharedPreferences("opencall", MODE_PRIVATE)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (56 * density).toInt()
        }
        val labelView = TextView(this).apply {
            text = label
            textSize = 18f
            setTextColor(TopoPalette.fg(nightModeEnabled))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val valueView = TextView(this).apply { textSize = 18f; setTextColor(TopoPalette.fg(nightModeEnabled)); setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0) }
        fun refresh() { valueView.text = "${prefs.getInt(prefKey, default)}$unit" }
        refresh()
        val minusBtn = Button(this).apply {
            text = "-"
            setOnClickListener {
                val v = (prefs.getInt(prefKey, default) - step).coerceIn(min, max)
                prefs.edit().putInt(prefKey, v).apply()
                refresh()
            }
        }
        val plusBtn = Button(this).apply {
            text = "+"
            setOnClickListener {
                val v = (prefs.getInt(prefKey, default) + step).coerceIn(min, max)
                prefs.edit().putInt(prefKey, v).apply()
                refresh()
            }
        }
        row.addView(labelView)
        row.addView(minusBtn)
        row.addView(valueView)
        row.addView(plusBtn)
        return row
    }

    /** PHASE 2.2 "Mesh network" live status card — recomputed each time the
     *  Settings tab is (re)selected (see selectTab) and whenever its own
     *  refresh button is tapped; not on a timer, since a stale one-line
     *  status is harmless and a Settings-screen redraw loop would be pure
     *  battery waste for a screen that isn't the ring. States exactly why
     *  relay is or is not currently sending, reading the SAME three prefs
     *  Phase 4's relayDelayMs() gates on. */
    private fun buildRelayStatusCard(): TextView {
        val prefs = getSharedPreferences("opencall", MODE_PRIVATE)
        val card = settingsInfoLine("")
        fun refresh() {
            val enabled = prefs.getBoolean("relay_enabled", true)
            val minBatt = prefs.getInt("relay_min_battery", 20)
            val onlyCharging = prefs.getBoolean("relay_only_charging", false)
            val pct = readBatteryPercent()
            val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val charging = (status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            card.text = when {
                !enabled -> "Your messages will NOT be relayed — relay is turned off."
                onlyCharging && !charging -> "Your messages will NOT be relayed — \"only when charging\" is on and this device isn't plugged in."
                pct != null && pct < minBatt && !charging -> "Your messages will NOT be relayed — battery $pct% is below the $minBatt% minimum."
                pct != null -> "Your messages will be relayed — battery $pct%${if (charging) " (charging)" else ""}."
                else -> "Your messages will be relayed — battery level unknown."
            }
        }
        refresh()
        card.setOnClickListener { refresh() }
        return card
    }

    /** PHASE 2.2 "Appearance" — a 3rd, additive pref ("appearance_mode":
     *  system/light/dark) layered on top of the EXISTING "night_mode"
     *  boolean, which stays the single source of truth setNightMode()/every
     *  other reader already uses — "system" just computes what that boolean
     *  should be, once, from the OS night-mode flag, rather than this app
     *  adopting a live day/night theme engine (out of scope here). */
    private fun resolveSystemNightMode(): Boolean {
        val flag = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return flag == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun buildSettingsScreen(): android.widget.ScrollView {
        val density = resources.displayMetrics.density
        val prefs = getSharedPreferences("opencall", MODE_PRIVATE)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (12 * density).toInt(), (20 * density).toInt(), (48 * density).toInt())
        }

        // Identity
        body.addView(settingsSectionHeader("Identity"))
        val identityRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val shortIdView = TextView(this).apply {
            textSize = 22f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(TopoPalette.fg(nightModeEnabled))
        }
        // FIX 3c: identity exists before any connection ever happens — this
        // must never read "Not connected yet" (the ID is not "the connection's"
        // ID, it's this device's own, always available).
        fun refreshShortId() {
            shortIdView.text = localShortId()
        }
        refreshShortId()
        identityRow.addView(shortIdView)
        body.addView(identityRow)
        body.addView(settingsButtonRow("Display name", "Edit", sub = OfflineIdentity.displayName(applicationContext)) { showDisplayNameDialog() })

        // Tell a friend
        body.addView(settingsSectionHeader("Tell a friend"))
        body.addView(settingsButtonRow("Share an invite link", "Share") {
            val text = "Join me on OpenCall mesh — my id is ${localShortId()}" // FIX 3: never "?" — see localShortId's doc
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }, "Share via"))
        })
        body.addView(settingsButtonRow("Show QR code", "Show") { showInviteQrDialog() })

        // PART "HASSLE-FREE JOIN" item 4: group-JOINING pairing (this
        // section's own QR above, plus BLE) now lives on the Nearby
        // screen's "Start a group"/open-groups list/"Scan a code" — item
        // 4's own "two things and nothing else" for that screen pushed the
        // OPTICAL FILE/ALERT transfer feature (a separate concern, not a
        // group-join mechanism) back here.
        body.addView(settingsSectionHeader("Optical transfer"))
        body.addView(settingsInfoLine("Send a signed Group Alert, text note, or small file to a nearby phone by holding the two screens up to each other — no Wi-Fi Direct or radio link needed."))
        body.addView(settingsButtonRow("Send Group Alert", "Send") { showSendGroupAlertDialog() })
        body.addView(settingsButtonRow("Send text note", "Send") { showSendTextNoteDialog() })
        body.addView(settingsButtonRow("Send a file", "Send") { startPickOpticalFile() })
        body.addView(settingsButtonRow("Scan", "Scan") { startActivity(Intent(this, OpticalScanActivity::class.java)) })

        // Call controls
        body.addView(settingsSectionHeader("Call controls"))
        body.addView(settingsInfoLine("Button operation mode — tap for instant action, or long-press to prevent accidental presses with the phone at your ear. In-call controls only."))
        body.addView(settingsToggleRow("Long-press for in-call buttons", PREF_CALL_BUTTON_LONGPRESS, false))

        // Location
        body.addView(settingsSectionHeader("Location"))
        body.addView(settingsToggleRow("Share my location with the group", "location_sharing_enabled", true))
        body.addView(settingsInfoLine("Lets other members see your distance and direction on the ring. Turning this off only stops your OWN position from being shared — you'll still see everyone else's."))

        // Appearance
        body.addView(settingsSectionHeader("Appearance"))
        val themeBtn = Button(this)
        fun refreshThemeBtn() {
            themeBtn.text = "Theme: " + when (prefs.getString("appearance_mode", "system")) {
                "light" -> "Light"
                "dark" -> "Dark"
                else -> "Match system"
            }
        }
        refreshThemeBtn()
        themeBtn.setOnClickListener {
            val next = when (prefs.getString("appearance_mode", "system")) {
                "system" -> "light"
                "light" -> "dark"
                else -> "system"
            }
            prefs.edit().putString("appearance_mode", next).apply()
            refreshThemeBtn()
            val resolvedNight = when (next) {
                "dark" -> true
                "light" -> false
                else -> resolveSystemNightMode()
            }
            setNightMode(resolvedNight)
        }
        body.addView(themeBtn)
        // Step 5 (diagnostic follow-up): the separate "Night mode" toggle row
        // DELETED — redundant with themeBtn above (both ultimately drive the
        // same setNightMode()/night_mode pref) and with the Offline mesh
        // screen's own top-right icon (Step 2). themeBtn stays: its
        // "Match system" option is real, distinct capability the plain
        // toggle never had — not literally the "Night mode toggle" this
        // step asked to remove.

        // Communication
        body.addView(settingsSectionHeader("Communication"))
        body.addView(settingsToggleRow("Notifications", "notif_enabled", true))
        body.addView(settingsButtonRow("Ringtone", "Choose") {
            try {
                startActivity(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                })
            } catch (e: Exception) {
                Toast.makeText(this, "No ringtone picker available", Toast.LENGTH_SHORT).show()
            }
        })
        body.addView(settingsToggleRow("BLE chat notifications", "ble_chat_notif_enabled", true))

        // Mesh network
        body.addView(settingsSectionHeader("Mesh network"))
        // TOPO PART B2: "End session" — tearing down the Wi-Fi Direct group
        // is not a messaging action, so it lives here, not in Messages/
        // Nearby. Confirm-gated; calls the EXACT SAME leaveGroup("user left
        // group") this control has always called (see OUTPUT proof #6).
        body.addView(settingsButtonRow("End session", "End session", sub = "Disconnects everyone") {
            AlertDialog.Builder(this)
                .setTitle("End session")
                .setMessage("Disconnects everyone in this session from the mesh group. This can't be undone from here — everyone will need to reconnect.")
                .setPositiveButton("End session") { _, _ -> leaveGroup("user left group") }
                .setNegativeButton("Cancel", null)
                .show()
        })
        val relayStatusCard = buildRelayStatusCard()
        body.addView(settingsToggleRow("Enable relay", "relay_enabled", true) { relayStatusCard.callOnClick() })
        body.addView(relayStatusCard)
        body.addView(settingsStepperRow("Minimum battery level", "relay_min_battery", 20, 0, 100, 5, "%"))
        body.addView(settingsToggleRow("Relay only when charging", "relay_only_charging", false) { relayStatusCard.callOnClick() })
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            body.addView(settingsButtonRow(
                "Battery optimization is ON",
                "Fix",
                sub = "Doze can kill background mesh relay. Exempt OpenCall to keep it alive when your screen is off."
            ) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                } catch (e: Exception) {
                    Toast.makeText(this, "Couldn't open battery settings", Toast.LENGTH_SHORT).show()
                }
            })
        }

        // TOPO PHASE 2.3/2.4: the "Safety" section (SOS hold duration/siren
        // volume/trigger settings/PLB line) MOVED into sosSectionOverlay —
        // not duplicated here (per the task's explicit instruction).

        // Power
        body.addView(settingsSectionHeader("Power"))
        body.addView(settingsInfoLine(
            "Battery saving mode: at 15% or below and not charging, the ring freezes, the compass stops, and your position broadcasts every 120s instead of 30s. Group Alert and quick phrases stay fully live the whole time. Restores above 20% (5% hysteresis so it can't flicker)."
        ))
        body.addView(settingsInfoLine(if (inBatteryCliff) "Currently ACTIVE — battery saving mode is on right now." else "Currently off — battery is above the threshold."))

        // Legal
        body.addView(settingsSectionHeader("Legal"))
        body.addView(settingsButtonRow("Privacy & terms", "View") {
            AlertDialog.Builder(this)
                .setTitle("Privacy & terms")
                .setMessage(
                    "OpenCall's offline mesh mode sends no data to any server — all traffic stays " +
                        "on-device and over Wi-Fi Direct/BLE to nearby devices you're grouped with. " +
                        "No ads. No analytics. No third-party SDKs."
                )
                .setPositiveButton("Close", null)
                .show()
        })

        return android.widget.ScrollView(this).apply {
            setBackgroundColor(TopoPalette.bgBase(currentTopoMode())) // TOPO PART B3
            addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /** PHASE 5BC: builds one full-screen, scrollable overlay — used for both the
     *  SOS alert and the party-status screen (see their call sites). GONE by
     *  default. [onCloseSilenceAll] is non-null only for the SOS alert, where
     *  "Silence" stops sound on this device only (does not clear SOS state or
     *  this screen — see SosAlarm.silence's doc); the party-status screen's
     *  close button is a plain dismiss. */
    private fun buildFullScreenOverlay(
        titleText: String,
        titleBg: Int,
        onCloseSilenceAll: (() -> Unit)?,
        bodyOut: (LinearLayout) -> Unit
    ): LinearLayout {
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // PART 3.4 (batch B): was Color.argb(245, ...) — 96% opaque, not
            // fully solid, is what let the layer underneath (red-on-red for
            // the Group Alert screen specifically) bleed through. Fully
            // opaque now.
            setBackgroundColor(Color.rgb(20, 20, 20))
            visibility = View.GONE
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(titleBg)
            setPadding(24, 48, 24, 24)
        }
        val titleLabel = TextView(this).apply {
            text = titleText
            textSize = 22f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleRow.addView(titleLabel)
        if (onCloseSilenceAll != null) {
            val silenceButton = Button(this).apply {
                text = "Silence"
                setOnClickListener { onCloseSilenceAll() }
            }
            titleRow.addView(silenceButton)
        }
        val closeButton = Button(this).apply {
            text = "Close"
            setOnClickListener { overlay.visibility = View.GONE }
        }
        titleRow.addView(closeButton)
        overlay.addView(titleRow)

        val scroll = android.widget.ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        scroll.addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        overlay.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        bodyOut(body)
        return overlay
    }

    /** PHASE 5BC: onboarding copy shown once per install, plainly — this
     *  supplements and does not replace a PLB or satellite messenger. */
    private fun maybeShowSosOnboarding() {
        val prefs = getSharedPreferences("opencall", MODE_PRIVATE)
        if (prefs.getBoolean("sos_onboarding_shown", false)) return
        AlertDialog.Builder(this)
            .setTitle("Before you rely on Group Alert")
            .setMessage(
                "This mesh Group Alert works over Wi-Fi Direct only — it has no satellite or " +
                    "cellular link. It can only reach party members within Wi-Fi Direct " +
                    "range (roughly 50-200m line of sight, much less through snow, rock, " +
                    "or bodies), or who later come back into range and pick up a cached " +
                    "alert.\n\nIt supplements, but does NOT replace, a personal locator " +
                    "beacon (PLB) or satellite messenger. Carry one on any serious trip."
            )
            .setPositiveButton("Understood", null)
            .setCancelable(false)
            .show()
        prefs.edit().putBoolean("sos_onboarding_shown", true).apply()
    }

    /** PHASE 3B: large active-speaker area (video, or a text placeholder in audio
     *  mode / when nobody's currently speaking) + a horizontally-scrolling
     *  participant strip below it. Built once here, shown only once a group call is
     *  actually running (see [onGroupCallStarted]). */
    private fun buildGroupCallScreen(root: LinearLayout) {
        val density = resources.displayMetrics.density
        groupCallScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        groupCallStatusText = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        groupCallScreen.addView(groupCallStatusText)

        // PHASE 3C: the grid itself — cell count/shape is recomputed on every
        // participants change (see gridDimensionsFor); tiles are built once per
        // participant and reparented into new cells on each rebuild rather than
        // recreated, so a live decoder's Surface binding survives a resize.
        groupCallGrid = GridLayout(this)
        groupCallScreen.addView(
            groupCallGrid,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        // PHASE 3C: local controls bar — camera toggle (sends TYPE_CAM, starts/
        // stops the local encoder once the GO confirms), mic hard-mute toggle,
        // leave call.
        groupCallCameraButton = Button(this).apply {
            text = "Camera: Off"
            setOnClickListener {
                // FIX G: decide what to request from the transport's REAL camera
                // state, not the groupCallLocalCameraOn mirror alone — that mirror
                // only ever advances on a confirmed onGroupCallCamState callback,
                // so if a prior request was ever dropped/denied and the mirror
                // stayed at its correct "false", this still asks for "true" again
                // (fine); but guarding against drift here means a future desync
                // between the mirror and reality can never turn this button into a
                // silent no-op either way.
                val actuallyOn = mediaTransport?.isLocalCameraOn() ?: groupCallLocalCameraOn
                val turningOn = !actuallyOn
                mediaTransport?.setGroupCallCameraOn(turningOn)
                // Turning OFF is always accepted, so it's safe to reflect right
                // away; turning ON waits for onGroupCallCamState (accepted) or
                // onGroupCallCamDenied (cap reached) before the button/tile change.
                if (!turningOn) {
                    groupCallLocalCameraOn = false
                    updateGroupCallControlsBar()
                }
            }
        }
        groupCallMicButton = Button(this).apply {
            text = "Mic: On"
            setOnClickListener {
                groupCallLocalMicMuted = !groupCallLocalMicMuted
                mediaTransport?.setGroupCallMicMuted(groupCallLocalMicMuted)
                updateGroupCallControlsBar()
            }
        }
        leaveGroupCallButton = Button(this).apply {
            text = "Leave call"
            setOnClickListener { mediaTransport?.leaveGroupCall() }
        }
        // PHASE 8 TRACK A4: same merged nearby list, reachable mid-call — GO
        // only (pulling a new member into the WFD group is still GO-only, see
        // renderRosterList's doc), reuses the shared adapter/row builder, not a
        // second implementation.
        groupCallInviteButton = Button(this).apply {
            text = "Invite"
            setOnClickListener { showMidCallInviteDialog() }
        }
        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, (8 * density).toInt(), 0, (16 * density).toInt())
            addView(groupCallCameraButton)
            addView(groupCallMicButton)
            addView(groupCallInviteButton)
            addView(leaveGroupCallButton)
        }
        groupCallScreen.addView(controlsRow)

        // FIX: root.addView(groupCallScreen) with no explicit LayoutParams used to
        // fall back to LinearLayout's default WRAP_CONTENT/WRAP_CONTENT — the
        // classic Android weight-collapse trap: groupCallGrid's OWN
        // LayoutParams(MATCH_PARENT, 0, 1f) relative to groupCallScreen were
        // already correct, but a weighted child's "0 + weight" only resolves
        // against space its PARENT actually has to distribute, and a WRAP_CONTENT
        // groupCallScreen has none — so groupCallGrid (and every tile inside it)
        // measured to a real width but zero height. root itself IS bounded
        // (MATCH_PARENT via setContentView), so giving groupCallScreen a weight
        // here is enough to fix the whole chain without touching the per-tile
        // GridLayout.LayoutParams at all.
        root.addView(
            groupCallScreen,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f }
        )
    }


    private fun initialsFor(name: String): String {
        val letters = name.trim().split(Regex("\\s+")).mapNotNull { it.firstOrNull()?.uppercaseChar() }
        return if (letters.isEmpty()) "?" else letters.take(2).joinToString("")
    }

    /** Builds one tile's view tree — video Surface underneath, avatar/initials
     *  overlay above it (visibility toggled by camera state, never both hidden),
     *  name/speaking-indicator/battery overlays on top of both. Registers this
     *  tile's Surface with the transport as either the LOCAL camera preview target
     *  (this device's own id — see setLocalPreviewSurface) or a remote sender's
     *  decode target (setGroupTileSurface) the moment the SurfaceView is created,
     *  and unregisters it on destroy. */
    private fun createGroupTile(nodeId: Long): GroupTile {
        val isMe = nodeId == mediaTransport?.localNodeId
        val container = FrameLayout(this)
        val surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.d("OFFTRACE", "UI: surfaceCreated tile=${MeshFrame.hex(nodeId)} " +
                    "${holder.surfaceFrame.width()}x${holder.surfaceFrame.height()}")
                if (isMe) mediaTransport?.setLocalPreviewSurface(holder.surface)
                else mediaTransport?.setGroupTileSurface(nodeId, holder.surface)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (isMe) mediaTransport?.setLocalPreviewSurface(null)
                else mediaTransport?.setGroupTileSurface(nodeId, null)
            }
        })
        container.addView(surfaceView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val avatarText = TextView(this).apply {
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(255, 55, 55, 65))
        }
        container.addView(avatarText, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val nameLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            setPadding(12, 4, 12, 4)
        }
        container.addView(
            nameLabel,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.START
            }
        )

        val speakingDot = TextView(this).apply { text = "🔊"; textSize = 14f; visibility = View.GONE }
        container.addView(
            speakingDot,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.END
            }
        )

        val batteryText = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 10f
            visibility = View.GONE
        }
        container.addView(
            batteryText,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.END
            }
        )

        // PHASE 8 STEP 2/4: sits ABOVE the surface (added after it, below name/
        // battery/speaking overlays isn't required — always-on-top is fine,
        // it's only ever visible while genuinely degraded/excluded) so a
        // frozen last-decoded frame or "video unavailable" reads clearly
        // instead of blending into whatever's underneath.
        val statusScrim = View(this).apply {
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            visibility = View.GONE
        }
        container.addView(statusScrim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        container.addView(
            statusText,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
        )

        val tile = GroupTile(container, surfaceView, avatarText, nameLabel, speakingDot, batteryText, statusScrim, statusText)
        container.setOnClickListener { onTileTapped(nodeId) }
        // Point 7's "letterboxed via the existing aspect fix" — re-run on every
        // resize of THIS tile's own cell (grid rebuild, rotation, fullscreen
        // toggle), same pattern as the 1:1/old speaker view's frame listener.
        container.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if ((right - left) != (oldRight - oldLeft) || (bottom - top) != (oldBottom - oldTop)) {
                letterbox(tile.surfaceView, tile.container, tile.videoWidth, tile.videoHeight)
            }
        }
        return tile
    }

    // ── TASK 1: remote video letterboxing ───────────────────────────────────────

    /** Shared math behind [applyVideoAspectRatio] (1:1) and [applyTileAspectRatio]
     *  (PHASE 3C, per grid tile) — resizes [view] to the largest centered rect that
     *  fits [parent] while preserving the decoder's actual width:height ratio —
     *  letterbox, never crop or stretch. Safe to call with a size before layout has
     *  happened; the caller's own layout-change listener re-runs it once real
     *  dimensions are available. Returns the (width, height) actually applied, or
     *  null if it couldn't run yet (no valid video size, view, or parent layout) —
     *  callers that log a resize (see [applyTileAspectRatio]) key off this return
     *  value. */
    private fun letterbox(view: SurfaceView?, parent: FrameLayout, videoWidth: Int, videoHeight: Int): Pair<Int, Int>? {
        if (videoWidth <= 0 || videoHeight <= 0) return null
        val parentWidth = parent.width
        val parentHeight = parent.height
        if (parentWidth <= 0 || parentHeight <= 0) return null // not laid out yet

        val parentRatio = parentWidth.toFloat() / parentHeight.toFloat()
        val videoRatio = videoWidth.toFloat() / videoHeight.toFloat()
        val targetWidth: Int
        val targetHeight: Int
        if (videoRatio > parentRatio) {
            // Video is relatively wider than the parent — fit width, letterbox top/bottom.
            targetWidth = parentWidth
            targetHeight = (parentWidth / videoRatio).toInt()
        } else {
            // Video is relatively taller/narrower — fit height, letterbox left/right.
            targetWidth = (parentHeight * videoRatio).toInt()
            targetHeight = parentHeight
        }

        val v = view ?: return null
        val params = v.layoutParams as? FrameLayout.LayoutParams
            ?: FrameLayout.LayoutParams(targetWidth, targetHeight)
        params.width = targetWidth
        params.height = targetHeight
        params.gravity = Gravity.CENTER
        v.layoutParams = params
        return targetWidth to targetHeight
    }

    private fun applyVideoAspectRatio(videoWidth: Int, videoHeight: Int) {
        remoteVideoWidth = videoWidth
        remoteVideoHeight = videoHeight
        letterbox(mediaRemoteView, videoFrame, videoWidth, videoHeight)
    }

    /** PHASE 3C: per-tile letterboxing — same shared [letterbox] math as the 1:1
     *  screen, targeting one grid tile's own SurfaceView/container instead of the
     *  old single active-speaker view. Re-run on every resize of that tile's own
     *  cell (see the addOnLayoutChangeListener in [createGroupTile]) and every time
     *  [OfflineMediaTransport.onGroupTileVideoSize] fires for that sender. */
    private fun applyTileAspectRatio(nodeId: Long, videoWidth: Int, videoHeight: Int) {
        val tile = groupTiles[nodeId] ?: return
        tile.videoWidth = videoWidth
        tile.videoHeight = videoHeight
        val rect = letterbox(tile.surfaceView, tile.container, videoWidth, videoHeight)
        if (rect != null) {
            Log.d("OFFTRACE", "VIDEO: tile ${shortId(nodeId)} sized ${videoWidth}x${videoHeight} -> rect ${rect.first}x${rect.second}")
        }
    }

    /** Called on hangup/link-lost so a stale letterboxed size doesn't bleed into the
     *  next call before its first video frame arrives. */
    private fun resetVideoAspectRatio() {
        remoteVideoWidth = 0
        remoteVideoHeight = 0
        mediaRemoteView?.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        )
    }

    // ── CALL MODES: per-mode UI ──────────────────────────────────────────────────

    /** VIDEO = current call screen (camera views + chat overlay). AUDIO = no remote/
     *  local camera views, peer name + timer shown instead, chat overlay unchanged.
     *  CHAT = same as AUDIO minus the camera views, plus the chat overlay is expanded
     *  to fill the whole screen instead of floating over video — this is also what
     *  the group-chat-only screen uses (PHASE 3). */
    private fun applyUiForMode(mode: OfflineMediaTransport.CallMode) {
        val density = resources.displayMetrics.density
        val showVideo = mode == OfflineMediaTransport.CallMode.VIDEO
        val fullScreenChat = mode == OfflineMediaTransport.CallMode.CHAT

        mediaRemoteView?.visibility = if (showVideo) View.VISIBLE else View.GONE
        modeInfoBar.visibility = if (showVideo) View.GONE else View.VISIBLE
        peerNameText.text = connectedPeerName.ifEmpty { "Peer" }

        bottomPanel.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            if (fullScreenChat) FrameLayout.LayoutParams.MATCH_PARENT else FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM }

        chatListView.layoutParams = if (fullScreenChat) {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        } else {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (200 * density).toInt())
        }
    }

    /** Neutral baseline restored on return-to-roster/leave-group so a later call in a
     *  different mode doesn't inherit stale layout. */
    private fun resetCallModeUi() {
        val density = resources.displayMetrics.density
        mediaRemoteView?.visibility = View.VISIBLE
        modeInfoBar.visibility = View.GONE
        bottomPanel.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM }
        chatListView.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (200 * density).toInt())
    }

    private fun startCallTimer() {
        callStartElapsedMs = SystemClock.elapsedRealtime()
        val r = object : Runnable {
            override fun run() {
                val secs = (SystemClock.elapsedRealtime() - callStartElapsedMs) / 1000
                callTimerText.text = String.format("%02d:%02d", secs / 60, secs % 60)
                timerHandler.postDelayed(this, 1000)
            }
        }
        timerRunnable = r
        timerHandler.post(r)
    }

    private fun stopCallTimer() {
        timerRunnable?.let { timerHandler.removeCallbacks(it) }
        timerRunnable = null
        callTimerText.text = "00:00"
    }

    // ── TASK 2: chat overlay ─────────────────────────────────────────────────────

    private inner class ChatAdapter : BaseAdapter() {
        override fun getCount() = chatMessages.size
        override fun getItem(position: Int): ChatEntry = chatMessages[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val entry = chatMessages[position]
            val density = resources.displayMetrics.density
            val mode = currentTopoMode()
            val bubbleFg = if (entry.fromMe) TopoPalette.onAccent(mode) else TopoPalette.textPrimary(mode)
            val bubble = LinearLayout(this@OfflineCallActivity).apply {
                orientation = LinearLayout.VERTICAL
                // TOPO 1.4: was raw Color.WHITE/two raw Color.argb literals,
                // never routed through night mode at all — my-bubble uses
                // accent (this app's one "it's mine/active" colour), their-
                // bubble uses bgRaised, both text in onAccent/textPrimary
                // respectively so contrast holds in every mode.
                setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
                setBackgroundColor(if (entry.fromMe) TopoPalette.accent(mode) else TopoPalette.bgRaised(mode))
                // Step 3 (diagnostic follow-up): an attachment entry renders
                // a kind-specific row instead of entry.text — same bubble,
                // same ListView/adapter, just different content. entry.text
                // still carries a sensible fallback label (unused by this
                // branch, but kept for any other reader of chatMessages,
                // e.g. a future export).
                val ref = entry.attachment
                if (ref != null) {
                    if (!entry.fromMe) {
                        addView(TextView(this@OfflineCallActivity).apply {
                            text = "[Group] ${nameForGroupParticipant(ref.senderNodeId)}"
                            textSize = 12f
                            setTextColor(bubbleFg)
                        })
                    }
                    addView(buildAttachmentRow(ref, entry, bubbleFg))
                } else {
                    addView(TextView(this@OfflineCallActivity).apply { text = entry.text; setTextColor(bubbleFg) })
                }
                // TOPO 3.2: transport badge — see ChatEntry.transport's doc
                // for why this is always "Wi-Fi Direct" in this app today,
                // not a guess.
                addView(TextView(this@OfflineCallActivity).apply {
                    text = "via ${entry.transport}"
                    textSize = 10f
                    alpha = 0.7f
                    setTextColor(bubbleFg)
                })
            }
            val row = FrameLayout(this@OfflineCallActivity).apply {
                setPadding((6 * density).toInt(), (2 * density).toInt(), (6 * density).toInt(), (2 * density).toInt())
            }
            row.addView(
                bubble,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = if (entry.fromMe) Gravity.END else Gravity.START
                }
            )
            return row
        }
    }

    private fun appendChatMessage(
        text: String,
        fromMe: Boolean,
        attachment: AttachmentRef? = null
    ) {
        chatMessages.add(ChatEntry(text, fromMe, attachment = attachment))
        chatAdapter.notifyDataSetChanged()
        chatListView.post { if (chatAdapter.count > 0) chatListView.setSelection(chatAdapter.count - 1) }
        // TOPO PART B1: Messages tab's OWN ListView/adapter instance (never
        // callScreen's) reads the SAME chatMessages data — this is data-layer
        // reuse, not a shared view; see messagesChatListView's doc.
        if (::messagesChatAdapter.isInitialized) {
            messagesChatAdapter.notifyDataSetChanged()
            messagesChatListView.post { if (messagesChatAdapter.count > 0) messagesChatListView.setSelection(messagesChatAdapter.count - 1) }
        }
    }

    /** TOPO PART B1: the actual send — shared by chatInput's send button
     *  (callScreen) and messagesComposerInput's send button (Messages tab).
     *  Neither view is touched here; only the string and the result are
     *  the caller's concern (each clears its OWN EditText). */
    private fun sendChatText(text: String): Boolean {
        if (text.isEmpty()) return false
        val sent = if (isGroupChatScreen) {
            mediaTransport?.sendGroupChat(text) ?: false
        } else {
            mediaTransport?.sendChat(text) ?: false
        }
        if (!sent) {
            Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show()
            return false
        }
        appendChatMessage(text, fromMe = true)
        val threadKey = if (isGroupChatScreen) MeshFrame.BROADCAST_ID else connectedPeerId
        if (threadKey != null) recordThreadPreview(threadKey, text, fromMe = true)
        return true
    }

    private fun onSendChatClicked() {
        val text = chatInput.text?.toString()?.trim().orEmpty()
        if (sendChatText(text)) chatInput.setText("")
    }

    /** TOPO PHASE 3.1: updates [threadPreviews] for [threadKey] — ADDITIVE only,
     *  never touches chatMessages/chatAdapter (appendChatMessage's own job,
     *  unchanged). Unread only increments for an INCOMING message on a
     *  thread that isn't the one currently open (see [openThreadKey]'s doc);
     *  an outgoing message never counts as unread for its own thread. */
    private fun recordThreadPreview(threadKey: Long, text: String, fromMe: Boolean) {
        val preview = threadPreviews.getOrPut(threadKey) { ThreadPreview(text, fromMe, System.currentTimeMillis(), 0) }
        preview.lastText = text
        preview.lastFromMe = fromMe
        preview.lastAtMs = System.currentTimeMillis()
        if (!fromMe && openThreadKey != threadKey) preview.unread++
        if (messagesScreenView != null) refreshMessagesScreen()
    }

    /** PHASE 3: routes an incoming chat frame to the overlay — [isGroup] messages are
     *  labeled so they're not mistaken for the current 1:1 call partner's message. */
    private fun onTransportChatMessage(fromNodeId: Long, fromName: String, text: String, isGroup: Boolean) {
        val label = if (isGroup) "[Group] $fromName" else fromName
        appendChatMessage("$label: $text", fromMe = false)
        val threadKey = if (isGroup) MeshFrame.BROADCAST_ID else fromNodeId
        recordThreadPreview(threadKey, text, fromMe = false)
    }

    /** Called on return-to-roster/leave-group — chat is in-memory for the
     *  call/session's duration only. */
    private fun resetChat() {
        chatMessages.clear()
        chatAdapter.notifyDataSetChanged()
        if (::messagesChatAdapter.isInitialized) messagesChatAdapter.notifyDataSetChanged()
    }

    // ── TOPO PART B1: Messages tab's own open-thread session — NEVER
    // touches callScreen/hangupButton/callTimerText/videoFrame/bottomPanel.
    // Establishes the DATA-layer chat session only (mirrors startDirectCall/
    // openGroupChat minus every call-screen-visibility/timer side effect). ──

    /** [member]=null means the group thread. Switching to a DIFFERENT
     *  thread than the one already open clears chatMessages first (same
     *  ephemeral, session-scoped chat history this app has always had —
     *  see ChatEntry's original doc — just applied consistently per
     *  thread instead of per call). Re-opening the SAME thread keeps
     *  whatever's already on screen. */
    private fun openMessageThread(threadKey: Long, name: String, member: RoutingTable.Member?) {
        if (openThreadKey != threadKey) {
            resetChat()
            if (member != null) {
                isGroupChatScreen = false
                connectedPeerName = member.name
                connectedPeerId = member.nodeId
                // CallMode.CHAT never touches audio/camera (showVideo=false,
                // see applyUiForMode) — this only sets activeCallPeerId so
                // mediaTransport.sendChat(...) has a destination; no
                // call-screen/timer side effect happens here.
                mediaTransport?.placeCall(member.nodeId, member.name, OfflineMediaTransport.CallMode.CHAT)
            } else {
                isGroupChatScreen = true
                connectedPeerName = "Group chat"
                connectedPeerId = MeshFrame.BROADCAST_ID
            }
            openThreadKey = threadKey
        }
        messagesThreadNameText.text = name
        threadPreviews[threadKey]?.unread = 0
        // Step 2 (diagnostic follow-up): voice notes are broadcast-only
        // (sendVoiceNote has no 1:1 targeting) — only the Group thread.
        if (::voiceNoteHoldButton.isInitialized) {
            voiceNoteHoldButton.visibility = if (isGroupChatScreen) View.VISIBLE else View.GONE
        }
        // Step 4: sendAttachment is broadcast-only too — same gate.
        if (::imageAttachmentButton.isInitialized) {
            imageAttachmentButton.visibility = if (isGroupChatScreen) View.VISIBLE else View.GONE
        }
        // Step 5: same gate.
        if (::documentAttachmentButton.isInitialized) {
            documentAttachmentButton.visibility = if (isGroupChatScreen) View.VISIBLE else View.GONE
        }
        // Step 6: same gate.
        if (::locationAttachmentButton.isInitialized) {
            locationAttachmentButton.visibility = if (isGroupChatScreen) View.VISIBLE else View.GONE
        }
        // Step 7: same gate.
        if (::contactAttachmentButton.isInitialized) {
            contactAttachmentButton.visibility = if (isGroupChatScreen) View.VISIBLE else View.GONE
        }
        messagesListBody.visibility = View.GONE
        messagesThreadView.visibility = View.VISIBLE
        updateForegroundState() // PART 1 (batch A): messagesThreadView is now a full-bleed root sibling
        refreshMessagesScreen()
    }

    private fun closeOpenMessageThread() {
        if (!::messagesThreadView.isInitialized || messagesThreadView.visibility != View.VISIBLE) return
        messagesThreadView.visibility = View.GONE
        messagesListBody.visibility = View.VISIBLE
        openThreadKey = null
        updateForegroundState()
        refreshMessagesScreen()
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun requiredPermissions(): List<String> {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        // PHASE 6 TRACK C: BLE presence/beacon — runtime permissions only exist
        // from API 31; below that, plain manifest-level BLUETOOTH/BLUETOOTH_ADMIN
        // (maxSdkVersion=30) plus the already-requested ACCESS_FINE_LOCATION above
        // cover it, nothing further to request here.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        return perms
    }

    private fun onSearchClicked() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            // PART 4.3: a rationale first, in this app's own dialog — the
            // user just tapped "Search," so the ask is already in context,
            // but the system dialog(s) themselves only appear after they
            // tap "Continue" here.
            AlertDialog.Builder(this)
                .setTitle("Find nearby devices")
                .setMessage(
                    "OpenCall needs Location, Nearby Devices, Microphone, and Camera " +
                    "access to discover and connect to nearby phones for offline calls."
                )
                .setPositiveButton("Continue") { _, _ ->
                    ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERM_REQUEST)
                }
                .setNegativeButton("Not now", null)
                .show()
            return
        }
        proceedToDiscovery()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // PART 0: a permission dialog can be answered well after the user
        // has left this screen (backgrounded the app, pressed back) — the
        // callback still fires on this Activity instance regardless.
        // proceedToDiscovery() below touches Views (statusText etc.) built
        // in buildUi(); never do that once this Activity is finishing/torn
        // down, and never before those Views exist in the first place.
        if (isFinishing || isDestroyed || !::statusText.isInitialized) return
        if (requestCode == PERM_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                proceedToDiscovery()
            } else {
                Toast.makeText(
                    this,
                    "Location, Nearby Devices, Microphone, and Camera permissions are required for offline calls",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }
        // Step 7: a grant here resumes straight into the picker dialog —
        // the tap that triggered the request is otherwise lost.
        if (requestCode == REQUEST_CONTACTS_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                showContactPickerDialog()
            } else {
                Toast.makeText(this, "Contacts permission is required to share a contact", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** CASE 6 fix: permissions being granted isn't enough — the system Location toggle
     *  must also be on, or Wi-Fi Direct discovery/connection silently never completes. */
    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            val mode = Settings.Secure.getInt(contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF)
            mode != Settings.Secure.LOCATION_MODE_OFF
        }
    }

    private fun proceedToDiscovery() {
        // PART "HASSLE-FREE JOIN" 2.2: permissions just got granted (this
        // is only reached from onSearchClicked/onRequestPermissionsResult
        // once they are) — (re)start BLE now that MeshBleBeacon.start()'s
        // own permission checks will actually pass, so a "Start a group"
        // or Nearby-tab visit before the FIRST grant isn't stuck degraded
        // forever after.
        startPreConnectionBleDiscovery()
        if (!isLocationEnabled()) {
            Log.e("OFFTRACE", "location services OFF — blocking discovery")
            Toast.makeText(this, "Turn on Location — required for Wi-Fi Direct", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        requestDiscoveryWhenReady()
    }

    // ── Discovery ─────────────────────────────────────────────────────────────

    private fun onP2pStateChanged(enabled: Boolean) {
        p2pEnabled = enabled
        if (enabled && searchPending) {
            searchPending = false
            startDiscovery()
        }
    }

    /** Permissions are granted at this point; only proceed once P2P itself is reported enabled. */
    private fun requestDiscoveryWhenReady() {
        if (p2pEnabled) {
            startDiscovery()
        } else {
            searchPending = true
            statusText.text = "Waiting for Wi-Fi Direct to turn on..."
        }
    }

    private fun startDiscovery() {
        statusText.text = "Searching for nearby devices..."
        // BUG (MAKE THE INVITE ACTUALLY TRANSMIT) FIX PART 2.3: used to
        // start only once a group had already formed (see
        // onConnectionChangedInternal's GO branch) — meaning nothing kept
        // this process alive/unkillable during the whole discovery-and-
        // invite window an INCOMING invite can arrive in. startOfflineCallService()
        // is idempotent (ACTION_START on an already-foregrounded service is
        // a harmless no-op), so starting it here too costs nothing.
        startOfflineCallService()
        // BUG (DISCOVERY IS ONE-SHOT) FIX: used to call wifiDirect.startDiscovery()
        // and wifiDirect.discoverServices() side by side — two independent,
        // unchained scan triggers 1ms apart in the established capture,
        // wpa_supplicant rejecting the second ("Reject scan trigger since one
        // is already pending"). startDiscovery() alone now runs the whole
        // service-discovery-then-peer-discovery chain itself, and keeps
        // re-issuing it on a 12s cadence for as long as this device stays in
        // DISCOVERING (see WifiDirectManager.runDiscoveryPass/armDiscoveryCadence).
        // LEAK (PART 1.3): result callback — MeshTransport.startDiscovery()
        // has no result path; dropping this Toast would be a behavior change.
        wifiDirect.startDiscovery { ok ->
            if (!ok) runOnUiThread {
                Toast.makeText(this, "Discovery failed to start", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // LEAK (PART 1.3): onPeersChanged(WifiP2pDeviceList) — see the identical
    // leak note at this method's wiring site in onCreate.
    private fun onPeersChanged(list: WifiP2pDeviceList) {
        devices = list.deviceList.toList()
        trackPeerStatusTransitions(devices)
        refreshPeerListUi() // -> refreshNearbyDevices(), synchronously repopulates nearbyDevices before the next line reads it
        // FIX 1d: reads visibleNearbyDevices() — the SAME function the rows
        // come from — instead of the raw OS-level `devices` list. Used to
        // read devices.size here while the rows read nearbyDevices
        // (populated only once a DNS-SD TXT record arrived): "1 device
        // found, 0 rows shown" was two collections disagreeing, not a
        // display bug.
        val rendered = visibleNearbyDevices().size
        statusText.text = if (rendered == 0) "No devices found yet" else "$rendered device(s) found"
        // FIX 6b: refresh the roster's "Add to group" rows if we're the GO and
        // currently showing that screen — discovery keeps running in the background
        // (see onConnectionChangedInternal) specifically to keep this list current.
        if (isLocalGroupOwner && groupScreen.visibility == View.VISIBLE) {
            renderRosterList()
        }
        // PHASE 6 TRACK E: see autoInviteDuringElection's doc — no-op unless
        // this device just became GO via re-election and is waiting to reclaim
        // its old party.
        autoInviteDuringElection()
    }

    private fun peerStatusName(status: Int?): String = when (status) {
        null -> "unknown"
        WifiP2pDevice.AVAILABLE -> "available"
        WifiP2pDevice.INVITED -> "invited"
        WifiP2pDevice.CONNECTED -> "connected"
        WifiP2pDevice.FAILED -> "failed"
        WifiP2pDevice.UNAVAILABLE -> "unavailable"
        else -> "status$status"
    }

    /** BUG (MAKE THE INVITE ACTUALLY TRANSMIT) FIX PART 1.3/2: see
     *  [lastKnownPeerStatus]'s doc — this is the provision-discovery/
     *  GO-negotiation progress proxy, AND item 2's "did someone invite US"
     *  detection in one pass: a transition INTO INVITED for a peer this
     *  device did NOT itself just call connect()/host() toward
     *  ([pendingInviteTargetDevice]) is, by construction, an INCOMING
     *  invite from them. */
    private fun trackPeerStatusTransitions(current: List<WifiP2pDevice>) {
        current.forEach { d ->
            val prev = lastKnownPeerStatus[d.deviceAddress]
            if (prev == d.status) return@forEach
            lastKnownPeerStatus[d.deviceAddress] = d.status
            Log.d(
                "OFFTRACE",
                "P2P: peer status ${d.deviceAddress} ${peerStatusName(prev)} -> ${peerStatusName(d.status)}"
            )
            if (d.status == WifiP2pDevice.INVITED && pendingInviteTargetDevice?.deviceAddress != d.deviceAddress) {
                handleIncomingInvite(d)
            }
        }
    }

    /** BUG (MAKE THE INVITE ACTUALLY TRANSMIT) FIX PART 2: surfaces an
     *  incoming P2P invite — a heads-up notification (works even
     *  backgrounded, see [OfflineCallService.notifyIncomingInvite]'s doc on
     *  why the foreground service being started from the discovery window
     *  rather than only post-formation matters here) plus, if this Activity
     *  is actually on screen right now, the same Accept/Decline dialog
     *  immediately. Android's own system PBC dialog may ALSO appear and
     *  auto-complete the connection before either of these get answered —
     *  logged, not double-connected, on that outcome (see
     *  [onConnectionChangedInternal]'s groupFormed observation, which is
     *  what actually ends this waiting state either way). */
    private fun handleIncomingInvite(device: WifiP2pDevice) {
        if (!announcedIncomingP2pInvite.add(device.deviceAddress)) return
        val name = dnsSdNamesByAddress[device.deviceAddress] ?: device.deviceName?.takeIf { it.isNotBlank() } ?: device.deviceAddress
        OfflineCallService.notifyIncomingInvite(applicationContext, name, device.deviceAddress)
        if (activityResumed) {
            AlertDialog.Builder(this)
                .setTitle("$name wants to connect")
                .setMessage("Join their Wi-Fi Direct group?")
                .setCancelable(false)
                .setPositiveButton("Accept") { _, _ -> acceptIncomingInvite(device) }
                .setNegativeButton("Decline") { _, _ -> announcedIncomingP2pInvite.remove(device.deviceAddress) }
                .show()
        }
    }

    /** BUG (MAKE THE INVITE ACTUALLY TRANSMIT) FIX PART 2.2: connects back
     *  toward the initiator with the same legacy PBC config connect() itself
     *  uses (see WifiDirectManager.connect's negotiate-path doc) — if
     *  Android's own system PBC flow already completed this negotiation
     *  before the user tapped Accept, canBeginConnect's state gate simply
     *  rejects this as a no-op (state is already CONNECTING/CONNECTED by
     *  then), logged, not double-connected. */
    private fun acceptIncomingInvite(device: WifiP2pDevice) {
        if (isLocalGroupOwner || connectRequested) {
            Log.d("OFFTRACE", "P2P: accept invite from=${device.deviceAddress} — already busy, Android's own PBC flow likely already completed it")
            return
        }
        connectRequested = true
        formedAtLeastOnceThisAttempt = false
        Log.d("OFFTRACE", "INVITE: path=connect-accept peer=${device.deviceAddress} transmitted=true reason=accept-incoming")
        // LEAK (PART 1.3): raw WifiP2pDevice.
        wifiDirect.connect(device, device.deviceAddress) { ok ->
            runOnUiThread { if (!ok) connectRequested = false }
        }
    }

    /** BUG 2 FIX: prefers the (unverified — see [dnsSdNamesByAddress]'s doc)
     *  DNS-SD name so a name set before pairing is visible here; falls back to
     *  the raw OS-level Wi-Fi Direct device name only when no TXT record has
     *  arrived yet for that address. Called both after an ordinary peers-changed
     *  broadcast and whenever a TXT record arrives on its own, so an
     *  already-visible row upgrades in place without waiting for the next scan. */
    /** PHASE 8 TRACK A: superseded — nearby-device rendering and invite taps
     *  are now driven entirely by refreshNearbyDevices()/sendInvite(), see that
     *  section's doc. Kept only as the trigger point onPeersChanged already
     *  calls (renamed in spirit, unchanged call sites). */
    private fun refreshPeerListUi() {
        refreshNearbyDevices()
    }

    private fun scheduleGroupFormationTimeout() {
        if (groupFormationTimeoutRunnable != null) return // already waiting on one
        val runnable = Runnable {
            groupFormationTimeoutRunnable = null
            connectRequested = false
            peerListView.isEnabled = true
            Log.e("OFFTRACE", "group formation TIMEOUT after 30s (formedAtLeastOnce=$formedAtLeastOnceThisAttempt)")
            statusText.text = "Connection timed out"
            // FIX 2d: this path is now only reachable when this attempt
            // never saw a real formation at all (a real "formed then
            // vanished" sequence routes through handleRealGroupTeardown's
            // generation check instead, before this timeout ever fires) —
            // kept as a defensive distinction anyway rather than assuming
            // that ordering can never change.
            errorText.text = if (formedAtLeastOnceThisAttempt) {
                "Connected, but the group disappeared"
            } else {
                "Could not form Wi-Fi Direct group (timed out)"
            }
            errorText.visibility = View.VISIBLE
        }
        groupFormationTimeoutRunnable = runnable
        timeoutHandler.postDelayed(runnable, GROUP_FORMATION_TIMEOUT_MS)
    }

    private fun cancelGroupFormationTimeout() {
        groupFormationTimeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }
        groupFormationTimeoutRunnable = null
    }

    /** CASE 2 fix: resets connect-attempt tracking; call on every disconnect/teardown path. */
    private fun resetConnectionAttemptState() {
        connectRequested = false
        peerListView.isEnabled = true
        cancelGroupFormationTimeout()
        explicitJoinAttempted.clear()
        announcedIncomingP2pInvite.clear()
        pendingInviteTargetNodeId = null
        pendingInviteTargetDevice = null
        connectFallbackInProgressForNodeId = null
        stopWaitingForExplicitJoin()
    }

    // ── Connection → local signaling → mesh transport ───────────────────────────

    /** STABILITY AUDIT 1c: guards a Toast/AlertDialog raised from a transport
     *  callback's runOnUiThread block. Those are queued from a background
     *  thread (the media read loop, a WifiP2pManager callback) and can still
     *  fire after this Activity has started finishing or been destroyed —
     *  the user backing out mid-call, or the system recreating the Activity,
     *  races exactly the kind of queued Handler message this guards. Raising
     *  a Toast/AlertDialog against a dead window throws
     *  WindowManager.BadTokenException, crashing the whole process. Only
     *  wraps the six transport-callback sites that actually raise a
     *  Toast/AlertDialog (finding 1c) — the ~30 runOnUiThread bodies at
     *  5701-5933 are otherwise unchanged (see finding 1d; not fixed here). */
    private fun runIfActive(action: () -> Unit) {
        if (isFinishing || isDestroyed) return
        action()
    }

    private fun onConnectionChanged(info: WifiP2pInfo) {
        // FIX 4: this runs directly as a WifiP2pManager callback on the main thread —
        // any uncaught exception here kills the whole process. Never let one escape.
        try {
            onConnectionChangedInternal(info)
        } catch (e: Exception) {
            Log.e("OFFTRACE", "FATAL onConnectionChanged: ${e.javaClass.simpleName}: ${e.message}", e)
            errorText.text = "Internal error: ${e.javaClass.simpleName}: ${e.message}"
            errorText.visibility = View.VISIBLE
        }
    }

    private fun onConnectionChangedInternal(info: WifiP2pInfo) {
        Log.d("OFFTRACE", "connInfo groupFormed=${info.groupFormed} isGO=${info.isGroupOwner} goAddr=${info.groupOwnerAddress}")
        // IDLE-SESSION FIX: updated unconditionally, BEFORE the "ignoring stale/
        // teardown connInfo" early return below — that return used to make a real
        // mid-session group breakage indistinguishable from noise as far as any other
        // code was concerned. mediaTransport's reconnect logic reads this via the
        // isGroupFormed lambda passed to its constructor.
        currentGroupFormed = info.groupFormed
        if (!info.groupFormed) {
            // FIX 2: real-vs-stale by GENERATION, not connectRequested. The old
            // check ("if (!connectRequested) treat as stale") only worked for
            // the CLIENT that had just called connect() — the GO (which never
            // calls connect(); it creates/hosts) always had
            // connectRequested==false, so a REAL teardown of a group the GO was
            // hosting was permanently misclassified as stale noise: logged,
            // then silently dropped forever — no leaveGroup(), no socket
            // close, no recovery. See formedGeneration's field doc for the
            // exact rule.
            if (isRealGroupTeardown(formedGeneration)) {
                val goneGeneration = formedGeneration
                formedGeneration = 0
                Log.d("OFFTRACE", "P2P: REAL teardown, generation=$goneGeneration")
                handleRealGroupTeardown()
                return
            }
            if (!connectRequested) {
                Log.d("OFFTRACE", "ignoring stale connInfo (generation=$groupGeneration, nothing formed)")
                return
            }
            Log.d("OFFTRACE", "waiting for group formation...")
            scheduleGroupFormationTimeout()
            return
        }
        cancelGroupFormationTimeout()
        // FIX 5: clear the connect-attempt flag now that formation succeeded, so a later
        // groupFormed=false flap (e.g. a transient blip) can't re-arm the 30s timeout mid-call.
        connectRequested = false
        formedAtLeastOnceThisAttempt = true // FIX 2d: this attempt DID reach formation
        // BUG (GROUP FORMS, NOBODY JOINS) FIX: used to unconditionally call
        // stopWaitingForExplicitJoin() here, clearing "gp" the instant THIS
        // device's own group formed — 664ms after it was published in the
        // established capture, with clients=0 forever after. Group formation
        // is a signal about this device's own link, not about whether the
        // invited peer ever associated; gp now stays set (re-published, not
        // cleared, in the isLocalGroupOwner branch below) until
        // onGroupClientsChanged sees that peer actually join, the 60s invite
        // deadline expires, or the attempt is abandoned. On a JOINER this is
        // already a no-op (groupWaitingForPeerAddress is never set on that side).
        // FIX 2: a new generation formed — checked before the "already wired
        // up" early return below so re-formation after a real teardown (new
        // generation) is always tracked, even if signaling/mediaTransport
        // somehow survived (they shouldn't, after handleRealGroupTeardown —
        // this just keeps the generation count correct regardless).
        groupGeneration++
        formedGeneration = groupGeneration
        pauseNearbyRefreshLoop() // FIX 1c: group is live — stop scanning for new peers
        updateRadarSweeping() // FIX 4e: group live — radar stops sweeping too
        peerListView.isEnabled = true
        if (signaling != null) return // already wired up for this group session

        isLocalGroupOwner = info.isGroupOwner
        statusText.text = "Connected — joining group..."
        searchScreen.visibility = View.GONE
        groupScreen.visibility = View.VISIBLE
        errorText.visibility = View.GONE

        if (isLocalGroupOwner) {
            // FIX 6d: log/stash the group's SSID — foundation for a legacy-join
            // fallback later, not consumed anywhere yet.
            // CAP PROBE: temporary read-only diagnostic riding the same callback —
            // see CapabilityProbe.kt.
            // LEAK (PART 1.3): requestGroupInfo(WifiP2pGroup) — see armInviteDeadline's identical leak note.
            wifiDirect.requestGroupInfo { group ->
                CapabilityProbe.logGroupFormedCapabilities(group)
                if (group != null) {
                    // BUG (FORCE 2.4GHZ) FIX: confirms the band createGroup actually
                    // came up on — GROUP_OWNER_BAND_2GHZ is now requested in
                    // WifiDirectManager.host() instead of AUTO (which picked 5220MHz
                    // on this hardware).
                    Log.d("OFFTRACE", "CAP: group ssid=${group.networkName} band=${group.frequency} clients=${group.clientList.size}")
                    // BUG (GROUP FORMS, NOBODY JOINS) FIX: the framework's own
                    // WifiP2pGroup is the source of truth for what this group
                    // ACTUALLY is — stash it and re-publish over DNS-SD so a
                    // joiner can use the real name/passphrase instead of
                    // deriving one. registerDnsSdLocalService() reads
                    // groupWaitingForPeerAddress for "gp" itself, so this
                    // re-publish carries the SAME gp value as before (still
                    // set — see the removed stopWaitingForExplicitJoin() call
                    // above), not a cleared one.
                    //
                    // SECURITY: DNS-SD TXT is broadcast UNENCRYPTED — anyone
                    // in Wi-Fi Direct range can read "pw" off the air, same
                    // exposure as any other unauthenticated Wi-Fi Direct
                    // invite. This is a conscious tradeoff: it only exposes
                    // the WFD-layer group password, not this app's actual
                    // security, which rests on the Ed25519 HELLO/signing
                    // layer that runs after this link exists (see
                    // MeshSigner's class doc) — never on this TXT record
                    // being secret.
                    hostedGroupNetworkName = group.networkName
                    hostedGroupPassphrase = group.passphrase
                    registerDnsSdLocalService()
                    // PART "HASSLE-FREE JOIN" 2.1/2.3: advertise "group
                    // open" over BLE the moment this device is confirmed
                    // hosting — cleared again the instant capacity is
                    // reached (WIFI_DIRECT_GO_CLIENT_CEILING, the known
                    // WFD GO client ceiling — a BLE-advertising concern
                    // only, independent of OfflineMediaTransport's own
                    // mesh-level MAX_GROUP_PARTICIPANTS enforcement) or on
                    // leave-group (see leaveGroup()).
                    mediaTransport?.bleBeacon?.setGroupOpen(group.clientList.size < WIFI_DIRECT_GO_CLIENT_CEILING)
                }
            }
            // FIX 1 (was FIX 6b "keep discovery running..."): this is the
            // EXACT call the bug report traced — discoverPeers()-restarting
            // logic (clearStaleGroupThenDiscover) used to call an
            // unconditional removeGroup() first, so the GO calling
            // startDiscovery() moments after forming a group destroyed the
            // very group it had just formed (see the established logcat
            // sequence: AP-STA-CONNECTED -> group ssid clients=1 ->
            // removeGroup (pre-discovery) -> P2P-GROUP-REMOVED). WifiDirect-
            // Manager's guardedRemoveGroup now blocks that outcome
            // structurally even if this call still fired, but per FIX 1c
            // ("discovery must not restart while a group is live") the
            // right fix is to not even ask: a live GO does not restart
            // peer discovery. "Add to group" still works for any peer
            // already known (WFD or BLE) before/after this group formed —
            // see refreshNearbyDevices/sendInvite — new peers are found
            // again once the group tears down and discovery resumes
            // (resumeNearbyRefreshLoop, in handleRealGroupTeardown).
        }

        // FIX: LocalSignaling is now just the underlying-connection hangup/keepalive
        // channel (see LocalSignaling.kt) — losing it means the whole WiFi Direct group
        // connection is gone, not just one call (see leaveGroup()).
        val local = LocalSignaling(info.isGroupOwner, info.groupOwnerAddress)
        signaling = local
        local.onConnected = { statusText.text = "Signaling connected" }
        local.onError = { err -> statusText.text = "Signaling error: $err" }
        // IDLE-SESSION FIX: a signaling-channel keepalive timeout (now 45s, up from
        // 15s — see LocalSignaling) no longer tears the group down by itself if the
        // MEDIA channel still looks alive — that channel has its own reconnect logic
        // (see OfflineMediaTransport.attemptClientReconnect) and is the better signal
        // for whether the underlying WiFi Direct link is actually still usable. Only
        // once the media transport has ALSO given up (mediaLinkAlive == false, set by
        // onLinkLost below) does a signaling loss actually end the group.
        local.onPeerGone = { reason ->
            runOnUiThread {
                if (mediaLinkAlive) {
                    Log.w("OFFTRACE", "signaling lost ($reason) but media link still alive — not leaving group")
                } else {
                    leaveGroup("signaling: $reason")
                }
            }
        }
        local.onDegraded = { silentMs ->
            runOnUiThread {
                Log.w("OFFTRACE", "SIG: link degraded, ${silentMs / 1000}s silent")
                statusText.text = "Reconnecting…"
            }
        }
        local.onRecovered = {
            runOnUiThread {
                // PHASE 1.2: same divergent "Group: ${roster.size} member(s)"
                // pattern as updateRosterUi had — routed through the same
                // partyStatusLine() so this recovery path can't reintroduce
                // the two-source bug on its own.
                statusText.text = if (roster.isNotEmpty()) partyStatusLine() else "Signaling connected"
            }
        }
        local.start()

        mediaLinkAlive = true
        // FIX 3: must run before constructing a new transport — if the system
        // destroyed-and-recreated this Activity while backgrounded (see FIX 4's
        // isFinishing-gated onDestroy), an old transport instance may still be alive
        // and bound to port 8889; constructing a second one without stopping the
        // first would BindException the new one's accept loop into never starting,
        // silently orphaning new joins on a transport nobody's UI is listening to.
        OfflineMediaTransport.stopOrphanedInstance()
        val transport = OfflineMediaTransport(
            applicationContext,
            info.isGroupOwner,
            info.groupOwnerAddress,
            localDisplayName = OfflineIdentity.displayName(applicationContext),
            isGroupFormed = { currentGroupFormed },
            onError = { msg ->
                runOnUiThread {
                    // errorText lives inside callScreen only — PHASE 3B added a THIRD
                    // screen (groupCallScreen) that doesn't contain it, so a Toast is
                    // the one channel guaranteed visible regardless of which screen is
                    // currently showing.
                    errorText.text = msg
                    errorText.visibility = View.VISIBLE
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    Log.e("OfflineMediaTransport", msg)
                }
            }
        )
        // PHASE 3: onLinkLost now means the MESH session itself is gone (a client's
        // one uplink dying after exhausting its own reconnect attempts, or the GO's
        // own accept loop failing) — full leaveGroup(), same as the old link-lost
        // teardown used to be for the single 1:1 call.
        transport.onLinkLost = { uiMessage ->
            runOnUiThread {
                mediaLinkAlive = false
                leaveGroup("media link lost", uiMessage)
            }
        }
        // IDLE-SESSION FIX: fires per reconnect attempt (see attemptClientReconnect) —
        // not fatal by itself, onLinkLost still fires if every attempt fails.
        transport.onReconnecting = { attempt, max ->
            runOnUiThread { statusText.text = "Reconnecting to group… ($attempt/$max)" }
        }
        // PHASE 3C: onVideoSize is now 1:1-only — group video sizing arrives per
        // sender via onGroupTileVideoSize instead (multiple simultaneous streams,
        // not one).
        transport.onVideoSize = { w, h -> runOnUiThread { applyVideoAspectRatio(w, h) } }
        transport.onGroupTileVideoSize = { nodeId, w, h -> runOnUiThread { applyTileAspectRatio(nodeId, w, h) } }
        transport.onGroupCallCamState = { nodeId, on ->
            runOnUiThread {
                groupCallCamStates[nodeId] = on
                if (nodeId == mediaTransport?.localNodeId) {
                    groupCallLocalCameraOn = on
                    updateGroupCallControlsBar()
                }
                groupTiles[nodeId]?.let { updateTileContent(nodeId, it) }
            }
        }
        transport.onGroupCallCamDenied = {
            runOnUiThread {
                groupCallLocalCameraOn = false
                updateGroupCallControlsBar()
                runIfActive {
                    Toast.makeText(
                        this,
                        "Too many cameras on (4 max) — ask someone to turn theirs off",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        // PHASE 8 STEP 2: a peer's video decoder failing — show "video
        // unavailable" on their tile, never a silent black surface.
        transport.onGroupTileDegraded = { nodeId, _ ->
            runOnUiThread { groupTiles[nodeId]?.let { updateTileContent(nodeId, it) } }
        }
        // PHASE 8 STEP 4: a peer's tile just entered/left the decode budget.
        transport.onGroupTileBudgetChanged = { nodeId, _ ->
            runOnUiThread { groupTiles[nodeId]?.let { updateTileContent(nodeId, it) } }
        }
        // PHASE 8 STEP 6/STEP 2: sustained outbound backpressure to a peer —
        // reflected in the roster (see updateRosterUi/nameForGroupParticipant
        // callers); no tile-level change, this is a mesh-reachability signal,
        // not a video decode one.
        transport.onPeerReachabilityChanged = { _, _ -> runOnUiThread { updateRosterUi(roster) } }
        transport.onChatMessage = { fromNodeId, fromName, text, isGroup ->
            runOnUiThread { onTransportChatMessage(fromNodeId, fromName, text, isGroup) }
        }
        transport.onPhraseReceived = { fromNodeId, code, seq, carrierId, hopCount ->
            runOnUiThread { onPhraseReceived(fromNodeId, code, seq, carrierId, hopCount) }
        }
        transport.onAttachmentMetaReceived = { state -> runOnUiThread { onAttachmentMetaReceived(state) } }
        transport.onAttachmentDataReceived = { msgId, bytes -> runOnUiThread { onAttachmentDataReceived(msgId, bytes) } }
        // PHASE 3: fires for BOTH the initiator (right after placeCall) and the callee
        // (auto-answered) — see onCallStarted for how each is handled.
        transport.onModeResolved = { peerId, peerName, mode -> runOnUiThread { onCallStarted(peerId, peerName, mode) } }
        transport.onCallEnded = { reason -> runOnUiThread { onCallEndedRemotely(reason) } }
        transport.onCallBusy = { peerName ->
            runOnUiThread {
                runIfActive { Toast.makeText(this, "$peerName is busy", Toast.LENGTH_SHORT).show() }
                returnToRosterScreen()
            }
        }
        transport.onRosterUpdated = { members -> runOnUiThread { updateRosterUi(members) } }
        // PHASE 3B: group call wiring.
        transport.onGroupCallInvite = { _, fromName, mode, callId ->
            runOnUiThread { runIfActive { showGroupCallInviteDialog(fromName, mode, callId) } }
        }
        transport.onGroupCallStarted = { mode, _ -> runOnUiThread { enterGroupCallScreen(mode) } }
        transport.onGroupCallParticipants = { ids ->
            runOnUiThread {
                groupCallParticipants = ids
                syncGroupTiles()
                rebuildGroupCallGrid()
                updateGroupCallStatusText()
            }
        }
        // PHASE 3C: video is no longer gated to a single active speaker — this now
        // ONLY drives the highlight border/indicator on that participant's tile
        // (see updateTileContent), per point 1's "keep TYPE_SPEAKER/VAD ONLY for
        // highlight" requirement. No camera/decoder/letterbox side effects here.
        transport.onGroupCallSpeaker = { nodeId, pinned ->
            runOnUiThread {
                groupCallActiveSpeaker = nodeId
                groupCallPinned = pinned
                groupTiles.forEach { (id, tile) -> updateTileContent(id, tile) }
            }
        }
        // FIX 1c: "no one answered" is the ringing timeout — worth a distinct toast
        // rather than just quietly returning to the roster like a normal call end.
        transport.onGroupCallEnded = { reason ->
            runOnUiThread {
                if (reason == "no one answered") {
                    runIfActive { Toast.makeText(this, "No one answered", Toast.LENGTH_SHORT).show() }
                }
                exitGroupCallScreen(reason)
            }
        }
        transport.onGroupCallRejected = { reason -> runOnUiThread { runIfActive { Toast.makeText(this, reason, Toast.LENGTH_LONG).show() } } }
        // PHASE 5A: SOS/FIND — independent of call state, so wired unconditionally
        // alongside the roster callback above rather than anywhere call-specific.
        transport.onSosEntry = { entry ->
            runOnUiThread {
                sosEntries[entry.srcId] = entry
                renderSosAlerts()
                val activeSenders = transport.activeSosSenderIds()
                sosAlarm.onActiveSendersChanged(activeSenders)
                renderSosOverlay(activeSenders)
                sosOverlay.visibility = if (activeSenders.isNotEmpty()) View.VISIBLE else View.GONE
                if (activeSenders.isNotEmpty()) {
                    Log.d(
                        "OFFTRACE",
                        "SOS: alert shown from=${MeshFrame.hex(entry.srcId)} tier=${lastKnownTier(entry.srcId)} " +
                            "dist=${lastKnownDistanceM(entry.srcId)} vert=${lastKnownVerticalM(entry.srcId)}"
                    )
                }
            }
        }
        transport.onFindResponse = { entry ->
            runOnUiThread {
                findResponses[entry.srcId] = entry
                renderSosAlerts()
            }
        }
        // PHASE 5BC: our own SOS's "SEEN BY k/N" progress.
        transport.onSosAckProgress = { seenBy, total ->
            runOnUiThread { updateSosButtonUi(seenBy, total) }
        }
        // PHASE 5BC: any peer's ledger track updated — refresh whichever overlay
        // is currently visible.
        transport.onPositionUpdated = {
            runOnUiThread {
                if (sosOverlay.visibility == View.VISIBLE) renderSosOverlay(transport.activeSosSenderIds())
                // PHASE 1.6a: was overlay-only — the ring (Home) needs this too.
                if (shouldRefreshPartyData()) renderPartyStatus()
            }
        }
        // PHASE 6 TRACK C: a BLE-only sighting changed — refresh party status if
        // it's the screen currently showing (same "only redraw if visible"
        // pattern as onPositionUpdated above).
        transport.onBlePresenceUpdated = {
            // PHASE 1.6a: was overlay-only — the ring (Home) needs this too.
            runOnUiThread { if (shouldRefreshPartyData()) renderPartyStatus() }
        }
        // PHASE 6 TRACK E: self-healing GO re-election.
        transport.onGoLost = { runOnUiThread { handleGoLost() } }
        transport.onElectionResult = { winnerId, isSelf -> runOnUiThread { handleElectionResult(winnerId, isSelf) } }
        transport.onSplitBrainDetected = { runOnUiThread { handleSplitBrainStandDown() } }
        // PHASE 6 TRACK B3: cellular relay — "TAP TO SEND" confirmation.
        transport.onRelayPromptReady = { prompt -> runOnUiThread { runIfActive { showRelayPrompt(transport, prompt) } } }
        // PHASE 6 TRACK B2: hands-free trigger countdown — the notification is
        // the reliable always-available surface (see SosTriggers' class doc);
        // this is just an in-app echo while the Activity happens to be visible.
        transport.onTriggerCountdownTick = { secondsRemaining, name ->
            // Only announce once, at the start — the notification (with its own
            // live countdown + Cancel action) is the reliable surface for the
            // rest of the window; a Toast every tick for 30s would just spam.
            if (secondsRemaining >= 29) {
                runOnUiThread {
                    Toast.makeText(this, "Group Alert trigger ($name) — 30s to cancel via the notification", Toast.LENGTH_LONG).show()
                }
            }
        }
        mediaTransport = transport
        // BUG (MAKE THE INVITE ACTUALLY TRANSMIT) FIX PART 4.2: this
        // device's own BLE scan, watching for someone else's invite-target
        // matching our own short node id — see MeshBleBeacon.setInviteTarget's
        // doc for the advertising half (only meaningful once THIS device is
        // itself hosting a createGroup fallback, i.e. has a transport/bleBeacon
        // of its own — the scanning half here has no such precondition and
        // is always worth wiring up once a transport exists).
        transport.bleBeacon.onInviteTargetMatched = { fromNodeId -> maybeAutoJoinExplicitGroupViaBle(fromNodeId) }
        // Both roles show the remote peer's camera on mediaRemoteView. Surface may
        // already be ready if the SurfaceView layout completed synchronously; if not,
        // the surfaceCreated callback above will call setDisplaySurface once it is.
        mediaSurface?.let { transport.setDisplaySurface(it) }
        transport.start()

        // PHASE 6 TRACK E: this fresh transport is the result of a completed
        // re-election (either we just called createGroup() as the winner, or
        // we were just invited by the new GO as a follower) — resume anything
        // that only lived on the OLD, now-discarded transport instance. See
        // handleGoLost's doc for exactly what does and doesn't survive.
        if (reconnectingAfterGoLoss) {
            reconnectingAfterGoLoss = false
            invitedDuringElection.clear()
            transport.meshElection.resetWatchdog()
            statusText.text = "Reconnected — new group owner ${if (isLocalGroupOwner) "(this device)" else ""}"
            if (sosActive) {
                // Our own SOS lived on the OLD MeshSosManager instance, which is
                // gone with the old transport — MeshCarrier (a process-wide
                // singleton, unaffected by this transport swap) still holds the
                // queued message and keeps offering it to reconnecting peers,
                // but the LIVE 30s-repeat beacon needs re-arming on the new
                // instance, or it silently stops.
                transport.startSos(null)
            }
            groupCallModeBeforeGoLoss?.let { mode ->
                groupCallModeBeforeGoLoss = null
                // Cold rebuild, not live continuity — see this track's design
                // decision doc: a fresh callId, existing/tested startGroupCall
                // path, every reconnected member re-announces (participants,
                // camera slots, VAD) rather than any state being transferred.
                Toast.makeText(this, "Restarting group call after reconnect…", Toast.LENGTH_SHORT).show()
                transport.startGroupCall(mode)
            }
        }

        // FIX 3: anchor process priority for the duration of the group session so OEM
        // battery managers don't kill us the moment the Activity is backgrounded.
        startOfflineCallService()

        // FIX 5: the foreground service alone doesn't stop aggressive OEM battery
        // managers from throttling background networking/CPU — ask, once per process,
        // for the standard exemption.
        maybePromptBatteryExemption()
    }

    /** FIX 5: shown at most once per process (see batteryPromptShown), only if the
     *  system doesn't already consider this app exempt. Declining is not re-prompted
     *  this session — the user can still grant it later from system battery settings. */
    private fun maybePromptBatteryExemption() {
        if (batteryPromptShown) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        batteryPromptShown = true
        AlertDialog.Builder(this)
            .setTitle("Keep this group connected")
            .setMessage(
                "Exempting OpenCall from battery optimization keeps this offline group " +
                    "(and any calls in it) alive when your screen is off."
            )
            .setPositiveButton("Continue") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                } catch (e: Exception) {
                    Log.w("OFFTRACE", "battery exemption prompt failed: ${e.message}")
                }
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    // ── PHASE 3: roster screen ───────────────────────────────────────────────────

    private fun updateRosterUi(members: List<RoutingTable.Member>) {
        roster = members
        renderRosterList()
        // PHASE 1.2: was "Group: ${members.size} member(s)" — roster-only,
        // silently diverging from the ring's ledger-based count (report C4).
        // partyStatusLine() is the one place both numbers now come from.
        statusText.text = partyStatusLine()
        // PHASE 3: identity strip / group call bar / member-list header all
        // key off the same roster change every other Home surface reacts to.
        updateIdentityStrip()
        updateGroupCallBarState()
        if (::groupMembersHeader.isInitialized) groupMembersHeader.text = "Group members (${members.size})"
        if (::groupsOverlay.isInitialized && groupsOverlay.visibility == View.VISIBLE) refreshGroupsScreen() // PART 1.3 (batch A): keep the member count live if the overlay happens to be open
        // PHASE 8 TRACK A: A3's Invite->Joined transition and A5's incoming-
        // invite detection both key off the roster actually changing.
        refreshNearbyDevices()
        maybeShowIncomingInviteDialogs(members)
    }

    /** FIX 6b / PHASE 8 TRACK A: rebuilds the roster ListView from [roster]
     *  alone — the GO-only "invite a nearby non-member" rows now live in
     *  inviteListView (see buildUi), rendered from nearbyDevices via
     *  sendInvite()/sendWifiInvite() rather than an ad hoc trailing section
     *  here. Newcomers are still added BY the group (GO-initiated invite, see
     *  sendWifiInvite's isLocalGroupOwner branch) rather than self-joining,
     *  since a device trying to connect() into an already-formed group on its
     *  own is unreliable — the original FIX 6c finding this preserves. */
    /** PHASE 1.5 (report F7 gap): rosterListView used the stock
     *  android.R.layout.simple_list_item_1 row — a framework layout this app
     *  never styles at all, so night mode could never reach it no matter
     *  what setNightMode() did. This is the minimal custom row needed to
     *  make it themeable; layout/behaviour (one label per row, same click/
     *  long-click position math in onRosterItemClicked/
     *  onRosterItemLongClicked) is otherwise unchanged. */
    private inner class RosterRowAdapter(private val labels: List<String>) : BaseAdapter() {
        override fun getCount() = labels.size
        override fun getItem(position: Int): String = labels[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val density = resources.displayMetrics.density
            val tv = (convertView as? TextView) ?: TextView(this@OfflineCallActivity).apply {
                textSize = 18f
                minHeight = (56 * density).toInt()
                gravity = Gravity.CENTER_VERTICAL
                setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
            }
            tv.text = labels[position]
            tv.setTextColor(TopoPalette.fg(nightModeEnabled))
            tv.setBackgroundColor(TopoPalette.cardBg(nightModeEnabled))
            return tv
        }
    }

    private fun renderRosterList() {
        val localId = mediaTransport?.localNodeId
        val labels = mutableListOf("Group chat  (${roster.size} member(s))", "Start group call")
        roster.forEach { m ->
            val youTag = if (m.nodeId == localId) " (you)" else ""
            // PHASE 8 STEP 6/STEP 2: sustained outbound backpressure to this
            // peer — they're still a full roster/call member (see
            // handleSustainedBackpressure's doc), just currently slow to reach.
            val unreachableTag = if (mediaTransport?.isPeerUnreachable(m.nodeId) == true) " (unreachable)" else ""
            labels.add("${m.name}$youTag$unreachableTag  ${shortId(m.nodeId)}")
        }
        rosterListView.adapter = RosterRowAdapter(labels)
        // PHASE 8 TRACK A: the old "Add to group: X" plain-text tail rows are
        // gone — inviteListView (below rosterListView, see buildUi) renders the
        // same GO-only invitable set via the shared NearbyDeviceAdapter/
        // buildNearbyDeviceRow instead, refreshed by notifyNearbyAdaptersChanged.
        notifyNearbyAdaptersChanged()
    }

    private fun shortId(id: Long): String = String.format("%016x", id).takeLast(6)

    /** Position 0/1 are the synthetic "Group chat"/"Start group call" rows; the
     *  next [roster].size positions map 1:1 onto [roster] — that's everything
     *  rosterListView holds now (PHASE 8 TRACK A moved the invitable-device
     *  tail into its own inviteListView, whose rows own their own taps). */
    private fun onRosterItemClicked(position: Int) {
        when (position) {
            // fix: voice notes reachable from a real nav path — routes to
            // the real Messages thread view (same underlying chatMessages
            // data, same sendGroupChat path — see sendChatText/
            // appendChatMessage's own docs) instead of the old openGroupChat()
            // overlay, which had no voice-note affordance and no path to it.
            0 -> { openMessageThread(MeshFrame.BROADCAST_ID, "Group chat", member = null); return }
            1 -> { showStartGroupCallDialog(); return }
        }
        val idx = position - 2
        val member = roster.getOrNull(idx) ?: return
        if (member.nodeId == mediaTransport?.localNodeId) {
            Toast.makeText(this, "That's you", Toast.LENGTH_SHORT).show()
            return
        }
        showCallModeDialog(member)
    }

    /** PHASE 5A: long-press companion to [onRosterItemClicked] — same position
     *  math (0/1 are synthetic rows, only an actual [roster] row can be found),
     *  but sends a FIND_REQ instead of opening the call-mode dialog. Returns
     *  whether the long-press was consumed, per ListView's listener contract. */
    private fun onRosterItemLongClicked(position: Int): Boolean {
        val idx = position - 2
        if (idx < 0 || idx >= roster.size) return false
        val member = roster[idx]
        if (member.nodeId == mediaTransport?.localNodeId) {
            Toast.makeText(this, "That's you", Toast.LENGTH_SHORT).show()
            return true
        }
        mediaTransport?.sendFindRequest(member.nodeId)
        Toast.makeText(this, "Finding ${member.name}…", Toast.LENGTH_SHORT).show()
        return true
    }

    /** PHASE 5BC: [seenBy]/[total] show "SEEN BY k/N" (or "NOT YET SEEN BY
     *  ANYONE") while our own SOS is active — a climber needs to know whether
     *  anyone got it, since it changes what they do next. */
    private fun updateSosButtonUi(seenBy: Int = -1, total: Int = -1) {
        if (sosActive) {
            val progress = mediaTransport?.sosAckProgress()
            val k = if (seenBy >= 0) seenBy else progress?.first ?: 0
            val n = if (total >= 0) total else progress?.second ?: 0
            sosButton.text = if (k > 0) "Group Alert active — seen by $k/$n (tap to cancel)" // TOPO 1.3: sentence case
                else "Group Alert active — not yet seen by anyone (tap to cancel)"
            // PHASE 1.5/TOPO 1.4: an active SOS keeps full-intensity danger
            // regardless of night mode — RED is already the night-safe
            // colour this whole palette is built around, and this is the
            // one state where maximum legibility matters more than
            // preserving night vision. TopoPalette.danger/onAccent instead
            // of the old raw Color.RED/Color.WHITE literals.
            sosButton.setBackgroundColor(TopoPalette.danger(currentTopoMode()))
            sosButton.setTextColor(TopoPalette.onAccent(currentTopoMode()))
        } else {
            sosButton.text = "Group Alert"
            // PHASE 1.5 (report F7 gap): was always Color.DKGRAY/Color.WHITE,
            // never consulting nightModeEnabled — now routed through
            // TopoPalette like every other surface setNightMode reaches.
            sosButton.setBackgroundColor(TopoPalette.dangerDim(nightModeEnabled))
            sosButton.setTextColor(TopoPalette.fg(nightModeEnabled))
        }
        // OFFLINE UI STEP 5: cancel-only surface — enabled while an SOS is
        // actually active (tap to cancel), disabled otherwise (arming is
        // slideToSosView's job now). Keeps the two views' state in sync
        // regardless of which path (this device's own gesture, or SOS
        // being cleared/re-armed some other way) changed sosActive.
        sosButton.isEnabled = sosActive
        applyGroupAlertBarStyle(sosActive)
    }

    /** PHASE 5A: renders every currently-active incoming SOS plus every FIND_RESP
     *  received so far into [sosAlertsText] — a separate element from
     *  rosterListView (see buildUi's doc comment on it) so an incoming SOS can
     *  never be silently folded into the ordinary peer list. Hidden entirely
     *  when there's nothing to show. */
    private fun renderSosAlerts() {
        val activeSos = sosEntries.values.filter { it.active }
        val lines = mutableListOf<String>()
        activeSos.forEach { e ->
            // BUG 1 FIX 3: a historical (non-alarmable — replayed >30min old, or
            // locally auto-stopped after 5min, see MeshSosManager.SosEntry) entry
            // is still shown, but never with the siren emoji — a day-old
            // replayed record and a live emergency must never look the same.
            val marker = if (e.alarmable) "🔔" else "🕐 HISTORICAL"
            lines.add("$marker ${nameForGroupParticipant(e.srcId)}: ${formatLocationLine(e)}")
        }
        findResponses.values.forEach { e -> lines.add("📍 ${nameForGroupParticipant(e.srcId)}: ${formatLocationLine(e)}") }
        if (lines.isEmpty()) {
            sosAlertsText.visibility = View.GONE
            return
        }
        sosAlertsText.visibility = View.VISIBLE
        sosAlertsText.text = lines.joinToString("\n")
        sosAlertsText.setTextColor(Color.WHITE)
        // Visually distinct: a live incoming SOS gets a strong red background,
        // never mistaken for an ordinary status line.
        sosAlertsText.setBackgroundColor(
            if (activeSos.isNotEmpty()) Color.argb(230, 180, 0, 0) else Color.argb(200, 40, 40, 40)
        )
    }

    /** "lat, lon (±Nm), Xm ago" when [MeshSosManager.SosEntry.hasFix], else "no
     *  GPS fix" — full coordinate precision is fine here (UI only); log lines
     *  elsewhere truncate to 3 decimal places instead (see MeshSosManager). */
    private fun formatLocationLine(entry: MeshSosManager.SosEntry): String {
        if (!entry.hasFix) return "no GPS fix"
        // FIX 4: receivedAtMs is our own local clock, but still clamp — a manual
        // clock change/NTP correction mid-session is rare but not impossible.
        val ageMin = ((System.currentTimeMillis() - entry.receivedAtMs) / 60_000).coerceAtLeast(0L)
        val acc = entry.accuracyMeters?.let { "±${it}m" } ?: "±?m"
        return String.format("%.5f, %.5f (%s), %dm ago", entry.latitude, entry.longitude, acc, ageMin)
    }

    // ── PHASE 5BC: SOS alert / party-status overlays ────────────────────────────

    /** Full-screen SOS alert body — one card per active sender, most recent
     *  first. Rebuilt from scratch on every call (cheap — bounded by party size,
     *  never more than ~8 rows in this mesh — see the survey behind this phase). */
    private fun renderSosOverlay(activeSenderIds: Set<Long>) {
        sosOverlayBody.removeAllViews()
        val ordered = activeSenderIds
            .mapNotNull { id -> sosEntries[id]?.let { id to it } }
            .sortedByDescending { it.second.receivedAtMs }
        if (ordered.isEmpty()) return
        ordered.forEach { (id, entry) -> sosOverlayBody.addView(buildMemberCard(id, isActiveSos = true)) }
        // entry is only used to sort; formatting reads fresh from the ledger.
    }

    // ── PHASE 1.2/1.5: single merged peer-count source (report C4) ──────────────
    // Before this, the ring (via renderPartyStatus -> feedPartyRing) counted
    // transport.ledger.knownNodeIds() while the status line
    // (updateRosterUi) counted roster.size — two independently-computed
    // numbers that provably diverge (a just-joined member with no position
    // fix yet undercounts on the ledger side; a member who left the roster
    // but has <=24h ledger history overcounts on it). partyView() is now the
    // ONLY place either number is computed; every other call site reads it.
    // The actual merge/format logic is the pure mergePartyView/
    // formatPartyStatusLine in the companion object (see there) — these are
    // thin wrappers supplying live Activity state to it.

    private fun partyView(): List<PartyEntry> {
        val transport = mediaTransport ?: return emptyList()
        return mergePartyView(roster, transport.ledger.knownNodeIds(), transport.localNodeId, ::nameForGroupParticipant)
    }

    private fun partyStatusLine(): String = formatPartyStatusLine(partyView())

    /** Every known ledger member, including anyone currently out of contact —
     *  the "where is everyone" screen. Self is skipped (nothing useful to show
     *  about your own position relative to yourself). PEER DIRECTION READOUT:
     *  reuses existing [PartyRow]s (create-for-new/reuse-for-existing/remove-
     *  for-gone, same pattern as syncGroupTiles) instead of a full
     *  removeAllViews()+rebuild — that's what lets [updatePartyArrows] update
     *  an arrow's rotation at full sensor rate without fighting a rebuild
     *  that's also happening at up to 4Hz (see startPartyRowRefreshLoop). */
    private fun renderPartyStatus() {
        if (mediaTransport == null) return
        // PHASE 1.2: was ledger-only (transport.ledger.knownNodeIds()) — now
        // the same merged partyView() the status line reads, so a roster
        // member with no position/BLE data yet still appears here too (their
        // computePeerVector comes back PeerState.UNKNOWN, rendered as a rim
        // arc per PartyRingView's PHASE 1.4c fix, instead of being invisible
        // until their first fix arrives).
        val ids = partyView().map { it.nodeId }.toSet()
        val gone = partyRows.keys - ids
        gone.forEach { id -> partyRows.remove(id)?.let { partyStatusOverlayBody.removeView(it.card) } }
        if (ids.isEmpty()) {
            partyStatusOverlayBody.removeAllViews()
            val empty = TextView(this).apply {
                text = "No position data yet."
                setTextColor(Color.LTGRAY)
            }
            partyStatusOverlayBody.addView(empty)
            return
        }
        ids.forEach { id ->
            if (id !in partyRows) {
                val row = buildPartyRow(id)
                partyRows[id] = row
                partyStatusOverlayBody.addView(row.card)
            }
        }
        refreshPartyRowTexts()
    }

    /** One row: name+arrow+vector summary text, built once and reused — see
     *  renderPartyStatus's doc. The arrow is a plain rotated TextView (an
     *  up-arrow glyph); [PartyRow.arrowView]'s rotation is set exclusively by
     *  [updatePartyArrows] and its text/visibility exclusively by
     *  [refreshPartyRowTexts] — the two never touch the same property, so
     *  there's no ordering hazard between the sensor callback and the 4Hz tick. */
    private fun buildPartyRow(nodeId: Long): PartyRow {
        val density = resources.displayMetrics.density
        // OFFLINE UI STEP 3: min touch target 56dp, min text 18sp, contrast
        // >= 7:1 (pure white #FFFFFF on this dark #282828-ish background is
        // well above 7:1 — WCAG's own formula gives ~14.5:1 here).
        val minTouchPx = (56 * density).toInt()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = minTouchPx
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
            setBackgroundColor(Color.argb(180, 40, 40, 40))
            isClickable = true
            isFocusable = true
            setOnClickListener { showPeerDetailForRing(nodeId) }
            setOnLongClickListener { sendPhraseTo(nodeId); true }
        }
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 12) }
        val arrow = TextView(this).apply {
            text = "↑" // up-arrow glyph; rotated to point bearingTrue relative to myAzimuth
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(0, 0, (12 * density).toInt(), 0)
            visibility = View.GONE // hidden until the first accurate reading with a real bearing
        }
        val summary = TextView(this).apply {
            text = "${nameForGroupParticipant(nodeId)} — …"
            textSize = 18f
            setTextColor(Color.WHITE)
        }
        card.addView(arrow)
        card.addView(summary)
        return PartyRow(card, arrow, summary)
    }

    /** Text only — throttled to 4Hz via startPartyRowRefreshLoop, never
     *  touches arrowView.rotation (see updatePartyArrows). Also opportunistically
     *  refreshes MeshCompass's declination reference position from this
     *  device's own latest fix — cheap, and doesn't need to be more frequent
     *  than this since declination changes slowly with location. */
    private fun refreshPartyRowTexts() {
        val transport = mediaTransport ?: return
        transport.ledger.latestEntry(transport.localNodeId)?.let { mine ->
            if (!(mine.latE7 == 0 && mine.lonE7 == 0)) {
                meshCompass.updateReferencePosition(mine.latitude, mine.longitude, 0.0)
            }
        }
        partyRows.forEach { (nodeId, row) ->
            val (text, bearingTrue) = peerVectorAndText(nodeId)
            row.summaryLine.text = text
            row.lastBearingTrue = bearingTrue
            if (bearingTrue.isNaN()) row.arrowView.visibility = View.GONE
        }
        feedPartyRing()
        refreshCarryChip()
        updateRingCardState() // PHASE 3 item 4: summary line + auto-collapse
    }

    /** OFFLINE UI STEP 2: builds PartyRingView.RingPeer for every currently
     *  known peer (same 4Hz cadence as the row list, see
     *  startPartyRowRefreshLoop) and pushes it to the ring — the ring itself
     *  only re-derives dMax/marker positions from this data, it never calls
     *  back into MeshLedger/OfflineMediaTransport. */
    private fun feedPartyRing() {
        if (!::partyRingView.isInitialized) return
        val transport = mediaTransport ?: return
        // PHASE 1.6d: delegates to the pure, tested buildRingPeers — same
        // transform as before, now verifiable off-device (a ledger-only
        // UNKNOWN peer surviving this mapNotNull is exactly what
        // OfflineCallActivityTest asserts).
        val ringPeers = buildRingPeers(
            nodeIds = partyRows.keys,
            vectorLookup = ::computePeerVector,
            nameFor = ::nameForGroupParticipant,
            isArticulation = { id -> transport.isArticulationPoint(id) },
            rssiTrend = { id -> transport.ledger.blePresenceFor(id)?.trend },
            isDirect = { id -> transport.isDirectlyConnected(id) }
        )
        partyRingView.setPeers(ringPeers, transport.isArticulationPoint(transport.localNodeId))
        partyRingView.setSelfLabel(if (isLocalGroupOwner) "You · GO" else "You")
    }

    /** OFFLINE UI STEP 4: "N waiting to be carried" — the WHOLE store-and-
     *  forward queue (SOS+chat+phrase+position, everything MeshCarrier ever
     *  holds — see MeshCarrier.queueSize's doc), not phrase-only, since
     *  that's the real, honest number the existing queue exposes. */
    private fun refreshCarryChip() {
        if (!::carryChip.isInitialized) return
        val n = mediaTransport?.carrier?.queueSize() ?: 0
        if (n <= 0) {
            carryChip.visibility = View.GONE
        } else {
            carryChip.visibility = View.VISIBLE
            carryChip.text = "$n waiting to be carried"
        }
    }

    // ── PHASE 3 item 4: ring card collapse state ─────────────────────────────
    // "Auto-collapsed and non-expandable when no peer has a position fix" —
    // a real fix is exactly what gives a peer a non-NaN bearingTrue (see
    // PartyRow.lastBearingTrue, set by refreshPartyRowTexts from
    // peerVectorAndText); UNKNOWN/BLE_ONLY/LOST-with-no-prior-fix peers never
    // set it, so this is a real proxy, not a guess.
    /** Signal Deck (diagnostic follow-up): "N nearby · battery% · charging"
     *  — real device/mesh state, not placeholder text. N is partyRows.size
     *  (every known peer, same source feedPartyRing itself uses — matches
     *  what the ring actually draws). Battery/charging read the same
     *  ACTION_BATTERY_CHANGED sticky broadcast evaluateBatteryCliff already
     *  uses, not a second mechanism. Also refreshes the top bar's group
     *  name (hostedGroupNetworkName is only non-null while HOSTING — a
     *  client without it shown as plain "Offline mesh" is a real, known
     *  simplification, not an attempt at a client-side SSID lookup). */
    private fun updateRingCardState() {
        if (!::signalDeckStatusText.isInitialized) return
        val n = partyRows.size
        val pct = readBatteryPercent()
        val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val charging = (status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val battWord = if (pct != null) "$pct%${if (charging) " charging" else ""}" else "battery unknown"
        signalDeckStatusText.text = "$n nearby · $battWord"
        if (::signalDeckGroupNameText.isInitialized) {
            signalDeckGroupNameText.text = hostedGroupNetworkName ?: "Offline mesh"
        }
    }

    // ── PHASE 3 item 3: group call bar enable state ──────────────────────────
    private fun updateGroupCallBarState() {
        if (!::groupCallVoiceButton.isInitialized) return
        val connected = partyView().count { it.origin == PartyOrigin.IN_ROSTER }
        val enabled = connected > 0
        groupCallVoiceButton.isEnabled = enabled
        groupCallVideoButton.isEnabled = enabled
        groupCallReasonText.text = if (enabled) "" else "No one connected yet — invite someone first"
    }

    // ── PHASE 3 item 1: identity strip refresh ───────────────────────────────
    private fun updateIdentityStrip() {
        if (!::identityShortIdText.isInitialized) return
        identityShortIdText.text = localShortId() // FIX 3: never "……" — the ID exists before any connection
        identityNameText.text = OfflineIdentity.displayName(applicationContext)
    }

    // Signal Deck (diagnostic follow-up): showQuickPhraseDialog() DELETED —
    // its only caller (the Home "Quick phrase" button) is gone. The
    // category-picker machinery it called into (showPhraseCategoryDialog/
    // onPhraseChosen) stays — still used by the per-member long-press entry
    // point (sendPhraseTo), unaffected by this removal.

    private fun onPhraseChosen(phrase: OfflineMediaTransport.PhraseCode, targetNodeId: Long?) {
        if (phrase == OfflineMediaTransport.PhraseCode.NEED_HELP && targetNodeId == null) {
            confirmAndSendNeedHelp()
        } else {
            mediaTransport?.sendPhrase(phrase, targetNodeId = targetNodeId)
            vibrateConfirm()
            if (targetNodeId == null) appendLocalPhraseMessage(phrase)
        }
    }

    /** Category picker, then that category's phrases — [onChosen] is called
     *  with whichever phrase the user ultimately picks; "Back" from the
     *  phrase list re-opens the category picker rather than dismissing
     *  entirely, "Cancel" at either level dismisses. */
    private fun showPhraseCategoryDialog(onChosen: (OfflineMediaTransport.PhraseCode) -> Unit) {
        val categories = OfflineMediaTransport.PhraseCategory.values()
        val labels = categories.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Quick phrase")
            .setItems(labels) { _, which -> showPhraseListDialog(categories[which], onChosen) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPhraseListDialog(category: OfflineMediaTransport.PhraseCategory, onChosen: (OfflineMediaTransport.PhraseCode) -> Unit) {
        val phrases = OfflineMediaTransport.PhraseCode.values().filter { it.category == category }
        val labels = phrases.map { it.text }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(category.label)
            .setItems(labels) { _, which -> onChosen(phrases[which]) }
            .setNegativeButton("Back") { _, _ -> showPhraseCategoryDialog(onChosen) }
            .show()
    }

    /** Replaces the old grid tile's "tap again within 3s" confirm with an
     *  explicit confirm dialog — different mechanism, same intent (never
     *  fires from a single accidental tap). */
    private fun confirmAndSendNeedHelp() {
        AlertDialog.Builder(this)
            .setTitle("Send \"Need help\"?")
            .setMessage("This alerts your whole group.")
            .setPositiveButton("Send") { _, _ ->
                mediaTransport?.sendPhrase(OfflineMediaTransport.PhraseCode.NEED_HELP)
                vibrateConfirm()
                appendLocalPhraseMessage(OfflineMediaTransport.PhraseCode.NEED_HELP)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCarryQueueDialog() {
        val summaries = mediaTransport?.carrier?.pendingSummaries().orEmpty()
        val lines = if (summaries.isEmpty()) {
            listOf("Nothing queued.")
        } else {
            summaries.map { s -> "type=${s.innerType} hops=${s.hopCount} age=${s.ageSec}s" }
        }
        AlertDialog.Builder(this)
            .setTitle("Waiting to be carried")
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showPeerDetailForRing(nodeId: Long) {
        val v = computePeerVector(nodeId)
        val name = nameForGroupParticipant(nodeId)
        val text = if (v != null) formatPeerVectorRow(name, v, mediaTransport?.ledger?.blePresenceFor(nodeId)) else "$name — direction unknown"
        AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage(text)
            .setPositiveButton("Close", null)
            .setNeutralButton("Send phrase") { _, _ -> sendPhraseTo(nodeId) }
            .show()
    }

    /** Long-press on a marker/row -> send to that peer only — a real 1:1
     *  unicast (dst=nodeId, still eligible for store-and-forward carry to
     *  exactly that recipient), not a broadcast every peer sees. */
    private fun sendPhraseTo(nodeId: Long) {
        // PHASE 5.1: same category-grouped picker as showQuickPhraseDialog —
        // NEED_HELP sent to a specific peer skips the broadcast-only confirm
        // dialog (see onPhraseChosen's targetNodeId!=null branch), matching
        // this function's pre-existing behaviour of sending immediately.
        showPhraseCategoryDialog { phrase -> onPhraseChosen(phrase, targetNodeId = nodeId) }
    }

    private fun vibrateConfirm() {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(android.os.VibrationEffect.createOneShot(40L, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Exception) {
        }
    }

    /** OFFLINE UI STEP 4: 2x4 grid, one tile per PhraseCode (7 real phrases;
     *  the reserved code-7 slot is left visually empty — never a sendable
     *  tile for a code this app itself never emits). Each tile >= 72dp tall.
     *  "Need help" is danger-styled and requires a SECOND tap within 3s —
     *  see NEED_HELP_CONFIRM_WINDOW_MS — so it can never fire from a single
     *  accidental pocket touch; every other tile sends on the first tap. */
    private fun buildPhraseGridTiles() {
        val density = resources.displayMetrics.density
        val minTileHeightPx = (72 * density).toInt()
        OfflineMediaTransport.PhraseCode.values().forEach { phrase ->
            val isDanger = phrase == OfflineMediaTransport.PhraseCode.NEED_HELP
            val tile = Button(this).apply {
                text = phrase.text
                textSize = 18f
                minHeight = minTileHeightPx
                if (isDanger) {
                    setBackgroundColor(Color.rgb(140, 0, 0))
                    setTextColor(Color.WHITE)
                }
                setOnClickListener { onPhraseTileTapped(phrase, this) }
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = minTileHeightPx
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED)
                setMargins(8, 8, 8, 8)
            }
            phraseGrid.addView(tile, params)
        }
    }

    /** PHASE 1.5 (report F7 gap): the "Need help" tile's danger colour was
     *  hardcoded (Color.rgb(140,0,0)/WHITE) and never revisited after
     *  buildPhraseGridTiles() ran once at startup — night mode never reached
     *  it. Tiles are built in PhraseCode.values() order (see above), so
     *  index-matching back onto that same enum finds the one danger tile
     *  without needing to keep a separate reference to it. */
    private fun applyNightModeToPhraseTiles() {
        if (!::phraseGrid.isInitialized) return
        OfflineMediaTransport.PhraseCode.values().forEachIndexed { i, phrase ->
            if (phrase != OfflineMediaTransport.PhraseCode.NEED_HELP) return@forEachIndexed
            val tile = phraseGrid.getChildAt(i) as? Button ?: return@forEachIndexed
            tile.setBackgroundColor(if (nightModeEnabled) Color.rgb(90, 0, 0) else Color.rgb(140, 0, 0))
            tile.setTextColor(TopoPalette.fg(nightModeEnabled))
        }
    }

    private val needHelpConfirmWindowMs = 3_000L
    private fun onPhraseTileTapped(phrase: OfflineMediaTransport.PhraseCode, button: Button) {
        if (phrase == OfflineMediaTransport.PhraseCode.NEED_HELP) {
            val now = SystemClock.elapsedRealtime()
            if (now - needHelpArmedAtMs > needHelpConfirmWindowMs) {
                needHelpArmedAtMs = now
                button.text = "Tap again to confirm"
                vibrateConfirm()
                return
            }
            needHelpArmedAtMs = 0L
            button.text = phrase.text
        }
        mediaTransport?.sendPhrase(phrase)
        vibrateConfirm()
        appendLocalPhraseMessage(phrase)
    }

    private fun appendLocalPhraseMessage(phrase: OfflineMediaTransport.PhraseCode) {
        phraseMessages.add(0, PhraseMessageEntry(null, "You", phrase.code, sentByMe = true, carrierName = null, hopCount = null))
    }

    /** OFFLINE UI STEP 4: fires for both live and store-and-forward-carried
     *  phrases — [carrierId]/[hopCount] non-null only for the carried case
     *  (see OfflineMediaTransport.onPhraseReceived's doc). [code] outside
     *  PhraseCode's table renders "unknown message" but is still recorded —
     *  the frame itself was already relayed before this callback ever fires
     *  (see handlePhraseFrame's doc / OUTPUT proof #7), this is display only. */
    private fun onPhraseReceived(fromNodeId: Long, code: Int, @Suppress("UNUSED_PARAMETER") seq: Long, carrierId: Long?, hopCount: Int?) {
        val phrase = OfflineMediaTransport.PhraseCode.fromCode(code)
        val text = phrase?.text ?: "unknown message"
        val fromName = nameForGroupParticipant(fromNodeId)
        val carrierName = carrierId?.let { nameForGroupParticipant(it) }
        phraseMessages.add(0, PhraseMessageEntry(null, fromName, code, sentByMe = false, carrierName = carrierName, hopCount = hopCount))
        val suffix = if (carrierName != null && hopCount != null) " (carried via $carrierName, $hopCount hops)" else ""
        Toast.makeText(this, "$fromName: $text$suffix", Toast.LENGTH_LONG).show()
    }

    // ── PHASE 1.5: one palette object, swapped in one place ─────────────────────
    // Report F7 found setNightMode() reached PartyRingView/sosAlertsText/
    // partyStatusButton/carryChip but MISSED sosButton, ssidBroadcastButton,
    // the invite rows, the phrase-grid "Need help" tile, and rosterListView
    // (which used the stock, unstylable android.R.layout.simple_list_item_1
    // row). Every one of those now reads colour from here instead of a
    // hardcoded Color.* literal picked at the point that view was built, so
    // a future new surface has to deliberately opt OUT of the palette to
    // miss night mode, rather than opting in by remembering to add it here.
    // Does not touch anything under G4 (call screen / group video grid).
    //
    // TOPO PHASE 1.1: NightPalette EXTENDED into TopoPalette — same object,
    // same public surface DiscoveryRadarView/every existing call site
    // already depends on (see FIX 4's doc above), now backed by a real
    // three-mode system (TopoMode.DAY/NIGHT/CLIFF) instead of a bare
    // Boolean. The five original boolean-keyed functions (fg/mutedFg/
    // failFg/cardBg/dangerDim) are kept, UNCHANGED in name and signature,
    // as a compatibility layer over the new mode-keyed roles below — every
    // one of their ~27 existing call sites keeps compiling and working
    // exactly as before, still routed through this one object, just with a
    // new name. New surfaces (Phases 2-4) call the mode-keyed roles
    // directly via currentTopoMode() instead.
    object TopoPalette {
        // ── DAY ──────────────────────────────────────────────────────────
        private const val DAY_BG_BASE = 0xFF14171c.toInt()
        private const val DAY_BG_SURFACE = 0xFF1b2027.toInt()
        private const val DAY_BG_RAISED = 0xFF232932.toInt()
        private const val DAY_CONTOUR = 0xFF2a3038.toInt()
        private const val DAY_ACCENT = 0xFFEF9F27.toInt()
        private const val DAY_ON_ACCENT = 0xFF412402.toInt()
        private const val DAY_TEXT_PRIMARY = 0xFFdbe3ec.toInt()
        private const val DAY_TEXT_SECONDARY = 0xFF8b96a5.toInt()
        private const val DAY_TEXT_MUTED = 0xFF5d6774.toInt()
        private const val DAY_OK = 0xFF5DCAA5.toInt()
        private const val DAY_WARN = 0xFFEF9F27.toInt()
        private const val DAY_LOST = 0xFF6b7683.toInt()
        private const val DAY_DANGER = 0xFFE24B4A.toInt()
        private const val DAY_DANGER_BG = 0xFF3d1414.toInt()

        // ── NIGHT: red monochrome — every role becomes a shade of red,
        // spaced by roughly the same relative-luminance ordering DAY has
        // (surfaces darkest, accent/danger brightest); never green/blue.
        // textPrimary/textSecondary/dangerDim below are deliberately the
        // EXACT rgb() values the old NightPalette used (200,0,0 / 140,0,0 /
        // 60,0,0) so the compatibility layer's visible result doesn't
        // regress for any of its ~27 existing call sites.
        private const val NIGHT_BG_BASE = 0xFF0A0000.toInt()
        private const val NIGHT_BG_SURFACE = 0xFF140000.toInt()
        private const val NIGHT_BG_RAISED = 0xFF1E0000.toInt()
        private const val NIGHT_CONTOUR = 0xFF3A0000.toInt() // dark red, per 1.2
        private const val NIGHT_ACCENT = 0xFFD40000.toInt() // strictly red monochrome, distinct from textPrimary's C80000
        private const val NIGHT_ON_ACCENT = 0xFF0A0000.toInt() // near-black but red-tinted — distinct from CLIFF's pure black
        private const val NIGHT_TEXT_PRIMARY = 0xFFC80000.toInt() // = old Color.rgb(200,0,0)
        private const val NIGHT_TEXT_SECONDARY = 0xFF8C0000.toInt() // = old Color.rgb(140,0,0)
        private const val NIGHT_TEXT_MUTED = 0xFF5A0000.toInt()
        private const val NIGHT_OK = 0xFFC80000.toInt() // red monochrome — "ok" isn't a distinct hue here
        private const val NIGHT_WARN = 0xFFE60000.toInt() // strictly red monochrome (old failFg's 255,60,60 had a non-zero G/B, not preserved bit-for-bit here)
        private const val NIGHT_LOST = 0xFF8C0000.toInt()
        private const val NIGHT_DANGER = 0xFFFF0000.toInt() // brightest, purest red — SOS stays maximally distinct even in red monochrome
        private const val NIGHT_DANGER_BG = 0xFF280000.toInt()

        // ── CLIFF: battery saver — strictly black/white, no hue at all.
        // Contours are never drawn in this mode (see TopoBackgroundDrawable).
        private const val CLIFF_BG_BASE = 0xFF000000.toInt()
        private const val CLIFF_BG_SURFACE = 0xFF000000.toInt()
        private const val CLIFF_BG_RAISED = 0xFF1A1A1A.toInt()
        private const val CLIFF_CONTOUR = 0xFF000000.toInt() // unused — CLIFF never draws contours
        private const val CLIFF_ACCENT = 0xFFFFFFFF.toInt()
        private const val CLIFF_ON_ACCENT = 0xFF000000.toInt()
        private const val CLIFF_TEXT_PRIMARY = 0xFFFFFFFF.toInt()
        private const val CLIFF_TEXT_SECONDARY = 0xFFAAAAAA.toInt()
        private const val CLIFF_TEXT_MUTED = 0xFF666666.toInt()
        private const val CLIFF_OK = 0xFFFFFFFF.toInt()
        private const val CLIFF_WARN = 0xFFFFFFFF.toInt()
        private const val CLIFF_LOST = 0xFF888888.toInt()
        private const val CLIFF_DANGER = 0xFFFFFFFF.toInt() // no red exists in strict black/white — SOS is distinguished by dangerBg's outline instead
        private const val CLIFF_DANGER_BG = 0xFF3A3A3A.toInt()

        fun bgBase(mode: TopoMode) = when (mode) { TopoMode.DAY -> DAY_BG_BASE; TopoMode.NIGHT -> NIGHT_BG_BASE; TopoMode.CLIFF -> CLIFF_BG_BASE }
        fun bgSurface(mode: TopoMode) = when (mode) { TopoMode.DAY -> DAY_BG_SURFACE; TopoMode.NIGHT -> NIGHT_BG_SURFACE; TopoMode.CLIFF -> CLIFF_BG_SURFACE }
        fun bgRaised(mode: TopoMode) = when (mode) { TopoMode.DAY -> DAY_BG_RAISED; TopoMode.NIGHT -> NIGHT_BG_RAISED; TopoMode.CLIFF -> CLIFF_BG_RAISED }
        fun contour(mode: TopoMode) = when (mode) { TopoMode.DAY -> DAY_CONTOUR; TopoMode.NIGHT -> NIGHT_CONTOUR; TopoMode.CLIFF -> CLIFF_CONTOUR }
        fun accent(mode: TopoMode) = when (mode) { TopoMode.DAY -> DAY_ACCENT; TopoMode.NIGHT -> NIGHT_ACCENT; TopoMode.CLIFF -> CLIFF_ACCENT }
        fun onAccent(mode: TopoMode) = when (mode) { TopoMode.DAY -> DAY_ON_ACCENT; TopoMode.NIGHT -> NIGHT_ON_ACCENT; TopoMode.CLIFF -> CLIFF_ON_ACCENT }
        fun textPrimary(mode: TopoMode) = when (mode) { TopoMode.DAY -> DAY_TEXT_PRIMARY; TopoMode.NIGHT -> NIGHT_TEXT_PRIMARY; TopoMode.CLIFF -> CLIFF_TEXT_PRIMARY }
        fun textSecondary(mode: TopoMode) = when (mode) { TopoMode.DAY -> DAY_TEXT_SECONDARY; TopoMode.NIGHT -> NIGHT_TEXT_SECONDARY; TopoMode.CLIFF -> CLIFF_TEXT_SECONDARY }
        fun textMuted(mode: TopoMode) = when (mode) { TopoMode.DAY -> DAY_TEXT_MUTED; TopoMode.NIGHT -> NIGHT_TEXT_MUTED; TopoMode.CLIFF -> CLIFF_TEXT_MUTED }
        fun ok(mode: TopoMode) = when (mode) { TopoMode.DAY -> DAY_OK; TopoMode.NIGHT -> NIGHT_OK; TopoMode.CLIFF -> CLIFF_OK }
        fun warn(mode: TopoMode) = when (mode) { TopoMode.DAY -> DAY_WARN; TopoMode.NIGHT -> NIGHT_WARN; TopoMode.CLIFF -> CLIFF_WARN }
        fun lost(mode: TopoMode) = when (mode) { TopoMode.DAY -> DAY_LOST; TopoMode.NIGHT -> NIGHT_LOST; TopoMode.CLIFF -> CLIFF_LOST }
        /** RED is reserved for SOS and nothing else — never call this for
         *  an ordinary destructive action; use [accent] with a confirm
         *  step instead (per the task spec). */
        fun danger(mode: TopoMode) = when (mode) { TopoMode.DAY -> DAY_DANGER; TopoMode.NIGHT -> NIGHT_DANGER; TopoMode.CLIFF -> CLIFF_DANGER }
        fun dangerBg(mode: TopoMode) = when (mode) { TopoMode.DAY -> DAY_DANGER_BG; TopoMode.NIGHT -> NIGHT_DANGER_BG; TopoMode.CLIFF -> CLIFF_DANGER_BG }

        // ── Compatibility layer (see class doc above) ───────────────────
        fun fg(night: Boolean) = textPrimary(if (night) TopoMode.NIGHT else TopoMode.DAY)
        fun mutedFg(night: Boolean) = textSecondary(if (night) TopoMode.NIGHT else TopoMode.DAY)
        fun failFg(night: Boolean) = warn(if (night) TopoMode.NIGHT else TopoMode.DAY)
        fun cardBg(night: Boolean) = bgSurface(if (night) TopoMode.NIGHT else TopoMode.DAY)
        fun dangerDim(night: Boolean) = if (night) NIGHT_TEXT_MUTED else 0xFF444444.toInt() // = old Color.rgb(60,0,0) / Color.DKGRAY, preserved verbatim
    }

    /** TOPO PHASE 1.1: the one place the three-mode system is resolved from
     *  existing state — CLIFF always wins over NIGHT (a battery-saver
     *  safety mode outranks a cosmetic preference), matching how
     *  [evaluateBatteryCliff] already overrides the ring/compass regardless
     *  of night mode. Every new (Phase 2-4) surface calls this instead of
     *  reading nightModeEnabled/inBatteryCliff directly. */
    private fun currentTopoMode(): TopoMode = when {
        inBatteryCliff -> TopoMode.CLIFF
        nightModeEnabled -> TopoMode.NIGHT
        else -> TopoMode.DAY
    }

    /** TOPO PHASE 1.2: the root's contour background — a single Drawable
     *  instance, its Paths built once per size in onBoundsChange (see that
     *  class's doc), just recoloured/hidden per mode from here. */
    private val topoBackground = TopoBackgroundDrawable()

    /** [topoBackground] fills its own base colour AND draws the contours
     *  (see that class's draw()) — a single Drawable is the view's whole
     *  background, so this only ever needs to tell it which mode to
     *  recolour to; no separate setBackgroundColor call anywhere. */
    private fun applyTopoMode() {
        val mode = currentTopoMode()
        topoBackground.setMode(mode)
        if (::slideToSosView.isInitialized) slideToSosView.setMode(mode)
        // fix: restore cancel path for active group alert — idle state is
        // now an outline (GradientDrawable), not a plain setBackgroundColor
        // fill, so a mode change must go through applyGroupAlertBarStyle
        // (which picks the right drawable/fill for the CURRENT sosActive
        // state) rather than unconditionally overwriting the background.
        applyGroupAlertBarStyle(sosActive)
        // Signal Deck (diagnostic follow-up): top bar / ring status text +
        // the theme icon + call-row styling, all mode-dependent.
        applyThemeToggleIcon()
        applyCallButtonStyles()
        if (::signalDeckDimLabel.isInitialized) signalDeckDimLabel.setTextColor(TopoPalette.textMuted(mode))
        if (::signalDeckGroupNameText.isInitialized) signalDeckGroupNameText.setTextColor(TopoPalette.fg(nightModeEnabled))
        if (::signalDeckStatusText.isInitialized) signalDeckStatusText.setTextColor(TopoPalette.textSecondary(mode))
    }

    /** OFFLINE UI STEP 5: single toggle, no picker, persisted via the same
     *  getSharedPreferences("opencall", MODE_PRIVATE) pattern already used
     *  by SosTriggers/SosRelay/OfflineIdentity. Only ever mutates existing
     *  Paint fields on partyRingView (see PartyRingView.setNightMode's doc)
     *  — never constructs new Paint objects. PHASE 1.5: now also re-applies
     *  every previously-missed surface (see NightPalette's doc above) so a
     *  toggle is genuinely all-or-nothing across every tab, not just the
     *  ring/status-line/carry-chip subset it used to reach. */
    private fun setNightMode(enabled: Boolean) {
        nightModeEnabled = enabled
        getSharedPreferences("opencall", MODE_PRIVATE).edit().putBoolean("night_mode", enabled).apply()
        applyThemeToggleIcon()
        applyTopoMode() // TOPO 1.2: recolours the contour background too, including its contours
        if (::partyRingView.isInitialized) partyRingView.setNightMode(enabled)
        val bg = TopoPalette.cardBg(enabled)
        val fg = TopoPalette.fg(enabled)
        sosAlertsText.setTextColor(fg)
        partyStatusButton.setTextColor(fg)
        if (::carryChip.isInitialized) carryChip.setBackgroundColor(bg)
        // PHASE 1.5: previously-missed surfaces (report F7).
        if (::sosButton.isInitialized) updateSosButtonUi()
        if (::ssidBroadcastButton.isInitialized) {
            updateSsidBroadcastButtonUi(lastSsidBroadcastActive, lastSsidBroadcastBroadcasting, lastSsidBroadcastSsid)
        }
        applyNightModeToPhraseTiles()
        // Rebuilds rosterListView's RosterRowAdapter fresh (picks up the new
        // palette) and, via its own call to notifyNearbyAdaptersChanged(),
        // also repaints the invite rows and any open mid-call invite dialog —
        // one call covers all three, since none of their colours are cached,
        // only re-read from nightModeEnabled at each row's next getView().
        if (::rosterListView.isInitialized) renderRosterList()
        if (::discoveryRadarView.isInitialized) discoveryRadarView.applyNightMode(enabled) // FIX 4f
    }

    /** OFFLINE UI STEP 5: <=15% and not charging collapses the screen (see
     *  BatteryCliff.nextCliffState's hysteresis — 5% band, can't oscillate);
     *  >20% and charging restores. Phrase grid and SOS stay fully live in
     *  either state (neither is disabled below) — only the ring/compass/
     *  position-cadence change. */
    private fun evaluateBatteryCliff() {
        val pct = readBatteryPercent() ?: return
        val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val charging = plugged != 0
        val next = BatteryCliff.nextCliffState(pct, charging, inBatteryCliff)
        if (next == inBatteryCliff) return
        inBatteryCliff = next
        if (inBatteryCliff) {
            Log.d("OFFTRACE", "PWR: cliff enter batt=$pct% posInterval=120s")
            batteryCliffBanner.visibility = View.VISIBLE
            batteryCliffBanner.text = "Battery saving: ring frozen, compass off, updates every 120s"
            batteryCliffBanner.setBackgroundColor(Color.BLACK)
            window.decorView.setBackgroundColor(Color.BLACK)
            if (::partyRingView.isInitialized) partyRingView.setFrozen(true)
            meshCompass.stop()
            mediaTransport?.setPositionBroadcastIntervalMs(120_000L)
        } else {
            Log.d("OFFTRACE", "PWR: cliff exit batt=$pct%")
            batteryCliffBanner.visibility = View.GONE
            window.decorView.setBackgroundColor(if (nightModeEnabled) Color.BLACK else Color.argb(255, 6, 10, 16))
            if (::partyRingView.isInitialized) partyRingView.setFrozen(false)
            meshCompass.start()
            mediaTransport?.setPositionBroadcastIntervalMs(30_000L)
        }
        updateRadarSweeping() // FIX 4c: battery cliff stops the sweep, same as it freezes the ring
        applyTopoMode() // TOPO 1.2/1.1: CLIFF overrides NIGHT — no contours drawn while it's active
    }

    /** Rotation only, full sensor rate — never touches summaryLine.text (see
     *  STEP 2's hard rule: myAzimuth must never influence any text). Hides
     *  the arrow entirely (not just freezes it) when the compass itself
     *  isn't currently accurate, per that requirement — text stays visible
     *  either way. */
    private fun updatePartyArrows(reading: MeshCompass.Reading) {
        partyRows.values.forEach { row ->
            val bearing = row.lastBearingTrue
            if (bearing.isNaN() || !reading.accurate) {
                row.arrowView.visibility = View.GONE
                return@forEach
            }
            row.arrowView.visibility = View.VISIBLE
            row.arrowView.rotation = ((bearing - reading.trueAzimuthDeg) % 360f + 360f) % 360f
        }
    }

    private fun startPartyRowRefreshLoop() {
        if (partyRefreshRunnable != null) return
        val r = object : Runnable {
            override fun run() {
                // PHASE 1.6a: was overlay-only, which is why the ring never
                // refreshed on an ordinary Home session — this loop is what
                // ultimately drives feedPartyRing()/setPeers() at up to 4Hz.
                // Still gated (not unconditional): only runs at all between
                // onResume/onPause (loop lifecycle below), and even then only
                // does work when Home or the overlay actually needs it.
                if (shouldRefreshPartyData()) refreshPartyRowTexts()
                partyRefreshHandler.postDelayed(this, PARTY_ROW_REFRESH_INTERVAL_MS)
            }
        }
        partyRefreshRunnable = r
        partyRefreshHandler.postDelayed(r, PARTY_ROW_REFRESH_INTERVAL_MS)
    }

    private fun stopPartyRowRefreshLoop() {
        partyRefreshRunnable?.let { partyRefreshHandler.removeCallbacks(it) }
        partyRefreshRunnable = null
    }

    /** PEER DIRECTION READOUT: single source of truth for a peer's
     *  directional summary text — used by both the party-status row (with an
     *  arrow) and the plain SOS-overlay card (text only, see buildMemberCard).
     *  [linkUp] (roster membership) is the one input MeshLedger.vectorTo can't
     *  derive itself. Returns (text, bearingTrue) — bearingTrue is the RAW
     *  value for the caller's own arrow math, never consulted by this
     *  function for anything text-related. */
    private fun peerVectorAndText(nodeId: Long): Pair<String, Float> {
        val name = nameForGroupParticipant(nodeId)
        val v = computePeerVector(nodeId) ?: return "$name — direction unknown" to Float.NaN
        val ble = mediaTransport?.ledger?.blePresenceFor(nodeId)
        return formatPeerVectorRow(name, v, ble) to v.bearingTrue
    }

    /** Shared vectorTo call — used by both the row-list text (peerVectorAndText)
     *  and the ring (see feedPartyRing) so a given 4Hz tick computes each
     *  peer's vector exactly once, not twice. */
    private fun computePeerVector(nodeId: Long): MeshLedger.PeerVector? {
        val transport = mediaTransport ?: return null
        val linkUp = roster.any { it.nodeId == nodeId }
        return transport.ledger.vectorTo(nodeId, transport.localNodeId, linkUp, transport.barometer)
    }

    /** Exact per-state text formats — see the task's own worked examples;
     *  every branch below was checked against them directly. 16-point
     *  cardinals only (GeoUtils.compassPoint) — degrees are never shown. */
    /** One member's detail card — shared by the SOS alert and the party-status
     *  screen (see class doc). Short lines, large type, no jargon: this screen
     *  matters most to someone cold, exhausted, and frightened. */
    private fun buildMemberCard(nodeId: Long, isActiveSos: Boolean): LinearLayout {
        val transport = mediaTransport
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(if (isActiveSos) Color.argb(230, 120, 0, 0) else Color.argb(180, 40, 40, 40))
        }
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 16) }

        fun addLine(text: String, sizeSp: Float = 18f, bold: Boolean = false) {
            card.addView(
                TextView(this).apply {
                    this.text = text
                    textSize = sizeSp
                    setTextColor(Color.WHITE)
                    if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
            )
        }

        val name = nameForGroupParticipant(nodeId)
        addLine("${if (isActiveSos) "🔔 " else ""}$name  ${shortId(nodeId)}", sizeSp = 20f, bold = true)

        val ledger = transport?.ledger
        val latest = ledger?.latestEntry(nodeId)

        // PEER DIRECTION READOUT: same vectorTo-driven summary line the
        // party-status row shows (see peerVectorAndText/formatPeerVectorRow)
        // — text only here, no arrow, rebuilt fresh each time this card is
        // shown exactly like the rest of the SOS overlay always has been.
        val (summaryText, _) = peerVectorAndText(nodeId)
        addLine(summaryText, bold = true)

        // Raw coordinates + MGRS stay as supplementary detail (SAR convention,
        // not something vectorTo's compact row replaces) — only when a real
        // fix exists.
        if (latest != null && latest.tier != MeshLocation.LOC_TIER_NONE && !(latest.latE7 == 0 && latest.lonE7 == 0)) {
            addLine(String.format("%.5f, %.5f", latest.latitude, latest.longitude), sizeSp = 14f)
            addLine(GeoUtils.toMgrs(latest.latitude, latest.longitude), sizeSp = 14f)
        }

        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val copyButton = Button(this).apply {
            text = "Copy coordinates"
            setOnClickListener {
                val text = if (latest != null && !(latest.latE7 == 0 && latest.lonE7 == 0)) {
                    "${latest.latitude}, ${latest.longitude} (${GeoUtils.toMgrs(latest.latitude, latest.longitude)})"
                } else "No coordinates available"
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("coordinates", text))
                Toast.makeText(this@OfflineCallActivity, "Copied", Toast.LENGTH_SHORT).show()
            }
        }
        actionRow.addView(copyButton)
        if (isActiveSos) {
            val silenceButton = Button(this).apply {
                text = "Silence"
                setOnClickListener { sosAlarm.silence() }
            }
            actionRow.addView(silenceButton)
        }
        card.addView(actionRow)

        return card
    }

    private fun lastKnownTier(nodeId: Long): Int =
        mediaTransport?.ledger?.latestEntry(nodeId)?.tier ?: MeshLocation.LOC_TIER_NONE

    private fun lastKnownDistanceM(nodeId: Long): Int {
        val transport = mediaTransport ?: return -1
        val latest = transport.ledger.latestEntry(nodeId) ?: return -1
        val mine = transport.ledger.latestEntry(transport.localNodeId) ?: return -1
        if (latest.latE7 == 0 && latest.lonE7 == 0) return -1
        return GeoUtils.haversineMeters(mine.latitude, mine.longitude, latest.latitude, latest.longitude).toInt()
    }

    // ── PHASE 6 TRACK D: last-resort SSID broadcast ─────────────────────────────

    private fun onSsidBroadcastButtonClicked() {
        if (sosSsidBroadcast.active) {
            sosSsidBroadcast.deactivate()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Broadcast Group Alert via Wi-Fi name?")
            .setMessage(
                "This DISCONNECTS you from the rest of the party's mesh while active. " +
                    "Your phone will alternate 60s broadcasting a Wi-Fi name containing your " +
                    "position (visible to ANY nearby phone, no app needed) with 30s attempting " +
                    "to reconnect to the group. Only use this as a last resort — e.g. you are " +
                    "alone and out of mesh range and need any nearby person to see you.\n\n" +
                    "The broadcast name itself starts with \"SOS\" on purpose — that's " +
                    "recognisable to anyone nearby, with or without this app."
            )
            .setPositiveButton("Broadcast") { _, _ -> startSsidBroadcast() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startSsidBroadcast() {
        val transport = mediaTransport
        val name = connectedPeerName.ifEmpty { "Party member" }
        val myFix = transport?.ledger?.latestEntry(transport.localNodeId)
        if (myFix == null || (myFix.latE7 == 0 && myFix.lonE7 == 0)) {
            Toast.makeText(this, "No location fix yet — cannot compose a Group Alert Wi-Fi broadcast", Toast.LENGTH_LONG).show()
            return
        }
        sosSsidBroadcast.activate(name, myFix.latitude, myFix.longitude)
    }

    // PHASE 1.5: cached so setNightMode can re-apply this button's colour
    // without needing to re-derive (active, broadcasting, ssid) from
    // sosSsidBroadcast — text content doesn't change on a night-mode
    // toggle, only colour does.
    private var lastSsidBroadcastActive = false
    private var lastSsidBroadcastBroadcasting = false
    private var lastSsidBroadcastSsid: String? = null

    private fun updateSsidBroadcastButtonUi(active: Boolean, broadcasting: Boolean, ssid: String?) {
        lastSsidBroadcastActive = active
        lastSsidBroadcastBroadcasting = broadcasting
        lastSsidBroadcastSsid = ssid
        ssidBroadcastButton.text = when {
            !active -> "Last resort: broadcast SSID"
            broadcasting -> "Broadcasting \"$ssid\" — tap to stop"
            else -> "Attempting to reconnect… — tap to stop"
        }
        // PHASE 1.5 (report F7 gap): was always Color.RED/Color.DKGRAY/
        // Color.WHITE, never consulting nightModeEnabled.
        // TOPO 1.4: was raw Color.RED/Color.WHITE for the active branch —
        // routed through the danger/onAccent roles now, matching sosButton's
        // own active-state fix above.
        ssidBroadcastButton.setBackgroundColor(if (active) TopoPalette.danger(currentTopoMode()) else TopoPalette.dangerDim(nightModeEnabled))
        ssidBroadcastButton.setTextColor(if (active) TopoPalette.onAccent(currentTopoMode()) else TopoPalette.fg(nightModeEnabled))
    }

    // ── PHASE 6 TRACK E: self-healing GO re-election ────────────────────────────
    //
    // DESIGN NOTE — read before touching this section: the codebase's own FIX 6c
    // (see onPeerSelected's doc, this file) already discovered and documented
    // that a client calling connect() against an ALREADY-FORMED group's GO is
    // unreliable — the proven, reliable direction is the GO inviting a client in
    // (invitePeer). This track's own instructions describe "losers connect to
    // [the winner]" — that phrasing was written before checking against this
    // codebase's own prior finding. Rather than build on a path this app's own
    // history already flags as unreliable, the winner instead auto-INVITES every
    // nearby discovered peer (reusing invitePeerToGroup's exact, already-tested
    // mechanism) and followers simply stay discoverable and wait. The "rank * 2s"
    // stagger from the spec is applied to how long each FOLLOWER waits before
    // starting its own discovery-visibility phase, reducing thundering-herd
    // discovery/invite traffic, rather than to a connect() attempt that this
    // codebase's own history says doesn't work well.

    /** Fired once, when this CLIENT hasn't heard a GO heartbeat in 30s (never
     *  fires on the device that IS the GO — see MeshElection's doc). Per the
     *  HARD RULE this track was built against: an active call is never left
     *  half-alive — it's about to be cold-rebuilt (see the post-reconnect hook
     *  in the transport-setup function), never silently frozen. */
    private fun handleGoLost() {
        if (reconnectingAfterGoLoss) return
        reconnectingAfterGoLoss = true
        groupCallModeBeforeGoLoss = groupCallMode
        statusText.text = "Group owner lost — reconnecting" // TOPO 1.3: sentence case
        errorText.text = "Group owner lost — electing a new one…"
        errorText.visibility = View.VISIBLE
        Log.w("OFFTRACE", "ELECT: GO lost — running election")
        mediaTransport?.meshElection?.runElection()
    }

    /** The deterministic election result — every surviving device computes the
     *  same [winnerId] independently (see MeshElection's class doc), so this
     *  fires on every device, not just the winner's. */
    private fun handleElectionResult(winnerId: Long, isSelf: Boolean) {
        val rank = mediaTransport?.meshElection?.myRank()?.coerceAtLeast(0) ?: 0
        Log.d("OFFTRACE", "ELECT: new GO up, reconnecting in ${if (isSelf) 0 else rank * 2000}ms (rank $rank)")
        if (isSelf) {
            becomeNewGoAfterElection()
        } else {
            Handler(Looper.getMainLooper()).postDelayed({ waitForInviteAfterElection() }, rank * 2_000L)
        }
    }

    /** Split-brain guard: this device is GO and just heard another device that
     *  ALSO believes itself GO — MeshElection already restricted this callback
     *  to firing only on the higher-nodeId side (the lower one stands down
     *  symmetrically on its own end when it hears OUR heartbeat), so reaching
     *  here always means THIS device should yield. Reuses the exact same
     *  reconnect path as an ordinary GO loss — from this device's perspective
     *  its own "GO" is now effectively gone (there can only be one). */
    private fun handleSplitBrainStandDown() {
        Log.w("OFFTRACE", "ELECT: split-brain — this device standing down")
        handleGoLost()
    }

    /** Winner path: tear down the old, now-headless transport (the OLD GO is
     *  gone; this instance's isGroupOwner=false is stale either way) and
     *  unilaterally form a fresh group. MeshLedger/MeshCarrier are process-wide
     *  singletons unaffected by this instance swap — position history and any
     *  queued SOS/chat survive by construction, per this track's design. */
    private fun becomeNewGoAfterElection() {
        statusText.text = "Becoming new group owner…"
        mediaTransport?.stop()
        mediaTransport = null
        signaling?.stop()
        signaling = null
        // LEAK (PART 1.3): createGroupForElection — Wi-Fi-Direct-specific
        // election-fallback concept (host unilaterally, no target peer),
        // no interface equivalent.
        wifiDirect.createGroupForElection { ok ->
            if (!ok) {
                runOnUiThread {
                    Toast.makeText(this, "Could not become group owner — returning to search", Toast.LENGTH_LONG).show()
                    leaveGroup("election: createGroup failed")
                }
            }
            // onConnectionChangedInternal picks up groupFormed=true from here,
            // the SAME bootstrap path a normal initial connection already uses
            // — no duplicated setup logic.
        }
    }

    /** Follower path: stay discoverable and wait to be invited by the new GO —
     *  see this section's design note on why this device does NOT call
     *  connect() itself. */
    private fun waitForInviteAfterElection() {
        statusText.text = "Waiting for new group owner to reconnect us…"
        mediaTransport?.stop()
        mediaTransport = null
        signaling?.stop()
        signaling = null
        // LEAK (PART 1.3): result callback — see proceedToDiscovery's identical leak note.
        wifiDirect.startDiscovery { ok ->
            if (!ok) Log.w("OFFTRACE", "ELECT: rediscovery failed while waiting for invite")
        }
    }

    /** Called from onPeersChanged while this device is the just-elected GO
     *  waiting to reconnect its old party — auto-invites every discovered
     *  device instead of requiring a manual "Add to group" tap (see this
     *  section's design note). Safe/idempotent per device address. */
    private fun autoInviteDuringElection() {
        if (!reconnectingAfterGoLoss || !isLocalGroupOwner) return
        devices.filter { it.status != WifiP2pDevice.CONNECTED && invitedDuringElection.add(it.deviceAddress) }
            .forEach { device ->
                Log.d("OFFTRACE", "ELECT: auto-inviting ${device.deviceName} back into the re-formed group")
                // LEAK (PART 1.3): raw WifiP2pDevice.
                wifiDirect.invitePeer(device)
            }
    }

    // ── PHASE 7A STEP 5: signed display name ────────────────────────────────

    /** Editable any time, not just before joining — a change here only takes
     *  effect on the NEXT group join (HELLO is sent once, at connect; this
     *  deliberately does not attempt to re-announce a name change to peers
     *  already in an active session). */
    private fun showDisplayNameDialog() {
        val input = EditText(this).apply {
            hint = "Shown to other party members"
            setText(OfflineIdentity.displayName(applicationContext))
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Display name")
            .setMessage("Sent to other devices signed with your mesh identity — max ${OfflineIdentity.MAX_DISPLAY_NAME_BYTES} bytes.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val saved = OfflineIdentity.setDisplayName(applicationContext, input.text.toString())
                if (saved == null) {
                    Toast.makeText(this, "Name can't be empty", Toast.LENGTH_SHORT).show()
                } else {
                    // TOPO PART B9: displayNameButton is gone (see buildUi's
                    // doc) — Settings' own "Display name" row shows this
                    // string as its `sub` line, cached at build time; drop
                    // the cache so it rebuilds fresh next time Settings is
                    // opened, rather than showing a stale name until restart.
                    settingsScreenView = null
                    // BUG 2 FIX: re-advertise immediately so a name change is
                    // visible to a peer still browsing the discovery list, without
                    // needing an app restart.
                    registerDnsSdLocalService()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── PHASE 6 TRACK B2: hands-free trigger settings ───────────────────────────

    private fun showSosSettingsDialog() {
        val transport = mediaTransport ?: return
        val triggers = transport.sosTriggers
        val labels = arrayOf(
            "Volume-down x5 (screen off OK)",
            "Hardware button long-press (not available on most phones)",
            "Freefall + impact",
            "No motion 20min while separated from party"
        )
        val keys = arrayOf(
            SosTriggers.PREF_VOLUME_DOWN, SosTriggers.PREF_BUTTON_LONGPRESS,
            SosTriggers.PREF_FREEFALL, SosTriggers.PREF_NO_MOTION
        )
        val checked = keys.map { triggers.isEnabled(it) }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle("Hands-free Group Alert triggers")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                triggers.setEnabled(keys[which], isChecked)
            }
            .setNeutralButton("Contacts to notify") { _, _ -> showEmergencyContactsDialog() }
            // BUG 1 FIX 4: plain, discoverable escape hatch for stale test-session
            // SOS state — see MeshSosManager.clearStoredAlerts's doc for exactly
            // what this does and does not touch.
            .setNegativeButton("Clear stored alerts") { _, _ -> confirmClearStoredAlerts() }
            .setPositiveButton("Done", null)
            .show()
    }

    private fun confirmClearStoredAlerts() {
        AlertDialog.Builder(this)
            .setTitle("Clear stored alerts?")
            .setMessage(
                "Removes every cached/historical Group Alert record on this device, including " +
                    "ones being carried for other members. Does not cancel your own Group Alert " +
                    "if you currently have one active."
            )
            .setPositiveButton("Clear") { _, _ ->
                val transport = mediaTransport ?: return@setPositiveButton
                val n = transport.clearStoredSosAlerts()
                sosEntries.clear()
                renderSosAlerts()
                val activeSenders = transport.activeSosSenderIds()
                sosAlarm.onActiveSendersChanged(activeSenders)
                renderSosOverlay(activeSenders)
                sosOverlay.visibility = if (activeSenders.isNotEmpty()) View.VISIBLE else View.GONE
                Toast.makeText(this, "Cleared $n stored alert(s)", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEmergencyContactsDialog() {
        val transport = mediaTransport ?: return
        val input = EditText(this).apply {
            hint = "comma-separated phone numbers"
            setText(transport.sosRelay.emergencyContacts().joinToString(", "))
        }
        AlertDialog.Builder(this)
            .setTitle("Contacts to notify")
            .setMessage("Used only for the cellular-relay \"tap to send\" prompt — never sent automatically.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val numbers = input.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }
                transport.sosRelay.setEmergencyContacts(numbers)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** PHASE 6 TRACK B3: one tap, no permission — a human still has to confirm
     *  before an emergency SMS actually goes out (see SosRelay's class doc). */
    private fun showRelayPrompt(transport: OfflineMediaTransport, prompt: SosRelay.RelayPrompt) {
        if (!handledRelayPrompts.add(prompt.msgId)) return // already prompted this session
        val toNumber = prompt.contacts.firstOrNull() ?: return
        AlertDialog.Builder(this)
            .setTitle("Tap to send — relaying Group Alert for ${prompt.senderName}") // TOPO 1.3: sentence case
            .setMessage(prompt.smsBody)
            .setCancelable(false)
            .setPositiveButton("Send") { _, _ ->
                try {
                    startActivity(transport.sosRelay.buildSendIntent(prompt, toNumber))
                } catch (e: Exception) {
                    Toast.makeText(this, "No SMS app available: ${e.message}", Toast.LENGTH_LONG).show()
                }
                transport.sosRelay.markRelayed(prompt.msgId)
            }
            .setNegativeButton("Not now") { _, _ -> handledRelayPrompts.remove(prompt.msgId) }
            .show()
    }

    /** PHASE 6 TRACK B2: foreground-reliable companion to SosTriggers' MediaSession
     *  path (screen-off case) — Android routes a volume press to whichever of the
     *  two actually has priority at the moment, never reliably both, so double
     *  counting isn't a practical concern (see SosTriggers.onVolumeDownPress's doc). */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN && event.action == android.view.KeyEvent.ACTION_DOWN) {
            mediaTransport?.sosTriggers?.onVolumeDownPress()
        }
        return super.dispatchKeyEvent(event)
    }

    private fun lastKnownVerticalM(nodeId: Long): Int {
        val transport = mediaTransport ?: return 0
        val latest = transport.ledger.latestEntry(nodeId) ?: return 0
        val p = latest.pressureHpaX10 ?: return 0
        return transport.barometer.relativeAltitudeTo(p / 10.0) ?: 0
    }

    /** PHASE 3B: initiator picks audio or video, then broadcasts the invite —
     *  [OfflineMediaTransport.startGroupCall] joins this device to it immediately. */
    private fun showStartGroupCallDialog() {
        val labels = arrayOf("Audio", "Video")
        AlertDialog.Builder(this)
            .setTitle("Start group call")
            .setItems(labels) { _, which ->
                val mode = if (which == 0) {
                    OfflineMediaTransport.GroupCallMode.AUDIO
                } else {
                    OfflineMediaTransport.GroupCallMode.VIDEO
                }
                mediaTransport?.startGroupCall(mode)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** PHASE 3B: shown on every OTHER member when someone starts a group call.
     *  FIX 4: the "Join" button disables itself the instant it's tapped, before
     *  dismissing — a plain setPositiveButton listener's dismiss-after-return isn't
     *  synchronous with touch input, so a fast double-tap could otherwise fire
     *  acceptGroupCall() twice for the same callId. */
    private fun showGroupCallInviteDialog(fromName: String, mode: OfflineMediaTransport.GroupCallMode, callId: Long) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Group call")
            .setMessage("$fromName started a ${mode.name.lowercase()} group call.")
            .setPositiveButton("Join", null)
            .setNegativeButton("Ignore", null)
            .create()
        dialog.setOnShowListener {
            val joinButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            joinButton.setOnClickListener {
                joinButton.isEnabled = false
                mediaTransport?.acceptGroupCall(callId)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /** Tap a member → the 3-mode dialog → call/message THAT member specifically.
     *  Transparent whether they're directly connected or relayed through the GO. */
    private fun showCallModeDialog(member: RoutingTable.Member) {
        val labels = arrayOf("Video call", "Audio call", "Message")
        AlertDialog.Builder(this)
            .setTitle("Call ${member.name}")
            .setItems(labels) { _, which ->
                val mode = when (which) {
                    0 -> OfflineMediaTransport.CallMode.VIDEO
                    1 -> OfflineMediaTransport.CallMode.AUDIO
                    else -> OfflineMediaTransport.CallMode.CHAT
                }
                startDirectCall(member, mode)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startDirectCall(member: RoutingTable.Member, mode: OfflineMediaTransport.CallMode) {
        isGroupChatScreen = false
        connectedPeerName = member.name
        connectedPeerId = member.nodeId
        openThreadKey = member.nodeId // TOPO 3.1: this thread's unread count won't increment while its overlay is open
        groupScreen.visibility = View.GONE
        callScreen.visibility = View.VISIBLE
        errorText.visibility = View.GONE
        updateForegroundState()
        applyUiForMode(mode)
        startCallTimer()
        mediaTransport?.placeCall(member.nodeId, member.name, mode)
    }

    // fix: voice notes reachable from a real nav path — openGroupChat()
    // RETIRED (was the only caller-less function after routing the roster's
    // "Group chat" row to openMessageThread instead). messagesThreadView
    // already covers everything this did: same chatMessages data, same
    // sendGroupChat path (isGroupChatScreen/connectedPeerId=BROADCAST_ID is
    // openMessageThread's own member==null branch), plus a real nav path
    // and voice notes neither this nor the call-screen chat overlay ever had.

    /** Fires for both the initiator (redundantly, right after startDirectCall already
     *  switched screens — a no-op re-apply) and the callee (the ONLY trigger that
     *  switches it from the roster screen to the call screen — auto-answered, same as
     *  every mode resolution in this app always has been). */
    private fun onCallStarted(peerId: Long, peerName: String, mode: OfflineMediaTransport.CallMode) {
        isGroupChatScreen = false
        connectedPeerName = peerName
        connectedPeerId = peerId
        openThreadKey = peerId
        if (groupScreen.visibility == View.VISIBLE) {
            groupScreen.visibility = View.GONE
            callScreen.visibility = View.VISIBLE
            errorText.visibility = View.GONE
            updateForegroundState()
            startCallTimer()
        }
        applyUiForMode(mode)
    }

    /** The call partner hung up, or their link died — return to the roster without
     *  disturbing the group itself. */
    private fun onCallEndedRemotely(reason: String) {
        Log.d("OFFTRACE", "call ended: $reason")
        returnToRosterScreen()
    }

    private fun returnToRosterScreen() {
        stopCallTimer()
        resetVideoAspectRatio()
        resetChat()
        resetCallModeUi()
        isGroupChatScreen = false
        connectedPeerName = ""
        connectedPeerId = null
        openThreadKey = null
        errorText.visibility = View.GONE
        callScreen.visibility = View.GONE
        // fix: searchScreen/groupScreen overlap — this was the one
        // asymmetric transition in the file (every other groupScreen=VISIBLE
        // site already pairs it with searchScreen=GONE; see e.g. the group-
        // joined and group-call-ended transitions). A call can only be
        // reached once a group has formed, so searchScreen should already
        // be GONE here in practice — this is defensive, closing the one gap
        // rather than relying on that always holding.
        searchScreen.visibility = View.GONE
        groupScreen.visibility = View.VISIBLE
        updateForegroundState()
    }

    // ── PHASE 3B: group call screen ──────────────────────────────────────────────

    /** Fires once this device is a confirmed participant — for the initiator right
     *  after [showStartGroupCallDialog], for everyone else once accepted (see
     *  [showGroupCallInviteDialog]). */
    /** Deliberately does NOT reset groupCallLocalCameraOn/groupCallLocalMicMuted —
     *  when THIS device is the GO, [OfflineMediaTransport.startGroupCall]/
     *  [acceptGroupCall] call setGroupCallCameraOn(true) SYNCHRONOUSLY before
     *  posting onGroupCallStarted (which is what calls this), so its
     *  onGroupCallCamState confirmation is already posted to mainHandler ahead of
     *  this one and would otherwise get clobbered by a blind reset here. Both flags
     *  instead default false from [exitGroupCallScreen]'s cleanup after the
     *  previous call and are only ever set true by the real onGroupCallCamState
     *  callback. */
    private fun enterGroupCallScreen(mode: OfflineMediaTransport.GroupCallMode) {
        groupCallMode = mode
        groupCallActiveSpeaker = null
        groupCallPinned = false
        groupCallFocusedTile = null
        searchScreen.visibility = View.GONE
        groupScreen.visibility = View.GONE
        callScreen.visibility = View.GONE
        groupCallScreen.visibility = View.VISIBLE
        // PART 1 (batch A): groupCallScreen's own addView/LayoutParams
        // (below, in buildGroupCallScreen) are UNCHANGED — this is what
        // actually makes it full-bleed. updateForegroundState hides
        // header/startGroupScanRow/mainScroll (the only other weighted/sized
        // children of `root`) whenever groupCallScreen is VISIBLE, leaving
        // it the sole claimant of root's weighted space — same effect the
        // old contentFrame+bottomNav GONE toggle had, different mechanism.
        updateForegroundState()
        errorText.visibility = View.GONE
        updateGroupCallStatusText()
        updateGroupCallControlsBar()
    }

    /** FIX 1b: reflects RINGING (fewer than 2 participants — a valid, expected state
     *  for a founding call, not an error) vs. a normal in-progress call. */
    private fun updateGroupCallStatusText() {
        val mode = groupCallMode ?: return
        groupCallStatusText.text = if (groupCallParticipants.size < 2) {
            "Group call — ${mode.name.lowercase()} — ringing…"
        } else {
            "Group call — ${mode.name.lowercase()} — ${groupCallParticipants.size} participants"
        }
    }

    private fun updateGroupCallControlsBar() {
        groupCallCameraButton.isEnabled = groupCallMode == OfflineMediaTransport.GroupCallMode.VIDEO
        groupCallCameraButton.text = if (groupCallLocalCameraOn) "Camera: On" else "Camera: Off"
        groupCallMicButton.text = if (groupCallLocalMicMuted) "Mic: Muted" else "Mic: On"
        groupCallInviteButton?.visibility = if (isLocalGroupOwner) View.VISIBLE else View.GONE
    }

    /** A4: mid-call reachability — opens the same nearbyDevices data in a
     *  fresh ListView/NearbyDeviceAdapter pair rather than a second data
     *  model; registered into [midCallDialogAdapter] so it keeps receiving
     *  the same notifyDataSetChanged() every other surface gets (see
     *  notifyNearbyAdaptersChanged) for as long as it's on screen. */
    private fun showMidCallInviteDialog() {
        val listView = ListView(this)
        val adapter = NearbyDeviceAdapter()
        listView.adapter = adapter
        midCallDialogAdapter = adapter
        AlertDialog.Builder(this)
            .setTitle("Invite to group")
            .setView(listView)
            .setPositiveButton("Done", null)
            .setOnDismissListener { midCallDialogAdapter = null }
            .show()
    }

    /** Fires when THIS device's own group call ends — local leave, the call falling
     *  below 2 participants, or a join rejection (see
     *  [OfflineMediaTransport.onGroupCallRejected] for the latter's extra toast).
     *  Returns to the roster without disturbing the group itself, same spirit as
     *  [onCallEndedRemotely]. */
    private fun exitGroupCallScreen(reason: String) {
        Log.d("OFFTRACE", "group call ended: $reason")
        groupTiles.values.forEach { (it.container.parent as? ViewGroup)?.removeView(it.container) }
        groupTiles.clear()
        groupCallGrid.removeAllViews()
        groupCallParticipants = emptyList()
        groupCallCamStates.clear()
        groupCallActiveSpeaker = null
        groupCallPinned = false
        groupCallFocusedTile = null
        groupCallMode = null
        groupCallLocalCameraOn = false
        groupCallLocalMicMuted = false
        errorText.visibility = View.GONE
        groupCallScreen.visibility = View.GONE
        // "Returns to the roster without disturbing the group itself" (see
        // this function's own class doc) — direct restore, not a cached
        // "whichever was visible before" guess: the group session itself is
        // still live (this only ends the CALL), so groupScreen (the roster)
        // is always the correct thing to show next, exactly like every
        // other direct searchScreen/groupScreen transition in this file
        // (group-joined at ~5533, group-left at ~7722).
        searchScreen.visibility = View.GONE
        groupScreen.visibility = View.VISIBLE
        updateForegroundState()
    }

    private fun nameForGroupParticipant(id: Long): String =
        roster.firstOrNull { it.nodeId == id }?.name ?: shortId(id)

    /** Creates a [GroupTile] for any newly-joined participant and tears down (incl.
     *  unregistering its Surface with the transport) any tile whose participant is
     *  no longer in [groupCallParticipants] — e.g. they left or disconnected. Call
     *  before [rebuildGroupCallGrid] whenever the participant list changes. */
    private fun syncGroupTiles() {
        val ids = groupCallParticipants.toSet()
        val gone = groupTiles.keys - ids
        gone.forEach { id ->
            groupTiles.remove(id)?.let { tile -> (tile.container.parent as? ViewGroup)?.removeView(tile.container) }
            groupCallCamStates.remove(id)
            if (id == mediaTransport?.localNodeId) mediaTransport?.setLocalPreviewSurface(null)
            else mediaTransport?.setGroupTileSurface(id, null)
            if (groupCallFocusedTile == id) groupCallFocusedTile = null
        }
        groupCallParticipants.forEach { id ->
            if (id !in groupTiles) groupTiles[id] = createGroupTile(id)
        }
    }

    /** Point 7: reparents each surviving tile into the grid at its computed cell —
     *  or, with a tile focused (point 9), shows just that one at full size.
     *
     *  FIX 4 (3-device black-tile bug): this used to call
     *  [GridLayout.removeAllViews] up front, then re-[addTileToGrid] every
     *  participant on EVERY call — including tiles whose cell didn't actually
     *  change. Detaching a SurfaceView from its parent (even to immediately
     *  re-add it) destroys and recreates its underlying Surface, which fired
     *  surfaceCreated for every already-in-use tile the moment a third device
     *  joined and forced a 1x2->2x2 reshape — releasing their live group
     *  decoders out from under them (see FIX 2/3 in OfflineMediaTransport for
     *  the decoder-side half of this fix). [addTileToGrid] now re-specs an
     *  ALREADY-parented tile's LayoutParams in place instead of detaching it,
     *  so only a genuinely new tile (or one returning from focus mode, where
     *  every other tile really was detached) ever gets a fresh addView — see
     *  that function's doc. Cheap enough at up to 8 tiles (point 10's hard
     *  cap) to just refresh every tile's content each call rather than diff. */
    private fun rebuildGroupCallGrid() {
        val focused = groupCallFocusedTile
        if (focused != null && groupTiles.containsKey(focused)) {
            // Point 9: focus mode reshapes to 1x1 — a rare, user-initiated
            // event (tapping a tile), not something that happens on every
            // join/leave, so the simple full-rebuild path stays here; every
            // OTHER tile really is meant to leave the grid while focused.
            groupCallGrid.removeAllViews()
            groupCallGrid.rowCount = 1
            groupCallGrid.columnCount = 1
            addTileToGrid(focused, 0, 0)
        } else {
            val ids = groupCallParticipants
            val (rows, cols) = gridDimensionsFor(ids.size)
            // STABILITY AUDIT 1a: see gridRebuildMustClearFirst's doc — only
            // clear (removeAllViews(), same order the focus branch above
            // uses) when actually shrinking; a grow keeps addTileToGrid's
            // no-detach reflow-in-place path.
            if (gridRebuildMustClearFirst(groupCallGrid.rowCount, groupCallGrid.columnCount, rows, cols)) {
                groupCallGrid.removeAllViews()
            }
            groupCallGrid.rowCount = rows
            groupCallGrid.columnCount = cols
            ids.forEachIndexed { i, id -> addTileToGrid(id, i / cols, i % cols, localTileWeightFor(ids.size, id, mediaTransport?.localNodeId)) }
        }
        groupTiles.forEach { (id, tile) -> updateTileContent(id, tile) }
        groupCallGrid.post {
            Log.d("OFFTRACE", "UI: grid size=${groupCallGrid.width}x${groupCallGrid.height}")
            groupTiles.forEach { (id, t) ->
                Log.d("OFFTRACE", "UI: tile ${MeshFrame.hex(id)} size=" +
                    "${t.container.width}x${t.container.height} sv=" +
                    "${t.surfaceView.width}x${t.surfaceView.height}")
            }
        }
    }

    /** FIX 4: if [nodeId]'s tile is ALREADY a child of [groupCallGrid] (the
     *  common case — an existing participant whose cell moved because the
     *  grid reshaped, e.g. 1x2->2x2 on a new join), just reassigns its
     *  LayoutParams — a plain [View.setLayoutParams] call, which reflows the
     *  child in place without ever detaching it, so its SurfaceView's Surface
     *  (and whatever decoder is bound to it) survives untouched. Only a tile
     *  that ISN'T already parented here (brand new, or returning from focus
     *  mode) goes through an actual remove+addView. */
    private fun addTileToGrid(nodeId: Long, row: Int, col: Int, weight: Float = 1f) {
        val tile = groupTiles[nodeId] ?: return
        // FIX: GridLayout.spec(index, span, weight) alone leaves cell alignment at
        // its non-FILL default — combined with width/height=0 that measures the
        // tile to 0x0 (no BufferQueue ever gets allocated for a 0x0 SurfaceView, so
        // surfaceCreated never fires and setGroupTileSurface/setLocalPreviewSurface
        // are never called). All three of GridLayout.FILL on both specs,
        // width/height=0, and setGravity(Gravity.FILL) are required together for
        // the weighted cell to actually stretch to fill its allotted space.
        // OCP PHASE 5.3: [weight] defaults to 1f (every existing caller/shape
        // is unaffected) — only rebuildGroupCallGrid's n==8 case passes a
        // reduced weight, see [localTileWeightFor].
        val params = GridLayout.LayoutParams(
            GridLayout.spec(row, 1, GridLayout.FILL, weight),
            GridLayout.spec(col, 1, GridLayout.FILL, weight)
        ).apply {
            width = 0
            height = 0
            setGravity(Gravity.FILL)
        }
        if (tile.container.parent === groupCallGrid) {
            tile.container.layoutParams = params
        } else {
            (tile.container.parent as? ViewGroup)?.removeView(tile.container)
            groupCallGrid.addView(tile.container, params)
        }
    }

    /** Refreshes one tile's video-vs-avatar visibility, name, speaking highlight,
     *  and (self only) battery — called after any camera-state, speaker, or grid
     *  change. Battery is local-only, same reasoning as [readBatteryPercent]'s doc:
     *  no wire frame carries other participants' battery level in this phase. */
    private fun updateTileContent(nodeId: Long, tile: GroupTile) {
        val isMe = nodeId == mediaTransport?.localNodeId
        val camOn = isMe && groupCallLocalCameraOn || !isMe && groupCallCamStates[nodeId] == true
        // FIX: a GONE SurfaceView never produces a Surface — surfaceCreated would
        // never fire and setGroupTileSurface/setLocalPreviewSurface would never be
        // called, so the decoder/camera could never bind to it once camOn actually
        // went true. Always keep it VISIBLE; the avatar overlay (added as a later
        // sibling in the FrameLayout, see createGroupTile) draws on top of it and
        // hides it when the camera is off instead.
        tile.surfaceView.visibility = View.VISIBLE
        tile.avatarText.visibility = if (camOn) View.GONE else View.VISIBLE
        Log.d("OFFTRACE", "UI: tile ${MeshFrame.hex(nodeId)} camOn=$camOn surfaceValid=${tile.surfaceView.holder.surface?.isValid}")
        val name = nameForGroupParticipant(nodeId)
        tile.avatarText.text = initialsFor(name)
        tile.nameLabel.text = name + if (isMe) " (you)" else ""
        tile.speakingDot.visibility = if (nodeId == groupCallActiveSpeaker) View.VISIBLE else View.GONE
        if (isMe) {
            val pct = readBatteryPercent()
            tile.batteryText.visibility = if (pct != null) View.VISIBLE else View.GONE
            if (pct != null) tile.batteryText.text = "🔋 $pct%"
        } else {
            tile.batteryText.visibility = View.GONE
        }
        // PHASE 8 STEP 2/4: only meaningful for a remote peer whose camera IS
        // on (matches this function's own "camOn" gate above) — a camera-off
        // tile already shows the avatar and neither state applies to it.
        // Degraded (STEP 2, decoder actually broken) takes priority over a
        // mere budget exclusion (STEP 4, decoder simply not allocated right
        // now) when reporting which one is showing, since a viewer needs to
        // know THAT is genuinely wrong, not just "not currently prioritized."
        val transport = mediaTransport
        val degraded = !isMe && camOn && transport?.isGroupPeerDegraded(nodeId) == true
        val budgetExcluded = !isMe && camOn && !degraded && transport?.isGroupPeerBudgetExcluded(nodeId) == true
        when {
            degraded -> {
                tile.statusScrim.visibility = View.VISIBLE
                tile.statusText.visibility = View.VISIBLE
                tile.statusText.text = "Video unavailable"
            }
            budgetExcluded -> {
                // Frozen last-decoded frame stays visible underneath (the
                // surface simply isn't being fed new frames) — just dimmed,
                // no text, so it doesn't read as broken.
                tile.statusScrim.visibility = View.VISIBLE
                tile.statusText.visibility = View.GONE
            }
            else -> {
                tile.statusScrim.visibility = View.GONE
                tile.statusText.visibility = View.GONE
            }
        }
    }

    /** Point 9: tapping a tile shows it fullscreen; tapping the same one again
     *  returns to the grid. PHASE 8 STEP 4: also pins/unpins this peer in the
     *  tile decode budget, so a tap always keeps them decoded regardless of
     *  speaking history. */
    private fun onTileTapped(nodeId: Long) {
        groupCallFocusedTile = if (groupCallFocusedTile == nodeId) null else nodeId
        mediaTransport?.setTileBudgetPin(groupCallFocusedTile)
        rebuildGroupCallGrid()
    }

    /** Local battery only — there's no wire frame carrying OTHER participants'
     *  battery level in this phase's protocol (see the group call class doc), so
     *  every OTHER tile simply omits it rather than showing fabricated data. */
    private fun readBatteryPercent(): Int? {
        val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return level * 100 / scale
    }

    private fun onHangupClicked() {
        // A group-chat-only screen never placed a call, so there's nothing to end —
        // just go back. A real call sends TYPE_HANGUP to the partner (best-effort;
        // endCall() no-ops harmlessly if the call already ended some other way).
        if (!isGroupChatScreen) {
            mediaTransport?.endCall()
        }
        returnToRosterScreen()
    }

    // ── Leaving the group entirely ───────────────────────────────────────────────

    /** FIX 2c: fired ONLY when onConnectionChangedInternal's generation check
     *  (formedGeneration > 0) has confirmed a groupFormed=false callback is a
     *  REAL teardown of the group this device currently believes is live —
     *  never for a stale/pre-formation callback (those return earlier and
     *  never reach here). leaveGroup() already does everything this needs:
     *  closes the signaling ServerSocket (LocalSignaling.stop() -> port
     *  8888), closes the media listener (OfflineMediaTransport.stop() ->
     *  closeSockets() -> port 8889), cancels pending work
     *  (resetConnectionAttemptState -> cancelGroupFormationTimeout), resets
     *  every piece of session state, and calls proceedToDiscovery() at the
     *  end. What was MISSING before this fix was simply that nothing ever
     *  called leaveGroup() from this specific detection path — the old
     *  "ignoring stale/teardown connInfo" branch swallowed it unconditionally
     *  whenever connectRequested was false (always true on the GO side). */
    private fun handleRealGroupTeardown() {
        leaveGroup("wifi direct group teardown (real, generation-confirmed)", persistentErrorMessage = "Connection lost — the Wi-Fi Direct group ended")
    }

    /** PHASE 3: full teardown of the WiFi Direct group + mesh session — this used to
     *  be what a single call's end/link-loss did (Phase 2 had no group to leave, just
     *  the one call). Now triggered by: the "Leave group" button, the underlying
     *  signaling connection going away, or the mesh session itself dying.
     *  [persistentErrorMessage] is set only for a protocol version mismatch — kept
     *  visible via a Toast rather than the transient errorText, same as before. */
    private fun leaveGroup(reason: String, persistentErrorMessage: String? = null) {
        Log.e("OFFTRACE", "leaving group ($reason)")
        statusText.text = "Disconnected"
        currentGroupFormed = false
        // FIX 1c/2: leaveGroup is the ONE shared teardown path (button tap,
        // media link lost, signaling lost, and now a real WFD-level
        // teardown) — resetting the generation and resuming discovery HERE,
        // rather than only in handleRealGroupTeardown, means every trigger
        // leaves the app in the same consistent "ready to scan again" state.
        formedGeneration = 0
        resumeNearbyRefreshLoop()
        updateRadarSweeping() // FIX 4e: group ended — radar resumes sweeping too
        signaling?.stop()
        signaling = null
        // PART "HASSLE-FREE JOIN" 2.3: stop advertising "group open" the
        // instant this device leaves — before mediaTransport (and its
        // bleBeacon reference) goes away below.
        mediaTransport?.bleBeacon?.setGroupOpen(false)
        mediaTransport?.stop()
        mediaTransport = null
        stopOfflineCallService()
        transport.stopDiscovery() // PART 1.3: converted — bare call, no result callback
        // LEAK (PART 1.3): no interface concept for "leave the current
        // group but keep the transport running" distinct from stop() —
        // MeshTransport only has stop() (full shutdown). Stays direct.
        wifiDirect.disconnect()
        resetConnectionAttemptState()
        resetVideoAspectRatio()
        resetChat()
        resetCallModeUi()
        stopCallTimer()
        isGroupChatScreen = false
        connectedPeerName = ""
        connectedPeerId = null
        openThreadKey = null
        roster = emptyList()
        isLocalGroupOwner = false
        // BUG (GROUP FORMS, NOBODY JOINS) FIX: this device's own hosted-group
        // credentials don't survive past this group's lifetime.
        hostedGroupNetworkName = null
        hostedGroupPassphrase = null
        nearbyDevices.clear()
        pendingInvites.clear()
        announcedIncoming.clear()
        announcedIncomingP2pInvite.clear()
        // PART "HASSLE-FREE JOIN" 2.2: mediaTransport.stop() above can stop
        // the shared MeshBleBeacon singleton with it (no reference count
        // between the two owners — see startPreConnectionBleDiscovery's own
        // doc) — resume it so the Nearby screen's open-groups discovery
        // keeps working after leaving a group, not just before the first one.
        startPreConnectionBleDiscovery()
        lastKnownPeerStatus.clear()
        notifyNearbyAdaptersChanged()
        groupCallParticipants = emptyList()
        groupCallActiveSpeaker = null
        groupCallPinned = false
        groupCallFocusedTile = null
        groupCallCamStates.clear()
        groupTiles.values.forEach { (it.container.parent as? ViewGroup)?.removeView(it.container) }
        groupTiles.clear()
        groupCallGrid.removeAllViews()
        groupCallMode = null
        groupCallLocalCameraOn = false
        groupCallLocalMicMuted = false
        // PHASE 5A: mediaTransport?.stop() above already shuts down MeshSosManager/
        // OfflineLocationProvider on the transport side — this just clears this
        // Activity's own local mirror so a stale SOS/Find alert doesn't survive
        // into the next session.
        sosActive = false
        sosEntries.clear()
        findResponses.clear()
        updateSosButtonUi()
        renderSosAlerts()
        // PHASE 5BC: same "stale alert must not survive into the next session"
        // reasoning as the PHASE 5A clear above — drive the siren back to silent
        // through its normal empty-set transition rather than an ad hoc stop, and
        // hide both overlays.
        sosAlarm.onActiveSendersChanged(emptySet())
        sosOverlay.visibility = View.GONE
        partyStatusOverlay.visibility = View.GONE
        if (::groupsOverlay.isInitialized) groupsOverlay.visibility = View.GONE
        if (::offlineSettingsOverlay.isInitialized) offlineSettingsOverlay.visibility = View.GONE
        if (::messagesThreadView.isInitialized) messagesThreadView.visibility = View.GONE
        errorText.visibility = View.GONE
        callScreen.visibility = View.GONE
        groupScreen.visibility = View.GONE
        groupCallScreen.visibility = View.GONE
        searchScreen.visibility = View.VISIBLE
        updateForegroundState()
        if (persistentErrorMessage != null) {
            Toast.makeText(this, persistentErrorMessage, Toast.LENGTH_LONG).show()
        }
        proceedToDiscovery()
    }

    // ── FIX 3: foreground service anchor ────────────────────────────────────────

    private fun startOfflineCallService() {
        val intent = Intent(this, OfflineCallService::class.java).apply {
            action = OfflineCallService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopOfflineCallService() {
        val intent = Intent(this, OfflineCallService::class.java).apply {
            action = OfflineCallService.ACTION_STOP
        }
        startService(intent)
    }

    override fun onStop() {
        super.onStop()
        // FIX 3: onStop fires for reasons unrelated to the user ending the call — screen
        // off, the system's own Wi-Fi Direct connect dialog, a notification pull-down —
        // any of which could happen mid-negotiation or mid-call. Tearing down the P2P
        // group here (as this used to) was the actual cause of groupFormed flapping
        // true->false during setup. Only pause discovery; disconnect() now only happens
        // in leaveGroup()/onDestroy(), i.e. real end-of-group/end-of-activity.
        Log.d("OFFTRACE", "onStop — keeping session alive")
        transport.stopDiscovery() // PART 1.3: converted — bare call, no result callback
        // PHASE 5BC: an immediate ledger flush, not a stop — the mesh session (and
        // the ambient position feed) keeps running with the screen off, see the
        // comment above; this just makes sure onStop itself isn't a data-loss
        // window, per the persistence decision this phase was built against.
        mediaTransport?.ledger?.flushNow()
    }

    /** PEER DIRECTION READOUT STEP 6: register in onResume, unregister in
     *  onPause — no sensor work while the screen is off. */
    override fun onResume() {
        super.onResume()
        meshCompass.onReading = { reading ->
            updatePartyArrows(reading)
            // OFFLINE UI STEP 2: same Reading, two independent consumers —
            // updatePartyArrows (row-list arrows) and the ring — neither
            // touches peer TEXT, only rotation/lock state (see PartyRingView's
            // class doc for why that split is what keeps text immune to
            // rotation).
            if (::partyRingView.isInitialized) partyRingView.setAzimuth(reading.trueAzimuthDeg, reading.accurate)
        }
        // OFFLINE UI STEP 5: battery cliff isn't started/stopped here — it's
        // gated by inBatteryCliff itself (evaluateBatteryCliff only starts
        // the compass if NOT currently in cliff), matching "phrase grid and
        // SOS still fully live" even across a screen pause/resume.
        if (!inBatteryCliff) meshCompass.start()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        evaluateBatteryCliff()
        startPartyRowRefreshLoop()
        // FIX 4c: radar sweep is part of "no sensor/animation work while the
        // screen is off" too — same onResume/onPause gate as the compass above.
        activityResumed = true
        updateRadarSweeping()
    }

    override fun onPause() {
        super.onPause()
        meshCompass.stop()
        meshCompass.onReading = null
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        stopPartyRowRefreshLoop()
        activityResumed = false
        updateRadarSweeping()
    }

    override fun onDestroy() {
        super.onDestroy()
        // FIX 4 (idle-session): onDestroy() fires both when the user genuinely finishes
        // this screen (back press -> finish(), isFinishing == true) AND when the system
        // destroys just this Activity object to reclaim memory from a backgrounded/
        // locked screen while keeping the hosting process — and its foreground service
        // — alive (isFinishing == false then). This used to tear the whole group down
        // unconditionally, so Activity death alone (nothing the user asked for) could
        // kill a perfectly healthy session. Only tear down on a genuine finish.
        // wifiDirect itself was constructed with applicationContext (see onCreate), so
        // its broadcast receiver keeps working regardless of this Activity's lifecycle
        // — a system-reclaimed (non-finishing) destroy here leaves mediaTransport and
        // signaling running, anchored by the still-live foreground service, exactly as
        // intended. Known limitation of this minimal fix (vs. moving transport
        // ownership into the service): a freshly re-created Activity after such a
        // reclaim has no way to rediscover that still-running session — it starts with
        // null mediaTransport/signaling fields, same as a first launch.
        if (isFinishing) {
            signaling?.stop()
            mediaTransport?.stop()
            stopOfflineCallService() // safety net in case leaveGroup didn't run
            transport.stopDiscovery() // PART 1.3: converted — bare call, no result callback
            // LEAK (PART 1.3): see leaveGroup's identical disconnect() leak note.
            wifiDirect.disconnect()
            transport.stop() // PART 1.3: converted — was wifiDirect.teardown()
        }
        // D3: unconditional, NOT inside the isFinishing block above — this
        // Activity INSTANCE is going away either way (a non-finishing
        // destroy still means a fresh instance gets created on the next
        // resume, per this function's own doc). sosAlarm/sosSsidBroadcast
        // are process-wide singletons (SosAlarm.get/SosSsidBroadcast.get in
        // onCreate) that outlive any one Activity instance; their
        // onAutoStopTimeout/onRejoinWindow/onStateChanged closures all
        // capture `this` (via runOnUiThread and direct field/method
        // references). Left assigned, the singleton holds this destroyed
        // Activity alive forever (a real leak) AND, worse, can still FIRE
        // that stale closure later — updating views on a dead Activity — if
        // the timeout/rejoin-window/state-change event happens to land after
        // this exact instance was destroyed but before a replacement one
        // re-registers its own.
        sosAlarm.onAutoStopTimeout = null
        sosSsidBroadcast.onRejoinWindow = null
        sosSsidBroadcast.onStateChanged = null
        resetConnectionAttemptState()
        stopCallTimer()
        nearbyRefreshHandler.removeCallbacksAndMessages(null)
        // B5: voiceNoteRecorder/voiceNotePlayer are this-Activity-instance-scoped
        // (unlike sosAlarm/sosSsidBroadcast above) — always clean up regardless
        // of isFinishing, same reasoning as every other per-instance resource
        // in this unconditional block.
        voiceNoteRecorder.cancel()
        stopVoiceNotePlayback()
    }
}
