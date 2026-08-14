package com.reweb.browser.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Detection has to be conservative. A false positive interrupts a working page
 * with a prompt the user did not need; a false negative just means the user sees
 * the provider's own "browser not secure" page and can hand off by hand.
 */
class AuthHandoffTest {

    @Test
    fun `google oauth authorization endpoint is detected`() {
        assertTrue(
            AuthHandoff.isOAuthAuthorizationUrl(
                "https://accounts.google.com/o/oauth2/v2/auth" +
                    "?client_id=123.apps.googleusercontent.com" +
                    "&redirect_uri=https%3A%2F%2Fexample.com%2Fcb&response_type=code&scope=email"
            )
        )
    }

    @Test
    fun `github oauth authorization endpoint is detected`() {
        assertTrue(
            AuthHandoff.isOAuthAuthorizationUrl(
                "https://github.com/login/oauth/authorize" +
                    "?client_id=abc&redirect_uri=https://example.com/cb&response_type=code"
            )
        )
    }

    @Test
    fun `microsoft oauth authorization endpoint is detected`() {
        assertTrue(
            AuthHandoff.isOAuthAuthorizationUrl(
                "https://login.microsoftonline.com/common/oauth2/v2.0/authorize" +
                    "?client_id=abc&response_type=code&redirect_uri=https://example.com/cb"
            )
        )
    }

    @Test
    fun `visiting a provider's ordinary pages does not trigger a handoff`() {
        // The whole point of requiring both a path and query markers.
        assertFalse(AuthHandoff.isOAuthAuthorizationUrl("https://github.com"))
        assertFalse(AuthHandoff.isOAuthAuthorizationUrl("https://github.com/torvalds/linux"))
        assertFalse(AuthHandoff.isOAuthAuthorizationUrl("https://accounts.google.com/"))
        assertFalse(AuthHandoff.isOAuthAuthorizationUrl("https://www.facebook.com/somepage"))
    }

    @Test
    fun `unrelated hosts are never treated as OAuth`() {
        assertFalse(
            AuthHandoff.isOAuthAuthorizationUrl(
                "https://example.com/oauth/authorize?client_id=a&response_type=code&redirect_uri=b"
            )
        )
    }

    @Test
    fun `non-http schemes are rejected outright`() {
        assertFalse(AuthHandoff.isOAuthAuthorizationUrl("spotify:track:abc"))
        assertFalse(AuthHandoff.isOAuthAuthorizationUrl(""))
        assertFalse(AuthHandoff.isOAuthAuthorizationUrl("about:blank"))
    }

    @Test
    fun `subdomains of a known provider match`() {
        assertTrue(
            AuthHandoff.isOAuthAuthorizationUrl(
                "https://tenant.auth0.com/authorize?client_id=a&response_type=code&redirect_uri=b"
            )
        )
    }

    @Test
    fun `the callback constant matches the manifest's registered scheme`() {
        // AuthRedirectActivity's intent-filter declares reweb://auth; if these
        // drift apart the redirect silently stops coming back to the app.
        assertTrue(AuthHandoff.CALLBACK_URL.startsWith("${AuthHandoff.CALLBACK_SCHEME}://"))
        assertTrue(AuthHandoff.CALLBACK_URL.endsWith(AuthHandoff.CALLBACK_HOST))
    }
}
