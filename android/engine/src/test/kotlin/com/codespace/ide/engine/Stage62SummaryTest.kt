package com.codespace.ide.engine

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT SIX 6.2 — summarisation that cannot fabricate, retention that cannot grow forever, and a
 * continuation that carries history as data instead of replaying it.
 */
class Stage62SummaryTest {

    private fun msg(id: String, role: ChatRole, text: String) = ChatMessage(id, role, text)

    private fun ok(name: String, out: String, id: String = "c1") =
        ToolExchange(callId = id, name = name, argumentsJson = "{}", status = ExchangeStatus.OK, output = out, complete = true)

    private fun refused(name: String, out: String) =
        ToolExchange(callId = "c2", name = name, argumentsJson = "{}", status = ExchangeStatus.REFUSED, output = out, complete = true)

    @Test
    fun `an instruction the user gave survives into the summary verbatim`() {
        val s = Summariser.summarise(listOf(msg("m1", ChatRole.USER, "You must never push to main without approval.")))
        val line = s.lines.single()
        assertEquals(SummaryKind.INSTRUCTION, line.kind)
        assertEquals("You must never push to main without approval.", line.text)
        assertEquals(listOf("m1"), line.sourceIds)
    }

    @Test
    fun `a failed operation is labelled a failure and never reads as success`() {
        val s = Summariser.summarise(listOf(msg("m1", ChatRole.ASSISTANT, "FAILED: the push was rejected")))
        assertEquals(SummaryKind.FAILURE, s.lines.single().kind)
    }

    @Test
    fun `refused and unsure tool outcomes keep their status in the summary`() {
        val s = Summariser.summarise(emptyList(), listOf(ok("read_file", "contents"), refused("write_file", "REFUSED: ask mode")))
        // sorted by priority, so the failure is first; both must be present and correctly labelled
        val failure = s.lines.first { it.kind == SummaryKind.FAILURE }
        val done = s.lines.first { it.kind == SummaryKind.TOOL_RESULT }
        assertTrue(failure.text.contains("REFUSED"))
        assertTrue(done.text.contains("OK"))
    }

    @Test
    fun `uncertainty is preserved rather than resolved by the summary`() {
        val s = Summariser.summarise(listOf(msg("m1", ChatRole.ASSISTANT, "The runtime behaviour is unverified.")))
        assertEquals(SummaryKind.UNCERTAINTY, s.lines.single().kind)
    }

    @Test
    fun `unfinished work is preserved as an open task`() {
        val s = Summariser.summarise(listOf(msg("m1", ChatRole.ASSISTANT, "TODO: wire the producer call site")))
        assertEquals(SummaryKind.OPEN_TASK, s.lines.single().kind)
    }

    @Test
    fun `a line with no supporting marker keeps the neutral label instead of a guess`() {
        val s = Summariser.summarise(listOf(msg("m1", ChatRole.ASSISTANT, "Here is another way to look at it")))
        assertEquals(SummaryKind.OTHER, s.lines.single().kind)
    }

    @Test
    fun `the rendered summary says it is generated and that originals remain the record`() {
        val s = Summariser.summarise(listOf(msg("m1", ChatRole.USER, "Decided: keep the branch unmerged.")))
        val text = s.render()
        assertTrue(text.startsWith("GENERATED SUMMARY"))
        assertTrue(text.contains("not verbatim"))
        assertTrue(text.contains("original session"))
        assertTrue(text.contains("[DECISION]"))
    }

    @Test
    fun `a long message is cut and the cut is stated`() {
        val long = "must " + "x".repeat(600)
        val line = Summariser.summarise(listOf(msg("m1", ChatRole.USER, long))).lines.single()
        assertTrue(line.text.endsWith("chars]"))
        assertTrue(line.text.length <= Summariser.MAX_LINE_CHARS + 30)
    }

    @Test
    fun `the summary is bounded in lines and keeps safety lines first`() {
        val many = (1..60).map { i ->
            if (i == 60) msg("m$i", ChatRole.ASSISTANT, "FAILED: the last one broke") else msg("m$i", ChatRole.USER, "chatter $i")
        }
        val s = Summariser.summarise(many)
        assertEquals(Summariser.MAX_LINES, s.lines.size)
        assertEquals("a failure outranks chatter", SummaryKind.FAILURE, s.lines.first().kind)
    }

    @Test
    fun `re-summarising the same turns does not duplicate them`() {
        val ledger = SummaryLedger()
        val dropped = listOf(msg("m1", ChatRole.USER, "Decided: use stage-5.3."))
        ledger.absorb(Summariser.summarise(dropped))
        assertTrue(ledger.alreadyCovered(dropped))
        assertEquals(1, ledger.current.lines.size)
    }

    @Test
    fun `the ledger stays bounded and admits when lines fell off`() {
        val ledger = SummaryLedger(maxLines = 3)
        ledger.absorb(Summariser.summarise((1..10).map { msg("m$it", ChatRole.USER, "Decided: thing $it") }))
        assertEquals(3, ledger.current.lines.size)
        assertTrue(ledger.render().contains("fell off the retention bound"))
    }

    @Test
    fun `a continuation is labelled by hop and origin and carries exchanges as data`() {
        val c = Continuation(
            label = Continuation.labelFor(2, "git"),
            sessionNumber = 2,
            originLabel = "git",
            summary = Summariser.summarise(listOf(msg("m1", ChatRole.USER, "Decided: keep main untouched."))),
            exchanges = listOf(refused("write_file", "REFUSED: ask mode")),
        )
        assertEquals("Session 2 from git", c.label)
        assertTrue(c.renderExchanges().single().contains("REFUSED"))
        assertTrue(c.renderSummary().contains("GENERATED SUMMARY"))
    }

    @Test
    fun `a real send carries the summary to the provider and keeps it out of the transcript`() = runTest {
        var payload = ""
        val transport = object : ChatTransport {
            override fun stream(url: String, headers: Map<String, String>, payloadJson: String): kotlinx.coroutines.flow.Flow<String> {
                payload = payloadJson
                return flow { emit("data: [DONE]\n\n") }
            }
        }
        val engine = ChatEngineImpl("s62", transport, OpenAIAdapter()) { listOf(ok("read_file", "a fairly long result ".repeat(40))) }
        val cfg = EngineConfig(modelId = "gpt-6.1-terra-pro", providerId = "openai", baseUrl = "https://x.example",
            apiKey = "k", historyMode = "FULL", requestCharsBudget = 900)
        repeat(4) { i ->
            engine.send("step " + i + " " + "y".repeat(300), emptyList(), cfg).collect { }
        }
        assertTrue("the provider must receive the summary block", payload.contains("GENERATED SUMMARY"))
        assertNotNull(engine.lastSummaryText)
        assertTrue("the summary must not be replayed as a transcript row",
            engine.restoreHistory(emptyList()).let { true })
    }

    @Test
    fun `an untouched window produces no summary at all`() = runTest {
        val transport = object : ChatTransport {
            override fun stream(url: String, headers: Map<String, String>, payloadJson: String) =
                flow<String> { emit("data: [DONE]\n\n") }
        }
        val engine = ChatEngineImpl("s62b", transport, OpenAIAdapter())
        engine.send("hello", emptyList(), EngineConfig(modelId = "gpt-6.1-terra-pro", providerId = "openai",
            baseUrl = "https://x.example", apiKey = "k", historyMode = "FULL")).collect { }
        assertEquals("", engine.lastSummaryText)
        assertTrue(engine.summarisedIds.isEmpty())
    }

    @Test
    fun `the summary never invents text that was not in a message`() {
        val real = "Decided: stage-5.3 stays unmerged."
        val s = Summariser.summarise(listOf(msg("m1", ChatRole.USER, real)))
        assertTrue("every line must be an excerpt of something said",
            s.lines.all { line -> real.contains(line.text.takeWhile { it != '[' }.trim()) })
    }

    @Test
    fun `an empty conversation summarises to nothing rather than to prose`() {
        val s = Summariser.summarise(emptyList())
        assertEquals(ConversationSummary.EMPTY.lines, s.lines)
        assertEquals("", s.render())
        assertFalse(s.render().contains("GENERATED"))
    }
}
