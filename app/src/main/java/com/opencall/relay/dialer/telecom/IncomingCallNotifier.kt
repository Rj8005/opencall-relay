package com.opencall.relay.dialer.telecom

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.VideoProfile
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.opencall.relay.R
import com.opencall.relay.dialer.data.ContactsRepository
import com.opencall.relay.dialer.ui.InCallActivity

/**
 * PART 2.3: incoming-call presentation. One notification does both jobs the
 * task asks for — "full-screen intent when locked, heads-up notification
 * when not" — because that split is the PLATFORM's own decision, not this
 * app's: [NotificationCompat.Builder.setFullScreenIntent] combined with
 * [NotificationCompat.CATEGORY_CALL] and max priority/importance is the
 * documented, standard way to build a calling notification, and Android
 * itself chooses to actually present it full-screen only when the device is
 * locked (or a small number of other restricted-attention states),
 * otherwise surfacing the SAME notification as a heads-up banner. Building
 * two different code paths keyed off a manual [android.app.KeyguardManager]
 * check would be redundant with — and could drift out of sync with — that
 * platform behaviour, so this deliberately does not do that.
 */
object IncomingCallNotifier {

    private const val CHANNEL_ID = "opencall_dialer_incoming_call"
    private const val NOTIFICATION_ID = 0x0CA11 // "call"

    const val ACTION_ANSWER = "com.opencall.relay.dialer.ACTION_ANSWER"
    const val ACTION_REJECT = "com.opencall.relay.dialer.ACTION_REJECT"

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID, "Incoming calls", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Incoming phone calls"
            setBypassDnd(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(channel)
    }

    fun notify(context: Context, call: Call) {
        ensureChannel(context)
        val number = call.details?.handle?.schemeSpecificPart
        val displayName = number?.let { ContactsRepository.lookupNameForNumber(context, it) } ?: number ?: "Unknown"

        val fullScreenIntent = Intent(context, InCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(displayName)
            .setContentText(if (number != null && number != displayName) number else "Incoming call")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .addAction(0, "Answer", actionPendingIntent(context, ACTION_ANSWER))
            .addAction(0, "Decline", actionPendingIntent(context, ACTION_REJECT))
            .setOngoing(true)
            .setAutoCancel(false)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun actionPendingIntent(context: Context, action: String): PendingIntent {
        val intent = Intent(context, IncomingCallActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

/** Backs the notification's Answer/Decline actions (Part 2.3) — acts on
 *  whichever call is currently RINGING per [OcpInCallService]'s own
 *  tracked list, exactly the same `Call.answer`/`Call.reject` the in-call
 *  UI's own buttons use (see InCallActivity), so both entry points behave
 *  identically. */
class IncomingCallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val call = OcpInCallService.ringingCall() ?: return
        when (intent.action) {
            IncomingCallNotifier.ACTION_ANSWER -> {
                call.answer(VideoProfile.STATE_AUDIO_ONLY)
                IncomingCallNotifier.cancel(context)
            }
            IncomingCallNotifier.ACTION_REJECT -> {
                call.reject(false, null)
                IncomingCallNotifier.cancel(context)
            }
        }
    }
}
