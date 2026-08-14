package com.reweb.browser.history

import android.content.ContentValues
import android.content.Context
import com.reweb.browser.data.ReWebDatabase

data class HistoryEntry(
    val id: Long,
    val url: String,
    val title: String,
    val visitedAt: Long
)

/**
 * Local browsing history.
 *
 * Never receives entries from private tabs — [com.reweb.browser.browser.BrowserController]
 * filters those out before calling [recordVisit], so incognito pages have no path
 * into this table at all.
 *
 * The table is capped at [MAX_ENTRIES]. On a device with 512 MB of storage an
 * unbounded history is a real problem, and old entries have little value.
 */
class HistoryStore(context: Context) {

    private val helper = ReWebDatabase(context)

    /**
     * Records a visit, collapsing repeats: revisiting the URL that is already the
     * most recent entry updates its timestamp instead of adding a duplicate row,
     * which keeps reloads and single-page-app navigation from flooding the list.
     */
    fun recordVisit(url: String, title: String?) {
        if (!shouldRecord(url)) return
        val db = helper.writableDatabase
        val now = System.currentTimeMillis()

        // The id is the tie-break, and it is load-bearing rather than cosmetic:
        // several visits can share a millisecond, and ordering by timestamp alone
        // makes "the most recent row" ambiguous. SQLite is then free to return an
        // older row, and this method would update that instead of inserting -
        // silently losing a visit. AUTOINCREMENT ids give a total order.
        db.rawQuery(
            "SELECT ${ReWebDatabase.COLUMN_ID}, ${ReWebDatabase.COLUMN_URL} FROM ${ReWebDatabase.TABLE_HISTORY} " +
                "ORDER BY ${ReWebDatabase.COLUMN_VISITED_AT} DESC, ${ReWebDatabase.COLUMN_ID} DESC LIMIT 1",
            null
        ).use { cursor ->
            if (cursor.moveToFirst() && cursor.getString(1) == url) {
                val values = ContentValues().apply {
                    put(ReWebDatabase.COLUMN_VISITED_AT, now)
                    if (!title.isNullOrBlank()) put(ReWebDatabase.COLUMN_TITLE, title)
                }
                db.update(
                    ReWebDatabase.TABLE_HISTORY,
                    values,
                    "${ReWebDatabase.COLUMN_ID} = ?",
                    arrayOf(cursor.getLong(0).toString())
                )
                return
            }
        }

        val values = ContentValues().apply {
            put(ReWebDatabase.COLUMN_URL, url)
            put(ReWebDatabase.COLUMN_TITLE, title.orEmpty())
            put(ReWebDatabase.COLUMN_VISITED_AT, now)
        }
        db.insert(ReWebDatabase.TABLE_HISTORY, null, values)
        trimToLimit(db)
    }

    /** Updates the title of the newest row for [url], once the page reports one. */
    fun updateTitle(url: String, title: String) {
        if (title.isBlank() || !shouldRecord(url)) return
        val db = helper.writableDatabase
        db.execSQL(
            "UPDATE ${ReWebDatabase.TABLE_HISTORY} SET ${ReWebDatabase.COLUMN_TITLE} = ? " +
                "WHERE ${ReWebDatabase.COLUMN_ID} = (" +
                "  SELECT ${ReWebDatabase.COLUMN_ID} FROM ${ReWebDatabase.TABLE_HISTORY} " +
                "  WHERE ${ReWebDatabase.COLUMN_URL} = ? " +
                "  ORDER BY ${ReWebDatabase.COLUMN_VISITED_AT} DESC, ${ReWebDatabase.COLUMN_ID} DESC LIMIT 1)",
            arrayOf(title, url)
        )
    }

    fun recent(limit: Int = 200): List<HistoryEntry> = query(null, limit)

    fun search(query: String, limit: Int = 200): List<HistoryEntry> {
        if (query.isBlank()) return recent(limit)
        return query(query.trim(), limit)
    }

    private fun query(searchTerm: String?, limit: Int): List<HistoryEntry> {
        val db = helper.readableDatabase
        val selection = if (searchTerm == null) null
        else "${ReWebDatabase.COLUMN_URL} LIKE ? OR ${ReWebDatabase.COLUMN_TITLE} LIKE ?"
        val args = if (searchTerm == null) null
        else arrayOf("%$searchTerm%", "%$searchTerm%")

        return db.query(
            ReWebDatabase.TABLE_HISTORY,
            arrayOf(
                ReWebDatabase.COLUMN_ID,
                ReWebDatabase.COLUMN_URL,
                ReWebDatabase.COLUMN_TITLE,
                ReWebDatabase.COLUMN_VISITED_AT
            ),
            selection,
            args,
            null,
            null,
            "${ReWebDatabase.COLUMN_VISITED_AT} DESC, ${ReWebDatabase.COLUMN_ID} DESC",
            limit.toString()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        HistoryEntry(
                            id = cursor.getLong(0),
                            url = cursor.getString(1),
                            title = cursor.getString(2).orEmpty(),
                            visitedAt = cursor.getLong(3)
                        )
                    )
                }
            }
        }
    }

    /** Distinct most-recent URLs, for the home page's "Recent sites" row. */
    fun recentDistinctSites(limit: Int = 8): List<HistoryEntry> {
        val db = helper.readableDatabase
        return db.rawQuery(
            "SELECT MAX(${ReWebDatabase.COLUMN_ID}), ${ReWebDatabase.COLUMN_URL}, " +
                "${ReWebDatabase.COLUMN_TITLE}, MAX(${ReWebDatabase.COLUMN_VISITED_AT}) AS v " +
                "FROM ${ReWebDatabase.TABLE_HISTORY} GROUP BY ${ReWebDatabase.COLUMN_URL} " +
                "ORDER BY v DESC, MAX(${ReWebDatabase.COLUMN_ID}) DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        HistoryEntry(
                            id = cursor.getLong(0),
                            url = cursor.getString(1),
                            title = cursor.getString(2).orEmpty(),
                            visitedAt = cursor.getLong(3)
                        )
                    )
                }
            }
        }
    }

    fun delete(id: Long) {
        helper.writableDatabase.delete(
            ReWebDatabase.TABLE_HISTORY,
            "${ReWebDatabase.COLUMN_ID} = ?",
            arrayOf(id.toString())
        )
    }

    fun clear() {
        helper.writableDatabase.delete(ReWebDatabase.TABLE_HISTORY, null, null)
    }

    fun count(): Int {
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM ${ReWebDatabase.TABLE_HISTORY}",
            null
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun trimToLimit(db: android.database.sqlite.SQLiteDatabase) {
        db.execSQL(
            "DELETE FROM ${ReWebDatabase.TABLE_HISTORY} WHERE ${ReWebDatabase.COLUMN_ID} NOT IN (" +
                "SELECT ${ReWebDatabase.COLUMN_ID} FROM ${ReWebDatabase.TABLE_HISTORY} " +
                "ORDER BY ${ReWebDatabase.COLUMN_VISITED_AT} DESC, ${ReWebDatabase.COLUMN_ID} DESC LIMIT $MAX_ENTRIES)"
        )
    }

    companion object {
        const val MAX_ENTRIES = 3000

        /**
         * URLs that must never reach the history table. `data:` documents are how
         * ReWeb renders its own error and interstitial pages, and their bodies can
         * be large; the rest are not real destinations.
         */
        fun shouldRecord(url: String): Boolean {
            if (url.isBlank()) return false
            if (url == "about:blank") return false
            val lower = url.lowercase()
            return !(lower.startsWith("data:") ||
                lower.startsWith("javascript:") ||
                lower.startsWith("blob:") ||
                lower.startsWith("about:"))
        }
    }
}
