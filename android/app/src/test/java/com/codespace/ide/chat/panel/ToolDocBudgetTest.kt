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

    /**
     * THE GATE: the bytes the provider actually receives.
     *
     * 2026-11-11 (Wave 1 Group A): this test used to measure `AgentToolWire.protocol(mode)`, i.e. the
     * WHOLE mode catalogue, on the assumption that it was the prompt. It was not - production sent
     * the whole catalogue AND a filtered block beside it, so the guard passed while the real prompt
     * was larger than the guard's own number. The measurement now goes through
     * [ChatInstructions.build], the single composition path ChatSession uses, with the offer Lean
     * Tool Mode would produce for this mode. Limits are unchanged: Ask 5,000, Agent 12,500. Edit has
     * no separate historical limit and shares the agent ceiling.
     */
    @Test
    fun theRealPromptForEachModeFitsItsBudget() {
        ChatMode.entries.forEach { mode ->
            val offer = ToolOffer.plan(mode, "")
            val prompt = ChatInstructions.build(mode, sendTools = true, offered = offer.names, wsBlock = "", mcpDocs = "")
            println("[comp] " + mode + " offered=" + offer.names.size + "/" + offer.total +
                " prompt=" + prompt.length)
            val cap = if (mode == ChatMode.ASK) 5000 else 12500
            assertTrue(mode.label + " prompt must stay under " + cap + " chars: " + prompt.length,
                prompt.length < cap)
            // A prompt that quietly grew to the whole catalogue is the exact defect this guards.
            assertTrue(mode.label + " must advertise fewer tools than it exposes",
                offer.names.size < offer.total)
        }
    }

    /**
     * Catalogue growth is watched SEPARATELY, and it is not the prompt.
     *
     * The full registry block is never sent (it is documentation and the /tools view), so it must not
     * be guarded by the prompt limit - but a registry that doubles unnoticed is still a problem for
     * the lean offer's quality, so it keeps a generous tripwire of its own.
     */
    @Test
    fun theCatalogueItselfIsWatchedButIsNotThePrompt() {
        val catalogue = ToolRegistry.promptBlock(ChatMode.AGENT).length
        val agentOffer = ChatInstructions.build(ChatMode.AGENT, true, ToolOffer.plan(ChatMode.AGENT, "").names, "", "")
        println("[comp] catalogue=" + catalogue + " agentPrompt=" + agentOffer.length)
        assertTrue("the registry catalogue has grown unexpectedly: " + catalogue, catalogue < 18500)
        assertTrue("the prompt must be smaller than the catalogue it is drawn from",
            agentOffer.length < catalogue)
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
