package com.codespace.ide.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A limit the provider states about itself is learned ONCE and then respected: the next send is
 * smaller, without the owner touching a setting and without a transcript row explaining an engine
 * decision.
 */
class LearnedLimitTest {

    /** Rejects the first send with the Groq TPM shape, accepts everything after. */
    private class TpmWallTransport(private val limit: Int, private val requested: Int) : ChatTransport {
        val payloads = mutableListOf<String>()
        override fun stream(url: String, headers: Map<String, String>, payloadJson: String): Flow<String> = flow {
            payloads.add(payloadJson)
            if (payloads.size == 1) {
                throw TransportHttpException(400, emptyMap(),
                    """{"error":{"message":"Request too large for model `m` on tokens per minute (TPM): Limit """ +
                    limit + ", Requested " + requested + ", please reduce your message size" + "\"}}")
            }
            emit("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n")
            emit("data: [DONE]\n\n")
        }
    }

    private fun cfg() = EngineConfig(
        modelId = "qwen-test", providerId = "openai",
        baseUrl = "https://api.groq.com/openai/v1", apiKey = "fake-key-for-tests-only",
    )

    @Test
    fun theWallIsHitOnceThenRespected() = runBlocking {
        val t = TpmWallTransport(limit = 300, requested = 900)
        val engine = ChatEngineImpl("s-learn", t)
        // Build a conversation far bigger than the learned budget.
        repeat(6) { engine.send("u$it " + "x".repeat(400), emptyList(), cfg()).toList() }
        val learned = engine.learnedBudgetChars(cfg())
        assertEquals("85% of the stated limit, in chars", (300 * 4 * 85) / 100, learned)
        engine.send("now " + "y".repeat(400), emptyList(), cfg()).toList()
        val payload = t.payloads.last()
        val historyChars = engine.transcript().sumOf { it.text.length }
        println("[learn] payloadChars=" + payload.length + " transcriptChars=" + historyChars +
            " learnedChars=" + learned + " dropped=" + engine.lastFitDroppedMessages)
        assertTrue("the transcript is bigger than the budget: " + historyChars, historyChars > learned)
        assertTrue("yet the request respects it: " + payload.length, payload.length < learned + 600)
        assertTrue("and the newest message still travels", payload.contains("now"))
        assertTrue("older turns were dropped", engine.lastFitDroppedMessages > 0)
    }

    @Test
    fun nothingIsLearnedFromAnUnrelatedFailure() = runBlocking {
        val t = object : ChatTransport {
            override fun stream(url: String, headers: Map<String, String>, payloadJson: String): Flow<String> = flow {
                throw TransportHttpException(401, emptyMap(), """{"error":"bad key"}""")
            }
        }
        val engine = ChatEngineImpl("s-nolearn", t)
        engine.send("hi", emptyList(), cfg()).toList()
        assertEquals("no budget invented from an auth error", 0, engine.learnedBudgetChars(cfg()))
    }
}
