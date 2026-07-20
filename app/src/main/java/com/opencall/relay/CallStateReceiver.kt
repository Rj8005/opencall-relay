package com.opencall.relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

class CallStateReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "CallStateReceiver"
        var onOffhook: (() -> Unit)? = null
        var onIdle: (() -> Unit)? = null
        var onRinging: (() -> Unit)? = null
        var isMonitoring = false
        var lastState = TelephonyManager.CALL_STATE_IDLE
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!isMonitoring) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        Log.d(TAG, "📶 BroadcastReceiver state: $state")

        when (state) {
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (lastState != TelephonyManager.CALL_STATE_OFFHOOK) {
                    lastState = TelephonyManager.CALL_STATE_OFFHOOK
                    Log.d(TAG, "✅ OFFHOOK — call active!")
                    onOffhook?.invoke()
                }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (lastState != TelephonyManager.CALL_STATE_IDLE) {
                    lastState = TelephonyManager.CALL_STATE_IDLE
                    Log.d(TAG, "📴 IDLE — call ended")
                    onIdle?.invoke()
                }
            }
            TelephonyManager.EXTRA_STATE_RINGING -> {
                lastState = TelephonyManager.CALL_STATE_RINGING
                Log.d(TAG, "🔔 RINGING")
                onRinging?.invoke()
            }
        }
    }
}
