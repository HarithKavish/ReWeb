package com.reweb.browser.engine.webview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import com.reweb.browser.BuildConfig
import com.reweb.browser.engine.BrowserEngine
import com.reweb.browser.engine.EngineClient
import com.reweb.browser.engine.EngineConfiguration

/**
 * [BrowserEngine] backed by the device's system WebView.
 *
 * This is the only class in the app that is allowed to know it is driving a
 * WebView. Its job is to configure the WebView the way a browser needs (rather
 * than the way an embedded app view is configured by default) and to translate
 * WebView callbacks into engine-neutral events.
 */
@SuppressLint("SetJavaScriptEnabled")
class SystemWebViewEngine(context: Context) : BrowserEngine {

    internal val webView: WebView = WebView(context)

    private val webViewClient = ReWebWebViewClient(this)
    private val webChromeClient = ReWebWebChromeClient(this)
    private val mediaBridge = MediaStateBridge(this)

    private var userAgentOverride: String? = null
    private var destroyed = false
    private var lastProgress = 0
    private var lastFavicon: Bitmap? = null

    override var client: EngineClient? = null

    /** The factory user agent, captured before any override is applied. */
    private val factoryUserAgent: String = runCatching { webView.settings.userAgentString }
        .getOrDefault(System.getProperty("http.agent").orEmpty())

    init {
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        webView.webViewClient = webViewClient
        webView.webChromeClient = webChromeClient
        webView.isFocusableInTouchMode = true

        // A solid background avoids the white flash that is very visible on the
        // slow panels these devices ship with.
        webView.setBackgroundColor(0xFFFFFFFF.toInt())

        // Legacy GPU drivers frequently mis-render composited layers; keep the
        // default (hardware) layer type but disable the scrollbar overlay cost.
        webView.isScrollbarFadingEnabled = true
        webView.isVerticalScrollBarEnabled = true
        webView.isHorizontalScrollBarEnabled = false

        applyBaselineSettings(webView.settings)
        mediaBridge.attachTo(webView)

        if (BuildConfig.VERBOSE_LOGGING && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    /**
     * Settings that are constant for every tab. Per-tab and per-user preferences
     * arrive later through [applyConfiguration].
     */
    private fun applyBaselineSettings(settings: WebSettings) {
        // Required by essentially every modern site.
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true

        // Viewport handling: without these a desktop layout renders at 980px and
        // is unreadable on a small legacy screen.
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.setSupportZoom(true)

        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true

        // Geolocation is gated per-request by the permission prompt; this only
        // enables the API's existence.
        settings.setGeolocationEnabled(true)

        // Security posture: web content gets no filesystem reach whatsoever.
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = false

        // ReWeb does not store credentials; disabling form data keeps typed values
        // out of the WebView's own on-disk cache.
        @Suppress("DEPRECATION")
        settings.saveFormData = false

        // Match desktop-browser behaviour: passive mixed content loads, active
        // mixed content is blocked. NEVER_ALLOW breaks too much of the real web,
        // ALWAYS_ALLOW would silently weaken HTTPS pages.
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        // Autoplay stays gated behind a user gesture, as in Chrome. This matters
        // more here than on modern hardware: unattended video decode is the
        // fastest way to drain a legacy battery.
        settings.mediaPlaybackRequiresUserGesture = true

        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.loadsImagesAutomatically = true
        settings.blockNetworkImage = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.safeBrowsingEnabled = true
        }

        // Third-party cookies are what keep federated logins and embedded
        // checkout flows working; browsers still allow them by default.
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
    }

    override fun applyConfiguration(config: EngineConfiguration) {
        if (destroyed) return
        val settings = webView.settings
        settings.javaScriptEnabled = config.javaScriptEnabled
        settings.loadsImagesAutomatically = config.loadImages
        settings.blockNetworkImage = !config.loadImages
        settings.javaScriptCanOpenWindowsAutomatically = config.allowPopups
        settings.setSupportMultipleWindows(config.allowPopups)
        settings.textZoom = config.textZoomPercent

        setUserAgent(config.userAgent)

        if (config.incognito) {
            // The legacy WebView has a single process-wide cookie jar, so this is
            // damage limitation rather than true isolation. The scope of that
            // limitation is spelled out in PrivacyManager and COMPATIBILITY.md.
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            @Suppress("DEPRECATION")
            settings.saveFormData = false
        } else {
            settings.cacheMode = WebSettings.LOAD_DEFAULT
        }
    }

    override val view: View get() = webView

    override val currentUrl: String? get() = if (destroyed) null else webView.url

    override val title: String? get() = if (destroyed) null else webView.title

    override val progress: Int get() = lastProgress

    override val favicon: Bitmap? get() = lastFavicon

    override fun loadUrl(url: String, additionalHeaders: Map<String, String>) {
        if (destroyed) return
        if (additionalHeaders.isEmpty()) webView.loadUrl(url) else webView.loadUrl(url, additionalHeaders)
    }

    override fun loadHtml(html: String, baseUrl: String?) {
        if (destroyed) return
        // A null base URL gives the document a unique opaque origin, so an error
        // page cannot read storage or cookies belonging to the site it replaced.
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", baseUrl)
    }

    override fun goBack() {
        if (!destroyed && webView.canGoBack()) webView.goBack()
    }

    override fun goForward() {
        if (!destroyed && webView.canGoForward()) webView.goForward()
    }

    override fun reload() {
        if (!destroyed) webView.reload()
    }

    override fun stopLoading() {
        if (!destroyed) webView.stopLoading()
    }

    override fun canGoBack(): Boolean = !destroyed && webView.canGoBack()

    override fun canGoForward(): Boolean = !destroyed && webView.canGoForward()

    override fun setUserAgent(userAgent: String?) {
        if (destroyed) return
        if (userAgentOverride == userAgent) return
        userAgentOverride = userAgent
        // Assigning null asks the WebView to fall back to its own default.
        webView.settings.userAgentString = userAgent
    }

    override fun currentUserAgent(): String =
        if (destroyed) factoryUserAgent else webView.settings.userAgentString.orEmpty()

    override fun defaultUserAgent(): String = factoryUserAgent

    override fun evaluateJavaScript(script: String, resultCallback: ((String?) -> Unit)?) {
        if (destroyed) {
            resultCallback?.invoke(null)
            return
        }
        webView.evaluateJavascript(script) { value -> resultCallback?.invoke(value) }
    }

    override fun saveState(outState: Bundle): Boolean {
        if (destroyed) return false
        @Suppress("DEPRECATION")
        return webView.saveState(outState) != null
    }

    override fun restoreState(state: Bundle): Boolean {
        if (destroyed) return false
        @Suppress("DEPRECATION")
        return webView.restoreState(state) != null
    }

    override fun onActivityPause() {
        if (destroyed) return
        webView.onPause()
    }

    override fun onActivityResume() {
        if (destroyed) return
        webView.onResume()
        webView.resumeTimers()
    }

    override fun trimMemory() {
        if (destroyed) return
        webView.clearMatches()
        // Frees the raster cache without touching the back/forward list.
        webView.freeMemory()
    }

    override fun setActive(active: Boolean) {
        if (destroyed) return
        if (active) {
            webView.onResume()
            webView.resumeTimers()
        } else {
            webView.onPause()
            // pauseTimers() is process-wide, so it is only safe to call when no
            // other tab needs to keep running. TabManager owns that decision.
        }
    }

    /** Process-wide timer control, used by TabManager when the whole app backgrounds. */
    fun pauseTimersGlobally() {
        if (!destroyed) webView.pauseTimers()
    }

    fun resumeTimersGlobally() {
        if (!destroyed) webView.resumeTimers()
    }

    override fun clearNavigationHistory() {
        if (!destroyed) webView.clearHistory()
    }

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        client = null
        lastFavicon = null
        mediaBridge.detachFrom(webView)
        webView.stopLoading()
        webView.webChromeClient = null
        webView.setWebViewClient(android.webkit.WebViewClient())
        webView.setDownloadListener(null)
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.removeAllViews()
        webView.destroy()
    }

    // --- Internal hooks used by the WebViewClient/WebChromeClient ---

    internal val isDestroyed: Boolean get() = destroyed

    internal fun updateProgress(progress: Int) {
        lastProgress = progress
    }

    internal fun updateFavicon(icon: Bitmap?) {
        lastFavicon = icon
    }

    internal fun onDocumentReady() {
        mediaBridge.injectObserver()
    }

    internal fun setDownloadListener(listener: android.webkit.DownloadListener) {
        if (!destroyed) webView.setDownloadListener(listener)
    }
}
