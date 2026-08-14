package com.reweb.browser.intents

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.reweb.browser.browser.UrlUtils

/**
 * Handles URLs that belong to another application.
 *
 * The hard part is `intent:` URIs. They are a general-purpose way for a web page
 * to construct an Android Intent, so an unsanitised one lets any website start
 * any component this app can reach — including ReWeb's own non-exported
 * activities and content providers. [sanitize] strips exactly the pieces that
 * make that possible, which is the same hardening Chrome applies.
 */
object ExternalIntents {

    sealed class Outcome {
        object Launched : Outcome()
        /** No installed app can handle it; [fallbackUrl] is the page's own fallback. */
        data class NoHandler(val scheme: String, val fallbackUrl: String?) : Outcome()
        /** The URI could not be parsed at all. */
        object Malformed : Outcome()
        /** ReWeb should load [url] itself instead of handing it off. */
        data class HandleInBrowser(val url: String) : Outcome()
    }

    /**
     * Removes everything that would let page-supplied content reach a component
     * of ReWeb's choosing rather than a public app entry point.
     */
    fun sanitize(intent: Intent): Intent = intent.apply {
        // An explicit component would bypass the exported/permission checks that
        // implicit resolution performs.
        component = null
        // A selector can smuggle in a second, unsanitised intent.
        selector = null
        // BROWSABLE is what marks a target as safe to start from web content.
        addCategory(Intent.CATEGORY_BROWSABLE)
        // Never let a page hand out access to URIs this process can read.
        flags = flags and
            Intent.FLAG_GRANT_READ_URI_PERMISSION.inv() and
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION.inv() and
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION.inv() and
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION.inv() and
            Intent.FLAG_ACTIVITY_NEW_TASK.inv()
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Parses an `intent:` URI into a launchable Intent, or null if malformed. */
    fun parseIntentUri(url: String): Intent? = runCatching {
        sanitize(Intent.parseUri(url, Intent.URI_INTENT_SCHEME))
    }.getOrNull()

    /**
     * The `S.browser_fallback_url` extra an `intent:` URI may carry, used when no
     * app is installed. Only http/https fallbacks are accepted, so the fallback
     * cannot itself be another scheme handoff.
     */
    fun fallbackUrlOf(intent: Intent): String? {
        val fallback = runCatching { intent.getStringExtra("browser_fallback_url") }.getOrNull()
            ?: return null
        return fallback.takeIf { UrlUtils.isHttpScheme(it) }
    }

    /**
     * Attempts to hand [url] to another app.
     *
     * Returns [Outcome.HandleInBrowser] for http/https so callers can use a single
     * code path for every navigation.
     */
    fun launch(context: Context, url: String): Outcome {
        val scheme = UrlUtils.schemeOf(url) ?: return Outcome.Malformed

        if (scheme == "http" || scheme == "https") return Outcome.HandleInBrowser(url)

        if (scheme == "intent") {
            val intent = parseIntentUri(url) ?: return Outcome.Malformed
            val fallback = fallbackUrlOf(intent)
            if (!canResolve(context, intent)) return Outcome.NoHandler(scheme, fallback)
            return try {
                context.startActivity(intent)
                Outcome.Launched
            } catch (_: ActivityNotFoundException) {
                Outcome.NoHandler(scheme, fallback)
            } catch (_: SecurityException) {
                Outcome.NoHandler(scheme, fallback)
            }
        }

        val intent = sanitize(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        if (!canResolve(context, intent)) return Outcome.NoHandler(scheme, null)
        return try {
            context.startActivity(intent)
            Outcome.Launched
        } catch (_: ActivityNotFoundException) {
            Outcome.NoHandler(scheme, null)
        } catch (_: SecurityException) {
            Outcome.NoHandler(scheme, null)
        }
    }

    /**
     * Whether any installed app declares a handler.
     *
     * On API 30+ this is subject to package-visibility filtering, so it can return
     * false for an app that is actually installed but not declared in <queries>.
     * The caller therefore still guards startActivity with a try/catch rather than
     * trusting this alone.
     */
    fun canResolve(context: Context, intent: Intent): Boolean = runCatching {
        val packageManager = context.packageManager
        // The ResolveInfoFlags overload is API 33+; the version check has to wrap
        // the call itself, not just the flags, or the old path resolves against a
        // method that does not exist below 33.
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        resolved.isNotEmpty()
    }.getOrDefault(false)

    /**
     * `spotify:` URIs open the Spotify app when it is installed. When it is not,
     * the equivalent open.spotify.com page is a genuine web fallback, so ReWeb
     * offers that rather than a dead end.
     *
     * Returns null when no web equivalent exists.
     */
    fun webEquivalentFor(url: String): String? {
        val scheme = UrlUtils.schemeOf(url) ?: return null
        return when (scheme) {
            "spotify" -> {
                // spotify:track:ID -> https://open.spotify.com/track/ID
                val path = url.removePrefix("spotify:").replace(':', '/')
                if (path.isBlank()) null else "https://open.spotify.com/$path"
            }
            else -> null
        }
    }
}
