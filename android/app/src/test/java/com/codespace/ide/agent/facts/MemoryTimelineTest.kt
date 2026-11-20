package com.codespace.ide.agent.facts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** STAGE 9 — the timeline is a record of what the app did, never a reconstruction of what it did not. */
class MemoryTimelineTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun dir(): File = tmp.newFolder()

    private fun event(
        type: TimelineEventType,
        subject: String = "f1",
        at: Long = 1_000L,
        scope: FactScope = FactScope.USER,
        projectId: String? = null,
        subjectTime: Long = 0L,
        timeSource: TimeSource = TimeSource.UNKNOWN,
        replacement: String? = null,
    ) = TimelineEvent(
        id = MemoryTimeline.idFor(type, subject, at),
        type = type,
        subjectId = subject,
        subjectKind = "fact",
        atMs = at,
        scope = scope,
        projectId = projectId,
        newText = "text",
        subjectTimeMs = subjectTime,
        timeSource = timeSource,
        replacementId = replacement,
    )

    @Test
    fun `an event round trips through the document`() {
        val d = dir()
        assertTrue(MemoryTimeline.record(d, event(TimelineEventType.CREATED)))
        val loaded = MemoryTimeline.loadFrom(d)
        assertEquals(1, loaded.size)
        assertEquals(TimelineEventType.CREATED, loaded[0].type)
        assertEquals("f1", loaded[0].subjectId)
        assertEquals(1_000L, loaded[0].atMs)
    }

    @Test
    fun `event identity is stable and repeated writes do not duplicate`() {
        val d = dir()
        val e = event(TimelineEventType.CREATED, at = 5_000L)
        assertTrue(MemoryTimeline.record(d, e))
        assertTrue(MemoryTimeline.record(d, e))
        assertEquals(1, MemoryTimeline.loadFrom(d).size)
        assertEquals(e.id, MemoryTimeline.idFor(TimelineEventType.CREATED, "f1", 5_000L))
    }

    @Test
    fun `newest first, and equal timestamps do not reorder history`() {
        val d = dir()
        MemoryTimeline.record(d, event(TimelineEventType.CREATED, "f1", 1_000L))
        MemoryTimeline.record(d, event(TimelineEventType.UPDATED, "f1", 3_000L))
        MemoryTimeline.record(d, event(TimelineEventType.UPDATED, "f1", 2_000L))
        val order = MemoryTimeline.loadFrom(d).map { it.atMs }
        assertEquals(listOf(3_000L, 2_000L, 1_000L), order)
    }

    @Test
    fun `a fact save records a created event with its verified date`() {
        val d = dir()
        val r = FactsStore.saveTo(
            d, "The build uses Gradle", FactScope.USER, null,
            provenance = FactProvenance(
                sourceConversationId = "conv-1",
                sourceConversationAt = 7_000L,
                timeSource = TimeSource.TRANSCRIPT_VERIFIED,
            ),
        ) as FactsStore.SaveResult.Saved
        val e = MemoryTimeline.loadFrom(d).single()
        assertEquals(TimelineEventType.CREATED, e.type)
        assertEquals(r.fact.id, e.subjectId)
        assertEquals(7_000L, e.subjectTimeMs)
        assertEquals(TimeSource.TRANSCRIPT_VERIFIED, e.timeSource)
        assertTrue(e.hasVerifiedTime())
        assertEquals("conv-1", e.sourceConversationId)
    }

    @Test
    fun `a replacement records supersession and the replacement link`() {
        val d = dir()
        val first = (FactsStore.saveTo(d, "The build uses Gradle", FactScope.USER, null)
            as FactsStore.SaveResult.Saved).fact
        val second = (FactsStore.saveTo(d, "The build uses Gradle 8 with the wrapper", FactScope.USER, null)
            as FactsStore.SaveResult.Saved).fact
        val events = MemoryTimeline.loadFrom(d)
        val sup = events.first { it.type == TimelineEventType.SUPERSEDED }
        assertEquals(first.id, sup.subjectId)
        assertEquals(second.id, sup.replacementId)
        assertTrue(events.any { it.type == TimelineEventType.CREATED && it.subjectId == second.id })
        // The old fact survives: supersession is history, not deletion.
        assertTrue(FactsStore.loadFrom(d).any { it.id == first.id && it.status == FactStatus.SUPERSEDED })
    }

    @Test
    fun `editing records the previous text and keeps the original date`() {
        val d = dir()
        val f = (FactsStore.saveTo(
            d, "Prefer tabs", FactScope.USER, null,
            provenance = FactProvenance(sourceConversationAt = 9_000L, timeSource = TimeSource.TRANSCRIPT_VERIFIED),
        ) as FactsStore.SaveResult.Saved).fact
        FactsStore.updateIn(d, f.id, content = "Prefer tabs everywhere")
        val e = MemoryTimeline.loadFrom(d).first { it.type == TimelineEventType.UPDATED }
        assertEquals("Prefer tabs", e.previousText)
        assertEquals("Prefer tabs everywhere", e.newText)
        assertEquals(9_000L, e.subjectTimeMs)
    }

    @Test
    fun `deleting records an event and removes only the fact`() {
        val d = dir()
        val f = (FactsStore.saveTo(d, "Temporary note", FactScope.USER, null)
            as FactsStore.SaveResult.Saved).fact
        assertTrue(FactsStore.deleteIn(d, f.id))
        assertEquals(0, FactsStore.loadFrom(d).size)
        assertTrue(MemoryTimeline.loadFrom(d).any { it.type == TimelineEventType.DELETED })
    }

    @Test
    fun `a refused save records no event`() {
        val d = dir()
        val r = FactsStore.saveTo(d, "  ", FactScope.USER, null)
        assertTrue(r is FactsStore.SaveResult.Refused)
        assertTrue(MemoryTimeline.loadFrom(d).isEmpty())
    }

    @Test
    fun `a failed write records no event`() {
        val d = dir()
        File(d, "facts.json").mkdirs()
        File(File(d, "facts.json"), "keep.txt").writeText("x")
        val r = FactsStore.saveTo(d, "The build uses Gradle", FactScope.USER, null)
        assertFalse(r is FactsStore.SaveResult.Saved)
        assertTrue(MemoryTimeline.loadFrom(d).isEmpty())
    }

    @Test
    fun `accepting a candidate records an acceptance with the candidate provenance`() {
        val d = dir()
        MemoryExtraction.capture(
            d, "I prefer tabs over spaces in this project.",
            FactProvenance(
                sourceConversationId = "conv-2",
                sourceConversationAt = 4_000L,
                timeSource = TimeSource.TRANSCRIPT_VERIFIED,
            ),
        )
        val c = ReviewQueue.loadFrom(d).single()
        val out = ReviewQueue.accept(d, c.id)
        assertTrue(out is ReviewQueue.Outcome.Accepted)
        val e = MemoryTimeline.loadFrom(d).first { it.type == TimelineEventType.ACCEPTED }
        assertEquals(c.id, e.subjectId)
        assertEquals("conv-2", e.sourceConversationId)
        assertEquals(4_000L, e.subjectTimeMs)
        assertEquals(TimeSource.TRANSCRIPT_VERIFIED, e.timeSource)
    }

    @Test
    fun `rejection and reconsideration are recorded without accepting`() {
        val d = dir()
        MemoryExtraction.capture(d, "I prefer tabs over spaces in this project.", FactProvenance.NONE)
        val c = ReviewQueue.loadFrom(d).single()
        assertTrue(ReviewQueue.reject(d, c.id, "not now") is ReviewQueue.Outcome.Rejected)
        assertTrue(ReviewQueue.reconsider(d, c.id))
        val types = MemoryTimeline.loadFrom(d).map { it.type }
        assertTrue(types.contains(TimelineEventType.REJECTED))
        assertTrue(types.contains(TimelineEventType.RECONSIDERED))
        assertFalse(types.contains(TimelineEventType.ACCEPTED))
        assertEquals(0, FactsStore.loadFrom(d).size)
    }

    @Test
    fun `a repeated review action cannot duplicate its event`() {
        val d = dir()
        MemoryExtraction.capture(d, "I prefer tabs over spaces in this project.", FactProvenance.NONE)
        val c = ReviewQueue.loadFrom(d).single()
        ReviewQueue.reject(d, c.id)
        ReviewQueue.reject(d, c.id)
        assertEquals(1, MemoryTimeline.loadFrom(d).count { it.type == TimelineEventType.REJECTED })
    }

    @Test
    fun `unknown dates stay unknown and never borrow the event time`() {
        val d = dir()
        FactsStore.saveTo(d, "The build uses Gradle", FactScope.USER, null)
        val e = MemoryTimeline.loadFrom(d).single()
        assertEquals(0L, e.subjectTimeMs)
        assertEquals(TimeSource.UNKNOWN, e.timeSource)
        assertFalse(e.hasVerifiedTime())
    }

    @Test
    fun `user stated dates are distinguishable from verified ones`() {
        val d = dir()
        FactsStore.saveTo(
            d, "We agreed the deadline in March", FactScope.USER, null,
            provenance = FactProvenance(sourceConversationAt = 3_000L, timeSource = TimeSource.USER_STATED),
        )
        val e = MemoryTimeline.loadFrom(d).single()
        assertEquals(TimeSource.USER_STATED, e.timeSource)
        assertFalse(e.hasVerifiedTime())
    }

    @Test
    fun `project scope isolation holds`() {
        val d = dir()
        MemoryTimeline.record(d, event(TimelineEventType.CREATED, "a", 1_000L, FactScope.PROJECT, "p1"))
        MemoryTimeline.record(d, event(TimelineEventType.CREATED, "b", 2_000L, FactScope.PROJECT, "p2"))
        MemoryTimeline.record(d, event(TimelineEventType.CREATED, "c", 3_000L, FactScope.USER))
        val p1 = MemoryTimeline.events(d, "p1").map { it.subjectId }.toSet()
        assertEquals(setOf("a", "c"), p1)
        assertFalse(p1.contains("b"))
    }

    @Test
    fun `storage is bounded and pruning never touches facts`() {
        val d = dir()
        val f = (FactsStore.saveTo(d, "The build uses Gradle", FactScope.USER, null)
            as FactsStore.SaveResult.Saved).fact
        for (i in 0 until MemoryTimeline.MAX_EVENTS + 25) {
            MemoryTimeline.record(d, event(TimelineEventType.UPDATED, "f" + i, 10_000L + i))
        }
        val events = MemoryTimeline.loadFrom(d)
        assertEquals(MemoryTimeline.MAX_EVENTS, events.size)
        assertTrue("newest kept", events.first().atMs >= 10_000L + MemoryTimeline.MAX_EVENTS)
        assertEquals(1, FactsStore.loadFrom(d).count { it.id == f.id })
    }

    @Test
    fun `a corrupt document reads as empty rather than invented history`() {
        val d = dir()
        File(d, MemoryTimeline.FILE_NAME).writeText("{not json")
        assertTrue(MemoryTimeline.loadFrom(d).isEmpty())
        // Recording after corruption still works and does not fabricate earlier events.
        MemoryTimeline.record(d, event(TimelineEventType.CREATED))
        assertEquals(1, MemoryTimeline.loadFrom(d).size)
    }

    @Test
    fun `a truncated document keeps the events it can parse`() {
        val d = dir()
        MemoryTimeline.record(d, event(TimelineEventType.CREATED, "f1", 1_000L))
        val raw = File(d, MemoryTimeline.FILE_NAME).readText()
        File(d, MemoryTimeline.FILE_NAME).writeText(raw.substring(0, raw.length / 2))
        val loaded = MemoryTimeline.loadFrom(d)
        assertTrue(loaded.isEmpty() || loaded.all { it.id.isNotBlank() })
    }

    @Test
    fun `legacy facts without events are not given invented history`() {
        val d = dir()
        File(d, "facts.json").writeText(
            """[{"id":"legacy1","content":"Old build note","scope":"user","category":"build"}]""",
        )
        assertEquals(1, FactsStore.loadFrom(d).size)
        assertTrue(MemoryTimeline.loadFrom(d).isEmpty())
    }

    @Test
    fun `events keep the conversation link and never invent a message id`() {
        val d = dir()
        FactsStore.saveTo(
            d, "The build uses Gradle", FactScope.USER, null,
            provenance = FactProvenance(
                sourceConversationId = "conv-9",
                sourceConversationAt = 1_500L,
                timeSource = TimeSource.TRANSCRIPT_VERIFIED,
            ),
        )
        val e = MemoryTimeline.loadFrom(d).single()
        assertEquals("conv-9", e.sourceConversationId)
        assertNull(e.sourceMessageId)
        assertNotNull(e.id)
    }
}
