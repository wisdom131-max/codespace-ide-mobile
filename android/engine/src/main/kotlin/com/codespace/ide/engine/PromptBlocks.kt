package com.codespace.ide.engine

/**
 * PROJECT NINE 9.48 (catalog 29 + 28 WIRED) - the prompt blocks are framed and the rules are anchored.
 *
 * Two pieces sat finished and unused. Structured delimiters (29) existed because bare headings make
 * a model guess where one block ends; a closing tag makes it unambiguous. Attention anchors (28)
 * existed because a rule buried mid-prompt gets ignored, and a short bulleted block at the end does
 * not.
 *
 * Deliberate about one thing: a rule that fits today still fits. The anchor pass runs with the same
 * character cap as the block, so wiring it in cannot silently drop a rule that used to be sent.
 */
object PromptBlocks {

    fun render(plan: String, ledger: String, rules: String, maxChars: Int = 1200): String {
        if (maxChars <= 0) return ""
        // Bullets are normalised before anchoring, so an already-bulleted rule does not become
        // "- - rule". Input that arrives bulleted comes out byte-identical.
        val anchored = AttentionAnchors.block(
            rules.lines().map { it.removePrefix("- ").trim() },
            maxRules = Int.MAX_VALUE,
            maxChars = maxChars,
        )
        val sb = StringBuilder()
        for ((name, body) in listOf("plan" to plan, "ledger" to ledger, "rules" to anchored)) {
            if (body.isBlank()) continue
            // Rendered one section at a time so the cap can drop a whole section instead of
            // slicing through a closing tag and leaving the frame broken.
            val one = ContextSections.render(listOf(name to body))
            if (sb.length + one.length > maxChars) break
            sb.append(one)
        }
        return sb.toString().trim()
    }
}
