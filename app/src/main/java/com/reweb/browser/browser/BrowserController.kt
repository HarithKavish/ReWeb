package com.reweb.browser.browser

import android.content.Context
import android.graphics.Bitmap
import com.reweb.browser.auth.AuthHandoff
import com.reweb.browser.bookmarks.BookmarkStore
import com.reweb.browser.engine.BrowserEngine
import com.reweb.browser.engine.DownloadRequest
import com.reweb.browser.engine.EngineClient
import com.reweb.browser.engine.EngineConfiguration
import com.reweb.browser.engine.FileChooserRequest
import com.reweb.browser.engine.FileChooserResponse
import com.reweb.browser.engine.JsDialogRequest
import com.reweb.browser.engine.JsDialogResponse
import com.reweb.browser.engine.MediaPlaybackState
import com.reweb.browser.engine.PageError
import com.reweb.browser.engine.SecurityState
import com.reweb.browser.engine.SslDecision
import com.reweb.browser.engine.SslIssue
import com.reweb.browser.engine.WebPermissionRequest
import com.reweb.browser.engine.webview.SystemWebViewEngine
import com.reweb.browser.history.HistoryStore
import com.reweb.browser.settings.Settings
import com.reweb.browser.settings.SiteSettingsStore

/**
 * Coordinates tabs, engines, storage and navigation policy.
 *
 * The UI layer talks to this class and never to an engine directly. That keeps
 * every decision that is *browser behaviour* — is this input a search, should
 * this navigation leave the app, does this page go in history, which user agent
 * applies here — in one testable place, and leaves the activity responsible only
 * for showing things.
 */
class BrowserController(
    private val context: Context,
    private val settings: Settings,
    private val siteSettings: SiteSettingsStore,
    private val historyStore: HistoryStore,
    private val bookmarkStore: BookmarkStore,
    private val host: Host
) {

    /** What the controller needs the UI to do. */
    interface Host {
        fun onTabStateChanged(tab: Tab)
        fun onTabListChanged()
        fun onActiveTabChanged(tab: Tab?)
        fun onEngineViewChanged(tab: Tab)

        fun showHomeScreen(show: Boolean)
        fun showMessage(message: String)

        fun requestFileChooser(request: FileChooserRequest, response: FileChooserResponse): Boolean
        fun requestWebPermission(request: WebPermissionRequest)
        fun showJsDialog(request: JsDialogRequest, response: JsDialogResponse): Boolean
        fun showSslInterstitial(issue: SslIssue, decision: SslDecision)

        fun enterFullscreen(view: android.view.View, onExitRequested: () -> Unit)
        fun exitFullscreen()

        fun onDownloadRequested(request: DownloadRequest)
        /** An external app should handle [url]; the UI confirms and dispatches. */
        fun onExternalSchemeRequested(url: String)
        /** [url] looks like an OAuth sign-in that a real browser would handle better. */
        fun onOAuthHandoffAvailable(url: String, returnsToApp: Boolean)
        fun onMediaPlaybackChanged(state: MediaPlaybackState)
        fun onPageErrorRendered(tab: Tab, error: PageError)
    }

    val tabManager = TabManager(context) { tab -> buildEngine(tab) }

    /**
     * Hosts for which the user accepted a certificate warning. Process-scoped and
     * never persisted, so the warning returns next launch.
     */
    private val sslBypassedHosts = mutableSetOf<String>()

    /** OAuth URLs already offered for handoff, so the prompt appears once per flow. */
    private val oauthPrompted = mutableSetOf<String>()

    val activeTab: Tab? get() = tabManager.activeTab

    init {
        tabManager.addListener(object : TabManager.Listener {
            override fun onTabListChanged() = host.onTabListChanged()
            override fun onActiveTabChanged(tab: Tab?) = host.onActiveTabChanged(tab)
            override fun onTabEngineChanged(tab: Tab) = host.onEngineViewChanged(tab)
        })
    }

    // --- Engine construction ---

    private fun buildEngine(tab: Tab): BrowserEngine? {
        val engine = runCatching { SystemWebViewEngine(context) }.getOrNull() ?: return null
        engine.client = TabEngineClient(tab)
        engine.applyConfiguration(configurationFor(tab, engine))
        engine.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            host.onDownloadRequested(
                DownloadRequest(url, userAgent, contentDisposition, mimeType, contentLength)
            )
        }
        return engine
    }

    /**
     * Builds the engine settings for [tab], resolving the user agent from the
     * per-site override first and the global default second.
     */
    private fun configurationFor(tab: Tab, engine: BrowserEngine): EngineConfiguration {
        val url = tab.engine?.currentUrl ?: tab.url
        val mode = siteSettings.userAgentModeForUrl(url) ?: settings.defaultUserAgentMode
        return EngineConfiguration(
            javaScriptEnabled = settings.javaScriptEnabled,
            loadImages = settings.loadImages,
            userAgent = UserAgent.resolve(mode, engine.defaultUserAgent(), settings.customUserAgent),
            incognito = tab.isPrivate,
            allowPopups = settings.allowPopups,
            textZoomPercent = settings.textZoomPercent
        )
    }

    /** Re-applies settings to every live engine, after a change in Settings. */
    fun reapplySettings() {
        tabManager.tabs.forEach { tab ->
            tab.engine?.let { it.applyConfiguration(configurationFor(tab, it)) }
        }
    }

    // --- Tabs ---

    fun openNewTab(url: String? = null, isPrivate: Boolean = false, activate: Boolean = true): Tab {
        val tab = tabManager.createTab(url = url, isPrivate = isPrivate, activate = activate)
        if (url == null) {
            tab.isShowingHome = true
            if (activate) host.showHomeScreen(true)
        }
        return tab
    }

    fun selectTab(id: Long) {
        val tab = tabManager.setActiveTab(id) ?: return
        host.showHomeScreen(tab.isShowingHome)
        host.onTabStateChanged(tab)
    }

    fun closeTab(id: Long) {
        val wasPrivate = tabManager.tabs.firstOrNull { it.id == id }?.isPrivate ?: false
        tabManager.closeTab(id)
        if (wasPrivate && !tabManager.hasPrivateTabs()) {
            host.showMessage(context.getString(com.reweb.browser.R.string.private_session_ended))
        }
        if (tabManager.count == 0) openNewTab()
    }

    // --- Navigation ---

    /** Handles whatever the user typed in the address bar. */
    fun submitAddressBarInput(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return
        when (val intent = UrlUtils.classify(trimmed)) {
            is UrlUtils.Intent.Navigate -> {
                if (UrlUtils.isExternalScheme(intent.url)) {
                    host.onExternalSchemeRequested(intent.url)
                } else {
                    navigate(intent.url)
                }
            }
            is UrlUtils.Intent.Search -> navigate(settings.searchEngine().buildSearchUrl(intent.query))
        }
    }

    fun navigate(url: String) {
        val tab = activeTab ?: openNewTab()
        val engine = tabManager.ensureEngine(tab)
        if (engine == null) {
            host.showMessage(context.getString(com.reweb.browser.R.string.error_engine_unavailable))
            return
        }
        tab.isShowingHome = false
        host.showHomeScreen(false)
        applyPerSiteConfiguration(tab, url)
        engine.loadUrl(url)
    }

    /** Applies the per-site user agent for [url] before the navigation starts. */
    private fun applyPerSiteConfiguration(tab: Tab, url: String) {
        val engine = tab.engine ?: return
        val mode = siteSettings.userAgentModeForUrl(url) ?: settings.defaultUserAgentMode
        val resolved = UserAgent.resolve(mode, engine.defaultUserAgent(), settings.customUserAgent)
        engine.setUserAgent(resolved)
    }

    fun goBack(): Boolean {
        val tab = activeTab ?: return false
        if (tab.isShowingHome) return false
        val engine = tab.engine ?: return false
        if (engine.canGoBack()) {
            engine.goBack()
            return true
        }
        // Reaching the start of a tab's history returns to the home screen rather
        // than closing the tab, which is far less destructive if it was a misfire.
        if (settings.usesNativeHomePage) {
            showHome(tab)
            return true
        }
        return false
    }

    fun goForward() {
        activeTab?.engine?.goForward()
    }

    fun reload() {
        val tab = activeTab ?: return
        if (tab.isShowingHome) return
        tab.engine?.reload()
    }

    fun stopLoading() {
        activeTab?.engine?.stopLoading()
    }

    fun goHome() {
        val tab = activeTab ?: return
        if (settings.usesNativeHomePage) {
            showHome(tab)
        } else {
            navigate(settings.homePage)
        }
    }

    private fun showHome(tab: Tab) {
        tab.isShowingHome = true
        tab.securityState = SecurityState.NEUTRAL
        tab.isLoading = false
        tab.progress = 0
        host.showHomeScreen(true)
        host.onTabStateChanged(tab)
    }

    // --- Per-site settings ---

    fun setUserAgentModeForCurrentSite(mode: UserAgentMode?) {
        val tab = activeTab ?: return
        val url = tab.engine?.currentUrl ?: tab.url ?: return
        siteSettings.setUserAgentModeForUrl(url, mode)
        val engine = tab.engine ?: return
        val resolved = UserAgent.resolve(
            mode ?: settings.defaultUserAgentMode,
            engine.defaultUserAgent(),
            settings.customUserAgent
        )
        engine.setUserAgent(resolved)
        engine.reload()
    }

    fun currentUserAgentMode(): UserAgentMode {
        val url = activeTab?.engine?.currentUrl ?: activeTab?.url
        return siteSettings.userAgentModeForUrl(url) ?: settings.defaultUserAgentMode
    }

    // --- Bookmarks ---

    fun toggleBookmarkForCurrentPage(): Boolean {
        val tab = activeTab ?: return false
        val url = tab.engine?.currentUrl ?: tab.url ?: return false
        if (!BookmarkStore.isBookmarkable(url)) return false
        return if (bookmarkStore.isBookmarked(url)) {
            bookmarkStore.removeByUrl(url)
            false
        } else {
            bookmarkStore.add(url, tab.title.orEmpty().ifBlank { url }, tab.favicon)
            true
        }
    }

    fun isCurrentPageBookmarked(): Boolean {
        val url = activeTab?.engine?.currentUrl ?: activeTab?.url ?: return false
        return bookmarkStore.isBookmarked(url)
    }

    // --- Lifecycle ---

    fun onPause() = tabManager.onActivityPause()

    fun onResume() = tabManager.onActivityResume()

    fun onTrimMemory(level: Int) = tabManager.onTrimMemory(level)

    fun saveSession() {
        settings.lastSessionUrls = tabManager.sessionUrls()
    }

    fun restoreSession(): Int {
        val urls = settings.lastSessionUrls
        if (urls.isEmpty()) return 0
        // Restored tabs start suspended: only the one the user lands on builds an
        // engine, so restoring twenty tabs costs one WebView, not twenty.
        urls.forEachIndexed { index, url ->
            tabManager.createTab(url = url, isPrivate = false, activate = index == 0)
        }
        return urls.size
    }

    fun destroy() {
        tabManager.closeAll()
    }

    // --- Engine callbacks ---

    /**
     * One instance per tab, so every callback knows which tab it came from
     * without the engine having to carry that knowledge.
     */
    private inner class TabEngineClient(private val tab: Tab) : EngineClient {

        override fun onPageStarted(url: String) {
            tab.isLoading = true
            tab.progress = 0
            tab.url = url
            tab.isShowingHome = false
            tab.securityState = securityStateFor(url)
            host.onTabStateChanged(tab)
        }

        override fun onPageFinished(url: String) {
            tab.isLoading = false
            tab.progress = 100
            tab.url = url
            tab.canGoBack = tab.engine?.canGoBack() ?: false
            tab.canGoForward = tab.engine?.canGoForward() ?: false
            tab.securityState = securityStateFor(url)
            recordHistory(tab, url)
            host.onTabStateChanged(tab)
        }

        override fun onProgressChanged(progress: Int) {
            tab.progress = progress
            tab.isLoading = progress < 100
            host.onTabStateChanged(tab)
        }

        override fun onTitleChanged(title: String) {
            tab.title = title
            if (!tab.isPrivate) {
                tab.url?.let { historyStore.updateTitle(it, title) }
            }
            host.onTabStateChanged(tab)
            host.onTabListChanged()
        }

        override fun onFaviconChanged(favicon: Bitmap?) {
            tab.favicon = favicon
            host.onTabListChanged()
        }

        override fun onUrlChanged(url: String) {
            tab.url = url
            tab.securityState = securityStateFor(url)
            recordHistory(tab, url)
            host.onTabStateChanged(tab)
        }

        override fun onNavigationStateChanged() {
            tab.canGoBack = tab.engine?.canGoBack() ?: false
            tab.canGoForward = tab.engine?.canGoForward() ?: false
            host.onTabStateChanged(tab)
        }

        override fun onPageError(error: PageError) {
            if (!error.isForMainFrame) return
            tab.isLoading = false
            tab.securityState = SecurityState.NEUTRAL
            tab.engine?.loadHtml(ErrorPages.networkError(context, error), error.url)
            host.onPageErrorRendered(tab, error)
            host.onTabStateChanged(tab)
        }

        override fun onSslError(issue: SslIssue, decision: SslDecision) {
            val host_ = UrlUtils.hostOf(issue.url)
            if (host_ != null && host_ in sslBypassedHosts) {
                // The user already accepted this host's certificate in this session.
                decision.proceed()
                return
            }
            tab.securityState = SecurityState.WARNING
            host.showSslInterstitial(issue, object : SslDecision {
                override fun proceed() {
                    host_?.let { sslBypassedHosts.add(it) }
                    decision.proceed()
                }

                override fun cancel() {
                    decision.cancel()
                    tab.engine?.loadHtml(ErrorPages.sslWarning(context, issue), issue.url)
                }
            })
        }

        override fun shouldOverrideNavigation(
            url: String,
            isRedirect: Boolean,
            isUserGesture: Boolean
        ): Boolean {
            if (UrlUtils.isExternalScheme(url)) {
                host.onExternalSchemeRequested(url)
                return true
            }

            if (settings.oauthHandoffEnabled &&
                AuthHandoff.isOAuthAuthorizationUrl(url) &&
                url !in oauthPrompted
            ) {
                oauthPrompted.add(url)
                host.onOAuthHandoffAvailable(url, AuthHandoff.redirectsBackToApp(url))
                // Let the WebView continue loading as well: if the provider does
                // serve it, the flow works without the user doing anything, and the
                // prompt is only an offer.
                return false
            }

            applyPerSiteConfiguration(tab, url)
            return false
        }

        override fun onDownloadRequested(request: DownloadRequest) {
            host.onDownloadRequested(request)
        }

        override fun onEnterFullscreen(fullscreenView: android.view.View, onExitRequested: () -> Unit) {
            host.enterFullscreen(fullscreenView, onExitRequested)
        }

        override fun onExitFullscreen() {
            host.exitFullscreen()
        }

        override fun onFileChooserRequested(
            request: FileChooserRequest,
            response: FileChooserResponse
        ): Boolean = host.requestFileChooser(request, response)

        override fun onPermissionRequested(request: WebPermissionRequest) {
            host.requestWebPermission(request)
        }

        override fun onJsDialog(request: JsDialogRequest, response: JsDialogResponse): Boolean =
            host.showJsDialog(request, response)

        override fun onCreateWindowRequested(isUserGesture: Boolean): BrowserEngine? {
            if (!settings.allowPopups) return null
            // Popups inherit the opener's private state; a private page must not be
            // able to spawn a tab that writes to history.
            val popupTab = tabManager.createTab(url = null, isPrivate = tab.isPrivate, activate = false)
            popupTab.isShowingHome = false
            val engine = tabManager.ensureEngine(popupTab)
            if (engine == null) {
                tabManager.closeTab(popupTab.id)
                return null
            }
            tabManager.setActiveTab(popupTab.id)
            host.showHomeScreen(false)
            return engine
        }

        override fun onCloseWindowRequested() {
            tabManager.closeTab(tab.id)
            if (tabManager.count == 0) openNewTab()
        }

        override fun onRenderProcessGone(didCrash: Boolean) {
            // The engine's WebView is unusable after this; rebuild the tab around a
            // fresh one instead of leaving a dead view on screen.
            val lastUrl = tab.url
            tab.engine?.destroy()
            tab.engine = null
            val engine = tabManager.ensureEngine(tab)
            if (engine != null && lastUrl != null) {
                engine.loadHtml(ErrorPages.rendererCrashed(context, lastUrl), lastUrl)
            }
            host.onEngineViewChanged(tab)
            host.onTabStateChanged(tab)
        }

        override fun onMediaPlaybackChanged(state: MediaPlaybackState) {
            host.onMediaPlaybackChanged(state)
        }

        private fun securityStateFor(url: String): SecurityState {
            val host_ = UrlUtils.hostOf(url)
            return when {
                url.startsWith("https://") && host_ != null && host_ in sslBypassedHosts -> SecurityState.WARNING
                url.startsWith("https://") -> SecurityState.SECURE
                url.startsWith("http://") -> SecurityState.INSECURE
                else -> SecurityState.NEUTRAL
            }
        }

        private fun recordHistory(tab: Tab, url: String) {
            if (tab.isPrivate) return
            if (!HistoryStore.shouldRecord(url)) return
            historyStore.recordVisit(url, tab.title)
        }
    }
}
