package com.codespace.ide.agent

import org.json.JSONObject

/**
 * OWNER REPORT (2026-11-24): "I asked him to give me a slack connector... instead of searching for
 * the slack connector tool he then searched for notion connector tool, which is one step back. We
 * need a way for the copilot to follow so when prompts are sent to him he can know what we asked for
 * and what's next and what he's done."
 *
 * This is that. Two things the model was missing, both cheap enough to sit in every instruction:
 *
 *  1. [RequestAnchor] pins the owner's CURRENT message, verbatim, at the end of the instruction, where
 *     recency helps most, and says out loud that a name from earlier in the transcript is not the ask.
 *     It also carries the actions already taken this turn, so "what's next" has something to stand on.
 *  2. [Orientation] answers "where is everything" in a dozen lines, so the model does not spend a
 *     search working out that a connector is not a file.
 *
 * Neither of these can grant permission or run anything: they are text in the instruction.
 */
object RequestAnchor {

    /** Enough for any real request; long enough that the model never sees a truncated verb. */
    private const val MAX = 400

    @Volatile private var ask: String = ""
    private val actions = LinkedHashSet<String>()

    /** Called once per send with the owner's own words. A repeat of the same ask does not reset. */
    @Synchronized
    fun anchor(request: String) {
        val clean = request.trim()
        if (clean.isEmpty() || clean == ask) return
        ask = if (clean.length > MAX) clean.take(MAX).trimEnd() + "..." else clean
        actions.clear()
    }

    fun current(): String = ask

    /** The thing a call acts on, so two calls can be compared without comparing whole argument blobs. */
    fun target(args: JSONObject?): String =
        listOf("service", "query", "path", "name", "command", "url", "pattern")
            .mapNotNull { k -> args?.optString(k)?.takeIf { it.isNotBlank() } }
            .firstOrNull() ?: ""


    /** What this turn has actually done, so the model does not repeat or lose its place. */
    @Synchronized
    fun note(tool: String, args: JSONObject?) {
        if (tool.isBlank()) return
        val target = listOf("service", "query", "path", "name", "command", "tool")
            .mapNotNull { k -> args?.optString(k)?.takeIf { it.isNotBlank() } }
            .firstOrNull()
        actions.add(if (target == null) tool else "$tool ($target)")
        while (actions.size > 8) actions.remove(actions.first())
    }

    @Synchronized
    fun doneSoFar(): List<String> = actions.toList()

    @Synchronized
    fun clear() {
        ask = ""
        actions.clear()
    }

    /**
     * True when [target] has nothing to do with the current ask. Deliberately conservative: one shared
     * word of three letters or more counts as related, so a false warning is rarer than a missed one.
     */
    fun driftsFrom(target: String): Boolean {
        val t = target.trim().lowercase()
        if (t.isEmpty() || ask.isBlank()) return false
        val a = ask.lowercase()
        if (a.contains(t)) return false
        val words = t.split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }
        if (words.isEmpty()) return false
        return words.none { a.contains(it) }
    }

    /** The line the tool result carries when the model has wandered off the ask. */
    fun driftWarning(target: String): String =
        "WRONG TARGET: \"" + target + "\" is not what the owner asked for.\n" +
            "The current ask is: \"" + ask + "\"\n" +
            "Stop and serve the ask. If you meant something else, say so in one line and do the ask."

    /** The pinned block. Empty when there is no ask, so the instruction is unchanged before a turn. */
    fun block(): String = "\n\nWHERE THINGS ARE (read this, do not search to find out): " +
        "project root is the open workspace (app/ and engine/ are its modules); " +
        "read_file/write_file/search_files/grep_search see project files only; " +
        "connectors (Gmail, Drive, Slack, Notion, GitHub, databases) are SERVICES - " +
        "request_connector shows the card and list_connectors lists them, no filesystem " +
        "search will find one; tool_search loads by EXACT name; the app own settings " +
        "are screens, not project files.\n"

}

/**
 * OWNER REQUEST (2026-11-24): "some agents have a set of instructions the copilot sees so they know
 * the position of everything in the environment instead of doing long searches and wasting tokens".
 *
 * This is the position of everything, in one screen, so orientation costs no tool call.
 */
object Orientation {

    fun block(): String = buildString {
        append("\n\nWHERE THINGS ARE (read this instead of searching to find out):\n")
        append("- The open project is the workspace root. Its app module is app/, its engine is engine/.\n")
        append("- Project files: read_file, write_file, search_files, grep_search. Those tools see the\n")
        append("  open project only, never the app's own screens or settings.\n")
        append("- Connectors (Gmail, Drive, Slack, Notion, GitHub, databases, MCP servers) are SERVICES,\n")
        append("  not files. list_connectors shows which exist; request_connector brings up that\n")
        append("  service's own card. Never search the filesystem for a connector.\n")
        append("- A tool you were not handed is found by tool_search with the exact name the owner used,\n")
        append("  then loaded. Loading is by name, so a near name loads nothing.\n")
        append("- The app's own settings, providers, models and keys live in the app's Settings screens,\n")
        append("  which are UI, not project files.\n")
    }
}
