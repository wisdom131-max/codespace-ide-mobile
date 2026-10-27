package com.codespace.ide.chat.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A(a) token consistency: android/chat-tokens.json is the SINGLE source; the
 * checked-in generated outputs (ChatTokens.kt + chat-tokens.css) must match it.
 * If anyone hand-edits an output or changes the JSON without regenerating,
 * this fails. Read from the repo tree, no Android/Compose classes needed.
 */
class ChatTokensConsistencyTest {

    private val colorFields = listOf(
        "bg", "surface", "surfaceAlt", "border", "text", "textSecondary",
        "accent", "success", "warning", "error", "errorBg", "warningBg", "streamCursor"
    )

    /** Gradle runs unit tests with the module dir as CWD; cover repo-root too. */
    private fun findRepoFile(relative: String): File {
        val candidates = listOf(File(relative), File("../$relative"), File("../../$relative"))
        return candidates.firstOrNull { it.exists() }
            ?: error("cannot locate $relative from ${File(".").absolutePath} — regenerate via scripts/generate_chat_tokens.py")
    }

    private fun json(): JSONObject {
        val f = findRepoFile("android/chat-tokens.json")
        return JSONObject(f.readText())
    }

    private fun kt(): String =
        findRepoFile("src/main/java/com/codespace/ide/chat/ui/ChatTokens.kt").readText()

    private fun css(): String =
        findRepoFile("src/main/assets/chat-tokens.css").readText()

    @Test
    fun generatedKtCarriesTheGenerationHeader() {
        val t = kt()
        assertTrue("ChatTokens.kt must be generated (header missing)", t.contains("GENERATED from android/chat-tokens.json"))
        assertTrue("ChatTokens.kt must warn against hand edits", t.contains("DO NOT EDIT BY HAND"))
    }

    @Test
    fun generatedColorsMatchTheTokenSource() {
        val pal = json().getJSONObject("palette")
        val ktText = kt()
        for (theme in listOf("dark", "light")) {
            val p = pal.getJSONObject(theme)
            for (k in colorFields) {
                val hex = p.getString(k)
                val expected = "Color(0xFF" + hex.removePrefix("#").uppercase() + ")"
                assertTrue(
                    "theme $theme field $k: $expected missing from ChatTokens.kt — run scripts/generate_chat_tokens.py",
                    ktText.contains(expected)
                )
            }
        }
    }

    @Test
    fun cssMatchesTheTokenSource() {
        val pal = json().getJSONObject("palette")
        val cssText = css()
        for (theme in listOf("dark", "light")) {
            val p = pal.getJSONObject(theme)
            for (k in colorFields) {
                val expected = "--chat-" + k.lowercase() + ": " + p.getString(k) + ";"
                assertTrue(
                    "theme $theme field $k: '$expected' missing from chat-tokens.css — run scripts/generate_chat_tokens.py",
                    cssText.contains(expected)
                )
            }
        }
    }

    @Test
    fun mandatoryUiRulesHoldInTokens() {
        val t = json()
        val r = t.getJSONObject("radius")
        val sp = t.getJSONObject("spacing")
        assertEquals("panel padding horizontal is 12dp by standing UI rule", 12, sp.getInt("padHorizontal"))
        assertEquals("panel padding vertical is 10dp by standing UI rule", 10, sp.getInt("padVertical"))
        for (k in listOf("panel", "chip")) {
            val radius = r.getInt(k)
            assertTrue("radius.$k must stay within the mandated 8-12dp (got $radius)", radius in 8..12)
        }
        // The generated Kotlin mirrors the same numbers.
        val ktText = kt()
        assertTrue("RADIUS constants missing from ChatTokens.kt", ktText.contains("const val RADIUS_PANEL = " + r.getInt("panel")))
        assertTrue("PADDING constants missing from ChatTokens.kt", ktText.contains("const val PAD_HORIZONTAL = " + sp.getInt("padHorizontal")))
    }
}
