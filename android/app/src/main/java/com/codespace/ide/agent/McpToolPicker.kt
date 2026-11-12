package com.codespace.ide.agent

/**
 * PROJECT 4.5 - CHOOSING AMONG MCP TOOLS WITHOUT TRUSTING THEM.
 *
 * Connected servers can expose dozens of tools, most irrelevant to the current task. This ranks them
 * against the request so the irrelevant definitions stay out of the prompt - and it stamps every
 * pick with `requiresApproval = true`, which is not a default but an invariant: MCP calls stay behind
 * explicit owner approval even under AUTO_ALL, and no ranking can change that.
 *
 * Server ownership is carried through unchanged, because a tool name is only meaningful with the
 * server that exposed it.
 */
object McpToolPicker {

    data class Candidate(
        val server: String,
        val tool: String,
        val description: String = "",
        val connected: Boolean = true,
        /** Definitions captured before a reconnect may no longer exist on the server. */
        val stale: Boolean = false,
    )

    data class Pick(
        val candidate: Candidate,
        val score: Int,
        /** Always true. Present so a caller cannot accidentally forget the gate. */
        val requiresApproval: Boolean = true,
    )

    private val STOP = setOf(
        "the", "a", "an", "and", "or", "of", "for", "to", "in", "on", "is", "are", "with", "from",
        "that", "this", "it", "be", "as", "by", "my", "me", "please", "can", "you",
    )

    private fun tokens(text: String): Set<String> =
        text.lowercase().split(Regex("[^a-z0-9_]+"))
            .filter { it.length > 2 && it !in STOP }
            .toSet()

    /** Text overlap with the request, weighted toward the tool's own name. */
    fun score(request: String, c: Candidate): Int {
        val asked = tokens(request)
        if (asked.isEmpty()) return 0
        val name = tokens(c.tool)
        val desc = tokens(c.description)
        val onName = asked.intersect(name).size * 3
        val onDesc = asked.intersect(desc).size
        val verb = if (asked.any { a -> name.any { it.contains(a) || a.contains(it) } }) 2 else 0
        return onName + onDesc + verb
    }

    /**
     * Ranked, best first. Unusable entries are dropped with a reason rather than offered and then
     * failing: a disconnected server cannot answer, and a stale definition may not exist any more.
     */
    fun rank(request: String, candidates: List<Candidate>, limit: Int = 8): List<Pick> =
        candidates.asSequence()
            .filter { it.connected && !it.stale }
            .map { Pick(it, score(request, it)) }
            .filter { it.score > 0 }
            .sortedWith(compareByDescending<Pick> { it.score }.thenBy { it.candidate.server }.thenBy { it.candidate.tool })
            .take(limit)
            .toList()

    fun choose(request: String, candidates: List<Candidate>): Pick? = rank(request, candidates, 1).firstOrNull()

    /** Why a server's tools were left out, for an honest note instead of silence. */
    fun exclusions(candidates: List<Candidate>): List<String> =
        candidates.filter { !it.connected || it.stale }.take(6).map { c ->
            c.server + "/" + c.tool + " skipped: " + (if (!c.connected) "server not connected" else "definition may be stale")
        }
}
