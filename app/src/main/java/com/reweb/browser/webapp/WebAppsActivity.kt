package com.reweb.browser.webapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.reweb.browser.R
import com.reweb.browser.ReWebApplication
import com.reweb.browser.browser.BrowserActivity
import com.reweb.browser.databinding.ActivityListBinding
import com.reweb.browser.ui.WebAppAdapter

class WebAppsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityListBinding
    private lateinit var adapter: WebAppAdapter
    private lateinit var installer: WebAppInstaller
    private val store: WebAppStore by lazy { (application as ReWebApplication).webAppStore }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        installer = WebAppInstaller(this, store)
        adapter = WebAppAdapter(
            iconProvider = { store.icon(it) },
            onClick = { profile -> startActivity(WebAppActivity.intentFor(this, profile.id)) },
            onLongClick = { profile, anchor -> showItemMenu(profile, anchor) }
        )
        // A grid that adapts to width: four across on a small legacy screen,
        // more on anything larger.
        val columns = (resources.displayMetrics.widthPixels /
            (96 * resources.displayMetrics.density)).toInt().coerceIn(3, 6)
        binding.list.layoutManager = GridLayoutManager(this, columns)
        binding.list.adapter = adapter
        binding.emptyView.setText(R.string.web_apps_empty)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val apps = store.all()
        adapter.submit(apps)
        binding.emptyView.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
        binding.list.visibility = if (apps.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showItemMenu(profile: WebAppProfile, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_OPEN, 0, R.string.action_open)
            menu.add(0, MENU_OPEN_BROWSER, 1, R.string.web_app_open_in_browser)
            menu.add(0, MENU_EDIT, 2, R.string.action_edit)
            menu.add(0, MENU_REMOVE, 3, R.string.action_delete)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_OPEN -> startActivity(WebAppActivity.intentFor(this@WebAppsActivity, profile.id))
                    MENU_OPEN_BROWSER -> startActivity(
                        Intent(this@WebAppsActivity, BrowserActivity::class.java)
                            .setAction(Intent.ACTION_VIEW)
                            .setData(Uri.parse(profile.url))
                    )
                    MENU_EDIT -> installer.promptEdit(profile) { refresh() }
                    MENU_REMOVE -> confirmRemove(profile)
                    else -> return@setOnMenuItemClickListener false
                }
                true
            }
            show()
        }
    }

    private fun confirmRemove(profile: WebAppProfile) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.web_app_remove_confirm, profile.name))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                store.remove(profile.id)
                Toast.makeText(
                    this,
                    getString(R.string.web_app_removed, profile.name),
                    Toast.LENGTH_SHORT
                ).show()
                refresh()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private companion object {
        const val MENU_OPEN = 1
        const val MENU_OPEN_BROWSER = 2
        const val MENU_EDIT = 3
        const val MENU_REMOVE = 4
    }
}
