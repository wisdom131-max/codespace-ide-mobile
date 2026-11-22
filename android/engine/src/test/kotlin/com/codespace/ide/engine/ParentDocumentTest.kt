package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParentDocumentTest {

    private val chunks = listOf(
        ParentDocument.Chunk("c1", "p1", "half a function"),
        ParentDocument.Chunk("c2", "p1", "the other half"),
        ParentDocument.Chunk("c3", "p2", "a different file"),
    )
    private val parents = listOf(
        ParentDocument.Parent("p1", "whole function"),
        ParentDocument.Parent("p2", "whole other file"),
    )

    @Test
    fun twoHitsInOneParentReturnItOnce() {
        val out = ParentDocument.expand(listOf("c1", "c2"), chunks, parents)
        assertEquals(1, out.size)
        assertEquals("p1", out.single().id)
    }

    @Test
    fun theBudgetIsRespected() {
        val out = ParentDocument.expand(listOf("c1", "c3"), chunks, parents, maxChars = 15)
        assertEquals(1, out.size)
    }

    @Test
    fun anUnknownHitIsIgnored() {
        assertTrue(ParentDocument.expand(listOf("nope"), chunks, parents).isEmpty())
    }
}
