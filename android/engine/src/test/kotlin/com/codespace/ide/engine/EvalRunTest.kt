package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalRunTest {

    private fun case(id: String) = EvalDataset.EvalCase(id, "ask " + id, "answer " + id)

    @Test
    fun theReportJoinsAllThreeScores() {
        val cases = (1..10).map { case("c$it") }
        val verdicts = listOf(
            JudgeRubric.Verdict(mapOf("overall" to 4), "good"),
            JudgeRubric.Verdict(mapOf("overall" to 5), "good"),
        )
        val turns = listOf(
            TrajectoryEvaluator.Turn(2, 0, true, false),
            TrajectoryEvaluator.Turn(3, 0, true, false),
        )
        val report = EvalRun.run(cases, verdicts, turns)
        assertEquals(8, report.trainCases)
        assertEquals(2, report.holdoutCases)
        assertEquals(100, report.cleanRate)
        assertEquals(0, report.wastedCalls)
        assertTrue(report.healthy)
    }

    @Test
    fun wastedCallsAndDisagreementMakeItUnhealthy() {
        val verdicts = listOf(
            JudgeRubric.Verdict(mapOf("overall" to 1), "poor"),
            JudgeRubric.Verdict(mapOf("overall" to 5), "great"),
        )
        val turns = listOf(TrajectoryEvaluator.Turn(4, 2, true, false))
        val report = EvalRun.run(emptyList(), verdicts, turns)
        assertEquals(0, report.trainCases)
        assertEquals(2, report.wastedCalls)
        assertFalse(report.healthy)
        assertTrue(report.render().contains("needs attention"))
    }

    @Test
    fun anEmptyRunIsNotCalledHealthy() {
        val report = EvalRun.run(emptyList(), emptyList(), emptyList())
        assertFalse(report.healthy)
        assertEquals(0, report.cleanRate)
    }
}
