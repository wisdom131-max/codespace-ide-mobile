package com.codespace.ide.chat.panel

import com.codespace.ide.engine.FinishReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinishReasonTest {

    @Test fun openAiNatural() = assertEquals(FinishReason.NATURAL, FinishReason.normalize("openai", "stop"))
    @Test fun openAiCeilingIsNotComplete() {
        val r = FinishReason.normalize("openai", "length")
        assertEquals(FinishReason.OUTPUT_LIMIT, r)
        assertFalse(r.complete)
        assertTrue(r.continuable)
    }
    @Test fun openAiFilter() = assertEquals(FinishReason.CONTENT_FILTER, FinishReason.normalize("openai", "content_filter"))
    @Test fun anthropicCeiling() = assertEquals(FinishReason.OUTPUT_LIMIT, FinishReason.normalize("anthropic", "max_tokens"))
    @Test fun anthropicNatural() = assertEquals(FinishReason.NATURAL, FinishReason.normalize("anthropic", "end_turn"))
    @Test fun anthropicStopSequence() = assertEquals(FinishReason.STOP_SEQUENCE, FinishReason.normalize("anthropic", "stop_sequence"))
    @Test fun anthropicToolUse() = assertEquals(FinishReason.TOOL_CALLS, FinishReason.normalize("anthropic", "tool_use"))
    @Test fun geminiCeiling() = assertEquals(FinishReason.OUTPUT_LIMIT, FinishReason.normalize("gemini", "MAX_TOKENS"))
    @Test fun geminiSafety() = assertEquals(FinishReason.CONTENT_FILTER, FinishReason.normalize("gemini", "SAFETY"))
    @Test fun claudeAliasWorks() = assertEquals(FinishReason.OUTPUT_LIMIT, FinishReason.normalize("Claude", "max_tokens"))

    @Test fun missingMetadataIsUnknownAndNotNatural() {
        val r = FinishReason.normalize("openai", null)
        assertEquals(FinishReason.UNKNOWN, r)
        assertFalse(r.complete)
        assertFalse(r.continuable)
    }
    @Test fun blankMetadataIsUnknown() = assertEquals(FinishReason.UNKNOWN, FinishReason.normalize("gemini", "  "))
    @Test fun unknownProviderValueIsUnknownNotGuessed() {
        assertEquals(FinishReason.UNKNOWN, FinishReason.normalize("openai", "some_new_value"))
    }
    @Test fun unknownProviderIsUnknown() {
        assertEquals(FinishReason.UNKNOWN, FinishReason.normalize("mystery", "stop"))
    }
    @Test fun onlyOutputLimitIsContinuable() {
        FinishReason.entries.filter { it != FinishReason.OUTPUT_LIMIT }.forEach {
            assertFalse("$it must not be continuable", it.continuable)
        }
    }
    @Test fun aFilterIsNeverContinued() {
        assertFalse(FinishReason.CONTENT_FILTER.continuable)
        assertFalse(FinishReason.CONTENT_FILTER.complete)
    }
}
