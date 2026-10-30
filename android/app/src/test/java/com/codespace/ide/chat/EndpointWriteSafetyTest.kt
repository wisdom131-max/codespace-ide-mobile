package com.codespace.ide.chat

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WRITE-SIDE data-loss guard (2026-10-30).
 *
 * Every mutation used to be built on `endpointsJson()`, which degrades a FAILED read to `[]`,
 * and then wrote the whole array back. One transient read failure during an edit therefore
 * replaced the endpoint document with (almost) nothing. These tests pin the rule: an unreadable
 * document means the mutation is REFUSED, the stored bytes are untouched, and the legacy
 * migration is neither run nor marked complete.
 */
class EndpointWriteSafetyTest {

    private class FailingRead(private val inner: CustomEndpointStore.MemoryEndpointStorage) :
        CustomEndpointStore.EndpointStorage by inner {
        override fun read(): String? = throw IllegalStateException("storage unavailable")
        override fun hasModelRow(key: String): Boolean? = null
        override fun readModelRow(key: String): String? = null
    }

    private class WriteFailing(private val inner: CustomEndpointStore.MemoryEndpointStorage) :
        CustomEndpointStore.EndpointStorage by inner {
        override fun write(json: String): Boolean = false
        override fun writeModelRow(key: String, value: String?): Boolean = false
    }

    private final class Seeded {
        val storage = CustomEndpointStore.MemoryEndpointStorage()
        init {
            CustomEndpointStore.useStorage(storage)
            CustomEndpointStore.add("Box", "https://box.example/v1")
            CustomEndpointStore.add("Lab", "https://lab.example/v1")
            CustomEndpointStore.setManualModels("box", "model-alpha\nmodel-beta")
            CustomEndpointStore.setManualModels("lab", "model-gamma")
        }
        fun bytes(): String? = storage.json
    }

    @After
    fun tearDown() {
        CustomEndpointStore.useStorage(CustomEndpointStore.MemoryEndpointStorage())
    }

    // ── unreadable document: refuse, never rewrite ────────────────────────────

    @Test
    fun addDoesNotOverwriteExistingEndpointsWhenTheReadFails() {
        val seeded = Seeded()
        val before = seeded.bytes()
        CustomEndpointStore.useStorage(FailingRead(seeded.storage))

        val added = CustomEndpointStore.add("Third", "https://third.example/v1")

        assertNull("add must report failure, not a phantom endpoint", added)
        assertEquals("the stored document is untouched", before, seeded.bytes())
        CustomEndpointStore.useStorage(seeded.storage)
        assertEquals("both endpoints survive", 2, CustomEndpointStore.list().size)
    }

    @Test
    fun updateDoesNotOverwriteExistingEndpointsWhenTheReadFails() {
        val seeded = Seeded()
        val before = seeded.bytes()
        CustomEndpointStore.useStorage(FailingRead(seeded.storage))

        assertFalse(CustomEndpointStore.update("box", "Renamed", "https://new.example/v1"))
        assertEquals(before, seeded.bytes())
        CustomEndpointStore.useStorage(seeded.storage)
        assertEquals("https://box.example/v1", CustomEndpointStore.byId("box")?.baseUrl)
    }

    @Test
    fun setEnabledDoesNotOverwriteExistingEndpointsWhenTheReadFails() {
        val seeded = Seeded()
        val before = seeded.bytes()
        CustomEndpointStore.useStorage(FailingRead(seeded.storage))

        assertFalse(CustomEndpointStore.setEnabled("box", false))
        assertEquals(before, seeded.bytes())
        CustomEndpointStore.useStorage(seeded.storage)
        assertTrue(CustomEndpointStore.byId("box")?.enabled ?: false)
    }

    @Test
    fun deleteDoesNotOverwriteExistingEndpointsWhenTheReadFails() {
        val seeded = Seeded()
        val before = seeded.bytes()
        CustomEndpointStore.useStorage(FailingRead(seeded.storage))

        assertFalse(CustomEndpointStore.delete("box"))
        assertEquals(before, seeded.bytes())
        CustomEndpointStore.useStorage(seeded.storage)
        assertEquals("nothing was deleted", 2, CustomEndpointStore.list().size)
        assertEquals("model rows are untouched", listOf("model-alpha", "model-beta"),
            CustomEndpointStore.readManualModels("box").let {
                (it as CustomEndpointStore.ModelRead.Loaded).ids
            })
    }

    // ── successful path keeps its contract ────────────────────────────────────

    @Test
    fun addStillWorksAndDeduplicatesSlugs() {
        val seeded = Seeded()
        val added = CustomEndpointStore.add("Box", "https://box2.example/v1")
        assertNotNull(added)
        assertEquals("box_2", added!!.id)
        assertEquals(3, CustomEndpointStore.list().size)
        assertEquals("https://box2.example/v1", CustomEndpointStore.byId("box_2")?.baseUrl)
        assertTrue(seeded.bytes()!!.contains("box_2"))
    }

    @Test
    fun updateStillWorksAndRefreshesTheUrl() {
        Seeded()
        assertTrue(CustomEndpointStore.update("box", "Box 2", "https://box2.example/v1"))
        assertEquals("Box 2", CustomEndpointStore.byId("box")?.label)
        assertEquals("https://box2.example/v1", CustomEndpointStore.byId("box")?.baseUrl)
    }

    @Test
    fun setEnabledStillWorks() {
        Seeded()
        assertTrue(CustomEndpointStore.setEnabled("box", false))
        assertFalse(CustomEndpointStore.byId("box")?.enabled ?: true)
        assertEquals("the other endpoint is untouched", true, CustomEndpointStore.byId("lab")?.enabled)
    }

    @Test
    fun deleteStillWorksAndDropsTheModelRows() {
        Seeded()
        assertTrue(CustomEndpointStore.delete("box"))
        assertEquals(listOf("lab"), CustomEndpointStore.list().map { it.id })
        assertEquals(CustomEndpointStore.ModelRead.Loaded(emptyList()),
            CustomEndpointStore.readManualModels("box"))
        assertEquals(listOf("model-gamma"),
            (CustomEndpointStore.readManualModels("lab") as CustomEndpointStore.ModelRead.Loaded).ids)
    }

    // ── failed writes report failure and change nothing ───────────────────────

    @Test
    fun aFailedWriteDoesNotClaimSuccessOrAlterTheDocument() {
        val seeded = Seeded()
        val before = seeded.bytes()
        CustomEndpointStore.useStorage(WriteFailing(seeded.storage))

        assertNull("a write that did not land is not a successful add",
            CustomEndpointStore.add("Third", "https://third.example/v1"))
        assertFalse(CustomEndpointStore.update("box", "Renamed", "https://new.example/v1"))
        assertFalse(CustomEndpointStore.setEnabled("lab", false))
        assertFalse(CustomEndpointStore.delete("lab"))
        assertEquals(before, seeded.bytes())
    }

    // ── legacy migration is read-gated ────────────────────────────────────────

    @Test
    fun migrationIsSkippedWhenTheDocumentIsUnreadable() {
        assertEquals(CustomEndpointStore.LegacyMigrationAction.Skip,
            CustomEndpointStore.planLegacyMigration(
                CustomEndpointStore.EndpointsRead.Unreadable,
                alreadyMigrated = false,
                legacyUrl = "https://legacy.example/v1",
                legacyManual = "legacy-model",
            ))
    }

    @Test
    fun migrationSeedsOnlyFromASuccessfulEmptyRead() {
        val seed = CustomEndpointStore.planLegacyMigration(
            CustomEndpointStore.EndpointsRead.Loaded(emptyList()),
            alreadyMigrated = false,
            legacyUrl = "https://legacy.example/v1",
            legacyManual = "legacy-model",
        )
        assertEquals(CustomEndpointStore.LegacyMigrationAction.Seed("https://legacy.example/v1", "legacy-model"), seed)
    }

    @Test
    fun migrationMarksCompleteAfterReadableStorageWithExistingEndpoints() {
        val existing = listOf(CustomEndpointStore.Endpoint("box", "Box", "https://box.example/v1", true))
        val action = CustomEndpointStore.planLegacyMigration(
            CustomEndpointStore.EndpointsRead.Loaded(existing),
            alreadyMigrated = false,
            legacyUrl = "https://legacy.example/v1",
            legacyManual = null,
        )
        assertTrue("a readable document with content only records completion",
            action is CustomEndpointStore.LegacyMigrationAction.MarkComplete)
    }

    @Test
    fun migrationWithoutALegacyUrlOnlyRecordsCompletion() {
        val action = CustomEndpointStore.planLegacyMigration(
            CustomEndpointStore.EndpointsRead.Loaded(emptyList()),
            alreadyMigrated = false,
            legacyUrl = null,
            legacyManual = null,
        )
        assertTrue(action is CustomEndpointStore.LegacyMigrationAction.MarkComplete)
    }

    @Test
    fun anAlreadyMigratedStoreNeverSeedsAgain() {
        val action = CustomEndpointStore.planLegacyMigration(
            CustomEndpointStore.EndpointsRead.Loaded(emptyList()),
            alreadyMigrated = true,
            legacyUrl = "https://legacy.example/v1",
            legacyManual = null,
        )
        assertEquals(CustomEndpointStore.LegacyMigrationAction.Skip, action)
    }
}
