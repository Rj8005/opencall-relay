package com.opencall.relay.international

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.http.SslError
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.opencall.relay.account.AccountStore

/**
 * PART 1.1: hosts https://opencall.space/ directly — native app chrome
 * (built by [InternationalCallScreen]) around a full-site WebView, not a
 * scoped calling-path embed. No browser UI (no address bar, no
 * find-in-page, no download manager) is ever attached to this WebView.
 *
 * PART 1.3: [WebViewClient] below reports load failures ([onLoadFailed])
 * instead of letting the platform render its own browser-style error page
 * inside the WebView — [InternationalCallScreen] turns that into the native
 * error state.
 *
 * PART 2B: the page's own OCP identity (name/number registration form, OCP
 * address block, handle claim, back-up link, install card) is suppressed —
 * NOT via a JS↔native bridge, only a one-shot, load-time query string (see
 * [resolveLoadUrl]). [injectAppModeSafetyNet] does the actual hiding+
 * auto-advance today, entirely client-side against the LIVE opencall.space
 * (which has not been redeployed with the local PWA's app-embed branch —
 * see that function's own doc for the diagnosis). Once opencall.space IS
 * redeployed, its own app-embed CSS/JS will do this server-side and
 * injectAppModeSafetyNet becomes a true no-op (same elements, already
 * hidden, nothing left for the MutationObserver to catch) — both paths are
 * written to coexist without conflict.
 *
 * The `@JavascriptInterface` credential bridge from the previous, narrower
 * design (a scoped `/call` page reading a signed-in SIP account) is kept —
 * harmless if the page's own JS never calls it, ready if opencall.space
 * ever wants device-stored credentials — but PART 1's own fetch of the live
 * site shows it already generates its own OCP identity client-side with no
 * sign-in gate, so nothing here blocks on [AccountStore] having a SIP
 * account the way the old design did.
 */
class OcpCallWebView(private val activity: AppCompatActivity) {

    companion object {
        const val HOME_URL = "https://opencall.space/"
    }

    // PART 2B: set by [resolveLoadUrl] on every (re)load — whether the URL
    // we asked for actually carried the app-mode query string, so
    // [injectAppModeSafetyNet] only ever runs when it's meaningful.
    private var appModeActive = false

    inner class OcpCredentialBridge(private val username: String, private val password: String) {
        @JavascriptInterface
        fun getUsername(): String = username
        @JavascriptInterface
        fun getPassword(): String = password
    }

    // PART 0: same Activity-alive guard as every other permission-result
    // path in this app — see PhoneTabController's own doc for why.
    private val recordAudioLauncher = activity.registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!activity.isFinishing && !activity.isDestroyed) {
            pendingWebView?.let { if (granted) grantAudioTo(it) }
        }
    }
    private var pendingWebView: WebView? = null
    private var pendingPermissionRequest: PermissionRequest? = null

    private var webViewRef: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun build(onLoadFailed: () -> Unit, onLoadSucceeded: () -> Unit): WebView {
        val webView = WebView(activity)
        webViewRef = webView
        webView.settings.javaScriptEnabled = true
        // PART 1.2: WebRTC's getUserMedia needs a user-gesture-free media
        // element AND DOM storage (JsSIP/the calling UI's own state).
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.domStorageEnabled = true

        val account = AccountStore.get(activity)
        val username = account.sipUsername
        val password = username?.let { AccountStore.decryptSipPassword(activity) }
        if (username != null && password != null) {
            webView.addJavascriptInterface(OcpCredentialBridge(username, password), "OcpNativeBridge")
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                val wantsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                if (!wantsAudio) { request.deny(); return }
                if (hasRecordAudioPermission()) {
                    activity.runOnUiThread { request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) }
                } else {
                    pendingWebView = webView
                    pendingPermissionRequest = request
                    // PART 4.3: a rationale first — the page just asked for
                    // the mic (the user tapped call inside it), so this is
                    // already in context, but the system dialog itself only
                    // appears after "Continue" here.
                    activity.runOnUiThread {
                        androidx.appcompat.app.AlertDialog.Builder(activity)
                            .setTitle("Microphone access")
                            .setMessage("OpenCall needs the microphone to place this call.")
                            .setPositiveButton("Continue") { _, _ ->
                                recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                            .setNegativeButton("Not now") { _, _ -> request.deny() }
                            .show()
                    }
                }
            }
        }

        // PART 1.3: never the platform's own browser error page — report
        // failure natively instead. onReceivedError fires for the main
        // frame AND for sub-resources (images, scripts); only a main-frame
        // failure on the URL we actually asked for counts as "the page
        // can't load" — a failed sub-resource (an ad blocker'd tracker, a
        // flaky third-party script) must not blank the whole call screen.
        webView.webViewClient = object : WebViewClient() {
            private var mainFrameFailed = false

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                mainFrameFailed = false
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    mainFrameFailed = true
                    onLoadFailed()
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: android.webkit.WebResourceResponse
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request.isForMainFrame) {
                    mainFrameFailed = true
                    onLoadFailed()
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                // Never silently accept a bad cert — the platform default
                // (cancel) is correct; report it as a native load failure
                // too rather than leaving the WebView on a blank page.
                handler.cancel()
                mainFrameFailed = true
                onLoadFailed()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                if (!mainFrameFailed) {
                    if (appModeActive) injectAppModeSafetyNet(view)
                    onLoadSucceeded()
                }
            }
        }

        if (isOnline()) {
            webView.loadUrl(resolveLoadUrl())
        } else {
            onLoadFailed()
        }
        return webView
    }

    /** PART 2B.2: [HOME_URL] plus a native, load-time-only app-mode marker —
     *  `?app=1&name=...&number=...` built fresh from [AccountStore] on every
     *  call. This is a one-shot query string built before the page loads,
     *  nothing runs after — not a [android.webkit.JavascriptInterface]
     *  bridge. The PWA's own JS (index.html's `initFlow()`) reads it once,
     *  registers straight into the dialer with these values, and never
     *  shows its own name/number form. Falls back to plain [HOME_URL]
     *  (ordinary browser mode, registration form included) if there's no
     *  verified SIM number to pass yet — shouldn't happen in practice since
     *  Tab 1 is only reachable after setup completes, but this must never
     *  crash or blank the tab if it somehow does. */
    private fun resolveLoadUrl(): String {
        val account = AccountStore.get(activity)
        val number = account.simNumber
        appModeActive = number != null
        return if (number != null) {
            "$HOME_URL?app=1&name=${Uri.encode(account.displayName)}&number=${Uri.encode(number)}"
        } else {
            HOME_URL
        }
    }

    /** PART 2B-FIX: this is now the ONLY suppression in play — opencall.space
     *  has not been redeployed with the local PWA's app-embed branch, so
     *  none of that (register-form-card, dialer-identity-card, etc.) exists
     *  on the live DOM; confirmed by fetching https://opencall.space/
     *  directly and diffing (see the batch report — that's the diagnosis
     *  2B-F.1 asked for). This targets ONLY ids/classes ALREADY present on
     *  the live, unmodified site today — #screen-register, #screen-dialer,
     *  .app-container, .card, .app-header, #ocp-address-display,
     *  #ocp-addr-dialer, #install-hint-card — the same anchors the site's
     *  own onclick handlers already depend on, so they can't vanish without
     *  the site breaking itself. Nothing here is an id/class this app
     *  invented.
     *
     *  Runs via MutationObserver (2B-F.3), not once — installed on first
     *  run per document, re-applied on every mutation so anything the PWA's
     *  own client JS re-renders stays hidden. A fresh onPageFinished (a
     *  real navigation, e.g. answer.html/relay.html) gets a fresh JS
     *  context and reinstalls from scratch automatically.
     *
     *  Also auto-advances past #screen-register (2B-F.5): fills the site's
     *  OWN #input-name/#input-number from the SAME name/number
     *  [resolveLoadUrl] already put in the URL's query string, then clicks
     *  the site's OWN #btn-claim — the exact same localStorage-write +
     *  initSignalServer() path a real user tapping "Claim my number" would
     *  trigger. Only ever fires once per document, only while
     *  #screen-register is actually the visible screen (a returning user
     *  with `claimed` already in localStorage lands straight on
     *  #screen-dialer and this is a no-op for them).
     *
     *  Hides via style.display, never element.remove() (2B-F.4) — the
     *  PWA's own JS still expects every one of these nodes to exist
     *  (getElementById calls throughout, e.g. copyOCPAddress/backupIdentity)
     *  and must never NPE on a node this app deleted out from under it. */
    private fun injectAppModeSafetyNet(view: WebView) {
        android.util.Log.d("OcpCallWebView", "PART 2B-FIX: injectAppModeSafetyNet firing, url=${view.url}")
        val js = """
            (function(){
              if (window.__ocpAppModeInstalled) {
                var again = window.__ocpAppModeApply();
                return JSON.stringify({reinstalled:false, hidden:again, advanced:false});
              }
              window.__ocpAppModeInstalled = true;

              function hide(el){ if (el && el.style) el.style.setProperty('display','none','important'); }

              function findByText(root, tag, needle){
                var els = root.querySelectorAll(tag);
                for (var i = 0; i < els.length; i++) {
                  var t = (els[i].textContent || '').trim();
                  if (t.indexOf(needle) !== -1) return els[i];
                }
                return null;
              }

              function apply(){
                var count = 0;
                var reg = document.getElementById('screen-register');
                if (reg) {
                  var container = reg.querySelector('.app-container');
                  if (container && container.firstElementChild) { hide(container.firstElementChild); count++; } // logo/tagline block
                  var card = reg.querySelector('.card');
                  if (card) { hide(card); count++; } // name/number/signal-server/claim button
                  var addr = document.getElementById('ocp-address-display');
                  if (addr && addr.parentElement && addr.parentElement.parentElement) { hide(addr.parentElement.parentElement); count++; } // OCP address block + copy link
                  var backup = findByText(reg, 'span', 'back up identity');
                  if (backup && backup.parentElement) { hide(backup.parentElement); count++; }
                }
                var header = document.querySelector('.app-header');
                if (header) { hide(header); count++; }
                var dialerAddr = document.getElementById('ocp-addr-dialer');
                if (dialerAddr) { var idCard = dialerAddr.closest('.card'); if (idCard) { hide(idCard); count++; } }
                var installHint = document.getElementById('install-hint-card');
                if (installHint) { hide(installHint); count++; }
                return count;
              }
              window.__ocpAppModeApply = apply;

              function autoAdvance(){
                if (window.__ocpAutoAdvanced) return false;
                var reg = document.getElementById('screen-register');
                if (!reg || getComputedStyle(reg).display === 'none') return false;
                var params = new URLSearchParams(location.search);
                var name = params.get('name');
                var number = params.get('number');
                if (!name || !number) return false;
                var nameInp = document.getElementById('input-name');
                var numInp = document.getElementById('input-number');
                var claimBtn = document.getElementById('btn-claim');
                if (!nameInp || !numInp || !claimBtn) return false;
                nameInp.value = name;
                numInp.value = number;
                window.__ocpAutoAdvanced = true;
                claimBtn.click();
                return true;
              }
              window.__ocpAutoAdvance = autoAdvance;

              var firstCount = apply();
              var advanced = autoAdvance();

              var mo = new MutationObserver(function(){ apply(); autoAdvance(); });
              mo.observe(document.body, {childList:true, subtree:true, attributes:true, attributeFilter:['style','class']});

              return JSON.stringify({reinstalled:true, hidden:firstCount, advanced:advanced});
            })();
        """.trimIndent()
        view.evaluateJavascript(js) { result ->
            android.util.Log.d("OcpCallWebView", "PART 2B-FIX: injectAppModeSafetyNet result=$result")
        }
    }

    /** PART 1.1: "native back handling" — the WebView's own history first,
     *  system back only once it has none. Called from MainActivity's
     *  OnBackPressedCallback while Tab 1 is the visible tab. */
    fun handleBackPressed(): Boolean {
        val webView = webViewRef ?: return false
        if (webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return false
    }

    fun reload() {
        if (isOnline()) webViewRef?.loadUrl(resolveLoadUrl())
    }

    private fun grantAudioTo(webView: WebView) {
        val request = pendingPermissionRequest ?: return
        if (hasRecordAudioPermission()) {
            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        } else {
            request.deny()
        }
        pendingPermissionRequest = null
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** PART 5.3 (prior pass)/1.3: checked before ever loading the page — a
     *  blank/hung WebView is not an acceptable "no internet" state. */
    fun isOnline(): Boolean {
        val cm = ContextCompat.getSystemService(activity, ConnectivityManager::class.java) ?: return false
        val network: Network = cm.activeNetwork ?: return false
        val caps: NetworkCapabilities = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
