package com.opencall.relay.offline

/**
 * OFFLINE UI STEP 5: pure, off-device-testable decision logic for the safety
 * shell (battery cliff hysteresis, slide-to-SOS hold timing) — deliberately
 * separated from BatteryCliffMonitor/SlideToSosView (which own the actual
 * Android sensor/touch/haptic wiring) so the STATE MACHINES themselves are
 * directly unit-testable without a Context, same "extract the pure decision
 * core" pattern as MeshElection.pickWinner / MeshLedger.computeVector.
 */
object BatteryCliff {
    const val ENTER_PCT = 15
    const val EXIT_PCT = 20

    /** Hysteresis: enter only at <=15% AND not charging; once in, stay in
     *  until BOTH >20% AND charging — a battery sitting at, say, 18% while
     *  unplugged (inside the 15-20 hysteresis band) never flips either
     *  direction on its own; only the ENTER or EXIT condition being fully
     *  met moves the state. */
    fun nextCliffState(batteryPct: Int, charging: Boolean, currentlyInCliff: Boolean): Boolean {
        return if (currentlyInCliff) {
            !(batteryPct > EXIT_PCT && charging)
        } else {
            batteryPct <= ENTER_PCT && !charging
        }
    }
}

/** Slide-then-hold 3s SOS trigger — see SlideToSosView for the actual touch/
 *  haptic wiring. [HOLD_MS] is the single source of truth for "how long" —
 *  both the fire decision and the haptic ramp read it, so they can never
 *  disagree about when the hold completes. */
object SlideToSos {
    const val HOLD_MS = 3_000L

    /** True only once the hold has reached (or exceeded) [HOLD_MS] — fires
     *  exactly once in practice because the caller (SlideToSosView) checks
     *  this on each timer tick and cancels the timer the instant it returns
     *  true, never on release (release before this is true is a silent
     *  cancel, no call to this function's result matters at all then). */
    fun shouldFire(heldMs: Long): Boolean = heldMs >= HOLD_MS

    /** 0f at touch-down, 1f at (or past) HOLD_MS — the caller scales haptic
     *  amplitude/vibration-effect strength off this fraction. */
    fun hapticIntensityFraction(heldMs: Long): Float = (heldMs.toFloat() / HOLD_MS).coerceIn(0f, 1f)
}
