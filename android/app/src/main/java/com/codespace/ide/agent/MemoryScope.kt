package com.codespace.ide.agent

/**
 * M5 — MEMORY SCOPE.
 *
 * The agent's saved memories used to be one flat, process-wide key/value store: anything the
 * agent learned in any conversation was readable from every conversation. This setting makes the
 * boundary explicit and enforced, not advisory:
 *
 *  - [CURRENT_SESSION]: only memories written by THIS conversation are visible. Read paths filter
 *    by the attribution recorded at save time, so another session's memory cannot be reached even
 *    if the model asks for it by key.
 *  - [ALL_SESSIONS]: every memory is visible, which is the pre-M5 behaviour and remains the
 *    default so nothing an owner already relies on disappears.
 *
 * Legacy memories (written before attribution existed) belong to no session. They are treated as
 * GLOBAL rather than dropped: an upgrade must not silently erase knowledge the owner had, and a
 * memory from before scoping cannot "belong" to a session that did not exist yet.
 */
enum class MemoryScope(val id: String, val label: String) {
    CURRENT_SESSION("current_session", "Current session only"),
    ALL_SESSIONS("all_sessions", "All sessions"),
    ;

    fun next(): MemoryScope = if (this == CURRENT_SESSION) ALL_SESSIONS else CURRENT_SESSION

    companion object {
        val DEFAULT: MemoryScope = ALL_SESSIONS

        fun parse(raw: String?): MemoryScope =
            entries.firstOrNull { it.id == raw } ?: DEFAULT
    }
}

/**
 * The visibility rule, pure and unit-tested.
 *
 * @param attribution key -> the session that wrote it (absent or blank = global/legacy).
 */
object MemoryScopeFilter {

    fun visibleKeys(
        allKeys: Collection<String>,
        attribution: Map<String, String>,
        scope: MemoryScope,
        sessionId: String,
    ): List<String> {
        if (scope == MemoryScope.ALL_SESSIONS) return allKeys.toList()
        return allKeys.filter { key ->
            val owner = attribution[key].orEmpty()
            // Blank = global/legacy, visible everywhere. Otherwise the owner must be this session.
            owner.isBlank() || (sessionId.isNotBlank() && owner == sessionId)
        }
    }

    fun isVisible(
        key: String,
        attribution: Map<String, String>,
        scope: MemoryScope,
        sessionId: String,
    ): Boolean {
        if (scope == MemoryScope.ALL_SESSIONS) return true
        val owner = attribution[key].orEmpty()
        return owner.isBlank() || (sessionId.isNotBlank() && owner == sessionId)
    }

    /** Attribution to record for a save under [scope]. Blank means global. */
    fun attributionFor(scope: MemoryScope, sessionId: String): String =
        if (scope == MemoryScope.CURRENT_SESSION) sessionId else ""
}
