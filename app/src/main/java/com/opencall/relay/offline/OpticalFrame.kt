package com.opencall.relay.offline

import android.content.Context
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.Base64

/**
 * PART 2.4: optical carries the SAME signed MeshFrame bytes the radio does —
 * no new wire type. This file builds/parses exactly two of the EXISTING
 * frame types (see [OfflineMediaTransport]'s TYPE_* registry):
 *   - [OfflineMediaTransport.TYPE_POSITION]: a signed Group Alert with
 *     location — the same [MeshLocation] payload MeshSosManager's own
 *     startSos/reportPosition already build (MeshSosManager.kt:269/490).
 *   - [OfflineMediaTransport.TYPE_CHAT]: a text message OR a file under
 *     64KB — both still literally TYPE_CHAT bytes; [ChatEnvelope] is a
 *     PAYLOAD-level (not wire-level) discriminator, so a plain radio
 *     TYPE_CHAT frame that happens to carry this same JSON shape decodes
 *     identically — no divergent behavior introduced for the existing path.
 * The pairing payload (2.1's QrInvitePayload, in OfflineCallActivity.kt) is
 * deliberately NOT a MeshFrame at all — it's pre-connection identity with no
 * peer link to address yet, exactly like the existing radio-QR invite it
 * extends.
 *
 * SIGNING mirrors [MeshSigner.signIfNeeded] exactly (payload || timestamp(4B)
 * || Ed25519 sig(64B)) — there is no live [MeshSigner] instance to call here
 * (an optical transfer has no socket, no [RoutingTable], no retry queue), so
 * [sign] inlines the identical construction using the same PUBLIC primitives
 * [MeshSigner.buildSignedMaterial] and [OfflineIdentity.sign]. VERIFICATION
 * reuses [MeshSigner.splitCarriedTrailer]/[MeshSigner.verifyCarriedTrailer]
 * verbatim — the exact functions MeshSigner's own store-and-forward path
 * uses for a frame with no live replay-window context, which is exactly
 * optical's situation too (a scan can easily take longer than
 * [MeshSigner.REPLAY_WINDOW_SEC]'s 120s). "Verification is identical" is
 * therefore literally true, not just asserted: this file calls the same
 * functions the radio path calls, not a reimplementation of them.
 */
object OpticalFrame {

    /** 2.3's own cap — refuse outright rather than run a four-minute
     *  animated QR. Applied to the FINAL wire frame (header + signed
     *  payload), so it also accounts for the timestamp+signature trailer. */
    const val MAX_PAYLOAD_BYTES = 64 * 1024

    private const val HEADER_SIZE = 1 + 8 + 8 + 1 + 1 + 4 // mirrors MeshFrame's own private HEADER_SIZE

    private val TTL_BROADCAST: Byte = 6
    private val TTL_UNICAST: Byte = 8

    class PayloadTooLargeException(val actualBytes: Int) :
        Exception("Optical payload is $actualBytes bytes, over the ${MAX_PAYLOAD_BYTES}B cap")

    /** Builds a complete, signed MeshFrame wire blob — byte-identical to
     *  what [OfflineMediaTransport.writeFrame] would put on a socket for the
     *  same (dst, type, payload). Throws [PayloadTooLargeException] before
     *  this ever reaches the fountain-coding/QR layer. */
    fun buildSignedFrame(context: Context, localNodeId: Long, dst: Long, type: Byte, payload: ByteArray): ByteArray =
        buildSignedFrame(localNodeId, dst, type, payload) { bytes -> OfflineIdentity.sign(context, bytes) }

    /** Pure core of [buildSignedFrame] — [signer] is [OfflineIdentity.sign]'s
     *  exact shape (raw bytes in, a 64-byte Ed25519 signature out) but
     *  without the Context-bound Keystore behind it, so this is directly
     *  unit-testable with a plain in-test BouncyCastle keypair (this
     *  project has no Robolectric — a real [OfflineIdentity.sign] call
     *  needs a real AndroidKeyStore, same limitation
     *  [OfflineIdentityTest]'s own class doc documents — see
     *  OpticalFrameTest). */
    fun buildSignedFrame(localNodeId: Long, dst: Long, type: Byte, payload: ByteArray, signer: (ByteArray) -> ByteArray): ByteArray {
        val ttl = if (dst == MeshFrame.BROADCAST_ID) TTL_BROADCAST else TTL_UNICAST
        val signedPayload = sign(localNodeId, dst, type, payload, signer)
        val frame = MeshFrame.encode(localNodeId, dst, ttl, type, signedPayload)
        if (frame.size > MAX_PAYLOAD_BYTES) throw PayloadTooLargeException(frame.size)
        return frame
    }

    /** A signed Group Alert with location — [OfflineMediaTransport.TYPE_POSITION],
     *  always broadcast (matches MeshSosManager's own dst for this type). */
    fun buildGroupAlert(context: Context, localNodeId: Long, location: MeshLocation): ByteArray =
        buildSignedFrame(context, localNodeId, MeshFrame.BROADCAST_ID, OfflineMediaTransport.TYPE_POSITION, MeshLocation.encode(location))

    /** A text note or a file under 64KB — both [OfflineMediaTransport.TYPE_CHAT];
     *  see [ChatEnvelope] for how the two are told apart. [dst] is
     *  [MeshFrame.BROADCAST_ID] for "anyone who scans this," or a specific
     *  nodeId if the sender picked one recipient. */
    fun buildChat(context: Context, localNodeId: Long, dst: Long, envelope: ChatEnvelope): ByteArray =
        buildSignedFrame(context, localNodeId, dst, OfflineMediaTransport.TYPE_CHAT, ChatEnvelope.encode(envelope))

    private fun sign(srcId: Long, dst: Long, type: Byte, payload: ByteArray, signer: (ByteArray) -> ByteArray): ByteArray {
        val timestamp = System.currentTimeMillis() / 1000L
        val signedMaterial = MeshSigner.buildSignedMaterial(srcId, dst, type, timestamp, payload)
        val sig = signer(signedMaterial)
        val buf = ByteBuffer.allocate(payload.size + MeshSigner.SIGNATURE_TRAILER_BYTES)
        buf.put(payload)
        buf.putInt((timestamp and 0xFFFFFFFFL).toInt())
        buf.put(sig)
        return buf.array()
    }

    data class ParsedFrame(val header: MeshFrame.Header, val rawPayload: ByteArray)

    /** Parses ONLY the fixed 23-byte MeshFrame header plus its declared-length
     *  payload — pure, no signature verification. [MeshFrame.decodeHeader]
     *  needs a DataInputStream; an optical decode already has the whole blob
     *  in memory (reassembled from fountain-coded symbols), so this reads the
     *  same fixed layout straight off a [ByteArray] instead. Returns null for
     *  anything short or malformed rather than throwing — a corrupted/foreign
     *  scan must never crash, same posture as every other QR decoder in this
     *  app (see [OfflineCallActivity.decodeQrPayload]'s own doc). */
    fun parseHeader(bytes: ByteArray): ParsedFrame? {
        if (bytes.size < HEADER_SIZE) return null
        return try {
            val buf = ByteBuffer.wrap(bytes)
            val ver = buf.get()
            val src = buf.long
            val dst = buf.long
            val ttl = buf.get()
            val type = buf.get()
            val len = buf.int
            if (len < 0 || HEADER_SIZE + len > bytes.size) return null
            val payload = bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + len)
            ParsedFrame(MeshFrame.Header(ver, src, dst, ttl, type, len), payload)
        } catch (e: Exception) {
            null
        }
    }

    sealed class VerifyOutcome {
        data class Verified(val innerPayload: ByteArray, val signedTimestampSec: Long, val header: MeshFrame.Header) : VerifyOutcome()
        /** The sender's pubkey isn't known to this device yet — e.g. an
         *  optical frame from a nodeId never paired with, over radio or QR.
         *  Distinct from [Tampered]: nothing here says the frame is FORGED,
         *  just that this device can't check. */
        object UnknownSender : VerifyOutcome()
        /** Pubkey is known, but the signature does not match — a forged or
         *  corrupted frame. */
        object Tampered : VerifyOutcome()
        object Malformed : VerifyOutcome()
    }

    /** [pubkeyForSender] is the caller's own lookup — [RoutingTable.pubkeyFor]
     *  for an already-paired peer, or [OfflineIdentity.publicKeyBytes] for a
     *  self-addressed round-trip test — this file holds no RoutingTable of
     *  its own. */
    fun verify(bytes: ByteArray, pubkeyForSender: (Long) -> ByteArray?): VerifyOutcome {
        val parsed = parseHeader(bytes) ?: return VerifyOutcome.Malformed
        val split = MeshSigner.splitCarriedTrailer(parsed.rawPayload) ?: return VerifyOutcome.Malformed
        val pubkey = pubkeyForSender(parsed.header.srcId) ?: return VerifyOutcome.UnknownSender
        return if (MeshSigner.verifyCarriedTrailer(parsed.header.srcId, parsed.header.dstId, parsed.header.type, split, pubkey)) {
            VerifyOutcome.Verified(split.innerPayload, split.timestampSec, parsed.header)
        } else {
            VerifyOutcome.Tampered
        }
    }
}

/** PART 2.4: payload-level discriminator carried INSIDE a TYPE_CHAT frame —
 *  the wire type never changes (see [OpticalFrame]'s class doc: "do not
 *  invent new wire types"). Uses [java.util.Base64], not
 *  `android.util.Base64` — this project's unit tests set
 *  `unitTests.returnDefaultValues = true` (no Robolectric), under which the
 *  Android stub silently returns defaults instead of throwing, which would
 *  make a broken encode/decode pass silently rather than fail loudly; see
 *  [MeshSigner]'s own pure companion functions for the same reasoning. */
sealed class ChatEnvelope {
    data class Text(val body: String) : ChatEnvelope()
    data class FileAttachment(val name: String, val data: ByteArray) : ChatEnvelope()

    companion object {
        fun encode(envelope: ChatEnvelope): ByteArray = when (envelope) {
            is Text -> JSONObject().apply {
                put("kind", "text")
                put("body", envelope.body)
            }.toString().toByteArray(Charsets.UTF_8)
            is FileAttachment -> JSONObject().apply {
                put("kind", "file")
                put("name", envelope.name)
                put("data", Base64.getEncoder().encodeToString(envelope.data))
            }.toString().toByteArray(Charsets.UTF_8)
        }

        /** Never throws. Bytes that aren't this envelope's JSON shape at all
         *  (an ordinary radio TYPE_CHAT frame from before this feature
         *  existed) decode as plain [Text] of the raw UTF-8 bytes — the same
         *  thing CallLogScreen-era chat handling already assumed every
         *  TYPE_CHAT payload was. */
        fun decode(bytes: ByteArray): ChatEnvelope = try {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            when (json.optString("kind")) {
                "file" -> FileAttachment(json.getString("name"), Base64.getDecoder().decode(json.getString("data")))
                else -> Text(json.optString("body", String(bytes, Charsets.UTF_8)))
            }
        } catch (e: Exception) {
            Text(String(bytes, Charsets.UTF_8))
        }
    }
}
