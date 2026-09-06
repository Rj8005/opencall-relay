package com.opencall.relay.dialer.route

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import android.Manifest as AndroidManifest

/**
 * PART 4.2: the only [CallRoute] implementation that exists today — places a
 * call on the user's own SIM via [TelecomManager.placeCall]. With this as
 * the only route registered, [CallRouteRegistry] adds no behaviour on top of
 * this class (see that file's doc) — "one route registered, behaviour
 * identical to placing a call directly" is a property of the registry, not
 * something this class has to prove itself.
 *
 * MULTI-SIM: deliberately NOT expressed through [CallRoute]'s generic
 * `place(target)` — [PhoneAccountHandle] selection is a SIM-specific detail
 * no other route will ever have a use for, so it lives on this concrete
 * class as an overload ([place] with an explicit handle) plus a
 * per-number "remembered choice" store, rather than widening the shared
 * interface for one implementation's concern.
 */
class SimCallRoute(private val context: Context) : CallRoute {

    override val id: String = "sim"

    private val telecomManager: TelecomManager? by lazy {
        ContextCompat.getSystemService(context, TelecomManager::class.java)
    }
    private val subscriptionManager: SubscriptionManager? by lazy {
        ContextCompat.getSystemService(context, SubscriptionManager::class.java)
    }
    private val prefs: SharedPreferences by lazy {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun hasCallPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, AndroidManifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED

    /** True if the platform will actually let a voice call proceed right
     *  now — false covers both airplane mode (no radio) and "no telephony
     *  hardware at all" (a tablet/Wi-Fi-only build of this app). Read fresh
     *  every call, never cached — airplane mode can flip at any time. */
    private fun radioUsable(): Boolean {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) return false
        val airplaneMode = android.provider.Settings.Global.getInt(
            context.contentResolver, android.provider.Settings.Global.AIRPLANE_MODE_ON, 0
        ) != 0
        return !airplaneMode
    }

    override fun canReach(target: CallTarget): Reachability {
        if (target.e164Number.isNullOrBlank()) return Reachability.unavailable()
        if (!hasCallPermission()) return Reachability.unavailable()
        if (!radioUsable()) return Reachability.unavailable()
        // A real SIM call always costs real carrier minutes — this route can
        // never truthfully claim FREE, and it has no rate-plan visibility to
        // claim a specific METERED figure, so UNKNOWN is the honest answer
        // (see CostHint's own doc).
        return Reachability.available(CostHint.UNKNOWN)
    }

    /** [CallRoute]'s generic entry point — resolves the account via
     *  [resolveAccount] (remembered choice, else the platform default, else
     *  none) and delegates to the explicit-handle overload below. */
    override fun place(target: CallTarget): CallHandle =
        place(target, resolveAccount(target.e164Number))

    /** Explicit-account overload — what the UI calls once it has resolved
     *  (or the user has just picked) which SIM to use. */
    fun place(target: CallTarget, accountHandle: PhoneAccountHandle?): CallHandle {
        val number = target.e164Number
        if (number.isNullOrBlank()) {
            return failedHandle(target, "No phone number for this target")
        }
        if (!hasCallPermission()) {
            return failedHandle(target, "CALL_PHONE permission not granted")
        }
        if (!radioUsable()) {
            return failedHandle(
                target,
                if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY))
                    "This device has no telephony radio"
                else
                    "Airplane mode is on"
            )
        }
        val tm = telecomManager ?: return failedHandle(target, "TelecomManager unavailable")
        return try {
            val extras = android.os.Bundle().apply {
                if (accountHandle != null) putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, accountHandle)
            }
            tm.placeCall(Uri.fromParts("tel", number, null), extras)
            SimCallHandle(target = target, requestAccepted = true, failureReason = null)
        } catch (e: SecurityException) {
            failedHandle(target, "Not permitted to place calls: ${e.message}")
        } catch (e: Exception) {
            failedHandle(target, "placeCall failed: ${e.message}")
        }
    }

    private fun failedHandle(target: CallTarget, reason: String): CallHandle =
        SimCallHandle(target = target, requestAccepted = false, failureReason = reason)

    // ── Multi-SIM (Part 2.1) ─────────────────────────────────────────────

    /** Every calling-capable [PhoneAccountHandle] the platform currently
     *  knows about — empty on a single-SIM device (nothing to pick between;
     *  callers should only show a picker when this has more than one
     *  entry), empty also if READ_PHONE_STATE isn't granted (the underlying
     *  TelecomManager query needs it on some OEM builds) or the permission
     *  check itself throws. */
    fun callCapableAccounts(): List<PhoneAccountHandle> {
        if (!hasCallPermission()) return emptyList()
        val tm = telecomManager ?: return emptyList()
        return try {
            tm.callCapablePhoneAccounts
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    /** Human-readable label for [handle] — the SIM's carrier/display name if
     *  [SubscriptionManager] can resolve it, else TelecomManager's own
     *  PhoneAccount label, else the raw handle id as a last resort. */
    fun labelFor(handle: PhoneAccountHandle): String {
        val tm = telecomManager
        val account = try {
            tm?.getPhoneAccount(handle)
        } catch (e: SecurityException) {
            null
        }
        return account?.label?.toString() ?: handle.id
    }

    /** Part 2.1: "remember the choice per contact" — keyed by the E.164
     *  number, not a contact-row id, so the same number reached from
     *  different UI entry points (keypad redial, contact row, call log)
     *  shares one remembered choice. */
    fun rememberAccountChoice(e164Number: String, handle: PhoneAccountHandle) {
        prefs.edit().putString(KEY_PREFIX + e164Number, handle.id).apply()
    }

    fun forgetAccountChoice(e164Number: String) {
        prefs.edit().remove(KEY_PREFIX + e164Number).apply()
    }

    /** Remembered choice for [number] if one exists AND that account is
     *  still among the platform's current call-capable accounts (a SIM can
     *  be removed between calls — a stale remembered id must not silently
     *  resolve to nothing or crash placeCall); else the platform's single
     *  account if there's exactly one; else null (caller must ask the
     *  user). */
    fun resolveAccount(number: String?): PhoneAccountHandle? {
        val accounts = callCapableAccounts()
        if (accounts.isEmpty()) return null
        if (accounts.size == 1) return accounts.single()
        val remembered = number?.let { prefs.getString(KEY_PREFIX + it, null) }
        return accounts.firstOrNull { it.id == remembered }
    }

    companion object {
        private const val PREFS_NAME = "opencall_dialer_sim_accounts"
        private const val KEY_PREFIX = "account_for:"
    }
}

private data class SimCallHandle(
    override val target: CallTarget,
    override val requestAccepted: Boolean,
    override val failureReason: String?
) : CallHandle {
    override val routeId: String = "sim"
    override fun disconnect() {
        // See CallHandle's own doc: once TelecomManager.placeCall has been
        // issued, this app no longer has a synchronous handle on the
        // resulting Call — real disconnect happens through
        // OcpInCallService's own Call.Callback once onCallAdded fires
        // (Call.disconnect()), not through this receipt object.
    }
}
