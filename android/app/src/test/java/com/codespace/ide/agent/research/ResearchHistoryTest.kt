package com.codespace.ide.agent.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PROJECT 1.7: research that outlives the turn, and a follow-up that reuses what was read.
 *
 * Two things are being guarded: the record must come back from disk unchanged, and "this question
 * continues that one" must never match on a single shared word, because a wrong match puts
 * unrelated citations in an answer.
 */
class ResearchHistoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun source(id: String, url: String, title: String = "Title " + id) =
        SourceLedger.Source(
            id = id, title = title, url = url, domain = UrlTools.domain(url),
            retrievedAt = "2026-11-15 10:00", publishedAt = "", excerpt = "excerpt " + id,
        )

    @Test
    fun aRecordedQuestionComesBackFromDisk() {
        val file = tmp.newFile("h.json")
        file.delete()
        val store = ResearchHistory.forTest(file)
        store.remember("proot link2symlink crash", 1000L, listOf(
            source("S1", "https://example.com/a"), source("S2", "https://other.org/b")))
        val fresh = ResearchHistory.forTest(file)
        val all = fresh.all()
        assertEquals(1, all.size)
        assertEquals("proot link2symlink crash", all[0].question)
        assertEquals(2, all[0].sources.size)
        assertEquals("https://other.org/b", all[0].sources[1].url)
    }

    @Test
    fun theSameQuestionReplacesItsOldRecordInsteadOfPilingUp() {
        val file = tmp.newFile("h2.json")
        file.delete()
        val store = ResearchHistory.forTest(file)
        store.remember("gradle build failing", 1L, listOf(source("S1", "https://a.example.com/x")))
        store.remember("Gradle build failing", 2L, listOf(source("S1", "https://a.example.com/y")))
        assertEquals(1, store.all().size)
        assertEquals(2L, store.all()[0].atMs)
    }

    @Test
    fun nothingIsRecordedWithoutSources() {
        val file = tmp.newFile("h3.json")
        file.delete()
        val store = ResearchHistory.forTest(file)
        store.remember("a question with no evidence", 1L, emptyList())
        store.remember("   ", 1L, listOf(source("S1", "https://example.com/a")))
        assertEquals(0, store.all().size)
    }

    @Test
    fun aFollowUpFindsTheResearchItContinues() {
        val file = tmp.newFile("h4.json")
        file.delete()
        val store = ResearchHistory.forTest(file)
        store.remember("proot link2symlink crash on Android 14", 1L, listOf(source("S1", "https://example.com/a")))
        store.remember("kotlin flow collection", 2L, listOf(source("S2", "https://other.org/b")))
        val hit = store.continuationFor("how do I avoid the link2symlink crash in proot")
        assertNotNull(hit)
        assertEquals("proot link2symlink crash on Android 14", hit?.question)
    }

    @Test
    fun oneSharedWordIsATopicNotAFollowUp() {
        val entries = listOf(
            ResearchHistory.Entry("gradle build failing", 1L, listOf(source("S1", "https://a.example.com/x"))),
        )
        assertNull(ResearchHistory.pick(entries, "gradle plugin version catalogue"))
        assertNull(ResearchHistory.pick(entries, "gradle"))
    }

    @Test
    fun aCorruptFileReadsAsEmptyRatherThanThrowing() {
        val file = tmp.newFile("h5.json")
        file.writeText("{not json at all")
        assertEquals(0, ResearchHistory.forTest(file).all().size)
    }
}
