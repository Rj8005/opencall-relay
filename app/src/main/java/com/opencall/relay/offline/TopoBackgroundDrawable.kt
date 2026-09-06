package com.opencall.relay.offline

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import kotlin.math.sin

/**
 * TOPO PHASE 1.2: the app's signature background — a solid base fill plus
 * 5-7 smooth horizontal contour lines in [OfflineCallActivity.TopoPalette.contour].
 * Built as one root-level Drawable (not per-view) so every screen shares
 * the identical texture.
 *
 * BUILT ONCE, NOT PER FRAME: the contour [Path] objects are constructed in
 * [onBoundsChange] — a Drawable's equivalent of a View's onSizeChanged;
 * there is no other per-size hook here — and cached in [paths]. [draw] only
 * ever strokes the cached Paths and never calls `Path()`/`quadTo` itself;
 * changing [mode] (day/night/cliff) only recolours the existing Paint,
 * never rebuilds a Path.
 *
 * CLIFF mode draws no contours at all (still fills the base colour) — a
 * battery-saver screen has no business spending fill-rate on decoration
 * that competes with legibility, per the task's own subtlety requirement.
 */
class TopoBackgroundDrawable : Drawable() {

    companion object {
        private const val LINE_COUNT = 6

        // Deterministic, gentle offsets — enough visual variety between
        // lines that they don't read as parallel rulings, without any
        // randomness (a background that redrew differently across process
        // restarts would be a distracting, pointless flicker).
        private val PHASE_OFFSETS = floatArrayOf(0.15f, 0.55f, 0.05f, 0.85f, 0.35f, 0.65f)
        private val AMPLITUDE_FRACTIONS = floatArrayOf(0.06f, 0.09f, 0.05f, 0.11f, 0.07f, 0.08f)

        /** Pure, off-device-testable sample-point generator — separated
         *  from the actual android.graphics.Path construction so the
         *  SHAPE (the y-coordinates a contour line passes through) is
         *  directly assertable without instantiating a Path/Canvas (this
         *  project has no Robolectric). [buildContourPath] just feeds
         *  these points into a real Path via quad-to smoothing. */
        fun contourSampleYs(width: Float, height: Float, lineIndex: Int, sampleCount: Int = 24): FloatArray {
            if (width <= 0f || height <= 0f) return FloatArray(sampleCount + 1)
            val slot = LINE_COUNT.coerceAtLeast(2) - 1
            val baseY = height * (0.12f + (lineIndex % LINE_COUNT) * (0.76f / slot))
            val phase = PHASE_OFFSETS[lineIndex % PHASE_OFFSETS.size]
            val amp = height * AMPLITUDE_FRACTIONS[lineIndex % AMPLITUDE_FRACTIONS.size]
            return FloatArray(sampleCount + 1) { s ->
                val t = s.toFloat() / sampleCount
                baseY + amp * sin((t + phase) * 2f * Math.PI.toFloat() * 1.3f)
            }
        }
    }

    private val fillPaint = Paint().apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private var paths: Array<Path> = emptyArray()
    private var mode: OfflineCallActivity.TopoMode = OfflineCallActivity.TopoMode.DAY

    /** Recolours only — never touches [paths]. Safe to call every time the
     *  app's mode changes (night-mode toggle, battery-cliff enter/exit);
     *  no Path work happens here. */
    fun setMode(newMode: OfflineCallActivity.TopoMode) {
        if (mode == newMode) return
        mode = newMode
        invalidateSelf()
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        paths = if (bounds.width() <= 0 || bounds.height() <= 0) {
            emptyArray()
        } else {
            Array(LINE_COUNT) { i -> buildContourPath(bounds.width().toFloat(), bounds.height().toFloat(), i) }
        }
    }

    private fun buildContourPath(width: Float, height: Float, lineIndex: Int): Path {
        val ys = contourSampleYs(width, height, lineIndex)
        val path = Path()
        val step = width / (ys.size - 1)
        path.moveTo(0f, ys[0])
        for (i in 1 until ys.size) {
            val prevX = (i - 1) * step
            val x = i * step
            val midX = (prevX + x) / 2f
            val midY = (ys[i - 1] + ys[i]) / 2f
            path.quadTo(prevX, ys[i - 1], midX, midY)
        }
        return path
    }

    override fun draw(canvas: Canvas) {
        fillPaint.color = OfflineCallActivity.TopoPalette.bgBase(mode)
        canvas.drawRect(bounds, fillPaint)
        if (mode == OfflineCallActivity.TopoMode.CLIFF) return // 1.2: no contours in CLIFF
        linePaint.color = OfflineCallActivity.TopoPalette.contour(mode)
        for (p in paths) canvas.drawPath(p, linePaint)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        linePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        linePaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.OPAQUE"))
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
