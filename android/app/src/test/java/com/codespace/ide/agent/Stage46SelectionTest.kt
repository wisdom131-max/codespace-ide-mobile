package com.codespace.ide.agent

import com.codespace.ide.agent.runtime.PrereqLedger
import com.codespace.ide.chat.panel.ChatMode
import com.codespace.ide.chat.panel.ToolHealth
import com.codespace.ide.chat.panel.ToolSuitability
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 4.6: eligibility is mandatory, ranking is a preference, and both are explainable. */
class Stage46SelectionTest {

    private val notDemoted = emptySet<String>()

    @After
    fun tearDown() {
        ToolHealth.clear()
        PrereqLedger.clear()
        EvidenceReconciler.clear()
    }

    @Test
    fun theToolThatFitsTheTaskOutranksAHealthierUnrelatedOne() {
        val choice = ToolSuitability.rank(
            request = "grep for the failing test name",
            candidates = listOf("grep_search", "web_search"),
            mode = ChatMode.AGENT,
            isOffered = { true },
            exists = { true },
            healthPenalty = { 0 },
            demoted = notDemoted,
        )
        assertEquals("grep_search", choice.best?.tool)
    }

    @Test
    fun aToolNotOfferedInThisModeIsNeverChosenWhateverItsScore() {
        val choice = ToolSuitability.rank(
            request = "edit the file",
            candidates = listOf("write_file", "read_file"),
            mode = ChatMode.ASK,
            isOffered = { it != "write_file" },
            exists = { true },
            healthPenalty = { 0 },
            demoted = notDemoted,
        )
        val blocked = choice.ranked.first { it.tool == "write_file" }
        assertFalse(blocked.eligible)
        assertTrue(blocked.explanation.contains("ask mode does not offer it"))
        assertEquals("read_file", choice.best?.tool)
    }

    @Test
    fun anUnregisteredToolIsRefusedWithAReason() {
        val choice = ToolSuitability.rank(
            "read the file", listOf("imaginary_tool"), ChatMode.AGENT,
            isOffered = { true }, exists = { it == "imaginary_tool" }, healthPenalty = { 0 }, demoted = notDemoted,
        )
        assertNull(choice.best)
        assertNotNull(choice.refusal)
        assertTrue(choice.refusal!!.contains("no such tool"))
    }

    @Test
    fun nothingEligibleIsExplainedRatherThanGuessed() {
        val choice = ToolSuitability.rank(
            "edit", listOf("write_file"), ChatMode.ASK,
            isOffered = { false }, exists = { true }, healthPenalty = { 0 }, demoted = notDemoted,
        )
        assertNull(choice.best)
        assertTrue(choice.refusal!!.contains("None of the proposed tools"))
    }

    @Test
    fun aPoorRecordDemotesButNeverDisqualifies() {
        val choice = ToolSuitability.rank(
            "search for a string", listOf("grep_search", "file_search"), ChatMode.AGENT,
            isOffered = { true }, exists = { true }, healthPenalty = { if (it == "grep_search") 12 else 0 },
            demoted = setOf("grep_search"),
        )
        assertEquals("file_search", choice.best?.tool)
        val bad = choice.ranked.first { it.tool == "grep_search" }
        assertTrue("demotion is a score, not a ban", bad.eligible)
        assertTrue(bad.signals.any { it.name == "health" && it.earned < 0 })
    }

    @Test
    fun aDemotedToolStillWinsWhenNothingElseFits() {
        val choice = ToolSuitability.rank(
            "read the file", listOf("read_file"), ChatMode.AGENT,
            isOffered = { true }, exists = { true }, healthPenalty = { 15 }, demoted = setOf("read_file"),
        )
        assertEquals("read_file", choice.best?.tool)
        val note = ToolSuitability.note(choice, setOf("read_file"))
        assertNotNull(note)
        assertTrue(note!!.contains("poor record"))
    }

    @Test
    fun everyCandidateCarriesItsReasons() {
        val choice = ToolSuitability.rank(
            "read the file contents", listOf("read_file"), ChatMode.AGENT,
            isOffered = { true }, exists = { true }, healthPenalty = { 0 }, demoted = notDemoted,
        )
        val best = choice.best!!
        assertTrue(best.signals.any { it.name == "task words" && it.earned > 0 })
        assertTrue(best.signals.any { it.name == "output contract" })
        assertTrue(best.explanation.contains("scores"))
    }

    @Test
    fun rankingIsDeterministicForEqualScores() {
        val first = ToolSuitability.rank(
            "do the thing", listOf("read_file", "grep_search"), ChatMode.AGENT,
            isOffered = { true }, exists = { true }, healthPenalty = { 0 }, demoted = notDemoted,
        ).ranked.map { it.tool }
        val second = ToolSuitability.rank(
            "do the thing", listOf("grep_search", "read_file"), ChatMode.AGENT,
            isOffered = { true }, exists = { true }, healthPenalty = { 0 }, demoted = notDemoted,
        ).ranked.map { it.tool }
        assertEquals(first, second)
    }

    @Test
    fun aResourceObservedTwiceWithOneAnswerAgrees() {
        val first = EvidenceReconciler.compare("src/Main.kt", "fun main() {}", "s1", 1, "read_file", false)
        assertEquals(EvidenceReconciler.Verdict.NOT_COMPARABLE, first.verdict)
        val second = EvidenceReconciler.compare("src/Main.kt", "fun main() {}", "s1", 1, "read_file", false)
        assertEquals(EvidenceReconciler.Verdict.SAME, second.verdict)
        assertFalse(second.blocksSatisfaction)
    }

    @Test
    fun twoAnswersWithNoWriteBetweenThemAreAContradiction() {
        EvidenceReconciler.compare("src/Main.kt", "version one", "s1", 1, "read_file", false)
        val clash = EvidenceReconciler.compare("src/Main.kt", "version two", "s1", 1, "read_file", false)
        assertEquals(EvidenceReconciler.Verdict.CONTRADICTION, clash.verdict)
        assertTrue("a contradiction must not satisfy anything", clash.blocksSatisfaction)
        assertTrue(clash.note.contains("no verified write"))
    }

    @Test
    fun aVerifiedWriteExplainsTheDifferenceRatherThanCallingItAConflict() {
        EvidenceReconciler.compare("src/Main.kt", "before", "s1", 1, "read_file", false)
        val changed = EvidenceReconciler.compare("src/Main.kt", "after", "s1", 1, "read_file", true)
        assertEquals(EvidenceReconciler.Verdict.LEGITIMATE_CHANGE, changed.verdict)
        assertFalse(changed.blocksSatisfaction)
    }

    @Test
    fun anObservationFromAnotherConversationIsStaleNotContradictory() {
        EvidenceReconciler.compare("src/Main.kt", "a", "s1", 1, "read_file", false)
        val stale = EvidenceReconciler.compare("src/Main.kt", "b", "s2", 1, "read_file", false)
        assertEquals(EvidenceReconciler.Verdict.STALE, stale.verdict)
        assertFalse(stale.blocksSatisfaction)
    }

    @Test
    fun anObservationFromAnOlderPlanIsStale() {
        EvidenceReconciler.compare("src/Main.kt", "a", "s1", 1, "read_file", false)
        val stale = EvidenceReconciler.compare("src/Main.kt", "b", "s1", 2, "read_file", false)
        assertEquals(EvidenceReconciler.Verdict.STALE, stale.verdict)
    }

    @Test
    fun aWriteIsNotAComparableResource() {
        assertNull(EvidenceReconciler.resourceOf("write_file", "/tmp/x"))
        assertEquals("src/a.kt", EvidenceReconciler.resourceOf("read_file", "src/a.kt"))
    }
}
