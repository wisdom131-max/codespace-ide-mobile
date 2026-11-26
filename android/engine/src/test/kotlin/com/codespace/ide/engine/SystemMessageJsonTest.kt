package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OWNER REPORT (2026-11-26): every OpenAI-compatible provider started answering HTTP 400
 * `failed to unmarshal JSON: invalid character ',' looking for beginning of value`.
 *
 * The cause was two owners of the same separator. systemMsg() returned a fragment that already
 * ended in a comma, and messagesJson() joined its parts with another one, so the array came out as
 * `[{system},,{user}]`. The existing tests all looked at substrings ("does it contain role:system",
 * "does it carry tools") and none of them ever checked that the body was valid JSON, which is why a
 * body no provider would accept passed a green suite.
 *
 * These tests assert the SHAPE, not the wording.
 */
class SystemMessageJsonTest {

    private fun body(system: String, window: List<ChatMessage> = listOf(ChatMessage("m1", ChatRole.USER, "hi"))): String =
        OpenAIAdapter().payload(window, "hi", EngineConfig("m1").copy(systemInstruction = system))

    private fun messagesOf(json: String): String {
        val start = json.indexOf("\"messages\":[")
        assertTrue("no messages array in: " + json, start >= 0)
        val open = json.indexOf('[', start)
        var depth = 0
        var i = open
        while (i < json.length) {
            when (json[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return json.substring(open, i + 1)
                }
            }
            i++
        }
        throw AssertionError("unterminated messages array: " + json)
    }

    @Test
    fun `a system message and a turn are separated by exactly one comma`() {
        val msgs = messagesOf(body("You are VN Code."))
        assertFalse("double comma in " + msgs, msgs.contains(",,"))
        assertFalse("trailing comma in " + msgs, msgs.contains(",]"))
        assertEquals(2, msgs.split("{\"role\"").size - 1)
    }

    @Test
    fun `a blank instruction leaves a one element array with no trailing comma`() {
        val msgs = messagesOf(body(""))
        assertFalse("trailing comma in " + msgs, msgs.contains(",]"))
        assertFalse("double comma in " + msgs, msgs.contains(",,"))
    }

    @Test
    fun `the attachment path has the same shape`() {
        val json = OpenAIAdapter().payload(
            listOf(ChatMessage("m1", ChatRole.USER, "hi")), "hi",
            listOf(ChatAttachment("a", "notes.txt", "text/plain", "", "hello")),
            EngineConfig("m1").copy(systemInstruction = "You are VN Code."),
        )
        val msgs = messagesOf(json)
        assertFalse("double comma in " + msgs, msgs.contains(",,"))
        assertFalse("trailing comma in " + msgs, msgs.contains(",]"))
    }

    @Test
    fun `tools are spliced inside the object, never after it`() {
        val cfg = EngineConfig("m1").copy(
            systemInstruction = "You are VN Code.",
            toolDeclarations = listOf(ToolDeclaration(name = "read_file", description = "Read a file")),
        )
        val json = OpenAIAdapter().withTools(OpenAIAdapter().payload(listOf(ChatMessage("m1", ChatRole.USER, "hi")), "hi", cfg), cfg)
        assertTrue("tools missing", json.contains("\"tools\":["))
        assertTrue("body does not end with the object close", json.endsWith("}"))
        assertFalse("double comma before tools", json.contains(",,", ignoreCase = false))
        // The property is "inside the object", so assert exactly that. The old tail arithmetic
        // assumed a "}," existed in the body; when none does, lastIndexOf returns -1 and the
        // assertion failed on a correct payload.
        val toolsAt = json.indexOf("\"tools\":[")
        assertTrue("tools are spliced inside the object, not after it", toolsAt in 0 until json.lastIndexOf('}'))
    }
}
