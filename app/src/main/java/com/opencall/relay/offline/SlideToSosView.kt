package com.opencall.relay.offline

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View

/**
 * OFFLINE UI STEP 5: replaces the plain sosButton click with slide-then-hold
 * 3s — see [SlideToSos] for the pure hold-timing logic this view is only a
 * thin touch/haptic/visual wrapper around. The actual SOS trigger call
 * ([onArmed]) is wired by the caller to the EXACT SAME `mediaTransport
 * ?.startSos(null)` the old button used — this view changes only the
 * GESTURE, never the payload/ack/carry path (see OUTPUT proof #6).
 *
 * Gesture: touch down and drag the handle past [SLIDE_THRESHOLD_FRACTION] of
 * the track width, then KEEP HOLDING (no further drag required) for
 * [SlideToSos.HOLD_MS]. Releasing (ACTION_UP/CANCEL) at any point before the
 * hold completes cancels SILENTLY — no callback, no log, no partial state
 * left behind (see [reset]).
 */
class SlideToSosView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val SLIDE_THRESHOLD_FRACTION = 0.6f
        private const val TICK_MS = 50L
    }

    /** Fires exactly once per completed hold — wired by the caller to the
     *  unchanged mediaTransport?.startSos(null) call. */
    var onArmed: (() -> Unit)? = null

    // TOPO 1.4: routed through TopoPalette — the handle is the one
    // legitimate use of the danger role (this view's whole purpose is
    // arming SOS); the track/label use setMode's mode-appropriate text
    // colours rather than a hardcoded white.
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 32f
        textAlign = Paint.Align.CENTER
    }
    private val trackRect = RectF()
    private var mode: OfflineCallActivity.TopoMode = OfflineCallActivity.TopoMode.DAY

    /** Pushed by the caller whenever the app's mode changes (night toggle,
     *  battery cliff) — same pattern as PartyRingView.setNightMode/
     *  DiscoveryRadarView.applyNightMode. */
    fun setMode(newMode: OfflineCallActivity.TopoMode) {
        mode = newMode
        trackPaint.color = withAlpha(OfflineCallActivity.TopoPalette.bgRaised(newMode), 160)
        handlePaint.color = OfflineCallActivity.TopoPalette.danger(newMode)
        // Drawn over the TRACK (see onDraw), not the handle — textPrimary
        // reads correctly against bgRaised, unlike onAccent (meant for use
        // atop the accent/danger fill itself).
        labelPaint.color = OfflineCallActivity.TopoPalette.textPrimary(newMode)
        invalidate()
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    init {
        setMode(OfflineCallActivity.TopoMode.DAY)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    private var handleFraction = 0f
    private var slidPastThreshold = false
    private var downAtElapsedMs = 0L
    private var holdRunnable: Runnable? = null
    private var downX = 0f

    private val tick = object : Runnable {
        override fun run() {
            if (!slidPastThreshold) return
            val held = android.os.SystemClock.elapsedRealtime() - downAtElapsedMs
            if (SlideToSos.shouldFire(held)) {
                fire()
                return
            }
            vibrateForFraction(SlideToSos.hapticIntensityFraction(held))
            mainHandler.postDelayed(this, TICK_MS)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                handleFraction = 0f
                slidPastThreshold = false
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val w = width.coerceAtLeast(1)
                handleFraction = ((event.x - downX) / w).coerceIn(0f, 1f)
                if (!slidPastThreshold && handleFraction >= SLIDE_THRESHOLD_FRACTION) {
                    slidPastThreshold = true
                    downAtElapsedMs = android.os.SystemClock.elapsedRealtime()
                    Log.d("OFFTRACE", "SOS: armed hold=${SlideToSos.HOLD_MS}ms")
                    mainHandler.postDelayed(tick, TICK_MS)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                reset() // release before HOLD_MS completes -> silent cancel, no callback
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun fire() {
        reset()
        onArmed?.invoke()
    }

    /** Silent cancel — no log, no callback, matches "release before 3s
     *  cancels silently" exactly. Also the terminal step of a successful
     *  fire (called from [fire] before invoking the callback) so the handle
     *  always visually resets regardless of how the gesture ended. */
    private fun reset() {
        mainHandler.removeCallbacks(tick)
        handleFraction = 0f
        slidPastThreshold = false
        invalidate()
    }

    private fun vibrateForFraction(fraction: Float) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            val amplitude = (30 + fraction * 225).toInt().coerceIn(1, 255)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(TICK_MS, amplitude))
            }
        } catch (e: Exception) {
            Log.w("OFFTRACE", "SOS: haptic ramp failed: ${e.message}")
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        val w = width.toFloat()
        trackRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(trackRect, h / 2, h / 2, trackPaint)
        val handleR = h / 2 - 6f
        val handleX = handleR + 6f + (w - 2 * (handleR + 6f)) * handleFraction
        canvas.drawCircle(handleX, h / 2, handleR, handlePaint)
        val label = if (slidPastThreshold) "Hold\u2026" else "Slide for Group Alert" // TOPO 1.3: sentence case; PART 5.2: renamed
        canvas.drawText(label, w / 2, h / 2 + 10f, labelPaint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // STEP 3's 56dp minimum touch target — never measured shorter than this.
        val minHeightPx = (56 * resources.displayMetrics.density).toInt()
        val requestedHeight = MeasureSpec.getSize(heightMeasureSpec)
        val height = if (requestedHeight > 0) requestedHeight.coerceAtLeast(minHeightPx) else minHeightPx
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }
}
