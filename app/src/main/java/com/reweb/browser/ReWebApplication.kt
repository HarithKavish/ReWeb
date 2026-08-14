package com.reweb.browser

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Build
import android.webkit.WebView
import com.reweb.browser.bookmarks.BookmarkStore
import com.reweb.browser.downloads.DownloadStore
import com.reweb.browser.history.HistoryStore
import com.reweb.browser.privacy.PrivacyManager
import com.reweb.browser.settings.Settings
import com.reweb.browser.settings.SitePermissionStore
import com.reweb.browser.settings.SiteSettingsStore
import com.reweb.browser.webapp.WebAppStore

/**
 * Holds the app's stores.
 *
 * These are plain objects created lazily on first use — no dependency-injection
 * framework, because six singletons do not need one and every such library costs
 * dex size and startup time that matters on the hardware this app targets.
 */
class ReWebApplication : Application() {

    val settings: Settings by lazy { Settings(this) }
    val siteSettings: SiteSettingsStore by lazy { SiteSettingsStore(this) }
    val sitePermissions: SitePermissionStore by lazy { SitePermissionStore(this) }
    val historyStore: HistoryStore by lazy { HistoryStore(this) }
    val bookmarkStore: BookmarkStore by lazy { BookmarkStore(this) }
    val downloadStore: DownloadStore by lazy { DownloadStore(this) }
    val webAppStore: WebAppStore by lazy { WebAppStore(this) }

    val privacyManager: PrivacyManager by lazy {
        PrivacyManager(this, historyStore, bookmarkStore, downloadStore)
    }

    /** Set once a WebView has been created, so diagnostics can read the real UA. */
    @Volatile
    var observedUserAgent: String? = null

    override fun onCreate() {
        super.onCreate()

        // Multiple processes cannot share one WebView data directory. ReWeb is
        // single-process today, but naming the directory explicitly makes that a
        // deliberate choice rather than an accident waiting for a future service.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { WebView.setDataDirectorySuffix("reweb") }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Activities register their own handling; this covers the case where the
        // process is trimmed with no activity alive.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            System.gc()
        }
    }
}
