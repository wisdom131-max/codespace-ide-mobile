package com.codespace.ide.chat.voice

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * M3 — voice transcripts are stored as fact, per conversation, and a corrupt line is reported
 * rather than silently repaired. Nothing here fabricates a transcript: a blank result is
 * refused, so an empty utterance can never appear in the view.
 */
class VoiceTranscriptStoreTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = File.createTempFile("vncode-voice", "test").let {
            it.delete(); it.mkdirs(); it
        }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun store() = VoiceTranscriptStore(root)

    @Test
    fun `an utterance round-trips with its timestamp`() {
        val s = store()
        assertNull(s.append("p", "c1", "open the settings panel", 1_700_000_000_000L))
        val read = s.read("p", "c1")
        assertEquals(1, read.items.size)
        assertEquals("open the settings panel", read.items[0].text)
        assertEquals(1_700_000_000_000L, read.items[0].atMs)
        assertNull(read.reason)
    }

    @Test
    fun `tabs, newlines and backslashes survive storage`() {
        val s = store()
        val messy = "line one\nline two\twith a tab and a \\ backslash"
        s.append("p", "c1", messy, 5L)
        assertEquals(messy, s.read("p", "c1").items[0].text)
    }

    @Test
    fun `blank dictation is refused, never stored as an empty transcript`() {
        val s = store()
        assertNotNull(s.append("p", "c1", "   ", 1L))
        assertTrue(s.read("p", "c1").items.isEmpty())
    }

    @Test
    fun `transcripts are per conversation and never leak across sessions`() {
        val s = store()
        s.append("p", "alpha", "alpha words", 1L)
        s.append("p", "beta", "beta words", 2L)
        assertEquals(listOf("alpha words"), s.read("p", "alpha").items.map { it.text })
        assertEquals(listOf("beta words"), s.read("p", "beta").items.map { it.text })
        assertEquals(0, s.read("p", "gamma").items.size)
    }

    @Test
    fun `an unreadable record is counted, not repaired`() {
        val s = store()
        s.append("p", "c1", "good one", 1L)
        s.fileFor("p", "c1").appendText("not-a-timestamp\tbroken\n")
        val read = s.read("p", "c1")
        assertEquals(1, read.items.size)
        assertEquals(1, read.skipped)
    }

    @Test
    fun `clearing removes only this conversation`() {
        val s = store()
        s.append("p", "alpha", "a", 1L)
        s.append("p", "beta", "b", 2L)
        assertNull(s.clear("p", "alpha"))
        assertTrue(s.read("p", "alpha").items.isEmpty())
        assertEquals(1, s.read("p", "beta").items.size)
    }

    @Test
    fun `escape and unescape are inverse`() {
        val raw = "a\tb\nc\\d\rE"
        assertEquals(raw, VoiceTranscriptStore.unescape(VoiceTranscriptStore.escape(raw)))
    }

    @Test
    fun `oversized dictation is clipped rather than rejected`() {
        val s = store()
        val huge = "x".repeat(VoiceTranscriptStore.MAX_TEXT + 500)
        assertNull(s.append("p", "c1", huge, 1L))
        assertEquals(VoiceTranscriptStore.MAX_TEXT, s.read("p", "c1").items[0].text.length)
    }
}
