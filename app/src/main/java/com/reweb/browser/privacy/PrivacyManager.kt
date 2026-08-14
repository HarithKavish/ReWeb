package com.reweb.browser.privacy

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import com.reweb.browser.bookmarks.BookmarkStore
import com.reweb.browser.downloads.DownloadStore
import com.reweb.browser.history.HistoryStore

/**
 * Clears local browsing data.
 *
 * ## What ReWeb stores, in full
 *
 * | Data                     | Where                                   |
 * |--------------------------|-----------------------------------------|
 * | History                  | reweb.db, `history` table                |
 * | Bookmarks                | reweb.db, `bookmarks` table              |
 * | Download records         | reweb.db, `downloads` table              |
 * | Preferences              | SharedPreferences `reweb_settings`       |
 * | Per-site user agent      | SharedPreferences `reweb_site_settings`  |
 * | Installed web apps       | filesDir/webapps.json + webapp_icons/    |
 * | Cookies, DOM storage     | the system WebView's own data directory  |
 * | HTTP cache               | the system WebView's own cache directory |
 *
 * Nothing is sent anywhere. There is no account, no sync and no server.
 *
 * ## The incognito caveat
 *
 * Below API 34 the platform gives an app exactly one WebView cookie jar and one
 * storage area; `Profile` isolation does not exist. Private tabs therefore get:
 * no history, no disk cache, and session cookies removed when the last private
 * tab closes. A *persistent* cookie set by a page in a private tab lives in the
 * same jar as normal browsing and is not removed by closing the tab, because
 * doing so would also delete the equivalent cookie for normal sessions. ReWeb
 * states this plainly in the UI instead of implying isolation it cannot deliver.
 * [clearAll] is the reliable way to remove everything.
 */
class PrivacyManager(
    private val context: Context,
    private val historyStore: HistoryStore,
    private val bookmarkStore: BookmarkStore,
    private val downloadStore: DownloadStore
) {

    data class Selection(
        val history: Boolean = false,
        val cookies: Boolean = false,
        val cache: Boolean = false,
        val siteStorage: Boolean = false,
        val downloadRecords: Boolean = false,
        val bookmarks: Boolean = false
    ) {
        val isEmpty: Boolean
            get() = !history && !cookies && !cache && !siteStorage && !downloadRecords && !bookmarks
    }

    /**
     * @param sampleWebView a live WebView used to clear its per-instance cache.
     *   Optional: the process-wide stores below are cleared either way.
     */
    fun clear(selection: Selection, sampleWebView: WebView? = null) {
        if (selection.history) historyStore.clear()
        if (selection.bookmarks) bookmarkStore.clear()
        if (selection.downloadRecords) downloadStore.clear()

        if (selection.cookies) {
            val cookieManager = CookieManager.getInstance()
            cookieManager.removeAllCookies(null)
            cookieManager.flush()
            // Any HTTP-auth credentials the WebView cached alongside them.
            runCatching { WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword() }
        }

        if (selection.siteStorage) {
            // Remembered protected-media grants are site data too.
            com.reweb.browser.settings.SitePermissionStore(context).clear()
            // Covers localStorage, sessionStorage, IndexedDB and the legacy Web SQL
            // area for every origin the WebView knows about.
            WebStorage.getInstance().deleteAllData()
        }

        if (selection.cache) {
            sampleWebView?.clearCache(true)
            runCatching { context.cacheDir.deleteRecursively() }
            // The WebView keeps its HTTP cache outside the app cache dir on some
            // builds; clearCache(true) is what reaches it.
        }
    }

    /** Everything except bookmarks, which users rarely mean by "clear browsing data". */
    fun clearAll(sampleWebView: WebView? = null) {
        clear(
            Selection(
                history = true,
                cookies = true,
                cache = true,
                siteStorage = true,
                downloadRecords = true,
                bookmarks = false
            ),
            sampleWebView
        )
    }

    /**
     * Called when the last private tab closes.
     *
     * Removes session cookies only. See the class documentation for why this is
     * not full isolation.
     */
    fun endPrivateSession() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeSessionCookies(null)
        cookieManager.flush()
    }

    /** Formats what will be removed, for the confirmation dialog. */
    fun summarize(selection: Selection): String = buildList {
        if (selection.history) add("${historyStore.count()} history entries")
        if (selection.cookies) add("all cookies and signed-in sessions")
        if (selection.cache) add("cached files")
        if (selection.siteStorage) add("site storage")
        if (selection.downloadRecords) add("download records (files are kept)")
        if (selection.bookmarks) add("all bookmarks")
    }.joinToString(", ")
}
