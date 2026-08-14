package com.reweb.browser.settings

import android.content.Context
import com.reweb.browser.engine.WebPermissionKind

/**
 * Remembers permission answers for the one capability where remembering is
 * correct: protected media playback.
 *
 * ReWeb deliberately does not remember camera, microphone or location. Those
 * grant a site access to the user and their surroundings, and re-asking is a
 * feature.
 *
 * Protected media is a different thing entirely. It grants no access to the user
 * — it lets the page ask the platform's DRM module whether it can decrypt a
 * stream. Re-prompting on every page load is not a safety measure, it is an
 * obstacle, and a practical one: a streaming site requests EME while its player
 * is initialising, and a modal dialog sitting in front of that request long
 * enough will make the player give up and disable itself. That is precisely the
 * failure seen with Spotify on a real device.
 *
 * Answers are per-origin, stored locally, and cleared by
 * *Settings → Privacy → Clear browsing data* along with site storage.
 */
class SitePermissionStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** True when [origin] has previously been granted protected-media playback. */
    fun isProtectedMediaGranted(origin: String): Boolean {
        val key = keyFor(origin) ?: return false
        return prefs.getBoolean(key, false)
    }

    fun rememberProtectedMedia(origin: String, granted: Boolean) {
        val key = keyFor(origin) ?: return
        // Only a grant is worth remembering. Persisting a denial would silently
        // refuse the site forever with no way for the user to notice or undo it.
        if (granted) prefs.edit().putBoolean(key, true).apply()
        else prefs.edit().remove(key).apply()
    }

    fun grantedOrigins(): List<String> = prefs.all.keys
        .filter { it.startsWith(PROTECTED_MEDIA_PREFIX) }
        .map { it.removePrefix(PROTECTED_MEDIA_PREFIX) }
        .sorted()

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun keyFor(origin: String): String? {
        val normalized = normalizeOrigin(origin) ?: return null
        return PROTECTED_MEDIA_PREFIX + normalized
    }

    companion object {
        const val PREFS_NAME = "reweb_site_permissions"
        private const val PROTECTED_MEDIA_PREFIX = "drm:"

        /** Only this kind is ever remembered; see the class documentation. */
        fun isRememberable(kind: WebPermissionKind): Boolean =
            kind == WebPermissionKind.PROTECTED_MEDIA

        /**
         * Keeps the full scheme+host origin, unlike per-site user agents which key
         * on host alone. A permission granted to https://example.com must not
         * apply to http://example.com.
         */
        fun normalizeOrigin(origin: String): String? {
            val trimmed = origin.trim().removeSuffix("/")
            if (trimmed.isBlank()) return null
            if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) return null
            return trimmed.lowercase()
        }
    }
}
