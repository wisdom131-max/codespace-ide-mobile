package com.codespace.ide.agent.facts

import com.codespace.ide.chat.ThinkingTreeStore
import com.codespace.ide.chat.ui.ChatRowModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * STAGE 11 — the Facts & Memory evaluation and regression suite.
 *
 * Deterministic application regression over all eleven stages: extraction, review, storage,
 * retrieval, chronology, provenance, timeline and Thinking Tree. No network, no device, no model
 * calls — model QUALITY is out of scope here and is reported separately as unavailable.
 *
 * Every metric is computed from the fixtures below and printed in one block, so a regression shows
 * up as a changed number rather than a vague failure.
 */
class FactsMemoryEvaluationTest {

    @get:Rule val tmp = TemporaryFolder()

    /** Labelled corpus: message -> how many durable candidates it SHOULD yield. */
    private val extractionCorpus = listOf(
        "I prefer tabs over spaces in this project." to 1,
        "We decided to use Room for the local cache." to 1,
        "Always run the full suite before pushing." to 1,
        "The project uses Kotlin and Gradle." to 1,
        "thanks, that works" to 0,
        "Do I prefer tabs or spaces?" to 0,
        "The weather in Lagos is warm today." to 0,
        "ok cool, continue" to 0,
    )

    private val verifiedProv = FactProvenance(
        sourceConversationId = "conv-eval",
        conversationStartedAt = 1_700_000_000_000L,
        sourceConversationAt = 1_700_000_000_000L,
        sourceMessageId = "m-2",
        sourceMessageAt = 1_700_000_060_000L,
        timeSource = TimeSource.TRANSCRIPT_VERIFIED,
    )

    // ---------------------------------------------------------------- 4. extraction

    @Test
    fun `extraction metrics over the labelled corpus`() {
        var tp = 0; var fp = 0; var fn = 0
        for ((text, expected) in extractionCorpus) {
            val actual = MemoryExtraction.candidates(text).size
            tp += minOf(expected, actual)
            fp += maxOf(0, actual - expected)
            fn += maxOf(0, expected - actual)
        }
        val precision = if (tp + fp == 0) 1.0 else tp.toDouble() / (tp + fp)
        val recall = if (tp + fn == 0) 1.0 else tp.toDouble() / (tp + fn)
        println("EVAL extraction: tp=$tp fp=$fp fn=$fn precision=$precision recall=$recall")
        // Floors are the measured values, so any regression fails rather than drifting silently.
        assertTrue("precision regressed: $precision", precision >= 0.8)
        assertTrue("recall regressed: $recall", recall >= 0.8)
    }

    // ---------------------------------------------------------------- 5. review

    @Test
    fun `an extracted candidate never becomes a fact without review`() {
        val d = tmp.newFolder("review")
        MemoryExtraction.capture(d, "I prefer tabs over spaces in this project.", verifiedProv)
        assertEquals(0, FactsStore.loadFrom(d).size)
        val pending = ReviewQueue.pending(ReviewQueue.loadFrom(d))
        assertEquals(1, pending.size)
        assertEquals("the candidate keeps its scope", FactScope.USER, pending.single().scope)
    }

    @Test
    fun `acceptance is the only route in, and it keeps provenance`() {
        val d = tmp.newFolder("accept")
        MemoryExtraction.capture(d, "We decided to use Room for the local cache.", verifiedProv)
        val c = ReviewQueue.pending(ReviewQueue.loadFrom(d)).single()
        val out = ReviewQueue.accept(d, c.id, 1_700_000_100_000L)
        assertTrue("accept failed: $out", out is ReviewQueue.Outcome.Accepted)
        val fact = FactsStore.loadFrom(d).single()
        assertEquals("conv-eval", fact.sourceConversationId)
        assertEquals("m-2", fact.sourceMessageId)
        assertEquals(TimeSource.TRANSCRIPT_VERIFIED, fact.timeSource)
        assertEquals("accepting twice must not save twice",
            1, FactsStore.loadFrom(d).size)
    }

    @Test
    fun `rejection blocks a reworded repeat and never saves`() {
        val d = tmp.newFolder("reject")
        MemoryExtraction.capture(d, "Always run the full suite before pushing.", verifiedProv)
        val c = ReviewQueue.pending(ReviewQueue.loadFrom(d)).single()
        // Same clock as the capture: a rejection outside the 90-day window is pruned by design.
        ReviewQueue.reject(d, c.id, "not useful", System.currentTimeMillis())
        assertEquals(0, FactsStore.loadFrom(d).size)
        val again = MemoryExtraction.capture(
            d, "Always run the full suite before pushing anything.", verifiedProv,
        )
        assertEquals("a reworded repeat must not return", 0, again.queued)
        assertEquals(0, ReviewQueue.pending(ReviewQueue.loadFrom(d)).size)
    }

    @Test
    fun `manual saves are unchanged by the review pipeline`() {
        val d = tmp.newFolder("manual")
        val r = FactsStore.saveTo(d, "The build uses Gradle", FactScope.USER, null)
        assertTrue(r is FactsStore.SaveResult.Saved)
        assertEquals(1, FactsStore.loadFrom(d).size)
        assertEquals(0, ReviewQueue.pending(ReviewQueue.loadFrom(d)).size)
    }

    // ---------------------------------------------------------------- 6. retrieval

    @Test
    fun `retrieval finds the right fact for paraphrased questions`() {
        val facts = listOf(
            fact("f1", "The build uses Gradle with the wrapper"),
            fact("f2", "Use tabs for indentation in this project"),
            fact("f3", "The local cache uses Room"),
        )
        val pairs = listOf(
            "how do I build the app" to "f1",
            "what indentation do we use" to "f2",
            "what does the local cache use" to "f3",
        )
        var hits = 0
        for ((q, want) in pairs) {
            val top = FactRetrieval.temporalRank(facts, q, null, 1_700_000_000_000L).firstOrNull()
            if (top?.fact?.id == want) hits++
        }
        val precision = hits.toDouble() / pairs.size
        println("EVAL retrieval: hits=$hits of ${pairs.size} top1=$precision")
        assertTrue("top-1 retrieval regressed: $precision", precision >= 0.6)
    }

    @Test
    fun `a superseded fact never answers as the current one`() {
        val d = tmp.newFolder("supersede")
        val first = FactsStore.saveTo(d, "The build uses Gradle", FactScope.USER, null)
            as FactsStore.SaveResult.Saved
        val second = FactsStore.saveTo(d, "The build uses Gradle 8 with the wrapper", FactScope.USER, null)
            as FactsStore.SaveResult.Saved
        val all = FactsStore.loadFrom(d)
        assertEquals("history is kept, not overwritten", 2, all.size)
        val old = all.first { it.id == first.fact.id }
        assertEquals(FactStatus.SUPERSEDED, old.status)
        assertEquals(second.fact.id, old.supersededBy)
        assertTrue("supersession is timed", old.supersededAt > 0L)
        assertEquals("the statement itself is preserved",
            "The build uses Gradle", old.content)
        val active = FactRetrieval.temporalRank(all, "build gradle", null, 1L)
            .filter { it.fact.isRetrievable() }
        assertFalse(active.any { it.fact.id == first.fact.id })
    }

    // ---------------------------------------------------------------- 7. chronology

    @Test
    fun `chronology respects trust and never upgrades an unknown date`() {
        val verified = fact("v", "release branch is main", at = 1_700_000_000_000L, src = TimeSource.TRANSCRIPT_VERIFIED)
        val stated = fact("s", "release branch is stable", at = 1_600_000_000_000L, src = TimeSource.USER_STATED)
        val unknown = fact("u", "release branch is develop")
        val order = FactRetrieval.chronological(listOf(unknown, stated, verified))
        assertEquals(listOf("v", "s", "u"), order.map { it.id })
        assertEquals("an unknown date stays unknown", 0L, unknown.knownTime())
        assertEquals(TimeSource.UNKNOWN, unknown.timeSource)
    }

    @Test
    fun `latest picks the verified newest and ignores unknowns`() {
        val verified = fact("v", "a", at = 1_700_000_000_000L, src = TimeSource.TRANSCRIPT_VERIFIED)
        val unknown = fact("u", "b")
        assertEquals("v", FactRetrieval.latest(listOf(unknown, verified))?.id)
        assertNull(FactRetrieval.latest(listOf(unknown)))
    }

    // ---------------------------------------------------------------- 8. provenance

    @Test
    fun `provenance is attributed to the right message or to nothing`() {
        val d = tmp.newFolder("prov")
        MemoryExtraction.capture(d, "I prefer tabs over spaces in this project.", verifiedProv)
        val c = ReviewQueue.pending(ReviewQueue.loadFrom(d)).single()
        assertEquals("m-2", c.sourceMessageId)
        assertEquals(TimeSource.TRANSCRIPT_VERIFIED, c.timeSource)

        // A candidate with no reliable source must carry NO link at all.
        val bare = tmp.newFolder("prov2")
        MemoryExtraction.capture(bare, "I prefer tabs over spaces in this project.", FactProvenance.NONE)
        val b = ReviewQueue.pending(ReviewQueue.loadFrom(bare)).single()
        assertNull(b.sourceMessageId)
        assertNull(b.sourceConversationId)
        assertEquals(0L, b.sourceConversationAt)
        assertEquals(TimeSource.UNKNOWN, b.timeSource)
    }

    // ---------------------------------------------------------------- 9. timeline

    @Test
    fun `the timeline records only what actually happened`() {
        val d = tmp.newFolder("timeline")
        FactsStore.saveTo(d, "The build uses Gradle", FactScope.USER, null)
        FactsStore.saveTo(d, "The build uses Gradle 8 with the wrapper", FactScope.USER, null)
        val events = MemoryTimeline.loadFrom(d)
        assertEquals(2, events.count { it.type == TimelineEventType.CREATED })
        assertEquals(1, events.count { it.type == TimelineEventType.SUPERSEDED })
        // A refused save must not appear as history.
        FactsStore.saveTo(d, "", FactScope.USER, null)
        assertEquals(events.size, MemoryTimeline.loadFrom(d).size)
        // Idempotent: the same event recorded twice stays once.
        val before = MemoryTimeline.loadFrom(d).size
        val e = MemoryTimeline.loadFrom(d).first()
        MemoryTimeline.record(d, e)
        assertEquals(before, MemoryTimeline.loadFrom(d).size)
    }

    @Test
    fun `a corrupt timeline does not damage accepted facts`() {
        val d = tmp.newFolder("tl2")
        FactsStore.saveTo(d, "The build uses Gradle", FactScope.USER, null)
        MemoryTimeline.fileIn(d).writeText("{ this is not json")
        assertEquals(0, MemoryTimeline.loadFrom(d).size)
        assertEquals("facts survive timeline corruption", 1, FactsStore.loadFrom(d).size)
    }

    // ---------------------------------------------------------------- 10. thinking tree

    @Test
    fun `tree steps restore with their identity, state and isolation`() {
        val root = tmp.newFolder("chat")
        val step = ThinkingTreeStore.Step(
            nodeId = "r1c1", turnId = "t1", label = "read_file", detail = "ok",
            ok = true, state = "DONE", atMs = 1_700_000_000_000L,
        )
        assertTrue(ThinkingTreeStore.appendTo(root, "p1", "c1", step))
        assertTrue(ThinkingTreeStore.appendTo(root, "p1", "c1", step))
        val restored = ThinkingTreeStore.loadFrom(root, "p1", "c1")
        assertEquals("a repeated step is not duplicated", 1, restored.size)
        assertEquals("r1c1", restored.single().nodeId)
        assertEquals("DONE", restored.single().state)
        assertEquals("other conversations are isolated",
            0, ThinkingTreeStore.loadFrom(root, "p1", "c2").size)
        val rows = ThinkingTreeStore.mergeRows(
            listOf(ChatRowModel.User("hi"), ChatRowModel.AssistantStream("done", cursorOn = false)),
            restored,
        )
        // OWNER DECISION (2026-11-24): the steps come back through the tree, never as a transcript
        // row. The store still restores them (asserted above), so nothing is lost by the change.
        assertTrue("the steps are restored", restored.isNotEmpty())
        assertEquals("the transcript stays as written", 2, rows.size)
        assertTrue("no tool row is spliced in", rows.none { it is ChatRowModel.ToolActivity })
    }

    @Test
    fun `an interrupted step restores as interrupted, never as success`() {
        val root = tmp.newFolder("chat2")
        ThinkingTreeStore.appendTo(root, "p", "c", ThinkingTreeStore.Step(
            nodeId = "r1c1", turnId = "t1", label = "run", detail = "started",
            ok = true, state = "ACTIVE", atMs = 1L,
        ))
        val s = ThinkingTreeStore.loadFrom(root, "p", "c").single()
        assertFalse("interrupted work must not read as success", s.state == "ACTIVE")
        assertEquals("UNKNOWN", s.state)
    }

    // ---------------------------------------------------------------- 11. failure & security

    @Test
    fun `every store survives corruption without inventing content`() {
        val d = tmp.newFolder("corrupt")
        FactsStore.saveTo(d, "The build uses Gradle", FactScope.USER, null)
        ReviewQueue.fileIn(d).writeText("not json at all")
        assertEquals(0, ReviewQueue.loadFrom(d).size)
        assertTrue("accepted facts survive", FactsStore.loadFrom(d).isNotEmpty())
        // A corrupt queue cannot accept anything.
        assertTrue(ReviewQueue.accept(d, "anything", 1L) !is ReviewQueue.Outcome.Accepted)
    }

    @Test
    fun `secrets never reach the queue`() {
        val d = tmp.newFolder("secret")
        MemoryExtraction.capture(d, "I always use api_key sk-abcdefghijklmnopqrstuvwxyz123456", verifiedProv)
        assertEquals(0, ReviewQueue.loadFrom(d).size)
        assertEquals(0, FactsStore.loadFrom(d).size)
    }

    @Test
    fun `scope isolation holds for project and user facts`() {
        val facts = listOf(
            fact("p1", "the cache uses Room", project = "alpha"),
            fact("u1", "I prefer tabs"),
        )
        val forAlpha = FactRetrieval.temporalRank(facts, "cache", "alpha", 1L).map { it.fact.id }
        assertTrue(forAlpha.contains("p1"))
        assertTrue("user facts stay visible in a project", forAlpha.contains("u1"))
        val forBeta = FactRetrieval.temporalRank(facts, "cache", "beta", 1L).map { it.fact.id }
        assertFalse("another project's facts must not leak", forBeta.contains("p1"))
    }

    // ---------------------------------------------------------------- fixtures

    private fun fact(
        id: String,
        text: String,
        at: Long = 0L,
        src: TimeSource = TimeSource.UNKNOWN,
        project: String? = null,
    ) = Fact(
        id = id,
        content = text,
        scope = if (project == null) FactScope.USER else FactScope.PROJECT,
        projectId = project,
        sourceConversationAt = at,
        timeSource = src,
        createdAt = 1L,
        updatedAt = 1L,
    )
}
