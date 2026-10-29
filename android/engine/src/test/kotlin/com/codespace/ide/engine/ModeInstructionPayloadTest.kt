package com.codespace.ide.engine

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
        assertTrue("top-level system missing: $p", p.contains("\"system\":\"$instruction\""))
        // Anthropic rejects a system ROLE inside messages, so it must not appear there.
        assertFalse(p.contains("\"role\":\"system\""))
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
