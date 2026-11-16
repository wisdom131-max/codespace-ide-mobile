package com.codespace.ide.chat.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PROJECT SEVEN 7.9A — the palette is measured against WCAG AA, for both themes. */
class Stage79ContrastTest {

    @Test
    fun `the ratio formula matches known values`() {
        // Black on white is 21:1 by definition; identical colours are 1:1.
        assertEquals(21.0, ContrastAudit.ratio(Color(0xFF000000), Color(0xFFFFFFFF)), 0.01)
        assertEquals(1.0, ContrastAudit.ratio(Color(0xFF777777), Color(0xFF777777)), 0.01)
    }

    @Test
    fun `every text pair in the shipped dark palette meets AA`() {
        val bad = ContrastAudit.problems(ChatTokens.dark)
        assertTrue("dark palette contrast problems: $bad", bad.isEmpty())
    }

    @Test
    fun `every text pair in the shipped light palette meets AA`() {
        val bad = ContrastAudit.problems(ChatTokens.light)
        assertTrue("light palette contrast problems: $bad", bad.isEmpty())
    }

    @Test
    fun `a meaning-bearing border derived from the palette reaches 3 to 1`() {
        val dark = ContrastAudit.strongBorder(ChatTokens.dark, ChatTokens.dark.surface, dark = true)
        val light = ContrastAudit.strongBorder(ChatTokens.light, ChatTokens.light.surface, dark = false)
        assertTrue(
            "derived dark border = " + "%.2f".format(ContrastAudit.ratio(dark, ChatTokens.dark.surface)),
            ContrastAudit.ratio(dark, ChatTokens.dark.surface) >= 3.0,
        )
        assertTrue(
            "derived light border = " + "%.2f".format(ContrastAudit.ratio(light, ChatTokens.light.surface)),
            ContrastAudit.ratio(light, ChatTokens.light.surface) >= 3.0,
        )
    }

    @Test
    fun `the hairline divider is documented as decorative, not silently dropped`() {
        val hairline = ContrastAudit.ratio(ChatTokens.dark.border, ChatTokens.dark.surface)
        assertTrue("the divider stays a hairline: " + "%.2f".format(hairline), hairline < 3.0)
    }

    @Test
    fun `the audit reports the pairs it measured rather than a verdict alone`() {
        val text = ContrastAudit.describe(ChatTokens.dark)
        assertTrue(text.contains("body text on background"))
        assertTrue(text.contains(":1"))
        assertEquals(ContrastAudit.pairs(ChatTokens.dark).size, text.split(";").size)
    }
}
