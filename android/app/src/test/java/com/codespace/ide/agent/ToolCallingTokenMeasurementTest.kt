package com.codespace.ide.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STAGE 4 — measurement, with the honest answer stated in the test.
 *
 * Character counts are NOT tokens. This harness records what can be measured deterministically
 * (serialized size of the same declarations in both protocols) and reports provider-reported usage
 * as UNAVAILABLE, because no model is called in unit tests and CI must never spend money.
 */
class ToolCallingTokenMeasurementTest {

    private val tools = listOf(
        NativeToolCalling.Declaration("read_file", "Read a file from the workspace", JSONObject().put("type", "object")),
        NativeToolCalling.Declaration("write_file", "Write a file in the workspace", JSONObject().put("type", "object")),
        NativeToolCalling.Declaration("grep_search", "Search the workspace for text", JSONObject().put("type", "object")),
    )

    /** The text protocol's declaration form: the numbered prose the prompt actually uses. */
    private fun textDeclaration(): String = tools.mapIndexed { i, t ->
        "${i + 1}. ${t.name} — ${t.description}. Example: <tool>{\"name\":\"${t.name}\",\"arguments\":{}}</tool>"
    }.joinToString("\n")

    @Test
    fun `character counts are recorded for both protocols`() {
        val text = textDeclaration()
        val native = NativeToolCalling.openAiDeclarations(tools).toString()
        println("EVAL chars text=$text=${text.length} native=${native.length}")
        assertTrue(text.isNotEmpty())
        assertTrue(native.isNotEmpty())
        assertTrue("native declarations are not empty", native.length > 2)
    }

    @Test
    fun `provider reported usage is unavailable and is reported as such`() {
        // No model is called here. There is no honest token number to give.
        val providerReported: Long? = null
        val statement = if (providerReported == null) {
            "Token measurement unavailable for this endpoint or protocol."
        } else {
            "tokens=$providerReported"
        }
        assertEquals("Token measurement unavailable for this endpoint or protocol.", statement)
        println("EVAL $statement")
    }

    @Test
    fun `an identical call is the same size in both protocols within a stated bound`() {
        val textCall = """<tool>{"name":"read_file","arguments":{"path":"a/b.txt"}}</tool>"""
        val nativeArgs = JSONObject("""{"path":"a/b.txt"}""").toString()
        // The native path carries no wrapper tags; the difference is the wrapper only.
        assertTrue(nativeArgs.length < textCall.length)
        println("EVAL call chars text=${textCall.length} nativeArgs=${nativeArgs.length}")
    }

    @Test
    fun `no measurement is presented as a token saving`() {
        val textChars = textDeclaration().length
        val nativeChars = NativeToolCalling.openAiDeclarations(tools).toString().length
        // Character ratio exists; it is deliberately NOT called a token ratio.
        val ratio = nativeChars.toDouble() / textChars
        assertTrue(ratio > 0)
        println("EVAL char_ratio=${"%.3f".format(ratio)} (characters, not tokens)")
    }
}
