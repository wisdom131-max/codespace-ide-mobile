package com.codespace.ide.chat.session

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Item 18 — the mode survives the process.
 *
 * The reported bug: choosing Agent (or Edit) and reopening the app dropped back to Ask, so the
 * mode read as unreliable. These tests pin the two halves of the fix that can regress silently:
 * the mode is stored per SESSION, and an existing v1 index keeps working after the format grows.
 */
class SessionModePersistenceTest {

    private lateinit var chatRoot: File
    private var now = 5_000L

    private fun newStore(seed: Int = 3): SessionIndexStore =
        SessionIndexStore(chatRoot, clock = { now }, random = Random(seed))

    @Before
    fun setUp() {
        chatRoot = File.createTempFile("vncode-modes", "test").let { f ->
            f.delete()
            f.mkdirs()
            f
        }
        now = 5_000L
    }

    @After
    fun tearDown() {
        chatRoot.deleteRecursively()
    }

    private fun seedSession(project: String = "proj"): Pair<SessionIndexStore, String> {
        val store = newStore()
        store.ensure(project)
        val created = store.create(project)
        val id = (created as SessionCreate.Created).meta.id
        return store to id
    }

    @Test
    fun aNewSessionStartsInAsk() {
        val (store, id) = seedSession()
        val index = (store.read("proj") as SessionIndexRead.Ok).index
        assertEquals(SessionMode.ASK, index.find(id)!!.mode)
    }

    @Test
    fun theChosenModeIsStillThereAfterTheProcessRestarts() {
        val (store, id) = seedSession()
        assertTrue(store.setMode("proj", id, SessionMode.AGENT) is SessionWrite.Ok)
        // A brand-new store over the same directory is what a restart looks like.
        val restarted = newStore().read("proj")
        assertTrue(restarted is SessionIndexRead.Ok)
        val meta = (restarted as SessionIndexRead.Ok).index.find(id)!!
        assertEquals(SessionMode.AGENT, meta.mode)
    }

    @Test
    fun eachSessionKeepsItsOwnModeRatherThanOneSharedDefault() {
        val (store, askId) = seedSession()
        val created = store.create("proj")
        val agentId = (created as SessionCreate.Created).meta.id
        store.setMode("proj", agentId, SessionMode.AGENT)
        store.setMode("proj", askId, SessionMode.EDIT)
        val index = (store.read("proj") as SessionIndexRead.Ok).index
        assertEquals(SessionMode.EDIT, index.find(askId)!!.mode)
        assertEquals(SessionMode.AGENT, index.find(agentId)!!.mode)
        val third = (store.create("proj") as SessionCreate.Created).meta.id
        val after = (store.read("proj") as SessionIndexRead.Ok).index
        assertEquals("a session nobody switched stays in Ask", SessionMode.ASK, after.find(third)!!.mode)
    }

    @Test
    fun settingTheModeItAlreadyHasIsANoOpNotAWrite() {
        val (store, id) = seedSession()
        assertTrue(store.setMode("proj", id, SessionMode.ASK) is SessionWrite.Ok)
    }

    @Test
    fun aModeForAnUnknownSessionFailsInsteadOfInventingOne() {
        val (store, _) = seedSession()
        val write = store.setMode("proj", "nope", SessionMode.AGENT)
        assertTrue(write is SessionWrite.Failed)
    }

    @Test
    fun aV1IndexStillReadsAndDefaultsToAsk() {
        val legacy = buildString {
            append("vsessions 1\n")
            append("active\tmain\n")
            append("s\tmain\tOld session\t1000\t2000\t0\t0\n")
        }
        val read = SessionIndexCodec.decode(legacy)
        assertTrue("a v1 file must not become Unreadable: " + read, read is SessionIndexRead.Ok)
        val meta = (read as SessionIndexRead.Ok).index.find("main")!!
        assertEquals("Old session", meta.title)
        assertEquals(SessionMode.ASK, meta.mode)
    }

    @Test
    fun anUnknownModeTokenFallsBackToAskAndKeepsTheConversation() {
        val future = buildString {
            append("vsessions 2\n")
            append("active\tmain\n")
            append("s\tmain\tKept\t1000\t2000\t0\t0\twarp\n")
        }
        val read = SessionIndexCodec.decode(future)
        assertTrue("an unknown mode must not discard every session: " + read, read is SessionIndexRead.Ok)
        assertEquals(SessionMode.ASK, (read as SessionIndexRead.Ok).index.find("main")!!.mode)
    }

    @Test
    fun theModeSurvivesAnEncodeDecodeRoundTrip() {
        val index = SessionIndex(
            sessions = listOf(
                SessionMeta("a", "Agent chat", 10L, 20L, false, 20L, SessionMode.AGENT),
                SessionMeta("b", "Edit chat", 10L, 20L, false, 0L, SessionMode.EDIT),
            ),
            activeId = "a",
        )
        val back = SessionIndexCodec.decode(SessionIndexCodec.encode(index))
        assertTrue(back is SessionIndexRead.Ok)
        val sessions = (back as SessionIndexRead.Ok).index.sessions
        assertEquals(SessionMode.AGENT, sessions[0].mode)
        assertEquals(SessionMode.EDIT, sessions[1].mode)
    }
}
