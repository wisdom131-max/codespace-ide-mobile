package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskLedgerTest {

    @Test
    fun anEmptyLedgerCostsNoPrompt() {
        assertEquals("", TaskLedger().render())
    }

    @Test
    fun completedItemsAreShownButNotOwed() {
        val l = TaskLedger()
        l.set(listOf("read the file", "apply the edit"))
        assertTrue(l.complete("t1"))
        val text = l.render()
        assertTrue(text.contains("[done] read the file"))
        assertTrue(text.contains("[ ] apply the edit"))
        assertEquals(1, l.outstanding().size)
    }

    @Test
    fun aFullyDoneLedgerRendersNothing() {
        val l = TaskLedger()
        l.set(listOf("only step"))
        l.complete("t1")
        assertEquals("", l.render())
    }

    @Test
    fun completingAnUnknownIdChangesNothing() {
        val l = TaskLedger()
        l.set(listOf("one"))
        assertFalse(l.complete("nope"))
        assertEquals(1, l.outstanding().size)
    }

    @Test
    fun theLedgerIsBoundedAndAdmitsIt() {
        val l = TaskLedger(maxItems = 3)
        l.set(listOf("a", "b", "c", "d", "e"))
        assertEquals(3, l.size())
        assertEquals(2, l.droppedCount())
        assertTrue(l.render().contains("not listed"))
    }

    @Test
    fun reSettingKeepsADoneItemOnlyWhenItsTextIsUnchanged() {
        val l = TaskLedger()
        l.set(listOf("read the file"))
        l.complete("t1")
        l.set(listOf("read the file"))
        assertTrue("same text keeps done", l.outstanding().isEmpty())
        l.set(listOf("read a different file"))
        assertEquals("changed text is a new step", 1, l.outstanding().size)
    }
}
