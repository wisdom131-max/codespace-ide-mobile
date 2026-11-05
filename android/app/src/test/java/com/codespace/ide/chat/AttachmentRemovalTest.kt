package com.codespace.ide.chat

import com.codespace.ide.chat.panel.PendingRemoval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Owner item 5: one X removes one attachment, and nothing else. */
class AttachmentRemovalTest {

    @Test
    fun removingOneLeavesTheOthersInOrder() {
        val pending = listOf("Main.kt", "settings.gradle", "notes.md")
        assertEquals(listOf("Main.kt", "notes.md"), PendingRemoval.withoutAt(pending, 1))
    }

    @Test
    fun theSameFileAttachedTwiceLosesOneRowNotBoth() {
        // The old name-based filter deleted every chip carrying that name.
        val pending = listOf("stack.txt", "Main.kt", "stack.txt")
        assertEquals(listOf("stack.txt", "Main.kt"), PendingRemoval.withoutAt(pending, 2))
        assertEquals(listOf("Main.kt", "stack.txt"), PendingRemoval.withoutAt(pending, 0))
    }

    @Test
    fun aStaleIndexRemovesNothing() {
        val pending = listOf("a.txt", "b.txt")
        assertSame(pending, PendingRemoval.withoutAt(pending, -1))
        assertSame(pending, PendingRemoval.withoutAt(pending, 2))
        val empty = emptyList<String>()
        assertSame(empty, PendingRemoval.withoutAt(empty, 0))
    }
}
