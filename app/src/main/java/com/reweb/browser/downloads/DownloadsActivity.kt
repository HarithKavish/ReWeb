package com.reweb.browser.downloads

import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.reweb.browser.R
import com.reweb.browser.ReWebApplication
import com.reweb.browser.databinding.ActivityListBinding
import com.reweb.browser.databinding.ItemDownloadBinding

class DownloadsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityListBinding
    private lateinit var adapter: DownloadAdapter
    private lateinit var controller: DownloadController
    private val app: ReWebApplication get() = application as ReWebApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        controller = DownloadController(this, app.settings, app.downloadStore)
        adapter = DownloadAdapter(
            onClick = { record -> open(record) },
            onOverflow = { record, anchor -> showItemMenu(record, anchor) }
        )
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.emptyView.setText(R.string.downloads_empty)
    }

    override fun onResume() {
        super.onResume()
        // Reconciling here rather than on a timer keeps the app free of background
        // polling; the list is only interesting while it is on screen.
        controller.refreshStatuses()
        refresh()
    }

    private fun refresh() {
        val records = app.downloadStore.all()
        adapter.submit(records)
        binding.emptyView.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        binding.list.visibility = if (records.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun open(record: DownloadRecord) {
        if (record.status != DownloadStatus.COMPLETE) return
        if (!controller.openDownload(this, record)) {
            Toast.makeText(this, R.string.download_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun showItemMenu(record: DownloadRecord, anchor: View) {
        PopupMenu(this, anchor).apply {
            if (record.status == DownloadStatus.COMPLETE) {
                menu.add(0, MENU_OPEN, 0, R.string.action_open)
            }
            menu.add(0, MENU_REMOVE, 1, R.string.action_delete)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_OPEN -> open(record)
                    MENU_REMOVE -> {
                        // Removes only the record. The file stays where the user can
                        // still find it; silently deleting downloaded files would be
                        // a surprise.
                        app.downloadStore.remove(record.id)
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
        menu.add(0, MENU_CLEAR, 0, R.string.download_clear)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }
        MENU_CLEAR -> {
            app.downloadStore.clear()
            refresh()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private inner class DownloadAdapter(
        private val onClick: (DownloadRecord) -> Unit,
        private val onOverflow: (DownloadRecord, View) -> Unit
    ) : RecyclerView.Adapter<DownloadAdapter.ViewHolder>() {

        private var records: List<DownloadRecord> = emptyList()

        fun submit(newRecords: List<DownloadRecord>) {
            records = newRecords
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(records[position])

        override fun getItemCount(): Int = records.size

        inner class ViewHolder(private val itemBinding: ItemDownloadBinding) :
            RecyclerView.ViewHolder(itemBinding.root) {

            fun bind(record: DownloadRecord) {
                itemBinding.fileName.text = record.fileName
                val status = getString(
                    when (record.status) {
                        DownloadStatus.COMPLETE -> R.string.download_status_complete
                        DownloadStatus.FAILED -> R.string.download_status_failed
                        DownloadStatus.PENDING -> R.string.download_status_pending
                        DownloadStatus.RUNNING -> R.string.download_status_running
                    }
                )
                val size = if (record.totalBytes > 0) {
                    Formatter.formatShortFileSize(this@DownloadsActivity, record.totalBytes)
                } else {
                    getString(R.string.download_size_unknown)
                }
                itemBinding.status.text = "$status · $size"
                itemBinding.root.setOnClickListener { onClick(record) }
                itemBinding.overflow.setOnClickListener { anchor -> onOverflow(record, anchor) }
            }
        }
    }

    private companion object {
        const val MENU_OPEN = 1
        const val MENU_REMOVE = 2
        const val MENU_CLEAR = 3
    }
}
