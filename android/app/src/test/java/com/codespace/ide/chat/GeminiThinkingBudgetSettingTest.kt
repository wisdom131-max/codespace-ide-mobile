package com.codespace.ide.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 1a (advisor 2026-10-26): the bounded-thinking default is the core of the
 * Gemini fix — the vendor default (dynamic, up to 8,192 reasoning tokens)
 * was the slow-full-prompt root cause. These pins keep the DEFAULT bounded
 * and the label honest.
 */
class GeminiThinkingBudgetSettingTest {
    @Test fun default_isBounded() {
        GeminiThinkingBudgetSetting.setForTest(GeminiThinkingBudgetSetting.BOUNDED)
        assertEquals(GeminiThinkingBudgetSetting.BOUNDED, GeminiThinkingBudgetSetting.current())
    }

    @Test fun offAndDynamic_roundTrip() {
        GeminiThinkingBudgetSetting.setForTest(GeminiThinkingBudgetSetting.OFF)
        assertEquals(GeminiThinkingBudgetSetting.OFF, GeminiThinkingBudgetSetting.current())
        GeminiThinkingBudgetSetting.setForTest(GeminiThinkingBudgetSetting.DYNAMIC)
        assertEquals(GeminiThinkingBudgetSetting.DYNAMIC, GeminiThinkingBudgetSetting.current())
        // restore the default so test order can never leak state
        GeminiThinkingBudgetSetting.setForTest(GeminiThinkingBudgetSetting.BOUNDED)
    }

    @Test fun labels_areHonest() {
        assertEquals("Off (no thinking)", GeminiThinkingBudgetSetting.label(GeminiThinkingBudgetSetting.OFF))
        assertEquals("Dynamic (vendor default)", GeminiThinkingBudgetSetting.label(GeminiThinkingBudgetSetting.DYNAMIC))
    }
}
