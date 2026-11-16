package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT SIX 6.1 — context compatibility, and where Project Five Area E lands.
 *
 * The rule under test throughout: a limit is used only when the catalogue knows it, reduction is
 * reported rather than silent, and an output allowance is never mistaken for an input budget.
 */
class Stage61ContextTest {

    private val known = ModelCapability("gpt-6.1-terra-pro", 1_050_000, supportsImages = true, supportsPdf = true)
    private val small = ModelCapability("llama-3.3-70b", 131_072, supportsImages = false, supportsPdf = false)

    @Test
    fun `a window inside a known limit is FITS with nothing to say`() {
        val d = ContextPreparer.decide(known, charsInWindow = 40_000, droppedMessages = 0, droppedChars = 0)
        assertEquals(ContextPreparer.Verdict.FITS, d.verdict)
        assertTrue(d.limitKnown)
        assertNull(d.notice)
        assertFalse(d.blocking)
    }

    @Test
    fun `an unknown model is reported unknown and nothing is claimed about the limit`() {
        val d = ContextPreparer.decide(null, charsInWindow = 40_000, droppedMessages = 0, droppedChars = 0)
        assertEquals(ContextPreparer.Verdict.LIMIT_UNKNOWN, d.verdict)
        assertFalse(d.limitKnown)
        assertNull(d.contextTokens)
        assertNull(d.inputTokensBudget)
        assertNull("nothing to say when nothing was dropped", d.notice)
    }

    @Test
    fun `an unknown model that lost turns says so without inventing a limit`() {
        val d = ContextPreparer.decide(null, 40_000, droppedMessages = 7, droppedChars = 9_000)
        assertEquals(ContextPreparer.Verdict.LIMIT_UNKNOWN, d.verdict)
        assertTrue(d.notice!!.contains("7"))
        assertTrue("the notice must not cite a token number", !d.notice!!.contains("tokens)"))
    }

    @Test
    fun `dropped turns under a known limit are REDUCED and explained`() {
        val d = ContextPreparer.decide(small, 200_000, droppedMessages = 3, droppedChars = 20_000)
        assertEquals(ContextPreparer.Verdict.REDUCED, d.verdict)
        assertTrue(d.notice!!.contains("3"))
        assertTrue(d.notice!!.contains("131072"))
    }

    @Test
    fun `the output allowance is subtracted from the input budget, not added to it`() {
        val withOutput = ContextPreparer.decide(small, 10_000, 0, 0, maxOutputTokens = 32_000)
        val without = ContextPreparer.decide(small, 10_000, 0, 0, maxOutputTokens = 0)
        assertTrue(withOutput.inputTokensBudget!! < without.inputTokensBudget!!)
    }

    @Test
    fun `a conversation larger than the window is OVERFLOW and blocking`() {
        val d = ContextPreparer.decide(small, charsInWindow = 4_000_000, droppedMessages = 0, droppedChars = 0)
        assertEquals(ContextPreparer.Verdict.OVERFLOW, d.verdict)
        assertTrue(d.blocking)
        assertTrue(d.notice!!.contains("not sent"))
    }

    @Test
    fun `the estimate is chars per token and says so`() {
        val d = ContextPreparer.decide(known, charsInWindow = 40_000, droppedMessages = 0, droppedChars = 0)
        assertEquals(40_000 / ContextPreparer.CHARS_PER_TOKEN_ESTIMATE, d.estimatedTokens)
    }

    @Test
    fun `the catalogue decides whether a model's limit is known at all`() {
        assertEquals(ModelCatalog.Source.UNKNOWN, ModelCatalog.resolve("openai", "totally-unknown-model").source)
        val d = ContextPreparer.decide(
            ModelCatalog.capabilityFor("openai", "totally-unknown-model"), 1_000, 0, 0)
        assertEquals(ContextPreparer.Verdict.LIMIT_UNKNOWN, d.verdict)
    }

    @Test
    fun `a real send records the decision it took`() {
        val transport = object : ChatTransport {
            override fun stream(url: String, headers: Map<String, String>, payloadJson: String) =
                kotlinx.coroutines.flow.flow<String> { emit("data: [DONE]\n\n") }
        }
        val engine = ChatEngineImpl("s61", transport, OpenAIAdapter())
        kotlinx.coroutines.test.runTest {
            engine.send("hello", emptyList(),
                EngineConfig(modelId = "gpt-6.1-terra-pro", providerId = "openai",
                    baseUrl = "https://x.example", apiKey = "k")).collect { }
        }
        val d = engine.lastContextDecision
        assertTrue("the engine must record what it decided", d != null)
        assertTrue(d!!.limitKnown)
    }
}
