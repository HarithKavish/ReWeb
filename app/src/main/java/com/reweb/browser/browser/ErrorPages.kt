package com.reweb.browser.browser

import android.content.Context
import com.reweb.browser.R
import com.reweb.browser.engine.ErrorKind
import com.reweb.browser.engine.PageError
import com.reweb.browser.engine.SslIssue
import com.reweb.browser.engine.SslIssueKind

/**
 * Builds the documents ReWeb renders in place of a failed page.
 *
 * Every failure gets a specific cause and a specific next step. "Something went
 * wrong" tells a user nothing, and on these devices the distinction that matters
 * most — is this the site, my connection, or is my WebView simply too old? — is
 * one only the browser can make.
 *
 * The generated HTML is loaded with a null base URL, so it runs in an opaque
 * origin and cannot touch the failed site's storage.
 */
object ErrorPages {

    /**
     * Everything interpolated into a page goes through this first. The URL and
     * the engine's description are attacker-influenced strings.
     */
    fun escapeHtml(raw: String): String = buildString(raw.length) {
        for (ch in raw) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }

    data class ErrorCopy(val title: String, val explanation: String, val suggestion: String)

    fun copyFor(context: Context, error: PageError): ErrorCopy {
        val host = UrlUtils.hostOf(error.url) ?: error.url
        return when (error.kind) {
            ErrorKind.NO_NETWORK -> ErrorCopy(
                context.getString(R.string.error_no_network_title),
                context.getString(R.string.error_no_network_body),
                context.getString(R.string.error_no_network_hint)
            )
            ErrorKind.DNS_FAILURE -> ErrorCopy(
                context.getString(R.string.error_dns_title),
                context.getString(R.string.error_dns_body, host),
                context.getString(R.string.error_dns_hint)
            )
            ErrorKind.CONNECTION_REFUSED -> ErrorCopy(
                context.getString(R.string.error_refused_title),
                context.getString(R.string.error_refused_body, host),
                context.getString(R.string.error_refused_hint)
            )
            ErrorKind.CONNECTION_RESET -> ErrorCopy(
                context.getString(R.string.error_reset_title),
                context.getString(R.string.error_reset_body, host),
                context.getString(R.string.error_reset_hint)
            )
            ErrorKind.TIMEOUT -> ErrorCopy(
                context.getString(R.string.error_timeout_title),
                context.getString(R.string.error_timeout_body, host),
                context.getString(R.string.error_timeout_hint)
            )
            ErrorKind.TLS_FAILURE -> ErrorCopy(
                context.getString(R.string.error_tls_title),
                context.getString(R.string.error_tls_body, host),
                // The single most common real cause on a legacy device.
                context.getString(R.string.error_tls_hint)
            )
            ErrorKind.TOO_MANY_REDIRECTS -> ErrorCopy(
                context.getString(R.string.error_redirect_title),
                context.getString(R.string.error_redirect_body, host),
                context.getString(R.string.error_redirect_hint)
            )
            ErrorKind.UNSUPPORTED_SCHEME -> ErrorCopy(
                context.getString(R.string.error_scheme_title),
                context.getString(R.string.error_scheme_body),
                context.getString(R.string.error_scheme_hint)
            )
            ErrorKind.FILE_NOT_FOUND -> ErrorCopy(
                context.getString(R.string.error_not_found_title),
                context.getString(R.string.error_not_found_body, host),
                context.getString(R.string.error_not_found_hint)
            )
            ErrorKind.TOO_MANY_REQUESTS -> ErrorCopy(
                context.getString(R.string.error_rate_limited_title),
                context.getString(R.string.error_rate_limited_body, host),
                context.getString(R.string.error_rate_limited_hint)
            )
            ErrorKind.HTTP_ERROR -> ErrorCopy(
                context.getString(R.string.error_http_title, error.httpStatusCode),
                context.getString(R.string.error_http_body, host),
                context.getString(R.string.error_http_hint)
            )
            ErrorKind.UNKNOWN -> ErrorCopy(
                context.getString(R.string.error_unknown_title),
                context.getString(R.string.error_unknown_body, host),
                context.getString(R.string.error_unknown_hint)
            )
        }
    }

    fun networkError(context: Context, error: PageError): String {
        val copy = copyFor(context, error)
        val detail = error.description.takeIf { it.isNotBlank() }
        return document(
            accent = ACCENT_NEUTRAL,
            title = copy.title,
            body = copy.explanation,
            hint = copy.suggestion,
            url = error.url,
            technicalDetail = detail,
            actionLabel = context.getString(R.string.action_try_again),
            actionScript = "location.reload()"
        )
    }

    /**
     * Certificate interstitial. Deliberately has no inline "proceed" control: the
     * bypass lives in the app's own UI, behind a confirmation, so that a page
     * cannot script its way past the warning.
     */
    fun sslWarning(context: Context, issue: SslIssue): String {
        val reason = when (issue.kind) {
            SslIssueKind.EXPIRED -> context.getString(R.string.ssl_expired)
            SslIssueKind.NOT_YET_VALID -> context.getString(R.string.ssl_not_yet_valid)
            SslIssueKind.HOSTNAME_MISMATCH -> context.getString(R.string.ssl_hostname_mismatch)
            SslIssueKind.UNTRUSTED_AUTHORITY -> context.getString(R.string.ssl_untrusted)
            SslIssueKind.INVALID_DATE -> context.getString(R.string.ssl_invalid_date)
            SslIssueKind.UNKNOWN -> context.getString(R.string.ssl_unknown)
        }
        val detail = buildString {
            issue.certificateSubject?.let { append(context.getString(R.string.ssl_issued_to, it)) }
            issue.certificateIssuer?.let {
                if (isNotEmpty()) append('\n')
                append(context.getString(R.string.ssl_issued_by, it))
            }
        }.ifBlank { null }

        return document(
            accent = ACCENT_DANGER,
            title = context.getString(R.string.ssl_warning_title),
            body = context.getString(R.string.ssl_warning_body, UrlUtils.hostOf(issue.url) ?: issue.url, reason),
            hint = context.getString(R.string.ssl_warning_hint),
            url = issue.url,
            technicalDetail = detail,
            actionLabel = null,
            actionScript = null
        )
    }

    /**
     * Shown when a page fails in a way that points at the rendering engine rather
     * than the network — the distinction the whole app exists to make clear.
     */
    fun platformLimitation(
        context: Context,
        url: String,
        chromiumVersion: String?,
        detail: String?
    ): String = document(
        accent = ACCENT_WARNING,
        title = context.getString(R.string.compat_limitation_title),
        body = context.getString(
            R.string.compat_limitation_body,
            UrlUtils.hostOf(url) ?: url,
            chromiumVersion ?: context.getString(R.string.value_unknown)
        ),
        hint = context.getString(R.string.compat_limitation_hint),
        url = url,
        technicalDetail = detail,
        actionLabel = null,
        actionScript = null
    )

    fun rendererCrashed(context: Context, url: String): String = document(
        accent = ACCENT_WARNING,
        title = context.getString(R.string.error_renderer_title),
        body = context.getString(R.string.error_renderer_body, UrlUtils.hostOf(url) ?: url),
        hint = context.getString(R.string.error_renderer_hint),
        url = url,
        technicalDetail = null,
        actionLabel = context.getString(R.string.action_reload_page),
        actionScript = "location.replace(${quoteForJs(url)})"
    )

    private fun quoteForJs(raw: String): String =
        "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "\\u003C") + "\""

    private const val ACCENT_NEUTRAL = "#5b6b7f"
    private const val ACCENT_WARNING = "#b06f16"
    private const val ACCENT_DANGER = "#b3261e"

    /**
     * A single self-contained template. No external CSS, fonts or images: an
     * error page that needs the network to render is not an error page.
     */
    private fun document(
        accent: String,
        title: String,
        body: String,
        hint: String,
        url: String,
        technicalDetail: String?,
        actionLabel: String?,
        actionScript: String?
    ): String {
        val safeUrl = escapeHtml(url)
        val detailBlock = technicalDetail
            ?.takeIf { it.isNotBlank() }
            ?.let { "<pre class=\"detail\">${escapeHtml(it)}</pre>" }
            .orEmpty()
        val actionBlock = if (actionLabel != null && actionScript != null) {
            "<button type=\"button\" onclick='$actionScript'>${escapeHtml(actionLabel)}</button>"
        } else {
            ""
        }

        return """
            <!DOCTYPE html>
            <html><head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>${escapeHtml(title)}</title>
            <style>
              :root { color-scheme: light dark; }
              body {
                margin: 0; padding: 24px;
                font-family: sans-serif; font-size: 16px; line-height: 1.5;
                color: #1b1b1b; background: #ffffff;
              }
              .bar { height: 4px; background: $accent; border-radius: 2px; margin-bottom: 20px; }
              h1 { font-size: 20px; margin: 0 0 12px; color: $accent; }
              p { margin: 0 0 12px; }
              .hint { color: #4a4a4a; }
              .url {
                font-size: 13px; color: #5a5a5a; word-break: break-all;
                border-top: 1px solid #e0e0e0; padding-top: 12px; margin-top: 20px;
              }
              .detail {
                font-size: 12px; white-space: pre-wrap; word-break: break-word;
                background: #f3f3f3; padding: 10px; border-radius: 4px; color: #444;
              }
              button {
                margin-top: 8px; padding: 10px 18px; font-size: 15px;
                border: 0; border-radius: 4px; background: $accent; color: #fff;
              }
              @media (prefers-color-scheme: dark) {
                body { color: #e8e8e8; background: #121212; }
                .hint { color: #b5b5b5; }
                .url { color: #9a9a9a; border-top-color: #333; }
                .detail { background: #1e1e1e; color: #c8c8c8; }
              }
            </style>
            </head><body>
              <div class="bar"></div>
              <h1>${escapeHtml(title)}</h1>
              <p>${escapeHtml(body)}</p>
              <p class="hint">${escapeHtml(hint)}</p>
              $actionBlock
              $detailBlock
              <p class="url">$safeUrl</p>
            </body></html>
        """.trimIndent()
    }
}
