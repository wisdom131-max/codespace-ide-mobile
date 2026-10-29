package com.codespace.ide.chat.panel

import com.codespace.ide.chat.ui.ChatRowModel
import com.codespace.ide.engine.ChatError

/**
 * BATCH 5 — LIVE STATUS ROWS (owner mission part 5).
 *
 * The panel already had five purpose-built status cards (RateLimited, NoCredits, Offline,
 * TrimmedHistory and Thinking) and a renderer that drew them, but the engine's own
 * taxonomy was being flattened on the way in: ChatSession turned EVERY failure into
 * `Failed(verdict, "", retryable)`, so the 429 card, the 402 card and the offline card
 * could never appear no matter what the provider actually said. A user out of credits saw
 * the same generic red card as a user with a bad key.
 *
 * This maps the engine's classification to the row the renderer was built for, and it is
 * pure so the mapping is asserted instead of discovered on a device.
 *
 * ON CLEARING, because "status" is a statement about NOW: a card that says the provider is
 * rate limiting must not sit in the transcript after the next attempt succeeds. These rows
 * describe the request that just ended, so they are removed when a new send begins (see
 * [dropStaleStatus]) while the actual conversation - user text, answers, notes - is kept.
 * A failure that is part of history rather than state (e.g. a note) survives.
 */
internal object ChatStatusMapping {

    /** Shown when the provider sent 429 without a usable Retry-After. */
    const val UNKNOWN_WAIT_SEC = 0

    /**
     * One engine error -> one row.
     *
     * 429 / 402 / transport failures get their OWN cards: they need different actions
     * (wait, top up, reconnect) and the generic card would misdescribe all three. Only a
     * genuinely generic failure lands in Failed.
     */
    fun rowForError(err: ChatError): ChatRowModel = when (err) {
        is ChatError.RateLimited ->
            ChatRowModel.RateLimited(err.retryAfterSec?.coerceAtLeast(0) ?: UNKNOWN_WAIT_SEC)
        is ChatError.OutOfCredits -> ChatRowModel.NoCredits
        is ChatError.Network -> ChatRowModel.Offline
        is ChatError.OverBudget -> ChatRowModel.OverBudget(err.detail)
        else -> ChatRowModel.Failed(err.userVerdict, "", err.retryable)
    }

    /**
     * True for rows that describe the LAST attempt rather than the conversation.
     *
     * Thinking is included because it is regenerated for every send anyway; keeping a
     * frozen "Thinking 4s" above a new message would claim the model is thinking when it
     * has not been asked anything yet.
     */
    fun isStaleOnNewSend(row: ChatRowModel): Boolean = when (row) {
        is ChatRowModel.Thinking, is ChatRowModel.RateLimited, is ChatRowModel.Failed,
        ChatRowModel.NoCredits, ChatRowModel.Offline, is ChatRowModel.OverBudget -> true
        // Conversation content and honest notes are history; TrimmedHistory describes the
        // window that was sent for the turn it belongs to and is likewise replaced per send.
        is ChatRowModel.TrimmedHistory -> true
        else -> false
    }

    /**
     * The wait is over: remove the trailing "rate limited, retrying in Ns" card.
     *
     * Walks back over transient rows (a Thinking row is appended while the retry's answer
     * has not started) and stops at anything that is conversation. Returns the same
     * instance when there is no resolved wait, so the delta path stays allocation-free on
     * the common frame.
     */
    fun clearResolvedWait(rows: List<ChatRowModel>): List<ChatRowModel> {
        var i = rows.lastIndex
        while (i >= 0) {
            when (rows[i]) {
                is ChatRowModel.Thinking -> i--
                is ChatRowModel.RateLimited -> return rows.filterIndexed { idx, _ -> idx != i }
                else -> return rows
            }
        }
        return rows
    }

    /** Rows with the stale status cards removed, order otherwise untouched. */
    fun dropStaleStatus(rows: List<ChatRowModel>): List<ChatRowModel> {
        if (rows.none(::isStaleOnNewSend)) return rows
        return rows.filterNot(::isStaleOnNewSend)
    }
}
