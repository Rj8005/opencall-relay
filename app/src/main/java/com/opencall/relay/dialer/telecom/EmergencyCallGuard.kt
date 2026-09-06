package com.opencall.relay.dialer.telecom

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * PART 2.4 — the one check in this whole pillar that must never be wrong.
 * An emergency number is NEVER handed to [com.opencall.relay.dialer.route.CallRouteRegistry],
 * NEVER checked against [com.opencall.relay.dialer.route.SimCallRoute]'s
 * permission/airplane-mode gates, and NEVER shown in this app's own in-call
 * UI as if it were an ordinary call this app is managing — it goes straight
 * to the platform's own emergency-call handling, which the Android Telecom
 * stack guarantees runs regardless of which app is the current default
 * dialer (see [placeViaSystem]'s doc for the exact mechanism and why this
 * is true even though the intent originates from this app).
 *
 * EVERY call-initiation path in this pillar — the keypad's dial button
 * (Part 3.1), a contact/call-log row's call button (Part 3.2/3.3), and
 * [com.opencall.relay.dialer.role.DialerRoleManager]'s own dial handling —
 * must call [isEmergencyNumber] FIRST, before ever touching a [com.opencall.relay.dialer.route.CallRoute].
 */
object EmergencyCallGuard {

    /** Only used below [Build.VERSION_CODES.Q], where
     *  [TelephonyManager.isEmergencyNumber] does not exist yet (it landed in
     *  API 29) — minSdk for this app is 26. NOT exhaustive by design: it is
     *  a floor, not the source of truth. The real, authoritative,
     *  country-aware list is [TelephonyManager]'s own, used on every device
     *  that has it (API 29+ — the large majority of this app's supported
     *  range). Numbers below are the ones that appear on virtually every
     *  "list of emergency numbers by country" reference: 911 (NANP), 112
     *  (EU/GSM standard, also accepted by every GSM handset worldwide as an
     *  alias), 999 (UK/Commonwealth), 000 (Australia), 110/119 (Japan
     *  police/fire), 100/101/102/108 (India police/fire/ambulance/medical),
     *  122 (Egypt). */
    private val STATIC_FALLBACK_EMERGENCY_NUMBERS = setOf(
        "911", "112", "999", "000", "110", "119", "100", "101", "102", "108", "122"
    )

    /** [number] may be in any user-typed form (spaces, dashes, a leading
     *  '+'); only digits and a leading '+' are meaningful to this check. */
    private fun normalize(number: String): String =
        number.filter { it.isDigit() || it == '+' }

    /** The static-list half of [isEmergencyNumber], pure and Context-free —
     *  extracted so it is directly unit-testable (this project has no
     *  Robolectric/mocking framework, so nothing touching a real
     *  [android.content.Context]/[TelephonyManager] can be exercised in a
     *  unit test — see EmergencyCallGuardTest). This is also exactly the
     *  path every API 26-28 device (below where
     *  [TelephonyManager.isEmergencyNumber] exists at all) actually runs in
     *  production, so testing it is testing real, live logic, not a stand-in. */
    fun isEmergencyNumberStatic(number: String): Boolean {
        val cleaned = normalize(number)
        return cleaned.isNotBlank() && cleaned in STATIC_FALLBACK_EMERGENCY_NUMBERS
    }

    /** True if [number] is an emergency number — checked against the
     *  platform's own authoritative list on API 29+, [isEmergencyNumberStatic]
     *  below that. Never throws: a [SecurityException] or any other
     *  failure from the platform API falls back to the static list rather
     *  than silently returning false (a false negative here is the one
     *  mistake this function must never make — a false POSITIVE merely
     *  routes an ordinary call through the system dialer instead of this
     *  app's own UI, mildly annoying; a false NEGATIVE could route a real
     *  emergency call into this app's own SIM/permission/airplane-mode gate
     *  logic instead of guaranteed platform handling). */
    fun isEmergencyNumber(context: Context, number: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cleaned = normalize(number)
            if (cleaned.isBlank()) return false
            val tm = ContextCompat.getSystemService(context, TelephonyManager::class.java)
            if (tm != null) {
                val platformAnswer = try {
                    tm.isEmergencyNumber(cleaned)
                } catch (e: Exception) {
                    null // fall through to the static list below
                }
                // The platform can say true when the static list would have
                // said false (country-specific numbers this list doesn't
                // know) — trust an authoritative TRUE outright. It should
                // never be trusted to say a definitive FALSE for something
                // the static list independently flags, so OR them below.
                if (platformAnswer == true) return true
            }
        }
        return isEmergencyNumberStatic(number)
    }

    /** PART 2.4: hands [number] to the SYSTEM's own call handling — never
     *  through this app's [com.opencall.relay.dialer.route.CallRoute]/UI.
     *  Two paths, tried in order:
     *   1. [Intent.ACTION_CALL] — if this app currently holds CALL_PHONE,
     *      this places the call immediately. This is Android's own
     *      documented exception to "ACTION_CALL should be avoided for
     *      permission reasons": emergency numbers are explicitly called out
     *      as safe/expected to use ACTION_CALL directly. Critically, this
     *      does NOT mean "this app makes the call" — ACTION_CALL is
     *      resolved by the platform's Telecom stack, and Android
     *      guarantees an emergency number is routed through the system's
     *      own emergency-call handling regardless of the default dialer or
     *      which app's Intent triggered it; a third-party app (default
     *      dialer or not) is architecturally unable to intercept or
     *      redirect an emergency call — Telecom recognizes the number
     *      itself and takes over.
     *   2. [Intent.ACTION_DIAL] — needs NO permission at all, always
     *      succeeds in opening a dialer pre-filled with the number. Used
     *      whenever CALL_PHONE isn't currently granted, so a missing
     *      permission can never be the reason an emergency dial attempt
     *      goes nowhere — the absolute worst case is the user has to tap
     *      the call button themselves on the screen this opens. */
    fun placeViaSystem(context: Context, number: String) {
        val uri = Uri.fromParts("tel", number, null)
        val hasCallPermission = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.CALL_PHONE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        val intent = Intent(if (hasCallPermission) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: SecurityException) {
            // Extremely defensive fallback: ACTION_CALL rejected at the last
            // moment (permission revoked between the check above and here) —
            // ACTION_DIAL needs no permission and must not also fail.
            val dialIntent = Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(dialIntent)
        }
    }
}
