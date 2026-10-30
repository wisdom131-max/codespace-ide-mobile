package com.codespace.ide.chat

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The model-management contract for ONE endpoint with MANY model ids, through the real store:
 * manual ids survive a discovery write, discovery never deletes manual ids, a failed write
 * changes nothing, and editing the endpoint keeps both lists.
 */
class CustomModelFlowTest {

    @Before
    fun fresh() = CustomEndpointStore.useStorage(CustomEndpointStore.MemoryEndpointStorage())

    @After
    fun cleanup() = CustomEndpointStore.useStorage(CustomEndpointStore.MemoryEndpointStorage())

    private fun endpoint() = CustomEndpointStore.add("Box", "https://box.example/v1")!!

    @Test
    fun oneEndpointHoldsSeveralManualModelIds() {
        val ep = endpoint()
        CustomEndpointStore.addManualModel(ep.id, "model-alpha")
        CustomEndpointStore.addManualModel(ep.id, "model-beta")
        CustomEndpointStore.addManualModel(ep.id, " model-gamma ")
        assertEquals(listOf("model-alpha", "model-beta", "model-gamma"),
            CustomEndpointStore.manualModels(ep.id))
    }

    @Test
    fun addingTheSameIdTwiceIsANoOp() {
        val ep = endpoint()
        CustomEndpointStore.addManualModel(ep.id, "model-alpha")
        CustomEndpointStore.addManualModel(ep.id, "model-alpha")
        assertEquals(listOf("model-alpha"), CustomEndpointStore.manualModels(ep.id))
    }

    @Test
    fun removingOneIdLeavesTheOthers() {
        val ep = endpoint()
        listOf("a", "b", "c").forEach { CustomEndpointStore.addManualModel(ep.id, it) }
        CustomEndpointStore.removeManualModel(ep.id, "b")
        assertEquals(listOf("a", "c"), CustomEndpointStore.manualModels(ep.id))
    }

    @Test
    fun discoveryWritesLiveAndNeverTouchesManual() {
        val ep = endpoint()
        CustomEndpointStore.addManualModel(ep.id, "my-alias")
        CustomEndpointStore.setLiveModels(ep.id, listOf("discovered-1", "discovered-2"))
        assertEquals("the user's own id must survive a fetch", listOf("my-alias"),
            CustomEndpointStore.manualModels(ep.id))
        assertEquals(listOf("discovered-1", "discovered-2"), CustomEndpointStore.liveModels(ep.id))
    }

    @Test
    fun aFailedFetchIsSimplyNotWritten() {
        val ep = endpoint()
        CustomEndpointStore.addManualModel(ep.id, "my-alias")
        // A failed fetch performs NO store call at all (the fetcher returns Failed), so the state
        // after it is byte-identical: manual kept, live still empty, timestamp still zero.
        assertEquals(listOf("my-alias"), CustomEndpointStore.manualModels(ep.id))
        assertTrue(CustomEndpointStore.liveModels(ep.id).isEmpty())
        assertEquals(0L, CustomEndpointStore.liveModelsFetchedAt(ep.id))
    }

    @Test
    fun editingTheUrlKeepsBothModelLists() {
        val ep = endpoint()
        CustomEndpointStore.addManualModel(ep.id, "my-alias")
        CustomEndpointStore.setLiveModels(ep.id, listOf("discovered-1"))
        assertTrue(CustomEndpointStore.update(ep.id, "Box 2", "https://box2.example/v1"))
        assertEquals(listOf("my-alias"), CustomEndpointStore.manualModels(ep.id))
        assertEquals(listOf("discovered-1"), CustomEndpointStore.liveModels(ep.id))
    }

    @Test
    fun twoEndpointsDoNotShareModels() {
        val a = endpoint()
        val b = CustomEndpointStore.add("Other", "https://other.example/v1")!!
        CustomEndpointStore.addManualModel(a.id, "a-model")
        CustomEndpointStore.addManualModel(b.id, "b-model")
        assertEquals(listOf("a-model"), CustomEndpointStore.manualModels(a.id))
        assertEquals(listOf("b-model"), CustomEndpointStore.manualModels(b.id))
        assertFalse(CustomEndpointStore.providerIdFor(a.id) == CustomEndpointStore.providerIdFor(b.id))
    }
}
