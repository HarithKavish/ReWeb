# Security

ReWeb's threat model starts from one assumption: **every page is hostile.** A
browser's entire job is to run code written by strangers, so the question is
never whether untrusted code runs, only what it can reach.

---

## Transport security

**Certificate validation is never disabled.** There is no `acceptAllCertificates`,
no `ignoreSslErrors`, no debug-only trust override, and no code path anywhere
that calls `SslErrorHandler.proceed()` without an explicit user action.

`ReWebWebViewClient.onReceivedSslError` hands the decision up as an
engine-neutral `SslDecision` and never answers it itself. The UI shows an
interstitial naming the host and the specific failure — expired, hostname
mismatch, untrusted authority, invalid dates — with "Go back" as the default
action. Choosing to continue requires a **second** confirmation. Any exception is
scoped to one host, held in memory only, and gone when the process ends. Nothing
is written to disk.

The URL bar shows a distinct icon for a bypassed certificate for as long as the
exception is in force.

**HTTPS is never silently downgraded.** An `https://` URL is never rewritten to
`http://`. Cleartext is permitted at the platform level because a browser must be
able to load a URL you explicitly typed, but such pages are marked "Not secure".

**User-installed certificate authorities are trusted.** From API 24 an app stops
trusting user-added roots unless it opts back in, and ReWeb opts back in.

This is a deliberate decision, and on the target hardware it is the difference
between a working browser and a useless one. Android 7.0 and older ship their root
store inside the system image, where it is never updated, and it predates ISRG
Root X1 — the Let's Encrypt root that now secures a large fraction of the web. On
a verified Android 5.1.1 device this makes ordinary sites, Wikipedia among them,
fail with "untrusted authority". Without this opt-in the user would have no
remedy at all: they could install the missing root through Android Settings and
ReWeb would still reject it.

The trade-off is understood. A user-installed root can sign for any host, so a
user who is tricked or coerced into installing one can be intercepted. That is
equally true of Chrome, which trusts user roots for the same reason, and it
requires a deliberate trip through Settings. Set against a browser that cannot
open Wikipedia, and cannot be repaired by its owner, the balance is not close.

Nothing else about validation changes: chain building and hostname verification
are untouched, and failures still produce the interstitial described above.

**Mixed content** uses `MIXED_CONTENT_COMPATIBILITY_MODE`: passive content loads,
active content is blocked. `NEVER_ALLOW` breaks too much of the real web to be
usable; `ALWAYS_ALLOW` would silently weaken every HTTPS page.

---

## The JavaScript bridge

ReWeb exposes exactly **one** object to web content, and it takes four primitives:

```kotlin
@JavascriptInterface
fun report(playing: Boolean, title: String?, artist: String?, pageUrl: String?)
```

**Why it exists.** The platform gives an embedder no other way to observe media
playback. `WebChromeClient` reports no playback state, and
`AudioManager`'s playback callbacks are API 26+ and carry no metadata. Without
this, background audio and lock-screen controls cannot work at all.

**What a hostile page can do with it.** Lie about what is playing. The worst
outcome is an incorrect media notification. The bridge holds no reference to any
store, activity, file, or intent; it reads nothing and returns nothing.

**Mitigations.** Strings are length-capped (200 characters for metadata, 2048 for
the URL) and never interpreted as markup. The call arrives on a WebView
JavaScript thread and is posted to the view thread before touching state, with a
destroyed-engine check.

No other `addJavascriptInterface` call exists in the codebase.

---

## What web content cannot reach

```kotlin
settings.allowFileAccess = false
settings.allowContentAccess = false
settings.allowFileAccessFromFileURLs = false
settings.allowUniversalAccessFromFileURLs = false
```

Web content gets no filesystem reach and no `content://` provider access. A page
cannot read ReWeb's own data directory, its database, or another origin's files.

Error and interstitial documents are loaded with a **null base URL**, giving them
an opaque origin, so an error page cannot read storage or cookies belonging to
the site it replaced. Everything interpolated into one — the URL, the engine's
description — is HTML-escaped first, and that escaping is unit-tested against
injection attempts.

---

## `intent:` URIs

A web page can construct an arbitrary Android Intent through an `intent:` URI.
Unsanitised, that lets any website start components it should never reach —
including ReWeb's own non-exported activities.

`ExternalIntents.sanitize` removes exactly what makes that possible:

| Stripped | Why |
|---|---|
| `component` | An explicit component bypasses the exported/permission checks implicit resolution performs |
| `selector` | Can smuggle in a second, unsanitised intent |
| `FLAG_GRANT_*_URI_PERMISSION` | Would hand a page access to URIs this process can read |
| **Added:** `CATEGORY_BROWSABLE` | Only components that opted into being started from web content are reachable |

`S.browser_fallback_url` is honoured only when it is `http` or `https`, so a
fallback cannot chain into another scheme handoff. Every dispatch is wrapped
against `ActivityNotFoundException` and `SecurityException`, so a missing app
produces a message rather than a crash. This is tested.

Address-bar input is subject to the same reasoning: `javascript:` URLs are
classified as searches, never navigated to, because pasting one into an address
bar is a classic self-XSS vector.

---

## Downloads

A server controls both the URL and the `Content-Disposition` header, so a
filename from either is untrusted input.

`DownloadNaming.sanitize` reduces any string to a single safe path component:
directory separators removed (so `../../../etc/passwd` collapses to `passwd`),
control and reserved characters stripped or replaced, leading dots removed so no
hidden file can be created, Windows reserved device names defused, and length
capped while preserving the extension. Path traversal is directly unit-tested.

The user-configurable download folder gets the same treatment — parent references
and absolute paths cannot escape the Downloads collection.

**APKs are never installed automatically.** A downloaded APK is saved like any
other file; opening it hands it to Android's installer, where the user confirms.
The download prompt says so explicitly.

Downloads go through the platform's DownloadManager, so ReWeb runs no HTTP stack
of its own. Session cookies are forwarded so authenticated downloads work — and
are never logged.

---

## Permissions

Nothing is requested at startup. `CAMERA`, `RECORD_AUDIO` and location are
declared but only ever requested when a page asks and the user approves.

Two gates, in order:

1. **The site prompt**, naming the origin and the capability. Answers are **not
   remembered** — ReWeb has no per-origin permission store, and silently granting
   on a later visit would be worse than asking again.
2. **Android's runtime permission**, if the capability needs one. Refusing here
   denies the site rather than leaving it waiting.

There is no path through `PermissionCoordinator` that grants without a user tap.
Every request object answers exactly once; dismissing a prompt denies rather than
dropping it, so a page cannot be left hanging on an unanswered request. The same
discipline applies to file choosers, JS dialogs and certificate decisions.

Geolocation grants use `retain = false`, so a site cannot acquire permanent
location access from one tap.

File uploads use the Storage Access Framework and request **no storage
permission**: the result URI carries its own grant.

---

## What is never logged

Passwords, cookies, authentication tokens, OAuth authorization codes, redirect
URLs, and URLs containing credentials.

Release builds set `VERBOSE_LOGGING = false`, and R8 strips `Log.v` and `Log.d`
entirely via `-assumenosideeffects`. The OAuth redirect handler passes the
redirect URL straight through without logging or storing it, and it never enters
history.

The Diagnostics "copy report" output deliberately omits the full user agent,
which identifies a device more precisely than anything else in the report and
which people paste into public issue trackers.

---

## Data at rest

Everything is local. No account, no sync, no server, no telemetry, no analytics,
no crash reporting, no advertising SDK.

Backup and device transfer are **disabled** (`allowBackup="false"` plus explicit
exclusion rules), because copying a browser profile into a cloud backup moves
live session tokens off the device.

ReWeb stores no passwords, and `saveFormData` is off so typed values do not land
in the WebView's own cache.

### Private browsing, stated accurately

Below API 34 the platform provides one WebView cookie jar and one storage area
per process; `Profile` isolation does not exist on any device this app targets.
Private tabs therefore get no history, no disk cache, no form data, and session
cookies removed when the last one closes — but a *persistent* cookie set by a
private page shares the single jar. Removing it would also destroy the equivalent
cookie for normal browsing.

This is documented in the UI, in the code, and in
[COMPATIBILITY.md](COMPATIBILITY.md), rather than implied to be complete
isolation.

---

## Authentication

ReWeb does not implement Google sign-in inside the WebView, and does not attempt
to defeat any provider's embedded-browser policy. Those policies are
anti-phishing measures, and an embedding app *can* read what is typed into a
WebView, so they are well founded.

The default user agent removes the `; wv` and `Version/4.0` tokens that mark an
embedded view, because ReWeb is a browser rather than an app embedding a web view
for its own content. Every other component — including the real Chromium version
— is left exactly as the device reports it. Claiming a newer engine would make
sites serve JavaScript the device cannot parse, which fails worse than being
served the legacy path.

The handoff to a Custom Tab is offered, with its limitation stated up front: the
resulting cookies live in that browser and Android does not let apps share them.

---

## Process isolation

`onRenderProcessGone` returns `true`, so a renderer crash kills the tab and not
the application. Without that, any page could take the whole browser down. The
tab is rebuilt around a fresh engine with an explanatory page.

---

## Reporting a vulnerability

Open a GitHub issue for anything low-risk. For something exploitable, please
report it privately through GitHub's security advisory feature on this
repository rather than in a public issue.
