package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class JudgeRubricTest {
    @Test fun parsesScoresAndReason() {
        val v = JudgeRubric.parse("correctness: 4" + 10.toChar() + "evidence: 2" + 10.toChar() + "reason: thin")
        assertEquals(4, v.scores["correctness"])
        assertEquals("thin", v.reason)
        assertFalse(JudgeRubric.passes(v))
    }
    @Test fun outOfRangeIsClamped() {
        assertEquals(5, JudgeRubric.parse("honesty: 99").scores["honesty"])
    }
}
