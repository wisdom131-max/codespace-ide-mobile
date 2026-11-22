package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeChunkerTest {
    @Test fun splitsAtTopLevelDeclarations() {
        val src = "fun a() {\n}\nclass B {\n}\n"
        val c = CodeChunker.chunk(src)
        assertEquals(2, c.size)
        assertTrue(c[0].header.startsWith("fun a"))
        assertTrue(c[1].header.startsWith("class B"))
    }
    @Test fun wholeFileWhenNoDeclarations() {
        assertEquals(1, CodeChunker.chunk("val x = 1\nval y = 2").size)
    }
}
