package com.codespace.ide.chat.session

import com.codespace.ide.chat.StoredMessage
import com.codespace.ide.chat.TranscriptFiles
import com.codespace.ide.chat.TranscriptLoad
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * M1 — the session table on disk.
 *
 * These are the assertions that make multi-session storage safe rather than merely working:
 * a damaged table is never read as empty, a delete never destroys bytes before its metadata
 * write has landed, an existing "main" conversation is adopted without its transcript being
 * touched, and one project cannot name another project's files.
 *
 * The REAL transcript layer is used (TranscriptFiles, over a temp directory), not a stub, so
 * the index/transcript interaction that actually ships is the one under test.
 */
class SessionIndexStoreTest {

    private lateinit var chatRoot: File
    private lateinit var real: RealTranscripts
    private var now = 1_000L

    /** The real transcript files, in the real layout, with the real deletion path. */
    private class RealTranscripts(private val root: File) : SessionTranscripts {
        private val files = TranscriptFiles(root)

        override fun ids(projectId: String): List<String> = files.ids(projectId)
        override fun readNow(projectId: String, conversationId: String): TranscriptLoad =
            files.load(projectId, conversationId)
        override fun deleteNow(projectId: String, conversationId: String): String? =
            files.saveNow(projectId, conversationId, emptyList())

        fun write(projectId: String, conversationId: String, messages: List<StoredMessage>): String? =
            files.saveNow(projectId, conversationId, messages)

        fun txt(projectId: String, conversationId: String): File =
            File(File(root, TranscriptFiles.safeSegment(projectId)), conversationId + ".txt")

        fun bak(projectId: String, conversationId: String): File =
            File(File(root, TranscriptFiles.safeSegment(projectId)), conversationId + ".txt.bak")
    }

    private fun newStore(seed: Int = 1): SessionIndexStore =
        SessionIndexStore(chatRoot, clock = { now }, random = Random(seed), transcripts = real)

    @Before
    fun setUp() {
        chatRoot = File.createTempFile("vncode-sessions", "test").let { f ->
            f.delete()
            f.mkdirs()
            f
        }
        real = RealTranscripts(chatRoot)
        now = 1_000L
    }

    @After
    fun tearDown() {
        chatRoot.deleteRecursively()
    }

    private fun msgs(vararg text: String): List<StoredMessage> =
        text.mapIndexed { i, t -> StoredMessage("user", t, true, 500L + i * 100L) }

    private fun okIndex(read: SessionIndexRead): SessionIndex {
        assertTrue("expected Ok but was " + read, read is SessionIndexRead.Ok)
        return (read as SessionIndexRead.Ok).index
    }

    // ---------------------------------------------------------------- creation

    @Test
    fun `two created sessions are independent with their own transcript files`() {
        val store = newStore()
        val a = store.create("p") as SessionCreate.Created
        val b = store.create("p") as SessionCreate.Created

        assertFalse("ids must be generated, not shared", a.meta.id == b.meta.id)
        assertEquals(2, okIndex(store.read("p")).sessions.size)

        real.write("p", a.meta.id, msgs("alpha"))
        real.write("p", b.meta.id, msgs("beta"))

        assertEquals("alpha", real.readNow("p", a.meta.id).messages.single().text)
        assertEquals("beta", real.readNow("p", b.meta.id).messages.single().text)
    }

    @Test
    fun `creating a session makes it active and remembers that across a reopen`() {
        val first = (newStore(1).create("p") as SessionCreate.Created).meta
        now = 2_000L
        val second = (newStore(2).create("p") as SessionCreate.Created).meta

        assertEquals(second.id, okIndex(newStore().read("p")).activeId)

        // Switch back, then read with a brand new store: the choice survives.
        assertTrue(newStore().setActive("p", first.id) is SessionWrite.Ok)
        assertEquals(first.id, okIndex(newStore().read("p")).activeId)
    }

    @Test
    fun `an empty session is kept in the table and survives a reopen`() {
        val created = (newStore().create("p") as SessionCreate.Created).meta
        // Nothing was ever written for it: no transcript file at all.
        assertFalse(real.txt("p", created.id).exists())

        val reopened = okIndex(newStore().read("p"))
        assertTrue("an empty session must not disappear", reopened.contains(created.id))
        assertEquals(SessionMeta.UNTITLED, reopened.find(created.id)?.title)
    }

    // ---------------------------------------------------------------- rename

    @Test
    fun `renaming changes the title only, never the id, the path or the dates`() {
        val store = newStore()
        val created = (store.create("p") as SessionCreate.Created).meta
        real.write("p", created.id, msgs("hello"))

        assertTrue(store.rename("p", created.id, "  Sprint   planning ") is SessionWrite.Ok)

        val after = okIndex(store.read("p")).find(created.id)!!
        assertEquals("Sprint planning", after.title)
        assertEquals(created.id, after.id)
        assertEquals(created.createdAtMs, after.createdAtMs)
        assertEquals("a rename is not conversation activity", created.updatedAtMs, after.updatedAtMs)
        // The transcript is still exactly where it was.
        assertTrue(real.txt("p", created.id).exists())
    }

    @Test
    fun `a blank rename is refused instead of blanking the row`() {
        val store = newStore()
        val created = (store.create("p") as SessionCreate.Created).meta
        val result = store.rename("p", created.id, "   ")
        assertTrue(result is SessionWrite.Failed)
        assertEquals(SessionMeta.UNTITLED, okIndex(store.read("p")).find(created.id)?.title)
    }

    // ---------------------------------------------------------------- delete

    @Test
    fun `deleting removes the transcript, its backup and the metadata`() {
        val store = newStore()
        val created = (store.create("p") as SessionCreate.Created).meta
        // Two writes so the .bak exists as well.
        real.write("p", created.id, msgs("one"))
        real.write("p", created.id, msgs("one", "two"))
        assertTrue(real.txt("p", created.id).exists())
        assertTrue(real.bak("p", created.id).exists())

        val result = store.delete("p", created.id)
        assertTrue(result is SessionDelete.Deleted)
        assertEquals(true, (result as SessionDelete.Deleted).transcriptRemoved)
        assertNull(result.note)

        assertFalse(real.txt("p", created.id).exists())
        assertFalse("a stale backup would resurrect the file on next load", real.bak("p", created.id).exists())
        assertFalse(okIndex(store.read("p")).contains(created.id))
    }

    @Test
    fun `deleting the active session moves the active pointer to a surviving one`() {
        val store = newStore()
        val a = (store.create("p") as SessionCreate.Created).meta
        now = 2_000L
        val b = (store.create("p") as SessionCreate.Created).meta
        assertEquals(b.id, okIndex(store.read("p")).activeId)

        store.delete("p", b.id)
        assertEquals(a.id, okIndex(store.read("p")).activeId)
    }

    @Test
    fun `a delete refuses and leaves the transcript alone when the table cannot be read`() {
        val store = newStore()
        val created = (store.create("p") as SessionCreate.Created).meta
        real.write("p", created.id, msgs("precious"))
        // Damage the table.
        store.fileFor("p").writeText("not a session index")

        val result = store.delete("p", created.id)
        assertTrue("a damaged table must block the delete", result is SessionDelete.Failed)
        assertTrue(
            "the conversation must still be on disk",
            real.txt("p", created.id).exists(),
        )
        assertEquals("precious", real.readNow("p", created.id).messages.single().text)
    }

    // ---------------------------------------------------------------- damage

    @Test
    fun `a damaged table is reported as unreadable, never as an empty project`() {
        val store = newStore()
        (store.create("p") as SessionCreate.Created)
        store.fileFor("p").writeText("garbage")

        assertTrue(store.read("p") is SessionIndexRead.Unreadable)
        // And a write is refused rather than clobbering whatever is still there.
        assertTrue(store.create("p") is SessionCreate.Failed)
    }

    @Test
    fun `a damaged primary falls back to the backup and keeps the user's titles`() {
        val store = newStore()
        val created = (store.create("p") as SessionCreate.Created).meta
        store.rename("p", created.id, "My custom title")   // write 2 -> .bak = create
        store.rename("p", created.id, "My custom title 2") // write 3 -> .bak = write 2

        store.fileFor("p").writeText("garbage")

        val read = store.read("p")
        assertTrue(read is SessionIndexRead.Ok)
        val index = (read as SessionIndexRead.Ok).index
        assertNotNull("the user should be told the backup was used", read.note)
        assertEquals("My custom title", index.find(created.id)?.title)
    }

    @Test
    fun `recovery rebuilds the table from the transcripts and quarantines the damaged file`() {
        val store = newStore()
        real.write("p", SessionMeta.LEGACY_ID, msgs("Fix the parser"))
        real.write("p", "other", msgs("Second conversation"))
        store.fileFor("p").writeText("garbage")

        val read = store.ensure("p")
        assertTrue(read is SessionIndexRead.Ok)
        val index = (read as SessionIndexRead.Ok).index
        assertNotNull((read as SessionIndexRead.Ok).note)

        assertEquals(
            "both conversations must come back",
            setOf(SessionMeta.LEGACY_ID, "other"),
            index.sessions.map { it.id }.toSet(),
        )
        assertEquals("Fix the parser", index.find(SessionMeta.LEGACY_ID)?.title)

        // The damaged bytes are preserved for inspection, not destroyed.
        val quarantined = store.fileFor("p").parentFile!!.listFiles()!!
            .filter { it.name.contains("corrupt") }
        assertEquals(1, quarantined.size)
        assertEquals("garbage", quarantined.single().readText())

        // And the table is usable again immediately.
        assertTrue(store.read("p") is SessionIndexRead.Ok)
        assertTrue(store.create("p") is SessionCreate.Created)
    }

    @Test
    fun `an index path that cannot be written reports failure instead of claiming success`() {
        val store = newStore()
        val file = store.fileFor("p")
        file.parentFile!!.mkdirs()
        assertTrue("precondition: a directory where the file belongs", file.mkdirs())

        val result = store.save("p", SessionIndex(listOf(SessionMeta("s_a", "T", 1, 2)), "s_a"))
        assertTrue("the write must fail loudly", result is SessionWrite.Failed)
        assertTrue((result as SessionWrite.Failed).reason.isNotBlank())

        // And because the bytes cannot be read, mutations are refused rather than guessed at.
        assertTrue(store.read("p") is SessionIndexRead.Unreadable)
        assertTrue(store.create("p") is SessionCreate.Failed)
    }

    // ---------------------------------------------------------------- adoption

    @Test
    fun `the legacy main conversation is adopted without its transcript being touched`() {
        // Exactly the upgrade state: a conversation on disk, no session table.
        val messages = listOf(
            StoredMessage("user", "Fix the parser", true, 1_000L),
            StoredMessage("assistant", "Done", true, 2_500L),
        )
        real.write("p", SessionMeta.LEGACY_ID, messages)
        val before = real.txt("p", SessionMeta.LEGACY_ID).readBytes()

        val store = newStore()
        val index = okIndex(store.ensure("p"))

        val adopted = index.find(SessionMeta.LEGACY_ID)
        assertNotNull("main must remain reachable", adopted)
        assertEquals("Fix the parser", adopted!!.title)
        assertEquals(1_000L, adopted.createdAtMs)
        assertEquals(2_500L, adopted.updatedAtMs)
        assertEquals(SessionMeta.LEGACY_ID, index.activeId)
        assertFalse("an upgraded conversation must not look unread", !adopted.isRead)

        // Byte for byte identical: adoption writes metadata, never a transcript.
        assertTrue(before.contentEquals(real.txt("p", SessionMeta.LEGACY_ID).readBytes()))
        assertEquals(2, real.readNow("p", SessionMeta.LEGACY_ID).messages.size)
    }

    @Test
    fun `adoption is idempotent and does not duplicate the legacy session`() {
        real.write("p", SessionMeta.LEGACY_ID, msgs("Hello"))
        val store = newStore()
        store.ensure("p")
        store.ensure("p")
        val reopened = okIndex(newStore().ensure("p"))
        assertEquals(1, reopened.sessions.count { it.id == SessionMeta.LEGACY_ID })
    }

    // ---------------------------------------------------------------- isolation

    @Test
    fun `project A cannot see or reach project B sessions`() {
        val store = newStore()
        val a = (store.create("project-a") as SessionCreate.Created).meta
        real.write("project-a", a.id, msgs("belongs to A"))

        assertTrue(okIndex(store.read("project-b")).sessions.isEmpty())
        assertFalse(store.contains("project-b", a.id))

        // Even naming the id directly, B has no such session to delete.
        val attempt = store.delete("project-b", a.id)
        assertTrue(attempt is SessionDelete.Failed)
        assertTrue("A's transcript is untouched", real.txt("project-a", a.id).exists())

        // The two projects physically live in different directories.
        assertFalse(
            store.fileFor("project-a").parentFile == store.fileFor("project-b").parentFile,
        )
    }

    @Test
    fun `the session table is never listed as if it were a conversation`() {
        val store = newStore()
        (store.create("p") as SessionCreate.Created)
        real.write("p", SessionMeta.LEGACY_ID, msgs("hi"))

        assertEquals(listOf(SessionMeta.LEGACY_ID), real.ids("p"))
    }

    // ---------------------------------------------------------------- bookkeeping

    @Test
    fun `touch moves a session up the Updated order and never moves it backwards`() {
        val store = newStore()
        val created = (store.create("p") as SessionCreate.Created).meta
        assertEquals(created.updatedAtMs, okIndex(store.read("p")).find(created.id)?.updatedAtMs)

        store.touch("p", created.id, 9_000L)
        assertEquals(9_000L, okIndex(store.read("p")).find(created.id)?.updatedAtMs)
        store.touch("p", created.id, 1_000L)
        assertEquals("a stale save must not rewind Updated", 9_000L, okIndex(store.read("p")).find(created.id)?.updatedAtMs)
    }

    @Test
    fun `markRead flips the derived Read state`() {
        val store = newStore()
        val created = (store.create("p") as SessionCreate.Created).meta
        store.touch("p", created.id, 5_000L)
        assertFalse(okIndex(store.read("p")).find(created.id)!!.isRead)

        store.markRead("p", created.id, 6_000L)
        assertTrue(okIndex(store.read("p")).find(created.id)!!.isRead)
    }

    @Test
    fun `archiving is a flag, not a deletion`() {
        val store = newStore()
        val created = (store.create("p") as SessionCreate.Created).meta
        real.write("p", created.id, msgs("keep me"))

        store.setArchived("p", created.id, true)
        assertTrue(okIndex(store.read("p")).find(created.id)!!.archived)
        assertTrue("archived must not delete anything", real.txt("p", created.id).exists())
    }
}
