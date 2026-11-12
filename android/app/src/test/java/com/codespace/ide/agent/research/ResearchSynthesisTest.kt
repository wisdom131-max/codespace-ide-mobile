package com.codespace.ide.agent.research

import com.codespace.ide.agent.research.ResearchSynthesis.RecoveryKind
import com.codespace.ide.agent.research.ResearchSynthesis.RecoveryNote
import com.codespace.ide.agent.research.ResearchSynthesis.StopReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Project 1.4 / 1.5: claim-level provenance, honest buckets, bounded iteration, honest recovery. */
class ResearchSynthesisTest {

    private fun ev(id: String, host: String, text: String, published: String = "") =
        ResearchSynthesis.Evidence(id, "https://$host.example/page", "Page", text, published)

    private val agreeing = listOf(
        ev("S1", "one", "Android 14 requires min sdk 34."),
        ev("S2", "two", "Android 14 requires min sdk 34."),
    )

    private val conflicting = listOf(
        ev("S1", "one", "The release requires jdk version 17."),
        ev("S2", "two", "The release requires jdk version 21."),
    )

    @Test fun citationsBelongToClaimsNotToTheParagraph() {
        val brief = ResearchSynthesis.build("what min sdk does android 14 require", agreeing)
        val claim = brief.corroborated.single()
        assertEquals("S1", claim.sourceId)
        assertTrue(brief.render.contains(claim.sourceId))
    }

    @Test fun corroboratedAndSingleSourceClaimsAreSeparated() {
        val brief = ResearchSynthesis.build(
            "what min sdk does android 14 require",
            agreeing + ev("S3", "three", "The emulator needs 8 gb of memory."),
        )
        assertEquals(1, brief.corroborated.size)
        assertEquals(1, brief.singleSource.size)
        assertTrue(brief.render.contains("Corroborated"))
        assertTrue(brief.render.contains("Single-source"))
    }

    @Test fun aDisputeCarriesBothSidesAndNamesNoWinner() {
        val brief = ResearchSynthesis.build("which jdk does the release require", conflicting)
        val d = brief.disputed.single()
        assertEquals(2, d.claims.map { it.sourceId }.distinct().size)
        assertTrue(brief.render.contains("17"))
        assertTrue(brief.render.contains("21"))
        assertTrue(brief.render.contains("present both, choose neither"))
    }

    @Test fun aDisputeMakesTheDisagreementAnUnresolvedPoint() {
        val brief = ResearchSynthesis.build("which jdk does the release require", conflicting)
        assertTrue(brief.unresolved.any { it.contains("disagree") })
    }

    @Test fun twoAgreeingHostsAreEnoughToStop() {
        val brief = ResearchSynthesis.build("what min sdk does android 14 require", agreeing)
        assertEquals(StopReason.SUFFICIENT, brief.stop)
        assertTrue(brief.followUps.isEmpty())
    }

    @Test fun oneHostIsNeverPresentedAsConfirmation() {
        val brief = ResearchSynthesis.build(
            "what min sdk does android 14 require",
            listOf(ev("S1", "one", "Android 14 requires min sdk 34.")),
        )
        assertTrue(brief.render.contains("single account"))
    }

    @Test fun anUncoveredQuestionTermProducesOneTargetedFollowUp() {
        val brief = ResearchSynthesis.build(
            "android 14 min sdk emulator",
            listOf(ev("S1", "one", "Android 14 requires min sdk 34.")),
        )
        assertEquals(1, brief.followUps.size)
        assertTrue(brief.followUps.first().endsWith("emulator"))
        assertTrue(brief.continued)
    }

    @Test fun aCoveredQuestionProducesNoFollowUpAtAll() {
        val brief = ResearchSynthesis.build(
            "android min sdk",
            listOf(ev("S1", "one", "Android min sdk is a build setting."), ev("S2", "two", "Android min sdk")),
        )
        assertTrue(brief.followUps.isEmpty())
        assertFalse(brief.render.contains("Next searches worth running"))
    }

    @Test fun theRoundLimitStopsTheRun() {
        val brief = ResearchSynthesis.build(
            "android 14 min sdk emulator",
            listOf(ev("S1", "one", "Android 14 requires min sdk 34.")),
            budget = ResearchSynthesis.Budget(round = ResearchPlanner.MAX_ROUNDS, searchesUsed = 1, searchBudget = 6),
        )
        assertEquals(StopReason.MAX_ROUNDS, brief.stop)
        assertTrue(brief.followUps.isEmpty())
    }

    @Test fun anExhaustedSearchBudgetStopsTheRun() {
        val brief = ResearchSynthesis.build(
            "android 14 min sdk emulator",
            listOf(ev("S1", "one", "Android 14 requires min sdk 34.")),
            budget = ResearchSynthesis.Budget(round = 1, searchesUsed = 6, searchBudget = 6),
        )
        assertEquals(StopReason.BUDGET_EXHAUSTED, brief.stop)
        assertTrue(brief.followUps.isEmpty())
        assertTrue(brief.render.contains("budget"))
    }

    @Test fun stoppingIsNeverRenderedAsCompletionUnlessEvidenceWasEnough() {
        val brief = ResearchSynthesis.build(
            "android 14 min sdk emulator",
            listOf(ev("S1", "one", "Android 14 requires min sdk 34.")),
            budget = ResearchSynthesis.Budget(1, 6, 6),
        )
        assertTrue(brief.render.contains("do not describe the research as complete"))
    }

    @Test fun partialEvidenceSurvivesAFetchFailure() {
        val brief = ResearchSynthesis.build(
            "android 14 min sdk emulator",
            listOf(ev("S1", "one", "Android 14 requires min sdk 34.")),
            recovery = listOf(RecoveryNote(RecoveryKind.FETCH_FAILED, "two.example/page", retriable = true)),
        )
        assertEquals(1, brief.corroborated.size + brief.singleSource.size)
        assertTrue(brief.render.contains("could not be fetched"))
        assertTrue(brief.unresolved.any { it.contains("could not be fetched") })
    }

    @Test fun everyRecoveryStatusReadsDifferently() {
        val phrases = RecoveryKind.entries.map { it.phrase }
        assertEquals(phrases.size, phrases.distinct().size)
        assertTrue(phrases.none { it.contains("error") || it.contains("exception") })
    }

    @Test fun aNonRetriableFailureIsNamedAndIsTheReasonWhenNothingElseCanRun() {
        // Nothing left to search for and a source that refused access: the run stopped because of it.
        val brief = ResearchSynthesis.build(
            "android min sdk",
            listOf(ev("S1", "one", "Android min sdk is a build setting.")),
            recovery = listOf(RecoveryNote(RecoveryKind.BLOCKED, "three.example", retriable = false)),
        )
        assertEquals(StopReason.FAILURES, brief.stop)
        assertTrue(brief.render.contains("refused automated access"))
    }

    @Test fun aRetriableFailureWithGapsStillLeftToCoverIsNotAFinalStop() {
        val brief = ResearchSynthesis.build(
            "android 14 min sdk emulator",
            listOf(ev("S1", "one", "Android 14 requires min sdk 34.")),
            recovery = listOf(RecoveryNote(RecoveryKind.TIMEOUT, "two.example", retriable = true)),
        )
        assertEquals(StopReason.PARTIAL, brief.stop)
        assertTrue(brief.continued)
        assertTrue(brief.render.contains("timed out"))
    }

    @Test fun noEvidenceAtAllIsStatedPlainly() {
        val brief = ResearchSynthesis.build(
            "android 14 min sdk emulator",
            emptyList(),
            recovery = listOf(RecoveryNote(RecoveryKind.NO_RESULTS, "all phrasings", retriable = false)),
        )
        assertTrue(brief.render.contains("no part of the answer is sourced"))
        assertTrue(brief.render.contains("nothing"))
    }

    @Test fun evidenceTextIsBoundedPerSource() {
        val huge = "Android 14 requires min sdk 34. " + "x".repeat(20_000)
        val brief = ResearchSynthesis.build("android 14 min sdk", listOf(ev("S1", "one", huge)))
        assertTrue("render must stay small", brief.render.length < 4_000)
    }

    @Test fun aStatusLineSaysWhyAndWhatIsLeft() {
        val brief = ResearchSynthesis.build("which jdk does the release require", conflicting)
        val line = ResearchSynthesis.statusLine(brief)
        assertTrue(line.startsWith(brief.stop.phrase))
        assertTrue(line.contains("disputed"))
    }

    @Test fun synthesisReusesTheExistingScannersRatherThanDuplicatingThem() {
        // Same evidence through both paths must agree on what is disputed.
        val scan = ContradictionScan.report(conflicting.map { it.sourceId to (it.title + ". " + it.text) })
        val brief = ResearchSynthesis.build("which jdk", conflicting)
        assertEquals(scan.disputed.size, brief.disputed.size)
    }
}
