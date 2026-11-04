package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 1(a) — 2026-11-04 device finding: raw tool-call markup rendered as assistant prose.
 *
 * The model was qwen/qwen3.8-27b behind a custom OpenAI-compatible endpoint. It never received a
 * native `tools` field (VN Code speaks one text protocol), so it answered in the dialect its own
 * chat template uses: Hermes/Qwen XML. CALL_REGEX only understood `<tool_call>{"name":…}</tool_call>`,
 * so the markup matched nothing, visibleText() returned it unchanged, and the transcript showed
 * the protocol as if the assistant had said it.
 *
 * These fixtures are the device text, its fragmented forms, and the malformed variants that must
 * never execute anything.
 */
class AgentToolWireTextFormatsTest {

    private val screenshotBody = """
        <tool_call>
        <function=read_file>
        <parameter=path>
        wire-test/notes.txt
        </parameter>
        </function>
        </tool_call>
        <tool_call>
        <function=read_file>
        <parameter=path>
        wire-test/keep.txt
        </parameter>
        </function>
        </tool_call>
        <tool_call>
        <function=read_file>
        <parameter=path>
        wire-test/app.conf
        </parameter>
        </function>
        </tool_call>
    """.trimIndent()

    @Test
    fun theDeviceMarkupYieldsThreeCallsInOrder() {
        val calls = AgentToolWire.parse(screenshotBody)
        assertEquals(3, calls.size)
        assertEquals(listOf("read_file", "read_file", "read_file"), calls.map { it.name })
        assertEquals("{\"path\":\"wire-test/notes.txt\"}", calls[0].argumentsJson)
        assertEquals("{\"path\":\"wire-test/keep.txt\"}", calls[1].argumentsJson)
        assertEquals("{\"path\":\"wire-test/app.conf\"}", calls[2].argumentsJson)
    }

    @Test
    fun theDeviceMarkupNeverReachesTheVisibleAnswer() {
        val visible = AgentToolWire.visibleText(screenshotBody)
        assertEquals("", visible)
        assertTrue("no tag survives", !visible.contains("<"))
        assertTrue("no path is leaked as prose", !visible.contains("wire-test/app.conf"))
    }

    @Test
    fun markupAroundRealWordsKeepsTheWords() {
        val reply = "Let me look at the notes.\n" + screenshotBody + "\nDone."
        assertEquals("Let me look at the notes.\n\nDone.", AgentToolWire.visibleText(reply))
        assertEquals(3, AgentToolWire.parse(reply).size)
    }

    @Test
    fun aCallSplitAcrossStreamChunksIsParsedOnlyWhenComplete() {
        val full = screenshotBody.lines().first { it.contains("notes") }
        // Progressive accumulation, exactly as the panel's StringBuilder sees it.
        val firstHalf = "<tool_call>\n<function=read_file>\n<param"
        assertTrue("half a call executes nothing", AgentToolWire.parse(firstHalf).isEmpty())
        assertEquals("half a call is still not prose", "", AgentToolWire.visibleText(firstHalf))
        val complete = """
            <tool_call>
            <function=read_file>
            <parameter=path>
            wire-test/notes.txt
            </parameter>
            </function>
            </tool_call>
        """.trimIndent()
        assertEquals(1, AgentToolWire.parse(complete).size)
        assertTrue(full.isNotBlank())
    }

    @Test
    fun aMalformedBlockExecutesNothingAndIsNotShown() {
        val unclosed = "<tool_call>\n<function=read_file>\n<parameter=path>\nnotes.txt"
        assertTrue(AgentToolWire.parse(unclosed).isEmpty())
        assertEquals("", AgentToolWire.visibleText(unclosed))
    }

    @Test
    fun aMissingParameterProducesAnEmptyArgumentObject() {
        val reply = "<tool_call><function=list_files></function></tool_call>"
        val calls = AgentToolWire.parse(reply)
        assertEquals(1, calls.size)
        assertEquals("{}", calls[0].argumentsJson)
    }

    @Test
    fun twoParametersAreBothKept() {
        val reply = """
            <tool_call>
            <function=replace_string_in_file>
            <parameter=path>a.kt</parameter>
            <parameter=old_string>val a = 1</parameter>
            <parameter=new_string>val a = 2</parameter>
            </function>
            </tool_call>
        """.trimIndent()
        val call = AgentToolWire.parse(reply).single()
        assertEquals("replace_string_in_file", call.name)
        assertTrue(call.argumentsJson.contains("\"path\":\"a.kt\""))
        assertTrue(call.argumentsJson.contains("\"old_string\":\"val a = 1\""))
        assertTrue(call.argumentsJson.contains("\"new_string\":\"val a = 2\""))
    }

    @Test
    fun quotesBackslashesAndNewlinesAreEscapedNotDropped() {
        val reply = """
            <tool_call>
            <function=write_file>
            <parameter=path>q.txt</parameter>
            <parameter=content>say "hi" \ then
            next line</parameter>
            </function>
            </tool_call>
        """.trimIndent()
        val args = AgentToolWire.parse(reply).single().argumentsJson
        assertTrue(args.contains("\\\"hi\\\""))
        assertTrue(args.contains("\\\\"))
        assertTrue(args.contains("\\n"))
    }

    @Test
    fun theJsonDialectStillWorksUnchanged() {
        val reply = """<tool>{"name":"read_file","arguments":{"path":"a.kt"}}</tool>"""
        val calls = AgentToolWire.parse(reply)
        assertEquals(1, calls.size)
        assertEquals("{\"path\":\"a.kt\"}", calls[0].argumentsJson)
        assertEquals("", AgentToolWire.visibleText(reply))
    }

    @Test
    fun bothDialectsInOneReplyKeepTheirOrder() {
        val reply = """
            <tool>{"name":"list_files","arguments":{}}</tool>
            <tool_call>
            <function=read_file>
            <parameter=path>b.kt</parameter>
            </function>
            </tool_call>
        """.trimIndent()
        assertEquals(listOf("list_files", "read_file"), AgentToolWire.parse(reply).map { it.name })
    }

    @Test
    fun ordinaryTextThatMentionsAFunctionIsNotDamaged() {
        val reply = "The function=read_file syntax is how the wire works; see read_file above."
        assertEquals(reply, AgentToolWire.visibleText(reply))
        assertTrue(AgentToolWire.parse(reply).isEmpty())
    }

    @Test
    fun decideRunsTheDeviceMarkupInsteadOfShowingIt() {
        val decision = AgentToolWire.decide(ChatMode.AGENT, screenshotBody, round = 1)
        assertTrue(decision is ToolDecision.Run)
        val run = decision as ToolDecision.Run
        assertEquals(3, run.calls.size)
        assertEquals("", run.visibleText)
    }

    @Test
    fun decideInAskModeStillRefusesTheDeviceMarkupHonestly() {
        val decision = AgentToolWire.decide(ChatMode.ASK, screenshotBody, round = 1)
        // read_file is allowed in Ask, so this one runs; a write in the same dialect must not.
        assertTrue(decision is ToolDecision.Run)
        val write = AgentToolWire.decide(
            ChatMode.ASK,
            "<tool_call><function=write_file><parameter=path>a.txt</parameter></function></tool_call>",
            round = 1,
        )
        assertTrue(write is ToolDecision.Refused)
        assertEquals("", (write as ToolDecision.Refused).visibleText)
    }
}
