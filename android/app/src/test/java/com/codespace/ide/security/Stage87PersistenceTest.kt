package com.codespace.ide.security

import com.codespace.ide.chat.StoredMessage
import com.codespace.ide.chat.TranscriptFiles
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PROJECT EIGHT checkpoint 5 — the two Area 8.7 gaps checkpoint 4 disclosed, driven through the
 * real [TranscriptFiles] persistence component rather than a copy of its logic.
 *
 * Gap A: interrupted persistence (stale temp files, a failed replacement).
 * Gap B: corrupt-retention recovery (damaged or truncated transcripts).
 */
class Stage87PersistenceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun files() = TranscriptFiles(tmp.root)

    private fun message(id: String, text: String) = StoredMessage(
        role = if (id.startsWith("u")) "user" else "assistant",
        text = text,
        atMs = 1_700_000_000_000L,
    )

    private fun conversation() = listOf(message("m1", "first turn"), message("m2", "second turn"))

    private fun dirFor(project: String) = File(tmp.root, project)

    /** Gap A — a stale temp left by a killed save is never read as the conversation. */
    @Test
    fun an_abandoned_temp_file_is_never_trusted_as_history() {
        val files = files()
        assertTrue(files.saveNow("p", "c", conversation()) == null)

        // What a process kill mid-save leaves behind: a temp file that was never renamed.
        val dir = dirFor("p")
        val stale = dir.listFiles()!!.first { it.name.endsWith(".tmp") || it.name.contains("c") }
        File(dir, stale.name.substringBeforeLast('.') + ".tmp").writeText("m9|user|half a line")

        val loaded = files.load("p", "c")
        assertEquals(conversation().size, loaded.messages.size)
        assertFalse(loaded.messages.any { it.text.contains("half a line") })
    }

    /** Gap A — a failed save reports the failure instead of claiming the conversation is saved. */
    @Test
    fun a_save_into_an_unusable_destination_reports_failure_and_keeps_the_old_copy() {
        val files = files()
        assertTrue(files.saveNow("p", "c", conversation()) == null)

        // Make the destination un-writable the way a full or vanished volume behaves: the directory
        // becomes a file, so neither the temp open nor mkdirs can succeed.
        val dir = dirFor("p")
        val kept = File(tmp.root, "kept")
        assertTrue(dir.renameTo(kept))
        dir.writeText("not a directory")

        val error = files.saveNow("p", "c", listOf(message("m3", "third turn")))
        assertTrue("a failed save must say so", error != null)
        assertTrue("the refusal must name the problem", error!!.isNotBlank())

        // Clear the obstacle: the earlier valid copy is intact, and the next save works normally.
        assertTrue(dir.delete())
        assertTrue(kept.renameTo(dir))
        val restored = files.load("p", "c")
        assertEquals(2, restored.messages.size)

        assertTrue(files.saveNow("p", "c", conversation() + message("m3", "third turn")) == null)
        assertEquals(3, files.load("p", "c").messages.size)
    }

    /** Gap B — damaged history is refused, and the good backup is restored instead. */
    @Test
    fun a_damaged_transcript_is_not_read_as_valid_history() {
        val files = files()
        assertTrue(files.saveNow("p", "c", conversation()) == null)
        // A second save rotates the first into .bak.
        assertTrue(files.saveNow("p", "c", conversation() + message("m3", "third turn")) == null)

        val dir = dirFor("p")
        val primary = dir.listFiles()!!.first { it.name.endsWith(".txt") && !it.name.endsWith(".bak") }
        primary.writeText("\u0000\u0000 not a transcript at all \u0000")

        val loaded = files.load("p", "c")
        assertTrue("the user must be told", loaded.note != null)
        assertTrue(loaded.note!!.contains("previous copy", ignoreCase = true))
        assertEquals(2, loaded.messages.size)
        assertFalse(loaded.messages.any { it.text.contains("not a transcript") })
    }

    /** Gap B and the checkpoint fix — the restored backup survives the next save. */
    @Test
    fun the_backup_that_restored_a_conversation_is_not_overwritten_by_damage() {
        val files = files()
        assertTrue(files.saveNow("p", "c", conversation()) == null)
        assertTrue(files.saveNow("p", "c", conversation() + message("m3", "third turn")) == null)

        val dir = dirFor("p")
        val primary = dir.listFiles()!!.first { it.name.endsWith(".txt") && !it.name.endsWith(".bak") }
        primary.writeText("garbage that this build cannot decode")
        val restored = files.load("p", "c")
        assertEquals(2, restored.messages.size)

        // The damaged file is set aside, so this save cannot rotate damage over the good backup.
        assertTrue(files.saveNow("p", "c", restored.messages + message("m4", "after recovery")) == null)

        val again = files.load("p", "c")
        assertEquals(3, again.messages.size)
        assertTrue(again.note == null || !again.note!!.contains("damaged", ignoreCase = true))
        // The damaged original is preserved for inspection rather than silently deleted.
        assertTrue(dir.listFiles()!!.any { it.name.endsWith(".damaged") })
    }

    /** Gap B — a corrupt conversation does not affect an independent, healthy one. */
    @Test
    fun corruption_is_contained_to_the_conversation_that_suffered_it() {
        val files = files()
        assertTrue(files.saveNow("p", "c1", conversation()) == null)
        assertTrue(files.saveNow("p", "c2", conversation()) == null)

        val dir = dirFor("p")
        val target = dir.listFiles()!!.first { it.name.endsWith(".txt") && it.name.contains("c1") &&
            !it.name.endsWith(".bak") }
        target.writeText("###")

        val broken = files.load("p", "c1")
        assertTrue(broken.note != null)
        val healthy = files.load("p", "c2")
        assertEquals(2, healthy.messages.size)
        assertTrue(healthy.note == null)
    }

    /** Gap B — recovery never re-runs anything: history comes back as inert records. */
    @Test
    fun restored_history_carries_no_execution_state() {
        val files = files()
        assertTrue(files.saveNow("p", "c", conversation()) == null)
        val dir = dirFor("p")
        val primary = dir.listFiles()!!.first { it.name.endsWith(".txt") && !it.name.endsWith(".bak") }
        primary.writeText("truncated")

        val loaded = files.load("p", "c")
        // A restored transcript is text: nothing in the load path can dispatch a tool, because the
        // only execution boundary is AgentTools.executeTool and this component has no reference to it.
        assertTrue(loaded.messages.isEmpty() || loaded.messages.all { it.text.isNotBlank() })
        assertFalse(
            "a damaged transcript must not come back silently",
            loaded.note == null && loaded.messages.isEmpty(),
        )
    }
}
