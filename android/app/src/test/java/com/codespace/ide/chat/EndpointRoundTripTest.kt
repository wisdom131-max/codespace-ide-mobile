package com.codespace.ide.chat

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * U1 (build 657 finding c): "add endpoint -> save -> restart -> read back -> select".
 *
 * These exercise the REAL CustomEndpointStore code through a storage seam
 * (MemoryEndpointStorage), so the serialization, the id assignment, the write-result
 * propagation and the read-back are all the production paths. What a JVM test cannot
 * prove: that Android's EncryptedSharedPreferences/SharedPreferences actually commit on
 * this device (filesystem, keystore, storage pressure) - that stays a device check.
 */
class EndpointRoundTripTest {

    private class FailingStorage : CustomEndpointStore.EndpointStorage {
        var lastJson: String? = null
        override fun read(): String? = lastJson
        override fun write(json: String): Boolean = false // a failed commit
    }

    @Before
    fun fresh() = CustomEndpointStore.useStorage(CustomEndpointStore.MemoryEndpointStorage())

    @After
    fun cleanup() = CustomEndpointStore.useStorage(CustomEndpointStore.MemoryEndpointStorage())

    @Test
    fun addThenReadBackAfterARestartKeepsTheEndpoint() {
        val saved = CustomEndpointStore.add("HF Router", "https://router.huggingface.co/v1")
        assertNotNull("add must report success", saved)
        // "restart": the store keeps NO cached copy - a fresh read from storage is the truth.
        val afterRestart = CustomEndpointStore.list()
        assertEquals(1, afterRestart.size)
        assertEquals("hf_router", afterRestart[0].id)
        assertEquals("HF Router", afterRestart[0].label)
        assertEquals("https://router.huggingface.co/v1", afterRestart[0].baseUrl)
        assertTrue("new endpoints are enabled", afterRestart[0].enabled)
        assertEquals("custom_hf_router", CustomEndpointStore.providerIdFor("hf_router"))
    }

    @Test
    fun aFailedWriteIsReportedAndNothingPretendsToHaveSaved() {
        CustomEndpointStore.useStorage(FailingStorage())
        assertNull("a failed save must NOT look like success", CustomEndpointStore.add("Local", "http://10.0.0.2:11434/v1"))
        assertTrue(CustomEndpointStore.list().isEmpty())
    }

    @Test
    fun updateDeleteAndToggleSurviveReadBack() {
        val ep = CustomEndpointStore.add("Box", "http://10.0.0.2:8000/v1")!!
        assertTrue(CustomEndpointStore.update(ep.id, "Box 2", "http://10.0.0.2:9000/v1"))
        val edited = CustomEndpointStore.byId(ep.id)!!
        assertEquals("Box 2", edited.label)
        assertEquals("http://10.0.0.2:9000/v1", edited.baseUrl)
        assertTrue(CustomEndpointStore.setEnabled(ep.id, false))
        assertFalse(CustomEndpointStore.byId(ep.id)!!.enabled)
        assertTrue(CustomEndpointStore.delete(ep.id))
        assertNull(CustomEndpointStore.byId(ep.id))
    }

    @Test
    fun modelRowsRoundTripThroughTheSameStorage() {
        val ep = CustomEndpointStore.add("Box", "http://10.0.0.2:8000/v1")!!
        assertTrue(CustomEndpointStore.setManualModels(ep.id, "llama3.2, qwen2.5-coder"))
        assertEquals(listOf("llama3.2", "qwen2.5-coder"), CustomEndpointStore.manualModels(ep.id))
        CustomEndpointStore.setLiveModels(ep.id, listOf("a", "b"))
        assertEquals(listOf("a", "b"), CustomEndpointStore.liveModels(ep.id))
        assertTrue("the fetch stamp is stored", CustomEndpointStore.liveModelsFetchedAt(ep.id) > 0L)
        CustomEndpointStore.clearLiveModels(ep.id)
        assertTrue(CustomEndpointStore.liveModels(ep.id).isEmpty())
        assertEquals(0L, CustomEndpointStore.liveModelsFetchedAt(ep.id))
    }

    @Test
    fun deletingAnEndpointDropsItsModelRowsButKeepsItsKeySlot() {
        val ep = CustomEndpointStore.add("Box", "http://10.0.0.2:8000/v1")!!
        CustomEndpointStore.setManualModels(ep.id, "m1")
        assertTrue(CustomEndpointStore.delete(ep.id))
        assertTrue("manual models go with the endpoint", CustomEndpointStore.manualModels(ep.id).isEmpty())
        // The KEY is not this store's business: only the endpoint + model rows are removed.
        assertEquals("ai_CUSTOM_BOX", com.codespace.ide.chat.keys.SlotNaming.primarySlot(
            CustomEndpointStore.providerIdFor(ep.id)))
    }

    @Test
    fun duplicateLabelsGetUniqueIdsInsteadOfOverwriting() {
        val a = CustomEndpointStore.add("Local", "http://10.0.0.2:8000/v1")!!
        val b = CustomEndpointStore.add("Local", "http://10.0.0.2:9000/v1")!!
        assertEquals("local", a.id)
        assertEquals("local_2", b.id)
        assertEquals(2, CustomEndpointStore.list().size)
    }

    @Test
    fun badUrlsAreRefusedWithoutTouchingStorage() {
        assertNull(CustomEndpointStore.add("No scheme", "10.0.0.2:8000"))
        assertTrue(CustomEndpointStore.list().isEmpty())
    }
}
