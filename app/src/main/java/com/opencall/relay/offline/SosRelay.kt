package com.opencall.relay.offline

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.TelephonyManager
import android.util.Log

/**
 * PHASE 6 TRACK B3: the first device to regain any cellular signal escalates a
 * still-un-cleared SOS to the outside world. Deliberately does NOT request
 * SEND_SMS — that's a Play Store restricted permission and would complicate
 * the target-API review already open on this account (per this track's
 * instructions). Instead this fires an ACTION_SENDTO intent with the SMS body
 * pre-filled and asks the CALLER (OfflineCallActivity) to show a full-screen
 * "TAP TO SEND" prompt — one tap, no permission, a human confirms before an
 * emergency SMS actually goes out.
 *
 * SCOPE NOTE on "mark relayed so twenty phones do not all prompt": this is
 * implemented as PER-DEVICE suppression only (a device that has already
 * prompted for a given SOS msgId won't prompt again for it on THIS device) —
 * true cross-device coordination (device A relays, so device B never even
 * asks) would need a new wire signal, which isn't among the frame types this
 * track specifies and isn't added here. Multiple phones independently
 * prompting for the same SOS once each regains signal is a real, redundant
 * behaviour that survives this track — arguably not pure downside (coverage
 * gaps mean the phone that successfully sends may not be the first one that
 * tried), but it's a known, deliberate scope limit, not an oversight.
 */
class SosRelay private constructor(context: Context) {

    data class RelayPrompt(
        val msgId: String,
        val senderName: String,
        val smsBody: String,
        val contacts: List<String>
    )

    companion object {
        private const val PREFS_NAME = "opencall"
        private const val PREF_EMERGENCY_CONTACTS = "emergency_contacts"
        private const val PREF_RELAYED_MSG_IDS = "relayed_sos_msg_ids"

        @Volatile private var instance: SosRelay? = null

        fun get(context: Context): SosRelay =
            instance ?: synchronized(this) {
                instance ?: SosRelay(context.applicationContext).also { instance = it }
            }
    }

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null
    @Volatile private var hadServiceLast = false

    /** Supplies the currently-pending (un-cleared) SOS entries to relay-check
     *  against — wired by the caller from MeshSosManager.sosEntries/ledger. */
    var pendingSosProvider: (() -> List<PendingSos>)? = null
    /** Fired (main thread) with a fully-composed prompt whenever cell signal
     *  returns with at least one not-yet-relayed SOS pending. */
    var onRelayPromptReady: ((RelayPrompt) -> Unit)? = null

    data class PendingSos(
        val msgId: String,
        val senderName: String,
        val latitude: Double?,
        val longitude: Double?,
        val locTier: Int,
        val fixAgeSec: Long,
        val verticalSeparationM: Int?,
        val message: String
    )

    fun emergencyContacts(): List<String> =
        prefs.getString(PREF_EMERGENCY_CONTACTS, "")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    fun setEmergencyContacts(numbers: List<String>) {
        prefs.edit().putString(PREF_EMERGENCY_CONTACTS, numbers.joinToString(",")).apply()
    }

    /** Session-scoped: call when a group session becomes active. Degrades
     *  cleanly (logged) without READ_PHONE_STATE or on a device with no
     *  telephony radio at all — this app already tolerates missing GPS/
     *  barometer hardware the same way. */
    fun register() {
        val mgr = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (mgr == null) {
            Log.d("OFFTRACE", "RELAY: no TelephonyManager on this device")
            return
        }
        telephonyManager = mgr
        // hadServiceLast starts false — if service is already available the
        // moment this registers (e.g. relaunched already back in coverage with
        // an old un-relayed SOS still queued), the listener's own first
        // delivery (Android guarantees one immediately on registration) will
        // correctly treat that as "signal returned" and prompt right away,
        // which is the right behaviour here.
        @Suppress("DEPRECATION")
        val listener = object : PhoneStateListener() {
            @Suppress("DEPRECATION")
            override fun onServiceStateChanged(serviceState: ServiceState) {
                val hasService = serviceState.state == ServiceState.STATE_IN_SERVICE
                if (hasService && !hadServiceLast) {
                    onSignalReturned()
                }
                hadServiceLast = hasService
            }
        }
        try {
            @Suppress("DEPRECATION")
            mgr.listen(listener, PhoneStateListener.LISTEN_SERVICE_STATE)
            phoneStateListener = listener
        } catch (e: SecurityException) {
            Log.w("OFFTRACE", "RELAY: no READ_PHONE_STATE — cell-signal-return detection unavailable")
        } catch (e: Exception) {
            Log.w("OFFTRACE", "RELAY: listener registration failed: ${e.message}")
        }
    }

    fun unregister() {
        val listener = phoneStateListener ?: return
        try {
            @Suppress("DEPRECATION")
            telephonyManager?.listen(listener, PhoneStateListener.LISTEN_NONE)
        } catch (_: Exception) { }
        phoneStateListener = null
    }

    private fun onSignalReturned() {
        val pending = pendingSosProvider?.invoke().orEmpty().filter { !wasAlreadyRelayed(it.msgId) }
        if (pending.isEmpty()) return
        Log.d("OFFTRACE", "RELAY: cell signal detected, ${pending.size} SOS pending — prompting")
        val contacts = emergencyContacts()
        if (contacts.isEmpty()) {
            Log.w("OFFTRACE", "RELAY: signal returned but no emergency contacts configured — nothing to prompt")
            return
        }
        // One prompt per still-pending SOS, most urgent (oldest fix) first is
        // not knowable here — caller renders in whatever order it already
        // shows SOS entries; this just hands over composed prompts.
        pending.forEach { sos ->
            onRelayPromptReady?.invoke(
                RelayPrompt(
                    msgId = sos.msgId,
                    senderName = sos.senderName,
                    smsBody = composeSmsBody(sos),
                    contacts = contacts
                )
            )
        }
    }

    private fun composeSmsBody(sos: PendingSos): String {
        val coordLine = if (sos.latitude != null && sos.longitude != null) {
            val mgrs = GeoUtils.toMgrs(sos.latitude, sos.longitude)
            "%.5f, %.5f (%s)".format(sos.latitude, sos.longitude, mgrs)
        } else {
            "No location fix"
        }
        val tierWord = when (sos.locTier) {
            MeshLocation.LOC_TIER_GPS_LIVE -> "GPS"
            MeshLocation.LOC_TIER_GPS_STALE -> "GPS (stale)"
            MeshLocation.LOC_TIER_PASSIVE -> "approximate"
            else -> "none"
        }
        val vertLine = sos.verticalSeparationM?.let {
            "Vertical separation from relaying phone: ${kotlin.math.abs(it)}m ${if (it >= 0) "above" else "below"}"
        } ?: ""
        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date())
        return buildString {
            appendLine("SOS relayed via OpenCall mesh")
            appendLine("From: ${sos.senderName}")
            appendLine("Location: $coordLine")
            appendLine("Fix: $tierWord, ${sos.fixAgeSec}s old")
            if (vertLine.isNotEmpty()) appendLine(vertLine)
            appendLine("Time: $timestamp")
            if (sos.message.isNotEmpty()) appendLine("Message: ${sos.message}")
        }.trim()
    }

    /** Builds the ACTION_SENDTO intent — no SEND_SMS permission needed, opens
     *  the user's own SMS app with the body pre-filled; the human still has to
     *  tap that app's own Send button. */
    fun buildSendIntent(prompt: RelayPrompt, toNumber: String): Intent =
        Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:$toNumber")
            putExtra("sms_body", prompt.smsBody)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** Call once the user has actually tapped through to send (or explicitly
     *  dismissed) — suppresses further prompts for this msgId on THIS device. */
    fun markRelayed(msgId: String) {
        val set = prefs.getStringSet(PREF_RELAYED_MSG_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
        set.add(msgId)
        prefs.edit().putStringSet(PREF_RELAYED_MSG_IDS, set).apply()
    }

    private fun wasAlreadyRelayed(msgId: String): Boolean =
        prefs.getStringSet(PREF_RELAYED_MSG_IDS, emptySet())?.contains(msgId) == true
}
