package com.codespace.ide.agent

import android.content.Context
import com.codespace.ide.agent.facts.FactCategory
import com.codespace.ide.agent.facts.FactScope
import com.codespace.ide.agent.facts.FactSource
import com.codespace.ide.agent.facts.FactsStore
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
    ): String = saveIn(FactsStore.memoryDir(context), args, memoryScope, sessionId, projectId)

    /** Directory form: the shipped implementation, testable without an Android Context. */
    fun saveIn(
        d: File,
        args: JSONObject,
        memoryScope: MemoryScope,
        sessionId: String,
        projectId: String?,
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
