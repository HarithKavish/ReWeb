package com.reweb.browser.ui

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.reweb.browser.R
import com.reweb.browser.databinding.ItemHomeWebappBinding
import com.reweb.browser.webapp.WebAppProfile

/** Icon grid of installed web apps, used on the home screen and the Web apps screen. */
class WebAppAdapter(
    private val iconProvider: (WebAppProfile) -> Bitmap?,
    private val onClick: (WebAppProfile) -> Unit,
    private val onLongClick: ((WebAppProfile, View) -> Unit)? = null
) : RecyclerView.Adapter<WebAppAdapter.ViewHolder>() {

    private var items: List<WebAppProfile> = emptyList()

    fun submit(newItems: List<WebAppProfile>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemHomeWebappBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemHomeWebappBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(profile: WebAppProfile) {
            binding.name.text = profile.name
            val icon = iconProvider(profile)
            if (icon != null) binding.icon.setImageBitmap(icon)
            else binding.icon.setImageResource(R.drawable.ic_globe)

            binding.root.setOnClickListener { onClick(profile) }
            binding.root.setOnLongClickListener { anchor ->
                onLongClick?.invoke(profile, anchor)
                onLongClick != null
            }
        }
    }
}
