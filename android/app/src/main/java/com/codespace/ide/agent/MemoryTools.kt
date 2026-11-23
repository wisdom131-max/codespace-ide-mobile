package com.codespace.ide.agent

import android.content.Context
import com.codespace.ide.agent.facts.FactCategory
import com.codespace.ide.agent.facts.FactScope
import com.codespace.ide.agent.facts.FactSource
import com.codespace.ide.agent.facts.FactProvenance
import com.codespace.ide.agent.facts.FactSourceContext
import com.codespace.ide.agent.facts.FactsStore
import com.codespace.ide.agent.facts.FactVectors
import org.json.JSONObject
import java.io.File

/**
 * MEMORY TOOLS (2026-11-11) — the save path, with scopes.
 *
 * `save_memory` keeps its old shape (`key`, `value`) and gains three optional arguments, because a
 * second tool name for the same action would be exactly the redundant alias the owner has ruled
 * out:
 *
 *   scope    "user" (default) or "project"
 *   category preference | workflow | convention | architecture | build | test | constraint | general
 *   citation the file or URL the fact can be rechecked against
 *
 * The approval gate is untouched: `save_memory` is not in `ChatPermissionStore.SAFE_TOOLS`, so a
 * manual or safe flow still asks the human before anything is written. This object decides WHAT to
 * write, never WHETHER the call was allowed.
 */
object MemoryTools {

    fun save(
        context: Context,
        args: JSONObject,
        memoryScope: MemoryScope,
        sessionId: String,
        projectId: String?,
        provenance: FactProvenance = FactSourceContext.current(),
    ): String {
        // OWNER REQUEST (2026-11-23). LangMem's one genuinely good idea: the model names the fact it
        // is replacing, so a contradicting fact UPDATES instead of sitting beside the old one. Without
        // an id, a near-duplicate is refused and the existing fact is handed back so the model can
        // name it - a refusal with the way out, never a dead end.
        val dir = FactsStore.memoryDir(context)
        val value = args.optString("value").trim()
        val wantedId = args.optString("id").trim()
        val facts = FactsStore.loadFrom(dir)

        if (wantedId.isNotEmpty()) {
            val target = facts.firstOrNull { it.id == wantedId }
                ?: return "There is no fact with id " + wantedId +
                    ". Save without an id to create a new one."
            if (value.isEmpty()) return "Give the new wording in 'value'."
            val vec = com.codespace.ide.agent.embedding.TrainedEmbeddings.embed(value)
            FactsStore.writeTo(
                dir,
                facts.map {
                    if (it.id == wantedId) it.copy(content = value, updatedAt = System.currentTimeMillis()) else it
                },
            )
            if (vec != null) FactVectors.put(context, wantedId, vec)
            return "Updated the existing fact (" + wantedId + ") to: " + value
        }

        val newVec = if (value.isEmpty()) null
        else com.codespace.ide.agent.embedding.TrainedEmbeddings.embed(value)
        if (newVec != null) {
            val stored = runCatching { FactVectors.load(context) }.getOrDefault(emptyMap())
            val clash = facts.firstOrNull { f ->
                f.isRetrievable() && stored[f.id]?.let { v ->
                    v.size == newVec.size && FactVectors.cosine(newVec, v) > DUPLICATE_SIMILARITY
                } ?: false
            }
            if (clash != null) {
                return "That says the same thing as an existing fact (id " + clash.id + "): \"" +
                    clash.content + "\". Call save_memory again with that id to update it instead of " +
                    "adding a second version."
            }
        }

        val result = saveIn(dir, args, memoryScope, sessionId, projectId, provenance)
        // Straight away, so the new fact is findable by meaning on the very next turn.
        if (newVec != null) {
            val after = FactsStore.loadFrom(dir)
            val newest = after.filter { f -> facts.none { it.id == f.id } }.maxByOrNull { it.updatedAt }
            if (newest != null) FactVectors.put(context, newest.id, newVec)
        }
        return result
    }

    /** Two facts this close in meaning are the same fact said twice. */
    private const val DUPLICATE_SIMILARITY = 0.92

    /** Directory form: the shipped implementation, testable without an Android Context. */
    fun saveIn(
        d: File,
        args: JSONObject,
        memoryScope: MemoryScope,
        sessionId: String,
        projectId: String?,
        provenance: FactProvenance = FactProvenance.NONE,
    ): String {
        val value = args.optString("value").trim()
        if (value.isEmpty()) {
            return "Give the fact to remember in 'value', e.g. " +
                "{\"key\":\"build_command\",\"value\":\"Build with ./gradlew :app:assembleDevDebug\"}."
        }
        val scope = FactScope.parse(args.optString("scope")) ?: FactScope.USER
        val category = if (args.has("category")) FactCategory.parse(args.optString("category")) else null
        val citation = args.optString("citation", "").trim().ifBlank { null }
        val owner = MemoryScopeFilter.attributionFor(memoryScope, sessionId)

        if (scope == FactScope.PROJECT) {
            if (projectId.isNullOrBlank()) {
                return "There is no project open, so this cannot be a project fact. " +
                    "It can be saved as a user fact (scope=\"user\") instead."
            }
            return when (val r = FactsStore.saveTo(
                d = d,
                content = value,
                scope = FactScope.PROJECT,
                projectId = projectId,
                category = category,
                source = FactSource.EXPLICIT_USER,
                citation = citation,
                sessionOwner = owner,
                provenance = provenance,
            )) {
                is FactsStore.SaveResult.Saved -> savedLine(r, scope, category)
                is FactsStore.SaveResult.Refused -> "Memory not saved: " + r.reason
                FactsStore.SaveResult.Full -> "Memory is full (" + FactsStore.MAX_FACTS + " facts)."
                FactsStore.SaveResult.WriteFailed -> "Memory not saved: the store write failed."
            }
        }

        // User-scope via the legacy key contract, so read_memory/delete_memory and any prompt that
        // mentions the key keep working exactly as before.
        val key = args.optString("key").trim().ifBlank {
            (category ?: FactCategory.infer(value)).id + "_" + value.lowercase()
                .replace(Regex("[^a-z0-9]+"), "_").trim('_').take(28)
        }
        return AgentMemory.saveIn(
            d = d,
            key = key,
            value = value,
            scope = memoryScope,
            sessionId = sessionId,
            category = category,
            citation = citation,
        )
    }

    /** The saved line says WHICH scope it landed in: that is the part a user must not guess. */
    private fun savedLine(r: FactsStore.SaveResult.Saved, scope: FactScope, category: FactCategory?): String {
        val where = if (scope == FactScope.PROJECT) "this project" else "your user memory"
        val cat = (category ?: r.fact.category).label.lowercase()
        return when (r.decision) {
            com.codespace.ide.agent.facts.FactDecision.NEW ->
                "Memory saved (" + where + ", " + cat + ")."
            com.codespace.ide.agent.facts.FactDecision.CORRECTION ->
                "Memory updated (" + where + ", " + cat + "): an existing fact said almost the same " +
                    "thing, so it was corrected rather than duplicated."
            com.codespace.ide.agent.facts.FactDecision.DUPLICATE ->
                "That fact was already saved (" + where + "); its provenance was refreshed instead " +
                    "of adding a duplicate."
            com.codespace.ide.agent.facts.FactDecision.CONFLICT ->
                "Saved, but it CONTRADICTS an existing fact. Both are kept and marked as an " +
                    "unresolved conflict; resolve it in Settings > Facts so it is not treated as settled."
        }
    }
}
