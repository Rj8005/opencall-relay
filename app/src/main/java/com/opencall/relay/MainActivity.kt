package com.opencall.relay

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.flexbox.FlexboxLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.opencall.relay.databinding.ActivityMainBinding
import com.opencall.relay.offline.OfflineCallActivity
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val REQUIRED_PERMISSIONS = arrayOf(
        android.Manifest.permission.READ_PHONE_STATE,
        android.Manifest.permission.SEND_SMS,
        android.Manifest.permission.RECEIVE_SMS,
        android.Manifest.permission.CALL_PHONE,
        android.Manifest.permission.RECORD_AUDIO,
    )

    private val relayStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == RelayService.ACTION_STOPPED) {
                updateRelayStatus()
            }
        }
    }
    private var receiverRegistered = false

    private val relaySmsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != RelayService.ACTION_RELAY_SMS) return
            val callId       = intent.getStringExtra("callId")       ?: return
            val targetNumber = intent.getStringExtra("targetNumber") ?: return
            val joinURL      = intent.getStringExtra("joinURL")      ?: return
            handleRelaySms(callId, targetNumber, joinURL)
        }
    }
    private var smsReceiverRegistered = false

    private val COUNTRY_SCORES = mapOf(
        "IN"      to mapOf("whatsapp" to 95, "sms" to 80, "telegram" to 65,
                           "viber" to 30, "signal" to 20, "call" to 90),
        "US"      to mapOf("whatsapp" to 40, "sms" to 95, "telegram" to 25,
                           "viber" to 10, "signal" to 35, "call" to 85),
        "RU"      to mapOf("whatsapp" to 50, "sms" to 70, "telegram" to 95,
                           "viber" to 80, "signal" to 15, "call" to 85),
        "BR"      to mapOf("whatsapp" to 95, "sms" to 70, "telegram" to 40,
                           "viber" to 15, "signal" to 15, "call" to 85),
        "CN"      to mapOf("whatsapp" to 10, "sms" to 65, "telegram" to 5,
                           "viber" to 5,  "signal" to 5,  "call" to 90),
        "DEFAULT" to mapOf("whatsapp" to 70, "sms" to 80, "telegram" to 40,
                           "viber" to 25, "signal" to 20, "call" to 85)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // CAP PROBE: temporary read-only diagnostic — see CapabilityProbe.kt.
        CapabilityProbe.logStartupCapabilities(this)

        if (REQUIRED_PERMISSIONS.any {
                checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }) {
            requestPermissions(REQUIRED_PERMISSIONS, 1001)
        }
        setupStatusBar()
        setupRelayButton()
        setupModeButtons()
        addOfflineCallButton()

        binding.btnSetupComplete.setOnClickListener {
            val name   = binding.etSetupName.text.toString().trim()
            val number = binding.etSetupNumber.text.toString().trim()
            val server = binding.etSetupServer.text.toString().trim()

            if (name.isEmpty() || number.isEmpty()) {
                Toast.makeText(this, "Please enter your name and number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!number.startsWith("+")) {
                Toast.makeText(this, "Add country code: +91, +1, +44...", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val normalized = normalizeNumber(number)
            getSharedPreferences("opencall", MODE_PRIVATE).edit()
                .putString("user_name",   name)
                .putString("user_number", normalized)
                .putString("server_url",  server)
                .putBoolean("setup_complete", true)
                .apply()

            showScreen("dashboard")
            updateHeaderInfo(name, normalized)
            startRelayService(server, normalized)
        }

        binding.etSetupNumber.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val num = s.toString().trim()
                if (num.isNotEmpty() && !num.startsWith("+")) {
                    binding.etSetupNumber.error =
                        "Add country code: +91 India · +1 USA/Canada · +44 UK"
                } else {
                    binding.etSetupNumber.error = null
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        binding.tvChangeUser.setOnClickListener {
            getSharedPreferences("opencall", MODE_PRIVATE).edit()
                .putBoolean("setup_complete", false)
                .apply()
            stopRelayService()
            showScreen("setup")
        }

        initFlow()
    }

    // ── Two-screen flow ───────────────────────────────────────────────────────

    private fun showScreen(screen: String) {
        binding.screenSetup.visibility =
            if (screen == "setup") View.VISIBLE else View.GONE
        binding.screenDashboard.visibility =
            if (screen == "dashboard") View.VISIBLE else View.GONE
        if (screen == "dashboard") restoreRelayMode()
    }

    private fun initFlow() {
        val prefs   = getSharedPreferences("opencall", MODE_PRIVATE)
        val claimed = prefs.getBoolean("setup_complete", false)
        val name    = prefs.getString("user_name",   null)
        val number  = prefs.getString("user_number", null)
        if (claimed && !name.isNullOrBlank() && !number.isNullOrBlank()) {
            showScreen("dashboard")
            updateHeaderInfo(name, number)
            // Restore saved server URL to the dashboard field
            val savedServer = prefs.getString("server_url", RelayService.DEFAULT_SERVER) ?: RelayService.DEFAULT_SERVER
            binding.etServerUrl.setText(savedServer)
        } else {
            showScreen("setup")
        }
    }

    private fun updateHeaderInfo(name: String, number: String) {
        binding.tvHeaderUserInfo.text = "$name · $number"
    }

    // ── Relay service helpers ─────────────────────────────────────────────────

    fun normalizeNumber(num: String): String {
        var n = num.trim().replace(Regex("[\\s\\-\\(\\)]"), "")
        if (n.isNotEmpty() && !n.startsWith("+")) n = "+$n"
        return n
    }

    private fun startRelayService(serverUrl: String, e164: String) {
        val hasCall  = checkSelfPermission(Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        val hasAudio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

        if (!hasCall || !hasAudio) {
            Toast.makeText(
                this,
                "Grant Call and Microphone permissions first",
                Toast.LENGTH_LONG
            ).show()
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, 1001)
            return
        }

        val prefs  = getSharedPreferences("opencall", MODE_PRIVATE)
        val intent = Intent(this, RelayService::class.java).apply {
            action = RelayService.ACTION_START
            putExtra(RelayService.EXTRA_SERVER_URL, serverUrl)
            putExtra(RelayService.EXTRA_AREA_CODE,  prefs.getString("area_code", "+91"))
            putExtra(RelayService.EXTRA_COUNTRY,    detectCountry(e164))
            putExtra(RelayService.EXTRA_RELAY_MODE, prefs.getString("relay_mode", "both"))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(intent)
        else
            startService(intent)
    }

    private fun stopRelayService() {
        startService(Intent(this, RelayService::class.java).apply {
            action = RelayService.ACTION_STOP
        })
    }

    private fun startRelay() {
        val prefs = getSharedPreferences("opencall", MODE_PRIVATE)
        val number = prefs.getString("user_number", "") ?: ""

        if (number.isEmpty()) {
            Toast.makeText(this, "Complete setup first", Toast.LENGTH_SHORT).show()
            return
        }

        val hasSend = checkSelfPermission(Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED
        val hasReceive = checkSelfPermission(Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED

        if (!hasSend || !hasReceive) {
            Toast.makeText(this,
                "Grant Send SMS and Receive SMS permissions first",
                Toast.LENGTH_LONG).show()
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, 1001)
            return
        }

        val intent = Intent(this, RelayForegroundService::class.java).apply {
            action = RelayForegroundService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        binding.tvStatusPill?.text = "ACTIVE"
        binding.btnToggleRelay?.text = "Stop Relay"
        Toast.makeText(this, "Relay service started", Toast.LENGTH_SHORT).show()
    }

    private fun stopRelay() {
        val intent = Intent(this, RelayForegroundService::class.java).apply {
            action = RelayForegroundService.ACTION_STOP
        }
        startService(intent)
        binding.tvStatusPill?.text = "IDLE"
        binding.btnToggleRelay?.text = "Start Relay"
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        val denied = permissions.filterIndexed { i, _ ->
            grantResults[i] != android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (denied.isEmpty()) {
            Toast.makeText(
                this,
                "✅ All permissions granted — relay ready",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(
                this,
                "⚠️ Denied: ${denied.joinToString { it.substringAfterLast('.') }}" +
                "\nRelay may not work fully",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2001) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                android.provider.Settings.canDrawOverlays(this)) {
                startRelay() // retry after permission granted
            } else {
                Toast.makeText(this,
                    "Overlay permission needed for relay",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Status bar card → opens RelaySettingsFragment ─────────────────────────

    private fun setupStatusBar() {
        binding.relayStatusBar.setOnClickListener {
            RelaySettingsFragment().show(supportFragmentManager, "relay_settings")
        }
    }

    // ── Start/Stop relay button ───────────────────────────────────────────────

    private fun setupRelayButton() {
        binding.btnToggleRelay.setOnClickListener {
            if (RelayService.isRunning) {
                stopRelayService()
                Handler(Looper.getMainLooper()).postDelayed({ updateRelayStatus() }, 600)
            } else {
                startRelay()
            }
        }
    }

    // ── Offline Wi-Fi-Direct call launcher ───────────────────────────────────────
    // Added programmatically (no layout XML edit) — floats over whichever
    // screen (setup/dashboard) is currently visible in the root FrameLayout.

    private fun addOfflineCallButton() {
        val root = binding.root as? FrameLayout ?: return
        val density = resources.displayMetrics.density
        val margin = (16 * density).toInt()

        val button = Button(this).apply {
            text = "Offline Call"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, OfflineCallActivity::class.java))
            }
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(margin, margin, margin, margin)
        }
        root.addView(button, params)
    }

    // ── Relay mode toggles ────────────────────────────────────────────────────

    private fun setupModeButtons() {
        binding.btnModeBoth.setOnClickListener { saveRelayMode("both"); updateModeUI("both") }
        binding.btnModeCall.setOnClickListener { saveRelayMode("call"); updateModeUI("call") }
        binding.btnModeSms.setOnClickListener  { saveRelayMode("sms");  updateModeUI("sms")  }
    }

    private fun saveRelayMode(mode: String) {
        getSharedPreferences("opencall", MODE_PRIVATE).edit()
            .putString("relay_mode", mode).apply()
    }

    private fun restoreRelayMode() {
        val mode = getSharedPreferences("opencall", MODE_PRIVATE)
            .getString("relay_mode", "both") ?: "both"
        updateModeUI(mode)
    }

    private fun updateModeUI(mode: String) {
        val btnBoth = binding.btnModeBoth
        val btnCall = binding.btnModeCall
        val btnSms  = binding.btnModeSms

        listOf(btnBoth, btnCall, btnSms).forEach { btn ->
            btn.setBackgroundResource(R.drawable.mode_btn_normal)
            btn.setTextColor(getColor(R.color.text_muted))
        }

        val selected = when (mode) {
            "call" -> btnCall
            "sms"  -> btnSms
            else   -> btnBoth
        }
        selected.setBackgroundResource(R.drawable.mode_btn_selected)
        selected.setTextColor(getColor(R.color.accent_blue))
    }

    // ── Relay status indicator ────────────────────────────────────────────────

    private fun updateRelayStatus() {
        val running = RelayService.isRunning
        val accent  = Color.parseColor("#c8f55a")
        val grey    = Color.parseColor("#666666")
        binding.tvRelayDot.text = if (running) "●" else "○"
        binding.tvRelayDot.setTextColor(if (running) accent else grey)
        binding.tvRelayStatus.text = if (running) " Relay ON" else " Relay OFF"
        binding.tvRelayStatus.setTextColor(if (running) accent else grey)
        binding.tvStatusPill.text = if (running) "ACTIVE" else "STOPPED"
        binding.tvStatusPill.setTextColor(if (running) accent else grey)
        binding.btnToggleRelay.text = if (running) "Stop Relay" else "Start Relay"
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        val running = RelayForegroundService.instance != null
        if (running) {
            binding.tvStatusPill?.text = "ACTIVE"
            binding.btnToggleRelay vv            b?.text = "Stop Relay"
        } else {
            binding.tvStatusPill?.text = "IDLE"
            binding.btnToggleRelay?.text = "Start Relay"
        }
    }

    override fun onPause() {
        super.onPause()
        if (receiverRegistered) {
            unregisterReceiver(relayStoppedReceiver)
            receiverRegistered = false
        }
        if (smsReceiverRegistered) {
            unregisterReceiver(relaySmsReceiver)
            smsReceiverRegistered = false
        }
    }

    // ── Default dialer prompt ─────────────────────────────────────────────────

    private fun checkDefaultDialer() {
        val prefs = getSharedPreferences("opencall", Context.MODE_PRIVATE)
        if (prefs.getBoolean("default_dialer_prompted", false)) return

        val tm = getSystemService(TelecomManager::class.java) ?: return
        if (tm.defaultDialerPackage == packageName) return

        prefs.edit().putBoolean("default_dialer_prompted", true).apply()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Enable full privacy protection")
            .setMessage(
                "Set OpenCall as your default dialer so relay calls show " +
                "the OCP number — not your real number."
            )
            .setPositiveButton("Set as default") { _, _ ->
                try {
                    startActivity(
                        Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                            putExtra(
                                TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME,
                                packageName
                            )
                        }
                    )
                } catch (_: Exception) {
                    Toast.makeText(this, "Could not open dialer settings", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    // ── SMS relay ─────────────────────────────────────────────────────────────

    private fun handleRelaySms(callId: String, targetNumber: String, joinURL: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.SEND_SMS), 102
            )
            Toast.makeText(
                this, "SMS permission required to relay messages", Toast.LENGTH_LONG
            ).show()
            return
        }

        val smsText = "Hi! You have a free call waiting on OpenCall.\n" +
                      "Tap to answer: $joinURL\n" +
                      "(No app needed - works in any browser)"

        try {
            @Suppress("DEPRECATION")
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                getSystemService(SmsManager::class.java)
            else
                SmsManager.getDefault()

            val parts = smsManager.divideMessage(smsText)
            smsManager.sendMultipartTextMessage(targetNumber, null, parts, null, null)
            notifySmsSent(callId, "ok")
        } catch (e: Exception) {
            Toast.makeText(this, "SMS send failed: ${e.message}", Toast.LENGTH_SHORT).show()
            notifySmsSent(callId, "error")
        }
    }

    private fun notifySmsSent(callId: String, status: String) {
        startService(Intent(this, RelayService::class.java).apply {
            action = RelayService.ACTION_SMS_SENT
            putExtra("callId", callId)
            putExtra("status", status)
        })
    }

    // ── Number validation ─────────────────────────────────────────────────────

    fun validateNumber(number: String): Boolean {
        return when {
            number.isBlank() -> {
                showDialError("Enter a number to call")
                false
            }
            !number.startsWith("+") -> {
                showDialError("Add country code: +91, +1, +44...")
                false
            }
            number.length < 8 -> {
                showDialError("Number too short")
                false
            }
            else -> true
        }
    }

    fun showDialError(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ── Channel engine ────────────────────────────────────────────────────────

    fun detectCountry(e164: String): String {
        val n = e164.removePrefix("+")
        val prefixMap = mapOf(
            "91"  to "IN", "1"   to "US", "44"  to "GB", "7"   to "RU",
            "98"  to "IR", "86"  to "CN", "81"  to "JP", "82"  to "KR",
            "55"  to "BR", "49"  to "DE", "234" to "NG", "92"  to "PK",
            "62"  to "ID", "63"  to "PH", "84"  to "VN", "380" to "UA",
            "90"  to "TR", "52"  to "MX", "27"  to "ZA", "66"  to "TH"
        )
        for (len in listOf(3, 2, 1)) {
            val prefix = n.take(len)
            prefixMap[prefix]?.let { return it }
        }
        return "DEFAULT"
    }

    fun getRankedChannels(e164: String): List<Pair<String, Int>> {
        val country = detectCountry(e164)
        val scores  = COUNTRY_SCORES[country] ?: COUNTRY_SCORES["DEFAULT"]!!
        return scores.entries
            .sortedByDescending { it.value }
            .map { Pair(it.key, it.value) }
    }

    fun buildDeepLinkIntent(channel: String, e164: String, inviteURL: String): Intent? {
        val num = e164.removePrefix("+")
        val msg = Uri.encode("Hey! Call me free on OpenCall — tap: $inviteURL")
        return when (channel) {
            "whatsapp" -> Intent(Intent.ACTION_VIEW,
                Uri.parse("https://wa.me/$num?text=$msg"))
            "telegram" -> Intent(Intent.ACTION_VIEW,
                Uri.parse("https://t.me/+$e164"))
            "sms"      -> Intent(Intent.ACTION_SENDTO,
                Uri.parse("sms:$e164")).apply {
                    putExtra("sms_body", "Call me free on OpenCall: $inviteURL")
                }
            "viber"    -> Intent(Intent.ACTION_VIEW,
                Uri.parse("viber://chat?number=%2B$num"))
            "signal"   -> Intent(Intent.ACTION_VIEW,
                Uri.parse("https://signal.me/#p/$e164"))
            "email"    -> Intent(Intent.ACTION_SENDTO,
                Uri.parse("mailto:")).apply {
                    putExtra(Intent.EXTRA_SUBJECT, "Join me on OpenCall")
                    putExtra(Intent.EXTRA_TEXT, "Call me free: $inviteURL")
                }
            "call"     -> Intent(Intent.ACTION_DIAL,
                Uri.parse("tel:$e164"))
            else       -> null
        }
    }

    fun showInvitePanel(e164: String, inviteURL: String) {
        val ranked  = getRankedChannels(e164)
        val country = detectCountry(e164)

        val sheet = BottomSheetDialog(this)
        val view  = layoutInflater.inflate(R.layout.invite_panel, null)

        view.findViewById<TextView>(R.id.invite_number).text =
            "$e164 is not on OpenCall yet"
        view.findViewById<TextView>(R.id.invite_country).text =
            "Best options for $country:"

        view.findViewById<EditText>(R.id.invite_link).setText(inviteURL)
        view.findViewById<Button>(R.id.copy_btn).setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("invite", inviteURL))
            Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
        }

        val grid = view.findViewById<FlexboxLayout>(R.id.channel_grid)
        ranked.forEach { (channel, score) ->
            val btn = layoutInflater.inflate(R.layout.channel_button, grid, false)
            btn.findViewById<TextView>(R.id.ch_label).text =
                channel.replaceFirstChar { it.uppercase() }
            btn.findViewById<TextView>(R.id.ch_score).text = "$score%"
            btn.setOnClickListener {
                val intent = buildDeepLinkIntent(channel, e164, inviteURL)
                if (intent != null) {
                    try {
                        startActivity(intent)
                        showWaitingState(e164, channel, sheet)
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(
                            this,
                            "${channel.replaceFirstChar { it.uppercase() }} not installed",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            grid.addView(btn)
        }

        view.findViewById<Button>(R.id.share_btn).setOnClickListener {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, inviteURL)
            }
            startActivity(Intent.createChooser(shareIntent, "Send invite via"))
        }

        view.findViewById<Button>(R.id.textbelt_btn).setOnClickListener {
            sendViaTextBelt(e164, inviteURL,
                view.findViewById(R.id.textbelt_status))
        }

        sheet.setContentView(view)
        sheet.show()
    }

    fun showWaitingState(e164: String, channel: String, sheet: BottomSheetDialog) {
        val handler      = Handler(Looper.getMainLooper())
        val pollRunnable = object : Runnable {
            override fun run() {
                checkDHTForNumber(e164) { found ->
                    if (found) {
                        sheet.dismiss()
                        Toast.makeText(
                            this@MainActivity,
                            "They joined! Connecting...",
                            Toast.LENGTH_SHORT
                        ).show()
                        initiateOCPCall(e164)
                    } else {
                        handler.postDelayed(this, 5000)
                    }
                }
            }
        }
        handler.postDelayed(pollRunnable, 5000)
        handler.postDelayed({ handler.removeCallbacks(pollRunnable) }, 600_000)
    }

    fun sendViaTextBelt(e164: String, inviteURL: String, statusView: TextView) {
        statusView.text = "Sending SMS..."
        Thread {
            try {
                val url  = URL("https://node.opencall.space/reach/textbelt")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                val body = """{"to":"$e164","inviteURL":"$inviteURL"}"""
                conn.outputStream.write(body.toByteArray())
                val response = conn.inputStream.bufferedReader().readText()
                runOnUiThread {
                    statusView.text = if (response.contains("\"success\":true"))
                        "✓ SMS sent" else "✗ Failed — use buttons above"
                }
            } catch (e: Exception) {
                runOnUiThread { statusView.text = "✗ Network error" }
            }
        }.start()
    }

    private fun checkDHTForNumber(e164: String, callback: (Boolean) -> Unit) {
        // TODO: implement DHT lookup via /dht/lookup?number=e164
        callback(false)
    }

    private fun initiateOCPCall(e164: String) {
        // TODO: initiate OCP WebRTC call to e164
    }
}
