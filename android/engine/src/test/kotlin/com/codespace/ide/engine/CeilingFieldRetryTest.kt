package com.codespace.ide.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one retry, end to end through the real engine: a 400 that names the ceiling field it refuses
 * flips the field once and completes; anything else must not retry at all.
 */
class CeilingFieldRetryTest {

    private class ScriptedTransport(
        private val rejectFirst: Boolean,
        private val body: String,
        private val status: Int = 400,
    ) : ChatTransport {
        val payloads = mutableListOf<String>()
        override fun stream(url: String, headers: Map<String, String>, payloadJson: String): Flow<String> = flow {
            payloads.add(payloadJson)
            if (rejectFirst && payloads.size == 1) {
                throw TransportHttpException(status, emptyMap(), body)
            }
            emit("data: {\"choices\":[{\"delta\":{\"content\":\"Hello!\"}}]}\n\n")
            emit("data: [DONE]\n\n")
        }
    }

    private fun cfg() = EngineConfig(
        modelId = "gpt-test",
        providerId = "openai",
        baseUrl = "https://api.openai.com/v1",
        apiKey = "fake-key-for-tests-only",
        maxTokensField = "max_tokens",
    )

    @Test
    fun aNamedRefusalFlipsTheFieldOnceAndCompletes() = runBlocking {
        val t = ScriptedTransport(
            rejectFirst = true,
            body = """{"error":{"message":"Unsupported parameter: 'max_tokens' is not supported with this model. Use 'max_completion_tokens' instead.","type":"invalid_request_error"}}""",
        )
        val engine = ChatEngineImpl("s-retry", t)
        val events = engine.send("hi", emptyList(), cfg()).toList()

        assertEquals("exactly two attempts", 2, t.payloads.size)
        assertTrue("first attempt used the old name", t.payloads[0].contains("\"max_tokens\":"))
        assertTrue("the retry used the name the provider asked for",
            t.payloads[1].contains("\"max_completion_tokens\":"))
        assertTrue("and the answer still arrived", events.any { it is EngineEvent.Completed })
        assertEquals("Hello!", events.filterIsInstance<EngineEvent.Delta>().joinToString("") { it.text })
    }

    @Test
    fun anAuthFailureIsNeverRetriedAsAFieldProblem() = runBlocking {
        val t = ScriptedTransport(rejectFirst = true, status = 401, body = """{"error":"bad key"}""")
        val engine = ChatEngineImpl("s-auth", t)
        val events = engine.send("hi", emptyList(), cfg()).toList()
        assertEquals("one attempt only", 1, t.payloads.size)
        assertTrue(events.any { it is EngineEvent.Failed })
    }

    @Test
    fun aBare400IsNotTreatedAsAFieldRefusal() = runBlocking {
        val t = ScriptedTransport(rejectFirst = true, body = """{"error":"messages: field required"}""")
        val engine = ChatEngineImpl("s-bare", t)
        engine.send("hi", emptyList(), cfg()).toList()
        assertEquals("one attempt only", 1, t.payloads.size)
    }
}
