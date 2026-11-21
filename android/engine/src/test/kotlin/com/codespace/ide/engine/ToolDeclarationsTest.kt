package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT EIGHT 8.2 — a tool that is never advertised can never be called natively.
 *
 * The engine could parse native tool calls before this, but no adapter sent a tools array, so the
 * model always answered in the text `<tool>` protocol instead. These tests pin the half that was
 * missing, and the two properties that make it safe to ship:
 *
 *  - with no declarations the request body is byte-identical to before (no behaviour change), and
 *  - every payload variant carries the tools, including the attachment one, because an adapter that
 *    silently drops tools when a file is attached is a bug that only shows up in real use.
 */
class ToolDeclarationsTest {

    private val readFile = ToolDeclaration(
        name = "read_file",
        description = "Read a file from the workspace",
        parametersJson = "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}",
    )
    private val writeFile = ToolDeclaration(
        name = "write_file",
        description = "Write a file",
    )

    private fun config(
        providerId: String,
        baseUrl: String,
        tools: List<ToolDeclaration>,
    ) = EngineConfig(
        modelId = "m",
        providerId = providerId,
        baseUrl = baseUrl,
        apiKey = "k",
        systemInstruction = "",
        reasoningBudget = 0,
        toolDeclarations = tools,
    )

    private val openAi = OpenAIAdapter()
    private val anthropic = AnthropicAdapter()
    private val gemini = GeminiAdapter()

    private fun openAiCfg(tools: List<ToolDeclaration>) =
        config("openai", "https://api.openai.com/v1/chat/completions", tools)

    private fun anthropicCfg(tools: List<ToolDeclaration>) =
        config("anthropic", "https://api.anthropic.com/v1/messages", tools)

    private fun geminiCfg(tools: List<ToolDeclaration>) =
        config("gemini", "https://generativelanguage.googleapis.com/v1beta/models/m:streamGenerateContent", tools)

    // ── the safety property: nothing changes when no tools are declared ─────────────────────

    @Test
    fun `no declarations leaves every request body byte identical`() {
        for ((adapter, cfg) in listOf(
            Triple(openAi as ProviderAdapter, openAiCfg(emptyList()), "openai"),
            Triple(anthropic as ProviderAdapter, anthropicCfg(emptyList()), "anthropic"),
            Triple(gemini as ProviderAdapter, geminiCfg(emptyList()), "gemini"),
        )) {
            val body = adapter.payload(emptyList(), "hello", cfg)
            assertEquals("$cfg must not gain a tools array", body, adapter.withTools(body, cfg))
            assertFalse("${cfg.providerId} must not mention tools", body.contains("\"tools\""))
        }
    }

    // ── each provider gets its own shape ───────────────────────────────────────────────────

    @Test
    fun `openai gets a function tools array and an explicit auto choice`() {
        val cfg = openAiCfg(listOf(readFile))
        val body = openAi.withTools(openAi.payload(emptyList(), "hi", cfg), cfg)
        assertTrue(body.contains("\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"read_file\""))
        assertTrue(body.contains("\"description\":\"Read a file from the workspace\""))
        assertTrue(body.contains("\"tool_choice\":\"auto\""))
        assertTrue("the caller's schema must survive verbatim", body.contains(readFile.parametersJson))
    }

    @Test
    fun `anthropic gets input_schema and no tool_choice`() {
        val cfg = anthropicCfg(listOf(readFile))
        val body = anthropic.withTools(anthropic.payload(emptyList(), "hi", cfg), cfg)
        assertTrue(body.contains("\"tools\":[{\"name\":\"read_file\""))
        assertTrue(body.contains("\"input_schema\":"))
        assertTrue(body.contains(readFile.parametersJson))
        // This wire has no tool_choice parameter; sending one would be inventing a field.
        assertFalse(body.contains("tool_choice"))
        // Anthropic calls it input_schema, never parameters.
        assertFalse(body.contains("\"parameters\":"))
    }

    @Test
    fun `gemini nests declarations and calls the schema parameters`() {
        val cfg = geminiCfg(listOf(readFile))
        val body = gemini.withTools(gemini.payload(emptyList(), "hi", cfg), cfg)
        assertTrue(body.contains("\"tools\":[{\"functionDeclarations\":[{\"name\":\"read_file\""))
        assertTrue(body.contains("\"parameters\":"))
        assertTrue(body.contains(readFile.parametersJson))
        assertFalse(body.contains("input_schema"))
    }

    @Test
    fun `every provider keeps the body a single object with tools inside it`() {
        val bodies = listOf(
            openAi.withTools(openAi.payload(emptyList(), "hi", openAiCfg(listOf(readFile, writeFile))), openAiCfg(listOf(readFile, writeFile))),
            anthropic.withTools(anthropic.payload(emptyList(), "hi", anthropicCfg(listOf(readFile, writeFile))), anthropicCfg(listOf(readFile, writeFile))),
            gemini.withTools(gemini.payload(emptyList(), "hi", geminiCfg(listOf(readFile, writeFile))), geminiCfg(listOf(readFile, writeFile))),
        )
        for (b in bodies) {
            assertTrue(b.startsWith("{"))
            assertTrue(b.endsWith("}"))
            // Tools must sit inside the object, never after its closing brace.
            assertTrue(b.indexOf("\"tools\"") < b.length - 1)
            assertEquals("exactly one tools array", 1, Regex("\"tools\"").findAll(b).count())
        }
    }

    @Test
    fun `two declarations are both sent and separated correctly`() {
        val cfg = openAiCfg(listOf(readFile, writeFile))
        val body = openAi.withTools(openAi.payload(emptyList(), "hi", cfg), cfg)
        assertTrue(body.contains("\"name\":\"read_file\""))
        assertTrue(body.contains("\"name\":\"write_file\""))
        assertFalse("no empty slot between declarations", body.contains(",,]"))
    }

    // ── the trap: an attached file must not drop the tools ────────────────────────────────

    @Test
    fun `tools survive the attachment payload variant`() {
        val attachment = ChatAttachment(
            id = "a1",
            name = "notes.txt",
            mimeType = "text/plain",
            textData = "some file content",
        )
        val cases = listOf(
            openAi as ProviderAdapter to openAiCfg(listOf(readFile)),
            anthropic as ProviderAdapter to anthropicCfg(listOf(readFile)),
            gemini as ProviderAdapter to geminiCfg(listOf(readFile)),
        )
        for ((adapter, cfg) in cases) {
            val body = adapter.withTools(
                adapter.payload(emptyList(), "hi", listOf(attachment), cfg),
                cfg,
            )
            assertTrue("${cfg.providerId} dropped tools when a file was attached", body.contains("\"tools\""))
        }
    }

    @Test
    fun `tools survive the tool exchange history variant`() {
        val exchange = ToolExchange(
            callId = "c1",
            name = "read_file",
            argumentsJson = "{\"path\":\"a.kt\"}",
            status = ExchangeStatus.OK,
            output = "fun main() {}",
        )
        val cases = listOf(
            openAi as ProviderAdapter to openAiCfg(listOf(readFile)),
            anthropic as ProviderAdapter to anthropicCfg(listOf(readFile)),
            gemini as ProviderAdapter to geminiCfg(listOf(readFile)),
        )
        for ((adapter, cfg) in cases) {
            val body = adapter.withTools(
                adapter.payloadWithHistory(emptyList(), "hi", emptyList(), cfg, listOf(exchange)),
                cfg,
            )
            assertTrue("${cfg.providerId} dropped tools on the exchange path", body.contains("\"tools\""))
        }
    }

    // ── declaration hygiene ───────────────────────────────────────────────────────────────

    @Test
    fun `a blank schema becomes the smallest valid schema`() {
        val cfg = openAiCfg(listOf(ToolDeclaration("t", "d", "")))
        val body = openAi.withTools(openAi.payload(emptyList(), "hi", cfg), cfg)
        assertTrue(body.contains("\"parameters\":" + ToolDeclaration.EMPTY_SCHEMA))
    }

    @Test
    fun `quotes and newlines in a description are escaped`() {
        val cfg = openAiCfg(listOf(ToolDeclaration("t", "say \"hi\"\nthen stop")))
        val body = openAi.withTools(openAi.payload(emptyList(), "hi", cfg), cfg)
        assertTrue(body.contains("say \\\"hi\\\"\\nthen stop"))
        // An unescaped quote would end the JSON string and break the whole request body.
        assertFalse(body.contains("say \"hi\""))
    }


    // ── catalog 27: prompt caching ────────────────────────────────────────────────────────

    @Test
    fun `anthropic marks one cache breakpoint, on the last declaration only`() {
        val cfg = anthropicCfg(listOf(readFile, writeFile))
        val body = anthropic.withTools(anthropic.payload(emptyList(), "hi", cfg), cfg)
        // Exactly one breakpoint: the prefix is cached once, and the four allowed slots are not
        // spent on a declaration that is already inside the cached prefix.
        assertEquals("exactly one cache breakpoint", 1, Regex("cache_control").findAll(body).count())
        // It must sit on the LAST declaration, or the prefix would end before the final tool and
        // every turn would re-send the tail.
        val lastToolAt = body.lastIndexOf("\"name\":\"write_file\"")
        val breakpointAt = body.indexOf("cache_control")
        assertTrue("breakpoint must follow the last declaration", breakpointAt > lastToolAt)
        assertTrue(body.contains("{\"type\":\"ephemeral\"}"))
    }

    @Test
    fun `no tools means no cache breakpoint`() {
        val cfg = anthropicCfg(emptyList())
        val body = anthropic.withTools(anthropic.payload(emptyList(), "hi", cfg), cfg)
        assertFalse(body.contains("cache_control"))
    }

    @Test
    fun `only anthropic carries cache_control`() {
        val oa = openAiCfg(listOf(readFile))
        val gm = geminiCfg(listOf(readFile))
        assertFalse(openAi.withTools(openAi.payload(emptyList(), "hi", oa), oa).contains("cache_control"))
        assertFalse(gemini.withTools(gemini.payload(emptyList(), "hi", gm), gm).contains("cache_control"))
    }

    @Test
    fun `splicing a fragment keeps the closing brace last`() {
        assertEquals("{\"a\":1,\"tools\":[]}", spliceTools("{\"a\":1}", ",\"tools\":[]"))
        // Nothing to splice: the body is returned untouched rather than mangled.
        assertEquals("{\"a\":1}", spliceTools("{\"a\":1}", ""))
        assertEquals("not json", spliceTools("not json", ",\"tools\":[]"))
    }
}
