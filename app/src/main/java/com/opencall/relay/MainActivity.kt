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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.flexbox.FlexboxLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.opencall.relay.account.AccountStore
import com.opencall.relay.databinding.ActivityMainBinding
import com.opencall.relay.dialer.ui.PhoneTabController
import com.opencall.relay.international.InternationalCallScreen
import com.opencall.relay.offline.OfflineCallActivity
import com.opencall.relay.settings.SettingsActivity
import com.opencall.relay.shell.AppShell
import com.opencall.relay.shell.AppTab
import java.net.HttpURLConnection
import java.net.URL

/**
 * PART 1: the app's shell — hosts Tab 1 (International) and Tab 2 (Phone)
 * as sibling containers inside `screen_dashboard` (same visibility-toggle
 * pattern this Activity already used for `screen_setup`/`screen_dashboard`
 * itself); Tab 3 (Offline) is a hop to [OfflineCallActivity], which wears
 * the same shared chrome ([AppShell]) — see this task's report for why
 * that's a separate Activity rather than a third container here (a 6955-
 * line, already-stable screen the brief explicitly asked not to refactor).
 * `android:launchMode="singleTask"` (manifest) + [Intent.
 * FLAG_ACTIVITY_REORDER_TO_FRONT] on every cross-Activity tab switch, never
 * `finish()`, is what keeps both this Activity's and OfflineCallActivity's
 * state alive across pillar switching — see [onTabBarSelected].
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** Set by [OfflineCallActivity]'s global tab bar / [com.opencall.relay.
         *  dialer.ui.DialerHostActivity]'s redirect — which [AppTab] to show. */
        const val EXTRA_SELECT_TAB = "select_tab"
        /** Set by DialerHostActivity's redirect (ACTION_DIAL/tel:) — pre-fills
         *  the Phone tab's keypad. */
        const val EXTRA_PREFILL_NUMBER = "prefill_number"
        /** Set by SettingsActivity's "Change account" row — see the header
         *  comment near the old `tvChangeUser` click listener below. */
        const val EXTRA_RESET_SETUP = "reset_setup"
        private const val PREF_LAST_TAB = "last_tab"
    }

    private lateinit var binding: ActivityMainBinding
    private var currentTab: AppTab = AppTab.INTERNATIONAL
    private lateinit var internationalScreen: InternationalCallScreen
    private lateinit var phoneTabController: PhoneTabController

    // PART 0: every permission-result path in this app — this class's own
    // legacy onRequestPermissionsResult below included — checks the
    // Activity is alive AND phoneTabController is actually constructed
    // before touching anything. A permission dialog can be answered well
    // after the user has backgrounded/left the screen; the result callback
    // still fires, on whatever Activity instance issued the request.
    private val phoneCallPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> ifAliveAndReady { phoneTabController.handleCallPermissionResult(granted) } }
    private val phoneCallLogPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> ifAliveAndReady { phoneTabController.handleCallLogPermissionResult(granted) } }
    private val phoneContactsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> ifAliveAndReady { phoneTabController.handleContactsPermissionResult(granted) } }

    /** PART 0: the shared guard — Activity not finishing/destroyed, and the
     *  tab-shell Views/controllers this callback would touch actually exist
     *  (they're built in [setupTabShell], called from [onCreate]; a stray
     *  callback that somehow fired before that would otherwise crash on an
     *  uninitialized `lateinit`). */
    private fun ifAliveAndReady(action: () -> Unit) {
        if (isFinishing || isDestroyed) return
        if (!::phoneTabController.isInitialized) return
        action()
    }

    private val REQUIRED_PERMISSIONS = arrayOf(
        android.Manifest.permission.READ_PHONE_STATE,
        android.Manifest.permission.SEND_SMS,
        android.Manifest.permission.RECEIVE_SMS,
        android.Manifest.permission.CALL_PHONE,
        android.Manifest.permission.RECORD_AUDIO,
    )

    // PART 1.3: relayStoppedReceiver (+ the status-pill UI it drove) moved
    // to SettingsActivity along with the rest of Cards 1/2/3 — this
    // Activity no longer shows relay status directly.

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
        // PART 2.2: targetSdk 36 — edge-to-edge is mandatory, no opt-out.
        AppShell.applySystemBarInsets(binding.root)

        // PART 1.1: native back handling for Tab 1's WebView — its own
        // history first, system back (leave the app / whatever's next in
        // the task) only once it has none left, and only while Tab 1 is
        // actually the visible tab.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val consumed = currentTab == AppTab.INTERNATIONAL &&
                    ::internationalScreen.isInitialized && internationalScreen.handleBackPressed()
                if (!consumed) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        // CAP PROBE: temporary read-only diagnostic \u2014 see CapabilityProbe.kt.
        CapabilityProbe.logStartupCapabilities(this)

        // PART 4.3: removed the old blanket 5-permission request that used
        // to fire right here, unconditionally, on every cold launch, with
        // no rationale — exactly what that Part's instruction singles out.
        // Every permission this Activity needs is now requested at its own
        // point of use instead: see startRelayService's rationale dialog
        // below (CALL_PHONE/RECORD_AUDIO, right before the user's own "Set
        // up relay node" tap actually needs them).
        setupTabShell()

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
            // PART 5.1/5.2: this onboarding step doubles as "who is this
            // device" for the whole app now — written alongside (not instead
            // of) the "opencall" keys above, which the untouched relay/SMS
            // code in this file still reads directly.
            AccountStore.setDisplayName(this, name)
            AccountStore.setSimNumber(this, normalized, verified = true)

            showScreen("dashboard")
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

        // PART 1.2: the "change" link that used to sit in this header moved
        // into Settings' Account section ("Change account") — see
        // EXTRA_RESET_SETUP below, which is how it gets back here.

        initFlow()
        applyIncomingExtras(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIncomingExtras(intent)
    }

    private fun applyIncomingExtras(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_RESET_SETUP, false)) {
            getSharedPreferences("opencall", MODE_PRIVATE).edit()
                .putBoolean("setup_complete", false)
                .apply()
            stopRelayService()
            showScreen("setup")
            return
        }
        val tabName = intent.getStringExtra(EXTRA_SELECT_TAB)
        val tab = tabName?.let { runCatching { AppTab.valueOf(it) }.getOrNull() }
        if (tab != null && tab != AppTab.OFFLINE) showTab(tab)
        intent.getStringExtra(EXTRA_PREFILL_NUMBER)?.let { number ->
            showTab(AppTab.PHONE)
            if (::phoneTabController.isInitialized) phoneTabController.prefillNumber(number)
        }
    }

    // â”€â”€ Two-screen flow â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private fun showScreen(screen: String) {
        binding.screenSetup.visibility =
            if (screen == "setup") View.VISIBLE else View.GONE
        binding.screenDashboard.visibility =
            if (screen == "dashboard") View.VISIBLE else View.GONE
    }

    // ── PART 1: three-tab shell ──────────────────────────────────────────

    private fun setupTabShell() {
        internationalScreen = InternationalCallScreen(this, binding.tabInternational)
        phoneTabController = PhoneTabController(
            this, binding.tabPhone,
            phoneCallPermissionLauncher, phoneCallLogPermissionLauncher, phoneContactsPermissionLauncher
        )
        // PART 2.1: same AppShell.buildTopBar() call OfflineCallActivity.kt:2911
        // makes — one header-rendering code path for all three tabs now.
        binding.dashboardTopBarSlot.addView(
            AppShell.buildTopBar(this) {
                startActivity(Intent(this, SettingsActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                })
            }
        )

        // PART 1.4: process-death/first-launch restore — see AppShell.restoreTab's
        // own doc for why AppTab.OFFLINE is never what this returns.
        val stored = getSharedPreferences("opencall", MODE_PRIVATE).getString(PREF_LAST_TAB, null)
        showTab(AppShell.restoreTab(stored))
    }

    /** Bottom-bar tap: Offline hops to [OfflineCallActivity] (never
     *  `finish()`-ing this Activity, see class doc); the other two tabs are
     *  shown in place. */
    private fun onTabBarSelected(tab: AppTab) {
        if (tab == AppTab.OFFLINE) {
            getSharedPreferences("opencall", MODE_PRIVATE).edit().putString(PREF_LAST_TAB, tab.name).apply()
            startActivity(Intent(this, OfflineCallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            })
            return
        }
        showTab(tab)
    }

    private fun showTab(tab: AppTab) {
        currentTab = tab
        getSharedPreferences("opencall", MODE_PRIVATE).edit().putString(PREF_LAST_TAB, tab.name).apply()
        binding.tabInternational.visibility = if (tab == AppTab.INTERNATIONAL) View.VISIBLE else View.GONE
        binding.tabPhone.visibility = if (tab == AppTab.PHONE) View.VISIBLE else View.GONE
        if (tab == AppTab.INTERNATIONAL) internationalScreen.start()
        if (tab == AppTab.PHONE) phoneTabController.start()
        renderBottomTabBar()
    }

    /** PART 2.1: AppShell.buildBottomTabBar() bakes the selected tab's colour
     *  into the views it returns at build time (see AppShell.kt) — it has no
     *  separate "update selection" entry point, so unlike the old inline nav
     *  bar (which just recoloured its existing icon/label views in place),
     *  this rebuilds the bar fresh on every tab switch. */
    private fun renderBottomTabBar() {
        binding.dashboardBottomBarSlot.removeAllViews()
        binding.dashboardBottomBarSlot.addView(
            AppShell.buildDivider(this),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * resources.displayMetrics.density).toInt()
            )
        )
        binding.dashboardBottomBarSlot.addView(
            AppShell.buildBottomTabBar(this, currentTab) { tab -> onTabBarSelected(tab) }
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(PREF_LAST_TAB, currentTab.name)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        showTab(AppShell.restoreTab(savedInstanceState.getString(PREF_LAST_TAB)))
    }

    private fun initFlow() {
        val prefs   = getSharedPreferences("opencall", MODE_PRIVATE)
        val claimed = prefs.getBoolean("setup_complete", false)
        val name    = prefs.getString("user_name",   null)
        val number  = prefs.getString("user_number", null)
        if (claimed && !name.isNullOrBlank() && !number.isNullOrBlank()) {
            showScreen("dashboard")
            // PART 1.3: the server-URL field itself now lives in
            // SettingsActivity (Card 2), which reads/restores it on its own
            // onCreate — nothing to do with it here anymore.
        } else {
            showScreen("setup")
        }
    }

    // â”€â”€ Relay service helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    fun normalizeNumber(num: String): String {
        var n = num.trim().replace(Regex("[\\s\\-\\(\\)]"), "")
        if (n.isNotEmpty() && !n.startsWith("+")) n = "+$n"
        return n
    }

    private var pendingRelayStart: Pair<String, String>? = null

    // PART 4.3: this app's own permission-result callback for the CALL_PHONE/
    // RECORD_AUDIO pair below (requestCode 1001 is also still used by
    // onRequestPermissionsResult's generic toast, unchanged).
    private fun startRelayService(serverUrl: String, e164: String) {
        val hasCall  = checkSelfPermission(Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        val hasAudio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

        if (!hasCall || !hasAudio) {
            pendingRelayStart = serverUrl to e164
            // PART 4.3: a rationale FIRST, in this app's own dialog — the
            // system permission dialog(s) only appear after the user taps
            // "Continue" here, never as a surprise on launch or mid-flow.
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Call and microphone access")
                .setMessage(
                    "OpenCall needs to place calls and use the microphone " +
                    "to relay a call for you. You'll be asked to grant both next."
                )
                .setPositiveButton("Continue") { _, _ ->
                    ActivityCompat.requestPermissions(
                        this, arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.RECORD_AUDIO), 1001
                    )
                }
                .setNegativeButton("Not now", null)
                .show()
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

    // PART 1.3: startRelay()/stopRelay() (RelayForegroundService toggle) moved
    // to SettingsActivity along with the Start/Stop Relay button itself.

    // â”€â”€ Permissions â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // PART 0: see ifAliveAndReady's own doc.
        if (isFinishing || isDestroyed) return

        val denied = permissions.filterIndexed { i, _ ->
            grantResults[i] != android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (denied.isEmpty()) {
            Toast.makeText(
                this,
                "\u2705 All permissions granted \u2014 relay ready",
                Toast.LENGTH_SHORT
            ).show()
            // PART 4.3: resumes exactly where the user left off \u2014 they
            // shouldn't have to re-tap "Set up relay node" after granting.
            pendingRelayStart?.let { (serverUrl, e164) -> startRelayService(serverUrl, e164) }
            pendingRelayStart = null
        } else {
            pendingRelayStart = null
            Toast.makeText(
                this,
                "\u26a0\ufe0f Denied: ${denied.joinToString { it.substringAfterLast('.') }}" +
                "\nRelay may not work fully",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // PART 1.3: this used to retry startRelay() (now relocated to
    // SettingsActivity) after an overlay-permission grant — but requestCode
    // 2001 was never actually requested from anywhere in this Activity
    // (dead even before this restructure), so onActivityResult is dropped
    // entirely rather than left calling a function that no longer exists here.

    // â”€â”€ Status bar card â†’ opens RelaySettingsFragment â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    // PART 1.3: setupStatusBar() (opened RelaySettingsFragment from the
    // dashboard's status card) moved to SettingsActivity, verbatim.

    // â”€â”€ Start/Stop relay button â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    // PART 1.3: setupRelayButton() moved to SettingsActivity, verbatim.

    // PART 1: addOfflineCallButton() is gone — Tab 3 (Offline) in the new
    // bottom tab bar is what opens OfflineCallActivity now, so the old
    // floating "Offline Call" button would just be a second, redundant way in.

    // â”€â”€ Relay mode toggles â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    // PART 1.3: setupModeButtons()/saveRelayMode()/restoreRelayMode()/
    // updateModeUI() all moved to SettingsActivity, verbatim.

    // PART 1.3: updateRelayStatus() moved to SettingsActivity, verbatim.

    // â”€â”€ Lifecycle â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    // PART 1.3: the ACTIVE/Stop-Relay pill-sync used to live in onResume()
    // here — that UI is now SettingsActivity's own (see its onResume()).

    override fun onPause() {
        super.onPause()
        if (smsReceiverRegistered) {
            unregisterReceiver(relaySmsReceiver)
            smsReceiverRegistered = false
        }
    }

    // â”€â”€ Default dialer prompt â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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
                "the OCP number \u2014 not your real number."
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

    // â”€â”€ SMS relay â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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

    // â”€â”€ Number validation â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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

    // â”€â”€ Channel engine â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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
        val msg = Uri.encode("Hey! Call me free on OpenCall \u2014 tap: $inviteURL")
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
                        "\u2713 SMS sent" else "\u2717 Failed \u2014 use buttons above"
                }
            } catch (e: Exception) {
                runOnUiThread { statusView.text = "\u2717 Network error" }
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
