package com.reweb.browser.engine.webview

import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.reweb.browser.engine.FileChooserRequest
import com.reweb.browser.engine.FileChooserResponse
import com.reweb.browser.engine.JsDialogKind
import com.reweb.browser.engine.JsDialogRequest
import com.reweb.browser.engine.JsDialogResponse
import com.reweb.browser.engine.WebPermissionKind
import com.reweb.browser.engine.WebPermissionRequest

/**
 * Translates WebChromeClient callbacks — progress, titles, fullscreen video,
 * file pickers, permission prompts, JS dialogs and popup windows — into
 * engine-neutral events.
 */
internal class ReWebWebChromeClient(
    private val engine: SystemWebViewEngine
) : WebChromeClient() {

    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        engine.updateProgress(newProgress)
        engine.client?.onProgressChanged(newProgress)
    }

    override fun onReceivedTitle(view: WebView, title: String?) {
        engine.client?.onTitleChanged(title.orEmpty())
    }

    override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
        engine.updateFavicon(icon)
        engine.client?.onFaviconChanged(icon)
    }

    // --- Fullscreen video ---

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (customView != null) {
            // A second request while one is active; the platform expects the new
            // one to be refused rather than stacked.
            callback?.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        if (view == null) return
        engine.client?.onEnterFullscreen(view) { onHideCustomView() }
    }

    override fun onHideCustomView() {
        if (customView == null) return
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        engine.client?.onExitFullscreen()
    }

    // --- File upload ---

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams
    ): Boolean {
        val request = FileChooserRequest(
            acceptTypes = fileChooserParams.acceptTypes
                .orEmpty()
                .filter { it.isNotBlank() },
            allowMultiple = fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE,
            preferCapture = fileChooserParams.isCaptureEnabled
        )
        val response = object : FileChooserResponse {
            private var answered = false
            override fun submit(uris: List<Uri>) {
                if (answered) return
                answered = true
                filePathCallback.onReceiveValue(uris.toTypedArray())
            }

            override fun cancel() {
                if (answered) return
                answered = true
                // The page stays stuck on a pending picker unless null is sent.
                filePathCallback.onReceiveValue(null)
            }
        }
        val handled = engine.client?.onFileChooserRequested(request, response) ?: false
        if (!handled) filePathCallback.onReceiveValue(null)
        return handled
    }

    // --- Permissions ---

    override fun onPermissionRequest(request: PermissionRequest) {
        val requestedKinds = request.resources.mapNotNull { resource ->
            when (resource) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> WebPermissionKind.CAMERA
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> WebPermissionKind.MICROPHONE
                PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> WebPermissionKind.PROTECTED_MEDIA
                PermissionRequest.RESOURCE_MIDI_SYSEX -> WebPermissionKind.MIDI
                else -> WebPermissionKind.UNKNOWN
            }
        }.toSet()

        val neutral = object : WebPermissionRequest {
            private var answered = false
            override val origin: String = request.origin?.toString().orEmpty()
            override val kinds: Set<WebPermissionKind> = requestedKinds

            override fun grant(granted: Set<WebPermissionKind>) {
                if (answered) return
                answered = true
                val resources = request.resources.filter { resource ->
                    when (resource) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                            WebPermissionKind.CAMERA in granted
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                            WebPermissionKind.MICROPHONE in granted
                        PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID ->
                            WebPermissionKind.PROTECTED_MEDIA in granted
                        PermissionRequest.RESOURCE_MIDI_SYSEX ->
                            WebPermissionKind.MIDI in granted
                        else -> false
                    }
                }.toTypedArray()
                if (resources.isEmpty()) request.deny() else request.grant(resources)
            }

            override fun deny() {
                if (answered) return
                answered = true
                request.deny()
            }
        }

        val listener = engine.client
        if (listener == null) {
            request.deny()
            return
        }
        listener.onPermissionRequested(neutral)
    }

    override fun onPermissionRequestCanceled(request: PermissionRequest) {
        // Nothing to tear down: the UI treats a dismissed prompt as a denial.
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String,
        callback: GeolocationPermissions.Callback
    ) {
        val requestingOrigin = origin
        val neutral = object : WebPermissionRequest {
            private var answered = false
            override val origin: String = requestingOrigin
            override val kinds: Set<WebPermissionKind> = setOf(WebPermissionKind.LOCATION)

            override fun grant(granted: Set<WebPermissionKind>) {
                if (answered) return
                answered = true
                val allow = WebPermissionKind.LOCATION in granted
                // retain=false: the grant lasts for this page session only, so a
                // site cannot acquire permanent location access from one tap.
                callback.invoke(requestingOrigin, allow, false)
            }

            override fun deny() {
                if (answered) return
                answered = true
                callback.invoke(requestingOrigin, false, false)
            }
        }
        val listener = engine.client
        if (listener == null) {
            callback.invoke(requestingOrigin, false, false)
            return
        }
        listener.onPermissionRequested(neutral)
    }

    // --- JavaScript dialogs ---

    override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean =
        dispatchDialog(JsDialogKind.ALERT, url, message, null, result)

    override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean =
        dispatchDialog(JsDialogKind.CONFIRM, url, message, null, result)

    override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult): Boolean =
        dispatchDialog(JsDialogKind.BEFORE_UNLOAD, url, message, null, result)

    override fun onJsPrompt(
        view: WebView,
        url: String?,
        message: String?,
        defaultValue: String?,
        result: JsPromptResult
    ): Boolean = dispatchDialog(JsDialogKind.PROMPT, url, message, defaultValue, result)

    private fun dispatchDialog(
        kind: JsDialogKind,
        url: String?,
        message: String?,
        defaultValue: String?,
        result: JsResult
    ): Boolean {
        val response = object : JsDialogResponse {
            private var answered = false
            override fun confirm(promptResult: String?) {
                if (answered) return
                answered = true
                val prompt = result as? JsPromptResult
                if (prompt != null) prompt.confirm(promptResult.orEmpty()) else result.confirm()
            }

            override fun cancel() {
                if (answered) return
                answered = true
                result.cancel()
            }
        }
        val request = JsDialogRequest(
            kind = kind,
            origin = url.orEmpty(),
            message = message.orEmpty(),
            defaultPromptValue = defaultValue
        )
        val handled = engine.client?.onJsDialog(request, response) ?: false
        if (!handled) result.cancel()
        return handled
    }

    // --- Popup windows ---

    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message
    ): Boolean {
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        // Casting to the concrete engine is safe: only this engine implementation
        // can host a WebView transport, and the controller creates the new tab
        // with the same factory that produced this one.
        // Returning false without sending resultMsg tells the platform the window
        // was refused; the message must not be recycled by hand.
        val target = engine.client?.onCreateWindowRequested(isUserGesture) as? SystemWebViewEngine
            ?: return false
        transport.webView = target.webView
        resultMsg.sendToTarget()
        return true
    }

    override fun onCloseWindow(window: WebView) {
        engine.client?.onCloseWindowRequested()
    }
}
