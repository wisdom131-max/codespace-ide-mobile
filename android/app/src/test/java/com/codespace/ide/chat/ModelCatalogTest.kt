package com.codespace.ide.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MK-RESTRUCTURE PHASE B JVM suite (advisor condition d, 2026-10-07): the
 * merge/dedupe semantics, catalog assembly, group split, and refresh-failure
 * cache preservation — all pure Kotlin, run in the CI unit-test step next to
 * the shell-integration suite.
 */
class ModelCatalogTest {

    private class FakeEndpoint(val id: String, val label: String, val baseUrl: String)

    /** Mutable fake store — tests assert the cache is untouched on failure. */
    private class FakeStore(
        var endpoints: List<FakeEndpoint> = emptyList(),
        var manual: MutableMap<String, List<String>> = mutableMapOf(),
        var live: MutableMap<String, List<String>> = mutableMapOf(),
        var fetchedAt: MutableMap<String, Long> = mutableMapOf(),
    ) : ModelCatalog.StoreReader {
        override fun list(): List<CustomEndpointStore.Endpoint> =
            endpoints.map { CustomEndpointStore.Endpoint(it.id, it.label, it.baseUrl) }
        override fun manualModels(id: String): List<String> = manual[id] ?: emptyList()
        override fun liveModels(id: String): List<String> = live[id] ?: emptyList()
        override fun liveModelsFetchedAt(id: String): Long = fetchedAt[id] ?: 0L
    }

    // ── mergeDistinct ──────────────────────────────────────────────────────

    @Test
    fun `manual entries come first, live appended, no duplicates`() {
        val merged = ModelCatalog.mergeDistinct(
            manual = listOf("m1", "m2"),
            live = listOf("l1", "m1", "l2"),
        )
        assertEquals(listOf("m1", "m2", "l1", "l2"), merged)
    }

    @Test
    fun `empty live keeps manual only and vice versa`() {
        assertEquals(listOf("a"), ModelCatalog.mergeDistinct(listOf("a"), emptyList()))
        assertEquals(listOf("b"), ModelCatalog.mergeDistinct(emptyList(), listOf("b")))
        assertTrue(ModelCatalog.mergeDistinct(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `order is preserved within each group`() {
        val merged = ModelCatalog.mergeDistinct(
            manual = listOf("z", "a"),
            live = listOf("q", "b"),
        )
        assertEquals(listOf("z", "a", "q", "b"), merged)
    }

    // ── catalog assembly ───────────────────────────────────────────────────

    @Test
    fun `catalog assembles per-endpoint manual live and fetchedAt`() {
        val store = FakeStore(
            endpoints = listOf(FakeEndpoint("ep1", "My LLM", "https://x")),
            manual = mutableMapOf("ep1" to listOf("manual-model")),
            live = mutableMapOf("ep1" to listOf("live-model")),
            fetchedAt = mutableMapOf("ep1" to 42L),
        )
        val cat = ModelCatalog.catalog(store)
        assertEquals(1, cat.size)
        val ep = cat[0]
        assertEquals("ep1", ep.endpointId)
        assertEquals("My LLM", ep.label)
        assertEquals(listOf("manual-model"), ep.manual)
        assertEquals(listOf("live-model"), ep.live)
        assertEquals(42L, ep.liveFetchedAt)
        assertEquals(listOf("manual-model", "live-model"), ep.merged)
    }

    @Test
    fun `registry order is preserved in catalog`() {
        val store = FakeStore(
            endpoints = listOf(
                FakeEndpoint("b", "B", "https://b"),
                FakeEndpoint("a", "A", "https://a"),
            ),
        )
        assertEquals(listOf("b", "a"), ModelCatalog.catalog(store).map { it.endpointId })
    }

    // ── group split (picker groups) ────────────────────────────────────────

    @Test
    fun `groupEntries splits provider-scoped entries into manual and live`() {
        val store = FakeStore(
            endpoints = listOf(FakeEndpoint("ep1", "My LLM", "https://x")),
            manual = mutableMapOf("ep1" to listOf("m1")),
        )
        // providerIdFor("ep1") = "custom_ep1"; another provider's entries are ignored.
        val entries = listOf("custom_ep1:m1", "custom_ep1:l1", "custom_ep2:other", "openai:gpt")
        val groups = ModelCatalog.groupEntries(entries, store)
        assertEquals(1, groups.size)
        assertEquals("ep1", groups[0].endpointId)
        assertEquals(listOf("custom_ep1:m1"), groups[0].manual)
        assertEquals(listOf("custom_ep1:l1"), groups[0].live)
    }

    @Test
    fun `endpoint with no picker entries yields no group`() {
        val store = FakeStore(endpoints = listOf(FakeEndpoint("ep1", "E", "https://x")))
        assertTrue(ModelCatalog.groupEntries(listOf("openai:gpt"), store).isEmpty())
    }

    // ── refreshLive: condition b — failure keeps last good cache + timestamp ──

    @Test
    fun `refreshLive success returns fresh merged list`() = kotlinx.coroutines.runBlocking {
        val store = FakeStore(
            endpoints = listOf(FakeEndpoint("ep1", "E", "https://x")),
            manual = mutableMapOf("ep1" to listOf("m1")),
        )
        val outcome = ModelCatalog.refreshLive("ep1", { listOf("l1") }, store)
        assertTrue(outcome is ModelCatalog.RefreshOutcome.Ok)
        assertEquals(listOf("m1", "l1"), (outcome as ModelCatalog.RefreshOutcome.Ok).models)
    }

    @Test
    fun `refreshLive failure preserves cache and returns cached merged with timestamp`() = kotlinx.coroutines.runBlocking {
        val store = FakeStore(
            endpoints = listOf(FakeEndpoint("ep1", "E", "https://x")),
            manual = mutableMapOf("ep1" to listOf("m1")),
            live = mutableMapOf("ep1" to listOf("stale-live")),
            fetchedAt = mutableMapOf("ep1" to 99L),
        )
        val outcome = ModelCatalog.refreshLive("ep1", { throw IllegalStateException("404 boom") }, store)
        assertTrue(outcome is ModelCatalog.RefreshOutcome.Failed)
        val failed = outcome as ModelCatalog.RefreshOutcome.Failed
        assertEquals("404 boom", failed.reason)
        // Manual fallback is structural: manual + last-good live survive.
        assertEquals(listOf("m1", "stale-live"), failed.cachedModels)
        assertEquals(99L, failed.cachedAt)
        // The fake store was never written to — cache untouched by the facade.
        assertEquals(listOf("stale-live"), store.live["ep1"])
        assertEquals(99L, store.fetchedAt["ep1"])
    }

    @Test
    fun `refreshLive failure with empty cache still serves manual`() = kotlinx.coroutines.runBlocking {
        val store = FakeStore(
            endpoints = listOf(FakeEndpoint("ep1", "E", "https://x")),
            manual = mutableMapOf("ep1" to listOf("only-manual")),
        )
        val outcome = ModelCatalog.refreshLive("ep1", { throw IllegalStateException("down") }, store)
        assertTrue(outcome is ModelCatalog.RefreshOutcome.Failed)
        assertEquals(listOf("only-manual"), (outcome as ModelCatalog.RefreshOutcome.Failed).cachedModels)
        assertEquals(0L, (outcome as ModelCatalog.RefreshOutcome.Failed).cachedAt)
    }
}
