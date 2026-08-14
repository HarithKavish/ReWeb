# ReWeb

A lightweight web client for old Android phones.

ReWeb is a browser aimed squarely at devices that modern browsers have left
behind — Android 5.0 through 7.x, 1 GB of RAM, a Chromium build from several
years ago. It gets as much of the modern web working on that hardware as the
hardware actually allows, and tells you plainly when something cannot work.

That last part matters. A lot of the modern web is out of reach on a 2016 phone,
and no APK changes that. What ReWeb does is remove every limitation that *is*
the app's fault, and make the remaining ones legible instead of mysterious.

---

## Why it exists

Chrome dropped support for Android 7 and below. Firefox's legacy builds are
frozen. The stock browsers on these devices were never good. What is left on most
of these phones is the system WebView — which, on Android 5.0 and later, is a
Chromium build the Play Store still updates independently of the OS.

ReWeb is a real browser wrapped around that engine: tabs, history, bookmarks,
downloads, file uploads, private browsing, installable web apps, per-site user
agents, media controls, and diagnostics that tell you what your particular device
can and cannot do.

---

## Supported Android versions

| | |
|---|---|
| **Minimum** | Android 5.0 (API 21) |
| **Primary target** | Android 7.0 (API 24) |
| **Compiled against** | API 34 |

API 21 is the floor because it is the first version where the WebView is a
separately updatable APK. Below that, the engine is frozen into the ROM and
cannot render the modern web at all, so an app like this would have nothing to
work with. See [COMPATIBILITY.md](COMPATIBILITY.md).

Every dependency is pinned to a version that still supports API 21. The minimum
SDK is never raised to satisfy a library — a library gets replaced instead.

---

## What works

**Browsing.** Address bar that distinguishes URLs from searches, configurable
search engine (Google by default), back/forward/reload/stop, page loading
indicator, redirects, `target="_blank"` and JavaScript-opened windows.

**Tabs**, with a memory strategy — see [Performance](#performance).

**JavaScript, cookies and storage.** DOM storage, localStorage, sessionStorage,
IndexedDB, WebSockets and third-party cookies are all on, because logins depend
on them. Sessions persist across restarts.

**Downloads** through Android's own DownloadManager, so they survive the app
being killed. PDFs, ZIPs, images, APKs. Downloaded APKs are saved but never
installed automatically — opening one hands it to Android's installer, where you
confirm.

**File uploads** via `<input type="file">`, including multiple selection and
camera capture, using the system picker. No storage permission is requested.

**Media.** HTML5 audio and video, fullscreen with orientation handling,
background audio with a media notification and lock-screen transport controls.

**Web apps.** "Install web app" saves a site as its own launcher-style entry with
its own window, its own task in Recents, and its own user-agent preference.

**Private browsing**, with an honest description of what it does and does not
protect — it is not anonymity, and ReWeb says so in the UI.

**Per-site user agents** — default, mobile, desktop or custom, globally or per
site.

**Diagnostics** that run real capability probes inside your device's WebView and
report PASS, FAIL or UNKNOWN. Never PASS for something it could not measure.

**Permissions.** Camera, microphone and location are requested only when a page
asks and you approve, and are never remembered.

---

## What cannot work, and why

These are platform limits, not missing features. Full detail in
[COMPATIBILITY.md](COMPATIBILITY.md).

- **DRM video** (Netflix, Disney+, and Spotify's protected streams) needs a
  Widevine CDM at a level most legacy devices do not have. ReWeb does not attempt
  to circumvent DRM. The Diagnostics screen reports what your device has.
- **Google sign-in inside the app.** Google refuses to serve its authorization
  pages to embedded browsers. ReWeb offers to hand the flow to a real browser,
  but Android does not let apps share cookie jars, so the resulting session
  usually stays in that browser. ReWeb explains this before you choose.
- **Sites needing a newer engine.** If your WebView is Chromium 61 and a site
  ships ES2022, it will break. ReWeb detects this and says so rather than showing
  a blank page.
- **`blob:` downloads.** These exist only inside the page. Fetching one would
  require exposing a JavaScript bridge to every website, which ReWeb will not do.
- **Incognito cookie isolation.** Below API 34 the platform gives an app one
  cookie jar. Private tabs get no history, no cache and session-cookie cleanup,
  but not true isolation. Documented rather than implied.

---

## Getting the APK

### From GitHub Actions

1. Open the **Actions** tab of this repository.
2. Pick the most recent successful **Build** run on `main`.
3. Scroll to **Artifacts** and download **`reweb-debug-apk`**.
4. Unzip it — inside is `reweb-debug.apk`.

Artifacts are kept for 30 days. Every push to `main` produces one.

### Installing

1. Copy the APK to the phone.
2. Enable installing from unknown sources (the exact path varies:
   *Settings → Security → Unknown sources* on Android 7 and below,
   *Settings → Apps → Special access → Install unknown apps* on 8+).
3. Open the APK and confirm.

The debug APK uses the application ID `com.reweb.browser.debug`, so it installs
alongside a release build rather than replacing it.

Release APKs built without signing secrets are **unsigned and will not install** —
see [BUILD.md](BUILD.md).

---

## Building it yourself

```sh
./gradlew assembleDebug      # app/build/outputs/apk/debug/reweb-debug.apk
./gradlew assembleRelease    # app/build/outputs/apk/release/reweb-release.apk
./gradlew test               # unit tests
./gradlew lint               # Android Lint
./gradlew reportApkSize      # prints the size of every APK built
```

Requires JDK 17 and an Android SDK with API 34. Gradle itself is supplied by the
wrapper — nothing else to install. Full instructions in [BUILD.md](BUILD.md).

---

## Performance

The whole app is built around not having much memory.

- **No Jetpack Compose.** Android Views and XML.
- **No Material Components**, no dependency-injection framework, no image
  library, no Room, no analytics, no Firebase, no ad SDK, no telemetry. Four
  AndroidX libraries in total.
- **Tabs are capped.** The number of live WebViews is derived from the heap the
  system grants — one on a low-RAM device, up to four on a generous one. Extra
  tabs keep their URL, title and navigation history but release their engine, and
  restore transparently when you return to them.
- **Memory pressure is handled.** Inactive tabs are suspended before the system
  starts killing the process.
- **No background services** except the media notification, which runs only while
  audio is actually playing and stops itself afterwards.
- **Release builds** use R8 with resource shrinking.

---

## Privacy

Everything stays on the device. There is no account, no sync, no server, and no
network traffic that you did not initiate by visiting a page.

What ReWeb stores locally:

| Data | Location |
|---|---|
| History (capped at 3000 entries) | `reweb.db` |
| Bookmarks | `reweb.db` |
| Download records | `reweb.db` |
| Preferences, per-site user agents | SharedPreferences |
| Installed web apps and their icons | `filesDir/webapps.json`, `filesDir/webapp_icons/` |
| Cookies, site storage, HTTP cache | the system WebView's own directories |

Backup and device-transfer are disabled so none of it leaves the phone. ReWeb
never stores passwords, and never logs URLs, cookies, tokens or authorization
codes. *Settings → Privacy → Clear browsing data* removes any of it.

---

## Documentation

- [ARCHITECTURE.md](ARCHITECTURE.md) — how it is put together, and the research
  behind the technical choices
- [COMPATIBILITY.md](COMPATIBILITY.md) — what works on which Android version, and
  exactly why the rest does not
- [SECURITY.md](SECURITY.md) — the security decisions and their reasoning
- [BUILD.md](BUILD.md) — local builds, CI, and release signing

---

## Status

Version 0.1.0. See [ARCHITECTURE.md § Verification status](ARCHITECTURE.md#verification-status)
for what has been tested and what has not.

## Licence

Not yet chosen. Until one is added, no licence is granted.
