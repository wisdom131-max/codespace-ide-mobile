package com.codespace.ide.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * M5 — the memory scope must actually CONTROL access.
 *
 * The dangerous outcome is a "current session only" setting that still lets a session read what
 * another session saved, so the negative cases are the point of this file.
 */
class MemoryScopeTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File.createTempFile("vncode-memscope", "test").let { it.delete(); it.mkdirs(); it }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private val attribution = mapOf(
        "decided_theme" to "session_a",
        "my_other_note" to "session_b",
        "old_global_fact" to "",
    )

    @Test
    fun `current session sees its own memories and the legacy globals`() {
        val visible = MemoryScopeFilter.visibleKeys(
            attribution.keys, attribution, MemoryScope.CURRENT_SESSION, "session_a",
        )
        assertEquals(listOf("decided_theme", "old_global_fact"), visible)
    }

    @Test
    fun `current session cannot reach another session's memory`() {
        val visible = MemoryScopeFilter.visibleKeys(
            attribution.keys, attribution, MemoryScope.CURRENT_SESSION, "session_a",
        )
        assertFalse("the other session's note must be hidden", visible.contains("my_other_note"))
        assertFalse(MemoryScopeFilter.isVisible("my_other_note", attribution, MemoryScope.CURRENT_SESSION, "session_a"))
    }

    @Test
    fun `all sessions sees everything`() {
        val visible = MemoryScopeFilter.visibleKeys(
            attribution.keys, attribution, MemoryScope.ALL_SESSIONS, "session_a",
        )
        assertEquals(3, visible.size)
        assertTrue(MemoryScopeFilter.isVisible("my_other_note", attribution, MemoryScope.ALL_SESSIONS, "session_a"))
    }

    @Test
    fun `with no session id only globals are reachable`() {
        val visible = MemoryScopeFilter.visibleKeys(
            attribution.keys, attribution, MemoryScope.CURRENT_SESSION, "",
        )
        assertEquals(listOf("old_global_fact"), visible)
    }

    @Test
    fun `an unrecorded key is global, so an upgrade never hides existing memories`() {
        val visible = MemoryScopeFilter.visibleKeys(
            listOf("never_attributed"), emptyMap(), MemoryScope.CURRENT_SESSION, "session_z",
        )
        assertEquals(listOf("never_attributed"), visible)
    }

    @Test
    fun `attribution follows the scope`() {
        assertEquals("session_a", MemoryScopeFilter.attributionFor(MemoryScope.CURRENT_SESSION, "session_a"))
        assertEquals("", MemoryScopeFilter.attributionFor(MemoryScope.ALL_SESSIONS, "session_a"))
    }

    @Test
    fun `parse is tolerant and defaults to all sessions`() {
        assertEquals(MemoryScope.CURRENT_SESSION, MemoryScope.parse("current_session"))
        assertEquals(MemoryScope.ALL_SESSIONS, MemoryScope.parse("all_sessions"))
        assertEquals(MemoryScope.DEFAULT, MemoryScope.parse(null))
        assertEquals(MemoryScope.DEFAULT, MemoryScope.parse("nonsense"))
        assertEquals(MemoryScope.ALL_SESSIONS, MemoryScope.parse("current_session").next())
        assertEquals(MemoryScope.CURRENT_SESSION, MemoryScope.ALL_SESSIONS.next())
    }

    @Test
    fun `attribution file round-trips and skips unreadable lines`() {
        val f = File(dir, "attribution.tsv")
        val keyWithTab = "weird\tkey"
        f.writeText(
            MemoryAttribution.escape("decided_theme") + "\t" + MemoryAttribution.escape("session_a") + "\n" +
                MemoryAttribution.escape(keyWithTab) + "\t" + MemoryAttribution.escape("session_b") + "\n" +
                "global_one\t\n" +
                "\n" +
                "\torphan\n",
        )
        val read = MemoryAttribution.readFile(f)
        assertEquals("session_a", read["decided_theme"])
        assertEquals("session_b", read[keyWithTab])
        assertEquals("", read["global_one"])
        assertNull("a blank key is not a memory", read[""])
        assertEquals(3, read.size)
    }

    @Test
    fun `a key containing a tab cannot be mistaken for two fields`() {
        val line = MemoryAttribution.escape("a\tb") + "\t" + "session_a"
        val parsed = MemoryAttribution.parseLine(line)!!
        assertEquals("a\tb", parsed.first)
        assertEquals("session_a", parsed.second)
    }

    @Test
    fun `escape and unescape are inverse`() {
        val raw = "k\te\ny\\z\rw"
        assertEquals(raw, MemoryAttribution.unescape(MemoryAttribution.escape(raw)))
    }
}
