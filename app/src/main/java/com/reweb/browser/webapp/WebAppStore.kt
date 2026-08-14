package com.reweb.browser.webapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.reweb.browser.browser.UrlUtils
import com.reweb.browser.browser.UserAgentMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * A website the user chose to "install".
 *
 * @param themeColor ARGB colour parsed from the site's theme-color meta tag, or
 *   null when the site does not publish one.
 */
data class WebAppProfile(
    val id: String,
    val name: String,
    val url: String,
    val iconFileName: String?,
    val themeColor: Int?,
    val userAgentMode: UserAgentMode,
    val createdAt: Long,
    val lastUsedAt: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_ID, id)
        put(KEY_NAME, name)
        put(KEY_URL, url)
        put(KEY_ICON, iconFileName ?: JSONObject.NULL)
        put(KEY_THEME_COLOR, themeColor ?: JSONObject.NULL)
        put(KEY_UA_MODE, userAgentMode.name)
        put(KEY_CREATED_AT, createdAt)
        put(KEY_LAST_USED_AT, lastUsedAt)
    }

    companion object {
        private const val KEY_ID = "id"
        private const val KEY_NAME = "name"
        private const val KEY_URL = "url"
        private const val KEY_ICON = "icon"
        private const val KEY_THEME_COLOR = "themeColor"
        private const val KEY_UA_MODE = "userAgentMode"
        private const val KEY_CREATED_AT = "createdAt"
        private const val KEY_LAST_USED_AT = "lastUsedAt"

        fun fromJson(json: JSONObject): WebAppProfile? {
            val id = json.optString(KEY_ID).ifBlank { return null }
            val url = json.optString(KEY_URL).ifBlank { return null }
            if (!isInstallable(url)) return null
            return WebAppProfile(
                id = id,
                name = json.optString(KEY_NAME).ifBlank { UrlUtils.hostOf(url) ?: url },
                url = url,
                iconFileName = json.optString(KEY_ICON).takeIf { it.isNotBlank() && it != "null" },
                themeColor = if (json.isNull(KEY_THEME_COLOR)) null else json.optInt(KEY_THEME_COLOR),
                userAgentMode = UserAgentMode.fromName(json.optString(KEY_UA_MODE)),
                createdAt = json.optLong(KEY_CREATED_AT, System.currentTimeMillis()),
                lastUsedAt = json.optLong(KEY_LAST_USED_AT, 0L)
            )
        }

        /** Only real web origins can be installed; a data: or intent: URL cannot. */
        fun isInstallable(url: String): Boolean {
            val lower = url.trim().lowercase()
            return lower.startsWith("https://") || lower.startsWith("http://")
        }
    }
}

/**
 * Persists installed web apps as a single JSON document plus one PNG per icon.
 *
 * A handful of records with no querying needs does not justify another database
 * table; a whole-file rewrite of a few kilobytes is cheaper than the schema.
 *
 * ## Cookie model
 *
 * Installed web apps share the browser's cookie jar and site storage. That is a
 * deliberate choice, not an oversight: the legacy WebView exposes exactly one
 * cookie jar per process, so a separate one is not available below API 34. The
 * practical consequence is the one users want — signing into a site in the
 * browser leaves you signed in when you launch it as an app, and vice versa. It
 * also means "installed" apps are not a privacy boundary. This is documented in
 * README.md and SECURITY.md.
 */
class WebAppStore(context: Context) {

    private val appContext = context.applicationContext
    private val storeFile = File(appContext.filesDir, FILE_NAME)
    private val iconDir = File(appContext.filesDir, ICON_DIR)

    @Volatile
    private var cache: List<WebAppProfile>? = null

    fun all(): List<WebAppProfile> {
        cache?.let { return it }
        val loaded = load()
        cache = loaded
        return loaded
    }

    fun byId(id: String): WebAppProfile? = all().firstOrNull { it.id == id }

    fun findByUrl(url: String): WebAppProfile? = all().firstOrNull { it.url == url }

    /**
     * Creates a profile. Returns null if [url] is not an installable web origin.
     * An existing profile for the same URL is replaced rather than duplicated.
     */
    fun install(
        name: String,
        url: String,
        icon: Bitmap?,
        themeColor: Int?,
        userAgentMode: UserAgentMode
    ): WebAppProfile? {
        if (!WebAppProfile.isInstallable(url)) return null
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val iconName = icon?.let { saveIcon(id, it) }
        val profile = WebAppProfile(
            id = id,
            name = name.trim().ifBlank { UrlUtils.hostOf(url) ?: url },
            url = url.trim(),
            iconFileName = iconName,
            themeColor = themeColor,
            userAgentMode = userAgentMode,
            createdAt = now,
            lastUsedAt = now
        )
        val current = all()
        // Reinstalling the same URL replaces the old profile; drop its icon so the
        // icon directory does not accumulate orphans.
        current.filter { it.url == profile.url }.forEach { deleteIcon(it.iconFileName) }
        persist(current.filterNot { it.url == profile.url } + profile)
        return profile
    }

    fun update(profile: WebAppProfile): Boolean {
        val current = all()
        if (current.none { it.id == profile.id }) return false
        persist(current.map { if (it.id == profile.id) profile else it })
        return true
    }

    fun remove(id: String): Boolean {
        val current = all()
        val target = current.firstOrNull { it.id == id } ?: return false
        deleteIcon(target.iconFileName)
        persist(current.filterNot { it.id == id })
        return true
    }

    fun markUsed(id: String) {
        val current = all()
        if (current.none { it.id == id }) return
        persist(current.map { if (it.id == id) it.copy(lastUsedAt = System.currentTimeMillis()) else it })
    }

    fun icon(profile: WebAppProfile): Bitmap? {
        val name = profile.iconFileName ?: return null
        val file = File(iconDir, name)
        if (!file.exists()) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    fun clear() {
        all().forEach { deleteIcon(it.iconFileName) }
        persist(emptyList())
    }

    private fun load(): List<WebAppProfile> {
        if (!storeFile.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(storeFile.readText())
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    WebAppProfile.fromJson(obj)?.let { add(it) }
                }
            }
        }.getOrElse {
            // A corrupt store must not brick the app; start over rather than crash.
            emptyList()
        }
    }

    @Synchronized
    private fun persist(profiles: List<WebAppProfile>) {
        val sorted = profiles.sortedByDescending { it.lastUsedAt }
        val array = JSONArray()
        sorted.forEach { array.put(it.toJson()) }
        runCatching {
            // Write to a sibling then rename, so an interrupted write cannot leave a
            // half-written store behind.
            val temp = File(storeFile.parentFile, "$FILE_NAME.tmp")
            temp.writeText(array.toString())
            if (storeFile.exists()) storeFile.delete()
            temp.renameTo(storeFile)
        }
        cache = sorted
    }

    private fun saveIcon(id: String, icon: Bitmap): String? = runCatching {
        if (!iconDir.exists()) iconDir.mkdirs()
        val scaled = if (icon.width > MAX_ICON_PX || icon.height > MAX_ICON_PX) {
            Bitmap.createScaledBitmap(icon, MAX_ICON_PX, MAX_ICON_PX, true)
        } else {
            icon
        }
        val name = "$id.png"
        File(iconDir, name).outputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        name
    }.getOrNull()

    private fun deleteIcon(fileName: String?) {
        if (fileName.isNullOrBlank()) return
        runCatching { File(iconDir, fileName).delete() }
    }

    companion object {
        const val FILE_NAME = "webapps.json"
        const val ICON_DIR = "webapp_icons"
        const val MAX_ICON_PX = 192
    }
}
