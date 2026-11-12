package com.codespace.ide.agent.facts

/**
 * FACTS (2026-11-11) — the structured memory record.
 *
 * The old store was a flat `key -> String` map in `memory.json`. It could remember, but it could
 * not answer the three questions that decide whether a memory may be trusted: WHO is it about
 * (this user, or this project?), WHY does it exist (the user said it, the model guessed it, or a
 * file proved it?), and IS IT STILL TRUE (superseded, contradicted, or never checked?).
 *
 * One fact = one durable statement, not a document. [content] is the statement itself; every other
 * field exists because some code path reads it - a field nothing reads is a field that rots.
 */
data class Fact(
    /** Stable identity. Survives edits, so a citation or a conflict link never dangles. */
    val id: String,
    val content: String,
    val scope: FactScope,
    /** Owning project for [FactScope.PROJECT]; null for user facts. Stable projectId, not a path. */
    val projectId: String? = null,
    val category: FactCategory = FactCategory.GENERAL,
    val source: FactSource = FactSource.EXPLICIT_USER,
    val confidence: FactConfidence = FactConfidence.STATED,
    val status: FactStatus = FactStatus.ACTIVE,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** Last time retrieval put this fact in a prompt. Recency of USE, not of writing. */
    val lastUsedAt: Long = 0L,
    /** Conversation that produced it, when one is known. Never fabricated. */
    val sourceConversationId: String? = null,
    /** File or URL the fact can be rechecked against (project facts, verified facts). */
    val citation: String? = null,
    /** The key this fact was migrated from, so `get`/`delete_memory` keep their old contract. */
    val legacyKey: String? = null,
    /** M5 session ownership: blank = visible to every session, else that session only. */
    val sessionOwner: String = "",
    /** When [status] is SUPERSEDED, the fact that replaced this one. */
    val supersededBy: String? = null,
    /** Facts this one visibly disagrees with, both sides recorded. */
    val conflicts: List<String> = emptyList(),
) {
    /** Project facts live in exactly one project; user facts in none. */
    fun belongsTo(projectId: String?): Boolean =
        scope == FactScope.USER || (projectId != null && this.projectId == projectId)

    fun isRetrievable(): Boolean = status != FactStatus.SUPERSEDED && content.isNotBlank()

    /** One line for a prompt: the statement, then what backs it. */
    fun provenanceLine(): String {
        val bits = ArrayList<String>(3)
        when (source) {
            FactSource.EXPLICIT_USER -> bits.add("you said")
            FactSource.MANUAL -> bits.add("added by user")
            FactSource.INFERRED -> bits.add("inferred")
            FactSource.VERIFIED_FILE -> bits.add("checked against a file")
            FactSource.LEGACY -> bits.add("from earlier memory")
        }
        when (status) {
            FactStatus.CONFLICT -> bits.add("CONFLICTED, unresolved")
            FactStatus.SUPERSEDED -> bits.add("superseded")
            FactStatus.ACTIVE -> Unit
        }
        if (confidence == FactConfidence.INFERRED && source != FactSource.INFERRED) bits.add("unverified")
        citation?.takeIf { it.isNotBlank() }?.let { bits.add(it) }
        return if (bits.isEmpty()) "" else bits.joinToString(", ")
    }
}

enum class FactScope(val id: String, val label: String) {
    /** Follows the user into every project. */
    USER("user", "User"),
    /** Belongs to one project and must never appear in another. */
    PROJECT("project", "Project"),
    ;

    companion object {
        fun parse(raw: String?): FactScope? = entries.firstOrNull { it.id == raw?.trim()?.lowercase() }
    }
}

/**
 * Category is a coarse filter for the Facts screen and a small relevance signal - nothing more.
 * It is deliberately a closed list: free-form tags would need a tag editor to be usable.
 */
enum class FactCategory(val id: String, val label: String) {
    PREFERENCE("preference", "Preference"),
    WORKFLOW("workflow", "Workflow"),
    CONVENTION("convention", "Convention"),
    ARCHITECTURE("architecture", "Architecture"),
    BUILD("build", "Build"),
    TEST("test", "Test"),
    CONSTRAINT("constraint", "Constraint"),
    GENERAL("general", "General"),
    ;

    companion object {
        fun parse(raw: String?): FactCategory =
            entries.firstOrNull { it.id == raw?.trim()?.lowercase() } ?: GENERAL

        /** Best guess from the statement itself, used only when the caller gave no category. */
        fun infer(content: String): FactCategory {
            val t = content.lowercase()
            fun has(vararg words: String) = words.any { t.contains(it) }
            return when {
                has("prefer", "always use", "never use", "i like", "style", "tone", "concise", "format") -> PREFERENCE
                has("workflow", "run ", "then ", "pipeline", "before you", "after you") -> WORKFLOW
                has("convention", "naming", "indent", "lint", "style guide", "camelcase", "snake_case") -> CONVENTION
                has("architecture", "module", "layer", "pattern", "mvvm", "repository", "package") -> ARCHITECTURE
                has("gradle", "build", "compile", "assemble", "apk", "cmake", "npm run build") -> BUILD
                has("test", "spec", "junit", "pytest", "coverage", "regression") -> TEST
                has("must not", "cannot", "limit", "constraint", "quota", "64kb", "cap") -> CONSTRAINT
                else -> GENERAL
            }
        }
    }
}

/** Why this fact exists. Provenance is a fact about the fact, so it is never inferred later. */
enum class FactSource(val id: String) {
    EXPLICIT_USER("explicit_user"),
    MANUAL("manual"),
    INFERRED("inferred"),
    VERIFIED_FILE("verified_file"),
    LEGACY("legacy"),
    ;

    companion object {
        fun parse(raw: String?): FactSource =
            entries.firstOrNull { it.id == raw?.trim()?.lowercase() } ?: EXPLICIT_USER
    }
}

enum class FactConfidence(val id: String) {
    VERIFIED("verified"),
    STATED("stated"),
    INFERRED("inferred"),
    ;

    companion object {
        fun parse(raw: String?): FactConfidence =
            entries.firstOrNull { it.id == raw?.trim()?.lowercase() } ?: STATED
    }
}

enum class FactStatus(val id: String) {
    ACTIVE("active"),
    SUPERSEDED("superseded"),
    CONFLICT("conflict"),
    ;

    companion object {
        fun parse(raw: String?): FactStatus =
            entries.firstOrNull { it.id == raw?.trim()?.lowercase() } ?: ACTIVE
    }
}
