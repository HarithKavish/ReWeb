package com.reweb.browser.browser

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.reweb.browser.R
import com.reweb.browser.databinding.ItemTabBinding

class TabAdapter(
    private val activeTabId: () -> Long?,
    private val onSelect: (Tab) -> Unit,
    private val onClose: (Tab) -> Unit
) : RecyclerView.Adapter<TabAdapter.ViewHolder>() {

    private var tabs: List<Tab> = emptyList()

    fun submit(newTabs: List<Tab>) {
        tabs = newTabs
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemTabBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(tabs[position])

    override fun getItemCount(): Int = tabs.size

    inner class ViewHolder(private val binding: ItemTabBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(tab: Tab) {
            val context = binding.root.context
            binding.title.text = tab.displayTitle().ifBlank { context.getString(R.string.tab_untitled) }

            // Showing suspension is deliberate: a user who wonders why a tab
            // reloaded should be able to see that it was reclaimed, not lost.
            binding.subtitle.text = when {
                tab.isPrivate && tab.isSuspended ->
                    "${context.getString(R.string.tabs_private_label)} · " +
                        context.getString(R.string.tabs_suspended_label)
                tab.isPrivate -> context.getString(R.string.tabs_private_label)
                tab.isSuspended -> context.getString(R.string.tabs_suspended_label)
                else -> UrlUtils.displayUrl(tab.url)
            }

            val favicon = tab.favicon
            if (favicon != null) binding.favicon.setImageBitmap(favicon)
            else binding.favicon.setImageResource(
                if (tab.isPrivate) R.drawable.ic_private else R.drawable.ic_globe
            )

            binding.tabRow.setBackgroundResource(
                if (tab.id == activeTabId()) R.drawable.bg_tab_active else 0
            )

            binding.root.setOnClickListener { onSelect(tab) }
            binding.closeTab.setOnClickListener { onClose(tab) }
        }
    }
}
