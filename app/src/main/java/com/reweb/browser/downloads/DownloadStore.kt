package com.reweb.browser.downloads

import android.content.ContentValues
import android.content.Context
import com.reweb.browser.data.ReWebDatabase

enum class DownloadStatus { PENDING, RUNNING, COMPLETE, FAILED }

data class DownloadRecord(
    val id: Long,
    val systemId: Long,
    val fileName: String,
    val url: String,
    val mimeType: String?,
    val totalBytes: Long,
    val status: DownloadStatus,
    val localUri: String?,
    val createdAt: Long
)

/** Local record of downloads, so the app can show status without polling the system. */
class DownloadStore(context: Context) {

    private val helper = ReWebDatabase(context)

    /**
     * @param systemId the DownloadManager id, or -1 for downloads ReWeb wrote
     *   itself (data: URIs), which have no system-side counterpart.
     */
    fun insert(
        systemId: Long,
        fileName: String,
        url: String,
        mimeType: String?,
        totalBytes: Long,
        status: DownloadStatus = DownloadStatus.RUNNING,
        localUri: String? = null
    ): Long {
        val values = ContentValues().apply {
            put(ReWebDatabase.COLUMN_SYSTEM_ID, systemId)
            put(ReWebDatabase.COLUMN_FILENAME, fileName)
            // The URL is stored so the download can be retried. It is never logged.
            put(ReWebDatabase.COLUMN_URL, url)
            put(ReWebDatabase.COLUMN_MIME_TYPE, mimeType)
            put(ReWebDatabase.COLUMN_TOTAL_BYTES, totalBytes)
            put(ReWebDatabase.COLUMN_STATUS, status.name)
            put(ReWebDatabase.COLUMN_LOCAL_URI, localUri)
            put(ReWebDatabase.COLUMN_CREATED_AT, System.currentTimeMillis())
        }
        return helper.writableDatabase.insert(ReWebDatabase.TABLE_DOWNLOADS, null, values)
    }

    fun updateStatus(
        systemId: Long,
        status: DownloadStatus,
        localUri: String? = null,
        totalBytes: Long? = null
    ) {
        val values = ContentValues().apply {
            put(ReWebDatabase.COLUMN_STATUS, status.name)
            localUri?.let { put(ReWebDatabase.COLUMN_LOCAL_URI, it) }
            totalBytes?.let { put(ReWebDatabase.COLUMN_TOTAL_BYTES, it) }
        }
        helper.writableDatabase.update(
            ReWebDatabase.TABLE_DOWNLOADS,
            values,
            "${ReWebDatabase.COLUMN_SYSTEM_ID} = ?",
            arrayOf(systemId.toString())
        )
    }

    fun all(limit: Int = 200): List<DownloadRecord> {
        return helper.readableDatabase.query(
            ReWebDatabase.TABLE_DOWNLOADS,
            arrayOf(
                ReWebDatabase.COLUMN_ID,
                ReWebDatabase.COLUMN_SYSTEM_ID,
                ReWebDatabase.COLUMN_FILENAME,
                ReWebDatabase.COLUMN_URL,
                ReWebDatabase.COLUMN_MIME_TYPE,
                ReWebDatabase.COLUMN_TOTAL_BYTES,
                ReWebDatabase.COLUMN_STATUS,
                ReWebDatabase.COLUMN_LOCAL_URI,
                ReWebDatabase.COLUMN_CREATED_AT
            ),
            null,
            null,
            null,
            null,
            "${ReWebDatabase.COLUMN_CREATED_AT} DESC",
            limit.toString()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        DownloadRecord(
                            id = cursor.getLong(0),
                            systemId = cursor.getLong(1),
                            fileName = cursor.getString(2),
                            url = cursor.getString(3),
                            mimeType = cursor.getString(4),
                            totalBytes = cursor.getLong(5),
                            status = runCatching { DownloadStatus.valueOf(cursor.getString(6)) }
                                .getOrDefault(DownloadStatus.FAILED),
                            localUri = cursor.getString(7),
                            createdAt = cursor.getLong(8)
                        )
                    )
                }
            }
        }
    }

    fun remove(id: Long) {
        helper.writableDatabase.delete(
            ReWebDatabase.TABLE_DOWNLOADS,
            "${ReWebDatabase.COLUMN_ID} = ?",
            arrayOf(id.toString())
        )
    }

    fun clear() {
        helper.writableDatabase.delete(ReWebDatabase.TABLE_DOWNLOADS, null, null)
    }
}
