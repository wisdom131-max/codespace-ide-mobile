package com.codespace.ide.chat.panel

import com.codespace.ide.chat.CustomEndpointStore
import com.codespace.ide.chat.keys.StoredCredentialsReader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * U9 P1 storage-read safety (2026-10-30).
 *
 * B1: an unreadable settings store must not turn a coherent selection into NeedsSetup, and must
 *     never be able to persist a conclusion it could not verify.
 * B2: an unreadable model list must not be indistinguishable from a successful empty list.
 *
 * Everything here runs through the REAL store, reader and resolver via their test seams
 * (`useStorage`, the reader's slot/slotValue lambdas), so the assertions cover production code.
 */
class StorageUnreadableSelectionTest {

    /** A store whose document read throws: the "storage not answering" case. */
    private class ThrowingStorage(
        private val inner: CustomEndpointStore.MemoryEndpointStorage,
    ) : CustomEndpointStore.EndpointStorage by inner {
        override fun read(): String? = throw IllegalStateException("prefs unavailable")
    }

    private val mem = CustomEndpointStore.MemoryEndpointStorage()

    @Before
    fun setUp() {
        CustomEndpointStore.useStorage(mem)
    }

    @After
    fun tearDown() {
        // Never leak the throwing/failing storage into another suite.
        CustomEndpointStore.useStorage(CustomEndpointStore.MemoryEndpointStorage())
    }

    private fun endpoint(vararg labels: String): List<String> =
        labels.map { label ->
            val ep = CustomEndpointStore.add(label, "https://" + label + ".example.com/v1")
            requireNotNull(ep)
            ep.id
        }

    private fun cfgFor(vararg providerIds: String): ConfiguredProviders = ConfiguredProviders(
        withKey = providerIds.toList(),
        customEndpoints = emptyList(),
    )

    private fun cfgWithEndpoint(providerId: String, baseUrl: String): ConfiguredProviders =
        ConfiguredProviders(withKey = listOf(providerId), customEndpoints = listOf(providerId to baseUrl))

    // ── B1: configuration read outcome ──────────────────────────────────────

    @Test
    fun anEmptyDocumentIsASuccessfulRead() {
        val read = CustomEndpointStore.readEndpoints()
        assertTrue("no document must read as a successful empty list", read is CustomEndpointStore.EndpointsRead.Loaded)
        assertTrue((read as CustomEndpointStore.EndpointsRead.Loaded).endpoints.isEmpty())
    }

    @Test
    fun aStoredDocumentReadsBackAsLoaded() {
        endpoint("alpha")
        val read = CustomEndpointStore.readEndpoints()
        assertTrue(read is CustomEndpointStore.EndpointsRead.Loaded)
        assertEquals(1, (read as CustomEndpointStore.EndpointsRead.Loaded).endpoints.size)
    }

    @Test
    fun aFailedDocumentReadIsUnreadableNotEmpty() {
        CustomEndpointStore.useStorage(ThrowingStorage(mem))
        val read = CustomEndpointStore.readEndpoints()
        assertEquals("a throwing read must never look like an empty config",
            CustomEndpointStore.EndpointsRead.Unreadable, read)
        // The lenient list view stays empty for UI, which is exactly why selection must not use it.
        assertTrue(CustomEndpointStore.list().isEmpty())
    }

    @Test
    fun corruptDocumentIsUnreadableNotEmpty() {
        mem.json = "{not json"
        assertEquals(CustomEndpointStore.EndpointsRead.Unreadable, CustomEndpointStore.readEndpoints())
    }

    @Test
    fun unreadableConfigurationDoesNotBecomeNeedsSetup() {
        val sel = PanelSelection("custom_alpha", "ai_CUSTOM_ALPHA", "model-a", "https://alpha.example.com/v1")
        val resolved = SelectionResolver.resolve(
            sel, cfgWithEndpoint("custom_alpha", "https://alpha.example.com/v1"),
            { "ai_CUSTOM_ALPHA" }, settingsReadable = false,
        )
        assertTrue("a read failure is not 'never configured'", resolved is ResolvedSelection.Unavailable)
        assertNotEquals(ResolvedSelection.NeedsSetup, resolved)
    }

    @Test
    fun aCoherentSelectionBecomesUnavailableWithItsIdentityIntact() {
        val sel = PanelSelection("gemini", "ai_GEMINI", "gemini-3.8-flash", "https://generativelanguage.googleapis.com")
        val resolved = SelectionResolver.resolve(sel, cfgFor("gemini"), { "ai_GEMINI" }, settingsReadable = false)
        val unavailable = resolved as ResolvedSelection.Unavailable
        assertEquals("gemini", unavailable.selection.providerId)
        assertEquals("gemini-3.8-flash", unavailable.selection.modelId)
        assertEquals("ai_GEMINI", unavailable.selection.keySlot)
    }

    @Test
    fun anUnknownProviderStillNeedsSetup() {
        // A READABLE config that does not contain the provider is genuinely unconfigured:
        // NeedsSetup, never Unavailable (that state is reserved for "could not read").
        val sel = PanelSelection("ghost", "ai_GHOST", "some-model", "https://ghost.example.com")
        val resolved = SelectionResolver.resolve(sel, cfgFor(), { "ai_GHOST" }, settingsReadable = true)
        assertEquals("readable config without the provider is genuinely unconfigured",
            ResolvedSelection.NeedsSetup, resolved)
    }

    @Test
    fun anUnknownProviderIsReplacedByAConfiguredOneNotRestored() {
        // Never silently accept an invalid provider: with a usable provider configured, the
        // unknown pair is discarded in favour of a coherent one.
        val sel = PanelSelection("ghost", "ai_GHOST", "some-model", "https://ghost.example.com")
        val resolved = SelectionResolver.resolve(sel, cfgFor("gemini"), { "ai_GHOST" }, settingsReadable = true)
        val ready = resolved as ResolvedSelection.Ready
        assertEquals("gemini", ready.selection.providerId)
        assertNotEquals("some-model", ready.selection.modelId)
    }

    @Test
    fun aBlankIdentityIsNeverAccepted() {
        val sel = PanelSelection("gemini", "ai_GEMINI", "", "https://generativelanguage.googleapis.com")
        assertEquals("an unreadable config cannot vouch for a pair with no model",
            ResolvedSelection.NeedsSetup,
            SelectionResolver.resolve(sel, cfgFor(), { "ai_GEMINI" }, settingsReadable = false))
    }

    @Test
    fun recoveryReturnsTheSameIdentityAsReady() {
        val sel = PanelSelection("custom_alpha", "ai_CUSTOM_ALPHA", "model-a", "https://old.example.com/v1")
        val ok = SelectionResolver.resolve(
            sel, cfgWithEndpoint("custom_alpha", "https://alpha.example.com/v1"),
            { "ai_CUSTOM_ALPHA" }, settingsReadable = true,
        ) as ResolvedSelection.Ready
        assertEquals("custom_alpha", ok.selection.providerId)
        assertEquals("model-a", ok.selection.modelId)
        assertEquals("the URL is refreshed from the store on recovery",
            "https://alpha.example.com/v1", ok.selection.baseUrl)
    }

    @Test
    fun unavailableIsNeverPersisted() {
        val sel = PanelSelection("gemini", "ai_GEMINI", "gemini-3.8-flash", "https://generativelanguage.googleapis.com")
        assertFalse("an unreadable config must not be able to persist",
            persistDecision(sel, cfgFor("gemini"), settingsReadable = false))
        assertTrue("the same pair persists normally once readable",
            persistDecision(sel, cfgFor("gemini"), settingsReadable = true))
    }

    @Test
    fun theReaderReportsUnreadableEndpointsInsteadOfAnEmptySnapshot() {
        CustomEndpointStore.useStorage(ThrowingStorage(mem))
        val reader = StoredCredentialsReader(
            { mapOf("ai_GEMINI" to "key") },
            { "key" },
            CustomEndpointStore,
        )
        val read = reader.readSnapshot()
        assertFalse("a failed endpoint read must be reported, not hidden", read.readable)
        assertTrue(read.snapshot.endpoints.isEmpty())
    }

    @Test
    fun theReaderReportsAReadableEmptyConfigurationAsReadable() {
        val reader = StoredCredentialsReader({ emptyMap<String, String>() }, { null }, CustomEndpointStore)
        val read = reader.readSnapshot()
        assertTrue("empty is a real answer", read.readable)
        assertTrue(read.snapshot.endpoints.isEmpty())
        assertTrue(read.snapshot.keySlots.isEmpty())
    }

    @Test
    fun aFailingKeyReadAlsoReportsUnreadable() {
        val reader = StoredCredentialsReader(
            { throw IllegalStateException("keystore locked") },
            { null },
            CustomEndpointStore,
        )
        assertFalse(reader.readSnapshot().readable)
    }

    @Test
    fun aConfiguredEndpointSurvivesTheReaderRoundTrip() {
        endpoint("alpha")
        val reader = StoredCredentialsReader(
            { mapOf("ai_CUSTOM_ALPHA" to "k") },
            { "k" },
            CustomEndpointStore,
        )
        val snap = reader.readSnapshot()
        assertTrue(snap.readable)
        assertEquals(1, snap.snapshot.endpoints.size)
        assertEquals("custom_alpha", snap.snapshot.endpoints.first().providerId)
        assertTrue(snap.snapshot.endpoints.first().hasKey)
    }

    // ── B2: model lists ────────────────────────────────────────────────────

    @Test
    fun anUnreadableManualListIsNotAnEmptyList() {
        val id = endpoint("alpha").first()
        CustomEndpointStore.setManualModels(id, "model-a\nmodel-b")
        CustomEndpointStore.useStorage(object : CustomEndpointStore.EndpointStorage by mem {
            override fun hasModelRow(key: String): Boolean? = null
        })
        val read = CustomEndpointStore.readManualModels(id)
        assertEquals(CustomEndpointStore.ModelRead.Unreadable, read)
        assertNotEquals("an unreadable list must not equal a successful empty one",
            CustomEndpointStore.ModelRead.Loaded(emptyList<String>()), read)
    }

    @Test
    fun anUnreadableLiveListIsNotAnEmptyList() {
        val id = endpoint("alpha").first()
        CustomEndpointStore.setLiveModels(id, listOf("live-1"))
        CustomEndpointStore.useStorage(object : CustomEndpointStore.EndpointStorage by mem {
            override fun hasModelRow(key: String): Boolean? = null
        })
        assertEquals(CustomEndpointStore.ModelRead.Unreadable, CustomEndpointStore.readLiveModels(id))
    }

    @Test
    fun aReadableOfflineCacheStaysUsable() {
        val id = endpoint("alpha").first()
        CustomEndpointStore.setLiveModels(id, listOf("cached-1", "cached-2"))
        val read = CustomEndpointStore.readLiveModels(id) as CustomEndpointStore.ModelRead.Loaded
        assertEquals(listOf("cached-1", "cached-2"), read.ids)
        assertTrue("the cache age is recorded for the status surface", CustomEndpointStore.liveModelsFetchedAt(id) > 0L)
    }

    @Test
    fun aSuccessfulEmptyManualListStaysEmptyAndSaveable() {
        val id = endpoint("alpha").first()
        CustomEndpointStore.setManualModels(id, "")
        val read = CustomEndpointStore.readManualModels(id) as CustomEndpointStore.ModelRead.Loaded
        assertTrue(read.ids.isEmpty())
        assertTrue("an empty-but-readable list may be written", EndpointModelCommit.canSave(read))
        assertFalse("an unreadable one may not", EndpointModelCommit.canSave(CustomEndpointStore.ModelRead.Unreadable))
    }

    @Test
    fun endpointsStayIsolatedThroughTheReader() {
        val ids = endpoint("alpha", "beta")
        CustomEndpointStore.setManualModels(ids[0], "a-1")
        CustomEndpointStore.setManualModels(ids[1], "b-1")
        val a = CustomEndpointStore.readManualModels(ids[0]) as CustomEndpointStore.ModelRead.Loaded
        val b = CustomEndpointStore.readManualModels(ids[1]) as CustomEndpointStore.ModelRead.Loaded
        assertEquals(listOf("a-1"), a.ids)
        assertEquals(listOf("b-1"), b.ids)
    }
}
