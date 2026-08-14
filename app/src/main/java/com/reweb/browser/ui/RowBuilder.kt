package com.reweb.browser.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import com.reweb.browser.databinding.ItemSectionHeaderBinding
import com.reweb.browser.databinding.ItemSettingRowBinding

/**
 * Builds the settings and diagnostics screens row by row.
 *
 * These screens are long, mostly repetitive, and partly conditional — a WebView
 * fact that cannot be read on a given device should not leave a blank row.
 * Expressing them in code keeps that logic next to the data instead of spread
 * between a large XML file and the code that hides half of it.
 */
class RowBuilder(private val container: LinearLayout) {

    private val inflater: LayoutInflater = LayoutInflater.from(container.context)

    fun clear() {
        container.removeAllViews()
    }

    fun header(title: CharSequence) {
        val binding = ItemSectionHeaderBinding.inflate(inflater, container, false)
        binding.sectionHeader.text = title
        container.addView(binding.root)
    }

    /** A tappable row with an optional summary line. */
    fun row(
        title: CharSequence,
        summary: CharSequence? = null,
        onClick: (() -> Unit)? = null
    ): ItemSettingRowBinding {
        val binding = ItemSettingRowBinding.inflate(inflater, container, false)
        binding.settingTitle.text = title
        bindSummary(binding, summary)
        if (onClick == null) {
            binding.settingRow.isClickable = false
            binding.settingRow.background = null
        } else {
            binding.settingRow.setOnClickListener { onClick() }
        }
        container.addView(binding.root)
        return binding
    }

    /** A row whose trailing text is a read-only value, used by Diagnostics. */
    fun valueRow(title: CharSequence, value: CharSequence?, onClick: (() -> Unit)? = null) {
        val binding = row(title, null, onClick)
        binding.settingValue.visibility = View.VISIBLE
        binding.settingValue.text = value ?: ""
    }

    /**
     * A toggle. Tapping anywhere on the row flips it, which is a much easier
     * target than the switch itself on a small screen.
     */
    fun switchRow(
        title: CharSequence,
        summary: CharSequence? = null,
        checked: Boolean,
        onChanged: (Boolean) -> Unit
    ) {
        val binding = ItemSettingRowBinding.inflate(inflater, container, false)
        binding.settingTitle.text = title
        bindSummary(binding, summary)
        binding.settingSwitch.visibility = View.VISIBLE
        binding.settingSwitch.isChecked = checked
        binding.settingRow.setOnClickListener {
            val next = !binding.settingSwitch.isChecked
            binding.settingSwitch.isChecked = next
            onChanged(next)
        }
        container.addView(binding.root)
    }

    fun note(text: CharSequence) {
        val binding = ItemSettingRowBinding.inflate(inflater, container, false)
        binding.settingTitle.visibility = View.GONE
        binding.settingSummary.visibility = View.VISIBLE
        binding.settingSummary.text = text
        binding.settingRow.isClickable = false
        binding.settingRow.background = null
        container.addView(binding.root)
    }

    private fun bindSummary(binding: ItemSettingRowBinding, summary: CharSequence?) {
        if (summary.isNullOrBlank()) {
            binding.settingSummary.visibility = View.GONE
        } else {
            binding.settingSummary.visibility = View.VISIBLE
            binding.settingSummary.text = summary
        }
    }
}
