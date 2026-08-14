package com.reweb.browser.webapp

import android.app.Activity
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import com.reweb.browser.R
import com.reweb.browser.browser.UrlUtils
import com.reweb.browser.browser.UserAgentMode
import com.reweb.browser.databinding.DialogInstallWebappBinding

/**
 * The "Install web app" flow.
 *
 * A real PWA install reads the site's web app manifest. The legacy WebView gives
 * an embedder no access to a parsed manifest, so ReWeb collects the same fields
 * from what it can see — the document title, the favicon, the current URL and
 * the user agent in force — and lets the user correct them before saving. The
 * result behaves like an installed app (own launcher entry, own task, own user
 * agent) without pretending to be a spec-compliant PWA install.
 */
class WebAppInstaller(
    private val activity: Activity,
    private val store: WebAppStore
) {

    fun promptInstall(
        url: String,
        suggestedName: String,
        favicon: Bitmap?,
        currentUserAgentMode: UserAgentMode,
        onInstalled: (WebAppProfile) -> Unit
    ) {
        if (!WebAppProfile.isInstallable(url)) {
            AlertDialog.Builder(activity)
                .setMessage(R.string.install_web_app_failed)
                .setPositiveButton(R.string.action_ok, null)
                .show()
            return
        }

        val binding = DialogInstallWebappBinding.inflate(LayoutInflater.from(activity))
        binding.nameField.setText(suggestedName.ifBlank { UrlUtils.hostOf(url) ?: url })
        binding.urlField.setText(url)
        favicon?.let { binding.webAppIcon.setImageBitmap(it) }

        val modes = listOf(
            UserAgentMode.DEFAULT to activity.getString(R.string.ua_default),
            UserAgentMode.MOBILE to activity.getString(R.string.ua_mobile),
            UserAgentMode.DESKTOP to activity.getString(R.string.ua_desktop)
        )
        binding.userAgentSpinner.adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_item,
            modes.map { it.second }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val preselected = modes.indexOfFirst { it.first == currentUserAgentMode }
        binding.userAgentSpinner.setSelection(if (preselected >= 0) preselected else 0)

        AlertDialog.Builder(activity)
            .setTitle(R.string.install_web_app_title)
            .setView(binding.root)
            .setPositiveButton(R.string.install_web_app_action) { _, _ ->
                val chosenUrl = binding.urlField.text.toString().trim()
                val profile = store.install(
                    name = binding.nameField.text.toString(),
                    url = chosenUrl,
                    icon = favicon,
                    // The legacy WebView exposes no parsed theme-color, so this is
                    // left unset rather than guessed from a screenshot.
                    themeColor = null,
                    userAgentMode = modes.getOrNull(binding.userAgentSpinner.selectedItemPosition)?.first
                        ?: UserAgentMode.DEFAULT
                )
                if (profile == null) {
                    AlertDialog.Builder(activity)
                        .setMessage(R.string.install_web_app_failed)
                        .setPositiveButton(R.string.action_ok, null)
                        .show()
                } else {
                    onInstalled(profile)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** Edit dialog for an already-installed app. */
    fun promptEdit(profile: WebAppProfile, onSaved: (WebAppProfile) -> Unit) {
        val binding = DialogInstallWebappBinding.inflate(LayoutInflater.from(activity))
        binding.nameField.setText(profile.name)
        binding.urlField.setText(profile.url)
        store.icon(profile)?.let { binding.webAppIcon.setImageBitmap(it) }

        val modes = listOf(
            UserAgentMode.DEFAULT to activity.getString(R.string.ua_default),
            UserAgentMode.MOBILE to activity.getString(R.string.ua_mobile),
            UserAgentMode.DESKTOP to activity.getString(R.string.ua_desktop)
        )
        binding.userAgentSpinner.adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_item,
            modes.map { it.second }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.userAgentSpinner.setSelection(
            modes.indexOfFirst { it.first == profile.userAgentMode }.coerceAtLeast(0)
        )

        AlertDialog.Builder(activity)
            .setTitle(R.string.web_app_edit_title)
            .setView(binding.root)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val updated = profile.copy(
                    name = binding.nameField.text.toString().trim().ifBlank { profile.name },
                    url = binding.urlField.text.toString().trim().ifBlank { profile.url },
                    userAgentMode = modes.getOrNull(binding.userAgentSpinner.selectedItemPosition)?.first
                        ?: profile.userAgentMode
                )
                if (WebAppProfile.isInstallable(updated.url) && store.update(updated)) {
                    onSaved(updated)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
}
