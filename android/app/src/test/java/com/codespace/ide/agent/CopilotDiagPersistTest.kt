package com.codespace.ide.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CopilotDiagPersistTest {

    @get:Rule val tmp = TemporaryFolder()

    @Before fun reset() { CopilotDiag.clear() }

    private fun file(): File = File(tmp.newFolder("diag"), "events.jsonl")

    @Test fun `events survive a restart`() {
        val f = file()
        CopilotDiag.startSession("s1")
        CopilotDiag.record(CopilotDiag.Kind.TOOL_END, "read_file", true, "12ms")
        assertTrue(CopilotDiag.persistTo(f))
        CopilotDiag.clear()                      // process death
        assertEquals(2, CopilotDiag.loadFrom(f)) // session + tool
        assertTrue(CopilotDiag.recent().any { it.name == "read_file" })
    }

    @Test fun `order is preserved`() {
        val f = file()
        (1..5).forEach { CopilotDiag.record(CopilotDiag.Kind.TOOL_START, "t" + it, true) }
        CopilotDiag.persistTo(f)
        CopilotDiag.clear()
        CopilotDiag.loadFrom(f)
        assertEquals(listOf("t1", "t2", "t3", "t4", "t5"), CopilotDiag.recent().map { it.name })
    }

    @Test fun `timestamps and session ids survive`() {
        val f = file()
        CopilotDiag.startSession("corr-9")
        CopilotDiag.record(CopilotDiag.Kind.RETRIEVAL, "semantic_search", true, "3 results")
        CopilotDiag.persistTo(f)
        CopilotDiag.clear()
        CopilotDiag.loadFrom(f)
        val e = CopilotDiag.recent().last()
        assertEquals("corr-9", e.session)
        assertTrue(e.at > 0)
    }

    @Test fun `a second save does not duplicate what it already wrote`() {
        val f = file()
        CopilotDiag.record(CopilotDiag.Kind.TOOL_START, "a", true)
        CopilotDiag.persistTo(f)
        CopilotDiag.persistTo(f)
        assertEquals(1, f.readLines().count { it.isNotBlank() })
    }

    @Test fun `retention keeps only the newest bounded set`() {
        val f = file()
        repeat(CopilotDiag.MAX_PERSISTED + 30) { CopilotDiag.record(CopilotDiag.Kind.TOOL_START, "n" + it, true) }
        CopilotDiag.persistTo(f)
        CopilotDiag.clear()
        assertEquals(CopilotDiag.MAX_PERSISTED, CopilotDiag.loadFrom(f))
        assertEquals("n" + (CopilotDiag.MAX_PERSISTED + 29), CopilotDiag.recent().last().name)
    }

    @Test fun `expired events are dropped on load`() {
        val f = file()
        val old = System.currentTimeMillis() - 10L * 24 * 60 * 60 * 1000
        f.writeText("{\"at\":" + old + ",\"kind\":\"TOOL_START\",\"name\":\"stale\",\"ok\":true,\"detail\":\"\",\"session\":\"s\"}\n")
        CopilotDiag.record(CopilotDiag.Kind.TOOL_START, "fresh", true)
        CopilotDiag.persistTo(f)
        CopilotDiag.clear()
        CopilotDiag.loadFrom(f)
        assertTrue(CopilotDiag.recent().none { it.name == "stale" })
    }

    @Test fun `a corrupt line is skipped, not fatal`() {
        val f = file()
        CopilotDiag.record(CopilotDiag.Kind.TOOL_START, "good", true)
        CopilotDiag.persistTo(f)
        f.appendText("this is not json\n{\"broken\":\n")
        CopilotDiag.clear()
        assertEquals(1, CopilotDiag.loadFrom(f))
    }

    @Test fun `a torn final write still yields the earlier events`() {
        val f = file()
        CopilotDiag.record(CopilotDiag.Kind.TOOL_START, "one", true)
        CopilotDiag.persistTo(f)
        CopilotDiag.record(CopilotDiag.Kind.TOOL_START, "two", true)
        CopilotDiag.persistTo(f)
        val text = f.readText()
        f.writeText(text.substring(0, text.length - 12)) // cut mid-line
        CopilotDiag.clear()
        assertTrue(CopilotDiag.loadFrom(f) >= 1)
        assertTrue(CopilotDiag.recent().any { it.name == "one" })
    }

    @Test fun `an unwritable path returns false and does not throw`() {
        val blocked = File(tmp.newFolder("ro"), "events.jsonl")
        blocked.mkdirs() // a directory where a file must be
        CopilotDiag.record(CopilotDiag.Kind.TOOL_START, "a", true)
        assertFalse(CopilotDiag.persistTo(blocked))
    }

    @Test fun `a missing file loads as zero events`() {
        assertEquals(0, CopilotDiag.loadFrom(File(tmp.newFolder("none"), "absent.jsonl")))
    }

    @Test fun `credentials are redacted before they reach disk`() {
        val f = file()
        CopilotDiag.record(CopilotDiag.Kind.PROVIDER_ERROR, "openai", false, "api_key=abcdef123456")
        CopilotDiag.persistTo(f)
        assertFalse(f.readText().contains("abcdef123456"))
    }

    @Test fun `an empty ring writes nothing`() {
        val f = file()
        assertTrue(CopilotDiag.persistTo(f))
        assertFalse(f.exists() && f.length() > 0)
    }
}
