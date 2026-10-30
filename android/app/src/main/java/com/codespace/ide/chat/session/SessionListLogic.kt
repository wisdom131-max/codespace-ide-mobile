package com.codespace.ide.chat.session

/**
 * M0 — SORTING AND FILTERING, AS PURE DATA.
 *
 * These are VIEW operations. They return a new list and never touch a [SessionMeta], the index
 * file or a transcript, because "sort by Created" must not be able to change what "Updated"
 * says later. The test that proves it hashes the stored bytes before and after a sort.
 *
 * The labels are the OWNER'S LOCKED UI REFERENCE (2026-10-30) and are reproduced verbatim:
 * sorting is Created/Updated; filtering is Local, External, Completed, In Progress, Attention
 * Needed, Failed, Read, Archived. They are not renamed, reordered or substituted here, and M2
 * must render [SessionSort.label] and [SessionFilter.label] rather than its own strings.
 *
 * Every filter states whether it has a real data source TODAY. Two of them do not: see
 * [hasDataSoureToday] and the M2 note on each entry. A filter with no source is kept in the
 * model (so the vocabulary is fixed and reviewed) but is NOT allowed to be shown as a working
 * control yet — it would be a control that always returns nothing.
 */
internal enum class SessionSort(val label: String) {
    CREATED("Created"),
    UPDATED("Updated"),
}

/**
 * @param label the exact string the UI must show
 * @param dataSourceToday false = nothing in VN Code can currently produce this state, so M2
 *   must not present it as a working filter (see the M2 note in the design report)
 */
internal enum class SessionFilter(val label: String, val dataSourceToday: Boolean) {
    /** Every session VN Code can create is local. TRUE today (a constant). */
    LOCAL("Local", true),

    /**
     * External sessions would be agents running outside this device (a cloud or background
     * agent). VN Code has no such producer, so this can only ever be empty.
     */
    EXTERNAL("External", false),

    /** Derived: the last stored message is a complete assistant reply. TRUE. */
    COMPLETED("Completed", true),

    /**
     * Derived for the LIVE session only (its stream is running). A stored session cannot be
     * in progress across a restart, because nothing streams while the app is closed.
     */
    IN_PROGRESS("In Progress", true),

    /**
     * Derived: the session holds staged proposals or an approval awaiting the user. TRUE for
     * the live session; a stored one cannot know without loading its staged records.
     */
    ATTENTION_NEEDED("Attention Needed", true),

    /**
     * Failed sends are deliberately NOT persisted (ChatTranscript judges them a moment, not
     * history), so no stored session can be known to have failed.
     */
    FAILED("Failed", false),

    /** Derived from timestamps: lastReadAtMs >= updatedAtMs. TRUE. */
    READ("Read", true),

    /** Stored boolean. TRUE. */
    ARCHIVED("Archived", true),
    ;

    companion object {
        /** Filters that must not be rendered as working controls until their data exists. */
        val withoutDataSource: List<SessionFilter> = entries.filterNot { it.dataSourceToday }

        fun byLabel(label: String): SessionFilter? = entries.firstOrNull { it.label == label }
    }
}

/** What the caller can PROVE about a session right now. Never stored, always derived. */
internal data class SessionSignals(
    val source: SessionSource = SessionSource.LOCAL,
    val hasMessages: Boolean = false,
    /** Derived from the last stored message, when the caller has loaded it. */
    val lastReplyComplete: Boolean = false,
    /** The live session's stream is running. */
    val streamingNow: Boolean = false,
    /** Staged proposals or an approval waiting for the user, for the LIVE session. */
    val needsAttention: Boolean = false,
)

internal enum class SessionSource { LOCAL, EXTERNAL }

/**
 * A view request: what to show, in what order. [search] matches titles only in M0; content
 * search needs the Phase 2 index and is deliberately not faked here.
 */
internal data class SessionQuery(
    val sort: SessionSort = SessionSort.UPDATED,
    val filters: Set<SessionFilter> = emptySet(),
    /** Archived sessions are hidden unless the user asks for them. */
    val includeArchived: Boolean = false,
    val search: String? = null,
)

internal object SessionListLogic {

    /**
     * The visible list. Pure: the input list is not mutated, and nothing is written anywhere.
     *
     * Filter combination is OR within the chosen filters (a session appears if it satisfies
     * ANY selected filter), because the reference UI presents them as independent chips, not
     * as an AND-narrowing query. Archived is a visibility rule on top of that: it is hidden
     * unless [SessionQuery.includeArchived] is set or the Archived filter itself is selected.
     */
    fun select(
        sessions: List<SessionMeta>,
        query: SessionQuery,
        signalsFor: (SessionMeta) -> SessionSignals = { SessionSignals() },
    ): List<SessionMeta> {
        val needle = query.search?.trim()?.lowercase().orEmpty()
        val archivedRequested = query.includeArchived || SessionFilter.ARCHIVED in query.filters

        val filtered = sessions.filter { meta ->
            if (meta.archived && !archivedRequested) return@filter false
            if (needle.isNotEmpty() && !matchesSearch(meta, needle)) return@filter false
            if (query.filters.isEmpty()) return@filter true
            query.filters.any { matches(it, meta, signalsFor(meta)) }
        }
        return sorted(filtered, query.sort)
    }

    /** Title and id only. Content search arrives with the Phase 2 retrieval index. */
    fun matchesSearch(meta: SessionMeta, lowerNeedle: String): Boolean =
        meta.title.lowercase().contains(lowerNeedle) || meta.id.lowercase().contains(lowerNeedle)

    fun matches(filter: SessionFilter, meta: SessionMeta, signals: SessionSignals): Boolean =
        when (filter) {
            SessionFilter.LOCAL -> signals.source == SessionSource.LOCAL
            SessionFilter.EXTERNAL -> signals.source == SessionSource.EXTERNAL
            SessionFilter.COMPLETED -> signals.hasMessages && signals.lastReplyComplete && !signals.streamingNow
            SessionFilter.IN_PROGRESS -> signals.streamingNow
            SessionFilter.ATTENTION_NEEDED -> signals.needsAttention
            // No producer exists yet; kept explicit rather than silently never matching by
            // accident elsewhere (see SessionFilter.FAILED docs).
            SessionFilter.FAILED -> false
            SessionFilter.READ -> meta.isRead
            SessionFilter.ARCHIVED -> meta.archived
        }

    /**
     * Deterministic ordering with an id tiebreak, so two sessions updated in the same
     * millisecond never swap places between recompositions.
     */
    fun sorted(sessions: List<SessionMeta>, sort: SessionSort): List<SessionMeta> =
        when (sort) {
            SessionSort.UPDATED -> sessions.sortedWith(
                compareByDescending<SessionMeta> { it.updatedAtMs }
                    .thenByDescending { it.createdAtMs }
                    .thenBy { it.id },
            )
            SessionSort.CREATED -> sessions.sortedWith(
                compareByDescending<SessionMeta> { it.createdAtMs }
                    .thenByDescending { it.updatedAtMs }
                    .thenBy { it.id },
            )
        }
}
