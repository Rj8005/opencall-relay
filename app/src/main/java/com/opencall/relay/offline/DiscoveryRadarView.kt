package com.opencall.relay.offline

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * FIX 4: radar-style discovery UI — Xender-style sweeping arc with devices
 * as dots. A SEPARATE view from [PartyRingView] on purpose: PartyRingView
 * plots peers this device is already connected/roster-known to, with a
 * real bearing+distance vector (MeshLedger.vectorTo). This view plots
 * devices that are merely REACHABLE (pre-connect, from
 * OfflineCallActivity's nearbyDevices map) — there is no bearing for an
 * undiscovered device, and no reliable distance (Wi-Fi Direct exposes no
 * per-link RSSI at all — see MeshLedger.kt's own "RSSI TREND" note — and
 * even BLE RSSI is only a coarse signal-strength proxy, never a metres
 * figure). Merging the two would either invent bearings that don't exist,
 * or start treating an unverified pre-connect sighting as if it were a
 * verified peer position. The party ring is about where people ARE; this
 * is about who is REACHABLE — kept apart.
 *
 * DATA IN, PIXELS OUT — same discipline as PartyRingView: [setDevices] and
 * [setSweeping] are the only data inputs. This view never touches
 * WifiDirectManager/MeshLedger/nearbyDevices/sendInvite itself; a tap only
 * ever invokes [onDeviceTapped], which OfflineCallActivity wires to its
 * EXISTING sendInvite() choke point (see that wiring's own try/catch,
 * matching buildNearbyDeviceRow's Invite-button handler) — there is no
 * second connect path here.
 */
class DiscoveryRadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class RadarStatus { NOT_INVITED, INVITING, JOINED, FAILED }

    /** One reachable device's drawable state. [rssiDbm] is null for a
     *  Wi-Fi-Direct-only sighting with no BLE presence (no RSSI exists at
     *  all there — see class doc); [bandFor] maps that to UNKNOWN, never a
     *  guessed band. [nodeId] is the tap-target key (whatever
     *  OfflineCallActivity's nearbyDevices map currently has this device
     *  keyed under — may be a pre-resolution address/synthetic key).
     *  [radarSeed] defaults to [nodeId] but is deliberately a SEPARATE field
     *  ([stableAngleDeg] is keyed on it, not on [nodeId]): a device can be
     *  rekeyed (an UNRESOLVED sighting resolving onto its real nodeId)
     *  without its on-screen angle jumping, as long as the caller carries
     *  the original seed forward across that rekey — see
     *  OfflineCallActivity.NearbyDevice.radarSeed's doc for the producing
     *  side of that guarantee. */
    data class RadarDevice(
        val nodeId: Long,
        val displayName: String,
        val rssiDbm: Int?,
        val status: RadarStatus,
        val radarSeed: Long = nodeId
    )

    enum class RssiBand { NEAR, MID, FAR, UNKNOWN }
    enum class RadarUiState { NO_DEVICES_SWEEPING, DEVICES_FOUND, CONNECTING, CONNECTED, GROUP_LIVE_STOPPED }

    companion object {
        private const val SWEEP_REVOLUTION_MS = 3_000L
        // 20fps while sweeping — enough for a visibly smooth arc without
        // being a tight animation loop; fully stopped (not just skipped)
        // whenever setSweeping(false) is called, see that function's doc.
        private const val SWEEP_FRAME_INTERVAL_MS = 50L
        private const val SWEEP_WEDGE_DEG = 30f
        private const val MAX_HIT_TEST_MARKERS = 24

        /** 4a: stable per-nodeId angle, NOT a bearing (none exists for an
         *  undiscovered device) — a pure function of nodeId alone, so a
         *  dot never jitters across redraws just because [setDevices] was
         *  called again with the same device. Fibonacci hashing (multiply
         *  by the nearest odd integer to 2^64/phi, keep the top bits)
         *  spreads even numerically-close nodeIds across the full circle,
         *  unlike a plain `nodeId % 360` which would cluster them. */
        fun stableAngleDeg(nodeId: Long): Float {
            val mixed = nodeId * -0x61c8864680b583ebL
            val bucket = (mixed ushr 48) and 0xFFFFL
            return (bucket.toFloat() / 0xFFFFL.toFloat()) * 360f
        }

        /** 4b: COARSE bands only — never a metres figure, see class doc for
         *  why a distance number would be a lie here. Thresholds are the
         *  common BLE proximity convention. A device with no RSSI at all is
         *  UNKNOWN, never defaulted into NEAR or MID. */
        fun bandFor(rssiDbm: Int?): RssiBand = when {
            rssiDbm == null -> RssiBand.UNKNOWN
            rssiDbm >= -60 -> RssiBand.NEAR
            rssiDbm >= -80 -> RssiBand.MID
            else -> RssiBand.FAR
        }

        /** Radius FRACTION of the drawable radius for each band. UNKNOWN
         *  sits at the outer ring deliberately — better to under-claim
         *  proximity than invent it (same reasoning as PartyRingView's rim
         *  arc for peers with no bearing). */
        fun radiusFractionForBand(band: RssiBand): Float = when (band) {
            RssiBand.NEAR -> 0.35f
            RssiBand.MID -> 0.65f
            RssiBand.FAR -> 0.9f
            RssiBand.UNKNOWN -> 0.9f
        }

        /** 4c: pure sweep-angle-at-time-t, one full revolution every
         *  [revolutionMs]. Only ever fed SystemClock.elapsedRealtime-based
         *  input by [onDraw] — never wall-clock time, which can jump. */
        fun sweepAngleDeg(elapsedMs: Long, revolutionMs: Long = SWEEP_REVOLUTION_MS): Float {
            if (revolutionMs <= 0L) return 0f
            val phase = elapsedMs % revolutionMs
            return (phase.toFloat() / revolutionMs.toFloat()) * 360f
        }

        /** 4e: the five on-screen states, computed from data rather than
         *  tracked as a separately-mutable field that could drift out of
         *  sync with what's actually drawn. [groupLive] always wins — per
         *  FIX 1c the nearby-refresh loop is paused the moment a group
         *  forms, so there is no fresher device list coming either way. */
        fun uiStateFor(devices: List<RadarDevice>, groupLive: Boolean): RadarUiState {
            if (groupLive) return RadarUiState.GROUP_LIVE_STOPPED
            if (devices.any { it.status == RadarStatus.JOINED }) return RadarUiState.CONNECTED
            if (devices.any { it.status == RadarStatus.INVITING }) return RadarUiState.CONNECTING
            if (devices.isEmpty()) return RadarUiState.NO_DEVICES_SWEEPING
            return RadarUiState.DEVICES_FOUND
        }

        /** 4c: sweeping must stop for ANY of these — tab hidden, screen off
         *  (the Activity is paused either way), or the battery cliff is
         *  active — kept as one pure decision so OfflineCallActivity has a
         *  single call site to push into [setSweeping], instead of several
         *  call sites each independently deciding to stop/resume and
         *  risking one forgetting a condition. A live group also stops
         *  sweeping (see [uiStateFor]) but that's data-driven, not an
         *  activity-lifecycle/power gate, so it is deliberately NOT folded
         *  into this function — OfflineCallActivity ANDs both together. */
        fun shouldSweep(tabVisible: Boolean, activityResumed: Boolean, batteryCliffActive: Boolean): Boolean =
            tabVisible && activityResumed && !batteryCliffActive
    }

    /** Never throws on its own — a tap simply calls this and this view
     *  moves on; OfflineCallActivity's own try/catch (matching
     *  buildNearbyDeviceRow's Invite-button handler) is what surfaces a
     *  failure on screen, see class doc. */
    var onDeviceTapped: ((nodeId: Long) -> Unit)? = null

    // ── Hoisted drawing state — allocated once, never inside onDraw ────────
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val notInvitedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val invitingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val joinedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val failedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; textAlign = Paint.Align.CENTER }
    private val centreDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringRect = RectF()
    private val sweepRect = RectF()

    private val hitTestNodeIds = LongArray(MAX_HIT_TEST_MARKERS)
    private val hitTestX = FloatArray(MAX_HIT_TEST_MARKERS)
    private val hitTestY = FloatArray(MAX_HIT_TEST_MARKERS)
    private var hitTestCount = 0

    private var devices: List<RadarDevice> = emptyList()
    private var groupLive: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    private var sweeping = false
    private var sweepStartElapsedMs = 0L
    private val sweepTick = object : Runnable {
        override fun run() {
            invalidate()
            if (sweeping) handler.postDelayed(this, SWEEP_FRAME_INTERVAL_MS)
        }
    }

    init {
        applyNightMode(false)
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            hitTest(e.x, e.y)?.let { onDeviceTapped?.invoke(it) }
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return true
    }

    /** [4f]: every colour read from OfflineCallActivity.NightPalette — the
     *  same object every other surface in this app draws from — with only
     *  alpha compositing (for the background rings/sweep wedge) added on
     *  top; no colour literal chosen independently here. */
    fun applyNightMode(enabled: Boolean) {
        val fg = OfflineCallActivity.TopoPalette.fg(enabled)
        val muted = OfflineCallActivity.TopoPalette.mutedFg(enabled)
        val fail = OfflineCallActivity.TopoPalette.failFg(enabled)
        ringPaint.color = withAlpha(muted, 90)
        sweepPaint.color = withAlpha(fg, 70)
        notInvitedPaint.color = fg
        invitingPaint.color = muted
        joinedPaint.color = fg
        failedPaint.color = fail
        labelPaint.color = fg
        centreDotPaint.color = fg
        invalidate()
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    /** Only data input besides [setSweeping] — never allocates, never reads
     *  device state itself; the Activity is the only source of truth for
     *  "what's reachable right now" (see refreshNearbyDevices/
     *  notifyNearbyAdaptersChanged). */
    fun setDevices(newDevices: List<RadarDevice>, groupLive: Boolean) {
        this.devices = newDevices
        this.groupLive = groupLive
        invalidate()
    }

    /** 4c: starts/stops the sweep animation loop ENTIRELY — not merely
     *  skipping the draw while "sweeping" — so a stopped radar truly stops
     *  posting Runnables (an always-spinning animation on a rescue app is a
     *  battery bug, not a cosmetic nicety). Callers should push
     *  [shouldSweep]'s result here rather than deciding independently. */
    fun setSweeping(enabled: Boolean) {
        if (sweeping == enabled) return
        sweeping = enabled
        if (enabled) {
            sweepStartElapsedMs = SystemClock.elapsedRealtime()
            handler.post(sweepTick)
        } else {
            handler.removeCallbacks(sweepTick)
            invalidate() // one final frame with the wedge gone
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(sweepTick)
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val rMax = min(width, height) / 2f - 40f
        if (rMax <= 0f) return
        hitTestCount = 0

        drawRings(canvas, cx, cy, rMax)
        if (sweeping) drawSweep(canvas, cx, cy, rMax)
        drawDevices(canvas, cx, cy, rMax)
        canvas.drawCircle(cx, cy, 10f, centreDotPaint)
    }

    private fun drawRings(canvas: Canvas, cx: Float, cy: Float, rMax: Float) {
        for (frac in floatArrayOf(0.35f, 0.65f, 0.9f)) {
            val r = rMax * frac
            ringRect.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawOval(ringRect, ringPaint)
        }
    }

    private fun drawSweep(canvas: Canvas, cx: Float, cy: Float, rMax: Float) {
        val elapsed = SystemClock.elapsedRealtime() - sweepStartElapsedMs
        val angle = sweepAngleDeg(elapsed)
        sweepRect.set(cx - rMax, cy - rMax, cx + rMax, cy + rMax)
        canvas.drawArc(sweepRect, angle - SWEEP_WEDGE_DEG, SWEEP_WEDGE_DEG, true, sweepPaint)
    }

    private fun drawDevices(canvas: Canvas, cx: Float, cy: Float, rMax: Float) {
        for (d in devices) {
            val band = bandFor(d.rssiDbm)
            val r = rMax * radiusFractionForBand(band)
            // radarSeed, not nodeId — see RadarDevice's doc: a resolved
            // device's nodeId can change (rekeyed onto its real nodeId) but
            // radarSeed never does, so this dot never jumps when that happens.
            val rad = Math.toRadians(stableAngleDeg(d.radarSeed).toDouble())
            val x = cx + r * cos(rad).toFloat()
            val y = cy + r * sin(rad).toFloat()
            recordHitTestPosition(d.nodeId, x, y)
            val paint = when (d.status) {
                RadarStatus.NOT_INVITED -> notInvitedPaint
                RadarStatus.INVITING -> invitingPaint
                RadarStatus.JOINED -> joinedPaint
                RadarStatus.FAILED -> failedPaint
            }
            canvas.drawCircle(x, y, 14f, paint)
            canvas.drawText(d.displayName, x, y - 20f, labelPaint)
        }
    }
}
