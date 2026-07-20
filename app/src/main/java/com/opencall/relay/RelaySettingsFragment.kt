package com.opencall.relay

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.JustifyContent
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class RelaySettingsFragment : BottomSheetDialogFragment() {

    // ── Relay toggle views ────────────────────────────────────────────────────

    private var btnToggle: Button?   = null
    private var tvStatus:  TextView? = null
    private var tvCredits: TextView? = null

    // ── Invite views ──────────────────────────────────────────────────────────

    private var etInviteNumber: EditText?      = null
    private var tvCountryChip:  TextView?      = null
    private var tvInviteLink:   TextView?      = null
    private var btnCopyLink:    Button?        = null
    private var flChannels:     FlexboxLayout? = null
    private var llQrContainer:  LinearLayout?  = null
    private var ivQrCode:       ImageView?     = null
    private var btnAutoSms:     Button?        = null
    private var rgRelayMode:    RadioGroup?    = null

    private var inviteUrl       = ""
    private var detectedCountry = "UNKNOWN"

    private val httpClient = OkHttpClient()

    // ── Broadcast receiver ────────────────────────────────────────────────────

    private val relayStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == RelayService.ACTION_STOPPED) refresh()
        }
    }
    private var receiverRegistered = false

    // ── Channel definitions ───────────────────────────────────────────────────

    private val channelDefs = mapOf(
        "sms"       to Pair("📱", "SMS"),
        "whatsapp"  to Pair("💬", "WhatsApp"),
        "share"     to Pair("📤", "Share"),
        "telegram"  to Pair("✈️", "Telegram"),
        "viber"     to Pair("💜", "Viber"),
        "line"      to Pair("💚", "LINE"),
        "kakaotalk" to Pair("💛", "KakaoTalk"),
        "signal"    to Pair("🔵", "Signal"),
        "email"     to Pair("✉️", "Email"),
        "call"      to Pair("📞", "Call First"),
        "wechat"    to Pair("🟢", "WeChat QR")
    )

    // ── Inflation ─────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_relay_settings, container, false)

    // ── Bottom sheet appearance + receiver registration ───────────────────────

    override fun onStart() {
        super.onStart()
        val bottomSheet = dialog?.findViewById<View>(
            com.google.android.material.R.id.design_bottom_sheet
        ) ?: return
        bottomSheet.setBackgroundColor(Color.parseColor("#111111"))
        BottomSheetBehavior.from(bottomSheet).apply {
            state         = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                requireContext(),
                relayStoppedReceiver,
                IntentFilter(RelayService.ACTION_STOPPED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
    }

    override fun onStop() {
        super.onStop()
        if (receiverRegistered) {
            try { requireContext().unregisterReceiver(relayStoppedReceiver) }
            catch (_: Exception) {}
            receiverRegistered = false
        }
    }

    // ── View wiring ───────────────────────────────────────────────────────────

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs     = requireContext().getSharedPreferences("opencall", Context.MODE_PRIVATE)
        val etUrl     = view.findViewById<EditText>(R.id.et_server_url)
        val etArea    = view.findViewById<EditText>(R.id.et_area_code)
        val etCountry = view.findViewById<EditText>(R.id.et_country)

        btnToggle   = view.findViewById(R.id.btn_toggle)
        tvStatus    = view.findViewById(R.id.tv_relay_status)
        tvCredits   = view.findViewById(R.id.tv_credits)
        rgRelayMode = view.findViewById(R.id.rg_relay_mode)

        when (prefs.getString("relay_mode", "both")) {
            "call" -> rgRelayMode?.check(R.id.rb_calls_only)
            "sms"  -> rgRelayMode?.check(R.id.rb_sms_only)
            else   -> rgRelayMode?.check(R.id.rb_calls_and_sms)
        }

        etInviteNumber = view.findViewById(R.id.et_invite_number)
        tvCountryChip  = view.findViewById(R.id.tv_country_chip)
        tvInviteLink   = view.findViewById(R.id.tv_invite_link)
        btnCopyLink    = view.findViewById(R.id.btn_copy_link)
        llQrContainer  = view.findViewById(R.id.ll_qr_container)
        ivQrCode       = view.findViewById(R.id.iv_qr_code)
        btnAutoSms     = view.findViewById(R.id.btn_auto_sms)

        flChannels = view.findViewById<FlexboxLayout>(R.id.fl_channels).also {
            it.flexWrap       = FlexWrap.WRAP
            it.justifyContent = JustifyContent.FLEX_START
        }

        etUrl.setText(prefs.getString("server_url", RelayService.DEFAULT_SERVER))
        etArea.setText(prefs.getString("area_code", "+91"))
        etCountry.setText(prefs.getString("country", "IN"))

        refresh()
        fetchInviteToken()
        renderChannels(defaultChannels())

        // ── Relay toggle ──────────────────────────────────────────────────────

        btnToggle?.setOnClickListener {
            val ctx = requireContext()

            if (RelayService.isRunning) {
                btnToggle?.apply {
                    text = "STOPPING..."
                    setBackgroundColor(Color.parseColor("#555555"))
                    isEnabled = false
                }
                ctx.startService(
                    Intent(ctx, RelayService::class.java).apply { action = RelayService.ACTION_STOP }
                )
                view.postDelayed({ if (isAdded) refresh() }, 4_000)

            } else {
                val url       = etUrl.text.toString().trim()
                val area      = etArea.text.toString().trim()
                val ctry      = etCountry.text.toString().trim()
                val relayMode = when (rgRelayMode?.checkedRadioButtonId) {
                    R.id.rb_calls_only -> "call"
                    R.id.rb_sms_only   -> "sms"
                    else               -> "both"
                }

                if (url.isEmpty()) {
                    showToast("Enter a server URL")
                    return@setOnClickListener
                }

                prefs.edit()
                    .putString("server_url", url)
                    .putString("area_code",  area)
                    .putString("country",    ctry)
                    .putString("relay_mode", relayMode)
                    .apply()

                val i = Intent(ctx, RelayService::class.java).apply {
                    action = RelayService.ACTION_START
                    putExtra(RelayService.EXTRA_SERVER_URL,  url)
                    putExtra(RelayService.EXTRA_AREA_CODE,   area)
                    putExtra(RelayService.EXTRA_COUNTRY,     ctry)
                    putExtra(RelayService.EXTRA_RELAY_MODE,  relayMode)
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                    ctx.startForegroundService(i)
                else
                    ctx.startService(i)

                view.postDelayed({ if (isAdded) refresh() }, 600)
            }
        }

        // ── Country auto-detect + channel refresh ─────────────────────────────

        etInviteNumber?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { updateCountryChip(s?.toString() ?: "") }
        })

        // ── Copy invite link ──────────────────────────────────────────────────

        btnCopyLink?.setOnClickListener {
            if (inviteUrl.isEmpty()) return@setOnClickListener
            val cb = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("OCP Invite", inviteUrl))
            showToast("Link copied")
        }

        // ── Close QR panel ────────────────────────────────────────────────────

        view.findViewById<Button>(R.id.btn_close_qr)?.setOnClickListener {
            llQrContainer?.visibility = View.GONE
        }

        // ── Auto-send SMS via server ──────────────────────────────────────────

        btnAutoSms?.setOnClickListener { sendAutoSms() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        btnToggle      = null
        tvStatus       = null
        tvCredits      = null
        rgRelayMode    = null
        etInviteNumber = null
        tvCountryChip  = null
        tvInviteLink   = null
        btnCopyLink    = null
        flChannels     = null
        llQrContainer  = null
        ivQrCode       = null
        btnAutoSms     = null
    }

    // ── Relay refresh ─────────────────────────────────────────────────────────

    private fun refresh() {
        val btn     = btnToggle ?: return
        val status  = tvStatus  ?: return
        val credits = tvCredits ?: return
        val prefs   = requireContext().getSharedPreferences("opencall", Context.MODE_PRIVATE)

        val running = RelayService.isRunning
        val accent  = Color.parseColor("#c8f55a")
        val grey    = Color.parseColor("#666666")
        val red     = Color.parseColor("#cc3333")
        val black   = Color.parseColor("#0a0a0a")

        btn.isEnabled = true
        btn.text      = if (running) "STOP RELAY" else "START RELAY"
        btn.setBackgroundColor(if (running) red else accent)
        btn.setTextColor(black)

        status.text = if (running) "● Relay is ACTIVE" else "○ Relay is STOPPED"
        status.setTextColor(if (running) accent else grey)

        credits.text = "Credits: ${prefs.getInt("credits", 0)}"
    }

    // ── Token fetch ───────────────────────────────────────────────────────────

    private fun fetchInviteToken() {
        val ctx        = context ?: return
        val ocpAddress = ctx.getSharedPreferences("opencall", Context.MODE_PRIVATE)
            .getString("relay_id", "") ?: ""

        if (ocpAddress.isEmpty()) {
            tvInviteLink?.text = "Start relay once to get your address"
            return
        }

        tvInviteLink?.text = "Generating…"

        val request = Request.Builder()
            .url("https://node.opencall.space/invite/token?from=${Uri.encode(ocpAddress)}")
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUi { tvInviteLink?.text = "— server unreachable —" }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                runOnUi {
                    if (response.isSuccessful) {
                        val token = try { JSONObject(body).optString("token", "") }
                                    catch (_: Exception) { "" }
                        if (token.isNotEmpty()) {
                            inviteUrl = "https://opencall.net/join/$token"
                            tvInviteLink?.text = inviteUrl
                        } else {
                            tvInviteLink?.text = "— no token —"
                        }
                    } else {
                        tvInviteLink?.text = "— error ${response.code} —"
                    }
                }
            }
        })
    }

    // ── Channel fetch ─────────────────────────────────────────────────────────

    private fun fetchChannels(country: String, number: String) {
        val url = "https://node.opencall.space/invite/channels" +
                  "?country=${Uri.encode(country)}&number=${Uri.encode(number)}"

        httpClient.newCall(Request.Builder().url(url).get().build())
            .enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUi { renderChannels(defaultChannels()) }
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string() ?: ""
                    runOnUi {
                        val channels = if (response.isSuccessful) {
                            try {
                                val arr = JSONArray(body)
                                List(arr.length()) { arr.getString(it) }
                                    .filter { channelDefs.containsKey(it) }
                                    .ifEmpty { defaultChannels() }
                            } catch (_: Exception) { defaultChannels() }
                        } else { defaultChannels() }
                        renderChannels(channels)
                    }
                }
            })
    }

    private fun defaultChannels() = listOf(
        "sms", "whatsapp", "share", "telegram",
        "viber", "line", "kakaotalk", "signal", "email", "call"
    )

    // ── Channel grid rendering ────────────────────────────────────────────────

    private fun renderChannels(channels: List<String>) {
        val fl = flChannels ?: return
        fl.removeAllViews()

        // WeChat QR is added automatically for CN numbers; include it if in server list
        val list = if (detectedCountry == "CN" && "wechat" !in channels)
            channels + "wechat" else channels

        for (id in list) {
            val (emoji, label) = channelDefs[id] ?: continue
            fl.addView(createChannelButton(id, emoji, label))
        }
    }

    private fun createChannelButton(id: String, emoji: String, label: String): View {
        val ctx  = requireContext()
        val dp4  = dpToPx(4)
        val dp8  = dpToPx(8)
        val dp12 = dpToPx(12)

        // Outer wrapper — exactly 1/3 of FlexboxLayout width via flexBasisPercent
        val outer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER
            layoutParams = FlexboxLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                flexBasisPercent = 0.333f
            }
            setPadding(dp4, dp4, dp4, dp4)
        }

        // Inner card with rounded corners + ripple
        val cardBg = GradientDrawable().apply {
            setColor(Color.parseColor("#1e1e1e"))
            cornerRadius = dpToPx(12).toFloat()
        }
        val rippleMask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dpToPx(12).toFloat()
        }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp8, dp12, dp8, dp12)
            isClickable = true
            isFocusable = true
            background  = cardBg
            foreground  = RippleDrawable(
                ColorStateList.valueOf(Color.parseColor("#333333")),
                null,
                rippleMask
            )
        }

        card.addView(TextView(ctx).apply {
            text     = emoji
            textSize = 26f
            gravity  = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })

        card.addView(TextView(ctx).apply {
            text     = label
            textSize = 10f
            setTextColor(Color.parseColor("#BFFF00"))
            gravity  = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(5) }
        })

        outer.addView(card)
        outer.setOnClickListener { handleChannelClick(id) }
        return outer
    }

    // ── Channel intent dispatch ───────────────────────────────────────────────

    private fun handleChannelClick(id: String) {
        val number      = etInviteNumber?.text?.toString()?.trim() ?: ""
        val noPlus      = number.replace("+", "").replace(" ", "").replace("-", "")
        val message     = buildInviteMessage()
        val link        = if (inviteUrl.isNotEmpty()) inviteUrl else "https://opencall.net"
        val needsNumber = id !in listOf("share", "email", "wechat")

        if (needsNumber && number.isEmpty()) {
            showToast("Enter a phone number first")
            return
        }

        when (id) {
            "sms" -> try {
                startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("sms:+$noPlus")).apply {
                    putExtra("sms_body", message)
                })
            } catch (_: Exception) { showToast("SMS not available") }

            "whatsapp"  -> openUri("https://wa.me/$noPlus?text=${Uri.encode(message)}")

            "share" -> startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, link)
                },
                "Send invite via"
            ))

            "telegram"  -> openUri("https://t.me/+$noPlus")

            "viber"     -> openUri("viber://chat?number=%2B$noPlus")

            "line"      -> openUri("https://line.me/ti/p/~$noPlus")

            "kakaotalk" -> openUri("kakaoplus://friend/$noPlus")

            "signal"    -> openUri("https://signal.me/#p/+$noPlus")

            "email" -> try {
                startActivity(Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:")
                    putExtra(Intent.EXTRA_SUBJECT, "Join me on OpenCall")
                    putExtra(Intent.EXTRA_TEXT, message)
                })
            } catch (_: Exception) { showToast("No email app found") }

            "call" -> try {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:+$noPlus")))
            } catch (_: Exception) { showToast("Cannot open dialer") }

            "wechat" -> showQrCode(link)
        }
    }

    // ── WeChat QR code ────────────────────────────────────────────────────────

    private fun showQrCode(url: String) {
        if (url.isEmpty()) { showToast("Wait for invite link to load"); return }
        try {
            val bitmap = BarcodeEncoder().encodeBitmap(url, BarcodeFormat.QR_CODE, 600, 600)
            ivQrCode?.setImageBitmap(bitmap)
            llQrContainer?.visibility = View.VISIBLE
        } catch (e: Exception) {
            showToast("QR error: ${e.message}")
        }
    }

    // ── Auto-send SMS via server ──────────────────────────────────────────────

    private fun sendAutoSms() {
        val number = etInviteNumber?.text?.toString()?.trim() ?: ""
        if (number.isEmpty()) { showToast("Enter a phone number first"); return }
        if (inviteUrl.isEmpty()) { showToast("Wait for invite link to load"); return }

        val json = JSONObject().apply {
            put("to",        number)
            put("channel",   "textbelt")
            put("inviteURL", inviteUrl)
        }.toString()

        val request = Request.Builder()
            .url("https://node.opencall.space/invite/send")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

        btnAutoSms?.isEnabled = false

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUi { btnAutoSms?.isEnabled = true; showToast("Failed") }
            }
            override fun onResponse(call: Call, response: Response) {
                runOnUi {
                    btnAutoSms?.isEnabled = true
                    showToast(if (response.isSuccessful) "SMS sent" else "Failed")
                }
            }
        })
    }

    // ── Country detection (prefix map) ────────────────────────────────────────

    private fun detectCountry(number: String): String {
        val digits = number.trimStart('+', ' ', '(')
        val map3 = mapOf("380" to "UA")
        val map2 = mapOf(
            "91" to "IN", "44" to "GB", "81" to "JP", "82" to "KR",
            "86" to "CN", "55" to "BR", "61" to "AU", "49" to "DE",
            "84" to "VN", "63" to "PH", "98" to "IR", "66" to "TH"
        )
        val map1 = mapOf("7" to "RU", "1" to "US")

        if (digits.length >= 3) map3[digits.take(3)]?.let { return it }
        if (digits.length >= 2) map2[digits.take(2)]?.let { return it }
        if (digits.length >= 1) map1[digits.take(1)]?.let { return it }
        return "UNKNOWN"
    }

    private fun countryToFlag(code: String): String {
        if (code.length != 2) return "🌍"
        val offset = 0x1F1E6 - 'A'.code
        return String(Character.toChars(offset + code[0].uppercaseChar().code)) +
               String(Character.toChars(offset + code[1].uppercaseChar().code))
    }

    private fun updateCountryChip(number: String) {
        if (number.length < 4) {
            tvCountryChip?.text = "🌍"
            detectedCountry = "UNKNOWN"
            return
        }
        val country = detectCountry(number)
        detectedCountry = country
        tvCountryChip?.text = if (country != "UNKNOWN") "${countryToFlag(country)} $country"
                              else "🌍"

        // Refresh channel grid when a valid country is detected
        if (country != "UNKNOWN" && number.length >= 7) {
            val e164 = if (number.startsWith("+")) number else "+$number"
            fetchChannels(country, e164)
        } else if (country == "CN") {
            // Ensure WeChat is in the grid for CN even before server responds
            renderChannels(defaultChannels() + "wechat")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildInviteMessage(): String {
        val link = if (inviteUrl.isNotEmpty()) inviteUrl else "https://opencall.net"
        return "Hey! I'm on OpenCall — a free, open calling network. " +
               "Use this link to join me: $link"
    }

    private fun openUri(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        } catch (_: Exception) { showToast("App not available") }
    }

    private fun showToast(msg: String) {
        if (!isAdded) return
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    private fun runOnUi(block: () -> Unit) {
        if (!isAdded) return
        activity?.runOnUiThread { if (isAdded) block() }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()
}
