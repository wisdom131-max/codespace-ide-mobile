package com.codespace.ide.chat

/**
 * MK-RESTRUCTURE PHASE B (approved 2026-10-07, advisor conditions a-d):
 * the SHARED MODEL CATALOG FACADE — the single typed catalog every consumer
 * reads for CUSTOM-ENDPOINT models, and the one home for the merge/dedupe
 * semantics that previously lived duplicated across CustomOpenAiProvider's
 * fetch merge, the picker's group split, and fetchLiveModelEntries' distinct
 * merges.
 *
 * SCOPE (locked by the approved phase-B plan):
 *  - Custom endpoints ONLY. Built-in providers' model enumeration and their
 *    live fetching stay on the existing direct ChatProviderRegistry /
 *    fetchModels path — moving them behind this facade would be EXPANDED
 *    SCOPE (advisor condition c) and needs its own ruling; the natural home
 *    is the phase-C UI pass.
 *  - Pure READ facade + refresh API. No UI, no storage migration (phase A
 *    already made storage per-endpoint), no image bindings (D), no
 *    capability flags (E), no legacy writer removal (F).
 *  - Refresh failures never touch the cache (condition b): the last good
 *    live list and its timestamp stay, and manual entries always remain
 *    pickable — the manual-fallback behavior CustomOpenAiProvider already
 *    guarantees on the fetch path is preserved here on the catalog path.
 *
 * JVM TESTABILITY: everything in this file is pure Kotlin — the Android
 * SharedPreferences store is reached only through [StoreReader], and the
 * refresh's network call only through the caller-supplied `fetcher`
 * suspend lambda. ModelCatalogTest.kt injects fakes for both; the CI JVM
 * unit-test step runs that suite alongside the shell-integration one.
 */
object ModelCatalog {

    /** Read-only view of the per-endpoint model storage (phase-A shape). */
    interface StoreReader {
        fun list(): List<CustomEndpointStore.Endpoint>
        fun manualModels(id: String): List<String>
        fun liveModels(id: String): List<String>
        fun liveModelsFetchedAt(id: String): Long
    }

    /** Production reader — delegates to the phase-A store. */
    private object LiveStoreReader : StoreReader {
        override fun list(): List<CustomEndpointStore.Endpoint> = CustomEndpointStore.list()
        override fun manualModels(id: String): List<String> = CustomEndpointStore.manualModels(id)
        override fun liveModels(id: String): List<String> = CustomEndpointStore.liveModels(id)
        override fun liveModelsFetchedAt(id: String): Long = CustomEndpointStore.liveModelsFetchedAt(id)
    }

    /** One endpoint's typed model catalog entry. */
    data class EndpointModels(
        val endpointId: String,
        val label: String,
        val baseUrl: String,
        val manual: List<String>,
        val live: List<String>,
        val liveFetchedAt: Long,
    ) {
        /** Manual-first distinct merge — the ONE merge semantic (condition d test target). */
        val merged: List<String> get() = mergeDistinct(manual, live)
    }

    /** One "providerId:model" picker entry split into its group membership. */
    data class GroupedEntries(
        val endpointId: String,
        val label: String,
        val manual: List<String>,
        val live: List<String>,
    )

    /**
     * THE merge/dedupe semantic, extracted to one home:
     * manual entries first (owner-curated beats live), then live entries not
     * already present, order preserved on both sides. Identical to the
     * CustomOpenAiProvider fetch merge — now asserted by JVM tests.
     */
    fun mergeDistinct(manual: List<String>, live: List<String>): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>(manual.size + live.size)
        for (m in manual) { if (seen.add(m)) out.add(m) }
        for (l in live) { if (seen.add(l)) out.add(l) }
        return out
    }

    /** Full catalog, registry order. */
    fun catalog(reader: StoreReader = LiveStoreReader): List<EndpointModels> =
        reader.list().map { ep ->
            EndpointModels(
                endpointId = ep.id,
                label = ep.label,
                baseUrl = ep.baseUrl,
                manual = reader.manualModels(ep.id),
                live = reader.liveModels(ep.id),
                liveFetchedAt = reader.liveModelsFetchedAt(ep.id),
            )
        }

    /**
     * Split "providerId:model" picker entries into per-endpoint manual/live
     * groups (the picker's group builder — same split logic, one home).
     */
    fun groupEntries(
        entries: List<String>,
        reader: StoreReader = LiveStoreReader,
    ): List<GroupedEntries> {
        val out = ArrayList<GroupedEntries>()
        for (ep in reader.list()) {
            val pid = CustomEndpointStore.providerIdFor(ep.id)
            val scoped = entries.filter { it.startsWith(pid + ":") }
            if (scoped.isEmpty()) continue
            val manualIds = reader.manualModels(ep.id).toHashSet()
            val manual = scoped.filter { it.substringAfter(':') in manualIds }
            val live = scoped.filter { it !in manual }
            out.add(GroupedEntries(ep.id, ep.label, manual, live))
        }
        return out
    }

    /** Result of a live refresh — cache state is part of the type (condition b). */
    sealed class RefreshOutcome {
        /** Fetch succeeded; [models] is the fresh manual-first merged list. */
        data class Ok(val models: List<String>) : RefreshOutcome()

        /**
         * Fetch failed. The cache and its timestamp are UNTOUCHED — [cachedModels]
         * is what the catalog still serves (manual + last-good live), so manual
         * fallback is structural, not a special case.
         */
        data class Failed(val reason: String, val cachedModels: List<String>, val cachedAt: Long) : RefreshOutcome()
    }

    /**
     * Refresh one endpoint's live list through the caller-supplied fetcher.
     * The fetcher is the existing provider call (ChatKeyFailover-wrapped
     * CustomOpenAiProvider.fetchModels) which writes the cache on success;
     * on failure this function writes NOTHING and returns the surviving
     * cached state. Kept here so phase C's per-group Refetch has one API.
     */
    suspend fun refreshLive(
        endpointId: String,
        fetcher: suspend () -> List<String>,
        reader: StoreReader = LiveStoreReader,
    ): RefreshOutcome {
        return try {
            val fresh = fetcher()
            RefreshOutcome.Ok(mergeDistinct(reader.manualModels(endpointId), fresh))
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // Cancellation is never an endpoint failure — rethrow (C6 rule).
            throw ce
        } catch (e: Exception) {
            RefreshOutcome.Failed(
                reason = (e.message ?: "unreachable").take(200),
                cachedModels = mergeDistinct(reader.manualModels(endpointId), reader.liveModels(endpointId)),
                cachedAt = reader.liveModelsFetchedAt(endpointId),
            )
        }
    }
}
