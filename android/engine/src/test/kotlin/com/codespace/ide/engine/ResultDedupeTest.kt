package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ResultDedupeTest {

    @Test
    fun oneResultPerFileKeepsTheBest() {
        val hits = listOf(
            ResultDedupe.Hit("A.kt", 0.4, "a1"),
            ResultDedupe.Hit("A.kt", 0.9, "a2"),
            ResultDedupe.Hit("B.kt", 0.5, "b1"),
        )
        val out = ResultDedupe.byPath(hits)
        assertEquals(listOf("a2", "b1"), out.map { it.id })
    }

    @Test
    fun firstSeenOrderIsPreserved() {
        val hits = listOf(
            ResultDedupe.Hit("B.kt", 0.1, "b"),
            ResultDedupe.Hit("A.kt", 0.9, "a"),
        )
        assertEquals(listOf("B.kt", "A.kt"), ResultDedupe.byPath(hits).map { it.path })
    }

    @Test
    fun nothingInNothingOut() {
        assertEquals(0, ResultDedupe.byPath(emptyList()).size)
    }
}
