package com.reweb.browser.diagnostics

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.reweb.browser.BuildConfig
import com.reweb.browser.R
import com.reweb.browser.ReWebApplication
import com.reweb.browser.browser.BrowserActivity
import com.reweb.browser.browser.TabManager
import com.reweb.browser.databinding.ActivityScrollBinding
import com.reweb.browser.engine.BrowserEngine
import com.reweb.browser.engine.EngineConfiguration
import com.reweb.browser.engine.WebPermissionRequest
import com.reweb.browser.engine.WebPermissionKind
import com.reweb.browser.engine.EngineClient
import com.reweb.browser.engine.webview.SystemWebViewEngine
import com.reweb.browser.ui.RowBuilder

/**
 * Reports what this specific device can actually do.
 *
 * The point of the screen is to separate "ReWeb is broken" from "this device's
 * WebView is from 2017", which is the single most useful thing a browser for
 * legacy hardware can tell its user. Every capability line comes from running
 * real code in the engine — see [CompatibilityTest].
 */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScrollBinding
    private lateinit var rows: RowBuilder
    private val app: ReWebApplication get() = application as ReWebApplication

    /**
     * A hidden engine used purely to run the probes. It is never shown and is
     * destroyed with the activity, so the test cannot be influenced by, or
     * influence, a real page.
     */
    private var probeEngine: BrowserEngine? = null

    private var results: List<CompatCheck> = emptyList()
    private var isRunning = false
    private var info: WebViewInfo = WebViewInfo.readNotAvailable()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScrollBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        rows = RowBuilder(binding.container)

        info = WebViewInfo.read(this, app.observedUserAgent)
        app.observedUserAgent = info.userAgent.ifBlank { app.observedUserAgent }

        build()

        if (intent?.getBooleanExtra(EXTRA_RUN_TEST, false) == true) runCompatibilityTest()
    }

    private fun build() {
        rows.clear()

        rows.header(getString(R.string.diagnostics_device))
        rows.valueRow(getString(R.string.about_android), Build.VERSION.RELEASE)
        rows.valueRow(getString(R.string.about_api), Build.VERSION.SDK_INT.toString())
        rows.valueRow(getString(R.string.about_device), "${Build.MANUFACTURER} ${Build.MODEL}")
        rows.valueRow(getString(R.string.about_engine), getString(R.string.about_engine_value))
        rows.valueRow(
            getString(R.string.about_webview_package),
            info.packageName ?: getString(R.string.value_unknown)
        )
        rows.valueRow(
            getString(R.string.about_webview_version),
            info.packageVersion ?: getString(R.string.value_unknown)
        )
        rows.valueRow(
            getString(R.string.about_chromium),
            info.chromiumFullVersion ?: getString(R.string.value_unknown)
        )
        rows.valueRow(getString(R.string.about_version), BuildConfig.VERSION_NAME)

        val memoryClass = (getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.memoryClass
        rows.valueRow(
            "Heap limit",
            memoryClass?.let { "$it MB" } ?: getString(R.string.value_unknown)
        )
        rows.valueRow("Live tabs allowed", TabManager.computeMaxLiveEngines(this).toString())

        val trustStore = com.reweb.browser.browser.CertificateAdvice.trustStoreStatus()
        rows.valueRow(
            getString(R.string.diagnostics_trust_store),
            getString(
                when (trustStore) {
                    com.reweb.browser.browser.CertificateAdvice.TrustStoreStatus.CURRENT ->
                        R.string.diagnostics_trust_store_current
                    com.reweb.browser.browser.CertificateAdvice.TrustStoreStatus.REPAIRED ->
                        R.string.diagnostics_trust_store_repaired
                    com.reweb.browser.browser.CertificateAdvice.TrustStoreStatus.OUTDATED ->
                        R.string.diagnostics_trust_store_outdated
                }
            )
        )

        rows.note(
            when {
                info.chromiumMajorVersion == null -> getString(R.string.diagnostics_webview_unknown)
                info.isBelowModernBaseline ->
                    getString(R.string.diagnostics_webview_outdated, info.chromiumMajorVersion)
                else -> getString(R.string.diagnostics_webview_current)
            }
        )

        // The engine and the trust store are updated by completely different
        // mechanisms, so a device can have a modern Chromium and still fail TLS.
        // Only nag while the root is actually missing: repeating the instructions
        // to someone who has already followed them is just noise.
        when (trustStore) {
            com.reweb.browser.browser.CertificateAdvice.TrustStoreStatus.OUTDATED ->
                rows.note(getString(R.string.diagnostics_trust_store_note))
            com.reweb.browser.browser.CertificateAdvice.TrustStoreStatus.REPAIRED ->
                rows.note(getString(R.string.diagnostics_trust_store_repaired_note))
            com.reweb.browser.browser.CertificateAdvice.TrustStoreStatus.CURRENT -> Unit
        }

        buildDrmSection()

        rows.header(getString(R.string.diagnostics_capabilities))
        rows.note(getString(R.string.diagnostics_intro))
        rows.row(
            getString(if (isRunning) R.string.diagnostics_running else R.string.diagnostics_run_test),
            null
        ) { if (!isRunning) runCompatibilityTest() }

        results.forEach { check ->
            val binding = rows.row(check.label, check.detail)
            binding.settingValue.visibility = android.view.View.VISIBLE
            binding.settingValue.text = getString(
                when (check.result) {
                    CompatResult.PASS -> R.string.diagnostics_result_pass
                    CompatResult.FAIL -> R.string.diagnostics_result_fail
                    CompatResult.UNKNOWN -> R.string.diagnostics_result_unknown
                }
            )
            binding.settingValue.setBackgroundResource(
                when (check.result) {
                    CompatResult.PASS -> R.drawable.bg_result_pass
                    CompatResult.FAIL -> R.drawable.bg_result_fail
                    CompatResult.UNKNOWN -> R.drawable.bg_result_unknown
                }
            )
        }

        if (results.isNotEmpty()) {
            rows.row(getString(R.string.diagnostics_copy), null) { copyReport() }
        }

        buildTestTargets()
    }

    /**
     * Platform DRM facts, read from MediaDrm rather than from inside a page.
     *
     * Separating this from the in-page EME probe is the whole point: a device can
     * hold a Widevine library it cannot use, and can be able to use it natively
     * while the WebView still refuses. Those are three different problems with
     * three different answers, and only one of them is repairable.
     */
    private fun buildDrmSection() {
        val drm = DrmCapabilities.read()
        rows.header(getString(R.string.diagnostics_drm))

        rows.valueRow(
            getString(R.string.diagnostics_drm_scheme),
            getString(if (drm.schemeSupported) R.string.value_yes else R.string.value_no)
        )
        if (drm.schemeSupported) {
            rows.valueRow(
                getString(R.string.diagnostics_drm_level),
                drm.securityLevel ?: getString(R.string.value_unknown)
            )
            drm.vendor?.let { rows.valueRow(getString(R.string.diagnostics_drm_vendor), it) }
            drm.version?.let { rows.valueRow(getString(R.string.diagnostics_drm_version), it) }
            rows.valueRow(
                getString(R.string.diagnostics_drm_session),
                getString(if (drm.sessionOpened) R.string.value_yes else R.string.value_no)
            )
        }

        rows.note(
            when {
                !drm.schemeSupported -> getString(R.string.diagnostics_drm_absent)
                drm.needsProvisioning -> getString(R.string.diagnostics_drm_unprovisioned)
                drm.isUsableByPlatform -> getString(R.string.diagnostics_drm_platform_ok)
                else -> getString(
                    R.string.diagnostics_drm_failed,
                    drm.failure ?: getString(R.string.value_unknown)
                )
            }
        )
    }

    /**
     * Well-known sites for checking behaviour on this device. They are listed only
     * here; nothing in the browser treats any of them specially.
     */
    private fun buildTestTargets() {
        rows.header(getString(R.string.diagnostics_test_targets))
        rows.note(getString(R.string.diagnostics_test_targets_note))
        TEST_TARGETS.forEach { (name, url) ->
            rows.row(name, url) {
                startActivity(
                    Intent(this, BrowserActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .setData(Uri.parse(url))
                )
            }
        }
    }

    private fun runCompatibilityTest() {
        if (isRunning) return
        val engine = ensureProbeEngine()
        if (engine == null) {
            Toast.makeText(this, R.string.error_engine_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        isRunning = true
        build()

        // The probes need a document with a real, secure origin — see
        // BrowserEngine.loadDocumentAtOrigin. The origin uses the reserved
        // .invalid TLD so it can never collide with a real site, and nothing is
        // fetched over the network: the test measures the engine, not the link.
        engine.loadDocumentAtOrigin(PROBE_DOCUMENT, PROBE_ORIGIN)
        engine.view.postDelayed({
            CompatibilityTest(engine).run { checks ->
                if (isFinishing || isDestroyed) return@run
                results = checks
                isRunning = false
                build()
            }
        }, DOCUMENT_SETTLE_MS)
    }

    private fun ensureProbeEngine(): BrowserEngine? {
        probeEngine?.let { return it }
        val engine = runCatching { SystemWebViewEngine(this) }.getOrNull() ?: return null
        engine.applyConfiguration(
            EngineConfiguration(
                javaScriptEnabled = true,
                loadImages = false,
                userAgent = null,
                // Not incognito: a cookie/storage probe must run under the same
                // conditions as ordinary browsing, or it measures the probe.
                incognito = false,
                allowPopups = false,
                textZoomPercent = 100
            )
        )
        // Without a client, every permission the probe requests is denied by
        // default — including RESOURCE_PROTECTED_MEDIA_ID, which WebView requires
        // the embedder to grant before it will expose Widevine to EME. A probe
        // that denies its own DRM permission then reports the refusal as a device
        // limitation, which is exactly backwards.
        //
        // Only protected media is granted, and only to the synthetic probe origin.
        // Camera, microphone and location are still refused: measuring DRM needs
        // this, measuring nothing else does.
        engine.client = object : EngineClient {
            override fun onPermissionRequested(request: WebPermissionRequest) {
                val drmOnly = request.kinds.filterTo(mutableSetOf()) {
                    it == WebPermissionKind.PROTECTED_MEDIA
                }
                if (drmOnly.isEmpty()) request.deny() else request.grant(drmOnly)
            }
        }

        // The engine must be attached to the window. A detached WebView has no
        // surface, so graphics contexts (WebGL) fail to initialise and the test
        // would report a GPU limitation the device does not actually have.
        // 1x1 keeps it invisible without making it "gone", which would detach it.
        addContentView(engine.view, FrameLayout.LayoutParams(1, 1))
        probeEngine = engine
        return engine
    }

    private fun copyReport() {
        val report = buildString {
            appendLine("ReWeb ${BuildConfig.VERSION_NAME} diagnostics")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("WebView package: ${info.packageName ?: "unknown"} ${info.packageVersion.orEmpty()}")
            appendLine("Chromium: ${info.chromiumFullVersion ?: "unknown"}")
            appendLine()
            results.forEach { check ->
                appendLine("${check.result.name.padEnd(8)} ${check.label} — ${check.detail.orEmpty()}")
            }
        }
        // The user agent is deliberately excluded: it identifies the device more
        // precisely than anything else here, and this report gets pasted publicly.
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ReWeb diagnostics", report))
        Toast.makeText(this, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        probeEngine?.destroy()
        probeEngine = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RUN_TEST = "run_test"

        private const val DOCUMENT_SETTLE_MS = 300L

        /**
         * Reserved TLD, so this can never resolve to or collide with a real site.
         * https so the document counts as a secure context.
         */
        private const val PROBE_ORIGIN = "https://compat.reweb.invalid/"

        private const val PROBE_DOCUMENT =
            "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
                "<title>ReWeb compatibility probe</title></head><body></body></html>"

        private val TEST_TARGETS = listOf(
            "Google" to "https://www.google.com",
            "ChatGPT" to "https://chatgpt.com",
            "Spotify Web" to "https://open.spotify.com",
            "YouTube" to "https://m.youtube.com",
            "GitHub" to "https://github.com",
            "Wikipedia" to "https://en.wikipedia.org"
        )
    }
}
