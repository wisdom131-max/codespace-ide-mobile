package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composer menu: it appears for the token being typed and for nothing else.
 *
 * Every case here is a way the menu could steal the field: mid-word slashes, a token from an
 * earlier line, an empty field.
 */
class ComposerCompletionsTest {

    @Test
    fun slashAtTheStartOfATokenOpensTheCommandList() {
        val menu = ComposerCompletions.menuFor("/")
        assertEquals(ComposerCompletions.commands, menu)
    }

    @Test
    fun atOpensParticipants() {
        val menu = ComposerCompletions.menuFor("@")
        assertEquals(ComposerCompletions.participants, menu)
    }

    @Test
    fun typingNarrowsTheList() {
        assertEquals(listOf("/clear"), ComposerCompletions.menuFor("/cl").map { it.token })
        assertEquals(listOf("@file"), ComposerCompletions.menuFor("@f").map { it.token })
    }

    @Test
    fun proseWithASlashInsideIsNotAMenu() {
        // The word does not start with the slash, so there is no token at all - which is the
        // point: "and/or" must stay prose, not become a half-typed command.
        assertNull(ComposerCompletions.activeToken("and/or"))
        assertTrue(ComposerCompletions.menuFor("and/or").isEmpty())
        assertTrue(ComposerCompletions.menuFor("see src/main for that").isEmpty())
    }

    @Test
    fun aTokenOnAnEarlierLineIsNotActive() {
        assertNull(ComposerCompletions.activeToken("/clear\nhello"))
        assertTrue(ComposerCompletions.menuFor("hello\nworld").isEmpty())
    }

    @Test
    fun anEmptyFieldShowsNothing() {
        assertNull(ComposerCompletions.activeToken(""))
        assertTrue(ComposerCompletions.menuFor("just a sentence").isEmpty())
    }

    @Test
    fun aParticipantIsInsertedInPlaceOfTheToken() {
        assertEquals("@file ", ComposerCompletions.applyTo("@fi", "@file"))
        assertEquals("look at @file ", ComposerCompletions.applyTo("look at @f", "@file"))
    }

    @Test
    fun theWorkspaceTokenIsNotInsertableBecauseItIsAlreadyAutomatic() {
        val ws = ComposerCompletions.participants.single { it.token == "@workspace" }
        assertTrue(ws.insertable.not())
        assertEquals(null, ws.command)
    }
}
