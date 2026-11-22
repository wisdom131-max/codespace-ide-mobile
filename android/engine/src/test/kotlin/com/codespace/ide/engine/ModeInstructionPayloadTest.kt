package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U4-d: a conversation mode must reach the model through each provider's OWN system
 * slot. Before this, ChatRole.SYSTEM mapped to "assistant" (OpenAI/Anthropic) and
 * "model" (Gemini), so an instruction would have arrived as a fake reply the model
 * had supposedly written. These assertions are on the serialized payload, which is
 * the only thing that actually goes on the wire.
 */
class ModeInstructionPayloadTest {

    private val window = listOf(
        ChatMessage("1", ChatRole.USER, "hi"),
        ChatMessage("2", ChatRole.ASSISTANT, "hello"),
    )

    private val instruction = "You are editing this project."

    @Test
    fun openAiCarriesTheInstructionAsALeadingSystemTurn() {
        val p = OpenAIAdapter().payload(window, "hi", EngineConfig("m1").copy(systemInstruction = instruction))
        assertTrue("system turn missing: $p", p.contains("{\"role\":\"system\",\"content\":\"$instruction\"}"))
        // and it must come BEFORE the first user message
        assertTrue(p.indexOf("role\":\"system") < p.indexOf("hi"))
    }

    @Test
    fun anthropicCarriesTheInstructionTopLevelNeverInTheMessages() {
        val p = AnthropicAdapter().payload(window, "hi", EngineConfig("m1").copy(systemInstruction = instruction))
        // PROJECT EIGHT 8.10: the instruction is still top-level, but now as a content BLOCK array
        // so it can carry a cache breakpoint. Anthropic accepts `system` as either a string or an
        // array of blocks, so this is the same field, sent in the form that can be cached.
        assertTrue("top-level system missing: $p", p.contains("\"system\":[{\"type\":\"text\",\"text\":\"$instruction\""))
        assertTrue("the static instruction must carry the cache breakpoint: $p", p.contains("\"cache_control\":{\"type\":\"ephemeral\"}"))
        // Anthropic rejects a system ROLE inside messages, so it must not appear there.
        assertFalse(p.contains("\"role\":\"system\""))
    }

    @Test
    fun theClockRidesOutsideTheCachedBlockOnAnthropic() {
        // The whole point of the split: a clock inside the cached block would miss every minute.
        val p = AnthropicAdapter().payload(
            window, "hi",
            EngineConfig("m1").copy(systemInstruction = instruction, volatileSystemTail = "Current local time: now"),
        )
        val blocks = Regex("\"system\":\\[(.*?)\\]").find(p)?.groupValues?.get(1).orEmpty()
        assertTrue("both blocks must be present: $p", blocks.contains("Current local time: now"))
        // Exactly one breakpoint, and it must be the STATIC block, never the clock.
        assertEquals(1, Regex("cache_control").findAll(p).count())
        val staticBlock = blocks.substringBefore("Current local time")
        assertTrue("the breakpoint belongs on the static block", staticBlock.contains("cache_control"))
    }

    @Test
    fun geminiCarriesTheInstructionAsSystemInstruction() {
        val p = GeminiAdapter().payload(window, "hi", EngineConfig("m1").copy(systemInstruction = instruction))
        assertTrue(
            "systemInstruction missing: $p",
            p.contains("\"systemInstruction\":{\"parts\":[{\"text\":\"$instruction\"}]}"),
        )
        assertTrue("contents must still follow", p.contains("\"contents\":["))
    }

    @Test
    fun aBlankInstructionChangesNothing() {
        val c = EngineConfig("m1")
        listOf(OpenAIAdapter(), AnthropicAdapter(), GeminiAdapter()).forEach { a ->
            val p = a.payload(window, "hi", c)
            assertFalse("${a.providerId} leaked an empty system slot: $p", p.contains("systemInstruction") || p.contains("\"system\":"))
        }
    }
}
