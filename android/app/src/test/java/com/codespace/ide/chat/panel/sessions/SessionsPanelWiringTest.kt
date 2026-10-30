package com.codespace.ide.chat.panel.sessions

import com.codespace.ide.chat.CustomEndpointStore
import com.codespace.ide.chat.keys.StoredCredentialsReader
import com.codespace.ide.chat.panel.ChatSessionManager
import com.codespace.ide.chat.session.SessionDelete
import com.codespace.ide.chat.session.SessionIndexStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * M2 — the panel's wiring against the REAL M0/M1 store.
 *
 * Three claims are proven here, and they are the ones a screenshot cannot show:
 *
 *  1. creating, switching and deleting publish the new active id on the observable flow the
 *     panel collects - otherwise the chat would keep rendering the old conversation;
 *  2. browsing (search, sort, filter) writes NOTHING - the index file is byte-identical after a
 *     full round of view changes;
 *  3. a delete that leaves the list empty moves the active id to a real conversation, never to a
 *     phantom.
 */
class SessionsPanelWiringTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = File.createTempFile("vncode-m2", "test").let { f ->
            f.delete()
            f.mkdirs()
            f
        }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun store() = SessionIndexStore(root)

    private fun manager() = ChatSessionManager(
        StoredCredentialsReader({ emptyMap() }, { null }, CustomEndpointStore),
        appContext = null,
        sessionStore = store(),
    )

    private fun indexFile(): File = store().fileFor("p")

    @Test
    fun `creating a session publishes it as the active conversation`() {
        val m = manager()
        val first = m.activeSessionFlow("p").value

        val created = m.createSession("p")
        assertTrue(created is com.codespace.ide.chat.session.SessionCreate.Created)
        val id = (created as com.codespace.ide.chat.session.SessionCreate.Created).meta.id

        assertEquals("the panel re-keys from this flow", id, m.activeSessionFlow("p").value)
        assertNotEquals(first, id)
        assertEquals(id, m.activeSessionId("p"))
    }

    @Test
    fun `switching publishes the target conversation`() {
        val m = manager()
        val a = (m.createSession("p") as com.codespace.ide.chat.session.SessionCreate.Created).meta.id
        val b = (m.createSession("p") as com.codespace.ide.chat.session.SessionCreate.Created).meta.id
        assertEquals(b, m.activeSessionFlow("p").value)

        assertEquals(null, m.switchTo("p", a))
        assertEquals(a, m.activeSessionFlow("p").value)
        assertEquals(a, m.activeSessionId("p"))
    }

    @Test
    fun `a refused switch leaves the flow on the old conversation`() {
        val m = manager()
        val a = (m.createSession("p") as com.codespace.ide.chat.session.SessionCreate.Created).meta.id

        val failure = m.switchTo("p", "s_does_not_exist")
        assertTrue("a phantom switch must be refused", failure != null)
        assertEquals(a, m.activeSessionFlow("p").value)
    }

    @Test
    fun `deleting the active session publishes a real successor`() {
        val m = manager()
        val a = (m.createSession("p") as com.codespace.ide.chat.session.SessionCreate.Created).meta.id
        val b = (m.createSession("p") as com.codespace.ide.chat.session.SessionCreate.Created).meta.id
        assertEquals(b, m.activeSessionFlow("p").value)

        val result = m.deleteSession("p", b)
        assertTrue(result is SessionDelete.Deleted)
        val next = m.activeSessionFlow("p").value
        assertEquals("the survivor becomes active", a, next)
        assertTrue("never a phantom", m.containsSession("p", next))
    }

    @Test
    fun `browsing the list writes nothing to disk`() {
        val m = manager()
        m.createSession("p", "Alpha")
        m.createSession("p", "Beta")
        val before = indexFile().readBytes()

        val index = (m.sessionIndex("p") as com.codespace.ide.chat.session.SessionIndexRead.Ok).index
        var query = com.codespace.ide.chat.session.SessionQuery()
        query = SessionsViewLogic.withSearch(query, "a")
        query = SessionsViewLogic.withSort(query, com.codespace.ide.chat.session.SessionSort.CREATED)
        query = SessionsViewLogic.toggleFilter(query, com.codespace.ide.chat.session.SessionFilter.LOCAL)
        query = SessionsViewLogic.toggleFilter(query, com.codespace.ide.chat.session.SessionFilter.ARCHIVED)
        SessionsViewLogic.rows(index.sessions, query, nowMs = 1L, activeId = null)

        assertTrue("sorting and filtering must not rewrite the table", before.contentEquals(indexFile().readBytes()))
        assertEquals(2, index.sessions.size)
    }

    @Test
    fun `a rename really writes and the list shows the new title`() {
        val m = manager()
        val id = (m.createSession("p", "Before") as com.codespace.ide.chat.session.SessionCreate.Created).meta.id
        assertEquals(null, m.renameSession("p", id, "After"))

        val index = (m.sessionIndex("p") as com.codespace.ide.chat.session.SessionIndexRead.Ok).index
        val rows = SessionsViewLogic.rows(index.sessions, com.codespace.ide.chat.session.SessionQuery(), nowMs = 1L, activeId = id)
        assertEquals("After", rows.single().title)
    }

    @Test
    fun `archive hides the session and unarchive brings it back`() {
        val m = manager()
        val id = (m.createSession("p", "Tidy") as com.codespace.ide.chat.session.SessionCreate.Created).meta.id
        assertEquals(null, m.archiveSession("p", id, true))

        val archived = (m.sessionIndex("p") as com.codespace.ide.chat.session.SessionIndexRead.Ok).index
        val hidden = SessionsViewLogic.rows(archived.sessions, com.codespace.ide.chat.session.SessionQuery(), nowMs = 1L, activeId = id)
        assertTrue("an archived session is out of the default view", hidden.isEmpty())

        assertEquals(null, m.archiveSession("p", id, false))
        val restored = (m.sessionIndex("p") as com.codespace.ide.chat.session.SessionIndexRead.Ok).index
        assertFalse(SessionsViewLogic.rows(restored.sessions, com.codespace.ide.chat.session.SessionQuery(), nowMs = 1L, activeId = id).isEmpty())
    }
}
