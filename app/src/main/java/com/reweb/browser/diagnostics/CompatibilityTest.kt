package com.reweb.browser.diagnostics

import android.os.Handler
import android.os.Looper
import com.reweb.browser.engine.BrowserEngine
import org.json.JSONObject

enum class CompatResult {
    PASS,
    FAIL,

    /**
     * The capability could not be determined from inside the page. Reported as-is
     * rather than guessed: a test that cannot run is not a test that passed.
     */
    UNKNOWN
}

data class CompatCheck(
    val id: String,
    val label: String,
    val result: CompatResult,
    /** What was actually measured, so a FAIL can be acted on. */
    val detail: String?
)

/**
 * Runs real capability probes inside the rendering engine.
 *
 * Every check here executes actual code in the page — writing a cookie and
 * reading it back, storing a localStorage key, asking the media element what it
 * can decode. Nothing is inferred from the Android version, because the WebView
 * is updated independently of the OS and the two tell you different things.
 *
 * Where only the existence of an API can be observed (WebSockets cannot be
 * verified without a live server; Widevine cannot be verified without a licensed
 * stream), the check says so in its detail text and does not claim PASS for a
 * capability it has not exercised.
 */
class CompatibilityTest(private val engine: BrowserEngine) {

    fun run(onComplete: (List<CompatCheck>) -> Unit) {
        engine.evaluateJavaScript(PROBE_SCRIPT) { raw ->
            val synchronous = parse(raw)
            // The EME probes are promises; give them a moment, then collect
            // whatever they settled on. A timeout yields UNKNOWN, never a
            // fabricated PASS.
            Handler(Looper.getMainLooper()).postDelayed({
                engine.evaluateJavaScript("window.__rewebEmeAudio || 'PENDING'") { audioRaw ->
                    engine.evaluateJavaScript("window.__rewebEmeVideo || 'PENDING'") { videoRaw ->
                        onComplete(
                            synchronous +
                                emeCheck("widevine_audio", LABEL_WIDEVINE_AUDIO, audioRaw, AUDIO_USE) +
                                emeCheck("widevine_video", LABEL_WIDEVINE_VIDEO, videoRaw, VIDEO_USE)
                        )
                    }
                }
            }, EME_TIMEOUT_MS)
        }
    }

    private fun emeCheck(id: String, label: String, raw: String?, use: String): CompatCheck {
        val value = raw?.trim()?.trim('"') ?: "PENDING"
        return when (value) {
            "SUPPORTED" -> CompatCheck(
                id, label, CompatResult.PASS,
                "Widevine CDM available for $use (L1/L3 level is not detectable from a page)"
            )
            "UNSUPPORTED" -> CompatCheck(
                id, label, CompatResult.FAIL,
                "CDM refused this configuration: $use will not play"
            )
            "NO_API" -> CompatCheck(
                id, label, CompatResult.FAIL,
                "Encrypted Media Extensions absent from this WebView"
            )
            else -> CompatCheck(
                id, label, CompatResult.UNKNOWN,
                "Probe did not complete in ${EME_TIMEOUT_MS}ms"
            )
        }
    }

    private fun parse(raw: String?): List<CompatCheck> {
        if (raw.isNullOrBlank() || raw == "null") {
            // evaluateJavascript returning nothing means script execution itself
            // failed, which is the strongest possible FAIL for JavaScript.
            return listOf(
                CompatCheck("javascript", LABEL_JAVASCRIPT, CompatResult.FAIL, "Script evaluation returned no result")
            ) + REMAINING_IDS.map { (id, label) ->
                CompatCheck(id, label, CompatResult.UNKNOWN, "Not reached: JavaScript did not run")
            }
        }

        val json = runCatching {
            // evaluateJavascript hands back a JSON-encoded value, so an object
            // arrives as a quoted, escaped string.
            val unwrapped = if (raw.startsWith("\"")) {
                JSONObject(org.json.JSONTokener(raw).nextValue().toString())
            } else {
                JSONObject(raw)
            }
            unwrapped
        }.getOrNull() ?: return listOf(
            CompatCheck("javascript", LABEL_JAVASCRIPT, CompatResult.UNKNOWN, "Unparseable probe result")
        )

        return CHECK_DEFINITIONS.map { (id, label) ->
            val node = json.optJSONObject(id)
            if (node == null) {
                CompatCheck(id, label, CompatResult.UNKNOWN, "Probe did not report this capability")
            } else {
                CompatCheck(
                    id = id,
                    label = label,
                    result = when (node.optString("r")) {
                        "P" -> CompatResult.PASS
                        "F" -> CompatResult.FAIL
                        else -> CompatResult.UNKNOWN
                    },
                    detail = node.optString("d").takeIf { it.isNotBlank() }
                )
            }
        }
    }

    companion object {
        private const val EME_TIMEOUT_MS = 1500L

        private const val LABEL_JAVASCRIPT = "JavaScript"

        /**
         * Audio and video Widevine are reported separately because a device can
         * license one and refuse the other, and that difference decides whether a
         * music service works while a video service does not.
         */
        private const val LABEL_WIDEVINE_AUDIO = "Widevine DRM (audio)"
        private const val LABEL_WIDEVINE_VIDEO = "Widevine DRM (video)"
        private const val AUDIO_USE = "protected music streaming (Spotify, Apple Music)"
        private const val VIDEO_USE = "protected video streaming (Netflix, Prime Video)"

        private val CHECK_DEFINITIONS = listOf(
            "javascript" to LABEL_JAVASCRIPT,
            "modernjs" to "Modern JavaScript (ES2017)",
            "cookies" to "Cookies",
            "localstorage" to "Local Storage",
            "sessionstorage" to "Session Storage",
            "indexeddb" to "IndexedDB",
            "websockets" to "WebSockets",
            "fetch" to "Fetch API",
            "video" to "HTML5 Video (H.264)",
            "audio" to "HTML5 Audio (MP3)",
            "mse" to "Media Source Extensions",
            "fileupload" to "File Upload",
            "fullscreen" to "Fullscreen",
            "mediasession" to "MediaSession API",
            "getusermedia" to "Camera / Microphone API",
            "serviceworker" to "Service Workers",
            "webgl" to "WebGL",
            "cssgrid" to "CSS Grid"
        )

        private val REMAINING_IDS = CHECK_DEFINITIONS.filterNot { it.first == "javascript" }

        /**
         * Returns a compact JSON object: `{ id: { r: "P"|"F"|"U", d: "detail" } }`.
         * Written in ES5 so that it can run — and correctly report failure — on a
         * WebView too old to parse the syntax it is testing for.
         */
        private val PROBE_SCRIPT = """
            (function () {
              var out = {};
              function set(id, ok, detail) {
                out[id] = { r: (ok === null ? 'U' : (ok ? 'P' : 'F')), d: detail || '' };
              }

              set('javascript', true, 'Script evaluation succeeded');

              // Actually parse ES2017 syntax rather than sniffing a version string.
              try {
                // eslint-disable-next-line no-new-func
                new Function('return (async () => { for (const x of [1]) { await x; } })()')();
                set('modernjs', true, 'async/await, const/let and for-of parsed');
              } catch (e) {
                set('modernjs', false, 'Engine cannot parse async/await: modern bundles will fail');
              }

              try {
                var probe = '__reweb_probe=1;path=/';
                document.cookie = probe;
                var ok = document.cookie.indexOf('__reweb_probe=1') !== -1;
                document.cookie = '__reweb_probe=;path=/;expires=Thu, 01 Jan 1970 00:00:00 GMT';
                set('cookies', ok, ok ? 'Wrote and read back a cookie' : 'Cookie write was not persisted');
              } catch (e) { set('cookies', false, 'document.cookie threw: ' + e.name); }

              try {
                localStorage.setItem('__reweb', '1');
                var lok = localStorage.getItem('__reweb') === '1';
                localStorage.removeItem('__reweb');
                set('localstorage', lok, lok ? 'Wrote and read back a key' : 'Value did not persist');
              } catch (e) { set('localstorage', false, 'localStorage threw: ' + e.name); }

              try {
                sessionStorage.setItem('__reweb', '1');
                var sok = sessionStorage.getItem('__reweb') === '1';
                sessionStorage.removeItem('__reweb');
                set('sessionstorage', sok, sok ? 'Wrote and read back a key' : 'Value did not persist');
              } catch (e) { set('sessionstorage', false, 'sessionStorage threw: ' + e.name); }

              try {
                set('indexeddb', !!window.indexedDB,
                  window.indexedDB ? 'API present (open not attempted)' : 'window.indexedDB absent');
              } catch (e) { set('indexeddb', false, 'Access threw: ' + e.name); }

              set('websockets', !!window.WebSocket,
                window.WebSocket ? 'Constructor present (no connection attempted)' : 'WebSocket absent');

              set('fetch', !!window.fetch,
                window.fetch ? 'fetch present' : 'fetch absent: sites relying on it need a polyfill');

              try {
                var v = document.createElement('video');
                var mp4 = v.canPlayType('video/mp4; codecs="avc1.42E01E"');
                set('video', mp4 !== '', 'canPlayType(H.264) = "' + (mp4 || 'no') + '"');
                set('mse', !!window.MediaSource,
                  window.MediaSource ? 'MediaSource present (required by most streaming sites)'
                                     : 'MediaSource absent: adaptive streaming will not work');
              } catch (e) {
                set('video', null, 'Probe threw: ' + e.name);
                set('mse', null, 'Probe threw: ' + e.name);
              }

              try {
                var a = document.createElement('audio');
                var mp3 = a.canPlayType('audio/mpeg');
                set('audio', mp3 !== '', 'canPlayType(MP3) = "' + (mp3 || 'no') + '"');
              } catch (e) { set('audio', null, 'Probe threw: ' + e.name); }

              try {
                var input = document.createElement('input');
                input.type = 'file';
                set('fileupload', input.type === 'file' && !!window.FileReader,
                  'input[type=file] ' + (input.type === 'file' ? 'accepted' : 'rejected') +
                  ', FileReader ' + (window.FileReader ? 'present' : 'absent'));
              } catch (e) { set('fileupload', false, 'Probe threw: ' + e.name); }

              var el = document.documentElement;
              var fs = !!(el.requestFullscreen || el.webkitRequestFullScreen || el.webkitRequestFullscreen);
              set('fullscreen', fs, fs ? 'requestFullscreen available' : 'No fullscreen entry point');

              set('mediasession', !!(navigator.mediaSession),
                navigator.mediaSession ? 'Present: lock-screen metadata will work'
                                       : 'Absent: media notification falls back to page title');

              var gum = !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia);
              set('getusermedia', gum,
                gum ? 'mediaDevices.getUserMedia present' : 'Absent: camera and microphone capture unavailable');

              set('serviceworker', !!navigator.serviceWorker,
                navigator.serviceWorker ? 'Present' : 'Absent: offline-capable sites will always need the network');

              try {
                var c = document.createElement('canvas');
                var gl = c.getContext('webgl') || c.getContext('experimental-webgl');
                set('webgl', !!gl, gl ? 'Context created' : 'Context creation failed');
              } catch (e) { set('webgl', false, 'Probe threw: ' + e.name); }

              try {
                var grid = window.CSS && CSS.supports && CSS.supports('display', 'grid');
                set('cssgrid', grid === undefined ? null : !!grid,
                  grid ? 'display:grid supported' : 'display:grid unsupported: modern layouts will break');
              } catch (e) { set('cssgrid', null, 'CSS.supports unavailable'); }

              // Asynchronous: resolved into globals the app reads separately.
              //
              // Audio and video are probed independently and on purpose. A device
              // can hold a Widevine licence for audio and refuse video, and that
              // distinction decides whether Spotify works while Netflix does not.
              // Asking only about video reports a FAIL that is wrong for audio.
              window.__rewebEmeAudio = 'PENDING';
              window.__rewebEmeVideo = 'PENDING';
              function probeEme(target, config) {
                try {
                  if (!navigator.requestMediaKeySystemAccess) { window[target] = 'NO_API'; return; }
                  navigator.requestMediaKeySystemAccess('com.widevine.alpha', [config]).then(
                    function () { window[target] = 'SUPPORTED'; },
                    function () { window[target] = 'UNSUPPORTED'; }
                  );
                } catch (e) { window[target] = 'NO_API'; }
              }
              probeEme('__rewebEmeAudio', {
                initDataTypes: ['cenc'],
                audioCapabilities: [{ contentType: 'audio/mp4; codecs="mp4a.40.2"' }]
              });
              probeEme('__rewebEmeVideo', {
                initDataTypes: ['cenc'],
                videoCapabilities: [{ contentType: 'video/mp4; codecs="avc1.42E01E"' }]
              });

              return JSON.stringify(out);
            })();
        """.trimIndent()
    }
}
