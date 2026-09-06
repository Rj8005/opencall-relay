package com.opencall.relay.offline

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin

/**
 * OFFLINE UI STEP 2: the party ring home screen — a plain [View] subclass,
 * Canvas/[onDraw] only. NO Compose anywhere in this module (confirmed by
 * survey before writing this file) and none is added here.
 *
 * DATA IN, PIXELS OUT: this view never calls MeshLedger/MeshCompass/
 * OfflineMediaTransport itself — [setPeers]/[setAzimuth] are its only two
 * inputs, both pushed by the Activity. That split is what makes the two
 * throttles independent and correct: [setPeers] (peer distance/bearing/
 * state, 4Hz max — see that function's doc) never touches anything the
 * compass owns, and [setAzimuth] (10Hz max, self-throttled here) never
 * touches anything a peer vector owns. Rotating the phone can only ever
 * change what [setAzimuth] receives — there is no path from an azimuth
 * value to peer text, because peer text is built by the Activity (see
 * formatPeerVectorRow) BEFORE it ever reaches this view; this view only
 * ever draws a name label and a distance/age string it was handed verbatim.
 *
 * NO ALLOCATION IN [onDraw]: every Paint/Path/RectF this view ever draws
 * with is a field, allocated once in the constructor/init block. Per-frame
 * work reuses them (Path.reset(), Paint.setColor(), etc.) rather than
 * constructing new instances — see the OUTPUT proof this class was built
 * against. Small, unavoidable String formatting for dynamic labels (peer
 * names, distance text — already fully formatted TEXT handed in via
 * [RingPeer], not built here) is not what that rule targets; the four
 * object categories the task named (Paint/Path/Rect/RectF) are the ones
 * that matter for GC churn on a view that can redraw at 10Hz, and none of
 * them are ever `new`'d after the constructor.
 */
class PartyRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** One peer's full drawable state — the Activity builds this from
     *  MeshLedger.vectorTo()/blePresenceFor()/isArticulationPoint(), this
     *  view only ever reads it. [displayText] is already the fully-formatted
     *  row string (see formatPeerVectorRow) — this view never invents or
     *  reformats distance/bearing text itself. */
    data class RingPeer(
        val nodeId: Long,
        val name: String,
        val vector: MeshLedger.PeerVector,
        val isArticulation: Boolean,
        val rssiTrend: MeshLedger.Trend? = null
    )

    companion object {
        private const val MAX_HIT_TEST_MARKERS = 16
        private const val D0_M = 25f
        val DMAX_STEPS_M = floatArrayOf(100f, 250f, 500f, 1000f, 2500f)
        private const val AZIMUTH_INVALIDATE_INTERVAL_MS = 100L // 10Hz cap
        private const val UI_LOG_INTERVAL_MS = 1_000L

        /** Pure, off-device-testable (see PartyRingViewTest): log-scaled
         *  radius FRACTION of R (0..1) for [distanceM] against [dMax] —
         *  r = R * ln(1+d/d0)/ln(1+dMax/d0), clamped to the rim beyond dMax.
         *  Never negative, never NaN (a non-finite/negative input distance
         *  clamps to the centre rather than propagating garbage onto the
         *  canvas). */
        fun radiusFraction(distanceM: Float, dMax: Float, d0: Float = D0_M): Float {
            if (!distanceM.isFinite() || distanceM <= 0f) return 0f
            if (distanceM >= dMax) return 1f
            val denom = ln((1.0 + dMax / d0))
            if (denom <= 0.0) return 0f
            val f = (ln(1.0 + distanceM / d0) / denom).toFloat()
            return f.coerceIn(0f, 1f)
        }

        // Shrink only once the furthest LIVE peer has dropped to well under
        // (not just under) the CURRENT dMax — a plain "next step down"
        // check turns out NOT to be real hysteresis (verified by working
        // through the exact oscillation this constant exists to prevent, see
        // PartyRingViewTest): a value bouncing across a step boundary (e.g.
        // 245/255 around the 250 step) would still grow to the next tier on
        // every high reading and immediately re-shrink on every low one,
        // because "one step down" from the NEW (grown) tier is often still
        // above the oscillating value. Requiring the reading to fall below a
        // FRACTION of the CURRENT (already-grown) dMax — well below where a
        // boundary-adjacent bounce can reach — is what actually breaks the
        // cycle: after growing to the tier above a boundary bounce, the low
        // readings never come close to a fraction of THAT (much larger)
        // tier, so no further transition happens until the peer has moved
        // decisively closer.
        private const val DMAX_SHRINK_MARGIN = 0.4f

        /** Pure, off-device-testable: snaps to [DMAX_STEPS_M] with
         *  hysteresis — grows immediately the moment the furthest LIVE peer's
         *  natural step exceeds the CURRENT one (never lags growth, so a peer
         *  walking away is never clipped off the rim for long), but only
         *  shrinks once the peer has dropped below [DMAX_SHRINK_MARGIN] of
         *  the CURRENT dMax — see that constant's doc for why this (not a
         *  "one step down" check) is what actually prevents flapping.
         *  [currentDMax] null means "never set yet" — picks the natural
         *  (no-hysteresis) step. */
        fun pickDMax(furthestLiveM: Float, currentDMax: Float?): Float {
            val natural = DMAX_STEPS_M.firstOrNull { furthestLiveM <= it } ?: DMAX_STEPS_M.last()
            if (currentDMax == null) return natural
            if (natural > currentDMax) return natural
            return if (furthestLiveM <= currentDMax * DMAX_SHRINK_MARGIN) natural else currentDMax
        }

        // ── PHASE 1.5: pure, off-device-testable companions for the two
        // remaining pieces of onDraw-adjacent logic (report C3/1.3/1.4c) ────

        /** Pure formatter for one range-ring/rim label — extracted so
         *  PartyRingViewTest can assert the exact string without
         *  instantiating a View. */
        fun formatRangeLabel(m: Float): String =
            if (m >= 1000f) "%.1fkm".format(m / 1000f) else "${m.toInt()}m"

        /** True iff dMax actually moved (or this is the first-ever pick) —
         *  the ONLY condition under which range labels should be
         *  reformatted. [setPeers] is called at up to 4Hz on every peer
         *  update, but dMax (and therefore the labels) changes far less
         *  often than that; this is what keeps label formatting off the
         *  hot path entirely rather than merely throttling it. */
        fun dMaxChanged(newDMax: Float, currentDMax: Float?): Boolean =
            currentDMax == null || newDMax != currentDMax

        /** How a peer in [state] is drawn — never a decision made ad hoc
         *  inline in [drawPeers], so PartyRingViewTest can assert it
         *  directly. RIM_ARC (PHASE 1.4c) covers both UNKNOWN and
         *  BLE_ONLY: neither has a real bearing, so neither is ever
         *  eligible for POINT. SKIP does not occur for any current
         *  [MeshLedger.PeerState] value — kept so this function has an
         *  explicit, exhaustive answer rather than relying on callers to
         *  assume "anything not listed is a point". [RenderMode] itself is
         *  declared at the class level, not in here — a type nested inside
         *  a companion object is only reachable as Outer.Companion.Nested
         *  from outside, not the shorter Outer.Nested PartyRingViewTest
         *  needs. */
        fun renderModeFor(state: MeshLedger.PeerState): RenderMode = when (state) {
            MeshLedger.PeerState.UNKNOWN, MeshLedger.PeerState.BLE_ONLY -> RenderMode.RIM_ARC
            MeshLedger.PeerState.LOST -> RenderMode.LOST_CONE
            MeshLedger.PeerState.LIVE, MeshLedger.PeerState.STALE -> RenderMode.POINT
        }
    }

    /** See [renderModeFor]'s doc for why this lives here, at the class
     *  level, rather than inside the companion object above. */
    enum class RenderMode { POINT, RIM_ARC, LOST_CONE, SKIP }

    // ── Hoisted drawing state — allocated ONCE, never inside onDraw ─────────
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(90, 255, 255, 255)
        strokeWidth = 2f
    }
    private val ringLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 255, 255, 255)
        textSize = 24f
    }
    private val rimLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }
    private val northTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val northTickLockedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }
    // PHASE 1.6b: the "N" glyph used to be drawn with northTickLockedPaint/
    // rimLabelPaint's STROKE-style sibling — style=STROKE applies to
    // drawText too, so the letter rendered as a near-invisible hollow
    // outline at the stock 12f text size, left-aligned instead of centred
    // on the tick. Dedicated FILL paints, 28f (matches rimLabelPaint), CENTER
    // -aligned — the tick LINE keeps using northTickPaint/northTickLockedPaint
    // unchanged, only the text gets its own paint. Locked stays YELLOW (an
    // intentional exception to night mode, same as northTickLockedPaint
    // already was — see setNightMode's doc); unlocked mirrors rimLabelPaint's
    // WHITE and IS routed through night mode below.
    private val northTickTextLockedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        textSize = 28f
        textAlign = Paint.Align.CENTER
        style = Paint.Style.FILL
    }
    private val northTickTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        textAlign = Paint.Align.CENTER
        style = Paint.Style.FILL
    }
    private val centreDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val livePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x1d, 0x9e, 0x75); style = Paint.Style.FILL } // teal
    private val stalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x1d, 0x9e, 0x75); style = Paint.Style.FILL }
    private val staleRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x1d, 0x9e, 0x75)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val weakeningPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xff, 0xb3, 0x00); style = Paint.Style.FILL } // amber
    private val lostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 200, 200, 200)
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }
    private val conePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 200, 200, 200)
        style = Paint.Style.FILL
    }
    private val bleArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 100, 180, 255)
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
    }
    private val nameLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }
    private val bridgeGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val conePath = Path()
    private val ringRect = RectF()
    private val bleArcRect = RectF()

    // PHASE 1.3: was `floatArrayOf(0.25f, 0.60f, 1.0f)` allocated fresh inside
    // onDraw's drawRangeRings every frame — hoisted here, allocated once.
    private val rangeFractions = floatArrayOf(0.25f, 0.60f, 1.0f)
    // PHASE 1.3: range-ring labels only change when dMaxM changes (at most
    // 4Hz, via setPeers -> recomputeRangeLabels), not on every 10Hz onDraw —
    // was two "%.1fkm"/"${m}m" string formats built fresh per ring per frame.
    private var cachedRingLabels: Array<String> = Array(rangeFractions.size) { "" }

    // Night mode: single toggle, all non-red channels zeroed on the ring —
    // see applyNightMode's doc for exactly which Paints are affected.
    private var nightMode = false

    // ── State pushed in by the Activity ──────────────────────────────────────
    private var peers: List<RingPeer> = emptyList()
    private var meIsCutVertex: Boolean = false
    private var dMaxM: Float = DMAX_STEPS_M[0]
    private var dMaxInitialized = false
    private var myAzimuthDeg: Float = 0f
    private var compassLocked: Boolean = true // true = north-up (no compass, or inaccurate)
    private var lastAzimuthInvalidateAtMs = 0L
    private var lastUiLogAtMs = 0L
    private var frozen = false // STEP 5 battery cliff: ring stops rotating entirely

    var onMarkerTapped: ((nodeId: Long) -> Unit)? = null
    var onMarkerLongPressed: ((nodeId: Long) -> Unit)? = null

    // Screen position of every marker drawn in the LAST onDraw pass — hit-
    // tested against on tap/long-press. Fixed-size PARALLEL arrays (not a
    // Map<Long, FloatArray>, which would need a fresh FloatArray boxed per
    // entry) indexed by [hitTestCount] — genuinely zero allocation on every
    // redraw, not just "usually doesn't allocate": MAX_HIT_TEST_MARKERS is a
    // generous fixed cap (MAX_GROUP_PARTICIPANTS is 8 elsewhere in this app;
    // this doubles it for headroom), sized once at construction.
    private val hitTestNodeIds = LongArray(MAX_HIT_TEST_MARKERS)
    private val hitTestX = FloatArray(MAX_HIT_TEST_MARKERS)
    private val hitTestY = FloatArray(MAX_HIT_TEST_MARKERS)
    private var hitTestCount = 0
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            hitTest(e.x, e.y)?.let { onMarkerTapped?.invoke(it) }
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            hitTest(e.x, e.y)?.let { onMarkerLongPressed?.invoke(it) }
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return true
    }

    private fun recordHitTestPosition(nodeId: Long, x: Float, y: Float) {
        if (hitTestCount >= MAX_HIT_TEST_MARKERS) return
        hitTestNodeIds[hitTestCount] = nodeId
        hitTestX[hitTestCount] = x
        hitTestY[hitTestCount] = y
        hitTestCount++
    }

    private fun hitTest(x: Float, y: Float): Long? {
        val touchRadiusPx = 24f * resources.displayMetrics.density
        var best: Long? = null
        var bestDist = Float.MAX_VALUE
        for (i in 0 until hitTestCount) {
            val d = hypot((x - hitTestX[i]).toDouble(), (y - hitTestY[i]).toDouble()).toFloat()
            if (d <= touchRadiusPx && d < bestDist) {
                bestDist = d
                best = hitTestNodeIds[i]
            }
        }
        return best
    }

    /** OFFLINE UI STEP 8: recomputed at max 4Hz by the Activity's own tick —
     *  this function itself does no throttling (the caller already did), it
     *  just applies dMax hysteresis and redraws. [furthestLiveM] is derived
     *  by the caller from [newPeers] itself (passed separately so this
     *  function's dMax logic is a pure re-application of [pickDMax], not a
     *  second computation of "which peers are LIVE"). */
    fun setPeers(newPeers: List<RingPeer>, meIsCutVertex: Boolean) {
        this.peers = newPeers
        this.meIsCutVertex = meIsCutVertex
        val furthestLive = newPeers.filter { it.vector.state == MeshLedger.PeerState.LIVE && !it.vector.distM.isNaN() }
            .maxOfOrNull { it.vector.distM } ?: 0f
        val newDMax = pickDMax(furthestLive, if (dMaxInitialized) dMaxM else null)
        // PHASE 1.3/1.5: only reformat the range labels when dMax actually
        // moves (per the pure, tested dMaxChanged) — this is the "dMax
        // setter" the range-label strings now live in, instead of being
        // rebuilt every onDraw.
        if (dMaxChanged(newDMax, if (dMaxInitialized) dMaxM else null)) {
            dMaxM = newDMax
            recomputeRangeLabels()
        }
        dMaxInitialized = true
        maybeLogUiState()
        invalidate()
    }

    private fun recomputeRangeLabels() {
        cachedRingLabels = Array(rangeFractions.size) { i -> formatRangeLabel(dMaxM * rangeFractions[i]) }
    }

    /** OFFLINE UI STEP 6/battery cliff: freezes rotation entirely — no
     *  compass rotation, ring stays exactly as last drawn otherwise (peer
     *  text still updates via setPeers, only the heading-up rotation
     *  freezes). Call with false to resume. */
    fun setFrozen(frozen: Boolean) {
        if (this.frozen == frozen) return
        this.frozen = frozen
        invalidate()
    }

    /** Self-throttled to 10Hz — the Activity can call this at the compass's
     *  full sensor rate without flooding invalidate(). [accurate]=false (or
     *  no compass at all) locks the ring north-up per STEP 2's rule; the
     *  locked state itself still updates immediately (never throttled) so
     *  the N-tick highlight and "which way is up" never lag the throttle. */
    fun setAzimuth(azimuthDeg: Float, accurate: Boolean) {
        myAzimuthDeg = azimuthDeg
        val lockedNow = !accurate
        val lockChanged = lockedNow != compassLocked
        compassLocked = lockedNow
        if (frozen) return
        val now = SystemClock.elapsedRealtime()
        if (!lockChanged && now - lastAzimuthInvalidateAtMs < AZIMUTH_INVALIDATE_INTERVAL_MS) return
        lastAzimuthInvalidateAtMs = now
        invalidate()
    }

    /** OFFLINE UI STEP 5: red-monochrome — zeroes every non-red channel on
     *  every Paint field this view owns. Persisted by the caller (see
     *  OfflineCallActivity's SharedPreferences toggle); this function only
     *  applies the visual change to THIS view's already-hoisted Paints —
     *  still no new Paint objects, just mutating color on the existing ones. */
    fun setNightMode(enabled: Boolean) {
        if (nightMode == enabled) return
        nightMode = enabled
        val red = Color.rgb(200, 0, 0)
        val dimRed = Color.argb(90, 200, 0, 0)
        // PHASE 1.6b: northTickTextPaint added — same WHITE<->red swap as
        // rimLabelPaint, which it mirrors. northTickTextLockedPaint is
        // deliberately NOT here: it stays YELLOW in both modes, same
        // intentional exemption northTickLockedPaint (the tick line) already had.
        val paints = listOf(
            ringLabelPaint, rimLabelPaint, centreDotPaint, livePaint, stalePaint,
            staleRingPaint, weakeningPaint, nameLabelPaint, bridgeGlyphPaint, northTickTextPaint
        )
        if (enabled) {
            ringPaint.color = dimRed
            northTickPaint.color = red
            for (p in paints) p.color = red
            lostPaint.color = red
            conePaint.color = Color.argb(60, 200, 0, 0)
            bleArcPaint.color = red
        } else {
            ringPaint.color = Color.argb(90, 255, 255, 255)
            northTickPaint.color = Color.argb(200, 255, 255, 255)
            ringLabelPaint.color = Color.argb(160, 255, 255, 255)
            rimLabelPaint.color = Color.WHITE
            centreDotPaint.color = Color.WHITE
            livePaint.color = Color.rgb(0x1d, 0x9e, 0x75)
            stalePaint.color = Color.rgb(0x1d, 0x9e, 0x75)
            staleRingPaint.color = Color.rgb(0x1d, 0x9e, 0x75)
            weakeningPaint.color = Color.rgb(0xff, 0xb3, 0x00)
            lostPaint.color = Color.argb(220, 200, 200, 200)
            conePaint.color = Color.argb(60, 200, 200, 200)
            bleArcPaint.color = Color.argb(200, 100, 180, 255)
            nameLabelPaint.color = Color.WHITE
            bridgeGlyphPaint.color = Color.WHITE
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val rMax = min(width, height) / 2f - 60f
        if (rMax <= 0f) return

        // Rotation applied to the whole peer layer: heading-up unless
        // locked (no compass, or inaccurate — STEP 2's hard rule). north-up
        // means rotationDeg = 0.
        val rotationDeg = if (compassLocked) 0f else -myAzimuthDeg
        hitTestCount = 0

        drawRangeRings(canvas, cx, cy, rMax)
        drawNorthTick(canvas, cx, cy, rMax, rotationDeg)
        drawPeers(canvas, cx, cy, rMax, rotationDeg)
        drawCentre(canvas, cx, cy)
    }

    private fun drawRangeRings(canvas: Canvas, cx: Float, cy: Float, rMax: Float) {
        // PHASE 1.3: fractions + labels are hoisted/cached fields now — no
        // allocation here. PHASE 1.4b: the old extra "rim label" above the
        // outermost ring duplicated the value the i=last iteration below
        // already draws on that same ring; removed rather than kept as a
        // fourth label.
        for (i in rangeFractions.indices) {
            val f = rangeFractions[i]
            val r = rMax * f
            ringRect.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawOval(ringRect, ringPaint)
            canvas.drawText(cachedRingLabels[i], cx + r * 0.72f, cy - r * 0.72f, ringLabelPaint)
        }
    }

    private fun drawNorthTick(canvas: Canvas, cx: Float, cy: Float, rMax: Float, rotationDeg: Float) {
        // North tick is FIXED north-up always — it rotates the OPPOSITE way
        // from the peer layer when heading-up, so it always points at true
        // north on screen; when locked, rotationDeg is already 0 so this
        // simply always points up, matching the peer layer (both north-up).
        // PHASE 1.4a: SHORT rim tick only (innerR..outerR) — the previous
        // version drew all the way from the centre (cx,cy), which read as a
        // spoke through the whole ring rather than a tick at its edge.
        val rad = Math.toRadians((0.0 - rotationDeg).toDouble())
        val sinR = sin(rad).toFloat()
        val cosR = cos(rad).toFloat()
        val outerR = rMax + 10f
        val innerR = rMax - 14f
        val x1 = cx + innerR * sinR
        val y1 = cy - innerR * cosR
        val x2 = cx + outerR * sinR
        val y2 = cy - outerR * cosR
        val linePaint = if (compassLocked) northTickLockedPaint else northTickPaint
        canvas.drawLine(x1, y1, x2, y2, linePaint)
        // PHASE 1.6b: dedicated FILL+28f+CENTER text paints — see their
        // field doc for why the old STROKE-style/12f/LEFT-aligned draw was
        // effectively invisible.
        val textPaint = if (compassLocked) northTickTextLockedPaint else northTickTextPaint
        canvas.drawText("N", x2, y2 - 10f, textPaint)
    }

    private fun drawCentre(canvas: Canvas, cx: Float, cy: Float) {
        canvas.drawCircle(cx, cy, 14f, centreDotPaint)
        if (meIsCutVertex) drawBridgeGlyph(canvas, cx, cy, 24f)
    }

    private fun drawPeers(canvas: Canvas, cx: Float, cy: Float, rMax: Float, rotationDeg: Float) {
        for (p in peers) {
            // PHASE 1.4c/1.5: dispatch driven by the pure, tested
            // renderModeFor — UNKNOWN used to `continue` here (never drawn —
            // a peer with no fix and no BLE sighting simply vanished from
            // the ring); both UNKNOWN and BLE_ONLY now render via the same
            // rim arc (see drawRimArcPeer's doc for why that's the only
            // honest rendering for "known to exist, direction unknown").
            when (renderModeFor(p.vector.state)) {
                RenderMode.RIM_ARC -> drawRimArcPeer(canvas, cx, cy, rMax, p)
                RenderMode.LOST_CONE -> drawLostPeer(canvas, cx, cy, rMax, rotationDeg, p)
                RenderMode.POINT -> drawLiveOrStalePeer(canvas, cx, cy, rMax, rotationDeg, p)
                RenderMode.SKIP -> {}
            }
        }
    }

    // Hoisted output of peerScreenPos — a single reusable pair, valid only
    // until the next call (every call site consumes it immediately, before
    // computing any other peer's position). Avoids returning a fresh
    // FloatArray per peer per draw.
    private val scratchPos = FloatArray(2)

    private fun peerScreenPos(cx: Float, cy: Float, rMax: Float, rotationDeg: Float, bearingTrue: Float, distanceM: Float) {
        val screenBearing = bearingTrue + rotationDeg
        val rad = Math.toRadians(screenBearing.toDouble())
        val frac = radiusFraction(distanceM, dMaxM)
        val r = rMax * frac
        scratchPos[0] = cx + r * sin(rad).toFloat()
        scratchPos[1] = cy - r * cos(rad).toFloat()
    }

    private fun drawLiveOrStalePeer(canvas: Canvas, cx: Float, cy: Float, rMax: Float, rotationDeg: Float, p: RingPeer) {
        if (p.vector.bearingTrue.isNaN()) return // no bearing to plot — never guess (see STEP 2's hard rule via distance floor)
        peerScreenPos(cx, cy, rMax, rotationDeg, p.vector.bearingTrue, p.vector.distM)
        val x = scratchPos[0]
        val y = scratchPos[1]
        val weakening = p.vector.state == MeshLedger.PeerState.LIVE && p.rssiTrend == MeshLedger.Trend.FARTHER
        val dotPaint = if (weakening) weakeningPaint else livePaint
        recordHitTestPosition(p.nodeId, x, y)
        canvas.drawCircle(x, y, 12f, dotPaint)
        if (p.vector.state == MeshLedger.PeerState.STALE) {
            canvas.drawCircle(x, y, 20f, staleRingPaint)
        }
        if (p.isArticulation) drawBridgeGlyph(canvas, x, y, 20f)
        canvas.drawText(p.name, x, y - 26f, nameLabelPaint)
    }

    private fun drawLostPeer(canvas: Canvas, cx: Float, cy: Float, rMax: Float, rotationDeg: Float, p: RingPeer) {
        if (p.vector.bearingTrue.isNaN()) return
        peerScreenPos(cx, cy, rMax, rotationDeg, p.vector.bearingTrue, p.vector.distM)
        val x = scratchPos[0]
        val y = scratchPos[1]
        recordHitTestPosition(p.nodeId, x, y)
        canvas.drawCircle(x, y, 12f, lostPaint)
        if (p.isArticulation) drawBridgeGlyph(canvas, x, y, 20f)
        canvas.drawText(p.name, x, y - 26f, nameLabelPaint)
        drawSearchCone(canvas, cx, cy, rMax, rotationDeg, p)
    }

    /** Search cone wedge — bearing = last known heading, half-angle/lo/hi
     *  straight from MeshLedger.vectorTo's coneMinM/coneMaxM (already
     *  computed there, never recomputed here). NaN cone bounds (peer never
     *  had a heading at all) simply draws no wedge — the dashed dot alone
     *  still shows their last known position. */
    private fun drawSearchCone(canvas: Canvas, cx: Float, cy: Float, rMax: Float, rotationDeg: Float, p: RingPeer) {
        val v = p.vector
        if (v.coneMinM.isNaN() || v.coneMaxM.isNaN() || v.peerHeading.isNaN()) return
        val halfAngle = 25.0 + 15.0 // base + default bearingAcc fallback, see MeshLedger.vectorTo's doc
        val centreBearing = v.peerHeading + rotationDeg
        val loFrac = radiusFraction(v.coneMinM, dMaxM)
        val hiFrac = radiusFraction(v.coneMaxM, dMaxM)
        val loR = rMax * loFrac
        val hiR = rMax * hiFrac
        conePath.reset()
        val steps = 8
        for (i in 0..steps) {
            val ang = Math.toRadians((centreBearing - halfAngle + (2 * halfAngle * i / steps)))
            val x = cx + hiR * sin(ang).toFloat()
            val y = cy - hiR * cos(ang).toFloat()
            if (i == 0) conePath.moveTo(x, y) else conePath.lineTo(x, y)
        }
        for (i in steps downTo 0) {
            val ang = Math.toRadians((centreBearing - halfAngle + (2 * halfAngle * i / steps)))
            val x = cx + loR * sin(ang).toFloat()
            val y = cy - loR * cos(ang).toFloat()
            conePath.lineTo(x, y)
        }
        conePath.close()
        canvas.drawPath(conePath, conePaint)
    }

    /** BLE_ONLY and (PHASE 1.4c) UNKNOWN: pinned to the rim as an ARC
     *  SEGMENT, not a point — we do not know their direction and must never
     *  imply one by picking an arbitrary bearing. A fixed-width arc is the
     *  only honest rendering for either state: "somewhere out there," not
     *  "over there." Never omitted — a peer this app knows about (roster
     *  member or BLE sighting) always gets a mark, even with zero position
     *  data. */
    private fun drawRimArcPeer(canvas: Canvas, cx: Float, cy: Float, rMax: Float, p: RingPeer) {
        val r = rMax - 4f
        bleArcRect.set(cx - r, cy - r, cx + r, cy + r)
        // Spread multiple BLE-only peers around the rim deterministically by
        // nodeId so they don't all stack on the same arc.
        val slot = (p.nodeId.hashCode().mod(12))
        val startAngle = -90f + slot * 30f
        canvas.drawArc(bleArcRect, startAngle, 20f, false, bleArcPaint)
        val labelY = cy - r - 10f - slot * 2f
        canvas.drawText(p.name, cx, labelY, nameLabelPaint)
        // Approximate hit-test anchor at the arc's midpoint — a BLE_ONLY
        // peer has no real bearing (see class doc), so this is only ever
        // "close enough to tap the label," never a claimed direction.
        val midRad = Math.toRadians((startAngle + 10.0))
        recordHitTestPosition(p.nodeId, cx + r * cos(midRad).toFloat(), cy + r * sin(midRad).toFloat())
    }

    private fun drawBridgeGlyph(canvas: Canvas, x: Float, y: Float, size: Float) {
        // Simple bridge/link glyph: two short parallel strokes crossing —
        // reuses bridgeGlyphPaint, no allocation.
        canvas.drawLine(x - size / 2, y - size / 2, x + size / 2, y + size / 2, bridgeGlyphPaint)
        canvas.drawLine(x - size / 2, y + size / 2, x + size / 2, y - size / 2, bridgeGlyphPaint)
    }

    // PHASE 1.6c: `in` (setPeers input) vs `drawn` (non-SKIP render modes) —
    // renderModeFor is exhaustive over MeshLedger.PeerState with no SKIP
    // case in current use, so in==drawn always today; if a future PeerState
    // ever maps to SKIP, this line is what would catch the ring silently
    // dropping peers again (report 1.6's original bug, one level removed).
    private fun maybeLogUiState() {
        val now = System.currentTimeMillis()
        if (now - lastUiLogAtMs < UI_LOG_INTERVAL_MS) return
        lastUiLogAtMs = now
        val drawn = peers.count { renderModeFor(it.vector.state) != RenderMode.SKIP }
        val arcs = peers.count { renderModeFor(it.vector.state) == RenderMode.RIM_ARC }
        Log.d("OFFTRACE", "UI: ring in=${peers.size} drawn=$drawn arcs=$arcs dMax=${dMaxM.toInt()}m")
    }
}
