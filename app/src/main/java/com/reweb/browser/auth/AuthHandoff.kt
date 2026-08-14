package com.reweb.browser.auth

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.reweb.browser.browser.UrlUtils
import java.util.Locale

/**
 * Detects OAuth sign-in pages and, when asked, runs them in a real browser
 * instead of inside the WebView.
 *
 * ## Why this exists
 *
 * Google (and increasingly Microsoft, Apple and others) refuse to serve their
 * authorization endpoints to embedded user agents, returning a
 * "this browser or app may not be secure" page. That policy is deliberate: an
 * embedding app can read everything typed into a WebView, so an embedded sign-in
 * form is indistinguishable from a phishing page. ReWeb does not try to defeat
 * that check.
 *
 * ## What the handoff can and cannot do
 *
 * Handing the flow to a Custom Tab makes the sign-in itself succeed, because it
 * runs in a genuine browser. What it *cannot* do on a legacy device is bring the
 * resulting session back: the cookies the provider sets land in the external
 * browser's cookie jar, and no API lets one app read another's. So the flow ends
 * with the user signed in *in that browser*, not in ReWeb.
 *
 * The handoff is therefore genuinely useful for flows that redirect back to a
 * URL ReWeb can capture — a custom scheme or an https callback ReWeb handles —
 * where the callback carries the credential in the URL and the originating page
 * completes the exchange itself. It does not help cookie-only flows. Both cases
 * are spelled out to the user before the handoff, and in COMPATIBILITY.md.
 *
 * No token, authorization code or redirect URL is ever logged.
 */
object AuthHandoff {

    /** The scheme registered by [AuthRedirectActivity] for capturing redirects. */
    const val CALLBACK_SCHEME = "reweb"
    const val CALLBACK_HOST = "auth"
    const val CALLBACK_URL = "$CALLBACK_SCHEME://$CALLBACK_HOST"

    /**
     * Hosts whose authorization endpoints are known to reject embedded browsers.
     * Matching is on the registrable host suffix.
     */
    private val KNOWN_OAUTH_HOSTS = setOf(
        "accounts.google.com",
        "accounts.youtube.com",
        "appleid.apple.com",
        "login.microsoftonline.com",
        "login.live.com",
        "login.yahoo.com",
        "www.facebook.com",
        "m.facebook.com",
        "github.com",
        "gitlab.com",
        "auth0.com",
        "okta.com",
        "id.twitch.tv",
        "discord.com",
        "www.linkedin.com",
        "api.twitter.com",
        "x.com"
    )

    /** Path fragments that mark an OAuth/OIDC authorization request. */
    private val AUTHORIZATION_PATH_MARKERS = listOf(
        "/o/oauth2/", "/oauth2/", "/oauth/", "/authorize", "/auth/authorize",
        "/signin/oauth", "/login/oauth", "/connect/authorize", "/v2/auth",
        "/dialog/oauth", "/oidc/"
    )

    /** Query parameters that only an OAuth authorization request carries. */
    private val AUTHORIZATION_QUERY_MARKERS = listOf(
        "response_type=", "client_id=", "redirect_uri="
    )

    /**
     * True when [url] looks like the start of an OAuth authorization flow that an
     * embedded engine is likely to be refused.
     *
     * Conservative on purpose: a false positive interrupts a working page with an
     * unnecessary prompt, which is worse than a false negative (the user simply
     * sees the provider's own "not secure" page and can hand off manually).
     */
    fun isOAuthAuthorizationUrl(url: String): Boolean {
        if (!UrlUtils.isHttpScheme(url)) return false
        val lower = url.lowercase(Locale.US)
        val host = UrlUtils.hostOf(url) ?: return false

        val hostMatches = KNOWN_OAUTH_HOSTS.any { known ->
            val normalized = known.removePrefix("www.")
            host == normalized || host.endsWith(".$normalized")
        }
        if (!hostMatches) return false

        val path = lower.substringAfter("://").substringAfter('/', "")
        val hasPathMarker = AUTHORIZATION_PATH_MARKERS.any { marker ->
            lower.contains(marker)
        }
        val hasQueryMarker = AUTHORIZATION_QUERY_MARKERS.count { lower.contains(it) } >= 2

        // Require a path marker AND the query shape, so that simply visiting
        // github.com or facebook.com does not trigger a handoff prompt.
        return hasPathMarker && (hasQueryMarker || path.startsWith("o/oauth2"))
    }

    /**
     * Whether the flow can return to ReWeb. True when the redirect target is
     * ReWeb's own callback scheme; those are the flows where a handoff actually
     * restores state to the originating page.
     */
    fun redirectsBackToApp(url: String): Boolean {
        val redirectUri = queryParameter(url, "redirect_uri") ?: return false
        return redirectUri.startsWith("$CALLBACK_SCHEME://", ignoreCase = true)
    }

    fun queryParameter(url: String, name: String): String? = runCatching {
        Uri.parse(url).getQueryParameter(name)
    }.getOrNull()

    /**
     * Opens [url] in a Custom Tab, falling back to whichever browser the user has
     * set as default.
     *
     * Returns false when the device has no other browser at all, which is common
     * on stripped-down legacy ROMs — the caller must then keep the flow in the
     * WebView and tell the user it may be refused.
     */
    fun launchExternal(activity: Activity, url: String): Boolean {
        if (!UrlUtils.isHttpScheme(url)) return false
        val uri = Uri.parse(url)

        val customTabsIntent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setUrlBarHidingEnabled(false)
            .build()
        customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (runCatching { customTabsIntent.launchUrl(activity, uri) }.isSuccess) return true

        // No Custom Tabs provider: try a plain VIEW intent, but exclude ReWeb so
        // the navigation cannot bounce straight back into the WebView it came from.
        return launchInOtherBrowser(activity, uri)
    }

    private fun launchInOtherBrowser(activity: Activity, uri: Uri): Boolean {
        val viewIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val others = externalBrowserPackages(activity, uri)
        if (others.isEmpty()) return false

        // If exactly one other browser exists, target it directly; otherwise let
        // the user choose, which also avoids ReWeb being picked again by default.
        return try {
            if (others.size == 1) {
                viewIntent.setPackage(others.first())
                activity.startActivity(viewIntent)
            } else {
                activity.startActivity(Intent.createChooser(viewIntent, null))
            }
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    /** Packages other than ReWeb that can open [uri]. */
    fun externalBrowserPackages(context: Context, uri: Uri): List<String> = runCatching {
        val probe = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        @Suppress("DEPRECATION")
        context.packageManager.queryIntentActivities(probe, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()
            .filterNot { it == context.packageName }
    }.getOrDefault(emptyList())

    /** True when at least one other browser or Custom Tabs provider is installed. */
    fun hasExternalBrowser(context: Context): Boolean =
        externalBrowserPackages(context, Uri.parse("https://example.com")).isNotEmpty()
}
