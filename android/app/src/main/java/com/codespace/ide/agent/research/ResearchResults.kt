package com.codespace.ide.agent.research

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * PROJECT 1.11 (2026-11-15): research results other projects can use.
 *
 * The prompt's last Project One requirement is that research results be "reusable by other Copilot
 * projects, especially Documents & Media Intelligence and Advanced Agent Capabilities". Until now a
 * round produced a rendered STRING and nothing else, so another feature could only scrape prose. The
 * ledger that held the evidence is per-turn memory and gone by the next question.
 *
 * So a finished brief is kept as typed data, bucketed the way the answer distinguishes them:
 * corroborated facts, claims only one source supports, and claims sources actually dispute. A
 * consumer asks for the latest result or the one closest to a question and gets claims with their
 * source ids, not paragraphs.
 */
class ResearchResults private constructor(private val file: File) {

    data class Claim(val subject: String, val value: String, val sourceId: String, val quote: String)

    /** One claim as the result shows it, with the sources used to judge it. */
    data class Reconciled(
        val subject: String,
        val value: String,
        val status: String,
        val note: String,
        val sourceId: String,
        val sources: List<String>,
    )

    data class Entry(
        val question: String,
        val atMs: Long,
        val corroborated: List<Claim>,
        val unsupported: List<Claim>,
        val disputed: List<Claim>,
        val unresolved: List<String>,
        val followUps: List<String>,
        /** Empty for results saved before claims were reconciled; the screen must cope. */
        val reconciled: List<Reconciled> = emptyList(),
    )

    @Synchronized
    fun save(question: String, atMs: Long, brief: ResearchSynthesis.Brief) {
        val q = question.trim().ifBlank { brief.question.trim() }
        if (q.isEmpty()) return
        val entry = Entry(
            question = q,
            atMs = atMs,
            corroborated = brief.corroborated.map { it.toClaim() },
            unsupported = brief.singleSource.map { it.toClaim() },
            disputed = brief.disputed.flatMap { d -> d.claims.map { it.toClaim() } },
            unresolved = brief.unresolved.take(12),
            followUps = brief.followUps.take(6),
            reconciled = brief.reconciled.take(12).map {
                Reconciled(
                    subject = it.subject,
                    value = it.value,
                    status = it.support.name.lowercase(),
                    note = it.note,
                    sourceId = it.sourceId,
                    sources = it.independentSources,
                )
            },
        )
        val kept = all().filter { !it.question.equals(q, ignoreCase = true) }
        write((listOf(entry) + kept).take(MAX_ENTRIES))
    }

    @Synchronized
    fun latest(): Entry? = all().firstOrNull()

    /** The most recent result for a question that shares two content words, else the latest. */
    @Synchronized
    fun forQuestion(question: String): Entry? {
        val entries = all()
        if (entries.isEmpty()) return null
        val asked = words(question)
        return entries.maxByOrNull { e -> words(e.question).count { it in asked } }
            ?.takeIf { e -> words(e.question).count { it in asked } >= 2 }
            ?: entries.first()
    }

    @Synchronized
    fun all(): List<Entry> = try {
        if (!file.isFile) emptyList() else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i -> entryOrNull(arr.optJSONObject(i)) }
        }
    } catch (_: Throwable) {
        emptyList()
    }

    private fun entryOrNull(o: JSONObject?): Entry? {
        if (o == null) return null
        val q = o.optString("question").takeIf { it.isNotBlank() } ?: return null
        return Entry(
            question = q,
            atMs = o.optLong("atMs"),
            corroborated = claims(o.optJSONArray("corroborated")),
            unsupported = claims(o.optJSONArray("unsupported")),
            disputed = claims(o.optJSONArray("disputed")),
            unresolved = strings(o.optJSONArray("unresolved")),
            reconciled = reconciledRows(o.optJSONArray("reconciled")),
            followUps = strings(o.optJSONArray("followUps")),
        )
    }

    /** Missing or malformed rows are skipped: an old result simply has nothing to show. */
    private fun reconciledRows(arr: JSONArray?): List<Reconciled> {
        if (arr == null) return emptyList()
        val out = ArrayList<Reconciled>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val subject = o.optString("subject").trim()
            if (subject.isEmpty()) continue
            out.add(
                Reconciled(
                    subject = subject,
                    value = o.optString("value"),
                    status = o.optString("status"),
                    note = o.optString("note"),
                    sourceId = o.optString("sourceId"),
                    sources = strings(o.optJSONArray("sources")),
                )
            )
        }
        return out
    }

    private fun claims(arr: JSONArray?): List<Claim> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val subject = o.optString("subject")
            if (subject.isBlank()) null else Claim(
                subject = subject,
                value = o.optString("value"),
                sourceId = o.optString("sourceId"),
                quote = o.optString("quote"),
            )
        }
    }

    private fun strings(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }

    private fun write(entries: List<Entry>) {
        try {
            val arr = JSONArray()
            entries.forEach { e ->
                val o = JSONObject()
                o.put("question", e.question)
                o.put("atMs", e.atMs)
                o.put("corroborated", json(e.corroborated))
                o.put("unsupported", json(e.unsupported))
                o.put("disputed", json(e.disputed))
                o.put("unresolved", JSONArray(e.unresolved))
                o.put("followUps", JSONArray(e.followUps))
                if (e.reconciled.isNotEmpty()) o.put("reconciled", jsonOf(e.reconciled))
                arr.put(o)
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(arr.toString())
                tmp.delete()
            }
        } catch (_: Throwable) {
            // A structured copy is an extra; it must never fail the search that produced it.
        }
    }

    private fun jsonOf(rows: List<Reconciled>): JSONArray {
        val arr = JSONArray()
        rows.forEach { r ->
            arr.put(
                JSONObject()
                    .put("subject", r.subject)
                    .put("value", r.value)
                    .put("status", r.status)
                    .put("note", r.note)
                    .put("sourceId", r.sourceId)
                    .put("sources", JSONArray(r.sources))
            )
        }
        return arr
    }

    private fun json(claims: List<Claim>): JSONArray {
        val arr = JSONArray()
        claims.forEach { c ->
            arr.put(JSONObject().apply {
                put("subject", c.subject); put("value", c.value)
                put("sourceId", c.sourceId); put("quote", c.quote)
            })
        }
        return arr
    }

    companion object {
        private const val MAX_ENTRIES = 8

        @Volatile
        private var instance: ResearchResults? = null

        fun get(context: Context): ResearchResults =
            instance ?: synchronized(this) {
                instance ?: ResearchResults(
                    File(context.applicationContext.filesDir, "research-results.json"),
                ).also { instance = it }
            }

        internal fun forTest(file: File): ResearchResults = ResearchResults(file)

        private fun words(text: String): Set<String> =
            text.lowercase().split(Regex("[^a-z0-9+#.]+")).filter { it.length > 2 }.toSet()

        private fun ContradictionScan.Claim.toClaim(): Claim =
            Claim(subject = subject, value = value, sourceId = sourceId, quote = quote)
    }
}
