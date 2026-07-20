package com.opencall.relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

class SmsReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val from = messages[0].originatingAddress ?: run {
            Log.w(TAG, "SMS received with no originating address")
            return
        }
        // Concatenate multipart message bodies in order
        val text = messages.joinToString("") { it.messageBody }

        Log.d(TAG, "SMS from $from: ${text.take(60)}${if (text.length > 60) "…" else ""}")
        RelayForegroundService.instance?.forwardIncomingSms(from, text)
    }
}
