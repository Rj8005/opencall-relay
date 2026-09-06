package com.opencall.relay.dialer.role

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.opencall.relay.R

/**
 * PART 1.1: shown BEFORE the system role/default-dialer prompt, every time
 * — this app never calls [DialerRoleManager.buildRequestIntent] without the
 * user having seen this screen first (see [com.opencall.relay.dialer.ui.DialerHostActivity]'s
 * own call site). Programmatic views, no XML, no Compose — matches
 * OfflineCallActivity's own construction style (see that file's class doc
 * for the established pattern this mirrors).
 */
class DialerRoleExplainerActivity : AppCompatActivity() {

    // Instance-scoped: tracks only "was a request launched by THIS activity
    // instance, awaiting its result" — see DialerRoleStateMachine's own doc
    // for why the machine itself stays Android-free and separately tested.
    private val roleStateMachine = DialerRoleStateMachine()

    private lateinit var statusText: TextView

    private val roleRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // The result code from RoleManager's/TelecomManager's own prompt is
        // not itself reliable across OEMs — the one ground truth is
        // re-querying the role holder, which DialerRoleManager.currentState
        // does. See onResume — it re-checks unconditionally, request or not.
        finishWithOutcome()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(colorOf(R.color.bg_deep)))
        val content = buildContent()
        setContentView(content)
        // PART 2.2: targetSdk 36 — edge-to-edge is mandatory, no opt-out.
        com.opencall.relay.shell.AppShell.applySystemBarInsets(content)
    }

    override fun onResume() {
        super.onResume()
        // 1.1: "handle... role revoked while running" — if this screen is
        // ever re-shown (e.g. the user backgrounded it mid-flow, changed the
        // default dialer in system Settings, and returned), reflect the
        // CURRENT truth rather than a stale assumption.
        updateStatus()
    }

    private fun updateStatus() {
        statusText.text = when {
            DialerRoleManager.isDefaultDialer(this) ->
                "OpenCall is already your default phone app."
            DialerRoleManager.isHeldByAnotherApp(this) ->
                "Currently set to: ${DialerRoleManager.currentHolderPackage(this)}"
            else -> "No default phone app is currently set."
        }
    }

    private fun colorOf(id: Int) = ContextCompat.getColor(this, id)

    private fun buildContent(): View {
        val density = resources.displayMetrics.density
        val pad = (24 * density).toInt()

        val title = TextView(this).apply {
            text = "Make OpenCall your phone app"
            textSize = 22f
            setTextColor(colorOf(R.color.text_primary))
            setPadding(0, 0, 0, (16 * density).toInt())
        }

        val body = TextView(this).apply {
            text = "To place and receive calls on your SIM, answer incoming " +
                "calls, and keep a call log, Android requires OpenCall to be " +
                "set as the default phone app.\n\n" +
                "• Your calls still go over your carrier's own network — " +
                "this does not change how calls are billed.\n\n" +
                "• You can switch back to any other phone app at any time, " +
                "from Android Settings → Apps → Default apps → Phone app.\n\n" +
                "• Every other OpenCall feature (mesh calling, nearby, " +
                "messages, groups) keeps working exactly as before whether " +
                "or not you make this change now."
            textSize = 15f
            setTextColor(colorOf(R.color.text_secondary))
            setLineSpacing(4 * density, 1f)
        }

        statusText = TextView(this).apply {
            textSize = 13f
            setTextColor(colorOf(R.color.text_muted))
            setPadding(0, (16 * density).toInt(), 0, 0)
        }

        val continueButton = Button(this).apply {
            text = "Continue"
            setOnClickListener { launchRoleRequest() }
        }

        val notNowButton = Button(this).apply {
            text = "Not now"
            setOnClickListener { finish() }
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, (24 * density).toInt(), 0, 0)
            addView(notNowButton)
            addView(continueButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (12 * density).toInt() })
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            addView(title)
            addView(body)
            addView(statusText)
            addView(buttonRow)
        }

        return ScrollView(this).apply {
            setBackgroundColor(colorOf(R.color.bg_deep))
            addView(column)
        }
    }

    /** PART 1.1: the actual system prompt — only reachable after the
     *  explanation above has been shown and the user tapped Continue. */
    private fun launchRoleRequest() {
        roleStateMachine.onRequestLaunched()
        roleRequestLauncher.launch(DialerRoleManager.buildRequestIntent(this))
    }

    private fun finishWithOutcome() {
        val newState = DialerRoleManager.currentState(this)
        when (roleStateMachine.onObserved(newState)) {
            DialerRoleStateMachine.Event.Granted -> {
                Toast.makeText(this, "OpenCall is now your default phone app", Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            }
            DialerRoleStateMachine.Event.Denied -> {
                // 1.1: "the app must remain fully usable as a non-default
                // dialer" — this is a report, not a dead end; the caller
                // decides what (if anything) to do next.
                Toast.makeText(
                    this,
                    if (DialerRoleManager.isHeldByAnotherApp(this))
                        "Default phone app is still ${DialerRoleManager.currentHolderPackage(this)}"
                    else "Not set as default phone app",
                    Toast.LENGTH_LONG
                ).show()
                setResult(RESULT_CANCELED)
                finish()
            }
            else -> updateStatus()
        }
    }
}
