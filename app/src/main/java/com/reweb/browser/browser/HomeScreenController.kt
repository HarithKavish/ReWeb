package com.reweb.browser.browser

import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.reweb.browser.bookmarks.BookmarkStore
import com.reweb.browser.databinding.ViewHomeBinding
import com.reweb.browser.history.HistoryStore
import com.reweb.browser.ui.LinkAdapter
import com.reweb.browser.ui.LinkItem
import com.reweb.browser.ui.WebAppAdapter
import com.reweb.browser.webapp.WebAppProfile
import com.reweb.browser.webapp.WebAppStore

/**
 * Drives the native start screen.
 *
 * Reads straight from the stores on refresh. The lists are capped and the
 * queries are indexed, so there is no background thread here — introducing one
 * would cost more in scheduling than the few milliseconds it saves.
 */
class HomeScreenController(
    private val binding: ViewHomeBinding,
    private val webAppStore: WebAppStore,
    private val bookmarkStore: BookmarkStore,
    private val historyStore: HistoryStore,
    private val onNavigate: (String) -> Unit,
    private val onLaunchWebApp: (WebAppProfile) -> Unit
) {

    private val webAppAdapter = WebAppAdapter(
        iconProvider = { webAppStore.icon(it) },
        onClick = { onLaunchWebApp(it) }
    )

    private val bookmarkAdapter = LinkAdapter(onClick = { onNavigate(it.url) })
    private val recentAdapter = LinkAdapter(onClick = { onNavigate(it.url) })

    init {
        binding.webAppsList.layoutManager = LinearLayoutManager(
            binding.root.context,
            RecyclerView.HORIZONTAL,
            false
        )
        binding.webAppsList.adapter = webAppAdapter

        binding.bookmarksList.layoutManager = LinearLayoutManager(binding.root.context)
        binding.bookmarksList.adapter = bookmarkAdapter

        binding.recentList.layoutManager = LinearLayoutManager(binding.root.context)
        binding.recentList.adapter = recentAdapter

        // Fixed row heights let RecyclerView skip a measure pass per row.
        binding.bookmarksList.setHasFixedSize(false)
        binding.recentList.setHasFixedSize(false)
    }

    fun refresh(isPrivate: Boolean) {
        binding.privateNotice.visibility = if (isPrivate) View.VISIBLE else View.GONE

        val webApps = webAppStore.all()
        webAppAdapter.submit(webApps)
        setSectionVisibility(binding.webAppsList, binding.webAppsEmpty, webApps.isNotEmpty())

        val bookmarks = bookmarkStore.all().take(MAX_BOOKMARKS).map { bookmark ->
            LinkItem(
                id = bookmark.id,
                title = bookmark.title,
                subtitle = UrlUtils.displayUrl(bookmark.url),
                url = bookmark.url,
                icon = bookmark.favicon()
            )
        }
        bookmarkAdapter.submit(bookmarks)
        setSectionVisibility(binding.bookmarksList, binding.bookmarksEmpty, bookmarks.isNotEmpty())

        // Private tabs write no history, and showing normal history on a private
        // start screen would leak it into a context the user expects to be clean.
        val recent = if (isPrivate) {
            emptyList()
        } else {
            historyStore.recentDistinctSites(MAX_RECENT).map { entry ->
                LinkItem(
                    id = entry.id,
                    title = entry.title.ifBlank { UrlUtils.displayUrl(entry.url) },
                    subtitle = UrlUtils.displayUrl(entry.url),
                    url = entry.url
                )
            }
        }
        recentAdapter.submit(recent)
        setSectionVisibility(binding.recentList, binding.recentEmpty, recent.isNotEmpty())
    }

    private fun setSectionVisibility(list: View, empty: View, hasContent: Boolean) {
        list.visibility = if (hasContent) View.VISIBLE else View.GONE
        empty.visibility = if (hasContent) View.GONE else View.VISIBLE
    }

    private companion object {
        const val MAX_BOOKMARKS = 6
        const val MAX_RECENT = 8
    }
}
