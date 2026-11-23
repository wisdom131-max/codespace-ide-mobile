package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class RoutedBudgetTest {

    @Test
    fun aLightQuestionSpendsNoThinkingBudget() {
        assertEquals(0, RoutedBudget.forTier(ComplexityRouter.Tier.LIGHT, 1024))
    }

    @Test
    fun aStandardQuestionKeepsWhatTheCallerAskedFor() {
        assertEquals(1024, RoutedBudget.forTier(ComplexityRouter.Tier.STANDARD, 1024))
    }

    @Test
    fun aHeavyQuestionGetsDoubleButNeverRunsAway() {
        assertEquals(2048, RoutedBudget.forTier(ComplexityRouter.Tier.HEAVY, 1024))
        assertEquals(RoutedBudget.MAX_HEAVY, RoutedBudget.forTier(ComplexityRouter.Tier.HEAVY, 9000))
    }
}
