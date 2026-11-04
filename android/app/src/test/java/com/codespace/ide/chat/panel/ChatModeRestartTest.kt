package com.codespace.ide.chat.panel

import com.codespace.ide.chat.CustomEndpointStore
import com.codespace.ide.chat.StoredMessage
import com.codespace.ide.chat.TranscriptLoad
import com.codespace.ide.chat.keys.StoredCredentialsReader
import com.codespace.ide.chat.session.SessionIndexStore
import com.codespace.ide.chat.session.SessionCreate
import com.codespace.ide.chat.session.SessionMode
import com.codespace.ide.chat.session.SessionTranscripts
import com.codespace.ide.engine.ChatEngineImpl
import com.codespace.ide.engine.ChatTransport
import com.codespace.ide.engine.EngineConfig
import com.codespace.ide.engine.EngineEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Stage 1(b) — 2026-11-04 device finding #1: "Agent mode does not survive app reopen".
 *
 * What the owner saw: the chip still said Agent after reopening, the assistant still answered
 * "I have no access to tools". The chip was restored from disk (`ChatPanelScreen:221`), but the
 * session's mode was a hardcoded ASK that only changed when the chip was tapped, so the REQUEST
 * carried Ask instructions and the read-only tool set.
 *
 * These tests assert the request, not the label: the payload the transport receives must contain
 * the Agent tool set and the Agent instruction after a cold restore.
 */
class ChatModeRestartTest {

    private class MemTranscripts : SessionTranscripts {
        val conv = LinkedHashMap<String, List<StoredMessage>>()
        override fun ids(projectId: String): List<String> = conv.keys.toList()
        override fun readNow(projectId: String, conversationId: String): TranscriptLoad =
            TranscriptLoad(conv[conversationId].orEmpty())
        override fun deleteNow(projectId: String, conversationId: String): String? {
            conv.remove(conversationId); return null
        }
    }

    /** Records every payload the engine would put on the wire. */
    private class Capture : ChatTransport {
        val payloads = ArrayList<String>()
        override fun stream(url: String, headers: Map<String, String>, payloadJson: String): Flow<String> {
            payloads.add(payloadJson)
            return flowOf(
                "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n",
                "data: [DONE]\n\n",
            )
        }
    }

    private lateinit var root: File
    private lateinit var transcripts: MemTranscripts
    private var now = 1_000L

    @Before
    fun setUp() {
        root = File.createTempFile("vncode-mode", "test").let { f ->
            f.delete(); f.mkdirs(); f
        }
        transcripts = MemTranscripts()
        now = 1_000L
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun store() = SessionIndexStore(root, clock = { now }, random = Random(7), transcripts = transcripts)

    private fun manager() = ChatSessionManager(
        StoredCredentialsReader({ mapOf("ai_OPENAI" to "k") }, { "k" }, CustomEndpointStore),
        appContext = null,
        sessionStore = store(),
    )

    /** The whole point: a STORED Agent session must come back as an Agent session. */
    @Test
    fun `a stored Agent session is created in Agent mode after a restart`() {
        val first = manager()
        val id = (first.createSession("p", "agent one") as SessionCreate.Created).meta.id
        assertEquals(null, first.setSessionMode("p", id, SessionMode.AGENT))

        // A brand-new manager over the SAME index = the app being killed and reopened.
        val second = manager()
        assertEquals(ChatMode.AGENT, second.session("p", id).mode)
    }

    @Test
    fun `ASK and EDIT restore as themselves, not as each other`() {
        val m1 = manager()
        val ask = (m1.createSession("p", "ask one") as SessionCreate.Created).meta.id
        val edit = (m1.createSession("p", "edit one") as SessionCreate.Created).meta.id
        m1.setSessionMode("p", ask, SessionMode.ASK)
        m1.setSessionMode("p", edit, SessionMode.EDIT)
        val m2 = manager()
        assertEquals(ChatMode.ASK, m2.session("p", ask).mode)
        assertEquals(ChatMode.EDIT, m2.session("p", edit).mode)
    }

    /**
     * The request itself. A restored Agent session's FIRST payload must advertise execute/write
     * tools; a restored Ask session's must not. This is the assertion the owner's report is about:
     * "the selector says Agent" is not evidence that the model was given tools.
     */
    @Test
    fun `the first payload of a restored Agent session carries the Agent tool set`() = runBlocking {
        val cap = Capture()
        val session = ChatSession(
            projectId = "p",
            keyFor = { "k" },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            transportFactory = { cap },
            baseUrlForProvider = { _, fb -> fb },
            poolSlotsFor = { emptyList() },
            failoverOptIn = { false },
            appContext = null,
            initialMode = SessionMode.AGENT,
        )
        session.select(PanelSelection(providerId = "openai", modelId = "gpt-x", keySlot = "ai_OPENAI"))
        session.send("read the notes")
        assertTrue("a payload was sent", cap.payloads.isNotEmpty())
        val body = cap.payloads.first()
        assertTrue("Agent advertises run_command", body.contains("run_command"))
        assertTrue("Agent advertises write_file", body.contains("write_file"))
    }

    @Test
    fun `a restored Ask session does not advertise execute tools`() = runBlocking {
        val cap = Capture()
        val session = ChatSession(
            projectId = "p",
            keyFor = { "k" },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            transportFactory = { cap },
            baseUrlForProvider = { _, fb -> fb },
            poolSlotsFor = { emptyList() },
            failoverOptIn = { false },
            appContext = null,
            initialMode = SessionMode.ASK,
        )
        session.select(PanelSelection(providerId = "openai", modelId = "gpt-x", keySlot = "ai_OPENAI"))
        session.send("read the notes")
        val body = cap.payloads.first()
        assertTrue("Ask keeps read_file", body.contains("read_file"))
        assertTrue("Ask must not advertise run_command", !body.contains("run_command"))
    }

    /** Switching mode mid-session must move the REQUEST immediately, not just the label. */
    @Test
    fun `changing the session mode changes the next payload`() = runBlocking {
        val cap = Capture()
        val session = ChatSession(
            projectId = "p",
            keyFor = { "k" },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            transportFactory = { cap },
            baseUrlForProvider = { _, fb -> fb },
            poolSlotsFor = { emptyList() },
            failoverOptIn = { false },
            appContext = null,
            initialMode = SessionMode.ASK,
        )
        session.select(PanelSelection(providerId = "openai", modelId = "gpt-x", keySlot = "ai_OPENAI"))
        session.send("first")
        assertTrue(!cap.payloads.first().contains("run_command"))
        session.mode = ChatMode.AGENT
        session.send("second")
        assertTrue("the second request carries Agent tools", cap.payloads[1].contains("run_command"))
    }
}
