package com.opencall.relay.dialer.ui

import android.content.Context
import android.content.Intent
import android.telecom.PhoneAccountHandle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.opencall.relay.R
import com.opencall.relay.dialer.data.ContactsRepository
import com.opencall.relay.dialer.data.LocalOcpDirectory
import com.opencall.relay.dialer.data.OcpAccountRef
import com.opencall.relay.dialer.role.DialerPermissions
import com.opencall.relay.dialer.role.DialerRoleExplainerActivity
import com.opencall.relay.dialer.role.DialerRoleManager
import com.opencall.relay.dialer.role.DialerRoleStateMachine
import com.opencall.relay.dialer.route.CallRoute
import com.opencall.relay.dialer.route.CallRouteRegistry
import com.opencall.relay.dialer.route.CallTarget
import com.opencall.relay.dialer.route.SimCallRoute
import com.opencall.relay.dialer.telecom.EmergencyCallGuard

/**
 * PART 3.1: Tab 2's own content — keypad/T9, call log, contacts — extracted
 * from [DialerHostActivity] verbatim (same functions, same behaviour) now
 * that Tab 2 is a true top-level pillar rather than a screen nested inside
 * that Activity's own 5-tab outer shell (see this task's report for why:
 * the outer shell duplicated the offline app's Nearby/Messages/Calls/
 * Groups/Settings bar, which now lives only inside Tab 3). This class owns
 * no Activity lifecycle of its own — its host (`MainActivity`) owns the
 * three permission launchers (they must be registered unconditionally
 * during the host's own initialization) and forwards results in here via
 * [handleCallPermissionResult]/[handleCallLogPermissionResult]/
 * [handleContactsPermissionResult]. The same applies to the default-dialer
 * role explainer's launcher ([handleRoleExplainerResult]) and to the
 * host's onResume ([onHostResume]).
 */
class PhoneTabController(
    private val activity: AppCompatActivity,
    private val contentFrame: FrameLayout,
    private val callPermissionLauncher: ActivityResultLauncher<String>,
    private val callLogPermissionLauncher: ActivityResultLauncher<String>,
    private val contactsPermissionLauncher: ActivityResultLauncher<String>,
    private val roleExplainerLauncher: ActivityResultLauncher<Intent>
) {
    private enum class InnerTab { KEYPAD, LOG, CONTACTS }
    private var innerTab = InnerTab.KEYPAD

    private val registry = CallRouteRegistry()
    private val simRoute = SimCallRoute(activity.applicationContext)
    private val ocpRoute = com.opencall.relay.dialer.route.OcpCallRoute()

    private lateinit var dialDisplay: TextView
    private lateinit var t9ResultsList: LinearLayout
    private var pendingDialAfterPermission: String? = null

    // PART 3.2: resolved lazily, once, the first time Contacts is opened —
    // never blocks render(); an empty map (all-SIM) until/unless it resolves.
    private var ocpMatches: Map<String, OcpAccountRef> = emptyMap()
    private var ocpLookupStarted = false

    // Default-dialer role: the banner shown while this app does NOT hold the
    // role, and the state machine that labels each change (Granted/Denied/
    // Revoked). Seeded with the real state at construction, so its first
    // observation is a baseline, not a false "Revoked".
    private val roleStateMachine = DialerRoleStateMachine().apply {
        onObserved(DialerRoleManager.currentState(activity))
    }
    private var lastRoleEvent: DialerRoleStateMachine.Event = DialerRoleStateMachine.Event.NoChange
    private var awaitingRoleResult = false
    private var roleBanner: TextView? = null

    fun start() {
        registry.register(simRoute)
        registry.register(ocpRoute) // PART B.2: always unavailable — see OcpCallRoute's own doc
        render()
        maybePromptForRoleOnce()
    }

    /** 3.1's own "open the keypad with this number ready" entry point —
     *  called by the host when the app is opened via ACTION_DIAL/tel:. */
    fun prefillNumber(number: String) {
        innerTab = InnerTab.KEYPAD
        render()
        if (::dialDisplay.isInitialized) {
            dialDisplay.text = number
            refreshT9()
        }
    }

    fun handleCallPermissionResult(granted: Boolean) {
        if (granted) pendingDialAfterPermission?.let { dial(it) }
        else Toast.makeText(activity, "Call permission is needed to place a call", Toast.LENGTH_SHORT).show()
        pendingDialAfterPermission = null
    }

    fun handleCallLogPermissionResult(granted: Boolean) = render()
    fun handleContactsPermissionResult(granted: Boolean) = render()

    // ── Default-dialer role ──────────────────────────────────────────────

    /** Automatic prompt, at most once ever: the first time this tab is
     *  shown AFTER onboarding. With Pillar 1 hidden, the host shows this tab
     *  during onCreate on a fresh install while onboarding still covers it,
     *  so that call must not count; [PREF_ROLE_PROMPTED] is written only once
     *  `setup_complete` is true. It is written before the role check, so a
     *  first open where the role is already held also uses up the one
     *  automatic prompt. */
    private fun maybePromptForRoleOnce() {
        val prefs = activity.getSharedPreferences("opencall", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("setup_complete", false)) return
        if (prefs.getBoolean(PREF_ROLE_PROMPTED, false)) return
        prefs.edit().putBoolean(PREF_ROLE_PROMPTED, true).apply()
        if (DialerRoleManager.isDefaultDialer(activity)) return
        launchRoleExplainer()
    }

    private fun launchRoleExplainer() {
        roleStateMachine.onRequestLaunched()
        awaitingRoleResult = true
        roleExplainerLauncher.launch(Intent(activity, DialerRoleExplainerActivity::class.java))
    }

    /** The explainer returns RESULT_OK only on Granted and RESULT_CANCELED
     *  otherwise ("Not now", back, or the system prompt declined). Both codes
     *  come from the explainer's own re-query of the role holder, so the
     *  state machine here is fed that same ground truth (a fresh
     *  [DialerRoleManager.currentState]) rather than [resultCode]; the code
     *  and the re-query cannot disagree. */
    @Suppress("UNUSED_PARAMETER")
    fun handleRoleExplainerResult(resultCode: Int) {
        awaitingRoleResult = false
        onRoleEvent(roleStateMachine.onObserved(DialerRoleManager.currentState(activity)))
    }

    /** Host's onResume: catches the role being revoked in system Settings
     *  while this app was in the background. Skipped while the explainer is
     *  open, so an onResume that lands before the explainer's result can't
     *  consume the machine's "request in flight" state and mislabel the
     *  outcome. */
    fun onHostResume() {
        if (awaitingRoleResult) return
        onRoleEvent(roleStateMachine.onObserved(DialerRoleManager.currentState(activity)))
    }

    private fun onRoleEvent(event: DialerRoleStateMachine.Event) {
        if (event == DialerRoleStateMachine.Event.NoChange) return
        lastRoleEvent = event
        updateRoleBanner()
    }

    /** Hidden while the role is held; otherwise a passive banner. Tapping it
     *  reopens the explainer on request, which is not the automatic prompt. */
    private fun buildRoleBanner(density: Float): TextView = TextView(activity).apply {
        textSize = 13f
        setTextColor(colorOf(R.color.text_primary))
        setBackgroundColor(colorOf(R.color.bg_card))
        val pad = (12 * density).toInt()
        setPadding(pad, pad, pad, pad)
        setOnClickListener { launchRoleExplainer() }
    }

    private fun updateRoleBanner() {
        val banner = roleBanner ?: return
        if (roleStateMachine.currentlyDefault()) {
            banner.visibility = View.GONE
            return
        }
        banner.text = if (lastRoleEvent == DialerRoleStateMachine.Event.Revoked)
            "OpenCall is no longer your default phone app. Tap to set it again."
        else
            "OpenCall isn't your default phone app. Tap to set it."
        banner.visibility = View.VISIBLE
    }

    private fun colorOf(id: Int) = ContextCompat.getColor(activity, id)

    // ── PART 3.4: inner Keypad/Log/Contacts sub-nav ──────────────────────

    private fun render() {
        contentFrame.removeAllViews()
        val density = activity.resources.displayMetrics.density
        val banner = buildRoleBanner(density)
        roleBanner = banner
        updateRoleBanner()
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(banner)
            addView(buildInnerTabBar(density))
            val body = when (innerTab) {
                InnerTab.KEYPAD -> buildKeypadScreen(density)
                InnerTab.LOG -> buildCallLogScreenOrPermissionPrompt()
                InnerTab.CONTACTS -> buildContactsScreenOrPermissionPrompt()
            }
            addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        contentFrame.addView(column)
    }

    private fun buildInnerTabBar(density: Float): View {
        val tabs = linkedMapOf(InnerTab.KEYPAD to "Keypad", InnerTab.LOG to "Log", InnerTab.CONTACTS to "Contacts")
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(colorOf(R.color.bg_card))
            tabs.forEach { (tab, label) ->
                addView(TextView(activity).apply {
                    text = label
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setPadding(0, (14 * density).toInt(), 0, (14 * density).toInt())
                    setTextColor(colorOf(if (tab == innerTab) R.color.accent_blue else R.color.text_secondary))
                    setOnClickListener { innerTab = tab; render() }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
    }

    // ── PART 3.1: keypad + T9 ─────────────────────────────────────────────

    private fun buildKeypadScreen(density: Float): View {
        // PART 4.1: large, letter-spaced, single line, ellipsize from the
        // START (a long number keeps its most recently typed/most
        // significant end visible) — weight=1f in displayRow below so
        // backspace (4.1's "at its right edge") has a fixed slot beside it.
        dialDisplay = TextView(activity).apply {
            textSize = 30f
            letterSpacing = 0.06f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.START
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(colorOf(R.color.text_primary))
            setPadding((20 * density).toInt(), (20 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
        }
        // PART 4.1: moved out of the bottom action row — now sits at the
        // number display's own right edge. Same click/long-click behaviour,
        // unchanged.
        val backspace = TextView(activity).apply {
            text = "⌫"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(colorOf(R.color.text_secondary))
            setPadding((16 * density).toInt(), (20 * density).toInt(), (20 * density).toInt(), (8 * density).toInt())
            setOnClickListener { backspace() }
            setOnLongClickListener { dialDisplay.text = ""; refreshT9(); true }
        }
        val displayRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(dialDisplay, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(backspace, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        // PART 4.2: WRAP_CONTENT + GONE-when-empty (see refreshT9) — was
        // LayoutParams(MATCH_PARENT, 0, 1f), an empty weighted view eating
        // all free vertical space between the display and the keypad.
        t9ResultsList = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        val keys = listOf(
            "1" to "", "2" to "ABC", "3" to "DEF",
            "4" to "GHI", "5" to "JKL", "6" to "MNO",
            "7" to "PQRS", "8" to "TUV", "9" to "WXYZ",
            "*" to "", "0" to "+", "#" to ""
        )
        val grid = GridLayout(activity).apply {
            columnCount = 3
            keys.forEach { (digit, letters) ->
                addView(buildKeyButton(digit, letters), GridLayout.LayoutParams().apply {
                    width = (88 * density).toInt(); height = (72 * density).toInt()
                })
            }
        }

        // PART 4.3: call FAB alone, centred below the keypad grid.
        val callButton = TextView(activity).apply {
            text = "📞"
            textSize = 26f
            gravity = Gravity.CENTER
            setBackgroundColor(colorOf(R.color.accent_green))
            setPadding((28 * density).toInt(), (12 * density).toInt(), (28 * density).toInt(), (12 * density).toInt())
            setOnClickListener { onCallButtonTapped() }
        }
        val callRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (12 * density).toInt(), 0, (12 * density).toInt())
            addView(callButton)
        }

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(displayRow)
            addView(t9ResultsList, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(callRow)
        }
    }

    private fun buildKeyButton(digit: String, letters: String): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(TextView(activity).apply {
                text = digit; textSize = 22f; gravity = Gravity.CENTER
                setTextColor(colorOf(R.color.text_primary))
            })
            if (letters.isNotEmpty()) {
                addView(TextView(activity).apply {
                    text = letters; textSize = 9f; gravity = Gravity.CENTER
                    setTextColor(colorOf(R.color.text_muted))
                })
            }
            setOnClickListener { appendDigit(digit) }
        }

    private fun appendDigit(digit: String) {
        dialDisplay.text = dialDisplay.text.toString() + digit
        refreshT9()
    }

    private fun backspace() {
        val current = dialDisplay.text.toString()
        if (current.isNotEmpty()) dialDisplay.text = current.dropLast(1)
        refreshT9()
    }

    // PART 0: guards against an earlier, slower keystroke's T9 query landing
    // AFTER a later one and showing stale results.
    private val t9Generation = java.util.concurrent.atomic.AtomicInteger(0)

    private fun refreshT9() {
        val digits = dialDisplay.text.toString().filter { it.isDigit() }
        if (digits.isEmpty()) {
            t9ResultsList.removeAllViews()
            t9ResultsList.visibility = View.GONE // PART 4.2: no dead weighted space once WRAP_CONTENT (see buildKeypadScreen)
            return
        }
        if (!ContactsRepository.isPermissionGranted(activity)) {
            t9ResultsList.removeAllViews()
            t9ResultsList.visibility = View.GONE
            return
        }
        t9ResultsList.visibility = View.VISIBLE
        val myGeneration = t9Generation.incrementAndGet()
        // PART 0: ContactsRepository.queryContacts is a ContentResolver
        // query — used to run here on the main thread on every keystroke.
        activity.runOffMainThread(
            work = { T9Matcher.filterContacts(digits, ContactsRepository.queryContacts(activity)) },
            onResult = { matches ->
                if (t9Generation.get() != myGeneration) return@runOffMainThread
                t9ResultsList.removeAllViews()
                matches.take(20).forEach { contact ->
                    t9ResultsList.addView(buildContactRow(contact) { number -> dialDisplay.text = number })
                }
            }
        )
    }

    private fun onCallButtonTapped() {
        val number = dialDisplay.text.toString()
        if (number.isBlank()) return
        dial(number)
    }

    // ── PART 3.2 / 3.3: call log and contacts screens ────────────────────

    private fun buildCallLogScreenOrPermissionPrompt(): View {
        if (!com.opencall.relay.dialer.data.CallLogRepository.isReadPermissionGranted(activity)) {
            return buildPermissionPrompt("Call log access is needed to show your call history.") {
                callLogPermissionLauncher.launch(DialerPermissions.READ_CALL_LOG)
            }
        }
        return CallLogScreen.build(activity) { number -> dial(number) }
    }

    private fun buildContactsScreenOrPermissionPrompt(): View {
        if (!ContactsRepository.isPermissionGranted(activity)) {
            return buildPermissionPrompt("Contacts access is needed to show and search your contacts.") {
                contactsPermissionLauncher.launch(DialerPermissions.READ_CONTACTS)
            }
        }
        startOcpLookupOnce()
        return ContactsScreen.build(activity, ocpMatches) { number -> dial(number) }
    }

    /** PART 3.2: one background lookup per screen lifetime — re-renders in
     *  place once it resolves. Only fires once contacts are actually
     *  viewed, never eagerly on tab construction.
     *  PART 5.1: local-only now — see [LocalOcpDirectory]'s own doc. Still
     *  off the main thread (a SharedPreferences read plus a ContentResolver
     *  query), still via [runOffMainThread] so this never touches
     *  ocpMatches/render() after the Activity has started finishing. */
    private fun startOcpLookupOnce() {
        if (ocpLookupStarted) return
        ocpLookupStarted = true
        activity.runOffMainThread(
            work = {
                val numbers = ContactsRepository.queryContacts(activity).flatMap { it.phoneNumbers }.distinct()
                LocalOcpDirectory.lookupLocal(activity, numbers)
            },
            onResult = { matches ->
                ocpMatches = matches
                if (innerTab == InnerTab.CONTACTS) render()
            }
        )
    }

    private fun buildPermissionPrompt(message: String, onGrant: () -> Unit): View {
        val density = activity.resources.displayMetrics.density
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((32 * density).toInt(), (32 * density).toInt(), (32 * density).toInt(), (32 * density).toInt())
            addView(TextView(activity).apply {
                text = message
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(colorOf(R.color.text_secondary))
            })
            addView(android.widget.Button(activity).apply {
                text = "Grant access"
                setOnClickListener { onGrant() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (16 * density).toInt()
            })
        }
    }

    private fun buildContactRow(contact: com.opencall.relay.dialer.data.DialerContact, onCall: (String) -> Unit): View {
        val density = activity.resources.displayMetrics.density
        val number = contact.phoneNumbers.firstOrNull() ?: return View(activity)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (56 * density).toInt()
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
            addView(TextView(activity).apply {
                text = contact.displayName
                textSize = 16f
                setTextColor(colorOf(R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            setOnClickListener { onCall(number) }
        }
    }

    // ── dial — emergency bypass first, then the route registry ───────────

    /** PART B.4: route-vs-route, not SIM-vs-SIM — queries every registered
     *  route ([CallRouteRegistry.routesFor]), builds the picker's row model
     *  ([CallPickerModel], pure/tested separately), and skips the sheet
     *  entirely when exactly one row would be actionable — the single-SIM,
     *  no-mesh case this app runs in today, so existing behaviour for that
     *  case is unchanged: straight to [CallRouteRegistry.placeVia]. */
    private fun dial(rawNumber: String) {
        val number = rawNumber.trim()
        if (number.isBlank()) return

        if (EmergencyCallGuard.isEmergencyNumber(activity, number)) {
            EmergencyCallGuard.placeViaSystem(activity, number)
            return
        }

        if (!DialerPermissions.isGranted(activity, DialerPermissions.CALL_PHONE)) {
            pendingDialAfterPermission = number
            callPermissionLauncher.launch(DialerPermissions.CALL_PHONE)
            return
        }

        val target = CallTarget(e164Number = number)
        val routes: List<Pair<CallRoute, com.opencall.relay.dialer.route.Reachability>> = registry.routesFor(target)
        val simAvailable = routes.firstOrNull { it.first.id == "sim" }
            ?.second?.availability == com.opencall.relay.dialer.route.Availability.AVAILABLE
        // PART B.3 step 2 (once it lands): a real "mesh" route registering
        // here is the ONLY change this function needs — the id it will use
        // matches "sim"/"ocp"'s own convention. Until then routesFor never
        // contains one, so this is always false and "Call nearby" never
        // shows — see MeshCallRoute's own doc once it exists.
        val meshAvailable = routes.firstOrNull { it.first.id == "mesh" }
            ?.second?.availability == com.opencall.relay.dialer.route.Availability.AVAILABLE

        if (!simAvailable && !meshAvailable) {
            Toast.makeText(activity, "Cannot place this call right now (no signal, airplane mode, or no SIM)", Toast.LENGTH_LONG).show()
            return
        }

        val accounts = if (simAvailable) simRoute.callCapableAccounts() else emptyList()
        val simLabels = accounts.map { simRoute.labelFor(it) }
        val rows = CallPickerModel.buildRows(simLabels, meshAvailable)

        when (val single = CallPickerModel.singleActionableRoute(rows)) {
            is CallPickerModel.Row.Sim -> placeSimCall(number, target)
            is CallPickerModel.Row.Mesh -> placeMeshCall(target)
            null -> showCallPicker(number, target, accounts, rows)
            is CallPickerModel.Row.Ocp -> Unit // never actionable — CallPickerModel filters this out
        }
    }

    /** The exact call this function always made before B.4 — a single
     *  callable SIM resolves its own account internally
     *  ([SimCallRoute.resolveAccount]), so this is unchanged for the
     *  single-SIM device this app runs on today. */
    private fun placeSimCall(number: String, target: CallTarget) {
        val handle = registry.placeVia("sim", target)
        if (handle?.requestAccepted != true) {
            Toast.makeText(activity, handle?.failureReason ?: "Could not place call", Toast.LENGTH_LONG).show()
        }
    }

    /** Unreachable today (see [dial]'s own doc) — wired for the moment a
     *  real "mesh" route registers with [Availability.AVAILABLE]. */
    private fun placeMeshCall(target: CallTarget) {
        val handle = registry.placeVia("mesh", target)
        if (handle?.requestAccepted != true) {
            Toast.makeText(activity, handle?.failureReason ?: "Could not place call", Toast.LENGTH_LONG).show()
        }
    }

    /** PART B.4: one row per SIM, "Call with OpenCall" always greyed
     *  (OcpCallRoute never accepts a call), "Call nearby" only if [rows]
     *  contains it. Same dialog/card construction [showAccountPicker] used
     *  (LinearLayout in an AlertDialog, no new dependency), same type scale/
     *  colours as every other row in this file. */
    private fun showCallPicker(
        number: String,
        target: CallTarget,
        accounts: List<PhoneAccountHandle>,
        rows: List<CallPickerModel.Row>
    ) {
        val density = activity.resources.displayMetrics.density
        val dialogView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (24 * density).toInt(), (24 * density).toInt(), (24 * density).toInt())
            addView(TextView(activity).apply {
                text = "Call with"
                textSize = 16f
                setTextColor(colorOf(R.color.text_primary))
            })
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(activity).setView(dialogView).create()

        fun addRow(title: String, subtitle: String?, enabled: Boolean, onClick: () -> Unit) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = enabled
                isFocusable = enabled
                minimumHeight = (48 * density).toInt()
                setPadding(0, (12 * density).toInt(), 0, (12 * density).toInt())
                if (enabled) setOnClickListener { onClick() }
            }
            row.addView(TextView(activity).apply {
                text = title
                textSize = 16f
                setTextColor(colorOf(if (enabled) R.color.text_primary else R.color.text_muted))
            })
            if (subtitle != null) {
                row.addView(TextView(activity).apply {
                    text = subtitle
                    textSize = 12f
                    setTextColor(colorOf(R.color.text_muted))
                })
            }
            dialogView.addView(row)
        }

        rows.forEach { row ->
            when (row) {
                is CallPickerModel.Row.Sim -> {
                    val account = accounts.getOrNull(row.accountIndex)
                    addRow(row.label, subtitle = null, enabled = true) {
                        if (account != null) simRoute.rememberAccountChoice(number, account)
                        dialog.dismiss()
                        val handle = simRoute.place(target, account)
                        if (!handle.requestAccepted) {
                            Toast.makeText(activity, handle.failureReason ?: "Could not place call", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                CallPickerModel.Row.Ocp -> addRow("Call with OpenCall", subtitle = "Not provisioned", enabled = false) {}
                CallPickerModel.Row.Mesh -> addRow("Call nearby", subtitle = null, enabled = true) {
                    dialog.dismiss()
                    placeMeshCall(target)
                }
            }
        }
        dialog.show()
    }

    companion object {
        /** "opencall" prefs key: the one automatic role prompt has been used. */
        private const val PREF_ROLE_PROMPTED = "phone_tab_role_prompted"
    }
}
