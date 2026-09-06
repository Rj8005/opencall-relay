package com.opencall.relay.dialer.role

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat

/** Whether THIS app currently holds the default-dialer role — the only
 *  thing [DialerRoleManager.currentState] answers. Who else might hold it
 *  is a separate, best-effort query ([DialerRoleManager.currentHolderPackage]),
 *  not part of this enum, because the platform does not always let a
 *  non-holder resolve the current holder's identity beyond its package
 *  name. */
enum class DialerRoleState { DEFAULT, NOT_DEFAULT }

/**
 * PART 1.1: the role-request state machine. Pure and Android-framework-free
 * — takes observed [DialerRoleState] values in, emits [Event]s out — so it
 * is directly unit-testable without a real RoleManager/TelecomManager (see
 * DialerRoleManagerTest). [DialerRoleManager] (the object below) is the
 * thin Android-facing wrapper that actually queries the platform and feeds
 * this machine.
 */
class DialerRoleStateMachine {

    sealed class Event {
        /** A request this machine was told about (via [onRequestLaunched])
         *  came back with the role held. */
        object Granted : Event()
        /** A request came back WITHOUT the role held — covers both "user
         *  tapped deny" and "role is held by someone else and the system
         *  prompt didn't change that." */
        object Denied : Event()
        /** No request was in flight, but the role went from held to not
         *  held since the last observation — Part 1.1's "role revoked while
         *  running" case (e.g. the user changed the default dialer in
         *  system Settings while this app was in the background). */
        object Revoked : Event()
        /** Nothing changed since the last observation. */
        object NoChange : Event()
    }

    @Volatile private var lastKnown: DialerRoleState = DialerRoleState.NOT_DEFAULT
    @Volatile private var requestInFlight: Boolean = false

    /** Call right before launching the system role-request intent, so the
     *  NEXT [onObserved] call is interpreted as that request's outcome
     *  rather than a background revoke. */
    fun onRequestLaunched() {
        requestInFlight = true
    }

    /** Call with a freshly-queried [DialerRoleState] — on `onResume()`, and
     *  right after the role-request Activity result comes back. Returns
     *  the event this transition represents; also updates internal state
     *  for the next call. */
    fun onObserved(newState: DialerRoleState): Event {
        val previous = lastKnown
        lastKnown = newState
        val wasRequestInFlight = requestInFlight
        requestInFlight = false
        return when {
            wasRequestInFlight && newState == DialerRoleState.DEFAULT -> Event.Granted
            wasRequestInFlight && newState == DialerRoleState.NOT_DEFAULT -> Event.Denied
            !wasRequestInFlight &&
                previous == DialerRoleState.DEFAULT &&
                newState == DialerRoleState.NOT_DEFAULT -> Event.Revoked
            else -> Event.NoChange
        }
    }

    fun currentlyDefault(): Boolean = lastKnown == DialerRoleState.DEFAULT
}

/**
 * PART 1.1: the Android-facing half — queries the platform for the real
 * state and builds the request Intent. Two code paths, chosen by API level:
 * [RoleManager] (API 29+, the platform-recommended mechanism) and the
 * legacy [TelecomManager.ACTION_CHANGE_DEFAULT_DIALER] intent (API 26-28,
 * this app's minSdk floor — [RoleManager]/`ROLE_DIALER` do not exist below
 * 29).
 */
object DialerRoleManager {

    /** True iff THIS app currently holds the default-dialer role/slot. */
    fun isDefaultDialer(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = ContextCompat.getSystemService(context, RoleManager::class.java)
            roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) == true
        } else {
            currentHolderPackage(context) == context.packageName
        }
    }

    fun currentState(context: Context): DialerRoleState =
        if (isDefaultDialer(context)) DialerRoleState.DEFAULT else DialerRoleState.NOT_DEFAULT

    /** Best-effort package name of whoever currently holds the role —
     *  [TelecomManager.getDefaultDialerPackage] has existed since API 23
     *  and works identically whether or not [RoleManager] is also in play
     *  on this API level, so it's used for this query on every supported
     *  version rather than branching. Null means "no default dialer set at
     *  all" (rare — some emulator images, or right after a factory reset
     *  before any app has ever been chosen). */
    fun currentHolderPackage(context: Context): String? {
        val telecomManager = ContextCompat.getSystemService(context, TelecomManager::class.java)
        return try {
            telecomManager?.defaultDialerPackage
        } catch (e: SecurityException) {
            null
        }
    }

    /** True iff [isDefaultDialer] is false AND [currentHolderPackage] is
     *  neither null nor this app — i.e. specifically "held by ANOTHER app,"
     *  distinct from "held by nobody." */
    fun isHeldByAnotherApp(context: Context): Boolean {
        val holder = currentHolderPackage(context) ?: return false
        return holder != context.packageName
    }

    /** The Intent to launch for the system role/default-dialer prompt.
     *  Caller must have already shown Part 1.1's explanation screen —
     *  this function does no explaining itself, it only builds the request. */
    fun buildRequestIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = ContextCompat.getSystemService(context, RoleManager::class.java)
            roleManager!!.createRequestRoleIntent(RoleManager.ROLE_DIALER)
        } else {
            Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, context.packageName)
        }
    }
}
