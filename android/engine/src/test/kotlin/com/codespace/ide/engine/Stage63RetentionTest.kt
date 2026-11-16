package com.codespace.ide.engine

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PROJECT SIX 6.3 — retention that survives restarts, corrections, and its own corruption. */
class Stage63RetentionTest {

    private fun msg(id: String, role: ChatRole, text: String) = ChatMessage(id, role, text)
    private fun dir(): File = Files.createTempDirectory("retention").toFile()
    private fun cfg(budget: Int = 900) = EngineConfig(
        modelId = "gpt-6.1-terra-pro", providerId = "openai", baseUrl = "https://x.example",
        apiKey = "k", historyMode = "FULL", requestCharsBudget = budget,
    )
    private fun quietTransport(sink: (String) -> Unit = {}) = object : ChatTransport {
        override fun stream(url: String, headers: Map<String, String>, payloadJson: String) =
            flow<String> { sink(payloadJson); emit("data: [DONE]\n\n") }
    }

    // ---------- 6.3A provenance and certainty ----------

    @Test
    fun `a line knows whether it came from a message, a tool, or an earlier summary`() {
        val s = Summariser.summarise(
            listOf(msg("m1", ChatRole.USER, "Decided: keep stage-5.3 unmerged.")),
            listOf(ToolExchange("c1", "write_file", "{}", ExchangeStatus.REFUSED, "REFUSED: ask mode", true)),
        )
        val fromMessage = s.lines.first { it.kind == SummaryKind.DECISION }
        val fromTool = s.lines.first { it.origin == LineOrigin.TOOL_EXCHANGE }
        assertEquals(LineOrigin.MESSAGE, fromMessage.origin)
        assertEquals(ClaimStatus.CONFIRMED, fromTool.status)
    }

    @Test
    fun `unverified beats a nearby verified`() {
        assertEquals(ClaimStatus.UNRESOLVED, ClaimStatus.of("the device behaviour is unverified even though the build was verified"))
    }

    @Test
    fun `an explicit assumption stays an assumption and never becomes certainty`() {
        val s = Summariser.summarise(listOf(msg("m1", ChatRole.ASSISTANT, "Assume the runner has python3.")))
        assertEquals(ClaimStatus.ASSUMED, s.lines.single().status)
        assertTrue(s.render().contains("[assumed]"))
    }

    @Test
    fun `an uncertain tool call stays unresolved in the retained text`() {
        val s = Summariser.summarise(
            emptyList(),
            listOf(ToolExchange("c1", "push", "{}", ExchangeStatus.UNCERTAIN, "no response", false)),
        )
        assertEquals(ClaimStatus.UNRESOLVED, s.lines.single().status)
    }

    // ---------- 6.3B repeated summarisation ----------

    @Test
    fun `a newer decision replaces the older statement of the same subject`() {
        val ledger = SummaryLedger()
        ledger.absorb(Summariser.summarise(listOf(msg("m1", ChatRole.USER, "Decided: keep the branch stage-5.3 unmerged."))))
        ledger.absorb(Summariser.summarise(listOf(msg("m2", ChatRole.USER, "Decided: keep the branch stage-5.3 on hold instead."))))
        val decisions = ledger.current.lines.filter { it.kind == SummaryKind.DECISION }
        assertEquals("one decision, not two contradictory ones", 1, decisions.size)
        assertTrue(decisions.single().text.contains("on hold"))
        assertEquals(1, ledger.supersededCount())
        assertTrue(ledger.render().contains("replaced by a newer one"))
    }

    @Test
    fun `a later success never deletes the record of an earlier failure`() {
        val ledger = SummaryLedger()
        ledger.absorb(Summariser.summarise(listOf(msg("m1", ChatRole.ASSISTANT, "FAILED: the CI run went red on push"))))
        ledger.absorb(Summariser.summarise(listOf(msg("m2", ChatRole.ASSISTANT, "FAILED: the CI run went red on merge"))))
        assertEquals(2, ledger.current.lines.count { it.kind == SummaryKind.FAILURE })
        assertEquals(0, ledger.supersededCount())
    }

    @Test
    fun `the same sentence summarised twice is stored once`() {
        val ledger = SummaryLedger()
        val one = Summariser.summarise(listOf(msg("m1", ChatRole.USER, "Decided: keep stage-5.3 unmerged.")))
        ledger.absorb(one)
        ledger.absorb(one)
        assertEquals(1, ledger.current.lines.size)
    }

    @Test
    fun `retention preserves the order things were said in`() {
        val ledger = SummaryLedger(maxLines = 10)
        ledger.absorb(Summariser.summarise(listOf(
            msg("m1", ChatRole.USER, "Decided: first thing."),
            msg("m2", ChatRole.USER, "Decided: second thing."),
        )))
        val seqs = ledger.current.lines.map { it.seq }
        assertEquals(seqs.sorted(), seqs)
    }

    // ---------- 6.3D storage, recovery, bounds ----------

    @Test
    fun `a stored summary comes back with its provenance intact`() {
        val store = SummaryStore(dir())
        val built = Summariser.summarise(listOf(msg("m1", ChatRole.USER, "Decided: keep stage-5.3 unmerged.")))
        assertTrue(store.save("s1", built, built.coveredIds))
        val back = store.load("s1")
        assertTrue(back is SummaryStore.Load.Ok)
        val line = (back as SummaryStore.Load.Ok).summary.lines.single()
        assertEquals(SummaryKind.DECISION, line.kind)
        assertEquals(LineOrigin.MESSAGE, line.origin)
        assertEquals(listOf("m1"), line.sourceIds)
    }

    @Test
    fun `a summary written by another session is refused, not adopted`() {
        val store = SummaryStore(dir())
        val built = Summariser.summarise(listOf(msg("m1", ChatRole.USER, "Decided: keep stage-5.3 unmerged.")))
        store.save("s1", built, built.coveredIds)
        val f = store.javaClass.getDeclaredMethod("fileFor", String::class.java).apply { isAccessible = true }
        val other = f.invoke(store, "s2") as File
        other.parentFile.mkdirs()
        other.writeText("vncode-summary-1|s1|1\n0|DECISION|CONFIRMED|MESSAGE|user|m1|leaked|0\n")
        val load = store.load("s2")
        assertTrue(load is SummaryStore.Load.Corrupt)
        assertTrue((load as SummaryStore.Load.Corrupt).reason.contains("another session"))
    }

    @Test
    fun `a torn or unparseable record is reported, never half-trusted`() {
        val store = SummaryStore(dir())
        val built = Summariser.summarise(listOf(msg("m1", ChatRole.USER, "Decided: keep stage-5.3 unmerged.")))
        store.save("s1", built, built.coveredIds)
        val f = store.javaClass.getDeclaredMethod("fileFor", String::class.java).apply { isAccessible = true }
            .invoke(store, "s1") as File
        f.writeText("vncode-summary-1|s1|2\n0|NOT_A_KIND|CONFIRMED|MESSAGE|user|m1|x|0\n")
        val load = store.load("s1")
        assertTrue(load is SummaryStore.Load.Corrupt)
        assertTrue((load as SummaryStore.Load.Corrupt).reason.contains("unknown kind"))
    }

    @Test
    fun `a write is atomic so a crash cannot destroy the previous summary`() {
        val d = dir()
        val store = SummaryStore(d)
        val first = Summariser.summarise(listOf(msg("m1", ChatRole.USER, "Decided: keep stage-5.3 unmerged.")))
        store.save("s1", first, first.coveredIds)
        // Simulate a process death between writing the temp file and renaming it.
        val f = store.javaClass.getDeclaredMethod("fileFor", String::class.java).apply { isAccessible = true }
            .invoke(store, "s1") as File
        File(f.absolutePath + ".tmp").writeText("half a summary")
        val load = store.load("s1")
        assertTrue("the good file is still what is read", load is SummaryStore.Load.Ok)
        assertEquals(1, (load as SummaryStore.Load.Ok).summary.lines.size)
    }

    @Test
    fun `nothing credential-shaped reaches the disk`() {
        val d = dir()
        val store = SummaryStore(d)
        val built = Summariser.summarise(listOf(msg("m1", ChatRole.USER, "the token is ghp_012345678901234567890123456789012345 and the key sk-ant-abcdefghijkl")))
        store.save("s1", built, built.coveredIds)
        val raw = d.walkTopDown().filter { it.isFile }.joinToString("") { it.readText() }
        assertFalse("no token in the raw file", raw.contains("ghp_") || raw.contains("sk-ant"))
        val back = store.load("s1")
        assertTrue(back is SummaryStore.Load.Ok)
        val text = (back as SummaryStore.Load.Ok).summary.render()
        assertTrue("the reader is told the value was removed: " + text, text.contains("[redacted]"))
    }

    @Test
    fun `retention is bounded to a fixed number of sessions`() {
        val d = dir()
        val store = SummaryStore(d, maxSessions = 2)
        val built = Summariser.summarise(listOf(msg("m1", ChatRole.USER, "Decided: keep stage-5.3 unmerged.")))
        repeat(4) { i ->
            store.save("s$i", built, built.coveredIds)
            Thread.sleep(12)
        }
        val files = d.listFiles { f -> f.name.endsWith(".summary") } ?: emptyArray()
        assertEquals(2, files.size)
    }

    @Test
    fun `no directory means no retention, reported as a failure to write`() {
        assertFalse(SummaryStore(null).save("s1", Summariser.summarise(emptyList()), emptyList()))
        assertTrue(SummaryStore(null).load("s1") is SummaryStore.Load.Empty)
    }

    // ---------- 6.3C historical retrieval ----------

    @Test
    fun `an old turn is quoted back verbatim when the question matches it`() {
        val old = msg("m1", ChatRole.USER, "The prober fix lives in scripts/backend-health-probe.sh and its timeout is 120 seconds.")
        val r = HistoryRetriever.retrieve("what timeout does the prober script use?", listOf(old), emptySet(), 800)
        assertEquals(1, r.lines.size)
        assertTrue(r.lines.single().text.contains("scripts/backend-health-probe.sh"))
        assertTrue(r.render().startsWith("RETRIEVED FROM EARLIER TURNS"))
        assertEquals(listOf("m1"), r.lines.single().sourceIds)
    }

    @Test
    fun `retrieval never quotes what the window already holds`() {
        val old = msg("m1", ChatRole.USER, "The prober timeout is 120 seconds in the script.")
        assertTrue(HistoryRetriever.retrieve("prober timeout", listOf(old), setOf("m1"), 800).isEmpty())
    }

    @Test
    fun `retrieval stays inside its own budget`() {
        val olds = (1..6).map { msg("m$it", ChatRole.USER, "prober timeout detail number $it " + "z".repeat(300)) }
        val r = HistoryRetriever.retrieve("prober timeout detail", olds, emptySet(), 300)
        assertTrue("chars=" + r.chars, r.chars <= 300)
    }

    @Test
    fun `retrieval ignores an empty question and system rows`() {
        val sys = msg("s", ChatRole.SYSTEM, "prober timeout detail lives here")
        assertTrue(HistoryRetriever.retrieve("", listOf(sys), emptySet(), 500).isEmpty())
        assertTrue(HistoryRetriever.retrieve("prober timeout", listOf(sys), emptySet(), 500).isEmpty())
    }

    // ---------- engine integration: restore, isolate, budget ----------

    @Test
    fun `a send carries the summary and the retrieved evidence, both inside the budget`() = runTest {
        var payload = ""
        val engine = ChatEngineImpl("s63", quietTransport { payload = it }, OpenAIAdapter())
        val long = "The prober timeout is 120 seconds and the script is scripts/backend-health-probe.sh. "
        repeat(4) { engine.send(long + "turn " + it + " " + "q".repeat(260), emptyList(), cfg(900)).collect { } }
        engine.send("what timeout does the prober script use?", emptyList(), cfg(900)).collect { }
        assertTrue("summary rides along", payload.contains("GENERATED SUMMARY") || payload.contains("RETRIEVED FROM EARLIER TURNS"))
        assertTrue("request stays inside the wall: " + payload.length, payload.length < 900 * 2)
        assertTrue("neither block becomes a transcript row",
            engine.transcript().none { it.id == ChatEngineImpl.SUMMARY_ID || it.id == ChatEngineImpl.RETRIEVED_ID })
    }

    @Test
    fun `a restored summary arrives with a note saying it was restored`() = runTest {
        val d = dir()
        val store = SummaryStore(d)
        val a = ChatEngineImpl("s1", quietTransport(), OpenAIAdapter(), summaryStore = store)
        repeat(3) { a.send("Decided: keep stage-5.3 unmerged. " + "w".repeat(300), emptyList(), cfg(800)).collect { } }
        assertTrue("something was retained", a.lastSummaryText.isNotEmpty())

        val b = ChatEngineImpl("s1", quietTransport(), OpenAIAdapter(), summaryStore = store)
        assertNotNull(b.lastRetentionNote)
        assertTrue(b.lastRetentionNote!!.contains("Restored"))
        assertTrue(b.lastSummaryText.contains("stage-5.3"))
    }

    @Test
    fun `another session never inherits a retained summary`() = runTest {
        val store = SummaryStore(dir())
        val a = ChatEngineImpl("s1", quietTransport(), OpenAIAdapter(), summaryStore = store)
        repeat(3) { a.send("Decided: keep stage-5.3 unmerged. " + "w".repeat(300), emptyList(), cfg(800)).collect { } }
        val other = ChatEngineImpl("s2", quietTransport(), OpenAIAdapter(), summaryStore = store)
        assertEquals("", other.lastSummaryText)
        assertEquals(null, other.lastRetentionNote)
    }

    @Test
    fun `a corrupt record is ignored with a reason and rebuilt from the conversation`() = runTest {
        val d = dir()
        val store = SummaryStore(d)
        val a = ChatEngineImpl("s1", quietTransport(), OpenAIAdapter(), summaryStore = store)
        repeat(3) { a.send("Decided: keep stage-5.3 unmerged. " + "w".repeat(300), emptyList(), cfg(800)).collect { } }
        d.listFiles { f -> f.name.endsWith(".summary") }!!.forEach { it.writeText("garbage") }
        val b = ChatEngineImpl("s1", quietTransport(), OpenAIAdapter(), summaryStore = store)
        assertTrue(b.lastRetentionNote!!.contains("ignored"))
        repeat(3) { b.send("Decided: keep the branch unmerged. " + "v".repeat(300), emptyList(), cfg(800)).collect { } }
        assertTrue("it rebuilt rather than stayed blank", b.lastSummaryText.isNotEmpty())
    }
}
