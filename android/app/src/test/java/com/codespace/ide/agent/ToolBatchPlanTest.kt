package com.codespace.ide.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4.A: the planner has to decide something real. These assert the two decisions the request path
 * now takes from it - execution order, and which duplicate is answered instead of repeated.
 */
class ToolBatchPlanTest {

    private fun planOf(request: String, names: List<String>) = ToolPlanner.plan(request, names.toSet())

    @Test fun aSingleCallIsLeftAlone() {
        assertEquals(listOf(0), ToolBatchPlan.order(planOf("read it", listOf("read_file")), listOf("read_file")))
    }

    @Test fun orderIsAlwaysAPermutationSoNothingIsDropped() {
        val names = listOf("write_file", "read_file", "list_dir", "unknown_tool")
        val order = ToolBatchPlan.order(planOf("fix the file", names), names)
        assertEquals("every call must survive planning", names.indices.toSet(), order.toSet())
        assertEquals(names.size, order.size)
    }

    @Test fun aReadIsScheduledBeforeTheWriteItFeeds() {
        val names = listOf("write_file", "read_file")
        val order = ToolBatchPlan.order(planOf("replace the string in the file", names), names)
        assertTrue("read must not follow the write it informs", order.indexOf(1) < order.indexOf(0))
    }

    /**
     * Found by the test above: dropping an unoffered step must also drop dependencies on it. The
     * search that will never run cannot gate the read behind it, or the plan silently collapses.
     */
    @Test fun aDroppedStepDoesNotBlockWhatDependedOnIt() {
        val plan = ToolPlanner.plan("replace the string in the file", setOf("write_file", "read_file"))
        assertTrue("the read must still be schedulable", plan.waves().flatten().any { it.tool == "read_file" })
        assertTrue(plan.problems().none { it.contains("needs missing") })
    }

    @Test fun aToolThePlanNeverMentionsKeepsItsPlaceAtTheEnd() {
        val names = listOf("read_file", "brand_new_tool")
        val order = ToolBatchPlan.order(planOf("read", names), names)
        assertEquals(1, order.last())
    }

    @Test fun anExactRepeatIsAnsweredByTheFirstCall() {
        val names = listOf("read_file", "read_file")
        val args = listOf("{\"path\":\"/a\"}", "{\"path\":\"/a\"}")
        assertEquals(mapOf(1 to 0), ToolBatchPlan.duplicates(names, args))
    }

    @Test fun theSameToolWithDifferentArgumentsIsNotARepeat() {
        val names = listOf("read_file", "read_file")
        val args = listOf("{\"path\":\"/a\"}", "{\"path\":\"/b\"}")
        assertTrue(ToolBatchPlan.duplicates(names, args).isEmpty())
    }

    @Test fun differentToolsWithIdenticalArgumentsAreNotARepeat() {
        val names = listOf("read_file", "zip_list")
        val args = listOf("{\"path\":\"/a\"}", "{\"path\":\"/a\"}")
        assertTrue(ToolBatchPlan.duplicates(names, args).isEmpty())
    }

    @Test fun threeIdenticalCallsAllPointAtTheFirst() {
        val names = List(3) { "read_file" }
        val args = List(3) { "{}" }
        assertEquals(mapOf(1 to 0, 2 to 0), ToolBatchPlan.duplicates(names, args))
    }

    @Test fun theNoteIsQuietForOneWaveAndSpeaksForSeveral() {
        val single = ToolPlanner.plan("read it", setOf("read_file"))
        assertNull(ToolBatchPlan.note(single, listOf("read_file")))
        val multi = planOf("replace the string in the file", listOf("read_file", "write_file"))
        multi.waves().takeIf { it.size > 1 }?.let { waves ->
            val note = ToolBatchPlan.note(multi, listOf("read_file", "write_file"))
            assertTrue(note != null && note.contains("wave"))
            assertEquals(waves.size, Regex("wave").findAll(note!!).count())
        }
    }
}
