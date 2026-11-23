package com.codespace.ide.engine

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolArgCheckTest {

    @Test
    fun aMissingPathIsRefusedByName() {
        val p = ToolArgCheck.problem("read_file", mapOf("pattern" to "x"))
        assertTrue(p != null && p.contains("path"))
        assertTrue(p!!.contains("Nothing was run"))
    }

    @Test
    fun aCompleteCallIsAllowed() {
        assertNull(ToolArgCheck.problem("read_file", mapOf("path" to "/a/b.kt")))
    }

    @Test
    fun anUnknownToolIsNeverRefusedByGuesswork() {
        assertNull(ToolArgCheck.problem("some_future_tool", emptyMap()))
    }
}
