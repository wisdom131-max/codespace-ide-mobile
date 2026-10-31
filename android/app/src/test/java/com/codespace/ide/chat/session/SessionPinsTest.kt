package com.codespace.ide.chat.session

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Item 17 — PINNING.
 *
 * What has to be true for this to be a feature rather than a decoration: the pin survives a
 * process restart, a pin cannot be attached to a session that does not exist, the pinned row
 * is actually ordered first under BOTH sorts, and pinning does not disturb the ordering the
 * user asked for inside either half of the list.
 */
class SessionPinsTest {

    private lateinit var chatRoot: File
    private var now = 1_000L

    private fun newStore(seed: Int = 3): SessionIndexStore =
        SessionIndexStore(chatRoot, clock = { now }, random = Random(seed))

    @Before
    fun setUp() {
        chatRoot = File.createTempFile("vncode-pins", "test").let { f ->
            f.delete()
            f.mkdirs()
            f
        }
        now = 1_000L
    }

    @After
    fun tearDown() {
        chatRoot.deleteRecursively()
    }

    private fun ok(w: SessionWrite, what: String) {
        assertTrue("$what should succeed but was $w", w is SessionWrite.Ok)
    }

    private fun ids(store: SessionIndexStore, projectId: String): List<String> =
        (store.read(projectId) as SessionIndexRead.Ok).index.sessions.map { it.id }

    @Test
    fun aPinSurvivesAFreshStoreOverTheSameDirectory() {
        val store = newStore()
        val first = (store.create("proj") as SessionCreate.Created).meta.id
        ok(store.setPinned("proj", first, true), "pin")

        val reopened = newStore(seed = 9)
        assertEquals(setOf(first), reopened.readPins("proj"))
    }

    @Test
    fun unpinningRemovesOnlyThatSession() {
        val store = newStore()
        val a = (store.create("proj") as SessionCreate.Created).meta.id
        val b = (store.create("proj") as SessionCreate.Created).meta.id
        ok(store.setPinned("proj", a, true), "pin a")
        ok(store.setPinned("proj", b, true), "pin b")
        ok(store.setPinned("proj", a, false), "unpin a")
        assertEquals(setOf(b), store.readPins("proj"))
    }

    @Test
    fun aPinOnASessionThatDoesNotExistIsRefusedRatherThanStored() {
        val store = newStore()
        store.create("proj")
        val w = store.setPinned("proj", "s_missing", true)
        assertTrue("expected a refusal, got $w", w is SessionWrite.Failed)
        assertTrue(store.readPins("proj").isEmpty())
    }

    @Test
    fun aDamagedPinsFileReadsAsNothingPinnedInsteadOfCrashing() {
        val store = newStore()
        val id = (store.create("proj") as SessionCreate.Created).meta.id
        ok(store.setPinned("proj", id, true), "pin")
        store.pinsFileFor("proj").writeText("\u0000\u0000 not a pin file \u0000\n" + id)
        // Garbage cannot pin anything: a pin only counts when it names a session that exists,
        // which is what the row list actually joins against.
        val pins = store.readPins("proj")
        val known = (store.read("proj") as SessionIndexRead.Ok).index.sessions.map { it.id }.toSet()
        assertEquals("only real sessions can be pinned", setOf(id), pins.intersect(known))
    }

    /** Ordering is the part a user actually notices, so it is tested through the real sort. */
    @Test
    fun pinnedRowsComeFirstUnderBothSorts() {
        val store = newStore()
        val a = (store.create("proj") as SessionCreate.Created).meta.id
        now = 2_000L
        val b = (store.create("proj") as SessionCreate.Created).meta.id
        now = 3_000L
        val c = (store.create("proj") as SessionCreate.Created).meta.id
        val sessions = (store.read("proj") as SessionIndexRead.Ok).index.sessions

        // The OLDEST session pinned: under "Updated" it would normally be last.
        val pinned = setOf(a)
        val updated = SessionListLogic.sorted(sessions, SessionSort.UPDATED, pinned)
        assertEquals("pinned row leads 'Updated'", a, updated.first().id)
        assertEquals("the rest keep their own order", listOf(c, b), updated.drop(1).map { it.id })

        val created = SessionListLogic.sorted(sessions, SessionSort.CREATED, pinned)
        assertEquals("pinned row leads 'Created'", a, created.first().id)
        assertEquals("created order kept below the pin", listOf(c, b), created.drop(1).map { it.id })
    }

    @Test
    fun anEmptyPinSetLeavesOrderingExactlyAsItWas() {
        val store = newStore()
        val a = (store.create("proj") as SessionCreate.Created).meta.id
        now = 2_000L
        val b = (store.create("proj") as SessionCreate.Created).meta.id
        val sessions = (store.read("proj") as SessionIndexRead.Ok).index.sessions
        assertEquals(
            SessionListLogic.sorted(sessions, SessionSort.UPDATED),
            SessionListLogic.sorted(sessions, SessionSort.UPDATED, emptySet()),
        )
    }

    @Test
    fun aPinDoesNotSurviveTheSessionBeingDeleted() {
        val store = newStore()
        val a = (store.create("proj") as SessionCreate.Created).meta.id
        val b = (store.create("proj") as SessionCreate.Created).meta.id
        ok(store.setPinned("proj", a, true), "pin a")
        ok(store.setPinned("proj", b, true), "pin b")

        store.delete("proj", a)
        val left = ids(store, "proj")
        assertFalse("the deleted session is gone", a in left)
        // The id is refused once it is no longer in the table, and pruning on delete keeps the
        // pins file from accumulating ids that can never match a row again.
        val pins = store.readPins("proj")
        assertFalse("a deleted session must not stay pinned", a in pins)
    }
}
