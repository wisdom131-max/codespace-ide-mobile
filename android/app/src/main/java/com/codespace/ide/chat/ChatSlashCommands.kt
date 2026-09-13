package com.codespace.ide.chat

/**
 * CHAT SLASH COMMANDS (Round 1, Copilot-chat parity):
 *
 * Pure-Kotlin command framework for the chat input box — no Compose, no UI.
 * The panel calls [parse] on every send; if it returns non-null, the panel
 * executes the mapped UI action instead of sending the line to the model.
 *
 * This object deliberately owns NO state: sessions, model picker, dialogs all
 * stay in the panel so the framework stays side-effect-free.
 */
object ChatSlashCommands {

    /** One command in the registry. [takesArg] = accepts text after the name. */
    data class Command(
        val name: String,
        val description: String,
        val takesArg: Boolean = false,
    )

    /** Parsed input: "/rename My chat" -> Parsed("rename", "My chat"). */
    data class Parsed(val name: String, val arg: String)

    val COMMANDS: List<Command> = listOf(
        Command("clear", "Clear the messages of the current chat"),
        Command("new", "Start a new chat session"),
        Command("rename", "Rename the current session (usage: /rename <title>, or /rename alone to edit)", takesArg = true),
        Command("models", "Open the model picker"),
        Command("skills", "Run a skill — prefills the input and attaches its context (review before sending)"),
        Command("tools", "List the tools available in Agent mode"),
        Command("help", "Show available slash commands"),
    )

    /**
     * Parse an input line. Returns null when the line is not a slash command
     * (i.e. it should be sent to the model as a normal message). Unknown
     * commands still return a Parsed — the panel reports them as unknown.
     */
    fun parse(input: String): Parsed? {
        val t = input.trim()
        if (!t.startsWith("/") || t.length < 2) return null
        val body = t.substring(1)
        if (body.isBlank()) return null
        val name = body.substringBefore(' ').lowercase()
        if (name.isEmpty()) return null
        val arg = if (body.contains(' ')) body.substringAfter(' ').trim() else ""
        return Parsed(name, arg)
    }

    /** Text shown for /help — markdown the chat renderer understands. */
    fun helpText(): String {
        val sb = StringBuilder("**Slash commands**\n")
        COMMANDS.forEach { c ->
            sb.append("- `/").append(c.name).append("` — ").append(c.description).append('\n')
        }
        sb.append("\nAnything not starting with `/` is sent to the model normally.")
        return sb.toString()
    }

    /** Compact one-line-per-tool summary shown for /tools. */
    fun toolsText(): String {
        val tools = com.codespace.ide.agent.AgentTools.toolNames()
        if (tools.isEmpty()) {
            return "**Agent tools:** none registered."
        }
        val sb = StringBuilder("**Agent mode tools** (`").append(tools.size).append("` registered)\n")
        tools.forEach { sb.append("- `").append(it).append("`\n") }
        sb.append("\nExternal MCP tools appear automatically once an MCP server is connected and enabled.")
        return sb.toString()
    }
}
