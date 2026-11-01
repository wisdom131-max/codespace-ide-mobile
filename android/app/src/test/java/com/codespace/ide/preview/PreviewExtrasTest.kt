package com.codespace.ide.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Custom CSS is injected into a <style> element, so it must not be able to close it. */
class PreviewThemeTest {

    @Test
    fun aClosingStyleTagIsNeutralised() {
        val hostile = "h1{color:red}</style><script>alert(1)</script>"
        val safe = PreviewTheme.sanitize(hostile)
        assertFalse(safe.contains("</style"))
    }

    @Test
    fun ordinaryCssIsLeftAlone() {
        assertEquals("h1 { color: #4FC3F7 }", PreviewTheme.sanitize("h1 { color: #4FC3F7 }"))
    }
}

/** The scroll ratio has to survive every caret position the editor can report. */
class PreviewScrollSyncTest {

    @Test
    fun theDocumentEndsAreExact() {
        assertEquals(0.0, PreviewScrollSync.ratio(0, 10), 0.0001)
        assertEquals(1.0, PreviewScrollSync.ratio(9, 10), 0.0001)
    }

    @Test
    fun outOfRangeAndEmptyDocumentsAreClampedNotDivided() {
        assertEquals(0.0, PreviewScrollSync.ratio(5, 1), 0.0001)
        assertEquals(0.0, PreviewScrollSync.ratio(0, 0), 0.0001)
        assertEquals(1.0, PreviewScrollSync.ratio(500, 10), 0.0001)
    }
}
