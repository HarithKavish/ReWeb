package com.reweb.browser.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The app's only database.
 *
 * Deliberately uses the platform's `SQLiteOpenHelper` rather than Room. History
 * and bookmarks are three flat tables with no relations; Room would add roughly
 * a megabyte of dex plus an annotation processor to generate the same SQL that
 * fits on one screen here. See the dependency policy in ARCHITECTURE.md.
 */
internal class ReWebDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_HISTORY (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_URL TEXT NOT NULL,
                $COLUMN_TITLE TEXT,
                $COLUMN_VISITED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_history_visited_at ON $TABLE_HISTORY($COLUMN_VISITED_AT DESC)")
        db.execSQL("CREATE INDEX idx_history_url ON $TABLE_HISTORY($COLUMN_URL)")

        db.execSQL(
            """
            CREATE TABLE $TABLE_BOOKMARKS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_URL TEXT NOT NULL UNIQUE,
                $COLUMN_TITLE TEXT,
                $COLUMN_FAVICON BLOB,
                $COLUMN_CREATED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_DOWNLOADS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_SYSTEM_ID INTEGER NOT NULL DEFAULT -1,
                $COLUMN_FILENAME TEXT NOT NULL,
                $COLUMN_URL TEXT NOT NULL,
                $COLUMN_MIME_TYPE TEXT,
                $COLUMN_TOTAL_BYTES INTEGER NOT NULL DEFAULT 0,
                $COLUMN_STATUS TEXT NOT NULL,
                $COLUMN_LOCAL_URI TEXT,
                $COLUMN_CREATED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 is the first shipped schema, so there is no upgrade path yet. When one
        // is added it must migrate rather than drop: this data is not recoverable
        // from anywhere else.
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    companion object {
        const val DATABASE_NAME = "reweb.db"
        const val DATABASE_VERSION = 1

        const val TABLE_HISTORY = "history"
        const val TABLE_BOOKMARKS = "bookmarks"
        const val TABLE_DOWNLOADS = "downloads"

        const val COLUMN_ID = "_id"
        const val COLUMN_URL = "url"
        const val COLUMN_TITLE = "title"
        const val COLUMN_VISITED_AT = "visited_at"
        const val COLUMN_CREATED_AT = "created_at"
        const val COLUMN_FAVICON = "favicon"

        const val COLUMN_SYSTEM_ID = "system_id"
        const val COLUMN_FILENAME = "filename"
        const val COLUMN_MIME_TYPE = "mime_type"
        const val COLUMN_TOTAL_BYTES = "total_bytes"
        const val COLUMN_STATUS = "status"
        const val COLUMN_LOCAL_URI = "local_uri"
    }
}
