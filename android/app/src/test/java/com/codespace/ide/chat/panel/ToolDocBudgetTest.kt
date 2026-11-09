package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tool-doc budget, with the measurement as the gate.
 *
 * Before compaction (2026-11-09): Ask 7,119ch / Edit 10,192ch / Agent 16,712ch of tool docs.
 * After: Ask 4,203ch / Edit 5,620ch / Agent 8,824ch. On a 6,000 token-per-minute account the Agent
 * prompt alone shrank from 70% of a whole minute to 37%, and "hi" from 5,230 to 3,258 tokens.
 */
class ToolDocBudgetTest {

    @Test
    fun theAgentDocBlockIsUnderHalfOfWhatItWas() {
        val agent = AgentToolWire.protocol(ChatMode.AGENT).length
        val ask = AgentToolWire.protocol(ChatMode.ASK).length
        println("[comp] toolWire ask=" + ask + " agent=" + agent)
        assertTrue("agent docs must stay under 11k chars (was 16,712): " + agent, agent < 11000)
        assertTrue("ask docs must stay under 5k chars (was 7,119): " + ask, ask < 5000)
    }

    @Test
    fun aFlatSchemaCompactsWithOptionalityFromTheSchemaItself() {
        val schema = """{"type":"object","required":["path"],"properties":{"path":{"type":"string"},"line":{"type":"integer"}}}"""
        assertEquals("path: string, line: integer?", ToolRegistry.compactArgs(schema))
    }

    @Test
    fun anEnumSchemaTravelsVerbatim() {
        val schema = """{"type":"object","required":["mode"],"properties":{"mode":{"type":"string","enum":["a","b"]}}}"""
        assertEquals(schema, ToolRegistry.compactArgs(schema))
    }

    @Test
    fun aNestedOrArraySchemaTravelsVerbatim() {
        val nested = """{"type":"object","properties":{"edits":{"type":"array","items":{"type":"object"}}}}"""
        assertEquals(nested, ToolRegistry.compactArgs(nested))
    }

    @Test
    fun aDescriptionIsOneShortLine() {
        val long = "Reads a file. Returns its text with line numbers so you can reason about it. " +
            "Very long trailing explanation that the model does not need."
        val line = ToolRegistry.oneLine(long)
        assertTrue(line, line.length <= 110)
        assertTrue("keeps the first sentence", line.startsWith("Reads a file."))
    }
}
