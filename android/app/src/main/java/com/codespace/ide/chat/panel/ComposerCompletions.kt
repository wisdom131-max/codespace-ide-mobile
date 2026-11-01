package com.codespace.ide.chat.panel

/**
 * `/` commands and `@` participants in the composer (owner request: "then @participants and
 * /slash like vs code").
 *
 * The rule that keeps this honest: a menu entry exists only if the app can actually carry it
 * out. VS Code's lists are filled by extensions, and its `/` entries are contributions; here
 * there is one binary, so the list is short and every item lands somewhere real. A command that
 * merely typed text into the field and did nothing would be indistinguishable from a broken one.
 *
 *  - `/clear` clears this conversation (the same action as Settings > Clear conversation).
 *  - `/attach` and `@file` open the attachment picker - the picker that already stages files.
 *  - `@workspace` is listed but NOT offered as insertable: WorkspaceContextSource already
 *    sends the project context with every request, so a token for it would be noise that
 *    looks like control.
 *
 * Matching is on the token being typed at the END of the field, which is the only place a
 * completion can be inserted without moving the caret.
 */
internal data class ComposerCompletion(
    /** What the row shows and what gets inserted for a participant. */
    val token: String,
    /** One line of plain language: what pressing it does. */
    val hint: String,
    /** Command id when the entry runs an action instead of inserting a token. */
    val command: String? = null,
    /** Tokens that cannot be inserted because the feature is already automatic. */
    val insertable: Boolean = true,
)

internal object ComposerCompletions {

    internal val commands = listOf(
        ComposerCompletion("/clear", "Clear this conversation", command = "clear"),
        ComposerCompletion("/attach", "Attach files from the project", command = "attach"),
    )

    internal val participants = listOf(
        ComposerCompletion("@file", "Attach a file from the project", command = "attach"),
        ComposerCompletion(
            "@workspace",
            "Project context is always included - no token needed",
            insertable = false,
        ),
    )

    /** The token currently being typed at the end of the field, or null when there is none. */
    fun activeToken(text: String): String? {
        val tail = text.substringAfterLast('\n')
        val token = tail.substringAfterLast(' ')
        if (token.isEmpty()) return null
        if (!token.startsWith("/") && !token.startsWith("@")) return null
        // A slash inside a word ("and/or") is prose, not a command.
        val before = tail.dropLast(token.length).lastOrNull()
        if (before != null && !before.isWhitespace()) return null
        return token
    }

    /** Rows for the menu, most specific first; empty when the field is not showing a menu. */
    fun menuFor(text: String): List<ComposerCompletion> {
        val token = activeToken(text) ?: return emptyList()
        val pool = if (token.startsWith("/")) commands else participants
        return pool.filter { it.token.startsWith(token, ignoreCase = true) }
    }

    /** Replace the token being typed with [insert] (a completion), keeping everything before it. */
    fun applyTo(text: String, insert: String): String {
        val token = activeToken(text) ?: return text
        val head = text.dropLast(token.length)
        return if (insert.endsWith(" ")) head + insert else head + insert + " "
    }
}
