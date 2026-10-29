package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec section 4: the border is blue exactly when there is text, and never moves the card. */
class ChatComposerBorderTest {

    @Test
    fun emptyInputKeepsTheNeutralBorder() {
        assertEquals(ComposerBorder.NEUTRAL, ChatComposerBorder.forText(""))
    }

    @Test
    fun typingTurnsTheBorderBlue() {
        assertEquals(ComposerBorder.ACTIVE, ChatComposerBorder.forText("a"))
        assertEquals(ComposerBorder.ACTIVE, ChatComposerBorder.forText("hello world"))
    }

    @Test
    fun deletingEverythingRestoresTheNeutralBorder() {
        // Simulates type -> keep typing -> delete all, in order. A one-time change that
        // stays blue would fail the last step.
        val states = listOf("h", "he", "hel", "", "x", "").map { ChatComposerBorder.forText(it) }
        assertEquals(
            listOf(
                ComposerBorder.ACTIVE, ComposerBorder.ACTIVE, ComposerBorder.ACTIVE,
                ComposerBorder.NEUTRAL, ComposerBorder.ACTIVE, ComposerBorder.NEUTRAL,
            ),
            states,
        )
    }

    @Test
    fun pastedTextCountsTheSameAsTypedText() {
        // A paste is just a value that jumps from empty to long in one step.
        assertEquals(ComposerBorder.ACTIVE, ChatComposerBorder.forText("line one\nline two\n".repeat(50)))
    }

    @Test
    fun whitespaceOnlyCountsAsEmpty() {
        for (blank in listOf(" ", "   ", "\n", "\t", " \n \t ")) {
            assertEquals("'$blank'", ComposerBorder.NEUTRAL, ChatComposerBorder.forText(blank))
        }
    }

    @Test
    fun theStrokeWidthIsOneConstantSoTheCardCannotJump() {
        // Both states draw the same width; only the colour differs.
        assertEquals(1, ChatComposerBorder.WIDTH_DP)
    }
}
