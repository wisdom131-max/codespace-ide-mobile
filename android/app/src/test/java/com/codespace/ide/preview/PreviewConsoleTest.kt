package com.codespace.ide.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The console has to stay bounded and readable, even under a page that logs in a loop. */
class PreviewConsoleTest {

    @Test
    fun `entries are capped`() {
        PreviewConsole.clear()
        repeat(PreviewConsole.MAX_ENTRIES + 50) { PreviewConsole.add(PreviewConsole.Level.INFO, "line $it") }
        assertEquals(PreviewConsole.MAX_ENTRIES, PreviewConsole.entries.value.size)
        PreviewConsole.clear()
    }

    @Test
    fun `blank messages are ignored`() {
        PreviewConsole.clear()
        PreviewConsole.add(PreviewConsole.Level.ERROR, "   ")
        assertTrue(PreviewConsole.entries.value.isEmpty())
    }

    @Test
    fun `the summary counts what matters`() {
        PreviewConsole.clear()
        PreviewConsole.add(PreviewConsole.Level.ERROR, "boom")
        PreviewConsole.add(PreviewConsole.Level.ERROR, "boom again")
        PreviewConsole.add(PreviewConsole.Level.WARN, "careful")
        assertEquals("2 errors, 1 warning", PreviewConsole.summary())
        assertTrue(PreviewConsole.hasErrors)
        PreviewConsole.clear()
        assertFalse(PreviewConsole.hasErrors)
        assertEquals("Console", PreviewConsole.summary())
    }

    @Test
    fun `location never ends in a dangling colon`() {
        val noLine = PreviewConsole.Entry(1, PreviewConsole.Level.INFO, "m", "file.js", 0)
        assertEquals("file.js", noLine.location)
        val withLine = PreviewConsole.Entry(2, PreviewConsole.Level.INFO, "m", "file.js", 12)
        assertEquals("file.js:12", withLine.location)
        val none = PreviewConsole.Entry(3, PreviewConsole.Level.INFO, "m")
        assertEquals("", none.location)
    }
}
