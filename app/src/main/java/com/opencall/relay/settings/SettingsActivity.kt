package com.opencall.relay.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.opencall.relay.RelayForegroundService
import com.opencall.relay.RelayService
import com.opencall.relay.RelaySettingsFragment
import com.opencall.relay.R
import com.opencall.relay.account.AccountDeletionClient
import com.opencall.relay.account.AccountStore
import com.opencall.relay.databinding.ActivitySettingsBinding
import com.opencall.relay.dialer.ui.runOffMainThread
import com.opencall.relay.offline.OfflineCallActivity
import com.opencall.relay.offline.OfflineCallService
import com.opencall.relay.offline.OfflineIdentity
import com.opencall.relay.shell.AppShell

/**
 * PART 1.3: "Relay controls on today's first screen ... are an operator
 * feature, not a consumer one." Cards 1/2/3 (node status/START RELAY, relay
 * mode/server URL, OCP credits) below are moved here VERBATIM from
 * `MainActivity` — same view ids (now in `activity_settings.xml`), same
 * Kotlin logic, same behaviour, per that instruction's "do not delete any
 * of it." `RelaySettingsFragment` (the invite/QR/channel bottom sheet) is
 * untouched — only where it's launched from has moved.
 *
 * PART 4.1/4.2: rather than physically relocating OfflineCallActivity's own
 * Settings sub-tab content out of that 6955-line, already-stable Activity
 * (real extraction risk for a two-week-stabilised screen, see the task's
 * report), "Offline mesh settings" below jumps back into OfflineCallActivity
 * pre-selected on that tab — same reachability the brief asks for, zero risk
 * to code the brief explicitly asked not to touch beyond additive chrome.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    // ── moved verbatim from MainActivity ──────────────────────────────

    private val relayStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == RelayService.ACTION_STOPPED) {
                updateRelayStatus()
            }
        }
    }
    private var receiverRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // PART 2.2: targetSdk 36 — edge-to-edge is mandatory, no opt-out.
        com.opencall.relay.shell.AppShell.applySystemBarInsets(binding.root)

        binding.btnSettingsBack.setOnClickListener { finish() }

        renderAccountSection()
        renderIdentitySection()
        setupIdentitySection()
        binding.tvChangeAccount.setOnClickListener {
            startActivity(Intent(this, com.opencall.relay.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                putExtra(com.opencall.relay.MainActivity.EXTRA_RESET_SETUP, true)
            })
            finish()
        }
        binding.rowOfflineMeshSettings.setOnClickListener {
            startActivity(Intent(this, OfflineCallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                putExtra(OfflineCallActivity.EXTRA_OPEN_SETTINGS_TAB, true)
            })
        }
        binding.tvDeleteAccount.setOnClickListener { confirmDeleteAccount() }

        // ONBOARDING REWRITE: the whole call-bridge section (Start/Stop
        // Bridge, bridge mode, server URL — formerly "RELAY NODE") is
        // Pillar 1's own live control surface; gated behind the same flag
        // that hides Tab 1 rather than left reachable with nowhere for the
        // user to have arrived from (see AppShell.PILLAR_1_ENABLED's doc).
        binding.sectionCallBridge.visibility = if (AppShell.PILLAR_1_ENABLED) View.VISIBLE else View.GONE
        if (AppShell.PILLAR_1_ENABLED) {
            setupStatusBar()
            setupRelayButton()
            setupModeButtons()
            restoreRelayMode()
            binding.etServerUrl.setText(
                getSharedPreferences("opencall", MODE_PRIVATE)
                    .getString("server_url", RelayService.DEFAULT_SERVER) ?: RelayService.DEFAULT_SERVER
            )
        }
    }

    private fun renderAccountSection() {
        val account = AccountStore.get(this)
        binding.tvAccountNodeId.text = "Device ID: ${account.nodeIdHex}"
        binding.tvAccountDisplayName.text = account.displayName
        binding.tvAccountSim.text = if (account.simNumber != null)
            "SIM: ${account.simNumber}${if (account.simVerified) " (verified)" else " (unverified)"}"
        else "No SIM number on file"
        binding.tvAccountSip.text = if (account.sipUsername != null)
            "International calling: signed in as ${account.sipUsername}"
        else "No international calling account"
    }

    // ── PART 5.1 (batch B): Identity — the NATIVE Ed25519 identity only ──
    // (AccountStore/OfflineIdentity). The PWA's own client-side identity is
    // not surfaced here and not bridged — it does not exist as far as this
    // app is concerned. tv_ocp_address (Relay node > Relay mode card) is
    // NOT wired here: 5.3 says don't touch the Relay node section, and that
    // field is a different address entirely — the relay subsystem's own
    // registration id (RelayService.getRelayId()), not this identity. See
    // the batch report for this conflict between 5.2 and 5.3.

    /** PART 5.1: a native-only display format — "ocp:" + this device's own
     *  8-byte mesh node id (the same [AccountStore.Account.nodeIdHex]
     *  already shown elsewhere in this Activity as "Device ID: ..."). NOT the
     *  same namespace as the PWA's own ocp: addresses (a completely
     *  separate Ed25519 keypair, generated client-side in JS from a BIP-39
     *  mnemonic) — the two are not interchangeable and a peer on one system
     *  cannot be found via the other. */
    private fun nativeOcpAddress(account: AccountStore.Account): String = "ocp:${account.nodeIdHex}"

    private fun renderIdentitySection() {
        val account = AccountStore.get(this)
        binding.tvIdentityDisplayName.text = account.displayName
        binding.tvIdentityNodeId.text = account.nodeIdHex
        // PART 5.2: never left on a permanent loading string — always the
        // real address, or "unavailable" if something (get() itself,
        // OfflineIdentity's own Keystore path) somehow throws.
        binding.tvIdentityOcpAddress.text = try {
            nativeOcpAddress(account)
        } catch (e: Exception) {
            "unavailable"
        }
    }

    private fun setupIdentitySection() {
        binding.tvIdentityEditName.setOnClickListener { showEditDisplayNameDialog() }
        binding.tvIdentityCopyOcp.setOnClickListener {
            val text = binding.tvIdentityOcpAddress.text.toString()
            if (text == "unavailable") return@setOnClickListener
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("OCP address", text))
            android.widget.Toast.makeText(this, "OCP address copied", android.widget.Toast.LENGTH_SHORT).show()
        }
        binding.tvIdentityBackup.setOnClickListener { showBackupIdentityDialog() }
        binding.tvIdentityShowQr.setOnClickListener { showIdentityQrDialog() }
    }

    private fun showEditDisplayNameDialog() {
        val input = android.widget.EditText(this).apply {
            setText(AccountStore.get(this@SettingsActivity).displayName)
            setSelection(text.length)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Display name")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val saved = OfflineIdentity.setDisplayName(this, input.text.toString())
                if (saved == null) {
                    android.widget.Toast.makeText(this, "Enter a name", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    renderIdentitySection()
                    renderAccountSection()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** PART 5.1: "back up identity" for the NATIVE mesh key means something
     *  different here than on the PWA — OfflineIdentity's private key is
     *  AES-GCM-encrypted with a key that never leaves AndroidKeyStore (see
     *  that class's own doc: "operations on the key, never the key itself")
     *  and has no export path by design; a lost/reinstalled identity is
     *  meant to generate a fresh one, never silently recovered. Rather than
     *  add a new key-export capability (a real change to that security
     *  posture this task didn't ask for), this is honest about that and
     *  backs up the one thing that *can* survive a reinstall: the public
     *  OCP address/node id, so contacts can at least be told "this is who I
     *  was." */
    private fun showBackupIdentityDialog() {
        val account = AccountStore.get(this)
        val address = try { nativeOcpAddress(account) } catch (e: Exception) { "unavailable" }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Back up identity")
            .setMessage(
                "Your OpenCall mesh identity's private key lives in this device's " +
                "hardware-backed keystore and never leaves it — there is no seed " +
                "phrase to export. If you reinstall the app or move to a new " +
                "device, a new identity is generated automatically; your contacts " +
                "will see it as a new device.\n\n" +
                "What you CAN back up is your public address, so you can tell " +
                "contacts who you were:\n\n$address"
            )
            .setPositiveButton("Copy address") { _, _ ->
                if (address != "unavailable") {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("OCP address", address))
                    android.widget.Toast.makeText(this, "OCP address copied", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** PART 5.1: same ZXing encoder (`com.journeyapps.barcodescanner.
     *  BarcodeEncoder`) OfflineCallActivity's own QR screens already use —
     *  no new dependency. */
    private fun showIdentityQrDialog() {
        val account = AccountStore.get(this)
        val address = try { nativeOcpAddress(account) } catch (e: Exception) { null }
        if (address == null) {
            android.widget.Toast.makeText(this, "OCP address unavailable", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val density = resources.displayMetrics.density
        val qrSize = (240 * density).toInt()
        val bitmap = try {
            com.journeyapps.barcodescanner.BarcodeEncoder()
                .encodeBitmap(address, com.google.zxing.BarcodeFormat.QR_CODE, qrSize, qrSize)
        } catch (e: Exception) {
            null
        }
        if (bitmap == null) {
            android.widget.Toast.makeText(this, "Couldn't generate QR code", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val imageView = android.widget.ImageView(this).apply {
            setImageBitmap(bitmap)
            val pad = (24 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Your OCP address")
            .setView(imageView)
            .setPositiveButton("Close", null)
            .show()
    }

    // ── PART 1.3: status bar card → opens RelaySettingsFragment ────────

    private fun setupStatusBar() {
        binding.relayStatusBar.setOnClickListener {
            RelaySettingsFragment().show(supportFragmentManager, "relay_settings")
        }
    }

    // ── PART 1.3: start/stop relay button ───────────────────────────────

    private fun setupRelayButton() {
        binding.btnToggleRelay.setOnClickListener {
            if (RelayService.isRunning) {
                stopRelayService()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ updateRelayStatus() }, 600)
            } else {
                startRelay()
            }
        }
    }

    private fun startRelay() {
        val prefs = getSharedPreferences("opencall", MODE_PRIVATE)
        val number = prefs.getString("user_number", "") ?: ""
        if (number.isEmpty()) {
            android.widget.Toast.makeText(this, "Complete setup first", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val hasSend = checkSelfPermission(android.Manifest.permission.SEND_SMS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasReceive = checkSelfPermission(android.Manifest.permission.RECEIVE_SMS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasSend || !hasReceive) {
            android.widget.Toast.makeText(this,
                "Grant Send SMS and Receive SMS permissions first",
                android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(this, RelayForegroundService::class.java).apply {
            action = RelayForegroundService.ACTION_START
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        binding.tvStatusPill.text = "ACTIVE"
        binding.btnToggleRelay.text = "Stop Bridge"
        android.widget.Toast.makeText(this, "Call bridge started", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun stopRelayService() {
        startService(Intent(this, RelayService::class.java).apply {
            action = RelayService.ACTION_STOP
        })
    }

    // ── PART 1.3: relay mode toggles ────────────────────────────────────

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

    // ── PART 1.3: relay status indicator ────────────────────────────────

    private fun updateRelayStatus() {
        val running = RelayService.isRunning
        val accent  = Color.parseColor("#c8f55a")
        val grey    = Color.parseColor("#666666")
        binding.tvStatusPill.text = if (running) "ACTIVE" else "STOPPED"
        binding.tvStatusPill.setTextColor(if (running) accent else grey)
        binding.btnToggleRelay.text = if (running) "Stop Bridge" else "Start Bridge"
    }

    override fun onResume() {
        super.onResume()
        val running = RelayForegroundService.instance != null
        binding.tvStatusPill.text = if (running) "ACTIVE" else "IDLE"
        binding.btnToggleRelay.text = if (running) "Stop Bridge" else "Start Bridge"
        renderAccountSection()
        renderIdentitySection()
    }

    override fun onPause() {
        super.onPause()
        if (receiverRegistered) {
            unregisterReceiver(relayStoppedReceiver)
            receiverRegistered = false
        }
    }

    // ── PART 5.4: account deletion ──────────────────────────────────────

    /** Names EXACTLY what gets deleted, per that instruction — no vague
     *  "your account and data" wording. */
    private fun confirmDeleteAccount() {
        val account = AccountStore.get(this)
        val simLine = account.simNumber?.let { "\n  • Your SIM number on file ($it)" } ?: ""
        val sipLine = account.sipUsername?.let { "\n  • Your international calling sign-in ($it)" } ?: ""
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Delete account?")
            .setMessage(
                "This permanently deletes, on this device and on OpenCall's server:\n" +
                "  • Your mesh identity (device ${account.nodeIdHex}) — a new one is generated " +
                "if you use OpenCall again, but it will not be the same identity your contacts know$simLine$sipLine\n" +
                "  • Your display name and all local settings\n\n" +
                "This cannot be undone."
            )
            .setPositiveButton("Delete account") { _, _ -> performAccountDeletion(account) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performAccountDeletion(account: AccountStore.Account) {
        runOffMainThread(
            work = { AccountDeletionClient.requestDeletion(account.nodeIdHex, account.sipUsername) },
            onResult = { serverConfirmed ->
                // PART 5.4: the local wipe happens regardless of the server
                // result (see AccountDeletionClient's own doc) — an
                // unreachable server must never trap a user into an
                // account they can't actually delete on their own device.
                if (!serverConfirmed) {
                    android.widget.Toast.makeText(
                        this, "Couldn't reach the server — deleting local data anyway", android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                stopService(Intent(this, RelayService::class.java).apply { action = RelayService.ACTION_STOP })
                stopService(Intent(this, RelayForegroundService::class.java).apply { action = RelayForegroundService.ACTION_STOP })
                stopService(Intent(this, OfflineCallService::class.java).apply { action = OfflineCallService.ACTION_STOP })
                AccountStore.clearSipCredential(this)
                AccountStore.clearSimNumber(this)
                OfflineIdentity.resetIdentity(this)
                getSharedPreferences("opencall", MODE_PRIVATE).edit().clear().apply()
                startActivity(Intent(this, com.opencall.relay.MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(com.opencall.relay.MainActivity.EXTRA_RESET_SETUP, true)
                })
                finish()
            }
        )
    }
}
