# Building ReWeb

## Requirements

| | |
|---|---|
| JDK | **17** (required by Android Gradle Plugin 8.5) |
| Android SDK | Platform **34**, build-tools **34.0.0** |
| Gradle | Supplied by the wrapper — do not install it |

The JDK version governs the build only. The app still runs on API 21 devices.

Point the build at your SDK either by setting `ANDROID_HOME` or by creating
`local.properties` in the repository root:

```properties
sdk.dir=/path/to/Android/Sdk
```

`local.properties` is gitignored and must never be committed.

## Commands

```sh
./gradlew assembleDebug            # debug APK
./gradlew assembleRelease          # release APK (see Signing below)
./gradlew test                     # unit tests, all variants
./gradlew testDebugUnitTest        # unit tests, debug only
./gradlew lint                     # Android Lint
./gradlew lintDebug                # Lint, debug only
./gradlew reportApkSize            # print the size of every APK built
./gradlew connectedDebugAndroidTest  # instrumentation tests (needs a device)
./gradlew clean
```

On Windows use `gradlew.bat`.

### Output

```
app/build/outputs/apk/debug/reweb-debug.apk
app/build/outputs/apk/release/reweb-release.apk
app/build/reports/lint-results-debug.html
app/build/reports/tests/testDebugUnitTest/index.html
app/build/outputs/mapping/release/mapping.txt
```

Debug builds use the application ID `com.reweb.browser.debug` so they install
alongside a release build.

## Reproducibility

The wrapper pins both the Gradle version and its SHA-256, so a substituted
distribution fails the build rather than running silently:

```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-8.7-bin.zip
distributionSha256Sum=544c35d6bd849ae8a5ed0bcea39ba677dc40f49df7d1835561582da2009b961d
```

All dependency versions are pinned in `gradle/libs.versions.toml` — no dynamic
versions, no `+`, no snapshots. Each is held at the newest release that still
supports `minSdk 21`; raising one requires checking that floor.

## Release signing

Release builds are signed only when a keystore is supplied. **Without one the APK
still builds, but it is unsigned and Android will refuse to install it.**

### Generating a keystore

```sh
keytool -genkeypair -v \
  -keystore reweb-release.jks \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -alias reweb
```

Keep this file and its passwords safe and out of the repository. Losing it means
you can never ship an update that upgrades an existing install.

### Locally

Create `keystore.properties` in the repository root (gitignored):

```properties
storeFile=/absolute/path/to/reweb-release.jks
storePassword=...
keyAlias=reweb
keyPassword=...
```

Or set `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`
and `ANDROID_KEY_PASSWORD` in the environment. The build checks
`keystore.properties` first, then the environment, and falls back to an unsigned
build.

### In CI

Add four repository secrets under *Settings → Secrets and variables → Actions*:

| Secret | Contents |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w 0 reweb-release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | key alias |
| `ANDROID_KEY_PASSWORD` | key password |

When they are absent the release workflow still runs, emits a warning, writes an
explanation into the job summary, and names the artifact
`reweb-release-unsigned.apk` so the limitation is obvious to whoever downloads
it. The decoded keystore is deleted in an `always()` step.

## Publishing to the store

ReWeb is distributed through [store.harithkavish.com](https://store.harithkavish.com),
the same self-hosted store as Jarvis, using the same mechanism.

### How it works

`.github/workflows/publish-store.yml` is manual (`workflow_dispatch`) and does:

1. **Refuses to run without signing secrets.** More on why below.
2. Derives the next version by incrementing the patch of the latest release tag,
   so `v0.1.0` becomes `v0.1.1`. `versionCode` is computed as
   `major * 10000 + minor * 100 + patch`, which increases monotonically —
   Android rejects an update whose `versionCode` did not go up.
3. Writes both values into `app/build.gradle.kts`.
4. Runs lint and unit tests. A store build is the one that most needs these, so
   they are not skipped.
5. Assembles a **signed** release APK and verifies the signature with
   `apksigner` — an unsigned APK installs nowhere, so this is a hard gate.
6. Commits the version bump, tags a GitHub Release, and attaches the APK.
7. Clones the store repo and commits the APK to
   `apps/reweb/mobile/android/reweb-v<version>.apk`.

The store's own *Update app manifests* workflow then regenerates
`apps/reweb/mobile/android/latest.json` from the highest version present. Nothing
in this repository writes that manifest.

### Required secrets

| Secret | Contents |
|---|---|
| `STORE_REPO_PAT` | PAT with `contents: write` on `HarithKavish/store` |
| `ANDROID_KEYSTORE_BASE64` | `base64 -w 0 reweb-release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | key alias |
| `ANDROID_KEY_PASSWORD` | key password |

### Why signing is mandatory here, unlike `release.yml`

`release.yml` will happily produce an unsigned APK for local inspection. The
store workflow will not, because Android identifies an app by its signing
certificate:

- An unsigned APK cannot be installed at all.
- An APK signed with a *different* key than the previous version is, to Android,
  a different app. The install fails with a signature mismatch, and the only
  remedy for the user is to uninstall — losing their history, bookmarks and
  sign-ins.

So the same keystore must sign every published build, for the life of the app.
Generating a fresh key per release would break every existing install. Keep the
`.jks` file backed up somewhere you will still have in five years; losing it
means no existing installation can ever be updated again.

## Continuous integration

### `.github/workflows/build.yml`

Runs on every push and pull request to `main`, and on demand.

1. Check out
2. JDK 17 (Temurin)
3. Android SDK (`android-actions/setup-android`)
4. Gradle with caching (`gradle/actions/setup-gradle`) — read-only on PRs
5. `./gradlew lintDebug`
6. `./gradlew testDebugUnitTest`
7. `./gradlew assembleDebug`
8. `./gradlew reportApkSize`
9. Locate the APK, copy it to `artifacts/reweb-debug.apk`, write the size to the
   job summary
10. Upload it as **`reweb-debug-apk`** with `if-no-files-found: error`
11. Upload lint and test reports (`if: always()`)

Each stage is a separate step so a failure names what broke. **No step swallows
errors** — there is no `|| true` or equivalent anywhere in either workflow, and
the APK-location step uses `set -euo pipefail` and fails explicitly if no APK was
produced.

### `.github/workflows/release.yml`

Runs on `v*` tags and on demand: decodes the keystore if present, runs release
unit tests, assembles the release APK, uploads it plus the R8 mapping file, and
optionally attaches it to a draft GitHub Release.

### Downloading the APK

Actions → the run → **Artifacts** → `reweb-debug-apk`. GitHub serves artifacts as
a zip; the `.apk` is inside. Retention is 30 days for debug, 90 for release.

## Release build optimisation

R8 with resource shrinking is on for release. `proguard-rules.pro` keeps only
what the framework instantiates by name (views inflated from XML, manifest
components, `MediaSessionCompat`'s reflective callbacks) and strips `Log.v`/`Log.d`
via `-assumenosideeffects`.

`resourceConfigurations` is limited to English, since the app ships English-only
strings. The APK contains no native code, so there is nothing to split on ABI.

Run `./gradlew reportApkSize` after any build to print the exact size; CI prints
it too and puts it in the job summary.

## Troubleshooting

**`SDK location not found`** — set `ANDROID_HOME` or create `local.properties`.

**`Unsupported class file major version`** — you are not on JDK 17. Check
`java -version` and `JAVA_HOME`.

**`Failed to install the following Android SDK packages`** — accept the licences:
```sh
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
```

**Wrapper checksum mismatch** — the downloaded distribution does not match the
pinned SHA-256. Do not delete the pin; investigate. Clear
`~/.gradle/wrapper/dists` and retry on a trusted network.

**Configuration cache errors after editing the build script** — `./gradlew clean`
or add `--no-configuration-cache` for one run.
