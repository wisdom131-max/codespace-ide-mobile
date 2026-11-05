package com.codespace.ide.chat.panel

/**
 * 2026-11-05: the editor's sparkle sent a SENTINEL, not an instruction.
 *
 * `RefactorMenu` offers "✨ Refactor with AI" and hands `"__refactor_ai__"` to the same handoff the
 * AI code actions use. The shell then opened the chat and auto-sent that literal string, so the
 * owner's message in the transcript read `__refactor_ai__` and the model had nothing to act on.
 * Every other entry point (chip menu, code actions) builds its prompt with chipPrompt(); only this
 * one skipped it.
 *
 * Kept as a pure function so the substitution is testable, and resolved in the shell rather than in
 * CodeEditor, whose composable is at the JVM 64KB method ceiling (CI 3552).
 */
internal object EditorAiPrompts {

    const val REFACTOR_AI = "__refactor_ai__"

    /**
     * Turn a placeholder into a real instruction. Anything that is already a prompt passes
     * through untouched, so callers cannot lose wording by routing through here.
     */
    fun resolve(prompt: String, activeFilePath: String?): String {
        if (prompt != REFACTOR_AI) return prompt
        val where = activeFilePath?.takeIf { it.isNotBlank() }?.substringAfterLast('/')
        if (where.isNullOrBlank()) {
            return "Propose a refactor for the file I have open. Ask me which file if you cannot " +
                "tell. Do not change any files yet."
        }
        return "Read " + where + " and propose the refactor that would most improve it. " +
            "Say what you would change and why, in order, and do not change any files yet."
    }
}
