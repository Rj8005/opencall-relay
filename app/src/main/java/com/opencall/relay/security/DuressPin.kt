package com.opencall.relay.security

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.opencall.relay.MainActivity
import java.io.File

/**
 * B2 (diagnostic follow-up): the duress/panic trigger's TRIGGER logic —
 * what happens once it fires, not how/where the user fires it (that's a
 * UX decision, not this file's concern; see the diagnostic report for the
 * open question on PIN entry gesture/location and recovery path).
 *
 * SCOPE, as decided: (a) hide the launcher icon, (c) wipe MeshLedger's
 * persisted position/trust data. Explicitly OUT of scope: touching
 * AndroidKeyStore/OfflineIdentity — this device's own signed identity
 * survives a duress trigger untouched, so a peer who verified this device
 * before still recognizes it after (only the OTHER side's cached position
 * history and this device's cache of ITS peers' verified pubkeys are wiped
 * — see [wipeLedgerData]'s doc for why that second part is an unavoidable
 * side effect, not a separate decision).
 *
 * Chat wipe (scope item (b), in-memory chatMessages) is NOT handled here —
 * that list is private to a live OfflineCallActivity instance (never
 * persisted, so it's already gone on the next process restart regardless).
 * See OfflineCallActivity.wipeChatForDuress() for that half, to be called
 * by the trigger UI directly on the activity if one is currently alive.
 */
object DuressPin {

    /** Disables MainActivity's own launcher component — the app disappears
     *  from the home screen/app drawer immediately, without touching any
     *  app data or killing this process (DONT_KILL_APP). This is a
     *  ONE-WAY action from the launcher's perspective: once disabled,
     *  tapping "the app" from Settings > Apps (if the user even finds it
     *  there — a disabled app is hidden from most normal listings too)
     *  is the only OS-level way back in until [restoreAppIcon] runs from
     *  SOME still-reachable code path. That recovery path does not exist
     *  yet — deciding it is part of the same open UX question as the PIN
     *  entry gesture itself; do not wire a caller to this function until
     *  that's resolved, or this can strand a user out of their own
     *  emergency-comms app with no way back in. */
    fun hideAppIcon(context: Context) {
        val pm = context.applicationContext.packageManager
        val component = ComponentName(context.applicationContext, MainActivity::class.java)
        pm.setComponentEnabledSetting(
            component,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
        Log.w("OFFTRACE", "DURESS: launcher icon hidden")
    }

    /** Reverses [hideAppIcon] — restores MainActivity to the launcher.
     *  Whatever recovery UI eventually calls this needs its own reachable
     *  entry point independent of the (now-hidden) launcher icon; that
     *  entry point is not designed yet (same open question). */
    fun restoreAppIcon(context: Context) {
        val pm = context.applicationContext.packageManager
        val component = ComponentName(context.applicationContext, MainActivity::class.java)
        pm.setComponentEnabledSetting(
            component,
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.DONT_KILL_APP
        )
        Log.w("OFFTRACE", "DURESS: launcher icon restored")
    }

    /** Deletes every file under filesDir/ledger/ — MeshLedger's persisted
     *  per-node position/lost-contact tracks AND MeshSigner's pubkeys.json
     *  (same directory, see MeshLedger.isLedgerTrackFileName's doc for why
     *  they share it). That second part is a real, unavoidable side
     *  effect worth knowing about: every peer this device has ever
     *  cryptographically VERIFIED (e.g. via a QR scan) goes back to
     *  UNVERIFIED — re-pairing (re-scanning a QR, or a fresh HELLO
     *  exchange) is required afterward. This does NOT touch this
     *  device's OWN identity (offline_mesh_identity.dat / the
     *  AndroidKeyStore wrap key, both in OfflineIdentity, a completely
     *  separate file/keystore alias) — only cached knowledge ABOUT
     *  OTHER devices. Never throws — a missing/already-empty directory
     *  is already the desired end state, same posture as
     *  OfflineIdentity.resetIdentity. */
    fun wipeLedgerData(context: Context) {
        val ledgerDir = File(context.applicationContext.filesDir, "ledger")
        val files = ledgerDir.listFiles() ?: run {
            Log.w("OFFTRACE", "DURESS: ledger wipe — nothing to delete")
            return
        }
        var deleted = 0
        files.forEach { if (it.delete()) deleted++ }
        Log.w("OFFTRACE", "DURESS: ledger wiped n=$deleted")
    }

    /** Runs every in-scope wipe/hide action. Does NOT clear in-memory
     *  chat — see this object's class doc; the caller is responsible for
     *  also calling OfflineCallActivity.wipeChatForDuress() if a live
     *  instance exists. */
    fun trigger(context: Context) {
        wipeLedgerData(context)
        hideAppIcon(context)
    }
}
