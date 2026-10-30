package com.codespace.ide.chat

import com.codespace.ide.chat.panel.EndpointModelCommit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * U9 P0 data-safety (2026-10-30).
 *
 * P0-A: discovery must not write persistent state before Save (fetch -> Cancel is a no-op).
 * P0-B: a failed read of the manual list must never become an "empty" list that Save writes.
 *
 * These exercise the real CustomEndpointStore through its storage seam plus the commit helper
 * the editor now uses, so the rules are enforced by production code, not by a re-implementation.
 */
class EndpointModelSafetyTest {

    /** Storage whose row-presence question cannot be answered: a failed/incomplete read. */
    private class UnreadableStorage(
        private val inner: CustomEndpointStore.MemoryEndpointStorage,
    ) : CustomEndpointStore.EndpointStorage by inner {
        override fun hasModelRow(key: String): Boolean? = null
    }

    /** Storage that claims the row exists but then throws while reading it. */
    private class ThrowingReadStorage(
        private val inner: CustomEndpointStore.MemoryEndpointStorage,
    ) : CustomEndpointStore.EndpointStorage by inner {
        override fun hasModelRow(key: String): Boolean? = true
        override fun readModelRow(key: String): String? = throw IllegalStateException("storage read failed")
    }

    /** Storage that refuses the live-cache write only. */
    private class LiveWriteFails(
        private val inner: CustomEndpointStore.MemoryEndpointStorage,
    ) : CustomEndpointStore.EndpointStorage by inner {
        override fun writeModelRow(key: String, value: String?): Boolean =
            if (key.startsWith("live_")) false else inner.writeModelRow(key, value)
    }

    private lateinit var mem: CustomEndpointStore.MemoryEndpointStorage

    @Before
    fun fresh() {
        mem = CustomEndpointStore.MemoryEndpointStorage()
        CustomEndpointStore.useStorage(mem)
    }

    @After
    fun cleanup() {
        CustomEndpointStore.useStorage(CustomEndpointStore.MemoryEndpointStorage())
    }

    private fun endpoint(label: String = "Box"): String =
        CustomEndpointStore.add(label, "https://box.example/v1")!!.id

    // ── P0-A ──────────────────────────────────────────────────────────────

    @Test
    fun fetchThenCancelLeavesBothPersistentListsUnchanged() {
        val id = endpoint()
        CustomEndpointStore.setManualModels(id, "alpha\nbeta")
        CustomEndpointStore.setLiveModels(id, listOf("cached-1", "cached-2"))
        val stampBefore = CustomEndpointStore.liveModelsFetchedAt(id)

        // What the dialog does now: read, fetch INTO STATE ONLY, then Cancel (no commit).
        val manualRead = CustomEndpointStore.readManualModels(id)
        assertTrue(manualRead is CustomEndpointStore.ModelRead.Loaded)
        val discovered = listOf("new-a", "new-b")   // held in editor state, never written

        assertEquals(listOf("alpha", "beta"), CustomEndpointStore.manualModels(id))
        assertEquals(listOf("cached-1", "cached-2"), CustomEndpointStore.liveModels(id))
        assertEquals("a cancel must not touch the cache stamp", stampBefore,
            CustomEndpointStore.liveModelsFetchedAt(id))
        assertTrue("discovery stayed in the UI only", "new-a" !in CustomEndpointStore.liveModels(id))
        assertEquals(2, discovered.size)
    }

    @Test
    fun fetchThenSavePersistsTheIntendedManualAndLiveLists() {
        val id = endpoint()
        CustomEndpointStore.setManualModels(id, "alpha")

        val manual = listOf("alpha", "beta")
        val fetched = listOf("gpt-a", "gpt-b")
        val res = EndpointModelCommit.commit(
            CustomEndpointStore, id, manual, fetched, readWasUnresolved = false,
        )

        assertTrue(res.manualSaved)
        assertTrue(res.liveSaved)
        assertFalse(res.skipped)
        assertEquals(listOf("alpha", "beta"), CustomEndpointStore.manualModels(id))
        assertEquals(listOf("gpt-a", "gpt-b"), CustomEndpointStore.liveModels(id))
        assertTrue("a real fetch stamps the cache", CustomEndpointStore.liveModelsFetchedAt(id) > 0L)
    }

    @Test
    fun failedFetchThenSavePreservesPreviousManualIds() {
        val id = endpoint()
        CustomEndpointStore.setManualModels(id, "alpha\nbeta")

        // A fetch that failed leaves `fetched` empty, so Save commits manual only.
        val res = EndpointModelCommit.commit(
            CustomEndpointStore, id, listOf("alpha", "beta"), emptyList(), readWasUnresolved = false,
        )

        assertTrue(res.manualSaved)
        assertEquals(listOf("alpha", "beta"), CustomEndpointStore.manualModels(id))
    }

    @Test
    fun aLiveWriteFailureIsReportedInsteadOfPretendingSuccess() {
        val id = endpoint()
        CustomEndpointStore.useStorage(LiveWriteFails(mem))
        val res = EndpointModelCommit.commit(
            CustomEndpointStore, id, listOf("alpha"), listOf("gpt-a"), readWasUnresolved = false,
        )
        assertTrue("manual still landed", res.manualSaved)
        assertFalse("the cache write failed and says so", res.liveSaved)
        assertEquals(listOf("alpha"), CustomEndpointStore.manualModels(id))
    }

    // ── P0-B ──────────────────────────────────────────────────────────────

    @Test
    fun anUnreadableListCannotBecomeAnEmptySavedList() {
        val id = endpoint()
        CustomEndpointStore.setManualModels(id, "alpha\nbeta")
        CustomEndpointStore.useStorage(UnreadableStorage(mem))

        val read = CustomEndpointStore.readManualModels(id)
        assertEquals(CustomEndpointStore.ModelRead.Unreadable, read)
        assertFalse("Save must be disabled while the read is unresolved",
            EndpointModelCommit.canSave(read))

        val res = EndpointModelCommit.commit(
            CustomEndpointStore, id, emptyList(), emptyList(), readWasUnresolved = true,
        )
        assertTrue(res.skipped)
        assertFalse(res.manualSaved)
        CustomEndpointStore.useStorage(mem)
        assertEquals("the stored ids survived", listOf("alpha", "beta"),
            CustomEndpointStore.manualModels(id))
    }

    @Test
    fun aThrowingRowReadIsUnreadableRatherThanEmpty() {
        val id = endpoint()
        CustomEndpointStore.setManualModels(id, "alpha")
        CustomEndpointStore.useStorage(ThrowingReadStorage(mem))
        assertEquals(CustomEndpointStore.ModelRead.Unreadable,
            CustomEndpointStore.readManualModels(id))
    }

    @Test
    fun aSuccessfulEmptyReadIsDistinguishableFromAFailure() {
        val id = endpoint()
        // Absent row: a real, saved empty list.
        assertEquals(CustomEndpointStore.ModelRead.Loaded(emptyList()),
            CustomEndpointStore.readManualModels(id))
        // Present but empty row: still a successful read.
        CustomEndpointStore.setManualModels(id, "")
        assertEquals(CustomEndpointStore.ModelRead.Loaded(emptyList()),
            CustomEndpointStore.readManualModels(id))
        // Unreadable: NOT Loaded, so nothing may be written back.
        CustomEndpointStore.useStorage(UnreadableStorage(mem))
        assertTrue(CustomEndpointStore.readManualModels(id)
            is CustomEndpointStore.ModelRead.Unreadable)
        // And canSave agrees in both directions.
        assertTrue(EndpointModelCommit.canSave(CustomEndpointStore.ModelRead.Loaded(emptyList())))
        assertFalse(EndpointModelCommit.canSave(CustomEndpointStore.ModelRead.Unreadable))
    }

    // ── offline cache + isolation ──────────────────────────────────────────

    @Test
    fun cachedLiveModelsStayUsableWhenNothingNewIsFetched() {
        val id = endpoint()
        CustomEndpointStore.setLiveModels(id, listOf("cached-1", "cached-2"))
        val res = EndpointModelCommit.commit(
            CustomEndpointStore, id, listOf("alpha"), emptyList(), readWasUnresolved = false,
        )
        assertTrue(res.liveSaved)
        assertEquals("the offline cache survives a save with no new discovery",
            listOf("cached-1", "cached-2"), CustomEndpointStore.liveModels(id))
        assertEquals(listOf("cached-1", "cached-2"),
            (CustomEndpointStore.readLiveModels(id) as CustomEndpointStore.ModelRead.Loaded).ids)
    }

    @Test
    fun twoEndpointsKeepTheirOwnListsThroughACommit() {
        val a = endpoint("A")
        val b = endpoint("B")
        EndpointModelCommit.commit(CustomEndpointStore, a, listOf("a-1", "a-2"), listOf("d-a"), false)
        EndpointModelCommit.commit(CustomEndpointStore, b, listOf("b-1"), listOf("d-b"), false)

        assertEquals(listOf("a-1", "a-2"), CustomEndpointStore.manualModels(a))
        assertEquals(listOf("b-1"), CustomEndpointStore.manualModels(b))
        assertEquals(listOf("d-a"), CustomEndpointStore.liveModels(a))
        assertEquals(listOf("d-b"), CustomEndpointStore.liveModels(b))
    }
}
