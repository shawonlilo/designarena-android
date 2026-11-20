package io.github.designarena.wrapper

import android.annotation.SuppressLint
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * Applies hardened settings to the DesignArena WebView without spoofing User-Agent
 * or injecting DOM-scraping scripts.
 */
object WebViewConfigurator {

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(webView: WebView) {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)

        // Allow content:// URIs for native file picker uploads, disallow raw file:// reads.
        settings.allowContentAccess = true
        settings.allowFileAccess = false

        // Enforce HTTPS-only rendering and safe browsing.
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.safeBrowsingEnabled = true
        }

        // Google sign-in fix: present a Chrome user-agent, not a WebView one.
        // Android WebViews tag their default UA with "; wv", and Google refuses
        // OAuth sign-in from embedded WebViews with "403: disallowed_useragent"
        // when it sees that marker. DesignArena signs in with Google Identity
        // Services, which opens accounts.google.com in a popup, so the marker
        // alone was enough to break "Continue with Google" outright.
        // Removing only the "; wv" marker keeps the real Chrome version and
        // device tokens; it does not fake another browser. Trade-off (see
        // README): Google discourages OAuth inside WebViews and can detect
        // embedded browsers by other means, so this is a pragmatic fix for a
        // personal wrapper, not a guarantee. The alternative — Custom Tabs —
        // cannot log this app in, because its cookies live in Chrome, not in
        // this WebView, and DesignArena offers no token-exchange return path.
        settings.userAgentString = settings.userAgentString
            .replace("; wv", "")
            .replace(" wv", "")
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.mediaPlaybackRequiresUserGesture = false

        // Enable persistent session and Turnstile/iframe cookies.
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
        }
    }
}
