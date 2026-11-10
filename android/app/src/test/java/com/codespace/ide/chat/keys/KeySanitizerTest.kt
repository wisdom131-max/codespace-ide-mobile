package com.codespace.ide.chat.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every case here is a paste that LOOKS correct on a phone screen and is rejected by the provider,
 * which reads to the owner as "the key is wrong". Sanitising is what stops that hunt.
 */
class KeySanitizerTest {

    @Test
    fun aCleanKeyIsUntouched() {
        val r = KeySanitizer.sanitize("sk-proj-abcdef1234567890")
        assertEquals("sk-proj-abcdef1234567890", r.key)
        assertFalse("nothing to report", r.cleaned)
        assertNull(r.problem)
    }

    @Test
    fun surroundingQuotesGo() {
        listOf("\"sk-proj-abc123\"", "'sk-proj-abc123'", "\u201Csk-proj-abc123\u201D").forEach { raw ->
            val r = KeySanitizer.sanitize(raw)
            assertEquals("sk-proj-abc123", r.key)
            assertTrue(r.clean1())
        }
    }

    private fun KeySanitizer.Result.clean1(): Boolean = changes.any { it.contains("quotes") }

    @Test
    fun aBearerPrefixIsRemovedNotDoubled() {
        val r = KeySanitizer.sanitize("Bearer sk-or-v1-abc123")
        assertEquals("sk-or-v1-abc123", r.key)
        assertTrue(r.changes.any { it.contains("Bearer") })
    }

    @Test
    fun aWholeAuthorizationHeaderIsUnwrapped() {
        val r = KeySanitizer.sanitize("authorization: Bearer sk-ant-abc123")
        assertEquals("sk-ant-abc123", r.key)
        assertTrue(r.changes.any { it.contains("authorization") })
    }

    @Test
    fun aWrappedPasteBecomesOneLine() {
        val r = KeySanitizer.sanitize("sk-proj-abc\n123def")
        assertEquals("sk-proj-abc123def", r.key)
        assertTrue(r.changes.any { it.contains("one line") })
    }

    @Test
    fun invisibleCharactersAreRemoved() {
        val r = KeySanitizer.sanitize("sk-proj-ab\u200Bc123\uFEFF")
        assertEquals("sk-proj-abc123", r.key)
        assertTrue(r.changes.any { it.contains("invisible") })
    }

    @Test
    fun twoPastedValuesAreRefusedNotTruncated() {
        val r = KeySanitizer.sanitize("sk-aaa111, sk-bbb222")
        assertFalse("do not silently keep the first: that looks like a key that stopped working", r.usable)
        assertTrue(r.problem!!.contains("more than one value"))
    }

    @Test
    fun aPastedDocumentIsRefusedByLength() {
        val r = KeySanitizer.sanitize("x".repeat(KeySanitizer.MAX_LENGTH + 1))
        assertFalse(r.usable)
        assertTrue(r.problem!!.contains("more than a key"))
    }

    @Test
    fun packagingWithoutAKeyIsRefused() {
        val r = KeySanitizer.sanitize("Bearer ")
        assertFalse(r.usable)
    }

    @Test
    fun anEmptyValueIsNotAnErrorJustEmpty() {
        assertFalse(KeySanitizer.sanitize("").usable)
        assertFalse(KeySanitizer.sanitize(null).usable)
    }

    @Test
    fun duplicatesAreFoundByValueAndOnlyForAnotherSlot() {
        val slots = listOf("ai_OPENAI" to "sk-same", "ai_ANTHROPIC" to "sk-other")
        assertEquals("ai_OPENAI", KeySanitizer.duplicateOf("sk-same", "ai_ANTHROPIC", slots))
        assertNull("the same slot is not a duplicate of itself",
            KeySanitizer.duplicateOf("sk-same", "ai_OPENAI", slots))
        assertNull(KeySanitizer.duplicateOf("sk-new", "ai_OPENAI", slots))
    }
}
