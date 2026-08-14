package com.reweb.browser.browser

import java.net.URLEncoder
import java.util.Locale
import java.util.regex.Pattern

/**
 * Decides whether something the user typed is a URL or a search query, and
 * normalises URLs for display.
 *
 * The rules are the ones a user actually expects from an address bar:
 *   "example.com"        -> https://example.com
 *   "www.example.com"    -> https://www.example.com
 *   "http://example.com" -> unchanged
 *   "localhost:8080"     -> http://localhost:8080
 *   "192.168.1.1"        -> http://192.168.1.1
 *   "how to fix a tap"   -> search
 *   "define: recursion"  -> search (space present)
 */
object UrlUtils {

    /** Schemes ReWeb will navigate to directly from the address bar. */
    private val NAVIGABLE_SCHEMES = setOf("http", "https", "about", "data", "file", "content", "blob")

    /**
     * Schemes that belong to another app. These are recognised so the browser can
     * hand them off rather than trying (and failing) to render them.
     */
    val EXTERNAL_SCHEMES = setOf(
        "mailto", "tel", "sms", "smsto", "mms", "geo", "market",
        "intent", "spotify", "whatsapp", "tg", "maps", "callto", "bitcoin", "magnet"
    )

    private val SCHEME_PATTERN: Pattern =
        Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:")

    /**
     * Host that looks routable: labels separated by dots ending in an alphabetic
     * TLD of two or more characters, optionally with port and path.
     */
    private val HOSTNAME_PATTERN: Pattern = Pattern.compile(
        "^(?:[\\p{L}\\p{N}][\\p{L}\\p{N}_-]{0,62}\\.)+[\\p{L}]{2,63}\\.?(?::\\d{1,5})?(?:[/?#].*)?$"
    )

    private val IPV4_PATTERN: Pattern = Pattern.compile(
        "^(?:(?:25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)\\.){3}" +
            "(?:25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)(?::\\d{1,5})?(?:[/?#].*)?$"
    )

    private val LOCALHOST_PATTERN: Pattern =
        Pattern.compile("^localhost(?::\\d{1,5})?(?:[/?#].*)?$", Pattern.CASE_INSENSITIVE)

    /** What the address bar should do with a given input. */
    sealed class Intent {
        data class Navigate(val url: String) : Intent()
        data class Search(val query: String) : Intent()
    }

    /**
     * Classifies raw address-bar input.
     *
     * A scheme that is neither navigable nor a known external scheme (for example
     * a typo like "htp://x") falls through to search, which is what the user
     * almost certainly wanted.
     */
    fun classify(rawInput: String): Intent {
        val input = rawInput.trim()
        if (input.isEmpty()) return Intent.Search("")

        // A recognised scheme settles it immediately.
        val scheme = schemeOf(input)
        if (scheme != null && (scheme in NAVIGABLE_SCHEMES || scheme in EXTERNAL_SCHEMES)) {
            return Intent.Navigate(input)
        }

        // An *unrecognised* scheme is not decisive, because "localhost:8080" and
        // "example.com:8080" both look like one. Fall through to host detection
        // and only give up afterwards. "javascript:" survives that fall-through as
        // a search, which is deliberate: navigating to a pasted javascript: URL is
        // a classic self-XSS vector.

        // Any whitespace means it cannot be a bare host.
        if (input.any { it.isWhitespace() }) return Intent.Search(input)

        if (LOCALHOST_PATTERN.matcher(input).matches()) return Intent.Navigate("http://$input")
        if (IPV4_PATTERN.matcher(input).matches()) return Intent.Navigate("http://$input")
        if (HOSTNAME_PATTERN.matcher(input).matches()) return Intent.Navigate("https://$input")

        return Intent.Search(input)
    }

    /** Convenience wrapper: resolves input to a loadable URL using [searchEngine]. */
    fun resolve(rawInput: String, searchEngine: SearchEngine): String =
        when (val intent = classify(rawInput)) {
            is Intent.Navigate -> intent.url
            is Intent.Search -> searchEngine.buildSearchUrl(intent.query)
        }

    fun isNavigable(url: String): Boolean = classify(url) is Intent.Navigate

    fun schemeOf(url: String): String? {
        val matcher = SCHEME_PATTERN.matcher(url.trim())
        if (!matcher.find()) return null
        return url.trim().substring(0, matcher.end() - 1).lowercase(Locale.US)
    }

    fun isHttpScheme(url: String): Boolean = schemeOf(url) in setOf("http", "https")

    fun isExternalScheme(url: String): Boolean {
        val scheme = schemeOf(url) ?: return false
        return scheme !in NAVIGABLE_SCHEMES
    }

    /** Host without a leading "www.", or null if [url] has no host. */
    fun hostOf(url: String): String? {
        return try {
            val host = java.net.URI(url).host ?: return null
            host.removePrefix("www.").lowercase(Locale.US).ifEmpty { null }
        } catch (_: Exception) {
            // URI is strict about characters the WebView tolerates; fall back to a
            // manual parse rather than losing the host entirely.
            manualHost(url)
        }
    }

    private fun manualHost(url: String): String? {
        val withoutScheme = url.substringAfter("://", url)
        val authority = withoutScheme.substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('@')
            .substringBefore(':')
        if (authority.isBlank()) return null
        return authority.removePrefix("www.").lowercase(Locale.US)
    }

    /**
     * Shortened form for the address bar: scheme and "www." hidden for ordinary
     * https pages, everything shown otherwise so that http:// stays visible.
     */
    fun displayUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        if (url == "about:blank") return ""
        if (!url.startsWith("https://")) return url
        val trimmed = url.removePrefix("https://").removePrefix("www.")
        return trimmed.removeSuffix("/").ifEmpty { url }
    }

    fun encodeQuery(query: String): String =
        URLEncoder.encode(query, "UTF-8").replace("+", "%20")
}

/**
 * A configurable search provider. [template] must contain the `%s` placeholder,
 * which is replaced with the percent-encoded query.
 */
data class SearchEngine(
    val id: String,
    val displayName: String,
    val template: String
) {
    fun buildSearchUrl(query: String): String =
        template.replace("%s", UrlUtils.encodeQuery(query))

    companion object {
        val GOOGLE = SearchEngine("google", "Google", "https://www.google.com/search?q=%s")
        val DUCKDUCKGO = SearchEngine("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=%s")
        val BING = SearchEngine("bing", "Bing", "https://www.bing.com/search?q=%s")
        val WIKIPEDIA = SearchEngine("wikipedia", "Wikipedia", "https://en.wikipedia.org/w/index.php?search=%s")

        /** Search engine used when the user has not chosen one. */
        val DEFAULT = GOOGLE

        val BUILT_IN = listOf(GOOGLE, DUCKDUCKGO, BING, WIKIPEDIA)

        const val CUSTOM_ID = "custom"

        fun byId(id: String?): SearchEngine? = BUILT_IN.firstOrNull { it.id == id }

        /** Builds a user-defined engine; returns null if the template is unusable. */
        fun custom(template: String): SearchEngine? {
            val trimmed = template.trim()
            if (!trimmed.contains("%s")) return null
            if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return null
            return SearchEngine(CUSTOM_ID, "Custom", trimmed)
        }
    }
}
