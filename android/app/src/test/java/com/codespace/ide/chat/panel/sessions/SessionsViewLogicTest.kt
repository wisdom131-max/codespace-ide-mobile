package com.codespace.ide.chat.panel.sessions

import com.codespace.ide.chat.session.SessionFilter
import com.codespace.ide.chat.session.SessionListLogic
import com.codespace.ide.chat.session.SessionMeta
import com.codespace.ide.chat.session.SessionQuery
import com.codespace.ide.chat.session.SessionSignals
import com.codespace.ide.chat.session.SessionSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * M2 — the Sessions list's decisions, proven without a screen.
 *
 * Two rules of the owner's are enforced structurally here: sorting and filtering must not be
 * able to write anything (these functions have no store in scope, and the last test asserts the
 * input is unchanged), and no control may claim something the data cannot support.
 */
class SessionsViewLogicTest {

    private val t0 = 1_700_000_000_000L

    private fun meta(
        id: String,
        title: String,
        created: Long,
        updated: Long,
        archived: Boolean = false,
        read: Boolean = true,
    ) = SessionMeta(
        id = id,
        title = title,
        createdAtMs = created,
        updatedAtMs = updated,
        archived = archived,
        lastReadAtMs = if (read) updated else 0L,
    )

    private fun today() = listOf(
        meta("s_a", "Alpha", t0, t0 + 60_000L),
        meta("s_b", "Beta", t0 + 1_000L, t0 + 120_000L),
        meta("s_c", "Gamma", t0 + 2_000L, t0 + 30_000L),
    )

    // ---------------------------------------------------------------- time

    @Test
    fun `relative time is coarse and never negative`() {
        assertEquals("just now", SessionsViewLogic.relativeTime(t0, t0))
        assertEquals("just now", SessionsViewLogic.relativeTime(t0, t0 + 5_000L))
        assertEquals("just now", SessionsViewLogic.relativeTime(t0, t0 - 30_000L))
        assertEquals("1m ago", SessionsViewLogic.relativeTime(t0, t0 - 60_000L))
        assertEquals("59m ago", SessionsViewLogic.relativeTime(t0, t0 - 59L * 60_000L))
        assertEquals("1h ago", SessionsViewLogic.relativeTime(t0, t0 - 60L * 60_000L))
        assertEquals("23h ago", SessionsViewLogic.relativeTime(t0, t0 - 23L * 3_600_000L))
        assertEquals("1d ago", SessionsViewLogic.relativeTime(t0, t0 - 24L * 3_600_000L))
        assertEquals("6d ago", SessionsViewLogic.relativeTime(t0, t0 - 6L * 24L * 3_600_000L))
        val old = t0 - 10L * 24L * 3_600_000L
        val expected = Instant.ofEpochMilli(old).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        assertEquals("a week old becomes a date", expected, SessionsViewLogic.relativeTime(t0, old))
    }

    // ---------------------------------------------------------------- ordering

    @Test
    fun `default ordering is newest updated first and created ordering uses created time`() {
        val byUpdated = SessionsViewLogic.rows(today(), SessionQuery(), nowMs = t0 + 200_000L, activeId = null)
        assertEquals(listOf("s_b", "s_a", "s_c"), byUpdated.map { it.id })

        val byCreated = SessionsViewLogic.rows(
            today(),
            SessionQuery(sort = SessionSort.CREATED),
            nowMs = t0 + 200_000L,
            activeId = null,
        )
        // s_c was created last, so it leads even though it was updated least recently.
        assertEquals(listOf("s_c", "s_b", "s_a"), byCreated.map { it.id })
        // The time column follows the chosen sort, so the label always explains the order:
        // s_c was CREATED 198s before "now", even though it was UPDATED last of the three.
        assertEquals("3m ago", byCreated.first().timeLabel)
        assertEquals("1m ago", byUpdated.first().timeLabel)
    }

    @Test
    fun `the active session and unread state are marked, never inferred from order`() {
        val rows = SessionsViewLogic.rows(
            listOf(meta("s_a", "Alpha", t0, t0, read = false), meta("s_b", "Beta", t0, t0, read = true)),
            SessionQuery(),
            nowMs = t0,
            activeId = "s_b",
        )
        val a = rows.first { it.id == "s_a" }
        val b = rows.first { it.id == "s_b" }
        assertTrue(a.unread)
        assertFalse(b.unread)
        assertTrue(b.isActive)
        assertFalse(a.isActive)
    }

    // ---------------------------------------------------------------- filters

    @Test
    fun `all eight reference filters are offered, and the two without a source are marked unusable`() {
        val all = SessionsViewLogic.filters()
        assertEquals(8, all.size)
        assertEquals(
            listOf("Local", "External", "Completed", "In Progress", "Attention Needed", "Failed", "Read", "Archived"),
            all.map { it.label },
        )
        assertEquals(
            listOf(SessionFilter.EXTERNAL, SessionFilter.FAILED),
            all.filterNot { SessionsViewLogic.isUsable(it) },
        )
        assertEquals(listOf(SessionFilter.EXTERNAL, SessionFilter.FAILED), SessionFilter.withoutDataSource)
    }

    @Test
    fun `an unusable filter cannot be selected and a usable one toggles`() {
        var q = SessionQuery()
        q = SessionsViewLogic.toggleFilter(q, SessionFilter.EXTERNAL)
        q = SessionsViewLogic.toggleFilter(q, SessionFilter.FAILED)
        assertTrue("no fake selections", q.filters.isEmpty())

        q = SessionsViewLogic.toggleFilter(q, SessionFilter.LOCAL)
        assertEquals(setOf(SessionFilter.LOCAL), q.filters)
        q = SessionsViewLogic.toggleFilter(q, SessionFilter.LOCAL)
        assertTrue(q.filters.isEmpty())
    }

    @Test
    fun `archived changes visibility but is not counted as a filter badge`() {
        var q = SessionQuery()
        q = SessionsViewLogic.toggleFilter(q, SessionFilter.ARCHIVED)
        assertTrue(q.includeArchived)
        assertEquals("archived is a visibility rule, not a content filter", 0, SessionsViewLogic.activeFilterCount(q))
        q = SessionsViewLogic.toggleFilter(q, SessionFilter.ARCHIVED)
        assertFalse(q.includeArchived)

        q = SessionsViewLogic.toggleFilter(SessionsViewLogic.toggleFilter(q, SessionFilter.LOCAL), SessionFilter.READ)
        assertEquals(2, SessionsViewLogic.activeFilterCount(q))
    }

    @Test
    fun `archived sessions are hidden until asked for`() {
        val sessions = listOf(
            meta("s_a", "Alpha", t0, t0),
            meta("s_b", "Archived one", t0, t0, archived = true),
        )
        assertEquals(
            listOf("s_a"),
            SessionsViewLogic.rows(sessions, SessionQuery(), nowMs = t0, activeId = null).map { it.id },
        )
        assertEquals(
            listOf("s_b"),
            SessionsViewLogic.rows(
                sessions,
                SessionQuery(filters = setOf(SessionFilter.ARCHIVED), includeArchived = true),
                nowMs = t0,
                activeId = null,
            ).map { it.id },
        )
    }

    // ---------------------------------------------------------------- search

    @Test
    fun `search narrows on title and id without touching the sessions`() {
        val sessions = today()
        val before = sessions.toList()
        val q = SessionsViewLogic.withSearch(SessionQuery(), "bet")
        assertEquals(listOf("s_b"), SessionsViewLogic.rows(sessions, q, nowMs = t0, activeId = null).map { it.id })
        assertEquals("the input list must be untouched", before, sessions)

        val byId = SessionsViewLogic.withSearch(SessionQuery(), "s_c")
        assertEquals(listOf("s_c"), SessionsViewLogic.rows(sessions, byId, nowMs = t0, activeId = null).map { it.id })

        assertEquals("blank search clears itself", SessionQuery(), SessionsViewLogic.withSearch(q, "   "))
    }

    // ---------------------------------------------------------------- status

    @Test
    fun `status is derived with a fixed priority and never invented`() {
        assertNull(SessionsViewLogic.statusFor(SessionSignals()))
        assertNull(SessionsViewLogic.statusFor(SessionSignals(hasMessages = true, lastReplyComplete = false)))
        assertEquals(
            "Completed",
            SessionsViewLogic.statusFor(SessionSignals(hasMessages = true, lastReplyComplete = true)),
        )
        assertEquals(
            "In Progress",
            SessionsViewLogic.statusFor(
                SessionSignals(hasMessages = true, lastReplyComplete = true, streamingNow = true),
            ),
        )
        assertEquals(
            "Attention Needed",
            SessionsViewLogic.statusFor(
                SessionSignals(hasMessages = true, lastReplyComplete = true, streamingNow = true, needsAttention = true),
            ),
        )
    }

    @Test
    fun `a streaming session is labelled In Progress in its row`() {
        val sessions = listOf(meta("s_a", "Alpha", t0, t0))
        val rows = SessionsViewLogic.rows(
            sessions,
            SessionQuery(),
            signalsFor = { SessionSignals(streamingNow = true) },
            nowMs = t0,
            activeId = "s_a",
        )
        assertEquals("In Progress", rows.single().statusLabel)
    }

    // ---------------------------------------------------------------- row actions

    @Test
    fun `row actions follow the archive state`() {
        assertEquals(
            listOf(SessionRowAction.RENAME, SessionRowAction.ARCHIVE, SessionRowAction.DELETE),
            SessionsViewLogic.rowActions(archived = false),
        )
        assertEquals(
            listOf(SessionRowAction.RENAME, SessionRowAction.UNARCHIVE, SessionRowAction.DELETE),
            SessionsViewLogic.rowActions(archived = true),
        )
    }

    @Test
    fun `empty state distinguishes an empty project from a hidden view`() {
        val emptyProject = SessionsViewLogic.emptyMessage(0, SessionQuery())
        assertTrue(emptyProject.contains(SessionsPanelSpec.EMPTY_TITLE))
        assertEquals(SessionsPanelSpec.NO_MATCH, SessionsViewLogic.emptyMessage(3, SessionQuery(search = "zzz")))
    }

    @Test
    fun `the active view is described so a filtered list is never silent`() {
        assertNull(SessionsViewLogic.viewSummary(SessionQuery()))
        assertNotNull(SessionsViewLogic.viewSummary(SessionQuery(filters = setOf(SessionFilter.LOCAL))))
        val summary = SessionsViewLogic.viewSummary(
            SessionQuery(filters = setOf(SessionFilter.LOCAL), sort = SessionSort.CREATED),
        )
        assertTrue(summary!!.contains("1 filter"))
        assertTrue(summary.contains("Created"))
        assertEquals("singular", "1 filter", summary.substringBefore(" ·"))
    }

    @Test
    fun `the underlying list logic is the single source of ordering and matching`() {
        val sessions = today()
        val q = SessionQuery(sort = SessionSort.UPDATED, search = "a")
        assertEquals(
            SessionListLogic.select(sessions, q).map { it.id },
            SessionsViewLogic.rows(sessions, q, nowMs = t0, activeId = null).map { it.id },
        )
    }
}
