package com.reweb.browser.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.WebView
import com.reweb.browser.browser.UserAgent

/**
 * Facts about the rendering engine actually installed on this device.
 *
 * This is the information the whole app is organised around: on these phones the
 * difference between "ReWeb is broken" and "this device's WebView is from 2017"
 * is the first thing anyone needs to know.
 */
data class WebViewInfo(
    val packageName: String?,
    val packageVersion: String?,
    val chromiumMajorVersion: Int?,
    val chromiumFullVersion: String?,
    val userAgent: String,
    val isUpdatable: Boolean
) {
    /**
     * Chromium builds older than this predate the baseline modern sites assume:
     * ES2017 async/await, CSS grid, fetch, and the Web APIs most single-page
     * applications are compiled against. Below it, breakage is expected and is a
     * platform property, not an app defect.
     */
    val isBelowModernBaseline: Boolean
        get() = chromiumMajorVersion != null && chromiumMajorVersion < MODERN_BASELINE

    val isSeverelyOutdated: Boolean
        get() = chromiumMajorVersion != null && chromiumMajorVersion < SEVERELY_OUTDATED_BASELINE

    companion object {
        const val MODERN_BASELINE = 80
        const val SEVERELY_OUTDATED_BASELINE = 60

        private val KNOWN_WEBVIEW_PACKAGES = listOf(
            "com.google.android.webview",
            "com.android.webview",
            "com.android.chrome",
            "org.chromium.webview_shell"
        )

        /**
         * Reads the installed WebView. Must be called after a WebView has been
         * constructed at least once, or [userAgentFallback] is used.
         */
        fun read(context: Context, userAgentFallback: String? = null): WebViewInfo {
            val userAgent = userAgentFallback
                ?: runCatching { WebView(context).let { view -> view.settings.userAgentString.also { view.destroy() } } }
                    .getOrNull()
                ?: System.getProperty("http.agent").orEmpty()

            val packageInfo = currentWebViewPackage(context)
            return WebViewInfo(
                packageName = packageInfo?.first,
                packageVersion = packageInfo?.second,
                chromiumMajorVersion = UserAgent.chromeMajorVersion(userAgent),
                chromiumFullVersion = UserAgent.fullChromeVersion(userAgent),
                userAgent = userAgent,
                // Below API 21 the WebView is baked into the system image. From 21
                // on it is a separately updatable APK, which is the entire reason
                // API 21 is a workable minimum for this app.
                isUpdatable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
            )
        }

        /** Package name and version of the WebView provider, or null if unknown. */
        private fun currentWebViewPackage(context: Context): Pair<String, String?>? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val info = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
                if (info != null) return info.packageName to info.versionName
            }
            // Pre-O there is no API for this, so probe the packages the platform
            // actually ships as WebView providers.
            val packageManager = context.packageManager
            for (candidate in KNOWN_WEBVIEW_PACKAGES) {
                val info = runCatching {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(candidate, 0)
                }.getOrNull()
                if (info != null) return candidate to info.versionName
            }
            return null
        }

        fun readNotAvailable(): WebViewInfo = WebViewInfo(
            packageName = null,
            packageVersion = null,
            chromiumMajorVersion = null,
            chromiumFullVersion = null,
            userAgent = "",
            isUpdatable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
        )

        /** True if the device has no usable WebView provider at all. */
        fun isWebViewMissing(context: Context): Boolean =
            runCatching {
                val view = WebView(context)
                view.destroy()
                false
            }.getOrElse { error ->
                // A missing or mid-update WebView package throws here rather than
                // returning null, and it is a genuinely reachable state on old
                // devices whose WebView is being updated by the Play Store.
                error is android.util.AndroidRuntimeException ||
                    error is UnsupportedOperationException ||
                    error is PackageManager.NameNotFoundException ||
                    error is RuntimeException
            }
    }
}
