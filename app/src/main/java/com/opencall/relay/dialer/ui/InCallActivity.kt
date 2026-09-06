package com.opencall.relay.dialer.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.VideoProfile
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.opencall.relay.R
import com.opencall.relay.dialer.data.ContactsRepository
import com.opencall.relay.dialer.telecom.OcpInCallService

/**
 * PART 2.2/2.3: the app's own in-call UI. One Activity, whose content
 * changes with the call's state — ringing (answer/reject), active
 * (name/number/duration + mute/speaker/hold/keypad/end), and a second-call
 * banner when a new call rings in during an active one — rather than
 * separate Activities per state, since Telecom can transition a single
 * `Call` through all of these while this screen is on top regardless.
 * Programmatic views, matching OfflineCallActivity's own construction style.
 */
class InCallActivity : AppCompatActivity(), OcpInCallService.Listener {

    private lateinit var nameText: TextView
    private lateinit var numberText: TextView
    private lateinit var stateText: TextView
    private lateinit var secondCallBanner: TextView
    private lateinit var ringingButtonRow: LinearLayout
    private lateinit var activeButtonRow: LinearLayout
    private lateinit var muteButton: Button
    private lateinit var speakerButton: Button
    private lateinit var holdButton: Button
    private lateinit var keypadPanel: GridLayout
    private var keypadVisible = false

    private var muted = false
    private var speakerOn = false

    private val durationHandler = Handler(Looper.getMainLooper())
    private val durationTick = object : Runnable {
        override fun run() {
            updateDurationText()
            durationHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(colorOf(R.color.bg_deep)))
        val content = buildContent()
        setContentView(content)
        // PART 2.2: targetSdk 36 — edge-to-edge is mandatory, no opt-out.
        // Highest-risk screen per that task's own callout: mute/speaker/
        // hold/end buttons must never sit under the gesture nav bar.
        com.opencall.relay.shell.AppShell.applySystemBarInsets(content)
    }

    override fun onStart() {
        super.onStart()
        OcpInCallService.addListener(this)
        durationHandler.post(durationTick)
    }

    override fun onStop() {
        OcpInCallService.removeListener(this)
        durationHandler.removeCallbacks(durationTick)
        super.onStop()
    }

    // ── OcpInCallService.Listener ────────────────────────────────────────

    override fun onCallsChanged(calls: List<Call>) {
        runOnUiThread { render(calls) }
    }

    private fun render(calls: List<Call>) {
        if (calls.isEmpty()) {
            finish()
            return
        }
        val ringing = calls.firstOrNull { it.state == Call.STATE_RINGING }
        val primary = ringing ?: calls.firstOrNull {
            it.state == Call.STATE_ACTIVE || it.state == Call.STATE_DIALING || it.state == Call.STATE_HOLDING
        } ?: calls.first()

        val number = primary.details?.handle?.schemeSpecificPart
        val name = number?.let { ContactsRepository.lookupNameForNumber(this, it) }
        nameText.text = name ?: number ?: "Unknown"
        numberText.text = if (name != null) number.orEmpty() else ""
        numberText.visibility = if (name != null && !number.isNullOrBlank()) View.VISIBLE else View.GONE
        stateText.text = stateLabel(primary.state)

        val isRinging = primary.state == Call.STATE_RINGING
        ringingButtonRow.visibility = if (isRinging) View.VISIBLE else View.GONE
        activeButtonRow.visibility = if (isRinging) View.GONE else View.VISIBLE
        if (keypadVisible && isRinging) toggleKeypad() // never leave the keypad open behind a fresh ringing screen

        // PART 2.3: a second incoming call during an active one.
        val secondRinging = if (ringing != null && calls.size > 1) null else calls.firstOrNull {
            it !== primary && it.state == Call.STATE_RINGING
        }
        if (secondRinging != null) {
            val secondNumber = secondRinging.details?.handle?.schemeSpecificPart ?: "Unknown"
            secondCallBanner.text = "Incoming: ${ContactsRepository.lookupNameForNumber(this, secondNumber) ?: secondNumber} — tap to answer"
            secondCallBanner.visibility = View.VISIBLE
            secondCallBanner.setOnClickListener {
                secondRinging.answer(VideoProfile.STATE_AUDIO_ONLY)
            }
        } else {
            secondCallBanner.visibility = View.GONE
        }

        holdButton.text = if (primary.state == Call.STATE_HOLDING) "Resume" else "Hold"
        this.currentPrimaryCall = primary
        updateDurationText()
    }

    private var currentPrimaryCall: Call? = null

    private fun stateLabel(state: Int): String = when (state) {
        Call.STATE_NEW, Call.STATE_CONNECTING -> "Connecting…"
        Call.STATE_DIALING -> "Dialing…"
        Call.STATE_RINGING -> "Incoming call"
        Call.STATE_HOLDING -> "On hold"
        Call.STATE_ACTIVE -> "" // duration text takes over, see updateDurationText
        Call.STATE_DISCONNECTED -> "Call ended"
        Call.STATE_DISCONNECTING -> "Ending…"
        else -> ""
    }

    private fun updateDurationText() {
        val call = currentPrimaryCall ?: return
        if (call.state != Call.STATE_ACTIVE) return
        val connectMs = call.details?.connectTimeMillis ?: return
        if (connectMs <= 0L) return
        val elapsedSec = ((System.currentTimeMillis() - connectMs) / 1000L).coerceAtLeast(0L)
        stateText.text = "%d:%02d".format(elapsedSec / 60, elapsedSec % 60)
    }

    // ── Actions ───────────────────────────────────────────────────────────

    private fun answer() {
        OcpInCallService.ringingCall()?.answer(VideoProfile.STATE_AUDIO_ONLY)
    }

    private fun reject() {
        OcpInCallService.ringingCall()?.reject(false, null)
    }

    private fun endCall() {
        currentPrimaryCall?.disconnect()
    }

    private fun toggleMute() {
        muted = !muted
        OcpInCallService.setMuted(muted)
        muteButton.text = if (muted) "Unmute" else "Mute"
    }

    private fun toggleSpeaker() {
        speakerOn = !speakerOn
        OcpInCallService.setSpeakerOn(speakerOn)
        speakerButton.text = if (speakerOn) "Speaker off" else "Speaker"
    }

    private fun toggleHold() {
        val call = currentPrimaryCall ?: return
        if (call.state == Call.STATE_HOLDING) call.unhold() else call.hold()
    }

    private fun toggleKeypad() {
        keypadVisible = !keypadVisible
        keypadPanel.visibility = if (keypadVisible) View.VISIBLE else View.GONE
    }

    private fun sendDtmf(digit: Char) {
        val call = currentPrimaryCall ?: return
        call.playDtmfTone(digit)
        durationHandler.postDelayed({ call.stopDtmfTone() }, 150L)
    }

    // ── UI construction ──────────────────────────────────────────────────

    private fun colorOf(id: Int) = ContextCompat.getColor(this, id)

    private fun buildContent(): View {
        val density = resources.displayMetrics.density
        val pad = (24 * density).toInt()

        nameText = TextView(this).apply {
            textSize = 26f
            setTextColor(colorOf(R.color.text_primary))
            gravity = Gravity.CENTER
        }
        numberText = TextView(this).apply {
            textSize = 16f
            setTextColor(colorOf(R.color.text_secondary))
            gravity = Gravity.CENTER
        }
        stateText = TextView(this).apply {
            textSize = 18f
            setTextColor(colorOf(R.color.accent_green))
            gravity = Gravity.CENTER
            setPadding(0, (16 * density).toInt(), 0, 0)
        }
        secondCallBanner = TextView(this).apply {
            textSize = 14f
            setTextColor(colorOf(R.color.bg_deep))
            setBackgroundColor(colorOf(R.color.accent_blue))
            setPadding(pad, (12 * density).toInt(), pad, (12 * density).toInt())
            visibility = View.GONE
        }

        val answerButton = Button(this).apply { text = "Answer"; setOnClickListener { answer() } }
        val rejectButton = Button(this).apply { text = "Decline"; setOnClickListener { reject() } }
        ringingButtonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            addView(rejectButton)
            addView(answerButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (24 * density).toInt() })
        }

        muteButton = Button(this).apply { text = "Mute"; setOnClickListener { toggleMute() } }
        speakerButton = Button(this).apply { text = "Speaker"; setOnClickListener { toggleSpeaker() } }
        holdButton = Button(this).apply { text = "Hold"; setOnClickListener { toggleHold() } }
        val keypadButton = Button(this).apply { text = "Keypad"; setOnClickListener { toggleKeypad() } }
        val endButton = Button(this).apply { text = "End"; setOnClickListener { endCall() } }

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(muteButton)
            addView(speakerButton, marginStartParams(density))
            addView(holdButton, marginStartParams(density))
        }
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (16 * density).toInt(), 0, 0)
            addView(keypadButton)
            addView(endButton, marginStartParams(density))
        }
        activeButtonRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row1)
            addView(row2)
        }

        keypadPanel = buildKeypadGrid(density).apply { visibility = View.GONE }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            addView(secondCallBanner)
            addView(nameText)
            addView(numberText)
            addView(stateText)
            addView(ringingButtonRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (32 * density).toInt() })
            addView(activeButtonRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (32 * density).toInt() })
            addView(keypadPanel, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (16 * density).toInt() })
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(colorOf(R.color.bg_deep))
            addView(column)
        }
    }

    private fun marginStartParams(density: Float) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { marginStart = (16 * density).toInt() }

    private fun buildKeypadGrid(density: Float): GridLayout {
        val digits = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")
        return GridLayout(this).apply {
            columnCount = 3
            digits.forEach { digit ->
                addView(Button(this@InCallActivity).apply {
                    text = digit
                    setOnClickListener { sendDtmf(digit[0]) }
                }, GridLayout.LayoutParams().apply {
                    width = (72 * density).toInt()
                    height = (72 * density).toInt()
                })
            }
        }
    }

    companion object {
        /** PART 2.2: the single entry point every call-adding path uses —
         *  [OcpInCallService.onCallAdded] for both incoming and outgoing.
         *  [callInProgress] is documentation-only at the call site today
         *  (there's exactly one caller shape); kept as a named parameter
         *  rather than a bare Unit-returning launch() so a future second
         *  caller (e.g. a "resume call" action from a notification) reads
         *  clearly at its own call site. */
        fun launch(context: Context, @Suppress("UNUSED_PARAMETER") callInProgress: Boolean) {
            val intent = Intent(context, InCallActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
