package com.opencall.relay.onboarding

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.opencall.relay.R
import com.opencall.relay.account.AccountStore
import com.opencall.relay.shell.AppShell
import com.opencall.relay.shell.AppTab

/**
 * ONBOARDING REWRITE: three swipeable screens with a persistent skip,
 * replacing the old single-form "Relay Node Setup" screen. Built entirely
 * programmatically (no new layout XML, no Compose, no new dependency),
 * matching this codebase's existing convention for a self-contained
 * screen-controller class owning one container ([InternationalCallScreen],
 * [com.opencall.relay.dialer.ui.PhoneTabController] are the same shape).
 *
 * "Swipeable" is a plain [HorizontalScrollView] over three full-width pages
 * (no ViewPager2 — not an existing dependency, and the task ruled out adding
 * one) with a settle-and-snap listener standing in for real paging physics;
 * the bottom primary button (Next -> Next -> Start) and the top-right Skip
 * are the reliable, always-available way through the flow regardless of
 * whether a swipe lands cleanly on a page boundary.
 *
 * Screen 1 shows [RingMarkView]'s pulse; it is explicitly paused whenever
 * this flow isn't the visible screen (page change, or the host Activity
 * pausing) and only resumed on page 1 — see [onResume]/[onPause]/[goToPage].
 */
class OnboardingFlow(
    private val activity: AppCompatActivity,
    private val container: FrameLayout,
    /** Called once, after either "Start" (screen 3) or "Skip" (any screen)
     *  completes — `setup_complete` and the display name (or its default)
     *  are already persisted by the time this fires. The caller's job is
     *  just to switch from the setup screen to the dashboard. */
    private val onFinished: () -> Unit
) {
    companion object {
        const val PAGE_COUNT = 3
        private const val SCROLL_SETTLE_DELAY_MS = 120L

        // ── TESTS: every literal user-visible string this flow renders,
        // pulled into one place so a plain-JVM test can assert none of them
        // contains "relay"/"node" without instantiating any View — see
        // buildPage1/buildPage2/buildPage3/render below, which all read from
        // here rather than duplicating a literal inline.
        const val SKIP_LABEL = "Skip"
        const val SCREEN1_HEADLINE = "OpenCall"
        const val SCREEN1_SUBTITLE = "Calls that work when the network doesn't."
        const val SCREEN2_ROW1 = "Your phone, better — dialer, contacts, call history"
        const val SCREEN2_ROW2 = "Off the grid — call people nearby with no signal, no SIM, no internet"
        const val SCREEN2_ROW3 = "Emergency SOS — a signed alert that carries across devices"
        const val SCREEN3_LABEL = "What should people see when you call?"
        const val NEXT_LABEL = "Next"
        const val START_LABEL = "Start"
        val ONBOARDING_STRINGS = listOf(
            SKIP_LABEL, SCREEN1_HEADLINE, SCREEN1_SUBTITLE,
            SCREEN2_ROW1, SCREEN2_ROW2, SCREEN2_ROW3,
            SCREEN3_LABEL, NEXT_LABEL, START_LABEL
        )

        /** Pure decision core of [goToPage]'s button-label switch — the
         *  bottom primary button reads "Start" only on the last page (screen
         *  3), "Next" on every other one. */
        fun primaryButtonLabelFor(pageIndex: Int): String =
            if (pageIndex == PAGE_COUNT - 1) START_LABEL else NEXT_LABEL

        /** Pure decision core of [finish] — an empty/blank typed name never
         *  blocks completion; it falls back to whatever [existingOrDefault]
         *  already is (this class always calls it with
         *  [com.opencall.relay.offline.OfflineIdentity.displayName]'s
         *  result, which is itself never blank — see that function's own
         *  Build.MODEL fallback). */
        fun resolveDisplayNameToPersist(typed: String, existingOrDefault: String): String =
            typed.trim().ifEmpty { existingOrDefault }

        /** Pure decision core of "does a stored flag mean onboarding is
         *  already done" — [MainActivity.initFlow]'s entire gate now. */
        fun isOnboardingComplete(setupCompleteFlag: Boolean): Boolean = setupCompleteFlag

        /** Both completion paths — "Start" on screen 3, or "Skip" from any
         *  screen — resolve to the SAME landing tab: Phone, regardless of
         *  [AppShell.PILLAR_1_ENABLED] (Tab 1's visibility is decoupled from
         *  which tab is the default). */
        fun landingTabAfterOnboarding(): AppTab = AppTab.PHONE
    }

    private val prefs = activity.getSharedPreferences("opencall", Context.MODE_PRIVATE)
    private val density = activity.resources.displayMetrics.density

    private lateinit var scrollView: HorizontalScrollView
    private lateinit var pages: List<FrameLayout>
    private lateinit var dots: List<View>
    private lateinit var primaryButton: TextView
    private lateinit var nameField: EditText
    private lateinit var ringMark: RingMarkView

    private var pageWidth = 0
    private var currentPage = 0
    private var started = false
    private val settleHandler = Handler(Looper.getMainLooper())
    private val settleRunnable = Runnable { snapToNearestPage() }

    fun start() {
        if (started) return
        started = true
        render()
    }

    /** "Change account" (Settings) re-arms onboarding after resetting
     *  `setup_complete` — unlike [start], this always re-renders from a
     *  clean page 1, discarding whatever was typed into [nameField] before. */
    fun restart() {
        started = true
        pageWidth = 0
        currentPage = 0
        render()
    }

    /** Paired with the host Activity's onResume — resumes the ring pulse
     *  only if screen 1 is the one currently showing. */
    fun onResume() {
        if (!started) return
        if (currentPage == 0) ringMark.startPulse()
    }

    /** Paired with the host Activity's onPause — a mark that keeps pulsing
     *  while the Activity isn't visible is just wasted animation work. */
    fun onPause() {
        if (!started) return
        ringMark.stopPulse()
    }

    private fun colorOf(id: Int) = ContextCompat.getColor(activity, id)
    private fun dp(v: Int) = (v * density).toInt()

    private fun render() {
        container.removeAllViews()

        val root = FrameLayout(activity)

        scrollView = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val pagesRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val page1 = buildPage1()
        val page2 = buildPage2()
        val page3 = buildPage3()
        pages = listOf(page1, page2, page3)
        pages.forEach { pagesRow.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT)) }
        scrollView.addView(pagesRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        scrollView.setOnScrollChangeListener { _, _, _, _, _ ->
            settleHandler.removeCallbacks(settleRunnable)
            settleHandler.postDelayed(settleRunnable, SCROLL_SETTLE_DELAY_MS)
        }
        scrollView.addOnLayoutChangeListener { v, left, _, right, _, _, _, _, _ ->
            val w = right - left
            if (w > 0 && w != pageWidth) {
                pageWidth = w
                pages.forEach { it.layoutParams = LinearLayout.LayoutParams(pageWidth, LinearLayout.LayoutParams.MATCH_PARENT) }
                v.post { goToPage(currentPage, smooth = false) }
            }
        }
        root.addView(scrollView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // ── Skip: top-right, always available, on every screen ──────────
        val skip = TextView(activity).apply {
            text = SKIP_LABEL
            textSize = 14f
            setTextColor(colorOf(R.color.text_secondary))
            setPadding(dp(16), dp(16), dp(16), dp(16))
            isClickable = true
            isFocusable = true
            setOnClickListener { skip() }
        }
        root.addView(skip, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.END
        })

        // ── Bottom: dot indicators + the one primary button ─────────────
        val bottomBar = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), 0, dp(24), dp(28))
        }
        val dotRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        dots = (0 until PAGE_COUNT).map { i ->
            View(activity).apply {
                background = dotDrawable(selected = i == 0)
                layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply {
                    marginStart = dp(4); marginEnd = dp(4)
                }
            }
        }
        dots.forEach { dotRow.addView(it) }
        bottomBar.addView(dotRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(16)
        })
        primaryButton = TextView(activity).apply {
            text = primaryButtonLabelFor(currentPage)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(colorOf(android.R.color.black))
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.btn_primary)
            isClickable = true
            isFocusable = true
            setOnClickListener { advance() }
        }
        bottomBar.addView(primaryButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))
        root.addView(bottomBar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
        })

        container.addView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        ringMark.startPulse()
    }

    private fun dotDrawable(selected: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(colorOf(if (selected) R.color.accent_blue else R.color.border_subtle))
    }

    // ── Screen 1 — what this is ──────────────────────────────────────────

    private fun buildPage1(): FrameLayout {
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        ringMark = RingMarkView(activity)
        col.addView(ringMark, LinearLayout.LayoutParams(dp(160), dp(160)))
        col.addView(TextView(activity).apply {
            text = SCREEN1_HEADLINE
            textSize = 26f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(colorOf(R.color.text_primary))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })
        col.addView(TextView(activity).apply {
            text = SCREEN1_SUBTITLE
            textSize = 14f
            setTextColor(colorOf(R.color.text_secondary))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        return pageFrame(col)
    }

    // ── Screen 2 — the three things it does ──────────────────────────────

    private fun buildPage2(): FrameLayout {
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        listOf(
            "📞" to SCREEN2_ROW1,
            "🕸" to SCREEN2_ROW2,
            "🆘" to SCREEN2_ROW3
        ).forEach { (icon, line) -> col.addView(iconRow(icon, line), rowParams()) }
        return pageFrame(col)
    }

    private fun iconRow(icon: String, line: String): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(activity).apply {
            text = icon
            textSize = 26f
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(16) })
        addView(TextView(activity).apply {
            text = line
            textSize = 15f
            setTextColor(colorOf(R.color.text_primary))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun rowParams() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = dp(28)
    }

    // ── Screen 3 — your name only ────────────────────────────────────────

    private fun buildPage3(): FrameLayout {
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        col.addView(TextView(activity).apply {
            text = SCREEN3_LABEL
            textSize = 16f
            setTextColor(colorOf(R.color.text_primary))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(20) })
        nameField = EditText(activity).apply {
            setBackgroundResource(R.drawable.field_bg)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            textSize = 16f
            setTextColor(colorOf(R.color.text_primary))
            setHintTextColor(colorOf(R.color.text_muted))
            hint = com.opencall.relay.offline.OfflineIdentity.displayName(activity)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PERSON_NAME
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { advance(); true } else false
            }
        }
        col.addView(nameField, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return pageFrame(col)
    }

    private fun pageFrame(content: View): FrameLayout = FrameLayout(activity).apply {
        val scroll = NestedScrollView(activity).apply { isFillViewport = true }
        val inner = FrameLayout(activity).apply { setPadding(dp(32), dp(32), dp(32), dp(96)) }
        inner.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })
        scroll.addView(inner, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    // ── Paging ────────────────────────────────────────────────────────────

    private fun snapToNearestPage() {
        if (pageWidth <= 0) return
        val idx = ((scrollView.scrollX + pageWidth / 2) / pageWidth).coerceIn(0, PAGE_COUNT - 1)
        goToPage(idx, smooth = true)
    }

    private fun goToPage(index: Int, smooth: Boolean) {
        val target = index * pageWidth
        if (smooth) scrollView.smoothScrollTo(target, 0) else scrollView.scrollTo(target, 0)
        if (currentPage != index) {
            currentPage = index
            dots.forEachIndexed { i, dot -> dot.background = dotDrawable(selected = i == index) }
            primaryButton.text = primaryButtonLabelFor(index)
            if (index == 0) ringMark.startPulse() else ringMark.stopPulse()
        }
    }

    private fun advance() {
        if (currentPage < PAGE_COUNT - 1) {
            goToPage(currentPage + 1, smooth = true)
        } else {
            finish()
        }
    }

    // ── Completion ────────────────────────────────────────────────────────

    /** An empty name never blocks — see [resolveDisplayNameToPersist]. */
    private fun finish() {
        val existingOrDefault = com.opencall.relay.offline.OfflineIdentity.displayName(activity)
        val resolved = resolveDisplayNameToPersist(nameField.text.toString(), existingOrDefault)
        AccountStore.setDisplayName(activity, resolved)
        markComplete()
    }

    private fun skip() {
        markComplete()
    }

    private fun markComplete() {
        settleHandler.removeCallbacks(settleRunnable)
        ringMark.stopPulse()
        prefs.edit().putBoolean("setup_complete", true).apply()
        onFinished()
    }
}
