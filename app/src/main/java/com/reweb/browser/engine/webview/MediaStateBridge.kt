package com.reweb.browser.engine.webview

import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.reweb.browser.engine.MediaPlaybackState

/**
 * The app's only JavaScript interface.
 *
 * ReWeb needs to know whether a page is currently playing audio so that it can
 * (a) keep the WebView running when the user leaves the activity and (b) show a
 * media notification with working transport controls. The platform gives an
 * embedder no other way to observe this: WebChromeClient reports no playback
 * state, and AudioManager's playback callbacks are API 26+ and carry no metadata.
 *
 * The exposed surface is deliberately one method taking four primitives. A
 * hostile page can therefore do exactly one thing: lie about what is playing,
 * which at worst produces a wrong media notification. It cannot reach any
 * application state, file, intent or stored data. See SECURITY.md.
 */
internal class MediaStateBridge(private val engine: SystemWebViewEngine) {

    private var lastState: MediaPlaybackState? = null

    fun attachTo(webView: WebView) {
        webView.addJavascriptInterface(this, BRIDGE_NAME)
    }

    fun detachFrom(webView: WebView) {
        webView.removeJavascriptInterface(BRIDGE_NAME)
        lastState = null
    }

    /** Re-injects the observer after every committed navigation. */
    fun injectObserver() {
        engine.evaluateJavaScript(OBSERVER_SCRIPT, null)
    }

    /**
     * Called from page JavaScript on a background thread. Values are treated as
     * untrusted: strings are length-capped and never interpreted as markup.
     */
    @JavascriptInterface
    fun report(playing: Boolean, title: String?, artist: String?, pageUrl: String?) {
        val state = MediaPlaybackState(
            isPlaying = playing,
            title = title?.take(MAX_METADATA_CHARS),
            artist = artist?.take(MAX_METADATA_CHARS),
            pageUrl = pageUrl?.take(MAX_URL_CHARS)
        )
        if (state == lastState) return
        lastState = state
        engine.view.post {
            if (!engine.isDestroyed) engine.client?.onMediaPlaybackChanged(state)
        }
    }

    private companion object {
        const val BRIDGE_NAME = "__rewebMedia"
        const val MAX_METADATA_CHARS = 200
        const val MAX_URL_CHARS = 2048

        /**
         * Watches every <audio>/<video> element, including ones added later, and
         * prefers navigator.mediaSession metadata when the page publishes it
         * (Spotify Web, YouTube and most players do).
         */
        val OBSERVER_SCRIPT = """
            (function () {
              if (window.__rewebMediaInstalled) { return; }
              window.__rewebMediaInstalled = true;

              function meta() {
                try {
                  var ms = navigator.mediaSession;
                  if (ms && ms.metadata && ms.metadata.title) {
                    return { title: ms.metadata.title, artist: ms.metadata.artist || '' };
                  }
                } catch (e) {}
                return { title: document.title || '', artist: '' };
              }

              function anyPlaying() {
                var els = document.querySelectorAll('audio,video');
                for (var i = 0; i < els.length; i++) {
                  var el = els[i];
                  if (!el.paused && !el.ended && el.readyState > 2) { return true; }
                }
                return false;
              }

              var last = null;
              function push() {
                var playing = anyPlaying();
                var m = meta();
                var key = playing + '|' + m.title + '|' + m.artist;
                if (key === last) { return; }
                last = key;
                try {
                  __rewebMedia.report(playing, m.title, m.artist, location.href);
                } catch (e) {}
              }

              function bind(el) {
                if (el.__rewebBound) { return; }
                el.__rewebBound = true;
                ['play', 'pause', 'ended', 'emptied', 'loadedmetadata'].forEach(function (evt) {
                  el.addEventListener(evt, push, true);
                });
              }

              function scan() {
                var els = document.querySelectorAll('audio,video');
                for (var i = 0; i < els.length; i++) { bind(els[i]); }
              }

              scan();
              push();

              if (window.MutationObserver) {
                new MutationObserver(function () { scan(); push(); })
                  .observe(document.documentElement, { childList: true, subtree: true });
              } else {
                // Legacy WebViews without MutationObserver fall back to polling.
                setInterval(function () { scan(); push(); }, 2000);
              }
            })();
        """.trimIndent()
    }
}
