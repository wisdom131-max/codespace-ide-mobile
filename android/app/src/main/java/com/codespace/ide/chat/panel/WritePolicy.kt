package com.codespace.ide.chat.panel

/**
 * PHASE 4a (owner decisions 1 and 2, 2026-11-14): ONE answer to "may this tool touch the disk,
 * and if so does it happen now or as a proposal?".
 *
 * Why this exists: Edit mode staged only three hand-listed tool names (write_file,
 * grep_replace_all, copy_path). Every other file writer - edit_file, apply_patch,
 * multi_replace_string_in_file, insert_edit_into_file, delete_path, move_path - wrote straight
 * through, which is why a README changed in Edit mode with no review card. The list was the
 * policy, and the list was incomplete.
 *
 * The classification now comes from [com.codespace.ide.agent.ToolEffectIndex], whose own rule is
 * that an unknown tool counts as SIDE_EFFECTING. Ask therefore refuses by construction rather
 * than by remembering to filter, and a new write tool is dangerous by default instead of
 * invisible.
 *
 * Pure: the decision is tested without a session, a Context or Compose.
 */
internal object WritePolicy {

    enum class Decision {
        /** Execute the call as asked. */
        ALLOW,
        /** Hold the change as a proposal the user applies or discards (Edit mode). */
        STAGE,
        /** Never execute: the mode forbids touching files. */
        REFUSE,
    }

    /**
     * The tools whose effect is a change to a FILE, and which can therefore be staged as a
     * proposal. Deliberately narrower than SIDE_EFFECTING: a shell command or a git operation is
     * not a diff, so it keeps its approval gate instead of becoming a review card.
     */
    private val fileWriters: Set<String> = setOf(
        "write_file", "create_file", "insert_edit_into_file", "multi_replace_string_in_file",
        "edit_file", "apply_patch", "grep_replace_all", "copy_path", "move_path", "delete_path",
    )

    fun isFileWriter(toolName: String): Boolean = toolName in fileWriters

    fun decide(mode: ChatMode, toolName: String): Decision {
        val effect = com.codespace.ide.agent.ToolEffectIndex.effectOf(toolName)
        return when (mode) {
            ChatMode.ASK ->
                if (effect == ToolEffect.SIDE_EFFECTING) Decision.REFUSE else Decision.ALLOW
            ChatMode.EDIT ->
                if (isFileWriter(toolName)) Decision.STAGE else Decision.ALLOW
            ChatMode.AGENT -> Decision.ALLOW
        }
    }

    /** What the model is told when Ask refuses, so the refusal is not mistaken for a bug. */
    fun refusalMessage(toolName: String): String =
        "REFUSED: Ask mode is read-only, so `$toolName` was not run and nothing on disk changed. " +
            "Describe the change, or switch to Edit to propose it or Agent to make it."

    /** Same wording the staged branch already used, so the transcript reads unchanged. */
    fun stagedNote(relPath: String, chars: Int): String =
        "Proposed edit staged: $relPath ($chars chars). Nothing has been written to disk. " +
            "Review it in the editor strip and Apply or Discard there."
}
