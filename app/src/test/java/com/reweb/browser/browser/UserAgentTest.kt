package com.reweb.browser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserAgentTest {

    /** A real Android 7.0 system WebView user agent. */
    private val legacyWebViewUa =
        "Mozilla/5.0 (Linux; Android 7.0; SM-G930F Build/NRD90M; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/61.0.3163.98 Mobile Safari/537.36"

    @Test
    fun `asBrowser removes the embedded-webview markers and nothing else`() {
        val result = UserAgent.asBrowser(legacyWebViewUa)
        assertFalse("The wv token marks an embedded view", result.contains("wv"))
        assertFalse("Version/4.0 marks an embedded view", result.contains("Version/4.0"))
        // Everything identifying the real device and engine must survive.
        assertTrue(result.contains("Android 7.0"))
        assertTrue(result.contains("SM-G930F"))
        assertTrue(result.contains("Chrome/61.0.3163.98"))
        assertTrue(result.contains("Mobile Safari/537.36"))
        assertEquals(
            "Mozilla/5.0 (Linux; Android 7.0; SM-G930F Build/NRD90M) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/61.0.3163.98 Mobile Safari/537.36",
            result
        )
    }

    @Test
    fun `asBrowser leaves an already-clean user agent alone`() {
        val chrome = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        assertEquals(chrome, UserAgent.asBrowser(chrome))
    }

    @Test
    fun `desktop user agent reports the device's real Chromium version`() {
        // Claiming a newer engine would make sites ship JavaScript this build
        // cannot parse, which fails worse than being served the legacy path.
        val desktop = UserAgent.desktop(legacyWebViewUa)
        assertTrue(desktop.contains("Chrome/61.0.3163.98"))
        assertTrue(desktop.contains("X11; Linux x86_64"))
        assertFalse("Desktop UA must not claim Mobile", desktop.contains("Mobile"))
        assertFalse(desktop.contains("Android"))
    }

    @Test
    fun `desktop falls back to a fixed version when none can be parsed`() {
        val desktop = UserAgent.desktop("something without a version")
        assertTrue(desktop.contains("Chrome/112.0.0.0"))
    }

    @Test
    fun `chrome version parsing`() {
        assertEquals(61, UserAgent.chromeMajorVersion(legacyWebViewUa))
        assertEquals("61.0.3163.98", UserAgent.fullChromeVersion(legacyWebViewUa))
        assertNull(UserAgent.chromeMajorVersion("Mozilla/5.0 (compatible)"))
        assertNull(UserAgent.fullChromeVersion(""))
    }

    @Test
    fun `resolve maps every mode`() {
        val clean = UserAgent.asBrowser(legacyWebViewUa)
        assertEquals(clean, UserAgent.resolve(UserAgentMode.DEFAULT, legacyWebViewUa))
        assertEquals(clean, UserAgent.resolve(UserAgentMode.MOBILE, legacyWebViewUa))
        assertEquals(
            UserAgent.desktop(legacyWebViewUa),
            UserAgent.resolve(UserAgentMode.DESKTOP, legacyWebViewUa)
        )
        assertEquals(
            "My Custom UA",
            UserAgent.resolve(UserAgentMode.CUSTOM, legacyWebViewUa, "My Custom UA")
        )
    }

    @Test
    fun `custom mode with a blank value falls back rather than sending an empty header`() {
        val clean = UserAgent.asBrowser(legacyWebViewUa)
        assertEquals(clean, UserAgent.resolve(UserAgentMode.CUSTOM, legacyWebViewUa, "   "))
        assertEquals(clean, UserAgent.resolve(UserAgentMode.CUSTOM, legacyWebViewUa, null))
    }

    @Test
    fun `mode names parse case-insensitively and default safely`() {
        assertEquals(UserAgentMode.DESKTOP, UserAgentMode.fromName("desktop"))
        assertEquals(UserAgentMode.DESKTOP, UserAgentMode.fromName("DESKTOP"))
        assertEquals(UserAgentMode.DEFAULT, UserAgentMode.fromName("nonsense"))
        assertEquals(UserAgentMode.DEFAULT, UserAgentMode.fromName(null))
    }

    @Test
    fun `isMobileUserAgent`() {
        assertTrue(UserAgent.isMobileUserAgent(legacyWebViewUa))
        assertFalse(UserAgent.isMobileUserAgent(UserAgent.desktop(legacyWebViewUa)))
    }
}
