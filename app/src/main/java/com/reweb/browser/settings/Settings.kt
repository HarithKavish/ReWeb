package com.reweb.browser.settings

import android.content.Context
import android.content.SharedPreferences
import com.reweb.browser.browser.SearchEngine
import com.reweb.browser.browser.UserAgentMode

/**
 * Application preferences, stored in a single SharedPreferences file.
 *
 * Everything here is a small scalar, so SharedPreferences is the right tool; it
 * is already loaded in memory and costs nothing at read time, which matters on
 * the low-end devices this app targets.
 */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- General ---

    var homePage: String
        get() = prefs.getString(KEY_HOME_PAGE, HOME_NATIVE) ?: HOME_NATIVE
        set(value) = prefs.edit().putString(KEY_HOME_PAGE, value.trim()).apply()

    /** True when the home page is ReWeb's own native start screen rather than a site. */
    val usesNativeHomePage: Boolean
        get() = homePage == HOME_NATIVE || homePage.isBlank()

    var searchEngineId: String
        get() = prefs.getString(KEY_SEARCH_ENGINE, SearchEngine.DEFAULT.id) ?: SearchEngine.DEFAULT.id
        set(value) = prefs.edit().putString(KEY_SEARCH_ENGINE, value).apply()

    var customSearchTemplate: String?
        get() = prefs.getString(KEY_CUSTOM_SEARCH_TEMPLATE, null)
        set(value) = prefs.edit().putString(KEY_CUSTOM_SEARCH_TEMPLATE, value).apply()

    /** Resolves the configured provider, falling back to the default if unusable. */
    fun searchEngine(): SearchEngine {
        val id = searchEngineId
        if (id == SearchEngine.CUSTOM_ID) {
            return SearchEngine.custom(customSearchTemplate.orEmpty()) ?: SearchEngine.DEFAULT
        }
        return SearchEngine.byId(id) ?: SearchEngine.DEFAULT
    }

    var javaScriptEnabled: Boolean
        get() = prefs.getBoolean(KEY_JAVASCRIPT, true)
        set(value) = prefs.edit().putBoolean(KEY_JAVASCRIPT, value).apply()

    var loadImages: Boolean
        get() = prefs.getBoolean(KEY_LOAD_IMAGES, true)
        set(value) = prefs.edit().putBoolean(KEY_LOAD_IMAGES, value).apply()

    var allowPopups: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_POPUPS, true)
        set(value) = prefs.edit().putBoolean(KEY_ALLOW_POPUPS, value).apply()

    var textZoomPercent: Int
        get() = prefs.getInt(KEY_TEXT_ZOOM, 100).coerceIn(MIN_TEXT_ZOOM, MAX_TEXT_ZOOM)
        set(value) = prefs.edit().putInt(KEY_TEXT_ZOOM, value.coerceIn(MIN_TEXT_ZOOM, MAX_TEXT_ZOOM)).apply()

    // --- Compatibility ---

    var defaultUserAgentMode: UserAgentMode
        get() = UserAgentMode.fromName(prefs.getString(KEY_USER_AGENT_MODE, UserAgentMode.DEFAULT.name))
        set(value) = prefs.edit().putString(KEY_USER_AGENT_MODE, value.name).apply()

    var customUserAgent: String?
        get() = prefs.getString(KEY_CUSTOM_USER_AGENT, null)
        set(value) = prefs.edit().putString(KEY_CUSTOM_USER_AGENT, value).apply()

    /** Show the "your WebView is old" notice; dismissible, so it is remembered. */
    var showsCompatibilityWarning: Boolean
        get() = prefs.getBoolean(KEY_SHOW_COMPAT_WARNING, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_COMPAT_WARNING, value).apply()

    // --- Privacy ---

    /** Offer to hand OAuth sign-in pages to a real browser. See auth/AuthHandoff. */
    var oauthHandoffEnabled: Boolean
        get() = prefs.getBoolean(KEY_OAUTH_HANDOFF, true)
        set(value) = prefs.edit().putBoolean(KEY_OAUTH_HANDOFF, value).apply()

    var clearOnExit: Boolean
        get() = prefs.getBoolean(KEY_CLEAR_ON_EXIT, false)
        set(value) = prefs.edit().putBoolean(KEY_CLEAR_ON_EXIT, value).apply()

    // --- Downloads ---

    /**
     * Relative directory inside the public Downloads collection. Empty means the
     * root of Downloads. ReWeb never writes outside that collection.
     */
    var downloadSubdirectory: String
        get() = prefs.getString(KEY_DOWNLOAD_SUBDIR, DEFAULT_DOWNLOAD_SUBDIR) ?: DEFAULT_DOWNLOAD_SUBDIR
        set(value) = prefs.edit().putString(KEY_DOWNLOAD_SUBDIR, sanitizeSubdirectory(value)).apply()

    var askBeforeDownloading: Boolean
        get() = prefs.getBoolean(KEY_ASK_BEFORE_DOWNLOAD, true)
        set(value) = prefs.edit().putBoolean(KEY_ASK_BEFORE_DOWNLOAD, value).apply()

    // --- Session restore ---

    var lastSessionUrls: List<String>
        get() = prefs.getString(KEY_LAST_SESSION, null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        set(value) = prefs.edit()
            .putString(KEY_LAST_SESSION, value.take(MAX_RESTORED_TABS).joinToString("\n"))
            .apply()

    companion object {
        const val PREFS_NAME = "reweb_settings"

        /** Sentinel meaning "use the built-in start screen". */
        const val HOME_NATIVE = "reweb://home"

        const val MIN_TEXT_ZOOM = 50
        const val MAX_TEXT_ZOOM = 200
        const val MAX_RESTORED_TABS = 20
        const val DEFAULT_DOWNLOAD_SUBDIR = "ReWeb"

        private const val KEY_HOME_PAGE = "home_page"
        private const val KEY_SEARCH_ENGINE = "search_engine"
        private const val KEY_CUSTOM_SEARCH_TEMPLATE = "custom_search_template"
        private const val KEY_JAVASCRIPT = "javascript_enabled"
        private const val KEY_LOAD_IMAGES = "load_images"
        private const val KEY_ALLOW_POPUPS = "allow_popups"
        private const val KEY_TEXT_ZOOM = "text_zoom"
        private const val KEY_USER_AGENT_MODE = "user_agent_mode"
        private const val KEY_CUSTOM_USER_AGENT = "custom_user_agent"
        private const val KEY_SHOW_COMPAT_WARNING = "show_compat_warning"
        private const val KEY_OAUTH_HANDOFF = "oauth_handoff"
        private const val KEY_CLEAR_ON_EXIT = "clear_on_exit"
        private const val KEY_DOWNLOAD_SUBDIR = "download_subdir"
        private const val KEY_ASK_BEFORE_DOWNLOAD = "ask_before_download"
        private const val KEY_LAST_SESSION = "last_session"

        /**
         * Keeps a user-supplied folder name inside Downloads. Path separators and
         * parent references are stripped so the value can never escape the
         * collection it is joined onto.
         */
        fun sanitizeSubdirectory(raw: String): String =
            raw.trim()
                .replace('\\', '/')
                .split('/')
                .filter { it.isNotBlank() && it != "." && it != ".." }
                .joinToString("/") { segment -> segment.filter { it.isLetterOrDigit() || it in "-_ " }.trim() }
                .filter { it.isLetterOrDigit() || it in "-_ /" }
                .trim('/')
    }
}
