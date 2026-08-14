package com.reweb.browser.engine

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View

/**
 * The rendering-engine boundary.
 *
 * Everything above this interface (BrowserController, the activities, the tab
 * model) is written against these methods and the engine-neutral types in this
 * package. Nothing above it imports `android.webkit`. That is what makes it
 * possible to add a second engine later without rewriting the browser.
 *
 * The one deliberate exception is [SystemWebViewEngine]'s internal handling of
 * `onCreateWindow`, where a new window must be handed a concrete WebView; that
 * cast is confined to the engine implementation itself.
 */
interface BrowserEngine {

    /** The view that renders page content. Added to the content container by the UI. */
    val view: View

    /** Currently committed URL, or null before the first navigation. */
    val currentUrl: String?

    /** Document title, or null if the page has not reported one. */
    val title: String?

    /** Last reported load progress, 0..100. */
    val progress: Int

    /** Receives all engine events. Set by the controller that owns this engine. */
    var client: EngineClient?

    fun loadUrl(url: String, additionalHeaders: Map<String, String> = emptyMap())

    /** Renders an in-app document (error page, interstitial) attributed to [baseUrl]. */
    fun loadHtml(html: String, baseUrl: String?)

    /**
     * Loads an in-app document under a real, non-opaque origin.
     *
     * Used only by the compatibility probe. `about:blank` and `data:` documents
     * get an opaque, non-secure origin, under which cookies, DOM storage,
     * getUserMedia, service workers and Encrypted Media all throw or vanish —
     * producing failures that describe the probe's own sandbox rather than the
     * device. [origin] must be an https origin so the document is a secure
     * context.
     */
    fun loadDocumentAtOrigin(html: String, origin: String)

    fun goBack()
    fun goForward()
    fun reload()
    fun stopLoading()
    fun canGoBack(): Boolean
    fun canGoForward(): Boolean

    /** Passing null restores the engine's built-in default user agent. */
    fun setUserAgent(userAgent: String?)

    /** The user agent the engine would send right now. */
    fun currentUserAgent(): String

    /** The engine's unmodified factory user agent, ignoring any override. */
    fun defaultUserAgent(): String

    fun applyConfiguration(config: EngineConfiguration)

    fun evaluateJavaScript(script: String, resultCallback: ((String?) -> Unit)? = null)

    /** Serialises navigation history into [outState]. Returns false if unsupported. */
    fun saveState(outState: Bundle): Boolean

    /** Restores navigation history saved by [saveState]. Returns false on failure. */
    fun restoreState(state: Bundle): Boolean

    /** Full-page bitmap is intentionally not exposed; only the favicon is. */
    val favicon: Bitmap?

    fun onActivityPause()
    fun onActivityResume()

    /**
     * Releases discretionary memory without destroying navigation state.
     * Called under memory pressure for the tab the user is still looking at.
     */
    fun trimMemory()

    /**
     * Suspends timers and media. Used for tabs that stay resident but are not
     * visible, so that background pages stop burning CPU on a slow device.
     */
    fun setActive(active: Boolean)

    fun clearNavigationHistory()

    /** After this call the engine and its [view] must not be used again. */
    fun destroy()
}

/** Settings the controller pushes down into whichever engine is in use. */
data class EngineConfiguration(
    val javaScriptEnabled: Boolean,
    val loadImages: Boolean,
    val userAgent: String?,
    /** Private tabs must not write cookies, cache or site storage to disk. */
    val incognito: Boolean,
    val allowPopups: Boolean,
    val textZoomPercent: Int
)
