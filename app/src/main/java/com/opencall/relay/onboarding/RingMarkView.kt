package com.opencall.relay.onboarding

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat
import com.opencall.relay.R
import kotlin.math.min

/**
 * ONBOARDING REWRITE, screen 1: the app's identity mark, drawn fresh here as
 * a plain custom View rather than reused from [com.opencall.relay.offline.
 * TopoBackgroundDrawable] (that class draws horizontal sine-wave contour
 * lines — the day/night/cliff background texture — not concentric rings; see
 * this task's step-1 report for that premise correction). Modeled directly
 * on `ic_launcher_foreground.xml`'s proportions instead: 5 concentric rings
 * + 4 cardinal crosshairs + 4 cardinal dots + a solid center dot, all in
 * [R.color.accent_blue] on the caller's own background (this view draws no
 * fill of its own — [android.view.View.setBackgroundColor] on the host is
 * what supplies `bg_deep`, same as every other screen). Radius/alpha
 * fractions are lifted directly from that vector's own r=80/58/38/20/8 and
 * stroke-color-alpha values (out of its 216x216 viewport, 108 center) so
 * this reads as the exact same mark at any size, not a reinterpretation.
 *
 * No new asset, no animation library, no dependency beyond the platform's
 * own [ValueAnimator] — a single slow alpha "breath" across every ring/
 * crosshair/dot together (the solid center dot never dims, staying the one
 * fixed anchor). Deliberately slow (3.2s each direction) and low-contrast
 * (0.72-1.0) — a mark, not a loading spinner, per this task's instruction.
 * [startPulse]/[stopPulse] are the caller's responsibility to pair with its
 * own onResume/onPause (a plain View has no such lifecycle callbacks of its
 * own); [onDetachedFromWindow] stops it regardless, as a safety net.
 */
class RingMarkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        // Fractions of maxRadius (= min(width, height) / 2), matching
        // ic_launcher_foreground.xml's r=80/58/38/20/8 out of its 108 half-viewport.
        val RING_RADIUS_FRACTIONS = floatArrayOf(80f / 108f, 58f / 108f, 38f / 108f, 20f / 108f, 8f / 108f)
        // Matching that same vector's stroke alpha bytes (0x26/0x33/0x47/0x73/0xFF).
        val RING_ALPHAS = floatArrayOf(0x26 / 255f, 0x33 / 255f, 0x47 / 255f, 0x73 / 255f, 1f)
        const val CROSSHAIR_ALPHA = 0x40 / 255f
        const val CARDINAL_DOT_ALPHA = 0x80 / 255f
        const val CARDINAL_DOT_RADIUS_FRACTION = 3f / 108f
        const val CENTER_DOT_RADIUS_FRACTION = 4f / 108f
        private const val OUTER_STROKE_WIDTH_DP = 1f
        private const val CENTER_RING_STROKE_WIDTH_DP = 2f
        private const val CROSSHAIR_STROKE_WIDTH_DP = 0.5f

        private const val PULSE_MIN_ALPHA = 0.72f
        private const val PULSE_MAX_ALPHA = 1.0f
        private const val PULSE_DURATION_MS = 3_200L
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val accentColor = ContextCompat.getColor(context, R.color.accent_blue)

    @Volatile private var pulseFraction = PULSE_MAX_ALPHA
    private var animator: ValueAnimator? = null

    /** Idempotent — a second call while already pulsing is a no-op. */
    fun startPulse() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(PULSE_MIN_ALPHA, PULSE_MAX_ALPHA).apply {
            duration = PULSE_DURATION_MS
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                pulseFraction = it.animatedValue as Float
                invalidate()
            }
        }
        animator?.start()
    }

    /** Idempotent — safe to call whether or not [startPulse] is running. */
    fun stopPulse() {
        animator?.cancel()
        animator = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopPulse()
    }

    private fun alphaByte(baseAlphaFraction: Float): Int =
        (255 * baseAlphaFraction * pulseFraction).toInt().coerceIn(0, 255)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val density = resources.displayMetrics.density
        val cx = width / 2f
        val cy = height / 2f
        val maxRadius = min(width, height) / 2f

        for (i in RING_RADIUS_FRACTIONS.indices) {
            ringPaint.strokeWidth =
                (if (i == RING_RADIUS_FRACTIONS.lastIndex) CENTER_RING_STROKE_WIDTH_DP else OUTER_STROKE_WIDTH_DP) * density
            ringPaint.color = accentColor
            ringPaint.alpha = alphaByte(RING_ALPHAS[i])
            canvas.drawCircle(cx, cy, maxRadius * RING_RADIUS_FRACTIONS[i], ringPaint)
        }

        val outerR = maxRadius * RING_RADIUS_FRACTIONS[0]
        val centerR = maxRadius * CENTER_DOT_RADIUS_FRACTION
        crosshairPaint.strokeWidth = CROSSHAIR_STROKE_WIDTH_DP * density
        crosshairPaint.color = accentColor
        crosshairPaint.alpha = alphaByte(CROSSHAIR_ALPHA)
        canvas.drawLine(cx, cy - centerR, cx, cy - outerR, crosshairPaint) // north
        canvas.drawLine(cx, cy + centerR, cx, cy + outerR, crosshairPaint) // south
        canvas.drawLine(cx + centerR, cy, cx + outerR, cy, crosshairPaint) // east
        canvas.drawLine(cx - centerR, cy, cx - outerR, cy, crosshairPaint) // west

        dotPaint.color = accentColor
        dotPaint.alpha = alphaByte(CARDINAL_DOT_ALPHA)
        val dotR = maxRadius * CARDINAL_DOT_RADIUS_FRACTION
        canvas.drawCircle(cx, cy - outerR, dotR, dotPaint)
        canvas.drawCircle(cx, cy + outerR, dotR, dotPaint)
        canvas.drawCircle(cx + outerR, cy, dotR, dotPaint)
        canvas.drawCircle(cx - outerR, cy, dotR, dotPaint)

        // Center dot is the one fixed anchor — never dims with the pulse.
        centerPaint.color = accentColor
        centerPaint.alpha = 255
        canvas.drawCircle(cx, cy, centerR, centerPaint)
    }
}
