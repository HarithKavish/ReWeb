package com.reweb.browser.webapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.reweb.browser.R
import com.reweb.browser.ReWebApplication
import com.reweb.browser.browser.FileChooserCoordinator
import com.reweb.browser.browser.PermissionCoordinator
import com.reweb.browser.browser.UrlUtils
import com.reweb.browser.browser.UserAgent
import com.reweb.browser.engine.BrowserEngine
import com.reweb.browser.engine.DownloadRequest
import com.reweb.browser.engine.EngineClient
import com.reweb.browser.engine.EngineConfiguration
import com.reweb.browser.engine.FileChooserRequest
import com.reweb.browser.engine.FileChooserResponse
import com.reweb.browser.engine.PageError
import com.reweb.browser.engine.SslDecision
import com.reweb.browser.engine.SslIssue
import com.reweb.browser.engine.WebPermissionKind
import com.reweb.browser.engine.WebPermissionRequest
import com.reweb.browser.engine.webview.SystemWebViewEngine
import com.reweb.browser.browser.BrowserActivity
import com.reweb.browser.browser.ErrorPages
import com.reweb.browser.downloads.DownloadController
import com.reweb.browser.intents.ExternalIntents

/**
 * Runs one installed web app in its own task, with no browser chrome.
 *
 * This is what makes an "installed" app feel installed: its own entry in
 * Recents, its own back stack, no address bar, and the user agent the profile
 * was saved with. It shares cookies and site storage with normal browsing,
 * because the platform provides one jar per process — see [WebAppStore].
 */
class WebAppActivity : AppCompatActivity() {

    private lateinit var container: FrameLayout
    private lateinit var progressBar: ProgressBar
    private var engine: BrowserEngine? = null
    private var profile: WebAppProfile? = null
    private var fullscreenView: View? = null
    private var exitFullscreen: (() -> Unit)? = null

    private lateinit var fileChooser: FileChooserCoordinator
    private lateinit var permissions: PermissionCoordinator
    private lateinit var downloadController: DownloadController

    private val app: ReWebApplication get() = application as ReWebApplication

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> fileChooser.onResult(result) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> permissions.onAndroidPermissionResult(results) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val id = intent?.getStringExtra(EXTRA_WEB_APP_ID)
        val loaded = id?.let { app.webAppStore.byId(it) }
        if (loaded == null) {
            // The profile was removed while a launcher shortcut still pointed at it.
            Toast.makeText(this, R.string.web_apps_empty, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, BrowserActivity::class.java))
            finish()
            return
        }
        profile = loaded
        app.webAppStore.markUsed(loaded.id)

        container = FrameLayout(this)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (3 * resources.displayMetrics.density).toInt()
            )
        }
        setContentView(container)

        fileChooser = FileChooserCoordinator(this, fileChooserLauncher)
        permissions = PermissionCoordinator(this, permissionLauncher)
        downloadController = DownloadController(this, app.settings, app.downloadStore)

        loaded.themeColor?.let { color ->
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.statusBarColor = color
        }

        if (!startEngine(loaded)) {
            Toast.makeText(this, R.string.error_engine_unavailable, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    fullscreenView != null -> exitFullscreen?.invoke()
                    engine?.canGoBack() == true -> engine?.goBack()
                    else -> finish()
                }
            }
        })
    }

    private fun startEngine(profile: WebAppProfile): Boolean {
        val created = runCatching { SystemWebViewEngine(this) }.getOrNull() ?: return false
        engine = created

        created.client = WebAppEngineClient()
        created.applyConfiguration(
            EngineConfiguration(
                javaScriptEnabled = app.settings.javaScriptEnabled,
                loadImages = app.settings.loadImages,
                userAgent = UserAgent.resolve(
                    profile.userAgentMode,
                    created.defaultUserAgent(),
                    app.settings.customUserAgent
                ),
                incognito = false,
                allowPopups = app.settings.allowPopups,
                textZoomPercent = app.settings.textZoomPercent
            )
        )
        created.setDownloadListener { url, userAgent, disposition, mimeType, length ->
            handleDownload(DownloadRequest(url, userAgent, disposition, mimeType, length))
        }

        container.addView(
            created.view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        container.addView(progressBar)
        created.loadUrl(profile.url)
        return true
    }

    private fun handleDownload(request: DownloadRequest) {
        when (val result = downloadController.enqueue(request)) {
            is DownloadController.Result.Started ->
                Toast.makeText(this, getString(R.string.download_started, result.fileName), Toast.LENGTH_LONG).show()
            is DownloadController.Result.SavedLocally ->
                Toast.makeText(this, getString(R.string.download_saved, result.fileName), Toast.LENGTH_LONG).show()
            is DownloadController.Result.Failed ->
                Toast.makeText(this, R.string.download_error_unknown, Toast.LENGTH_LONG).show()
        }
    }

    private inner class WebAppEngineClient : EngineClient {

        override fun onProgressChanged(progress: Int) {
            progressBar.progress = progress
            progressBar.visibility = if (progress in 1..99) View.VISIBLE else View.GONE
        }

        override fun onPageFinished(url: String) {
            app.historyStore.recordVisit(url, engine?.title)
        }

        override fun onPageError(error: PageError) {
            if (!error.isForMainFrame) return
            engine?.loadHtml(ErrorPages.networkError(this@WebAppActivity, error), error.url)
        }

        override fun onSslError(issue: SslIssue, decision: SslDecision) {
            // A web app runs unattended by design; refuse a bad certificate rather
            // than teaching users to click through warnings. The site can be opened
            // in the browser, where the interstitial explains the risk properly.
            decision.cancel()
            engine?.loadHtml(ErrorPages.sslWarning(this@WebAppActivity, issue), issue.url)
        }

        override fun shouldOverrideNavigation(
            url: String,
            isRedirect: Boolean,
            isUserGesture: Boolean
        ): Boolean {
            if (UrlUtils.isExternalScheme(url)) {
                ExternalIntents.launch(this@WebAppActivity, url)
                return true
            }
            // Navigating off the installed app's own origin opens the browser, so
            // an outbound link does not silently become part of the "app".
            val appHost = profile?.url?.let { UrlUtils.hostOf(it) }
            val targetHost = UrlUtils.hostOf(url)
            if (appHost != null && targetHost != null && targetHost != appHost && isUserGesture) {
                startActivity(
                    Intent(this@WebAppActivity, BrowserActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .setData(android.net.Uri.parse(url))
                )
                return true
            }
            return false
        }

        override fun onFileChooserRequested(
            request: FileChooserRequest,
            response: FileChooserResponse
        ): Boolean = fileChooser.start(request, response)

        override fun onPermissionRequested(request: WebPermissionRequest) {
            val labels = request.kinds.joinToString(", ") { labelFor(it) }
            AlertDialog.Builder(this@WebAppActivity)
                .setTitle(getString(R.string.permission_title, request.origin))
                .setMessage(getString(R.string.permission_body, labels))
                .setPositiveButton(R.string.permission_allow) { _, _ ->
                    permissions.fulfill(request, request.kinds)
                }
                .setNegativeButton(R.string.permission_deny) { _, _ -> request.deny() }
                .setOnCancelListener { request.deny() }
                .show()
        }

        override fun onEnterFullscreen(view: View, onExitRequested: () -> Unit) {
            fullscreenView = view
            exitFullscreen = onExitRequested
            container.addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        override fun onExitFullscreen() {
            fullscreenView?.let { container.removeView(it) }
            fullscreenView = null
            exitFullscreen = null
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        override fun onRenderProcessGone(didCrash: Boolean) {
            val url = profile?.url ?: return
            engine?.destroy()
            engine = null
            container.removeAllViews()
            profile?.let { startEngine(it) }
            engine?.loadHtml(ErrorPages.rendererCrashed(this@WebAppActivity, url), url)
        }

        override fun onMediaPlaybackChanged(state: com.reweb.browser.engine.MediaPlaybackState) {
            com.reweb.browser.media.MediaPlaybackService.update(this@WebAppActivity, state)
        }
    }

    private fun labelFor(kind: WebPermissionKind): String = getString(
        when (kind) {
            WebPermissionKind.CAMERA -> R.string.permission_camera
            WebPermissionKind.MICROPHONE -> R.string.permission_microphone
            WebPermissionKind.LOCATION -> R.string.permission_location
            WebPermissionKind.PROTECTED_MEDIA -> R.string.permission_protected_media
            WebPermissionKind.MIDI -> R.string.permission_midi
            WebPermissionKind.UNKNOWN -> R.string.permission_unknown
        }
    )

    override fun onResume() {
        super.onResume()
        engine?.onActivityResume()
    }

    override fun onStop() {
        super.onStop()
        if (!com.reweb.browser.media.MediaPlaybackService.isActive) engine?.onActivityPause()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) engine?.trimMemory()
    }

    override fun onDestroy() {
        // The coordinators are only initialised once a profile has been resolved;
        // a launch with a stale web-app id finishes before that happens.
        if (this::fileChooser.isInitialized) fileChooser.cancelPending()
        if (this::permissions.isInitialized) permissions.cancelPending()
        engine?.destroy()
        engine = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_WEB_APP_ID = "web_app_id"

        fun intentFor(context: Context, webAppId: String): Intent =
            Intent(context, WebAppActivity::class.java)
                .putExtra(EXTRA_WEB_APP_ID, webAppId)
                // A new task per app is what gives each one its own Recents entry.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    }
}
