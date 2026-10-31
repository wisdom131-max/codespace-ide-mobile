package com.codespace.ide.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bulb has to say which kind of help is actually available. */
class LightbulbSpecTest {

    @Test
    fun `the icon follows what is on offer`() {
        assertEquals(BulbIcon.BULB, LightbulbSpec.iconFor(hasQuickFix = true, hasAiFix = false))
        assertEquals(BulbIcon.SPARKLE, LightbulbSpec.iconFor(hasQuickFix = false, hasAiFix = true))
        assertEquals(BulbIcon.SPARKLE_WITH_FIX, LightbulbSpec.iconFor(hasQuickFix = true, hasAiFix = true))
    }

    @Test
    fun `each state has its own glyph`() {
        val glyphs = BulbIcon.values().map { LightbulbSpec.glyph(it) }
        assertEquals(glyphs.size, glyphs.toSet().size)
    }

    @Test
    fun `ai kinds are recognised by prefix`() {
        assertTrue(LightbulbSpec.hasAiAction(listOf("quickfix", "ai.refactor")))
        assertFalse(LightbulbSpec.hasAiAction(listOf("quickfix", "source.organizeImports")))
        assertTrue(LightbulbSpec.hasAiAction(emptyList<String>()).not())
    }

    @Test
    fun `the menu is padded off the line it fixes`() {
        val padded = LightbulbSpec.menuPaddingDp(14f)
        assertTrue(padded > 0f)
        assertTrue(padded < 14f)
    }

    @Test
    fun `nothing that writes code is ever auto-run`() {
        assertFalse(LightbulbSpec.autoRunAllowed(writesCode = true))
        assertTrue(LightbulbSpec.autoRunAllowed(writesCode = false))
    }
}
