package com.codespace.ide.agent.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Project 1.1 / 1.5: selection, dedupe, diversity, gap-driven follow-up and bounded rounds. */
class ResearchPlannerTest {

    private fun c(url: String, title: String, rank: Int = 0) =
        ResearchPlanner.Candidate(url, title, "", rank)

    @Test fun equivalentUrlsCollapseToOneCandidate() {
        val picked = ResearchPlanner.select(
            listOf(
                c("https://docs.example.com/guide/", "Guide"),
                c("HTTPS://WWW.DOCS.EXAMPLE.COM/guide?utm_source=x", "Guide"),
                c("https://docs.example.com/guide#section", "Guide"),
            ),
        )
        assertEquals(1, picked.size)
    }

    @Test fun aMirroredPageWithTheSameTitleIsOneSource() {
        val picked = ResearchPlanner.select(
            listOf(
                c("https://a.example/x", "Kotlin coroutines cancellation guide"),
                c("https://b.example/y", "Kotlin coroutines cancellation guide"),
            ),
        )
        assertEquals(1, picked.size)
    }

    @Test fun differentPagesWithDifferentTitlesAreBothKept() {
        val picked = ResearchPlanner.select(
            listOf(
                c("https://a.example/x", "Kotlin coroutines cancellation guide"),
                c("https://a.example/y", "Structured concurrency in practice"),
            ),
        )
        assertEquals(2, picked.size)
    }

    @Test fun noHostTakesMoreThanTwoSlotsWhileOthersRemain() {
        val picked = ResearchPlanner.select(
            listOf(
                c("https://one.example/1", "Alpha"),
                c("https://one.example/2", "Beta"),
                c("https://one.example/3", "Gamma"),
                c("https://two.example/4", "Delta"),
            ),
            limit = 3,
        )
        assertEquals(3, picked.size)
        assertEquals(2, picked.count { UrlTools.domain(it.url) == "one.example" })
    }

    @Test fun aSecondPageFromOneHostIsUsedWhenNothingElseExists() {
        val picked = ResearchPlanner.select(
            listOf(
                c("https://one.example/1", "Alpha"),
                c("https://one.example/2", "Beta"),
                c("https://one.example/3", "Gamma"),
            ),
            limit = 3,
        )
        assertEquals(3, picked.size)
    }

    @Test fun alreadySeenUrlsAreNotFetchedAgain() {
        val picked = ResearchPlanner.select(
            listOf(c("https://a.example/x", "One"), c("https://b.example/y", "Two")),
            seenUrls = setOf("https://a.example/x/"),
        )
        assertEquals(1, picked.size)
        assertEquals("https://b.example/y", picked.first().url)
    }

    @Test fun rankOrdersTheSelectionAndLimitIsRespected() {
        val picked = ResearchPlanner.select(
            listOf(c("https://a.example/1", "A", 2), c("https://b.example/2", "B", 1)),
            limit = 1,
        )
        assertEquals("https://b.example/2", picked.single().url)
    }

    @Test fun aCoveredQuestionNeedsNoFollowUpSearch() {
        val gaps = ResearchPlanner.gapQueries(
            "kotlin coroutines cancellation",
            listOf(ResearchPlanner.Evidence("https://a.example", "Kotlin coroutines cancellation is cooperative")),
        )
        assertTrue(gaps.isEmpty())
    }

    @Test fun aQuestionTermWithNoSupportBecomesAFollowUp() {
        val gaps = ResearchPlanner.gapQueries(
            "kotlin coroutines cancellation",
            listOf(ResearchPlanner.Evidence("https://a.example", "kotlin coroutines are cooperative")),
        )
        assertEquals(1, gaps.size)
        assertTrue(gaps.first().endsWith("cancellation"))
    }

    @Test fun followUpsAreBounded() {
        val gaps = ResearchPlanner.gapQueries(
            "android macos windows linux freebsd",
            listOf(ResearchPlanner.Evidence("https://a.example", "android")),
            limit = 2,
        )
        assertEquals(2, gaps.size)
    }

    @Test fun roundsAreBoundedByCountAndBudget() {
        assertTrue(ResearchPlanner.allowAnotherRound(0, 0, 6))
        assertFalse(ResearchPlanner.allowAnotherRound(ResearchPlanner.MAX_ROUNDS, 0, 6))
        assertFalse(ResearchPlanner.allowAnotherRound(1, 6, 6))
    }

    @Test fun coverageNoteIsHonestAboutASingleSource() {
        assertEquals("No source was read, so nothing below is sourced.",
            ResearchPlanner.coverageNote(emptyList()))
        assertTrue(ResearchPlanner.coverageNote(
            listOf(ResearchPlanner.Evidence("https://a.example/x", "t")),
        ).contains("single account"))
        assertTrue(ResearchPlanner.coverageNote(
            listOf(ResearchPlanner.Evidence("https://a.example/x", "t"), ResearchPlanner.Evidence("https://b.example/y", "t")),
        ).contains("Two independent sources"))
    }
}
