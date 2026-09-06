package com.opencall.relay.offline

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.util.Random

/**
 * PART D1/D2/D6/D-REMEDIATION: the SHOW half of the optical transfer
 * channel — renders an endless carousel of [OpticalFountain]-framed QR
 * codes, OCP's own independent wire format (see [OpticalFountain]'s class
 * doc for why this is no longer a port of an AGPL-licensed project).
 * Generic over its payload: [EXTRA_PAYLOAD] is an already-
 * [OpticalFileContainer]-packed (and, if [EXTRA_ENCRYPTED], already
 * [OpticalEncryption]-encrypted, with a leading 1-byte flag — see
 * [buildEnvelope]) byte blob — this Activity has no idea whether it's a
 * share-sheet file or an OCP Group Alert wrapped as one (see
 * [OfflineCallActivity]'s call sites).
 *
 * D2 FIXES carried over unchanged: encoding + zxing rendering runs on
 * [encodeThread], never the main thread; bitmap size is computed from the
 * QR's own module count via the encoded [com.google.zxing.qrcode.encoder.Encoder]
 * result, never a fixed constant.
 */
class OpticalShowActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PAYLOAD = "payload"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ENCRYPTED = "encrypted"

        /** Payload bytes per fountain symbol — this app's own choice, not
         *  tied to any external project's frame budget. */
        const val SYMBOL_SIZE_BYTES = 400

        private const val PIXELS_PER_MODULE = 6
        private const val WARMUP_FRAMES = 3
        private const val ACK_REQUEST_CODE = 9301
        private val FPS_OPTIONS = listOf(6, 10, 15, 20)
        private val EC_LEVEL_OPTIONS = listOf(
            "L" to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L,
            "M" to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M,
            "Q" to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.Q
        )
    }

    private lateinit var encodeThread: HandlerThread
    private lateinit var encodeHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var preStartView: LinearLayout
    private lateinit var transferView: LinearLayout
    private lateinit var qrImage: ImageView
    private lateinit var progressLabel: TextView

    /** [OpticalFileContainer]/encryption-envelope bytes, prefixed with our
     *  own 1-byte "is this encrypted" flag (see [buildEnvelope]) — this is
     *  the actual byte[] that gets fountain-encoded. */
    private var envelope = ByteArray(0)
    private var payloadId = 0
    private var k = 0
    private var cycleLength = 0
    @Volatile private var running = false
    private var seq = 0L
    private var fps = 10
    private var ecLevel = com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M
    private var measuredFps = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val payload = intent.getByteArrayExtra(EXTRA_PAYLOAD) ?: ByteArray(0)
        val encrypted = intent.getBooleanExtra(EXTRA_ENCRYPTED, false)
        envelope = buildEnvelope(encrypted, payload)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Optical transfer"
        payloadId = Random().nextInt()
        encodeThread = HandlerThread("OpticalEncode").apply { start() }
        encodeHandler = Handler(encodeThread.looper)

        val density = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
        }
        root.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        })

        // ── D1: size + a MEASURED (not guessed) duration estimate, shown
        // BEFORE anything starts, with an explicit Start action.
        preStartView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val sizeLabel = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, (24 * density).toInt(), 0, (8 * density).toInt())
            text = "Measuring this device's encode speed…"
        }
        preStartView.addView(sizeLabel)

        preStartView.addView(TextView(this).apply { text = "Speed"; textSize = 13f; gravity = Gravity.CENTER })
        val fpsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        FPS_OPTIONS.forEach { option ->
            fpsRow.addView(Button(this).apply { text = "${option}fps"; setOnClickListener { fps = option } })
        }
        preStartView.addView(fpsRow)

        preStartView.addView(TextView(this).apply { text = "Error correction (higher = more resilient, denser code)"; textSize = 13f; gravity = Gravity.CENTER })
        val ecRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        EC_LEVEL_OPTIONS.forEach { (label, level) ->
            ecRow.addView(Button(this).apply { text = label; setOnClickListener { ecLevel = level } })
        }
        preStartView.addView(ecRow)

        val startButton = Button(this).apply {
            text = "Start"
            isEnabled = false
            setOnClickListener { showTransferView(); startTransfer() }
        }
        preStartView.addView(startButton)
        root.addView(preStartView)

        transferView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = android.view.View.GONE
        }
        qrImage = ImageView(this)
        transferView.addView(qrImage, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (16 * density).toInt()
            gravity = Gravity.CENTER_HORIZONTAL
        })
        progressLabel = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        transferView.addView(progressLabel)
        transferView.addView(Button(this).apply {
            text = "Check delivery"
            setOnClickListener { checkDelivery() }
        })
        transferView.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener { finish() }
        })
        root.addView(transferView)

        setContentView(root)

        if (envelope.isEmpty()) {
            sizeLabel.text = "Nothing to show"
            return
        }
        k = OpticalFountain.symbolCount(envelope.size, SYMBOL_SIZE_BYTES)
        cycleLength = k + maxOf(20, k / 2)
        measureThenShowEstimate(sizeLabel, startButton)
    }

    /** Our own 1-byte flag prefix, NOT part of [OpticalFountain]'s own
     *  header — kept out of that file so its wire format stays generic and
     *  reusable; this Activity is the only place that knows what "byte 0"
     *  means. */
    private fun buildEnvelope(encrypted: Boolean, payloadOrCiphertext: ByteArray): ByteArray {
        val flag = if (encrypted) 1.toByte() else 0.toByte()
        return byteArrayOf(flag) + payloadOrCiphertext
    }

    private fun measureThenShowEstimate(sizeLabel: TextView, startButton: Button) {
        encodeHandler.post {
            val startedAt = System.nanoTime()
            for (i in 0 until minOf(WARMUP_FRAMES, k)) buildFrameBitmap(i)
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0
            measuredFps = if (elapsedMs > 0) (minOf(WARMUP_FRAMES, k) * 1000.0 / elapsedMs) else 4.0
            mainHandler.post {
                val kb = envelope.size / 1024.0
                val estSeconds = if (measuredFps > 0.1) (k * 1.5 / measuredFps).toInt() else -1
                sizeLabel.text = buildString {
                    append("%.1f KB".format(kb))
                    append(" · $k frames")
                    if (estSeconds >= 0) append("\nEstimated ~${formatDuration(estSeconds)} at this device's own ${"%.1f".format(measuredFps)} fps encode rate")
                    append("\n(actual time also depends on the receiving camera)")
                }
                startButton.isEnabled = true
            }
        }
    }

    private fun formatDuration(seconds: Int): String =
        if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"

    private fun showTransferView() {
        preStartView.visibility = android.view.View.GONE
        transferView.visibility = android.view.View.VISIBLE
    }

    private fun startTransfer() {
        running = true
        seq = 0
        encodeHandler.post(renderLoopStep)
    }

    override fun onResume() {
        super.onResume()
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
    }

    override fun onPause() {
        super.onPause()
        running = false
        encodeHandler.removeCallbacks(renderLoopStep)
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
    }

    override fun onDestroy() {
        super.onDestroy()
        encodeThread.quitSafely()
    }

    private fun buildFrameBitmap(frameIndex: Int): Bitmap? {
        val frame = OpticalFountain.encodeFrame(payloadId, envelope, SYMBOL_SIZE_BYTES, frameIndex)
        val wire = OpticalFountain.serializeFrame(frame)
        val content = String(wire, Charsets.ISO_8859_1)
        val hints = mapOf(
            com.google.zxing.EncodeHintType.ERROR_CORRECTION to ecLevel,
            com.google.zxing.EncodeHintType.MARGIN to 1
        )
        return try {
            val qrCode = com.google.zxing.qrcode.encoder.Encoder.encode(content, ecLevel, java.util.Hashtable(hints))
            val modules = qrCode.matrix.width
            val px = modules * PIXELS_PER_MODULE
            com.journeyapps.barcodescanner.BarcodeEncoder().encodeBitmap(content, com.google.zxing.BarcodeFormat.QR_CODE, px, px, hints)
        } catch (e: Exception) {
            null
        }
    }

    private val renderLoopStep: Runnable = Runnable {
        if (!running) return@Runnable
        val index = (seq % cycleLength).toInt()
        val bitmap = buildFrameBitmap(index)
        mainHandler.post {
            if (bitmap != null) qrImage.setImageBitmap(bitmap)
            progressLabel.text = if (index < k) "Sweeping ${index + 1}/$k" else "Repair frame ${index - k + 1}"
        }
        seq++
        if (running) {
            val intervalMs = (1000L / fps).coerceAtLeast(1L)
            encodeHandler.postDelayed(renderLoopStep, intervalMs)
        }
    }

    // ── D6: completion handshake — the receiver shows a small static QR
    // {"d":true,"pid":payloadId,"len":envelopeLength} once it verifies the
    // container; this scans for exactly that.

    private fun checkDelivery() {
        com.google.zxing.integration.android.IntentIntegrator(this)
            .setDesiredBarcodeFormats(listOf(com.google.zxing.integration.android.IntentIntegrator.QR_CODE))
            .setPrompt("Scan the receiver's confirmation code")
            .setBeepEnabled(false)
            .setCaptureActivity(PortraitCaptureActivity::class.java)
            .setOrientationLocked(true)
            .setRequestCode(ACK_REQUEST_CODE)
            .initiateScan()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val scan = com.google.zxing.integration.android.IntentIntegrator.parseActivityResult(requestCode, resultCode, data) ?: return
        val raw = scan.contents ?: return
        val ack = try {
            val json = JSONObject(raw)
            Triple(json.optBoolean("d", false), json.optInt("pid", 0), json.optInt("len", -1))
        } catch (e: Exception) {
            null
        }
        if (ack != null && ack.first && ack.second == payloadId && ack.third == envelope.size) {
            AlertDialog.Builder(this).setTitle("Delivered ✓").setMessage("The recipient confirmed they received and verified this transfer.").setPositiveButton("OK", null).show()
        } else {
            Toast.makeText(this, "That's not a matching delivery confirmation — try again once the receiver finishes", Toast.LENGTH_LONG).show()
        }
    }
}
