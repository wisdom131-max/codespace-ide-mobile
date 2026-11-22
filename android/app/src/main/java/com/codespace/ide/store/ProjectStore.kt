package com.codespace.ide.store

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.codespace.ide.engine.LexicalIndex

/**
 * The drawer the app never had.
 *
 * Android ships SQLite, so none of this needed a library, a download or a service: the filing
 * cabinet was already on the device and nobody had opened a drawer in it. Everything that has to
 * remember something between turns now comes through here, instead of each feature inventing its
 * own file format and its own way of failing.
 *
 * Two things live in it:
 *
 *  - The lexical index. [LexicalIndex] already knew the SQL; it had no handle to run it against.
 *  - A small key/value table, for state that is one value rather than a table: a checkpoint, a
 *    cached answer, the last trace.
 *
 * FTS5 is not guaranteed on every device build. If it is missing, [ftsAvailable] goes false and
 * every search returns nothing, which the caller treats as "no index yet" and answers the way it
 * did before. A missing feature must degrade, never crash.
 */
class ProjectStore private constructor(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    null,
    VERSION,
) {

    /** False when this device's SQLite has no FTS5; search then falls back to the plain walk. */
    @Volatile
    var ftsAvailable: Boolean = false
        private set

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT NOT NULL, updated_at INTEGER NOT NULL);",
        )
        ftsAvailable = try {
            db.execSQL(LexicalIndex.createSql())
            true
        } catch (_: Throwable) {
            false
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Nothing to migrate yet. Recreating is safe: every row here is derived, and the caller
        // rebuilds by indexing on the next search rather than restoring a backup.
        db.execSQL("DROP TABLE IF EXISTS kv;")
        onCreate(db)
    }

    /** Replace one file's indexed text. A path is indexed at most once. */
    fun indexFile(path: String, body: String) {
        if (!ftsAvailable || body.isEmpty()) return
        try {
            writableDatabase.apply {
                execSQL(LexicalIndex.deleteSql(), arrayOf<Any>(path))
                execSQL(LexicalIndex.insertSql(), arrayOf<Any>(path, body))
            }
        } catch (_: Throwable) {
            // A single file failing to index must not fail the search that triggered it.
        }
    }

    fun forgetFile(path: String) {
        if (!ftsAvailable) return
        try {
            writableDatabase.execSQL(LexicalIndex.deleteSql(), arrayOf<Any>(path))
        } catch (_: Throwable) {
        }
    }

    /** Ranked by bm25 inside SQLite, so the order is the index's, not insertion order. */
    fun searchFiles(terms: List<String>, limit: Int): List<String> {
        if (!ftsAvailable) return emptyList()
        val match = LexicalIndex.matchQuery(terms)
        if (match.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        try {
            readableDatabase.rawQuery(LexicalIndex.searchSql(limit), arrayOf(match)).use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(0) ?: continue
                    val snip = if (c.columnCount > 1) (c.getString(1) ?: "") else ""
                    out.add(path + ": " + snip.replace('\n', ' ').trim())
                }
            }
        } catch (_: Throwable) {
            return emptyList()
        }
        return out
    }

    fun indexedCount(): Int {
        if (!ftsAvailable) return 0
        return try {
            readableDatabase.rawQuery("SELECT count(*) FROM " + LexicalIndex.TABLE + ";", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
        } catch (_: Throwable) {
            0
        }
    }

    fun put(key: String, value: String) {
        try {
            writableDatabase.execSQL(
                "INSERT OR REPLACE INTO kv (k, v, updated_at) VALUES (?, ?, ?);",
                arrayOf<Any>(key, value, System.currentTimeMillis()),
            )
        } catch (_: Throwable) {
        }
    }

    fun get(key: String): String? = try {
        readableDatabase.rawQuery("SELECT v FROM kv WHERE k = ?;", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Throwable) {
        null
    }

    fun remove(key: String) {
        try {
            writableDatabase.execSQL("DELETE FROM kv WHERE k = ?;", arrayOf<Any>(key))
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val DB_NAME = "vncode-project.db"
        private const val VERSION = 1

        @Volatile
        private var shared: ProjectStore? = null

        /**
         * One handle per process. Opening the database per search would pay the open cost on every
         * call and lose the page cache that makes the index worth having.
         */
        fun of(context: Context): ProjectStore =
            shared ?: synchronized(this) {
                shared ?: ProjectStore(context).also { it.writableDatabase; shared = it }
            }
    }
}
