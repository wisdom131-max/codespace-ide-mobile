package com.codespace.ide.agent.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT 1.6: one question in, the independent asks it actually contains out.
 *
 * The failure this guards against is the one the spec names first: an assistant that searches once
 * and treats the first page as definitive. The failure it is NOT allowed to introduce is splitting
 * ordinary prose into fragments that each cost the owner a search.
 */
class ResearchDecompositionTest {

    @Test
    fun oneAskStaysOneAsk() {
        assertEquals(emptyList<String>(), ResearchPlanner.decompose("Why is my Gradle build failing?"))
        assertEquals(emptyList<String>(), ResearchPlanner.decompose(""))
        assertEquals(emptyList<String>(), ResearchPlanner.decompose("   "))
    }

    @Test
    fun aComparisonNamesTwoAlternatives() {
        val parts = ResearchPlanner.decompose("Kotlin Flow vs LiveData for collecting state")
        assertEquals(2, parts.size)
        assertEquals("Kotlin Flow", parts[0])
        assertEquals("LiveData for collecting state", parts[1])
    }

    @Test
    fun versusAndComparedWithAreTheSameIntent() {
        assertEquals(2, ResearchPlanner.decompose("proot versus chroot").size)
        assertEquals(2, ResearchPlanner.decompose("Compose compared with Views").size)
    }

    @Test
    fun anAdditiveSplitDropsThinFragments() {
        // "how do I add" carries one content token, so splitting here would spend a search on a
        // fragment. One real ask survives, which is not two.
        assertEquals(emptyList<String>(), ResearchPlanner.decompose("how do I add and remove items"))
    }

    @Test
    fun twoRealAsksAreSplit() {
        val parts = ResearchPlanner.decompose(
            "does proot need link2symlink and how do I avoid symlinkat crashes")
        assertEquals(2, parts.size)
        assertTrue(parts[0].contains("proot"))
        assertTrue(parts[1].contains("symlinkat"))
    }

    @Test
    fun semicolonsSeparateAsks() {
        val parts = ResearchPlanner.decompose("what is a transcripts lock; when should it be released")
        assertEquals(2, parts.size)
    }

    @Test
    fun repeatsCollapseInsteadOfBecomingTwoSearches() {
        val parts = ResearchPlanner.decompose("gradle build failing and gradle build failing on device")
        assertTrue("near-identical parts must collapse, got " + parts, parts.size <= 1)
    }

    @Test
    fun theCapIsRespected() {
        val parts = ResearchPlanner.decompose(
            "alpha beta and gamma delta and epsilon zeta and eta theta", limit = 3)
        assertEquals(3, parts.size)
    }

    @Test
    fun tokensThatAreNotWordsDoNotCount() {
        // "vs" alone, or punctuation only, must not manufacture an ask.
        assertEquals(emptyList<String>(), ResearchPlanner.decompose("vs"))
    }

    @Test
    fun aThinFirstSearchEarnsTheSubquestions() {
        val asks = listOf("proot link2symlink", "symlinkat crashes")
        assertEquals(asks, ResearchPlanner.followUpSearches(parentResultCount = 1, subquestions = asks))
    }

    @Test
    fun aSearchThatAlreadyAnsweredIsNotRepeated() {
        val asks = listOf("proot link2symlink", "symlinkat crashes")
        // Four or more distinct results is not thin; spending data again buys nothing.
        assertEquals(emptyList<String>(), ResearchPlanner.followUpSearches(4, asks))
        assertEquals(emptyList<String>(), ResearchPlanner.followUpSearches(9, asks))
    }

    @Test
    fun neverMoreThanTwoExtraSearches() {
        val asks = listOf("alpha beta", "gamma delta", "epsilon zeta")
        assertEquals(2, ResearchPlanner.followUpSearches(0, asks).size)
    }

    @Test
    fun noSubquestionsMeansNoExtraSearch() {
        assertEquals(emptyList<String>(), ResearchPlanner.followUpSearches(0, emptyList()))
        assertEquals(emptyList<String>(), ResearchPlanner.followUpSearches(0, listOf("only one ask here")))
    }
}
