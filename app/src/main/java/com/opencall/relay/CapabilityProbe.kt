package com.opencall.relay

import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pGroup
import android.os.Build
import android.util.Log

/**
 * CAP PROBE — temporary, read-only diagnostic. Logs device/Wi-Fi capability facts
 * under the OFFTRACE tag ("CAP:" prefix) so they can be pulled from logcat; changes
 * no existing behavior. Safe to delete once the capability report has been
 * collected — nothing else in the app reads from this file.
 */
object CapabilityProbe {
    private const val TAG = "OFFTRACE"

    /** Call once at app start (see MainActivity.onCreate). */
    fun logStartupCapabilities(context: Context) {
        Log.d(TAG, "CAP: ---- startup capability probe ----")

        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiManager == null) {
            Log.d(TAG, "CAP: WifiManager unavailable")
        } else {
            // isStaApConcurrencySupported/isP2pSupported/is6GHzBandSupported/isTdlsSupported
            // are all API 30+ (R); minSdk here is 26, so guard each.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Log.d(TAG, "CAP: staApConcurrency=${wifiManager.isStaApConcurrencySupported}")
                Log.d(TAG, "CAP: isP2pSupported=${wifiManager.isP2pSupported}")
                Log.d(TAG, "CAP: is6GHzBandSupported=${wifiManager.is6GHzBandSupported}")
                Log.d(TAG, "CAP: isTdlsSupported=${wifiManager.isTdlsSupported}")
            } else {
                Log.d(TAG, "CAP: staApConcurrency=unsupported (API ${Build.VERSION.SDK_INT} < 30)")
                Log.d(TAG, "CAP: isP2pSupported=unsupported (API ${Build.VERSION.SDK_INT} < 30)")
                Log.d(TAG, "CAP: is6GHzBandSupported=unsupported (API ${Build.VERSION.SDK_INT} < 30)")
                Log.d(TAG, "CAP: isTdlsSupported=unsupported (API ${Build.VERSION.SDK_INT} < 30)")
            }
            // is5GHzBandSupported has existed since API 21, well below minSdk 26.
            Log.d(TAG, "CAP: is5GHzBandSupported=${wifiManager.is5GHzBandSupported}")
        }

        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL
        } else {
            "unsupported (API ${Build.VERSION.SDK_INT} < 31)"
        }
        Log.d(
            TAG,
            "CAP: manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} " +
                "hardware=${Build.HARDWARE} socModel=$socModel sdkInt=${Build.VERSION.SDK_INT}"
        )
        Log.d(TAG, "CAP: ---- end startup capability probe ----")
    }

    /** Call once right after a WiFi Direct group forms as GO — passed as the result
     *  callback of the existing WifiDirectManager.requestGroupInfo() call (see
     *  OfflineCallActivity.onConnectionChangedInternal's FIX 6d call site). */
    fun logGroupFormedCapabilities(group: WifiP2pGroup?) {
        if (group == null) {
            Log.d(TAG, "CAP: group=null (requestGroupInfo returned nothing)")
            return
        }
        val hasPassphrase = !group.passphrase.isNullOrEmpty()
        Log.d(
            TAG,
            "CAP: group ssid=${group.networkName} hasPassphrase=$hasPassphrase " +
                "band=${group.frequency} clients=${group.clientList.size}"
        )
    }
}
