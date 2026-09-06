package com.opencall.relay.dialer.role

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * PART 1.3: every runtime permission this pillar needs, requested IN
 * CONTEXT — at the moment a screen actually needs it — never in a batch on
 * app launch. Call sites:
 *   - [Manifest.permission.CALL_PHONE] — the keypad's/a row's call button,
 *     right before [com.opencall.relay.dialer.route.SimCallRoute.place].
 *   - [Manifest.permission.READ_PHONE_STATE] — before
 *     [com.opencall.relay.dialer.identity.DialerIdentity.readOwnMsisdn], and
 *     before querying [android.telecom.TelecomManager] call-capable
 *     accounts for multi-SIM.
 *   - [Manifest.permission.READ_CALL_LOG] / [Manifest.permission.WRITE_CALL_LOG]
 *     — opening the call-log tab (read), and nowhere writes today (this
 *     pillar never inserts its own CallLog rows — the platform does that
 *     for calls placed via TelecomManager; WRITE_CALL_LOG is declared only
 *     because deleting an entry, Part 3.2's "delete single or all," also
 *     requires it).
 *   - [Manifest.permission.READ_CONTACTS] — opening the contacts tab, or
 *     the keypad's T9 search needing a name to match against.
 *   - [Manifest.permission.ANSWER_PHONE_CALLS] — right before answering an
 *     incoming call from this app's own UI (Part 2.3); without it,
 *     `Call.answer()` throws.
 *   - [Manifest.permission.MANAGE_OWN_CALLS] — a normal (not "dangerous")
 *     install-time permission; declared in the manifest, never
 *     runtime-requested, listed here only for completeness of "what this
 *     pillar needs."
 *
 * PLAY POLICY — READ_CALL_LOG and READ_PHONE_STATE specifically:
 * Google Play's Permissions declaration form restricts both to apps
 * fulfilling a "core app functionality" use case; the accepted one here is
 * the **default dialer / calling app** case, which additionally requires:
 *   - The app must actually implement [android.telecom.InCallService] and
 *     be capable of being set as the default Phone app (this pillar's Part
 *     1/2) — Play checks this by inspecting the manifest, not just the
 *     declared permissions.
 *   - The Play Console's Permissions Declaration Form for this app must
 *     select "Default Phone/Dialer handling" as the approved use, with a
 *     short written justification (e.g. "OpenCall is a default dialer
 *     replacement app; READ_PHONE_STATE identifies the active SIM/telephony
 *     state for call handling, READ_CALL_LOG powers the app's own call
 *     history screen").
 *   - A demonstration video/screenshots showing the role-request flow
 *     (Part 1.1's explainer screen) may be required on submission or during
 *     a policy review.
 *   - Play periodically re-verifies the app is still installed as, or
 *     capable of being set as, the default dialer by real users — an app
 *     that never actually gets set as default by a meaningful fraction of
 *     installs risks the declaration being rejected on renewal.
 */
object DialerPermissions {

    const val CALL_PHONE = Manifest.permission.CALL_PHONE
    const val READ_PHONE_STATE = Manifest.permission.READ_PHONE_STATE
    const val READ_CALL_LOG = Manifest.permission.READ_CALL_LOG
    const val WRITE_CALL_LOG = Manifest.permission.WRITE_CALL_LOG
    const val READ_CONTACTS = Manifest.permission.READ_CONTACTS
    const val ANSWER_PHONE_CALLS = Manifest.permission.ANSWER_PHONE_CALLS
    // MANAGE_OWN_CALLS is a normal permission (auto-granted at install,
    // manifest-declared only) — included here for completeness, never
    // passed to a runtime request call.
    const val MANAGE_OWN_CALLS = Manifest.permission.MANAGE_OWN_CALLS

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
