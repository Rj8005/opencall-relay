package com.opencall.relay.dialer.identity

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.opencall.relay.account.AccountStore
import com.opencall.relay.offline.OfflineIdentity

/**
 * PART 5.1: the dialer never invents a second notion of "who the user is."
 * [OfflineIdentity]'s Ed25519 nodeId (already the mesh pillar's root
 * identity — see that object's own doc) stays the ONE root identity for the
 * whole app; this object only ever READS it via [OfflineIdentity]'s already-
 * public API ([OfflineIdentity.nodeId]/[OfflineIdentity.hex]) — nothing here
 * touches a file under offline/, and nothing here generates or stores any
 * competing key/id.
 *
 * A SIM number (MSISDN) is an ATTRIBUTE of the account this device happens
 * to be running on right now, not an identity — a user who swaps SIMs, or
 * runs this app on a Wi-Fi-only tablet with no SIM at all, is still the
 * exact same nodeId. This object's only job is storing that attribute
 * alongside the nodeId, honestly marked as verified or not (see
 * [readOwnMsisdn]'s doc for why it is ALWAYS read as unverified).
 */
object DialerIdentity {

    // PART 5.2: the number itself + verified flag now live in AccountStore —
    // the one account store shared by all three tabs — so a SIM number
    // learned here and one edited from Settings can never disagree. This
    // prefs store now holds ONLY the "how did we learn this" provenance
    // (Source), which AccountStore has no concept of and doesn't need one.
    private const val PREFS_NAME = "opencall_dialer_identity"
    private const val KEY_SOURCE = "own_msisdn_source"

    /** The root identity this whole app is keyed on — delegates straight to
     *  [OfflineIdentity], never a second key. */
    fun rootNodeIdHex(context: Context): String = OfflineIdentity.hex(OfflineIdentity.nodeId(context))

    /** A stored MSISDN plus how it got there. [verified] is true ONLY for a
     *  number the user typed in and explicitly confirmed as their own
     *  (there is no OTP/verification flow in this pillar — "verified" here
     *  means "user-attested," not "network-proven"); a number read straight
     *  off [TelephonyManager] is ALWAYS [Source.PLATFORM_READ] and ALWAYS
     *  unverified — see [readOwnMsisdn]'s doc for why that platform value
     *  itself cannot be trusted as ground truth. */
    enum class Source { PLATFORM_READ, USER_ENTERED }
    data class OwnNumber(val msisdn: String, val verified: Boolean, val source: Source)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** PART 5.1: reads the device's own number where the platform allows it.
     *  [TelephonyManager.getLine1Number] is well documented as unreliable —
     *  many carriers (especially prepaid, and most non-US carriers) never
     *  populate it at all, and even when populated the platform gives NO
     *  guarantee it is correct or current (it is whatever the SIM/carrier
     *  happens to report, not a cryptographically verified fact). For that
     *  reason a value read from here is ALWAYS stored with
     *  [OwnNumber.verified] = false, regardless of whether the read itself
     *  "succeeded" — there is no code path in this object that marks a
     *  platform-read number verified.
     *
     *  Requires READ_PHONE_STATE (or READ_PHONE_NUMBERS/READ_SMS on some
     *  API levels/OEMs — READ_PHONE_STATE alone is what this app requests,
     *  see Part 1.3) to even attempt the read; returns null (not a thrown
     *  exception) for every absence case: permission not granted, API
     *  throwing SecurityException anyway (some OEMs), or the platform
     *  simply having no number to report — all three are the SAME
     *  "we don't know" outcome to every caller of this function. */
    fun readOwnMsisdn(context: Context): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val tm = ContextCompat.getSystemService(context, TelephonyManager::class.java) ?: return null
        return try {
            @Suppress("DEPRECATION") // getLine1Number has no non-deprecated replacement as of API 34
            tm.line1Number?.trim()?.takeIf { it.isNotBlank() }
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /** Refreshes the stored attribute from the platform, if the platform
     *  has anything to say — a null [readOwnMsisdn] result NEVER clears an
     *  already-stored number (platform absence is not evidence the
     *  previously-stored number, especially a user-entered one, is wrong)
     *  — it just leaves storage untouched. */
    fun refreshFromPlatform(context: Context) {
        val msisdn = readOwnMsisdn(context) ?: return
        store(context, msisdn, verified = false, source = Source.PLATFORM_READ)
    }

    /** Explicit user entry — still stored as [verified]=true only when the
     *  caller has actually confirmed it with the user (e.g. a "yes, this is
     *  my number" screen); this function does not itself impose that
     *  confirmation step, it just records whatever the caller asserts. */
    fun storeUserEntered(context: Context, msisdn: String, verified: Boolean) {
        store(context, msisdn, verified, Source.USER_ENTERED)
    }

    private fun store(context: Context, msisdn: String, verified: Boolean, source: Source) {
        AccountStore.setSimNumber(context, msisdn, verified)
        prefs(context).edit().putString(KEY_SOURCE, source.name).apply()
    }

    fun getStoredOwnNumber(context: Context): OwnNumber? {
        val account = AccountStore.get(context)
        val number = account.simNumber ?: return null
        val source = prefs(context).getString(KEY_SOURCE, null)
            ?.let { runCatching { Source.valueOf(it) }.getOrNull() }
            ?: Source.PLATFORM_READ
        return OwnNumber(number, account.simVerified, source)
    }

    fun clearStoredOwnNumber(context: Context) {
        AccountStore.clearSimNumber(context)
        prefs(context).edit().remove(KEY_SOURCE).apply()
    }
}
