package com.codespace.ide.chat.keys

/**
 * KEY SANITIZATION: the difference between "the key is wrong" and "the key we stored is wrong".
 *
 * Owners paste keys from a password manager, a chat message, a PDF, a screenshot OCR. All four
 * introduce the same damage: surrounding quotes, a stray "Authorization:" or "Bearer " prefix, a
 * smart quote from a phone keyboard, a zero-width character from a web page, or a line break where
 * the reader wrapped. Every one of those produces a key that LOOKS right on screen and is rejected
 * by the provider, which is the most expensive kind of bug for the person holding the phone.
 *
 * So: normalise before storing, and tell the owner what was changed. Never guess a DIFFERENT key -
 * the only transformations here are removal of packaging, never of content.
 */
object KeySanitizer {

    /** Longest accepted key. Real keys are well under this; anything longer is a pasted document. */
    const val MAX_LENGTH = 4096

    data class Result(
        val key: String,
        /** Human-readable list of what was removed. Empty when the value was already clean. */
        val changes: List<String>,
        /** Non-null when the value cannot be used AS IS and the owner must fix something. */
        val problem: String? = null,
    ) {
        val cleaned: Boolean get() = changes.isNotEmpty()
        val usable: Boolean get() = problem == null && key.isNotEmpty()
    }

    private val SMART_QUOTES = setOf('\u2018', '\u2019', '\u201C', '\u201D', '"', '\'')

    fun sanitize(raw: String?): Result {
        if (raw == null || raw.isBlank()) return Result("", emptyList(), "No key was entered.")
        val changes = ArrayList<String>()
        // Non-null copy: Kotlin does not smart-cast a `var`, so the whole body would type as String?
        var v: String = raw

        // 1. Invisible characters. A zero-width space in the middle of a key is invisible on screen
        // and fatal on the wire.
        val beforeInvisible = v
        v = v.filterNot { it == '\u200B' || it == '\u200C' || it == '\u200D' || it == '\uFEFF' }
        if (v != beforeInvisible) changes.add("removed invisible characters")

        // 2. Line breaks and tabs from a wrapped paste become nothing: a key is one token.
        if (v.any { it == '\n' || it == '\r' || it == '\t' }) {
            v = v.replace("\r", "").replace("\n", "").replace("\t", "")
            changes.add("joined a wrapped paste into one line")
        }

        v = v.trim()
        if (v.length > MAX_LENGTH) {
            return Result("", changes, "That looks like more than a key (" + v.length +
                " characters). Paste only the key itself.")
        }

        // 3. A header line pasted whole: "Authorization: Bearer sk-..."
        val header = Regex("""(?i)^\s*(authorization|x-api-key|api-key|x-goog-api-key)\s*[:=]\s*""").find(v)
        if (header != null) {
            v = v.substring(header.range.last + 1)
            changes.add("removed the '" + header.groupValues[1] + ":' prefix")
        }

        // 4. "Bearer ..." - we add that ourselves at send time, so a stored copy would be doubled.
        val bearer = Regex("""(?i)^bearer\s+""").find(v)
        if (bearer != null) {
            v = v.substring(bearer.range.last + 1)
            changes.add("removed the 'Bearer' prefix")
        }

        // 5. Quoting from a manager or a phone keyboard.
        v = v.trim()
        while (v.length >= 2 && v.first() in SMART_QUOTES && v.last() in SMART_QUOTES) {
            v = v.substring(1, v.length - 1).trim()
            changes.add("removed surrounding quotes")
        }

        // "Bearer" alone (or a bare header name) survives the prefix rules because trimming ate the
        // space the rule needs. It is packaging with nothing in it, never a key.
        val packagingOnly = setOf("bearer", "authorization", "authorization:", "x-api-key", "api-key")
        if (v.isEmpty() || v.lowercase() in packagingOnly) {
            return Result("", changes, "That contained only packaging, no key.")
        }

        // 6. What is left must be one unbroken token. A space or a comma here means two things were
        // pasted, and silently keeping the first would look like "the key stopped working".
        val offending = v.any { it.isWhitespace() || it == ',' }
        if (offending) {
            return Result(v, changes, "That contains a space or a comma, so it looks like more than " +
                "one value was pasted. Paste only the key.")
        }
        return Result(v, changes, null)
    }

    /**
     * The slot that already holds THIS SAME value, so one key is stored once and "which key is this
     * provider using" has a single answer. Compares values, never "any other slot is filled".
     */
    fun duplicateOf(value: String, slot: String, slots: List<Pair<String, String?>>): String? {
        if (value.isBlank()) return null
        return slots.firstOrNull { (name, stored) -> name != slot && stored == value }?.first
    }
}
