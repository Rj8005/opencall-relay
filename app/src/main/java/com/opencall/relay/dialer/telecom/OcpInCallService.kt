package com.opencall.relay.dialer.telecom

import android.telecom.Call
import android.telecom.InCallService
import android.telephony.PhoneNumberUtils
import android.util.Log
import com.opencall.relay.dialer.ui.InCallActivity

/**
 * PART 2.2: the InCallService implementation — this is what makes the app a
 * real dialer rather than just something that can fire off a `tel:` intent.
 * Manifest registration (BIND_INCALL_SERVICE + IN_CALL_SERVICE_UI) is in
 * Part 1.2's diff.
 *
 * Telecom delivers every call this device is involved in here —
 * [onCallAdded] for a new one (incoming OR the one this app/another app
 * just placed), [onCallRemoved] once it's fully gone. This class does not
 * own any UI itself; it maintains the live call list and notifies observers
 * (see [Listener]) — [InCallActivity] and [IncomingCallNotifier] are the
 * observers that turn that into what the user actually sees.
 */
class OcpInCallService : InCallService() {

    interface Listener {
        /** Fired on every call-list or call-state change. [calls] is the
         *  full current list — observers diff it themselves if they need to. */
        fun onCallsChanged(calls: List<Call>)
    }

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            Log.d(TAG, "call state -> $state for ${safeNumber(call)}")
            notifyListeners()
            if (state == Call.STATE_RINGING) {
                maybeShowIncomingUi(call)
            }
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) {
            notifyListeners()
        }
    }

    override fun onCreate() {
        super.onCreate()
        boundInstance = this
    }

    override fun onDestroy() {
        boundInstance = null
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        Log.d(TAG, "onCallAdded ${safeNumber(call)} state=${call.state}")
        call.registerCallback(callCallback)
        synchronized(callsLock) { trackedCalls = trackedCalls + call }
        notifyListeners()
        when (call.state) {
            Call.STATE_RINGING -> maybeShowIncomingUi(call)
            else -> InCallActivity.launch(this, callInProgress = true)
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.d(TAG, "onCallRemoved ${safeNumber(call)}")
        call.unregisterCallback(callCallback)
        synchronized(callsLock) { trackedCalls = trackedCalls - call }
        notifyListeners()
        IncomingCallNotifier.cancel(this)
    }

    /** PART 2.4: an emergency call is never given OUR OWN incoming-call UI
     *  treatment (full-screen intent pointed at our activity, notification
     *  actions that route through our own answer/reject logic) — the
     *  platform's own emergency-call handling takes priority regardless of
     *  which InCallService is bound, and this app must not get in the way
     *  of that by assuming ownership of the UI for it. The call is still
     *  tracked (so [onCallsChanged]'s list stays accurate for anything that
     *  reads it), just never routed through [IncomingCallNotifier]/
     *  [InCallActivity] as if it were an ordinary ringing call. */
    private fun maybeShowIncomingUi(call: Call) {
        val number = safeNumber(call)
        if (number != null && EmergencyCallGuard.isEmergencyNumber(this, number)) {
            Log.d(TAG, "emergency call detected in onCallAdded — not showing our own incoming UI")
            return
        }
        IncomingCallNotifier.notify(this, call)
    }

    private fun safeNumber(call: Call): String? =
        call.details?.handle?.schemeSpecificPart?.let { PhoneNumberUtils.stripSeparators(it) }

    private fun notifyListeners() {
        val snapshot = synchronized(callsLock) { trackedCalls }
        listeners.forEach { it.onCallsChanged(snapshot) }
    }

    companion object {
        private const val TAG = "OcpInCallService"
        private val callsLock = Any()
        @Volatile private var trackedCalls: List<Call> = emptyList()
        private val listeners = java.util.concurrent.CopyOnWriteArraySet<Listener>()

        // PART 2.2: mute/speaker are properties of the InCallService binding
        // itself (InCallService.setMuted/setAudioRoute), not of a Call — the
        // UI Activity is a separate component from the Service the system
        // binds, so it needs a live reference to call these on. Null
        // whenever Telecom has unbound the service (no active/ringing call).
        @Volatile private var boundInstance: OcpInCallService? = null

        fun setMuted(muted: Boolean) {
            boundInstance?.setMuted(muted)
        }

        fun setSpeakerOn(on: Boolean) {
            boundInstance?.setAudioRoute(
                if (on) android.telecom.CallAudioState.ROUTE_SPEAKER else android.telecom.CallAudioState.ROUTE_EARPIECE
            )
        }

        fun currentAudioState(): android.telecom.CallAudioState? = boundInstance?.callAudioState

        fun addListener(listener: Listener) {
            listeners.add(listener)
            listener.onCallsChanged(currentCalls())
        }

        fun removeListener(listener: Listener) {
            listeners.remove(listener)
        }

        fun currentCalls(): List<Call> = synchronized(callsLock) { trackedCalls }

        /** The call currently RINGING, if any — Part 2.3's "second incoming
         *  call during an active one" reads this alongside [activeCall]. */
        fun ringingCall(): Call? = currentCalls().firstOrNull { it.state == Call.STATE_RINGING }

        fun activeCall(): Call? = currentCalls().firstOrNull {
            it.state == Call.STATE_ACTIVE || it.state == Call.STATE_DIALING || it.state == Call.STATE_HOLDING
        }
    }
}
