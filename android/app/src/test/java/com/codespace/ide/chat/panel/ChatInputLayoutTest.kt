package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U1 (build 657 finding e) — the input layout decision, tested at the widths the
 * advisor named (280 / 360 / 480 dp) with the keyboard closed and open.
 *
 * These are the real decisions the UI renders from: ChatInputComposer calls
 * [ChatInputLayout.forWidth] with the measured width and the live IME inset, and
 * renders exactly what comes back. So a regression here is a regression on the
 * device, not a test-only artifact.
 */
class ChatInputLayoutTest {

    // ---- keyboard CLOSED -------------------------------------------------

    @Test
    fun splitView280_stacksTheField() {
        val s = ChatInputLayout.forWidth(280, imeVisible = false)
        assertEquals(ChatInputMode.STACKED, s.mode)
        assertEquals(ChatInputLayout.FIELD_MIN_DP, s.fieldMinWidthDp)
        assertTrue("280dp must shorten labels, not clip them", s.shortLabels)
    }

    @Test
    fun phone360_keepsTheInlineRow() {
        // 360dp is the common phone width in portrait: field + two short actions fit,
        // and height is the scarce resource, so the row stays inline.
        val s = ChatInputLayout.forWidth(360, imeVisible = false)
        assertEquals(ChatInputMode.INLINE, s.mode)
        assertTrue(s.shortLabels)
        assertEquals(3, s.fieldMaxLines)
    }

    @Test
    fun tablet480_givesFullLabelsAndATallerField() {
        val s = ChatInputLayout.forWidth(480, imeVisible = false)
        assertEquals(ChatInputMode.INLINE, s.mode)
        assertEquals(5, s.fieldMaxLines)
        assertEquals("480dp has room for the full action label", false, s.shortLabels)
    }

    // ---- keyboard OPEN ---------------------------------------------------

    @Test
    fun phone360WithKeyboard_stacksTheField() {
        // The keyboard halves the height. A 4-line field above it hides the reply the
        // user is answering, so the layout stacks at 400dp and drops to 2 lines.
        val s = ChatInputLayout.forWidth(360, imeVisible = true)
        assertEquals(ChatInputMode.STACKED, s.mode)
        assertEquals(2, s.fieldMaxLines)
    }

    @Test
    fun phone280WithKeyboard_isStillUsable() {
        val s = ChatInputLayout.forWidth(280, imeVisible = true)
        assertEquals(ChatInputMode.STACKED, s.mode)
        assertEquals(2, s.fieldMaxLines)
        assertEquals(ChatInputLayout.FIELD_MIN_DP, s.fieldMinWidthDp)
    }

    @Test
    fun tablet480WithKeyboard_keepsTheRowButShrinksTheField() {
        val s = ChatInputLayout.forWidth(480, imeVisible = true)
        assertEquals(ChatInputMode.INLINE, s.mode)
        assertEquals(2, s.fieldMaxLines)
    }

    // ---- the guarantees that make the sliver impossible -------------------

    @Test
    fun theFieldAlwaysKeepsAUsableFloor() {
        for (w in 0..1200 step 40) {
            for (ime in listOf(false, true)) {
                val s = ChatInputLayout.forWidth(w, ime)
                assertTrue(
                    "field floor collapsed at ${w}dp (ime=$ime)",
                    s.fieldMinWidthDp >= ChatInputLayout.FIELD_MIN_DP,
                )
                assertTrue("field must keep at least one line", s.fieldMaxLines >= 1)
            }
        }
    }

    @Test
    fun ultraNarrowStacksTheActionsToo() {
        val s = ChatInputLayout.forWidth(200, imeVisible = false)
        assertTrue("below 240dp two action labels cannot sit side by side", s.actionsStackVertically)
        assertEquals(ChatInputMode.STACKED, s.mode)
    }

    @Test
    fun actionsDoNotStackWhenThereIsRoom() {
        for (w in 240..1200 step 40) {
            assertFalse("actions stacked needlessly at ${w}dp", ChatInputLayout.forWidth(w, false).actionsStackVertically)
        }
    }

    @Test
    fun degenerateWidthsDoNotCrash() {
        // A not-yet-measured BoxWithConstraints reports 0; negative is defensive.
        assertEquals(ChatInputMode.STACKED, ChatInputLayout.forWidth(0, false).mode)
        assertEquals(ChatInputMode.STACKED, ChatInputLayout.forWidth(-1, false).mode)
        assertTrue(ChatInputLayout.forWidth(0, true).actionsStackVertically)
    }
}
