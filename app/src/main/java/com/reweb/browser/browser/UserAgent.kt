package com.reweb.browser.browser

import java.util.regex.Pattern

enum class UserAgentMode {
    /** Browser default: the device UA, presented as a browser rather than a WebView. */
    DEFAULT,

    /** Explicitly mobile — identical to DEFAULT on a phone, meaningful as an override. */
    MOBILE,

    /** Desktop Chrome on Linux, derived from the device's own Chromium version. */
    DESKTOP,

    /** A string the user typed in Settings. */
    CUSTOM;

    companion object {
        fun fromName(name: String?): UserAgentMode =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: DEFAULT
    }
}

/**
 * Builds user-agent strings.
 *
 * ## Why the default is not the raw WebView UA
 *
 * The system WebView advertises itself with a `; wv` token and a `Version/4.0`
 * marker, e.g.
 *
 * ```
 * Mozilla/5.0 (Linux; Android 7.0; SM-G930F Build/NRD90M; wv)
 *   AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/61.0.3163.98 Mobile Safari/537.36
 * ```
 *
 * Many sites — including several sign-in pages — refuse to serve embedded user
 * agents outright. ReWeb is a browser, not an app embedding a web view for its
 * own content, so removing those two tokens is an accurate self-description
 * rather than a disguise: every other component, including the real Chromium
 * version, is left exactly as the device reports it.
 *
 * This is the only modification made by default. It does not, and cannot, make a
 * provider accept an old Chromium build that it has decided to block, and it is
 * not a way around Google's embedded-user-agent policy — see COMPATIBILITY.md.
 */
object UserAgent {

    private val CHROME_VERSION_PATTERN: Pattern = Pattern.compile("Chrome/(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)")
    private val WEBVIEW_TOKEN_PATTERN: Pattern = Pattern.compile(";\\s*wv\\b")
    private val VERSION_TOKEN_PATTERN: Pattern = Pattern.compile("\\bVersion/\\d+(?:\\.\\d+)*\\s*")

    /** Chromium version used for the desktop UA when the device UA cannot be parsed. */
    private const val FALLBACK_CHROME_VERSION = "112.0.0.0"

    /**
     * Resolves a mode to the string to hand the engine.
     *
     * Returns null only when the engine should keep its own untouched default,
     * which never happens today but keeps that option open for a future engine
     * whose default already looks like a browser.
     */
    fun resolve(
        mode: UserAgentMode,
        deviceUserAgent: String,
        customUserAgent: String? = null
    ): String? = when (mode) {
        UserAgentMode.DEFAULT, UserAgentMode.MOBILE -> asBrowser(deviceUserAgent)
        UserAgentMode.DESKTOP -> desktop(deviceUserAgent)
        UserAgentMode.CUSTOM -> customUserAgent?.trim()?.ifBlank { null } ?: asBrowser(deviceUserAgent)
    }

    /** Removes the `; wv` and `Version/x` tokens that mark an embedded WebView. */
    fun asBrowser(deviceUserAgent: String): String {
        if (deviceUserAgent.isBlank()) return deviceUserAgent
        var ua = WEBVIEW_TOKEN_PATTERN.matcher(deviceUserAgent).replaceAll("")
        ua = VERSION_TOKEN_PATTERN.matcher(ua).replaceAll("")
        return ua.replace(Regex("\\s{2,}"), " ").trim()
    }

    /**
     * Desktop Chrome UA carrying the device's real Chromium version. Claiming a
     * newer Chromium than the device has would make sites serve JavaScript the
     * engine cannot parse, which fails worse than being served the old path.
     */
    fun desktop(deviceUserAgent: String): String {
        val version = fullChromeVersion(deviceUserAgent) ?: FALLBACK_CHROME_VERSION
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$version Safari/537.36"
    }

    /** Major Chromium version of the installed WebView, or null if unknown. */
    fun chromeMajorVersion(userAgent: String): Int? {
        val matcher = CHROME_VERSION_PATTERN.matcher(userAgent)
        if (!matcher.find()) return null
        return matcher.group(1)?.toIntOrNull()
    }

    fun fullChromeVersion(userAgent: String): String? {
        val matcher = CHROME_VERSION_PATTERN.matcher(userAgent)
        if (!matcher.find()) return null
        return matcher.group(0)?.removePrefix("Chrome/")
    }

    fun isMobileUserAgent(userAgent: String): Boolean = userAgent.contains("Mobile", ignoreCase = false)
}
