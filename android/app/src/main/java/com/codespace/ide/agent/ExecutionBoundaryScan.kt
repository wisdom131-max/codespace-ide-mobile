package com.codespace.ide.agent

/**
 * PROJECT EIGHT 8.3 — the single execution gate, checked as an INVARIANT rather than trusted.
 *
 * Every tool call in VN Code is supposed to enter through `AgentTools.executeTool`: that is where the
 * plan's first-and-last word, the permission check, the approval gate, the prerequisite ledger, the
 * duplicate-execution guard and the side-effect verification live. A second route - a helper that calls
 * a tool body directly, a copy of the gate in another file, a retry that bypasses the plan - would be
 * invisible in a green test suite, because every existing test enters through the front door.
 *
 * This is deliberately a source-level invariant, not a text search for one string:
 *
 *  1. the body dispatcher is `private` and exists once, so nothing outside its own file can reach it;
 *  2. every call site of that dispatcher lives in the gate's file;
 *  3. no other file declares an `executeTool` of its own (a second front door);
 *  4. every name the registry advertises resolves to a dispatch arm in the body, so a tool added to
 *     `BUILTIN_TOOL_NAMES` without wiring cannot ship as a listed-but-unreachable tool, and a dispatch
 *     arm cannot be added without being advertised.
 *
 * It reads source text only. That is a real limitation: a determined bypass could hide behind
 * reflection or a string built at runtime, and this scan would not see it. What it does catch is the
 * ordinary regression - someone wiring a quick path because the gate was inconvenient that afternoon.
 */
object ExecutionBoundaryScan {

    /** The file that owns the gate. Everything else is "outside". */
    const val GATE_FILE = "AgentTools.kt"

    private const val BODY = "executeToolBody"

    data class Report(
        val violations: List<String>,
        val bodyIsPrivate: Boolean,
        val bodyCallSites: Int,
        val registryNames: Int,
        val dispatchArms: Int,
        val uncovered: List<String>,
        val unadvertised: List<String>,
    ) {
        val clean: Boolean get() = violations.isEmpty() && uncovered.isEmpty() && unadvertised.isEmpty()

        fun summary(): String = buildString {
            append("body private=").append(bodyIsPrivate)
            append(", body call sites=").append(bodyCallSites)
            append(", registry names=").append(registryNames)
            append(", dispatch arms=").append(dispatchArms)
            if (violations.isNotEmpty()) append(", violations=").append(violations.size)
            if (uncovered.isNotEmpty()) append(", unadvertised arms missing names=").append(uncovered.size)
            if (unadvertised.isNotEmpty()) append(", advertised-without-arm=").append(unadvertised.size)
        }
    }

    /**
     * @param sources file name to file text, for every production source the caller chose to include.
     * @param names the names the live registry advertises, so newly added tools are covered.
     */
    fun scan(input: Map<String, String>, names: List<String>): Report {
        // Comments talk about the gate without calling it; only code counts.
        val sources = input.mapValues { (_, text) -> stripComments(text) }
        val violations = ArrayList<String>()
        val gate = sources[GATE_FILE].orEmpty()
        if (gate.isBlank()) violations.add("$GATE_FILE was not supplied, so the gate could not be checked")

        // 1. The dispatcher must be private: a public body is a second front door by definition.
        val bodyDecl = Regex("""\b(private\s+)?(suspend\s+)?fun\s+$BODY\s*\(""").find(gate)
        val bodyIsPrivate = bodyDecl?.groupValues?.get(1)?.isNotBlank() == true
        if (bodyDecl == null) {
            violations.add("$GATE_FILE does not declare $BODY: the gate cannot be located")
        } else if (!bodyIsPrivate) {
            violations.add("$BODY is not private, so any file can call a tool body directly")
        }

        // 2. Every call site of the dispatcher must be inside the gate's own file.
        var callSites = 0
        for ((file, text) in sources) {
            val hits = Regex("""\b$BODY\s*\(""").findAll(text).count()
            if (file == GATE_FILE) {
                // One of those hits is the declaration itself, not a call.
                callSites += (hits - 1).coerceAtLeast(0)
            } else if (hits > 0) {
                violations.add("$file calls $BODY directly, outside the execution gate")
            }
        }

        // 3. No other file may declare an executeTool of its own.
        for ((file, text) in sources) {
            if (file == GATE_FILE) continue
            if (Regex("""\bfun\s+executeTool\s*\(""").containsMatchIn(text)) {
                violations.add("$file declares its own executeTool, which is a second execution gate")
            }
        }

        // 4. Registry coverage, in both directions.
        val body = bodyRegion(gate)
        val arms = dispatchArms(body)
        val prefixes = prefixArms(body)
        val uncovered = names.filter { name -> !arms.contains(name) && prefixes.none { name.startsWith(it) } }
        val unadvertised = arms.filter { arm -> !names.contains(arm) }
        if (uncovered.isNotEmpty()) {
            violations.add("registered but no dispatch arm: " + uncovered.sorted().joinToString(", "))
        }
        if (unadvertised.isNotEmpty()) {
            violations.add("dispatch arm not advertised by the registry: " + unadvertised.sorted().joinToString(", "))
        }

        return Report(
            violations = violations,
            bodyIsPrivate = bodyIsPrivate,
            bodyCallSites = callSites,
            registryNames = names.size,
            dispatchArms = arms.size,
            uncovered = uncovered,
            unadvertised = unadvertised,
        )
    }

    /**
     * Only the dispatcher's own body counts. The gate file also holds nested `when`s (git, npm, pip
     * sub-verbs) that are not tools, and counting those made the guard report 22 phantom tools.
     */
    internal fun bodyRegion(gate: String): String {
        val start = gate.indexOf("fun " + BODY + "(")
        if (start < 0) return ""
        // Checkpoint 0.5 made the gate delegate to a private raw dispatcher, and the dispatch arms
        // moved with it. The region must FOLLOW that delegation: ending at it reported zero arms and
        // failed every advertised tool as "registered but no dispatch arm".
        val raw = gate.indexOf("fun " + BODY + "Raw(")
        val from = if (raw > start) raw else start
        val next = Regex("""\n    (?:private|internal|public) """).find(gate, from + 10)
        return if (next == null) gate.substring(start) else gate.substring(start, next.range.first)
    }

    /** Drops block and line comments so documentation of the gate is not mistaken for a call of it. */
    internal fun stripComments(text: String): String =
        text.replace(Regex("/\\*[\\s\\S]*?\\*/"), " ")
            .replace(Regex("//[^\\n]*"), " ")

    /** `"read_file" -> ...` style arms, camelCase names included. */
    private fun dispatchArms(gate: String): Set<String> =
        Regex("""^\s*"([A-Za-z0-9_]+)"\s*->""", RegexOption.MULTILINE)
            .findAll(gate)
            .map { it.groupValues[1] }
            .toSet()

    /** `name.startsWith("mcp_")` style dynamic families. */
    private fun prefixArms(gate: String): List<String> =
        Regex("""startsWith\("([a-z0-9_]+)"\)""")
            .findAll(gate)
            .map { it.groupValues[1] }
            .toList()
}
