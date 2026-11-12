package com.codespace.ide.agent.facts

/**
 * FACTS — what must never become a memory.
 *
 * Two different dangers, two different checks:
 *
 *  1. CREDENTIALS. A secret in a memory is a secret in every future prompt, written to a file
 *     nobody thinks of as a secret store. The patterns are the ones `detect_secrets` already uses
 *     (one family, so an owner who trusts that tool gets the same answer here), plus a
 *     high-entropy check for opaque tokens that match no known shape.
 *  2. UNTRUSTED INSTRUCTIONS. A memory is read back INSIDE the system prompt, so anything stored
 *     becomes an instruction the model obeys. Text that reads like an instruction and did not come
 *     from the user is refused: a quoted page saying "ignore previous instructions" must never be
 *     memorised as if the owner had said it.
 *
 * Pure Kotlin so every rule is unit-tested.
 */
object FactGuard {

    private val SECRET_PATTERNS = listOf(
        Regex("AKIA[0-9A-Z]{16}") to "AWS access key",
        Regex("gh[pousr]_[A-Za-z0-9]{36,}") to "GitHub token",
        Regex("AIza[0-9A-Za-z_\\-]{35}") to "Google API key",
        Regex("AQ\\.[0-9A-Za-z_\\-]{25,}") to "Gemini auth key",
        Regex("ya29\\.[0-9A-Za-z_\\-]+") to "Google OAuth token",
        Regex("sk-[A-Za-z0-9]{20,}") to "OpenAI-style API key",
        Regex("sk-ant-[A-Za-z0-9_\\-]{20,}") to "Anthropic API key",
        Regex("xox[baprs]-[A-Za-z0-9-]+") to "Slack token",
        Regex("(sk|pk|rk)_(live|test)_[A-Za-z0-9]{16,}") to "Stripe key",
        Regex("-----BEGIN (RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----") to "private key",
        Regex("eyJ[A-Za-z0-9_\\-]{8,}\\.eyJ[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}") to "JWT token",
        Regex("(?i)(password|passwd|pwd)\\s*[=:]\\s*\\S{4,}") to "password",
        Regex("(?i)(api[_-]?key|secret[_-]?key|auth[_-]?token|access[_-]?token)\\s*[=:]\\s*['\"]?[A-Za-z0-9_\\-]{16,}['\"]?") to "API key or token",
        Regex("(?i)bearer\\s+[A-Za-z0-9_\\-.]{20,}") to "bearer token",
    )

    /** Instruction shapes that must not enter memory from an untrusted source. */
    private val INJECTION_PATTERNS = listOf(
        Regex("(?i)ignore (all |any )?(previous|prior|above|earlier) (instructions|prompts?|rules)"),
        Regex("(?i)disregard (all |any )?(previous|prior|above|earlier|the) (instructions|prompts?|rules|system)"),
        Regex("(?i)forget (all |everything )?(you were told|your instructions|previous)"),
        Regex("(?i)you are now\\b"),
        Regex("(?i)new (system )?(instructions?|prompt)\\s*[::]"),
        Regex("(?i)</?system>|<\\|im_start\\|>|<\\|im_end\\|>"),
        Regex("(?i)reveal (your|the) (system )?(prompt|instructions)"),
        Regex("(?i)print (your|the) (system )?(prompt|instructions)"),
        Regex("(?i)act as (the )?(system|developer|admin)"),
        Regex("(?i)^\\s*(system|assistant)\\s*[::]"),
    )

    /** Why [content] must not be stored, or null when it is safe. */
    fun secretReason(content: String): String? {
        for ((pattern, label) in SECRET_PATTERNS) {
            if (pattern.containsMatchIn(content)) {
                return "That looks like a $label, so it will not be saved to memory. " +
                    "Keep credentials in the secret store, never in a fact."
            }
        }
        if (looksHighEntropy(content)) {
            return "That contains a long random-looking string, which is probably a credential. " +
                "It will not be saved to memory."
        }
        return null
    }

    /**
     * A single token of 32+ characters mixing letters and digits and not a path/URL/identifier is
     * what an opaque key looks like. Thresholds are high on purpose: false positives here would
     * silently refuse a legitimate fact.
     */
    fun looksHighEntropy(content: String): Boolean {
        for (raw in content.split(' ', '\n', '\t', ',', ';')) {
            val tok = raw.trim().trim('"', '\'', '`', '.', ':', '=')
            if (tok.length < 32) continue
            if (tok.contains('/') || tok.contains('\\') || tok.startsWith("http")) continue
            val digits = tok.count { it.isDigit() }
            val letters = tok.count { it.isLetter() }
            if (digits >= 8 && letters >= 12) return true
        }
        return false
    }

    /**
     * Instruction-shaped text is only allowed from the user themselves. An inferred fact or one
     * scraped out of a document is data, and data must not become a rule.
     */
    fun injectionReason(content: String, source: FactSource): String? {
        val hit = INJECTION_PATTERNS.firstOrNull { it.containsMatchIn(content) } ?: return null
        if (source == FactSource.EXPLICIT_USER || source == FactSource.MANUAL) {
            // The owner may genuinely want to remember "always run the tests before pushing", which
            // is instruction-shaped but harmless. The dangerous shapes are refused even for them,
            // because no legitimate fact needs to tell a model to ignore its rules.
            val dangerous = hit.pattern.contains("ignore", true) ||
                hit.pattern.contains("disregard", true) ||
                hit.pattern.contains("system", true) ||
                hit.pattern.contains("reveal", true) ||
                hit.pattern.contains("print", true)
            return if (dangerous) {
                "That reads as an instruction to override the assistant's rules, so it will not be " +
                    "saved as a fact."
            } else null
        }
        return "That text is an instruction that came from something other than you " +
            "(a document, a page, a tool result), so it is not saved as a fact."
    }

    /** Everything at once: the gate every save path calls. */
    fun rejectionReason(content: String, source: FactSource): String? =
        secretReason(content) ?: injectionReason(content, source)
}
