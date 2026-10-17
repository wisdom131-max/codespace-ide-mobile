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
 * BATCH4-P3 JVM tests (S01 fix, SK01 model) for ChatPlanStore:
 *  - revision bumps only on a REAL save; a failed save rolls the in-memory
 *    change back so the store never claims a save that did not reach storage;
 *  - a corrupt payload is quarantined (copy parked aside, content-deduped) and
 *    the original is NEVER deleted or overwritten;
 *  - a corrupt STEP inside an otherwise-readable plan skips that step and
 *    keeps the rest.
 *
 * ChatPrefsBackend is internal — AGP unit tests are a friend module of main,
 * so the fake backend implements it directly.
 */
class ChatPlanStoreS01Test {

    private class FakeBackend : ChatPrefsBackend {
        var failWrites = false
        val map = HashMap<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String): Boolean {
            if (failWrites) return false
            map[key] = value
            return true
        }
        override fun allWithPrefix(prefix: String): Map<String, String> =
            map.filterKeys { it.startsWith(prefix) }.toMap()
    }

    private lateinit var backend: FakeBackend

    @Before
    fun setUp() {
        backend = FakeBackend()
        ChatPlanStore.resetForTests()
        ChatPlanStore.testBackend = backend
    }

    @After
    fun tearDown() {
        ChatPlanStore.testBackend = null
        ChatPlanStore.resetForTests()
    }

    @Test
    fun update_realSave_bumpsRevisionAndPersists() {
        val revBefore = ChatPlanStore.revision.value
        val ok = ChatPlanStore.update("s1", listOf(ChatPlanStore.PlanStep("Fix", "detail")))
        assertTrue(ok)
        assertEquals(revBefore + 1, ChatPlanStore.revision.value)
        val raw = backend.getString("plans_v1")
        assertNotNull("a real save must land in the backend", raw)
        val plan = ChatPlanStore.planFor("s1")
        assertNotNull(plan)
        assertEquals("Fix", plan!!.steps[0].title)
    }

    @Test
    fun update_failedSave_rollsBackAndDoesNotBumpRevision() {
        ChatPlanStore.update("s1", listOf(ChatPlanStore.PlanStep("First", "")))
        val revBefore = ChatPlanStore.revision.value
        val rawBefore = backend.getString("plans_v1")
        backend.failWrites = true
        val ok = ChatPlanStore.update("s1", listOf(ChatPlanStore.PlanStep("Second", "")))
        assertFalse("typed save must report failure", ok)
        assertEquals("revision must not bump on a failed save", revBefore, ChatPlanStore.revision.value)
        assertEquals("disk keeps the last good payload", rawBefore, backend.getString("plans_v1"))
        assertEquals("in-memory change rolled back", "First", ChatPlanStore.planFor("s1")!!.steps[0].title)
    }

    @Test
    fun clear_failedSave_keepsThePlan() {
        ChatPlanStore.update("s1", listOf(ChatPlanStore.PlanStep("Keep", "")))
        backend.failWrites = true
        val revBefore = ChatPlanStore.revision.value
        assertFalse(ChatPlanStore.clear("s1"))
        assertEquals(revBefore, ChatPlanStore.revision.value)
        assertNotNull("a failed clear must not remove the plan", ChatPlanStore.planFor("s1"))
    }

    @Test
    fun corruptPayload_isQuarantined_originalPreserved_dedupOnReload() {
        val corrupt = "{ this is not valid json"
        backend.map["plans_v1"] = corrupt
        ChatPlanStore.resetForTests() // force a cold load
        assertNull("corrupt payload loads as honest-empty", ChatPlanStore.planFor("s1"))
        assertEquals("original key is NEVER deleted or overwritten", corrupt, backend.getString("plans_v1"))
        val quarantined = backend.allWithPrefix("plans_v1.corrupt-")
        assertEquals("exactly one quarantine copy for a new corrupt payload", 1, quarantined.size)
        assertEquals(corrupt, quarantined.values.first())
        // Second launch with the SAME corrupt payload: no duplicate quarantine copies.
        ChatPlanStore.resetForTests()
        assertNull(ChatPlanStore.planFor("s1"))
        assertEquals(1, backend.allWithPrefix("plans_v1.corrupt-").size)
    }

    @Test
    fun corruptStep_skipsThatStep_keepsTheRest() {
        val steps = org.json.JSONArray()
            .put("not an object") // corrupt entry
            .put(org.json.JSONObject().put("title", "Survivor"))
        val payload = org.json.JSONObject()
            .put("s1", org.json.JSONObject().put("steps", steps).put("approved", true))
            .toString()
        backend.map["plans_v1"] = payload
        ChatPlanStore.resetForTests() // force a cold load
        val plan = ChatPlanStore.planFor("s1")
        assertNotNull(plan)
        assertEquals("readable steps survive a corrupt sibling", 1, plan!!.steps.size)
        assertEquals("Survivor", plan.steps[0].title)
        assertTrue(plan.approved)
    }
}
