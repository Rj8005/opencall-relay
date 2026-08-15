package com.opencall.relay.offline

import java.nio.ByteBuffer

/**
 * PHASE 5A/5BC: fixed-size binary location payload, shared by TYPE_SOS,
 * TYPE_FIND_REQ, TYPE_FIND_RESP, and (PHASE 5BC) TYPE_POSITION (see
 * OfflineMediaTransport's class doc for the frame types themselves — this file
 * only knows about the payload bytes, not the mesh envelope or sockets).
 * Deliberately pure Kotlin with no Android imports so [encode]/[decode] are
 * unit-testable off-device (see MeshLocationTest in the test source set).
 *
 * V1 wire layout, big-endian (matches MeshFrame's envelope byte order), UNCHANGED
 * by the v2 bump below — a v2 parser must still decode a v1-encoded payload
 * byte-for-byte the same way PHASE 5A's decoder did:
 *   offset size field
 *   0      1    payloadVersion (1 for this layout)
 *   1      1    flags: bit0 hasFix, bit1 isMoving, bit2 batteryLow
 *   2      4    msgSeq        — uint32, per-sender monotonic, the dedupe key field
 *   6      4    latitude      — int32, degrees * 1e7
 *   10     4    longitude     — int32, degrees * 1e7
 *   14     2    accuracyMeters — uint16, 0xFFFF = unknown
 *   16     2    altitudeMeters — int16 signed, 0x8000 = unknown
 *   18     1    batteryPercent — uint8, 0xFF = unknown
 *   19     4    unixSeconds   — uint32, when the FIX was taken, not when sent
 *   23     1    messageLength — uint8, 0-64
 *   24     n    message       — UTF-8, n = messageLength, max 64 bytes
 *   v1 total: 24 + n, max 88 bytes.
 *
 * PHASE 5BC — V2 wire layout: bytes 0 through (23+n) are EXACTLY the v1 layout
 * above, byte-for-byte — a v2 sender's payload starts with a fully valid v1
 * payload as its prefix. APPENDED after that (i.e. after the message, not
 * between the header and the message), big-endian:
 *   offset       size field
 *   24+n         1    locTier        0=NONE 1=GPS_LIVE 2=GPS_STALE 3=PASSIVE
 *   25+n         1    rssiToGo       RESERVED — always written 0xFF (unknown),
 *                                    never read on decode. Wi-Fi Direct exposes
 *                                    no link RSSI to either a GO or a client on
 *                                    any Android API level (confirmed by survey
 *                                    before this phase was built — see
 *                                    MeshProximity's absence: that file was
 *                                    deliberately never created). The byte stays
 *                                    reserved on the wire so a future payload
 *                                    version could fill it in without another
 *                                    version bump, but nothing in this app ever
 *                                    writes anything but the sentinel or reads it.
 *   26+n         2    altitudeBaroM  int16 metres; 0x8000 = unavailable
 *   28+n         2    pressureHpaX10 uint16 hPa * 10; 0 = unavailable
 *   30+n         1    headingDeg     0-179 = degrees/2; 0xFF = unknown
 *   31+n         1    speedCms       cm/s capped 254; 0xFF = unknown
 *   32+n         2    seqSinceBoot   uint16, monotonic per device
 *   v2 total: 34 + n, max 98 bytes.
 *
 * decode() accepts BOTH v1 and v2 without crashing, so a v1 device and a v2
 * device in the same party interoperate: a v1 payload decodes with locTier
 * DERIVED from hasFix (true -> GPS_LIVE, false -> NONE — the only signal a v1
 * payload carries) and every other v2 field null/unknown. encode() always
 * writes the current (v2) layout — there is no way to deliberately emit a v1
 * frame from this build; v1 only exists as something we might still RECEIVE
 * from an unupgraded peer.
 *
 * [hasFix] false is a hard contract, not just a hint: [encode] always writes 0 for
 * both latitude and longitude when hasFix is false, and [decode] forces them back
 * to 0 on the way out regardless of what bytes actually arrived (defends against a
 * malformed or malicious peer setting hasFix=false with non-zero coordinates still
 * in the payload) — a caller that only checks hasFix before rendering is safe by
 * construction; coordinates 0,0 must never be mistaken for a real fix.
 */
data class MeshLocation(
    val payloadVersion: Int,
    val hasFix: Boolean,
    val isMoving: Boolean,
    val batteryLow: Boolean,
    /** Per-sender monotonic counter, shared across all frame types this payload
     *  backs — the (srcId, type, msgSeq) dedupe key's third component. */
    val msgSeq: Long,
    /** Degrees * 1e7; 0 whenever [hasFix] is false — see class doc. */
    val latE7: Int,
    val lonE7: Int,
    /** Null means "unknown", not zero. */
    val accuracyMeters: Int?,
    val altitudeMeters: Int?,
    val batteryPercent: Int?,
    val unixSeconds: Long,
    val message: String,
    // ── PHASE 5BC v2 additions — always unknown/default for a v1-decoded payload ──
    /** [OfflineLocationProvider.Tier.wireValue] — 0 (NONE) for a v1-decoded payload
     *  with no fix, 1 (GPS_LIVE) for a v1-decoded payload that did have one (v1
     *  carried no tier concept, so "had a fix" is the only signal available). */
    val locTier: Int = LOC_TIER_NONE,
    /** Barometric relative altitude, metres — null = unavailable. */
    val altitudeBaroM: Int? = null,
    /** Raw pressure * 10 (tenths of hPa) — null = unavailable. Use [pressureHpa]
     *  for the actual value. */
    val pressureHpaX10: Int? = null,
    /** Degrees, 0-358 in steps of 2 (wire precision) — null = unknown. */
    val headingDeg: Int? = null,
    /** Speed, cm/s, capped at 254 — null = unknown. */
    val speedCms: Int? = null,
    /** Monotonic per-device counter since boot, uint16 range — 0 for a v1-decoded
     *  payload (no such field existed). */
    val seqSinceBoot: Int = 0
) {
    val latitude: Double get() = latE7 / 1e7
    val longitude: Double get() = lonE7 / 1e7
    val pressureHpa: Double? get() = pressureHpaX10?.let { it / 10.0 }

    companion object {
        /** Current wire version this build ENCODES with. [decode] additionally
         *  accepts [PAYLOAD_VERSION_V1] — see class doc. */
        const val PAYLOAD_VERSION = 2
        const val PAYLOAD_VERSION_V1 = 1

        const val LOC_TIER_NONE = 0
        const val LOC_TIER_GPS_LIVE = 1
        const val LOC_TIER_GPS_STALE = 2
        const val LOC_TIER_PASSIVE = 3

        const val MAX_MESSAGE_BYTES = 64
        const val HEADER_SIZE = 24 // v1 fixed header size — unchanged, see class doc
        const val V2_EXTENSION_SIZE = 10 // locTier+rssiToGo+altitudeBaroM+pressureHpa+headingDeg+speedCms+seqSinceBoot
        const val MAX_TOTAL_BYTES = HEADER_SIZE + V2_EXTENSION_SIZE + MAX_MESSAGE_BYTES

        private const val FLAG_HAS_FIX = 0x01
        private const val FLAG_IS_MOVING = 0x02
        private const val FLAG_BATTERY_LOW = 0x04

        private const val ACCURACY_UNKNOWN_RAW = 0xFFFF
        // 0x8000 as a raw (unsigned-read) 16-bit pattern is Short.MIN_VALUE (-32768)
        // when reinterpreted signed — that's the sentinel, not a real altitude.
        private const val ALTITUDE_UNKNOWN_RAW = 0x8000
        private const val BATTERY_UNKNOWN_RAW = 0xFF
        private const val RSSI_RESERVED_RAW = 0xFF
        private const val BARO_ALT_UNKNOWN_RAW = 0x8000
        private const val PRESSURE_UNKNOWN_RAW = 0
        private const val HEADING_UNKNOWN_RAW = 0xFF
        private const val SPEED_UNKNOWN_RAW = 0xFF

        /** Convenience for FIND_REQ (and any other case with nothing to report) —
         *  hasFix=false, no message, still carries a real [msgSeq] since that's
         *  required by every frame type this payload backs, dedupe included. */
        fun noFix(msgSeq: Long, unixSeconds: Long): MeshLocation = MeshLocation(
            payloadVersion = PAYLOAD_VERSION,
            hasFix = false,
            isMoving = false,
            batteryLow = false,
            msgSeq = msgSeq,
            latE7 = 0,
            lonE7 = 0,
            accuracyMeters = null,
            altitudeMeters = null,
            batteryPercent = null,
            unixSeconds = unixSeconds,
            message = ""
        )

        /** Never throws — truncated, oversized, or unrecognized-version input
         *  returns null so a caller can drop the frame instead of crashing.
         *  Accepts both [PAYLOAD_VERSION_V1] and [PAYLOAD_VERSION] — see class doc. */
        fun decode(bytes: ByteArray): MeshLocation? {
            if (bytes.size < HEADER_SIZE) return null
            return try {
                val buf = ByteBuffer.wrap(bytes)
                val version = buf.get().toInt() and 0xFF
                // Reject anything neither this file's current version nor its one
                // known-old version understands, rather than guess at a layout a
                // future v3 sender might use.
                if (version != PAYLOAD_VERSION_V1 && version != PAYLOAD_VERSION) return null

                val flags = buf.get().toInt() and 0xFF
                val hasFix = (flags and FLAG_HAS_FIX) != 0
                val isMoving = (flags and FLAG_IS_MOVING) != 0
                val batteryLow = (flags and FLAG_BATTERY_LOW) != 0

                val msgSeq = buf.int.toLong() and 0xFFFFFFFFL
                val rawLat = buf.int
                val rawLon = buf.int
                // Belt-and-suspenders: force 0,0 whenever hasFix is false, regardless
                // of what was actually in the bytes — see class doc.
                val latE7 = if (hasFix) rawLat else 0
                val lonE7 = if (hasFix) rawLon else 0

                val accRaw = buf.short.toInt() and 0xFFFF
                val accuracyMeters = if (accRaw == ACCURACY_UNKNOWN_RAW) null else accRaw

                val altRaw = buf.short.toInt() and 0xFFFF
                val altitudeMeters = if (altRaw == ALTITUDE_UNKNOWN_RAW) null else altRaw.toShort().toInt()

                val battRaw = buf.get().toInt() and 0xFF
                val batteryPercent = if (battRaw == BATTERY_UNKNOWN_RAW) null else battRaw

                val unixSeconds = buf.int.toLong() and 0xFFFFFFFFL

                val msgLen = buf.get().toInt() and 0xFF
                if (msgLen > MAX_MESSAGE_BYTES) return null
                if (buf.remaining() < msgLen) return null
                val msgBytes = ByteArray(msgLen)
                buf.get(msgBytes)
                val message = String(msgBytes, Charsets.UTF_8)

                if (version == PAYLOAD_VERSION_V1) {
                    return MeshLocation(
                        payloadVersion = PAYLOAD_VERSION_V1,
                        hasFix = hasFix,
                        isMoving = isMoving,
                        batteryLow = batteryLow,
                        msgSeq = msgSeq,
                        latE7 = latE7,
                        lonE7 = lonE7,
                        accuracyMeters = accuracyMeters,
                        altitudeMeters = altitudeMeters,
                        batteryPercent = batteryPercent,
                        unixSeconds = unixSeconds,
                        message = message,
                        // v1 carried no tier concept — "had a fix at all" is the only
                        // signal available, so that's what we derive from.
                        locTier = if (hasFix) LOC_TIER_GPS_LIVE else LOC_TIER_NONE,
                        altitudeBaroM = null,
                        pressureHpaX10 = null,
                        headingDeg = null,
                        speedCms = null,
                        seqSinceBoot = 0
                    )
                }

                // version == PAYLOAD_VERSION (2): read the appended extension block.
                if (buf.remaining() < V2_EXTENSION_SIZE) return null
                val locTierRaw = buf.get().toInt() and 0xFF
                val locTier = if (locTierRaw in LOC_TIER_NONE..LOC_TIER_PASSIVE) locTierRaw else LOC_TIER_NONE
                buf.get() // rssiToGo — reserved, deliberately never read, see class doc
                val baroRaw = buf.short.toInt() and 0xFFFF
                val altitudeBaroM = if (baroRaw == BARO_ALT_UNKNOWN_RAW) null else baroRaw.toShort().toInt()
                val pressureRaw = buf.short.toInt() and 0xFFFF
                val pressureHpaX10 = if (pressureRaw == PRESSURE_UNKNOWN_RAW) null else pressureRaw
                val headingRaw = buf.get().toInt() and 0xFF
                val headingDeg = if (headingRaw == HEADING_UNKNOWN_RAW) null else headingRaw * 2
                val speedRaw = buf.get().toInt() and 0xFF
                val speedCms = if (speedRaw == SPEED_UNKNOWN_RAW) null else speedRaw
                val seqSinceBoot = buf.short.toInt() and 0xFFFF

                MeshLocation(
                    payloadVersion = PAYLOAD_VERSION,
                    hasFix = hasFix,
                    isMoving = isMoving,
                    batteryLow = batteryLow,
                    msgSeq = msgSeq,
                    latE7 = latE7,
                    lonE7 = lonE7,
                    accuracyMeters = accuracyMeters,
                    altitudeMeters = altitudeMeters,
                    batteryPercent = batteryPercent,
                    unixSeconds = unixSeconds,
                    message = message,
                    locTier = locTier,
                    altitudeBaroM = altitudeBaroM,
                    pressureHpaX10 = pressureHpaX10,
                    headingDeg = headingDeg,
                    speedCms = speedCms,
                    seqSinceBoot = seqSinceBoot
                )
            } catch (_: Exception) {
                // Any other malformed/truncated shape (shouldn't happen given the
                // explicit length checks above, but a peer can send anything) — drop
                // rather than propagate.
                null
            }
        }

        /** Always encodes the CURRENT (v2) layout regardless of [loc]'s own
         *  payloadVersion field — this build never deliberately emits a v1 frame,
         *  it only ever needs to be able to RECEIVE one from an unupgraded peer. */
        fun encode(loc: MeshLocation): ByteArray {
            val msgBytes = loc.message.toByteArray(Charsets.UTF_8)
                .let { if (it.size > MAX_MESSAGE_BYTES) it.copyOf(MAX_MESSAGE_BYTES) else it }
            val buf = ByteBuffer.allocate(HEADER_SIZE + msgBytes.size + V2_EXTENSION_SIZE)
            buf.put(PAYLOAD_VERSION.toByte())

            var flags = 0
            if (loc.hasFix) flags = flags or FLAG_HAS_FIX
            if (loc.isMoving) flags = flags or FLAG_IS_MOVING
            if (loc.batteryLow) flags = flags or FLAG_BATTERY_LOW
            buf.put(flags.toByte())

            buf.putInt((loc.msgSeq and 0xFFFFFFFFL).toInt())
            // Hard contract: 0,0 on the wire whenever hasFix is false, never the
            // caller's possibly-stale latE7/lonE7 fields — see class doc.
            if (loc.hasFix) {
                buf.putInt(loc.latE7)
                buf.putInt(loc.lonE7)
            } else {
                buf.putInt(0)
                buf.putInt(0)
            }
            buf.putShort(((loc.accuracyMeters ?: ACCURACY_UNKNOWN_RAW) and 0xFFFF).toShort())
            buf.putShort(((loc.altitudeMeters ?: ALTITUDE_UNKNOWN_RAW) and 0xFFFF).toShort())
            buf.put(((loc.batteryPercent ?: BATTERY_UNKNOWN_RAW) and 0xFF).toByte())
            buf.putInt((loc.unixSeconds and 0xFFFFFFFFL).toInt())
            buf.put(msgBytes.size.toByte())
            buf.put(msgBytes)

            // ── v2 extension block, appended after the message — see class doc ──
            val locTier = if (loc.locTier in LOC_TIER_NONE..LOC_TIER_PASSIVE) loc.locTier else LOC_TIER_NONE
            buf.put(locTier.toByte())
            buf.put(RSSI_RESERVED_RAW.toByte()) // reserved — always unknown, see class doc
            buf.putShort(((loc.altitudeBaroM ?: BARO_ALT_UNKNOWN_RAW) and 0xFFFF).toShort())
            buf.putShort(((loc.pressureHpaX10 ?: PRESSURE_UNKNOWN_RAW) and 0xFFFF).toShort())
            val headingRaw = loc.headingDeg?.let { (it / 2).coerceIn(0, 179) } ?: HEADING_UNKNOWN_RAW
            buf.put(headingRaw.toByte())
            val speedRaw = loc.speedCms?.coerceIn(0, 254) ?: SPEED_UNKNOWN_RAW
            buf.put(speedRaw.toByte())
            buf.putShort((loc.seqSinceBoot and 0xFFFF).toShort())

            return buf.array()
        }
    }
}
