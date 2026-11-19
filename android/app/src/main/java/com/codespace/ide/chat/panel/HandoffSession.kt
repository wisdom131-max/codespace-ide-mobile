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
    // P0.6 (2026-11-19): never mint a successor that only LOOKS informed. An empty or trivial
    // summary is a failed handoff and is reported, not sent as if it were context.
    val summaryPayload = handoff.render()
    if (summaryPayload.isBlank() || summaryPayload.trim().length < 40) {
        manager.session(projectId, created.meta.id)?.appendNotice(
            "Continuation started WITHOUT a usable summary: the parent session had nothing worth " +
                "carrying. Ask again here, or let the parent finish a turn first.",
        )
        return "Continued, but the summary was empty - this session starts without the parent context."
    }
    manager.session(projectId, created.meta.id)?.send(summaryPayload)
    // PROJECT SEVEN 7.7: tell the owner WHAT was carried, counted from the summary the successor
    // actually received, instead of a bare "continued". Retention-on-disk is checked, not assumed.
    val carried = CopilotPresentation.carriedContext(
        label = com.codespace.ide.chat.session.SessionLineage.label(successor),
        kinds = CopilotPresentation.kindsFromRenderedSummary(handoff.generatedSummary),
        exchangeCount = 0,
        retainedOnDisk = handoffRetentionFileExists(context, activeId),
    )
    return CopilotPresentation.carriedContextLine(carried)
}

/**
 * True when this session's retained summary is already on disk. A false answer is not an error: a
 * session that never had to compress its window has nothing stored yet, and the notice says so rather
 * than promising a summary that is not there.
 */
private fun handoffRetentionFileExists(context: Context, sessionId: String): Boolean {
    val dir = java.io.File(context.filesDir, "chat-retention")
    if (!dir.isDirectory) return false
    val safe = sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)
    val tag = Integer.toHexString(sessionId.hashCode())
    return java.io.File(dir, safe + "-" + tag + ".summary").exists()
}
