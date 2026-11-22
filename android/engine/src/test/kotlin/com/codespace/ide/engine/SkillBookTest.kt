package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillBookTest {

    private val gradle = SkillRecord.Skill("gradle fix", listOf("sync", "clean build"))

    @Test
    fun aNewSkillIsLearned() {
        val b = SkillBook.Book()
        assertTrue(b.learn(gradle))
        assertEquals(1, b.size())
    }

    @Test
    fun learningTheSameNameTwiceReportsFalse() {
        val b = SkillBook.Book()
        b.learn(gradle)
        assertFalse("the old one is not lost silently", b.learn(gradle))
    }

    @Test
    fun aMatchingRequestFindsTheSkill() {
        val b = SkillBook.Book()
        b.learn(gradle)
        assertEquals("gradle fix", b.match("gradle sync is broken")?.name)
    }

    @Test
    fun anUnrelatedRequestFindsNothing() {
        val b = SkillBook.Book()
        b.learn(gradle)
        assertNull(b.match("compose preview"))
    }

    @Test
    fun usingASkillCountsIt() {
        val b = SkillBook.Book()
        b.learn(gradle)
        assertEquals(1, b.recordUse("gradle fix")?.uses)
        assertNull(b.recordUse("nothing"))
    }
}
