package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Section 6 — what the approval row SAYS.
 *
 * The failure worth guarding is misdescription: a card that reads "Write file / secret.env"
 * while the pending operation is a shell command would be worse than no card, because the
 * user's approval would be based on the wrong facts.
 */
class ApprovalPresentationTest {

    @Test
    fun aKnownToolGetsAVerbPhraseNotItsRawName() {
        val v = ApprovalPresentation.describe("write_file", "path: src/Main.kt")
        assertEquals("Write file", v.title)
    }

    @Test
    fun anUnknownToolDegradesToItsRawNameInsteadOfInventingAFriendlyOne() {
        val v = ApprovalPresentation.describe("mystery_tool", "something")
        assertEquals("mystery_tool", v.title)
    }

    @Test
    fun aQuotedArgumentBecomesTheTargetPill() {
        val v = ApprovalPresentation.describe("install_package", "package \"rust-analyzer\" via apt")
        assertEquals("rust-analyzer", v.target)
    }

    @Test
    fun aPathArgumentBecomesTheTargetPill() {
        val v = ApprovalPresentation.describe("write_file", "/root/proj/src/main.rs")
        assertEquals("/root/proj/src/main.rs", v.target)
    }

    @Test
    fun aShellCommandNeverGetsAPill() {
        // Run 3451 caught this: the pill took the FIRST token, so a build showed a pill
        // reading "npm" - a target that describes nothing, inviting approval on the wrong
        // fact. A command is not a target at all, short or long.
        assertNull(ApprovalPresentation.targetOf("run_command", "npm run build -- --watch --debug"))
        assertNull("even a short command must not become a pill", ApprovalPresentation.targetOf("run_command", "ls -la"))
        // Other tools keep their pills, so this is not a blanket removal.
        assertEquals("src/Main.kt", ApprovalPresentation.targetOf("write_file", "src/Main.kt"))
    }

    @Test
    fun theDescriptionIsTheRealSummaryNotARewrite() {
        val summary = "path: src/Main.kt, 120 lines"
        val v = ApprovalPresentation.describe("write_file", summary)
        assertEquals(summary, v.description)
    }

    @Test
    fun anEmptySummaryStillProducesACoherentCard() {
        val v = ApprovalPresentation.describe("run_command", "   ")
        assertNull(v.target)
        assertTrue(v.description.isNotBlank())
        assertTrue("never blank", v.title.isNotBlank())
    }

    @Test
    fun anAbsurdlyLongTargetIsTruncatedWithAnEllipsis() {
        val long = "x".repeat(120)
        val target = ApprovalPresentation.targetOf("write_file", long)!!
        assertTrue(target.length <= 60)
        assertTrue(target.endsWith("\u2026"))
    }

    @Test
    fun trailingPunctuationIsTrimmedFromThePill() {
        assertEquals("src/Main.kt", ApprovalPresentation.targetOf("write_file", "src/Main.kt, then tests"))
    }

    @Test
    fun everyScopeOptionExplainsItsDurability() {
        assertEquals(3, ApprovalPresentation.scopeOptions.size)
        // The three labels must be distinguishable at a glance, not synonyms.
        val labels = ApprovalPresentation.scopeOptions.map { it.label }
        assertEquals(labels.size, labels.toSet().size)
        assertTrue(ApprovalPresentation.scopeOptions.all { it.hint.isNotBlank() })
    }

    @Test
    fun aKnownToolHasItsOwnGlyphAndAnUnknownOneFallsBack() {
        assertTrue(ApprovalPresentation.describe("run_command", "ls").glyph !=
            ApprovalPresentation.describe("mystery_tool", "x").glyph)
    }
}
