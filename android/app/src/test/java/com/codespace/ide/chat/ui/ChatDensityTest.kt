package com.codespace.ide.chat.ui

import com.codespace.ide.chat.panel.sessions.SessionsPanelSpec
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Items 2 and 6 — compactness with the touch target kept.
 *
 * The reason these are tests and not just numbers in the source: the easy way to make a row
 * compact is to shrink its tap area, which silently breaks accessibility. These assertions make
 * "compact" mean "less empty space", never "smaller target".
 */
class ChatDensityTest {

    @Test
    fun theHeaderRowPadsLessThanItDidButKeepsItsChipsTappable() {
        assertTrue(
            "item 6: header row vertical padding must be visual breathing room only",
            ChatTokens.NAV_ROW_PAD_VERTICAL <= 4,
        )
        assertTrue(
            "the chip inside the row keeps the full 40 dp target",
            SessionsPanelSpec.TOUCH_TARGET_DP >= 40,
        )
    }

    @Test
    fun theNewSessionPillIsShorterThanItsTouchTarget() {
        assertTrue(
            "item 2: the visible pill is compact",
            ChatTokens.NEW_SESSION_VISUAL_HEIGHT < SessionsPanelSpec.TOUCH_TARGET_DP,
        )
        assertTrue(
            "but still a comfortable size, not a hairline",
            ChatTokens.NEW_SESSION_VISUAL_HEIGHT >= 28,
        )
    }

    @Test
    fun theLabelStaysAtReadableSmallTextRatherThanBodySize() {
        assertTrue(
            "the row label drops from body to small text instead of shrinking to micro",
            ChatTokens.FONT_SMALL < ChatTokens.FONT_BODY && ChatTokens.FONT_SMALL > ChatTokens.FONT_MICRO,
        )
    }
}
