package com.opencall.relay.international

import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.opencall.relay.R

/**
 * PART 1: Tab 1's entire content is now https://opencall.space/ itself
 * (see [OcpCallWebView.HOME_URL]) — native app chrome only (this class),
 * no native dial pad/country-picker/credit-rate UI layered on top of it
 * (that was the previous, narrower design; superseded — see this task's
 * report). This class owns: the WebView container, the native
 * couldn't-load error state (1.3), and exposing back-navigation to the
 * host's OnBackPressedCallback (1.1).
 */
class InternationalCallScreen(private val activity: AppCompatActivity, private val contentFrame: FrameLayout) {

    private val webView = OcpCallWebView(activity)
    private lateinit var errorOverlay: android.view.View
    private var started = false

    /** Idempotent — MainActivity calls this every time Tab 1 is selected;
     *  the WebView itself is only ever built once (reselecting the tab
     *  must not reload the page or lose call state). */
    fun start() {
        if (started) return
        started = true
        render()
    }

    private fun colorOf(id: Int) = ContextCompat.getColor(activity, id)

    private fun render() {
        contentFrame.removeAllViews()
        val root = FrameLayout(activity)

        if (!webView.isOnline()) {
            root.addView(buildErrorOverlay("No internet connection"), matchParent())
        } else {
            val webViewView = webView.build(
                onLoadFailed = { showError("Couldn't load OpenCall") },
                onLoadSucceeded = { hideError() }
            )
            root.addView(webViewView, matchParent())
            errorOverlay = buildErrorOverlay("Couldn't load OpenCall")
            errorOverlay.visibility = android.view.View.GONE
            root.addView(errorOverlay, matchParent())
        }
        contentFrame.addView(root, matchParent())
    }

    private fun matchParent() = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

    /** PART 1.3: never the platform's browser error page — this, instead. */
    private fun buildErrorOverlay(message: String): android.view.View {
        val density = activity.resources.displayMetrics.density
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(colorOf(R.color.bg_deep))
            setPadding((32 * density).toInt(), (32 * density).toInt(), (32 * density).toInt(), (32 * density).toInt())
            addView(TextView(activity).apply {
                text = "🌐"
                textSize = 40f
                gravity = Gravity.CENTER
            })
            addView(TextView(activity).apply {
                text = message
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(colorOf(R.color.text_primary))
                setPadding(0, (16 * density).toInt(), 0, 0)
            })
            addView(TextView(activity).apply {
                text = "Retry"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(colorOf(R.color.accent_blue))
                setPadding(0, (20 * density).toInt(), 0, 0)
                setOnClickListener { started = false; start() }
            })
        }
    }

    private fun showError(message: String) {
        if (!::errorOverlay.isInitialized) return
        (errorOverlay as? LinearLayout)?.let { overlay ->
            (overlay.getChildAt(1) as? TextView)?.text = message
        }
        errorOverlay.visibility = android.view.View.VISIBLE
    }

    private fun hideError() {
        if (!::errorOverlay.isInitialized) return
        errorOverlay.visibility = android.view.View.GONE
    }

    /** PART 1.1: native back handling — called from MainActivity's
     *  OnBackPressedCallback only while Tab 1 is the visible tab. Returns
     *  true if the WebView consumed the back press (had history to go to). */
    fun handleBackPressed(): Boolean = webView.handleBackPressed()
}
