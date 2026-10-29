package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Section 6 decision rules, tested without a device, a network, or a shell. */
class AgentToolWireTest {

    private val cmd = """Here you go <tool>{"name":"run_command","arguments":{"command":"ls"}}</tool> done"""

    @Test
    fun aPlainAnswerContainsNoCalls() {
        val d = AgentToolWire.decide(ChatMode.AGENT, "Just an explanation.", 1)
        assertTrue(d is ToolDecision.Answer)
        assertEquals("Just an explanation.", (d as ToolDecision.Answer).text)
    }

    @Test
    fun aCallIsParsedWithItsArguments() {
        val calls = AgentToolWire.parse(cmd)
        assertEquals(1, calls.size)
        assertEquals("run_command", calls[0].name)
        assertTrue(calls[0].argumentsJson.contains("\"command\""))
    }

    @Test
    fun theToolBlockIsStrippedFromWhatTheUserReads() {
        assertEquals("Here you go  done", AgentToolWire.visibleText(cmd))
    }

    @Test
    fun agentModeRunsACommand() {
        val d = AgentToolWire.decide(ChatMode.AGENT, cmd, 1)
        assertTrue(d is ToolDecision.Run)
        assertEquals("run_command", (d as ToolDecision.Run).calls[0].name)
    }

    @Test
    fun askModeRefusesAndSaysWhy() {
        val d = AgentToolWire.decide(ChatMode.ASK, cmd, 1)
        assertTrue(d is ToolDecision.Refused)
        assertTrue((d as ToolDecision.Refused).reason.contains("Agent"))
    }

    @Test
    fun askModeMayReadButMayNotChangeAnything() {
        val read = "Looking: <tool>{\"name\":\"read_file\",\"arguments\":{\"path\":\"src/App.kt\"}}</tool>"
        val d = AgentToolWire.decide(ChatMode.ASK, read, 1)
        assertTrue("Ask must be able to inspect the project it is asked about", d is ToolDecision.Run)
        assertEquals("read_file", (d as ToolDecision.Run).calls[0].name)
        assertTrue(AgentToolWire.allowsTool(ChatMode.ASK, "list_files"))
        assertTrue(AgentToolWire.allowsTool(ChatMode.ASK, "search_files"))
        assertFalse("Ask must never write", AgentToolWire.allowsTool(ChatMode.ASK, "write_file"))
        assertFalse("Ask must never execute", AgentToolWire.allowsTool(ChatMode.ASK, "run_command"))
        assertTrue(AgentToolWire.modeRunsTools(ChatMode.ASK))
    }

    @Test
    fun askModeRefusalNamesTheReadOnlyRule() {
        val d = AgentToolWire.decide(
            ChatMode.ASK,
            "<tool>{\"name\":\"write_file\",\"arguments\":{\"path\":\"a.txt\",\"content\":\"x\"}}</tool>",
            1,
        )
        assertTrue(d is ToolDecision.Refused)
        assertTrue((d as ToolDecision.Refused).reason.contains("only reads the project"))
    }

    @Test
    fun editModeStagesWritesInsteadOfTouchingDisk() {
        // Edit mode's prompt promises review-before-apply. The decision layer must agree.
        assertTrue(AgentToolWire.isStagedWrite(ChatMode.EDIT, "write_file"))
        assertFalse("reads are not proposals", AgentToolWire.isStagedWrite(ChatMode.EDIT, "read_file"))
        assertFalse("agent writes execute (behind the gate)", AgentToolWire.isStagedWrite(ChatMode.AGENT, "write_file"))
        assertFalse(AgentToolWire.isStagedWrite(ChatMode.ASK, "write_file"))
    }

    @Test
    fun aStagedNoteNeverClaimsTheFileWasWritten() {
        val note = AgentToolWire.stagedNote("src/App.kt", 42)
        assertTrue(note.contains("src/App.kt"))
        assertTrue(note.contains("42"))
        assertTrue(note.contains("Nothing has been written"))
        assertFalse(note.contains("Done"))
    }

    @Test
    fun editModeWritesFilesButNeverExecutesAnything() {
        val write = """<tool>{"name":"write_file","arguments":{"path":"a.txt","content":"x"}}</tool>"""
        assertTrue(AgentToolWire.decide(ChatMode.EDIT, write, 1) is ToolDecision.Run)
        assertTrue("Edit must not run commands", AgentToolWire.decide(ChatMode.EDIT, cmd, 1) is ToolDecision.Refused)
    }

    @Test
    fun secretsAreNeverReadOrWrittenByTheChatAgent() {
        for (t in listOf("save_secret", "get_secret")) {
            val call = """<tool>{"name":"$t","arguments":{}}</tool>"""
            assertTrue(t, AgentToolWire.decide(ChatMode.AGENT, call, 1) is ToolDecision.Refused)
        }
    }

    @Test
    fun theRoundCapStopsALoop() {
        assertTrue(AgentToolWire.decide(ChatMode.AGENT, cmd, AgentToolWire.MAX_ROUNDS - 1) is ToolDecision.Run)
        val atCap = AgentToolWire.decide(ChatMode.AGENT, cmd, AgentToolWire.MAX_ROUNDS)
        assertTrue(atCap is ToolDecision.Refused)
        assertTrue((atCap as ToolDecision.Refused).reason.contains("loop"))
    }

    @Test
    fun severalCallsInOneReplyAreAllFoundInOrder() {
        val two = """<tool>{"name":"list_files","arguments":{"path":"."}}</tool>
            |<tool>{"name":"read_file","arguments":{"path":"a.txt"}}</tool>""".trimMargin()
        assertEquals(listOf("list_files", "read_file"), AgentToolWire.parse(two).map { it.name })
    }

    @Test
    fun aMalformedBlockIsIgnoredRatherThanCrashing() {
        assertTrue("no name means no call", AgentToolWire.parse("""<tool>{"arguments":{}}</tool>""").isEmpty())
    }

    @Test
    fun theResultMessageTellsTheModelWhatHappened() {
        val msg = AgentToolWire.resultMessage(ParsedToolCall("run_command", "{}"), "file.txt")
        assertTrue(msg.contains("run_command"))
        assertTrue(msg.contains("file.txt"))
        assertTrue("must invite continuation", msg.contains("Continue"))
    }

    @Test
    fun theProtocolOnlyAdvertisesToolsTheModeMayActuallyRun() {
        val agent = AgentToolWire.protocol(ChatMode.AGENT)
        assertTrue(agent.contains("run_command"))
        assertTrue("the format must be shown", agent.contains("<tool>"))
        val edit = AgentToolWire.protocol(ChatMode.EDIT)
        assertFalse("offering a command tool to Edit then refusing it is a trap", edit.contains("run_command"))
        assertTrue(edit.contains("write_file"))
        assertEquals("Ask is offered no tools at all", "", AgentToolWire.protocol(ChatMode.ASK))
    }

    @Test
    fun theSummaryNamesTheCommandOrPath() {
        assertTrue(
            AgentToolWire.summary(ParsedToolCall("run_command", """{"command":"npm test"}"""))
                .contains("npm test"),
        )
        assertTrue(
            AgentToolWire.summary(ParsedToolCall("write_file", """{"path":"src/a.kt","content":"x"}"""))
                .contains("src/a.kt"),
        )
    }

    @Test
    fun longOutputIsClippedToOneLine() {
        val one = AgentToolWire.firstLine("\n\nfirst real line\nsecond line\n")
        assertEquals("first real line", one)
        assertTrue(AgentToolWire.firstLine("x".repeat(400)).length <= 120)
    }
}
