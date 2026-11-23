package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRankTest {

    @Test
    fun nothingFoundIsAnEmptyString() {
        assertEquals("", SearchRank.rank("anything", emptyList(), emptyList()))
    }

    @Test
    fun oneFileIsOneResultWithItsBestLinesUnderIt() {
        val out = SearchRank.rank(
            "gradle build",
            listOf("a/Foo.kt:3: gradle build failed", "a/Foo.kt:9: gradle build retry", "b/Bar.kt:1: gradle build ok"),
            emptyList(),
        )
        assertEquals("two files, so two blocks", 2, out.split("\n\n").size)
        assertTrue("the file header is present", out.contains("a/Foo.kt"))
    }

    @Test
    fun aMatchIsRecordedInTheEntityGraph() {
        SearchRank.rank("gradle", listOf("c/Baz.kt:1: gradle"), emptyList())
        assertTrue("the graph knows what the search touched", EntityGraph.objects("c/Baz.kt", "matched").isNotEmpty())
    }
}
