package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexEntryTest {

    @Test
    fun thePathIsNormalised() {
        assertEquals("app/Main.kt", IndexEntry.normalisePath("./app\\Main.kt"))
    }

    @Test
    fun aSmallFileIsIndexedWhole() {
        val e = IndexEntry.of("a.kt", "one\ntwo")
        assertEquals("one\ntwo", e.body)
    }

    @Test
    fun aLargeFileIsCutOnALineBoundary() {
        val source = (1..100).joinToString("\n") { "line " + it }
        val e = IndexEntry.of("a.kt", source, maxChars = 40)
        assertTrue(e.body.length <= 40)
        assertTrue("the last kept line must be whole", e.body.trimEnd().lines().all { it.startsWith("line ") })
        assertFalse(e.body.contains("line 100"))
    }
}
