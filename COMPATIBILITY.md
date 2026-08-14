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

## Verified on real hardware

Everything below this heading was measured on a physical device, not inferred.

**Samsung Galaxy J2 (SM-J200G)** — Android 5.1.1, API 22, armeabi-v7a, 913 MB RAM,
`ro.config.low_ram=true`, 96 MB heap limit, WebView **Chromium 94.0.4606.85**.

| Capability | Result | Notes |
|---|---|---|
| App launch, browsing, tabs, menus | Works | |
| JavaScript, modern JS (ES2017) | PASS | `async`/`await` parsed |
| Cookies, localStorage, sessionStorage | PASS | |
| IndexedDB, WebSockets, Fetch | PASS | |
| HTML5 video (H.264), audio (MP3) | PASS | `canPlayType` = "probably" |
| Media Source Extensions | PASS | |
| File upload, fullscreen | PASS | |
| Service Workers, getUserMedia | PASS | |
| CSS Grid | PASS | |
| **Widevine DRM** | **FAIL** | No CDM. DRM streaming cannot play. |
| **MediaSession API** | **FAIL** | Absent from WebView; notification falls back to the page title. |
| **WebGL** | **FAIL** | Context creation failed. |
| **Certificate trust store** | **Outdated** | Fixed on-device by installing ISRG Root X1; see below. |
| WebView package integrity | **Was corrupt** | `libwebviewchromium.so` had bit-rotted, crashing every WebView app with SIGBUS. See "When every WebView app dies instantly". |

After sideloading a clean **WebView 95.0.4638.74**, the device runs stably and
the compatibility banner correctly disappears (95 clears the modern baseline).
Re-running the capability test on 95 changed none of the failures above:
**Widevine, MediaSession and WebGL fail on both Chromium 56 and 95**, which
establishes them as device and WebView-embedder limits rather than engine-version
limits. In particular, Spotify Web cannot play on this handset at any engine
version, because there is no Widevine CDM to decrypt with.

Two things stand out.

**The engine is fine; the trust store is not.** Chromium 94 is well past ReWeb's
modern baseline, yet HTTPS fails on a large share of the web. These are updated by
completely different mechanisms — the WebView is an APK, the CA list is baked into
the system image — so a device can have a current engine and a decade-old set of
roots at the same time.

**Live tabs were correctly capped at 1.** `ro.config.low_ram=true` was detected and
the engine budget reduced accordingly, which is the behaviour a 913 MB device
needs.

### The trust-store problem, in detail

The device carries 162 system CAs. `ISRG Root X1` — Let's Encrypt's root — is
**absent**. `DST Root CA X3`, which expired in September 2021, is present.

For years that was survivable: Let's Encrypt cross-signed from DST Root CA X3, and
Android uniquely ignores the expiry date of a trust anchor, so old devices kept
validating the chain. [That arrangement has since ended](https://community.letsencrypt.org/t/support-for-android-7-and-older-from-oct-2024/216446),
and Let's Encrypt now serves the modern ISRG Root X1 chain by default.

The result today: **wikipedia.org fails on this device with "untrusted
authority"**, and so does anything else using Let's Encrypt. Nothing is wrong with
the site, the connection, or the browser.

**The fix, which works:** download ISRG Root X1 from
[letsencrypt.org/certificates](https://letsencrypt.org/certificates/) and install
it via *Settings → Security → Install from storage*. ReWeb trusts user-installed
certificate authorities — see [SECURITY.md](SECURITY.md) for why that is a
deliberate decision rather than a weakening.

ISRG Root X1 reached Android's own trust store in **7.1.1 (API 25)**. Android 7.0
and below are affected; 7.1.1 and above are not.

**Confirmed on the Galaxy J2:** after installing ISRG Root X1 through Settings,
wikipedia.org loads normally with a padlock, where it previously failed with
"untrusted authority". Note the device needs a screen lock set before Android will
allow a certificate to be installed at all.

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

**Measured end to end on the Galaxy J2 (Android 5.1.1, WebView 95, Premium account).**

| Step | Result |
|---|---|
| Load `open.spotify.com` | Works |
| Log in | Works — via `accounts.spotify.com`, which serves the mobile UA normally |
| Browse library, playlists, artwork | Works |
| "Unsupported browser" gate | **Bypassable** — it is a user-agent *version* check |
| Embed player (`/embed/track/...`) | Renders perfectly |
| **30-second previews** | **Play** |
| **Full DRM tracks** | **Do not play** |

Three separate things had to be understood here, and each was initially
misdiagnosed.

**1. The "Unsupported browser" wall is a version check.** Spotify's desktop player
refuses the device's real Chromium 95 user agent. Presenting a newer desktop
Chrome via *Settings → Compatibility → Default user agent → Custom* gets straight
past it. Note the trade-off: the full desktop player then renders badly, because
Chromium 95 does not implement the CSS the modern bundle assumes. The **embed
player** renders correctly and is the better target on old engines.

**2. Mobile web is browse-only by design.** Spotify's mobile site shows lyrics,
art and metadata but exposes no player, pushing users to the app. That is a
product decision, not a limitation of the browser.

**3. Full playback stops at the CDM, not at Widevine.** This device has working
Widevine: `MediaDrm` opens a session, the security level is **L3**, and every
software EME configuration is accepted by the browser (see the DRM section of
Diagnostics). Spotify's player initialises, requests a licence — and the browser
logs:

```
[ERROR:mojo_cdm.cc] Remote CDM connection error
```

The content decryption module cannot be reached, so the licence request fails and
Spotify falls back to the unencrypted 30-second preview, which plays with audio.
The platform's DRM is fine; the WebView's route to it is not, on this device and
version. A browser cannot supply its own CDM, so there is no app-side fix.

**What this means in practice:** on Android 5.x you can sign in, browse your
library, and hear previews. Full-catalogue playback needs the Spotify app, which
talks to the platform DRM directly instead of going through a WebView.

One ReWeb bug was found and fixed here. Spotify requests protected-media
permission while its player is initialising; ReWeb re-prompted on every load, and
a modal dialog in front of that request made the player disable itself outright
(`HarmonyError: local player is disabled`). Protected-media grants are now
remembered per origin, which is what moved the failure from "player disabled" to
"licence request attempted". Camera, microphone and location are still never
remembered — see [SECURITY.md](SECURITY.md).

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

## When every WebView app dies instantly

If ReWeb starts and vanishes with no error — and other WebView-based apps do the
same — the problem is usually not the app. On the verified Galaxy J2 this turned
out to be a **corrupt WebView APK**, and the symptom is distinctive:

```
I/WebViewFactory:   Loading com.google.android.webview version 94.0.4606.85
I/cr_LibraryLoader: Successfully loaded native library
I/Zygote:           Process 8040 exited due to signal (7)     <- SIGBUS
```

The native library loads, then the process dies on signal 7 with no Java
exception, because nothing in Java is running yet.

What made this hard to spot: the file **read back without any I/O error**. The
flash returned data happily; the data was simply wrong. Only a CRC check exposed
it:

```sh
adb pull /data/app/com.google.android.webview-1/base.apk
unzip -t base.apk
#   testing: lib/armeabi-v7a/libwebviewchromium.so
#   error:  invalid compressed data to inflate
#   bad CRC ef0c65a9  (should be 50c560dc)
```

Exactly one entry of that 47 MB archive had rotted — the 61 MB Chromium library —
and Chromium SIGBUSes when execution reaches the damaged pages. Reinstalling from
the device's own copy cannot help, because the source is the corrupt file.

**Recovery**, in order of preference:

1. **Sideload a fresh Android System WebView.** For Android 5.x the last supported
   build is **95.0.4638.74**; pick the ABI matching your device (`armeabi-v7a` for
   most legacy handsets). Install with `adb install`, or open the APK on the
   device. This restores a modern engine.
2. **Fall back to the ROM's built-in WebView** by removing the update:
   ```sh
   adb uninstall com.google.android.webview
   ```
   This reverts to whatever shipped in `/system` — on the Galaxy J2, Chromium 56.
   Everything starts working again, but a Chromium-56 engine is well below the
   modern baseline and ReWeb will show its compatibility banner accordingly.

This is worth knowing about generally: these devices have had the same flash cells
holding the same files for a decade, and a WebView package installed in 2021 has
had a long time to develop a bad block. Note that the Play Store cannot repair it
on a device where the Play Store itself no longer works.

## Reporting a problem

*Diagnostics → Run compatibility test → Copy report* produces a summary of your
device, WebView version and capability results. It deliberately omits your full
user agent, which would identify your device more precisely than anything else in
it.

Please include that report. "A site does not work" cannot be acted on without
knowing which engine it did not work on.
