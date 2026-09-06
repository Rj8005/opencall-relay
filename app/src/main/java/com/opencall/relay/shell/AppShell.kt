package com.opencall.relay.shell

import android.app.Activity
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.opencall.relay.R
import com.opencall.relay.account.AccountStore

/**
 * PART 1: the three top-level pillars. Order here is the order they render
 * in the bottom bar, left to right.
 */
enum class AppTab { INTERNATIONAL, PHONE, OFFLINE }

/**
 * PART 1.1/4.2: one shared chrome builder — a top bar (identity + Settings
 * gear) and a bottom 3-tab bar — used by every top-level host (MainActivity
 * for tabs 1/2, OfflineCallActivity for tab 3, SettingsActivity). Pure View
 * builders, no state of their own, deliberately styled to match
 * `dialer/ui/DialerHostActivity.buildOuterTabBar`'s existing emoji-glyph /
 * `bg_card` / `accent_blue` vs `text_secondary` convention exactly, rather
 * than introducing a new icon set — this is what "preserve the dark visual
 * language exactly" means in a codebase with zero vector-icon assets today.
 */
object AppShell {

    private fun colorOf(context: Context, id: Int) = ContextCompat.getColor(context, id)

    /** PART 1.5: this is the one place the "$name · $number" identity string
     *  is built app-wide now — the historical bug (MainActivity used to
     *  double-encode this "·" as "Â·") cannot recur here because this
     *  literal is typed fresh, in a UTF-8 source file, not copy-pasted from
     *  whatever produced the corruption originally. See the final report for
     *  the byte-level diagnosis of the original bug. */
    fun identityLine(context: Context): String {
        val account = AccountStore.get(context)
        val number = account.simNumber ?: "no SIM"
        return "${account.displayName} · $number"
    }

    fun buildTopBar(activity: Activity, onSettingsClick: () -> Unit): LinearLayout {
        val density = activity.resources.displayMetrics.density
        val identity = TextView(activity).apply {
            text = identityLine(activity)
            textSize = 14f
            setTextColor(colorOf(activity, R.color.text_primary))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val gear = TextView(activity).apply {
            text = "⚙"
            textSize = 20f
            setTextColor(colorOf(activity, R.color.text_secondary))
            setPadding((12 * density).toInt(), (8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { onSettingsClick() }
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(colorOf(activity, R.color.bg_card))
            setPadding((16 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
            addView(identity, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(gear)
        }
    }

    fun buildBottomTabBar(activity: Activity, selected: AppTab, onTabSelected: (AppTab) -> Unit): LinearLayout {
        val density = activity.resources.displayMetrics.density
        val tabs = linkedMapOf(
            AppTab.INTERNATIONAL to ("🌐" to "International"),
            AppTab.PHONE to ("📞" to "Phone"),
            AppTab.OFFLINE to ("🕸" to "Offline")
        )
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(colorOf(activity, R.color.bg_card))
        }
        tabs.forEach { (tab, iconLabel) ->
            val (icon, label) = iconLabel
            val isSelected = tab == selected
            val fg = colorOf(activity, if (isSelected) R.color.accent_blue else R.color.text_secondary)
            val item = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumHeight = (56 * density).toInt()
                isClickable = true
                isFocusable = true
                addView(TextView(activity).apply {
                    text = icon; textSize = 20f; gravity = Gravity.CENTER; setTextColor(fg)
                })
                addView(TextView(activity).apply {
                    text = label; textSize = 10f; gravity = Gravity.CENTER; setTextColor(fg)
                    maxLines = 1
                })
                setOnClickListener { onTabSelected(tab) }
            }
            bar.addView(item, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        return bar
    }

    /** A 1dp hairline in `border_subtle`, matching the divider already used
     *  above `activity_main.xml`'s old bottom nav — kept identical here so
     *  the new bar reads as the same design system. */
    fun buildDivider(activity: Activity): View = View(activity).apply {
        setBackgroundColor(colorOf(activity, R.color.border_subtle))
    }

    /** PART 1.4: pure decision core of "which tab to land on," used by
     *  [com.opencall.relay.MainActivity] on both process-death restore
     *  (`SharedPreferences`) and rotation (`onRestoreInstanceState`'s
     *  `Bundle`) — same string, same rule, so both paths agree. [stored] is
     *  whatever was last persisted; an unparseable/missing value defaults to
     *  [AppTab.INTERNATIONAL]. [AppTab.OFFLINE] is deliberately never
     *  returned — Tab 3 isn't a container inside MainActivity (it's
     *  [com.opencall.relay.offline.OfflineCallActivity]), so "last tab was
     *  Offline" correctly lands MainActivity back on Tab 1, not a blank
     *  container. */
    fun restoreTab(stored: String?): AppTab {
        val parsed = stored?.let { runCatching { AppTab.valueOf(it) }.getOrNull() }
        return if (parsed == null || parsed == AppTab.OFFLINE) AppTab.INTERNATIONAL else parsed
    }

    /** PART 4: the global tab bar's own routing rule — tapping the already-
     *  selected pillar is a no-op, never a redundant self-navigation
     *  (relevant inside [com.opencall.relay.offline.OfflineCallActivity],
     *  where [selected] is always [AppTab.OFFLINE]). */
    fun requiresLeavingCurrentActivity(selected: AppTab, tapped: AppTab): Boolean = tapped != selected

    /** PART 2.2: targeting API 36 means edge-to-edge is mandatory with no
     *  opt-out (`windowOptOutEdgeToEdgeEnforcement` is deprecated and
     *  disabled) — every screen's content can now be drawn behind the
     *  status bar, the gesture/3-button nav bar, and display cutouts. This
     *  app had ZERO inset handling before this pass (grep confirmed no
     *  [WindowInsetsCompat] usage anywhere) — every Activity's content
     *  would sit partly behind system bars without this. Padding the
     *  single outermost content view by the system-bar + cutout insets is
     *  correct here specifically because every one of this app's screens
     *  is a single full-bleed column with its own top/bottom chrome
     *  (header, bottom tab/nav bars) as the true outermost elements — the
     *  background itself still draws fully edge-to-edge (padding a
     *  ViewGroup doesn't inset its background, only its children's layout
     *  area), only the content inside gets pushed clear of the bars. Apply
     *  ONCE, to the single root view passed to `setContentView`. */
    fun applySystemBarInsets(root: View) {
        // ADDS to whatever padding the view already had (e.g. IncomingCallActivity's
        // own 32dp breathing room) rather than replacing it — captured once,
        // on the first dispatch, so repeated inset dispatches (rotation,
        // IME show/hide) never compound.
        val basePadding = intArrayOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                basePadding[0] + bars.left,
                basePadding[1] + bars.top,
                basePadding[2] + bars.right,
                basePadding[3] + bars.bottom
            )
            windowInsets
        }
    }
}
