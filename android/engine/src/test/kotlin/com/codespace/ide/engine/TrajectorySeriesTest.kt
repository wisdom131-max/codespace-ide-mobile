package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class TrajectorySeriesTest {

    @Test
    fun cleanTurnsAndFailuresAreBothCounted() {
        // A turn with failed calls but no claim of being done is still clean by the scorer's rule:
        // only claiming done while calls were failing is a defect. The second turn claims done.
        val turns = listOf(
            TrajectoryEvaluator.Turn(3, 0, true, false),
            TrajectoryEvaluator.Turn(4, 2, true, false),
        )
        val s = TrajectorySeries.summarise(turns)
        assertEquals(2, s.turns)
        assertEquals(1, s.clean)
        assertEquals(2, s.failedCalls)
        assertEquals(50, s.cleanRate)
    }

    @Test
    fun anEmptySeriesIsZeroNotACrash() {
        val s = TrajectorySeries.summarise(emptyList())
        assertEquals(0, s.turns)
        assertEquals(0, s.cleanRate)
    }
}
