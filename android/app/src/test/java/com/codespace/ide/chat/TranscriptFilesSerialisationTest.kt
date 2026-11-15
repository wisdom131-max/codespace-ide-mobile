package com.codespace.ide.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * PHASE 3: a delete must never run in the middle of a write.
 *
 * The failure this pins down is the one that recurs on device: the temp file open fails with
 * ENOENT because the conversation folder was removed while a save was inside it. Serialising the
 * two mutations is the fix, and the test forces the interleaving that used to be possible.
 */
class TranscriptFilesSerialisationTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun files() = TranscriptFiles(tmp.root)

    private fun messages(text: String) = listOf(
        StoredMessage(role = "user", text = text),
        StoredMessage(role = "assistant", text = "reply to $text"),
    )

    @Test
    fun aDeleteCannotLandInsideAWrite() {
        val f = files()
        val failure = AtomicReference<String?>(null)
        val started = CountDownLatch(1)
        val done = CountDownLatch(1)

        val writer = Thread {
            started.countDown()
            repeat(40) { i ->
                failure.compareAndSet(null, f.saveNow("p", "c", messages("turn $i")))
            }
            done.countDown()
        }
        val deleter = Thread {
            started.await(2, TimeUnit.SECONDS)
            repeat(40) {
                f.saveNow("p", "c", emptyList())
            }
        }

        writer.start(); deleter.start()
        assertTrue("writer finished", done.await(20, TimeUnit.SECONDS))
        writer.join(5_000); deleter.join(5_000)

        assertEquals("no write may fail because a delete interleaved", null, failure.get())
    }

    @Test
    fun theFinalTranscriptIsEitherWholeOrAbsent() {
        val f = files()
        repeat(25) { i ->
            f.saveNow("p", "c", messages("turn $i"))
            f.saveNow("p", "c", emptyList())
        }
        f.saveNow("p", "c", messages("last"))
        val loaded = ChatTranscript.decode(f.fileFor("p", "c").readText()).messages
        assertEquals(2, loaded.size)
        assertEquals("last", loaded[0].text)
    }
}
