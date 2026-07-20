package com.opencall.relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("opencall", Context.MODE_PRIVATE)
        // Only auto-start if the user had previously configured and started the relay
        val url = prefs.getString("server_url", null) ?: return
        if (url.isBlank()) return
        val i = Intent(context, RelayService::class.java).apply {
            action = RelayService.ACTION_START
            putExtra(RelayService.EXTRA_SERVER_URL, url)
            putExtra(RelayService.EXTRA_AREA_CODE,  prefs.getString("area_code", "+91"))
            putExtra(RelayService.EXTRA_COUNTRY,    prefs.getString("country",   "IN"))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            context.startForegroundService(i)
        else
            context.startService(i)
    }
}
