package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JudgeAggregateTest {

    @Test
    fun noVerdictsIsNull() {
        assertNull(JudgeAggregate.summarise(emptyList(), "accuracy"))
    }

    @Test
    fun agreeingJudgesAgree() {
        val vs = listOf(
            JudgeRubric.Verdict(mapOf("accuracy" to 4), ""),
            JudgeRubric.Verdict(mapOf("accuracy" to 4), ""),
        )
        val s = JudgeAggregate.summarise(vs, "accuracy")!!
        assertEquals(4, s.median)
        assertTrue(s.agreed)
    }

    @Test
    fun aWideSpreadIsDisagreement() {
        val vs = listOf(
            JudgeRubric.Verdict(mapOf("accuracy" to 1), ""),
            JudgeRubric.Verdict(mapOf("accuracy" to 5), ""),
        )
        val s = JudgeAggregate.summarise(vs, "accuracy")!!
        assertEquals(4, s.spread)
        assertFalse(s.agreed)
    }

    @Test
    fun anOutlierDoesNotDragTheMedian() {
        val vs = listOf(1, 4, 4, 4, 5).map { JudgeRubric.Verdict(mapOf("accuracy" to it), "") }
        assertEquals(4, JudgeAggregate.summarise(vs, "accuracy")!!.median)
    }
}
