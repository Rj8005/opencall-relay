package com.opencall.relay.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 1.5a/1.5b: pure-JVM tests for [OfflineCallActivity.mergePartyView] /
 * [OfflineCallActivity.formatPartyStatusLine] — the report-C4 fix ("the ring
 * and the status line must never compute a peer count from two different
 * sources again"). Both functions take plain data in (no Activity, no
 * Context, no mediaTransport), so this never instantiates the Activity
 * itself (this project has no Robolectric dependency — see
 * PartyRingViewTest/MeshCompassTest for the same constraint).
 */
class OfflineCallActivityTest {

    private fun member(id: Long, name: String) = RoutingTable.Member(id, name)

    private fun nameForTest(id: Long): (Long) -> String = { it2 -> if (it2 == id) "Ledger-$id" else "Ledger-$it2" }

    // ── 1.5a: mergePartyView ─────────────────────────────────────────────

    @Test
    fun `roster-only entries are tagged IN_ROSTER`() {
        val result = OfflineCallActivity.mergePartyView(
            rosterMembers = listOf(member(1L, "Alice")),
            ledgerKnownIds = emptySet(),
            localId = 99L,
            nameFor = { "unused" }
        )
        assertEquals(1, result.size)
        assertEquals(OfflineCallActivity.PartyEntry(1L, "Alice", OfflineCallActivity.PartyOrigin.IN_ROSTER), result[0])
    }

    @Test
    fun `ledger-only entries are tagged LEDGER_ONLY and named via nameFor`() {
        val result = OfflineCallActivity.mergePartyView(
            rosterMembers = emptyList(),
            ledgerKnownIds = setOf(2L),
            localId = 99L,
            nameFor = { id -> "Ledger-$id" }
        )
        assertEquals(1, result.size)
        assertEquals(OfflineCallActivity.PartyEntry(2L, "Ledger-2", OfflineCallActivity.PartyOrigin.LEDGER_ONLY), result[0])
    }

    @Test
    fun `a node present in both sources appears exactly once, with origin IN_ROSTER and the roster name winning`() {
        val result = OfflineCallActivity.mergePartyView(
            rosterMembers = listOf(member(3L, "Roster Name")),
            ledgerKnownIds = setOf(3L),
            localId = 99L,
            nameFor = { "Ledger Name (should never appear)" }
        )
        assertEquals(1, result.size)
        assertEquals(OfflineCallActivity.PartyEntry(3L, "Roster Name", OfflineCallActivity.PartyOrigin.IN_ROSTER), result[0])
    }

    @Test
    fun `both sources merge — roster and ledger-only entries both appear`() {
        val result = OfflineCallActivity.mergePartyView(
            rosterMembers = listOf(member(1L, "Alice"), member(3L, "Carl")),
            ledgerKnownIds = setOf(2L, 3L),
            localId = 99L,
            nameFor = { id -> "Ledger-$id" }
        )
        assertEquals(3, result.size)
        assertEquals(setOf(1L, 2L, 3L), result.map { it.nodeId }.toSet())
        assertEquals(OfflineCallActivity.PartyOrigin.IN_ROSTER, result.first { it.nodeId == 1L }.origin)
        assertEquals(OfflineCallActivity.PartyOrigin.LEDGER_ONLY, result.first { it.nodeId == 2L }.origin)
        // 3L is in both — IN_ROSTER must win, and it must not be duplicated.
        assertEquals(OfflineCallActivity.PartyOrigin.IN_ROSTER, result.first { it.nodeId == 3L }.origin)
        assertEquals("Carl", result.first { it.nodeId == 3L }.name)
    }

    @Test
    fun `self is excluded from both sources`() {
        val result = OfflineCallActivity.mergePartyView(
            rosterMembers = listOf(member(99L, "Me"), member(1L, "Alice")),
            ledgerKnownIds = setOf(99L, 2L),
            localId = 99L,
            nameFor = { id -> "Ledger-$id" }
        )
        assertEquals(setOf(1L, 2L), result.map { it.nodeId }.toSet())
    }

    // ── 1.5b: formatPartyStatusLine ──────────────────────────────────────

    @Test
    fun `0 connected and 0 last seen`() {
        assertEquals("0 connected", OfflineCallActivity.formatPartyStatusLine(emptyList()))
    }

    @Test
    fun `N connected, 0 last seen omits the last-seen clause`() {
        val entries = listOf(
            OfflineCallActivity.PartyEntry(1L, "A", OfflineCallActivity.PartyOrigin.IN_ROSTER),
            OfflineCallActivity.PartyEntry(2L, "B", OfflineCallActivity.PartyOrigin.IN_ROSTER)
        )
        assertEquals("2 connected", OfflineCallActivity.formatPartyStatusLine(entries))
    }

    @Test
    fun `0 connected, M last seen still states 0 connected explicitly`() {
        val entries = listOf(
            OfflineCallActivity.PartyEntry(1L, "A", OfflineCallActivity.PartyOrigin.LEDGER_ONLY),
            OfflineCallActivity.PartyEntry(2L, "B", OfflineCallActivity.PartyOrigin.LEDGER_ONLY),
            OfflineCallActivity.PartyEntry(3L, "C", OfflineCallActivity.PartyOrigin.LEDGER_ONLY)
        )
        assertEquals("0 connected, 3 last seen", OfflineCallActivity.formatPartyStatusLine(entries))
    }

    @Test
    fun `N connected, M last seen renders both counts`() {
        val entries = listOf(
            OfflineCallActivity.PartyEntry(1L, "A", OfflineCallActivity.PartyOrigin.IN_ROSTER),
            OfflineCallActivity.PartyEntry(2L, "B", OfflineCallActivity.PartyOrigin.IN_ROSTER),
            OfflineCallActivity.PartyEntry(3L, "C", OfflineCallActivity.PartyOrigin.IN_ROSTER),
            OfflineCallActivity.PartyEntry(4L, "D", OfflineCallActivity.PartyOrigin.IN_ROSTER),
            OfflineCallActivity.PartyEntry(5L, "E", OfflineCallActivity.PartyOrigin.LEDGER_ONLY),
            OfflineCallActivity.PartyEntry(6L, "F", OfflineCallActivity.PartyOrigin.LEDGER_ONLY)
        )
        assertEquals("4 connected, 2 last seen", OfflineCallActivity.formatPartyStatusLine(entries))
    }

    @Test
    fun `end to end — merge then format matches the exact worked example from the task`() {
        val entries = OfflineCallActivity.mergePartyView(
            rosterMembers = listOf(member(1L, "A"), member(2L, "B"), member(3L, "C"), member(4L, "D")),
            ledgerKnownIds = setOf(5L, 6L),
            localId = 99L,
            nameFor = { id -> "Ledger-$id" }
        )
        assertTrue(OfflineCallActivity.formatPartyStatusLine(entries) == "4 connected, 2 last seen")
    }

    // ── PHASE 1.6d: buildRingPeers — the root-cause fix's core claim ──────
    // "A ledger-only UNKNOWN peer survives feedPartyRing's mapNotNull."
    // MeshLedger.vectorTo() never returns null (an unknown peer comes back
    // as PeerState.UNKNOWN with NaN fields, not absent — see MeshLedger.kt's
    // computeVector doc) — the only null case buildRingPeers/feedPartyRing
    // ever sees is vectorLookup itself returning null, which only happens
    // when mediaTransport is null (computePeerVector's guard).

    private fun unknownVector() = MeshLedger.PeerVector(
        distM = Float.NaN, bearingTrue = Float.NaN, peerHeading = Float.NaN, speed = Float.NaN,
        ageS = 0L, vertM = null, state = MeshLedger.PeerState.UNKNOWN
    )

    @Test
    fun `a ledger-only UNKNOWN peer survives buildRingPeers`() {
        val result = OfflineCallActivity.buildRingPeers(
            nodeIds = setOf(7L),
            vectorLookup = { unknownVector() },
            nameFor = { "Ledger-7" },
            isArticulation = { false },
            rssiTrend = { null }
        )
        assertEquals(1, result.size)
        assertEquals(MeshLedger.PeerState.UNKNOWN, result[0].vector.state)
    }

    @Test
    fun `only a null vectorLookup result drops a peer, never the state itself`() {
        val result = OfflineCallActivity.buildRingPeers(
            nodeIds = setOf(1L, 2L, 3L),
            vectorLookup = { id -> if (id == 2L) null else unknownVector() },
            nameFor = { id -> "Peer-$id" },
            isArticulation = { false },
            rssiTrend = { null }
        )
        assertEquals(setOf(1L, 3L), result.map { it.nodeId }.toSet())
    }

    // ── PHASE 2.3/2.4: shortIdBase32 — display-only short ID ──────────────

    @Test
    fun `shortIdBase32 is always exactly 6 characters`() {
        assertEquals(6, OfflineCallActivity.shortIdBase32(0L).length)
        assertEquals(6, OfflineCallActivity.shortIdBase32(-1L).length)
        assertEquals(6, OfflineCallActivity.shortIdBase32(0x1234567890ABCDEFL).length)
    }

    @Test
    fun `shortIdBase32 alphabet excludes the classic ambiguous characters`() {
        val ambiguous = setOf('I', 'O', '0', '1')
        for (nodeId in listOf(0L, -1L, 0x1234567890ABCDEFL, Long.MAX_VALUE, Long.MIN_VALUE)) {
            val s = OfflineCallActivity.shortIdBase32(nodeId)
            for (c in s) {
                assertTrue("shortIdBase32($nodeId)='$s' contained ambiguous char '$c'", c !in ambiguous)
            }
        }
    }

    @Test
    fun `shortIdBase32 is uppercase`() {
        val s = OfflineCallActivity.shortIdBase32(0x1234567890ABCDEFL)
        assertEquals(s.uppercase(), s)
    }

    @Test
    fun `shortIdBase32 known nodeId produces the expected string — top 30 bits, MSB first`() {
        // nodeId = 0xFFFFFFFF00000000 -> top 30 bits are all 1s -> 6 chars of
        // alphabet index 31, the alphabet's LAST character.
        val allOnes = OfflineCallActivity.shortIdBase32(-0x100000000L) // 0xFFFFFFFF00000000
        assertEquals("999999", allOnes) // '9' is index 31 (last) in the 32-char alphabet
        // nodeId = 0 -> top 30 bits all 0 -> 6 chars of alphabet index 0 ('A').
        assertEquals("AAAAAA", OfflineCallActivity.shortIdBase32(0L))
    }

    @Test
    fun `shortIdBase32 is a pure function of nodeId — same input, same output`() {
        val id = 0x1234567890ABCDEFL
        assertEquals(OfflineCallActivity.shortIdBase32(id), OfflineCallActivity.shortIdBase32(id))
    }

    // ── PHASE 2.4: PREF_CALL_BUTTON_LONGPRESS vs SOS's PREF_BUTTON_LONGPRESS
    // — the task's own collision warning: an SOS pref is already
    // "trigger_button_longpress", and the new in-call pref must be a
    // DIFFERENT string, never reusing or shadowing it. Setting one must
    // never be readable back on the other's key.

    @Test
    fun `call_button_longpress and the SOS button-longpress trigger use different keys`() {
        assertEquals("call_button_longpress", OfflineCallActivity.PREF_CALL_BUTTON_LONGPRESS)
        assertEquals("trigger_button_longpress", SosTriggers.PREF_BUTTON_LONGPRESS)
        assertTrue(OfflineCallActivity.PREF_CALL_BUTTON_LONGPRESS != SosTriggers.PREF_BUTTON_LONGPRESS)
    }

    // ── PHASE 3.10: QR invite payload encode/decode round trip ────────────

    @Test
    fun `hosting QR payload round trips through encode then decode`() {
        val encoded = OfflineCallActivity.encodeHostingQrPayload(
            "00000000004d2000", "cHVia2V5Ynl0ZXM=", "Alice", "00000000004d2000", "DIRECT-ocp-ABCDEF", "somepassphrase"
        )
        val decoded = OfflineCallActivity.decodeQrPayload(encoded)
        assertTrue(decoded != null)
        assertEquals(0x4d2000L, decoded!!.nodeId)
        assertEquals("cHVia2V5Ynl0ZXM=", decoded.pubkeyB64)
        assertEquals("Alice", decoded.name)
        assertEquals("00000000004d2000", decoded.groupId)
        assertEquals("DIRECT-ocp-ABCDEF", decoded.ss)
        assertEquals("somepassphrase", decoded.pw)
    }

    @Test
    fun `pairing QR payload round trips through encode then decode — no group, no ss-pw`() {
        val encoded = OfflineCallActivity.encodePairingQrPayload("00000000004d2000", "cHVia2V5Ynl0ZXM=", "Alice")
        val decoded = OfflineCallActivity.decodeQrPayload(encoded)
        assertTrue(decoded != null)
        assertEquals(0x4d2000L, decoded!!.nodeId)
        assertEquals("cHVia2V5Ynl0ZXM=", decoded.pubkeyB64)
        assertEquals("Alice", decoded.name)
        assertEquals("", decoded.groupId)
        assertEquals(null, decoded.ss)
        assertEquals(null, decoded.pw)
    }

    // ── PART "WHY THE QR JOIN FAILS" C3: the hosting and pairing payloads
    // are distinct functions/shapes, not one nullable-parameter function
    // silently emitting either — see encodeHostingQrPayload/
    // encodePairingQrPayload's own docs. ──

    @Test
    fun `pairing payload never contains ss or pw keys at all`() {
        val payload = OfflineCallActivity.encodePairingQrPayload("00000000004d2000", "cHVia2V5Ynl0ZXM=", "Alice")
        assertFalse(payload.contains("\"ss\""))
        assertFalse(payload.contains("\"pw\""))
    }

    @Test
    fun `hosting payload always contains both ss and pw — non-nullable by construction`() {
        val payload = OfflineCallActivity.encodeHostingQrPayload(
            "00000000004d2000", "cHVia2V5Ynl0ZXM=", "Alice", "00000000004d2000", "DIRECT-ocp-ABCDEF", "somepassphrase"
        )
        assertTrue(payload.contains("\"ss\":\"DIRECT-ocp-ABCDEF\""))
        assertTrue(payload.contains("\"pw\":\"somepassphrase\""))
    }

    // ── PART "WHY THE QR JOIN FAILS" C1/C2/C6: hasValidHostCredentials —
    // the pure gate standing between requestGroupInfo()'s result and
    // showHostQrScreen; proves "a QR that cannot work never appears" at
    // the one point that actually decides it. ──

    @Test
    fun `hasValidHostCredentials is true only when both networkName and passphrase are non-blank`() {
        assertTrue(OfflineCallActivity.hasValidHostCredentials("DIRECT-ocp-ABCDEF", "somepassphrase"))
        assertFalse(OfflineCallActivity.hasValidHostCredentials(null, "somepassphrase"))
        assertFalse(OfflineCallActivity.hasValidHostCredentials("DIRECT-ocp-ABCDEF", null))
        assertFalse(OfflineCallActivity.hasValidHostCredentials(null, null))
        assertFalse(OfflineCallActivity.hasValidHostCredentials("", "somepassphrase"))
        assertFalse(OfflineCallActivity.hasValidHostCredentials("DIRECT-ocp-ABCDEF", ""))
        assertFalse(OfflineCallActivity.hasValidHostCredentials("   ", "somepassphrase"))
    }

    @Test
    fun `malformed QR (not JSON) decodes to null, never throws`() {
        assertEquals(null, OfflineCallActivity.decodeQrPayload("not json at all"))
    }

    @Test
    fun `malformed QR (valid JSON, missing nodeId) decodes to null`() {
        assertEquals(null, OfflineCallActivity.decodeQrPayload("""{"v":1,"name":"Alice"}"""))
    }

    @Test
    fun `malformed QR (nodeId not valid hex) decodes to null`() {
        assertEquals(null, OfflineCallActivity.decodeQrPayload("""{"v":1,"nodeId":"not-hex!","name":"Alice"}"""))
    }

    @Test
    fun `QR missing optional fields still decodes — name falls back to short id`() {
        val decoded = OfflineCallActivity.decodeQrPayload("""{"v":1,"nodeId":"00000000004d2000"}""")
        assertTrue(decoded != null)
        assertEquals("4d2000", decoded!!.name) // takeLast(6) of the hex nodeId
        assertEquals("", decoded.pubkeyB64)
        assertEquals("", decoded.groupId)
    }

    @Test
    fun `empty string is malformed and decodes to null`() {
        assertEquals(null, OfflineCallActivity.decodeQrPayload(""))
    }

    // ── PART 2.1: versioned, self-describing QR payload ────────────────────

    @Test
    fun `decoded payload carries the QR protocol version`() {
        val encoded = OfflineCallActivity.encodePairingQrPayload("00000000004d2000", "cHVia2V5Ynl0ZXM=", "Alice")
        val decoded = OfflineCallActivity.decodeQrPayload(encoded)!!
        assertEquals(OfflineCallActivity.QR_PROTOCOL_VERSION, decoded.version)
    }

    @Test
    fun `a QR payload with no version field is rejected`() {
        assertEquals(null, OfflineCallActivity.decodeQrPayload("""{"nodeId":"00000000004d2000","name":"Alice"}"""))
    }

    @Test
    fun `a QR payload with v 0 is rejected`() {
        assertEquals(null, OfflineCallActivity.decodeQrPayload("""{"v":0,"nodeId":"00000000004d2000","name":"Alice"}"""))
    }

    @Test
    fun `a QR payload from a NEWER version still decodes — forward compatible`() {
        val decoded = OfflineCallActivity.decodeQrPayload("""{"v":99,"nodeId":"00000000004d2000","name":"Alice"}""")
        assertTrue(decoded != null)
        assertEquals(99, decoded!!.version)
        assertEquals("Alice", decoded.name)
    }

    // ── PART 2.1 OUTPUT: pairing payload size and the QR version it needs
    // at error-correction level M — measured against zxing's own encoder,
    // not asserted from a guess, for a realistic worst-case payload (a
    // hosting device: full pubkey + a real Wi-Fi Direct network name and a
    // 63-char WPA2-PSK passphrase, the maximum passphrase length). ──

    @Test
    fun `pairing payload (hosting, worst-case field lengths) fits one QR at EC level M`() {
        val pubkeyB64 = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
        val payload = OfflineCallActivity.encodeHostingQrPayload(
            nodeIdHex = "00000000004d2000",
            pubkeyB64 = pubkeyB64,
            name = "A".repeat(32), // OfflineIdentity's own max display-name length
            groupId = "00000000004d2000",
            ss = "DIRECT-xy-OpenCall-AB12CD34",
            pw = "P".repeat(63) // WPA2-PSK's maximum passphrase length
        )
        val byteCount = payload.toByteArray(Charsets.UTF_8).size
        val qrCode = com.google.zxing.qrcode.encoder.Encoder.encode(
            payload, com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M
        )
        val qrVersion = qrCode.version.versionNumber
        // Measured (see OUTPUT): 268 bytes -> QR version 12 at EC level M
        // for this worst-case field-length payload. Pinned with headroom
        // so a small future field addition doesn't make this test flaky —
        // still comfortably one QR code (max version is 40).
        assertTrue("payload was $byteCount bytes", byteCount <= 320)
        assertTrue("QR needed version $qrVersion", qrVersion <= 16)
    }

    // ── FIX 2: generation-based real-vs-stale groupFormed=false decision ──

    @Test
    fun `generation 0 (nothing ever formed) is stale, not real`() {
        assertFalse(OfflineCallActivity.isRealGroupTeardown(formedGeneration = 0))
    }

    @Test
    fun `any positive generation is a real, current teardown`() {
        assertTrue(OfflineCallActivity.isRealGroupTeardown(formedGeneration = 1))
        assertTrue(OfflineCallActivity.isRealGroupTeardown(formedGeneration = 2))
        assertTrue(OfflineCallActivity.isRealGroupTeardown(formedGeneration = 47))
    }

    @Test
    fun `an older-generation callback is stale once its own teardown already reset the tracker to 0`() {
        // Simulates the actual field sequence: generation 1 forms, its real
        // teardown is processed (resetting the live tracker to 0), then a
        // duplicate/late groupFormed=false for that SAME now-past
        // generation arrives — it must not be treated as a second real
        // teardown.
        var formedGeneration = 0
        // Generation 1 forms.
        formedGeneration = 1
        assertTrue(OfflineCallActivity.isRealGroupTeardown(formedGeneration))
        // Real teardown processed — tracker reset (mirrors
        // onConnectionChangedInternal's `formedGeneration = 0` right before
        // calling handleRealGroupTeardown).
        formedGeneration = 0
        // A late/duplicate callback for that same generation now arrives —
        // correctly reads as stale.
        assertFalse(OfflineCallActivity.isRealGroupTeardown(formedGeneration))
    }

    @Test
    fun `a fresh formation (new generation) after a real teardown is real again`() {
        var formedGeneration = 1
        formedGeneration = 0 // generation 1's real teardown processed
        assertFalse(OfflineCallActivity.isRealGroupTeardown(formedGeneration))
        formedGeneration = 2 // generation 2 forms
        assertTrue(OfflineCallActivity.isRealGroupTeardown(formedGeneration))
    }

    // ── FIX 2c: "signaling socket is closed on real teardown" ─────────────
    // LocalSignaling/OfflineMediaTransport both need a real Android Looper
    // to even construct (Handler(Looper.getMainLooper()) in a field
    // initializer), which doesn't exist in a plain JVM test (this project
    // has no Robolectric) — so neither class can be instantiated here to
    // test its stop()/closeSockets() directly. What IS testable, and is
    // the exact underlying operation both rely on, is that closing a
    // java.net.ServerSocket genuinely releases its port for immediate
    // rebinding in-process — confirmed by reading LocalSignaling.stop()
    // (`serverSocket?.close()`) and OfflineMediaTransport.closeSockets()
    // (`serverSocket?.close()`), both plain java.net calls with no
    // Android-specific behaviour.

    @Test
    fun `closing a ServerSocket releases its port for immediate rebinding — the operation LocalSignaling stop and OfflineMediaTransport closeSockets both perform`() {
        val server = java.net.ServerSocket(0) // ephemeral port, never touches the real 8888/8889
        val port = server.localPort
        server.close()
        // If close() hadn't genuinely released the port, this would throw
        // BindException immediately.
        val rebound = java.net.ServerSocket(port)
        rebound.close()
    }

    @Test
    fun `an unclosed ServerSocket does keep its port bound — sanity check for the test above`() {
        val server = java.net.ServerSocket(0)
        val port = server.localPort
        try {
            var threw = false
            try {
                java.net.ServerSocket(port).close()
            } catch (e: java.net.BindException) {
                threw = true
            }
            assertTrue("expected BindException while the first socket is still open", threw)
        } finally {
            server.close()
        }
    }

    // ── FIX 1a/1b: resolveDiscoveredDeviceSlot — the pure decision core of
    // refreshNearbyDevices' WFD-discovery step ─────────────────────────────

    @Test
    fun `a WifiP2pDevice with no DNS-SD entry yet still gets a key — it produces a rendered row, not nothing`() {
        val slot = OfflineCallActivity.resolveDiscoveredDeviceSlot("aa:bb:cc:dd:ee:ff", shortId = null, realNodeIdForShortId = null)
        assertFalse("no TXT record yet means unresolved, not absent", slot.resolved)
        // The key itself just needs to be present/stable/valid — asserted
        // precisely against addressKeyFor below.
        assertEquals(OfflineCallActivity.addressKeyFor("aa:bb:cc:dd:ee:ff"), slot.key)
    }

    @Test
    fun `the same deviceAddress always resolves to the same address key — repeat sightings merge into one row`() {
        val a = OfflineCallActivity.addressKeyFor("11:22:33:44:55:66")
        val b = OfflineCallActivity.addressKeyFor("11:22:33:44:55:66")
        assertEquals(a, b)
        assertTrue(OfflineCallActivity.isAddressKey(a))
    }

    @Test
    fun `a later TXT record resolves the slot — resolved becomes true and the key changes to the matched real nodeId`() {
        val addr = "aa:bb:cc:dd:ee:ff"
        val unresolved = OfflineCallActivity.resolveDiscoveredDeviceSlot(addr, shortId = null, realNodeIdForShortId = null)
        assertFalse(unresolved.resolved)
        val resolved = OfflineCallActivity.resolveDiscoveredDeviceSlot(addr, shortId = "abc123", realNodeIdForShortId = 0x1234567890ABCDEFL)
        assertTrue(resolved.resolved)
        assertEquals(0x1234567890ABCDEFL, resolved.key)
        assertNotEquals("resolving genuinely changes the map key — this is the rekey the radarSeed field exists to survive", unresolved.key, resolved.key)
    }

    @Test
    fun `a shortId with no matching known real nodeId falls back to a synthetic key, still resolved`() {
        val slot = OfflineCallActivity.resolveDiscoveredDeviceSlot("aa:bb:cc:dd:ee:ff", shortId = "abc123", realNodeIdForShortId = null)
        assertTrue(slot.resolved)
        assertEquals(OfflineCallActivity.syntheticKeyFor("abc123"), slot.key)
        assertTrue(OfflineCallActivity.isSyntheticKey(slot.key))
        assertFalse(OfflineCallActivity.isAddressKey(slot.key))
    }

    @Test
    fun `address keys and synthetic keys never collide with each other`() {
        val addressKey = OfflineCallActivity.addressKeyFor("aa:bb:cc:dd:ee:ff")
        val syntheticKey = OfflineCallActivity.syntheticKeyFor("abc123")
        assertFalse(OfflineCallActivity.isSyntheticKey(addressKey))
        assertFalse(OfflineCallActivity.isAddressKey(syntheticKey))
    }

    @Test
    fun `radar angle stays fixed across a resolve-time rekey when radarSeed carries the ORIGINAL key forward, not the new one`() {
        // Exactly what refreshNearbyDevices' upgrade branch and
        // promoteSyntheticEntry now do: NearbyDevice.radarSeed is set once,
        // at first sighting, and copied forward (never re-defaulted to the
        // new nodeId) across every later rekey.
        val addr = "aa:bb:cc:dd:ee:ff"
        val addressKey = OfflineCallActivity.addressKeyFor(addr)
        val resolvedKey = OfflineCallActivity.resolveDiscoveredDeviceSlot(addr, "abc123", 0x1234567890ABCDEFL).key
        assertNotEquals("the map key genuinely changes on resolve", addressKey, resolvedKey)
        val radarSeed = addressKey // carried forward, per NearbyDevice(radarSeed = existing.radarSeed)
        val angleBeforeResolve = DiscoveryRadarView.stableAngleDeg(addressKey)
        val angleAfterResolveWithSeedCarried = DiscoveryRadarView.stableAngleDeg(radarSeed)
        assertEquals("radarSeed unchanged -> identical angle, no jump", angleBeforeResolve, angleAfterResolveWithSeedCarried, 0f)
        val angleIfSeedHadDefaultedToNewNodeId = DiscoveryRadarView.stableAngleDeg(resolvedKey)
        assertNotEquals(
            "sanity check: without carrying radarSeed forward, the angle WOULD have jumped — proves this fix is load-bearing",
            angleBeforeResolve, angleIfSeedHadDefaultedToNewNodeId
        )
    }

    // ── FIX 3: localShortId — never depends on mediaTransport ───────────────

    @Test
    fun `localShortIdFor works with no mediaTransport involved at all — the function signature never takes one`() {
        // The exact nodeId bytes for 0xbb1c2b0c02dd475c (big-endian, matching
        // ByteBuffer.wrap(...).long / nodeIdBytesToLong) — traced by hand in
        // the diagnostic report to "ZNQCYD".
        val nodeIdBytes = byteArrayOf(
            0xbb.toByte(), 0x1c, 0x2b, 0x0c, 0x02, 0xdd.toByte(), 0x47, 0x5c
        )
        assertEquals(0xbb1c2b0c02dd475cUL.toLong(), OfflineCallActivity.nodeIdBytesToLong(nodeIdBytes))
        assertEquals("ZNQCYD", OfflineCallActivity.localShortIdFor(nodeIdBytes))
    }

    @Test
    fun `localShortIdFor always returns exactly 6 characters, for any nodeId`() {
        listOf(0L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, 0x1234567890ABCDEFL).forEach { id ->
            val bytes = java.nio.ByteBuffer.allocate(8).putLong(id).array()
            assertEquals(6, OfflineCallActivity.localShortIdFor(bytes).length)
        }
    }

    // ── OCP PHASE 5.3: grid shapes for 5 through the MAX_GROUP_PARTICIPANTS(8) ceiling ──

    @Test
    fun `grid shapes 1-4 are unchanged`() {
        assertEquals(1 to 1, OfflineCallActivity.gridDimensionsFor(1))
        assertEquals(1 to 2, OfflineCallActivity.gridDimensionsFor(2))
        assertEquals(2 to 2, OfflineCallActivity.gridDimensionsFor(3))
        assertEquals(2 to 2, OfflineCallActivity.gridDimensionsFor(4))
    }

    @Test
    fun `5 and 6 get a real 2x3 shape instead of the old blanket 3x3`() {
        assertEquals(2 to 3, OfflineCallActivity.gridDimensionsFor(5))
        assertEquals(2 to 3, OfflineCallActivity.gridDimensionsFor(6))
    }

    @Test
    fun `7 and 8 — the new ceiling — are 3x3`() {
        assertEquals(3 to 3, OfflineCallActivity.gridDimensionsFor(7))
        assertEquals(3 to 3, OfflineCallActivity.gridDimensionsFor(8))
    }

    @Test
    fun `no shape has fewer cells than participants, for every count 1 through 8`() {
        for (n in 1..8) {
            val (rows, cols) = OfflineCallActivity.gridDimensionsFor(n)
            assertTrue("shape ${rows}x$cols has ${rows * cols} cells, too few for n=$n", rows * cols >= n)
        }
    }

    // ── STABILITY AUDIT 1a: gridRebuildMustClearFirst — the crash-fix decision core ──
    // Confirmed real-hardware crash: GridLayout.setColumnCount throws
    // "columnCount must be >= max grid index" whenever a still-attached
    // child holds a spec index from a wider grid than the count being set —
    // i.e. whenever the grid SHAPE shrinks in either dimension. This
    // exercises every (old participant count, new participant count) pair
    // in 2..8 through the real gridDimensionsFor mapping, and confirms the
    // decision always matches the actual shape comparison — never a false
    // negative (which would still crash) and never a false positive (which
    // would silently reintroduce the FIX 4 black-tile regression by
    // detaching tiles on an ordinary grow).

    @Test
    fun `gridRebuildMustClearFirst matches an actual shape shrink for every participant-count pair in 2 through 8`() {
        for (oldCount in 2..8) {
            for (newCount in 2..8) {
                val (oldRows, oldCols) = OfflineCallActivity.gridDimensionsFor(oldCount)
                val (newRows, newCols) = OfflineCallActivity.gridDimensionsFor(newCount)
                val expected = newRows < oldRows || newCols < oldCols
                val actual = OfflineCallActivity.gridRebuildMustClearFirst(oldRows, oldCols, newRows, newCols)
                assertEquals(
                    "oldCount=$oldCount (${oldRows}x$oldCols) -> newCount=$newCount (${newRows}x$newCols): " +
                        "expected mustClear=$expected",
                    expected,
                    actual
                )
            }
        }
    }

    @Test
    fun `every real shrink transition that would crash GridLayout is caught`() {
        // Concrete known shrinks from the gridDimensionsFor table (not every
        // count decrease shrinks the SHAPE — e.g. 6 to 5 stays 2x3 — but
        // every one of these genuinely reduces rows or cols and must clear).
        val shrinkTransitions = listOf(
            3 to 2,  // 2x2 -> 1x2 (rows 2->1)
            4 to 2,  // 2x2 -> 1x2 (rows 2->1)
            5 to 4,  // 2x3 -> 2x2 (cols 3->2)
            6 to 4,  // 2x3 -> 2x2 (cols 3->2)
            7 to 6,  // 3x3 -> 2x3 (rows 3->2)
            8 to 6   // 3x3 -> 2x3 (rows 3->2)
        )
        shrinkTransitions.forEach { (oldCount, newCount) ->
            val (oldRows, oldCols) = OfflineCallActivity.gridDimensionsFor(oldCount)
            val (newRows, newCols) = OfflineCallActivity.gridDimensionsFor(newCount)
            assertTrue(
                "oldCount=$oldCount -> newCount=$newCount must require clearing first",
                OfflineCallActivity.gridRebuildMustClearFirst(oldRows, oldCols, newRows, newCols)
            )
        }
    }

    @Test
    fun `a grow never requires clearing — preserves FIX 4's no-detach optimization`() {
        for (oldCount in 2..8) {
            for (newCount in oldCount..8) {
                val (oldRows, oldCols) = OfflineCallActivity.gridDimensionsFor(oldCount)
                val (newRows, newCols) = OfflineCallActivity.gridDimensionsFor(newCount)
                assertFalse(
                    "oldCount=$oldCount -> newCount=$newCount (never smaller) must NOT require clearing",
                    OfflineCallActivity.gridRebuildMustClearFirst(oldRows, oldCols, newRows, newCols)
                )
            }
        }
    }

    @Test
    fun `an unchanged shape never requires clearing`() {
        // 3 and 4 participants share 2x2; 5 and 6 share 2x3; 7 and 8 share 3x3.
        assertFalse(OfflineCallActivity.gridRebuildMustClearFirst(2, 2, 2, 2))
        assertFalse(OfflineCallActivity.gridRebuildMustClearFirst(2, 3, 2, 3))
        assertFalse(OfflineCallActivity.gridRebuildMustClearFirst(3, 3, 3, 3))
    }

    @Test
    fun `local tile is shrunk only at exactly 8 participants`() {
        assertEquals(1f, OfflineCallActivity.localTileWeightFor(7, nodeId = 1L, localNodeId = 1L))
        assertEquals(0.5f, OfflineCallActivity.localTileWeightFor(8, nodeId = 1L, localNodeId = 1L))
    }

    @Test
    fun `local tile shrink never applies to a REMOTE tile, even at 8 participants`() {
        assertEquals(1f, OfflineCallActivity.localTileWeightFor(8, nodeId = 2L, localNodeId = 1L))
    }

    // ── BUG (GROUP FORMS, NOBODY JOINS) FIX / BUG (INVITE MUST NOT DEPEND ON
    // DNS-SD RESOLUTION) FIX: association is now the ONLY thing gp checks —
    // there is no more resolveJoinNetworkName/Passphrase derivation fallback
    // to test (see maybeAutoJoinExplicitGroup's doc: ss/pw off the wire are
    // the only source now), and no more shouldRejectInviteTap gate on the
    // tap itself (see sendInvite's doc) ──────────────────────────────────

    @Test
    fun `invitedPeerAssociated is true once the target address shows up in the client list`() {
        assertTrue(OfflineCallActivity.invitedPeerAssociated(listOf("aa:bb:cc:dd:ee:ff"), "aa:bb:cc:dd:ee:ff"))
    }

    @Test
    fun `invitedPeerAssociated is false when the target address is absent, or unknown (null)`() {
        assertFalse(OfflineCallActivity.invitedPeerAssociated(listOf("11:22:33:44:55:66"), "aa:bb:cc:dd:ee:ff"))
        assertFalse(OfflineCallActivity.invitedPeerAssociated(emptyList(), "aa:bb:cc:dd:ee:ff"))
        assertFalse(OfflineCallActivity.invitedPeerAssociated(listOf("aa:bb:cc:dd:ee:ff"), null))
    }

    // ── BUG (DISCOVERY IS ONE-SHOT) FIX: honest "gave up" verdict for a row
    // that's been UNRESOLVED since first sighting longer than RESOLVE_GIVEUP_MS ──

    @Test
    fun `an already-resolved row is RESOLVED regardless of age`() {
        assertEquals(
            OfflineCallActivity.ResolveVerdict.RESOLVED,
            OfflineCallActivity.resolveVerdict(resolved = true, ageMs = 0L)
        )
        assertEquals(
            OfflineCallActivity.ResolveVerdict.RESOLVED,
            OfflineCallActivity.resolveVerdict(resolved = true, ageMs = 999_999L)
        )
    }

    @Test
    fun `an unresolved row under 20s is STILL_RESOLVING, not given up on yet`() {
        assertEquals(
            OfflineCallActivity.ResolveVerdict.STILL_RESOLVING,
            OfflineCallActivity.resolveVerdict(resolved = false, ageMs = 0L)
        )
        assertEquals(
            OfflineCallActivity.ResolveVerdict.STILL_RESOLVING,
            OfflineCallActivity.resolveVerdict(resolved = false, ageMs = 19_999L)
        )
    }

    @Test
    fun `an unresolved row at or past 20s from first sighting is marked NOT_AN_OPENCALL_DEVICE`() {
        assertEquals(
            OfflineCallActivity.ResolveVerdict.NOT_AN_OPENCALL_DEVICE,
            OfflineCallActivity.resolveVerdict(resolved = false, ageMs = 20_000L)
        )
        assertEquals(
            OfflineCallActivity.ResolveVerdict.NOT_AN_OPENCALL_DEVICE,
            OfflineCallActivity.resolveVerdict(resolved = false, ageMs = 60_000L)
        )
    }
}
