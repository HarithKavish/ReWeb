package com.reweb.browser.browser

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.reweb.browser.R
import com.reweb.browser.ReWebApplication
import com.reweb.browser.auth.AuthHandoff
import com.reweb.browser.databinding.ActivityBrowserBinding
import com.reweb.browser.databinding.SheetTabsBinding
import com.reweb.browser.diagnostics.DiagnosticsActivity
import com.reweb.browser.diagnostics.WebViewInfo
import com.reweb.browser.downloads.DownloadController
import com.reweb.browser.downloads.DownloadsActivity
import com.reweb.browser.engine.DownloadRequest
import com.reweb.browser.engine.FileChooserRequest
import com.reweb.browser.engine.FileChooserResponse
import com.reweb.browser.engine.JsDialogKind
import com.reweb.browser.engine.JsDialogRequest
import com.reweb.browser.engine.JsDialogResponse
import com.reweb.browser.engine.MediaPlaybackState
import com.reweb.browser.engine.PageError
import com.reweb.browser.engine.SecurityState
import com.reweb.browser.engine.SslDecision
import com.reweb.browser.engine.SslIssue
import com.reweb.browser.engine.WebPermissionKind
import com.reweb.browser.engine.WebPermissionRequest
import com.reweb.browser.intents.ExternalIntents
import com.reweb.browser.media.MediaPlaybackService
import com.reweb.browser.settings.SettingsActivity
import com.reweb.browser.settings.SitePermissionStore
import com.reweb.browser.webapp.WebAppInstaller
import com.reweb.browser.webapp.WebAppsActivity

/**
 * The browser screen.
 *
 * Holds no browsing logic of its own: it renders whatever [BrowserController]
 * reports and forwards user actions back. Everything it does implement is
 * genuinely presentation — dialogs, the toolbar, fullscreen window flags, and
 * the activity-result plumbing that file pickers and runtime permissions need.
 */
class BrowserActivity : AppCompatActivity(), BrowserController.Host, MediaPlaybackService.Commands {

    private lateinit var binding: ActivityBrowserBinding
    private lateinit var controller: BrowserController
    private lateinit var homeScreen: HomeScreenController
    private lateinit var downloadController: DownloadController
    private lateinit var fileChooser: FileChooserCoordinator
    private lateinit var permissions: PermissionCoordinator
    private lateinit var webAppInstaller: WebAppInstaller

    private val app: ReWebApplication get() = application as ReWebApplication

    private var fullscreenView: View? = null
    private var exitFullscreenCallback: (() -> Unit)? = null
    private var originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    private var attachedEngineView: View? = null
    private var urlBarHasFocus = false

    /** A download waiting on the legacy storage permission. */
    private var pendingDownload: DownloadRequest? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> fileChooser.onResult(result) }

    private val webPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> permissions.onAndroidPermissionResult(results) }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val request = pendingDownload
        pendingDownload = null
        if (request == null) return@registerForActivityResult
        if (granted) startDownload(request) else showMessage(getString(R.string.download_error_permission))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        downloadController = DownloadController(this, app.settings, app.downloadStore)
        fileChooser = FileChooserCoordinator(this, fileChooserLauncher)
        permissions = PermissionCoordinator(this, webPermissionLauncher)
        webAppInstaller = WebAppInstaller(this, app.webAppStore)

        controller = BrowserController(
            context = this,
            settings = app.settings,
            siteSettings = app.siteSettings,
            historyStore = app.historyStore,
            bookmarkStore = app.bookmarkStore,
            host = this
        )

        homeScreen = HomeScreenController(
            binding = binding.homeView,
            webAppStore = app.webAppStore,
            bookmarkStore = app.bookmarkStore,
            historyStore = app.historyStore,
            onNavigate = { url -> controller.navigate(url) },
            onLaunchWebApp = { profile -> startActivity(com.reweb.browser.webapp.WebAppActivity.intentFor(this, profile.id)) }
        )

        // Camera captures from a previous run are dead weight; the page that
        // requested them is long gone.
        FileChooserCoordinator.clearStagedUploads(this)

        wireToolbar()
        setupBackHandling()

        MediaPlaybackService.setCommandHandler(this)

        val opened = handleIntent(intent, isColdStart = true)
        if (!opened) {
            val restored = controller.restoreSession()
            if (restored == 0) controller.openNewTab()
        }
        maybeShowCompatibilityBanner()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, isColdStart = false)
    }

    /** Returns true if the intent caused a page to open. */
    private fun handleIntent(intent: Intent?, isColdStart: Boolean): Boolean {
        if (intent == null) return false
        return when (intent.action) {
            Intent.ACTION_VIEW -> {
                val url = intent.dataString ?: return false
                controller.openNewTab(url = url, activate = true)
                controller.navigate(url)
                true
            }
            Intent.ACTION_SEND -> {
                val shared = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return false
                if (isColdStart) controller.openNewTab()
                controller.submitAddressBarInput(shared)
                true
            }
            Intent.ACTION_WEB_SEARCH -> {
                val query = intent.getStringExtra("query") ?: return false
                if (isColdStart) controller.openNewTab()
                controller.submitAddressBarInput(query)
                true
            }
            ACTION_AUTH_REDIRECT -> {
                // The redirect URL carries an authorization code. It is loaded and
                // never logged, stored, or added to history.
                val redirect = intent.getStringExtra(EXTRA_AUTH_REDIRECT)
                showMessage(getString(R.string.oauth_returned))
                if (redirect != null && UrlUtils.isHttpScheme(redirect)) controller.navigate(redirect)
                true
            }
            else -> false
        }
    }

    // --- Toolbar wiring ---

    private fun wireToolbar() = with(binding) {
        backButton.setOnClickListener { if (!controller.goBack()) finishIfNoHistory() }
        forwardButton.setOnClickListener { controller.goForward() }
        homeButton.setOnClickListener { controller.goHome() }
        tabsButton.setOnClickListener { showTabSwitcher() }
        menuButton.setOnClickListener { showMenu(it) }

        reloadButton.setOnClickListener {
            val tab = controller.activeTab
            if (tab?.isLoading == true) controller.stopLoading() else controller.reload()
        }

        securityButton.setOnClickListener { showSecurityInfo() }

        urlBar.setOnEditorActionListener { _, actionId, event ->
            val isGo = actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                event?.keyCode == KeyEvent.KEYCODE_ENTER
            if (isGo) {
                submitUrlBar()
                true
            } else {
                false
            }
        }

        urlBar.setOnFocusChangeListener { _, hasFocus ->
            urlBarHasFocus = hasFocus
            // While focused the bar shows the full URL so it can be edited; when it
            // loses focus it goes back to the shortened display form.
            if (hasFocus) {
                urlBar.setText(controller.activeTab?.url.orEmpty())
                urlBar.selectAll()
            } else {
                controller.activeTab?.let { renderUrl(it) }
            }
        }

        compatBannerDismiss.setOnClickListener {
            app.settings.showsCompatibilityWarning = false
            compatBanner.visibility = View.GONE
        }
        compatBannerDetails.setOnClickListener {
            startActivity(Intent(this@BrowserActivity, DiagnosticsActivity::class.java))
        }
    }

    private fun submitUrlBar() {
        val input = binding.urlBar.text.toString()
        binding.urlBar.clearFocus()
        hideKeyboard()
        controller.submitAddressBarInput(input)
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    fullscreenView != null -> exitFullscreenCallback?.invoke()
                    urlBarHasFocus -> {
                        binding.urlBar.clearFocus()
                        hideKeyboard()
                    }
                    controller.goBack() -> Unit
                    else -> finishIfNoHistory()
                }
            }
        })
    }

    private fun finishIfNoHistory() {
        // Moving the task to the back rather than finishing keeps tabs and their
        // engines warm, which is the difference between an instant return and a
        // full reload on a slow device.
        moveTaskToBack(true)
    }

    // --- BrowserController.Host ---

    override fun onTabStateChanged(tab: Tab) {
        if (tab.id != controller.activeTab?.id) return
        renderUrl(tab)
        renderProgress(tab)
        renderSecurity(tab)
        binding.backButton.isEnabled = tab.canGoBack || !tab.isShowingHome
        binding.forwardButton.isEnabled = tab.canGoForward
        binding.forwardButton.alpha = if (tab.canGoForward) 1f else 0.35f
        binding.reloadButton.setImageResource(
            if (tab.isLoading) R.drawable.ic_stop else R.drawable.ic_reload
        )
        binding.reloadButton.contentDescription =
            getString(if (tab.isLoading) R.string.action_stop else R.string.action_reload)
    }

    override fun onTabListChanged() {
        binding.tabCount.text = controller.tabManager.count.toString()
    }

    override fun onActiveTabChanged(tab: Tab?) {
        onTabListChanged()
        if (tab == null) return
        attachEngineView(tab)
        applyPrivateChrome(tab.isPrivate)
        showHomeScreen(tab.isShowingHome)
        onTabStateChanged(tab)
    }

    override fun onEngineViewChanged(tab: Tab) {
        if (tab.id == controller.activeTab?.id) attachEngineView(tab)
    }

    private fun attachEngineView(tab: Tab) {
        val engineView = tab.engine?.view
        if (attachedEngineView === engineView) return

        attachedEngineView?.let { existing ->
            (existing.parent as? ViewGroup)?.removeView(existing)
        }
        attachedEngineView = engineView

        if (engineView == null) return
        (engineView.parent as? ViewGroup)?.removeView(engineView)
        // Index 0 keeps the engine below the home view, so toggling home does not
        // require re-adding the engine.
        binding.contentContainer.addView(engineView, 0)
    }

    override fun showHomeScreen(show: Boolean) {
        binding.homeView.root.visibility = if (show) View.VISIBLE else View.GONE
        attachedEngineView?.visibility = if (show) View.GONE else View.VISIBLE
        if (show) {
            homeScreen.refresh(isPrivate = controller.activeTab?.isPrivate == true)
        }
    }

    override fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun requestFileChooser(request: FileChooserRequest, response: FileChooserResponse): Boolean =
        fileChooser.start(request, response)

    override fun requestWebPermission(request: WebPermissionRequest) {
        // Protected media that this origin has already been granted is answered
        // immediately. A streaming player asks for EME while it is initialising,
        // and a dialog sitting in front of that request long enough makes the
        // player disable itself - so re-prompting is not merely annoying here,
        // it breaks playback. Nothing else is ever auto-granted.
        if (request.kinds.isNotEmpty() &&
            request.kinds.all { SitePermissionStore.isRememberable(it) } &&
            app.sitePermissions.isProtectedMediaGranted(request.origin)
        ) {
            request.grant(request.kinds)
            return
        }

        val origin = request.origin.ifBlank { getString(R.string.value_unknown) }
        val labels = request.kinds.joinToString(", ") { labelFor(it) }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.permission_title, origin))
            .setMessage(
                getString(R.string.permission_body, labels) + "\n\n" + getString(R.string.permission_note)
            )
            .setPositiveButton(R.string.permission_allow) { _, _ ->
                if (request.kinds.any { SitePermissionStore.isRememberable(it) }) {
                    app.sitePermissions.rememberProtectedMedia(request.origin, granted = true)
                }
                permissions.fulfill(request, request.kinds)
            }
            .setNegativeButton(R.string.permission_deny) { _, _ -> request.deny() }
            // Dismissing without answering must still answer, or the page hangs.
            .setOnCancelListener { request.deny() }
            .show()
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

    override fun showJsDialog(request: JsDialogRequest, response: JsDialogResponse): Boolean {
        if (isFinishing || isDestroyed) return false
        val origin = UrlUtils.hostOf(request.origin) ?: request.origin

        when (request.kind) {
            JsDialogKind.ALERT -> AlertDialog.Builder(this)
                .setTitle(getString(R.string.js_dialog_title, origin))
                .setMessage(request.message)
                .setPositiveButton(R.string.action_ok) { _, _ -> response.confirm() }
                .setOnCancelListener { response.cancel() }
                .show()

            JsDialogKind.CONFIRM -> AlertDialog.Builder(this)
                .setTitle(getString(R.string.js_dialog_title, origin))
                .setMessage(request.message)
                .setPositiveButton(R.string.action_ok) { _, _ -> response.confirm() }
                .setNegativeButton(R.string.action_cancel) { _, _ -> response.cancel() }
                .setOnCancelListener { response.cancel() }
                .show()

            JsDialogKind.BEFORE_UNLOAD -> AlertDialog.Builder(this)
                .setTitle(R.string.js_leave_page)
                .setMessage(request.message)
                .setPositiveButton(R.string.js_leave_confirm) { _, _ -> response.confirm() }
                .setNegativeButton(R.string.js_stay) { _, _ -> response.cancel() }
                .setOnCancelListener { response.cancel() }
                .show()

            JsDialogKind.PROMPT -> {
                val input = android.widget.EditText(this).apply {
                    setText(request.defaultPromptValue.orEmpty())
                    setSingleLine()
                }
                val container = android.widget.FrameLayout(this).apply {
                    val pad = (20 * resources.displayMetrics.density).toInt()
                    setPadding(pad, pad / 2, pad, 0)
                    addView(input)
                }
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.js_dialog_title, origin))
                    .setMessage(request.message)
                    .setView(container)
                    .setPositiveButton(R.string.action_ok) { _, _ -> response.confirm(input.text.toString()) }
                    .setNegativeButton(R.string.action_cancel) { _, _ -> response.cancel() }
                    .setOnCancelListener { response.cancel() }
                    .show()
            }
        }
        return true
    }

    override fun showSslInterstitial(issue: SslIssue, decision: SslDecision) {
        val host = UrlUtils.hostOf(issue.url) ?: issue.url
        // On a device whose root store predates Let's Encrypt, the generic warning
        // is technically true but useless. Name the real cause and the real fix.
        val trustStoreHint = CertificateAdvice.outdatedTrustStoreHint(this, issue.kind)
        AlertDialog.Builder(this)
            .setTitle(R.string.ssl_warning_title)
            .setMessage(
                getString(R.string.ssl_warning_body, host, sslReason(issue)) + "\n\n" +
                    (trustStoreHint ?: getString(R.string.ssl_warning_hint))
            )
            // "Go back" is the positive, default-focused action.
            .setPositiveButton(R.string.ssl_go_back) { _, _ -> decision.cancel() }
            .setNegativeButton(R.string.ssl_proceed) { _, _ ->
                // A second, explicit confirmation before weakening HTTPS.
                AlertDialog.Builder(this)
                    .setMessage(getString(R.string.ssl_proceed_confirm, host))
                    .setPositiveButton(R.string.ssl_proceed) { _, _ -> decision.proceed() }
                    .setNegativeButton(R.string.action_cancel) { _, _ -> decision.cancel() }
                    .setOnCancelListener { decision.cancel() }
                    .show()
            }
            .setCancelable(false)
            .show()
    }

    private fun sslReason(issue: SslIssue): String = getString(
        when (issue.kind) {
            com.reweb.browser.engine.SslIssueKind.EXPIRED -> R.string.ssl_expired
            com.reweb.browser.engine.SslIssueKind.NOT_YET_VALID -> R.string.ssl_not_yet_valid
            com.reweb.browser.engine.SslIssueKind.HOSTNAME_MISMATCH -> R.string.ssl_hostname_mismatch
            com.reweb.browser.engine.SslIssueKind.UNTRUSTED_AUTHORITY -> R.string.ssl_untrusted
            com.reweb.browser.engine.SslIssueKind.INVALID_DATE -> R.string.ssl_invalid_date
            com.reweb.browser.engine.SslIssueKind.UNKNOWN -> R.string.ssl_unknown
        }
    )

    override fun enterFullscreen(view: View, onExitRequested: () -> Unit) {
        if (fullscreenView != null) return
        fullscreenView = view
        exitFullscreenCallback = onExitRequested
        originalOrientation = requestedOrientation

        binding.fullscreenContainer.addView(
            view,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        binding.fullscreenContainer.visibility = View.VISIBLE
        binding.browserChrome.visibility = View.GONE

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applySystemUiFullscreen(true)
        // Video is overwhelmingly landscape; letting the page's own orientation
        // request through avoids a letterboxed portrait player.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
    }

    override fun exitFullscreen() {
        val view = fullscreenView ?: return
        binding.fullscreenContainer.removeView(view)
        binding.fullscreenContainer.visibility = View.GONE
        binding.browserChrome.visibility = View.VISIBLE
        fullscreenView = null
        exitFullscreenCallback = null

        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applySystemUiFullscreen(false)
        requestedOrientation = originalOrientation
    }

    @Suppress("DEPRECATION")
    private fun applySystemUiFullscreen(fullscreen: Boolean) {
        // The modern WindowInsetsController is API 30+; these devices need the
        // legacy flags, which still work on every supported level.
        window.decorView.systemUiVisibility = if (fullscreen) {
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else {
            View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    override fun onDownloadRequested(request: DownloadRequest) {
        if (!app.settings.askBeforeDownloading) {
            startDownload(request)
            return
        }
        val fileName = downloadController.proposedFileName(request)
        val size = if (request.contentLengthBytes > 0) {
            android.text.format.Formatter.formatShortFileSize(this, request.contentLengthBytes)
        } else {
            getString(R.string.download_size_unknown)
        }
        val isApk = fileName.endsWith(".apk", ignoreCase = true)

        AlertDialog.Builder(this)
            .setTitle(R.string.download_confirm_title)
            .setMessage(
                getString(R.string.download_confirm_body, fileName, size) +
                    if (isApk) "\n\n" + getString(R.string.download_apk_notice) else ""
            )
            .setPositiveButton(R.string.download_action) { _, _ -> startDownload(request) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun startDownload(request: DownloadRequest) {
        if (downloadController.needsLegacyStoragePermission()) {
            pendingDownload = request
            storagePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        when (val result = downloadController.enqueue(request)) {
            is DownloadController.Result.Started ->
                showMessage(getString(R.string.download_started, result.fileName))
            is DownloadController.Result.SavedLocally ->
                showMessage(getString(R.string.download_saved, result.fileName))
            is DownloadController.Result.Failed -> showMessage(
                getString(
                    when (result.reason) {
                        DownloadController.FailureReason.NEEDS_STORAGE_PERMISSION -> R.string.download_error_permission
                        DownloadController.FailureReason.UNSUPPORTED_SCHEME -> R.string.download_error_scheme
                        DownloadController.FailureReason.BLOB_UNSUPPORTED -> R.string.download_error_blob
                        DownloadController.FailureReason.DOWNLOAD_MANAGER_DISABLED -> R.string.download_error_manager
                        DownloadController.FailureReason.STORAGE_UNAVAILABLE -> R.string.download_error_storage
                        DownloadController.FailureReason.UNKNOWN -> R.string.download_error_unknown
                    }
                )
            )
        }
    }

    override fun onExternalSchemeRequested(url: String) {
        val scheme = UrlUtils.schemeOf(url) ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.external_title)
            .setMessage(getString(R.string.external_body, url))
            .setPositiveButton(R.string.external_open) { _, _ -> dispatchExternal(url, scheme) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun dispatchExternal(url: String, scheme: String) {
        when (val outcome = ExternalIntents.launch(this, url)) {
            is ExternalIntents.Outcome.Launched -> Unit
            is ExternalIntents.Outcome.HandleInBrowser -> controller.navigate(outcome.url)
            is ExternalIntents.Outcome.Malformed -> showMessage(getString(R.string.external_malformed))
            is ExternalIntents.Outcome.NoHandler -> {
                val fallback = outcome.fallbackUrl ?: ExternalIntents.webEquivalentFor(url)
                if (fallback == null) {
                    showMessage(getString(R.string.external_no_handler, scheme))
                } else {
                    AlertDialog.Builder(this)
                        .setMessage(R.string.external_no_handler_fallback)
                        .setPositiveButton(R.string.action_open) { _, _ -> controller.navigate(fallback) }
                        .setNegativeButton(R.string.action_cancel, null)
                        .show()
                }
            }
        }
    }

    override fun onOAuthHandoffAvailable(url: String, returnsToApp: Boolean) {
        if (!AuthHandoff.hasExternalBrowser(this)) {
            showMessage(getString(R.string.oauth_no_browser))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.oauth_title)
            .setMessage(if (returnsToApp) R.string.oauth_body_returns else R.string.oauth_body_no_return)
            .setPositiveButton(R.string.oauth_open_external) { _, _ ->
                if (!AuthHandoff.launchExternal(this, url)) {
                    showMessage(getString(R.string.oauth_no_browser))
                }
            }
            .setNegativeButton(R.string.oauth_stay, null)
            .show()
    }

    override fun onMediaPlaybackChanged(state: MediaPlaybackState) {
        // The service decides whether to start, update or tear itself down; it is
        // the only thing that knows whether it is currently running.
        MediaPlaybackService.update(this, state)
    }

    override fun onPageErrorRendered(tab: Tab, error: PageError) {
        // The error document is already on screen; nothing further to show. The
        // hook exists so a future engine could report errors that need extra UI.
    }

    // --- Media transport commands ---

    override fun onMediaPlayRequested() {
        controller.activeTab?.engine?.evaluateJavaScript(MEDIA_PLAY_SCRIPT, null)
    }

    override fun onMediaPauseRequested() {
        controller.activeTab?.engine?.evaluateJavaScript(MEDIA_PAUSE_SCRIPT, null)
    }

    override fun onMediaStopRequested() {
        controller.activeTab?.engine?.evaluateJavaScript(MEDIA_PAUSE_SCRIPT, null)
    }

    // --- Menus and sheets ---

    private fun showMenu(anchor: View) {
        val tab = controller.activeTab
        val popup = PopupMenu(this, anchor, Gravity.END)
        popup.menuInflater.inflate(R.menu.browser_menu, popup.menu)

        val hasPage = tab != null && !tab.isShowingHome && tab.url != null
        val bookmarked = controller.isCurrentPageBookmarked()

        popup.menu.findItem(R.id.menu_bookmark).apply {
            isVisible = hasPage
            setTitle(if (bookmarked) R.string.menu_bookmark_remove else R.string.menu_bookmark_add)
        }
        popup.menu.findItem(R.id.menu_install_web_app).isVisible = hasPage
        popup.menu.findItem(R.id.menu_share).isVisible = hasPage
        popup.menu.findItem(R.id.menu_copy_link).isVisible = hasPage
        popup.menu.findItem(R.id.menu_open_in_browser).isVisible = hasPage
        popup.menu.findItem(R.id.menu_desktop_site).apply {
            isVisible = hasPage
            isChecked = controller.currentUserAgentMode() == UserAgentMode.DESKTOP
        }

        popup.setOnMenuItemClickListener { item -> onMenuItemSelected(item.itemId) }
        popup.show()
    }

    private fun onMenuItemSelected(itemId: Int): Boolean {
        val tab = controller.activeTab
        val url = tab?.url
        when (itemId) {
            R.id.menu_new_tab -> controller.openNewTab()
            R.id.menu_new_private_tab -> controller.openNewTab(isPrivate = true)
            R.id.menu_bookmark -> {
                if (url != null && !com.reweb.browser.bookmarks.BookmarkStore.isBookmarkable(url)) {
                    showMessage(getString(R.string.bookmark_cannot))
                } else {
                    val added = controller.toggleBookmarkForCurrentPage()
                    showMessage(getString(if (added) R.string.bookmark_added else R.string.bookmark_removed))
                }
            }
            R.id.menu_bookmarks -> startActivity(Intent(this, com.reweb.browser.bookmarks.BookmarksActivity::class.java))
            R.id.menu_history -> startActivity(Intent(this, com.reweb.browser.history.HistoryActivity::class.java))
            R.id.menu_downloads -> startActivity(Intent(this, DownloadsActivity::class.java))
            R.id.menu_web_apps -> startActivity(Intent(this, WebAppsActivity::class.java))
            R.id.menu_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            R.id.menu_diagnostics -> startActivity(Intent(this, DiagnosticsActivity::class.java))

            R.id.menu_desktop_site -> {
                val next = if (controller.currentUserAgentMode() == UserAgentMode.DESKTOP) {
                    UserAgentMode.MOBILE
                } else {
                    UserAgentMode.DESKTOP
                }
                controller.setUserAgentModeForCurrentSite(next)
            }

            R.id.menu_install_web_app -> {
                if (url == null) return true
                webAppInstaller.promptInstall(
                    url = url,
                    suggestedName = tab.title.orEmpty(),
                    favicon = tab.favicon,
                    currentUserAgentMode = controller.currentUserAgentMode()
                ) { profile -> showMessage(getString(R.string.install_web_app_done, profile.name)) }
            }

            R.id.menu_share -> if (url != null) {
                startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, url)
                            putExtra(Intent.EXTRA_SUBJECT, tab.title.orEmpty())
                        },
                        null
                    )
                )
            }

            R.id.menu_copy_link -> if (url != null) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(tab.title.orEmpty(), url))
                showMessage(getString(R.string.copied_to_clipboard))
            }

            R.id.menu_open_in_browser -> if (url != null) {
                if (!AuthHandoff.launchExternal(this, url)) {
                    showMessage(getString(R.string.oauth_no_browser))
                }
            }

            else -> return false
        }
        return true
    }

    private fun showTabSwitcher() {
        val sheetBinding = SheetTabsBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.tabs_title, controller.tabManager.count))
            .setView(sheetBinding.root)
            .setNegativeButton(R.string.action_close, null)
            .create()

        lateinit var adapter: TabAdapter
        adapter = TabAdapter(
            activeTabId = { controller.activeTab?.id },
            onSelect = { tab ->
                controller.selectTab(tab.id)
                dialog.dismiss()
            },
            onClose = { tab ->
                controller.closeTab(tab.id)
                if (controller.tabManager.count == 0) dialog.dismiss()
                else adapter.submit(controller.tabManager.tabs.toList())
            }
        )
        sheetBinding.tabList.layoutManager = LinearLayoutManager(this)
        sheetBinding.tabList.adapter = adapter
        adapter.submit(controller.tabManager.tabs.toList())

        sheetBinding.newTabButton.setOnClickListener {
            controller.openNewTab()
            dialog.dismiss()
        }
        sheetBinding.newPrivateTabButton.setOnClickListener {
            controller.openNewTab(isPrivate = true)
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showSecurityInfo() {
        val tab = controller.activeTab ?: return
        val message = getString(
            when (tab.securityState) {
                SecurityState.SECURE -> R.string.security_secure
                SecurityState.INSECURE -> R.string.security_insecure
                SecurityState.WARNING -> R.string.security_warning
                SecurityState.NEUTRAL -> R.string.security_neutral
            }
        )
        AlertDialog.Builder(this)
            .setMessage(message + "\n\n" + tab.url.orEmpty())
            .setPositiveButton(R.string.action_ok, null)
            .show()
    }

    // --- Rendering helpers ---

    private fun renderUrl(tab: Tab) {
        if (urlBarHasFocus) return
        binding.urlBar.setText(
            if (tab.isShowingHome) "" else UrlUtils.displayUrl(tab.url)
        )
    }

    private fun renderProgress(tab: Tab) {
        if (tab.isLoading && tab.progress in 1..99) {
            binding.progressBar.visibility = View.VISIBLE
            binding.progressBar.progress = tab.progress
        } else {
            binding.progressBar.visibility = View.GONE
        }
    }

    private fun renderSecurity(tab: Tab) {
        val (icon, description) = when (tab.securityState) {
            SecurityState.SECURE -> R.drawable.ic_lock to R.string.security_secure
            SecurityState.INSECURE -> R.drawable.ic_insecure to R.string.security_insecure
            SecurityState.WARNING -> R.drawable.ic_warning to R.string.security_warning
            SecurityState.NEUTRAL ->
                (if (tab.isPrivate) R.drawable.ic_private else R.drawable.ic_globe) to R.string.security_neutral
        }
        binding.securityButton.setImageResource(icon)
        binding.securityButton.contentDescription = getString(description)
    }

    private fun applyPrivateChrome(isPrivate: Boolean) {
        val toolbarColor = if (isPrivate) R.color.toolbar_background_private else R.color.toolbar_background
        val textColor = if (isPrivate) R.color.text_on_private else R.color.text_primary
        val fieldBackground = if (isPrivate) R.drawable.bg_url_field_private else R.drawable.bg_url_field

        binding.topBar.setBackgroundResource(toolbarColor)
        binding.bottomBar.setBackgroundResource(toolbarColor)
        binding.urlBar.setBackgroundResource(fieldBackground)
        binding.urlBar.setTextColor(androidx.core.content.ContextCompat.getColor(this, textColor))
        binding.urlBar.setHint(if (isPrivate) R.string.url_bar_hint_private else R.string.url_bar_hint)
        binding.tabCount.setTextColor(androidx.core.content.ContextCompat.getColor(this, textColor))
    }

    private fun maybeShowCompatibilityBanner() {
        if (!app.settings.showsCompatibilityWarning) return
        val info = WebViewInfo.read(this, app.observedUserAgent)
        app.observedUserAgent = info.userAgent.ifBlank { app.observedUserAgent }
        if (!info.isBelowModernBaseline) return

        binding.compatBannerText.text = getString(
            R.string.compat_warning_banner,
            info.chromiumFullVersion ?: getString(R.string.value_unknown)
        )
        binding.compatBanner.visibility = View.VISIBLE
    }

    private fun hideKeyboard() {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.hideSoftInputFromWindow(binding.urlBar.windowToken, 0)
    }

    // --- Lifecycle ---

    override fun onResume() {
        super.onResume()
        controller.onResume()
        MediaPlaybackService.setCommandHandler(this)
        controller.reapplySettings()
        if (binding.homeView.root.visibility == View.VISIBLE) {
            homeScreen.refresh(isPrivate = controller.activeTab?.isPrivate == true)
        }
    }

    override fun onPause() {
        super.onPause()
        // Deliberately does NOT pause engines: pausing them stops web audio, which
        // is the whole point of the media service. Engines are paused in onStop,
        // and the tab that is playing keeps running because the foreground service
        // keeps the process alive.
        controller.saveSession()
    }

    override fun onStop() {
        super.onStop()
        if (!isPlayingMedia()) controller.onPause()
    }

    private fun isPlayingMedia(): Boolean = MediaPlaybackService.isActive

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        controller.onTrimMemory(level)
    }

    override fun onDestroy() {
        fileChooser.cancelPending()
        permissions.cancelPending()
        MediaPlaybackService.setCommandHandler(null)
        if (app.settings.clearOnExit && isFinishing) {
            app.privacyManager.clearAll(null)
        }
        controller.destroy()
        attachedEngineView = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_AUTH_REDIRECT = "com.reweb.browser.AUTH_REDIRECT"
        const val EXTRA_AUTH_REDIRECT = "auth_redirect"

        /** Resumes the first paused media element; MediaSession has no page-side API. */
        private const val MEDIA_PLAY_SCRIPT = """
            (function () {
              var els = document.querySelectorAll('audio,video');
              for (var i = 0; i < els.length; i++) {
                if (els[i].paused) { els[i].play(); return; }
              }
            })();
        """

        private const val MEDIA_PAUSE_SCRIPT = """
            (function () {
              var els = document.querySelectorAll('audio,video');
              for (var i = 0; i < els.length; i++) {
                if (!els[i].paused) { els[i].pause(); }
              }
            })();
        """
    }
}
