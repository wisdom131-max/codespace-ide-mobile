package com.codespace.ide.agent.context

import com.codespace.ide.agent.context.ContextDigest.Kind
import com.codespace.ide.agent.context.ContextDigest.Provenance
import com.codespace.ide.agent.context.ContextDigest.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextDigestTest {

    private fun turns(vararg pairs: Pair<String, String>) =
        pairs.map { (role, text) -> Turn(role, text) }

    @Test fun aCommitHashSurvivesCompression() {
        val d = ContextDigest.digest(
            turns("user" to "fix the archive bug", "assistant" to "Shipped in `a5410d3`. CI run 43275036618 was green."),
        )
        val evidence = d.of(Kind.EVIDENCE).joinToString(" ") { it.text }
        assertTrue(evidence.contains("a5410d3"))
        assertTrue(evidence.contains("43275036618"))
    }

    @Test fun nothingIsInventedWhenTheConversationHoldsNoEvidence() {
        val d = ContextDigest.digest(turns("user" to "how do I open a file", "assistant" to "Use the explorer."))
        assertTrue(d.of(Kind.EVIDENCE).isEmpty())
        // And no empty sections are rendered: a digest with no evidence must not print "Evidence:".
        assertFalse(d.render().contains("Evidence:"))
    }

    @Test fun proseThatLooksLikeAHashIsNotEvidence() {
        // "deadbeef" is a word, not a commit: no backticks, no "commit" in front of it.
        val d = ContextDigest.digest(turns("user" to "the deadbeef thing is odd"))
        assertTrue(d.of(Kind.EVIDENCE).isEmpty())
    }

    @Test fun aStatedConstraintOutlivesTheChatterAroundIt() {
        val d = ContextDigest.digest(
            turns(
                "user" to "hello",
                "user" to "thanks, that works",
                "user" to "You must never use sub-agents for this project.",
                "assistant" to "ok",
            ),
        )
        val constraints = d.of(Kind.CONSTRAINT).joinToString(" ") { it.text }
        assertTrue(constraints.contains("never use sub-agents"))
    }

    @Test fun aUserStatementBeatsTheModelsParaphraseOfIt() {
        val d = ContextDigest.digest(
            turns(
                "user" to "We must never push on a red base.",
                "assistant" to "we must never push on a red base.",
            ),
        )
        val item = d.of(Kind.CONSTRAINT).first { it.text.lowercase().contains("red base") }
        assertEquals(Provenance.USER_CONFIRMED, item.provenance)
    }

    @Test fun aModelOnlyClaimIsLabelled() {
        val text = ContextDigest.digest(turns("assistant" to "Implemented the RAR engine.")).render()
        assertTrue(text.contains("(assistant, unconfirmed)"))
    }

    @Test fun filePathsAreCarriedForAResume() {
        val d = ContextDigest.digest(turns("user" to "look at `agent/ServiceApi.kt` please"))
        assertTrue(d.of(Kind.FILE).any { it.text == "agent/ServiceApi.kt" })
    }

    @Test fun theBudgetIsNeverExceededAndNoLineIsCutInHalf() {
        val many = (1..120).map { Turn("user", "must keep constraint number $it intact and readable") }
        val d = ContextDigest.digest(many, maxChars = 400)
        assertTrue("budget respected: ${d.chars}", d.chars <= 400)
        for (item in d.items) {
            assertFalse("a line was cut mid-sentence: " + item.text, item.text.endsWith("\u2026"))
        }
    }

    @Test fun theSameTurnsAlwaysProduceTheSameDigest() {
        val input = turns("user" to "Fix the parser. It must not crash.", "assistant" to "Done in `72f8874`, 2647 tests.")
        assertEquals(ContextDigest.digest(input).render(), ContextDigest.digest(input).render())
    }

    @Test fun retrievalFindsTheEarlierDecisionAndStaysBounded() {
        val many = (1..30).map { Turn("assistant", "Filler line about unrelated things number $it.") } +
            Turn("user", "We decided to use junrar instead of a native unrar binary.") +
            Turn("assistant", "Noted.")
        val d = ContextDigest.digest(many, maxChars = 4_000)
        val hits = ContextDigest.retrieve(d, "what did we decide about junrar", limit = 3)
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.size <= 3)
        assertTrue(hits.first().text.contains("junrar"))
    }

    @Test fun aVagueQueryRetrievesNothingRatherThanEverything() {
        val d = ContextDigest.digest(turns("user" to "Ship the archive engine in `45d13f4`."))
        assertTrue(ContextDigest.retrieve(d, "the of and", limit = 5).isEmpty())
        assertTrue(ContextDigest.retrieve(d, "x", limit = 5).isEmpty())
    }

    @Test fun evidenceOutranksProseAtEqualOverlap() {
        // Built directly rather than through digest(): this asserts the TIE-BREAK, so the two items
        // must carry the same overlap. A digest-derived pair scores differently for reasons that
        // have nothing to do with the ranking rule being tested.
        val d = ContextDigest.Digest(
            items = listOf(
                ContextDigest.Item(Kind.COMPLETED, "archive build notes", Provenance.MODEL_STATED, 1),
                ContextDigest.Item(Kind.EVIDENCE, "archive build run 43275036618", Provenance.MODEL_STATED, 1),
            ),
            turnsConsidered = 2,
            turnsOmitted = 0,
            chars = 60,
        )
        assertEquals(Kind.EVIDENCE, ContextDigest.retrieve(d, "archive build", limit = 2).first().kind)
    }

}
