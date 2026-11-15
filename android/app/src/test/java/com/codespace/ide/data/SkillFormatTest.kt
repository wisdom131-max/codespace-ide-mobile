package com.codespace.ide.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 6a: a skill's enabled flag survives a round trip, an older file restores as enabled, and a
 * disabled skill is absent from the block the model reads.
 */
class SkillFormatTest {

    private fun record(enabled: Boolean = true) = SkillFormat.Record(
        slug = "run-the-tests",
        name = "Run the tests",
        description = "runs the gradle suite",
        body = "step 1",
        enabled = enabled,
    )

    @Test
    fun aSkillRoundTripsWithItsEnabledFlag() {
        val back = SkillFormat.decode(SkillFormat.encode(record(enabled = false)), "fallback")
        assertNotNull(back)
        assertEquals("Run the tests", back!!.name)
        assertEquals("runs the gradle suite", back.description)
        assertEquals("step 1", back.body)
        assertFalse(back.enabled)
    }

    @Test
    fun aFileWrittenBeforeThisPhaseRestoresAsEnabled() {
        val legacy = """{"name":"Old","description":"d","body":"b","slug":"old"}"""
        val back = SkillFormat.decode(legacy, "old")
        assertNotNull(back)
        assertTrue("an existing skill must not disappear on upgrade", back!!.enabled)
    }

    @Test
    fun malformedTextIsNullRatherThanHalfASkill() {
        assertEquals(null, SkillFormat.decode("{not json", "x"))
    }

    @Test
    fun theIndexOmitsDisabledSkillsEntirely() {
        val lines = SkillFormat.indexLines(listOf(record(true), record(false)))
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("Run the tests"))
        assertTrue(lines[0].contains("runs the gradle suite"))
    }

    @Test
    fun aSkillWithNoDescriptionIsStillOneLine() {
        val bare = SkillFormat.Record("s", "Bare", "", "b")
        assertEquals(listOf("- Bare"), SkillFormat.indexLines(listOf(bare)))
    }
}
