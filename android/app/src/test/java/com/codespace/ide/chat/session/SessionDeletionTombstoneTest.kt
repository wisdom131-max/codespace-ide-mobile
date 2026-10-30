package com.codespace.ide.chat.session

import com.codespace.ide.chat.StoredMessage
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
 * M1c — deletion is DURABLE, so recovery cannot resurrect a session the user deleted.
 *
 * The scenario every test below is really about: metadata removed, transcript bytes NOT
 * removed, index damaged later, index rebuilt from the transcripts on disk. Without a durable
 * record of the intent, that leftover file becomes a session again - with no error anywhere.
 *
 * The stubbed transcript layer is deliberate: it is the only way to make the byte delete FAIL on
 * demand, which is the half of the delete that has no other observable proof.
 */
class SessionDeletionTombstoneTest {

    private class StubTranscripts : SessionTranscripts {
        val files = LinkedHashMap<String, List<StoredMessage>>()

        /** When set, deleteNow reports this error instead of removing the bytes. */
        var failDeleteWith: String? = null

        private fun key(projectId: String, conversationId: String) = projectId + "/" + conversationId

        fun put(projectId: String, conversationId: String, vararg texts: String) {
            files[key(projectId, conversationId)] = texts.mapIndexed { i, t ->
                StoredMessage("user", t, true, 100L + i)
            }
        }

        fun has(projectId: String, conversationId: String): Boolean =
            files.containsKey(key(projectId, conversationId))

        override fun ids(projectId: String): List<String> =
            files.keys.filter { it.startsWith(projectId + "/") }
                .map { it.substringAfter("/") }
                .sorted()

        override fun readNow(projectId: String, conversationId: String): TranscriptLoad =
            TranscriptLoad(files[key(projectId, conversationId)].orEmpty())

        override fun deleteNow(projectId: String, conversationId: String): String? {
            failDeleteWith?.let { return it }
            files.remove(key(projectId, conversationId))
            return null
        }
    }

    /**
     * Produces ids from a fixed digit script, so a collision can be FORCED in a test instead of
     * hoped for. 16 digits make one id ("s_" + 16 characters), and the alphabet is
     * "0123456789abcdefghij...", so digit 10 is 'a' and 11 is 'b'.
     */
    private class DigitRandom(private val digits: List<Int>) : Random() {
        private var at = 0
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextInt(from: Int, until: Int): Int {
            val d = digits[at % digits.size]
            at++
            return from + (d % (until - from))
        }
    }

    private lateinit var root: File
    private lateinit var transcripts: StubTranscripts
    private var now = 5_000L

    @Before
    fun setUp() {
        root = File.createTempFile("vncode-tombstone", "test").let { f ->
            f.delete()
            f.mkdirs()
            f
        }
        transcripts = StubTranscripts()
        now = 5_000L
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun store(random: Random = Random(11)) =
        SessionIndexStore(root, clock = { now }, random = random, transcripts = transcripts)

    private fun create(store: SessionIndexStore, project: String = "p"): SessionMeta =
        (store.create(project) as SessionCreate.Created).meta

    /**
     * Forces a REAL rebuild: the primary index is damaged AND the fallback copy is removed.
     * Damaging only the primary is not enough - the backup legitimately answers, which is the
     * next test's subject.
     */
    private fun forceRebuild(store: SessionIndexStore, project: String = "p") {
        store.fileFor(project).writeText("garbage")
        File(
            store.fileFor(project).parentFile,
            SessionIndexStore.FILE_NAME + SessionIndexStore.BACKUP_SUFFIX,
        ).delete()
    }

    private fun log(store: SessionIndexStore, project: String = "p"): DeletionLog =
        (store.readDeletions(project) as DeletionLogRead.Ok).log

    // ---------------------------------------------------------------- 1. success

    @Test
    fun `a successful deletion records itself and confirms the bytes are gone`() {
        val s = store()
        val meta = create(s)
        transcripts.put("p", meta.id, "hello")

        val result = s.delete("p", meta.id)

        assertTrue(result is SessionDelete.Deleted)
        assertEquals(true, (result as SessionDelete.Deleted).transcriptRemoved)
        assertNull("a clean delete needs no explanation", result.note)

        val record = log(s).find(meta.id)
        assertNotNull("the deletion must be durable, not just remembered", record)
        assertEquals(true, record!!.bytesRemoved)
        assertFalse(transcripts.has("p", meta.id))
        assertFalse(s.contains("p", meta.id))
    }

    @Test
    fun `a deletion-record write failure changes nothing at all`() {
        val s = store()
        val meta = create(s)
        val survivor = create(s)
        transcripts.put("p", meta.id, "untouched bytes")
        val indexBefore = s.fileFor("p").readBytes()

        // The record cannot be written: its temp path is a directory, so the atomic write throws.
        // The record's own READ still succeeds (no sessions.deleted file exists yet), which is
        // exactly the state needed to reach the write step rather than failing earlier.
        val tmp = File(s.deletionsFileFor("p").parentFile, SessionIndexStore.DELETIONS_FILE + SessionIndexStore.TEMP_SUFFIX)
        assertTrue(tmp.mkdirs())

        val result = s.delete("p", meta.id)

        // 1. Failed, and it says nothing was deleted.
        assertTrue(result is SessionDelete.Failed)
        assertTrue((result as SessionDelete.Failed).reason.contains("could not be recorded"))

        // 2. The session is still in the index ...
        assertTrue("the session must still be listed", s.contains("p", meta.id))
        assertTrue(s.contains("p", survivor.id))
        // ... byte for byte unchanged, proving the index step was never reached.
        assertTrue(
            "the index must not have been rewritten at all",
            indexBefore.contentEquals(s.fileFor("p").readBytes()),
        )

        // 3. The transcript bytes are untouched.
        assertTrue(transcripts.has("p", meta.id))
        assertEquals("untouched bytes", transcripts.readNow("p", meta.id).messages.single().text)

        // 4. No deletion was recorded, so a rebuild still sees the session.
        assertFalse(s.isDeleted("p", meta.id))
        forceRebuild(s)
        val rebuilt = s.ensure("p") as SessionIndexRead.Ok
        assertTrue("nothing was deleted, so nothing may be withheld", rebuilt.index.contains(meta.id))
    }

    // ---------------------------------------------------------------- 2. failure

    @Test
    fun `a failed transcript delete is reported as failed, never as a clean success`() {
        val s = store()
        val meta = create(s)
        transcripts.put("p", meta.id, "precious")
        transcripts.failDeleteWith = "EACCES: permission denied"

        val result = s.delete("p", meta.id)

        assertTrue(result is SessionDelete.Deleted)
        val deleted = result as SessionDelete.Deleted
        assertEquals("the caller must be able to tell the halves apart", false, deleted.transcriptRemoved)
        assertNotNull(deleted.note)
        assertTrue(deleted.note!!.contains("EACCES"))

        // The bytes were not silently discarded, and the record knows they may still be there.
        assertTrue("nothing was discarded", transcripts.has("p", meta.id))
        assertEquals(false, log(s).find(meta.id)!!.bytesRemoved)
        // The list no longer names it: the user asked for it to be gone.
        assertFalse(s.contains("p", meta.id))
    }

    // ---------------------------------------------------------------- 3. crash window

    @Test
    fun `the state left by a crash between the two writes cannot resurrect the session`() {
        // Producing the crash state is the same as producing a failed byte delete: metadata
        // written, record written, bytes still present.
        val s = store()
        val meta = create(s)
        transcripts.put("p", meta.id, "still here")
        transcripts.failDeleteWith = "EIO"
        s.delete("p", meta.id)

        // Exactly the on-disk situation: not in the list, recorded as deleted, file present.
        assertFalse(s.contains("p", meta.id))
        assertTrue(s.isDeleted("p", meta.id))
        assertTrue(transcripts.has("p", meta.id))

        // A rebuild must keep it out.
        forceRebuild(s)
        val rebuilt = s.ensure("p") as SessionIndexRead.Ok
        assertFalse("the deleted session must not come back", rebuilt.index.contains(meta.id))
        assertNotNull(rebuilt.note)
        assertTrue(
            "the user must be told the leftover bytes were kept: " + rebuilt.note,
            rebuilt.note!!.contains("deleted session"),
        )
    }

    @Test
    fun `an index rebuild after a failed deletion keeps the bytes and reports them`() {
        val s = store()
        val keep = create(s)
        val doomed = create(s)
        transcripts.put("p", keep.id, "keep me")
        transcripts.put("p", doomed.id, "delete me")
        transcripts.failDeleteWith = "EROFS"

        s.delete("p", doomed.id)
        forceRebuild(s)

        val rebuilt = s.ensure("p") as SessionIndexRead.Ok
        assertEquals("the surviving session is rebuilt", setOf(keep.id), rebuilt.index.sessions.map { it.id }.toSet())
        assertTrue("the deleted session's bytes are untouched", transcripts.has("p", doomed.id))
        assertEquals("keep me", transcripts.readNow("p", keep.id).messages.single().text)
    }

    @Test
    fun `a backup copy written before the delete cannot bring the session back`() {
        val s = store()
        val survivor = create(s)
        val doomed = create(s)
        transcripts.put("p", survivor.id, "keep")
        transcripts.put("p", doomed.id, "leftover bytes")
        transcripts.failDeleteWith = "EIO"
        s.delete("p", doomed.id)

        // The .bak was written before that delete, so it still names the doomed session.
        val bak = File(s.fileFor("p").parentFile, SessionIndexStore.FILE_NAME + SessionIndexStore.BACKUP_SUFFIX)
        assertTrue("precondition: a backup exists", bak.exists())
        s.fileFor("p").writeText("garbage")   // primary damaged, backup intact

        val read = s.ensure("p")
        assertTrue(read is SessionIndexRead.Ok)
        val index = (read as SessionIndexRead.Ok).index
        assertTrue("the survivor must still be there", index.contains(survivor.id))
        assertFalse("a backup must not override the deletion record", index.contains(doomed.id))
    }

    // ---------------------------------------------------------------- 4. durability across restart

    @Test
    fun `the deletion record survives a restart and still excludes the session`() {
        val s = store()
        val meta = create(s)
        transcripts.put("p", meta.id, "gone but present")
        transcripts.failDeleteWith = "EIO"
        s.delete("p", meta.id)

        // A brand new store instance, as after a process restart.
        val restarted = store()
        assertTrue("the record is on disk, not in memory", restarted.isDeleted("p", meta.id))

        forceRebuild(restarted)
        val recovered = restarted.ensure("p") as SessionIndexRead.Ok
        assertFalse(recovered.index.contains(meta.id))
    }

    @Test
    fun `opening a project whose active conversation was deleted starts a fresh one`() {
        val s = store()
        // The legacy "main" conversation, adopted on first open.
        transcripts.put("p", SessionMeta.LEGACY_ID, "old conversation")
        val first = s.ensure("p") as SessionIndexRead.Ok
        assertEquals(SessionMeta.LEGACY_ID, first.index.activeId)

        transcripts.failDeleteWith = "EIO"
        s.delete("p", SessionMeta.LEGACY_ID)
        // The list is now empty, so the next open would fall back to "main" and reopen the
        // leftover transcript - the resurrection path that does not involve a rebuild at all.
        val reopened = s.ensure("p") as SessionIndexRead.Ok

        assertNotNull("the user must be told what happened", reopened.note)
        val active = reopened.index.activeId
        assertNotNull("a conversation must still be open", active)
        assertTrue("it must not be the deleted one", active != SessionMeta.LEGACY_ID)
        assertEquals("exactly one conversation is open, the new one", 1, reopened.index.sessions.size)
    }

    // ---------------------------------------------------------------- 5. id reuse

    @Test
    fun `a new session never inherits a deleted id`() {
        // Force the collision: the store's generator is scripted to produce the SAME id the
        // deleted session had, then a different one.
        val doomedId = "s_" + "a".repeat(16)
        val freshId = "s_" + "b".repeat(16)
        assertEquals(doomedId, SessionIds.newId(DigitRandom(List(16) { 10 })))
        assertEquals(freshId, SessionIds.newId(DigitRandom(List(16) { 11 })))

        val first = store(DigitRandom(List(16) { 10 }))
        val created = create(first)
        assertEquals(doomedId, created.id)
        transcripts.put("p", doomedId, "the deleted conversation")
        transcripts.failDeleteWith = "EIO"
        first.delete("p", doomedId)
        assertTrue(first.isDeleted("p", doomedId))

        // Now the generator WANTS to hand out the deleted id.
        val scripted = store(DigitRandom(List(16) { 10 } + List(16) { 11 }))
        val second = secondCreateOrNull(scripted)

        assertNotNull("a fresh id must be found", second)
        assertEquals("the deleted id must be skipped", freshId, second!!.id)
        assertFalse("the new id carries no deletion record", scripted.isDeleted("p", freshId))
        // And the new session must not open the old conversation's bytes.
        assertFalse(scripted.contains("p", doomedId))
        assertEquals(1, (scripted.read("p") as SessionIndexRead.Ok).index.sessions.count { it.id == freshId })
    }

    private fun secondCreateOrNull(store: SessionIndexStore): SessionMeta? =
        when (val c = store.create("p")) {
            is SessionCreate.Created -> c.meta
            is SessionCreate.Failed -> null
        }

    // ---------------------------------------------------------------- 6. isolation

    @Test
    fun `project A's deletion record has no effect on project B`() {
        val digits = List(16) { 11 }
        val sharedId = "s_" + "b".repeat(16)
        assertEquals(sharedId, SessionIds.newId(DigitRandom(digits)))

        val s = store(DigitRandom(digits))
        val inA = create(s, "project-a")
        transcripts.put("project-a", inA.id, "A's conversation")
        transcripts.put("project-b", inA.id, "B's own conversation")

        // Give B a real record for the same id, and a valid index that names it.
        val bMeta = SessionMeta(inA.id, "B session", 10L, 20L)
        s.save("project-b", SessionIndex(listOf(bMeta), inA.id))

        transcripts.failDeleteWith = "EIO"
        s.delete("project-a", inA.id)

        assertTrue(s.isDeleted("project-a", inA.id))
        assertFalse("the record must not leak", s.isDeleted("project-b", inA.id))
        assertTrue(s.contains("project-b", inA.id))

        // B's rebuild keeps its session; A's rebuild excludes its own.
        forceRebuild(s, "project-b")
        forceRebuild(s, "project-a")
        assertEquals(setOf(inA.id), (s.ensure("project-b") as SessionIndexRead.Ok).index.sessions.map { it.id }.toSet())
        assertTrue((s.ensure("project-a") as SessionIndexRead.Ok).index.sessions.isEmpty())
    }

    // ---------------------------------------------------------------- 7. damage

    @Test
    fun `a damaged deletion record refuses the rebuild instead of guessing`() {
        val s = store()
        val meta = create(s)
        transcripts.put("p", meta.id, "hello")
        forceRebuild(s)                                // force a rebuild
        s.deletionsFileFor("p").writeText("not a log") // ... with an unreadable record

        val result = s.ensure("p")

        assertTrue("rebuilding without the record is how sessions come back", result is SessionIndexRead.Unreadable)
        assertTrue((result as SessionIndexRead.Unreadable).reason.contains("deletion record"))
        // Nothing was written, so the damaged index is still there to be repaired.
        assertEquals("garbage", s.fileFor("p").readText())
        // And creating is refused too, rather than minting an id that might be a deleted one.
        assertTrue(s.create("p") is SessionCreate.Failed)
    }

    @Test
    fun `a failed index write during delete withdraws the record again`() {
        val s = store()
        val meta = create(s)
        transcripts.put("p", meta.id, "hello")
        // Read still works (the index file is intact) but the atomic write cannot land, because
        // its temp path is a directory. That is the "metadata write failed" branch.
        assertTrue(File(s.fileFor("p").parentFile, SessionIndexStore.FILE_NAME + SessionIndexStore.TEMP_SUFFIX).mkdirs())

        val result = s.delete("p", meta.id)

        assertTrue(result is SessionDelete.Failed)
        assertFalse("a deletion that did not happen must not stay recorded", s.isDeleted("p", meta.id))
        assertTrue(s.contains("p", meta.id))
        assertTrue(transcripts.has("p", meta.id))
    }

    // ---------------------------------------------------------------- 8. cleanup

    @Test
    fun `stale records are pruned only when their bytes are confirmed gone`() {
        val s = store()
        val clean = create(s)     // bytes will be removed properly
        val stuck = create(s)     // bytes will remain
        val recent = create(s)    // bytes removed, but too recent to prune

        transcripts.put("p", clean.id, "clean")
        transcripts.put("p", stuck.id, "stuck")
        transcripts.put("p", recent.id, "recent")
        s.delete("p", clean.id)

        transcripts.failDeleteWith = "EIO"
        s.delete("p", stuck.id)
        transcripts.failDeleteWith = null

        now = 11_500L
        s.delete("p", recent.id)   // deleted much later than the other two

        // Prune with no age requirement at all: only the record whose bytes are confirmed gone
        // AND that is old enough may go.
        val window = 5_000L
        now = 12_000L
        val pruned = s.pruneDeletions("p", olderThanMs = window)

        assertEquals(1, pruned.removed)
        assertEquals(2, pruned.kept)
        assertNull(pruned.failure)
        assertFalse("bytes gone, old enough", s.isDeleted("p", clean.id))
        assertTrue("bytes still on disk: the record must stay", s.isDeleted("p", stuck.id))
        assertTrue("too recent", s.isDeleted("p", recent.id))
        assertTrue("pruning must never delete a transcript", transcripts.has("p", stuck.id))
    }

    @Test
    fun `pruning keeps every record while its bytes are still on disk, however old`() {
        val s = store()
        val meta = create(s)
        transcripts.put("p", meta.id, "ancient")
        transcripts.failDeleteWith = "EIO"
        s.delete("p", meta.id)

        now = 5_000L + SessionIndexStore.STALE_AFTER_MS * 10L
        val pruned = s.pruneDeletions("p")

        assertEquals(0, pruned.removed)
        assertEquals(1, pruned.kept)
        assertTrue(s.isDeleted("p", meta.id))
        assertTrue(transcripts.has("p", meta.id))
    }

    @Test
    fun `resetting the record is an explicit repair with a stated consequence`() {
        val s = store()
        val meta = create(s)
        transcripts.put("p", meta.id, "may come back")
        transcripts.failDeleteWith = "EIO"
        s.delete("p", meta.id)

        assertTrue(s.resetDeletions("p") is SessionWrite.Ok)
        assertFalse(s.isDeleted("p", meta.id))

        // The consequence, asserted rather than hidden: the bytes are now visible to a rebuild.
        forceRebuild(s)
        val rebuilt = s.ensure("p") as SessionIndexRead.Ok
        assertTrue("after an explicit reset the leftover bytes are rebuilt", rebuilt.index.contains(meta.id))
    }


    // ---------------------------------------------------------------- 9. retrying pending bytes

    @Test
    fun `retrying pending deletions removes the bytes and upgrades the record`() {
        val s = store()
        val meta = create(s)
        transcripts.put("p", meta.id, "left behind")
        transcripts.failDeleteWith = "EIO"
        s.delete("p", meta.id)
        assertEquals(false, log(s).find(meta.id)!!.bytesRemoved)

        transcripts.failDeleteWith = null
        val retry = s.retryPendingDeletions("p")

        assertEquals(1, retry.retried)
        assertEquals(listOf(meta.id), retry.cleared)
        assertTrue(retry.failed.isEmpty())
        assertNull(retry.failure)
        assertFalse("the bytes are actually gone", transcripts.has("p", meta.id))
        assertEquals("the record is upgraded only now", true, log(s).find(meta.id)!!.bytesRemoved)
        // Still excluded from the project, and from any rebuild.
        assertFalse(s.contains("p", meta.id))
        assertTrue(s.isDeleted("p", meta.id))

        // And now the record is prunable, which it never was while the bytes remained.
        assertEquals(1, s.pruneDeletions("p", olderThanMs = 0L).removed)
        assertFalse(s.isDeleted("p", meta.id))
    }

    @Test
    fun `a retry that fails again preserves the record and reports the failure`() {
        val s = store()
        val meta = create(s)
        transcripts.put("p", meta.id, "stuck")
        transcripts.failDeleteWith = "EACCES"
        s.delete("p", meta.id)

        val first = s.retryPendingDeletions("p")
        val second = s.retryPendingDeletions("p")

        for (r in listOf(first, second)) {
            assertEquals(1, r.retried)
            assertTrue(r.cleared.isEmpty())
            assertEquals(1, r.failed.size)
            assertEquals(meta.id, r.failed.single().id)
            assertTrue(r.failed.single().reason.contains("EACCES"))
            assertNull(r.failure)
        }
        assertTrue("nothing was discarded", transcripts.has("p", meta.id))
        assertEquals("the record must survive repeated failure", false, log(s).find(meta.id)!!.bytesRemoved)
        assertTrue(s.isDeleted("p", meta.id))
    }

    @Test
    fun `a retried deletion survives a restart and still cannot be resurrected`() {
        val s = store()
        val meta = create(s)
        transcripts.put("p", meta.id, "was stuck")
        transcripts.failDeleteWith = "EIO"
        s.delete("p", meta.id)
        transcripts.failDeleteWith = null
        assertEquals(listOf(meta.id), s.retryPendingDeletions("p").cleared)

        // A cold start reads the upgraded record from disk.
        val restarted = store()
        assertEquals(true, log(restarted).find(meta.id)!!.bytesRemoved)
        assertFalse(transcripts.has("p", meta.id))

        forceRebuild(restarted)
        val recovered = restarted.ensure("p") as SessionIndexRead.Ok
        assertFalse(recovered.index.contains(meta.id))
    }

    @Test
    fun `retrying is scoped to one project and never runs on open`() {
        val digits = List(16) { 12 }
        val sharedId = "s_" + "c".repeat(16)
        assertEquals(sharedId, SessionIds.newId(DigitRandom(digits)))

        val s = store(DigitRandom(digits))
        val inA = create(s, "project-a")
        transcripts.put("project-a", inA.id, "A stuck")
        transcripts.put("project-b", inA.id, "B stuck")

        // A pending record and a valid index in BOTH projects, for the SAME id.
        s.save("project-b", SessionIndex(listOf(SessionMeta(inA.id, "B", 1L, 2L)), inA.id))
        s.deletionsFileFor("project-b").writeText(
            DeletionLogCodec.encode(DeletionLog(listOf(DeletionRecord(inA.id, 10L, bytesRemoved = false)))),
        )

        transcripts.failDeleteWith = "EIO"
        s.delete("project-a", inA.id)

        // Opening and reading must not retry anything.
        s.ensure("project-a")
        s.read("project-a")
        s.ensure("project-b")
        assertEquals(false, log(s, "project-a").find(inA.id)!!.bytesRemoved)
        assertEquals(false, log(s, "project-b").find(inA.id)!!.bytesRemoved)
        assertTrue(transcripts.has("project-a", inA.id))
        assertTrue(transcripts.has("project-b", inA.id))

        // Retrying A touches A only.
        transcripts.failDeleteWith = null
        val retryA = s.retryPendingDeletions("project-a")

        assertEquals(listOf(inA.id), retryA.cleared)
        assertFalse(transcripts.has("project-a", inA.id))
        assertTrue("project B's bytes must be untouched", transcripts.has("project-b", inA.id))
        assertEquals(
            "project B's record must be untouched",
            false,
            log(s, "project-b").find(inA.id)!!.bytesRemoved,
        )
        // B is hidden by ITS OWN record, not by A's. Retrying B reclaims B's bytes on its own,
        // which is the real proof that the two projects were never coupled.
        assertEquals(false, s.contains("project-b", inA.id))
        val retryB = s.retryPendingDeletions("project-b")
        assertEquals(listOf(inA.id), retryB.cleared)
        assertFalse(transcripts.has("project-b", inA.id))
        assertEquals(true, log(s, "project-b").find(inA.id)!!.bytesRemoved)
    }

    // ---------------------------------------------------------------- codec

    @Test
    fun `the record round trips and refuses to read damage as an empty record`() {
        val log = DeletionLog(
            listOf(
                DeletionRecord("s_one", 10L, bytesRemoved = true),
                DeletionRecord("s_two", 20L, bytesRemoved = false),
            ),
        )
        val decoded = DeletionLogCodec.decode(DeletionLogCodec.encode(log))
        assertTrue(decoded is DeletionLogRead.Ok)
        assertEquals(log, (decoded as DeletionLogRead.Ok).log)

        assertTrue(DeletionLogCodec.decode("") is DeletionLogRead.Unreadable)
        assertTrue(DeletionLogCodec.decode("garbage") is DeletionLogRead.Unreadable)
        assertTrue(DeletionLogCodec.decode(DeletionLogCodec.FORMAT_VERSION + "\nd\ts_1") is DeletionLogRead.Unreadable)
        assertTrue(
            DeletionLogCodec.decode(DeletionLogCodec.FORMAT_VERSION + "\nd\tbad id\t1\t0\n") is DeletionLogRead.Unreadable,
        )
        assertTrue(
            DeletionLogCodec.decode(DeletionLogCodec.FORMAT_VERSION + "\nd\ts_1\t1\t2\n") is DeletionLogRead.Unreadable,
        )
        assertTrue(
            DeletionLogCodec.decode(
                DeletionLogCodec.FORMAT_VERSION + "\nd\ts_1\t1\t0\nd\ts_1\t2\t0\n",
            ) is DeletionLogRead.Unreadable,
        )
    }
}
