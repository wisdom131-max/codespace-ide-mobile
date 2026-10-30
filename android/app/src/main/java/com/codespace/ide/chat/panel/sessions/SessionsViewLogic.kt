package com.codespace.ide.chat.panel.sessions

import com.codespace.ide.chat.session.SessionFilter
import com.codespace.ide.chat.session.SessionListLogic
import com.codespace.ide.chat.session.SessionMeta
import com.codespace.ide.chat.session.SessionQuery
import com.codespace.ide.chat.session.SessionSignals
import com.codespace.ide.chat.session.SessionSort
import java.time.Instant
import java.time.ZoneId

/**
 * M2 — WHAT THE SESSIONS LIST SHOWS, decided without composition.
 *
 * Everything here is pure: the same inputs produce the same rows, nothing is written, and the
 * index handed in is never mutated. That is what makes "sorting and filtering must not write
 * conversation data" a structural guarantee rather than a promise: this file has no store, no
 * file and no coroutine in scope, and the tests assert the index is identical afterwards.
 */
internal data class SessionRowUi(
    val id: String,
    val title: String,
    /** Relative for recent sessions, a date beyond a week. */
    val timeLabel: String,
    /** Derived status, or null when nothing can be proved about this session. */
    val statusLabel: String?,
    /** Updated after the user last looked at it. */
    val unread: Boolean,
    val archived: Boolean,
    val isActive: Boolean,
)

internal enum class SessionRowAction(val label: String) {
    RENAME("Rename"),
    ARCHIVE("Archive"),
    UNARCHIVE("Unarchive"),
    DELETE("Delete"),
}

internal object SessionsViewLogic {

    /**
     * The rows to draw, in the query's order.
     *
     * [signalsFor] is consulted per session because status is DERIVED, never stored: a session
     * is only "In Progress" while its stream is actually running in this process, and only
     * "Attention Needed" when something is really waiting for the user.
     */
    fun rows(
        sessions: List<SessionMeta>,
        query: SessionQuery,
        signalsFor: (SessionMeta) -> SessionSignals = { SessionSignals() },
        nowMs: Long,
        activeId: String?,
    ): List<SessionRowUi> {
        val selected = SessionListLogic.select(sessions, query, signalsFor)
        return selected.map { meta ->
            val signals = signalsFor(meta)
            SessionRowUi(
                id = meta.id,
                title = meta.title.ifBlank { SessionsPanelSpec.UNTITLED_FALLBACK },
                timeLabel = relativeTime(nowMs, if (query.sort == SessionSort.CREATED) meta.createdAtMs else meta.updatedAtMs),
                statusLabel = statusFor(signals),
                unread = !meta.isRead,
                archived = meta.archived,
                isActive = meta.id == activeId,
            )
        }
    }

    /**
     * Derived status. Order is a priority, not a preference: something waiting for the user
     * outranks something merely running, which outranks something already finished.
     */
    fun statusFor(signals: SessionSignals): String? = when {
        signals.needsAttention -> SessionFilter.ATTENTION_NEEDED.label
        signals.streamingNow -> SessionFilter.IN_PROGRESS.label
        signals.hasMessages && signals.lastReplyComplete -> SessionFilter.COMPLETED.label
        else -> null
    }

    /**
     * "just now", "5m ago", "3h ago", "2d ago", then a plain date.
     *
     * A clock that went backwards (a device time change, a restored file) reports "just now"
     * rather than a negative interval.
     */
    fun relativeTime(nowMs: Long, thenMs: Long): String {
        val delta = nowMs - thenMs
        if (delta <= 0L) return "just now"
        val minutes = delta / 60_000L
        if (minutes < 1L) return "just now"
        if (minutes < 60L) return minutes.toString() + "m ago"
        val hours = minutes / 60L
        if (hours < 24L) return hours.toString() + "h ago"
        val days = hours / 24L
        if (days < 7L) return days.toString() + "d ago"
        return Instant.ofEpochMilli(thenMs).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    }

    /** All eight filters of the reference, in its order. Unusable ones are marked, not hidden. */
    fun filters(): List<SessionFilter> = SessionFilter.entries

    /** A filter whose data can never exist is shown but cannot be selected. */
    fun isUsable(filter: SessionFilter): Boolean = filter.dataSourceToday

    fun activeFilterCount(query: SessionQuery): Int = query.filters.count { it != SessionFilter.ARCHIVED }

    fun toggleFilter(query: SessionQuery, filter: SessionFilter): SessionQuery {
        if (!isUsable(filter)) return query
        val next = if (filter in query.filters) query.filters - filter else query.filters + filter
        return query.copy(filters = next, includeArchived = SessionFilter.ARCHIVED in next)
    }

    fun withSort(query: SessionQuery, sort: SessionSort): SessionQuery = query.copy(sort = sort)

    fun withSearch(query: SessionQuery, text: String): SessionQuery =
        query.copy(search = text.ifBlank { null })

    /** Only the actions that make sense for this row. */
    fun rowActions(archived: Boolean): List<SessionRowAction> = listOf(
        SessionRowAction.RENAME,
        if (archived) SessionRowAction.UNARCHIVE else SessionRowAction.ARCHIVE,
        SessionRowAction.DELETE,
    )

    /**
     * What to show when nothing is listed. Distinguishes "this project has no sessions" from
     * "the current view hides all of them", because the fix is different: create one, or clear
     * the filters.
     */
    fun emptyMessage(total: Int, query: SessionQuery): String =
        if (total == 0) SessionsPanelSpec.EMPTY_TITLE + ". " + SessionsPanelSpec.EMPTY_BODY
        else SessionsPanelSpec.NO_MATCH

    /** One line describing the active view, so a filtered list is never silently filtered. */
    fun viewSummary(query: SessionQuery): String? {
        val count = activeFilterCount(query)
        val parts = ArrayList<String>(3)
        if (count > 0) parts.add(count.toString() + " filter" + (if (count == 1) "" else "s"))
        if (SessionFilter.ARCHIVED in query.filters) parts.add(SessionFilter.ARCHIVED.label)
        if (query.sort != SessionSort.UPDATED) parts.add(SessionsPanelSpec.SORT_TITLE + ": " + query.sort.label)
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }
}
