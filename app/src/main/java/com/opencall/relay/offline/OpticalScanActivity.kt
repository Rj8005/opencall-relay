package com.opencall.relay.offline

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import org.json.JSONObject
import java.io.File

/**
 * PART D2/D4/D6/D-REMEDIATION: the SCAN half — continuously decodes camera
 * frames (zxing-android-embedded's continuous mode) as [OpticalFountain]
 * frames — OCP's own independent wire format, see that file's class doc —
 * feeding each into an [OpticalFountainDecoder] until the payload is
 * complete, then strips the leading encrypted-flag byte (see
 * [OpticalShowActivity.buildEnvelope]), decrypts if needed (D4), and
 * [OpticalFileContainer.unpackFile]s + SHA-256-verifies the result.
 *
 * Live progress: symbols received, percent complete, and an estimated time
 * remaining derived from the OBSERVED accepted-frame rate — this doubles as
 * D2's "report what the device's camera can actually keep up with."
 */
class OpticalScanActivity : AppCompatActivity() {

    private lateinit var barcodeView: DecoratedBarcodeView
    private lateinit var progressLabel: TextView
    private lateinit var rateLabel: TextView
    private var decoder: OpticalFountainDecoder? = null
    private var currentPayloadId: Int? = null
    private var startedAtMs = 0L
    private var acceptedCount = 0
    private var finished = false
    /** D2 instrumentation: every decode attempt, outcome included. */
    private var totalDecodeAttempts = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val cameraFrame = FrameLayout(this)
        barcodeView = DecoratedBarcodeView(this)
        cameraFrame.addView(barcodeView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(cameraFrame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.BLACK)
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
        }
        progressLabel = TextView(this).apply {
            text = "Point the camera at the sender's screen"
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        footer.addView(progressLabel)
        rateLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }
        footer.addView(rateLabel)
        footer.addView(Button(this).apply {
            text = "Cancel"
            setOnClickListener { finish() }
        })
        root.addView(footer)

        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Camera permission is needed to scan", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            barcodeView.resume()
            barcodeView.decodeContinuous(callback)
        }
    }

    override fun onPause() {
        super.onPause()
        barcodeView.pause()
    }

    private val callback = object : BarcodeCallback {
        override fun barcodeResult(result: BarcodeResult) {
            if (finished) return
            totalDecodeAttempts++
            val bytes = result.result.rawBytes ?: result.text?.toByteArray(Charsets.ISO_8859_1)
            if (bytes == null) {
                Log.d("OFFTRACE", "OPT: decode=empty attempt=$totalDecodeAttempts")
                return
            }
            val frame = OpticalFountain.deserializeFrame(bytes)
            if (frame == null) {
                // CRC mismatch or too-short — malformed/foreign QR, dropped silently.
                Log.d("OFFTRACE", "OPT: decode=malformed len=${bytes.size} attempt=$totalDecodeAttempts")
                return
            }
            if (currentPayloadId != null && frame.header.payloadId != currentPayloadId) {
                // A different transfer entirely — reset onto it, same
                // "any disagreement resets the decoder" posture a stream
                // identity check would give.
                decoder = null
                acceptedCount = 0
            }
            if (decoder == null) {
                decoder = OpticalFountainDecoder(frame.symbol.size)
                currentPayloadId = frame.header.payloadId
                startedAtMs = System.currentTimeMillis()
            }
            val accepted = decoder!!.offer(frame)
            if (accepted) acceptedCount++
            Log.d("OFFTRACE", "OPT: decode=ok sym=${frame.header.symbolIndex} dup=${!accepted} attempt=$totalDecodeAttempts")
            runOnUiThread { updateProgress() }
            if (decoder?.isComplete() == true) {
                finished = true
                barcodeView.pause()
                onTransferComplete()
            }
        }
    }

    private fun updateProgress() {
        val d = decoder ?: return
        val (solved, total) = d.progress()
        if (total <= 0) return
        val percent = (solved * 100) / total
        progressLabel.text = "$solved / $total symbols — $percent%"
        val elapsedSec = (System.currentTimeMillis() - startedAtMs) / 1000.0
        val achievedFps = if (elapsedSec > 0) acceptedCount / elapsedSec else 0.0
        val remaining = total - solved
        val eta = if (achievedFps > 0.1) (remaining / achievedFps).toInt() else -1
        rateLabel.text = if (eta >= 0) {
            "~%.1f accepted frames/sec — about %ds left".format(achievedFps, eta)
        } else {
            "~%.1f accepted frames/sec".format(achievedFps)
        }
    }

    private fun onTransferComplete() {
        val envelope = decoder?.reconstruct()
        val payloadId = currentPayloadId
        if (envelope == null || envelope.isEmpty() || payloadId == null) {
            AlertDialog.Builder(this).setTitle("Scan failed").setMessage("Couldn't reassemble the data.").setPositiveButton("OK") { _, _ -> finish() }.show()
            return
        }
        // Strip OpticalShowActivity's own leading encrypted-flag byte.
        val encrypted = envelope[0] == 1.toByte()
        val body = envelope.copyOfRange(1, envelope.size)

        val containerBytes = if (encrypted) {
            decryptContainer(body) ?: return
        } else {
            body
        }

        val unpacked = try {
            OpticalFileContainer.unpackFile(containerBytes)
        } catch (e: OpticalFileContainer.OpticalContainerException) {
            AlertDialog.Builder(this).setTitle("Couldn't read this").setMessage("The received data isn't a readable file: ${e.message}").setPositiveButton("OK") { _, _ -> finish() }.show()
            return
        }
        if (!OpticalFileContainer.verifyFile(unpacked)) {
            AlertDialog.Builder(this).setTitle("⚠ Verification failed")
                .setMessage("SHA-256 mismatch — this file did not survive the transfer intact. Discarded.")
                .setPositiveButton("OK") { _, _ -> finish() }.show()
            return
        }

        showReceivedFile(unpacked, payloadId, envelope.size)
    }

    /** D4: only reachable when the sender's leading flag byte marked this
     *  envelope encrypted. This wire format carries no identity field of
     *  its own, so the sender's Ed25519 pubkey travels in the clear as the
     *  first 32 bytes of the encrypted body; this is safe (not an
     *  anonymity break beyond what "encrypted, when the recipient's key is
     *  known" already implies — prior pairing) because it's self-
     *  authenticating exactly like [OfflineIdentity]'s own nodeId=H(pubkey)
     *  binding elsewhere in this app: an attacker claiming a pubkey they
     *  don't hold the matching private scalar for simply cannot produce
     *  bytes that pass AES-GCM's authentication tag under the resulting
     *  shared secret. */
    private fun decryptContainer(bytes: ByteArray): ByteArray? {
        if (bytes.size <= 32) {
            failDialog("Encrypted transfer", "This encrypted transfer is malformed.")
            return null
        }
        val senderPubkey = bytes.copyOfRange(0, 32)
        val cipherEnvelope = bytes.copyOfRange(32, bytes.size)
        val sharedSecret = OfflineIdentity.x25519SharedSecret(applicationContext, senderPubkey)
        return try {
            OpticalEncryption.decrypt(cipherEnvelope, sharedSecret)
        } catch (e: OpticalEncryption.DecryptionFailedException) {
            failDialog("⚠ Can't decrypt", "This was encrypted to a different recipient, or the sender's key doesn't match what this device has on file.")
            null
        }
    }

    private fun failDialog(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK") { _, _ -> finish() }.show()
    }

    private fun showReceivedFile(file: OpticalFileContainer.UnpackedFile, payloadId: Int, envelopeSize: Int) {
        // D5: an OCP-specific payload (Group Alert/chat note, see
        // OfflineCallActivity.packOcpFrame) rides this same generic file
        // pipeline — recognized by media type, then handed to
        // OpticalFrame's own Ed25519 verification, exactly like the radio
        // path (see OpticalFrame's class doc: "verification is identical").
        if (file.type == OCP_FRAME_MEDIA_TYPE) {
            showVerifiedOcpFrame(file, payloadId, envelopeSize)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Received: ${file.name}")
            .setMessage("${file.type} · ${file.bytes.size} bytes · SHA-256 verified ✓")
            .setPositiveButton("Save") { _, _ -> saveReceivedFile(file); showDeliveryAck(payloadId, envelopeSize); finish() }
            .setNegativeButton("Discard") { _, _ -> finish() }
            .show()
    }

    private fun showVerifiedOcpFrame(file: OpticalFileContainer.UnpackedFile, payloadId: Int, envelopeSize: Int) {
        val outcome = OpticalFrame.verify(file.bytes) { nodeId -> lookupPersistedPubkey(nodeId) }
        when (outcome) {
            is OpticalFrame.VerifyOutcome.Verified -> {
                val fromShortId = MeshFrame.hex(outcome.header.srcId)
                when (outcome.header.type) {
                    OfflineMediaTransport.TYPE_POSITION -> {
                        val loc = MeshLocation.decode(outcome.innerPayload)
                        val body = if (loc != null && loc.hasFix) {
                            "From $fromShortId\n${loc.latitude}, ${loc.longitude}${if (loc.message.isNotBlank()) "\n\"${loc.message}\"" else ""}"
                        } else {
                            "From $fromShortId — no location fix in this alert."
                        }
                        AlertDialog.Builder(this).setTitle("Group Alert ✓ verified").setMessage(body)
                            .setPositiveButton("OK") { _, _ -> showDeliveryAck(payloadId, envelopeSize); finish() }.show()
                    }
                    OfflineMediaTransport.TYPE_CHAT -> {
                        val text = when (val e = ChatEnvelope.decode(outcome.innerPayload)) {
                            is ChatEnvelope.Text -> e.body
                            is ChatEnvelope.FileAttachment -> "[file: ${e.name}]"
                        }
                        AlertDialog.Builder(this).setTitle("Message from $fromShortId ✓ verified").setMessage(text)
                            .setPositiveButton("OK") { _, _ -> showDeliveryAck(payloadId, envelopeSize); finish() }.show()
                    }
                    else -> AlertDialog.Builder(this).setTitle("Verified data from $fromShortId")
                        .setMessage("Frame type ${outcome.header.type} — nothing in this UI knows how to show it.")
                        .setPositiveButton("OK") { _, _ -> finish() }.show()
                }
            }
            OpticalFrame.VerifyOutcome.UnknownSender -> failDialog("Unverified sender", "This OCP payload's sender isn't a device this phone has a verified key for yet.")
            OpticalFrame.VerifyOutcome.Tampered -> failDialog("⚠ Verification failed", "This OCP payload's signature does not match — discarded.")
            OpticalFrame.VerifyOutcome.Malformed -> failDialog("Couldn't read this", "This claimed to be an OCP payload but isn't shaped like one.")
        }
    }

    private fun lookupPersistedPubkey(nodeId: Long): ByteArray? {
        if (nodeId == OfflineCallActivity.nodeIdBytesToLong(OfflineIdentity.nodeId(applicationContext))) {
            return OfflineIdentity.publicKeyBytes(applicationContext)
        }
        val pubkeyFile = File(File(filesDir, "ledger"), "pubkeys.json")
        if (!pubkeyFile.exists()) return null
        return try {
            MeshSigner.decodePubkeysJson(pubkeyFile.readText(Charsets.UTF_8))[nodeId]
        } catch (e: Exception) {
            null
        }
    }

    private fun saveReceivedFile(file: OpticalFileContainer.UnpackedFile) {
        try {
            val dir = File(filesDir, "optical_received").apply { mkdirs() }
            val safeName = file.name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "received_file" }
            File(dir, safeName).writeBytes(file.bytes)
            Toast.makeText(this, "Saved $safeName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't save file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** D6: shows the small static confirmation QR the SENDER's "Check
     *  delivery" scans for — {"d":true,"pid":payloadId,"len":envelopeSize}. */
    private fun showDeliveryAck(payloadId: Int, envelopeSize: Int) {
        val ack = JSONObject().apply {
            put("d", true)
            put("pid", payloadId)
            put("len", envelopeSize)
        }.toString()
        val bitmap = try {
            com.journeyapps.barcodescanner.BarcodeEncoder().encodeBitmap(ack, com.google.zxing.BarcodeFormat.QR_CODE, 500, 500)
        } catch (e: Exception) {
            return
        }
        val image = android.widget.ImageView(this).apply { setImageBitmap(bitmap) }
        AlertDialog.Builder(this)
            .setTitle("Show this to the sender")
            .setMessage("They can scan this to confirm delivery.")
            .setView(image)
            .setPositiveButton("Done", null)
            .show()
    }

    companion object {
        private const val REQUEST_CAMERA = 9101
    }
}
