package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaCheckTest {

    private val fields = listOf(
        SchemaCheck.Field("path", SchemaCheck.Type.STRING, maxLength = 5),
        SchemaCheck.Field("lines", SchemaCheck.Type.NUMBER),
        SchemaCheck.Field("ok", SchemaCheck.Type.BOOLEAN, required = false),
    )

    @Test
    fun aGoodResultHasNoProblems() {
        val v = mapOf("path" to "a.kt", "lines" to "12")
        assertTrue(SchemaCheck.check(v, fields).isEmpty())
    }

    @Test
    fun aMissingFieldIsNamed() {
        val problems = SchemaCheck.check(mapOf("path" to "a.kt"), fields)
        assertEquals("lines", problems.single().field)
        assertEquals("missing", problems.single().reason)
    }

    @Test
    fun aWrongTypeIsNamed() {
        val problems = SchemaCheck.check(mapOf("path" to "a.kt", "lines" to "many"), fields)
        assertEquals("not a number", problems.single().reason)
    }

    @Test
    fun anOverlongFieldIsNamed() {
        val problems = SchemaCheck.check(mapOf("path" to "toolong.kt", "lines" to "1"), fields)
        assertTrue(problems.single().reason.contains("longer than 5"))
    }

    @Test
    fun anOptionalFieldMayBeAbsent() {
        val problems = SchemaCheck.check(mapOf("path" to "a.kt", "lines" to "1"), fields)
        assertTrue(problems.isEmpty())
    }
}
