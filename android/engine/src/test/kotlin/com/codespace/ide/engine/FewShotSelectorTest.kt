package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FewShotSelectorTest {

    private val gradle = FewShotSelector.Example("g", "how to add a gradle dependency")
    private val compose = FewShotSelector.Example("c", "compose preview not rendering")
    private val tagged = FewShotSelector.Example("t", "unrelated words here", setOf("gradle"))

    @Test
    fun anUnrelatedExampleIsNeverUsed() {
        assertTrue("a bad example is worse than none", FewShotSelector.select("gradle dependency", listOf(compose), 3).isEmpty())
    }

    @Test
    fun theClosestExampleComesFirst() {
        assertEquals("g", FewShotSelector.select("gradle dependency", listOf(compose, gradle), 2).first().id)
    }

    @Test
    fun kIsRespected() {
        assertEquals(1, FewShotSelector.select("gradle dependency", listOf(gradle, tagged), 1).size)
    }

    @Test
    fun tagsCountAsWords() {
        assertEquals("t", FewShotSelector.select("gradle", listOf(tagged), 1).single().id)
    }
}
