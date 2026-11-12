package com.codespace.ide.agent.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Project 1.2: authority by source characteristics, recency only where freshness matters. */
class SourceQualityTest {

    @Test fun aSpecificationIsAPrimarySource() {
        assertEquals(SourceQuality.Authority.PRIMARY,
            SourceQuality.classify("rfc-editor.org", "https://www.rfc-editor.org/rfc/rfc9110", "RFC 9110"))
    }

    @Test fun aProjectsOwnDocumentationIsOfficial() {
        assertEquals(SourceQuality.Authority.OFFICIAL,
            SourceQuality.classify("developer.android.com", "https://developer.android.com/guide", "Guide"))
        assertEquals(SourceQuality.Authority.OFFICIAL,
            SourceQuality.classify("docs.example.dev", "https://docs.example.dev/x", "Docs"))
    }

    @Test fun aReleasePageOnTheHostingSiteIsStillPrimary() {
        assertEquals(SourceQuality.Authority.PRIMARY,
            SourceQuality.classify("github.com", "https://github.com/o/r/releases/tag/v1.2", "v1.2"))
        assertEquals(SourceQuality.Authority.COMMUNITY,
            SourceQuality.classify("github.com", "https://github.com/o/r/issues/12", "issue"))
    }

    @Test fun aBlogIsSecondaryAndAQAThreadIsCommunity() {
        assertEquals(SourceQuality.Authority.SECONDARY,
            SourceQuality.classify("example.com", "https://example.com/blog/post", "A post"))
        assertEquals(SourceQuality.Authority.COMMUNITY,
            SourceQuality.classify("stackoverflow.com", "https://stackoverflow.com/q/1", "Q"))
    }

    @Test fun aRankFarmingPageIsWeakEvenOnARespectableHost() {
        assertEquals(SourceQuality.Authority.WEAK,
            SourceQuality.classify("example.com", "https://example.com/top-10-tools", "Top 10 tools"))
    }

    @Test fun rankingIsNotCredibilitySoAPrimarySourceOutranksAListicle() {
        val question = "how does http caching work"
        val primary = SourceQuality.assess("https://www.rfc-editor.org/rfc/rfc9111", "RFC 9111", question)
        val weak = SourceQuality.assess("https://example.com/best-caching", "Best caching tips", question)
        assertTrue(primary.score > weak.score)
        assertEquals(SourceQuality.Authority.WEAK, weak.authority)
    }

    @Test fun freshnessMattersOnlyForQuestionsAboutNow() {
        assertTrue(SourceQuality.freshnessMatters("what is the latest kotlin version"))
        assertTrue(SourceQuality.freshnessMatters("kotlin 2.0 breaking changes"))
        assertFalse(SourceQuality.freshnessMatters("how do coroutines cancel work"))
    }

    @Test fun aStaleSourceIsFlaggedWhenTheQuestionNeedsCurrentInformation() {
        val a = SourceQuality.assess(
            "https://example.com/post", "Post", "latest kotlin version", publishedAt = "2019-04-01",
        )
        assertTrue(a.stale)
        assertTrue(a.reason.contains("2019"))
    }

    @Test fun anOldSourceIsNotPenalisedWhenAgeIsIrrelevant() {
        val a = SourceQuality.assess(
            "https://rfc-editor.org/rfc/rfc2616", "RFC 2616", "how does http caching work",
            publishedAt = "1999-06-01",
        )
        assertFalse(a.stale)
        assertEquals(SourceQuality.Authority.PRIMARY, a.authority)
    }

    @Test fun aFreshSourceOnACurrentQuestionIsNotPenalised() {
        val a = SourceQuality.assess(
            "https://developer.android.com/x", "Guide", "current android api level",
            publishedAt = "2026-01-10",
        )
        assertFalse(a.stale)
    }

    @Test fun anUndatedSourceOnACurrentQuestionSaysCurrencyIsUnknown() {
        val a = SourceQuality.assess("https://example.com/x", "Post", "latest release")
        assertTrue(a.reason.contains("No publication date"))
    }

    @Test fun yearIsReadFromTheDateString() {
        assertEquals(2024, SourceQuality.yearOf("Published 2024-05-02"))
        assertEquals(null, SourceQuality.yearOf(""))
    }
}
