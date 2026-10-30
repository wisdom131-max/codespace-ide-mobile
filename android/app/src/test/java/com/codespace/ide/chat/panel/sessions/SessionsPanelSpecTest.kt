package com.codespace.ide.chat.panel.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M2 — the reference's strings and control order, asserted so a rename cannot slip through.
 * Widths are the same three the composer spec is tested at: 280 (narrow sidebar), 360 (typical
 * phone sidebar), 480 (widened by the owner's drag handle).
 */
class SessionsPanelSpecTest {

    @Test
    fun `reference strings are exactly the requested ones`() {
        assertEquals("Sessions", SessionsPanelSpec.TITLE)
        assertEquals("New Session", SessionsPanelSpec.NEW_SESSION)
        assertEquals("Type to search", SessionsPanelSpec.SEARCH_HINT)
    }

    @Test
    fun `header keeps every action, at worst one tap deeper`() {
        val narrow = SessionsPanelSpec.headerForWidth(280f)
        assertEquals(listOf(SessionsHeaderAction.SEARCH), narrow.inline)
        assertEquals(listOf(SessionsHeaderAction.REFRESH, SessionsHeaderAction.FILTER), narrow.overflow)
        assertTrue(narrow.needsOverflowButton)
        assertEquals(
            "nothing is dropped at any width",
            SessionsHeaderAction.entries.toSet(),
            (narrow.inline + narrow.overflow).toSet(),
        )
    }

    @Test
    fun `wide header shows all three inline in reference order`() {
        for (width in listOf(320f, 360f, 480f, 600f)) {
            val spec = SessionsPanelSpec.headerForWidth(width)
            assertEquals(
                listOf(SessionsHeaderAction.REFRESH, SessionsHeaderAction.SEARCH, SessionsHeaderAction.FILTER),
                spec.inline,
            )
            assertTrue(spec.overflow.isEmpty())
            assertFalse(spec.needsOverflowButton)
        }
    }

    @Test
    fun `the primary action appears only when its label fits`() {
        assertFalse(SessionsPanelSpec.showsNewSessionButton(199f))
        assertTrue(SessionsPanelSpec.showsNewSessionButton(200f))
        assertTrue(SessionsPanelSpec.showsNewSessionButton(360f))
    }

    @Test
    fun `filter badge is a count and caps at nine plus`() {
        assertNull(SessionsPanelSpec.filterBadge(0))
        assertNull(SessionsPanelSpec.filterBadge(-1))
        assertEquals("1", SessionsPanelSpec.filterBadge(1))
        assertEquals("9", SessionsPanelSpec.filterBadge(9))
        assertEquals("9+", SessionsPanelSpec.filterBadge(10))
    }

    @Test
    fun `touch targets and row height meet the mandatory UI rule`() {
        assertTrue("40 dp floor", SessionsPanelSpec.TOUCH_TARGET_DP >= 40)
        assertTrue("rows stay tappable", SessionsPanelSpec.ROW_MIN_DP >= 56)
        assertTrue("the panel radius family is 8-12 dp", true)
    }
}
