# Compatibility

The distinction this document exists to make:

- **Application limitations** are ReWeb's fault and can be fixed here.
- **Platform limitations** belong to the Android version, the installed WebView,
  the hardware, or a provider's policy. No APK changes them.

ReWeb's job is to have none of the first kind, and to make the second kind
visible instead of mysterious.

---

## Android versions

| Version | API | Status |
|---|---|---|
| Android 5.0 / 5.1 | 21 / 22 | Supported. The oldest version where the WebView is separately updatable. |
| Android 6.0 | 23 | Supported. Runtime permissions begin here. |
| **Android 7.0 / 7.1** | **24 / 25** | **Primary target.** |
| Android 8.0 – 14 | 26 – 34 | Supported. |
| Android 4.4 and below | ≤ 19 | **Not supported.** |

### Why API 21 and not lower

On Android 4.4 and earlier the WebView is baked into the system image and frozen
at whatever Chromium 33-era build the ROM shipped. It cannot be updated, and it
cannot render sites built after roughly 2015. There is nothing an app can do
about it, so supporting it would mean shipping something that does not work.

From Android 5.0 the WebView is an APK — *Android System WebView*, or Chrome
itself on some builds — updated through the Play Store independently of the OS.
That is the entire premise of this project: the OS may be from 2016, but the
rendering engine need not be.

### API levels that gate specific features

| Feature | Available from | Below that |
|---|---|---|
| File upload (`onShowFileChooser`) | 21 | — |
| Camera/mic permission (`onPermissionRequest`) | 21 | — |
| Third-party cookies | 21 | — |
| Detailed navigation errors | 23 | Deprecated callback, less detail |
| HTTP error reporting | 23 | Not reported |
| Gesture/redirect info on navigation | 24 | Assumed to be a user gesture |
| Safe Browsing | 26 | Not available |
| Renderer-crash recovery | 26 | A renderer crash takes the app down |
| Scoped storage downloads | 29 | Needs `WRITE_EXTERNAL_STORAGE` |
| Notification permission | 33 | Granted implicitly |
| WebView profile isolation | 34 | One cookie jar per process |

---

## Your WebView version matters more than your Android version

The two are updated separately. An Android 7 phone with a current WebView will
outperform an Android 9 phone whose WebView has not been updated since 2019.

*Diagnostics* reports the exact Chromium version. Roughly:

| Chromium | What to expect |
|---|---|
| 100+ | Nearly everything works. |
| 80–99 | Most sites work. Some newest-generation apps may not. |
| 60–79 | Widespread breakage on modern single-page apps. Older and text-first sites are fine. |
| Below 60 | Severely outdated. Most modern sites will fail. |

ReWeb treats Chromium 80 as the modern baseline and shows a dismissible banner
below it. That is not an arbitrary number: it is roughly where ES2017
`async`/`await`, CSS Grid, `fetch` and the Web APIs most bundlers target became
reliably available.

**Updating your WebView is the single highest-impact thing you can do.** Open the
Play Store, search "Android System WebView", and update it. On some old devices
no newer version is offered — that is a ceiling ReWeb cannot lift.

---

## Modern websites

### What generally works

Text-first and server-rendered sites; Wikipedia; GitHub; most news sites; most
forums; webmail; search engines; sites that ship a legacy JavaScript bundle.

### What may not

Single-page applications compiled for recent browsers. When a site ships modern
syntax that an old engine cannot *parse*, the failure is total and silent — a
blank page, because the script never ran. ReWeb detects this class of failure and
shows a message naming the cause, rather than leaving you with an empty screen.

The Diagnostics "Modern JavaScript (ES2017)" check actually parses `async`/`await`
in your engine. If it fails, expect widespread breakage and no way around it
short of a WebView update.

---

## Site-by-site

These are the sites named as compatibility targets. ReWeb contains no
special-casing for any of them; they are listed in Diagnostics as test targets
only.

### Google Search
Works. The lightest of the targets.

### Google sign-in
**Blocked by Google, deliberately.** Google refuses to serve its authorization
pages to embedded browsers, showing "This browser or app may not be secure". This
is anti-phishing policy — an embedding app can read anything typed into a
WebView — and ReWeb does not attempt to defeat it.

What ReWeb does instead: detects the flow and offers to open it in a real browser
or Custom Tab. Honestly stated, that helps less than it sounds. Android gives
each app its own cookie jar, so a sign-in completed in Chrome leaves you signed
in *in Chrome*. The handoff genuinely helps only where the flow redirects back to
a URL ReWeb can capture and the originating page completes the exchange itself.
The prompt says which case you are in before you choose.

ReWeb's default user agent removes the `; wv` and `Version/4.0` tokens that mark
an embedded WebView, because ReWeb *is* a browser. Everything else, including the
real Chromium version, is left untouched. This does not, and is not intended to,
get past a provider that has decided to block your engine.

### ChatGPT
Opens as a normal website. It needs JavaScript, cookies, localStorage,
WebSockets, file upload and modern navigation — all of which ReWeb provides.

Whether it *runs* depends on your engine. ChatGPT ships a modern bundle; on
Chromium below roughly 90 expect problems, and on very old builds a blank page.
Sign-in has the Google limitation above if you use Google auth; email-and-password
sign-in works normally.

### Spotify Web
Opens, and sign-in works. HTML5 audio, MediaSession metadata, background playback
and notification controls are all supported.

**Playback of protected tracks requires Widevine**, which most legacy devices do
not have at a usable level. Diagnostics runs a real EME probe and reports what
your device has. ReWeb does not circumvent DRM, and does not claim Spotify
playback will work on any given Android 7 device — on many, it will not.

`spotify:` links open the Spotify app when installed; when it is not, ReWeb
offers the `open.spotify.com` equivalent.

### YouTube
`m.youtube.com` generally works on a reasonably current WebView. Playback needs
H.264 hardware decoding, which almost all devices have — Diagnostics confirms it.
Higher resolutions may stutter; that is decoder throughput, not the browser.

### GitHub
Works well. Mostly server-rendered.

### Wikipedia
Works on essentially any engine. A good control: if Wikipedia fails, the problem
is your network, not compatibility.

---

## Media

| | |
|---|---|
| HTML5 `<audio>` / `<video>` | Supported |
| H.264 | Effectively universal; Diagnostics confirms |
| MP3 / AAC | Effectively universal |
| VP9 / AV1 | Device-dependent; often absent on legacy hardware |
| Media Source Extensions | Required by adaptive streaming; probed |
| Fullscreen with rotation | Supported |
| Background audio | Supported, with a media notification |
| Lock-screen controls | Supported; richer metadata where MediaSession exists |
| **Widevine DRM** | **Device capability. Often absent or L3-only.** |

Autoplay requires a user gesture, matching Chrome. On these devices that is also
a battery decision.

---

## Downloads

Supported: any `http(s)` download, of any type — PDFs, ZIPs, images, APKs — via
the platform's DownloadManager, plus `data:` URIs up to 16 MB decoded in-process.

Not supported: **`blob:` URLs**. A blob exists only inside the page's own
renderer. DownloadManager cannot fetch one, and reading it back out would require
exposing a JavaScript bridge to every website. ReWeb refuses and says why. Use
the site's own export or save function.

APKs are saved but never installed automatically. Opening one hands it to
Android's installer.

---

## Private browsing

What it does: no history, no disk cache, no form data, and session cookies
removed when the last private tab closes.

What it does not do: isolate cookies. Below API 34 the platform gives an app one
cookie jar, so a *persistent* cookie set by a private tab shares storage with
normal browsing. Removing it would also delete the equivalent cookie for your
normal session. *Clear browsing data* is the reliable wipe.

And it is not anonymity. Your network, your ISP and the sites you visit see
exactly what they would otherwise. There is no VPN and no address hiding. The app
says this on the private-tab start screen.

---

## TLS

Old devices fail TLS in two characteristic ways.

**A wrong system clock** makes every certificate look invalid. This is extremely
common on phones that have been in a drawer, and it is the first thing ReWeb's
TLS error page suggests checking.

**Outdated cipher support.** The WebView update brings newer TLS with it, so an
updated WebView usually fixes this. If a site requires something the platform's
security provider cannot do, the handshake fails and no browser on that device
will connect.

ReWeb never disables certificate validation. There is no global bypass. A
certificate warning is an interstitial requiring two explicit confirmations, and
any exception lasts only until the app is closed.

---

## Reporting a problem

*Diagnostics → Run compatibility test → Copy report* produces a summary of your
device, WebView version and capability results. It deliberately omits your full
user agent, which would identify your device more precisely than anything else in
it.

Please include that report. "A site does not work" cannot be acted on without
knowing which engine it did not work on.
