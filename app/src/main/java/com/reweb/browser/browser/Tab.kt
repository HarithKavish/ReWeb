package com.reweb.browser.browser

import android.graphics.Bitmap
import android.os.Bundle
import com.reweb.browser.engine.BrowserEngine
import com.reweb.browser.engine.SecurityState

/**
 * One browser tab.
 *
 * A tab outlives its engine. When memory is tight [engine] is destroyed and the
 * navigation history is kept in [savedState]; the tab still shows its title and
 * URL in the tab list, and reactivating it rebuilds the engine transparently.
 * That separation is what lets ReWeb keep many tabs on a device that can only
 * afford one or two live WebViews.
 */
class Tab(
    val id: Long,
    val isPrivate: Boolean,
    initialUrl: String?
) {
    var engine: BrowserEngine? = null
        internal set

    /** Navigation history of a suspended tab, produced by [BrowserEngine.saveState]. */
    var savedState: Bundle? = null
        internal set

    /** Last known URL. Survives suspension; used to rebuild the tab if state is lost. */
    var url: String? = initialUrl

    var title: String? = null

    var favicon: Bitmap? = null

    var securityState: SecurityState = SecurityState.NEUTRAL

    var isLoading: Boolean = false

    var progress: Int = 0

    /** True while this tab shows ReWeb's native start screen rather than a page. */
    var isShowingHome: Boolean = initialUrl == null

    var lastActiveAt: Long = System.currentTimeMillis()

    /**
     * Cached because the UI reads them on every toolbar refresh, and a suspended
     * tab has no engine to ask.
     */
    var canGoBack: Boolean = false
    var canGoForward: Boolean = false

    /** True when the engine has been destroyed but the tab is still listed. */
    val isSuspended: Boolean get() = engine == null

    fun displayTitle(): String {
        title?.takeIf { it.isNotBlank() }?.let { return it }
        url?.let { return UrlUtils.displayUrl(it).ifBlank { it } }
        return ""
    }
}
