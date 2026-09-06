package com.opencall.relay.dialer.data

import android.content.Context
import org.json.JSONObject

/** One local-knowledge match — same shape the old server-backed client
 *  returned, so [ContactsScreen]/[PhoneTabController] didn't need to
 *  change at all. */
data class OcpAccountRef(val e164Number: String, val ocpNodeIdHex: String?)

/**
 * PART 5.1: replaces the previous pass's `OcpDirectoryClient`, which POSTed
 * this device's contact numbers to `node.opencall.space/directory/lookup`
 * for matching — REMOVED outright (see this task's own report for exactly
 * what that file did and why). Nothing in this app now sends a phone
 * number, hashed or otherwise, anywhere. A contact is badged "OCP" only
 * from knowledge already on this device: a prior mesh connection, a
 * scanned QR code, or an inbound call — recorded locally, via
 * [recordContact], never looked up remotely.
 *
 * HONEST STATE OF THIS PASS: this store and its read path
 * ([lookupLocal]) are real and wired into the contacts screen exactly like
 * the removed network client was — but nothing in the app calls
 * [recordContact] yet. The three sourcing events the brief names each need
 * their own wiring, with different amounts of real work behind them:
 *   - "a scanned QR" is the closest to ready — [com.opencall.relay.offline.
 *     OfflineCallActivity]'s own QR invite payload (`QrInvitePayload`) does
 *     not carry a phone number field today; adding an OPTIONAL one (only
 *     present if the inviter chooses to share it) is a small, additive
 *     change to that payload/encode/decode trio and nothing else in the
 *     offline package. Not done in this pass — flagged, not silently
 *     built, since it also means designing the "share my number?" UI
 *     moment, which is a product decision, not just a wiring one.
 *   - "a prior mesh connection" would need the mesh HELLO/roster protocol
 *     itself to carry a phone number, which it does not — [OfflineIdentity]
 *     is nodeId+display-name only, deliberately. Extending that wire
 *     format touches MeshSigner/OfflineMediaTransport, which prior
 *     instructions on this app say not to modify — not attempted here.
 *   - "an inbound call": Tab 1 (opencall.space, an embedded WebView with no
 *     structured call events reaching native code — see Part 1) has no
 *     hook to expose a caller's number to this store at all today; Tab 2's
 *     SIM call log already has real numbers but no notion of "this number
 *     is an OCP account" to attach.
 * Until one of these is wired, this directory is honestly empty and every
 * contact shows SIM-only routing — the same "never fabricate" rule this
 * app applies to every not-yet-implemented data source.
 */
object LocalOcpDirectory {

    private const val PREFS_NAME = "opencall_local_ocp_directory"
    private const val KEY_ENTRIES = "entries" // JSON object: normalized E.164 -> nodeIdHex

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Records that [e164Number] belongs to the OCP account identified by
     *  [nodeIdHex] — call this from wherever this device actually learns
     *  that pairing locally (see this object's own doc for the three
     *  sourcing events, none wired up yet). Never a network call. */
    fun recordContact(context: Context, e164Number: String, nodeIdHex: String) {
        val normalized = normalize(e164Number)
        val json = readAll(context)
        json.put(normalized, nodeIdHex)
        prefs(context).edit().putString(KEY_ENTRIES, json.toString()).apply()
    }

    fun forgetContact(context: Context, e164Number: String) {
        val json = readAll(context)
        json.remove(normalize(e164Number))
        prefs(context).edit().putString(KEY_ENTRIES, json.toString()).apply()
    }

    /** Purely local read — replaces the removed client's network call with
     *  the same "degrade honestly, never invent" contract: numbers with no
     *  local record simply don't appear in the result (SIM-only). */
    fun lookupLocal(context: Context, e164Numbers: List<String>): Map<String, OcpAccountRef> {
        if (e164Numbers.isEmpty()) return emptyMap()
        val json = readAll(context)
        val result = LinkedHashMap<String, OcpAccountRef>()
        e164Numbers.forEach { number ->
            val normalized = normalize(number)
            val nodeId = json.optString(normalized, null.toString()).takeIf { json.has(normalized) }
            if (nodeId != null) result[normalized] = OcpAccountRef(normalized, nodeId)
        }
        return result
    }

    private fun readAll(context: Context): JSONObject {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return JSONObject()
        return try { JSONObject(raw) } catch (e: Exception) { JSONObject() }
    }

    private fun normalize(number: String): String {
        var n = number.trim().replace(Regex("[\\s\\-()]"), "")
        if (n.isNotEmpty() && !n.startsWith("+")) n = "+$n"
        return n
    }
}
