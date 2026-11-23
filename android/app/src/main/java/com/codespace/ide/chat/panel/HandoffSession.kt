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
    // A successor that inherits nothing inherits the DEFAULT mode, which is Ask - so it could read
    // the handoff and then be unable to act on it. It now starts in the mode the parent was in.
    runCatching {
        active?.mode?.let { m ->
            manager.setSessionMode(projectId, created.meta.id, ChatModePersistence.toStored(m))
        }
    }
    val successor = lineage.record(created.meta.id, activeId, parentLink.origin)
    manager.renameSession(
        projectId, created.meta.id,
        com.codespace.ide.chat.session.SessionLineage.label(successor),
    )
    // P0.6 (2026-11-19): never mint a successor that only LOOKS informed. An empty or trivial
    // summary is a failed handoff and is reported, not sent as if it were context.
    // PROJECT EIGHT 8.8 (catalog 8): the guard below was measuring the wrong thing.
    //
    // It checked the LENGTH of the rendered text, but render() always emits its header and its
    // "this is a handoff, not the transcript" line, so a handoff with no content whatsoever still
    // produced a payload comfortably over 40 characters. It passed the check and was sent: the new
    // session received a heading with nothing underneath, which is precisely the "remembers nothing"
    // report. Length was never evidence of substance.
    //
    // Substance is now checked field by field, and when there is none the most recent turns are
    // carried verbatim instead of an empty frame. Verbatim is deliberately second best and says so
    // in its own header: a labelled excerpt is honest, a summary-shaped void is not.
    val substantive = handoff.generatedSummary.isNotBlank() ||
        handoff.decisions.isNotEmpty() || handoff.constraints.isNotEmpty() ||
        handoff.progress.isNotEmpty() || handoff.open.isNotEmpty() ||
        handoff.files.isNotEmpty() || handoff.evidence.isNotEmpty() ||
        handoff.limitations.isNotEmpty()
    // OWNER REPORT (2026-11-23): "the continue in another session doesn't work". A substantive
    // digest rendered WITHOUT the conversation, so a summary that mentioned constraints but not the
    // actual exchange read as remembering nothing. The recent turns now always travel with it.
    val payload = if (substantive) handoff.render() + recentTurnsBlock(source) else fallbackHandoff(source)
    if (payload.isBlank()) {
        manager.session(projectId, created.meta.id)?.appendNotice(
            "Continuation started WITHOUT a usable summary: the parent session had nothing worth " +
                "carrying. Ask again here, or let the parent finish a turn first.",
        )
        return "Continued, but the summary was empty - this session starts without the parent context."
    }
    manager.session(projectId, created.meta.id)?.send(payload)
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

/**
 * PROJECT EIGHT 8.8 (catalog 8): what a continuation carries when nothing pattern-matched.
 *
 * The handoff's sections only fill in when the conversation happens to contain the wording they
 * look for, and the generated summary only exists once the engine has had to compress an over-long
 * history. An ordinary working session has neither, which left the successor with a header and no
 * content. The recent turns always exist, so they are the reliable floor.
 *
 * Bounded on both axes: the last few turns, each cut short. This is a fallback, not a transcript,
 * and it says so in its own words so the model does not mistake an excerpt for the full record.
 */
private const val MAX_FALLBACK_TURNS = 12
private const val MAX_FALLBACK_CHARS = 600

private fun fallbackHandoff(rows: List<ChatRowModel>): String {
    val kept = rows.mapNotNull { row ->
        when (row) {
            is ChatRowModel.User -> "user: " + row.text.trim()
            is ChatRowModel.AssistantStream -> "assistant: " + row.text.trim()
            else -> null
        }
    }.filter { it.length > 6 }.takeLast(MAX_FALLBACK_TURNS)
    if (kept.isEmpty()) return ""
    return buildString {
        append("Continuing from an earlier conversation. Nothing in it matched the usual patterns ")
        append("for decisions or open questions, so these are the most recent turns VERBATIM rather ")
        append("than a summary. They are context to build on, not work to redo.\n\n")
        kept.forEach { append("- ").append(it.take(MAX_FALLBACK_CHARS)).append("\n") }
    }
}

/**
 * The last few turns, verbatim and clearly labelled as an excerpt. Deliberately second best: a
 * labelled excerpt is honest, and a summary that omits the exchange itself is what the owner
 * experienced as "remembers nothing".
 */
private fun recentTurnsBlock(rows: List<ChatRowModel>, maxTurns: Int = 10, perTurn: Int = 700): String {
    val turns = rows.mapNotNull { row ->
        when (row) {
            is ChatRowModel.User -> "User: " + row.text
            is ChatRowModel.AssistantStream -> "Assistant: " + row.text
            else -> null
        }
    }.takeLast(maxTurns)
    if (turns.isEmpty()) return ""
    return buildString {
        append("\n\n## Recent turns, carried verbatim\n")
        append("These are the last exchanges, not a summary. Continue from them.\n\n")
        turns.forEach { turn ->
            append(if (turn.length > perTurn) turn.take(perTurn) + " [...]" else turn)
            append("\n\n")
        }
    }
}
