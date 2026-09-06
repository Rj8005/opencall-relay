package com.opencall.relay.offline

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Camera
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.journeyapps.barcodescanner.camera.CameraSettings

/**
 * PART "WHY THE QR JOIN FAILS" B2-B5 / C4: a continuous-scan replacement for
 * the old single-shot IntentIntegrator/PortraitCaptureActivity flow that
 * showScanQrDialog used before this file existed. Swapped in ONLY because
 * IntentIntegrator's public API exposes nothing but a final successful
 * result (or a plain cancellation) -- it gives no visibility into "the
 * camera is running but never decoding anything", which is exactly what B2
 * asks this instrumentation to distinguish.
 *
 * THE HONEST CEILING on what B2 can observe: zxing-android-embedded's
 * BarcodeCallback (the only API surface with any per-frame visibility at
 * all, via DecoratedBarcodeView.decodeContinuous) fires [barcodeResult] on
 * a full successful decode, and [possibleResultPoints] on a PARTIAL
 * finder-pattern detection -- there is no "attempted this frame and found
 * nothing at all" event anywhere in the library's public API. So
 * "ok=false" log lines mean "a candidate QR-shaped pattern was detected
 * but not fully decoded", not "every single camera frame, logged" -- that
 * finer granularity does not exist to observe.
 *
 * C4 CAMERA SETTINGS -- confirmed against the ACTUAL zxing-android-embedded
 * 4.3.0 classes.jar (decompiled with javap, not guessed from memory):
 *   - continuous autofocus: com.journeyapps.barcodescanner.camera.CameraSettings
 *     has a real, public setFocusMode(CameraSettings.FocusMode) plus
 *     setAutoFocusEnabled/setContinuousFocusEnabled -- all set below.
 *   - QR_CODE-only decode formats: DefaultDecoderFactory(Collection<BarcodeFormat>)
 *     -- confirmed constructor, used below with listOf(BarcodeFormat.QR_CODE).
 *   - TRY_HARDER off: that same 1-arg DefaultDecoderFactory constructor
 *     passes a null hints map internally (confirmed by javap) -- there is
 *     no TRY_HARDER hint in a null map, which IS "off"; nothing here adds
 *     one back.
 *   - MINIMUM 1280x720 preview: CameraSettings/CameraPreview/BarcodeView/
 *     DecoratedBarcodeView expose NO direct "requested preview size"
 *     setter at all in this library version (confirmed absent from all
 *     four classes) -- the one real hook is
 *     DecoratedBarcodeView.changeCameraParameters(CameraParametersCallback),
 *     which hands back the raw android.hardware.Camera.Parameters; used
 *     below (from the StateListener's previewStarted(), once the camera is
 *     actually running) to pick the smallest supported preview size that
 *     is still >= 1280x720, falling back to the largest available if none
 *     qualifies. This could not be exercised on real hardware in this
 *     environment -- the logged res=WxH (read back via the library's own
 *     public CameraPreview.getPreviewSize(), the ACTUAL negotiated size,
 *     not a view-layout proxy) is what confirms whether it took effect.
 *
 * NET USER-VISIBLE BEHAVIOR IS UNCHANGED: a successful scan returns the raw
 * decoded string to OfflineCallActivity via the same onActivityResult
 * mechanism IntentIntegrator itself used, which still calls
 * handleScannedQr(raw) exactly as before.
 */
class PairingScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RESULT_RAW = "raw"
        const val REQUEST_CODE = 9401
        private const val REQUEST_CAMERA_PERMISSION = 9402
        private const val MIN_PREVIEW_WIDTH = 1280
        private const val MIN_PREVIEW_HEIGHT = 720
    }

    private lateinit var barcodeView: DecoratedBarcodeView
    private lateinit var statusLabel: TextView
    private var finished = false
    private var attemptCount = 0
    private var possibleSightings = 0
    private var lastPossibleLogAtMs = 0L
    private var loggedGrantedResolution = false

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
        statusLabel = TextView(this).apply {
            text = "Scan the other device's QR code"
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        footer.addView(statusLabel)
        footer.addView(Button(this).apply {
            text = "Cancel"
            setOnClickListener { finish() }
        })
        root.addView(footer)

        setContentView(root)

        // C4: continuous autofocus, explicitly.
        barcodeView.cameraSettings = CameraSettings().apply {
            focusMode = CameraSettings.FocusMode.CONTINUOUS
            isAutoFocusEnabled = true
            isContinuousFocusEnabled = true
        }
        // C4: QR_CODE only, TRY_HARDER off (no hints map at all) -- see
        // class doc for why this exact 1-arg constructor is both.
        barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
        // C4: request >=1280x720 once the camera is actually running, and
        // log whatever was ACTUALLY granted (read back from the library,
        // not assumed) — see class doc's own caveat on this path.
        barcodeView.barcodeView.addStateListener(object : CameraPreview.StateListener {
            override fun previewSized() {}
            override fun previewStarted() {
                requestMinimumPreviewResolution()
            }
            override fun previewStopped() {}
            override fun cameraError(error: Exception) {
                Log.w("OFFTRACE", "QR: camera error ${error.javaClass.simpleName}:${error.message}")
            }
            override fun cameraClosed() {}
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
        }
    }

    private fun requestMinimumPreviewResolution() {
        try {
            barcodeView.barcodeView.changeCameraParameters { params: Camera.Parameters ->
                val supported = params.supportedPreviewSizes
                if (!supported.isNullOrEmpty()) {
                    val target = supported
                        .filter { it.width * it.height >= MIN_PREVIEW_WIDTH * MIN_PREVIEW_HEIGHT }
                        .minByOrNull { it.width * it.height }
                        ?: supported.maxByOrNull { it.width * it.height }
                    target?.let { params.setPreviewSize(it.width, it.height) }
                }
                params
            }
        } catch (e: Exception) {
            Log.w("OFFTRACE", "QR: changeCameraParameters failed ${e.javaClass.simpleName}:${e.message}")
        }
        // The parameter change above is dispatched onto the library's own
        // camera thread and may not be visible on getPreviewSize() the
        // instant this call returns — a short delay before reading it back
        // for the log line is a pragmatic, not provably-correct, choice.
        statusLabel.postDelayed({ logGrantedResolutionOnce() }, 300L)
    }

    private fun logGrantedResolutionOnce() {
        if (loggedGrantedResolution) return
        loggedGrantedResolution = true
        val size = barcodeView.barcodeView.previewSize
        val mode = barcodeView.cameraSettings.focusMode
        Log.d("OFFTRACE", "QR: camera res=${size?.width}x${size?.height} focus=$mode")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
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
            attemptCount++
            val size = barcodeView.barcodeView.previewSize
            Log.d(
                "OFFTRACE",
                "QR: raw decode ok=true len=${result.text?.length ?: 0} res=${size?.width}x${size?.height} " +
                    "focus=${barcodeView.cameraSettings.focusMode} attempt=$attemptCount"
            )
            finished = true
            barcodeView.pause()
            setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT_RAW, result.text))
            finish()
        }

        override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>) {
            // B2's closest available signal for "the camera sees SOMETHING
            // QR-shaped but has not fully decoded it" -- see class doc.
            // Throttled to 1/sec since this can fire many times a second
            // once a code is actually in frame.
            possibleSightings++
            val now = System.currentTimeMillis()
            if (now - lastPossibleLogAtMs < 1_000L) return
            lastPossibleLogAtMs = now
            val size = barcodeView.barcodeView.previewSize
            Log.d(
                "OFFTRACE",
                "QR: raw decode ok=false len=0 res=${size?.width}x${size?.height} " +
                    "focus=${barcodeView.cameraSettings.focusMode} attempt=$attemptCount possibleSightings=$possibleSightings"
            )
        }
    }
}
