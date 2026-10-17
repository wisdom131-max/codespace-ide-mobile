package com.codespace.ide.chat

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * BATCH4-P4 JVM tests (S01 fix, SK01 model) for ChatInputHistory:
 *  - push is typed: a failed write reports false and leaves the disk untouched;
 *  - a consecutive duplicate is a no-op success (no write happens at all);
 *  - a corrupt payload is quarantined (copy parked aside, content-deduped),
 *    the original is NEVER deleted, and the list reads honest-empty.
 *
 * Context is unused when the test backend seam is set, so the Android stub
 * context (null) never gets dereferenced.
 */
class ChatInputHistoryS01Test {

    private class FakeBackend : ChatPrefsBackend {
        var failWrites = false
        val map = HashMap<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String): Boolean {
            if (failWrites) return false
            map[key] = value
            return true
        }
        override fun allWithPrefix(prefix: String): Map<String, String> =
            map.filterKeys { it.startsWith(prefix) }.toMap()
    }

    private lateinit var backend: FakeBackend
    private val ctx: Context? = null // never dereferenced behind the test seam

    @Before
    fun setUp() {
        backend = FakeBackend()
        ChatInputHistory.testBackend = backend
    }

    @After
    fun tearDown() {
        ChatInputHistory.testBackend = null
    }

    @Test
    fun push_realSave_persistsAndReadsBack() {
        assertTrue(ChatInputHistory.push(ctx, "/proj", "how do I run the tests"))
        assertTrue(ChatInputHistory.push(ctx, "/proj", "second question"))
        assertEquals(listOf("second question", "how do I run the tests"), ChatInputHistory.list(ctx, "/proj"))
    }

    @Test
    fun push_consecutiveDuplicate_isNoOpSuccess() {
        assertTrue(ChatInputHistory.push(ctx, "/proj", "same question"))
        val before = HashMap(backend.map)
        assertTrue("a consecutive duplicate reports success without writing", ChatInputHistory.push(ctx, "/proj", "same question"))
        assertEquals("no new write happened", before, backend.map)
    }

    @Test
    fun push_failedSave_reportsFalseAndLeavesDiskUntouched() {
        assertTrue(ChatInputHistory.push(ctx, "/proj", "first"))
        val before = HashMap(backend.map)
        backend.failWrites = true
        assertFalse("failed write must report false", ChatInputHistory.push(ctx, "/proj", "second"))
        assertEquals("disk keeps the previous payload", before, backend.map)
        backend.failWrites = false
        assertEquals("only the last successful push is on disk", listOf("first"), ChatInputHistory.list(ctx, "/proj"))
    }

    @Test
    fun corruptPayload_quarantined_originalPreserved_readsEmpty_dedupOnReload() {
        val corrupt = "[ this is not a json array"
        val key = "hist_" + "/proj".hashCode()
        backend.map[key] = corrupt
        assertEquals("corrupt payload reads honest-empty", emptyList<String>(), ChatInputHistory.list(ctx, "/proj"))
        assertEquals("original key is NEVER deleted or overwritten", corrupt, backend.map[key])
        assertEquals(1, backend.allWithPrefix("$key.corrupt-").size)
        // Second read with the SAME corrupt payload: no duplicate quarantine copies.
        assertEquals(emptyList<String>(), ChatInputHistory.list(ctx, "/proj"))
        assertEquals(1, backend.allWithPrefix("$key.corrupt-").size)
    }
}
