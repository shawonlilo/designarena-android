package io.github.designarena.wrapper

import java.net.URI
import java.util.Locale

sealed interface RouteDecision {
    /** Load directly inside the app's WebView. */
    data object LoadInWebView : RouteDecision

    /** Open in Chrome Custom Tabs (OAuth sign-in flows and external HTTPS links). */
    data object OpenCustomTab : RouteDecision

    /** Hand off to Android's external intent chooser (e.g. mailto:, tel:). */
    data object OpenExternalIntent : RouteDecision

    /** Reject insecure or disallowed schemes (http://, javascript:, file://, etc.). */
    data object Block : RouteDecision
}

/**
 * Pure-Kotlin URL allowlist and OAuth routing rules.
 * Kept free of Android framework types so it can be verified with fast JVM unit tests.
 *
 * OAuth note: providers are loaded INSIDE the WebView, not in Chrome Custom Tabs.
 * That is deliberate, and the reason is session continuity, not convenience:
 * Custom Tabs run in Chrome with Chrome's cookie jar, while this app's session
 * lives in the WebView's cookie jar. DesignArena has no deep-link / token-exchange
 * return path (no intent-filter, no server-side code exchange the app can call),
 * so a Google sign-in completed in a Custom Tab would set its session cookie in
 * Chrome and the app WebView would still be logged out when the user came back.
 * Keeping the whole OAuth flow in the one WebView keeps the session cookie where
 * the app can use it. The trade-off is documented in the README: Google
 * discourages OAuth inside embedded WebViews, so this relies on the WebView
 * presenting a Chrome user-agent (see WebViewConfigurator).
 */
object UrlRouter {

    private const val PRIMARY_DOMAIN = "designarena.ai"

    private val OAUTH_HOSTS = setOf(
        "accounts.google.com",
        "appleid.apple.com",
        "login.microsoftonline.com",
        "github.com",
        "auth0.com"
    )

    private val EXTERNAL_INTENT_SCHEMES = setOf("mailto", "tel")

    fun route(rawUrl: String): RouteDecision {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return RouteDecision.Block

        val parsed = runCatching { URI(trimmed) }.getOrNull() ?: return RouteDecision.Block
        val scheme = parsed.scheme?.lowercase(Locale.US) ?: return RouteDecision.Block

        if (scheme in EXTERNAL_INTENT_SCHEMES) {
            return RouteDecision.OpenExternalIntent
        }

        // Strict HTTPS-only guardrail: block cleartext http://, file://, javascript:, content://
        if (scheme != "https") {
            return RouteDecision.Block
        }

        val host = parsed.host?.lowercase(Locale.US)?.trimEnd('.') ?: return RouteDecision.Block
        if (host.isEmpty()) return RouteDecision.Block

        if (isOAuthHost(host)) {
            // OAuth must stay in the WebView so its session cookie lands in the
            // same cookie jar the app uses (see class doc). External links below
            // still go to Custom Tabs.
            return RouteDecision.LoadInWebView
        }

        return if (isAllowedWebViewHost(host)) {
            RouteDecision.LoadInWebView
        } else {
            RouteDecision.OpenCustomTab
        }
    }

    internal fun isAllowedWebViewHost(host: String): Boolean {
        return host == PRIMARY_DOMAIN || host.endsWith(".$PRIMARY_DOMAIN")
    }

    internal fun isOAuthHost(host: String): Boolean {
        return OAUTH_HOSTS.any { oauth ->
            host == oauth || host.endsWith(".$oauth")
        }
    }
}
