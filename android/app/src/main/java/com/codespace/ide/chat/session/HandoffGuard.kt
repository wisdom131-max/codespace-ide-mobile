package com.codespace.ide.chat.session

/**
 * PROJECT SIX 6.4 — the plus button must not spawn the same successor twice.
 *
 * Two taps (or a tap that lands while the previous handoff is still being written) used to create two
 * successor sessions from the same parent, each carrying the same summary: the owner then has two
 * "Session 2 from git" chats and no way to tell which one their later questions went into.
 *
 * The rule is deliberately narrow. A successor is only reused when the parent matches AND that
 * successor is still EMPTY: once the owner has said anything in it, a new tap on the parent means they
 * want a fresh branch of the conversation, not to be thrown back into an old one.
 *
 * This is pure decision logic on purpose: the manager and the lineage file need Android storage, so the
 * part that can be wrong is the part that is testable on the JVM.
 */
object HandoffGuard {

    /** What the caller should do. */
    sealed class Decision {
        /** Send the owner to an existing successor instead of creating one. */
        data class Reuse(val sessionId: String, val label: String) : Decision()

        /** Create a new successor, as asked. */
        object Create : Decision()
    }

    /**
     * @param parentId the session the owner is branching from.
     * @param existingChildren successors already recorded for that parent, newest first.
     * @param rowsInChild how many transcript rows the candidate successor already has.
     * @param labelFor the lineage's own labelling, so the reused session keeps its displayed name.
     */
    fun decide(
        parentId: String,
        existingChildren: List<Pair<String, String>>,
        rowsInChild: (String) -> Int,
        labelFor: (String) -> String,
    ): Decision {
        if (parentId.isBlank()) return Decision.Create
        val candidate = existingChildren.firstOrNull { (id, _) -> id.isNotBlank() && id != parentId }
            ?: return Decision.Create
        val rows = rowsInChild(candidate.first)
        // A successor that already holds a turn is a conversation in its own right: branching again
        // from the parent is a legitimate new request, not a duplicate.
        if (rows > 0) return Decision.Create
        return Decision.Reuse(candidate.first, labelFor(candidate.first).ifBlank { candidate.second })
    }
}
