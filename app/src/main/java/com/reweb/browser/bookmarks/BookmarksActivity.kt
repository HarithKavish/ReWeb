package com.reweb.browser.bookmarks

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
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
import com.reweb.browser.databinding.DialogEditBookmarkBinding
import com.reweb.browser.ui.LinkAdapter
import com.reweb.browser.ui.LinkItem

class BookmarksActivity : AppCompatActivity() {

    private lateinit var binding: ActivityListBinding
    private lateinit var adapter: LinkAdapter
    private val store: BookmarkStore by lazy { (application as ReWebApplication).bookmarkStore }

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
        binding.emptyView.setText(R.string.bookmarks_empty)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val items = store.all().map { bookmark ->
            LinkItem(
                id = bookmark.id,
                title = bookmark.title,
                subtitle = UrlUtils.displayUrl(bookmark.url),
                url = bookmark.url,
                icon = bookmark.favicon()
            )
        }
        adapter.submit(items)
        binding.emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.list.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
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
            menu.add(0, MENU_EDIT, 1, R.string.action_edit)
            menu.add(0, MENU_DELETE, 2, R.string.action_delete)
            setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    MENU_OPEN -> openInBrowser(item.url)
                    MENU_EDIT -> showEditDialog(item)
                    MENU_DELETE -> confirmDelete(item)
                    else -> return@setOnMenuItemClickListener false
                }
                true
            }
            show()
        }
    }

    private fun showEditDialog(item: LinkItem) {
        val dialogBinding = DialogEditBookmarkBinding.inflate(LayoutInflater.from(this))
        dialogBinding.titleField.setText(item.title)
        dialogBinding.urlField.setText(item.url)

        AlertDialog.Builder(this)
            .setTitle(R.string.bookmark_edit_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val title = dialogBinding.titleField.text.toString().trim()
                val url = dialogBinding.urlField.text.toString().trim()
                if (!BookmarkStore.isBookmarkable(url)) {
                    Toast.makeText(this, R.string.bookmark_cannot, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                store.update(item.id, title, url)
                refresh()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmDelete(item: LinkItem) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.bookmark_delete_confirm, item.title.ifBlank { item.url }))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                store.remove(item.id)
                Toast.makeText(this, R.string.bookmark_removed, Toast.LENGTH_SHORT).show()
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
        const val MENU_EDIT = 2
        const val MENU_DELETE = 3
    }
}
