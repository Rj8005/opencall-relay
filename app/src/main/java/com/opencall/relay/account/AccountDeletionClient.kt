package com.opencall.relay.account

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * PART 5.4: account deletion. **This endpoint does not exist on the server
 * yet** — implemented against the contract it will need, same "degrade
 * honestly" rule as every other not-yet-built endpoint in this app.
 *
 * REQUIRED SERVER CONTRACT (not yet implemented):
 *   POST https://node.opencall.space/account/delete
 *   Request:  {"nodeId": "<hex nodeId>", "sipUsername": "<username or omitted>"}
 *   Response: 200 {"deleted": true}  — the account, its SIP registration,
 *             and any OCP-directory record for it are gone server-side.
 *   Response: 404 — no server-side account existed for this nodeId (still
 *             treated as success by [requestDeletion] — nothing to clean up
 *             server-side means the device-local wipe alone is correct).
 * This client does NOT block the local wipe on the server call succeeding
 * — see [SettingsActivity]'s own deletion flow: the local identity/account
 * data is authoritative for "can this device still act as that account,"
 * and is wiped regardless of whether the server call succeeds, so a
 * offline/unreachable server can never leave the user unable to delete
 * their local data.
 */
object AccountDeletionClient {

    private const val ENDPOINT = "https://node.opencall.space/account/delete"
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /** Synchronous — callers run this off the main thread. Best-effort:
     *  returns whether the server confirmed deletion, but callers should
     *  proceed with the local wipe regardless (see class doc). */
    fun requestDeletion(nodeIdHex: String, sipUsername: String?): Boolean {
        return try {
            val body = JSONObject().apply {
                put("nodeId", nodeIdHex)
                if (sipUsername != null) put("sipUsername", sipUsername)
            }.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(ENDPOINT).post(body).build()
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> true
                    response.code == 404 -> true // nothing server-side to delete — not a failure
                    else -> {
                        Log.w("AccountDeletion", "requestDeletion: http ${response.code}")
                        false
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("AccountDeletion", "requestDeletion: ${e.javaClass.simpleName}:${e.message}")
            false
        }
    }
}
