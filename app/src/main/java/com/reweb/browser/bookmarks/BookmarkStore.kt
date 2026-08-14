package com.reweb.browser.bookmarks

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.reweb.browser.data.ReWebDatabase
import java.io.ByteArrayOutputStream

data class Bookmark(
    val id: Long,
    val url: String,
    val title: String,
    val createdAt: Long,
    val faviconBytes: ByteArray?
) {
    fun favicon(): Bitmap? {
        val bytes = faviconBytes ?: return null
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
    }

    // ByteArray in a data class needs structural equality written out by hand,
    // otherwise two identical bookmarks compare unequal.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Bookmark) return false
        return id == other.id &&
            url == other.url &&
            title == other.title &&
            createdAt == other.createdAt &&
            faviconBytes.contentEquals(other.faviconBytes)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + url.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + (faviconBytes?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Bookmarks, keyed by URL. Adding a URL that already exists updates the existing
 * row rather than creating a duplicate, so the star in the toolbar is a simple
 * on/off state.
 */
class BookmarkStore(context: Context) {

    private val helper = ReWebDatabase(context)

    /** Returns the row id, or -1 if the URL is not bookmarkable. */
    fun add(url: String, title: String, favicon: Bitmap? = null): Long {
        if (!isBookmarkable(url)) return -1
        val db = helper.writableDatabase
        val values = ContentValues().apply {
            put(ReWebDatabase.COLUMN_URL, url)
            put(ReWebDatabase.COLUMN_TITLE, title.ifBlank { url })
            put(ReWebDatabase.COLUMN_CREATED_AT, System.currentTimeMillis())
            encodeFavicon(favicon)?.let { put(ReWebDatabase.COLUMN_FAVICON, it) }
        }
        return try {
            val id = db.insertOrThrow(ReWebDatabase.TABLE_BOOKMARKS, null, values)
            id
        } catch (_: SQLiteConstraintException) {
            // Already bookmarked: refresh title/icon and reuse the existing row.
            values.remove(ReWebDatabase.COLUMN_CREATED_AT)
            db.update(
                ReWebDatabase.TABLE_BOOKMARKS,
                values,
                "${ReWebDatabase.COLUMN_URL} = ?",
                arrayOf(url)
            )
            findByUrl(url)?.id ?: -1
        }
    }

    fun update(id: Long, title: String, url: String): Boolean {
        if (!isBookmarkable(url)) return false
        val values = ContentValues().apply {
            put(ReWebDatabase.COLUMN_TITLE, title.ifBlank { url })
            put(ReWebDatabase.COLUMN_URL, url)
        }
        return helper.writableDatabase.update(
            ReWebDatabase.TABLE_BOOKMARKS,
            values,
            "${ReWebDatabase.COLUMN_ID} = ?",
            arrayOf(id.toString())
        ) > 0
    }

    fun remove(id: Long): Boolean = helper.writableDatabase.delete(
        ReWebDatabase.TABLE_BOOKMARKS,
        "${ReWebDatabase.COLUMN_ID} = ?",
        arrayOf(id.toString())
    ) > 0

    fun removeByUrl(url: String): Boolean = helper.writableDatabase.delete(
        ReWebDatabase.TABLE_BOOKMARKS,
        "${ReWebDatabase.COLUMN_URL} = ?",
        arrayOf(url)
    ) > 0

    fun isBookmarked(url: String): Boolean = findByUrl(url) != null

    fun findByUrl(url: String): Bookmark? = readAll(
        selection = "${ReWebDatabase.COLUMN_URL} = ?",
        args = arrayOf(url),
        limit = 1
    ).firstOrNull()

    fun all(): List<Bookmark> = readAll(null, null, null)

    fun clear() {
        helper.writableDatabase.delete(ReWebDatabase.TABLE_BOOKMARKS, null, null)
    }

    private fun readAll(selection: String?, args: Array<String>?, limit: Int?): List<Bookmark> {
        val db = helper.readableDatabase
        return db.query(
            ReWebDatabase.TABLE_BOOKMARKS,
            arrayOf(
                ReWebDatabase.COLUMN_ID,
                ReWebDatabase.COLUMN_URL,
                ReWebDatabase.COLUMN_TITLE,
                ReWebDatabase.COLUMN_CREATED_AT,
                ReWebDatabase.COLUMN_FAVICON
            ),
            selection,
            args,
            null,
            null,
            "${ReWebDatabase.COLUMN_CREATED_AT} DESC",
            limit?.toString()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        Bookmark(
                            id = cursor.getLong(0),
                            url = cursor.getString(1),
                            title = cursor.getString(2).orEmpty(),
                            createdAt = cursor.getLong(3),
                            faviconBytes = if (cursor.isNull(4)) null else cursor.getBlob(4)
                        )
                    )
                }
            }
        }
    }

    companion object {
        /** Favicons are downscaled before storage; a bookmark list must stay cheap. */
        const val MAX_FAVICON_PX = 64

        fun isBookmarkable(url: String): Boolean {
            if (url.isBlank()) return false
            val lower = url.lowercase()
            return lower.startsWith("http://") || lower.startsWith("https://")
        }

        fun encodeFavicon(favicon: Bitmap?): ByteArray? {
            if (favicon == null || favicon.width <= 0 || favicon.height <= 0) return null
            return runCatching {
                val scaled = if (favicon.width > MAX_FAVICON_PX || favicon.height > MAX_FAVICON_PX) {
                    Bitmap.createScaledBitmap(favicon, MAX_FAVICON_PX, MAX_FAVICON_PX, true)
                } else {
                    favicon
                }
                ByteArrayOutputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.toByteArray()
                }
            }.getOrNull()
        }
    }
}
