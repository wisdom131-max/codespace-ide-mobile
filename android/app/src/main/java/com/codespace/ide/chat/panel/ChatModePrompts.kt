package com.codespace.ide.chat.panel

/**
 * U4-d: what each conversation mode actually tells the model.
 *
 * This is the honest half of Agent/Edit. The mode now changes the REQUEST (the provider's
 * own system slot carries this text), which is real and observable. What it does NOT do
 * yet is give the model tools: Edit cannot write a file and Agent cannot run a command
 * until the tool wire ships, so both instructions are written to be useful without tools
 * and to state the limit rather than promise an action the app cannot perform.
 *
 * Pure data, no Compose and no engine imports, so the wording is unit-tested.
 */
internal object ChatModePrompts {

    /** System instruction for [mode]. Ask is the default and stays the least directive. */
    fun instruction(mode: ChatMode): String = when (mode) {
        ChatMode.ASK ->
            "Answer questions about the open project. Be concise and concrete. " +
                "Explain what you would change instead of changing it."

        ChatMode.EDIT ->
            "You are editing this project. Make the smallest correct change that satisfies " +
                "the request. Show edits as a diff-style block naming the file and the exact " +
                "lines. Do not claim a file was modified: nothing is applied until the user " +
                "accepts it."

        ChatMode.AGENT ->
            "You are working in this project. Plan briefly, then carry the request through " +
                "step by step. When the app can perform a step, say what you need; when it " +
                "cannot yet, state plainly what remains for the user to do."
    }

    /** The v1 label used by the composer chip, so the UI and the prompt cannot disagree. */
    fun chipLabel(mode: ChatMode): String = mode.label
}
