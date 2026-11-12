package com.codespace.ide.chat.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P6: what a continued conversation is allowed to carry, and what it must never carry. */
class SessionHandoffTest {

    private val turns = listOf(
        SessionHandoff.Turn("user", "We decided to keep the JSON memory file and add project scopes"),
        SessionHandoff.Turn("assistant", "Done: agent/facts/FactsStore.kt is written and the store is atomic"),
        SessionHandoff.Turn("user", "Never log the token value anywhere"),
        SessionHandoff.Turn("assistant", "The remaining work is the Facts screen. Todo: wire the settings row."),
        SessionHandoff.Turn("assistant", "The change is in agent/facts/FactGuard.kt and cites [S1]."),
    )

    @Test fun decisionsConstraintsProgressFilesAndSourcesAllSurvive() {
        val h = SessionHandoff.build(turns)
        assertTrue(h.decisions.any { it.contains("We decided") })
        assertTrue(h.constraints.any { it.contains("Never log") })
        assertTrue("progress was " + h.progress, h.progress.any { it.contains("Done") })
        assertTrue(h.files.contains("agent/facts/FactsStore.kt"))
        assertTrue(h.sources.contains("[S1]"))
    }

    @Test fun noCredentialEverEntersAHandoff() {
        val leaky = turns + listOf(
            SessionHandoff.Turn("user", "my api_key is sk-abcdef1234567890 use it"),
            SessionHandoff.Turn("assistant", "token: ghp_aaaaaaaaaaaaaaaaaaaa"),
        )
        val h = SessionHandoff.build(leaky)
        val text = h.render()
        assertFalse(text.contains("sk-abcdef"))
        assertFalse(text.contains("ghp_aaaa"))
        assertTrue(SessionHandoff.isSecret("password: hunter2"))
    }

    @Test fun noApprovalIsInheritedAndTheHandoffSaysSo() {
        val text = SessionHandoff.build(turns).render()
        assertTrue(text.contains("Approvals are NOT carried over"))
    }

    @Test fun anEmptyConversationProducesNothingRenderedAsProgress() {
        val h = SessionHandoff.build(emptyList())
        assertTrue(h.isEmpty())
        assertTrue(SessionHandoff.describe(h).contains("Nothing durable"))
    }

    @Test fun listsAreBounded() {
        val many = (1..50).map { SessionHandoff.Turn("user", "We decided item $it for agent/x$it/File$it.kt") }
        val h = SessionHandoff.build(many)
        assertTrue(h.decisions.size <= SessionHandoff.MAX_ITEMS)
        assertTrue(h.files.size <= SessionHandoff.MAX_ITEMS)
        assertTrue(h.decisions.all { it.length <= SessionHandoff.MAX_ITEM_CHARS })
    }

    @Test fun aLongFirstMessageBecomesAShortLabel() {
        val h = SessionHandoff.build(listOf(SessionHandoff.Turn("user", "please " + "x".repeat(200))))
        assertTrue(h.label.length <= 41)
    }

    @Test fun theRenderedHandoffNamesItselfAsAHandoff() {
        val text = SessionHandoff.build(turns).render()
        assertTrue(text.contains("handoff, not the transcript"))
    }

    @Test fun describeCountsWhatWasCarried() {
        assertTrue(SessionHandoff.describe(SessionHandoff.build(turns)).contains("carried point"))
    }

    @Test fun aConstraintIsNotAlsoReportedAsProgress() {
        val h = SessionHandoff.build(listOf(SessionHandoff.Turn("assistant", "You must never commit the debug key")))
        assertTrue(h.progress.none { it.contains("never commit") })
    }

    @Test fun duplicatePointsAreCarriedOnce() {
        val twice = listOf(
            SessionHandoff.Turn("user", "We decided to use kotlinx"),
            SessionHandoff.Turn("user", "We decided to use kotlinx"),
        )
        assertEquals(1, SessionHandoff.build(twice).decisions.size)
    }

    // ── Item 35: evidence and lineage ─────────────────────────────────────────────────────────

    @Test fun commitHashesAndCiEvidenceSurviveAHandoff() {
        val h = SessionHandoff.build(listOf(
            SessionHandoff.Turn("user", "continue the archive work"),
            SessionHandoff.Turn("assistant", "Landed in `a5410d3`, CI run 43275036618, 2647 tests with one known failure."),
        ))
        assertTrue(h.commits.contains("a5410d3"))
        assertTrue(h.evidence.any { it.contains("43275036618") })
        assertTrue(h.evidence.any { it.contains("2647") })
        // The rendered first message names them, so the new session does not have to ask.
        assertTrue(h.render().contains("a5410d3"))
    }

    @Test fun aUserQuotingATestCountIsNotEvidence() {
        val h = SessionHandoff.build(listOf(SessionHandoff.Turn("user", "I ran 12 tests and they passed")))
        assertTrue(h.evidence.isEmpty())
    }

    @Test fun limitationsAreCarriedSoADeferralIsNotRefixed() {
        val h = SessionHandoff.build(listOf(SessionHandoff.Turn("assistant", "RAR 1.4 extraction is not supported; it stays deferred.")))
        assertTrue(h.limitations.isNotEmpty())
        assertTrue(h.render().contains("Known limitations"))
    }

    @Test fun theLabelSaysWhereTheChainStarted() {
        val h = SessionHandoff.build(
            listOf(SessionHandoff.Turn("user", "fix the git tools")),
            sessionNumber = 2,
            originLabel = "Git",
        )
        assertTrue(h.render().contains("session 2 of a chain that started in \"Git\""))
        assertEquals("Session 2 from Git", SessionHandoff.numberedLabel(2, "Git"))
        // A bogus number is corrected rather than printed.
        assertEquals("Session 1 from Git", SessionHandoff.numberedLabel(0, "Git"))
    }

}
