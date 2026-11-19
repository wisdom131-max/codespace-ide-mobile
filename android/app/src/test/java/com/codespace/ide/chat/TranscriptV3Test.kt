package com.codespace.ide.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * STAGE 3 — transcript v3: stable identity, honest timestamps, tolerant reads.
 *
 * The point of these tests is the two failure modes a transcript cannot recover from: an id that
 * moves when the text is edited (so nothing can point at a message), and a date that is invented
 * for a file that never held one.
 */
class TranscriptV3Test {

    private fun msgs(vararg m: StoredMessage) = m.toList()

    @Test
    fun `ids survive a write and a read`() {
        val list = msgs(
            StoredMessage("user", "hello", id = "m1", sentAtMs = 100L),
            StoredMessage("assistant", "hi", id = "m2", sentAtMs = 200L),
        )
        val back = ChatTranscript.decode(ChatTranscript.encode(list)).messages
        assertEquals(listOf("m1", "m2"), back.map { it.id })
        assertEquals(listOf(100L, 200L), back.map { it.sentAtMs })
        assertFalse(back.any { it.syntheticId })
    }

    @Test
    fun `ids are unique within a conversation`() {
        val list = (0 until 20).map { StoredMessage("user", "m$it") }
        val carried = ChatTranscript.carryIdentity(list, emptyList(), "conv", 5_000L)
        assertEquals(20, carried.map { it.id }.toSet().size)
        assertTrue(carried.all { it.id.isNotBlank() })
    }

    @Test
    fun `editing text does not move a message`() {
        val first = ChatTranscript.carryIdentity(
            listOf(StoredMessage("user", "original"), StoredMessage("assistant", "reply")),
            emptyList(), "conv", 1_000L,
        )
        val second = ChatTranscript.carryIdentity(
            listOf(StoredMessage("user", "edited text"), StoredMessage("assistant", "reply v2")),
            first, "conv", 9_000L,
        )
        assertEquals(first.map { it.id }, second.map { it.id })
        // and the first-observed time is not rewritten by the later save
        assertEquals(first.map { it.sentAtMs }, second.map { it.sentAtMs })
    }

    @Test
    fun `appending does not renumber the messages before it`() {
        val first = ChatTranscript.carryIdentity(listOf(StoredMessage("user", "one")), emptyList(), "c", 10L)
        val second = ChatTranscript.carryIdentity(
            listOf(StoredMessage("user", "one"), StoredMessage("assistant", "two")), first, "c", 20L,
        )
        assertEquals(first[0].id, second[0].id)
        assertNotEquals(second[0].id, second[1].id)
    }

    @Test
    fun `legacy v2 lines get deterministic synthetic ids and no send time`() {
        val raw = "v2\nuser\t1\t111\t-\thello\nassistant\t0\t222\tOUTPUT_LIMIT\thi\n"
        val a = ChatTranscript.decode(raw)
        val b = ChatTranscript.decode(raw)
        assertEquals(ChatTranscript.FORMAT_VERSION, a.version)
        assertTrue(a.messages.all { it.syntheticId })
        assertTrue(a.messages.all { it.sentAtMs == 0L })
        assertEquals(a.messages.map { it.id }, b.messages.map { it.id })
        assertEquals("OUTPUT_LIMIT", a.messages[1].finish)
    }

    @Test
    fun `v1 lines still read, with no verdict invented`() {
        val d = ChatTranscript.decode("v1\nuser\t1\t10\thello\n")
        assertEquals(ChatTranscript.LEGACY_FORMAT_VERSION, d.version)
        assertEquals(1, d.messages.size)
        assertNull(d.messages[0].finish)
        assertTrue(d.messages[0].syntheticId)
    }

    @Test
    fun `v3 header metadata round trips`() {
        val text = ChatTranscript.encode(
            msgs(StoredMessage("user", "hi", id = "m1", sentAtMs = 50L)),
            meta = TranscriptMeta(conversationId = "conv-7", startedAtMs = 40L),
        )
        val d = ChatTranscript.decode(text)
        assertEquals(ChatTranscript.CURRENT_FORMAT_VERSION, d.version)
        assertEquals("conv-7", d.meta?.conversationId)
        assertEquals(40L, d.meta?.startedAtMs)
    }

    @Test
    fun `a conversation with no known start stays unknown`() {
        val d = ChatTranscript.decode(ChatTranscript.encode(msgs(StoredMessage("user", "hi", id = "m1"))))
        assertEquals(0L, d.meta?.startedAtMs)
    }

    @Test
    fun `tool steps round trip and keep their ids`() {
        val text = ChatTranscript.encode(
            msgs(StoredMessage("user", "go", id = "m1")),
            steps = listOf(StoredStep("call-1", "turn-1", 77L, true, "read_file", "build.gradle.kts")),
        )
        val d = ChatTranscript.decode(text)
        assertEquals(1, d.steps.size)
        assertEquals("call-1", d.steps[0].callId)
        assertEquals(77L, d.steps[0].atMs)
        assertEquals("build.gradle.kts", d.steps[0].detail)
    }

    @Test
    fun `a malformed record costs only itself`() {
        val raw = "v3\nm\tm1\tuser\t1\t0\t5\t-\tkept\nm\tbroken\nm\tm2\tassistant\t1\t0\t6\t-\talso kept\n"
        val d = ChatTranscript.decode(raw)
        assertEquals(2, d.messages.size)
        assertEquals(1, d.skippedLines)
        assertEquals("kept", d.messages[0].text)
    }

    @Test
    fun `a torn final record keeps the earlier messages`() {
        val full = ChatTranscript.encode(
            msgs(
                StoredMessage("user", "one", id = "m1", sentAtMs = 1L),
                StoredMessage("assistant", "two", id = "m2", sentAtMs = 2L),
            ),
            meta = TranscriptMeta("c", 1L),
        )
        val torn = full.substring(0, full.length - 6)
        val d = ChatTranscript.decode(torn)
        assertTrue(d.messages.isNotEmpty())
        assertEquals("m1", d.messages[0].id)
    }

    @Test
    fun `a missing trailing newline is not a torn write`() {
        val full = ChatTranscript.encode(msgs(StoredMessage("user", "one", id = "m1"))).trimEnd('\n')
        assertEquals(1, ChatTranscript.decode(full).messages.size)
    }

    @Test
    fun `a duplicated record is dropped and counted`() {
        val raw = "v3\nm\tm1\tuser\t1\t0\t5\t-\ta\nm\tm1\tuser\t1\t0\t5\t-\ta\n"
        val d = ChatTranscript.decode(raw)
        assertEquals(1, d.messages.size)
        assertEquals(1, d.duplicateRecords)
    }

    @Test
    fun `an unknown record type is skipped, not guessed`() {
        val raw = "v3\nx\tsomething\tnew\nm\tm1\tuser\t1\t0\t5\t-\ta\n"
        val d = ChatTranscript.decode(raw)
        assertEquals(1, d.messages.size)
        assertEquals(1, d.skippedLines)
    }

    @Test
    fun `an alien header is unreadable rather than empty`() {
        assertTrue(ChatTranscript.decode("v9\nstuff\n").unreadable)
        assertTrue(ChatTranscript.decode("total nonsense").unreadable)
    }

    @Test
    fun `the store assigns identity on first save and keeps it on the second`() {
        val tmp = TemporaryFolder()
        tmp.create()
        val files = TranscriptFiles(tmp.root)
        val first = listOf(
            StoredMessage("user", "hello"),
            StoredMessage("assistant", "hi"),
        )
        assertNull(files.saveNow("p1", "c1", first))
        val raw1 = files.fileFor("p1", "c1").readText()
        val d1 = ChatTranscript.decode(raw1)
        assertEquals(ChatTranscript.CURRENT_FORMAT_VERSION, d1.version)
        assertTrue(d1.messages.all { it.id.isNotBlank() })
        assertTrue(d1.messages.all { it.sentAtMs > 0L })
        assertTrue((d1.meta?.startedAtMs ?: 0L) > 0L)

        // same content, later save: ids and first-observed times must not move
        Thread.sleep(5)
        assertNull(files.saveNow("p1", "c1", first))
        val d2 = ChatTranscript.decode(files.fileFor("p1", "c1").readText())
        assertEquals(d1.messages.map { it.id }, d2.messages.map { it.id })
        assertEquals(d1.messages.map { it.sentAtMs }, d2.messages.map { it.sentAtMs })
        assertEquals(d1.meta?.startedAtMs, d2.meta?.startedAtMs)
        tmp.delete()
    }

    @Test
    fun `a legacy file is rewritten as v3 without losing its messages`() {
        val tmp = TemporaryFolder()
        tmp.create()
        val files = TranscriptFiles(tmp.root)
        val target = files.fileFor("p1", "c2")
        target.parentFile.mkdirs()
        target.writeText("v2\nuser\t1\t111\t-\told one\nassistant\t1\t222\t-\told two\n")
        val loaded = files.load("p1", "c2")
        assertNull(files.saveNow("p1", "c2", loaded.messages))
        val after = ChatTranscript.decode(target.readText())
        assertEquals(ChatTranscript.CURRENT_FORMAT_VERSION, after.version)
        assertEquals(listOf("old one", "old two"), after.messages.map { it.text })
        assertTrue(after.messages.all { it.syntheticId })
        assertEquals(listOf(0L, 0L), after.messages.map { it.sentAtMs })
        tmp.delete()
    }
}
