package com.reweb.browser.engine.webview

import android.graphics.Bitmap
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Build
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.reweb.browser.engine.ErrorKind
import com.reweb.browser.engine.PageError
import com.reweb.browser.engine.SslDecision
import com.reweb.browser.engine.SslIssue
import com.reweb.browser.engine.SslIssueKind

/**
 * Translates WebViewClient callbacks into engine-neutral events.
 *
 * Two behaviours here are load-bearing for the app's security posture:
 *  - [onReceivedSslError] never calls `handler.proceed()`. It hands the decision
 *    up to the UI, which shows an interstitial. There is no global bypass.
 *  - [onRenderProcessGone] returns true, so a renderer crash kills the tab rather
 *    than the whole application.
 */
internal class ReWebWebViewClient(
    private val engine: SystemWebViewEngine
) : WebViewClient() {

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        engine.updateFavicon(favicon)
        engine.client?.onPageStarted(url)
        engine.client?.onNavigationStateChanged()
    }

    override fun onPageFinished(view: WebView, url: String) {
        engine.onDocumentReady()
        engine.client?.onPageFinished(url)
        engine.client?.onNavigationStateChanged()
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        // Fires for history.pushState/replaceState and fragment navigation, which
        // never trigger onPageStarted. Single-page apps depend on this to keep the
        // URL bar honest.
        engine.client?.onUrlChanged(url)
        engine.client?.onNavigationStateChanged()
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return engine.client?.shouldOverrideNavigation(
            url = request.url.toString(),
            isRedirect = request.isRedirect,
            isUserGesture = request.hasGesture()
        ) ?: false
    }

    @Deprecated("Required for API 21-23, where the WebResourceRequest overload is not called.")
    @Suppress("OVERRIDE_DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean {
        if (url == null) return false
        // The pre-N callback carries no gesture or redirect information. Treating
        // it as a user gesture matches how the platform actually behaves: it is
        // only invoked for navigations the page did not initiate programmatically
        // in the same tick.
        return engine.client?.shouldOverrideNavigation(url, isRedirect = false, isUserGesture = true) ?: false
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (!request.isForMainFrame) return
        engine.client?.onPageError(
            PageError(
                kind = mapErrorCode(error.errorCode),
                url = request.url.toString(),
                description = error.description?.toString().orEmpty(),
                isForMainFrame = true
            )
        )
    }

    @Deprecated("Required for API 21-22.")
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onReceivedError(
        view: WebView,
        errorCode: Int,
        description: String?,
        failingUrl: String?
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) return
        // Pre-M this callback is main-frame only, so no filtering is needed.
        engine.client?.onPageError(
            PageError(
                kind = mapErrorCode(errorCode),
                url = failingUrl.orEmpty(),
                description = description.orEmpty(),
                isForMainFrame = true
            )
        )
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (!request.isForMainFrame) return
        val status = errorResponse.statusCode
        // 4xx/5xx pages usually carry a real body the site wants shown. Only
        // report the ones that are genuinely blank-page failures.
        if (status < 400) return
        engine.client?.onPageError(
            PageError(
                kind = if (status == 429) ErrorKind.TOO_MANY_REQUESTS else ErrorKind.HTTP_ERROR,
                url = request.url.toString(),
                description = errorResponse.reasonPhrase.orEmpty(),
                httpStatusCode = status,
                isForMainFrame = true
            )
        )
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        val certificate: SslCertificate? = error.certificate
        val issue = SslIssue(
            url = error.url.orEmpty(),
            kind = mapSslError(error.primaryError),
            certificateSubject = certificate?.issuedTo?.cName,
            certificateIssuer = certificate?.issuedBy?.cName
        )
        val decision = object : SslDecision {
            private var answered = false
            override fun proceed() {
                if (answered) return
                answered = true
                handler.proceed()
            }

            override fun cancel() {
                if (answered) return
                answered = true
                handler.cancel()
            }
        }
        val listener = engine.client
        if (listener == null) {
            handler.cancel()
            return
        }
        listener.onSslError(issue, decision)
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val didCrash = detail?.didCrash() ?: true
        engine.client?.onRenderProcessGone(didCrash)
        // Returning true tells the framework we have handled the death of this
        // renderer. Returning false would kill the whole app process.
        return true
    }

    override fun onFormResubmission(view: WebView, dontResend: android.os.Message, resend: android.os.Message) {
        // Silently re-POSTing is how duplicate orders happen. Refuse; the user can
        // press reload deliberately.
        dontResend.sendToTarget()
    }

    private fun mapErrorCode(code: Int): ErrorKind = when (code) {
        ERROR_HOST_LOOKUP -> ErrorKind.DNS_FAILURE
        ERROR_CONNECT -> ErrorKind.CONNECTION_REFUSED
        ERROR_TIMEOUT -> ErrorKind.TIMEOUT
        ERROR_FAILED_SSL_HANDSHAKE -> ErrorKind.TLS_FAILURE
        ERROR_REDIRECT_LOOP -> ErrorKind.TOO_MANY_REDIRECTS
        ERROR_UNSUPPORTED_SCHEME -> ErrorKind.UNSUPPORTED_SCHEME
        ERROR_FILE_NOT_FOUND -> ErrorKind.FILE_NOT_FOUND
        ERROR_TOO_MANY_REQUESTS -> ErrorKind.TOO_MANY_REQUESTS
        // The platform has no dedicated "connection reset" code; it reports ERROR_IO.
        ERROR_IO -> ErrorKind.CONNECTION_RESET
        else -> ErrorKind.UNKNOWN
    }

    private fun mapSslError(primaryError: Int): SslIssueKind = when (primaryError) {
        SslError.SSL_NOTYETVALID -> SslIssueKind.NOT_YET_VALID
        SslError.SSL_EXPIRED -> SslIssueKind.EXPIRED
        SslError.SSL_IDMISMATCH -> SslIssueKind.HOSTNAME_MISMATCH
        SslError.SSL_UNTRUSTED -> SslIssueKind.UNTRUSTED_AUTHORITY
        SslError.SSL_DATE_INVALID -> SslIssueKind.INVALID_DATE
        else -> SslIssueKind.UNKNOWN
    }
}
