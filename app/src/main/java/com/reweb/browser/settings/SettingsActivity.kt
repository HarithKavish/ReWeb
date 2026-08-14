package com.reweb.browser.settings

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.reweb.browser.BuildConfig
import com.reweb.browser.R
import com.reweb.browser.ReWebApplication
import com.reweb.browser.browser.SearchEngine
import com.reweb.browser.browser.UserAgentMode
import com.reweb.browser.databinding.ActivityScrollBinding
import com.reweb.browser.diagnostics.DiagnosticsActivity
import com.reweb.browser.diagnostics.WebViewInfo
import com.reweb.browser.privacy.PrivacyManager
import com.reweb.browser.webapp.WebAppsActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScrollBinding
    private lateinit var rows: com.reweb.browser.ui.RowBuilder
    private val app: ReWebApplication get() = application as ReWebApplication
    private val settings: Settings get() = app.settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScrollBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        rows = com.reweb.browser.ui.RowBuilder(binding.container)
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        rows.clear()
        buildGeneral()
        buildPrivacy()
        buildDownloads()
        buildCompatibility()
        buildWebApps()
        buildAbout()
    }

    private fun buildGeneral() {
        rows.header(getString(R.string.settings_general))

        rows.row(
            getString(R.string.settings_home_page),
            if (settings.usesNativeHomePage) getString(R.string.settings_home_page_native) else settings.homePage
        ) { promptHomePage() }

        rows.row(getString(R.string.settings_search_engine), settings.searchEngine().displayName) {
            promptSearchEngine()
        }

        rows.switchRow(
            getString(R.string.settings_javascript),
            getString(R.string.settings_javascript_summary),
            settings.javaScriptEnabled
        ) { enabled ->
            settings.javaScriptEnabled = enabled
        }

        rows.switchRow(
            getString(R.string.settings_images),
            getString(R.string.settings_images_summary),
            settings.loadImages
        ) { enabled -> settings.loadImages = enabled }

        rows.switchRow(
            getString(R.string.settings_popups),
            getString(R.string.settings_popups_summary),
            settings.allowPopups
        ) { enabled -> settings.allowPopups = enabled }

        rows.row(
            getString(R.string.settings_text_zoom),
            getString(R.string.settings_text_zoom_value, settings.textZoomPercent)
        ) { promptTextZoom() }
    }

    private fun buildPrivacy() {
        rows.header(getString(R.string.settings_privacy))

        rows.row(
            getString(R.string.settings_clear_data),
            getString(R.string.settings_clear_data_summary)
        ) { promptClearData() }

        rows.switchRow(
            getString(R.string.settings_clear_on_exit),
            null,
            settings.clearOnExit
        ) { enabled -> settings.clearOnExit = enabled }

        rows.switchRow(
            getString(R.string.settings_oauth_handoff),
            getString(R.string.settings_oauth_handoff_summary),
            settings.oauthHandoffEnabled
        ) { enabled -> settings.oauthHandoffEnabled = enabled }

        rows.note(getString(R.string.settings_data_note))
    }

    private fun buildDownloads() {
        rows.header(getString(R.string.settings_downloads))

        val subdirectory = settings.downloadSubdirectory
        rows.row(
            getString(R.string.settings_download_folder),
            if (subdirectory.isBlank()) {
                getString(R.string.settings_download_folder_root)
            } else {
                getString(R.string.settings_download_folder_summary, subdirectory)
            }
        ) { promptDownloadFolder() }

        rows.switchRow(
            getString(R.string.settings_download_ask),
            null,
            settings.askBeforeDownloading
        ) { enabled -> settings.askBeforeDownloading = enabled }
    }

    private fun buildCompatibility() {
        rows.header(getString(R.string.settings_compatibility))

        rows.row(getString(R.string.settings_default_ua), labelFor(settings.defaultUserAgentMode)) {
            promptDefaultUserAgent()
        }

        val overrides = app.siteSettings.all()
        rows.row(
            getString(R.string.settings_site_ua),
            if (overrides.isEmpty()) {
                getString(R.string.settings_site_ua_none)
            } else {
                getString(R.string.settings_site_ua_summary, overrides.size)
            }
        ) { showSiteOverrides() }

        rows.row(getString(R.string.settings_webview_info), null) {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }

        rows.row(getString(R.string.settings_run_compat_test), null) {
            startActivity(
                Intent(this, DiagnosticsActivity::class.java)
                    .putExtra(DiagnosticsActivity.EXTRA_RUN_TEST, true)
            )
        }
    }

    private fun buildWebApps() {
        rows.header(getString(R.string.web_apps))
        val count = app.webAppStore.all().size
        val summary = if (count == 0) getString(R.string.value_none) else count.toString()
        rows.row(getString(R.string.menu_web_apps), summary) {
            startActivity(Intent(this, WebAppsActivity::class.java))
        }
    }

    private fun buildAbout() {
        rows.header(getString(R.string.settings_about))
        val info = WebViewInfo.read(this, app.observedUserAgent)
        app.observedUserAgent = info.userAgent.ifBlank { app.observedUserAgent }

        rows.valueRow(getString(R.string.about_version), BuildConfig.VERSION_NAME)
        rows.valueRow(getString(R.string.about_build), BuildConfig.VERSION_CODE.toString())
        rows.valueRow(getString(R.string.about_android), Build.VERSION.RELEASE)
        rows.valueRow(getString(R.string.about_api), Build.VERSION.SDK_INT.toString())
        rows.valueRow(getString(R.string.about_device), "${Build.MANUFACTURER} ${Build.MODEL}")
        rows.valueRow(getString(R.string.about_engine), getString(R.string.about_engine_value))
        rows.valueRow(
            getString(R.string.about_webview_package),
            info.packageName ?: getString(R.string.value_unknown)
        )
        rows.valueRow(
            getString(R.string.about_chromium),
            info.chromiumFullVersion ?: getString(R.string.value_unknown)
        )
        rows.valueRow(getString(R.string.about_min_sdk), "Android 5.0 (API 21)")
    }

    // --- Prompts ---

    private fun promptHomePage() {
        val input = EditText(this).apply {
            setText(if (settings.usesNativeHomePage) "" else settings.homePage)
            hint = getString(R.string.settings_home_page_native)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_home_page)
            .setView(padded(input))
            .setPositiveButton(R.string.action_save) { _, _ ->
                val value = input.text.toString().trim()
                settings.homePage = if (value.isBlank()) Settings.HOME_NATIVE else value
                build()
            }
            .setNeutralButton(R.string.settings_home_page_native) { _, _ ->
                settings.homePage = Settings.HOME_NATIVE
                build()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun promptSearchEngine() {
        val engines = SearchEngine.BUILT_IN
        val labels = engines.map { it.displayName } + getString(R.string.settings_search_custom)
        val currentIndex = engines.indexOfFirst { it.id == settings.searchEngineId }
            .let { if (it >= 0) it else labels.lastIndex }

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_search_engine)
            .setSingleChoiceItems(labels.toTypedArray(), currentIndex) { dialog, which ->
                dialog.dismiss()
                if (which < engines.size) {
                    settings.searchEngineId = engines[which].id
                    build()
                } else {
                    promptCustomSearch()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun promptCustomSearch() {
        val input = EditText(this).apply {
            setText(settings.customSearchTemplate.orEmpty())
            hint = getString(R.string.settings_search_custom_hint)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_search_custom)
            .setView(padded(input))
            .setPositiveButton(R.string.action_save) { _, _ ->
                val template = input.text.toString()
                if (SearchEngine.custom(template) == null) {
                    Toast.makeText(this, R.string.settings_search_custom_invalid, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                settings.customSearchTemplate = template.trim()
                settings.searchEngineId = SearchEngine.CUSTOM_ID
                build()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun promptTextZoom() {
        val options = listOf(75, 90, 100, 115, 130, 150, 175, 200)
        val labels = options.map { getString(R.string.settings_text_zoom_value, it) }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_text_zoom)
            .setSingleChoiceItems(
                labels.toTypedArray(),
                options.indexOf(settings.textZoomPercent).coerceAtLeast(0)
            ) { dialog, which ->
                settings.textZoomPercent = options[which]
                dialog.dismiss()
                build()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun promptDefaultUserAgent() {
        val modes = listOf(UserAgentMode.DEFAULT, UserAgentMode.MOBILE, UserAgentMode.DESKTOP, UserAgentMode.CUSTOM)
        val labels = modes.map { labelFor(it) }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_default_ua)
            .setSingleChoiceItems(
                labels.toTypedArray(),
                modes.indexOf(settings.defaultUserAgentMode).coerceAtLeast(0)
            ) { dialog, which ->
                dialog.dismiss()
                if (modes[which] == UserAgentMode.CUSTOM) promptCustomUserAgent()
                else {
                    settings.defaultUserAgentMode = modes[which]
                    build()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun promptCustomUserAgent() {
        val input = EditText(this).apply {
            setText(settings.customUserAgent.orEmpty())
            hint = getString(R.string.settings_custom_ua_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_custom_ua)
            .setView(padded(input))
            .setPositiveButton(R.string.action_save) { _, _ ->
                val value = input.text.toString().trim()
                if (value.isBlank()) return@setPositiveButton
                settings.customUserAgent = value
                settings.defaultUserAgentMode = UserAgentMode.CUSTOM
                build()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showSiteOverrides() {
        val overrides = app.siteSettings.all()
        if (overrides.isEmpty()) {
            Toast.makeText(this, R.string.settings_site_ua_none, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = overrides.map { "${it.host} — ${labelFor(it.userAgentMode)}" }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_site_ua)
            .setItems(labels.toTypedArray()) { _, which ->
                val target = overrides[which]
                AlertDialog.Builder(this)
                    .setMessage(getString(R.string.settings_site_ua_remove, target.host))
                    .setPositiveButton(R.string.action_delete) { _, _ ->
                        app.siteSettings.remove(target.host)
                        build()
                    }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    private fun promptDownloadFolder() {
        val input = EditText(this).apply {
            setText(settings.downloadSubdirectory)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_download_folder)
            .setView(padded(input))
            .setPositiveButton(R.string.action_save) { _, _ ->
                settings.downloadSubdirectory = input.text.toString()
                build()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun promptClearData() {
        val labels = arrayOf(
            getString(R.string.settings_clear_history),
            getString(R.string.settings_clear_cookies),
            getString(R.string.settings_clear_cache),
            getString(R.string.settings_clear_site_storage),
            getString(R.string.settings_clear_downloads),
            getString(R.string.settings_clear_bookmarks)
        )
        // Bookmarks default to off: "clear browsing data" should not destroy them.
        val checked = booleanArrayOf(true, true, true, true, false, false)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_clear_data)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.action_delete) { _, _ ->
                val selection = PrivacyManager.Selection(
                    history = checked[0],
                    cookies = checked[1],
                    cache = checked[2],
                    siteStorage = checked[3],
                    downloadRecords = checked[4],
                    bookmarks = checked[5]
                )
                if (selection.isEmpty) {
                    Toast.makeText(this, R.string.settings_clear_nothing_selected, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                confirmClear(selection)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmClear(selection: PrivacyManager.Selection) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.settings_clear_confirm, app.privacyManager.summarize(selection)))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                app.privacyManager.clear(selection, null)
                Toast.makeText(this, R.string.settings_clear_done, Toast.LENGTH_SHORT).show()
                build()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun labelFor(mode: UserAgentMode): String = getString(
        when (mode) {
            UserAgentMode.DEFAULT -> R.string.ua_default
            UserAgentMode.MOBILE -> R.string.ua_mobile
            UserAgentMode.DESKTOP -> R.string.ua_desktop
            UserAgentMode.CUSTOM -> R.string.ua_custom
        }
    )

    private fun padded(view: android.view.View): android.view.View =
        android.widget.FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(view)
        }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
