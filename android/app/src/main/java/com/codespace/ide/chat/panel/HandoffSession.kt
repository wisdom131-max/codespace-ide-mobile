package com.codespace.ide.chat.panel

import android.content.Context
import com.codespace.ide.chat.ui.ChatRowModel

/**
 * PHASE 7 (owner requirement 2026-11-14): the plus button and "Continue in New Session" are the
 * SAME operation, so they share one implementation.
 *
 * The plus chip used to call createSession on its own, which opened an empty conversation and
 * threw away everything the previous one knew. The handoff - what was decided, what is
 * constrained, what is still open - lived only behind the overflow menu, which is not where a
 * user reaches for a new chat.
 *
 * Blank-session creation still exists: it is the Sessions surface, not the plus chip.
 *
 * @return the one-line notice to show, or null when the new session could not be created.
 */
internal fun startHandoffSession(
    manager: ChatSessionManager,
    context: Context,
    projectId: String,
    activeId: String,
): String? {
    val active = manager.session(projectId, activeId)
    val source = active?.ui?.value?.rows.orEmpty()
    val handoff = com.codespace.ide.chat.session.SessionHandoff.build(
        source.mapNotNull { row ->
            when (row) {
                is ChatRowModel.User -> com.codespace.ide.chat.session.SessionHandoff.Turn("user", row.text)
                is ChatRowModel.AssistantStream -> com.codespace.ide.chat.session.SessionHandoff.Turn("assistant", row.text)
                else -> null
            }
        },
        // PROJECT SIX 6.2B: what the old session had to leave out of its window travels with the
        // handoff, labelled as generated.
        generatedSummary = active?.generatedSummary.orEmpty(),
    )
    val lineage = com.codespace.ide.chat.session.SessionLineage.forProject(context, projectId)
    val parentTitle = com.codespace.ide.chat.session.SessionTitles
        .derive(source.filterIsInstance<ChatRowModel.User>().firstOrNull()?.text)
    val parentLink = lineage.record(activeId, null, parentTitle)
    // PROJECT SIX 6.4: a second tap must not mint a second successor carrying the same summary. An
    // existing EMPTY successor is reused; one that already holds a turn is a conversation of its own,
    // so branching again is legitimate and a new session is created.
    val reuse = com.codespace.ide.chat.session.HandoffGuard.decide(
        parentId = activeId,
        existingChildren = lineage.childrenOf(activeId).map { it to activeId },
        rowsInChild = { child -> manager.session(projectId, child)?.ui?.value?.rows?.size ?: 0 },
        labelFor = { child ->
            com.codespace.ide.chat.session.SessionLineage.label(
                lineage.linkOf(child) ?: com.codespace.ide.chat.session.SessionLineage.Link(child, activeId, 2, "session"),
            )
        },
    )
    if (reuse is com.codespace.ide.chat.session.HandoffGuard.Decision.Reuse) {
        return "Continuing in " + reuse.label + " - it already carries this conversation's context."
    }
    val created = manager.createSession(projectId)
    if (created !is com.codespace.ide.chat.session.SessionCreate.Created) return null
    val successor = lineage.record(created.meta.id, activeId, parentLink.origin)
    manager.renameSession(
        projectId, created.meta.id,
        com.codespace.ide.chat.session.SessionLineage.label(successor),
    )
    manager.session(projectId, created.meta.id)?.send(handoff.render())
    return com.codespace.ide.chat.session.SessionLineage.label(successor) +
        " - " + com.codespace.ide.chat.session.SessionHandoff.describe(handoff)
}
