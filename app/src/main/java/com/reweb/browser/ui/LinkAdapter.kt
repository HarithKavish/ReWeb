package com.reweb.browser.ui

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.reweb.browser.databinding.ItemLinkBinding

/**
 * A URL row. Bookmarks, history and recent sites are the same thing on screen,
 * so they share one adapter rather than three near-identical ones.
 */
data class LinkItem(
    val id: Long,
    val title: String,
    val subtitle: String,
    val url: String,
    val icon: Bitmap? = null
)

class LinkAdapter(
    private val onClick: (LinkItem) -> Unit,
    private val onOverflow: ((LinkItem, View) -> Unit)? = null
) : RecyclerView.Adapter<LinkAdapter.ViewHolder>() {

    private var items: List<LinkItem> = emptyList()

    fun submit(newItems: List<LinkItem>) {
        items = newItems
        // The lists here are short and only ever replaced wholesale (a search, a
        // deletion, a screen reopening), so DiffUtil would cost more than it saves.
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemLinkBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemLinkBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: LinkItem) {
            binding.title.text = item.title.ifBlank { item.subtitle }
            binding.subtitle.text = item.subtitle
            if (item.icon != null) {
                binding.favicon.setImageBitmap(item.icon)
            } else {
                binding.favicon.setImageResource(com.reweb.browser.R.drawable.ic_globe)
            }
            binding.root.setOnClickListener { onClick(item) }

            if (onOverflow == null) {
                binding.overflow.visibility = View.GONE
            } else {
                binding.overflow.visibility = View.VISIBLE
                binding.overflow.setOnClickListener { anchor -> onOverflow.invoke(item, anchor) }
            }
        }
    }
}
