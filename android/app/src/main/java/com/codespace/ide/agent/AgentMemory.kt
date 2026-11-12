package com.codespace.ide.agent

import android.content.Context
import com.codespace.ide.agent.facts.Fact
import com.codespace.ide.agent.facts.FactCategory
import com.codespace.ide.agent.facts.FactScope
import com.codespace.ide.agent.facts.FactSource
import com.codespace.ide.agent.facts.FactStatus
import com.codespace.ide.agent.facts.FactsStore
import java.io.File

/**
 * AgentMemory — the memory FACADE the tools and the model still call.
 *
 * FACTS (2026-11-11): the durable record is now [FactsStore] (structured, scoped, with provenance),
 * and this object keeps the `key -> value` contract every existing caller, test and prompt line was
 * written against. A key is simply the [Fact.legacyKey] of a USER-scoped fact, so:
 *
 *   - `save_memory/read_memory/delete_memory` address the same things they always did;
 *   - the old scope rule (M5: current session vs all sessions) is unchanged, now applied to
 *     [Fact.sessionOwner];
 *   - everything a save used to throw away - why it exists, where it came from, when it changed -
 *     is kept, because the store has somewhere to put it.
 *
 * There is exactly ONE memory database. Two would have meant two migrations, two scope rules and
 * eventually two different answers to the same question.
 *
 * Each entry point has a `*In(directory)` form so the behaviour is testable on plain files; the
 * Context form only resolves the directory.
 */
object AgentMemory {

    /** Same ceiling as before: one huge save must not bloat every future prompt. */
    const val MAX_STORE_BYTES = FactsStore.MAX_STORE_BYTES

    // ── legacy key API ──────────────────────────────────────────────────────────────

    fun save(
        key: String,
        value: String,
        context: Context,
        scope: MemoryScope = MemoryScope.ALL_SESSIONS,
        sessionId: String = "",
        category: FactCategory? = null,
        citation: String? = null,
        conversationId: String? = null,
    ): String = saveIn(
        FactsStore.memoryDir(context), key, value, scope, sessionId, category, citation, conversationId,
    )

    /**
     * Saves (or corrects) a memory addressed by [key]. A key that already exists is a CORRECTION of
     * that fact, not a second memory: that is what the old store's overwrite semantics meant, and
     * changing it would turn every repeated save into a duplicate.
     */
    fun saveIn(
        d: File,
        key: String,
        value: String,
        scope: MemoryScope = MemoryScope.ALL_SESSIONS,
        sessionId: String = "",
        category: FactCategory? = null,
        citation: String? = null,
        conversationId: String? = null,
    ): String {
        val cleanKey = key.trim()
        if (cleanKey.isEmpty()) {
            return "A memory needs a key, e.g. {\"key\":\"build_command\",\"value\":\"...\"}."
        }
        val owner = MemoryScopeFilter.attributionFor(scope, sessionId)
        val existing = FactsStore.loadFrom(d).firstOrNull { it.legacyKey == cleanKey }
        if (existing != null) {
            val err = FactsStore.updateIn(d, existing.id, content = value, category = category)
            if (err != null) return "Memory not saved: " + err
            return "Memory updated: '" + cleanKey + "'" + scopeSuffix(owner)
        }
        return when (val r = FactsStore.saveTo(
            d = d,
            content = value,
            scope = FactScope.USER,
            projectId = null,
            category = category ?: FactCategory.infer(cleanKey + ": " + value),
            source = FactSource.EXPLICIT_USER,
            citation = citation,
            conversationId = conversationId,
            sessionOwner = owner,
        )) {
            is FactsStore.SaveResult.Saved -> {
                attachLegacyKey(d, r.fact.id, cleanKey)
                "Memory saved: '" + cleanKey + "'" + scopeSuffix(owner)
            }
            is FactsStore.SaveResult.Refused -> "Memory not saved: " + r.reason
            FactsStore.SaveResult.Full ->
                "Memory is full (" + FactsStore.MAX_FACTS + " facts). Delete a few and retry."
            FactsStore.SaveResult.WriteFailed ->
                "Memory saved nowhere: the store write failed. Nothing was changed."
        }
    }

    /** Records the key on the fact the store just wrote, so `get`/`delete` can address it. */
    private fun attachLegacyKey(d: File, factId: String, key: String) {
        val all = FactsStore.loadFrom(d)
        val updated = all.map { if (it.id == factId) it.copy(legacyKey = key) else it }
        FactsStore.writeTo(d, updated)
    }

    private fun scopeSuffix(owner: String): String =
        if (owner.isBlank()) " (shared with all sessions)" else " (this session only)"

    fun readAll(
        context: Context,
        scope: MemoryScope = MemoryScope.ALL_SESSIONS,
        sessionId: String = "",
        projectId: String? = null,
    ): String = readAllIn(FactsStore.memoryDir(context), scope, sessionId, projectId)

    fun readAllIn(
        d: File,
        scope: MemoryScope = MemoryScope.ALL_SESSIONS,
        sessionId: String = "",
        projectId: String? = null,
    ): String {
        val visible = visibleFactsIn(d, scope, sessionId, projectId)
        if (visible.isEmpty()) {
            val total = FactsStore.loadFrom(d).size
            return if (total == 0) "No memories saved yet."
            else "No memories are visible in this scope (" + scope.label + "). " + total +
                " saved memor" + (if (total == 1) "y belongs" else "ies belong") + " elsewhere."
        }
        val sb = StringBuilder("Saved memories (" + visible.size + "):\n")
        for (f in visible) {
            val label = f.legacyKey ?: f.id
            val prov = f.provenanceLine()
            sb.append("  ").append(label).append(": ").append(f.content.take(200))
            if (prov.isNotBlank()) sb.append("  [").append(prov).append(']')
            sb.append('\n')
        }
        if (scope == MemoryScope.CURRENT_SESSION) sb.append("Scope: this session only.")
        return sb.toString().trim()
    }

    fun delete(
        key: String,
        context: Context,
        scope: MemoryScope = MemoryScope.ALL_SESSIONS,
        sessionId: String = "",
    ): String = deleteIn(FactsStore.memoryDir(context), key, scope, sessionId)

    fun deleteIn(
        d: File,
        key: String,
        scope: MemoryScope = MemoryScope.ALL_SESSIONS,
        sessionId: String = "",
    ): String {
        val target = FactsStore.loadFrom(d).firstOrNull { it.legacyKey == key || it.id == key }
            ?: return "No memory found for key '" + key + "'"
        // M5: a scope that cannot SEE a memory cannot delete it either.
        if (!sessionAllows(target.sessionOwner, scope, sessionId)) {
            return "No memory found for key '" + key + "' in this scope."
        }
        return if (FactsStore.deleteIn(d, target.id)) "Deleted memory: '" + key + "'"
        else "The delete write failed, so the memory is unchanged."
    }

    fun get(
        key: String,
        context: Context,
        scope: MemoryScope = MemoryScope.ALL_SESSIONS,
        sessionId: String = "",
    ): String? = getIn(FactsStore.memoryDir(context), key, scope, sessionId)

    fun getIn(
        d: File,
        key: String,
        scope: MemoryScope = MemoryScope.ALL_SESSIONS,
        sessionId: String = "",
    ): String? {
        val fact = FactsStore.loadFrom(d).firstOrNull { it.legacyKey == key } ?: return null
        if (!sessionAllows(fact.sessionOwner, scope, sessionId)) return null
        return fact.content.ifBlank { null }
    }

    fun visibleEntries(
        context: Context,
        sessionId: String = "",
        scope: MemoryScope = MemoryScope.ALL_SESSIONS,
        projectId: String? = null,
    ): Map<String, String> =
        visibleEntriesIn(FactsStore.memoryDir(context), sessionId, scope, projectId)

    fun visibleEntriesIn(
        d: File,
        sessionId: String = "",
        scope: MemoryScope = MemoryScope.ALL_SESSIONS,
        projectId: String? = null,
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (f in visibleFactsIn(d, scope, sessionId, projectId)) {
            out[f.legacyKey ?: f.id] = f.content
        }
        return out
    }

    fun visibleFacts(
        context: Context,
        scope: MemoryScope = MemoryScope.ALL_SESSIONS,
        sessionId: String = "",
        projectId: String? = null,
        includeSuperseded: Boolean = false,
    ): List<Fact> = visibleFactsIn(FactsStore.memoryDir(context), scope, sessionId, projectId, includeSuperseded)

    /**
     * The M5 visibility rule for ONE fact, kept in step with MemoryScopeFilter: a blank owner means
     * global/legacy and is visible everywhere.
     */
    fun sessionAllows(owner: String, scope: MemoryScope, sessionId: String): Boolean =
        scope == MemoryScope.ALL_SESSIONS || owner.isBlank() ||
            (sessionId.isNotBlank() && owner == sessionId)

    /** The facts form of the same query, for the ranker and the Facts screen. */
    fun visibleFactsIn(
        d: File,
        scope: MemoryScope = MemoryScope.ALL_SESSIONS,
        sessionId: String = "",
        projectId: String? = null,
        includeSuperseded: Boolean = false,
    ): List<Fact> {
        val all = FactsStore.loadFrom(d)
        if (all.isEmpty()) return emptyList()
        return all.filter { f ->
            sessionAllows(f.sessionOwner, scope, sessionId) &&
                (includeSuperseded || f.status != FactStatus.SUPERSEDED) &&
                f.belongsTo(projectId)
        }
    }
}
