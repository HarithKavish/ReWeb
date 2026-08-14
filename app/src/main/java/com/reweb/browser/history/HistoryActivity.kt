package com.reweb.browser.history

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.reweb.browser.R
import com.reweb.browser.ReWebApplication
import com.reweb.browser.browser.BrowserActivity
import com.reweb.browser.browser.UrlUtils
import com.reweb.browser.databinding.ActivityListBinding
import com.reweb.browser.ui.LinkAdapter
import com.reweb.browser.ui.LinkItem
import java.text.DateFormat
import java.util.Date

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityListBinding
    private lateinit var adapter: LinkAdapter
    private val store: HistoryStore by lazy { (application as ReWebApplication).historyStore }
    private val timeFormat: DateFormat by lazy {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = LinkAdapter(
            onClick = { item -> openInBrowser(item.url) },
            onOverflow = { item, anchor -> showItemMenu(item, anchor) }
        )
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.emptyView.setText(R.string.history_empty)

        binding.searchField.visibility = View.VISIBLE
        binding.searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refresh()
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val query = binding.searchField.text.toString()
        val entries = if (query.isBlank()) store.recent() else store.search(query)
        adapter.submit(
            entries.map { entry ->
                LinkItem(
                    id = entry.id,
                    title = entry.title.ifBlank { UrlUtils.displayUrl(entry.url) },
                    subtitle = "${UrlUtils.displayUrl(entry.url)} · ${timeFormat.format(Date(entry.visitedAt))}",
                    url = entry.url
                )
            }
        )
        val isEmpty = entries.isEmpty()
        binding.emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.list.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun openInBrowser(url: String) {
        startActivity(
            Intent(this, BrowserActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse(url))
        )
        finish()
    }

    private fun showItemMenu(item: LinkItem, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_OPEN, 0, R.string.action_open)
            menu.add(0, MENU_DELETE, 1, R.string.action_delete)
            setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    MENU_OPEN -> openInBrowser(item.url)
                    MENU_DELETE -> {
                        store.delete(item.id)
                        Toast.makeText(
                            this@HistoryActivity,
                            R.string.history_entry_removed,
                            Toast.LENGTH_SHORT
                        ).show()
                        refresh()
                    }
                    else -> return@setOnMenuItemClickListener false
                }
                true
            }
            show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_CLEAR_ALL, 0, R.string.history_clear)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }
        MENU_CLEAR_ALL -> {
            confirmClearAll()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun confirmClearAll() {
        val count = store.count()
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.history_clear_confirm, count))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                store.clear()
                Toast.makeText(this, R.string.history_cleared, Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private companion object {
        const val MENU_OPEN = 1
        const val MENU_DELETE = 2
        const val MENU_CLEAR_ALL = 3
    }
}
