package com.codespace.ide.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrajectoryEvaluatorTest {
    @Test fun cleanRunIsClean() {
        assertTrue(TrajectoryEvaluator.score(TrajectoryEvaluator.Turn(3, 0, true, false)).clean)
    }
    @Test fun claimingDoneWithFailuresIsNotClean() {
        val s = TrajectoryEvaluator.score(TrajectoryEvaluator.Turn(3, 2, true, false))
        assertFalse(s.clean)
        assertTrue(s.reasons.isNotEmpty())
    }
    @Test fun claimingDoneWithNoWorkIsNotClean() {
        assertFalse(TrajectoryEvaluator.score(TrajectoryEvaluator.Turn(0, 0, true, false)).clean)
    }
}
