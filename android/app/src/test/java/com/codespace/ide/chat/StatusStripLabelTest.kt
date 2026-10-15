package com.codespace.ide.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Advisor item 2 (2026-10-10): status-bar label rendering — provider name ONCE,
 * long model ids middle-shortened. The legacy custom endpoint (label literally
 * "Custom") previously rendered "AI: Custom · Custom · Qwen/..." because the
 * registry displayName ("Custom · <label>") is section-formatted and got printed
 * into the one-line bar next to the model.
 */
class StatusStripLabelTest {

    // Advisor follow-up (2026-10-15, CI hygiene): both assertions below were
    // born wrong and never executed (compile-only suites before 685eb0b) —
    // statusStripLabel ALWAYS returns "provider · model", and long model ids
    // ARE middle-shortened by the maxModelChars=26 default.

    @Test
    fun `short model ids pass through unchanged`() {
        assertEquals("OpenAI · gpt-4o-mini", ChatModelSelection.statusStripLabel("OpenAI", "gpt-4o-mini"))
        assertEquals("Custom · Qwen/Qwen3-32B", ChatModelSelection.statusStripLabel("Custom", "Qwen/Qwen3-32B"))
    }

    @Test
    fun `provider appears once — no section prefix`() {
        // The exact legacy case: endpoint label "Custom", model "Qwen/Qwen2.5-..."
        // -> "Custom · <shortened model>", the provider exactly ONCE (the old bug
        // printed the section-formatted registry displayName: "Custom · Custom · …").
        val label = ChatModelSelection.statusStripLabel("Custom", "Qwen/Qwen2.5-Coder-32B-Instruct")
        assertEquals("Custom · " + ChatModelSelection.shortenMiddle("Qwen/Qwen2.5-Coder-32B-Instruct", 26), label)
        assertEquals(1, label.split("Custom").size - 1)
    }

    @Test
    fun `long model ids shorten from the middle keeping head and tail`() {
        val long = "Qwen/Qwen2.5-Coder-32B-Instruct"
        val short = ChatModelSelection.shortenMiddle(long, 26)
        org.junit.Assert.assertTrue(short.length <= 27)
        org.junit.Assert.assertTrue(short.startsWith("Qwen/"))
        org.junit.Assert.assertTrue(short.endsWith("B-Instruct"))
        org.junit.Assert.assertTrue(short.contains("…"))
    }

    @Test
    fun `shortenMiddle leaves short strings alone`() {
        assertEquals("abc", ChatModelSelection.shortenMiddle("abc", 26))
        assertEquals("exactly-26-characters----", ChatModelSelection.shortenMiddle("exactly-26-characters----", 26))
    }

    @Test
    fun `status label shortens the model not the provider`() {
        val label = ChatModelSelection.statusStripLabel("HF Router", "Qwen/Qwen2.5-Coder-32B-Instruct", maxModelChars = 20)
        assertEquals("HF Router · " + ChatModelSelection.shortenMiddle("Qwen/Qwen2.5-Coder-32B-Instruct", 20), label)
        org.junit.Assert.assertTrue(label.startsWith("HF Router · Qwen/"))
    }
}
