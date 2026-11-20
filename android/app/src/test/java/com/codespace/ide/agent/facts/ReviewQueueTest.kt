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

class ReviewQueueTest {

    @get:Rule val tmp = TemporaryFolder()

    private val prov = FactProvenance(
        sourceConversationId = "conv-1",
        conversationStartedAt = 1_000L,
        sourceConversationAt = 1_000L,
        sourceMessageId = "m7",
        sourceMessageAt = 6_000L,
        timeSource = TimeSource.TRANSCRIPT_VERIFIED,
    )

    private fun cand(text: String) =
        MemoryExtraction.Candidate(text, MemoryExtraction.Kind.PREFERENCE, FactCategory.PREFERENCE)

    @Test
    fun aCandidateIsPersistedAsPendingAndSavesNoFact() {
        val d = tmp.newFolder("m")
        val e = ReviewQueue.enqueue(d, listOf(cand("I prefer tabs for indentation")), prov)
        assertEquals(1, e.added)
        val q = ReviewQueue.loadFrom(d).single()
        assertEquals(ReviewStatus.PENDING, q.status)
        assertEquals("m7", q.sourceMessageId)
        assertEquals(TimeSource.TRANSCRIPT_VERIFIED, q.timeSource)
        assertEquals(0, FactsStore.loadFrom(d).size)
    }

    @Test
    fun aPendingCandidateSurvivesARestart() {
        val d = tmp.newFolder("m")
        ReviewQueue.enqueue(d, listOf(cand("I prefer tabs for indentation")), prov)
        // A fresh read from disk is the restart path: nothing is held in memory.
        assertEquals(1, ReviewQueue.pending(ReviewQueue.loadFrom(d)).size)
    }

    @Test
    fun acceptingSavesThroughTheFactStoreAndKeepsProvenance() {
        val d = tmp.newFolder("m")
        ReviewQueue.enqueue(d, listOf(cand("I prefer the Gradle wrapper")), prov)
        val id = ReviewQueue.loadFrom(d).single().id
        val out = ReviewQueue.accept(d, id, now = 9_000L)
        assertTrue(out is ReviewQueue.Outcome.Accepted)
        val f = FactsStore.loadFrom(d).single()
        assertEquals("conv-1", f.sourceConversationId)
        assertEquals("m7", f.sourceMessageId)
        assertEquals(TimeSource.TRANSCRIPT_VERIFIED, f.timeSource)
        // Approval never rewrites provenance: it was inferred by the app, and stays that way.
        assertEquals(FactSource.INFERRED, f.source)
        assertEquals(ReviewStatus.ACCEPTED, ReviewQueue.loadFrom(d).single().status)
        assertEquals(f.id, ReviewQueue.loadFrom(d).single().factId)
    }

    @Test
    fun acceptingTwiceSavesOnce() {
        val d = tmp.newFolder("m")
        ReviewQueue.enqueue(d, listOf(cand("I prefer the Gradle wrapper")), prov)
        val id = ReviewQueue.loadFrom(d).single().id
        ReviewQueue.accept(d, id)
        val second = ReviewQueue.accept(d, id)
        assertEquals(ReviewQueue.Outcome.AlreadyResolved, second)
        assertEquals(1, FactsStore.loadFrom(d).size)
    }

    @Test
    fun rejectingNeverSavesAFactAndRecordsTheTime() {
        val d = tmp.newFolder("m")
        ReviewQueue.enqueue(d, listOf(cand("I prefer tabs for indentation")), prov)
        val id = ReviewQueue.loadFrom(d).single().id
        assertEquals(ReviewQueue.Outcome.Rejected, ReviewQueue.reject(d, id, now = 5_000L))
        assertEquals(0, FactsStore.loadFrom(d).size)
        val q = ReviewQueue.loadFrom(d).single()
        assertEquals(ReviewStatus.REJECTED, q.status)
        assertEquals(5_000L, q.reviewedAt)
        assertNull(q.reason)
    }

    @Test
    fun aReasonIsOptionalButKeptWhenGiven() {
        val d = tmp.newFolder("m")
        ReviewQueue.enqueue(d, listOf(cand("I prefer tabs for indentation")), prov)
        val id = ReviewQueue.loadFrom(d).single().id
        ReviewQueue.reject(d, id, reason = "  not true anymore  ")
        assertEquals("not true anymore", ReviewQueue.loadFrom(d).single().reason)
    }

    @Test
    fun aRejectedCandidateCanBeReconsidered() {
        val d = tmp.newFolder("m")
        ReviewQueue.enqueue(d, listOf(cand("I prefer tabs for indentation")), prov)
        val id = ReviewQueue.loadFrom(d).single().id
        ReviewQueue.reject(d, id)
        assertTrue(ReviewQueue.reconsider(d, id))
        assertEquals(ReviewStatus.PENDING, ReviewQueue.loadFrom(d).single().status)
        assertNull(ReviewQueue.loadFrom(d).single().reason)
    }

    @Test
    fun aRejectedStatementIsNotSuggestedAgain() {
        val d = tmp.newFolder("m")
        ReviewQueue.enqueue(d, listOf(cand("I prefer tabs for indentation")), prov)
        ReviewQueue.reject(d, ReviewQueue.loadFrom(d).single().id)
        val again = ReviewQueue.enqueue(d, listOf(cand("I prefer tabs for indentation")), prov)
        assertEquals(0, again.added)
        assertEquals(1, again.rejectedBefore)
        assertEquals(1, ReviewQueue.loadFrom(d).size)
    }

    @Test
    fun aRewordedRejectionIsRecognisedAsTheSameStatement() {
        val d = tmp.newFolder("m")
        ReviewQueue.enqueue(d, listOf(cand("I prefer tabs for indentation in this project")), prov)
        ReviewQueue.reject(d, ReviewQueue.loadFrom(d).single().id)
        val again = ReviewQueue.enqueue(
            d, listOf(cand("I prefer tabs for indentation in this project.")), prov,
        )
        assertEquals(0, again.added)
    }

    @Test
    fun anUnrelatedStatementInTheSameCategoryIsStillAdded() {
        val d = tmp.newFolder("m")
        ReviewQueue.enqueue(d, listOf(cand("I prefer tabs for indentation")), prov)
        ReviewQueue.reject(d, ReviewQueue.loadFrom(d).single().id)
        val other = ReviewQueue.enqueue(d, listOf(cand("I prefer the Gradle wrapper for every build")), prov)
        assertEquals(1, other.added)
        assertEquals(2, ReviewQueue.loadFrom(d).size)
    }

    @Test
    fun aMissingSourceMessageStaysMissing() {
        val d = tmp.newFolder("m")
        ReviewQueue.enqueue(d, listOf(cand("I prefer tabs for indentation")), FactProvenance.NONE)
        val q = ReviewQueue.loadFrom(d).single()
        assertNull(q.sourceMessageId)
        assertNull(q.sourceConversationId)
        assertEquals(0L, q.sourceConversationAt)
        assertEquals(TimeSource.UNKNOWN, q.timeSource)
    }

    @Test
    fun acceptingWithoutProvenanceDoesNotInventOne() {
        val d = tmp.newFolder("m")
        ReviewQueue.enqueue(d, listOf(cand("I prefer tabs for indentation")), FactProvenance.NONE)
        ReviewQueue.accept(d, ReviewQueue.loadFrom(d).single().id)
        val f = FactsStore.loadFrom(d).single()
        assertNull(f.sourceMessageId)
        assertEquals(0L, f.sourceConversationAt)
        assertEquals(TimeSource.UNKNOWN, f.timeSource)
    }

    @Test
    fun scopeIsolationHoldsForTheQueue() {
        val d = tmp.newFolder("m")
        ReviewQueue.enqueue(d, listOf(cand("I prefer tabs for indentation")), prov, FactScope.PROJECT, "p1")
        val q = ReviewQueue.loadFrom(d).single()
        assertEquals(FactScope.PROJECT, q.scope)
        assertEquals("p1", q.projectId)
        assertTrue(q.belongsTo("p1"))
        assertFalse(q.belongsTo("p2"))
        assertFalse(q.belongsTo(null))
    }

    @Test
    fun aFailedAcceptanceWriteLeavesTheCandidatePending() {
        val d = tmp.newFolder("m")
        ReviewQueue.enqueue(d, listOf(cand("I prefer the Gradle wrapper")), prov)
        val id = ReviewQueue.loadFrom(d).single().id
        // Make the fact file unwritable by putting an unremovable directory in its place.
        val facts = File(d, "facts.json")
        facts.mkdirs()
        File(facts, "keep.txt").writeText("x")
        val out = ReviewQueue.accept(d, id)
        assertEquals(ReviewQueue.Outcome.WriteFailed, out)
        assertEquals(ReviewStatus.PENDING, ReviewQueue.loadFrom(d).single().status)
    }

    @Test
    fun aFailedRejectionWriteDoesNotClaimRejection() {
        val d = tmp.newFolder("m")
        ReviewQueue.enqueue(d, listOf(cand("I prefer tabs for indentation")), prov)
        val id = ReviewQueue.loadFrom(d).single().id
        val qfile = ReviewQueue.fileIn(d)
        qfile.delete()
        qfile.mkdirs()
        File(qfile, "keep.txt").writeText("x")
        val out = ReviewQueue.reject(d, id)
        // An unreadable queue cannot confirm a rejection: the outcome must never be "Rejected".
        assertFalse(out is ReviewQueue.Outcome.Rejected)
        // The unreadable queue reports nothing accepted, and nothing rejected either.
        assertEquals(0, ReviewQueue.pending(ReviewQueue.loadFrom(d)).size)
        assertEquals(0, FactsStore.loadFrom(d).size)
    }

    @Test
    fun aCorruptQueueCannotPromoteAnything() {
        val d = tmp.newFolder("m")
        ReviewQueue.fileIn(d).writeText("{ this is not json")
        assertTrue(ReviewQueue.loadFrom(d).isEmpty())
        assertEquals(ReviewQueue.Outcome.NotFound, ReviewQueue.accept(d, "r_nope"))
        assertEquals(0, FactsStore.loadFrom(d).size)
    }

    @Test
    fun duplicateCandidatesAreNotQueuedTwice() {
        val d = tmp.newFolder("m")
        val e = ReviewQueue.enqueue(
            d,
            listOf(cand("I prefer tabs for indentation"), cand("I prefer tabs for indentation")),
            prov,
        )
        assertEquals(1, e.added)
        assertEquals(1, e.duplicates)
        assertEquals(1, ReviewQueue.loadFrom(d).size)
    }

    @Test
    fun rejectionsOlderThanTheRetentionWindowAreDropped() {
        val d = tmp.newFolder("m")
        val old = 1_000L
        ReviewQueue.enqueue(d, listOf(cand("I prefer tabs for indentation")), prov, now = old)
        ReviewQueue.reject(d, ReviewQueue.loadFrom(d).single().id, now = old)
        val later = old + (ReviewQueue.REJECT_RETENTION_DAYS + 1) * 86_400_000L
        ReviewQueue.enqueue(d, listOf(cand("I prefer the Gradle wrapper")), prov, now = later)
        assertTrue(ReviewQueue.rejected(ReviewQueue.loadFrom(d)).isEmpty())
    }

    @Test
    fun aManualFactIsUnaffectedByTheQueue() {
        val d = tmp.newFolder("m")
        FactsStore.saveTo(d, "Build with the wrapper", FactScope.USER, null)
        assertTrue(ReviewQueue.loadFrom(d).isEmpty())
        assertEquals(1, FactsStore.loadFrom(d).size)
    }

    @Test
    fun acceptingAContradictionRoutesThroughSupersession() {
        val d = tmp.newFolder("m")
        FactsStore.saveTo(d, "We decided to use SQLite for storage", FactScope.USER, null)
        ReviewQueue.enqueue(d, listOf(cand("We decided to use SQLite for storage")), prov)
        ReviewQueue.accept(d, ReviewQueue.loadFrom(d).single().id)
        // Same statement, so it is a duplicate rather than a second fact.
        assertEquals(1, FactsStore.loadFrom(d).size)
        assertNotNull(FactsStore.loadFrom(d).single().id)
    }
}
