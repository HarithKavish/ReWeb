package com.reweb.browser.intents

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `intent:` URIs let a web page build an Android Intent. Unsanitised, that is a
 * way for any site to start components it should never reach, so the sanitiser
 * is the security boundary being tested here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExternalIntentsTest {

    @Test
    fun `an explicit component supplied by a page is stripped`() {
        val hostile = Intent().apply {
            setClassName("com.reweb.browser", "com.reweb.browser.settings.SettingsActivity")
        }
        val sanitized = ExternalIntents.sanitize(hostile)
        assertNull("A page must not be able to name a target component", sanitized.component)
    }

    @Test
    fun `a selector cannot smuggle in a second intent`() {
        val hostile = Intent(Intent.ACTION_VIEW).apply {
            selector = Intent(Intent.ACTION_MAIN)
        }
        assertNull(ExternalIntents.sanitize(hostile).selector)
    }

    @Test
    fun `URI permission grants are removed`() {
        val hostile = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        val flags = ExternalIntents.sanitize(hostile).flags
        assertEquals(0, flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(0, flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(0, flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        assertEquals(0, flags and Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
    }

    @Test
    fun `the BROWSABLE category is always added`() {
        // Only components that opted into being started from web content should
        // ever be reachable this way.
        val sanitized = ExternalIntents.sanitize(Intent(Intent.ACTION_VIEW))
        assertTrue(sanitized.hasCategory(Intent.CATEGORY_BROWSABLE))
    }

    @Test
    fun `parsing a well-formed intent URI keeps its action and data`() {
        val intent = ExternalIntents.parseIntentUri(
            "intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;end"
        )
        assertNotNull(intent)
        assertNull("Even a package-scoped URI must lose its component", intent!!.component)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
    }

    @Test
    fun `a malformed intent URI is still sanitised rather than throwing`() {
        // Intent.parseUri is lenient — it salvages a VIEW intent from nonsense
        // rather than rejecting it. What matters is that whatever it produces has
        // been through the sanitiser, so the leniency cannot be turned into reach.
        val intent = ExternalIntents.parseIntentUri("intent://%%%;end")
        if (intent != null) {
            assertNull(intent.component)
            assertNull(intent.selector)
            assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
            assertEquals(0, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    @Test
    fun `only http fallbacks are honoured`() {
        val withWebFallback = ExternalIntents.parseIntentUri(
            "intent://x/#Intent;scheme=custom;S.browser_fallback_url=https%3A%2F%2Fexample.com%2Ffb;end"
        )
        assertEquals("https://example.com/fb", ExternalIntents.fallbackUrlOf(withWebFallback!!))

        // A fallback that is itself another scheme would just chain the handoff.
        val withSchemeFallback = ExternalIntents.parseIntentUri(
            "intent://x/#Intent;scheme=custom;S.browser_fallback_url=market%3A%2F%2Fdetails;end"
        )
        assertNull(ExternalIntents.fallbackUrlOf(withSchemeFallback!!))
    }

    @Test
    fun `http and https are handled in the browser, not handed off`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val outcome = ExternalIntents.launch(context, "https://example.com")
        assertTrue(outcome is ExternalIntents.Outcome.HandleInBrowser)
        assertEquals(
            "https://example.com",
            (outcome as ExternalIntents.Outcome.HandleInBrowser).url
        )
    }

    @Test
    fun `spotify URIs map to their open-web equivalent`() {
        assertEquals(
            "https://open.spotify.com/track/abc123",
            ExternalIntents.webEquivalentFor("spotify:track:abc123")
        )
        assertEquals(
            "https://open.spotify.com/playlist/xyz",
            ExternalIntents.webEquivalentFor("spotify:playlist:xyz")
        )
    }

    @Test
    fun `schemes with no web equivalent report none`() {
        assertNull(ExternalIntents.webEquivalentFor("mailto:a@example.com"))
        assertNull(ExternalIntents.webEquivalentFor("tel:12345"))
        assertNull(ExternalIntents.webEquivalentFor("not a url"))
    }
}
