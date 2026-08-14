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

        rows.note(
            when {
                info.chromiumMajorVersion == null -> getString(R.string.diagnostics_webview_unknown)
                info.isBelowModernBaseline ->
                    getString(R.string.diagnostics_webview_outdated, info.chromiumMajorVersion)
                else -> getString(R.string.diagnostics_webview_current)
            }
        )

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

        // The probes need a document to run against. about:blank is enough and
        // needs no network, so the test measures the engine rather than the link.
        engine.loadUrl("about:blank")
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
                incognito = true,
                allowPopups = false,
                textZoomPercent = 100
            )
        )
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
