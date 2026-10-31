package com.codespace.ide.editor

import com.codespace.ide.lsp.CodeActionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The editor chip's behaviour, verified without a device (owner rule: judgement in pure Kotlin,
 * UI in the composable).
 */
class EditorActionMenuSpecTest {

    // ── Menu contents ───────────────────────────────────────────────────────

    @Test
    fun `fix with ai is always the first row`() {
        val withError = EditorActionMenuSpec.entries(hasDiagnostic = true)
        val withoutError = EditorActionMenuSpec.entries(hasDiagnostic = false)
        assertEquals(EditorActionMenuSpec.FIX_WITH_AI, withError.first().kind)
        assertEquals(EditorActionMenuSpec.FIX_WITH_AI, withoutError.first().kind)
    }

    @Test
    fun `fix with ai is disabled with a reason when there is no error`() {
        val fix = EditorActionMenuSpec.entries(hasDiagnostic = false).first()
        assertFalse(fix.enabled)
        assertEquals("No error on this line", fix.reason)
    }

    @Test
    fun `the menu is ai only and every ai action is present`() {
        val kinds = EditorActionMenuSpec.entries(hasDiagnostic = true).map { it.kind }
        assertTrue(kinds.all { it.startsWith("ai.") })
        assertEquals(1 + EditorActionMenuSpec.AI_ACTIONS.size, kinds.size)
        assertTrue(kinds.contains(CodeActionKind.AIRefactor))
        assertTrue(kinds.contains(CodeActionKind.AIExplain))
    }

    @Test
    fun `language server kinds never leak into the ai menu`() {
        val kinds = EditorActionMenuSpec.entries(hasDiagnostic = true).map { it.kind }
        assertFalse(kinds.contains(CodeActionKind.QuickFix))
        assertFalse(kinds.any { it.startsWith("source.") })
    }

    // ── Chip state ──────────────────────────────────────────────────────────

    @Test
    fun `state cycles solid ghost hidden and back`() {
        assertEquals(EditorChipState.GHOST, EditorActionMenuSpec.nextState(EditorChipState.SOLID))
        assertEquals(EditorChipState.HIDDEN, EditorActionMenuSpec.nextState(EditorChipState.GHOST))
        assertEquals(EditorChipState.SOLID, EditorActionMenuSpec.nextState(EditorChipState.HIDDEN))
    }

    @Test
    fun `each state is fainter than the one before it`() {
        assertTrue(EditorActionMenuSpec.alpha(EditorChipState.SOLID) > EditorActionMenuSpec.alpha(EditorChipState.GHOST))
        assertTrue(EditorActionMenuSpec.alpha(EditorChipState.GHOST) > EditorActionMenuSpec.alpha(EditorChipState.HIDDEN))
    }

    @Test
    fun `hidden shrinks to a findable handle`() {
        assertTrue(EditorActionMenuSpec.diameterDp(EditorChipState.HIDDEN) < 20)
        assertEquals(36, EditorActionMenuSpec.diameterDp(EditorChipState.SOLID))
    }

    // ── Visibility ──────────────────────────────────────────────────────────

    @Test
    fun `the keyboard being open hides the chip`() {
        assertFalse(
            EditorActionMenuSpec.shouldShow(
                state = EditorChipState.SOLID,
                hasSelection = true,
                hasWordUnderCursor = true,
                hasNearbyDiagnostic = true,
                imeVisible = true,
            )
        )
    }

    @Test
    fun `a bare caret with nothing under it shows no chip`() {
        assertFalse(
            EditorActionMenuSpec.shouldShow(
                state = EditorChipState.SOLID,
                hasSelection = false,
                hasWordUnderCursor = false,
                hasNearbyDiagnostic = false,
                imeVisible = false,
            )
        )
    }

    @Test
    fun `a selection or an error is enough to show the chip`() {
        assertTrue(
            EditorActionMenuSpec.shouldShow(EditorChipState.SOLID, true, false, false, false)
        )
        assertTrue(
            EditorActionMenuSpec.shouldShow(EditorChipState.SOLID, false, false, true, false)
        )
    }

    @Test
    fun `the hidden handle is always drawn so it can be brought back`() {
        assertTrue(
            EditorActionMenuSpec.shouldShow(EditorChipState.HIDDEN, false, false, false, false)
        )
    }

    // ── Placement ───────────────────────────────────────────────────────────

    @Test
    fun `a drag outside the editor is clamped inside it`() {
        val (x, y) = EditorActionMenuSpec.clamp(-500f, 9000f, 36, 400f, 800f)
        assertTrue(x >= 0f && x <= 400f)
        assertTrue(y >= 0f && y <= 800f)
    }

    @Test
    fun `release snaps to the nearer vertical edge`() {
        val nearLeft = EditorActionMenuSpec.snapped(20f, 100f, 36, 400f, 800f)
        assertEquals(EditorActionMenuSpec.EDGE_MARGIN_DP, nearLeft.first, 0.01f)
        val nearRight = EditorActionMenuSpec.snapped(330f, 100f, 36, 400f, 800f)
        assertTrue(nearRight.first > 300f)
        assertEquals(100f, nearRight.second, 0.01f)
    }

    // ── Prompts ─────────────────────────────────────────────────────────────

    private fun ctx(hasSelection: Boolean = true, diagnostic: String? = null, line: Int? = null) =
        EditorActionMenuSpec.contextFor(
            text = "package a\n\nimport b\n\nfun x() { y() }\n",
            selectionStart = if (hasSelection) 22 else 30,
            selectionEnd = if (hasSelection) 32 else 30,
            fileName = "/tmp/project/File.kt",
            languageName = "Kotlin",
            diagnosticMessage = diagnostic,
            diagnosticLine = line,
        )

    @Test
    fun `the header names the file the language and the imports`() {
        val header = EditorActionMenuSpec.header(ctx())
        assertTrue(header.contains("File: File.kt"))
        assertTrue(header.contains("Kotlin"))
        assertTrue(header.contains("import b"))
        assertTrue(header.contains("selected text"))
    }

    @Test
    fun `a bare caret falls back to the current line not the whole file`() {
        val c = ctx(hasSelection = false)
        assertFalse(c.hasSelection)
        assertFalse(c.selectionText.contains("\n"))
    }

    @Test
    fun `code producing actions ask for a staged write`() {
        listOf(
            EditorActionMenuSpec.FIX_WITH_AI,
            CodeActionKind.AIRefactor,
            CodeActionKind.AIGenerateTests,
            CodeActionKind.AIAddComments,
        ).forEach { kind ->
            assertTrue(
                "expected staging instruction for " + kind,
                EditorActionMenuSpec.build(kind, ctx()).contains("write_file"),
            )
        }
    }

    @Test
    fun `explain stays in chat and is never asked to write a file`() {
        val prompt = EditorActionMenuSpec.build(CodeActionKind.AIExplain, ctx())
        assertFalse(prompt.contains("write_file"))
        assertTrue(prompt.contains("Explain this code"))
    }

    @Test
    fun `the fix prompt carries the error and its line`() {
        val prompt = EditorActionMenuSpec.build(
            EditorActionMenuSpec.FIX_WITH_AI,
            ctx(diagnostic = "Unresolved reference: y", line = 5),
        )
        assertTrue(prompt.contains("Error on line 5: Unresolved reference: y"))
        assertTrue(prompt.contains("Fix this error"))
    }
}
