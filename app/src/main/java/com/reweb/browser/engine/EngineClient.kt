package com.reweb.browser.engine

import android.graphics.Bitmap
import android.view.View

/**
 * Events an engine raises. Implemented by the controller layer.
 *
 * Every method has a default so that an implementation only overrides what it
 * cares about, and so adding an event later does not break other engines.
 */
interface EngineClient {

    fun onPageStarted(url: String) {}

    fun onPageFinished(url: String) {}

    fun onProgressChanged(progress: Int) {}

    fun onTitleChanged(title: String) {}

    fun onFaviconChanged(favicon: Bitmap?) {}

    /** Committed URL changed without a full page load (history.pushState, fragment). */
    fun onUrlChanged(url: String) {}

    /** canGoBack/canGoForward may have changed. */
    fun onNavigationStateChanged() {}

    fun onPageError(error: PageError) {}

    /**
     * A certificate could not be validated. The implementation must eventually
     * call exactly one method on [decision]. Returning without answering blocks
     * the load forever.
     */
    fun onSslError(issue: SslIssue, decision: SslDecision) {
        decision.cancel()
    }

    /**
     * Give the app a chance to take a navigation away from the engine — external
     * schemes, intent: URIs, or an OAuth authorization endpoint that must run in a
     * real browser. Return true if the app handled it and the engine should not load it.
     */
    fun shouldOverrideNavigation(url: String, isRedirect: Boolean, isUserGesture: Boolean): Boolean = false

    fun onDownloadRequested(request: DownloadRequest) {}

    fun onEnterFullscreen(fullscreenView: View, onExitRequested: () -> Unit) {}

    fun onExitFullscreen() {}

    /** Return false to tell the page no file picker is available. */
    fun onFileChooserRequested(request: FileChooserRequest, response: FileChooserResponse): Boolean = false

    fun onPermissionRequested(request: WebPermissionRequest) {
        request.deny()
    }

    fun onJsDialog(request: JsDialogRequest, response: JsDialogResponse): Boolean = false

    /**
     * The page asked for a new window (target=_blank, window.open).
     *
     * Return a fresh, un-navigated engine to host it, or null to refuse. The
     * caller is responsible for registering the returned engine as a tab before
     * returning it.
     */
    fun onCreateWindowRequested(isUserGesture: Boolean): BrowserEngine? = null

    /** The page called window.close() on a window it opened. */
    fun onCloseWindowRequested() {}

    /**
     * The engine's rendering process died. On multi-process WebView builds this is
     * recoverable: the tab must be rebuilt rather than taking the whole app down.
     */
    fun onRenderProcessGone(didCrash: Boolean) {}

    /** Reported by the in-page media bridge; drives the media notification. */
    fun onMediaPlaybackChanged(state: MediaPlaybackState) {}
}

/**
 * Media state observed in the page. Populated from the MediaSession metadata the
 * page publishes, falling back to the document title.
 */
data class MediaPlaybackState(
    val isPlaying: Boolean,
    val title: String?,
    val artist: String?,
    val pageUrl: String?
)
