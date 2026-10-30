package com.codespace.ide.chat.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M0 — sorting and filtering.
 *
 * Two things are being defended here:
 *  1. The LOCKED labels. The owner's reference recording names exactly two sort options and
 *     eight filters; those strings are asserted so a later refactor cannot quietly rename or
 *     drop one.
 *  2. That a view operation is only a view: sorting and filtering must not change a single
 *     stored field, or "sorted by Created" could corrupt "Updated" for later.
 */
class SessionListLogicTest {

    private fun meta(
        id: String,
        title: String = "Session " + id,
        created: Long = 1_000L,
        updated: Long = 2_000L,
        archived: Boolean = false,
        lastRead: Long = 0L,
    ) = SessionMeta(id, title, created, updated, archived, lastRead)

    // ---------------------------------------------------------------- locked labels

    @Test
    fun `sort options are exactly Created and Updated in that order`() {
        assertEquals(listOf("Created", "Updated"), SessionSort.entries.map { it.label })
    }

    @Test
    fun `filters are exactly the eight reference labels in reference order`() {
        assertEquals(
            listOf(
                "Local", "External", "Completed", "In Progress",
                "Attention Needed", "Failed", "Read", "Archived",
            ),
            SessionFilter.entries.map { it.label },
        )
    }

    @Test
    fun `only the two categories with no data source are marked as such`() {
        assertEquals(
            listOf(SessionFilter.EXTERNAL, SessionFilter.FAILED),
            SessionFilter.withoutDataSource,
        )
    }

    @Test
    fun `every reference label maps back to its filter`() {
        for (f in SessionFilter.entries) {
            assertEquals(f, SessionFilter.byLabel(f.label))
        }
    }

    // ---------------------------------------------------------------- sorting

    @Test
    fun `Updated sorts newest first`() {
        val list = listOf(meta("a", updated = 10L), meta("b", updated = 30L), meta("c", updated = 20L))
        val out = SessionListLogic.sorted(list, SessionSort.UPDATED).map { it.id }
        assertEquals(listOf("b", "c", "a"), out)
    }

    @Test
    fun `Created sorts newest first`() {
        val list = listOf(meta("a", created = 10L), meta("b", created = 30L), meta("c", created = 20L))
        val out = SessionListLogic.sorted(list, SessionSort.CREATED).map { it.id }
        assertEquals(listOf("b", "c", "a"), out)
    }

    @Test
    fun `equal timestamps fall back to the id so the order never flickers`() {
        val list = listOf(meta("s_c", updated = 5L), meta("s_a", updated = 5L), meta("s_b", updated = 5L))
        val first = SessionListLogic.sorted(list, SessionSort.UPDATED).map { it.id }
        val second = SessionListLogic.sorted(list.reversed(), SessionSort.UPDATED).map { it.id }
        assertEquals(listOf("s_a", "s_b", "s_c"), first)
        assertEquals(first, second)
    }

    // ---------------------------------------------------------------- archived visibility

    @Test
    fun `archived sessions are hidden unless asked for`() {
        val list = listOf(meta("live"), meta("old", archived = true))
        assertEquals(listOf("live"), SessionListLogic.select(list, SessionQuery()).map { it.id })
        // Equal Updated timestamps tie-break on id (live < old), which is what keeps the order
        // from flickering between recompositions.
        assertEquals(
            listOf("live", "old"),
            SessionListLogic.select(list, SessionQuery(includeArchived = true)).map { it.id },
        )
    }

    @Test
    fun `selecting the Archived filter reveals archived sessions on its own`() {
        val list = listOf(meta("live"), meta("old", archived = true))
        val out = SessionListLogic.select(
            list,
            SessionQuery(filters = setOf(SessionFilter.ARCHIVED)),
        ).map { it.id }
        assertEquals(listOf("old"), out)
    }

    // ---------------------------------------------------------------- filters

    @Test
    fun `Read is derived from lastRead versus updated`() {
        val read = meta("read", updated = 100L, lastRead = 150L)
        val unread = meta("unread", updated = 100L, lastRead = 99L)
        val out = SessionListLogic.select(
            listOf(read, unread),
            SessionQuery(filters = setOf(SessionFilter.READ)),
        ).map { it.id }
        assertEquals(listOf("read"), out)
    }

    @Test
    fun `Completed needs stored messages and a finished reply that is not still streaming`() {
        val list = listOf(meta("a", updated = 20L), meta("b", updated = 10L))
        val out = SessionListLogic.select(
            list,
            SessionQuery(filters = setOf(SessionFilter.COMPLETED)),
        ) { m ->
            if (m.id == "a") SessionSignals(hasMessages = true, lastReplyComplete = true)
            else SessionSignals(hasMessages = true, lastReplyComplete = true, streamingNow = true)
        }.map { it.id }
        assertEquals(listOf("a"), out)
    }

    @Test
    fun `In Progress only matches a session that is streaming right now`() {
        val list = listOf(meta("a", updated = 20L), meta("b", updated = 10L))
        val out = SessionListLogic.select(list, SessionQuery(filters = setOf(SessionFilter.IN_PROGRESS))) { m ->
            SessionSignals(streamingNow = m.id == "b")
        }.map { it.id }
        assertEquals(listOf("b"), out)
    }

    @Test
    fun `Local matches every session because every session is local today`() {
        val list = listOf(meta("a"), meta("b"))
        assertEquals(2, SessionListLogic.select(list, SessionQuery(filters = setOf(SessionFilter.LOCAL))).size)
    }

    @Test
    fun `External and Failed match nothing because nothing produces them yet`() {
        // Not a bug: these two have no data source (SessionFilter.withoutDataSource). The test
        // exists so that if someone ever gives them a source, this expectation must change
        // deliberately rather than silently.
        val list = listOf(meta("a"), meta("b"))
        assertTrue(SessionListLogic.select(list, SessionQuery(filters = setOf(SessionFilter.EXTERNAL))).isEmpty())
        assertTrue(SessionListLogic.select(list, SessionQuery(filters = setOf(SessionFilter.FAILED))).isEmpty())
    }

    @Test
    fun `several filters combine as OR so chips never cancel each other out`() {
        val list = listOf(meta("read", updated = 100L, lastRead = 200L), meta("stream", updated = 90L))
        val out = SessionListLogic.select(
            list,
            SessionQuery(filters = setOf(SessionFilter.READ, SessionFilter.IN_PROGRESS)),
        ) { m -> SessionSignals(streamingNow = m.id == "stream") }.map { it.id }
        assertEquals(listOf("read", "stream"), out)
    }

    // ---------------------------------------------------------------- search

    @Test
    fun `search matches the title and the id, case insensitively`() {
        val list = listOf(meta("s_one", "Refactor parser"), meta("s_two", "Rename button"))
        assertEquals(
            listOf("s_one"),
            SessionListLogic.select(list, SessionQuery(search = "PARSER")).map { it.id },
        )
        assertEquals(
            listOf("s_two"),
            SessionListLogic.select(list, SessionQuery(search = "s_two")).map { it.id },
        )
    }

    // ---------------------------------------------------------------- purity

    @Test
    fun `sorting and filtering change no stored field`() {
        val list = listOf(
            meta("s_a", "Alpha", 100L, 400L, archived = false, lastRead = 10L),
            meta("s_b", "Beta", 200L, 300L, archived = true, lastRead = 300L),
        )
        val before = SessionIndexCodec.encode(SessionIndex(list, "s_a"))
        val copy = list.map { it.copy() }

        SessionListLogic.select(list, SessionQuery(sort = SessionSort.CREATED, includeArchived = true, search = "a"))
        SessionListLogic.select(list, SessionQuery(sort = SessionSort.UPDATED, filters = setOf(SessionFilter.READ)))
        SessionListLogic.sorted(list, SessionSort.CREATED)

        assertEquals(copy, list)
        assertEquals(before, SessionIndexCodec.encode(SessionIndex(list, "s_a")))
    }

    @Test
    fun `select returns its own list so a caller cannot mutate the index through it`() {
        val list = listOf(meta("s_a"), meta("s_b"))
        val out = SessionListLogic.select(list, SessionQuery())
        assertFalse(out === list)
        assertEquals(2, out.size)
    }
}
