package com.reweb.browser.engine

import android.net.Uri

/**
 * Engine-neutral event and request types.
 *
 * These exist so that callers never touch `android.webkit` classes such as
 * WebResourceError, SslError, PermissionRequest or ValueCallback. A future
 * engine maps its own failures onto the same vocabulary.
 */

/**
 * Why a navigation failed. Mapped from engine-specific error codes so the UI can
 * write an actionable message instead of "something went wrong".
 */
enum class ErrorKind {
    NO_NETWORK,
    DNS_FAILURE,
    CONNECTION_REFUSED,
    CONNECTION_RESET,
    TIMEOUT,
    TLS_FAILURE,
    TOO_MANY_REDIRECTS,
    UNSUPPORTED_SCHEME,
    FILE_NOT_FOUND,
    HTTP_ERROR,
    TOO_MANY_REQUESTS,
    UNKNOWN
}

data class PageError(
    val kind: ErrorKind,
    val url: String,
    /** Engine-supplied description. Never shown alone; always paired with guidance. */
    val description: String,
    val httpStatusCode: Int = 0,
    val isForMainFrame: Boolean = true
)

enum class SslIssueKind {
    NOT_YET_VALID,
    EXPIRED,
    HOSTNAME_MISMATCH,
    UNTRUSTED_AUTHORITY,
    INVALID_DATE,
    UNKNOWN
}

data class SslIssue(
    val url: String,
    val kind: SslIssueKind,
    val certificateSubject: String?,
    val certificateIssuer: String?
)

/**
 * The user's answer to a certificate interstitial.
 *
 * ReWeb never calls [proceed] on its own. It is invoked only from an explicit
 * "proceed anyway" action behind a warning screen, and the decision is scoped to
 * a single host for the lifetime of the process — never persisted.
 */
interface SslDecision {
    fun proceed()
    fun cancel()
}

data class DownloadRequest(
    val url: String,
    val userAgent: String?,
    val contentDisposition: String?,
    val mimeType: String?,
    val contentLengthBytes: Long
)

enum class WebPermissionKind {
    CAMERA,
    MICROPHONE,
    LOCATION,
    /** Encrypted Media / Widevine. Availability is a platform property. */
    PROTECTED_MEDIA,
    MIDI,
    UNKNOWN
}

/**
 * A permission a page asked for. Exactly one of [grant] or [deny] must be called,
 * exactly once. Dropping the request without answering leaves the page hanging,
 * so the UI answers deny on dismissal.
 */
interface WebPermissionRequest {
    val origin: String
    val kinds: Set<WebPermissionKind>
    fun grant(granted: Set<WebPermissionKind>)
    fun deny()
}

data class FileChooserRequest(
    val acceptTypes: List<String>,
    val allowMultiple: Boolean,
    /** The page asked for a live capture (`<input capture>`) rather than a picker. */
    val preferCapture: Boolean
)

/** Result channel for a [FileChooserRequest]; must be answered exactly once. */
interface FileChooserResponse {
    fun submit(uris: List<Uri>)
    fun cancel()
}

/** A JavaScript alert/confirm/prompt/beforeunload dialog. */
enum class JsDialogKind { ALERT, CONFIRM, PROMPT, BEFORE_UNLOAD }

data class JsDialogRequest(
    val kind: JsDialogKind,
    val origin: String,
    val message: String,
    val defaultPromptValue: String?
)

interface JsDialogResponse {
    fun confirm(promptResult: String? = null)
    fun cancel()
}

/** Transport security of the committed document, used for the URL-bar indicator. */
enum class SecurityState {
    /** https:// with a valid chain. */
    SECURE,

    /** http:// — transmitted in the clear. */
    INSECURE,

    /** https:// where the user explicitly bypassed a certificate warning. */
    WARNING,

    /** about:blank, the native home page, in-app error documents. */
    NEUTRAL
}
