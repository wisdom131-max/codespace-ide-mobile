package com.codespace.ide.chat.panel

import com.codespace.ide.chat.ui.ChatRowModel
import com.codespace.ide.engine.ChatTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP2 (d-min) JVM tests: engine-event -> ChatRowModel mapping through the
 * session (rows, streaming flags, over-budget "Send anyway?", cancel keeps
 * partial text, no-key honest card). No network: a scripted ChatTransport.
 */
class ChatSessionMappingTest {

    private class ScriptedTransport(private val chunks: List<String>) : ChatTransport {
        var payloads = ArrayList<String>()
        override fun stream(url: String, headers: Map<String, String>, payloadJson: String): Flow<String> {
            payloads.add(payloadJson)
            return flowOf(*chunks.toTypedArray())
        }
    }

    private class NeverEndingTransport : ChatTransport {
        var payloads = ArrayList<String>()
        override fun stream(url: String, headers: Map<String, String>, payloadJson: String): Flow<String> = flow {
            payloads.add(payloadJson)
            emit("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"par\"}]}]}}\n\n")
            delay(60_000)
        }
    }

    private val geminiChunks = listOf(
        "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hel\"}]}]}}\n\n",
        "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"lo!\"}]}]}}\n\n",
        "data: {\"candidates\":[{\"finishReason\":\"STOP\"}]}\n\n",
    )

    private fun session(transport: ChatTransport, key: String? = "k-test") =
        ChatSession("proj", { if (it == "ai_google") key else null },
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), { transport })

    private fun awaitIdle(s: ChatSession) = runBlocking {
        var spins = 0
        while (s.ui.value.streaming && spins < 400) { delay(10); spins++ }
    }

    @Test
    fun mapsDeltasToStreamRowAndCompletes() {
        val t = ScriptedTransport(geminiChunks)
        val s = session(t)
        s.send("hi")
        awaitIdle(s)
        val rows = s.ui.value.rows
        assertEquals(2, rows.size)
        assertTrue(rows[0] is ChatRowModel.User)
        val done = rows[1] as ChatRowModel.AssistantStream
        assertEquals("Hello!", done.text)
        assertFalse(done.cursorOn)
        assertFalse(s.ui.value.streaming)
    }

    @Test
    fun cancelKeepsPartialTextWithCursorOn() {
        val t = NeverEndingTransport()
        val s = session(t)
        s.send("hi")
        runBlocking {
            var spins = 0
            while (s.ui.value.firstDeltaMs == 0L && spins < 400) { delay(10); spins++ }
        }
        s.cancel()
        awaitIdle(s)
        val last = s.ui.value.rows.last() as ChatRowModel.AssistantStream
        assertEquals("par", last.text)
        assertTrue("cancelled partial keeps the live cursor", last.cursorOn)
        assertFalse(s.ui.value.streaming)
    }

    @Test
    fun overBudgetCardThenSendAnywayOverridesOnce() {
        val t = ScriptedTransport(geminiChunks)
        val s = session(t)
        s.send("x".repeat(60_000))
        awaitIdle(s)
        val over = s.ui.value.rows.last()
        assertTrue("over-budget shows the Send-anyway card", over is ChatRowModel.OverBudget)
        s.sendAnyway()
        awaitIdle(s)
        assertTrue("override reached the transport", t.payloads.isNotEmpty() && t.payloads.last().contains("x"))
        val last = s.ui.value.rows.last() as ChatRowModel.AssistantStream
        assertEquals("Hello!", last.text)
    }

    @Test
    fun missingKeyShowsHonestCardWithoutNetwork() {
        val t = ScriptedTransport(geminiChunks)
        val s = session(t, key = null)
        s.send("hi")
        awaitIdle(s)
        val last = s.ui.value.rows.last() as ChatRowModel.Failed
        assertTrue(last.verdict.startsWith("No key stored"))
        assertTrue("no network without a key", t.payloads.isEmpty())
    }
}

/** WP6: opt-in failover behavior - visible switch note, next key used, no silent retry. */
class KeyFailoverMappingTest {

    private class AuthFailOnceTransport : ChatTransport {
        var calls = 0
        override fun stream(url: String, headers: Map<String, String>, payloadJson: String): Flow<String> = flow {
            calls++
            if (calls == 1) throw com.codespace.ide.engine.TransportHttpException(401, emptyMap(), "denied")
            emit("data: {\"choices\":[{\"delta\":{\"content\":\"Hello!\"}}]}\n\n")
        }
    }

    private val openAiChunks = listOf(
        "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n",
        "data: {\"choices\":[{\"delta\":{\"content\":\"lo!\"}}]}\n\n",
        "data: [DONE]\n\n",
    )

    private fun session(optIn: Boolean, keySlotsUsed: MutableList<String>): ChatSession {
        val t = AuthFailOnceTransport()
        val s = ChatSession(
            "proj",
            { slot -> keySlotsUsed.add(slot); if (slot == "ai_openai" || slot == "ai_openai_2") "k-" + slot else null },
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            { t },
            poolSlotsFor = { pid -> if (pid == "openai") listOf("ai_openai", "ai_openai_2") else emptyList() },
            failoverOptIn = { optIn },
        )
        s.select(PanelSelection(providerId = "openai", keySlot = "ai_openai",
            modelId = "gpt-test", baseUrl = "http://127.0.0.1:1/chat/completions"))
        return s
    }

    @Test
    fun failoverOffByDefaultShowsFailureWithoutRetry() {
        val used = ArrayList<String>()
        val s = session(optIn = false, keySlotsUsed = used)
        s.send("hi")
        runBlocking { var n = 0; while (s.ui.value.streaming && n < 400) { delay(10); n++ } }
        val last = s.ui.value.rows.last()
        assertTrue("no failover: plain failure card", last is ChatRowModel.Failed)
        assertEquals(listOf("ai_openai"), used)
    }

    @Test
    fun optInFailoverSwitchesKeyVisiblyAndCompletes() {
        val used = ArrayList<String>()
        val s = session(optIn = true, keySlotsUsed = used)
        s.send("hi")
        runBlocking { var n = 0; while (s.ui.value.streaming && n < 400) { delay(10); n++ } }
        // Second attempt used the next stored key.
        assertEquals(listOf("ai_openai", "ai_openai_2"), used)
        val note = s.ui.value.rows.filterIsInstance<ChatRowModel.InfoNote>().singleOrNull()
        assertTrue("switch is visible, not silent", note != null)
        val done = s.ui.value.rows.last() as ChatRowModel.AssistantStream
        assertEquals("Hello!", done.text)
        assertFalse(done.cursorOn)
    }

    @Test
    fun chooseNextSlotTakesFirstUnusedOrNull() {
        assertEquals("ai_openai_2", chooseNextSlot(listOf("ai_openai_2")))
        assertNull(chooseNextSlot(emptyList()))
    }
}

/** WP8: panel-side staging — attach gate, chip row, retry keeps attachments. */
class PanelAttachmentStagingTest {

    private class CaptureTransport(var payloadRef: String? = null) : ChatTransport {
        override fun stream(url: String, headers: Map<String, String>, payloadJson: String): Flow<String> = flow {
            payloadRef = payloadJson
            emit("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}]}}\n\n")
        }
    }

    private fun sessionWith(t: CaptureTransport, provider: String = "gemini", model: String = "gemini-3.8-flash"): ChatSession {
        val s = ChatSession("proj",
            { slot -> if (slot == "ai_gemini") "k" else null },
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            { t },
            contextOverrideFor = { _, _ -> null },
        )
        s.select(PanelSelection(providerId = provider, keySlot = "ai_gemini",
            modelId = model, baseUrl = "http://127.0.0.1:1"))
        return s
    }

    @Test
    fun oversizedRefusedWithHonestNote() {
        val s = sessionWith(CaptureTransport())
        s.addPending("big.png", "image/png", "aGVsbG8=", 11L * 1024 * 1024)
        assertTrue(s.pending.value.isEmpty())
        val note = s.ui.value.rows.filterIsInstance<ChatRowModel.InfoNote>().lastOrNull()
        assertTrue(note != null && note.text.contains("10 MB"))
    }

    @Test
    fun wrongKindRefusedAndChipStages() {
        val s = sessionWith(CaptureTransport())
        s.addPending("note.txt", "text/plain", "eA==", 4L)
        assertTrue(s.pending.value.isEmpty())
        s.addPending("shot.png", "image/png", "aGVsbG8=", 5L)
        assertEquals(1, s.pending.value.size)
        assertEquals("shot.png", s.pendingChips().single().name)
    }

    @Test
    fun attachmentRidesPayloadAndRetryResendsIt() {
        val t = CaptureTransport()
        val s = sessionWith(t)
        s.addPending("shot.png", "image/png", "aGVsbG8=", 5L)
        s.send("look")
        runBlocking { var n = 0; while (s.ui.value.streaming && n < 400) { delay(10); n++ } }
        assertTrue(t.payloadRef!!.contains("aGVsbG8="))
        assertTrue(t.payloadRef!!.contains("inline_data"))
        // chips row shown for the sent message; pending cleared
        assertTrue(s.ui.value.rows.any { it is ChatRowModel.Attachments })
        assertTrue(s.pending.value.isEmpty())
        // retry re-sends the staged attachment rather than dropping it
        t.payloadRef = null
        s.retry()
        runBlocking { var n = 0; while (s.ui.value.streaming && n < 400) { delay(10); n++ } }
        assertTrue(t.payloadRef!!.contains("aGVsbG8="))
    }
}

/** WP9: text/context attachments + the credential-name redaction gate. */
class TextAndCredentialAttachmentTest {

    private fun session(): ChatSession {
        val t = object : ChatTransport {
            override fun stream(url: String, headers: Map<String, String>, payloadJson: String): Flow<String> = flow {
                emit("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}]}}\n\n")
            }
        }
        val s = ChatSession("proj", { if (it == "ai_gemini") "k" else null },
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), { t },
            contextOverrideFor = { _, _ -> null })
        s.select(PanelSelection(providerId = "gemini", keySlot = "ai_gemini",
            modelId = "gemini-3.8-flash", baseUrl = "http://127.0.0.1:1"))
        return s
    }

    @Test
    fun credentialShapedNamesRefused() {
        val s = session()
        listOf(".env", "id_rsa", "prod.pem", "gcp-credentials.json", "app.keystore").forEach {
            s.addPending(it, "text/plain", "", 10L, "SECRET=1")
        }
        assertTrue(s.pending.value.isEmpty())
        val notes = s.ui.value.rows.filterIsInstance<ChatRowModel.InfoNote>()
        assertEquals(5, notes.size)
        assertTrue(notes.all { it.text.contains("credential file") })
    }

    @Test
    fun textFileStagesAsTextNotBase64() {
        val s = session()
        s.addPending("Main.kt", "text/plain", "", 20L, "fun main() {}")
        val staged = s.pending.value.single()
        assertEquals("fun main() {}", staged.text)
        assertEquals("", staged.base64)
    }

    @Test
    fun textKindWithoutTextStagesEmptyNotCrash() {
        val s = session()
        s.addPending("empty.md", "text/markdown", "", 0L)
        assertEquals("", s.pending.value.single().text)
    }
}
