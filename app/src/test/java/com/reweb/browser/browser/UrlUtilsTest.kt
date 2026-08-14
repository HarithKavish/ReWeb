package com.reweb.browser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The address bar's classification rules. These are the behaviours a user
 * notices immediately when they are wrong: a search that navigates to a bogus
 * host, or a host that turns into a search.
 */
class UrlUtilsTest {

    private fun navigatesTo(input: String): String {
        val intent = UrlUtils.classify(input)
        assertTrue("Expected \"$input\" to be treated as a URL, got $intent", intent is UrlUtils.Intent.Navigate)
        return (intent as UrlUtils.Intent.Navigate).url
    }

    private fun searchesFor(input: String): String {
        val intent = UrlUtils.classify(input)
        assertTrue("Expected \"$input\" to be treated as a search, got $intent", intent is UrlUtils.Intent.Search)
        return (intent as UrlUtils.Intent.Search).query
    }

    @Test
    fun `explicit schemes navigate unchanged`() {
        assertEquals("https://example.com", navigatesTo("https://example.com"))
        assertEquals("http://example.com/a?b=c", navigatesTo("http://example.com/a?b=c"))
        assertEquals("about:blank", navigatesTo("about:blank"))
    }

    @Test
    fun `bare hostnames get an https scheme`() {
        assertEquals("https://example.com", navigatesTo("example.com"))
        assertEquals("https://www.example.com", navigatesTo("www.example.com"))
        assertEquals("https://sub.domain.example.co.uk/path", navigatesTo("sub.domain.example.co.uk/path"))
    }

    @Test
    fun `localhost and IP addresses default to http`() {
        // These are almost always development servers with no certificate.
        assertEquals("http://localhost:8080", navigatesTo("localhost:8080"))
        assertEquals("http://localhost", navigatesTo("localhost"))
        assertEquals("http://192.168.1.1", navigatesTo("192.168.1.1"))
        assertEquals("http://127.0.0.1:3000/admin", navigatesTo("127.0.0.1:3000/admin"))
    }

    @Test
    fun `a hostname with a port is not mistaken for a scheme`() {
        // "example.com:8080" matches the shape of "scheme:rest". Treating the host
        // as an unknown scheme sends the user to a search instead of the site.
        assertEquals("https://example.com:8080", navigatesTo("example.com:8080"))
        assertEquals("https://example.com:8080/path", navigatesTo("example.com:8080/path"))
    }

    @Test
    fun `anything containing whitespace is a search`() {
        assertEquals("how to fix a tap", searchesFor("how to fix a tap"))
        assertEquals("example.com is down", searchesFor("example.com is down"))
    }

    @Test
    fun `plain words are searches, not hosts`() {
        assertEquals("weather", searchesFor("weather"))
        assertEquals("chatgpt", searchesFor("chatgpt"))
    }

    @Test
    fun `a trailing dot-word that is not a TLD still searches`() {
        // Single-character TLDs do not exist, so this cannot be a host.
        assertEquals("version1.x", searchesFor("version1.x"))
    }

    @Test
    fun `javascript URLs are never navigated to from the address bar`() {
        // Pasting a javascript: URL into the address bar is a self-XSS vector, so
        // it must fall through to search rather than execute.
        val input = "javascript:alert(document.cookie)"
        assertEquals(input, searchesFor(input))
    }

    @Test
    fun `unknown schemes fall through to search`() {
        assertEquals("htp://typo.example", searchesFor("htp://typo.example"))
    }

    @Test
    fun `known external schemes are navigable so they can be handed off`() {
        assertEquals("mailto:a@example.com", navigatesTo("mailto:a@example.com"))
        assertEquals("tel:+441234567890", navigatesTo("tel:+441234567890"))
        assertEquals("spotify:track:abc123", navigatesTo("spotify:track:abc123"))
    }

    @Test
    fun `empty input is a no-op search`() {
        assertEquals("", searchesFor(""))
        assertEquals("", searchesFor("   "))
    }

    @Test
    fun `input is trimmed before classification`() {
        assertEquals("https://example.com", navigatesTo("  example.com  "))
    }

    @Test
    fun `isExternalScheme distinguishes app schemes from web ones`() {
        assertTrue(UrlUtils.isExternalScheme("mailto:a@example.com"))
        assertTrue(UrlUtils.isExternalScheme("intent://scan#Intent;scheme=zxing;end"))
        assertTrue(UrlUtils.isExternalScheme("spotify:track:abc"))
        assertFalse(UrlUtils.isExternalScheme("https://example.com"))
        assertFalse(UrlUtils.isExternalScheme("http://example.com"))
        assertFalse(UrlUtils.isExternalScheme("about:blank"))
    }

    @Test
    fun `hostOf strips www and lowercases`() {
        assertEquals("example.com", UrlUtils.hostOf("https://www.Example.com/path"))
        assertEquals("example.com", UrlUtils.hostOf("http://example.com"))
        assertEquals("sub.example.com", UrlUtils.hostOf("https://sub.example.com:8443/x"))
    }

    @Test
    fun `hostOf survives URLs that java-net-URI rejects`() {
        // WebViews accept characters java.net.URI refuses; losing the host here
        // would silently disable per-site settings for those pages.
        assertEquals("example.com", UrlUtils.hostOf("https://example.com/path with spaces"))
        assertEquals("example.com", UrlUtils.hostOf("https://user:pass@example.com/x"))
    }

    @Test
    fun `hostOf returns null when there is no host`() {
        assertNull(UrlUtils.hostOf("about:blank"))
        assertNull(UrlUtils.hostOf(""))
    }

    @Test
    fun `displayUrl hides the scheme only for plain https`() {
        assertEquals("example.com", UrlUtils.displayUrl("https://www.example.com/"))
        assertEquals("example.com/path", UrlUtils.displayUrl("https://example.com/path"))
        // http:// must stay visible so the user can see the page is not encrypted.
        assertEquals("http://example.com", UrlUtils.displayUrl("http://example.com"))
        assertEquals("", UrlUtils.displayUrl("about:blank"))
        assertEquals("", UrlUtils.displayUrl(null))
    }

    @Test
    fun `resolve turns non-URLs into search URLs`() {
        val resolved = UrlUtils.resolve("hello world", SearchEngine.GOOGLE)
        assertEquals("https://www.google.com/search?q=hello%20world", resolved)
    }
}
