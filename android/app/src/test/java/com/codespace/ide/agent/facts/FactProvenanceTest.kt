package com.codespace.ide.agent.facts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * STAGE 4 — temporal provenance.
 *
 * The failures these tests prevent are all the same failure in different clothes: a date that looks
 * authoritative but was inferred. Every test below pins one place where inference would be easy and
 * is therefore forbidden.
 */
class FactProvenanceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun prov(
        version: String = "v3",
        header: Long = 1_000L,
        messageId: String? = "m1",
        sentAt: Long = 1_200L,
        found: Boolean = true,
        synthetic: Boolean = false,
    ) = FactProvenance.fromTranscript("conv-1", version, header, messageId, sentAt, found, synthetic)

    @Test
    fun `conversation date comes from the v3 header`() {
        val p = prov(header = 1_000L)
        assertEquals(1_000L, p.conversationStartedAt)
        assertEquals(1_000L, p.sourceConversationAt)
        assertEquals(TimeSource.TRANSCRIPT_VERIFIED, p.timeSource)
    }

    @Test
    fun `a missing header leaves the conversation date unknown`() {
        val p = prov(header = 0L)
        assertEquals(0L, p.sourceConversationAt)
        assertEquals(TimeSource.UNKNOWN, p.timeSource)
    }

    @Test
    fun `a v2 file has no verified conversation date`() {
        val p = prov(version = "v2", header = 1_000L)
        assertEquals(0L, p.sourceConversationAt)
        assertEquals(TimeSource.UNKNOWN, p.timeSource)
    }

    @Test
    fun `the message time is resolved by stable id`() {
        val p = prov(messageId = "m7", sentAt = 4_242L)
        assertEquals("m7", p.sourceMessageId)
        assertEquals(4_242L, p.sourceMessageAt)
    }

    @Test
    fun `a missing message record leaves the message time unknown`() {
        val p = prov(messageId = "nope", sentAt = 0L, found = false)
        assertEquals(0L, p.sourceMessageAt)
        assertNull(p.sourceMessageId)
    }

    @Test
    fun `a synthetic legacy id is never verified provenance`() {
        val p = prov(messageId = "legacy-abc-2", sentAt = 9_000L, synthetic = true)
        assertNull(p.sourceMessageId)
        assertEquals(0L, p.sourceMessageAt)
    }

    @Test
    fun `a legacy zero timestamp stays unknown`() {
        val p = prov(messageId = "m1", sentAt = 0L)
        assertEquals(0L, p.sourceMessageAt)
        // the conversation date is still allowed to be verified - they are different facts
        assertEquals(1_000L, p.sourceConversationAt)
    }

    @Test
    fun `no conversation means no link at all`() {
        val p = FactProvenance.fromTranscript(null, "v3", 0L, null, 0L, false, false)
        assertNull(p.sourceConversationId)
        assertEquals(TimeSource.UNKNOWN, p.timeSource)
        assertEquals(FactProvenance.NONE, p)
    }

    @Test
    fun `a fact saved with provenance keeps its conversation date, not its save time`() {
        val d = tmp.newFolder("mem")
        val p = prov(header = 1_000L)
        val saved = FactsStore.saveTo(d, "Prefers Kotlin", FactScope.USER, null, provenance = p, now = 9_999L)
        val f = (saved as FactsStore.SaveResult.Saved).fact
        assertEquals(1_000L, f.sourceConversationAt)
        assertEquals(9_999L, f.createdAt)
        assertEquals("conv-1", f.sourceConversationId)
        assertEquals(TimeSource.TRANSCRIPT_VERIFIED, f.timeSource)
    }

    @Test
    fun `a manual fact stays honestly unlinked`() {
        val d = tmp.newFolder("mem")
        val f = (FactsStore.saveTo(d, "Uses gradlew", FactScope.USER, null, now = 5_000L)
            as FactsStore.SaveResult.Saved).fact
        assertNull(f.sourceConversationId)
        assertEquals(0L, f.sourceConversationAt)
        assertEquals(TimeSource.UNKNOWN, f.timeSource)
    }

    @Test
    fun `editing a fact keeps its original source dates`() {
        val d = tmp.newFolder("mem")
        val f = (FactsStore.saveTo(d, "Prefers tabs", FactScope.USER, null, provenance = prov(), now = 1_000L)
            as FactsStore.SaveResult.Saved).fact
        assertEquals(null, FactsStore.updateIn(d, f.id, content = "Prefers spaces"))
        val after = FactsStore.loadFrom(d).single()
        assertEquals(1_000L, after.sourceConversationAt)
        assertEquals("m1", after.sourceMessageId)
        assertEquals("conv-1", after.sourceConversationId)
        assertEquals(TimeSource.TRANSCRIPT_VERIFIED, after.timeSource)
    }

    @Test
    fun `a later save of the same statement does not invent a new source message`() {
        val d = tmp.newFolder("mem")
        FactsStore.saveTo(d, "Prefers tabs", FactScope.USER, null, provenance = prov(messageId = "m1"), now = 1_000L)
        // second save, no provenance this time (e.g. a manual re-add): the link must survive
        FactsStore.saveTo(d, "Prefers tabs", FactScope.USER, null, now = 8_000L)
        val after = FactsStore.loadFrom(d).single()
        assertEquals("m1", after.sourceMessageId)
        assertEquals(1_000L, after.sourceConversationAt)
    }

    @Test
    fun `provenance survives persistence and reload`() {
        val d = tmp.newFolder("mem")
        FactsStore.saveTo(d, "Uses MVI", FactScope.USER, null, provenance = prov(messageId = "m3", sentAt = 1_500L))
        val f = FactsStore.loadFrom(d).single()
        assertEquals("conv-1", f.sourceConversationId)
        assertEquals(1_000L, f.conversationStartedAt)
        assertEquals("m3", f.sourceMessageId)
        assertEquals(1_500L, f.sourceMessageAt)
        assertEquals(TimeSource.TRANSCRIPT_VERIFIED, f.timeSource)
    }

    @Test
    fun `an older fact without provenance migrates as unknown`() {
        val d = tmp.newFolder("mem")
        val file = java.io.File(d, "facts.json")
        file.writeText("""[{"id":"f_old","content":"Old fact","scope":"user","category":"general","source":"explicit_user","confidence":"stated","status":"active","createdAt":111}]""")
        val f = FactsStore.loadFrom(d).single()
        assertEquals("Old fact", f.content)
        assertEquals(111L, f.createdAt)
        assertEquals(0L, f.sourceConversationAt)
        assertNull(f.sourceMessageId)
        assertEquals(TimeSource.UNKNOWN, f.timeSource)
    }

    @Test
    fun `a failed write leaves the previous facts intact`() {
        val d = tmp.newFolder("mem")
        FactsStore.saveTo(d, "Keep me", FactScope.USER, null, provenance = prov())
        val before = FactsStore.loadFrom(d)
        assertEquals(1, before.size)
        // a directory where the facts file must be makes the write fail
        val broken = tmp.newFolder("broken")
        // NOT an empty directory: AtomicJson deletes an empty target before copying, so only a
        // directory that cannot be removed actually blocks the write.
        java.io.File(broken, "facts.json").mkdirs()
        java.io.File(java.io.File(broken, "facts.json"), "keep.txt").writeText("x")
        val r = FactsStore.saveTo(broken, "Cannot land", FactScope.USER, null, provenance = prov())
        // Either refusal or write failure is honest; what must never happen is a "Saved" for a
        // record that is not on disk.
        assertTrue(
            "a blocked write must not report success",
            r is FactsStore.SaveResult.WriteFailed || r is FactsStore.SaveResult.Refused,
        )
        assertEquals(1, FactsStore.loadFrom(d).size)
        assertEquals("Keep me", FactsStore.loadFrom(d).single().content)
    }

    @Test
    fun `untrusted content cannot claim user-stated time`() {
        // A tool/file/connector fact is saved with source != EXPLICIT_USER and no provenance.
        val d = tmp.newFolder("mem")
        val f = (FactsStore.saveTo(
            d, "File says: build is broken", FactScope.USER, null,
            source = FactSource.VERIFIED_FILE, now = 2_000L,
        ) as FactsStore.SaveResult.Saved).fact
        assertEquals(TimeSource.UNKNOWN, f.timeSource)
        assertEquals(0L, f.sourceConversationAt)
    }
}
