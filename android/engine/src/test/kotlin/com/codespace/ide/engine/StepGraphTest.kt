package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StepGraphTest {

    @Test
    fun aDependencyRunsFirst() {
        val r = StepGraph.order(listOf(StepGraph.Node("b", listOf("a")), StepGraph.Node("a")))
        assertEquals(listOf("a", "b"), (r as StepGraph.Result.Order).ids)
    }

    @Test
    fun aCycleIsReportedNotDropped() {
        val r = StepGraph.order(
            listOf(StepGraph.Node("a", listOf("b")), StepGraph.Node("b", listOf("a"))),
        )
        assertTrue(r is StepGraph.Result.Cycle)
        assertEquals(listOf("a", "b"), (r as StepGraph.Result.Cycle).ids)
    }

    @Test
    fun aDependencyFromAnEarlierTurnIsIgnored() {
        val r = StepGraph.order(listOf(StepGraph.Node("a", listOf("finished-yesterday"))))
        assertEquals(listOf("a"), (r as StepGraph.Result.Order).ids)
    }

    @Test
    fun independentStepsAllAppear() {
        val r = StepGraph.order(listOf(StepGraph.Node("b"), StepGraph.Node("a")))
        assertEquals(listOf("a", "b"), (r as StepGraph.Result.Order).ids)
    }
}
