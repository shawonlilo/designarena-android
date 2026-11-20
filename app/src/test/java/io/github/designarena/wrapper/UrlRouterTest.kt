package io.github.designarena.wrapper

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlRouterTest {

    @Test
    fun allowsPrimaryAndSubdomainsOverHttps() {
        assertEquals(RouteDecision.LoadInWebView, UrlRouter.route("https://designarena.ai/"))
        assertEquals(RouteDecision.LoadInWebView, UrlRouter.route("https://www.designarena.ai/arena"))
        assertEquals(RouteDecision.LoadInWebView, UrlRouter.route("https://cdn.designarena.ai/app.js"))
    }

    @Test
    fun blocksCleartextAndDangerousSchemes() {
        assertEquals(RouteDecision.Block, UrlRouter.route("http://www.designarena.ai/"))
        assertEquals(RouteDecision.Block, UrlRouter.route("javascript:alert(1)"))
        assertEquals(RouteDecision.Block, UrlRouter.route("file:///etc/passwd"))
        assertEquals(RouteDecision.Block, UrlRouter.route("content://com.example/secret"))
        assertEquals(RouteDecision.Block, UrlRouter.route(""))
    }

    @Test
    fun routesOAuthProvidersToCustomTabs() {
        assertEquals(
            RouteDecision.OpenCustomTab,
            UrlRouter.route("https://accounts.google.com/o/oauth2/v2/auth")
        )
        assertEquals(
            RouteDecision.OpenCustomTab,
            UrlRouter.route("https://github.com/login/oauth/authorize")
        )
        assertEquals(
            RouteDecision.OpenCustomTab,
            UrlRouter.route("https://appleid.apple.com/auth/authorize")
        )
    }

    @Test
    fun routesExternalAndLookalikeDomainsOutsideWebView() {
        assertEquals(RouteDecision.OpenCustomTab, UrlRouter.route("https://evil-designarena.ai/"))
        assertEquals(RouteDecision.OpenCustomTab, UrlRouter.route("https://designarena.ai.attacker.com/"))
        assertEquals(RouteDecision.OpenCustomTab, UrlRouter.route("https://example.com/docs"))
    }

    @Test
    fun routesMailtoAndTelToExternalIntent() {
        assertEquals(RouteDecision.OpenExternalIntent, UrlRouter.route("mailto:support@designarena.ai"))
        assertEquals(RouteDecision.OpenExternalIntent, UrlRouter.route("tel:+15551234567"))
    }
}
