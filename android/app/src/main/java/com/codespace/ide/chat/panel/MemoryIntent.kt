package com.codespace.ide.chat.panel

/**
 * PHASE 6c (2026-11-15): recognise the three things a user can say about memory, and route them.
 *
 * Why it exists: "remember that I deploy on Fridays" is a request to WRITE a memory, but it read
 * exactly like ordinary prose, so the answer was a friendly sentence and nothing was stored. The
 * keyword map in ToolOffer could offer `save_memory`, but offering a tool is not the same as
 * telling the model that this message is a directive.
 *
 * Deliberately narrow: only explicit phrasing counts. A message that merely contains the word
 * "remember" ("I remember when the build broke") is NOT a directive, because routing it would put
 * a fake memory in the store and a confirmation in the transcript that nothing supports.
 *
 * Pure, so the wording rules are tested without a session.
 */
internal object MemoryIntent {

    enum class Kind { REMEMBER, FORGET, RECALL, NONE }

    private val forgetPhrases = listOf(
        "forget that", "forget about", "forget the memory", "stop remembering",
        "delete the memory", "remove that memory",
    )
    private val rememberPhrases = listOf(
        "remember that", "remember this", "please remember", "keep in mind",
        "save this to memory", "save that to memory", "note that for later", "don't forget that",
    )
    private val recallPhrases = listOf(
        "what do you remember", "do you remember", "what have you remembered",
        "recall what", "what did i tell you to remember",
    )

    fun classify(text: String): Kind {
        val t = text.lowercase().replace("  ", " ").trim()
        if (t.isEmpty()) return Kind.NONE
        if (forgetPhrases.any { t.contains(it) }) return Kind.FORGET
        if (rememberPhrases.any { t.contains(it) }) return Kind.REMEMBER
        if (recallPhrases.any { t.contains(it) }) return Kind.RECALL
        return Kind.NONE
    }

    /**
     * The line added to this turn's workspace block. Null for NONE, so an ordinary message carries
     * nothing extra. Each line names the exact tool, because "use memory" is not an instruction a
     * model can follow.
     */
    fun directive(kind: Kind): String? = when (kind) {
        Kind.REMEMBER ->
            "MEMORY DIRECTIVE: this message asks you to REMEMBER something. Call save_memory with " +
                "the fact stated here, then confirm what you stored in one sentence. Do not claim " +
                "it is remembered unless the tool result says so."
        Kind.FORGET ->
            "MEMORY DIRECTIVE: this message asks you to FORGET something. Call delete_memory for " +
                "the matching entry, then say plainly what was removed. Never report a deletion " +
                "the tool did not confirm."
        Kind.RECALL ->
            "MEMORY DIRECTIVE: this message asks what you remember. Call read_memory first and " +
                "answer from what it returns, naming nothing you did not read."
        Kind.NONE -> null
    }
}
