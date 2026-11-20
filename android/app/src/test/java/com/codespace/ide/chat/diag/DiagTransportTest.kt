package com.codespace.ide.chat.diag

import com.codespace.ide.agent.CopilotDiag
import com.codespace.ide.engine.ChatTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class DiagTransportTest {

    @Before fun setUp() { CopilotDiag.detach(); CopilotDiag.clear() }
    @After fun tearDown() { CopilotDiag.detach(); CopilotDiag.clear() }

    private fun transport(f: () -> Flow<String>) = DiagTransport(
        object : ChatTransport {
            override fun stream(url: String, headers: Map<String, String>, payloadJson: String) = f()
        },
        { "gemini" },
        { "gemini-3.8-flash" },
    )

    @Test fun `a successful request records start then success`() = runBlocking {
        val out = transport { flow { emit("a"); emit("b") } }.stream("u", emptyMap(), "{}").toList()
        assertEquals(listOf("a", "b"), out)
        assertEquals(
            listOf(CopilotDiag.Kind.PROVIDER_REQUEST_START, CopilotDiag.Kind.PROVIDER_REQUEST_SUCCESS),
            CopilotDiag.recent().map { it.kind },
        )
    }

    @Test fun `chunks pass through unchanged and in order`() = runBlocking {
        val chunks = listOf("one", "two", "three")
        assertEquals(chunks, transport { flow { chunks.forEach { emit(it) } } }
            .stream("u", emptyMap(), "{}").toList())
    }

    @Test fun `a failure records failure and rethrows the same exception`() {
        val boom = IOException("network down")
        var caught: Throwable? = null
        runBlocking {
            try { transport { flow<String> { throw boom } }.stream("u", emptyMap(), "{}").toList() }
            catch (t: Throwable) { caught = t }
        }
        assertTrue(caught === boom)
        assertEquals(CopilotDiag.Kind.PROVIDER_REQUEST_FAILURE, CopilotDiag.recent().last().kind)
    }

    @Test fun `a cancelled request records cancelled and not failure`() {
        runBlocking {
            try {
                transport { flow<String> { throw CancellationException("stop") } }
                    .stream("u", emptyMap(), "{}").toList()
            } catch (_: CancellationException) { }
        }
        val kinds = CopilotDiag.recent().map { it.kind }
        assertEquals(CopilotDiag.Kind.PROVIDER_REQUEST_CANCELLED, kinds.last())
        assertTrue(kinds.none { it == CopilotDiag.Kind.PROVIDER_REQUEST_FAILURE })
        assertTrue(kinds.none { it == CopilotDiag.Kind.PROVIDER_REQUEST_SUCCESS })
    }

    @Test fun `one request produces exactly one start and one terminal event`() = runBlocking {
        transport { flow { emit("x") } }.stream("u", emptyMap(), "{}").toList()
        val kinds = CopilotDiag.recent().map { it.kind }
        assertEquals(1, kinds.count { it == CopilotDiag.Kind.PROVIDER_REQUEST_START })
        assertEquals(1, kinds.count { it == CopilotDiag.Kind.PROVIDER_REQUEST_SUCCESS })
    }

    @Test fun `credentials in headers are never recorded`() = runBlocking {
        transport { flow { emit("x") } }
            .stream("https://api.example/v1", mapOf("Authorization" to "Bearer SECRET123456"), "{}")
            .toList()
        val text = CopilotDiag.recent().joinToString(" ") { it.name + it.detail }
        assertTrue(!text.contains("SECRET123456"))
        assertTrue(!text.contains("api.example"))
    }

    @Test fun `the model id is recorded but the payload is not`() = runBlocking {
        transport { flow { emit("x") } }.stream("u", emptyMap(), "{\"prompt\":\"secret prompt\"}").toList()
        val text = CopilotDiag.recent().joinToString(" ") { it.name + it.detail }
        assertTrue(text.contains("gemini-3.8-flash"))
        assertTrue(!text.contains("secret prompt"))
    }
}
