package com.codespace.ide.chat.panel

import com.codespace.ide.chat.StoredMessage
import com.codespace.ide.chat.TranscriptLoad
import com.codespace.ide.chat.CustomEndpointStore
import com.codespace.ide.chat.keys.StoredCredentialsReader
import com.codespace.ide.chat.session.SessionCreate
import com.codespace.ide.chat.session.SessionDelete
import com.codespace.ide.chat.session.SessionIndexRead
import com.codespace.ide.chat.session.SessionIndexStore
import com.codespace.ide.chat.session.SessionMeta
import com.codespace.ide.chat.session.SessionTranscripts
import com.codespace.ide.chat.ui.ChatRowModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * M1 — the registry.
 *
 * The registry is where multi-session becomes real, so these tests are about the things a
 * screen cannot show you: that two conversations are genuinely separate objects backed by
 * separate files, that the active choice survives a restart, that a geminate switch stops a
 * live stream instead of running two, and that deleting a session takes its undo history with
 * it.
 *
 * The credentials are a fake: session behaviour has nothing to do with key storage, and the
 * production reader cannot be built without an Android Context.
 */
class ChatSessionMultiSessionTest {

    /** In-memory transcripts: this file is about the registry, not about file bytes. */
    private class MemTranscripts : SessionTranscripts {
        val conversations = LinkedHashMap<String, List<StoredMessage>>()
        private fun key(projectId: String, conversationId: String) = projectId + "/" + conversationId

        override fun ids(projectId: String): List<String> =
            conversations.keys.filter { it.startsWith(projectId + "/") }
                .map { it.substringAfter("/") }
                .sorted()

        override fun readNow(projectId: String, conversationId: String): TranscriptLoad =
            TranscriptLoad(conversations[key(projectId, conversationId)].orEmpty())

        override fun deleteNow(projectId: String, conversationId: String): String? {
            conversations.remove(key(projectId, conversationId))
            return null
        }

        fun exists(projectId: String, conversationId: String) = conversations.containsKey(key(projectId, conversationId))
    }

    private lateinit var root: File
    private lateinit var transcripts: MemTranscripts
    private var now = 1_000L

    @Before
    fun setUp() {
        root = File.createTempFile("vncode-registry", "test").let { f ->
            f.delete()
            f.mkdirs()
            f
        }
        transcripts = MemTranscripts()
        now = 1_000L
        com.codespace.ide.agent.AgentWriteJournal.clear()
    }

    @After
    fun tearDown() {
        com.codespace.ide.agent.AgentWriteJournal.clear()
        root.deleteRecursively()
    }

    private fun store() = SessionIndexStore(
        root,
        clock = { now },
        random = Random(3),
        transcripts = transcripts,
    )

    /**
     * The REAL reader, with no Android behind it: an empty slot map, a null key lookup, and the
     * endpoint store before init() - whose read is designed to report "unreadable" rather than
     * throw. That is why no test seam is needed for credentials at all.
     */
    private fun manager() = ChatSessionManager(
        StoredCredentialsReader({ emptyMap() }, { null }, CustomEndpointStore),
        appContext = null,
        sessionStore = store(),
    )

    // ---------------------------------------------------------------- keying

    @Test
    fun `sessions are keyed by project and conversation, not by project alone`() {
        val m = manager()
        val a = m.session("p", "s_a")
        val b = m.session("p", "s_b")

        assertFalse("two conversations must not share one session object", a === b)
        assertSame("the same conversation must resolve to the same object", a, m.session("p", "s_a"))
        assertEquals("s_a", a.conversationId)
        assertEquals("s_b", b.conversationId)
    }

    @Test
    fun `the same conversation id in two projects is two different sessions`() {
        val m = manager()
        assertFalse(m.session("p1", "s_a") === m.session("p2", "s_a"))
    }

    // ---------------------------------------------------------------- lifecycle

    @Test
    fun `creating sessions keeps them independent and makes the newest active`() {
        val m = manager()
        val first = m.createSession("p") as SessionCreate.Created
        now = 2_000L
        val second = m.createSession("p") as SessionCreate.Created

        assertFalse(first.meta.id == second.meta.id)
        assertEquals(second.meta.id, m.activeSessionId("p"))
        val listed = (m.sessionIndex("p") as SessionIndexRead.Ok).index
        assertEquals(setOf(first.meta.id, second.meta.id), listed.sessions.map { it.id }.toSet())
    }

    @Test
    fun `switching is remembered across a restart`() {
        val first = (manager().createSession("p") as SessionCreate.Created).meta
        now = 2_000L
        val second = (manager().createSession("p") as SessionCreate.Created).meta

        val m = manager()
        assertNull(m.switchTo("p", first.id))
        assertEquals(first.id, m.activeSessionId("p"))

        // A brand new manager = a cold start: no in-memory cache to help it.
        val restarted = manager()
        assertEquals(first.id, restarted.activeSessionId("p"))
        assertEquals("the list comes back too", 2, (restarted.sessionIndex("p") as SessionIndexRead.Ok).index.sessions.size)
        assertNotNull(second.id)
    }

    @Test
    fun `switching to a session that does not exist is refused and changes nothing`() {
        val m = manager()
        val created = (m.createSession("p") as SessionCreate.Created).meta
        val before = m.activeSessionId("p")

        assertNotNull(m.switchTo("p", "s_missing"))
        assertEquals("a refused switch must not move the pointer", before, m.activeSessionId("p"))
        assertEquals(created.id, m.activeSessionId("p"))
    }

    @Test
    fun `switching stops a streaming session instead of running two in parallel`() {
        val m = manager()
        val a = (m.createSession("p") as SessionCreate.Created).meta
        now = 2_000L
        val b = (m.createSession("p") as SessionCreate.Created).meta
        // Creating B made it active; go back to A so there is something to switch AWAY from.
        assertNull(m.switchTo("p", a.id))

        val live = m.session("p", a.id)
        live.ui.value = live.ui.value.copy(
            rows = listOf(ChatRowModel.User("q"), ChatRowModel.AssistantStream("part", cursorOn = false)),
            streaming = true,
        )
        assertTrue(m.isStreaming("p", a.id))

        now = 3_000L
        assertNull(m.switchTo("p", b.id))

        assertFalse("the outgoing stream must be stopped, not left running", m.isStreaming("p", a.id))
        assertEquals(b.id, m.activeSessionId("p"))
    }

    @Test
    fun `renaming and archiving go through the registry and change no id`() {
        val m = manager()
        val created = (m.createSession("p") as SessionCreate.Created).meta

        assertNull(m.renameSession("p", created.id, "Renamed"))
        assertNull(m.archiveSession("p", created.id, true))

        val index = (m.sessionIndex("p") as SessionIndexRead.Ok).index
        val after = index.find(created.id)!!
        assertEquals("Renamed", after.title)
        assertTrue(after.archived)
        assertEquals(created.id, after.id)
    }

    @Test
    fun `projects stay isolated through the registry`() {
        val m = manager()
        val a = (m.createSession("project-a") as SessionCreate.Created).meta

        assertFalse(m.containsSession("project-b", a.id))
        assertTrue((m.sessionIndex("project-b") as SessionIndexRead.Ok).index.sessions.isEmpty())
        assertEquals("project-b lists nothing", null, (m.sessionIndex("project-b") as SessionIndexRead.Ok).index.activeId)
        m.close()
    }

    // ---------------------------------------------------------------- deletion

    @Test
    fun `deleting a session removes its transcript, its record and its undo history`() {
        val m = manager()
        val created = (m.createSession("p") as SessionCreate.Created).meta
        transcripts.conversations["p/" + created.id] = listOf(StoredMessage("user", "hi", true, 10L))
        assertTrue(transcripts.exists("p", created.id))

        // An AI edit that belongs to this session.
        com.codespace.ide.agent.AgentWriteJournal.beginOperation(created.id)
        com.codespace.ide.agent.AgentWriteJournal.record(
            File(root, "touched.txt").absolutePath, null, "content",
        )
        assertEquals(1, com.codespace.ide.agent.AgentWriteJournal.setsFor(created.id).size)

        val result = m.deleteSession("p", created.id)
        assertTrue(result is SessionDelete.Deleted)
        assertEquals(true, (result as SessionDelete.Deleted).transcriptRemoved)

        assertFalse(transcripts.exists("p", created.id))
        assertFalse(m.containsSession("p", created.id))
        assertTrue(
            "a deleted session's edits must not be undoable afterwards",
            com.codespace.ide.agent.AgentWriteJournal.setsFor(created.id).isEmpty(),
        )
    }

    @Test
    fun `deleting is refused when the table cannot be read, and the transcript survives`() {
        val m = manager()
        val created = (m.createSession("p") as SessionCreate.Created).meta
        transcripts.conversations["p/" + created.id] = listOf(StoredMessage("user", "hi", true, 10L))
        store().fileFor("p").writeText("garbage")

        val result = m.deleteSession("p", created.id)
        assertTrue(result is SessionDelete.Failed)
        assertTrue("nothing may be deleted on a failed metadata write", transcripts.exists("p", created.id))
    }

    @Test
    fun `an unreadable table is surfaced rather than presented as an empty one`() {
        val m = manager()
        m.createSession("p")
        store().fileFor("p").writeText("garbage")

        val read = m.sessionIndex("p")
        assertTrue(read is SessionIndexRead.Unreadable)
        assertNotNull("the reason must be reportable", (read as SessionIndexRead.Unreadable).reason)
    }

    // ---------------------------------------------------------------- session id

    @Test
    fun `a project with no table yet falls back to the legacy conversation id`() {
        val m = manager()
        assertEquals(SessionMeta.LEGACY_ID, m.activeSessionId("fresh"))
        // And that first open adopts the conversation it is actually using.
        val index = (m.sessionIndex("fresh") as SessionIndexRead.Ok).index
        assertTrue(index.contains(SessionMeta.LEGACY_ID))
    }
}
