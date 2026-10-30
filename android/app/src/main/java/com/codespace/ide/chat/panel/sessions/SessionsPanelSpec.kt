package com.codespace.ide.chat.panel.sessions

/**
 * M2 — THE SESSIONS PANEL'S CONTROL INVENTORY.
 *
 * Pure data, exactly like [com.codespace.ide.chat.panel.ChatToolbarSpec]: the header decides
 * what fits at a given width, so the decision is testable without composition. The strings are
 * the reference's strings and are not chosen here; they are asserted in the tests so a rename
 * cannot slip through unnoticed.
 *
 * The reference shows the Sessions surface as its own column beside the chat. This panel is a
 * sidebar whose width the owner drags between 0 and 600 dp, and the chat column itself must
 * keep at least the composer's 280 dp minimum (see ChatToolbarSpec's tested widths). Two
 * columns inside a 360 dp sidebar would give each about 180 dp, so the surface SWAPS the
 * pane's content instead of splitting it. Same controls, same order, one column. Recorded as
 * an Android width limitation, not a design choice.
 */
internal enum class SessionsHeaderAction(val label: String) {
    REFRESH("Refresh sessions"),
    SEARCH("Search sessions"),
    FILTER("Filter and sort sessions"),
}

/**
 * @param inline actions shown as buttons in the header, left to right in declaration order
 * @param overflow actions that do not fit and move behind the header's "..." button
 */
internal data class SessionsHeaderSpec(
    val inline: List<SessionsHeaderAction>,
    val overflow: List<SessionsHeaderAction>,
) {
    val needsOverflowButton: Boolean get() = overflow.isNotEmpty()
}

internal object SessionsPanelSpec {

    // ---- reference strings. Any change here is a change to the requested UI.
    const val TITLE = "Sessions"
    const val NEW_SESSION = "New Session"
    const val SEARCH_HINT = "Type to search"
    const val BACK = "Back to chat"
    const val EMPTY_TITLE = "No sessions yet"
    const val EMPTY_BODY = "Start a conversation and it appears here."
    const val NO_MATCH = "No sessions match this view."
    const val SORT_TITLE = "Sort by"
    const val FILTER_TITLE = "Filter"
    const val UNAVAILABLE_NOTE = "Nothing can produce this yet"
    const val DELETE_TITLE = "Delete session?"
    const val DELETE_CONFIRM = "Delete"
    const val DELETE_CANCEL = "Cancel"
    const val RENAME_TITLE = "Rename session"
    const val RENAME_CONFIRM = "Save"
    const val UNTITLED_FALLBACK = com.codespace.ide.chat.session.SessionMeta.UNTITLED

    /** Every interactive control is at least this big, per the composer's tested 40 dp target. */
    const val TOUCH_TARGET_DP = 40

    /** A row must stay tappable with a title, a time and a status dot on one line. */
    const val ROW_MIN_DP = 56

    /** Below this the header keeps only the search action and hides the rest behind "...". */
    const val WIDE_HEADER_MIN_DP = 320

    /** Full-width primary action needs room for its own label plus padding. */
    const val NEW_SESSION_MIN_DP = 200

    /**
     * Which header actions fit. Narrow first: the search action is the one a user reaches for
     * most, so it is the only one that stays inline; refresh and filter/sort are still
     * reachable, one tap deeper, never removed.
     */
    fun headerForWidth(widthDp: Float): SessionsHeaderSpec =
        if (widthDp >= WIDE_HEADER_MIN_DP) {
            SessionsHeaderSpec(
                inline = listOf(
                    SessionsHeaderAction.REFRESH,
                    SessionsHeaderAction.SEARCH,
                    SessionsHeaderAction.FILTER,
                ),
                overflow = emptyList(),
            )
        } else {
            SessionsHeaderSpec(
                inline = listOf(SessionsHeaderAction.SEARCH),
                overflow = listOf(SessionsHeaderAction.REFRESH, SessionsHeaderAction.FILTER),
            )
        }

    /** The "New Session" button is full width; it only needs its own label to fit. */
    fun showsNewSessionButton(widthDp: Float): Boolean = widthDp >= NEW_SESSION_MIN_DP

    /** The active-filter badge is a count; two digits are the realistic maximum. */
    fun filterBadge(count: Int): String? = if (count <= 0) null else if (count > 9) "9+" else count.toString()
}
