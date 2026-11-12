package com.codespace.ide.chat.panel

import com.codespace.ide.agent.AgentTools

/**
 * 2026-11-05 (device finding: tool discovery): the chat can say what it can actually do.
 *
 * Before this there was no way to ask from inside the app. The engine has always known - the `/tools`
 * HTTP endpoint serves `AgentTools.toolNames()`, which is the EXECUTION registry - but a user typing
 * in the composer had no equivalent.
 *
 * The list comes from that same registry on purpose. The tool count drifted once already because a
 * hand-maintained list existed beside the real one (fixed in fbb013e: `/tools` now serves
 * toolNames() instead of a 30-name copy). This adds a READER, never a second list.
 *
 * MCP names are appended when they are cached: they are additional tools the agent can call, and
 * omitting them would make the answer incomplete rather than simple.
 */
object ToolDiscovery {

    /** Tool names the chat can currently call, builtin registry first, cached MCP after it. */
    fun names(mcpCached: List<String> = emptyList()): List<String> {
        val builtin = AgentTools.toolNames()
        return (builtin + mcpCached.filter { it !in builtin }).distinct()
    }

    /**
     * Plain-language answer for the transcript.
     *
     * [modeLabel] is passed in rather than computed here: the mode is a UI fact, and this stays
     * pure so the wording can be asserted without a session.
     */
    fun describe(mcpCached: List<String> = emptyList(), modeLabel: String? = null): String {
        val builtin = AgentTools.toolNames()
        val extra = mcpCached.filter { it !in builtin }
        val head = if (modeLabel.isNullOrBlank()) {
            "This chat can call " + builtin.size + " built-in tools"
        } else {
            "In " + modeLabel + " this chat can call " + builtin.size + " built-in tools"
        }
        val extraLine = if (extra.isEmpty()) {
            "No MCP tools are cached right now."
        } else {
            "Plus " + extra.size + " MCP tool(s): " + extra.joinToString(", ") + "."
        }
        return head + ": " + builtin.joinToString(", ") + ". " + extraLine +
            " Connectors are called through use_connector / request_connector."
    }
}

/**
 * THE CATALOGUE (2026-11-12, "/tools" picker).
 *
 * [describe] answers "which names can this chat call". That is not enough to CHOOSE a tool: the
 * answer was one flat comma-separated list, with no group, no ownership and no reason a tool might
 * be missing. This block turns the same registry into a catalogue instead of inventing a second one:
 *
 *  - group comes from [ToolMetaIndex] (`ToolMeta.category`), so the catalogue and the prompt's lean
 *    offer use ONE definition of what a tool is;
 *  - origin is the built-in registry or the MCP server that owns the tool, so `mcp_` entries are
 *    never presented as if they were ours;
 *  - a tool that is absent is not "unknown": it is either not cached yet (on demand) or belongs to
 *    another mode, and the answer says which.
 *
 * Pure: every entry takes the data it needs, so group/search/limit rules are asserted by tests.
 */
internal object ToolCatalogue {

    /** One catalogue row. [group] is the [ToolCategory] name, lowercased for display. */
    data class Entry(
        val name: String,
        val title: String,
        val group: String,
        val origin: String,
        val description: String,
        val keywords: List<String>,
        val destructive: Boolean,
        val modes: List<String>,
        val onDemand: Boolean = false,
    )

    /**
     * Builds the rows for [mode]. [mcpCached] is the cached MCP name list; [allModes] answers "does
     * this tool exist at all" so an absent tool can be explained instead of dropped silently.
     */
    fun entries(mode: ChatMode, mcpCached: List<String> = emptyList()): List<Entry> {
        val exposed = ToolRegistry.exposed(mode)
        val rows = ArrayList<Entry>(exposed.size + mcpCached.size)
        for (spec in exposed) {
            val meta = ToolMetaIndex.metaFor(spec)
            rows.add(
                Entry(
                    name = spec.name,
                    title = spec.displayName,
                    group = meta.category.name.lowercase(),
                    origin = "builtin",
                    description = spec.description,
                    keywords = meta.keywords,
                    destructive = meta.destructive,
                    modes = spec.modes.map { it.label }.sorted(),
                )
            )
        }
        for (name in mcpCached) {
            if (rows.any { it.name == name }) continue
            rows.add(
                Entry(
                    name = name,
                    title = name,
                    group = "mcp",
                    origin = "mcp:" + serverOf(name, mcpCached),
                    description = "Tool provided by a configured MCP server. Its schema arrives with " +
                        "the server, so it is loaded on demand.",
                    keywords = listOf("mcp", "connector", "external"),
                    destructive = true,   // an external tool is never assumed harmless
                    modes = ChatMode.entries.map { it.label }.sorted(),
                    onDemand = true,
                )
            )
        }
        return rows.sortedWith(compareBy({ it.group }, { it.name }))
    }

    /** `mcp_<server>_<tool>` → server, by the same longest-prefix rule the executor uses. */
    fun serverOf(toolName: String, known: List<String> = emptyList()): String {
        val body = toolName.removePrefix("mcp_")
        // Prefer a server that is itself in the cached set: it is the strongest evidence available.
        val match = known.asSequence()
            .map { it.removePrefix("mcp_") }
            .filter { body.startsWith(it + "_") }
            .maxByOrNull { it.length }
        return match ?: body.substringBefore('_')
    }

    /** Groups present in [rows], with counts, biggest first then alphabetical. */
    fun groups(rows: List<Entry>): List<Pair<String, Int>> =
        rows.groupingBy { it.group }.eachCount().toList()
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })

    /**
     * Search across name, title, description and keywords. A name match outranks a keyword match, so
     * "zip" finds zip_create before it finds a tool that merely mentions archives.
     */
    fun search(rows: List<Entry>, query: String, limit: Int = 20): List<Entry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return rows
        val terms = q.split(Regex("[^a-z0-9_]+")).filter { it.length > 1 }.ifEmpty { listOf(q) }
        data class Hit(val row: Entry, val score: Int)
        val hits = ArrayList<Hit>()
        for (r in rows) {
            var score = 0
            for (t in terms) {
                if (r.name == t) score += 100
                else if (r.name.contains(t)) score += 60
                if (r.title.lowercase().contains(t)) score += 25
                if (r.keywords.any { it.contains(t) }) score += 15
                if (r.description.lowercase().contains(t)) score += 8
            }
            if (score > 0) hits.add(Hit(r, score))
        }
        return hits.sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.row.name })
            .take(limit.coerceIn(1, 100)).map { it.row }
    }

    /**
     * The transcript answer for `/tools` (optionally filtered by [query]).
     *
     * Bounded on purpose: groups with counts first, then at most [limit] rows, then a line saying how
     * many were not shown and how to narrow. A catalogue that dumps 110 rows into the chat is the
     * problem this replaces.
     */
    fun describe(
        mode: ChatMode,
        mcpCached: List<String> = emptyList(),
        query: String = "",
        group: String? = null,
        limit: Int = 24,
    ): String {
        val all = entries(mode, mcpCached)
        val inGroup = if (group.isNullOrBlank()) all else all.filter { it.group == group.lowercase() }
        val rows = search(inGroup, query)
        val head = "Tool catalogue for " + mode.label + ": " + all.size + " tools in " +
            groups(all).size + " group(s)"
        val groupLine = groups(all).joinToString(", ") { it.first + " " + it.second }
        if (rows.isEmpty()) {
            return head + ".\nGroups: " + groupLine + "\nNothing matches \"" + query +
                "\". Try a shorter word, or a group name: " + groups(all).joinToString(", ") { it.first }
        }
        val sb = StringBuilder(head)
        sb.append("\nGroups: ").append(groupLine)
        if (!group.isNullOrBlank()) sb.append("\nFiltered to group: ").append(group.lowercase())
        if (query.isNotBlank()) sb.append("\nMatching \"").append(query.take(60)).append("\": ").append(rows.size)
        sb.append('\n')
        var lastGroup = ""
        for (r in rows.take(limit)) {
            if (r.group != lastGroup) {
                sb.append('\n').append(r.group.uppercase()).append('\n')
                lastGroup = r.group
            }
            sb.append("  ").append(r.name)
            if (r.origin != "builtin") sb.append(" [").append(r.origin).append(']')
            if (r.destructive) sb.append(" *writes*")
            sb.append(" - ").append(r.title).append('\n')
        }
        if (rows.size > limit) {
            sb.append("\n... ").append(rows.size - limit)
                .append(" more. Narrow with a group or a search word.")
        }
        sb.append("\nGroups: ").append(groupLine)
        sb.append("\n*MCP rows show their server.* Only the tools in the prompt are described; the " +
            "rest load on demand through tool_search.")
        return sb.toString()
    }

    /**
     * The advertised schema for [name], on request only. The catalogue deliberately does NOT carry
     * schemas in its rows: 110 definitions in one render is exactly the prompt bloat this replaces.
     */
    fun schema(name: String): String? = ToolRegistry.spec(name)?.schema
}
