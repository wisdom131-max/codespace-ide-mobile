package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnswerCacheTest {

    @Test
    fun anIdenticalQuestionIsAnsweredFromTheCache() {
        val c = AnswerCache.Cache()
        c.put("what is the budget", "gemini", "v1", "8000", 1L)
        assertEquals("8000", c.get("what is the budget", "gemini", "v1"))
        assertEquals(1, c.hits)
    }

    @Test
    fun aDifferentModelIsADifferentQuestion() {
        val c = AnswerCache.Cache()
        c.put("q", "gemini", "v1", "a", 1L)
        assertNull(c.get("q", "anthropic", "v1"))
    }

    @Test
    fun aStaleStampIsAMissAndEvicts() {
        val c = AnswerCache.Cache()
        c.put("q", "gemini", "v1", "a", 1L)
        assertNull("the project moved on", c.get("q", "gemini", "v2"))
        assertEquals(0, c.size())
    }

    @Test
    fun theOldestIsEvictedFirst() {
        val c = AnswerCache.Cache(maxEntries = 2)
        c.put("a", "m", "v1", "1", 1L)
        c.put("b", "m", "v1", "2", 2L)
        c.put("c", "m", "v1", "3", 3L)
        assertEquals(2, c.size())
        assertNull(c.get("a", "m", "v1"))
    }

    @Test
    fun aRepeatedHitIsNotEvictedByNoise() {
        val c = AnswerCache.Cache(maxEntries = 2)
        c.put("hot", "m", "v1", "1", 1L)
        c.put("cold", "m", "v1", "2", 2L)
        c.get("hot", "m", "v1")
        c.put("new", "m", "v1", "3", 3L)
        assertEquals("1", c.get("hot", "m", "v1"))
    }
}
