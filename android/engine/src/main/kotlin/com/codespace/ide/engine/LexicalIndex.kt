package com.codespace.ide.engine

object LexicalIndex {
    const val TABLE = "code_fts"
    fun createSql(): String =
        "CREATE VIRTUAL TABLE IF NOT EXISTS " + TABLE + " USING fts5(path UNINDEXED, body, tokenize=\"unicode61\");"
    fun insertSql(): String = "INSERT INTO " + TABLE + "(path, body) VALUES (?, ?);"
    fun deleteSql(): String = "DELETE FROM " + TABLE + " WHERE path = ?;"
    fun searchSql(limit: Int): String =
        "SELECT path, snippet(" + TABLE + ", 1, \"\", \"\", \"...\", 12) FROM " + TABLE +
        " WHERE " + TABLE + " MATCH ? ORDER BY bm25(" + TABLE + ") LIMIT " + limit.coerceIn(1, 100) + ";"
    fun matchQuery(terms: List<String>): String {
        val clean = terms.map { it.trim().replace("\"", "") }.filter { it.isNotEmpty() }.distinct().take(24)
        if (clean.isEmpty()) return ""
        return clean.joinToString(" OR ") { "\"" + it + "\"" }
    }
}
