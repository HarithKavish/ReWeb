package com.reweb.browser.settings

import android.content.Context
import com.reweb.browser.browser.UrlUtils
import com.reweb.browser.browser.UserAgentMode

data class SiteSetting(
    val host: String,
    val userAgentMode: UserAgentMode
)

/**
 * Per-site overrides, keyed by registrable host with any `www.` prefix removed
 * so that `chatgpt.com` and `www.chatgpt.com` share one entry.
 *
 * Only the user agent is overridable today. The store is written so that adding
 * a second per-site knob later does not change its shape.
 */
class SiteSettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The override for [url]'s host, or null if the site follows the global default. */
    fun userAgentModeForUrl(url: String?): UserAgentMode? {
        val host = url?.let { UrlUtils.hostOf(it) } ?: return null
        return userAgentModeForHost(host)
    }

    fun userAgentModeForHost(host: String): UserAgentMode? {
        val stored = prefs.getString(key(host), null) ?: return null
        return UserAgentMode.fromName(stored)
    }

    fun setUserAgentMode(host: String, mode: UserAgentMode?) {
        val normalized = normalizeHost(host)
        if (normalized.isBlank()) return
        val editor = prefs.edit()
        if (mode == null) editor.remove(key(normalized)) else editor.putString(key(normalized), mode.name)
        editor.apply()
    }

    fun setUserAgentModeForUrl(url: String, mode: UserAgentMode?) {
        val host = UrlUtils.hostOf(url) ?: return
        setUserAgentMode(host, mode)
    }

    fun all(): List<SiteSetting> = prefs.all.entries
        .mapNotNull { (key, value) ->
            if (!key.startsWith(UA_PREFIX)) return@mapNotNull null
            val host = key.removePrefix(UA_PREFIX)
            SiteSetting(host, UserAgentMode.fromName(value as? String))
        }
        .sortedBy { it.host }

    fun remove(host: String) = setUserAgentMode(host, null)

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun key(host: String) = UA_PREFIX + normalizeHost(host)

    companion object {
        const val PREFS_NAME = "reweb_site_settings"
        private const val UA_PREFIX = "ua:"

        fun normalizeHost(host: String): String =
            host.trim().lowercase().removePrefix("www.")
    }
}
