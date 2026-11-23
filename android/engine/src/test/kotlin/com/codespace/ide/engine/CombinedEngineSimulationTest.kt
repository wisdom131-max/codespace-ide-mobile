package com.codespace.ide.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalog simulation: every engine subsystem meshed in ONE conversation.
 *
 * Unit tests prove each part in isolation. This drives them together, because the failures that
 * reach a device are seams - two parts that are each right and still do not fit. The scripted
 * transport stands in for the network, so the real send loop, real trimming, real fusion and real
 * scoring all run here.
 */
class CombinedEngineSimulationTest {

    private class ScriptedTransport(private val replies: List<String>) : ChatTransport {
        val payloads = ArrayList<String>()
        private var calls = 0

        override fun stream(url: String, headers: Map<String, String>, payloadJson: String): Flow<String> =
            flow {
                payloads.add(payloadJson)
                val reply = replies.getOrElse(calls) { replies.last() }
                calls++
                for (chunk in reply.chunked(24)) {
                    emit("data: {\"choices\":[{\"delta\":{\"content\":\"" + chunk + "\"}}]}\n\n")
                }
                emit("data: [DONE]")
            }
    }

    private val report = LinkedHashMap<String, String>()

    private suspend fun check(name: String, block: suspend () -> Unit) {
        report[name] = try {
            block()
            "PASS"
        } catch (t: Throwable) {
            "FAIL: " + (t.message ?: t::class.java.simpleName)
        }
    }

    @Test
    fun `every engine subsystem works together in one conversation`() = runBlocking {
        val docs = listOf(
            SearchPipeline.Doc("d1", "auth/Login.kt", "fun login(user: String, pass: String) { token = api.authenticate(user, pass) }"),
            SearchPipeline.Doc("d2", "ui/Theme.kt", "val colors = darkColorScheme(primary = Purple)"),
            SearchPipeline.Doc("d3", "auth/Token.kt", "class Token(val value: String) // refresh when expired"),
        )

        check("retrieval: lexical + semantic + fusion + dedupe") {
            val out = SearchPipeline.run("where is the login authentication code", docs, listOf("d1"), limit = 3)
            assertTrue("no results at all", out.ids.isNotEmpty())
            assertTrue("the auth file should be in the result: " + out.ids, out.ids.contains("d1"))
            "PASS (" + out.ids.joinToString(",") + ")"
        }

        check("reranker: a phrase match outranks a closer text") {
            val out = Reranker.rerank(
                listOf(
                    Reranker.Candidate("d2", "val colors = darkColorScheme", 1.0),
                    Reranker.Candidate("d1", "fun login authenticate user pass", 0.9),
                ),
                listOf("login", "authenticate"),
            )
            assertEquals("d1", out.first().id)
        }

        check("vector index: identical text scores 1.0 and survives encode/decode") {
            val a = VectorIndex.embed("login authenticate token")
            val same = VectorIndex.embed("login authenticate token")
            val other = VectorIndex.embed("purple dark theme colors")
            assertEquals(1.0, VectorIndex.cosine(a, same), 0.001)
            assertTrue("unrelated text should score below 0.9", VectorIndex.cosine(a, other) < 0.9)
            assertEquals(a.size, VectorIndex.decode(VectorIndex.encode(a)).size)
        }

        val transport = ScriptedTransport(listOf("The login code is in auth/Login.kt and calls api.authenticate."))
        val engine = ChatEngineImpl("sim-1", transport)
        val config = EngineConfig(
            modelId = "gpt-4o-mini",
            systemInstruction = "You are a code assistant.",
            volatileSystemTail = "Now: 2026-11-23T10:00Z",
            maxPayloadChars = 48_000,
            historyMode = "BALANCED",
            baseUrl = "https://example.invalid/v1/chat/completions",
        )

        check("send loop: assemble -> transport -> SSE -> events") {
            val events = engine.send("where is the login code?", config).toList()
            val streamed = events.filterIsInstance<EngineEvent.Delta>().joinToString("") { it.text }
            assertTrue("stream never completed", events.any { it is EngineEvent.Completed })
            assertTrue("nothing streamed", streamed.contains("Login.kt"))
            assertTrue("system instruction missing from the request", transport.payloads.first().contains("code assistant"))
            assertTrue("question missing from the request", transport.payloads.first().contains("login code"))
        }

        check("multi-turn: the earlier turn stays in the transcript") {
            engine.send("and the token refresh?", config).toList()
            assertTrue("transcript should hold both turns", engine.transcript().size >= 3)
        }

        check("history window: drops old turns, keeps the newest, admits what it did") {
            val long = (1..40).map {
                ChatMessage("m$it", if (it % 2 == 1) ChatRole.USER else ChatRole.ASSISTANT, "x".repeat(400))
            }
            val fit = HistoryBudget.fit(long, HistoryMode.BALANCED, systemChars = 200, budgetChars = 4_000)
            assertTrue("nothing was dropped", fit.droppedMessages > 0)
            assertEquals("the newest turn must survive", "m40", fit.messages.last().id)
            val decision = ContextPreparer.decide(
                capability = null,
                charsInWindow = 3_800,
                droppedMessages = fit.droppedMessages,
                droppedChars = fit.droppedChars,
            )
            assertFalse("an unknown window must not claim a known limit", decision.limitKnown)
        }

        check("budget guard: refuses over the line, names the limit, passes under it") {
            val refusal = BudgetGuard.checkPayload(payloadChars = 60_000, maxPayloadChars = 48_000)
            assertNotNull("an oversized payload was allowed", refusal)
            assertTrue("the refusal should name the limit", refusal!!.detail.contains("48k"))
            assertNull(BudgetGuard.checkPayload(10_000, 48_000))
        }

        check("tool argument check answers for a call with no arguments") {
            val problem = ToolArgCheck.problem("read_file", emptyMap())
            "PASS (returned: " + (problem ?: "null") + ")"
        }

        check("answer cache: same question hits, a changed stamp and model miss") {
            val cache = AnswerCache.Cache(maxEntries = 4)
            cache.put("where is login", "gpt-4o-mini", "stamp1", "auth/Login.kt", 1_000)
            assertEquals("auth/Login.kt", cache.get("where is login", "gpt-4o-mini", "stamp1"))
            assertNull("a different stamp must miss", cache.get("where is login", "gpt-4o-mini", "stamp2"))
            assertNull("a different model must miss", cache.get("where is login", "other-model", "stamp1"))
        }

        check("trajectory scoring: clean work vs claimed-done") {
            assertTrue(TrajectoryEvaluator.score(TrajectoryEvaluator.Turn(2, 0, true, false)).clean)
            val bad = TrajectoryEvaluator.score(TrajectoryEvaluator.Turn(3, 1, true, false))
            assertFalse("a failed call plus a done claim is not clean", bad.clean)
            assertTrue(bad.reasons.isNotEmpty())
        }

        check("one eval pass: split sizes, judge, clean rate") {
            val cases = (1..10).map { EvalDataset.EvalCase("c$it", "q$it", "a$it") }
            val verdicts = (1..6).map { JudgeRubric.Verdict(mapOf("overall" to 4), "ok") }
            val turns = listOf(
                TrajectoryEvaluator.Turn(2, 0, true, false),
                TrajectoryEvaluator.Turn(1, 1, false, false),
            )
            val r = EvalRun.run(cases, verdicts, turns)
            assertEquals(10, r.trainCases + r.holdoutCases)
            assertTrue("the report rendered nothing", r.render().isNotBlank())
            assertTrue("clean rate should be a percentage", r.cleanRate in 0..100)
        }

        val failures = report.filterValues { it.startsWith("FAIL") }
        println()
        println("=== COMBINED ENGINE SIMULATION ===")
        report.forEach { (name, verdict) -> println("  " + name.padEnd(56) + verdict) }
        println("=== " + (report.size - failures.size) + "/" + report.size + " subsystems agreed ===")
        assertTrue("subsystems failed: " + failures.keys, failures.isEmpty())
    }
}
