# Architecture

## The shape of it

```
                    BrowserActivity / WebAppActivity
                    (rendering, dialogs, window flags)
                                  |
                                  v
                          BrowserController
              (navigation policy, history, per-site settings)
                                  |
                    +-------------+-------------+
                    |                           |
                    v                           v
                TabManager                 BrowserEngine
          (which tabs may hold          (rendering-engine
           a live engine)                  boundary)
                                              |
                                  +-----------+-----------+
                                  |                       |
                                  v                       v
                         SystemWebViewEngine        (a future engine)
```

Supporting components hang off the controller and the activities rather than off
each other:

| Component | Responsibility |
|---|---|
| `WebAppStore` / `WebAppInstaller` | Installed web apps, their profiles and icons |
| `AuthHandoff` | Detecting OAuth sign-in pages, handing them to a real browser |
| `DownloadController` / `DownloadStore` | Downloads via the platform DownloadManager |
| `HistoryStore` | Local browsing history |
| `BookmarkStore` | Bookmarks |
| `Settings` / `SiteSettingsStore` | Global preferences and per-site overrides |
| `PrivacyManager` | Clearing browsing data |
| `WebViewInfo` / `CompatibilityTest` | What this device's engine can actually do |
| `ExternalIntents` | `mailto:`, `tel:`, `intent:`, `spotify:` and friends |
| `PermissionCoordinator` | Site permission prompt ↔ Android runtime permission |
| `FileChooserCoordinator` | `<input type="file">` via the system picker |
| `MediaPlaybackService` | Background audio and transport controls |

## The engine boundary

`BrowserEngine` is the single most important decision in the codebase. Nothing
above it imports `android.webkit`.

```kotlin
interface BrowserEngine {
    val view: View
    val currentUrl: String?
    val title: String?
    var client: EngineClient?

    fun loadUrl(url: String, additionalHeaders: Map<String, String> = emptyMap())
    fun loadHtml(html: String, baseUrl: String?)
    fun goBack(); fun goForward(); fun reload(); fun stopLoading()
    fun canGoBack(): Boolean; fun canGoForward(): Boolean
    fun setUserAgent(userAgent: String?)
    fun applyConfiguration(config: EngineConfiguration)
    fun saveState(outState: Bundle): Boolean
    fun restoreState(state: Bundle): Boolean
    fun setActive(active: Boolean); fun trimMemory(); fun destroy()
}
```

Making that boundary real required engine-neutral vocabulary for everything an
engine reports, because the WebView types leak implementation detail in both
directions. `EngineTypes.kt` defines `PageError` and `ErrorKind`, `SslIssue` and
`SslDecision`, `WebPermissionRequest`, `FileChooserRequest`/`Response`,
`JsDialogRequest`/`Response`, `DownloadRequest` and `SecurityState`. A second
engine implements those; it does not force the UI to learn its error codes.

One deliberate exception: `WebChromeClient.onCreateWindow` hands over a
`WebView.WebViewTransport` that must receive a concrete `WebView`.
`SystemWebViewEngine` casts the engine it is given back to its own type to wire
that up. The cast is confined to the engine implementation and is safe, because
the controller creates every engine from one factory.

### Why the system WebView, and only that

Bundling Chromium was considered and rejected. A GeckoView build is ~40 MB per
ABI and requires API 21+ with substantially more RAM than the target devices
have; there is no buildable, legally distributable Chromium package that runs on
Android 5–7 and fits the size budget this project is working to. The system
WebView is what these devices actually have, it is still updated through the Play
Store on API 21+, and it costs nothing in APK size.

The abstraction exists so that conclusion can be revisited without rewriting the
browser.

## Tabs and memory

This is the other decision that shapes everything.

A WebView costs tens of megabytes, and on these devices every one of them lives
in this process. One per tab is how a browser becomes unusable on a 1 GB phone.

`TabManager` separates a tab from its engine. A `Tab` always holds its URL,
title, favicon and navigation flags. Its `BrowserEngine` is a resource that can
be taken away:

- **Live budget** — `computeMaxLiveEngines()` reads `ActivityManager.memoryClass`
  and `isLowRamDevice`: 1 engine on a low-RAM device, 2 below 96 MB heap, 3 below
  192 MB, 4 above.
- **Suspending** — save the navigation history into a `Bundle`, destroy the
  engine, keep everything the tab list needs to display. The active tab is never
  suspended.
- **Restoring** — rebuild the engine and `restoreState`. If the platform declined
  to produce a state bundle, fall back to reloading the last URL: forward history
  is lost, the tab is not.
- **Memory pressure** — `onTrimMemory` sheds the least-recently-used engine at
  `RUNNING_MODERATE`, all inactive engines at `RUNNING_LOW`, and additionally
  trims the active engine's caches at `BACKGROUND`.

Session restore creates tabs *unactivated*, so reopening twenty tabs costs one
WebView rather than twenty.

## Threading

There is none to speak of, and that is intentional.

Everything runs on the main thread. The stores are SQLite queries against indexed
columns over at most a few thousand rows, and SharedPreferences reads are already
in memory. On a slow single-core device, the scheduling overhead and lifecycle
complexity of moving that work to a background thread costs more than the work
itself. The one genuinely slow operation — downloading — is delegated to the
platform's DownloadManager, which has its own process.

`MediaStateBridge.report` is the exception: it is called from a WebView JavaScript
thread and posts to the view before touching anything.

## Storage

| Data | Mechanism | Why |
|---|---|---|
| History, bookmarks, downloads | `SQLiteOpenHelper` | Indexed queries over growing data. Room would add ~1 MB of dex and an annotation processor to generate SQL that fits on one screen. |
| Preferences, per-site UA | `SharedPreferences` | Small scalars, already in memory. |
| Web app profiles | JSON in `filesDir` | A handful of records with no querying needs. Written to a temp file and renamed, so an interrupted write cannot corrupt the store. |
| Cookies, DOM storage, cache | The WebView's own directories | Not ours to manage; `PrivacyManager` clears them through the platform APIs. |

## Dependencies

Four, all pinned to versions that still support API 21:

| Dependency | Why it is here | Why not something else |
|---|---|---|
| `androidx.appcompat` | Themes and `AppCompatActivity` consistently across API 21–34 | Writing this by hand is not worth it |
| `androidx.recyclerview` | Tabs, bookmarks, history, downloads lists | `ListView` cannot recycle heterogeneous rows well |
| `androidx.browser` | Custom Tabs for the OAuth handoff | The whole point is to use a *real* browser |
| `androidx.media` | `MediaSessionCompat` + media-style notification | Hand-rolling media notifications across API 21–34 is a large amount of version-specific code |

Rejected: Jetpack Compose (memory and APK size), Material Components (~1 MB for a
theme), Room, OkHttp/Retrofit (no HTTP client needed — the engine does the
fetching), Glide/Coil (favicons are ≤64 px), any DI framework, Firebase, and
every analytics or crash-reporting SDK.

## Findings that shaped these choices

Recorded here because they are the constraints the design answers to.

**The WebView is updatable from API 21.** Before Lollipop it is part of the
system image. This is the single fact that makes API 21 a sensible floor and
anything below it pointless.

**WebView API availability at API 21.** `onShowFileChooser`,
`onPermissionRequest` and `setAcceptThirdPartyCookies` all exist at 21 — file
upload, camera/microphone and third-party-cookie logins all work at the floor.
`onReceivedError(WebResourceRequest, WebResourceError)` and `onReceivedHttpError`
are API 23+, so the deprecated overloads are still implemented for 21–22.
`shouldOverrideUrlLoading(WebResourceRequest)` is API 24+, likewise. Safe Browsing
is API 26+. `onRenderProcessGone` is API 26+, and returning `true` from it is
what stops a renderer crash taking the whole app down.

**Provider policy on embedded browsers.** Google's block on embedded user agents
for OAuth is deliberate anti-phishing policy, not a bug to route around. The
handoff architecture in `AuthHandoff` is the honest answer, including its own
limitation: cookies set in an external browser cannot come back.

**A single cookie jar per process.** `Profile` isolation is API 34 and needs a
recent WebView, so it is unavailable on every device this app targets. Private
browsing is therefore documented as partial rather than implied to be complete.

**Autoplay is gated by default.** `setMediaPlaybackRequiresUserGesture` defaults
to `true`, matching Chrome. Left alone — unattended video decode is the fastest
way to flatten a legacy battery.

**Toolchain.** AGP 8.5.2 with Gradle 8.7 requires JDK 17 to build but places no
floor on the app's `minSdk`. `compileSdk 34` with `minSdk 21` is a supported
combination.

## Verification status

Being explicit about this, because "it builds" and "it works" are different
claims.

**Verified by automated tests** — URL-versus-search classification, search
engines, user-agent construction, download filename handling including path
traversal, error-page escaping, download-folder sanitisation, OAuth URL
detection, `intent:` URI sanitisation, and the history / bookmark / web app /
site-settings / settings / download stores against real SQLite and
SharedPreferences.

**Verified by building** — the project compiles, lints and packages an installable
APK.

**Verified on physical hardware** — Samsung Galaxy J2 (SM-J200G), Android 5.1.1 /
API 22, 913 MB RAM, WebView Chromium 94. Launch, browsing, tabs, menus, the
Diagnostics screen and the capability probes were all exercised over adb. Results
and limits are tabulated in [COMPATIBILITY.md](COMPATIBILITY.md#verified-on-real-hardware).

That session found three defects that no amount of desk checking would have
caught, and they are worth recording because each one is a category:

1. **The launcher icon did not exist on API 22.** `mipmap/ic_launcher.xml` was a
   `<layer-list>` wrapping a `<vector>`, and referencing a vector from inside
   another drawable container is only supported from API 24. The app installed and
   ran perfectly, but the launcher logged `Unable to create badged icon` and showed
   no usable entry — indistinguishable, from the outside, from "it crashes on
   startup". Fixed by shipping real PNG mipmaps at five densities and keeping the
   adaptive icon for API 26+. *Category: a resource that fails only on old
   platforms, in a component that is not the app.*

2. **The compatibility test reported false failures.** The probe ran against
   `about:blank`, which is an opaque, non-secure origin, so cookies, localStorage,
   sessionStorage, `getUserMedia` and service workers all threw or vanished — five
   FAILs that described the harness rather than the device. The probe engine was
   also never attached to the window, which independently breaks WebGL context
   creation. Fixed by [BrowserEngine.loadDocumentAtOrigin] under a synthetic https
   origin and attaching the probe view. *Category: a measurement tool measuring
   itself — the worst failure mode for the one feature whose entire purpose is
   honesty.*

3. **TLS failed on a large share of the web for a reason the UI did not explain.**
   The device's root store predates ISRG Root X1, so Let's Encrypt sites — Wikipedia
   included — fail with "untrusted authority". The warning was accurate and
   useless. Now [CertificateAdvice] names the cause and the fix, Diagnostics reports
   trust-store age as a first-class fact, and the app trusts user-installed roots so
   the fix is actually possible. *Category: a correct error message that leaves the
   user no action.*

**Still not verified** — Android 6.0 and 7.x specifically, and any device with a
Widevine CDM present. The instrumentation tests in `app/src/androidTest` need a
device or emulator:

```sh
./gradlew connectedDebugAndroidTest
```

The Diagnostics screen remains the intended way to find out what any particular
device can do, precisely because that cannot be predicted from here — a point the
Galaxy J2 made twice over, having a thoroughly modern engine and a ten-year-old
list of certificate authorities at the same time.
