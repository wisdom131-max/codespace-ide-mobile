package com.codespace.ide.engine

/**
 * PROJECT NINE 9.24 (catalog 79) - stop input that cannot be sent before it is sent.
 *
 * The guardrail decided about injection but nothing checked whether the text was sendable at all.
 * A pasted binary blob or a string of control characters goes out, fails at the provider, and
 * arrives back as an unhelpful error that costs a round.
 *
 * Refusing here is cheap and the message is specific.
 */
object InputLimits {

    data class Verdict(val ok: Boolean, val reason: String)

    fun check(text: String, maxChars: Int = 200_000): Verdict {
        if (text.isBlank()) return Verdict(false, "there is nothing to send")
        if (text.length > maxChars) {
            return Verdict(false, "that is longer than " + maxChars + " characters")
        }
        for (ch in text) {
            if (ch == '\u0000') return Verdict(false, "that contains binary data")
            if (ch < ' ' && ch != '\n' && ch != '\r' && ch != '\t') {
                return Verdict(false, "that contains control characters")
            }
        }
        return Verdict(true, "")
    }
}
