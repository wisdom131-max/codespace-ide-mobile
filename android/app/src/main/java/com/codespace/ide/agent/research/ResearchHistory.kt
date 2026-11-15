package com.codespace.ide.agent.research

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * PROJECT 1.7 (2026-11-15): research that survives the turn it happened in.
 *
 * [SourceLedger] is process-local memory, so a follow-up question started from nothing: the pages
 * already read were gone after a process death, and every restart paid for the same fetches again.
 * The spec asks for research history and for continuing an existing investigation, so the record
 * goes on disk, per question, and a related question reuses what was already read instead of
 * fetching it twice.
 *
 * Selection is a pure function of the question's own words, so "which earlier research is this a
 * follow-up to" is testable without a device and cannot quietly match on nothing.
 */
class ResearchHistory private constructor(private val file: File) {

    data class Entry(
        val question: String,
        val atMs: Long,
        val sources: List<SourceLedger.Source>,
    )

    /** Newest first, bounded. */
    @Synchronized
    fun all(): List<Entry> = load().sortedByDescending { it.atMs }

    @Synchronized
    fun remember(question: String, atMs: Long, sources: List<SourceLedger.Source>) {
        val q = question.trim()
        if (q.isEmpty() || sources.isEmpty()) return
        val kept = all().filter { it.question.trim().lowercase() != q.lowercase() }
        val next = (listOf(Entry(q, atMs, sources)) + kept).take(MAX_ENTRIES)
        save(next)
    }

    /**
     * The earlier research this question continues, or null. Two shared content words is the bar,
     * because one shared word ("gradle") is a topic, not a follow-up, and reusing the wrong sources
     * would put unrelated citations in an answer.
     */
    @Synchronized
    fun continuationFor(question: String): Entry? = pick(all(), question)

    private fun load(): List<Entry> = try {
        if (!file.isFile) emptyList() else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val q = o.optString("question").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val sources = parseSources(o.optJSONArray("sources") ?: JSONArray())
                if (sources.isEmpty()) null else Entry(q, o.optLong("atMs"), sources)
            }
        }
    } catch (_: Throwable) {
        emptyList()
    }

    private fun parseSources(srcs: JSONArray): List<SourceLedger.Source> = (0 until srcs.length()).mapNotNull { j ->
        val s = srcs.optJSONObject(j) ?: return@mapNotNull null
        val url = s.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        SourceLedger.Source(
            id = s.optString("id"),
            title = s.optString("title"),
            url = url,
            domain = s.optString("domain"),
            retrievedAt = s.optString("retrievedAt"),
            publishedAt = s.optString("publishedAt"),
            excerpt = s.optString("excerpt"),
        )
    }

    private fun save(entries: List<Entry>) {
        try {
            val arr = JSONArray()
            entries.forEach { e ->
                val o = JSONObject()
                o.put("question", e.question)
                o.put("atMs", e.atMs)
                val srcs = JSONArray()
                e.sources.forEach { s ->
                    srcs.put(JSONObject().apply {
                        put("id", s.id); put("title", s.title); put("url", s.url)
                        put("domain", s.domain); put("retrievedAt", s.retrievedAt)
                        put("publishedAt", s.publishedAt); put("excerpt", s.excerpt)
                    })
                }
                o.put("sources", srcs)
                arr.put(o)
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(arr.toString())
                tmp.delete()
            }
        } catch (_: Throwable) {
            // Research history is a convenience; losing it must never fail a search.
        }
    }

    companion object {
        private const val MAX_ENTRIES = 12

        @Volatile
        private var instance: ResearchHistory? = null

        fun get(context: Context): ResearchHistory =
            instance ?: synchronized(this) {
                instance ?: ResearchHistory(
                    File(context.applicationContext.filesDir, "research-history.json"),
                ).also { instance = it }
            }

        internal fun forTest(file: File): ResearchHistory = ResearchHistory(file)

        /** Pure: which earlier entry, if any, this question continues. */
        fun pick(entries: List<Entry>, question: String): Entry? {
            val asked = words(question)
            if (asked.size < 2) return null
            return entries
                .map { it to words(it.question).count { w -> w in asked } }
                .filter { it.second >= 2 }
                .maxByOrNull { it.second }
                ?.first
        }

        private fun words(text: String): Set<String> =
            text.lowercase()
                .split(Regex("[^a-z0-9+#.]+"))
                .filter { it.length > 2 }
                .toSet()
    }
}
