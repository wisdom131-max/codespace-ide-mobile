package com.codespace.ide.chat.panel

import com.codespace.ide.chat.ui.ChatRowModel
import com.codespace.ide.engine.ChatError

/**
 * BATCH 5 — LIVE STATUS ROWS (owner mission part 5).
 *
 * The panel already had purpose-built status cards (NoCredits, Offline, OverBudget and
 * Thinking) and a renderer that drew them, but the engine's own
 * taxonomy was being flattened on the way in: ChatSession turned EVERY failure into
 * `Failed(verdict, "", retryable)`, so the 429 card, the 402 card and the offline card
 * could never appear no matter what the provider actually said. A user out of credits saw
 * the same generic red card as a user with a bad key.
 *
 * This maps the engine's classification to the row the renderer was built for, and it is
 * pure so the mapping is asserted instead of discovered on a device.
 *
 * ON CLEARING, because "status" is a statement about NOW: a card that says the provider is
 * unreachable must not sit in the transcript after the next attempt succeeds. These rows
 * describe the request that just ended, so they are removed when a new send begins (see
 * [dropStaleStatus]) while the actual conversation - user text, answers, notes - is kept.
 * A failure that is part of history rather than state (e.g. a note) survives.
 */
internal object ChatStatusMapping {

    /**
     * One engine error -> one row.
     *
     * 402 and transport failures keep their own cards because they need their own action
     * (top up, reconnect).
     *
     * 429 carries VS Code's own sentence rather than the transport's verdict: the reference
     * humanises Retry-After and writes prose ("Sorry, your request was rate-limited. Please wait
     * 2 minutes before trying again."), and it does not retry by itself. RateLimitMessage holds
     * that copy and the reasoning, so a wording change has one home.
     */
    fun rowForError(err: ChatError, advice: String? = null): ChatRowModel = when (err) {
        is ChatError.OutOfCredits -> ChatRowModel.NoCredits
        is ChatError.Network -> ChatRowModel.Offline
        is ChatError.OverBudget -> ChatRowModel.OverBudget(err.detail)
        is ChatError.RateLimited -> ChatRowModel.Failed(
            RateLimitMessage.VERDICT, RateLimitMessage.detail(err.retryAfterSec), err.retryable,
        )
        // Item 36: the retry verdict rides INSIDE the failure card, as its detail. A second row
        // would break the one-error-one-card contract the UI and its tests rely on (and would be
        // the last row, so "is the last row a failure card" would answer no for an actual failure).
        else -> ChatRowModel.Failed(err.userVerdict, advice ?: "", err.retryable)
    }

    /**
     * True for rows that describe the LAST attempt rather than the conversation.
     *
     * Thinking is included because it is regenerated for every send anyway; keeping a
     * frozen "Thinking 4s" above a new message would claim the model is thinking when it
     * has not been asked anything yet.
     */
    fun isStaleOnNewSend(row: ChatRowModel): Boolean = when (row) {
        is ChatRowModel.Thinking, is ChatRowModel.Failed,
        ChatRowModel.NoCredits, ChatRowModel.Offline, is ChatRowModel.OverBudget -> true
        else -> false
    }

    /** Rows with the stale status cards removed, order otherwise untouched. */
    fun dropStaleStatus(rows: List<ChatRowModel>): List<ChatRowModel> {
        if (rows.none(::isStaleOnNewSend)) return rows
        return rows.filterNot(::isStaleOnNewSend)
    }
}
