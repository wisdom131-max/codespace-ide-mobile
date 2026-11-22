package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlackboardTest {

    @Test
    fun aNoteCanBeReadBack() {
        val b = Blackboard.Board()
        b.write("finding", "the bug is in history", "step-2", 1000L)
        assertEquals("the bug is in history", b.read("finding")?.value)
    }

    @Test
    fun sinceReturnsOnlyNewerNotes() {
        val b = Blackboard.Board()
        val first = b.write("a", "1", "s1", 1L)
        b.write("b", "2", "s2", 2L)
        assertEquals(listOf("b"), b.since(first.seq).map { it.key })
    }

    @Test
    fun overwritingKeepsOneNoteAndBumpsTheSequence() {
        val b = Blackboard.Board()
        val first = b.write("a", "old", "s1", 1L)
        val second = b.write("a", "new", "s2", 2L)
        assertEquals(1, b.size())
        assertEquals("new", b.read("a")?.value)
        assertEquals(first.seq + 1, second.seq)
    }

    @Test
    fun readingAMissingKeyIsNull() {
        assertNull(Blackboard.Board().read("nothing"))
    }
}

class BlackboardOrderTest {

    @Test
    fun anOverwrittenNoteSortsByItsNewSequence() {
        val b = Blackboard.Board()
        b.write("a", "first", "s1", 1L)
        b.write("b", "second", "s2", 2L)
        b.write("a", "rewritten", "s3", 3L)
        assertEquals(listOf("b", "a"), b.snapshot().map { it.key })
    }

    @Test
    fun sinceIsInSequenceOrder() {
        val b = Blackboard.Board()
        val first = b.write("a", "1", "s1", 1L)
        b.write("b", "2", "s2", 2L)
        b.write("a", "3", "s3", 3L)
        assertEquals(listOf("b", "a"), b.since(first.seq).map { it.key })
    }
}
