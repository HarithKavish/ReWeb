package com.reweb.browser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Error pages interpolate the failing URL, which is attacker-controlled. If it
 * were not escaped, a crafted link could inject script into a document ReWeb
 * itself rendered.
 */
class ErrorPagesEscapingTest {

    @Test
    fun `all HTML metacharacters are escaped`() {
        assertEquals(
            "&lt;script&gt;alert(1)&lt;/script&gt;",
            ErrorPages.escapeHtml("<script>alert(1)</script>")
        )
        assertEquals("&amp;amp;", ErrorPages.escapeHtml("&amp;"))
        assertEquals("&quot;quoted&quot;", ErrorPages.escapeHtml("\"quoted\""))
        assertEquals("it&#39;s", ErrorPages.escapeHtml("it's"))
    }

    @Test
    fun `an attribute-breaking URL cannot escape its context`() {
        val hostile = "https://example.com/\"><img src=x onerror=alert(1)>"
        val escaped = ErrorPages.escapeHtml(hostile)
        assertFalse(escaped.contains("<img"))
        assertFalse(escaped.contains("\""))
        assertFalse(escaped.contains(">"))
    }

    @Test
    fun `ordinary text is unchanged`() {
        assertEquals("https://example.com/path", ErrorPages.escapeHtml("https://example.com/path"))
        assertEquals("", ErrorPages.escapeHtml(""))
    }
}
