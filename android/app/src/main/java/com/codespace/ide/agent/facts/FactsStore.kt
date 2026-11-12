package com.codespace.ide.agent.facts

import android.content.Context
import com.codespace.ide.agent.MemoryAttribution
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * FACTS — the durable store.
 *
 * SAME STORAGE ARCHITECTURE AS BEFORE, one level more structured: a JSON document under
 * `filesDir/agent_memory/`, written through the shared atomic write (`AtomicJson`), capped, and
 * loaded defensively. `facts.json` sits NEXT TO the old `memory.json` rather than replacing it:
 * the old file is read once for migration and then left untouched, so nothing is deleted before a
 * successful migration and an owner who rolls back to an older build still finds every memory that
 * build knew how to read.
 *
 * Every function has two forms: one taking a `Context` (production) and one taking the memory
 * DIRECTORY (tests). The directory form is the real implementation - the Context form only resolves
 * `filesDir/agent_memory` and delegates - so the unit tests below exercise the shipped code rather
 * than a mirror of it.
 *
 * Deliberately not SQLite: the dataset is bounded and read whole, so a migration-free JSON document
 * is the smallest thing that satisfies every requirement. A second database would need its own
 * schema, its own migration and its own copy of the scope rules.
 */
object FactsStore {

    const val DIR = "agent_memory"
    const val FACTS_FILE = "facts.json"
    const val LEGACY_FILE = "memory.json"
    const val ATTRIBUTION_FILE = "attribution.tsv"

    /** Same ceiling as the store it extends: one huge store must not bloat every future prompt. */
    const val MAX_STORE_BYTES = 256 * 1024
    /** Bounded list: an unbounded fact list is an unbounded prompt and an unusable screen. */
    const val MAX_FACTS = 500
    const val MAX_CONTENT = 1000

    fun memoryDir(context: Context): File =
        File(context.filesDir, DIR).apply { mkdirs() }

    fun factsFile(context: Context): File = File(memoryDir(context), FACTS_FILE)

    // ── load / write ────────────────────────────────────────────────────────────────

    /** Every fact, migrated from the legacy store on first read. Never throws. */
    fun load(context: Context): List<Fact> = loadFrom(memoryDir(context))

    fun loadFrom(d: File): List<Fact> {
        val file = File(d, FACTS_FILE)
        if (!file.exists()) return migrateLegacy(d)
        return try {
            parse(file.readText())
        } catch (_: Throwable) {
            // An unreadable document is left on disk (never overwritten blind) and reported empty.
            emptyList()
        }
    }

    fun parse(raw: String): List<Fact> {
        val arr = try { JSONArray(raw) } catch (_: Throwable) { return emptyList() }
        val out = ArrayList<Fact>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val f = fromJson(obj) ?: continue
            out.add(f)
        }
        return out
    }

    fun toJson(facts: List<Fact>): String {
        val arr = JSONArray()
        for (f in facts) arr.put(jsonOf(f))
        return arr.toString(2)
    }

    /** One record. Kept small on purpose: absent means default, never "unknown". */
    private fun jsonOf(f: Fact): JSONObject {
        val o = JSONObject()
        o.put("id", f.id)
        o.put("content", f.content)
        o.put("scope", f.scope.id)
        if (f.projectId != null) o.put("projectId", f.projectId)
        o.put("category", f.category.id)
        o.put("source", f.source.id)
        o.put("confidence", f.confidence.id)
        o.put("status", f.status.id)
        o.put("createdAt", f.createdAt)
        o.put("updatedAt", f.updatedAt)
        o.put("lastUsedAt", f.lastUsedAt)
        if (!f.sourceConversationId.isNullOrBlank()) o.put("sourceConversationId", f.sourceConversationId)
        if (!f.citation.isNullOrBlank()) o.put("citation", f.citation)
        if (!f.legacyKey.isNullOrBlank()) o.put("legacyKey", f.legacyKey)
        if (f.sessionOwner.isNotBlank()) o.put("sessionOwner", f.sessionOwner)
        if (!f.supersededBy.isNullOrBlank()) o.put("supersededBy", f.supersededBy)
        if (f.conflicts.isNotEmpty()) {
            val c = JSONArray()
            for (id in f.conflicts) c.put(id)
            o.put("conflicts", c)
        }
        return o
    }

    /**
     * Reads back exactly what [jsonOf] wrote. A record missing its content or scope is skipped
     * rather than repaired into something the user never wrote; one bad entry never hides the rest.
     */
    fun fromJson(o: JSONObject): Fact? {
        val content = o.optString("content", "").trim()
        if (content.isEmpty()) return null
        val scope = FactScope.parse(o.optString("scope")) ?: return null
        val conflicts = ArrayList<String>()
        o.optJSONArray("conflicts")?.let { arr ->
            for (i in 0 until arr.length()) {
                val id = arr.optString(i, "")
                if (id.isNotBlank()) conflicts.add(id)
            }
        }
        return Fact(
            id = o.optString("id", "").ifBlank { newId() },
            content = content,
            scope = scope,
            projectId = o.optString("projectId", "").ifBlank { null },
            category = FactCategory.parse(o.optString("category")),
            source = FactSource.parse(o.optString("source")),
            confidence = FactConfidence.parse(o.optString("confidence")),
            status = FactStatus.parse(o.optString("status")),
            createdAt = o.optLong("createdAt", 0L),
            updatedAt = o.optLong("updatedAt", 0L),
            lastUsedAt = o.optLong("lastUsedAt", 0L),
            sourceConversationId = o.optString("sourceConversationId", "").ifBlank { null },
            citation = o.optString("citation", "").ifBlank { null },
            legacyKey = o.optString("legacyKey", "").ifBlank { null },
            sessionOwner = o.optString("sessionOwner", ""),
            supersededBy = o.optString("supersededBy", "").ifBlank { null },
            conflicts = conflicts,
        )
    }

    fun write(context: Context, facts: List<Fact>): Boolean = writeTo(memoryDir(context), facts)

    fun writeTo(d: File, facts: List<Fact>): Boolean {
        val capped = facts.take(MAX_FACTS)
        val content = toJson(capped)
        if (content.length > MAX_STORE_BYTES) return false
        return com.codespace.ide.util.AtomicJson.write(File(d, FACTS_FILE), content)
    }

    fun newId(): String = "f_" + UUID.randomUUID().toString().replace("-", "").take(20)

    // ── migration ───────────────────────────────────────────────────────────────────

    /**
     * The old store was `memory.json` (key -> value) plus `attribution.tsv` (key -> session).
     * Each pair becomes one USER fact, keeping its key in [Fact.legacyKey] so `read_memory`,
     * `save_memory` and `delete_memory` keep addressing exactly what they addressed before.
     *
     * Safety properties, in order of importance:
     *  - NOTHING IS DELETED. The legacy files stay as the rollback copy.
     *  - The import is persisted once, so it is idempotent: a second read finds `facts.json`.
     *  - If the persist fails, the migrated list is still returned for THIS read (memory is not
     *    lost) and nothing is written, so the next attempt starts from the same legacy file.
     *  - A legacy entry that would now be refused (a stored credential) migrates as a SUPERSEDED
     *    placeholder that carries no secret, so it stops being retrievable without pretending it
     *    never existed.
     */
    fun migrateLegacy(d: File): List<Fact> {
        val legacy = File(d, LEGACY_FILE)
        if (!legacy.exists()) return emptyList()
        val map = try { JSONObject(legacy.readText()) } catch (_: Throwable) { JSONObject() }
        if (map.length() == 0) return emptyList()
        val attr = try { MemoryAttribution.readFile(File(d, ATTRIBUTION_FILE)) } catch (_: Throwable) { emptyMap() }
        val now = System.currentTimeMillis()
        val out = ArrayList<Fact>(map.length())
        for (key in map.keys().asSequence().toList()) {
            val value = map.optString(key, "").trim()
            if (value.isEmpty()) continue
            val secret = FactGuard.secretReason(value) != null
            out.add(
                Fact(
                    id = newId(),
                    content = if (secret) "Withheld: the old memory '" + key + "' contained a credential." else value,
                    scope = FactScope.USER,
                    category = FactCategory.infer(value),
                    source = FactSource.LEGACY,
                    confidence = if (secret) FactConfidence.INFERRED else FactConfidence.STATED,
                    status = if (secret) FactStatus.SUPERSEDED else FactStatus.ACTIVE,
                    createdAt = now,
                    updatedAt = now,
                    legacyKey = key,
                    sessionOwner = attr[key].orEmpty(),
                )
            )
        }
        if (out.isNotEmpty()) writeTo(d, out)
        return out
    }

    // ── read helpers ────────────────────────────────────────────────────────────────

    fun all(context: Context): List<Fact> = load(context)

    fun byId(context: Context, id: String): Fact? = load(context).firstOrNull { it.id == id }

    fun byLegacyKey(context: Context, key: String): Fact? =
        load(context).firstOrNull { it.legacyKey == key }

    /** Facts a prompt or a search may see: this project's facts plus the user's own. */
    fun retrievable(context: Context, projectId: String?): List<Fact> =
        load(context).filter { it.isRetrievable() && it.belongsTo(projectId) }

    // ── mutation ────────────────────────────────────────────────────────────────────

    /** What actually happened to a save, so the caller can report it without guessing. */
    sealed class SaveResult {
        data class Saved(val fact: Fact, val decision: FactDecision) : SaveResult()
        data class Refused(val reason: String) : SaveResult()
        data object Full : SaveResult()
        data object WriteFailed : SaveResult()
    }

    fun save(
        context: Context,
        content: String,
        scope: FactScope,
        projectId: String?,
        category: FactCategory? = null,
        source: FactSource = FactSource.EXPLICIT_USER,
        citation: String? = null,
        conversationId: String? = null,
        sessionOwner: String = "",
        confidence: FactConfidence? = null,
        now: Long = System.currentTimeMillis(),
    ): SaveResult = saveTo(
        memoryDir(context), content, scope, projectId, category, source, citation,
        conversationId, sessionOwner, confidence, now,
    )

    /**
     * The one write path for a new fact. Guard, dedupe, then persist - in that order, so a refused
     * save never touches the file and a duplicate never grows the list.
     */
    fun saveTo(
        d: File,
        content: String,
        scope: FactScope,
        projectId: String?,
        category: FactCategory? = null,
        source: FactSource = FactSource.EXPLICIT_USER,
        citation: String? = null,
        conversationId: String? = null,
        sessionOwner: String = "",
        confidence: FactConfidence? = null,
        now: Long = System.currentTimeMillis(),
    ): SaveResult {
        val text = content.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return SaveResult.Refused("A fact needs some content.")
        if (text.length > MAX_CONTENT) {
            return SaveResult.Refused("That fact is " + text.length + " characters; the limit is " +
                MAX_CONTENT + ". Save the short version you would actually rely on.")
        }
        if (scope == FactScope.PROJECT && projectId.isNullOrBlank()) {
            return SaveResult.Refused("A project fact needs a project. Open a project first, or " +
                "save this one as a user fact.")
        }
        FactGuard.rejectionReason(text, source)?.let { return SaveResult.Refused(it) }

        val all = loadFrom(d)
        val match = FactDedupe.classify(text, scope, projectId, all)
        val cat = category ?: FactCategory.infer(text)
        val conf = confidence ?: when (source) {
            FactSource.VERIFIED_FILE -> FactConfidence.VERIFIED
            FactSource.EXPLICIT_USER, FactSource.MANUAL -> FactConfidence.STATED
            else -> FactConfidence.INFERRED
        }
        val target = match.targetId?.let { id -> all.firstOrNull { it.id == id } }
        val applied = applySave(all, text, scope, projectId, cat, source, conf,
            citation, conversationId, sessionOwner, now, match, target)
        if (applied.facts.size > MAX_FACTS) return SaveResult.Full
        if (!writeTo(d, applied.facts)) return SaveResult.WriteFailed
        return SaveResult.Saved(applied.fact, applied.decision)
    }

    private class Applied(
        val facts: List<Fact>,
        val fact: Fact,
        val decision: FactDecision,
    )

    /**
     * The dedupe outcomes, one branch each. Extracted from [saveTo] so every branch is a few lines
     * and the JVM 64 KB method ceiling is never in play.
     */
    private fun applySave(
        all: List<Fact>,
        text: String,
        scope: FactScope,
        projectId: String?,
        cat: FactCategory,
        source: FactSource,
        conf: FactConfidence,
        citation: String?,
        conversationId: String?,
        sessionOwner: String,
        now: Long,
        match: FactMatch,
        target: Fact?,
    ): Applied {
        if (target != null && match.decision == FactDecision.DUPLICATE) {
            // Same statement: refresh provenance and usage, keep the id and the creation time.
            val stronger = target.copy(
                updatedAt = now,
                citation = citation ?: target.citation,
                sourceConversationId = conversationId ?: target.sourceConversationId,
                // An explicit statement upgrades what was only inferred before, never the reverse.
                source = if (source == FactSource.EXPLICIT_USER || source == FactSource.VERIFIED_FILE)
                    source else target.source,
                confidence = if (conf == FactConfidence.VERIFIED || target.confidence == FactConfidence.INFERRED)
                    conf else target.confidence,
            )
            return Applied(all.map { if (it.id == target.id) stronger else it },
                stronger, FactDecision.DUPLICATE)
        }
        if (target != null && match.decision == FactDecision.CORRECTION) {
            val fixed = target.copy(
                content = text,
                category = cat,
                updatedAt = now,
                source = source,
                confidence = conf,
                citation = citation ?: target.citation,
                sourceConversationId = conversationId ?: target.sourceConversationId,
            )
            return Applied(all.map { if (it.id == target.id) fixed else it }, fixed, FactDecision.CORRECTION)
        }
        if (target != null && match.decision == FactDecision.CONFLICT) {
            // Both sides stay. Neither is "the truth", and the prompt says so.
            val fresh = newFact(text, scope, projectId, cat, source, conf, now, citation,
                conversationId, sessionOwner).copy(
                status = FactStatus.CONFLICT,
                conflicts = listOf(target.id),
            )
            val flagged = target.copy(
                status = FactStatus.CONFLICT,
                updatedAt = now,
                conflicts = (target.conflicts + fresh.id).distinct(),
            )
            return Applied(all.map { if (it.id == target.id) flagged else it } + fresh,
                fresh, FactDecision.CONFLICT)
        }
        val fresh = newFact(text, scope, projectId, cat, source, conf, now, citation,
            conversationId, sessionOwner)
        return Applied(all + fresh, fresh, FactDecision.NEW)
    }

    private fun newFact(
        text: String,
        scope: FactScope,
        projectId: String?,
        cat: FactCategory,
        source: FactSource,
        conf: FactConfidence,
        now: Long,
        citation: String?,
        conversationId: String?,
        sessionOwner: String,
    ): Fact = Fact(
        id = newId(),
        content = text,
        scope = scope,
        projectId = projectId,
        category = cat,
        source = source,
        confidence = conf,
        status = FactStatus.ACTIVE,
        createdAt = now,
        updatedAt = now,
        sourceConversationId = conversationId,
        citation = citation,
        sessionOwner = sessionOwner,
    )

    fun update(
        context: Context,
        id: String,
        content: String? = null,
        category: FactCategory? = null,
        scope: FactScope? = null,
        projectId: String? = null,
    ): String? = updateIn(memoryDir(context), id, content, category, scope, projectId)

    /** Edit in place: identity, creation time and provenance of the ORIGINAL statement survive. */
    fun updateIn(
        d: File,
        id: String,
        content: String? = null,
        category: FactCategory? = null,
        scope: FactScope? = null,
        projectId: String? = null,
    ): String? {
        val all = loadFrom(d)
        val target = all.firstOrNull { it.id == id } ?: return "No fact with that id."
        val text = content?.trim()?.replace(Regex("\\s+"), " ")
        if (text != null) {
            if (text.isEmpty()) return "A fact needs some content."
            if (text.length > MAX_CONTENT) return "That fact is too long (limit " + MAX_CONTENT + ")."
            FactGuard.rejectionReason(text, FactSource.MANUAL)?.let { return it }
        }
        val newScope = scope ?: target.scope
        val newProject = if (newScope == FactScope.PROJECT) {
            projectId ?: target.projectId ?: return "A project fact needs a project."
        } else null
        val edited = target.copy(
            content = text ?: target.content,
            category = category ?: target.category,
            scope = newScope,
            projectId = newProject,
            updatedAt = System.currentTimeMillis(),
            // The user edited it by hand, so the statement is theirs now and no longer conflicted.
            status = if (target.status == FactStatus.CONFLICT) FactStatus.ACTIVE else target.status,
            source = FactSource.MANUAL,
            confidence = FactConfidence.STATED,
            conflicts = if (target.status == FactStatus.CONFLICT) emptyList() else target.conflicts,
        )
        val updated = all.map { if (it.id == id) edited else it }
        return if (writeTo(d, updated)) null else "The write failed; the fact is unchanged."
    }

    fun delete(context: Context, id: String): Boolean = deleteIn(memoryDir(context), id)

    /** Real deletion. The fact leaves the file, so nothing can retrieve it afterwards. */
    fun deleteIn(d: File, id: String): Boolean {
        val all = loadFrom(d)
        if (all.none { it.id == id }) return false
        val remaining = all.filter { it.id != id }.map { f ->
            when {
                f.supersededBy == id -> f.copy(status = FactStatus.ACTIVE, supersededBy = null)
                f.conflicts.contains(id) -> f.copy(conflicts = f.conflicts - id)
                else -> f
            }
        }
        return writeTo(d, remaining)
    }

    fun deleteAll(context: Context, scope: FactScope, projectId: String?): Boolean {
        val remaining = load(context).filterNot { f ->
            f.scope == scope && (scope == FactScope.USER || f.projectId == projectId)
        }
        return write(context, remaining)
    }

    fun supersede(context: Context, oldId: String, newId: String): Boolean =
        supersedeIn(memoryDir(context), oldId, newId)

    /** Mark a fact as replaced by another, keeping both for provenance. */
    fun supersedeIn(d: File, oldId: String, newId: String): Boolean {
        val all = loadFrom(d)
        if (all.none { it.id == oldId }) return false
        val now = System.currentTimeMillis()
        val updated = all.map {
            if (it.id == oldId) it.copy(status = FactStatus.SUPERSEDED, supersededBy = newId, updatedAt = now)
            else it
        }
        return writeTo(d, updated)
    }

    fun markUsed(context: Context, ids: Collection<String>, now: Long = System.currentTimeMillis()) =
        markUsedIn(memoryDir(context), ids, now)

    /** Retrieval reports what it used, which is what makes recency-of-use meaningful at all. */
    fun markUsedIn(d: File, ids: Collection<String>, now: Long = System.currentTimeMillis()) {
        if (ids.isEmpty()) return
        val wanted = ids.toSet()
        val all = loadFrom(d)
        var changed = false
        val updated = all.map { f ->
            if (f.id in wanted && !isSameDay(f.lastUsedAt, now)) {
                changed = true
                f.copy(lastUsedAt = now)
            } else f
        }
        if (changed) writeTo(d, updated)
    }

    /** Whole-day equality, so a prompt-heavy turn writes the file at most once a day. */
    fun isSameDay(a: Long, b: Long): Boolean {
        if (a <= 0L) return false
        val calA = java.util.Calendar.getInstance()
        calA.timeInMillis = a
        val calB = java.util.Calendar.getInstance()
        calB.timeInMillis = b
        return calA.get(java.util.Calendar.YEAR) == calB.get(java.util.Calendar.YEAR) &&
            calA.get(java.util.Calendar.DAY_OF_YEAR) == calB.get(java.util.Calendar.DAY_OF_YEAR)
    }
}
